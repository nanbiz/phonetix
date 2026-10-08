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

/// The categories a form is described by, in the order a card names them.
pub const CATEGORIES: [&str; 9] = [
    "tense",
    "mood",
    "case",
    "gender",
    "person",
    "number",
    "degree",
    "declension",
    "nonfinite",
];

/// The category a tag is a value of, or nothing for a tag that is not a grammatical value -
/// "informal", "vos-form", "negative" say how a form is used rather than which it is.
pub fn category_of(tag: &str) -> Option<&'static str> {
    Some(match tag {
        "indicative" | "subjunctive" | "imperative" | "optative" | "jussive" => "mood",
        "present" | "preterite" | "imperfect" | "future" | "conditional" | "perfect"
        | "pluperfect" | "past" | "aorist" | "future-perfect" | "past-perfect" => "tense",
        "first-person" | "second-person" | "third-person" => "person",
        "singular" | "plural" | "dual" => "number",
        "nominative" | "accusative" | "dative" | "genitive" | "ablative" | "instrumental"
        | "locative" | "vocative" | "partitive" | "prepositional" | "essive" | "translative"
        | "inessive" | "elative" | "illative" | "adessive" | "allative" | "abessive"
        | "comitative" | "instructive" | "oblique" => "case",
        "masculine" | "feminine" | "neuter" | "common" => "gender",
        "comparative" | "superlative" => "degree",
        "strong" | "weak" | "mixed" | "definite" | "indefinite" => "declension",
        "infinitive" | "participle" | "gerund" | "supine" => "nonfinite",
        _ => return None,
    })
}

/// The order a grammar's own table lists the values of a category in, which is the order a
/// card offers them.
fn order_of(category: &str) -> &'static [&'static str] {
    match category {
        "tense" => &[
            "present",
            "preterite",
            "imperfect",
            "past",
            "perfect",
            "pluperfect",
            "future",
            "future-perfect",
            "conditional",
        ],
        "mood" => &["indicative", "subjunctive", "imperative"],
        "person" => &["first-person", "second-person", "third-person"],
        "number" => &["singular", "plural", "dual"],
        "case" => &[
            "nominative",
            "accusative",
            "dative",
            "genitive",
            "ablative",
            "instrumental",
            "locative",
            "vocative",
            "partitive",
        ],
        "gender" => &["masculine", "feminine", "neuter", "common"],
        "degree" => &["comparative", "superlative"],
        "declension" => &["strong", "weak", "mixed", "definite", "indefinite"],
        _ => &[],
    }
}

/// One place in a table: the grammatical values that say which it is, each with its category.
pub type Place = Vec<(String, &'static str)>;

/// The places a label says a form fills, each with only the tags that name a grammatical value.
pub fn places(label: &str) -> Vec<Place> {
    label
        .split("; ")
        .map(|place| {
            place
                .split(' ')
                .filter_map(|tag| category_of(tag).map(|category| (tag.to_string(), category)))
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

/// The form [spelling] is of [entry], where its table lists it: its place, its ending, and the
/// other places along each category.
pub fn form(spelling: &str, entry: &Entry) -> Option<Form> {
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
    let most = all.iter().map(Vec::len).max()?;
    // Among those, the third person, which running text is mostly written in: "ging" read on a
    // page is far more often "er ging" than "ich ging".
    let candidates: Vec<Place> = all
        .into_iter()
        .filter(|place| place.len() == most)
        .collect();
    let place = sorted(
        candidates
            .iter()
            .find(|place| value_in(place, "person") == Some("third-person"))
            .or(candidates.first())?
            .clone(),
    );
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
    Some(Form {
        ending_at: shared_start(spelling, &entry.lemma),
        place,
        along,
        said: None,
    })
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
                here: theirs == mine && other.to_lowercase() == lowered,
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

/// How many characters two words begin with alike, which is where a form's ending starts.
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
        let found = form("anduvo", &andar()).unwrap();
        assert_eq!(
            values(&found.place),
            ["preterite", "indicative", "third-person", "singular"]
        );
        assert_eq!(found.ending_at, 3, "and|uvo");
    }

    #[test]
    fn along_tense_keeps_the_person_and_the_mood() {
        let found = form("anduvo", &andar()).unwrap();
        let tense = found.along.iter().find(|a| a.category == "tense").unwrap();
        let words: Vec<&str> = tense.forms.iter().map(|o| o.spelling.as_str()).collect();
        assert_eq!(words, ["anda", "anduvo", "andaba", "andará", "andaría"]);
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
        let found = form("anda", &andar()).unwrap();
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
        let found = form("anduvo", &andar()).unwrap();
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
        let found = form("anduvo", &andar()).unwrap();
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
        let found = form("ging", &gehen).unwrap();
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
        let found = form("Hunde", &hund).unwrap();
        assert_eq!(values(&found.place), ["nominative", "plural"]);
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
            along(&form("bonita", &bonito).unwrap(), "gender"),
            ["bonito", "bonita"]
        );
    }

    #[test]
    fn a_word_its_table_does_not_list_has_no_form() {
        assert_eq!(form("andar", &andar()), None);
    }
}
