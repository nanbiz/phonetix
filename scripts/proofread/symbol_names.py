#!/usr/bin/env python3
"""Every IPA symbol's name must come from the IPA, not from memory.

The tooltip tells the reader what a sound is. A name written from recollection is
worse than none: it reads as authoritative and can be wrong, and nothing in the
build would notice. Each name in src/lib/ipa-symbols.ts is therefore checked here
against the ipapy database, which carries the IPA's own descriptors.

The check is containment, not equality: our names are the readable form of the
descriptor ("retroflex nasal" for ipapy's "voiced retroflex nasal consonant"), so
a name may leave a descriptor out, but may not contain a word the IPA does not
use for that symbol. That is what catches a wrong place or manner of articulation.

Run: uv run --with ipapy python scripts/proofread/symbol_names.py
"""
import os
import re
import sys

from ipapy import UNICODE_TO_IPA

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SOURCE = os.path.join(ROOT, "src", "lib", "ipa-symbols.ts")

# ipapy spells some descriptors as one hyphenated word; the readable name splits them.
REWRITE = {
    "non-sibilant-fricative": "fricative",
    "sibilant-fricative": "fricative",
    "sibilant-affricate": "affricate",
    "non-sibilant-affricate": "affricate",
    "lateral-approximant": "lateral approximant",
    "lateral-fricative": "lateral fricative",
    "lateral-flap": "lateral flap",
    "labio-dental": "labio dental",
    "alveolo-palatal": "alveolo palatal",
    "post-alveolar": "post alveolar",
    "close-mid": "close mid",
    "open-mid": "open mid",
}

# The readable name for a descriptor the IPA spells differently. These are the same
# sound under another accepted name, not a licence to rename a place or manner.
SYNONYMS = {
    "postalveolar": "palato alveolar",
    "labiodental": "labio dental",
    "labial": "labio",
    "tap": "flap",           # the IPA calls it a flap; "tap" is the usual reading of it
    "rhotic": "rhotacized",
    "stop": "plosive",       # the same manner under its other usual name
}

# Words that name the symbol rather than describe the sound, so they carry no claim
# about its articulation and need no descriptor behind them.
IGNORE = {
    "consonant", "vowel", "diacritic", "suprasegmental", "tone", "the", "a", "as", "in", "or",
    "schwa",                 # the name of ə, not a property of it
    "dark",                  # "dark L" is how ɫ is read aloud
    "colored", "r",          # "r-colored" is the usual reading of rhotacized
    "break", "group",        # a phrase break
    "bar", "tie", "below", "above",
    "l",                     # "dark L" names the letter, not a property of the sound
}

ENTRY = re.compile(r"^\s*'(?P<sym>[^']+)':\s*\{\s*name:\s*'(?P<name>[^']+)'")


def words(text):
    text = text.lower()
    for src, dst in REWRITE.items():
        text = text.replace(src, dst)
    out = set()
    for word in re.split(r"[\s\-()]+", text):
        word = SYNONYMS.get(word, word)
        for part in word.split():
            if part and part not in IGNORE:
                out.add(part)
    return out


def main():
    source = open(SOURCE, encoding="utf-8").read()

    checked = 0
    unknown = []
    wrong = []

    for line in source.splitlines():
        m = ENTRY.match(line)
        if not m:
            continue
        symbol, name = m.group("sym"), m.group("name")

        ipa_char = UNICODE_TO_IPA.get(symbol)
        if ipa_char is None:
            # Stress marks, length marks and the like: ipapy knows them, but a symbol
            # it does not know cannot be checked and must not silently pass as checked.
            unknown.append(f"{symbol!r} ({name})")
            continue

        checked += 1
        ours = words(name)
        theirs = words(ipa_char.name)
        invented = ours - theirs
        if invented:
            wrong.append(f"{symbol!r}: we say {name!r}, the IPA says {ipa_char.name!r} "
                         f"— {', '.join(sorted(invented))} is not in it")

    print(f"checked {checked} symbol names against the ipapy database")
    if unknown:
        print(f"{len(unknown)} not in the database (unchecked): {', '.join(unknown[:8])}"
              + (" ..." if len(unknown) > 8 else ""))

    if wrong:
        print(f"\nFAIL - {len(wrong)} name(s) say something the IPA does not:")
        for w in wrong:
            print("   ", w)
        sys.exit(1)

    print("PASS - every checked name matches the IPA's own descriptors")


if __name__ == "__main__":
    main()
