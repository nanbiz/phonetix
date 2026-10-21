#!/usr/bin/env python3
"""The phone telling a reader what a word means and how it is said, through the side button.

This serves the fixture packs the way the release host would, tells the app where they are and
which language the reader reads into, holds the side button on a word and reads the card it
shows while held: how the word is said and what it means, and only how it is said where the
word is in a language the reader reads as it is. The card has to be gone once the button is
let go.

  PHONETIX_ANDROID_SERIAL=emulator-5554 uv run python scripts/proofread/android_translates.py
"""
import http.server
import json
import os
import re
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import SERIAL, Device, adb, shell, card_while_held

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
CORE = os.path.join(ROOT, "core")
WORK = os.environ.get("PHONETIX_WORK", "/tmp/phonetix-translates")
PORT = int(os.environ.get("PHONETIX_PACK_PORT", "8925"))

SENTENCE = "El perro corre por el camino y descansa en el banco del parque."


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=900, **kw)


def build_packs():
    os.makedirs(WORK, exist_ok=True)
    for lang in ("es", "de"):
        source = os.path.join(CORE, "packbuild", "fixtures", f"{lang}.jsonl")
        got = run(["cargo", "run", "-q", "-p", "packbuild", "--", lang, source,
                   os.path.join(WORK, f"{lang}.pack")], cwd=CORE)
        if got.returncode != 0:
            raise SystemExit(f"packbuild failed for {lang}: {got.stderr[-300:]}")


def manifest():
    rows = []
    for lang in ("es", "de"):
        path = os.path.join(WORK, f"{lang}.pack")
        rows.append({
            "id": f"lex-{lang}", "lang": lang, "built": 0, "entries": 12, "keys": 14,
            "glosses": 12, "bytes": os.path.getsize(path), "sha256": "",
        })
    return json.dumps(rows).encode()


def serve():
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            if self.path == "/packs.json":
                body = manifest()
                kind = "application/json"
            elif self.path.endswith(".pack"):
                path = os.path.join(WORK, os.path.basename(self.path))
                if not os.path.exists(path):
                    self.send_error(404)
                    return
                body = open(path, "rb").read()
                kind = "application/octet-stream"
            else:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    httpd = http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    httpd.timeout = 0.5
    threading.Thread(target=httpd.serve_forever, daemon=True).start()


def set_on_the_screen(dev):
    """The reader adds a language they read on the app's Languages screen, and the service is
    told; the screen then lists it, and the home row names it without wrapping."""
    from webview import View
    import state as State
    failures = []
    dev.surface(mode="spanish", known="none", enable=1)
    for _ in range(6):
        shell("am", "start", "-n", "io.github.nanbiz.phonetix/.MainActivity",
              "--activity-reorder-to-front")
        time.sleep(2.0)
        if "MainActivity" in dev.top_activity():
            break
    with View() as view:
        for _ in range(20):
            if view.evaluate("Boolean(document.querySelector('[data-row=languages]'))"):
                break
            time.sleep(1)
        # The languages the reader has are all there is to set: which one a page is in is the
        # page's, and the card says both how a word sounds and what it means.
        extra = view.evaluate("[...document.querySelectorAll('[data-row=learning], [data-row=ipa], [data-row=translate], [data-row=known], [data-row=mine]')].map(r => r.dataset.row)")
        view.evaluate("document.querySelector('[data-row=languages]').click()")
        time.sleep(1)
        view.evaluate("document.querySelector('[data-does=add-language]').click()")
        time.sleep(1)
        view.evaluate("document.querySelector('[data-sheet] [data-choice=fr]')?.click()")
        time.sleep(1)
        listed = view.evaluate("[...document.querySelectorAll('[data-row=language]')].map(r => r.dataset.lang).join(' ')")
        view.evaluate("document.querySelector('[data-does=back]').click()")
        time.sleep(1)
        after = view.evaluate("document.querySelector('[data-row=languages] [data-about]')?.textContent?.trim()")
        # Named in the language the screen is in, which is the reader's own.
        french = view.evaluate("new Intl.DisplayNames([document.documentElement.lang || 'en'],"
                               " {type: 'language'}).of('fr')") or "French"
        wraps = view.evaluate("""(() => { const r = document.querySelector('[data-row=languages] [data-about]');
            const h = parseFloat(getComputedStyle(r).lineHeight) || 20; return r.getBoundingClientRect().height > h * 1.5 })()""")
    known = State.fetch(SERIAL, State.ask(SERIAL)).get("settings", {}).get("known")
    print(f"  French added on the screen: listed {listed!r}, home row {after!r}, "
          f"service knows {known}")
    if extra:
        failures.append(f"the screen still offers {extra}")
    if "fr" not in (listed or "").split():
        failures.append(f"the Languages screen does not list French once added: {listed!r}")
    if french.lower() not in (after or "").lower():
        failures.append(f"the Languages row does not name French ({french}) once added: {after!r}")
    if wraps:
        failures.append("the Languages row's value wraps")
    if "fr" not in (known or []):
        failures.append(f"the service was not told French is read: {known}")
    return failures


