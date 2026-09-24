//! Everything a typed word can mean in another language, as the phone's panel lists it:
//! `cargo run --example meanings -- <wanted pack> <typed language> <typed pack or -> <word>...`
use lexcore::answer::Lang;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let wanted = lexpack::Pack::open(std::fs::read(&args[1]).expect("reads")).expect("opens");
    let typed = (args[3] != "-")
        .then(|| lexpack::Pack::open(std::fs::read(&args[3]).expect("reads")).expect("opens"));
    for text in &args[4..] {
        println!("{text}");
        for m in lexcore::resolve::meanings(text, &Lang(args[2].clone()), &wanted, typed.as_ref()) {
            println!(
                "  {:<18} {:<6} {:<34} {}",
                m.word,
                m.pos,
                m.hint,
                m.ipa.unwrap_or_default()
            );
        }
    }
}
