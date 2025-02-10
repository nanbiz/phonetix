#!/bin/bash
#wget https://kaikki.org/dictionary/raw-wiktextract-data.jsonl.gz
#unpigz raw-wiktextract-data.jsonl.gz

#jq -c --unbuffered 'select((has("form_of") | not) and has("sounds")) | {(.lang):{(.word): (.sounds | map(select(has("ipa"))))}}' ./raw-wiktextract-data.jsonl | jq -s '.' > ipa_dict.json
#jq -c --unbuffered 'select((has("form_of") | not) and has("sounds")) | {(.word): (.sounds | map(select(has("ipa"))))}' ./raw-wiktextract-data.jsonl | jq -s '.' > ipa_dict.json

jq -s -c -f ./get_langs.jq raw-wiktextract-data.jsonl  > ipa_dict.json # requires ~ 185GB of RAM

jq -r 'to_entries[] | "\(.key)\t\(.value | @json)"' ipa_dict.json |
while IFS=$'\t' read -r key value; do
    echo "$value" | jq . > "/languages_with_audio/${key}.json"
done
