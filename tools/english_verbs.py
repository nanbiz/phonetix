#!/usr/bin/env python3
"""The English verbs and nouns whose forms no rule makes, from the English Wiktionary.

A card says a form in the reader's language the way it is used on the page: Spanish "anduvo"
is "he walked", "fue" is "he went". The core makes the regular forms of an English verb by
rule (walks, walking, walked) and reads the rest from data/english-verbs.tsv, which this
writes: every verb whose third person, present participle, past or past participle is not
what the rule makes, as the English Wiktionary lists them (kaikki.org's extract), with forms
marked dialectal, obsolete, archaic, nonstandard or colloquial left out.

  uv run python tools/english_verbs.py [.cache/kaikki/en.jsonl.gz] [data/english-verbs.tsv]

Nouns the same way: data/english-nouns.tsv holds every noun whose plural is not what the rule
makes ("children", "mice"), so "Kinder" is "children" and not "childs".

The rule here is the one in core/src/inflect.rs; the two have to agree, since a verb the rule
gets right is left out of the file.
"""
import gzip
import json
import sys

UNUSUAL = {"dialectal", "obsolete", "archaic", "nonstandard", "colloquial", "rare", "informal",
           "Scotland", "US", "UK", "British", "American", "regional", "poetic", "humorous"}
VOWELS = set("aeiou")


def third(verb):
    if verb.endswith(("s", "x", "z", "ch", "sh")):
        return verb + "es"
    if verb.endswith("y") and len(verb) > 1 and verb[-2] not in VOWELS:
        return verb[:-1] + "ies"
    return verb + "s"


def doubles(verb):
    """One short syllable ending consonant-vowel-consonant doubles its last letter."""
    return (len(verb) <= 4 and len(verb) >= 3 and verb[-1] not in VOWELS and verb[-1] not in "wxy"
            and verb[-2] in VOWELS and verb[-3] not in VOWELS)


def ing(verb):
    if verb.endswith("ie"):
        return verb[:-2] + "ying"
    if verb.endswith("e") and not verb.endswith(("ee", "ye", "oe")):
        return verb[:-1] + "ing"
    if doubles(verb):
        return verb + verb[-1] + "ing"
    return verb + "ing"


def past(verb):
    if verb.endswith("e"):
        return verb + "d"
    if verb.endswith("y") and len(verb) > 1 and verb[-2] not in VOWELS:
        return verb[:-1] + "ied"
    if doubles(verb):
        return verb + verb[-1] + "ed"
    return verb + "ed"


def first(forms, wanted, unwanted=()):
    for spelling, tags in forms:
        tags = set(tags)
        if wanted <= tags and not tags & UNUSUAL and not tags & set(unwanted):
            return spelling
    return None


def main():
    source = sys.argv[1] if len(sys.argv) > 1 else ".cache/kaikki/en.jsonl.gz"
    into = sys.argv[2] if len(sys.argv) > 2 else "data/english-verbs.tsv"
    rows = {}
    plurals = {}
    with gzip.open(source, "rt") as lines:
        for line in lines:
            if '"pos": "noun"' in line:
                entry = json.loads(line)
                noun = entry.get("word", "")
                if entry.get("pos") == "noun" and noun.isalpha() and noun.islower() and noun not in plurals:
                    forms = [(f.get("form", ""), f.get("tags", [])) for f in entry.get("forms", [])]
                    plural = first(forms, {"plural"})
                    if plural and plural != third(noun):
                        plurals[noun] = plural
                continue
            if '"pos": "verb"' not in line:
                continue
            entry = json.loads(line)
            verb = entry.get("word", "")
            if entry.get("pos") != "verb" or not verb.isalpha() or not verb.islower() or verb in rows:
                continue
            forms = [(f.get("form", ""), f.get("tags", [])) for f in entry.get("forms", [])]
            said = (
                first(forms, {"present", "singular", "third-person"}),
                first(forms, {"participle", "present"}),
                first(forms, {"past"}, ("participle",)),
                first(forms, {"participle", "past"}),
            )
            if not all(said):
                continue
            made = (third(verb), ing(verb), past(verb), past(verb))
            if said != made:
                rows[verb] = said
    with open(into, "w") as out:
        out.write("# verb\tthird person\tpresent participle\tpast\tpast participle\n")
        for verb in sorted(rows):
            out.write("\t".join((verb,) + rows[verb]) + "\n")
    nouns = into.replace("verbs", "nouns")
    with open(nouns, "w") as out:
        out.write("# noun\tplural\n")
        for noun in sorted(plurals):
            out.write(f"{noun}\t{plurals[noun]}\n")
    print(f"{len(rows)} English verbs and {len(plurals)} nouns no rule makes, written to {into} and {nouns}")


if __name__ == "__main__":
    main()
