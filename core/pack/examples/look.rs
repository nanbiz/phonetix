//! Print what a pack holds for some words: `cargo run --example look -- <pack> <word>...`
fn main() {
    let args: Vec<String> = std::env::args().collect();
    let bytes = std::fs::read(&args[1]).expect("the pack reads");
    let pack = lexpack::Pack::open(bytes).expect("the pack opens");
    for word in &args[2..] {
        println!("== {word}");
        for entry in pack.lookup(word) {
            println!(
                "  [{} {}] ipa={:?}",
                entry.lemma,
                entry.pos,
                entry.ipa.first()
            );
            for sense in entry.senses.iter().take(4) {
                println!("     - {} {:?}", sense.gloss, sense.marks);
            }
        }
    }
}
