//! Which form of its word a spelling is, said by grammatical category, and the same word in
//! every other value of one of them.
//!
//! A pack keeps each lemma's table of forms, and each form's label is the places it fills in
//! that table: the tags the dictionary gives them, several places joined by "; " - Spanish
//! "anda" is "indicative present singular third-person; imperative informal second-person
//! singular". A card names the place a word on the page fills ("preterite, indicative, third
//! person singular of andar") and lets a reader move along one category of it with the rest
//! kept: the same person and mood in every tense, the same tense in every person.
//!
//! Which category a tag belongs to is decided here once, for every language, from the tag
//! itself: the dictionary's tags are one vocabulary across languages.

use lexpack::Entry;

/// The categories a form is described by, in the order a card names them. A tag of any other
/// category - "informal", "formal" say how a form is used to whom, not which it is - is left
/// out of a form's place.
pub const CATEGORIES: [&str; 12] = [
    "tense",
    "aspect",
    "mood",
    "voice",
    "case",
    "gender",
    "person",
    "number",
    "degree",
    "declension",
    "definiteness",
    "nonfinite",
];

/// The category a tag is a value of, as data/grammar.json files it (see
/// [crate::grammar_tags]), or nothing for a tag a form is not described by.
///
/// The conditional is filed there as a mood, which it is in a grammar of German; the Romance
/// tables list it among the tenses of the indicative, and the dictionary tags it so -
/// "conditional indicative" - so a cell with both is a tense of the indicative.
pub fn category_of(tag: &str) -> Option<&'static str> {
    let category = match tag {
        "conditional" => "tense",
        _ => crate::grammar_tags::category_of(tag)?,
    };
    CATEGORIES.contains(&category).then_some(category)
}

/// The order a grammar's own table lists the values of a category in, which is the order a
/// card offers them.
fn order_of(category: &str) -> Vec<&'static str> {
    let mut order = crate::grammar_tags::order_of(category).to_vec();
    if category == "tense" && !order.contains(&"conditional") {
        order.push("conditional");
    }
    order
}

/// One place in a table: the grammatical values that say which it is, each with its category.
pub type Place = Vec<(String, &'static str)>;

/// The places a label says a form fills, each with only the tags that name a grammatical value.
///
/// A cell naming two values of one category is the form for both, and so says nothing about
/// that category: Spanish "grandes" is "feminine masculine plural", and is neither one.
pub fn places(label: &str) -> Vec<Place> {
    label
        .split("; ")
        .map(|place| {
            let named: Place = place
                .split(' ')
                .filter_map(|tag| category_of(tag).map(|category| (tag.to_string(), category)))
                .collect();
            named
                .iter()
                .filter(|(_, category)| named.iter().filter(|(_, c)| c == category).count() == 1)
                .cloned()
                .collect::<Place>()
        })
        .filter(|place| !place.is_empty())
        .collect()
}

/// A place without "definite" or "indefinite" where the table spells them alike: a German
/// noun's table is laid out under the article, and "Hunde" is the same word either way, while
/// Swedish "hus" and "huset" are two forms of the noun itself.
fn without_idle_declension(place: Place, entry: &Entry) -> Place {
    let spelled = |tag: &str| -> Vec<String> {
        let mut out: Vec<String> = entry
            .forms
            .iter()
            .filter(|row| row.label.split([';', ' ']).any(|t| t == tag))
            .map(|row| row.spelling.to_lowercase())
            .collect();
        out.sort();
        out.dedup();
        out
    };
    let definite = spelled("definite");
    let indefinite = spelled("indefinite");
    // A table that spells a form alike under both is laid out under the article; one that
    // never does inflects the noun for it.
    let idle = definite.is_empty()
        || indefinite.is_empty()
        || definite.iter().any(|form| indefinite.contains(form));
    if !idle {
        return place;
    }
    place
        .into_iter()
        .filter(|(tag, _)| tag != "definite" && tag != "indefinite")
        .collect()
}

/// The value a place has in one category.
fn value_in<'a>(place: &'a Place, category: &str) -> Option<&'a str> {
    place
        .iter()
        .find(|(_, of)| *of == category)
        .map(|(tag, _)| tag.as_str())
}

