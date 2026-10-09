#!/usr/bin/env python3
"""The words each accent says in a way of its own, out of Wiktionary, for scripts/build-accent-packs.sh.

Wiktionary labels a word's pronunciations by where they are said - "Received-Pronunciation",
"General-American", "Brazil" - and data/accents.json lists, for each accent, the labels that are
its own, best first. This writes, per accent, every word whose reading in that accent differs
from the one the language's dictionary leads with, to
assets/dictionaries/accents/<lang>.<accent>.json.gz as {word: ipa}.

Which reading is the accent's is decided by its best label: for British, a reading labelled
Received Pronunciation before one labelled only British or UK, so the Southern Standard British
/ˈand/ never stands in for the RP /ˈænd/ the dictionary already gives. A reading marked as a
letter's name or as the unstressed form is no word's reading in running text, and a word whose
best readings differ between its entries - "a", whose RP readings are those of a dialect pronoun
and adverb, never the article - is left to the accent's rule, since one spelling's reading
cannot say which word a page means. Phonemic transcriptions, between slashes, before phonetic
ones in brackets.

  uv run python tools/build_accent_overlays.py [<kaikki dir>]

<kaikki dir> holds the per-language dumps (<lang>.jsonl.gz, from kaikki.org), .cache/kaikki
by default.
"""
import gzip
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ACCENTS = os.path.join(ROOT, "data", "accents.json")
OUT = os.path.join(ROOT, "assets", "dictionaries", "accents")
# The dump's name for an accent, where it is not the interface's: the overlay keeps the dump's
# name, and scripts/build-accent-packs.sh maps it back.
ALSO_CALLED = {"en-gb-scotland": "en-scotland", "vi-vn-x-central": "vi-hue", "pt-pt": "pt"}
# Labels that make a reading something other than the word as it is said in running text.
NOT_THE_WORD = {"phoneme", "unstressed", "weak", "obsolete", "archaic", "nonstandard", "dialectal",
                "rare", "proscribed", "humorous", "nonce-word"}
# Fewer words than this and an accent with no rule is not worth a pack: it would leave nearly
# every word of a page as the dictionary has it under a name that says otherwise.
LEAST = 500


def transcription(sound):
    """The transcription a sound gives, without its slashes or brackets, or None. Phonemic ones
    are what is taken; a phonetic one, in brackets, only where a language gives nothing else
    (Vietnamese writes all of its so)."""
    ipa = (sound.get("ipa") or "").strip()
    if len(ipa) < 3 or len(ipa) > 80 or (ipa[0], ipa[-1]) not in (("/", "/"), ("[", "]")):
        return None
    ipa = ipa[1:-1].split(",")[0].split(" ~ ")[0].strip().strip("/[]")
    return ipa or None


def labels(sound):
    return set(sound.get("tags") or []) | set(sound.get("raw_tags") or [])


def bare(ipa):
    """A transcription without what only marks where syllables break."""
    return ipa.replace(".", "").replace("‿", "")


# accent -> how many words of its own it was built with, written back to data/accents.json
counted = {}


def build(lang, accents, dump):
    # word -> the dictionary's leading reading; per accent, word -> [(rank, reading) per entry]
    base = {}
    picked = {accent["id"]: {} for accent in accents}
    with gzip.open(dump, "rt", encoding="utf-8") as f:
        for line in f:
            entry = json.loads(line)
            if entry.get("lang_code") != lang:
                continue
            word = entry.get("word")
            sounds = [s for s in entry.get("sounds") or [] if transcription(s)]
            if not word or not sounds:
                continue
            if word not in base:
                slashed = [s for s in sounds if s["ipa"].strip().startswith("/")]
                base[word] = transcription((slashed or sounds)[0])
            for accent in accents:
                # This entry's reading in the accent: the first under its best label.
                chosen = None
                for sound in sounds:
                    tags = labels(sound)
                    if tags & NOT_THE_WORD:
                        continue
                    ranks = [at for at, tag in enumerate(accent["tags"]) if tag in tags]
                    if not ranks:
                        continue
                    phonemic = sound["ipa"].strip().startswith("/")
                    key = (min(ranks), not phonemic)
                    if chosen is None or key < chosen[0]:
                        chosen = (key, transcription(sound))
                if chosen:
                    picked[accent["id"]].setdefault(word, []).append(chosen)
    for accent in accents:
        words = {}
        torn = 0
        for word, entries in picked[accent["id"]].items():
            best = min(key for key, _ in entries)
            readings = [ipa for key, ipa in entries if key == best]
            if len({bare(ipa) for ipa in readings}) > 1:
                torn += 1
                continue
            ipa = readings[0]
            if bare(ipa) != bare(base.get(word, "")):
                words[word] = ipa
        stem = ALSO_CALLED.get(accent["id"], accent["id"])
        path = os.path.join(OUT, f"{lang}.{stem}.json.gz")
        counted[accent["id"]] = len(words) if len(words) >= LEAST or accent.get("rule") else 0
        if len(words) < LEAST and not accent.get("rule"):
            # And no file left from an earlier build saying otherwise.
            if os.path.exists(path):
                os.remove(path)
            print(f"  {accent['id']}: {len(words)} words of its own, too few to be a pack")
            continue
        with gzip.open(path, "wt", encoding="utf-8") as f:
            json.dump(words, f, ensure_ascii=False)
        print(f"  {accent['id']}: {len(words)} words of its own, {torn} left to the rule "
              f"for entries that differ -> {os.path.relpath(path, ROOT)}")


def main():
    dumps = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, ".cache", "kaikki")
    table = json.load(open(ACCENTS))["accents"]
    os.makedirs(OUT, exist_ok=True)
    for lang, accents in table.items():
        tagged = [accent for accent in accents if accent.get("tags")]
        if not tagged:
            continue
        dump = os.path.join(dumps, f"{lang}.jsonl.gz")
        if not os.path.exists(dump):
            raise SystemExit(f"{dump} is missing")
        print(f"{lang}, from {os.path.relpath(dump, ROOT)}")
        build(lang, tagged, dump)
    # How many words each accent has of its own, which decides whether the interface offers it
    # as one a whole page can be read in (tools/gen_types.py).
    whole = json.load(open(ACCENTS), object_pairs_hook=dict)
    for accents in whole["accents"].values():
        for accent in accents:
            if accent["id"] in counted:
                if counted[accent["id"]]:
                    accent["words"] = counted[accent["id"]]
                else:
                    accent.pop("words", None)
    with open(ACCENTS, "w") as f:
        f.write(json.dumps(whole, ensure_ascii=False, indent=2) + "\n")
    print("word counts written to data/accents.json; run tools/gen_types.py")


if __name__ == "__main__":
    main()
