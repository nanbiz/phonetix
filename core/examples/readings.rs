//! Every reading the cascade answers a word with, for seeing why a card is undecided:
//! `cargo run --example readings -- <source pack> <target pack> <source> <target> [before:]word...`
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
            "{word}: {:?} lemma={:?} form={:?} pos={:?} says={:?} glosses={:?}",
            answer.state,
            answer.lemma,
            answer.form,
            answer.pos,
            answer.says,
            answer.glosses.iter().take(2).collect::<Vec<_>>()
        );
        for reading in &answer.readings {
            println!(
                "    {:?} says={:?} glosses={:?}",
                reading.pos,
                reading.says,
                reading.glosses.iter().take(2).collect::<Vec<_>>()
            );
        }
    }
}
