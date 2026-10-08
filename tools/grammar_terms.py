#!/usr/bin/env python3
"""The grammatical terms a form is labelled with, what each one is, and Wiktionary's words for it.

A form in a pack carries the tags the dump gave its row in the entry's table, joined by spaces:
Spanish "anduvo" is "indicative preterite singular third-person". The card names those terms,
and a reader who points at one is learning it, so the explanation is Wiktionary's own: the
definition its glossary (Appendix:Glossary on en.wiktionary.org) gives the term, or where the
glossary has none, the grammar sense of the term's own English entry. Both are CC BY-SA, like
the rest of what the packs are built from, and nothing here is written by us: a term Wiktionary
does not explain is kept with no text rather than with one we made up.

  uv run --with orjson python tools/grammar_terms.py

It reads every dump in .cache/kaikki (the files tools/split_kaikki.py writes) and writes
data/grammar.json. Pages fetched from Wiktionary and GitHub, and the tag counts, are kept in
.cache/grammar, so a second run reads the network only for what it lacks; delete that
directory to fetch everything again.

Which tags count. A tag is counted once for every row of an entry's forms it is on, with the
rows the pack builder drops left out (core/packbuild/src/lib.rs: a table header, an auxiliary,
a conjugation class, a form with a pronoun attached). A tag on fewer than MIN_ROWS rows across
every language is a stray from the extractor rather than a term a reader will meet, and is left
out of the file.

What each tag is. wiktextract, which made the dump, files every tag it can emit under a
category of its own (valid_tags in src/wiktextract/tags.py), and those categories are what this
starts from: its "mood", "tense", "case" and so on are taken as they are, its "referent"
(definite, indefinite) is definiteness and its "non-finite" is nonfinite. Where its filing is
not what the tag means in the forms a reader sees, OVERRIDE says what it is instead and why,
each decided by reading rows the tag is on. A tag that names no grammatical value (a region, a
usage label, a note on the row) goes into "other" with a line saying what it is: the line
OTHER_REASON or OVERRIDE gives it, or else the one for its wiktextract category.

Which text. The glossary is one long list: a term on a line beginning with ";", with the ids it
can be linked by in {{anchor|...}}, and its definition on the lines beginning with ":" below it.
A value is looked for in the glossary under its most specific name first ("partitive case"
before "partitive", whose glossary entry is the partitive of a quantity), and a definition that
only says "See X" is followed to X. Where the glossary has no entry, the term's own English
entry is read, again the specific name first ("illative case"), and of the senses labelled
grammar or linguistics under a Noun or Adjective heading the first that names the value's
category is taken, or the first at all. A case is only ever explained by a text that calls it
a case, since many case names are ordinary words with another grammar sense. What is kept is
the first sentence, with the wiki markup taken out and the words and punctuation left as
Wiktionary has them. Each text records the page and the anchor or heading it came from.

The order. A grammar table lists the values of a category in an order of its own, and the card
lists them the same way: ORDER is that order, read from Wiktionary's conjugation and declension
tables (Spanish and French verbs, German, Latin, Russian, Polish, Czech and Finnish nouns,
German and Swedish adjectives). Cases come nominative, genitive, dative, accusative, as the
German, Latin and Slavic tables print them, then the Slavic cases, then the Finnish local cases
in the order the Finnish table gives them. A value the order does not name comes after the
ones it does, the commonest first.
"""
import ast
import gzip
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter
from concurrent.futures import ProcessPoolExecutor

# A parser in C: the dumps are gigabytes of JSON and the standard library's takes far longer
# over them.
import orjson

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DUMPS = os.path.join(ROOT, ".cache", "kaikki")
CACHE = os.path.join(ROOT, ".cache", "grammar")
OUT = os.path.join(ROOT, "data", "grammar.json")

