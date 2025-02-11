#!/bin/bash
echo "generating ipa_dict..."
jq -s -c -f ./get_langs.jq raw-wiktextract-data.jsonl  > ipa_dict.json # requires ~ 185GB of RAM

folder=languages_without_audio

echo "creating language jsons"
mkdir -p ./$folder
jq -r 'to_entries[] | "\(.key)\t\(.value | @json)"' ipa_dict.json |
while IFS=$'\t' read -r key value; do
    echo "$value" | jq . > "./$folder/${key}.json"
done
