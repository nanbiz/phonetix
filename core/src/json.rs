//! An answer as text, for the boundary.
//!
//! Both hosts read JSON and neither can be handed a Rust value, so this is where an Answer
//! becomes something they can parse. It is written by hand rather than derived, so the shape a
//! host reads is stated in one place: a field renamed in the core cannot quietly rename itself
//! in the browser and on the phone.
//!
//! It lives in the core rather than in either binding, because two copies of it would be two
//! shapes the moment one was edited.

use crate::resolve::Answer;

/// One answer, as JSON.
pub fn of(answer: &Answer) -> String {
    format!(
        "{{\"state\":\"{:?}\",\"spelling\":{},\"lemma\":{},\"pos\":{},\
\"ipa\":{},\"symbols\":{},\"says\":{},\"glosses\":{},\"example\":{},\"readings\":{},\
\"source\":{},\"target\":{}}}",
        answer.state,
        quoted(&answer.spelling),
        maybe(&answer.lemma),
        maybe(&answer.pos),
        strings(&answer.ipa),
        symbols_of(&answer.symbols),
        strings(&answer.says),
        strings(&answer.glosses),
        maybe(&answer.example),
        readings(&answer.readings),
        quoted(&answer.source.0),
        quoted(&answer.target.0),
    )
}

/// A batch of tokens and the misses in it, as the host reads them.
///
/// One object rather than two calls, because the host draws the tokens and asks its engines
/// about the misses in the same pass, and two calls could disagree about which batch they
/// were describing.
pub fn batch(id: u64, tokens: &[crate::answer::Token], misses: &[crate::answer::Miss]) -> String {
    let drawn: Vec<String> = tokens.iter().map(token).collect();
    let asked: Vec<String> = misses
        .iter()
        .map(|miss| {
            format!(
                "{{\"token\":{},\"need\":\"{:?}\"}}",
                miss.token_index, miss.need
            )
        })
        .collect();
    format!(
        "{{\"batch\":{},\"tokens\":[{}],\"misses\":[{}]}}",
        id,
        drawn.join(","),
        asked.join(",")
    )
}

/// One word of a run, with everything needed to draw it.
fn token(token: &crate::answer::Token) -> String {
    format!(
        "{{\"run\":{},\"start\":{},\"end\":{},\"spelling\":{},\"lang\":{},\
\"state\":\"{:?}\",\"gloss\":{},\"ipa\":{},\"inline\":{},\"provenance\":{}}}",
        token.run_id,
        token.start,
        token.end,
        quoted(&token.spelling),
        quoted(&token.lang.0),
        token.state,
        maybe(&token.gloss),
        maybe(&token.ipa),
        token.inline,
        provenance(&token.provenance),
    )
}

/// Where an answer came from, which the interface shows and never hides.
fn provenance(from: &Option<crate::answer::Provenance>) -> String {
    use crate::answer::Provenance;
    match from {
        None => "null".to_string(),
        Some(Provenance::Dictionary { pack }) => {
            format!("{{\"kind\":\"dictionary\",\"pack\":{}}}", quoted(pack))
        }
        Some(Provenance::Guess { engine }) => {
            format!("{{\"kind\":\"guess\",\"engine\":{}}}", quoted(engine))
        }
        Some(Provenance::Synthesised) => "{\"kind\":\"synthesised\"}".to_string(),
    }
}

/// What language a text was found to be in, and what every language scored.
pub fn guess(guess: &crate::detect::Guess) -> String {
    let scores: Vec<String> = guess
        .scores
        .iter()
        .map(|(code, score)| format!("{}:{score}", quoted(code)))
        .collect();
    format!(
        "{{\"language\":{},\"reliable\":{},\"scores\":{{{}}}}}",
        maybe(&guess.language),
        guess.reliable,
        scores.join(",")
    )
}

/// A transcription symbol by symbol, each with what is known about that sound.
pub fn symbols_of(items: &[crate::symbols::Symbol]) -> String {
    let inner: Vec<String> = items
        .iter()
        .map(|symbol| {
            format!(
                "{{\"token\":{},\"name\":{},\"kind\":{},\"example\":{},\"audio\":{},\
\"wiki\":{},\"diagram\":{},\"seeing\":{}}}",
                quoted(&symbol.token),
                quoted(&symbol.name),
                quoted(&symbol.kind),
                quoted(&symbol.example),
                quoted(&symbol.audio),
                quoted(&symbol.wiki),
                quoted(&symbol.diagram),
                quoted(&symbol.seeing),
            )
        })
        .collect();
    format!("[{}]", inner.join(","))
}

