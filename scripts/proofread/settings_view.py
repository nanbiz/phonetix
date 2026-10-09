#!/usr/bin/env python3
"""The toolbar popup, driven the way a reader drives it.

A setting is only a setting if changing it changes what the reader sees. This opens the popup
over a page, the way the toolbar button does, works its controls and looks at the page each
time: the words replaced by how they are said and how many of them, the card a word pointed at
opens and the language it answers in, the translator, the accent, and the two switches.

  uv run python scripts/proofread/settings_view.py
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP, OFFLINE
from on_a_page import PORT, build_packs, evaluate, serve, wait_for


def words(cdp, page):
    """What the page carries now: the words replaced, and what replaced them."""
    return json.loads(evaluate(cdp, page, """
        (() => {
          const replaced = [...document.querySelectorAll('.px-rep')];
          return JSON.stringify({
            count: replaced.length,
            sounds: replaced.map(r => (r.querySelector('.px-ph') || {}).textContent || '')
              .filter(Boolean),
            meanings: replaced.map(r => (r.querySelector('.px-gl') || {}).textContent || '')
              .filter(Boolean),
          });
        })()
    """) or "{}")


def shadow_text(host_id):
    """What one of our windows over the page says, without its stylesheet."""
    return """
        (() => {
          const host = document.getElementById(%r);
          if (!host || !host.shadowRoot) return null;
          return [...host.shadowRoot.children].filter(c => c.tagName !== 'STYLE')
            .map(c => c.innerText).join(' ').replace(/\\s+/g, ' ').trim();
        })()
    """ % host_id


def control(cdp, view, expression, settle=2.5):
    """Work one control in the popup and let the page hear about it."""
    got = evaluate(cdp, view, expression)
    time.sleep(settle)
    return got


def popup(cdp, extid, page_target):
    """The popup, opened over the page by the toolbar button's own call."""
    cdp.send("Target.activateTarget", {"targetId": page_target})
    time.sleep(0.5)
    worker = next(
        t for t in cdp.send("Target.getTargets")["targetInfos"]
        if t["type"] == "service_worker" and t["url"].startswith(f"chrome-extension://{extid}/"))
    session = cdp.send(
        "Target.attachToTarget", {"targetId": worker["targetId"], "flatten": True},
    )["sessionId"]
    opened = evaluate(cdp, session, "chrome.action.openPopup().then(() => 'opened', e => String(e))")
    if opened != "opened":
        raise SystemExit(f"FAIL - the popup would not open: {opened}")
    for _ in range(20):
        found = next((t for t in cdp.send("Target.getTargets")["targetInfos"]
                      if t["url"].startswith(f"chrome-extension://{extid}/popup.html")), None)
        if found:
            view = cdp.send(
                "Target.attachToTarget", {"targetId": found["targetId"], "flatten": True},
            )["sessionId"]
            cdp.send("Runtime.enable", session=view)
            return view
        time.sleep(0.5)
    raise SystemExit("FAIL - the toolbar button opened no popup")


def point_at(cdp, page, word):
    """Rest the pointer on a word of the page, as a reader does, and read the card it opens."""
    where = json.loads(evaluate(cdp, page, """
        (() => {
          const prose = document.getElementById('prose');
          // A word the page replaced is pointed at where its replacement is drawn.
          const box = [...prose.querySelectorAll('.px-w')]
            .find(w => (w.querySelector('.px-was') || {}).textContent === %r);
          if (box) {
            const at = box.getBoundingClientRect();
            return JSON.stringify({x: at.left + at.width / 2, y: at.top + at.height / 2});
          }
          const walker = document.createTreeWalker(prose, NodeFilter.SHOW_TEXT);
          for (let node = walker.nextNode(); node; node = walker.nextNode()) {
            const at = node.nodeValue.indexOf(%r);
            if (at < 0 || node.parentElement.closest('.px-rep')) continue;
            const range = document.createRange();
            range.setStart(node, at);
            range.setEnd(node, at + %d);
            const box = range.getBoundingClientRect();
            return JSON.stringify({x: box.left + box.width / 2, y: box.top + box.height / 2});
          }
          return 'null';
        })()
    """ % (word, word, len(word))) or "null")
    if not where:
        return None
    cdp.send("Input.dispatchMouseEvent", {"type": "mouseMoved", "x": 2, "y": 2}, session=page)
    time.sleep(0.6)
    for dx in (0, 1):
        cdp.send("Input.dispatchMouseEvent",
                 {"type": "mouseMoved", "x": where["x"] + dx, "y": where["y"]}, session=page)
    return wait_for(cdp, page, shadow_text("phonetix-card-host"), lambda v: bool(v), tries=10)