def parts_on_the_screen(dev, base):
    """The reader turns parts of the card off on the Look up screen, and the card held over a
    word says only what is left: no kind of word with Grammar off, no transcription with
    Pronunciation off, and the meaning still there."""
    from webview import View
    failures = []
    dev.surface(mode="spanish", packHost=base, target="de", known="none", enable=1, density=1)
    for _ in range(6):
        shell("am", "start", "-n", "io.github.nanbiz.phonetix/.MainActivity",
              "--activity-reorder-to-front")
        time.sleep(2.0)
        if "MainActivity" in dev.top_activity():
            break

    def switch(view, row):
        view.evaluate(f"document.querySelector('[data-row={row}] input').click()")
        time.sleep(0.5)

    with View() as view:
        for _ in range(20):
            if view.evaluate("Boolean(document.querySelector('[data-row=cards]'))"):
                break
            time.sleep(1)
        view.evaluate("document.querySelector('[data-row=cards]').click()")
        time.sleep(1)
        rows = view.evaluate("[...document.querySelectorAll('[data-view=lookup] [data-row]')].map(r => r.dataset.row).join(' ')")
        switch(view, "show-grammar")
        switch(view, "show-sound")
        view.evaluate("document.querySelector('[data-does=back]').click()")
    dev.surface(mode="spanish", packHost=base, target="de", known="none", enable=1, density=1)
    time.sleep(4)
    texts, _ = card_while_held(dev, "perro")
    print(f"  Look up screen rows {rows!r}; grammar and pronunciation off, perro says {texts}")
    joined = " ".join(texts or [])
    if rows != "show-sound show-meaning show-grammar show-others describe-all":
        failures.append(f"the Look up screen has the rows {rows!r}")
    if "Hund" not in joined:
        failures.append(f"with grammar and pronunciation off the card lost the meaning: {texts}")
    for gone in ("noun", "/pe.ro/"):
        if gone in joined:
            failures.append(f"with grammar and pronunciation off the card still says {gone!r}: {texts}")
    # Back as they were, for the checks after this one.
    for _ in range(6):
        shell("am", "start", "-n", "io.github.nanbiz.phonetix/.MainActivity",
              "--activity-reorder-to-front")
        time.sleep(2.0)
        if "MainActivity" in dev.top_activity():
            break
    with View() as view:
        view.evaluate("document.querySelector('[data-row=cards]').click()")
        time.sleep(1)
        switch(view, "show-grammar")
        switch(view, "show-sound")
        view.evaluate("document.querySelector('[data-does=back]').click()")
    return failures


def learning_is_translated(dev):
    """Until the reader chooses, the phone's own languages are left as they are, but never the
    one they are learning: this emulator is set to English, and English is what is learned."""
    import state as State
    failures = []
    for learning, left in (("es", True), ("en", False)):
        dev.surface(mode="spanish", known="default", target="de", learning=learning,
                    enable=1)
        time.sleep(2)
        known = State.fetch(SERIAL, State.ask(SERIAL)).get("settings", {}).get("known") or []
        print(f"  never translated by default while learning {learning}: {known}")
        if ("en" in known) != left:
            failures.append(f"learning {learning}, the phone's English is "
                            f"{'translated' if left else 'never translated'} by default: {known}")
    return failures


def main():
    build_packs()
    serve()
    # The emulator reaches this machine at the address its own network gives it.
    base = f"http://10.0.2.2:{PORT}"
    dev = Device()
    failures = []

    # The dictionaries first, pushed where a reader's own fetch would leave them. Stopping the
    # app comes before the service is bound, since stopping it unbinds the service and a run
    # against an unbound service measures a screen nothing is annotating.
    shell("am", "force-stop", "io.github.nanbiz.phonetix")
    for lang in ("es", "de"):
        adb("push", os.path.join(WORK, f"{lang}.pack"), f"/data/local/tmp/lex-{lang}.pack",
            timeout=120)
        adb("shell", f"run-as io.github.nanbiz.phonetix sh -c "
                     f"'cat /data/local/tmp/lex-{lang}.pack > files/lex-{lang}.pack'",
            timeout=120)
    # An install clears the accessibility permission, so it is granted rather than assumed.
    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)
    dev.clear_log()
    # Through the harness, which insists the page is really in front: the app's own screen is
    # an activity of the same app, and with it on top a bare `am start` delivers the intent to
    # the task behind it and reports success.
    # The card for "perro" read into German says how it is said and what it means, and with
    # Spanish among the languages the reader reads as they are it only says how it is said.
    for known, has, lacks in (("none", ["Hund", "pe"], []), ("es", ["pe"], ["Hund"])):
        dev.surface(mode="spanish", packHost=base, target="de", known=known, enable=1, density=1)
        time.sleep(4)
        texts, closed = card_while_held(dev, "perro")
        print(f"  never translating {known}: the card for perro says {texts}, closed after release: {closed}")
        if not texts:
            failures.append(f"never translating {known}, no card came up for perro")
            continue
        joined = " ".join(texts)
        for part in has:
            if part not in joined:
                failures.append(f"never translating {known}, the card for perro lacks {part!r}: {texts}")
        for part in lacks:
            if part in joined:
                failures.append(f"never translating {known}, the card for perro shows {part!r}: {texts}")
        if not closed:
            failures.append(f"never translating {known}, the card stayed up after the button was let go")

    failures += parts_on_the_screen(dev, base)
    failures += set_on_the_screen(dev)
    failures += learning_is_translated(dev)

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the side button's card says how a word sounds and what it means, and goes with the finger")


if __name__ == "__main__":
    main()
