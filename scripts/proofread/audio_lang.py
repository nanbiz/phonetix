#!/usr/bin/env python3
"""Human recordings must be in the word's language, never another.

Many words are spelled the same in several languages ("animations" is a word in
both English and French), and Wiktionary lists a recording for each. Picking the
wrong one plays a French voice for an English reader. This drives the real
background worker's Wiktionary lookup and asserts the returned audio, when any,
is a recording in the language that was asked for.

Run: PHONETIX_CHROMIUM=<chrome> uv run python scripts/proofread/audio_lang.py
"""
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP

# ISO 639-3 codes Lingua Libre stamps into a filename, keyed by our language code.
ISO3 = {
    "en": "eng", "fr": "fra", "de": "deu", "es": "spa", "it": "ita",
    "pt": "por", "nl": "nld", "ru": "rus", "pl": "pol",
}

# The two-letter prefix the older files use, keyed by our language code.
OLD_PREFIX = {
    "en": "en", "fr": "fr", "de": "de", "es": "es", "it": "it",
    "pt": "pt", "nl": "nl", "ru": "ru", "pl": "pl",
}

# word, language it is read in. Several are spelled the same in other languages
# and have a recording under each, so a language-blind picker mispicks them.
CASES = [
    ("animations", "en"),   # also a French word, French recording exists
    ("cat", "en"),
    ("information", "en"),   # also French
    ("nation", "en"),       # also French
    ("chat", "fr"),         # also English, must stay French here
    ("animations", "fr"),   # the French recording is correct here
    ("Katze", "de"),
    ("gato", "es"),
    ("casa", "it"),
]


def audio_lang(filename: str) -> str | None:
    """The language of a recording, read from its filename, or None if unknown."""
    ll = re.search(r"\bLL-Q\d+ \(([a-z]{3})\)", filename, re.I)
    if ll:
        return ll.group(1).lower()
    old = re.match(r"^([a-z]{2,3})(?:-[a-z]{2,})?[-_]", filename, re.I)
    if old:
        return old.group(1).lower()
    return None


def main():
    cdp = PipeCDP()
    cdp.send("Target.setDiscoverTargets", {"discover": True})
    extid = cdp.ensure_extension()

    tid = cdp.send("Target.createTarget", {"url": f"chrome-extension://{extid}/popup.html"})["targetId"]
    s = cdp.send("Target.attachToTarget", {"targetId": tid, "flatten": True})["sessionId"]
    cdp.send("Runtime.enable", session=s)
    time.sleep(4)

    def check(word, lang):
        expr = (
            "chrome.runtime.sendMessage({type:'checkWiktionary',timestamp:Date.now(),"
            f"data:{{word:{json.dumps(word)},lang:{json.dumps(lang)}}}}})"
            ".then(r => JSON.stringify(r.data ?? r))"
        )
        r = cdp.send("Runtime.evaluate",
                     {"expression": expr, "awaitPromise": True, "returnByValue": True},
                     session=s, timeout=40)
        try:
            d = json.loads(r["result"]["value"])
            return d.get("res") or d      # the handler wraps its reply in {res: ...}
        except Exception:
            return {}

    failures = []
    got_audio = 0
    print(f"checking Wiktionary audio language for {len(CASES)} words")
    for word, lang in CASES:
        info = check(word, lang)
        url = (info or {}).get("audioUrl")
        if not url:
            print(f"  ---   {lang}/{word:12} no audio")
            continue
        got_audio += 1
        # The filename is the last path segment, percent-decoded enough to read.
        fname = url.rsplit("/", 1)[-1]
        fname = fname.replace("%20", " ").replace("%28", "(").replace("%29", ")")
        flang = audio_lang(fname)
        want = {lang, ISO3.get(lang), OLD_PREFIX.get(lang)}
        ok = flang is None or flang in want
        mark = "PASS" if ok else "FAIL"
        note = "" if ok else f"  <- {flang} recording for a {lang} word"
        print(f"  {mark}  {lang}/{word:12} {fname}{note}")
        if not ok:
            failures.append(f"{lang}/{word}: {flang} recording {fname}")

    cdp.close()

    # Most of these words are known to have a recording; if almost none came back, the
    # whole Wiktionary/Commons lookup is broken (not just "no wrong-language audio"),
    # and this test would otherwise pass vacuously. Demand a floor so a dead lookup fails.
    if got_audio < 4:
        failures.append(f"only {got_audio}/{len(CASES)} words returned any audio — the lookup looks dead")

    if failures:
        print(f"\nFAIL:")
        for f in failures:
            print("   ", f)
        sys.exit(1)
    print(f"\nPASS - {got_audio}/{len(CASES)} words had audio, every recording in the word's language")


if __name__ == "__main__":
    main()