/// The words a spelling is, where it is more than one.
fn readings(items: &[crate::resolve::Reading]) -> String {
    let inner: Vec<String> = items
        .iter()
        .map(|reading| {
            format!(
                "{{\"pos\":{},\"ipa\":{},\"says\":{},\"glosses\":{}}}",
                maybe(&reading.pos),
                strings(&reading.ipa),
                strings(&reading.says),
                strings(&reading.glosses),
            )
        })
        .collect();
    format!("[{}]", inner.join(","))
}

fn strings(items: &[String]) -> String {
    let inner: Vec<String> = items.iter().map(|s| quoted(s)).collect();
    format!("[{}]", inner.join(","))
}

fn maybe(text: &Option<String>) -> String {
    match text {
        Some(text) => quoted(text),
        None => "null".to_string(),
    }
}

/// A string as JSON writes one.
///
/// The transcriptions are the reason this cannot be a format string: they are full of
/// characters that are ordinary in IPA and have to survive a parser exactly as they are.
fn quoted(text: &str) -> String {
    let mut out = String::with_capacity(text.len() + 2);
    out.push('"');
    for c in text.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::answer::{AnswerState, Lang};

    fn an_answer() -> Answer {
        Answer {
            state: AnswerState::Entry,
            spelling: "perro".into(),
            lemma: None,
            pos: Some("noun".into()),
            ipa: vec!["ˈpe.ro".into()],
            symbols: crate::symbols::explain("ˈpe.ro"),
            says: vec!["Hund".into()],
            glosses: vec!["dog".into()],
            readings: Vec::new(),
            example: Some("El perro ladra.".into()),
            provenance: None,
            source: Lang("es".into()),
            target: Lang("de".into()),
        }
    }

    #[test]
    fn the_words_a_spelling_is_are_all_there() {
        let mut answer = an_answer();
        answer.readings = vec![
            crate::resolve::Reading {
                pos: Some("noun".into()),
                ipa: vec!["bʊk".into()],
                says: vec!["Buch".into()],
                glosses: vec!["a bound volume".into()],
            },
            crate::resolve::Reading {
                pos: Some("verb".into()),
                ipa: vec!["bʊk".into()],
                says: vec!["buchen".into()],
                glosses: vec!["to reserve".into()],
            },
        ];
        let text = of(&answer);
        assert!(text.contains("\"readings\":[{"), "{text}");
        assert!(text.contains("\"says\":[\"Buch\"]"));
        assert!(text.contains("\"says\":[\"buchen\"]"));
    }

    #[test]
    fn an_answer_reads_back_as_the_host_expects() {
        let text = of(&an_answer());
        assert!(text.contains("\"state\":\"Entry\""));
        assert!(text.contains("\"spelling\":\"perro\""));
        assert!(text.contains("\"lemma\":null"));
        assert!(text.contains("\"says\":[\"Hund\"]"));
        assert!(text.contains("\"ipa\":[\"ˈpe.ro\"]"));
        assert!(text.contains("\"example\":\"El perro ladra.\""));
    }

    #[test]
    fn a_transcription_survives_being_written_out() {
        // A transcription is full of characters that are ordinary in IPA and would end a
        // string if they were let through unescaped.
        let mut answer = an_answer();
        answer.ipa = vec!["ˈzɪt͡sbaŋk".into(), "a\"b\\c".into()];
        answer.glosses = vec!["a gloss with a \"quote\" and a\nbreak".into()];
        let text = of(&answer);
        assert!(text.contains("ˈzɪt͡sbaŋk"));
        assert!(text.contains("a\\\"b\\\\c"), "{text}");
        assert!(text.contains("\\n"), "{text}");
        assert!(
            !text.contains('\n'),
            "a real break would end the line the host reads"
        );
    }

    #[test]
    fn every_string_is_closed() {
        // The cheapest guard against the thing that breaks a host silently: an odd number of
        // unescaped quotes.
        let mut answer = an_answer();
        answer.spelling = "a \"hard\" case".into();
        let text = of(&answer);
        let mut quotes = 0;
        let mut escaped = false;
        for c in text.chars() {
            match c {
                '\\' if !escaped => escaped = true,
                '"' if !escaped => quotes += 1,
                _ => escaped = false,
            }
        }
        assert_eq!(quotes % 2, 0, "{text}");
    }
}
