//! What a line is drawn as with a real pack:
//! `cargo run --example draw -- <pack> <lang> <text> [<the line as the engine translated it>]`
//!
//! With the translated line, every word left undecided between readings is read again with it
//! in hand, the way the host does once the engine has answered.
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
    let open = Open {
        source: Some(&pack),
        classifier: classifier.as_ref(),
        ..Open::default()
    };
    let options = AnnotateOptions {
        mode: InlineMode::Meaning,
        density: 1,
        narrow: false,
        hide_stress: false,
        accent: None,
        seen: Vec::new(),
    };
    let target = Lang("en".to_string());
    let (mut tokens, _) = annotate(
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
        let results: Vec<lexcore::answer::EngineResult> = tokens
            .iter()
            .enumerate()
            .filter(|(_, token)| token.state == lexcore::answer::AnswerState::Homograph)
            .map(|(at, _)| lexcore::answer::EngineResult {
                token_index: at as u32,
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
            "{:>14}  {:<30} {:?}",
            token.spelling,
            token.gloss.clone().unwrap_or_default(),
            token.state
        );
    }
}
