#!/usr/bin/env python3
"""Generate public/common-words.json: the most frequent words per language.

Sprinkle mode transcribes a sparse subset of a page's words. Left to a blind hash it
picks function words as often as content words, so half of what it marks is "the",
"of", "in" — noise to a reader who already knows them. This lists the most common
words in each language so sprinkle can skip them and land on the words worth reading.

Frequencies come from the wordfreq dataset (opensubtitles/wikipedia/news blend). The
output is committed so the build and CI need neither wordfreq nor the network.

  uv run --with wordfreq python scripts/build-common-words.py
"""
import json
import os
import re

from wordfreq import available_languages, top_n_list

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "public", "common-words.json")

# Every language phonetix resolves (src/lib/types.ts Languages).
PHONETIX_LANGS = (
    "en de es fr af ar bg bn bs ca cs cy da el eo et eu fa fi ga hi hr hu hy id is "
    "it ja ka kk ko ku la lt lv mk ml ms my nl no pl pt ro ru sk sl sq sr sv sw ta "
    "te th tr uk ur uz vi zh"
).split()

# How many of the most common words to skip per language.
TOP_N = 300

# A single word token: letters only (any script), no spaces, digits or punctuation.
WORD = re.compile(r"^[^\W\d_]+$", re.UNICODE)


def main():
    have = set(available_languages())
    out = {}
    for lang in PHONETIX_LANGS:
        if lang not in have:
            continue
        words = [w.lower() for w in top_n_list(lang, TOP_N * 2) if WORD.match(w)]
        # De-duplicate case folds while keeping order, then cap at TOP_N.
        seen, ranked = set(), []
        for w in words:
            if w not in seen:
                seen.add(w)
                ranked.append(w)
            if len(ranked) >= TOP_N:
                break
        if ranked:
            out[lang] = ranked

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")

    total = sum(len(v) for v in out.values())
    print(f"wrote {OUT}: {len(out)} languages, {total} words ({os.path.getsize(OUT)//1024} KB)")
    print("skipped (no wordfreq data):", " ".join(l for l in PHONETIX_LANGS if l not in have) or "none")


if __name__ == "__main__":
    main()
