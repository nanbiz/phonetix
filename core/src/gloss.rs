//! Matching a word in one language to a word in another through the English gloss both carry.
//!
//! Every language's entries in the English Wiktionary are glossed in English, so a Spanish
//! entry and a German entry can be held against each other directly: `perro` glosses "dog" and
//! `Hund` glosses "dog, hound". That is the join this crate is built on, and it was chosen
//! after two others were measured and rejected, which `docs/merge/coverage.md` records.
//!
//! What makes it work is that both sides are written by the same community in the same
//! register. What breaks it is everything around the words: a gloss carries articles, a verb's
//! leading "to", a parenthesised qualification, and several terms separated by either commas or
//! semicolons. Normalising those away is the whole of the matching.

/// The terms a gloss offers for matching, in the order they were written.
///
/// The parenthesised part of a gloss qualifies the term rather than naming it - "dog (the
/// species Canis familiaris)" is offering "dog" - so it is dropped. What is left is split on
/// both commas and semicolons, because "forest; woods; woodland" is one gloss holding three
/// terms and treating it as one made `bosque` fail to meet `Wald`.
pub fn terms(gloss: &str) -> Vec<String> {
    let head = gloss.split('(').next().unwrap_or(gloss);
    head.split([',', ';'])
        .filter_map(|part| {
            let term = normalise(part);
            if term.is_empty() {
                None
            } else {
                Some(term)
            }
        })
        .collect()
}

/// One term, reduced to what two languages can be compared on.
///
/// Lowercased, trimmed, and stripped of a leading article or the "to" of an infinitive, since
/// one side writes "a chair (to sit on)" where the other writes "chair".
fn normalise(part: &str) -> String {
    let lower = part.trim().to_lowercase();
    for prefix in ["to ", "a ", "an ", "the "] {
        if let Some(rest) = lower.strip_prefix(prefix) {
            return rest.trim().to_string();
        }
    }
    lower
}

/// How many terms two glosses share.
///
/// Sharing one is not enough to decide anything. "way, route" and "way, manner" both offer
/// "way", and they are `Weg` and `Weise`, which are not the same word: a reader told that a
/// road is a manner has been given a confident wrong answer, which is the failure this whole
/// join exists to avoid. What separates them is that the right one shares more, so the answer
/// is a count and the decision is made by comparing counts rather than by any single match.
pub fn overlap(source: &str, target: &str) -> usize {
    let theirs = terms(target);
    terms(source).iter().filter(|t| theirs.contains(t)).count()
}

/// The best of a set of candidate glosses, when it is clearly better than the rest.
///
/// Returns the index of the candidate sharing the most terms, and nothing at all when the best
/// does not beat the runner-up by `margin`. An ambiguous join yields no dictionary answer on
/// purpose: the word falls to the engine and is labelled a guess, because a miss is recoverable
/// and a confident wrong gloss is not.
pub fn best_of(source: &str, candidates: &[&str], margin: usize) -> Option<usize> {
    let mut scored: Vec<(usize, usize)> = candidates
        .iter()
        .enumerate()
        .map(|(i, c)| (overlap(source, c), i))
        .filter(|(score, _)| *score > 0)
        .collect();
    scored.sort_by_key(|scored| std::cmp::Reverse(scored.0));
    match scored.as_slice() {
        [] => None,
        [(_, only)] => Some(*only),
        [(best, i), (second, _), ..] if best >= &(second + margin) => Some(*i),
        _ => None,
    }
}

