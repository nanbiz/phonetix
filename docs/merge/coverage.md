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
