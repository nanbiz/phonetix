#!/usr/bin/env python3
"""Every espeak voice the extension can select must actually exist.

Asked for a voice it does not have, espeak does not fail — it returns nonsense
("əəəəəəə"), which lands on the page as the word's pronunciation. So each voice
named in src/lib/accents.ts is put through espeak here and its answer checked for
being a real transcription: several distinct sounds, and different from the
degenerate output espeak gives for an unknown voice.

Run: uv run python scripts/proofread/voice_check.py
"""
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ACCENTS_TS = os.path.join(ROOT, "src", "lib", "accents.ts")

# A word no dictionary knows, so espeak has to synthesize it.
NONSENSE = "zorblax"

failures = []


def voices_from_source():
    """Every `voice: '...'` named in the accent table, with its language."""
    src = open(ACCENTS_TS, encoding="utf-8").read()
    table = src[src.index("export const ACCENTS"):]
    out = []
    lang = None
    for line in table.splitlines():
        m = re.match(r"\s*([a-z]{2}):\s*\[", line)
        if m:
            lang = m.group(1)
        v = re.search(r"voice:\s*'([^']+)'", line)
        if v and lang:
            out.append((lang, v.group(1)))
    return out


def main():
    cdp = PipeCDP()
    cdp.send("Target.setDiscoverTargets", {"discover": True})
    extid = cdp.ensure_extension()

    tid = cdp.send("Target.createTarget", {"url": f"chrome-extension://{extid}/popup.html"})["targetId"]
    s = cdp.send("Target.attachToTarget", {"targetId": tid, "flatten": True})["sessionId"]
    cdp.send("Runtime.enable", session=s)
    time.sleep(4)

    def ipa_of(word, voice, lang):
        expr = (
            "chrome.runtime.sendMessage({type:'phonemize',timestamp:Date.now(),"
            f"data:{{words:['{word}'],voice:'{voice}',lang:'{lang}'}}}})"
            ".then(r => JSON.stringify(r.data ?? r))"
        )
        r = cdp.send("Runtime.evaluate",
                     {"expression": expr, "awaitPromise": True, "returnByValue": True},
                     session=s)
        try:
            d = json.loads(r["result"]["value"])
            return (d.get("res") or {}).get(word, {}).get("ipa", "")
        except Exception:
            return ""

    def ipa(voice, lang):
        return ipa_of(NONSENSE, voice, lang)

    # espeak keeps the previous voice when asked for one it lacks, so a single
    # reading proves nothing: it may be the last voice's answer leaking through.
    # Each voice is read twice, after two different control voices. A voice that
    # is really applied gives the same answer both times; a missing one echoes
    # whichever control came before it.
    CONTROLS = ["en-us", "en-gb-scotland"]

    voices = voices_from_source()
    print(f"checking {len(voices)} espeak voices named in accents.ts")
    for lang, voice in voices:
        readings = []
        for control in CONTROLS:
            ipa(control, "en")          # set a known, different voice first
            readings.append(ipa(voice, lang))

        stable = readings[0] == readings[1]
        got = readings[0]
        distinct = len(set(got.replace("ˈ", "").replace("ˌ", "").replace("ː", "")))
        ok = bool(got) and distinct > 2 and stable
        why = "" if ok else (" (echoes the previous voice)" if not stable else " (no such voice)")
        print(f"  {'PASS' if ok else 'FAIL'}  {lang}/{voice:20} -> {readings!r}{why}")
        if not ok:
            failures.append(f"{lang}/{voice} -> {readings!r}{why}")

    # An accent must actually change the words the dictionary knows, or choosing
    # it does nothing on a real page: this is what made accents look dead before
    # the overlays existed, when only synthesized words responded to the choice.
    print("\nchecking that accents change dictionary words")
    CASES = [
        ("en", "dance", "en", "en-us"),
        ("en", "schedule", "en", "en-us"),
        ("en", "car", "en", "en-us"),
        ("en", "bath", "en", "en-au"),
        ("es", "cielo", "es", "es-419"),
    ]
    for lang, word, base_accent, accent in CASES:
        a = ipa_of(word, base_accent, lang)
        b = ipa_of(word, accent, lang)
        ok = bool(a) and bool(b) and a != b
        print(f"  {'PASS' if ok else 'FAIL'}  {lang}/{word:9} {base_accent}={a!r} vs {accent}={b!r}")
        if not ok:
            failures.append(f"{lang}/{word}: {base_accent}={a!r} == {accent}={b!r}")

    cdp.close()

    if failures:
        print(f"\nFAIL - espeak cannot speak {len(failures)} of the voices offered:")
        for f in failures:
            print("   ", f)
        print("Remove the voice from the accent (the dictionary still carries it),")
        print("or name one espeak really has.")
        sys.exit(1)
    print(f"\nPASS - all {len(voices)} voices speak, and every accent changes real words")


if __name__ == "__main__":
    main()
