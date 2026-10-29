#!/usr/bin/env python3
"""How often the core's word for a word of one language is a word the other language's own
Wiktionary gives for it.

A card on a Spanish word for a German reader goes Spanish to English gloss to German, and an
English gloss is where it goes wrong: "hacer" is "to do", and "do" was "ja". The German
Wiktionary describes Spanish words in German itself, so what it says "hacer" is - "tun,
machen" - is the answer a German reader expects, written by people. This asks the core about
the commonest words of the source language that edition describes and counts how often the
word it leads with is one of those.

  uv run tools/measure_pair.py es de [count]        default 1000 words

Needs the published packs in .cache/release, the edition's dump in .cache/editions and the
source language's frequency list in .cache/frequencies. Prints the score and writes every
miss to /tmp/measure-<source>-<target>.tsv.
"""
import gzip
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, ".cache")


def gold(source, target):
    """What the target edition says each source word is: its words, lowercased."""
    said = {}
    path = os.path.join(CACHE, "editions", f"{target}-extract.jsonl.gz")
    marker = f'"lang_code": "{source}"'
    with gzip.open(path, "rt") as f:
        for line in f:
            if marker not in line:
                continue
            entry = json.loads(line)
            if entry.get("lang_code") != source:
                continue
            words = said.setdefault(entry["word"], set())
            for sense in entry.get("senses", []):
                for gloss in sense.get("glosses", []):
                    # A form's note is not a meaning: "2. Person Singular ... des Verbs poder".
                    if re.search(r"\b(Person|Plural|Singular|Partizip|Form)\b", gloss):
                        continue
                    for part in re.split(r"[,;:]", re.sub(r"\([^)]*\)", "", gloss)):
                        part = part.strip().lower()
                        if part:
                            words.add(part)
                            words.update(part.split())
    return {word: words for word, words in said.items() if words}


def main():
    source, target = sys.argv[1], sys.argv[2]
    count = int(sys.argv[3]) if len(sys.argv) > 3 else 1000
    expected = gold(source, target)
    common = []
    with open(os.path.join(CACHE, "frequencies", f"{source}.txt")) as f:
        for line in f:
            word = line.split()[0]
            if word in expected and word.isalpha():
                common.append(word)
            if len(common) == count:
                break
    release = os.path.join(CACHE, "release")
    out = subprocess.run(
        ["cargo", "run", "-q", "--release", "--example", "card", "--",
         os.path.join(release, f"{source}.pack"), os.path.join(release, f"{target}.pack"),
         source, target, *common],
        cwd=os.path.join(ROOT, "core"), capture_output=True, text=True, timeout=3600)
    answered = {}
    for line in out.stdout.splitlines():
        found = re.match(r"\s*(\S+)\s+\w+ ipa=.* says=\[(.*?)\]", line)
        if found:
            says = re.findall(r'"([^"]*)"', found.group(2))
            answered.setdefault(found.group(1), says[0].lower() if says else "")
    right = 0
    misses = []
    for word in common:
        said = answered.get(word, "")
        if said and said in expected[word]:
            right += 1
        else:
            misses.append((word, said, ", ".join(sorted(expected[word]))[:120]))
    print(f"{source} -> {target}: {right} of {len(common)} ({100 * right / len(common):.1f}%)")
    with open(f"/tmp/measure-{source}-{target}.tsv", "w") as f:
        for miss in misses:
            f.write("\t".join(miss) + "\n")


if __name__ == "__main__":
    main()