/// What a spelling is as a form of a lemma, and where else in its table a reader can go.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Form {
    /// The place on the page's word: its values in the order a card names them.
    pub place: Place,
    /// Where the spelling's own ending starts, in characters: what it shares with the lemma is
    /// the stem, and the rest is what makes it this form.
    pub ending_at: usize,
    /// For each category the reader can move along, every value with the word in it, the
    /// page's own value among them.
    pub along: Vec<Along>,
    /// What the word means said in this place, in the reader's language: "he walked".
    pub said: Option<String>,
    /// The same without who does it, which is what takes the word's place on a page: "walked".
    pub bare: Option<String>,
}

/// One category of a form, and the word in each of its values with everything else kept.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Along {
    /// The category, or "person" for person and number together, which a table lays out as one
    /// grid: who, and how many.
    pub category: &'static str,
    pub forms: Vec<Other>,
}

/// The word in one other place.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Other {
    pub place: Place,
    pub spelling: String,
    /// Whether this is the place of the word on the page.
    pub here: bool,
    /// What the word means said in this place, in the reader's language: "he will walk".
    pub said: Option<String>,
}

/// The categories a determiner and the word it goes with agree in.
const AGREES: [&str; 3] = ["case", "gender", "number"];

/// Whether a place agrees with one a determiner fills: no category both name differs, and an
/// adjective is declined the way that determiner declines it - weak after a definite article,
/// mixed after an indefinite one.
fn agrees(place: &Place, determiner: &Place) -> bool {
    let declined = match value_in(determiner, "definiteness") {
        Some("definite") => Some("weak"),
        Some("indefinite") => Some("mixed"),
        _ => None,
    };
    let declension = match (value_in(place, "declension"), declined) {
        (Some(one), Some(other)) => one == other,
        _ => true,
    };
    declension
        && AGREES.iter().all(|category| {
            match (value_in(place, category), value_in(determiner, category)) {
                (Some(one), Some(other)) => one == other,
                _ => true,
            }
        })
}

/// Whether every value of one place is a value of another: the same cell listed with less said.
fn within(one: &Place, other: &Place) -> bool {
    one.len() < other.len() && one.iter().all(|value| other.contains(value))
}

/// The places a determiner spelled [spelling] can fill, from the entries the pack has for it:
/// the cells of its table spelled so, and the lemma's own where it is the lemma. German "den"
/// is the accusative masculine singular and the dative plural of "der".
pub fn determiner_places(spelling: &str, entries: &[Entry]) -> Vec<Place> {
    let lowered = spelling.to_lowercase();
    let mut out = Vec::new();
    for entry in entries {
        if !matches!(entry.pos.as_str(), "det" | "article") {
            continue;
        }
        for row in &entry.forms {
            if row.spelling.to_lowercase() == lowered {
                out.extend(places(&row.label));
            }
        }
    }
    // The lemma is the cell its table does not list, a determiner's nominative masculine
    // singular, and is taken to be only where no table lists the spelling at all: German "den"
    // has an entry of its own whose lemma is "den", and it is never a nominative.
    if out.is_empty()
        && entries.iter().any(|entry| {
            matches!(entry.pos.as_str(), "det" | "article")
                && entry.lemma.to_lowercase() == lowered
                && !entry.forms.is_empty()
        })
    {
        out.extend(places("nominative masculine singular"));
    }
    out.retain(|place| {
        AGREES
            .iter()
            .any(|category| value_in(place, category).is_some())
    });
    out
}

/// The form [spelling] is of [entry], where its table lists it: its place, its ending, and the
/// other places along each category. [agreeing] is every place the determiner before it can
/// fill, where there is one.
///
/// A noun, an adjective or a determiner is often listed in several places spelled alike, and
/// which of them the page means is decided by what it goes with: "größten" after "den" is the
/// accusative masculine singular or the dative plural, and never the genitive its table lists
/// first. So the places that disagree with the determiner are dropped, and what is named is only
/// what every place left says: a reading that names a case the word cannot be in is a wrong
/// answer, where one naming less is a true one.
pub fn form(spelling: &str, entry: &Entry, agreeing: &[Place]) -> Option<Form> {
    let lowered = spelling.to_lowercase();
    let row = entry
        .forms
        .iter()
        .find(|form| form.spelling.to_lowercase() == lowered)?;
    // The place that says the most about the word, the first of those where several say as
    // much: German "ging" is listed as "past" and as "first/third-person singular preterite",
    // and only the second says who.
    let all: Vec<Place> = places(&row.label)
        .into_iter()
        .map(|place| without_idle_declension(place, entry))
        .filter(|place| !place.is_empty())
        .collect();
    let nominal = matches!(
        entry.pos.as_str(),
        "noun" | "adj" | "det" | "pron" | "article" | "num"
    );
    if nominal {
        return nominal_form(spelling, entry, row, &all, agreeing);
    }
    let most = all.iter().map(Vec::len).max()?;
    let candidates: Vec<Place> = all
        .into_iter()
        .filter(|place| place.len() == most)
        .collect();
    // Among those, the third person, which running text is mostly written in: "ging" read on a
    // page is far more often "er ging" than "ich ging".
    let place = sorted(
        candidates
            .iter()
            .find(|place| value_in(place, "person") == Some("third-person"))
            .or(candidates.first())?
            .clone(),
    );
    Some(form_at(spelling, place, entry))
}

