//! What a card says about one word with real packs:
//! `cargo run --example card -- <source pack> <target pack> <source> <target> [before:]word...`
use lexcore::answer::Lang;
use lexcore::resolve::{read_in_context, Open};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let source = lexpack::Pack::open(std::fs::read(&args[1]).expect("reads")).expect("opens");
    let target = lexpack::Pack::open(std::fs::read(&args[2]).expect("reads")).expect("opens");
    let open = Open {
        source: Some(&source),
        target: Some(&target),
        ..Open::default()
    };
    // "a:paragraph" asks about "paragraph" with "a" before it.
    for asked in &args[5..] {
        let (before, word) = match asked.split_once(':') {
            Some((before, word)) => (Some(before), word),
            None => (None, asked.as_str()),
        };
        let answer = read_in_context(
            word,
            before,
            &Lang(args[3].clone()),
            &Lang(args[4].clone()),
            &open,
        );
        println!(
            "{word:>10}  {:?} says={:?} readings={}",
            answer.state,
            answer.says,
            answer.readings.len()
        );
    }
}
