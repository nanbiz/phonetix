//! Build one language's pack.
//!
//!   packbuild <language> <extract.jsonl> <out.lexpack>       what its words mean
//!   packbuild ipa <language> <words.json.gz> <out.lexpack>   how its words are said
//!   packbuild define <language> <edition.jsonl.gz> <out dir>   words in an edition's language
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
    let mut args: Vec<String> = std::env::args().collect();
    // How often each word is met, where a list is given: `--frequencies=<words and counts>`.
    let counts: std::collections::HashMap<String, u64> = match args
        .iter()
        .position(|arg| arg.starts_with("--frequencies="))
    {
        Some(at) => {
            let path = args.remove(at)["--frequencies=".len()..].to_string();
            frequencies(&path)
        }
        None => std::collections::HashMap::new(),
    };
    // The English Wiktionary's translation tables into this language, where they are given:
    // `--tables=<rows.tsv.gz>[,<more>]`, as tools/split_translations.py writes them.
    let tables: Vec<String> = match args.iter().position(|arg| arg.starts_with("--tables=")) {
        Some(at) => args.remove(at)["--tables=".len()..]
            .split(',')
            .filter(|path| !path.is_empty())
            .map(str::to_string)
            .collect(),
        None => Vec::new(),
    };
    if args.len() == 5 && args[1] == "define" {
        definitions(&args[2], &args[3], &args[4]);
        return;
    }
    if args.len() == 5 && args[1] == "ipa" {
        pronunciations(&args[2], &args[3], &args[4]);
        return;
    }
    // A fifth argument names the codes the dump files the language under, where it is not
    // the language's own: "sh" for Croatian, "nb,nn,no" for Norwegian.
    if args.len() != 4 && args.len() != 5 {
        eprintln!(
            "packbuild <language> <extract.jsonl[.gz]> <out.lexpack> [filed,under,codes] \
[--frequencies=<list>]"
        );
        eprintln!("packbuild ipa <language> <words.json.gz> <out.lexpack>");
        eprintln!("packbuild define <edition language> <edition.jsonl.gz> <out dir>");
        std::process::exit(2);
    }
    let (lang, from, to) = (&args[1], &args[2], &args[3]);
    let filed: Vec<&str> = match args.get(4) {
        Some(codes) => codes.split(',').filter(|c| !c.is_empty()).collect(),
        None => vec![lang.as_str()],
    };

    // Read twice: once for every spelling a lemma lists among its forms, then to build. An
    // entry that only says it is a form of another word, where the other word already lists
    // it, is the same answer twice and is left out (see [packbuild::is_bare_form]). The first
    // read also counts how many entries list each spelling, since one listed by too many is
    // not a form of any of them and is dropped from all (see [packbuild::CROWDED]).
    let mut listed: std::collections::HashSet<String> = std::collections::HashSet::new();
    let mut tally: std::collections::HashMap<String, u32> = std::collections::HashMap::new();
    {
        let mut ignored = Skipped::default();
        for line in BufReader::new(extract(from)).lines() {
            let Ok(line) = line else { continue };
            let Some(read) = read_line_filed_as(&line, &filed, &mut ignored) else {
                continue;
            };
            packbuild::tally_forms(&read.entry, &mut tally);
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
    listed.retain(|spelling| {
        tally
            .get(spelling)
            .is_none_or(|&count| count as usize <= packbuild::CROWDED)
    });
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
        let mut entry = read.entry;
        packbuild::without_crowded(&mut entry, &tally);
        if let Some(count) = packbuild::how_often(&entry, &counts) {
            entry.tags.push(format!("count:{count}"));
        }
        match pack.add(entry, &read.forms) {
            Ok(_) => taken += 1,
            Err(_) => refused += 1,
        }
    }

    for table in &tables {
        let rows = BufReader::new(extract(table))
            .lines()
            .map_while(Result::ok)
            .filter_map(|line| packbuild::Translated::parse(&line));
        let reached = packbuild::file_translations(&mut pack, lang, rows);
        eprintln!("{reached} translations from {table}");
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

/// A frequency list, a word and how many times it was met on each line, lowercased.
fn frequencies(path: &str) -> std::collections::HashMap<String, u64> {
    let text = match std::fs::read_to_string(path) {
        Ok(text) => text,
        Err(e) => {
            eprintln!("cannot read {path}: {e}");
            std::process::exit(1);
        }
    };
    let mut counts = std::collections::HashMap::new();
    for line in text.lines() {
        let mut parts = line.split_whitespace();
        let (Some(word), Some(count)) = (parts.next(), parts.next()) else {
            continue;
        };
        if let Ok(count) = count.parse::<u64>() {
            *counts.entry(word.to_lowercase()).or_insert(0) += count;
        }
    }
    counts
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

/// Build packs of what words mean, from a Wiktionary edition: for the edition's own language
/// "def-<language>", what its words mean in it ("pensar" is "Ejercitar el intelecto para
/// explicarse o imaginarse algo"), for a reader who reads the language and to whom its English
/// glosses are a translation they did not ask for; and for every other language the edition
/// describes "<language>-in-<edition>", its words in the edition's language, written by people
/// who speak both, where going through English glosses made Spanish "hacer", "to do", into
/// German "ja".
///
/// One pass over the edition, which runs to gigabytes. A language gets a pack where the
/// edition describes at least [FEWEST] of its words and [out] holds its own pack beside it,
/// since what is asked of these is the lemma the language's own pack has worked out. Keyed by
/// the word alone, with no gloss index, since nothing is joined to them.
fn definitions(edition: &str, from: &str, out: &str) {
    const FEWEST: usize = 300;
    let built = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let mut found: std::collections::BTreeMap<String, Vec<lexpack::Entry>> =
        std::collections::BTreeMap::new();
    for line in BufReader::new(extract(from)).lines() {
        let Ok(line) = line else { continue };
        let Ok(value) = serde_json::from_str::<serde_json::Value>(&line) else {
            continue;
        };
        let Some(lang) = value.get("lang_code").and_then(|v| v.as_str()) else {
            continue;
        };
        if lang != edition
            && !std::path::Path::new(out)
                .join(format!("{lang}.pack"))
                .exists()
        {
            continue;
        }
        if let Some(entry) = defined(&value) {
            found.entry(lang.to_string()).or_default().push(entry);
        }
    }
    for (lang, entries) in found {
        if entries.len() < FEWEST && lang != edition {
            continue;
        }
        let id = if lang == edition {
            format!("def-{lang}")
        } else {
            format!("{lang}-in-{edition}")
        };
        let mut pack = Builder::new(&id, Kind::Lex, built).without_joins();
        let mut taken = 0usize;
        for entry in entries {
            if pack.add::<&str>(entry, &[]).is_ok() {
                taken += 1;
            }
        }
        let counts = pack.counts();
        let bytes = match pack.finish() {
            Ok(bytes) => bytes,
            Err(e) => {
                eprintln!("{id}: cannot build the pack: {e:?}");
                continue;
            }
        };
        let to = std::path::Path::new(out).join(format!("{id}.pack"));
        if let Err(e) = File::create(&to).and_then(|mut f| f.write_all(&bytes)) {
            eprintln!("cannot write {}: {e}", to.display());
            std::process::exit(1);
        }
        let sum = Sha256::digest(&bytes);
        let row = format!(
            "{{\"id\":\"{id}\",\"lang\":\"{id}\",\"built\":{built},\
\"entries\":{},\"keys\":{},\"glosses\":0,\"bytes\":{},\"sha256\":\"{:x}\"}}",
            counts.entries,
            counts.keys,
            bytes.len(),
            sum,
        );
        let manifest = std::path::Path::new(out).join(format!("{id}.pack.json"));
        if let Err(e) = std::fs::write(&manifest, format!("{row}\n")) {
            eprintln!("cannot write {}: {e}", manifest.display());
            std::process::exit(1);
        }
        println!("{row}");
        eprintln!("{id}: {taken} words, {} bytes", bytes.len());
    }
}

/// One entry of an edition as a pack holds it: its word, its kind, and up to eight senses,
/// each the narrowest of its nested glosses. Nothing for a page that only says which form of
/// another word this is, since what is asked of the pack is the lemma.
fn defined(value: &serde_json::Value) -> Option<lexpack::Entry> {
    /// What a card can show of a word, and what a sentence of definition runs to at most.
    const SENSES: usize = 8;
    const LONGEST: usize = 320;
    let word = value.get("word").and_then(|v| v.as_str())?;
    let pos = value.get("pos").and_then(|v| v.as_str())?;
    let tagged = |v: &serde_json::Value| {
        v.get("tags")
            .and_then(|t| t.as_array())
            .is_some_and(|t| t.iter().any(|t| t.as_str() == Some("form-of")))
    };
    if tagged(value) {
        return None;
    }
    let senses: Vec<lexpack::Sense> = value
        .get("senses")
        .and_then(|v| v.as_array())
        .into_iter()
        .flatten()
        .filter(|sense| !tagged(sense) && sense.get("form_of").is_none())
        .filter_map(|sense| {
            let gloss = sense.get("glosses")?.as_array()?.last()?.as_str()?.trim();
            // Without the number some editions lead a sense with, "[1] dein".
            let gloss = gloss
                .strip_prefix('[')
                .and_then(|rest| rest.split_once(']'))
                .filter(|(number, _)| number.chars().all(|c| c.is_ascii_digit() || c == ','))
                .map(|(_, rest)| rest.trim())
                .unwrap_or(gloss);
            // A sense that only points at a sense of another word, "Estar₉.", says nothing
            // by itself.
            let pointer = gloss.split_whitespace().count() == 1
                && gloss
                    .chars()
                    .any(|c| ('\u{2080}'..='\u{2089}').contains(&c));
            (!gloss.is_empty() && !pointer).then(|| lexpack::Sense {
                gloss: gloss.chars().take(LONGEST).collect(),
                ..lexpack::Sense::default()
            })
        })
        .take(SENSES)
        .collect();
    if word.is_empty() || senses.is_empty() {
        return None;
    }
    Some(lexpack::Entry {
        lemma: word.to_string(),
        pos: pos.to_string(),
        senses,
        ..lexpack::Entry::default()
    })
}
