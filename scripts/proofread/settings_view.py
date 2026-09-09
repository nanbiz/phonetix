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
              const panel = document.querySelector('.panel');
              if (!panel) return null;
              return JSON.stringify({
                rows: [...panel.querySelectorAll('.row .r-name')].map(r => r.textContent.trim()),
                choices: [...panel.querySelectorAll('.seg span')].map(s => s.textContent.trim()),
                on: panel.querySelector('.toggle').classList.contains('on'),
                often: (panel.querySelector('.r-sub') || {}).textContent || '',
                languages: panel.querySelectorAll('select')[0].options.length,
                surface: getComputedStyle(panel).backgroundColor,
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

        # The page starts annotated, in the language it is written in, because nothing has been
        # chosen to read into yet.
        before = wait_for(cdp, page, "document.querySelectorAll('.px-w').length", lambda v: v)
        if not before:
            failures.append("the page was not annotated to begin with")

        # Every word first, so that what the other settings do is visible on a page whose
        # dictionary knows only a handful of its words.
        control(cdp, view, "(() => { const s = document.querySelector('.slider');"
                           "s.value = s.max; s.dispatchEvent(new Event('input',{bubbles:true})); })()")
        told = evaluate(cdp, view, "document.querySelector('.r-sub').textContent")
        dense = words(cdp, page)
        if "1" not in (told or ""):
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
        control(cdp, view, "[...document.querySelectorAll('.seg span')]"
                           ".find(s => s.textContent.trim() === 'sound').click()")
        sound = words(cdp, page)
        print(f"  showing the sound: {sound['sounds'][:3]}")
        if sound["glosses"]:
            failures.append(f"meanings are still drawn: {sound['glosses'][:4]}")
        if not sound["sounds"]:
            failures.append("nothing is said about how the words sound")

        # In place: the word repainted as what it means.
        control(cdp, view, "[...document.querySelectorAll('.seg span')]"
                           ".find(s => s.textContent.trim() === 'in place').click()")
        swapped = words(cdp, page)
        print(f"  in place: {swapped['swapped'][:4]}")
        if "Hund" not in swapped["swapped"]:
            failures.append(f"nothing was repainted: {swapped['swapped'][:4]}")

        # How often: the sparse end of the bar draws fewer words than the dense end.
        control(cdp, view, "[...document.querySelectorAll('.seg span')]"
                           ".find(s => s.textContent.trim() === 'meaning').click()")
        control(cdp, view, "(() => { const s = document.querySelector('.slider');"
                           "s.value = 0; s.dispatchEvent(new Event('input',{bubbles:true})); })()")
        sparse = words(cdp, page)
        print(f"  dense {dense['count']} words ({told}), sparse {sparse['count']}")
        if dense["count"] <= sparse["count"]:
            failures.append(
                f"the bar changed nothing: {dense['count']} dense, {sparse['count']} sparse")
        if "1" not in (told or ""):
            failures.append(f"the dense end says {told!r}")

        # And the switch takes it all away.
        control(cdp, view, "document.querySelector('.toggle').click()")
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
