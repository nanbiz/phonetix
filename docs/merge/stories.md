# What a reader can do, and what actually happens on each platform

The merged product has two surfaces and they are not the same medium. A browser owns the page
it annotates: it can make room above a line and put a word's meaning there. An overlay on a
phone owns nothing - it can only paint over the pixels an app has already drawn - so the same
setting cannot mean the same thing on both, and pretending it does is how the phone came to
offer "the meaning, above the word" for a thing it draws *over* the word.

This is the list of what a reader wants to do, what each surface really does about it, and
what is still missing. It is written from the code, not from the design, and the design page
(`surface.html`) and the merge decisions (`decisions.md`) are what it is measured against.

Taplex's own model - `~/projects/code/taplex/docs/model/model.json` - is the other source: the
hover, the page in one language and the say field are its stories, and the merge owes them.

## Reading

| # | What the reader wants | Browser | Phone |
|---|---|---|---|
| R1 | See what a word means while reading | The meaning above the word, as ruby, page reflowed to make room | A chip painted over the word, in the app's own ink |
| R2 | See how a word is said | The transcription above the word | A chip over the word |
| R3 | Both at once | Two lines above the word | **Not possible**: one chip, one line, and the width of the word it covers. Asking for it silently gets the meaning, or the sound where there is no meaning |
| R4 | The word swapped for its meaning | The word itself replaced inline; resting on it reveals what was there | **The same thing as R1**: the chip *is* a replacement, so this is one choice on a phone, not two |
| R5 | Nothing drawn, ask when I want to | Layer off; rest on a word for the card | Layer off; the circle, or a press on a word |

R3 and R4 are the fault the phone's settings still carry: five choices where the medium has
three. The wording table (`data/wording.json`) now says which platform each choice belongs to.

## Asking about one word

| # | What the reader wants | Browser | Phone |
|---|---|---|---|
| A1 | Ask about the word under the pointer | Rest on it; the delay is the reader's, the card survives the trip to it and follows the word on a scroll | - |
| A2 | Ask about a word without giving up the screen's touches | - | The circle: a mark parked at the edge, dragged over the words. The circle rides clear above the hand on a thread of light, so the word being asked about is never under the finger asking |
| A3 | Ask by pressing the word itself | A click | A press, where the reader has switched the transcriptions on to touches |
| A4 | Have the answer clear of the hand | Nothing covers a pointer | The card opens above the word while a hand is on the screen |

## Asking about more than one word

| # | What the reader wants | Browser | Phone |
|---|---|---|---|
| P1 | Ask about a phrase | Select it; the card answers the selection as one thing, marked as a guess | **Missing**. DR-1 says long-press then drag; nothing implements it |

## The whole thing in my language

| # | What the reader wants | Browser | Phone |
|---|---|---|---|
| W1 | Read the page in my own language | Replace mode: every word swapped, the original on hover | **Missing**. Taplex laid the page's own lines over it, translated, on a tap of the mark, and took them away on the next tap |
| W2 | Have that read as sentences rather than words | **Missing on both**: DR-7 asks for aligned phrase units; the published engine build exposes no alignments, so nothing can partition a sentence yet |

## The other direction

| # | What the reader wants | Browser | Phone |
|---|---|---|---|
| S1 | Know the word for something I want to say | **Missing** | **Missing**. Taplex answered it with the word and that word's entry, so the guess could be judged |

## Dictionaries, scope, voice

| # | What the reader wants | Browser | Phone |
|---|---|---|---|
| D1 | Say where my dictionaries come from | A field on the dictionaries screen | A field on the dictionaries card |
| D2 | Fetch and give up a dictionary by name | One row each, with what it costs | The same |
| D3 | Keep it off some sites or apps | Per site, with the default beside it | Per app |
| D4 | Hear a word | A recording where one exists, the synthesiser otherwise, marked which | The same |
| D5 | Choose an accent per language | The accent screen, and the other languages under it | The accents card |
| D6 | Broad or narrow, stress marks | Two rows | The same two rows |

## What is still missing, in the order it matters

1. **The phrase gesture on the phone** (P1): the one way of asking that the phone cannot do at
   all, and the browser can.
2. **The page in one language on the phone** (W1): Taplex's, and the merge dropped it.
3. **Say it** (S1): Taplex's other direction, dropped.
4. **Phrase units for replace mode** (W2): blocked on an engine that reports alignments.