/// The ways an English word can be written in running text, the word itself first.
///
/// A gloss gives a verb in the infinitive and a noun in the singular, and a translated
/// sentence has them inflected: "to run" comes back as "runs", "to be" as "is". Regular
/// endings are made by rule, with the spelling changes the rules have (a final "e", a "y"
/// after a consonant, a doubled consonant), and the verbs common enough to be irregular are
/// listed. What this makes is a set to look for, not a claim that each is a word: a form that
/// does not exist is never in a sentence to be found.
///
/// Only a verb is given a verb's endings. A noun takes its plural and nothing else: "bank" the
/// noun is not in "banking", and every extra form of a word is one more way for a sentence to
/// seem to say it when it said something else.
pub fn english_forms(word: &str, verb: bool) -> Vec<String> {
    let word = word.to_lowercase();
    let mut forms = vec![word.clone()];
    if word.is_empty() || !word.chars().all(|c| c.is_ascii_alphabetic()) {
        return forms;
    }
    if let Some((_, irregular)) = IRREGULAR.iter().find(|(verb, _)| *verb == word) {
        forms.extend(irregular.iter().map(|form| form.to_string()));
    }
    let vowel = |c: char| matches!(c, 'a' | 'e' | 'i' | 'o' | 'u');
    let chars: Vec<char> = word.chars().collect();
    let last = chars[chars.len() - 1];
    let before = chars.len().checked_sub(2).map(|at| chars[at]);
    if word.ends_with(['s', 'x', 'z']) || word.ends_with("ch") || word.ends_with("sh") {
        forms.push(format!("{word}es"));
    } else if last == 'y' && before.is_some_and(|c| !vowel(c)) {
        forms.push(format!("{}ies", &word[..word.len() - 1]));
    } else {
        forms.push(format!("{word}s"));
    }
    if !verb {
        return forms;
    }
    if last == 'e' {
        forms.push(format!("{word}d"));
        forms.push(format!("{}ing", &word[..word.len() - 1]));
    } else if last == 'y' && before.is_some_and(|c| !vowel(c)) {
        forms.push(format!("{}ied", &word[..word.len() - 1]));
        forms.push(format!("{word}ing"));
    } else {
        forms.push(format!("{word}ed"));
        forms.push(format!("{word}ing"));
        // One short syllable ending consonant-vowel-consonant doubles its last letter: "run",
        // "running"; "stop", "stopped".
        let third = chars.len().checked_sub(3).map(|at| chars[at]);
        if chars.len() <= 4
            && !vowel(last)
            && !matches!(last, 'w' | 'x' | 'y')
            && before.is_some_and(vowel)
            && third.is_some_and(|c| !vowel(c))
        {
            forms.push(format!("{word}{last}ed"));
            forms.push(format!("{word}{last}ing"));
        }
    }
    forms
}

/// The irregular verbs a translated sentence is most likely to hold, with every form that is
/// not made by rule.
const IRREGULAR: &[(&str, &[&str])] = &[
    (
        "be",
        &[
            "is", "are", "am", "was", "were", "been", "being", "isn't", "aren't",
        ],
    ),
    ("have", &["has", "had", "having"]),
    ("do", &["does", "did", "done", "doing"]),
    ("go", &["goes", "went", "gone", "going"]),
    ("say", &["says", "said"]),
    ("make", &["made"]),
    ("get", &["got", "gotten", "getting"]),
    ("know", &["knew", "known"]),
    ("see", &["saw", "seen", "sees"]),
    ("come", &["came"]),
    ("take", &["took", "taken"]),
    ("give", &["gave", "given"]),
    ("run", &["ran"]),
    ("think", &["thought"]),
    ("find", &["found"]),
    ("tell", &["told"]),
    ("become", &["became"]),
    ("leave", &["left"]),
    ("feel", &["felt"]),
    ("bring", &["brought"]),
    ("begin", &["began", "begun", "beginning"]),
    ("keep", &["kept"]),
    ("hold", &["held"]),
    ("write", &["wrote", "written"]),
    ("stand", &["stood"]),
    ("hear", &["heard"]),
    ("mean", &["meant"]),
    ("meet", &["met"]),
    ("pay", &["paid"]),
    ("sit", &["sat", "sitting"]),
    ("speak", &["spoke", "spoken"]),
    ("lead", &["led"]),
    ("grow", &["grew", "grown"]),
    ("lose", &["lost"]),
    ("fall", &["fell", "fallen"]),
    ("send", &["sent"]),
    ("build", &["built"]),
    ("understand", &["understood"]),
    ("break", &["broke", "broken"]),
    ("spend", &["spent"]),
    ("drive", &["drove", "driven"]),
    ("buy", &["bought"]),
    ("wear", &["wore", "worn"]),
    ("choose", &["chose", "chosen"]),
    ("eat", &["ate", "eaten"]),
    ("drink", &["drank", "drunk"]),
    ("sleep", &["slept"]),
    ("fly", &["flew", "flown", "flies"]),
    ("sing", &["sang", "sung"]),
    ("swim", &["swam", "swum", "swimming"]),
    ("put", &["putting"]),
    ("let", &["letting"]),
    ("set", &["setting"]),
    ("cut", &["cutting"]),
    ("read", &[]),
    ("lie", &["lay", "lain", "lying"]),
    ("die", &["dying"]),
    ("can", &["could"]),
    ("will", &["would"]),
    ("shall", &["should"]),
    ("may", &["might"]),
    ("must", &[]),
    ("want", &[]),
    ("win", &["won", "winning"]),
    ("sell", &["sold"]),
    ("teach", &["taught"]),
    ("catch", &["caught"]),
    ("fight", &["fought"]),
    ("seek", &["sought"]),
    ("throw", &["threw", "thrown"]),
    ("show", &["shown"]),
    ("forget", &["forgot", "forgotten"]),
    ("hide", &["hid", "hidden"]),
    ("ride", &["rode", "ridden"]),
    ("rise", &["rose", "risen"]),
    ("shake", &["shook", "shaken"]),
    ("steal", &["stole", "stolen"]),
    ("wake", &["woke", "woken"]),
    ("draw", &["drew", "drawn"]),
    ("blow", &["blew", "blown"]),
    ("light", &["lit"]),
    ("feed", &["fed"]),
    ("lend", &["lent"]),
    ("bite", &["bit", "bitten"]),
    ("man", &["men"]),
    ("woman", &["women"]),
    ("child", &["children"]),
    ("person", &["people"]),
    ("foot", &["feet"]),
    ("tooth", &["teeth"]),
    ("mouse", &["mice"]),
    ("good", &["better", "best"]),
    ("bad", &["worse", "worst"]),
];

