[.[] | select(
  has("sounds") and 
  (.sounds != null) and 
  (.sounds | length > 0)
)]
| group_by(.lang_code)
| map(
  .[0].lang_code as $lang
  | { ($lang): (
      group_by(.word) | map({
        (.[0].word): (
          group_by(.pos) # if pos is not there (VERY rare) we don't have the word
          | map({
              (.[0].pos) : {
                # currently, I can only see ipa objects and audio objects separated from each other in the database
                # dialect tags seem to only exist on ipas
                # in the future, maybe they will be merged, then this logic would need to be rewritten, or we would waste stuff
                # documented here: https://github.com/tatuylonen/wiktextract?tab=readme-ov-file#pronunciation
                "ipas" : (map(.sounds[] | select(has("ipa"))) | unique),
                "audios" : (map(.sounds[] | select(has("ogg_url") or has("mp3_url"))) | unique),
                "rest" : (map(.sounds[] | select((has("ogg_url") or has("mp3_url") or has("ipa")) | not)) | unique),
              } | with_entries(select(.value | length > 0))
            }) 
            | add | with_entries(select(.value | length > 0))
        )
      })
      | add | with_entries(select(.value | length > 0))
    ) 
  })
| add | with_entries(select(.value | length > 0))