def main():
    build_packs()
    serve()
    base = f"http://127.0.0.1:{PORT}"
    failures = []

    cdp = PipeCDP(extra_args=[OFFLINE])
    cdp.send("Target.setDiscoverTargets", {"discover": True})
    extid = cdp.ensure_extension()
    try:
        # A reader with nothing chosen yet, except where the dictionaries come from, and both
        # of the host's dictionaries already here.
        book = cdp.send("Target.createTarget", {"url": f"chrome-extension://{extid}/viewbook.html"})
        book_session = cdp.send(
            "Target.attachToTarget", {"targetId": book["targetId"], "flatten": True},
        )["sessionId"]
        cdp.send("Runtime.enable", session=book_session)
        time.sleep(2)
        evaluate(cdp, book_session, (
            f"chrome.storage.local.set({{packBaseUrl:'{base}'}})"
            ".then(() => chrome.storage.local.remove(['on','layer','density','targetLanguage',"
            "'sourceLanguage','learning','knownLanguages','sitesOff','accents']))"
        ))
        for lang in ("es", "de"):
            evaluate(cdp, book_session, (
                "chrome.runtime.sendMessage({phonetix:'getPack',data:{lang:'" + lang + "'}})"
                ".then(r => JSON.stringify(r))"
            ))
        cdp.send("Target.closeTarget", {"targetId": book["targetId"]})

        page_target = cdp.send("Target.createTarget", {"url": f"{base}/page.html"})["targetId"]
        page = cdp.send(
            "Target.attachToTarget", {"targetId": page_target, "flatten": True},
        )["sessionId"]
        cdp.send("Runtime.enable", session=page)
        cdp.send("Emulation.setDeviceMetricsOverride", {
            "width": 1280, "height": 900, "deviceScaleFactor": 1, "mobile": False,
        }, session=page)

        # With nothing chosen, the page is read as it comes: some of its words replaced by how
        # they are said, and none by what they mean.
        wait_for(cdp, page, "document.querySelectorAll('.px-rep').length", lambda v: v)
        fresh = words(cdp, page)
        print(f"  a fresh reader's page: {fresh['count']} words replaced, {fresh['sounds'][:4]}")
        if not fresh["sounds"]:
            failures.append("a fresh reader's page has no word replaced by how it is said")
        if fresh["meanings"]:
            failures.append(f"words on the page were replaced by meanings: {fresh['meanings'][:4]}")

        view = popup(cdp, extid, page_target)
        drawn = wait_for(cdp, view, """
            (() => {
              if (!document.querySelector('[data-row=on]')) return null;
              return JSON.stringify({
                rows: [...document.querySelectorAll('[data-row]')].map(r => r.dataset.row),
                on: document.querySelector('[data-row=on] input').checked,
                site: (document.querySelector('[data-row=site] [data-about]') || {}).textContent,
                mine: (document.querySelector('[data-row=target] [data-about]') || {}).textContent,
              });
            })()
        """, lambda v: v is not None)
        if not drawn:
            print("FAIL - the popup drew nothing")
            sys.exit(1)
        panel = json.loads(drawn)
        print(f"  rows: {panel['rows']}")
        for row in ("on", "site", "inline", "density", "target", "known", "translator",
                    "pronunciation", "theme"):
            if row not in panel["rows"]:
                failures.append(f"the popup has no {row} row")
        if not panel["on"]:
            failures.append("a fresh reader is switched off")
        # Their own language is the browser's until they say otherwise, so the card has
        # something to translate into from the first word.
        if not (panel["mine"] or "").strip():
            failures.append("a fresh reader has no language of their own")
        expected = "following the main switch (currently: on)"
        print(f"  the site row: {panel['site']!r}")
        if (panel["site"] or "").strip() != expected:
            failures.append(f"the site row says {panel['site']!r}, not {expected!r}")

        def bar(at):
            return control(cdp, view, """
                (() => {
                  const s = document.querySelector('[data-row=density] input');
                  s.value = %s;
                  s.dispatchEvent(new Event('input', {bubbles: true}));
                  return document.querySelector('[data-row=density] [data-about]').textContent;
                })()
            """ % at)

        # How many words: every word at one end of the bar, few at the other, said in words.
        # Once the bar has its positions, which come from the host.
        wait_for(cdp, view, "Number(document.querySelector('[data-row=density] input').max)",
                 lambda v: bool(v))
        bar("s.max")
        told = evaluate(cdp, view, "document.querySelector('[data-row=density] [data-about]').textContent")
        dense = words(cdp, page)
        bar("0")
        sparse = words(cdp, page)
        print(f"  every word: {dense['count']} replaced ({told!r}); fewest: {sparse['count']}")
        if "every word" not in (told or "").lower():
            failures.append(f"the dense end says {told!r}")
        if dense["count"] <= sparse["count"]:
            failures.append(f"the bar changed nothing: {dense['count']} then {sparse['count']}")
        bar("s.max")

        # Replacing switched off: nothing on the page is replaced, and a word pointed at still
        # opens its card.
        control(cdp, view, "document.querySelector('[data-row=inline] [data-choice=off]').click()")
        bare = words(cdp, page)
        card = point_at(cdp, page, "perro")
        print(f"  replacing off: {bare['count']} replaced; pointing at perro: {(card or '')[:60]!r}")
        if bare["count"]:
            failures.append(f"{bare['count']} words stayed replaced with replacing off")
        if not card or "perro" not in card:
            failures.append(f"pointing at a word opened no card for it: {card!r}")
        control(cdp, view, "document.querySelector('[data-row=inline] [data-choice=sound]').click()")

        # Translation in place of pronunciation: the words replaced say what they mean, and
        # none says how it sounds, since one thing takes a word's place.
        control(cdp, view, "document.querySelector('[data-row=inline] [data-choice=meaning]').click()")
        kinds = wait_for(cdp, page, """
            (() => {
              const meant = document.querySelectorAll('.px-rep .px-gl').length;
              const said = document.querySelectorAll('.px-rep .px-ph').length;
              return meant ? [meant, said] : null;
            })()
        """, lambda v: bool(v), tries=12) or [0, 0]
        print(f"  replacing with translations: {kinds[0]} translated, {kinds[1]} transcribed")
        if not kinds[0] or kinds[1]:
            failures.append(f"translation in place of words drew {kinds[0]} translations and {kinds[1]} transcriptions")
        control(cdp, view, "document.querySelector('[data-row=inline] [data-choice=sound]').click()")

        # My language: the card says what a word means in it.
        control(cdp, view, "document.querySelector('[data-row=target]').click()", settle=1)
        control(cdp, view, "document.querySelector('[data-sheet] [data-choice=de]').click()")
        card = point_at(cdp, page, "camino")
        print(f"  my language German, pointing at camino: {(card or '')[:80]!r}")
        if not card or "Weg" not in card:
            failures.append(f"the card did not answer in German: {card!r}")

        # A language the reader reads as it is: its words pointed at open nothing, and a word
        # the page replaced opens a card that says it and does not translate it.
        control(cdp, view, "document.querySelector('[data-row=known]').click()", settle=1)
        control(cdp, view, "document.querySelector('[data-sheet] [data-choice=es]').click()")
        control(cdp, view, "document.querySelector('[data-sheet] .icon-button').click()", settle=1)
        known = evaluate(cdp, view, "document.querySelector('[data-row=known] [data-about]').textContent")
        # Every word is replaced at this end of the bar, so the plain word is pointed at with
        # replacing off.
        control(cdp, view, "document.querySelector('[data-row=inline] [data-choice=off]').click()")
        plain = point_at(cdp, page, "por")
        control(cdp, view, "document.querySelector('[data-row=inline] [data-choice=sound]').click()")
        print(f"  never translate {known!r}: pointing at por: {(plain or '')[:60]!r}")
        if (known or "").strip() != "Spanish":
            failures.append(f"the never-translate row says {known!r} after choosing Spanish")
        if plain:
            failures.append(f"a word in a language never translated opened a card: {plain!r}")
        replaced = point_at(cdp, page, "camino")
        print(f"  ... pointing at the replaced camino: {(replaced or '')[:60]!r}")
        if not replaced or "camino" not in replaced:
            failures.append(f"a replaced word in a language never translated opened no card: {replaced!r}")
        elif "Weg" in replaced:
            failures.append(f"a word in a language never translated was translated: {replaced!r}")
        control(cdp, view, "document.querySelector('[data-row=known]').click()", settle=1)
        control(cdp, view, "document.querySelector('[data-sheet] [data-choice=es]').click()")
        control(cdp, view, "document.querySelector('[data-sheet] .icon-button').click()", settle=1)

        # An accent whose difference is a rule changes how the page says a word.
        def sound_of(word):
            return evaluate(cdp, page, """
                (() => {
                  const box = [...document.querySelectorAll('.px-w')]
                    .find(w => (w.querySelector('.px-was') || {}).textContent === %r);
                  return box ? ((box.querySelector('.px-ph') || {}).textContent || '') : '';
                })()
            """ % word) or ""

        before = wait_for(cdp, page, """
            (() => {
              const box = [...document.querySelectorAll('.px-w')]
                .find(w => (w.querySelector('.px-was') || {}).textContent === 'calle');
              return box ? ((box.querySelector('.px-ph') || {}).textContent || null) : null;
            })()
        """, lambda v: bool(v), tries=12) or ""
        control(cdp, view, "document.querySelector('[data-row=pronunciation]').click()", settle=1)
        control(cdp, view, "document.querySelector('[data-row=accent-elsewhere]').click()", settle=1)
        control(cdp, view, "document.querySelector('[data-sheet] [data-choice=es]').click()", settle=1)
        picked = control(cdp, view, """
            (() => {
              const choice = [...document.querySelectorAll('[data-sheet] [data-choice]')]
                .find(c => c.textContent.includes('Latin'));
              if (!choice) return 'no Latin American accent';
              choice.click();
              return choice.dataset.choice;
            })()
        """, settle=3)
        after = sound_of("calle")
        print(f"  accent {picked}: calle said {before!r} -> {after!r}")
        if picked and picked.startswith("no "):
            failures.append(f"the popup offers no accent to pick ({picked})")
        elif not before:
            failures.append("the word the accent changes was not on the page")
        elif before == after:
            failures.append(f"picking an accent left calle as {before!r}")

        # How the page writes what it says: narrow rather than broad, and with or without the
        # stress marks. Each is judged by the words on the page changing, not by the setting.
        def written():
            return evaluate(cdp, page, "[...document.querySelectorAll('.px-ph')]"
                                       ".map(p => p.textContent).join(' ')") or ""

        # On a German page in a tab behind this one, every word drawn: Teppich's dictionary
        # gives a broad and a narrow transcription, and the setting picks which one is drawn.
        evaluate(cdp, view, "chrome.storage.local.set({density: 1}).then(() => 1)")
        german_target = cdp.send("Target.createTarget",
                                 {"url": f"{base}/german.html", "background": True})["targetId"]
        german = cdp.send("Target.attachToTarget",
                          {"targetId": german_target, "flatten": True})["sessionId"]
        cdp.send("Runtime.enable", session=german)

        def carpet():
            return evaluate(cdp, german, """
                (() => {
                  const box = [...document.querySelectorAll('.px-w')]
                    .find(w => (w.querySelector('.px-was') || {}).textContent === 'Teppich');
                  return box ? ((box.querySelector('.px-ph') || {}).textContent || null) : null;
                })()
            """)

        broad = None
        for _ in range(30):
            broad = carpet()
            if broad:
                break
            time.sleep(1)
        control(cdp, view, "document.querySelector('[data-row=narrow] [data-choice=narrow]').click()",
                settle=3)
        narrow = carpet()
        print(f"  Teppich broad {broad!r}, narrow {narrow!r}")
        if not broad:
            failures.append("the German page never drew Teppich")
        elif broad == narrow or "\u02b0" not in (narrow or ""):
            failures.append(f"choosing narrow drew Teppich as {narrow!r}, not the narrow [tʰɛpʰɪç]")
        # And the card, which writes the narrow transcription between brackets.
        cdp.send("Target.activateTarget", {"targetId": german_target})
        time.sleep(0.5)
        card = point_at(cdp, german, "Teppich") or ""
        print(f"  the card on Teppich, narrow: {card[:40]!r}")
        if "[" not in card or "\u02b0" not in card:
            failures.append(f"the card on Teppich is not the narrow transcription: {card[:60]!r}")
        view = popup(cdp, extid, page_target)
        control(cdp, view, "document.querySelector('[data-row=pronunciation]').click()", settle=1)
        control(cdp, view, "document.querySelector('[data-row=narrow] [data-choice=broad]').click()",
                settle=3)

        # The stress marks, which the switch puts on the page and takes off again.
        def marks():
            return (written() + (carpet() or "")).count("\u02c8")

        shown = evaluate(cdp, view, "document.querySelector('[data-row=stress] input').checked")
        first = marks()
        control(cdp, view, "document.querySelector('[data-row=stress] input').click()", settle=3)
        second = marks()
        control(cdp, view, "document.querySelector('[data-row=stress] input').click()", settle=3)
        print(f"  stress marks with the switch {'on' if shown else 'off'}: {first}, "
              f"turned {'off' if shown else 'on'}: {second}")
        on, off = (first, second) if shown else (second, first)
        if not on or off:
            failures.append(f"the stress switch left {on} marks on and {off} off")
        evaluate(cdp, view, "chrome.storage.local.remove('density').then(() => 1)")
        control(cdp, view, "document.querySelector('[aria-label=Back]').click()", settle=1)

        # Appearance: a palette is what the page's words are drawn in, and light or dark is
        # which way round the settings view itself is.
        def palette():
            return evaluate(cdp, page, "((document.querySelector('.px-w') || {}).className || '')"
                                       ".split(' ').find(c => c.startsWith('theme-')) || ''")

        control(cdp, view, "document.querySelector('[data-row=theme]').click()", settle=1)
        was = palette()
        picked = control(cdp, view, """
            (() => {
              const other = [...document.querySelectorAll('[data-row=palettes] [data-choice]')]
                .find(c => !c.getAttribute('aria-checked') || c.getAttribute('aria-checked') === 'false');
              if (!other) return 'no other palette';
              other.click();
              return other.dataset.choice;
            })()
        """, settle=3)
        now = palette()
        print(f"  palette {picked}: the page's words went from {was!r} to {now!r}")
        if now != f"theme-{picked}":
            failures.append(f"picking the {picked} palette drew the page's words in {now!r}")
        sides = {}
        for side in ("light", "dark"):
            control(cdp, view, f"document.querySelector('[data-row=dark] [data-choice={side}]').click()",
                    settle=1)
            sides[side] = evaluate(cdp, view, "document.documentElement.className")
        print(f"  the settings view, light: {sides['light']!r}, dark: {sides['dark']!r}")
        if "mode-light" not in (sides["light"] or "") or "mode-dark" not in (sides["dark"] or ""):
            failures.append(f"light and dark did not turn the settings view round: {sides}")
        control(cdp, view, "document.querySelector('[data-row=dark] [data-choice=system]').click()",
                settle=1)
        control(cdp, view, "document.querySelector('[aria-label=Back]').click()", settle=1)

        # Both commands' keys as the browser bound them, the main switch's beside it and the
        # translator's on its row.
        for does, command in (("shortcut-on-off", "switch-on-off"), ("shortcut", "translator")):
            shown = evaluate(cdp, view, f"[...document.querySelectorAll('[data-does={does}] kbd')]"
                                        ".map(k => k.textContent).join('+')")
            bound = evaluate(cdp, view, "chrome.commands.getAll().then(c => (c.find(x => x.name =="
                                        f" '{command}') || {{}}).shortcut || '')")
            print(f"  the keys for {command}: shown {shown!r}, bound {bound!r}")
            if not shown or shown != bound:
                failures.append(f"the popup shows {command} on {shown!r}, the browser binds {bound!r}")

        # The translator: opened from the popup, over the page, and answering both ways
        # between the reader's language and the one it opens on, which is the page's.
        evaluate(cdp, view, "document.querySelector('[data-does=open-panel]').click()")
        time.sleep(2)
        opened = evaluate(cdp, page, shadow_text("phonetix-card-host-ask"))
        print(f"  the translator: {(opened or '')[:40]!r}")
        if opened is None:
            failures.append("the popup's button opened no translator on the page")
        else:
            def type_in(text):
                evaluate(cdp, page, """
                    (() => {
                      const field = document.getElementById('phonetix-card-host-ask')
                        .shadowRoot.querySelector('input');
                      field.value = %r;
                      field.dispatchEvent(new Event('input', {bubbles: true}));
                    })()
                """ % text)

            for typed, wanted in (("perro", "Hund"), ("Hund", "perro")):
                # Emptied first, and the last answer gone, so what is read is this word's.
                type_in("")
                emptied = wait_for(cdp, page, shadow_text("phonetix-card-host-ask"),
                                   lambda v: v is not None and "dictionary" not in v, tries=6)
                if emptied is None or "dictionary" in emptied:
                    failures.append("emptying the translator's field left the last answer")
                type_in(typed)
                said = wait_for(cdp, page, shadow_text("phonetix-card-host-ask"),
                                lambda v: bool(v) and wanted in v, tries=12)
                print(f"  typed {typed}: {(said or '')[:70]!r}")
                if not said or wanted not in said:
                    failures.append(f"the translator did not answer {typed} with {wanted}")
            evaluate(cdp, page, "document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape'}))")

        # One site, rather than everywhere: switched off here, the page is bare, and the
        # extension is still on for everything else.
        view = popup(cdp, extid, page_target)
        wait_for(cdp, view, "document.querySelector('[data-row=site] input') !== null", lambda v: v)
        control(cdp, view, "document.querySelector('[data-row=site] input').click()")
        here = words(cdp, page)
        state = json.loads(evaluate(cdp, view, """
            JSON.stringify({
              on: document.querySelector('[data-row=on] input').checked,
              says: (document.querySelector('[data-row=site] [data-about]') || {}).textContent,
            })
        """))
        print(f"  switched off for this site: {here['count']} replaced, "
              f"still on elsewhere: {state['on']}, the row says {state['says']!r}")
        if here["count"] != 0:
            failures.append(f"{here['count']} words stayed replaced with the site switched off")
        if not state["on"]:
            failures.append("switching one site off switched the extension off everywhere")
        control(cdp, view, "document.querySelector('[data-row=site] input').click()")
        back = words(cdp, page)
        if back["count"] == 0:
            failures.append("switching the site back on left the page bare")

        # And the main switch takes it all away.
        control(cdp, view, "document.querySelector('[data-row=on] input').click()")
        off = words(cdp, page)
        if off["count"] != 0:
            failures.append(f"{off['count']} words stayed replaced with the extension off")

        # And the keys are changed where the browser changes them: pressing either opens its
        # page for an extension's shortcuts. Last, since the popup closes behind it.
        for does in ("shortcut-on-off", "shortcut"):
            try:
                evaluate(cdp, view, "window.close()")
            except RuntimeError:
                pass  # closed already, by the last press
            time.sleep(1)
            view = popup(cdp, extid, page_target)
            wait_for(cdp, view, f"document.querySelector('[data-does={does}]') !== null", lambda v: v)
            evaluate(cdp, view, f"document.querySelector('[data-does={does}]').click()")
            opened = None
            for _ in range(10):
                time.sleep(0.5)
                opened = next((t for t in cdp.send("Target.getTargets")["targetInfos"]
                               if t["url"].startswith("chrome://extensions/shortcuts")), None)
                if opened:
                    break
            print(f"  pressing the keys for {does}: {opened['url'] if opened else 'nothing opened'}")
            if not opened:
                failures.append(f"pressing the {does} keys opened no page to change them on")
            else:
                cdp.send("Target.closeTarget", {"targetId": opened["targetId"]})
    finally:
        cdp.close()

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - every control in the popup changes what the reader sees")


if __name__ == "__main__":
    main()
