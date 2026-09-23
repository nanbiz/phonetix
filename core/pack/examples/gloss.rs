//! Which entries a gloss term reaches: `cargo run --example gloss -- <pack> <gloss>...`
fn main() {
    let args: Vec<String> = std::env::args().collect();
    let pack = lexpack::Pack::open(std::fs::read(&args[1]).expect("reads")).expect("opens");
    for gloss in &args[2..] {
        println!("== {gloss} -> terms {:?}", lexpack::gloss_terms(gloss));
        for ((which, sense), shared) in pack.senses_matching(gloss).into_iter().take(40) {
            if let Some(entry) = pack.entry(which) {
                println!(
                    "  {} [{}] sense {sense} shared {shared}",
                    entry.lemma, entry.pos
                );
            }
        }
    }
}
