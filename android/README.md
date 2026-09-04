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

## Known limits

`FLAG_SECURE` windows cannot be read. Compose and Canvas-drawn text usually reports no
character bounds. Scrolling redraws on a settle timer, so the overlay trails a fast fling.
Distribution is sideload or F-Droid: Play polices `AccessibilityService` use heavily.