#[cfg(test)]
mod tests {
    use super::*;

    /// The three words the pivot through English lemmas got wrong, which is why this join
    /// exists. Each gloss is real, taken from kaikki on 2026-09-08.
    #[test]
    fn the_right_word_shares_more_than_the_wrong_one() {
        // perro: Hund against Rüde.
        let perro = "dog (the species Canis familiaris)";
        assert!(overlap(perro, "dog, hound") > overlap(perro, "male dog"));
        // silla: Stuhl against Sessel.
        let silla = "chair";
        assert!(
            overlap(silla, "a chair (to sit on)")
                > overlap(silla, "armchair, easy chair (comfortable chair with arms)")
        );
        // camino: Weg against Weise. Both offer "way", which is why one shared term decides
        // nothing and the count is what tells them apart.
        let camino = "way, route";
        assert!(
            overlap(camino, "route, way (to get from one place to another)")
                > overlap(camino, "way, manner")
        );
    }

    /// Picking between candidates, and refusing to when the best is not clearly best.
    #[test]
    fn picks_the_best_and_refuses_a_tie() {
        let camino = "way, route";
        let candidates = [
            "way, manner",
            "route, way (to get from one place to another)",
        ];
        assert_eq!(best_of(camino, &candidates, 1), Some(1));

        // Two candidates sharing as much as each other name nothing the reader can rely on.
        assert_eq!(best_of("chair", &["chair", "a chair (to sit on)"], 1), None);
        // And a word nothing shares a term with is a miss, not a guess dressed up.
        assert_eq!(best_of("aardvark", &["chair", "way, manner"], 1), None);
    }

    /// A gloss holding several terms separates on either mark. Reading only commas left
    /// "forest" unable to meet "forest; woods; woodland".
    #[test]
    fn splits_on_semicolons_as_well_as_commas() {
        assert_eq!(
            terms("forest; woods; woodland"),
            ["forest", "woods", "woodland"]
        );
        assert_eq!(overlap("forest", "forest; woods; woodland"), 1);
    }

    /// The parenthesis qualifies the term rather than naming it.
    #[test]
    fn drops_what_a_parenthesis_qualifies() {
        assert_eq!(terms("key (to open doors)"), ["key"]);
        assert_eq!(
            terms("book (collection of sheets of paper bound together)"),
            ["book"]
        );
    }

    /// An infinitive on one side and a bare verb on the other are the same word.
    #[test]
    fn a_verb_meets_its_infinitive() {
        assert_eq!(overlap("to eat", "eat"), 1);
        assert_eq!(terms("to go, to walk"), ["go", "walk"]);
    }

    #[test]
    fn an_article_is_not_part_of_the_term() {
        assert_eq!(terms("a chair"), ["chair"]);
        assert_eq!(terms("the truth"), ["truth"]);
    }

    /// A verb as a gloss gives it and as a translated sentence has it.
    #[test]
    fn english_is_written_in_its_inflections() {
        assert!(english_forms("be", true).contains(&"is".to_string()));
        assert!(english_forms("run", true).contains(&"runs".to_string()));
        assert!(english_forms("run", true).contains(&"running".to_string()));
        assert!(english_forms("carry", true).contains(&"carries".to_string()));
        assert!(english_forms("carry", true).contains(&"carried".to_string()));
        assert!(english_forms("make", true).contains(&"making".to_string()));
        assert!(english_forms("watch", true).contains(&"watches".to_string()));
        assert_eq!(english_forms("dog", false)[0], "dog");
        assert!(english_forms("dog", false).contains(&"dogs".to_string()));
        assert!(!english_forms("bank", false).contains(&"banking".to_string()));
    }
}
