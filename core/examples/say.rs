//! The word for something, out of the dictionaries alone:
//! `cargo run --example say -- <learning pack> <typed language> <typed pack or -> <text>...`
use lexcore::answer::Lang;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let wanted = lexpack::Pack::open(std::fs::read(&args[1]).expect("reads")).expect("opens");
    let typed = (args[3] != "-")
        .then(|| lexpack::Pack::open(std::fs::read(&args[3]).expect("reads")).expect("opens"));
    for text in &args[4..] {
        let words =
            lexcore::resolve::word_for(text, &Lang(args[2].clone()), &wanted, typed.as_ref());
        println!("{text:>12}  {words:?}");
    }
}
