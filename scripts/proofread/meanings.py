#!/usr/bin/env python3
"""What real dictionaries draw over real sentences.

Every test of the core builds its own small packs, with one tidy sense a word, and every one of
them passed while the published dictionaries drew the Spanish article as "masculine…", German
"die" as "CIA" and French "Le" as a surname. This reads sentences through the packs as they are
published and checks the words a reader would notice first - articles, pronouns, the commonest
nouns and verbs - against what they mean.

  uv run python scripts/proofread/meanings.py [pack dir]     default .cache/release

The packs are the published ones: tools/build_packs.sh writes them there, or they can be
downloaded from the release into the directory given.
"""
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

# A sentence per language and what its words have to be drawn as. Only words whose meaning is
# not in doubt: where a spelling is two words and the sentence decides between them, the check
# is of the one the sentence means.
CASES = {
    "es": ("El perro corre por el camino del banco. La casa es grande y los niños juegan en el parque.",
           {"perro": "dog", "camino": "way, route", "casa": "house", "es": "is", "parque": "park"}),
    "de": ("Er hat die ganze Nacht gearbeitet. Der Hund läuft auf dem Weg zur Bank. Das Haus ist groß. "
           "Meine Kinder spielen mit ihren Freunden.",
           {"hat": "has", "Nacht": "night", "Hund": "dog, hound", "Weg": "path, trail, track",
            "Haus": "house", "ist": "is"}),
    "fr": ("Le chien court sur le chemin de la banque. Les enfants jouent dans le parc.",
           {"chien": "dog", "banque": "bank", "parc": "park"}),
    "it": ("Il cane corre sulla strada verso la banca. La casa è grande.",
           {"cane": "dog, male dog", "corre": "runs", "banca": "bank", "casa": "house", "è": "is"}),
    "pt": ("O cão corre pelo caminho do banco. As crianças brincam no parque e a casa é grande.",
           {"cão": "dog", "parque": "park"}),
}

# The words of those sentences that hold them together: articles, prepositions, conjunctions,
# pronouns. None is ever drawn, because one translated by itself agrees with nothing around it;
# what each means is still the card's, asked the way the page asks it, lowercased where it
# starts a sentence and after the word before it, and has to be the word and not the grammar
# note it is filed under ("masculine singular definite article; the").
HELD = {
    "es": {"El": ("el", "the"), "del": ("camino:del", "of the, from the"), "y": ("grande:y", "and"),
           "los": ("y:los", "the")},
    "de": {"Er": ("er", "he"), "die": ("hat:die", "the"), "Der": ("der", "the"),
           "dem": ("auf:dem", "the"), "zur": ("weg:zur", "to the"), "Das": ("das", "the"),
           "Meine": ("meine", "my")},
    "fr": {"Le": ("le", "the"), "le": ("sur:le", "the"), "la": ("de:la", "the"),
           "Les": ("les", "the")},
    "it": {"Il": ("il", "the"), "sulla": ("corre:sulla", "on the"), "la": ("verso:la", "the")},
    "pt": {"O": ("o", "the"), "pelo": ("corre:pelo", "by the"), "As": ("as", "the"),
           "no": ("brincam:no", "in the, on the"), "a": ("e:a", "the")},
}

# And words the page alone leaves undecided, read again with the line as the engine translated
# it, which is what both hosts do: each reading's meaning is looked for in the translation, as
# the translation writes it - "est" is "third-person singular present indicative of être" in the
# dictionary and "is" in the sentence, "court" a form of "courir", "to run", which the sentence
# writes "runs". A form is drawn in its form: "is", "runs", "sit down" for "(I) sit down". A word already decided can still have several senses, and the line picks
# which is drawn: "banco" is a bank and a bench.
SETTLED = [
    ("fr", "La maison est grande et le chat court dans le parc.",
     "The house is big and the cat runs in the park.",
     {"est": "is", "court": "runs", "chat": "cat"}),
    ("fr", "Le chemin est court.", "The path is short.",
     {"est": "is", "court": "short"}),
    # And a word that is decided but has several senses, drawn in the one the line is about.
    ("es", "Me siento en el banco del parque.", "I sit on the park bench.",
     {"banco": "bench", "siento": "sit down"}),
    ("es", "El banco cerró mi cuenta.", "The bank closed my account.",
     {"banco": "bank", "cuenta": "account"}),
    ("de", "Er sitzt auf der Bank.", "He sits on the bench.", {"Bank": "bench"}),
    ("de", "Die Bank ist geschlossen.", "The bank is closed.", {"Bank": "bank"}),
]