TAGS_PY = "https://raw.githubusercontent.com/tatuylonen/wiktextract/master/src/wiktextract/tags.py"
API = "https://en.wiktionary.org/w/api.php"
GLOSSARY = "Appendix:Glossary"
# Wikimedia asks every client to say who it is.
AGENT = "phonetix-grammar-terms/1 (https://github.com/nanbiz/phonetix)"

MIN_ROWS = 20

# The rows the pack builder drops and the tags it leaves out of a label (NOT_FORMS and the
# filter after it in core/packbuild/src/lib.rs).
NOT_FORMS = {
    "table-tags",
    "inflection-template",
    "auxiliary",
    "class",
    "multiword-construction",
    "classifier",
}
NOT_IN_LABEL = {"canonical", "inflection-template"}

# wiktextract's category for a tag, as the category the card names.
FROM_KAIKKI = {
    "mood": "mood",
    "tense": "tense",
    "aspect": "aspect",
    "person": "person",
    "number": "number",
    "case": "case",
    "gender": "gender",
    "referent": "definiteness",
    "degree": "degree",
    "voice": "voice",
    "non-finite": "nonfinite",
}

# wiktextract files usage labels under "register" too; these are the ones that are the
# grammatical politeness of a form.
REGISTER = {"formal", "informal", "polite", "familiar", "honorific", "deferential", "majestic"}

# A tag whose meaning in the forms is not what wiktextract's category says. A category, or
# "other" with the line that says what the tag is.
OVERRIDE = {
    # German adjective declension, filed as misc. Russian uses "mixed" on two rows for a word
    # mixed from two languages.
    "strong": "declension",
    "weak": "declension",
    "mixed": "declension",
    # The Russian case, filed as misc. Dutch puts it on 142 rows of a preposition's other form.
    "prepositional": "case",
    # The Hindi and Urdu direct and oblique cases of a noun, filed under aspect.
    "direct": "case",
    "indirect": "case",
    # The Hungarian, Estonian and Basque case ("until"), filed under aspect.
    "terminative": "case",
    # The Welsh and Indonesian degree of an adjective ("as big as"), filed under case.
    "equative": "degree",
    # Moods and cases wiktextract files as misc: the Turkish, Albanian and Georgian optative,
    # the Latvian debitive, the Basque and Georgian ergative, the Hungarian causal-final.
    "optative": "mood",
    "debitive": "mood",
    "ergative": "case",
    "causal-final": "case",
    # The Armenian and Slovene converb, a verb form used like an adverb.
    "converb": "nonfinite",
    # The Arabic singulative of a collective noun, and the Bulgarian and Macedonian count form
    # a masculine noun takes after a numeral.
    "singulative": "number",
    "count-form": "number",
    # The Arabic construct state, which its tables list beside the indefinite and definite.
    "construct": "definiteness",
    # The Tamil gender of a noun for a person of either sex.
    "epicene": "gender",
    # Polish and Russian split the masculine by animacy, and Polish the plural into virile and
    # nonvirile, the way their declension tables head their columns.
    "animate": "gender",
    "inanimate": "gender",
    "virile": "gender",
    "nonvirile": "gender",
    # French and Italian "past historic", tagged "historic past"; and the Polish participles,
    # which are anterior or contemporary to the main verb.
    "historic": "tense",
    "contemporary": "tense",
    "anterior": "tense",
    "common": ("other", "a common spelling or form; the common gender is tagged common-gender"),
    "singular-possessive": ("other", "the number of the possessor in a Finnish possessive suffix, not of the word"),
    "plural-possessive": ("other", "the number of the possessor in a Finnish possessive suffix, not of the word"),
    "second-person-semantically": ("other", "a third-person form that addresses the listener (Spanish usted); its person is its third-person tag"),
    "vos-form": ("other", "the Spanish voseo variant of a second-person form"),
    "imperfect-se": ("other", "the -se variant of a Spanish form already tagged imperfect subjunctive"),
    "predicative": ("other", "an adjective form used after a verb; wiktextract files it under case, which it is not"),
    "attributive": ("other", "an adjective form used before a noun; wiktextract files it under case, which it is not"),
    "direct-object": ("other", "the role an attached pronoun fills, not a case of the word"),
    "indirect-object": ("other", "the role an attached pronoun fills, not a case of the word"),
    "relative": ("other", "a relative verb form (Swahili, Persian, Esperanto), filed under person"),
    "dependent": ("other", "the Irish verb form used after certain particles, filed under tense"),
    "causative": ("other", "a derived verb that makes someone do the action, filed under aspect"),
    "inversion": ("other", "a Georgian verb whose experiencer is marked like an object, filed under case"),
    "proximal": ("other", "how near a demonstrative points"),
    "medial": ("other", "how near a demonstrative points"),
    "distal": ("other", "how near a demonstrative points"),
    "by-personal-gender": ("other", "a form chosen by the gender of the person meant"),
    "person": ("other", "a noun or numeral form for people"),
    "negative": ("other", "the negated form of a verb"),
    "sigmatic": ("other", "a Latin form built with an added s, filed under mood"),
}

