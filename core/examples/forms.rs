//! A lemma's form table as the pack holds it: `cargo run --example forms -- <pack> word...`,
//! each entry with its glosses. With `ALL=1` every entry the spelling reaches, its own and
//! those whose tables list it.
fn main() {
    let args: Vec<String> = std::env::args().collect();
    let pack = lexpack::Pack::open(std::fs::read(&args[1]).expect("reads")).expect("opens");
    let all = std::env::var_os("ALL").is_some();
    for word in &args[2..] {
        for entry in pack
            .lookup(word)
            .into_iter()
            .filter(|e| all || &e.lemma == word)
        {
            println!(
                "== {} {} ({} forms)",
                entry.lemma,
                entry.pos,
                entry.forms.len()
            );
            for sense in &entry.senses {
                println!("  - {}", sense.gloss);
            }
            for form in &entry.forms {
                println!("  {:<16} {}", form.spelling, form.label);
            }
        }
    }
}
