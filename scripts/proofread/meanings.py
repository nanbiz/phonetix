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
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

# A sentence per language and what its words have to be drawn as. Only words whose meaning is
# not in doubt: where a spelling is two words and the sentence decides between them, the check
# is of the one the sentence means.
CASES = {
    "es": ("El perro corre por el camino del banco. La casa es grande y los niños juegan en el parque.",
           {"El": "the", "perro": "dog", "camino": "way, route", "del": "of the, from the",
            "casa": "house", "es": "to be", "y": "and", "los": "the", "parque": "park"}),
    "de": ("Er hat die ganze Nacht gearbeitet. Der Hund läuft auf dem Weg zur Bank. Das Haus ist groß.",
           {"Er": "he", "hat": "to have", "die": "the", "Nacht": "night", "Der": "the",
            "Hund": "dog, hound", "dem": "the", "Weg": "path, trail, track", "zur": "to the",
            "Das": "the", "Haus": "house", "ist": "to be"}),
    "fr": ("Le chien court sur le chemin de la banque. Les enfants jouent dans le parc.",
           {"Le": "the", "chien": "dog", "le": "the", "la": "the", "banque": "bank",
            "Les": "the", "parc": "park"}),
    "it": ("Il cane corre sulla strada verso la banca. La casa è grande.",
           {"Il": "the", "cane": "dog, male dog", "corre": "to run", "sulla": "on the",
            "la": "the", "banca": "bank", "casa": "house", "è": "is"}),
    "pt": ("O cão corre pelo caminho do banco. As crianças brincam no parque e a casa é grande.",
           {"O": "the", "cão": "dog", "pelo": "by the", "As": "the", "no": "in the, on the",
            "parque": "park", "a": "the"}),
}


def drawn(pack, lang, text):
    out = subprocess.run(
        ["cargo", "run", "-q", "--release", "--manifest-path", os.path.join(ROOT, "core/Cargo.toml"),
         "--example", "draw", "--", pack, lang, text],
        cwd=os.path.join(ROOT, "core"), capture_output=True, text=True, timeout=600,
    )
    if out.returncode != 0:
        raise SystemExit(f"the core would not read {lang}: {out.stderr[-400:]}")
    said = {}
    for line in out.stdout.splitlines():
        parts = line.strip().split(None, 1)
        if len(parts) == 2:
            said.setdefault(parts[0], parts[1].strip())
    return said


def main():
    packs = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, ".cache/release")
    failures = []
    for lang, (text, wanted) in CASES.items():
        pack = os.path.join(packs, f"{lang}.pack")
        if not os.path.exists(pack):
            failures.append(f"{lang}: no pack at {pack}")
            continue
        said = drawn(os.path.abspath(pack), lang, text)
        wrong = {word: (said.get(word, "nothing"), meant) for word, meant in wanted.items()
                 if said.get(word) != meant}
        right = len(wanted) - len(wrong)
        print(f"  {lang}: {right} of {len(wanted)}")
        for word, (got, meant) in wrong.items():
            failures.append(f"{lang} {word!r} drawn as {got!r}, which should be {meant!r}")
    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the published dictionaries draw what their words mean")


if __name__ == "__main__":
    main()