# The usual reason a tag in one of wiktextract's other categories is no grammatical value.
OTHER_BY_KAIKKI = {
    "dialect": "a region, variety or period the form belongs to",
    "register": "a usage label",
    "misc": "filed by wiktextract as misc, and no value of the categories here",
    "detail": "a detail of how the form is written",
    "script": "the script the form is written in",
    "pos": "the part of speech of the form",
    "possession": "a possessive form or suffix",
    "object": "the person or number of an object marked on the verb, not of the word",
    "polarity": "the negated or affirmative form",
    "category": "a class of noun",
    "transitivity": "transitivity of a verb",
    "class": "an inflection class",
    "trigger": "what the form triggers in the next word",
    "gradation": "consonant gradation",
    "derivation": "a word derived from this one",
    "mod": "a shortened or altered form",
    "pragmatic": "how the form is stressed or used",
    "phonetic": "how the form sounds",
    "lexical": "how the form is spelled",
    "with": "what the form goes with",
    "order": "where the form stands",
    "error": "the extractor could not read the row",
    "unknown": "not a tag wiktextract knows",
}

# A line for the tags this task met by name, where the category's line says too little.
OTHER_REASON = {
    "table-tags": "a table header row, which the pack builder drops",
    "inflection-template": "the template a table was made with, which the pack builder drops",
    "canonical": "the headword as printed, which the pack builder leaves out of the label",
    "romanization": "the form written in Latin letters",
    "combined-form": "the word with a pronoun attached, which the pack builder drops",
    "includes-article": "the form printed with its article",
    "without-article": "the form printed without an article",
    "adjectival": "a participle used as an adjective",
    "adverbial": "a participle used as an adverb",
    "subordinate-clause": "the order of a separable verb in a subordinate clause",
    "main-clause": "the order of a separable verb in a main clause",
    "short-form": "the short form of an adjective or participle",
    "long-form": "the long form of a participle",
    "personal": "the personal infinitive, or a personal pronoun",
    "alternative": "another spelling of the same form",
    "rare": "a rarely met form",
    "archaic": "an archaic form",
    "dated": "a dated form",
    "obsolete": "an obsolete form",
    "diminutive": "a diminutive derived from the word",
    "augmentative": "an augmentative derived from the word",
    "reflexive": "a reflexive form",
}

# Where the glossary files a value under another name than its tag.
GLOSSARY_NAME = {
    "historic": ["past historic"],
    "strong": ["strong declension"],
    "weak": ["weak declension"],
    "mixed": ["mixed declension"],
    "common-gender": ["common gender"],
    "degree": ["degrees of comparison"],
    "construct": ["construct state"],
}

