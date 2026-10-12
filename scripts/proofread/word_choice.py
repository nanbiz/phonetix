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


def pairs_for(lang):
    """400 sentences of 7 to 16 English words with their human translation, sampled once from
    Tatoeba's own exports and kept in .cache/eval."""
    path = os.path.join(ROOT, ".cache", "eval", f"{lang}.tsv")
    if not os.path.exists(path):
        import bz2
        import random
        import urllib.request
        where = os.path.dirname(path)
        os.makedirs(where, exist_ok=True)
        base = "https://downloads.tatoeba.org/exports/per_language"
        for name in (f"eng/eng_sentences.tsv.bz2", f"{lang}/{lang}_sentences.tsv.bz2",
                     f"{lang}/{lang}-eng_links.tsv.bz2"):
            into = os.path.join(where, os.path.basename(name))
            if not os.path.exists(into):
                urllib.request.urlretrieve(f"{base}/{name}", into)

        def sentences(name):
            out = {}
            for line in bz2.open(os.path.join(where, name), "rt", encoding="utf-8"):
                parts = line.rstrip("\n").split("\t")
                if len(parts) >= 3:
                    out[parts[0]] = parts[2]
            return out

        english, other = sentences("eng_sentences.tsv.bz2"), sentences(f"{lang}_sentences.tsv.bz2")
        pairs, seen = [], set()
        for line in bz2.open(os.path.join(where, f"{lang}-eng_links.tsv.bz2"), "rt"):
            theirs, ours = line.split()[:2]
            if theirs in other and ours in english and ours not in seen:
                if 7 <= len(english[ours].split()) <= 16:
                    pairs.append((english[ours], other[theirs]))
                    seen.add(ours)
        random.Random(7).shuffle(pairs)
        with open(path, "w", encoding="utf-8") as f:
            for e, o in pairs[:400]:
                f.write(e.replace("\t", " ") + "\t" + o.replace("\t", " ") + "\n")
    return [line.rstrip("\n").split("\t") for line in open(path, encoding="utf-8")]


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
    pairs = pairs_for(lang)
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