/// What every one of [places] says, each listing that is only a less full copy of another
/// left out: German "Herausforderungen" is listed as "plural" and as "dative plural", which is
/// one cell.
fn shared(places: &[&Place]) -> Place {
    let fuller: Vec<&Place> = places
        .iter()
        .filter(|place| !places.iter().any(|other| within(place, other)))
        .copied()
        .collect();
    let Some(first) = fuller.first() else {
        return Vec::new();
    };
    first
        .iter()
        .filter(|(tag, category)| {
            fuller
                .iter()
                .all(|place| value_in(place, category) == Some(tag.as_str()))
        })
        .cloned()
        .collect()
}

/// The form a noun, an adjective or a determiner is, read by the determiner before it.
///
/// It agrees with that determiner in case, gender and number, so only the cells that agree are
/// kept, and the cells the dictionary says are used with no article at all are not. What is
/// named is what every cell left says. Where the dictionary lists no cell that agrees - its
/// German adjective tables leave out the weak column - the word is in the determiner's own case,
/// gender and number, as far as every place the determiner can fill agrees on them. Which
/// declension a word after an article takes is not named: the dictionary does not file it by
/// the article, and calls "ein großer" strong.
fn nominal_form(
    spelling: &str,
    entry: &Entry,
    row: &lexpack::Form,
    all: &[Place],
    agreeing: &[Place],
) -> Option<Form> {
    let mut named =
        if agreeing.is_empty() {
            shared(&all.iter().collect::<Vec<_>>())
        } else {
            // Each cell by its own listing: "großer" is listed as the masculine nominative
            // singular with no article and again as the same cell after "ein", and only the
            // first is ruled out after an article.
            let cells: Vec<Place> = row
                .label
                .split("; ")
                .filter(|cell| !cell.split(' ').any(|tag| tag == "without-article"))
                .flat_map(places)
                .map(|place| without_idle_declension(place, entry))
                .filter(|place| !place.is_empty())
                .collect();
            let fitting: Vec<&Place> = cells
                .iter()
                .filter(|place| agreeing.iter().any(|determiner| agrees(place, determiner)))
                .collect();
            if fitting.is_empty() {
                let mut its: Place = shared(&agreeing.iter().collect::<Vec<_>>())
                    .into_iter()
                    .filter(|(_, category)| AGREES.contains(category))
                    .collect();
                its.extend(shared(&all.iter().collect::<Vec<_>>()).into_iter().filter(
                    |(_, category)| !AGREES.contains(category) && *category != "declension",
                ));
                its
            } else {
                shared(&fitting)
            }
        };
    if !agreeing.is_empty() {
        named.retain(|(_, category)| *category != "declension" && *category != "definiteness");
    }
    if named.is_empty() {
        return None;
    }
    Some(form_at(spelling, named, entry))
}

/// The form [spelling] is of [entry] in [place], a place its own entry names rather than its
/// lemma's table: Italian "corre" is filed as "third-person singular present indicative of
/// correre", and "correre"'s table does not list it.
pub fn form_at(spelling: &str, place: Place, entry: &Entry) -> Form {
    let place = sorted(place);
    let mut along = Vec::new();
    let personal = value_in(&place, "person").is_some();
    for category in CATEGORIES {
        // Number moves with person where a form has one, and alone where it has none: a
        // noun's singular and plural.
        if category == "nonfinite" || (category == "number" && personal) {
            continue;
        }
        if value_in(&place, category).is_none() {
            continue;
        }
        // Person and number go together: who, and how many, is one grid in every grammar.
        let moving: &[&str] = if category == "person" {
            &["person", "number"]
        } else {
            &[category]
        };
        let forms = others(&place, moving, entry, spelling);
        if forms.iter().filter(|other| !other.here).count() > 0 {
            along.push(Along { category, forms });
        }
    }
    Form {
        ending_at: stem_length(spelling, entry),
        place,
        along,
        said: None,
        bare: None,
    }
}