# The glossary id a value's category adds to it, so "partitive case" is found before
# "partitive".
SUFFIX = {
    "case": "case",
    "mood": "mood",
    "tense": "tense",
    "voice": "voice",
    "aspect": "aspect",
    "number": "number",
    "gender": "gender",
    "declension": "declension",
}

CATEGORIES = [
    "mood", "tense", "aspect", "person", "number", "case", "gender", "definiteness",
    "declension", "degree", "voice", "register", "nonfinite",
]

ORDER = {
    "mood": ["indicative", "conditional", "subjunctive", "imperative", "potential"],
    "tense": [
        "present", "imperfect", "preterite", "historic", "aorist", "past", "perfect",
        "pluperfect", "future",
    ],
    "aspect": ["imperfective", "perfective"],
    "person": ["first-person", "second-person", "third-person", "fourth-person", "impersonal"],
    "number": ["singular", "dual", "plural"],
    "case": [
        "nominative", "genitive", "dative", "accusative", "instrumental", "prepositional",
        "locative", "vocative", "partitive", "inessive", "elative", "illative", "adessive",
        "ablative", "allative", "essive", "translative", "instructive", "abessive",
        "comitative",
    ],
    "gender": [
        "masculine", "feminine", "neuter", "common-gender", "animate", "inanimate", "virile",
        "nonvirile",
    ],
    "definiteness": ["indefinite", "definite"],
    "declension": ["strong", "weak", "mixed"],
    "degree": ["positive", "comparative", "superlative"],
    "voice": ["active", "passive"],
    "register": ["informal", "formal", "polite"],
    "nonfinite": ["infinitive", "gerund", "participle", "supine"],
}


# Fetching.


def fetch(url, name):
    """A page, from the cache or the network."""
    path = os.path.join(CACHE, name)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as cached:
            return cached.read()
    request = urllib.request.Request(url, headers={"User-Agent": AGENT})
    with urllib.request.urlopen(request, timeout=60) as answer:
        text = answer.read().decode("utf-8")
    os.makedirs(CACHE, exist_ok=True)
    with open(path, "w", encoding="utf-8") as cached:
        cached.write(text)
    # Wikimedia's API etiquette: one request at a time, not in a burst.
    time.sleep(0.2)
    return text


def wikitext(page):
    """A Wiktionary page's wikitext, or None where there is no such page."""
    query = urllib.parse.urlencode(
        {"action": "parse", "page": page, "prop": "wikitext", "format": "json", "formatversion": 2}
    )
    safe = re.sub(r"[^\w.-]", "_", page)
    answer = json.loads(fetch(f"{API}?{query}", f"page-{safe}.json"))
    return answer.get("parse", {}).get("wikitext")


def kaikki_categories():
    """wiktextract's category for every tag it can emit, read from its source."""
    source = fetch(TAGS_PY, "tags.py")
    found = {}
    # Read as data rather than run: the dictionaries are literals.
    for node in ast.parse(source).body:
        target = node.targets[0] if isinstance(node, ast.Assign) else getattr(node, "target", None)
        if getattr(target, "id", None) in ("valid_tags", "uppercase_tags"):
            found[target.id] = ast.literal_eval(node.value)
    categories = dict(found["valid_tags"])
    # A region or variety, written capitalised ("Brazil", "Ekavian").
    for tag in found["uppercase_tags"]:
        categories.setdefault(tag.replace(" ", "-"), "dialect")
    return categories


# Counting.


def count_dump(path):
    tally = Counter()
    with gzip.open(path, "rb") as dump:
        for line in dump:
            if b'"forms"' not in line:
                continue
            entry = orjson.loads(line)
            word = entry.get("word")
            for form in entry.get("forms") or ():
                spelling = (form.get("form") or "").strip()
                if not spelling or spelling == word or spelling == "-":
                    continue
                tags = form.get("tags") or ()
                if any(tag in NOT_FORMS or tag == "combined-form" for tag in tags):
                    continue
                for tag in tags:
                    if tag not in NOT_IN_LABEL:
                        tally[tag] += 1
    return tally


