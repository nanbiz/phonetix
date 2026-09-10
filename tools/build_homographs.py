#!/usr/bin/env python3
"""The homograph classifiers, as something the core can read.

A homograph is a spelling that is several words, and which one a reader met is decided by what
surrounds it. The neighbour's part of speech decides some of them in every language; a trained
classifier decides far more, in the ten languages one exists for. These were trained for
PolyPhoneme and carried into the extension, and the merge dropped them.

They arrive as JSON, which the core has no parser for and should not grow one for: it reads
its language model as a binary it walks itself, and this is the same. So the JSON becomes one
file per language, in a format with no parsing to speak of.

  uv run python tools/build_homographs.py

The shape, all integers varint-coded and all strings length-prefixed UTF-8:

  "PXHG" 1
  count of words
    word, count of classes, which class is the default
      class id, label, pronunciation, count of short keywords, each keyword
    count of rules
      score (thousandths, as an integer), feature, which class it names
"""
import gzip
import json
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
FROM = os.path.join(ROOT, "assets", "homographs")
INTO = os.path.join(ROOT, "assets", "homographs")

MAGIC = b"PXHG"
VERSION = 1

# Only the short ones. They are read for one thing - a CJK compound whose neighbouring
# character belongs to one reading - and the long ones are what the decision rules already say.
KEYWORD_LIMIT = 2

# How many rules to keep per word, best first. The tail of a decision list is where its
# confidence has run out, and keeping it makes the file big without deciding anything.
RULES_PER_WORD = 40


def varint(value):
    out = bytearray()
    value = int(value)
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def text(value):
    encoded = (value or "").encode("utf-8")
    return varint(len(encoded)) + encoded


def read(lang):
    path = os.path.join(FROM, f"{lang}.json.gz")
    if not os.path.exists(path):
        return None
    with gzip.open(path) as f:
        return json.load(f)


def classes_of(entry):
    """The readings a word has, best first, in the two shapes the trainers wrote."""
    out = []
    if entry.get("readings"):
        for reading, about in entry["readings"].items():
            out.append({
                "id": reading,
                "label": "",
                "pronunciation": reading,
                "keywords": about.get("keywords") or [],
                "weight": about.get("frequency") or 0,
            })
    elif entry.get("classes"):
        for name, about in entry["classes"].items():
            out.append({
                "id": name,
                "label": about.get("label") or "",
                "pronunciation": about.get("pronunciation") or "",
                "keywords": about.get("keywords") or [],
                "weight": about.get("example_count") or 1,
            })
    out.sort(key=lambda c: -c["weight"])
    return out


def build(lang):
    table = read(lang)
    if not table:
        return None
    words = []
    for word, entry in table.items():
        classes = classes_of(entry)
        if len(classes) < 2:
            # A word with one reading is not a homograph, whatever the file says.
            continue
        where = {cls["id"]: at for at, cls in enumerate(classes)}
        rules = []
        for rule in (entry.get("decision_rules") or [])[:RULES_PER_WORD]:
            at = where.get(rule.get("class"))
            if at is None or not rule.get("feature"):
                continue
            rules.append((round(float(rule.get("score") or 0) * 1000), rule["feature"], at))
        words.append((word, classes, rules))

    out = bytearray(MAGIC)
    out += struct.pack("<B", VERSION)
    out += varint(len(words))
    for word, classes, rules in words:
        out += text(word)
        out += varint(len(classes))
        # The default is the first, which is the one the training saw most often.
        out += varint(0)
        for cls in classes:
            out += text(cls["id"])
            out += text(cls["label"])
            out += text(cls["pronunciation"])
            short = [k for k in cls["keywords"] if len(k) <= KEYWORD_LIMIT][:8]
            out += varint(len(short))
            for keyword in short:
                out += text(keyword)
        out += varint(len(rules))
        for score, feature, at in rules:
            out += varint(max(0, score))
            out += text(feature)
            out += varint(at)
    return bytes(out)


def main():
    made = 0
    for name in sorted(os.listdir(FROM)):
        if not name.endswith(".json.gz"):
            continue
        lang = name[: -len(".json.gz")]
        built = build(lang)
        if built is None:
            continue
        path = os.path.join(INTO, f"{lang}.hg")
        with open(path, "wb") as f:
            f.write(built)
        print(f"  {lang}: {len(built) // 1024} KB")
        made += 1
    print(f"{made} classifiers written to {os.path.relpath(INTO, ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
