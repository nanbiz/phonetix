#!/usr/bin/env python3
"""The settings view, driven the way a reader drives it.

A setting is only a setting if changing it changes what the reader sees. This opens the view
in the built extension, works its controls, and looks at a page each time: the switch, what is
drawn over a word, how often, and which language the answers come in.

  uv run python scripts/proofread/settings_view.py
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP
from on_a_page import PORT, build_packs, evaluate, serve, wait_for


def words(cdp, page):
    """What the page carries now: the annotations, and what they say."""
    return json.loads(evaluate(cdp, page, """
        (() => {
          const words = [...document.querySelectorAll('.px-w')];
          return JSON.stringify({
            count: words.length,
            glosses: words.map(w => (w.querySelector('.px-gl') || {}).textContent || '')
                          .filter(Boolean),
            sounds: words.map(w => (w.querySelector('.px-ph') || {}).textContent || '')
                         .filter(Boolean),
            swapped: [...document.querySelectorAll('.px-rep')].map(r => r.textContent),
          });
        })()
    """) or "{}")


def mode(cdp, view, sound, gloss):
    """Set what a word is replaced by: the two switches, worked one at a time.

    There is no list of modes any more - how it sounds and what it means are a switch each,
    and nothing between them - so this is what choosing a mode is. One at a time because each
    switch writes both halves of the setting, and two clicks in the same breath write the
    second from what the first had not finished saying.
    """
    for row, want in (("ipa", sound), ("translate", gloss)):
        evaluate(cdp, view, f"""
            (() => {{
              const box = document.querySelector('[data-row={row}] input');
              if (box && box.checked !== {str(bool(want)).lower()}) box.click();
            }})()
        """)
        time.sleep(1.5)
    time.sleep(1.5)


def control(cdp, view, expression):
    """Work one control in the settings view and let the page hear about it."""
    evaluate(cdp, view, expression)
    time.sleep(2.5)


def main():
    build_packs()
    serve()
    base = f"http://127.0.0.1:{PORT}"
    failures = []

    cdp = PipeCDP()
    cdp.send("Target.setDiscoverTargets", {"discover": True})
    extid = cdp.ensure_extension()
    try:
        # A reader with nothing chosen yet, except where the dictionaries come from.
        book = cdp.send("Target.createTarget", {"url": f"chrome-extension://{extid}/viewbook.html"})
        book_session = cdp.send(
            "Target.attachToTarget", {"targetId": book["targetId"], "flatten": True},
        )["sessionId"]
        cdp.send("Runtime.enable", session=book_session)
        time.sleep(2)
        evaluate(cdp, book_session, (
            f"chrome.storage.local.set({{packBaseUrl:'{base}'}})"
            ".then(() => chrome.storage.local.remove("
            "['on','layer','density','targetLanguage','sourceLanguage']))"
        ))
        cdp.send("Target.closeTarget", {"targetId": book["targetId"]})

        page_target = cdp.send("Target.createTarget", {"url": f"{base}/page.html"})
        page = cdp.send(
            "Target.attachToTarget", {"targetId": page_target["targetId"], "flatten": True},
        )["sessionId"]
        cdp.send("Runtime.enable", session=page)

        view_target = cdp.send("Target.createTarget", {"url": f"chrome-extension://{extid}/popup.html"})
        view = cdp.send(
            "Target.attachToTarget", {"targetId": view_target["targetId"], "flatten": True},
        )["sessionId"]
        cdp.send("Runtime.enable", session=view)

        # It draws itself from what the reader has chosen and what the host holds.
        drawn = wait_for(cdp, view, """
            (() => {
              const panel = document.querySelector('main');
              if (!panel) return null;
              return JSON.stringify({
                rows: [...panel.querySelectorAll('[data-row] [data-name]')]
                  .map(r => r.textContent.trim()),
                choices: [...panel.querySelectorAll('[data-view="layer"] [data-choice]')]
                  .map(c => (c.querySelector('.c-name') || c).textContent.trim()),
                on: panel.querySelector('[data-row="on"] input').checked,
                often: (panel.querySelector('[data-row="density"] [data-about]') || {})
                  .textContent || '',
                switches: ['ipa', 'translate']
                  .filter(row => panel.querySelector(`[data-row=${row}] input`)).length,
                surface: getComputedStyle(document.body).backgroundColor,
                width: Math.round(panel.getBoundingClientRect().width),
              });
            })()
        """, lambda v: v is not None)
        if not drawn:
            print("FAIL - the settings view drew nothing")
            sys.exit(1)
        panel = json.loads(drawn)
        print(f"  {len(panel['rows'])} settings, {panel['switches']} switches, "
              f"{panel['width']}px wide on {panel['surface']}")
        if len(panel["rows"]) < 5:
            failures.append(f"the view offers {panel['rows']}")
        if not panel["on"]:
            failures.append("a fresh reader is switched off")
        if "rgba(0, 0, 0, 0)" in panel["surface"]:
            failures.append("the view has no surface colour, so the tokens did not reach it")
        if panel["width"] < 300:
            failures.append(f"the view measured {panel['width']}px wide")

        # The language the words are turned into, asked for by the mode that turns them into
        # one and by nothing else.
        # Opened and then chosen from, with a moment between: the list is drawn in answer to
        # the press, so a check that opens it and picks in the same breath picks from nothing.
        control(cdp, view, "document.querySelector('[data-row=target] .select').click()")
        control(cdp, view, "document.querySelector('[data-sheet] [data-choice=en]').click()")
        time.sleep(1)

        # The dictionaries, fetched the way a reader fetches them: from the list, by name,
        # one button each. Nothing arrives because a page happened to be in that language.
        for _ in range(4):
            got = evaluate(cdp, view, """
                (() => {
                  // The host's own, not the published ones listed after them.
                  const row = [...document.querySelectorAll('[data-row="pack"]')].find(
                    r => ['es', 'de'].includes(r.dataset.lang)
                      && (r.querySelector('[data-does]') || {}).textContent?.trim() === 'get');
                  if (!row) return 'none left';
                  row.querySelector('[data-does]').click();
                  return row.querySelector('[data-name]').textContent.trim();
                })()
            """)
            if got == "none left":
                break
            print(f"  fetching {got}")
            time.sleep(4)

        # The page starts annotated, in the language it is written in, because nothing has been
        # chosen to read into yet.
        before = wait_for(cdp, page, "document.querySelectorAll('.px-w').length", lambda v: v)
        if not before:
            failures.append("the page was not annotated to begin with")

        # Every word first, so that what the other settings do is visible on a page whose
        # dictionary knows only a handful of its words.
        control(cdp, view, "(() => { const s = document.querySelector('[data-row=density] input');"
                           "s.value = s.max; s.dispatchEvent(new Event('input',{bubbles:true})); })()")
        told = evaluate(
            cdp, view,
            "document.querySelector('[data-row=density] [data-about]').textContent")
        dense = words(cdp, page)
        # At the dense end every word is annotated, and the view says so in those words
        # rather than as "one word in 1", which is a ratio nobody reads.
        if "every word" not in (told or "").lower():
            failures.append(f"the dense end says {told!r}")

        # Reading into German: the answers change language.
        control(cdp, view, "document.querySelector('[data-row=target] .select').click()")
        control(cdp, view, "document.querySelector('[data-sheet] [data-choice=de]').click()")
        print("  DEBUG after de:", evaluate(cdp, view, """
            (() => JSON.stringify({
              sheet: Boolean(document.querySelector('[data-sheet]')),
              chose: (document.querySelector('[data-row=target] .select') || {}).textContent,
              density: (document.querySelector('[data-row=density] input') || {}).value,
              max: (document.querySelector('[data-row=density] input') || {}).max,
            }))()
        """))
        german = words(cdp, page)
        print(f"  reading into German: {german['glosses'][:4]}")
        if "Hund" not in german["glosses"]:
            failures.append(f"the answers are {german['glosses'][:6]}, not German")

        # What a word is replaced by: how it is said rather than what it means.
        mode(cdp, view, sound=True, gloss=False)
        sound = words(cdp, page)
        print(f"  showing the sound: {sound['sounds'][:3]}")
        if sound["glosses"]:
            failures.append(f"meanings are still drawn: {sound['glosses'][:4]}")
        if not sound["sounds"]:
            failures.append("nothing is said about how the words sound")

        # Both: what it means, and how to say that.
        mode(cdp, view, sound=True, gloss=True)
        together = words(cdp, page)
        print(f"  both: {together['glosses'][:3]} said {together['sounds'][:3]}")
        if not together["glosses"]:
            failures.append("both showed no meanings")
        if not together["sounds"]:
            failures.append("both showed no pronunciations for the meanings")

        # Back to meanings for what follows.
        mode(cdp, view, sound=False, gloss=True)

        # An accent whose difference is a rule changes the transcriptions on the page.
        # Every word again first: the bar was left at its sparse end by the check above, and
        # a page with nothing on it says nothing about accents.
        control(cdp, view, "(() => { const s = document.querySelector('[data-row=density] input');"
                           "s.value = s.max; s.dispatchEvent(new Event('input',{bubbles:true})); })()")
        mode(cdp, view, sound=True, gloss=False)

        def sound_of(word):
            said = evaluate(cdp, page, """
                (() => {
                  const box = [...document.querySelectorAll('.px-w')]
                    .find(w => w.textContent.includes(%r));
                  return box ? ((box.querySelector('.px-ph') || {}).textContent || '') : '';
                })()
            """ % word)
            return said or ""

        # Waited for, and the page nudged into reading itself again if it is still bare: the
        # word this step is about has no dictionary entry, so its transcription comes from the
        # synthesiser, and a page annotated while that was still starting holds a token without
        # one until something asks for the page again.
        for _ in range(8):
            if sound_of("calle"):
                break
            # Down and back up, because the bar is already at its dense end: a control set to
            # the value it already holds changes no setting, so nothing asks the page again.
            control(cdp, view, """
                (() => {
                  const s = document.querySelector('[data-row=density] input');
                  s.value = 0;
                  s.dispatchEvent(new Event('input', {bubbles: true}));
                  s.value = s.max;
                  s.dispatchEvent(new Event('input', {bubbles: true}));
                })()
            """)
            time.sleep(3)
        before = sound_of("calle")
        # The accents are a screen of their own, opened from the row that says which one is
        # set: driven the way a reader drives it, through the row and then the choice.
        picked = evaluate(cdp, view, """
            (() => {
              const row = document.querySelector('[data-row="page"]');
              if (!row) return 'no page row';
              row.click();
              const choice = [...document.querySelectorAll('[data-accent]')]
                .find(c => c.textContent.includes('Latin'));
              if (!choice) return 'no Latin American accent';
              choice.click();
              return choice.getAttribute('data-accent');
            })()
        """)
        time.sleep(3)
        after = sound_of("calle")
        # And back out of it, the way a reader leaves a screen they are done with.
        evaluate(cdp, view, "(document.querySelector('[data-view=page] .back') || {}).click?.()")
        print(f"  accent {picked}: calle said {before!r} -> {after!r}")
        if picked and picked.startswith("no "):
            failures.append(f"the view offers no accent to pick ({picked})")
        elif not before:
            failures.append("the word the accent changes was not on the page")
        elif before == after:
            failures.append(f"picking an accent left calle as {before!r}")


        # How often: the sparse end of the bar draws fewer words than the dense end.
        control(cdp, view, "(() => { const s = document.querySelector('[data-row=density] input');"
                           "s.value = 0; s.dispatchEvent(new Event('input',{bubbles:true})); })()")
        sparse = words(cdp, page)
        print(f"  dense {dense['count']} words ({told}), sparse {sparse['count']}")
        if dense["count"] <= sparse["count"]:
            failures.append(
                f"the bar changed nothing: {dense['count']} dense, {sparse['count']} sparse")

        # The dictionaries a reader can have, and the two things to do with one. Behind the
        # advanced screen, because a reader who has one never opens this again.
        offered = wait_for(cdp, view, """
            (() => {
              const rows = [...document.querySelectorAll('[data-row="pack"]')]
                .filter(r => r.querySelector('[data-does]'));
              if (rows.length === 0) return null;
              return JSON.stringify(rows.map(r => ({
                name: r.querySelector('[data-name]').textContent.trim(),
                about: r.querySelector('[data-about]').textContent.trim(),
                action: r.querySelector('[data-does]').textContent.trim(),
              })));
            })()
        """, lambda v: v is not None)
        listed = json.loads(offered or "[]")
        print(f"  dictionaries offered: {[(d['name'], d['action']) for d in listed]}")
        # The host's own two first, as it describes them, and the published ones after.
        own = [(d["name"], d["about"].split(" ")[0]) for d in listed[:2]]
        if own != [("Spanish", "12"), ("German", "12")]:
            failures.append(f"the view does not lead with the host's own dictionaries: {listed[:3]}")
        elif not all(
            "words" in row["about"] and any(u in row["about"] for u in (" B", "KB", "MB"))
            for row in listed
        ):
            failures.append(f"a dictionary row says nothing about its cost: {listed}")

        # Giving one up: the row offers it back, and the page loses the answers it gave.
        held_before = json.loads(offered)
        if any(row["action"] == "remove" for row in held_before):
            control(cdp, view, "[...document.querySelectorAll('[data-row=pack] [data-does]')]"
                               ".find(b => b.textContent.trim() === 'remove').click()")
            time.sleep(2)
            after = json.loads(evaluate(cdp, view, """
                (() => JSON.stringify([...document.querySelectorAll('[data-row=pack] [data-does]')]
                  .map(b => b.textContent.trim())))()
            """) or "[]")
            print(f"  after giving one up: {after}")
            if after.count("get") < 1:
                failures.append(f"a dictionary given up is not offered back: {after}")
        else:
            failures.append(f"no dictionary was held to give up: {held_before}")

        # Where the dictionaries come from is not a setting any more - the reader does not run
        # a host and has nothing to type - but it is still a value, and what the view offers
        # comes from whatever it points at first, and from the published release after it. Changed where it actually lives, which is
        # storage, and the view opened again after it, because that is what the reader does.
        def pointed_at(where):
            control(cdp, view, "chrome.storage.local.set({packBaseUrl: %r})" % where)
            evaluate(cdp, view, "location.reload()")
            time.sleep(4)

        pointed_at("http://127.0.0.1:9")
        without = evaluate(cdp, view, """
            (() => document.querySelectorAll('[data-row=pack]').length)()
        """)
        pointed_at(base)
        back = wait_for(cdp, view, """
            (() => document.querySelectorAll('[data-row=pack]').length)()
        """, lambda v: v)
        print(f"  from a host with nothing on it: {without} dictionaries; "
              f"from one that has them: {back}")
        # The published dictionaries stand in for a host that has nothing, so the reader is
        # still offered them.
        if not without:
            failures.append("a host with nothing on it left the reader with no dictionaries")

        # And when something really stops answering, the view says so. A dictionary host that
        # is set and answers nothing is the case a reader cannot otherwise see: what it would
        # have served is simply missing from the page, which looks like a word nobody wrote an
        # entry for.
        pointed_at("http://127.0.0.1:9")
        said = wait_for(cdp, view, """
            (() => (document.querySelector('[data-row=trouble] [data-name]') || {})
              .textContent || null)()
        """, lambda v: v is not None, tries=12)
        print(f"  with a host that answers nothing: {(said or '').strip()[:70]}")
        if not said:
            failures.append("a host that answers nothing was reported as nothing wrong")

        # And says nothing once it answers again: a warning that stays after the trouble has
        # passed is a warning a reader learns to ignore.
        pointed_at(base)
        quiet = wait_for(cdp, view, """
            (() => !document.querySelector('[data-row=trouble]'))()
        """, lambda v: v, tries=10)
        if not quiet:
            failures.append("the view still reports trouble once the host answers again")

        # One site, rather than everywhere: switched off here, the page is bare, and the
        # extension is still on for everything else.
        control(cdp, view, """
            (() => {
              const row = document.querySelector('[data-row="site"]');
              if (!row) return 'no row';
              row.querySelector('input').click();
              return row.querySelector('[data-name]').textContent.trim();
            })()
        """)
        time.sleep(2)
        here = words(cdp, page)
        still_on = evaluate(cdp, view, """
            (() => {
              return document.querySelector('[data-row="on"] input').checked;
            })()
        """)
        print(f"  switched off for this site: {here['count']} words, still on elsewhere: {still_on}")
        if here["count"] != 0:
            failures.append(f"{here['count']} annotations survived switching the site off")
        if not still_on:
            failures.append("switching one site off switched the extension off everywhere")

        # And the switch takes it all away.
        control(cdp, view, "document.querySelector('[data-row=on] input').click()")
        off = words(cdp, page)
        if off["count"] != 0:
            failures.append(f"{off['count']} annotations survived the switch")

        cdp.send("Target.closeTarget", {"targetId": view_target["targetId"]})
        cdp.send("Target.closeTarget", {"targetId": page_target["targetId"]})
    finally:
        cdp.close()

    for m in cdp.events:
        text = json.dumps(m.get("params", {}))[:200]
        if '"error"' in text or "Error" in text:
            print("  LOG", text)
    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - every setting in the view changes what the reader sees")


if __name__ == "__main__":
    main()