def tag_counts():
    """Rows per tag across every dump, from the cache while the dumps are unchanged."""
    dumps = sorted(name for name in os.listdir(DUMPS) if name.endswith(".jsonl.gz"))
    stamp = {name: os.stat(os.path.join(DUMPS, name)).st_mtime_ns for name in dumps}
    path = os.path.join(CACHE, "counts.json")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as cached:
            saved = json.load(cached)
        if saved["stamp"] == stamp:
            return Counter(saved["counts"])
    total = Counter()
    with ProcessPoolExecutor(min(8, os.cpu_count() or 1)) as pool:
        for name, tally in zip(dumps, pool.map(count_dump, [os.path.join(DUMPS, d) for d in dumps])):
            print(f"  {name}: {sum(tally.values())} tags", file=sys.stderr)
            total.update(tally)
    os.makedirs(CACHE, exist_ok=True)
    with open(path, "w", encoding="utf-8") as cached:
        json.dump({"stamp": stamp, "counts": total}, cached)
    return total


# Wiki markup to plain text.


def template(inner):
    """What a template shows as text, for the ones a definition uses."""
    parts = [part.strip() for part in inner.split("|")]
    name, args = parts[0].lower(), [p for p in parts[1:] if "=" not in p]
    named = dict(p.split("=", 1) for p in parts[1:] if "=" in p)
    if name in ("m", "l", "l-lite", "m-lite", "mention", "link"):
        # {{m|lang|term|alt|gloss}}
        shown = (args[2] if len(args) > 2 and args[2] else args[1]) if len(args) > 1 else ""
        gloss = named.get("t") or named.get("gloss") or (args[3] if len(args) > 3 else "")
        return f"{shown} (“{gloss}”)" if gloss else shown
    if name in ("w", "wp", "lg", "nobold", "smallcaps", "lang", "term"):
        return args[-1] if args else ""
    if name in ("gloss", "q", "qualifier", "i", "qual"):
        return f"({', '.join(args)})"
    if name in ("...", "…"):
        return "..."
    # Markers that show nothing in a definition: anchors, gender letters, labels, examples.
    return ""


def plain(text):
    """Wikitext as the words a reader of the rendered page sees."""
    text = re.sub(r"<!--.*?-->", "", text, flags=re.DOTALL)
    text = re.sub(r"<ref[^>]*/>|<ref.*?</ref>", "", text, flags=re.DOTALL)
    # Innermost templates first, so a template inside another is read before its parent.
    while True:
        changed = re.sub(r"\{\{([^{}]*)\}\}", lambda m: template(m.group(1)), text)
        if changed == text:
            break
        text = changed
    text = re.sub(r"\[\[([^\]|]*)\|([^\]]*)\]\]", r"\2", text)
    text = re.sub(r"\[\[([^\]]*)\]\]", lambda m: m.group(1).split("#")[0], text)
    text = re.sub(r"'{2,}", "", text)
    text = re.sub(r"<[^>]+>", "", text)
    text = text.replace("&nbsp;", " ")
    return re.sub(r"\s+", " ", text).strip()


def first_sentence(text):
    """Up to the first full stop that ends a sentence, past "e.g." and its kind.

    A glossary definition can open by repeating its term in quotes, "Locative". A, and that
    sentence says nothing; the one after it is the definition.
    """
    text = re.sub(r'^"[^"]{1,40}"\.\s+(?=[A-Z])', "", text)
    for match in re.finditer(r"\.(?=\s+[A-Z\"“(])|\.$", text):
        before = text[: match.start()].rsplit(" ", 1)[-1].lower().lstrip("(\"'\u201c")
        if before in ("e.g", "i.e", "etc", "cf", "vs", "lit", "esp"):
            continue
        return text[: match.end()].strip()
    return text.strip()


# The glossary.


