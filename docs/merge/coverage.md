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

Worth testing before the pack format is settled: a kaikki entry carries a `translations` field,
and the English edition holds a hundred times more of every language than the other editions do.
A Spanish word glossed into German could come from the English edition's Spanish entry and its
German translation, rather than from the German edition's Spanish section. If that field is
dense enough, one rich extraction serves every pair and the edition key is only needed where a
reader wants definitions written in their own language. How dense it is is not published.
