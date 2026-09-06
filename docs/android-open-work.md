# Android: open work

What is being worked on and what is still owed, kept here because chat scrolls away.
Ticked items are done and verified by a suite; the rest are not.

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
- [ ] **A line given up on keeps its black box while the screen moves.** The follow path asked
  only for a line's own reading and never for the fallback, so an unreadable line kept
  background 0 - black - for as long as anything moved. Fixed in the tree, not yet verified.
- [ ] **A colour read that is throttled now comes back later.** Without it a still page whose
  lines could not be read on the first attempt showed nothing at all, for as long as the
  reader stayed on it. Fixed in the tree, not yet verified.
- [ ] **The fallback colour is read where the line is**, not taken from the commonest colour
  on the screen, which is how a title over cover art got a black patch on an olive page.
  Fixed in the tree, not yet verified; `mode=gradient` and `check_unreadable_colors` cover it.

## Owed

- [ ] **Language detection.** There is none: one espeak-generated dictionary that pronounces
  any string of letters, applied to every word on screen, so a German page gets English
  pronunciations - on "Song" and "Video", and on "war", "hat", "man". Dictionary coverage
  cannot answer it here the way it does in the extension, because that dictionary knows
  "und", "der" and "das" too. Being built from the 300 commonest words of each of the 38
  languages already shipped for the sprinkle: the language whose function words appear is the
  language of the line, and a line too short to hold one is answered for by its screen.
- [ ] **The card opens on a long press**, not on a tap.
- [ ] **An accessibility button to switch the whole thing off and on.** Not there yet. The
  quick-settings tile is (`PhonetixTileService`); the service does not ask for the
  accessibility button, which needs `flagRequestAccessibilityButton` in
  `accessibility_service_config.xml` and an `AccessibilityButtonController` callback.
- [ ] **Abbreviations are spelled out.** "Ms" comes back as `ɛmɛs`, which is the espeak
  dictionary reading it as initials. The extension has Wiktionary's `/mɪz/`. A dictionary
  question rather than a placement one.
- [ ] **What is drawn still lags at speed.** Slow reading scrolls sit within a line's height;
  a brisk fling is 130-270px behind, and the emulator's 5-8px/ms flings several hundred. The
  limit is the round trip into a busy app, ten-odd milliseconds each, several per reading.
