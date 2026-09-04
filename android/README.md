# Phonetix for Android

The extension's idea outside the browser: pronunciations painted over the words on
screen, in whatever app you are reading.

## How it works

Two halves, and neither is optional.

**Reading the words.** `PhonetixAccessibilityService` walks the node tree of the
foreground window. A node gives its text and one rectangle, but that rectangle covers a
whole paragraph, which is far too coarse to put a transcription on a single word. The
per-character rectangles from
`refreshWithExtraData(EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, …)` are what make word-level
placement possible at all: the characters of a word are unioned into that word's exact box.
Apps that expose text without layout (Compose or Canvas surfaces) return nothing here and
are skipped rather than guessed at.

**Painting them.** `OverlayController` holds a single full-screen
`TYPE_APPLICATION_OVERLAY` window, never focusable and never touchable, so every tap still
belongs to the app underneath. One canvas is redrawn rather than a window per word: the
words change on every scroll, and adding and removing dozens of windows a second would cost
far more than a redraw.

The word choice — which words, how often — is the extension's, ported in `Frequency` and
`Transcriber` so a given setting means the same thing on both. The dictionary is the same
asset the extension ships.

## Things that will waste an hour if you do not know them

- **A `.gz` asset is unpacked at packaging time.** `en.json.gz` in `assets/` arrives in the
  APK as plain `assets/en.json`. `Dictionary` accepts either name; without that the app
  ships with an empty dictionary and silently transcribes nothing.
- **Some apps hide overlays.** Settings (and anything else that sets
  `HIDE_NON_SYSTEM_OVERLAY_WINDOWS`, an anti-tapjacking measure) makes the overlay
  `shown=false, alpha=0.0` while it is in front. The overlay is working; that app is
  refusing it. Test somewhere else before believing it is broken.
- **`adb shell screencap` does not capture the overlay.** It returns the screen without
  other apps' overlay windows, so a screenshot looks empty while the overlay is plainly
  drawing. Use `adb -s <dev> emu screenrecord screenshot <dir>` for the real framebuffer.
- **Reinstalling clears the accessibility grant.** After every `install -r`, re-apply it or
  the service is silently off.

## Building and testing

The wrapper jar is not in the repository, so materialise it once (any local Gradle will do,
it only writes the wrapper files):

    cd android && gradle wrapper --gradle-version 9.3.1

Then:

    ./gradlew assembleDebug
    adb -s <device> install -r app/build/outputs/apk/debug/app-debug.apk

Granting both permissions without touching the UI:

    adb -s <device> shell appops set io.github.nanbiz.phonetix SYSTEM_ALERT_WINDOW allow
    adb -s <device> shell settings put secure enabled_accessibility_services \
        io.github.nanbiz.phonetix/io.github.nanbiz.phonetix.service.PhonetixAccessibilityService
    adb -s <device> shell settings put secure accessibility_enabled 1

`adb logcat -s Phonetix` reports the dictionary size once and then one line per scan with
the package, how many text nodes were measured, how many returned character bounds, and how
many words came out — enough to tell "read nothing" from "chose nothing".

## Drawing over the right pixels

Three things decide whether a transcription belongs on screen at all, and each was learned
the hard way:

- **Ancestors clip it.** A word's box is intersected with every ancestor's bounds on the way
  down the tree, which costs nothing because bounds travel with the node.
- **Later siblings cover it.** A list scrolls *under* a toolbar, so the bar neither clips the
  list nor makes `isVisibleToUser` false - it is simply painted afterwards. Depth-first
  position is paint order, so anything beginning after a node's subtree ended is on top of
  it, and a word it overlaps is dropped. Boxes spanning most of the screen are backdrops and
  do not count.
- **A window must move before it is shown.** Changing a window's text takes effect on the
  next draw; moving it goes through the window manager and lands a frame later. Doing both
  to a visible window paints the new word at the old word's place for that frame, which
  looks like a transcription flashing at random. A window that has to move is hidden first
  and shown once the move has been applied.

While a list is actually moving there is no position worth drawing, so the transcriptions
come down for the duration. Positions are never extrapolated from the scroll event's delta:
it does not describe the screen (one swipe reported 313 pixels while the word moved 176).

## One definition of the behaviour

Which words get transcribed, and how the frequency bar maps onto that, is defined once in
`src/lib/sprinkle.ts` and used directly by the extension. Kotlin cannot import it, so the
extension writes down what that module answers for a spread of inputs:

    pnpm gen:vectors        # -> shared/sprinkle-vectors.json

There are three such tables now: which words are chosen (`sprinkle-vectors.json`), and the
symbol names, tokenisation and display-stripping behind the tooltip (`ipa-symbols.json`,
from `pnpm gen:symbols`).

`SprinkleParityTest` asserts `Frequency` against every one of those cases, so a rule changed
on one side and not the other fails a test instead of quietly giving the same setting two
different meanings on a phone and in a browser. It has already earned its keep: the port
took the FNV hash as a signed `Int` where the extension ends with `>>> 0`, which chose
different words for any hash above 2^31.

    ./gradlew testDebugUnitTest

CI regenerates the vectors and fails if the checked-in copy is stale.

## Known limits

`FLAG_SECURE` windows cannot be read. Compose and Canvas-drawn text usually reports no
character bounds. Scrolling redraws on a settle timer, so the overlay trails a fast fling.
Distribution is sideload or F-Droid: Play polices `AccessibilityService` use heavily.
