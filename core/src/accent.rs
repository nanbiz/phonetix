//! An accent whose difference from the standard is a rule, applied to every word.
//!
//! An accent is two separable things. Some differ word by word, and those come from tagged
//! data: a pack of their own, laid over the standard one. Others differ by a shift that holds
//! across the whole vocabulary, and those are here, because a rule reaches every word
//! including the ones no dictionary knows and no data set would ever list.
//!
//! Which is which is not a preference. Wiktionary tags almost no Spanish word for accent,
//! while seseo and yeísmo hold for all of them; it tags a few hundred German words for
//! Switzerland, where no Swiss pronunciation lexicon exists at all.
//!
//! An accent can have both, and American does: twenty-seven thousand words of data over a
//! vocabulary of a hundred and ninety thousand. The cascade decides which speaks for a word -
//! the pack where it holds the word, these rules where it does not - so a reading the data
//! spelled out is never shifted a second time.

/// One shift: what to replace, what with, and the spelling it is limited to.
struct Rule {
    from: &'static str,
    to: &'static str,
    /// Only for words ending like this. [ɪç] is both the word "ich" and the ending -ig, and
    /// no rule can tell them apart from the transcription alone.
    spelled: Option<&'static str>,
    /// Only at the end of the transcription.
    at_end: bool,
}

const fn plain(from: &'static str, to: &'static str) -> Rule {
    Rule {
        from,
        to,
        spelled: None,
        at_end: false,
    }
}

const fn ending(from: &'static str, to: &'static str, spelled: &'static str) -> Rule {
    Rule {
        from,
        to,
        spelled: Some(spelled),
        at_end: true,
    }
}

/// The accents that are a rule, and what the rule is. Order matters: a narrower rule comes
/// first, because a broader one would swallow it.
static EN_US: [Rule; 3] = [plain("əʊ", "oʊ"), plain("ɐ", "ɚ"), plain("ɒ", "ɑ")];
static ES_419: [Rule; 2] = [plain("θ", "s"), plain("ʎ", "ʝ")];
static ES_AR: [Rule; 3] = [plain("θ", "s"), plain("ʎ", "ʃ"), plain("ʝ", "ʃ")];
static DE_CH: [Rule; 6] = [
    ending("ɪç", "ɪɡ", "ig"),
    plain("ç", "x"),
    plain("ʁ", "r"),
    plain("ɐ̯", "r"),
    plain("ɐ", "ər"),
    plain("ʔ", ""),
];
static DE_AT: [Rule; 2] = [ending("ɪç", "ɪk", "ig"), plain("ʔ", "")];

fn rules(accent: &str) -> &'static [Rule] {
    match accent {
        // General American, for words no American data covers. The standard transcriptions
        // lean British, so an uncovered word came out British: American is rhotic, its GOAT
        // vowel is [oʊ], and LOT is unrounded.
        "en-us" | "en-ca" => &EN_US,
        // Latin-American Spanish: seseo and yeísmo.
        "es-419" => &ES_419,
        // Rioplatense: seseo and yeísmo, then žeísmo - what the rest of the Spanish world
        // says as [ʝ] is postalveolar here, so "yo" and "calle" carry [ʃ].
        "es-ar" => &ES_AR,
        // Swiss Standard German: no ich-Laut, so the ach-Laut throughout; a trilled r, even
        // where northern German vocalises it; no glottal stop to begin a word; and final -ig
        // stays a plosive.
        "de-ch" => &DE_CH,
        // Austrian Standard German keeps the uvular r, but likewise has no initial glottal
        // stop and ends -ig on a plosive.
        "de-at" => &DE_AT,
        _ => &[],
    }
}

/// Whether this accent's difference is a rule rather than a pack of its own.
pub fn is_a_rule(accent: &str) -> bool {
    !rules(accent).is_empty()
}

/// Rewrite a standard transcription into the accent's own. Unchanged for an accent that has
/// no rule, which is every accent that has data instead.
pub fn apply(ipa: &str, accent: &str, word: &str) -> String {
    let rules = rules(accent);
    if rules.is_empty() || ipa.is_empty() {
        return ipa.to_string();
    }
    let mut out = ipa.to_string();
    for rule in rules {
        if let Some(spelled) = rule.spelled {
            if !word.to_lowercase().ends_with(spelled) {
                continue;
            }
        }
        out = if rule.at_end {
            match out.strip_suffix(rule.from) {
                Some(head) => format!("{head}{}", rule.to),
                None => out,
            }
        } else {
            out.replace(rule.from, rule.to)
        };
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_accent_with_no_rule_changes_nothing() {
        assert_eq!(apply("ˈhɛloʊ", "en-gb", "hello"), "ˈhɛloʊ");
        assert_eq!(apply("ˈhɛloʊ", "", "hello"), "ˈhɛloʊ");
    }

    #[test]
    fn american_is_rhotic_and_unrounds_lot() {
        assert_eq!(apply("ˈkɒmə", "en-us", "comma"), "ˈkɑmə");
        assert_eq!(apply("ˈɡəʊt", "en-us", "goat"), "ˈɡoʊt");
        assert_eq!(apply("ˈwɔːtɐ", "en-us", "water"), "ˈwɔːtɚ");
    }

    #[test]
    fn latin_american_spanish_says_the_same_sound_for_c_and_s() {
        assert_eq!(apply("ˈθapato", "es-419", "zapato"), "ˈsapato"); // seseo
        assert_eq!(apply("ˈkaʎe", "es-419", "calle"), "ˈkaʝe"); // yeísmo
    }

    #[test]
    fn rioplatense_says_calle_with_a_postalveolar() {
        assert_eq!(apply("ˈkaʎe", "es-ar", "calle"), "ˈkaʃe");
    }

    #[test]
    fn swiss_german_has_no_ich_laut_and_no_glottal_stop() {
        assert_eq!(apply("ɪç", "de-ch", "ich"), "ɪx");
        assert_eq!(apply("ʔapfəl", "de-ch", "Apfel"), "apfəl");
    }

    #[test]
    fn a_rule_about_the_end_of_a_word_fires_only_there() {
        // The ending, before the ich-Laut rule can swallow it.
        assert_eq!(apply("ˈʁɪçtɪç", "de-ch", "richtig"), "ˈrɪxtɪɡ");
        // And not in the middle of a word that only looks like it.
        assert_eq!(apply("ɪçtɪçt", "de-ch", "nicht"), "ɪxtɪxt");
    }

    #[test]
    fn applying_an_accent_twice_changes_nothing_the_second_time() {
        for accent in ["en-us", "es-419", "es-ar", "de-ch", "de-at"] {
            for (ipa, word) in [
                ("ˈkɒmə", "comma"),
                ("ˈkaʎe", "calle"),
                ("ˈʁɪçtɪç", "richtig"),
            ] {
                let once = apply(ipa, accent, word);
                let twice = apply(&once, accent, word);
                assert_eq!(once, twice, "{accent} on {ipa}");
            }
        }
    }
}
