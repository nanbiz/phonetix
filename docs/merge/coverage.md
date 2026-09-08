# What the dictionary packs would actually cover

Measured from kaikki.org's own index pages on 2026-09-08, against dumps dated 2026-09-01.
The proposal keys a pack by source language and Wiktionary edition rather than by language
pair, so this is the question of whether those cells hold anything.

## What exists

Twenty-one editions: English plus twenty others. **There is no Swedish edition and no Arabic
edition**, so those cannot be an edition at all, only a source language inside another. Kaikki
describes every non-English edition as work in progress.

## What they hold

Senses per source language, as published. The English edition against the four largest others:

| Source | English ed. | French ed. | Chinese ed. | German ed. | Spanish ed. |
| --- | ---: | ---: | ---: | ---: | ---: |
| English | 1,787,236 | 224,758 | 104,203 | 80,041 | 35,021 |
| German | 633,412 | 2,201,519 | 152,242 | *self* | 5,781 |
| Spanish | 875,726 | 259,054 | 170,049 | 9,448 | *self* |
| French | 459,894 | *self* | 96,489 | 16,914 | 11,045 |
| Italian | 719,680 | 1,309,697 | 194,980 | 28,470 | 7,734 |
| Russian | 492,474 | 363,189 | 98,250 | 4,985 | 1,505 |
| Japanese | 237,328 | 37,547 | 93,460 | 559 | none listed |
| Korean | 81,949 | 14,888 | 199,979 | none listed | 532 |

Of 112 cells that are not an edition's own language, 36 hold more than thirty thousand senses
and 14 hold more than a hundred thousand. Two editions, French and Chinese, account for well
over half of those. The German, Spanish and Korean editions are single-language packs in
practice: the German edition's Spanish section is one percent the size of the English edition's.

Read the French and Chinese numbers as generous. Those communities generate a page per
inflected form, so their senses per word run two to four times the English edition's.

Coverage of IPA, of translations and of definitions is not published by any edition. Getting
those needs the dumps themselves.

## What follows

The pair problem is not solved by the edition key alone. It bounds the matrix and then most of
the matrix turns out to be empty, so for most pairs the dictionary tier would miss and machine
translation would carry the product.

The IPA layer is unaffected: it is built per source language out of the English edition, which
is the rich one, which is where the current 54 dictionaries come from.

## The translations field, measured

Tested against kaikki's own per-word pages on 2026-09-08, since the idea that one rich
extraction could serve every pair rests on it.

**A `translations` field exists only on English lemmas.** The English edition's entry for
"house" carries sixteen translations blocks; its entries for Spanish "perro" and Spanish
"libro" carry none at all. What the English edition gives for a Spanish word is its senses
glossed in English, and nothing else. So a Spanish word does not carry a German translation
there, and reading one off the source entry is not possible.

**The pivot through English is possible, and it is rich.** The English entry for "house" holds
translations into 436 languages, each carrying the `sense` string it belongs to, a sense
distribution weight, and gender tags where the target language has them. So the chain is two
hops inside one edition: the Spanish entry gives an English gloss, and the English lemma for
that gloss gives the target language.

What that costs is sense fidelity at the joint. The Spanish sense arrives as an English phrase,
the English lemma's translations are tagged by their own sense strings, and matching one to the
other is a join on meaning rather than on an identifier. It is lossy in a way a direct field
would not have been, and how lossy is the thing to sample.

What it buys is shape: the translations table is a property of English lemmas alone, so it is
one shared asset every source pack points into rather than anything per pair.

## The pivot join, sampled

Twenty common Spanish nouns, run through both hops against kaikki's own pages on 2026-09-08,
taking the first German translation on the English lemma and ignoring the sense it is tagged
with, which is the naive join.

Every one produced a German word: coverage through the pivot was twenty of twenty. Three of
the twenty were wrong, and all three failed the same way, by landing on a sibling sense of the
English lemma:

| Spanish | English gloss | German taken | should be |
| --- | --- | --- | --- |
| perro | dog | Rüde (a male dog) | Hund |
| camino | way, route | Weise (a manner) | Weg |
| silla | chair | Sessel (an armchair) | Stuhl |

So the naive join is about eighty-five percent right, and what it gets wrong is a confident
wrong gloss rather than a miss, which is the failure that matters. Every translation on an
English lemma carries the sense string it belongs to, and every sense of the source entry
carries its English gloss, so the build has both halves of the join and this sample measures
what happens when it does not use them. It is a floor, not an estimate of the finished thing.

What it does not measure: rarer words, verbs and adjectives rather than nouns, and languages
whose entries are thinner than Spanish. The defined sample still has to be run.

## Matching senses by their words does not work

The obvious repair for the naive join is to use the sense each translation is tagged with, and
match it against the English gloss the source sense carried. Tried on twenty words: it changed
four answers and every change was worse (rennen to "ins Rennen schicken", Schnee to Schneefall,
Kraft to erzwingen), and it repaired none of the three known failures.

The reason is that the two strings are not written to be compared. A source sense arrives as
"dog (the species Canis familiaris)" and the translation is tagged "male canine": correct, and
sharing no words at all.

What the data does carry is `_dis1` on every translation, a distribution over the senses of the
English lemma, so a translation says numerically which sense it belongs to rather than
describing it in prose. Joining through that, and separately deciding which English sense the
source gloss means, is the approach worth measuring. String similarity is not.

(Two rows of that sample are void: the test words were written without accents, so `rapido`
found no entry and `sueno` matched a different word.)

## Joining through the sense distribution does not fix it either

Every translation carries `_dis1`, a distribution over the senses of the English lemma, so the
second half of the join is answerable from the data. Tried: pick the English sense the source
gloss means, then take the German translation whose distribution peaks on that sense. It picked
Weise for camino through an English sense reading "Personal interaction", and "ins Rennen
schicken" for correr through one reading "To strike (the ball)".

