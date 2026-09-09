#!/usr/bin/env bash
# The data half of every accent the interface offers, as packs.
#
# An accent is two things. The rules in core/src/accent.rs reach every word but only carry the
# shifts that hold across a whole vocabulary; the rest is word by word, and that is data. The
# overlays in assets/dictionaries/accents are what Wiktionary tags for a region, and this turns
# each one into a pack the reader's host serves beside the language packs.
#
#   scripts/build-accent-packs.sh            build into .output/packs
#   scripts/build-accent-packs.sh <dir>      build somewhere else
#
# The pack is named for the accent as the interface names it, because that is the name the
# core is asked for and the name the host is asked for. Where the overlay file is named
# differently - en-scotland for the accent called en-gb-scotland - the pack still carries the
# accent's name, since the file name is nobody's but the dump's.
#
# What comes out goes to the host that serves the dictionaries. Which host that is is the
# reader's own setting and is nowhere in this repository, so uploading is a separate step.
set -euo pipefail

cd "$(dirname "$0")/.."
out="${1:-.output/packs}"
mkdir -p "$out"

# Accent as the interface names it, and the overlay it is built from.
overlays=$(python3 - <<'PY'
import json, os

# The dump's name for an accent, where it is not ours. Wiktionary tags Scottish English as
# Scotland and Central Vietnamese by the city it is spoken in.
ALSO_CALLED = {"en-gb-scotland": "en-scotland", "vi-vn-x-central": "vi-hue"}

accents = json.load(open("data/accents.json"))["accents"]
for lang, listed in accents.items():
    for accent in listed:
        name = accent["id"]
        stem = ALSO_CALLED.get(name, name)
        path = f"assets/dictionaries/accents/{lang}.{stem}.json.gz"
        if os.path.exists(path):
            print(name, path)
PY
)

if [[ -z "$overlays" ]]; then
  echo "no overlays in assets/dictionaries/accents; run pnpm fetch:dict first" >&2
  exit 1
fi

while read -r name overlay; do
  echo "== $name from $overlay"
  cargo run -q --release -p packbuild --manifest-path core/Cargo.toml -- \
    ipa "$name" "$overlay" "$out/$name.pack"
done <<< "$overlays"

echo
echo "the packs are in $out; they go to the host that serves the dictionaries"
ls -la "$out"
