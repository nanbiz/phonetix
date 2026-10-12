#!/usr/bin/env python3
"""How often the card's answer for a word is the word people used translating it.

Sentences from Tatoeba with their human translations (CC BY 2.0 FR), 400 per language in
.cache/eval/<lang>.tsv. For each word the card would answer, the answer counts as agreeing when
the translation uses it: the same word, or one sharing its stem ("publicar" and "publicado").
A translation says a thing its own way, so no score reaches 100%; what matters is whether a
change moves the share, measured on the same sentences, and what the misses are.

  uv run python scripts/proofread/word_choice.py [spa|deu] [line]

With "line" each sentence's translation is handed to the core as the translated line, which is
what the card has when its host translates the line it was opened on.
"""
import json
import os
import subprocess
import sys
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RELEASE = os.path.join(ROOT, ".cache", "release")
LANGS = {"spa": "es", "deu": "de"}


def plain(word):
    word = unicodedata.normalize("NFD", word.lower())
    return "".join(c for c in word if not unicodedata.combining(c))


def agrees(lead, translation):
    words = [plain(w) for w in translation.replace("-", " ").split()]
    words = ["".join(c for c in w if c.isalnum()) for w in words]
    parts = [plain(p) for p in lead.split()]

    def one(part):
        for word in words:
            if word == part:
                return True
            stem = len(part) - 2
            if stem >= 4 and word[:stem] == part[:stem]:
                return True
        return False

    return bool(parts) and all(one(p) for p in parts)


def main():
    lang = sys.argv[1] if len(sys.argv) > 1 else "spa"
    mode = sys.argv[2] if len(sys.argv) > 2 else ""
    into = LANGS[lang]
    pairs = [line.rstrip("\n").split("\t") for line in
             open(os.path.join(ROOT, ".cache", "eval", f"{lang}.tsv"), encoding="utf-8")]
    run = subprocess.run(
        [os.path.join(ROOT, "core", "target", "release", "examples", "word_choice"),
         os.path.join(RELEASE, "en.pack"), os.path.join(RELEASE, f"{into}.pack"), "en", into]
        + ([mode] if mode else []),
        input="\n".join("\t".join(p) for p in pairs), capture_output=True, text=True, check=True)
    rows = [json.loads(line) for line in run.stdout.splitlines()]
    answered = [r for r in rows if r["lead"]]
    good = [r for r in answered if agrees(r["lead"], pairs[r["at"]][1])]
    print(f"{lang}{' with the line' if mode else ''}: {len(rows)} words read, "
          f"{len(answered)} answered, {len(good)} agree with the translation "
          f"({100 * len(good) / max(1, len(answered)):.1f}%)")
    misses = [r for r in answered if r not in good]
    for r in misses[:int(os.environ.get("SHOW", "0"))]:
        print(f"  {r['word']:>14} -> {r['lead']:<20} | {pairs[r['at']][1]}")


if __name__ == "__main__":
    main()
