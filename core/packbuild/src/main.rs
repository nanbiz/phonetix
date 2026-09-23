//! Build one language's pack.
//!
//!   packbuild <language> <extract.jsonl> <out.lexpack>       what its words mean
//!   packbuild ipa <language> <words.json.gz> <out.lexpack>   how its words are said
//!
//! Reads the extract a line at a time and writes the pack, then prints the manifest row: what
//! it holds, how big it is, and its checksum. A language's extract runs to gigabytes and the
//! pack is a fraction of it, so nothing but the pack is held in memory.

use std::fs::File;
use std::io::{BufRead, BufReader, Write};
use std::time::{SystemTime, UNIX_EPOCH};

use lexpack::{Builder, Kind};
use packbuild::{read_line_filed_as, Skipped};
use sha2::{Digest, Sha256};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() == 5 && args[1] == "ipa" {
        pronunciations(&args[2], &args[3], &args[4]);
        return;
    }
    // A fifth argument names the codes the dump files the language under, where it is not
    // the language's own: "sh" for Croatian, "nb,nn,no" for Norwegian.
    if args.len() != 4 && args.len() != 5 {
        eprintln!("packbuild <language> <extract.jsonl[.gz]> <out.lexpack> [filed,under,codes]");
        eprintln!("packbuild ipa <language> <words.json.gz> <out.lexpack>");
        std::process::exit(2);
    }
    let (lang, from, to) = (&args[1], &args[2], &args[3]);
    let filed: Vec<&str> = match args.get(4) {
        Some(codes) => codes.split(',').filter(|c| !c.is_empty()).collect(),
        None => vec![lang.as_str()],
    };

    // Read twice: once for every spelling a lemma lists among its forms, then to build. An
    // entry that only says it is a form of another word, where the other word already lists
    // it, is the same answer twice and is left out (see [packbuild::is_bare_form]).
    let mut listed: std::collections::HashSet<String> = std::collections::HashSet::new();
    {
        let mut ignored = Skipped::default();
        for line in BufReader::new(extract(from)).lines() {
            let Ok(line) = line else { continue };
            let Some(read) = read_line_filed_as(&line, &filed, &mut ignored) else {
                continue;
            };
            if !packbuild::is_bare_form(&read.entry) {
                listed.extend(
                    read.entry
                        .forms
                        .iter()
                        .map(|form| form.spelling.to_lowercase()),
                );
                listed.extend(read.forms.iter().map(|form| form.to_lowercase()));
            }
        }
    }
    let file = extract(from);
    let built = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let mut pack = Builder::new(lang, Kind::Lex, built);
    // English is never joined into: a reader of English is given the gloss itself.
    if lang == "en" {
        pack = pack.without_joins();
    }
    let mut skipped = Skipped::default();
    // A spelling that is two words - "book" as a noun and as a verb - is two entries under one
    // key, and the pack holds both. The order is the dump's, which lists the commoner part of
    // speech first, so a reader who does not choose still meets the likely one first.
    let mut taken = 0usize;
    let mut refused = 0usize;
    let mut repeated = 0usize;
    for line in BufReader::new(file).lines() {
        let Ok(line) = line else { continue };
        let Some(read) = read_line_filed_as(&line, &filed, &mut skipped) else {
            continue;
        };
        if packbuild::is_bare_form(&read.entry) && listed.contains(&read.entry.lemma.to_lowercase())
        {
            repeated += 1;
            continue;
        }
        match pack.add(read.entry, &read.forms) {
            Ok(_) => taken += 1,
            Err(_) => refused += 1,
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
Left out: {refused} the pack would not take, {repeated} forms their lemma lists, {} of \
another language, {} with nothing to show, {} without a word, {} unreadable.",
        counts.keys,
        counts.glosses,
        bytes.len(),
        skipped.other_language,
        skipped.empty,
        skipped.nameless,
        skipped.unreadable,
    );
}

/// An extract, opened for reading from the start.
///
/// Gzipped as it is published, or already unpacked. An extract of one language unpacks to
/// gigabytes, and unpacking it to disk first only to read it once is space for nothing.
fn extract(from: &str) -> Box<dyn std::io::Read> {
    let file = match File::open(from) {
        Ok(file) => file,
        Err(e) => {
            eprintln!("cannot read {from}: {e}");
            std::process::exit(1);
        }
    };
    if from.ends_with(".gz") {
        Box::new(flate2::read::MultiGzDecoder::new(file))
    } else {
        Box::new(file)
    }
}

/// Build a pack of how a language's words are said.
///
/// The source is the table the app used to carry as a compressed map of word to
/// transcription. It becomes a pack so that both platforms read a pronunciation the way they
/// read everything else, through the same reader, rather than one of them holding a format of
/// its own.
fn pronunciations(lang: &str, from: &str, to: &str) {
    let file = match File::open(from) {
        Ok(file) => file,
        Err(e) => {
            eprintln!("cannot read {from}: {e}");
            std::process::exit(1);
        }
    };
    let words: std::collections::BTreeMap<String, String> =
        match serde_json::from_reader(flate2::read::GzDecoder::new(BufReader::new(file))) {
            Ok(words) => words,
            Err(e) => {
                eprintln!("cannot read {from}: {e}");
                std::process::exit(1);
            }
        };

    let built = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let mut pack = Builder::new(lang, Kind::Ipa, built);
    let mut taken = 0;
    for (word, ipa) in &words {
        if word.is_empty() || ipa.is_empty() {
            continue;
        }
        let entry = lexpack::Entry {
            lemma: word.clone(),
            pos: String::new(),
            ipa: vec![ipa.clone()],
            tags: Vec::new(),
            senses: Vec::new(),
            // A pronunciation pack is a word and how it is said; the dump's inflection tables
            // are the lexicon's business and not this one's.
            forms: Vec::new(),
        };
        if pack.add::<&str>(entry, &[]).is_ok() {
            taken += 1;
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
    println!(
        "{{\"id\":\"ipa-{lang}\",\"lang\":\"{lang}\",\"built\":{built},\
\"entries\":{},\"keys\":{},\"glosses\":0,\"bytes\":{},\"sha256\":\"{:x}\"}}",
        counts.entries,
        counts.keys,
        bytes.len(),
        sum,
    );
    eprintln!("{taken} of {} words, {} bytes", words.len(), bytes.len());
}
