//! What a line is drawn as with a real pack:
//! `cargo run --example draw -- <pack> <lang> <text> [<the line as the engine translated it>]`
//!
//! With the translated line, every word that asked for it - undecided between readings, or
//! between senses - is read again with it in hand, the way a host does once the engine has
//! answered.
//!
//! Read into another language than English with `INTO=<lang> INTO_PACK=<pack>`, drawn the way
//! the "both" layer draws it: each word's translation and how that is said.
use lexcore::annotate::annotate;
use lexcore::answer::{AnnotateOptions, InlineMode, Lang, TextRun};
use lexcore::resolve::Open;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let pack = lexpack::Pack::open(std::fs::read(&args[1]).expect("reads")).expect("opens");
    // The trained classifier for the language, where the app carries one, as the phone has it.
    let classifier = std::fs::read(format!(
        "../android/app/src/main/assets/homographs/{}.hg",
        args[2]
    ))
    .ok()
    .and_then(|bytes| lexcore::homographs::Classifier::open(&bytes).ok());
    let into = std::env::var("INTO").unwrap_or_else(|_| "en".to_string());
    let into_pack = std::env::var("INTO_PACK")
        .ok()
        .map(|path| lexpack::Pack::open(std::fs::read(path).expect("reads")).expect("opens"));
    let open = Open {
        source: Some(&pack),
        target: into_pack.as_ref(),
        classifier: classifier.as_ref(),
        ..Open::default()
    };
    let options = AnnotateOptions {
        mode: if into == "en" {
            InlineMode::Meaning
        } else {
            InlineMode::Both
        },
        density: 1,
        narrow: false,
        hide_stress: false,
        accent: None,
        seen: Vec::new(),
    };
    let target = Lang(into);
    let (mut tokens, misses) = annotate(
        &[TextRun {
            id: 1,
            text: args[3].clone(),
            lang_hint: None,
        }],
        &Lang(args[2].clone()),
        &target,
        &open,
        &options,
    );
    if let Some(said) = args.get(4) {
        use lexcore::answer::Need;
        let results: Vec<lexcore::answer::EngineResult> = misses
            .iter()
            .filter(|miss| matches!(miss.need, Need::Sentence | Need::Sense))
            .map(|miss| lexcore::answer::EngineResult {
                token_index: miss.token_index,
                gloss: None,
                ipa: None,
                sentence: Some(said.clone()),
                engine: "draw".to_string(),
            })
            .collect();
        lexcore::annotate::complete(&mut tokens, &results, &options, &target, &open);
    }
    for token in tokens.iter().filter(|t| t.inline) {
        println!(
            "{:>14}  {:<24} {:<18} {:?}",
            token.spelling,
            token.gloss.clone().unwrap_or_default(),
            token.gloss_ipa.clone().unwrap_or_default(),
            token.state
        );
    }
}
