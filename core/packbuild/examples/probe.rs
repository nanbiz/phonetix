//! What packbuild reads out of some lines of an extract: `cargo run --example probe -- <lines.jsonl> <lang>`
fn main() {
    let args: Vec<String> = std::env::args().collect();
    let text = std::fs::read_to_string(&args[1]).unwrap();
    let mut skipped = packbuild::Skipped::default();
    for line in text.lines() {
        if let Some(read) = packbuild::read_line(line, &args[2], &mut skipped) {
            println!(
                "{} ipa={:?} bare={} marks={:?} forms={:?}",
                read.entry.lemma,
                read.entry.ipa,
                packbuild::is_bare_form(&read.entry),
                read.entry
                    .senses
                    .iter()
                    .map(|s| s.marks.clone())
                    .collect::<Vec<_>>(),
                read.forms
            );
        }
    }
}
