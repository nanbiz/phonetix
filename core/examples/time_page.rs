//! How long a page takes to annotate: `cargo run --release --example time_page -- <pack> <lang> <lines.json> [<into pack>]`
use lexcore::annotate::annotate;
use lexcore::answer::{AnnotateOptions, InlineMode, Lang, TextRun};
use lexcore::resolve::Open;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let pack = lexpack::Pack::open(std::fs::read(&args[1]).expect("reads")).expect("opens");
    let lines: Vec<String> =
        serde_json::from_slice(&std::fs::read(&args[3]).expect("reads")).expect("json");
    let into = args
        .get(4)
        .map(|path| lexpack::Pack::open(std::fs::read(path).expect("reads")).expect("opens"));
    let open = Open {
        source: Some(&pack),
        target: into.as_ref(),
        ..Open::default()
    };
    let options = AnnotateOptions {
        mode: InlineMode::Sound,
        density: 9,
        narrow: false,
        hide_stress: true,
        accent: None,
        seen: Vec::new(),
        counts: Default::default(),
        seed: 0,
    };
    let mut slowest: Vec<(u128, String)> = Vec::new();
    let all = std::time::Instant::now();
    for (at, line) in lines.iter().enumerate() {
        let started = std::time::Instant::now();
        let _ = annotate(
            &[TextRun {
                id: at as u32,
                text: line.clone(),
                lang_hint: None,
            }],
            &Lang(args[2].clone()),
            &Lang("en".into()),
            &open,
            &options,
        );
        slowest.push((
            started.elapsed().as_millis(),
            line.chars().take(80).collect(),
        ));
    }
    slowest.sort_by_key(|a| std::cmp::Reverse(a.0));
    println!(
        "all {} lines: {} ms",
        lines.len(),
        all.elapsed().as_millis()
    );
    for (ms, line) in slowest.iter().take(6) {
        println!("{ms} ms  {line}");
    }
}
