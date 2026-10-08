//! A lemma's form table as the pack holds it: `cargo run --example forms -- <pack> word...`
fn main() {
    let args: Vec<String> = std::env::args().collect();
    let pack = lexpack::Pack::open(std::fs::read(&args[1]).expect("reads")).expect("opens");
    for word in &args[2..] {
        for entry in pack.lookup(word).into_iter().filter(|e| &e.lemma == word) {
            println!(
                "== {} {} ({} forms)",
                entry.lemma,
                entry.pos,
                entry.forms.len()
            );
            for form in &entry.forms {
                println!("  {:<16} {}", form.spelling, form.label);
            }
        }
    }
}
