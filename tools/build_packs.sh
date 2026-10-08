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
  # The languages Wiktionary files under a code of their own, in a second pass that only
  # parses lines that could be one of them.
  uv run --with orjson python tools/split_kaikki.py "$dump" "$split" sh kmr nn
  touch "$split/.done"
fi

# A language the product names, built from the codes the dump files it under, where those
# are not its own: Serbo-Croatian is one heading for three languages, Norwegian is written two
# ways, and Kurdish here is Kurmanji.
declare -A filed=(
  [hr]="sh:sh" [sr]="sh:sh" [bs]="sh:sh"
  [no]="nb,nn,no:nb nn no" [nb]="nb,no:nb no"
  [ku]="kmr:kmr"
)

cargo build --release --manifest-path core/Cargo.toml -p packbuild
build=core/target/release/packbuild

# How often each word is met, from subtitles (FrequencyWords, OpenSubtitles 2018, CC BY-SA
# 4.0): what ranks "perro" above the poetic "can" when a reader asks for the word for "dog".
# Fetched once; a language the lists do not cover is built without one.
counts="$work/frequencies"
mkdir -p "$counts"
counted() {
  local lang="$1" code="$1"
  case "$lang" in nb) code=no ;; zh) code=zh_cn ;; esac
  if [[ ! -s "$counts/$lang.txt" ]]; then
    curl -fsSL -o "$counts/$lang.txt" \
      "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/$code/${code}_50k.txt" \
      || rm -f "$counts/$lang.txt"
  fi
  [[ -s "$counts/$lang.txt" ]] && printf -- '--frequencies=%s' "$counts/$lang.txt"
}

pack() {
  local lang="$1" extract="$2" codes="$3"
  if "$build" "$lang" "$extract" "$out/$lang.pack" $codes $(counted "$lang") > "$out/$lang.pack.json"; then
    printf '%s %s\n' "$lang" "$(head -c 200 "$out/$lang.pack.json")"
  else
    echo "$lang: no pack" >&2
    rm -f "$out/$lang.pack" "$out/$lang.pack.json"
  fi
}

for extract in "$split"/*.jsonl.gz; do
  lang="$(basename "$extract" .jsonl.gz)"
  # Built under the product's names below instead.
  case "$lang" in sh|kmr|nn|nb|no) continue ;; esac
  pack "$lang" "$extract" ""
done
for lang in "${!filed[@]}"; do
  codes="${filed[$lang]%%:*}"
  files=""
  for code in ${filed[$lang]#*:}; do files+=" $split/$code.jsonl.gz"; done
  # Gzip files joined end to end are one gzip stream to a reader, which is what packbuild
  # reads an extract as.
  cat $files > "$work/$lang.filed.jsonl.gz"
  pack "$lang" "$work/$lang.filed.jsonl.gz" "$codes"
  rm -f "$work/$lang.filed.jsonl.gz"
done

uv run python tools/models_manifest.py "$out/models.json"
cp tools/ATTRIBUTION.md "$out/ATTRIBUTION.md"
