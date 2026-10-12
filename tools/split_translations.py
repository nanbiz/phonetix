#!/usr/bin/env python3
"""Split the English Wiktionary's translation tables into one file per target language.

Every English entry of the dump carries the translation table its page has: per sense, what
the word is in each language, written by people who speak both. That is what an English word
is read into another language with (packbuild --tables), ahead of joining the two dictionaries
on their English glosses, which only guesses at it: "you" joined that way came out "vuestro",
where the table says "tú, usted, vosotros, ustedes".

One pass over the English extract (<split dir>/en.jsonl.gz, written by split_kaikki.py), one
gzipped TSV per language code. A table's groups are not in the order of the entry's senses
("team" translates the draft animals before the people who work together), so each group is
matched to the sense its label shares the most words with and numbered by it, and the rows go
out in that order; within a group, in the order its editors put the words in, which puts the
common word first far more often than anything counted does:

    english word, part of speech, sense number, sense label, word in the language

  uv run --with orjson python tools/split_translations.py <split dir> <out dir>
"""
import gzip
import os
import re
import sys

import orjson

# Notes a table puts beside a word, "(leísmo) les", which are about its use, not part of it.
NOTE = re.compile(r"\([^)]*\)")


def cleaned(word):
    word = NOTE.sub(" ", word)
    return " ".join(word.split()).strip(" ,;")


# Words that say nothing about which sense a label is.
EMPTY = {"a", "an", "the", "of", "to", "or", "and", "in", "on", "for", "with", "as", "by", "at",
         "be", "is", "that", "which", "who", "one", "something", "someone", "etc", "from"}


def words(text):
    return {w for w in re.findall(r"[a-z]+", text.lower()) if w not in EMPTY and len(w) > 1}


def sense_of(label, senses):
    """The number of the entry's sense a table group's label is about: the one sharing the most
    words with it, the earlier of equals; past every sense where it shares none."""
    asked = words(label)
    best, most = None, 0
    for number, said in enumerate(senses):
        shared = len(asked & said)
        if shared > most:
            best, most = number, shared
    return best


def main():
    split, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    files = {}
    written = 0
    with gzip.open(os.path.join(split, "en.jsonl.gz"), "rb") as lines:
        for line in lines:
            if b'"translations"' not in line:
                continue
            entry = orjson.loads(line)
            english = entry.get("word") or ""
            pos = entry.get("pos") or ""
            senses = [words(" ".join((sense.get("glosses") or []) + (sense.get("raw_glosses") or [])))
                      for sense in entry.get("senses") or []]
            # Each group by the sense it is about, and a group about none after every sense, in
            # the order the table names them.
            numbered = {}
            rows = []
            for row in entry.get("translations") or []:
                code = row.get("lang_code") or ""
                word = cleaned(row.get("word") or "")
                if not code or not word or not english:
                    continue
                label = " ".join((row.get("sense") or "").split())
                if label not in numbered:
                    matched = sense_of(label, senses)
                    numbered[label] = matched if matched is not None else len(senses) + len(numbered)
                rows.append((numbered[label], len(rows), code, label, word))
            for number, _, code, label, word in sorted(rows):
                if code not in files:
                    files[code] = gzip.open(os.path.join(out, f"{code}.tsv.gz"), "wt",
                                            encoding="utf-8")
                fields = (english, pos, str(number), label, word)
                files[code].write("\t".join(f.replace("\t", " ") for f in fields) + "\n")
                written += 1
    for handle in files.values():
        handle.close()
    print(f"{written} rows in {len(files)} languages")


if __name__ == "__main__":
    main()
