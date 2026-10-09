//! What a recogniser wrote down, put right against a dictionary, one line in and one out:
//! `cargo run --release --example heard -- <pack> < lines`
use std::io::BufRead;

fn main() {
    let path = std::env::args().nth(1).expect("a pack");
    let pack = lexpack::Pack::open(std::fs::read(path).expect("reads")).expect("opens");
    for line in std::io::stdin().lock().lines() {
        let line = line.expect("reads a line");
        let started = std::time::Instant::now();
        let fixed = lexcore::resolve::heard(&line, &pack);
        println!("{line}\t{fixed}\t{}us", started.elapsed().as_micros());
    }
}