The failure is in the first half, not the second. Deciding **which English sense a source gloss
means** is the hard part: a source gloss is two or three words ("way, route", "chair") and an
English sense is a full definition, so they share almost no vocabulary and a lexical match falls
through to whichever sense came first.

So both repairs measure worse than taking the first translation, and the naive join at about
eighty-five percent is the best measured so far. What is needed is a way to say that "way,
route" means one particular sense of "way", which is semantic matching rather than string
comparison, and nothing in the data does it for us.

That is worth weighing against what the tier is for. A dictionary answer is presented as the
answer while machine translation is labelled a guess. A dictionary tier that is confidently
wrong one time in seven is arguably worse than one that says it is guessing, so if this cannot
be raised, the honest options are to show the English pivot alongside the target word, or to
mark pivoted glosses as the inference they are rather than as dictionary provenance.

## Joining on the English gloss both sides already carry

The pivot went looking for a target word inside the English lemma's translations. There is a
better join, and it needs no translations at all: **every language's entries in the English
edition are glossed in English**, so a source entry and a target entry can be matched to each
other directly.

Spanish `perro` glosses "dog". German `Hund` glosses "dog, hound". Spanish `silla` glosses
"chair", German `Stuhl` glosses "a chair (to sit on)". Spanish `camino` glosses "way, route",
German `Weg` glosses "route, way (to get from one place to another)".

All three of the failures the naive pivot produced come out right this way. The words that
misled it are separated by **how much** they share rather than by whether they share anything,
which is a distinction worth being exact about: `Sessel` glosses "armchair, easy chair", which
offers no term the gloss "chair" carries, but `Weise` glosses "way, manner" and `camino` glosses
"way, route", so those two do share a term. What tells `Weg` from `Weise` is that `Weg` glosses
"route, way" and shares both. So the join is a count of shared terms and the decision is made by
comparing counts, with no answer at all when the best does not clearly beat the runner up.

Writing that as a test is what caught it: a first implementation asked whether a term was shared
at all and matched `camino` to `Weise`, which is exactly the confident wrong answer the whole
join is meant to avoid.

This is why the earlier attempts failed. A translation's sense tag ("male canine") and an
English lemma's definition ("a domesticated carnivorous mammal...") are a different register
from a source gloss ("dog"). Two glosses written by the same community in the same dump are
like for like, and a lexical match has something to work with.

What it changes: a pair needs the target language's pack as well as the source's, since the
join reads the target's own entries. That is still one pack per language rather than one per
pair, so N packs serve every pair among them, and it drops the translations table, the sense
distributions and the edition dimension all at once.

Verified on hand-picked words only. Building it needs an index of the target language's glosses,
which means the dumps rather than the per-word pages, and the sample to run is the same one:
how often the best gloss match is the right word.

## The recall side of the gloss join, sampled

Twenty Spanish nouns with the German word each should reach, checking whether the German
entry's own glosses share a head term with the Spanish entry's. **Twenty of twenty.**
`perro`/`Hund` meet on "dog", `silla`/`Stuhl` on "chair", `camino`/`Weg` on "path", "route"
and "way", `puente`/`Brücke` on "bridge", `llave`/`Schlüssel` on "key", `cocina`/`Küche` on
"kitchen" and "cuisine".

That is the half that decides whether the approach can work at all: if the right target word
were not findable by its gloss, nothing else about the join would matter. Matching is on the
head phrase, before any parenthesis, split on commas and semicolons, with the article and a
verb's leading "to" removed.

It is recall only. It says the right word is reachable, not that it is the one a search would
pick out of everything else carrying the same gloss, and that is the precision question the
defined sample still has to answer over a whole language rather than twenty hand-picked words.

(Two things that made a first run read 15 of 20 were faults in the probe rather than the data:
the German pages for Tür, Brücke, Küche and Schlüssel need their umlauts percent-encoded in
the path, and "forest; woods; woodland" is one gloss holding three terms, so semicolons have to
be split like commas.)

## How ambiguous the gloss join is

Streamed 125 MB of the German dump, which is page-ordered rather than alphabetical and so is a
fair sample: 20,061 entries, about a tenth of the language, giving 26,482 distinct gloss head
terms.

**Eighty-one percent of gloss terms name exactly one lemma.** The median number of German
lemmas behind a gloss term is one and the ninetieth percentile is two.

| gloss term | German lemmas in the sample |
| --- | --- |
| dog | Hund |
| kitchen | Küche |
| island | Insel |
| chair | Sessel, Stuhl |
| book | Buch, buchen |
| key | Schlüssel, Taste |
| bridge | Brücke, Bridge |
| city | Stadt, Kaliningrad |
| forest | Forst, Heide, Wald |
| way | eight, including Art, Fasson, Gang, Lauf, Manier, Tour |

Two things narrow it further at no cost. Several pairs are a noun against a verb (book against
buchen, water against bewässern) and the source entry carries its part of speech, so filtering
on it removes them outright. And a source gloss usually carries more than one term: `camino`
glosses "way, route", and while "way" alone reaches eight lemmas, the two together reach Weg.

What is left hard is abstract vocabulary, where a single English word covers several senses that
German splits. That is the same place any approach struggles, and the design already has the
answer for it: an ambiguous join yields no dictionary answer, and the word is a labelled guess
rather than a confident wrong one.

Read the eighty-one percent as a lower bound on how determinate the join is and an upper bound
on nothing: a tenth of the language holds fewer lemmas than the whole of it, so more of them
will share a gloss when the full dump is indexed. The sample to run is still the whole language
against a whole language, and it now has a shape: for each source sense, does the gloss reach
exactly one target lemma after the part of speech is applied.
