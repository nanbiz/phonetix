//! How long looking words up takes with real packs:
//! `cargo run --release --example time_card -- <source pack> <target pack> <source> <target> <word>...`
use lexcore::answer::Lang;
use lexcore::resolve::{look_up, Open};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let source = lexpack::Pack::open(std::fs::read(&args[1]).expect("reads")).expect("opens");
    let target = lexpack::Pack::open(std::fs::read(&args[2]).expect("reads")).expect("opens");
    let open = Open {
        source: Some(&source),
        target: Some(&target),
        ..Open::default()
    };
    if std::env::var("LOOKUP_ONLY").is_ok() {
        let began = std::time::Instant::now();
        let mut n = 0;
        for word in &args[5..] {
            n += source.lookup(word).len() + source.lookup(&word.to_lowercase()).len();
        }
        println!(
            "lookups alone: {:.1}ms for {} entries",
            began.elapsed().as_secs_f64() * 1000.0,
            n
        );
        return;
    }
    for word in &args[5..] {
        let began = std::time::Instant::now();
        let answer = look_up(word, &Lang(args[3].clone()), &Lang(args[4].clone()), &open);
        println!(
            "{word:>10} {:>6.1}ms {:?}",
            began.elapsed().as_secs_f64() * 1000.0,
            answer.says
        );
    }
}