/// A place with its values in the order a card names them.
fn sorted(mut place: Place) -> Place {
    place.sort_by_key(|(_, category)| CATEGORIES.iter().position(|c| c == category));
    place
}

/// Every place of [entry]'s table that differs from [here] only in the [moving] categories,
/// with its spelling, in the order a grammar lists them.
///
/// The dictionary does not tag every cell alike - Spanish "anduve" is "first-person preterite
/// singular", with no mood, where "anduvo" says "indicative" - so a category a cell leaves out
/// is taken to agree; one it names differently does not. Where two cells fill one place, the
/// one naming the most of it is taken.
fn others(here: &Place, moving: &[&str], entry: &Entry, spelling: &str) -> Vec<Other> {
    let fixed: Vec<&(String, &'static str)> = here
        .iter()
        .filter(|(_, category)| !moving.contains(category))
        .collect();
    let mut found: Vec<(Place, String, usize)> = Vec::new();
    // The lemma is a cell of its own table that the table does not list: a noun's singular.
    // The lemma is a cell of its own table that the table does not list: a noun's nominative
    // singular, a determiner's or an adjective's masculine one.
    let cased = entry.forms.iter().any(|row| {
        places(&row.label)
            .iter()
            .any(|place| value_in(place, "case").is_some())
    });
    let lemma_row = lexpack::Form {
        spelling: entry.lemma.clone(),
        label: match (entry.pos == "noun", cased) {
            (true, true) => "nominative singular",
            (true, false) => "singular",
            (false, true) => "nominative masculine singular",
            (false, false) => "masculine singular",
        }
        .to_string(),
    };
    let nominal = matches!(
        entry.pos.as_str(),
        "noun" | "adj" | "det" | "pron" | "article"
    );
    // First, so that where it fits as well as a listed cell it is the one taken.
    let rows: Vec<&lexpack::Form> = nominal
        .then_some(&lemma_row)
        .into_iter()
        .chain(entry.forms.iter())
        .collect();
    for row in rows {
        for place in places(&row.label) {
            // A participle or an infinitive is not a tense of a finite verb.
            if place.iter().any(|(_, category)| *category == "nonfinite")
                != here.iter().any(|(_, category)| *category == "nonfinite")
            {
                continue;
            }
            if !moving
                .iter()
                .all(|category| value_in(&place, category).is_some())
            {
                continue;
            }
            // What the cell names agrees with the word on the page, and what it leaves out is
            // only what the dictionary is known to leave out: the mood of an indicative, the
            // article a German noun's table is laid out under, and the tense of a command,
            // which has none.
            let names_alike = place.iter().all(|(tag, category)| {
                moving.contains(category)
                    || fixed
                        .iter()
                        .find(|(_, of)| of == category)
                        .is_none_or(|(had, _)| had == tag || counterpart(had, tag, &place, moving))
            });
            let leaves_out_only = fixed.iter().all(|(tag, category)| {
                value_in(&place, category).is_some()
                    || *category == "mood"
                    || *category == "declension"
                    || (*category == "tense"
                        && tag == "present"
                        && value_in(&place, "mood") == Some("imperative"))
            });
            if !names_alike || !leaves_out_only {
                continue;
            }
            // How well the cell fits: every value of the word's it names counts for it, and every
            // value it names that the word has none of counts against it, so "Hund" stands for
            // the singular of "Hunde" rather than "Hundes", the singular of one case.
            let named = fixed
                .iter()
                .filter(|(tag, _)| place.iter().any(|(had, _)| had == tag))
                .count()
                * 10;
            let extra = place
                .iter()
                .filter(|(_, category)| {
                    !moving.contains(category) && !fixed.iter().any(|(_, of)| of == category)
                })
                .count();
            let named = (named + 10).saturating_sub(extra);
            let key: Vec<&str> = moving
                .iter()
                .filter_map(|category| value_in(&place, category))
                .collect();
            match found.iter_mut().find(|(had, _, _)| {
                moving
                    .iter()
                    .filter_map(|category| value_in(had, category))
                    .collect::<Vec<_>>()
                    == key
            }) {
                Some(slot) if slot.2 < named => *slot = (place, row.spelling.clone(), named),
                Some(_) => {}
                None => found.push((place, row.spelling.clone(), named)),
            }
        }
    }
    let rank = |place: &Place| -> Vec<usize> {
        moving
            .iter()
            .map(|category| {
                let value = value_in(place, category).unwrap_or("");
                order_of(category)
                    .iter()
                    .position(|v| *v == value)
                    .unwrap_or(usize::MAX)
            })
            .collect()
    };
    // Number before person, so the grid reads row by row: I, you, he, then we, you, they.
    found.sort_by_key(|(place, _, _)| {
        let mut key = rank(place);
        key.reverse();
        key
    });
    // One spelling under two names for one place - German "ging" is filed as the preterite
    // and as the past - is one place, under the name a grammar lists first.
    let mut seen: Vec<(String, String)> = Vec::new();
    found.retain(|(place, other, _)| {
        let value = moving
            .first()
            .and_then(|category| value_in(place, category))
            .unwrap_or("")
            .to_string();
        let same = seen
            .iter()
            .any(|(word, had)| word == other && synonyms(had, &value));
        seen.push((other.clone(), value));
        !same || other.to_lowercase() == spelling.to_lowercase()
    });
    let lowered = spelling.to_lowercase();
    let mine: Vec<&str> = moving
        .iter()
        .filter_map(|category| value_in(here, category))
        .collect();
    found
        .into_iter()
        .map(|(place, other, _)| {
            let theirs: Vec<&str> = moving
                .iter()
                .filter_map(|category| value_in(&place, category))
                .collect();
            Other {
                here: theirs == mine && unmarked(&other) == unmarked(&lowered),
                place: sorted(place),
                spelling: other,
                said: None,
            }
        })
        .collect()
}

/// Whether two values are one place under two names in the dictionary's tags.
fn synonyms(one: &str, other: &str) -> bool {
    matches!((one, other), ("preterite", "past") | ("past", "preterite"))
}

/// Whether a cell in another mood stands for the same time under another tense's name: the
/// subjunctive has no preterite, and what the preterite says, "anduvo", the subjunctive says
/// in its imperfect, "anduviera".
fn counterpart(had: &str, tag: &str, place: &Place, moving: &[&str]) -> bool {
    moving.contains(&"mood")
        && value_in(place, "mood") == Some("subjunctive")
        && matches!(
            (had, tag),
            ("preterite", "imperfect") | ("past", "imperfect")
        )
}

/// Where a form's own ending starts: after the stem every form of its word shares, and never
/// past what it shares with the lemma. "corre" shares all of itself with "correr", and its
/// ending is still the "e" that sets it apart from "corro", "corres" and "corrí".
fn stem_length(spelling: &str, entry: &Entry) -> usize {
    let lemma = entry.lemma.to_lowercase();
    // Only the word's own inflections: a German noun's table also lists other words beside it
    // - "Hündin", "Rüde", "Hündchen" under "Hund" - and an irregular spelling a dictionary
    // marks as nonstandard.
    const ASIDE: [&str; 7] = [
        "diminutive",
        "augmentative",
        "nonstandard",
        "error-unknown-tag",
        "archaic",
        "obsolete",
        "dialectal",
    ];
    let inflected = |label: &str| {
        let first = label.split("; ").next().unwrap_or("");
        !first.split(' ').any(|tag| ASIDE.contains(&tag))
            && places(first).first().is_some_and(|place| {
                place.iter().any(|(_, category)| {
                    matches!(
                        *category,
                        "case" | "number" | "person" | "tense" | "mood" | "nonfinite"
                    )
                })
            })
    };
    entry
        .forms
        .iter()
        .filter(|row| !row.spelling.contains(' ') && inflected(&row.label))
        .fold(shared_start(spelling, &entry.lemma), |stem, row| {
            stem.min(shared_start(&row.spelling, &lemma))
        })
}

/// A spelling without the marks some dictionaries add to show where the stress falls:
/// Italian tables write "córre" for the "corre" on a page.
fn unmarked(spelling: &str) -> String {
    spelling
        .to_lowercase()
        .chars()
        .map(|c| match c {
            'á' | 'à' => 'a',
            'é' | 'è' => 'e',
            'í' | 'ì' => 'i',
            'ó' | 'ò' => 'o',
            'ú' | 'ù' => 'u',
            other => other,
        })
        .collect()
}

/// How many characters two words begin with alike.
fn shared_start(spelling: &str, lemma: &str) -> usize {
    spelling
        .to_lowercase()
        .chars()
        .zip(lemma.to_lowercase().chars())
        .take_while(|(one, other)| one == other)
        .count()
}

#[cfg(test)]
mod tests {
    /// A form read with no determiner before it.
    fn form_alone(spelling: &str, entry: &Entry) -> Option<Form> {
        form(spelling, entry, &[])
    }

    use super::*;
    use lexpack::Form as Row;

    fn andar() -> Entry {
        let rows = [
            ("ando", "first-person indicative present singular"),
            ("andas", "indicative informal present second-person singular"),
            ("anda", "indicative present singular third-person; imperative informal second-person singular"),
            ("andamos", "first-person indicative plural present"),
            ("andan", "indicative plural present third-person"),
            ("anduve", "first-person preterite singular"),
            ("anduviste", "indicative preterite second-person singular"),
            ("anduvo", "indicative preterite singular third-person"),
            ("anduvimos", "first-person indicative plural preterite"),
            ("anduvieron", "indicative plural preterite third-person"),
            ("andaba", "first-person imperfect indicative singular; imperfect indicative singular third-person"),
            ("andará", "future indicative singular third-person"),
            ("andaría", "conditional first-person indicative singular; conditional indicative singular third-person"),
            ("ande", "first-person present singular subjunctive; present singular subjunctive third-person"),
            ("anduviera", "first-person imperfect singular subjunctive; imperfect singular subjunctive third-person"),
            ("andado", "participle past"),
        ];
        Entry {
            lemma: "andar".to_string(),
            pos: "verb".to_string(),
            forms: rows
                .iter()
                .map(|(spelling, label)| Row {
                    spelling: spelling.to_string(),
                    label: label.to_string(),
                })
                .collect(),
            ..Entry::default()
        }
    }

    fn values(place: &Place) -> Vec<&str> {
        place.iter().map(|(tag, _)| tag.as_str()).collect()
    }

    #[test]
    fn a_form_is_named_by_its_categories_and_its_ending() {
        let found = form_alone("anduvo", &andar()).unwrap();
        assert_eq!(
            values(&found.place),
            ["preterite", "indicative", "third-person", "singular"]
        );
        assert_eq!(found.ending_at, 3, "and|uvo");
        assert_eq!(form_alone("anda", &andar()).unwrap().ending_at, 3, "and|a");
    }

    #[test]
    fn along_tense_keeps_the_person_and_the_mood() {
        let found = form_alone("anduvo", &andar()).unwrap();
        let tense = found.along.iter().find(|a| a.category == "tense").unwrap();
        let words: Vec<&str> = tense.forms.iter().map(|o| o.spelling.as_str()).collect();
        assert_eq!(words, ["anda", "andaba", "anduvo", "andará", "andaría"]);
        assert!(
            tense
                .forms
                .iter()
                .find(|o| o.spelling == "anduvo")
                .unwrap()
                .here
        );
    }

    #[test]
    fn along_mood_keeps_the_tense_and_the_person() {
        let found = form_alone("anda", &andar()).unwrap();
        let mood = found.along.iter().find(|a| a.category == "mood").unwrap();
        let words: Vec<&str> = mood.forms.iter().map(|o| o.spelling.as_str()).collect();
        assert_eq!(
            words,
            ["anda", "ande"],
            "present, third person: indicative and subjunctive"
        );
    }

    #[test]
    fn along_person_is_the_grid_of_who_and_how_many() {
        let found = form_alone("anduvo", &andar()).unwrap();
        let person = found.along.iter().find(|a| a.category == "person").unwrap();
        let words: Vec<&str> = person.forms.iter().map(|o| o.spelling.as_str()).collect();
        assert_eq!(
            words,
            ["anduve", "anduviste", "anduvo", "anduvimos", "anduvieron"]
        );
    }

    fn entry(lemma: &str, pos: &str, rows: &[(&str, &str)]) -> Entry {
        Entry {
            lemma: lemma.to_string(),
            pos: pos.to_string(),
            forms: rows
                .iter()
                .map(|(spelling, label)| Row {
                    spelling: spelling.to_string(),
                    label: label.to_string(),
                })
                .collect(),
            ..Entry::default()
        }
    }

    fn along<'a>(found: &'a Form, category: &str) -> Vec<&'a str> {
        found
            .along
            .iter()
            .find(|a| a.category == category)
            .map(|a| a.forms.iter().map(|o| o.spelling.as_str()).collect())
            .unwrap_or_default()
    }

    #[test]
    fn a_preterite_has_no_command_and_says_its_subjunctive_in_the_imperfect() {
        let found = form_alone("anduvo", &andar()).unwrap();
        assert_eq!(along(&found, "mood"), ["anduvo", "anduviera"]);
    }

    #[test]
    fn a_form_listed_for_several_persons_is_read_as_the_third() {
        let gehen = entry(
            "gehen",
            "verb",
            &[
                (
                    "ging",
                    "past; first-person preterite singular; preterite singular third-person",
                ),
                ("gingst", "preterite second-person singular"),
                ("geht", "present singular third-person"),
            ],
        );
        let found = form_alone("ging", &gehen).unwrap();
        assert_eq!(
            values(&found.place),
            ["preterite", "third-person", "singular"]
        );
        assert_eq!(along(&found, "tense"), ["geht", "ging"]);
    }

    #[test]
    fn a_nouns_singular_is_its_lemma_and_an_article_it_is_listed_under_is_no_form() {
        let hund = entry(
            "Hund",
            "noun",
            &[
                (
                    "Hundes",
                    "genitive singular definite; genitive singular indefinite",
                ),
                (
                    "Hunde",
                    "nominative plural definite; accusative plural definite",
                ),
                ("Hunden", "dative plural definite"),
            ],
        );
        let found = form_alone("Hunde", &hund).unwrap();
        // Nominative or accusative, which nothing around it says: only what both are.
        assert_eq!(values(&found.place), ["plural"]);
        assert_eq!(along(&found, "number"), ["Hund", "Hunde"]);
    }

    #[test]
    fn an_adjectives_masculine_is_its_lemma() {
        let bonito = entry(
            "bonito",
            "adj",
            &[("bonita", "feminine"), ("bonitos", "masculine plural")],
        );
        assert_eq!(
            along(&form_alone("bonita", &bonito).unwrap(), "gender"),
            ["bonito", "bonita"]
        );
    }

    #[test]
    fn a_form_spelled_alike_in_several_cases_is_read_by_its_determiner() {
        let gross = entry(
            "groß",
            "adj",
            &[(
                "größten",
                "superlative strong genitive masculine singular; \
                 superlative weak accusative masculine singular; \
                 superlative weak dative plural; superlative strong dative plural",
            )],
        );
        let der = entry(
            "der",
            "article",
            &[("den", "accusative masculine singular; dative plural")],
        );
        let den = determiner_places("den", &[der]);
        let found = form("größten", &gross, &den).unwrap();
        // The genitive is gone; accusative singular and dative plural are both still possible,
        // so neither is named.
        assert!(values(&found.place).contains(&"superlative"));
        assert!(!values(&found.place).contains(&"genitive"));
        assert!(!values(&found.place).contains(&"singular"));
        let alone = form_alone("größten", &gross).unwrap();
        assert!(!values(&alone.place).contains(&"genitive"));
    }

    #[test]
    fn a_nouns_case_comes_from_its_article() {
        let frage = entry(
            "Herausforderung",
            "noun",
            &[(
                "Herausforderungen",
                "nominative plural; genitive plural; dative plural; accusative plural",
            )],
        );
        let der = entry(
            "der",
            "article",
            &[("den", "accusative masculine singular; dative plural")],
        );
        let found = form(
            "Herausforderungen",
            &frage,
            &determiner_places("den", &[der]),
        )
        .unwrap();
        assert_eq!(values(&found.place), ["dative", "plural"]);
    }

    #[test]
    fn a_cell_for_both_genders_names_neither() {
        let grande = entry("grande", "adj", &[("grandes", "feminine masculine plural")]);
        assert_eq!(
            values(&form_alone("grandes", &grande).unwrap().place),
            ["plural"]
        );
    }

    #[test]
    fn a_word_its_table_does_not_list_has_no_form() {
        assert_eq!(form_alone("andar", &andar()), None);
    }
}
