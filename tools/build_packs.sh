#!/usr/bin/env bash
# Build every meaning pack and the model listing, ready to publish.
#
#   tools/build_packs.sh [work dir]      default .cache
#
# 1. the English Wiktionary as kaikki.org extracts it, every language in one file (about 3 GB);
# 2. split into one file per language in a single pass;
# 3. one pack per language, with packbuild, and its manifest row beside it;
# 4. the listing of translation models, pinned to what Mozilla publishes now.
#
# Then `node scripts/publish-packs.mjs <work dir>/release` puts them where the app fetches.
set -euo pipefail
cd "$(dirname "$0")/.."
work="${1:-.cache}"
dump="$work/raw-wiktextract-data.jsonl.gz"
split="$work/kaikki"
out="$work/release"
mkdir -p "$split" "$out"

if [[ ! -s "$dump" ]]; then
  curl -fSL -C - -o "$dump" https://kaikki.org/dictionary/raw-wiktextract-data.jsonl.gz
fi
if [[ ! -f "$split/.done" ]]; then
  uv run --with orjson python tools/split_kaikki.py "$dump" "$split"
  touch "$split/.done"
fi

cargo build --release --manifest-path core/Cargo.toml -p packbuild
build=core/target/release/packbuild
for extract in "$split"/*.jsonl.gz; do
  lang="$(basename "$extract" .jsonl.gz)"
  if "$build" "$lang" "$extract" "$out/$lang.pack" > "$out/$lang.pack.json"; then
    printf '%s %s\n' "$lang" "$(head -c 200 "$out/$lang.pack.json")"
  else
    echo "$lang: no pack" >&2
    rm -f "$out/$lang.pack" "$out/$lang.pack.json"
  fi
done

uv run python tools/models_manifest.py "$out/models.json"
cp tools/ATTRIBUTION.md "$out/ATTRIBUTION.md"
