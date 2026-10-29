//! What a card leads with for every word of real sentences, to measure word choice against
//! people's own translations of them:
//! `cargo run --release --example word_choice -- <source pack> <target pack> <source> <target> [line]`
//! with "English<TAB>translation" lines on stdin. With `line` the translation is handed in as
//! the line the host translated, which is the context a card asks its translator for.
//! One JSON line per word: the sentence's number, the word, and what the card leads with.
use lexcore::answer::Lang;
use lexcore::resolve::{holds_together, read_in_context, Open};
use std::io::BufRead;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let source = lexpack::Pack::open(std::fs::read(&args[1]).expect("reads")).expect("opens");
    let target = lexpack::Pack::open(std::fs::read(&args[2]).expect("reads")).expect("opens");
    let (from, into) = (Lang(args[3].clone()), Lang(args[4].clone()));
    // The source's words as the target's own Wiktionary gives them (IN_READER=<pack>).
    let in_reader = std::env::var("IN_READER")
        .ok()
        .map(|path| lexpack::Pack::open(std::fs::read(path).expect("reads")).expect("opens"));
    let with_line = args.get(5).is_some_and(|it| it == "line");
    for (at, line) in std::io::stdin().lock().lines().enumerate() {
        let line = line.expect("reads");
        let Some((english, translated)) = line.split_once('\t') else {
            continue;
        };
        let open = Open {
            source: Some(&source),
            target: Some(&target),
            said: with_line.then_some(translated),
            in_reader: in_reader.as_ref(),
            ..Open::default()
        };
        let words: Vec<&str> = english
            .split(|c: char| !c.is_alphanumeric() && c != '\'' && c != '-')
            .filter(|word| !word.is_empty())
            .collect();
        for (i, word) in words.iter().enumerate() {
            let before = i.checked_sub(1).map(|b| words[b]);
            let answer = read_in_context(word, before, &from, &into, &open);
            if holds_together(&answer, word, &from) {
                continue;
            }
            let lead = lexcore::annotate::lead(&answer, &open).unwrap_or_default();
            println!(
                "{{\"at\":{at},\"word\":{:?},\"lead\":{:?},\"pos\":{:?}}}",
                word,
                lead,
                answer.pos.unwrap_or_default()
            );
        }
    }
}
