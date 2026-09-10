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
                choices: [...panel.querySelectorAll('[data-row="layer"] [data-choice]')]
                  .map(s => s.textContent.trim()),
                on: panel.querySelector('[data-row="on"] input').checked,
                often: (panel.querySelector('[data-row="density"] [data-about]') || {})
                  .textContent || '',
                languages: panel.querySelectorAll('select')[0].options.length,
                surface: getComputedStyle(document.body).backgroundColor,
                width: Math.round(panel.getBoundingClientRect().width),
              });
            })()
        """, lambda v: v is not None)
        if not drawn:
            print("FAIL - the settings view drew nothing")
            sys.exit(1)
        panel = json.loads(drawn)
        print(f"  {len(panel['rows'])} settings, {panel['languages'] - 1} languages, "
              f"{panel['width']}px wide on {panel['surface']}")
        if len(panel["rows"]) < 5:
            failures.append(f"the view offers {panel['rows']}")
        if not panel["on"]:
            failures.append("a fresh reader is switched off")
        if "rgba(0, 0, 0, 0)" in panel["surface"]:
            failures.append("the view has no surface colour, so the tokens did not reach it")
        if panel["width"] < 300:
            failures.append(f"the view measured {panel['width']}px wide")

        # The dictionaries, fetched the way a reader fetches them: from the list, by name,
        # one button each. Nothing arrives because a page happened to be in that language.
        for _ in range(4):
            got = evaluate(cdp, view, """
                (() => {
                  const row = [...document.querySelectorAll('[data-row="pack"]')].find(
                    r => (r.querySelector('[data-does]') || {}).textContent
                          ?.trim() === 'get');
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
        control(cdp, view, "document.querySelectorAll('select')[0].value='de';"
                           "document.querySelectorAll('select')[0]"
                           ".dispatchEvent(new Event('change',{bubbles:true}))")
        german = words(cdp, page)
        print(f"  reading into German: {german['glosses'][:4]}")
        if "Hund" not in german["glosses"]:
            failures.append(f"the answers are {german['glosses'][:6]}, not German")

        # What is shown over a word: the sound rather than the meaning.
        control(cdp, view, "[...document.querySelectorAll('[data-choice]')]"
                           ".find(s => s.textContent.trim() === 'sound').click()")
        sound = words(cdp, page)
        print(f"  showing the sound: {sound['sounds'][:3]}")
        if sound["glosses"]:
            failures.append(f"meanings are still drawn: {sound['glosses'][:4]}")
        if not sound["sounds"]:
            failures.append("nothing is said about how the words sound")

        # In place: the word repainted as what it means.
        control(cdp, view, "[...document.querySelectorAll('[data-choice]')]"
                           ".find(s => s.textContent.trim() === 'in place').click()")
        swapped = words(cdp, page)
        print(f"  in place: {swapped['swapped'][:4]}")
        if "Hund" not in swapped["swapped"]:
            failures.append(f"nothing was repainted: {swapped['swapped'][:4]}")

        # And what the swap covered is shown in place while the cursor is on it: a reader who
        # wants to know what the word actually was rests on it, rather than hunting for the
        # browser's own tooltip.
        under = json.loads(evaluate(cdp, page, """
            (async () => {
              const box = [...document.querySelectorAll('.px-w')]
                .find(w => w.querySelector('.px-was'));
              if (!box) return JSON.stringify({found: false});
              const was = box.querySelector('.px-was');
              const before = getComputedStyle(was).display;
              const at = box.getBoundingClientRect();
              box.dispatchEvent(new MouseEvent('mouseover', {
                bubbles: true, clientX: at.left + 2, clientY: at.top + 2,
              }));
              await new Promise(r => setTimeout(r, 200));
              return JSON.stringify({
                found: true,
                word: was.textContent,
                before,
                after: getComputedStyle(was).display,
                ground: getComputedStyle(was).backgroundColor,
              });
            })()
        """) or "{}")
        if not under.get("found"):
            failures.append("no swapped word kept what it had covered")
        else:
            print(f"  resting on the swap shows {under['word']!r} "
                  f"({under['before']} -> {under['after']} on {under['ground']})")
            if under["before"] != "none" or under["after"] == "none":
                failures.append(
                    f"the original went from {under['before']} to {under['after']}")
            if "rgba(0, 0, 0, 0)" in under["ground"]:
                failures.append("the revealed word has no ground, so both forms show at once")

        # How often: the sparse end of the bar draws fewer words than the dense end.
        control(cdp, view, "[...document.querySelectorAll('[data-choice]')]"
                           ".find(s => s.textContent.trim() === 'meaning').click()")
        control(cdp, view, "(() => { const s = document.querySelector('[data-row=density] input');"
                           "s.value = 0; s.dispatchEvent(new Event('input',{bubbles:true})); })()")
        sparse = words(cdp, page)
        print(f"  dense {dense['count']} words ({told}), sparse {sparse['count']}")
        if dense["count"] <= sparse["count"]:
            failures.append(
                f"the bar changed nothing: {dense['count']} dense, {sparse['count']} sparse")
        # At the dense end every word is annotated, and the view says so in those words
        # rather than as "one word in 1", which is a ratio nobody reads.
        if "every word" not in (told or "").lower():
            failures.append(f"the dense end says {told!r}")

        # The dictionaries a reader can have, and the two things to do with one.
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
        if len(listed) != 2:
            failures.append(f"the view offers {listed}")
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

        # An accent whose difference is a rule changes the transcriptions on the page.
        # Every word again first: the bar was left at its sparse end by the check above, and
        # a page with nothing on it says nothing about accents.
        control(cdp, view, "(() => { const s = document.querySelector('[data-row=density] input');"
                           "s.value = s.max; s.dispatchEvent(new Event('input',{bubbles:true})); })()")
        control(cdp, view, "[...document.querySelectorAll('[data-choice]')]"
                           ".find(s => s.textContent.trim() === 'sound').click()")
        def sound_of(word):
            said = evaluate(cdp, page, """
                (() => {
                  const box = [...document.querySelectorAll('.px-w')]
                    .find(w => w.textContent.includes(%r));
                  return box ? ((box.querySelector('.px-ph') || {}).textContent || '') : '';
                })()
            """ % word)
            return said or ""

        before = sound_of("calle")
        # The accents are a screen of their own, opened from the row that says which one is
        # set: driven the way a reader drives it, through the row and then the choice.
        picked = evaluate(cdp, view, """
            (() => {
              const row = document.querySelector('[data-row="accent"]');
              if (!row) return 'no accent row';
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
        evaluate(cdp, view, "(document.querySelector('[data-view=accent] .back') || {}).click?.()")
        print(f"  accent {picked}: calle said {before!r} -> {after!r}")
        if picked and picked.startswith("no "):
            failures.append(f"the view offers no accent to pick ({picked})")
        elif not before:
            failures.append("the word the accent changes was not on the page")
        elif before == after:
            failures.append(f"picking an accent left calle as {before!r}")

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

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - every setting in the view changes what the reader sees")


if __name__ == "__main__":
    main()
