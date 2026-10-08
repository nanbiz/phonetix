//! The pack's own entries under a spelling, as stored: `cargo run --example entries -- <pack> word...`
fn main() {
    let args: Vec<String> = std::env::args().collect();
    let pack = lexpack::Pack::open(std::fs::read(&args[1]).expect("reads")).expect("opens");
    for word in &args[2..] {
        println!("== {word}");
        for entry in pack.lookup(word) {
            println!(
                "  lemma={:?} pos={:?} tags={:?} ipa={:?} forms={}",
                entry.lemma,
                entry.pos,
                entry.tags,
                entry.ipa.first(),
                entry.forms.len()
            );
            for sense in entry.senses.iter().take(4) {
                println!("      {:?} {:?}", sense.gloss, sense.marks);
            }
        }
    }
}
