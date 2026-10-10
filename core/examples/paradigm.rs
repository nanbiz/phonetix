//! Which form a spelling is and where else its table goes: `cargo run --example paradigm -- <pack> word...`,
//! where a word may follow its determiner: "den größten".
fn main() {
    let args: Vec<String> = std::env::args().collect();
    let pack = lexpack::Pack::open(std::fs::read(&args[1]).expect("reads")).expect("opens");
    for asked in &args[2..] {
        let (before, word) = asked.rsplit_once(' ').unwrap_or(("", asked));
        let agreeing = if before.is_empty() {
            Vec::new()
        } else {
            lexcore::paradigm::determiner_places(before, &pack.lookup(before))
        };
        for entry in pack.lookup(word) {
            let Some(form) = lexcore::paradigm::form(word, &entry, &agreeing) else {
                continue;
            };
            let names: Vec<&str> = form.place.iter().map(|(tag, _)| tag.as_str()).collect();
            println!(
                "== {word}: {} of {} ({}) ending at {}",
                names.join(" "),
                entry.lemma,
                entry.pos,
                form.ending_at
            );
            for along in &form.along {
                let words: Vec<String> = along
                    .forms
                    .iter()
                    .map(|o| {
                        let v: Vec<&str> = o
                            .place
                            .iter()
                            .filter(|(_, c)| {
                                *c == along.category
                                    || (along.category == "person" && *c == "number")
                            })
                            .map(|(t, _)| t.as_str())
                            .collect();
                        format!(
                            "{}{}={}",
                            if o.here { "*" } else { "" },
                            v.join("/"),
                            o.spelling
                        )
                    })
                    .collect();
                println!("   {:<7} {}", along.category, words.join("  "));
            }
        }
    }
}