def glossary():
    """Every glossary entry by each id it can be linked by: its headword and its anchors.

    Two entries can share an id: "construct state" is an anchor of "annexed state", the Berber
    one, as well as the headword of the Arabic one. The entry whose headword it is wins, and
    between anchors the first entry does.
    """
    entries, rank = {}, {}
    term, lines = None, []

    def keep():
        if term is None:
            return
        ids = re.findall(r"\{\{anchor\|([^}]*)\}\}", term)
        anchors = [name.strip() for group in ids for name in group.split("|")]
        head = plain(term.split(":", 1)[0] if ":" in term and not ids else term)
        heads = [part.strip() for part in head.split(",")]
        entry = {"lines": list(lines), "anchors": anchors}
        for weight, names in ((0, heads), (1, anchors)):
            for name in filter(None, (name.lower() for name in names)):
                if rank.get(name, 2) > weight:
                    entries[name], rank[name] = entry, weight

    for line in wikitext(GLOSSARY).splitlines():
        if line.startswith(";"):
            keep()
            term, lines = line[1:], []
            # A definition on the term's own line, after the headword: "; [[perfect]]: The".
            inline = re.match(r"(.*?\]\]|.*?\}\})\s*:\s*(.+)$", term)
            if inline and not re.search(r"\{\{[^}]*$", inline.group(1)):
                term, lines = inline.group(1), [inline.group(2)]
        elif line.startswith(":") and term is not None:
            lines.append(line.lstrip(":").strip())
        elif line.startswith("="):
            keep()
            term, lines = None, []
    keep()
    return entries


def from_glossary(entries, names, hops=3):
    """The first sentence of the glossary's definition under the first of these ids it has."""
    for name in names:
        entry = entries.get(name.lower())
        if not entry:
            continue
        for line in entry["lines"]:
            # "See X." is a pointer to the entry that says it.
            pointer = re.match(r"^''See '''\{\{lg\|([^}|]*)\}\}'''\.''\s*$", line)
            if pointer and hops:
                return from_glossary(entries, [pointer.group(1)], hops - 1)
            if re.match(r"^(''|)(See also|Contrast|Compare|Synonym|Antonym)", line):
                continue
            text = first_sentence(plain(line))
            if text:
                # The page has an id for each {{anchor}}, and none for a bare headword.
                ids = {anchor.lower(): anchor for anchor in entry["anchors"]}
                anchor = ids.get(name.lower()) or (entry["anchors"] or [""])[0]
                fragment = "#" + anchor.replace(" ", "_") if anchor else ""
                return {"text": text, "source": f"{GLOSSARY}{fragment}"}
    return None


def from_entry(term, category=None):
    """The grammar sense of the term's own English entry, under Noun first, then Adjective.

    Of the senses labelled grammar or linguistics, the first that names the value's category
    ("the illative case") is taken, since many of these words are ordinary adjectives too, and
    illative's first grammar sense is the illative of logic. A case is taken only from a sense
    that says it is a case.
    """
    page = wikitext(term)
    if not page:
        return None
    english = re.search(r"^==English==\s*$(.*?)(?=^==[^=]|\Z)", page, flags=re.MULTILINE | re.DOTALL)
    if not english:
        return None
    sections = re.split(r"^(={3,}[^=]+={3,})\s*$", english.group(1), flags=re.MULTILINE)
    senses = []
    for wanted in ("Noun", "Adjective"):
        for heading, body in zip(sections[1::2], sections[2::2]):
            if heading.strip("= ") != wanted:
                continue
            for line in body.splitlines():
                if not re.match(r"^#\s", line):
                    continue
                label = re.search(r"\{\{(?:lb|lbl|label)\|en\|([^}]*)\}\}", line)
                if not label or not re.search(r"\b(grammar|linguistics)\b", label.group(1)):
                    continue
                text = first_sentence(plain(line[1:]))
                if text:
                    senses.append({"text": text, "source": f"{term}#English ({wanted})"})
    naming = [sense for sense in senses if category and re.search(rf"\b{category}\b", sense["text"], re.IGNORECASE)]
    if naming or category == "case":
        return naming[0] if naming else None
    return senses[0] if senses else None