def drawn(pack, lang, text, translated=None):
    out = subprocess.run(
        ["cargo", "run", "-q", "--release", "--manifest-path", os.path.join(ROOT, "core/Cargo.toml"),
         "--example", "draw", "--", pack, lang, text] + ([translated] if translated else []),
        cwd=os.path.join(ROOT, "core"), capture_output=True, text=True, timeout=600,
    )
    if out.returncode != 0:
        raise SystemExit(f"the core would not read {lang}: {out.stderr[-400:]}")
    said = {}
    for line in out.stdout.splitlines():
        # The word, what is drawn over it, and the state the core left it in, last.
        parts = line.strip().split(None, 1)
        if len(parts) == 2:
            gloss, _, state = parts[1].rpartition(" ")
            said.setdefault(parts[0], (gloss.strip(), state))
    return said


def led(pack, english, lang, asked):
    """What the card leads with for each word asked, "before:word" giving the word before it."""
    out = subprocess.run(
        ["cargo", "run", "-q", "--release", "--manifest-path", os.path.join(ROOT, "core/Cargo.toml"),
         "--example", "card", "--", pack, english, lang, "en"] + asked,
        cwd=os.path.join(ROOT, "core"), capture_output=True, text=True, timeout=600,
    )
    if out.returncode != 0:
        raise SystemExit(f"the core would not read {lang}: {out.stderr[-400:]}")
    leads = []
    for line in out.stdout.splitlines():
        if line.startswith("            "):
            continue
        match = re.search(r'lead=(?:Some\((".*?")\)|None)', line)
        leads.append(json.loads(match.group(1)) if match and match.group(1) else None)
    return leads


def main():
    packs = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, ".cache/release")
    failures = []
    for lang, (text, wanted) in CASES.items():
        pack = os.path.join(packs, f"{lang}.pack")
        if not os.path.exists(pack):
            failures.append(f"{lang}: no pack at {pack}")
            continue
        said = {word: gloss for word, (gloss, _) in drawn(os.path.abspath(pack), lang, text).items()}
        wrong = {word: (said.get(word, "nothing"), meant) for word, meant in wanted.items()
                 if said.get(word) != meant}
        right = len(wanted) - len(wrong)
        print(f"  {lang}: {right} of {len(wanted)}")
        for word, (got, meant) in wrong.items():
            failures.append(f"{lang} {word!r} drawn as {got!r}, which should be {meant!r}")
        held = HELD.get(lang, {})
        english = os.path.abspath(os.path.join(packs, "en.pack"))
        leads = led(os.path.abspath(pack), english, lang, [asked for asked, _ in held.values()])
        for (word, (asked, meant)), lead in zip(held.items(), leads):
            if word in said:
                failures.append(f"{lang} {word!r} drawn as {said[word]!r}, which holds the "
                                "sentence together and is never drawn")
            if lead != meant:
                failures.append(f"{lang} {word!r}: the card leads with {lead!r}, not {meant!r}")
        print(f"  {lang}: {len(held)} held, on the card as {leads}")
    for lang, text, translated, wanted in SETTLED:
        pack = os.path.join(packs, f"{lang}.pack")
        if not os.path.exists(pack):
            failures.append(f"{lang}: no pack at {pack}")
            continue
        said = drawn(os.path.abspath(pack), lang, text, translated)
        wrong = {word: said.get(word, ("nothing", "")) for word, meant in wanted.items()
                 if said.get(word, ("", ""))[0] != meant or said[word][1] == "Homograph"}
        print(f"  {lang}, with the line translated: {len(wanted) - len(wrong)} of {len(wanted)}")
        for word, (got, state) in wrong.items():
            failures.append(f"{lang} {word!r} in {text!r} drawn as {got!r} ({state}), "
                            f"which should be {wanted[word]!r}")
    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the published dictionaries draw what their words mean")


if __name__ == "__main__":
    main()
