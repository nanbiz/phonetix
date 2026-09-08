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
            if term.is_empty() { None } else { Some(term) }
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
    scored.sort_by(|a, b| b.0.cmp(&a.0));
    match scored.as_slice() {
        [] => None,
        [(_, only)] => Some(*only),
        [(best, i), (second, _), ..] if best >= &(second + margin) => Some(*i),
        _ => None,
    }
}

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
        assert!(overlap(silla, "a chair (to sit on)")
            > overlap(silla, "armchair, easy chair (comfortable chair with arms)"));
        // camino: Weg against Weise. Both offer "way", which is why one shared term decides
        // nothing and the count is what tells them apart.
        let camino = "way, route";
        assert!(overlap(camino, "route, way (to get from one place to another)")
            > overlap(camino, "way, manner"));
    }

    /// Picking between candidates, and refusing to when the best is not clearly best.
    #[test]
    fn picks_the_best_and_refuses_a_tie() {
        let camino = "way, route";
        let candidates = ["way, manner", "route, way (to get from one place to another)"];
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
        assert_eq!(terms("forest; woods; woodland"), ["forest", "woods", "woodland"]);
        assert_eq!(overlap("forest", "forest; woods; woodland"), 1);
    }

    /// The parenthesis qualifies the term rather than naming it.
    #[test]
    fn drops_what_a_parenthesis_qualifies() {
        assert_eq!(terms("key (to open doors)"), ["key"]);
        assert_eq!(terms("book (collection of sheets of paper bound together)"), ["book"]);
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
}
