//! Build one language's pack from its extract of the dump.
//!
//!   packbuild <language> <extract.jsonl> <out.lexpack>
//!
//! Reads the extract a line at a time and writes the pack, then prints the manifest row: what
//! it holds, how big it is, and its checksum. A language's extract runs to gigabytes and the
//! pack is a fraction of it, so nothing but the pack is held in memory.

use std::fs::File;
use std::io::{BufRead, BufReader, Write};
use std::time::{SystemTime, UNIX_EPOCH};

use lexpack::{Builder, Kind};
use packbuild::{read_line, Skipped};
use sha2::{Digest, Sha256};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 4 {
        eprintln!("packbuild <language> <extract.jsonl> <out.lexpack>");
        std::process::exit(2);
    }
    let (lang, from, to) = (&args[1], &args[2], &args[3]);

    let file = match File::open(from) {
        Ok(file) => file,
        Err(e) => {
            eprintln!("cannot read {from}: {e}");
            std::process::exit(1);
        }
    };
    let built = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    let mut pack = Builder::new(lang, Kind::Lex, built);
    let mut skipped = Skipped::default();
    // A spelling can be claimed by two entries - the same word as a noun and as a verb - and
    // the pack refuses the second. Which of them wins is decided here rather than there: the
    // first, because the dump lists the commoner part of speech first.
    let mut taken = 0usize;
    let mut clashed = 0usize;
    for line in BufReader::new(file).lines() {
        let Ok(line) = line else { continue };
        let Some(read) = read_line(&line, lang, &mut skipped) else { continue };
        match pack.add(read.entry, &read.forms) {
            Ok(_) => taken += 1,
            Err(_) => clashed += 1,
        }
    }

    let counts = pack.counts();
    let bytes = match pack.finish() {
        Ok(bytes) => bytes,
        Err(e) => {
            eprintln!("cannot build the pack: {e:?}");
            std::process::exit(1);
        }
    };
    if let Err(e) = File::create(to).and_then(|mut f| f.write_all(&bytes)) {
        eprintln!("cannot write {to}: {e}");
        std::process::exit(1);
    }

    let sum = Sha256::digest(&bytes);
    // The manifest row, as JSON, so the job that publishes it does not have to parse prose.
    println!(
        "{{\"id\":\"lex-{lang}\",\"lang\":\"{lang}\",\"built\":{built},\
\"entries\":{},\"keys\":{},\"glosses\":{},\"bytes\":{},\"sha256\":\"{:x}\"}}",
        counts.entries,
        counts.keys,
        counts.glosses,
        bytes.len(),
        sum,
    );
    eprintln!(
        "{taken} entries, {} spellings, {} gloss terms, {} bytes. \
Left out: {clashed} spellings already claimed, {} of another language, {} with nothing to \
show, {} without a word, {} unreadable.",
        counts.keys,
        counts.glosses,
        bytes.len(),
        skipped.other_language,
        skipped.empty,
        skipped.nameless,
        skipped.unreadable,
    );
}
