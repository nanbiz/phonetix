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
\"ipa\":{},\"says\":{},\"glosses\":{},\"source\":{},\"target\":{}}}",
        answer.state,
        quoted(&answer.spelling),
        maybe(&answer.lemma),
        maybe(&answer.pos),
        strings(&answer.ipa),
        strings(&answer.says),
        strings(&answer.glosses),
        quoted(&answer.source.0),
        quoted(&answer.target.0),
    )
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
            says: vec!["Hund".into()],
            glosses: vec!["dog".into()],
            provenance: None,
            source: Lang("es".into()),
            target: Lang("de".into()),
        }
    }

    #[test]
    fn an_answer_reads_back_as_the_host_expects() {
        let text = of(&an_answer());
        assert!(text.contains("\"state\":\"Entry\""));
        assert!(text.contains("\"spelling\":\"perro\""));
        assert!(text.contains("\"lemma\":null"));
        assert!(text.contains("\"says\":[\"Hund\"]"));
        assert!(text.contains("\"ipa\":[\"ˈpe.ro\"]"));
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
        assert!(!text.contains('\n'), "a real break would end the line the host reads");
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
