# Android: open work

What is being worked on and what is still owed, kept here because chat scrolls away.
Ticked items are done and verified by a suite; the rest are not.

## Scrolling

- [x] **A swipe that started on a transcription did not scroll the page.** 0px against 941px
  for the same swipe beside it. The transcriptions are windows over the words and a window
  that takes a gesture keeps it, so most swipes on a page of text were swallowed whole. They
  take no touches now unless `touchWords` is on; that setting is what buys the card.
- [x] **What is drawn is measured from photographs of the screen**, not from the service's own
  account of itself (`android_eyes.py`, `marks=1`). It found the transcriptions a line below
  their words mid-swipe.
- [x] **The layer worked out the page's speed for itself and got two to eight times the real
  one**, from box positions over an interval it half guessed. The reading measures the speed
  properly; it is told now. Typical error through a swipe: a line, down to a third of one.
- [x] **A swipe no longer costs full reads of the screen.** Nothing answering a pass was read
  as every line having been recycled - it is what a busy app thread looks like - so a swipe
  provoked nine full reads, each a tenth to half a second with the words standing still. And
  the turnover re-read fired on any page under a pixel a millisecond, which is every drag a
  reader can read along with. Through a finger swipe, photographed: typical error 7 pixels of
  the capture (was 9-11), nine in ten within 16 (was 21), worst 20 (was 54).
- [ ] **A tenth of the frames through a swipe are still about a line out.** What is left is the
  gap between readings - 60 to 130ms on the emulator, where one round trip into a busy app
  costs ten. A phone should do better; there is no way to measure that from here.

## In flight

- [x] **Movement measured against the last pass, not against zero.** A shift is the distance
  from where the plan was made, so a page that scrolled once reported itself moving for ever:
  the follow loop never idled, the full read that ends a movement never ran, and the service
  hammered the app it was reading forty times a second, dropping that app's frames.
- [x] **`recycling` cleared with the plan.** It was never reset, so a pass that kept no words
  counted as a success and a plan describing a screen that had gone was ridden for seconds.
- [x] **The layer no longer flings the set off the screen.** Median shift instead of the mean,
  the interval between readings checked against when they arrived, speed and carry bounded,
  nothing predicted from the first reading of a movement, trust fading as a reading ages.
- [x] **A screen that has largely scrolled away is read again** rather than waiting out the
  clock, so words scrolling in are not blank until the movement stops.
- [x] **The suite measures what is drawn**, not only what the service read, and the page
  reports its own position every frame instead of leaving holes to interpolate across.
- [x] **A line given up on kept its black box while the screen moves.** The follow path asked
  only for a line's own reading and never for the fallback, so an unreadable line kept
  background 0 - black - for as long as anything moved.
- [x] **A colour read that is throttled now comes back later.** Without it a still page whose
  lines could not be read on the first attempt showed nothing at all, for as long as the
  reader stayed on it.
- [x] **The fallback colour is read where the line is**, not taken from the commonest colour
  on the screen, which is how a title over cover art got a black patch on an olive page.
  Covered by `mode=gradient` and `check_unreadable_colors`.

## Found by testing an app nobody wrote for the test

- [x] **An entire real app was invisible.** The settings app produced no transcriptions and
  nothing in the log mentioned it: the launcher is found by asking what answers the home
  intent, and a device with no launcher installed answers with the settings app's own
  placeholder activity - so every event of that app was dropped as a bystander's, before
  anything is read or logged. The placeholder and the chooser are not launchers now, an app
  that is dropped says so once, and `check_a_real_app` holds the settings app to being
  transcribed, wearing its own colours, and surviving a scroll.
- [x] **Transcriptions were drawn over the keyboard.** A word behind the keyboard is still in
  the app's tree, so its transcription was drawn where the word would have been - over the
  letter keys, since our window is above the keyboard. The system is asked which windows stand
  over the app.
- [x] **The accessibility button works**, verified by pressing it: the overlay goes empty and
  comes back. The system draws it as a floating button with the app's icon.

## Owed

- [x] **Language detection, as a stopgap.** There is none: one espeak-generated dictionary that pronounces
  any string of letters, applied to every word on screen, so a German page gets English
  pronunciations - on "Song" and "Video", and on "war", "hat", "man". Dictionary coverage
  cannot answer it here the way it does in the extension, because that dictionary knows
  "und", "der" and "das" too. Being built from the 300 commonest words of each of the 38
  languages already shipped for the sprinkle: the language whose function words appear is the
  language of the line. Replaced by eld below; the fixture it was built against stays
  (`mode=german`, `check_language`): a page of ordinary German comes back untouched.
- [x] **Ported eld, which is what the extension uses.** The model is an asset (819KB, a quarter
  of that packed); the port names the same language as the JavaScript, agrees about which
  answers are reliable, and scores to within a hundredth, over twelve languages
  (`EldParityTest`). The word lists are gone. Regenerate with
  `node scripts/build-eld-model.mjs XS` and gzip the result to `eld.bin.gz`.
- [ ] **A German sentence on an English page is still transcribed.** The language is decided
  for the whole screen, because eld reliably calls a line of English nouns Italian and per-line
  suppression cost an English page most of its transcriptions. The extension does better by
  measuring each block against every dictionary it has; there is one dictionary here.
- [x] **The card opens on a press held**, and a tap shows the word underneath.
- [x] **An accessibility button to switch the whole thing off and on.** Added:
  `flagRequestAccessibilityButton` plus an `AccessibilityButtonController` callback. It has to
  be assigned to Phonetix in the system's accessibility settings before it appears.
- [ ] **Abbreviations are spelled out.** "Ms" comes back as `ɛmɛs`. Not an Android gap: the
  extension ships the same file (`public/dictionaries/en.json.gz`, 191,952 entries) and gives
  the same answer, because that dictionary is espeak-generated rather than read from
  Wiktionary - espeak pronounces any string of letters, which is also why `und`, `der` and
  `das` are in it with English vowels. Fixing it means rebuilding the dictionaries from the
  kaikki dump (`scripts/build-dictionaries.mjs`, a 2.3GB download) and would change what both
  platforms say, so it is the user's call rather than a tidy-up.
- [ ] **What is drawn still lags at speed.** Slow reading scrolls sit within a line's height;
  a brisk fling is 130-270px behind, and the emulator's 5-8px/ms flings several hundred. The
  limit is the round trip into a busy app, ten-odd milliseconds each, several per reading.
