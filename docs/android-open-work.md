# Android: open work

What is being worked on and what is still owed on the phone, kept here because chat scrolls
away. Ticked items are done and verified by a suite or on the emulator; the rest are not.

The phone is the side button: drag it over a word for a card, tap it for the translate panel.
Nothing is drawn over an app's own text. The browser extension is not being developed for now.

## The side button

- [x] **The card is display only and goes with the finger.** What it shows follows the two
  switches (translation, pronunciation, both, neither); `android_translates.py` holds each.
- [x] **The thread comes out of the icon** and lands on the side of the circle that faces it,
  wherever the circle is carried; the icon stays in sight under the finger.
- [x] **The card is placed clear of the word, the circle and the icon.**
- [x] **The icon stays up** over every screen while Phonetix is on, and comes back where it
  waited after a drag the system cancelled (a screenshot mid-drag).
- [x] **Put away on the target at the foot of the screen** (`android_put_away.py`).
- [ ] **No card in the Gemini app.** Reported from the phone; not reproduced here, since the
  emulator has no Play Store. Every word on screen is now kept for the button, including lines
  whose characters an app will not place, which may be what it was.
- [ ] **A browser gives the button nothing.** Chrome answers the request for character
  positions with success and no data, and nothing else in the accessibility API places words
  inside a paragraph. Lines are laid out evenly across their rectangle instead, which is near
  enough to point at in some apps and not in a browser page.

## The translate panel

- [x] **Two languages, worked out from what is typed**: a word one dictionary holds and the
  other does not is in that one; held by both, it is in the one that uses it more; otherwise
  the detector says. The arrow turns it round.
- [x] **A word is answered with every meaning, commonest first**, each with the few words that
  say which meaning it is; a phrase with its translation.
- [ ] **Meanings are only as good as the join.** Into Spanish, "bank" lists "vera" and
  "ribazo" beside "orilla"; a meaning whose gloss carries no qualifier has no hint.

## Shipping

- [x] **The test page is out of the release APK** and refuses intents from other apps.
- [ ] **Releases ship a debug build**, so every debug log runs on a reader's phone. A signed
  release build needs a keystore only the author can hold.
