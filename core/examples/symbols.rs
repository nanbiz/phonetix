//! How a transcription is split into the sounds a card offers: `cargo run --example symbols -- ipa...`
fn main() {
    for ipa in std::env::args().skip(1) {
        let tokens: Vec<String> = lexcore::symbols::explain(&ipa)
            .into_iter()
            .map(|symbol| format!("{}[{}]", symbol.token, symbol.kind))
            .collect();
        println!("{ipa} -> {}", tokens.join(" "));
    }
}