def explain(entries, tag, category):
    """Wiktionary's words for a value or a category: the glossary first, then the entries.

    In each, the most specific name is looked for first, "illative case" before "illative",
    because the bare word is often another sense. A case is explained only by a text that
    says it is one: the glossary's "agentive" is a noun for the doer of an action, and the
    Hindi agentive is a case, which Wiktionary's own words do not explain under that name.
    """
    spoken = tag.replace("-", " ")
    specific = list(GLOSSARY_NAME.get(tag, []))
    if category in SUFFIX:
        specific += [f"{spoken} {SUFFIX[category]}"]
    specific = list(dict.fromkeys(specific))
    bare = from_glossary(entries, list(dict.fromkeys([tag, spoken])))
    if bare and category == "case" and not re.search(r"\bcase\b", bare["text"], re.IGNORECASE):
        bare = None
    return (
        from_glossary(entries, specific)
        or bare
        or next(filter(None, (from_entry(name, category) for name in specific)), None)
        or from_entry(spoken, category)
    )


# Deciding.


def classify(tag, kaikki):
    """The category a tag names a value of, or ("other", why it names none)."""
    if tag in OVERRIDE:
        decided = OVERRIDE[tag]
        return decided if isinstance(decided, tuple) else (decided, None)
    filed = kaikki.get(tag, "unknown")
    if filed == "register" and tag in REGISTER:
        return "register", None
    if filed in FROM_KAIKKI:
        return FROM_KAIKKI[filed], None
    return "other", OTHER_REASON.get(tag) or OTHER_BY_KAIKKI.get(filed, OTHER_BY_KAIKKI["misc"])


def main():
    kaikki = kaikki_categories()
    counts = tag_counts()
    entries = glossary()

    values, other = {}, {}
    for tag, rows in counts.most_common():
        if rows < MIN_ROWS:
            continue
        category, reason = classify(tag, kaikki)
        if category == "other":
            other[tag] = {"reason": reason, "rows": rows}
            continue
        found = explain(entries, tag, category)
        values[tag] = {
            "category": category,
            "text": found and found["text"],
            "source": found and found["source"],
            "rows": rows,
        }

    categories = {}
    for name in CATEGORIES:
        found = explain(entries, name, None)
        categories[name] = {
            "name": name,
            "text": found and found["text"],
            "source": found and found["source"],
        }

    order = {}
    for name in CATEGORIES:
        members = [tag for tag, value in values.items() if value["category"] == name]
        listed = [tag for tag in ORDER.get(name, []) if tag in members]
        # values is in order of rows already, so the rest come commonest first.
        order[name] = listed + [tag for tag in members if tag not in listed]

    result = {
        "note": (
            "Grammatical terms a form in a pack is labelled with, and Wiktionary's definitions "
            "of them (en.wiktionary.org, Appendix:Glossary and the terms' own entries, CC BY-SA "
            "4.0). Written by tools/grammar_terms.py; text is null where Wiktionary has none."
        ),
        "categories": categories,
        "values": values,
        "order": order,
        "other": other,
    }
    with open(OUT, "w", encoding="utf-8") as out:
        json.dump(result, out, ensure_ascii=False, indent=2)
        out.write("\n")

    missing = [tag for tag, value in values.items() if not value["text"]]
    print(f"{len(values)} values, {len(missing)} without text: {' '.join(missing)}", file=sys.stderr)
    bare = [name for name, value in categories.items() if not value["text"]]
    print(f"{len(categories)} categories, without text: {' '.join(bare) or 'none'}", file=sys.stderr)
    print(f"{len(other)} other tags", file=sys.stderr)


if __name__ == "__main__":
    main()
