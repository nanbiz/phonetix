//! What a card says about one word with real packs:
//! `cargo run --example card -- <source pack> <target pack> <source> <target> [before:]word...`,
//! with `DEFINES=<def pack>` for a word read in its own language, and `ACCENT=<id>` with
//! `ACCENT_PACK=<pack>` for a reader who chose an accent.
use lexcore::answer::Lang;
use lexcore::resolve::{read_in_context, Open};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let source = lexpack::Pack::open(std::fs::read(&args[1]).expect("reads")).expect("opens");
    let target = lexpack::Pack::open(std::fs::read(&args[2]).expect("reads")).expect("opens");
    // What the language's own Wiktionary says, where DEFINES names its pack.
    let defines = std::env::var("DEFINES")
        .ok()
        .map(|path| lexpack::Pack::open(std::fs::read(path).expect("reads")).expect("opens"));
    let accent = std::env::var("ACCENT").unwrap_or_default();
    let accent_pack = std::env::var("ACCENT_PACK")
        .ok()
        .map(|path| lexpack::Pack::open(std::fs::read(path).expect("reads")).expect("opens"));
    let open = Open {
        source: Some(&source),
        target: Some(&target),
        defines: defines.as_ref(),
        accent: &accent,
        accent_pack: accent_pack.as_ref(),
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
            "{word:>10}  {:?} ipa={:?} in={:?} lead={:?} says={:?} readings={}",
            answer.state,
            answer.ipa,
            answer.said_in,
            lexcore::annotate::lead(&answer, &open),
            answer.says,
            answer.readings.len()
        );
        if std::env::var("READINGS").is_ok() {
            for reading in &answer.readings {
                println!("            {:?}: {:?}", reading.pos, reading.glosses);
            }
            println!(
                "            form={:?} lemma={:?}",
                answer.form, answer.lemma
            );
        }
        if let Some(form) = &answer.paradigm {
            let place: Vec<&str> = form.place.iter().map(|(tag, _)| tag.as_str()).collect();
            println!(
                "            form: {} of {:?}, said {:?}",
                place.join(" "),
                answer.lemma,
                form.said
            );
            for along in &form.along {
                let each: Vec<String> = along
                    .forms
                    .iter()
                    .map(|o| {
                        format!(
                            "{}{} {:?}",
                            if o.here { "*" } else { "" },
                            o.spelling,
                            o.said
                        )
                    })
                    .collect();
                println!("            {:<7} {}", along.category, each.join(" | "));
            }
        }
    }
}
