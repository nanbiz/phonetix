# Android: open work

What is being worked on and what is still owed, kept here because chat scrolls away.
Ticked items are done and verified by a suite; the rest are not.

## Is each transcription on its own word

Measured by `scripts/proofread/android_words.py`, which is the only check that can tell a
transcription sitting on its own word from one left behind on somebody else's. It walks the
moving layer's own frames, sixty a second, so what it judges is what a reader sees rather than
the positions the service worked out. Three shapes of page: a list, wrapped paragraphs, a
conversation of wrapped messages.

- [x] **Once the page has settled: none, on all three.** It was eleven of fifty-five on wrapped
  paragraphs, up to a third on the other two. Three causes, each found by naming the words that
  were wrong and photographing the screen rather than by tuning: character positions remembered
  against the bounds the line had before the app laid it out again, two copies of a line kept
  when the strip read met one it was already carrying, and a following pass spending a second
  of a 1300ms drag asking a page where its characters are.
- [x] **Through a drag: 7 percent on a list, 16 on wrapped paragraphs, 7 on a conversation**,
  ten drags of each. It was 49, 81 and 78. The last and largest step was to stop reading the
  strip of screen that has just scrolled into view while the page is moving: that read walks
  the app's node tree, two to three hundred milliseconds a time, two to four times in a drag,
  and the following passes it starved are the only thing keeping the words on their text.
  Over the same ten drags of a page it also drew more rather than less, because a stale reading
  is one the layer refuses to draw from: the words were withheld on 75 to 862 samples with the
  strip read and on 0 to 737 without it.
- [ ] **A long drag now ends with little on the screen.** Nothing picks up the lines that scroll
  in while the page moves, so once the lines the plan was made from have left, the shift stops
  changing, the speed decays and the layer stops drawing until the page settles and is read
  again. Watched through one drag: the shift froze at 555 pixels for six passes running with
  one to three of eight lines still alive. This is the right way round, since nothing on a word
  beats another word's pronunciation on it, but it is the cost, and the fix is not to bring the
  strip read back as it was: it is to find the lines arriving without paying what that walk
  paid. Measured since: on a page standing still the walk is cheap, `plan=21ms (ipc=10ms in 23
  calls)`. It cost two to three hundred milliseconds mid-drag because every one of those round
  trips was blocking on the app's own main thread while it laid itself out. So the lever is the
  number of round trips, and the platform has one: `AccessibilityNodeInfo.getChild(index,
  prefetchingStrategy)` from API 33 fetches a subtree in one call where `getChild(index)` costs
  one call a node, and nothing here asks for prefetching today. Refreshing the nodes already
  held is not the answer, because they are not recycled with new text during a drag: watched
  through one, `gone=0` while `clipped=6`, so the lines simply leave and the arriving ones are
  nodes never seen before.
- Measured and settled, so do not revisit without better evidence than this: measuring lines
  that have never been measured while the page moves, which is what `MEASURE_MOVING_MAX`
  allows. Asking an app where a line's characters are takes about a quarter of a second. At
  twelve, with the strip read already gone, a conversation went from 7 percent wrong to 20 with
  three drags of ten above 85 percent, and the settled case broke on one drag in ten. It buys
  coverage during the movement and costs correctness, which is the wrong way round: nothing on
  a word is better than another word's pronunciation on it.
- Tried and measured worse, ten drags a page each, so also settled: carrying each line of a
  following pass forward from its own read moment; refusing a speed measured over less than a
  frame; believing a speed in proportion to how long it was measured over, which broke the
  settled case because a smoothed speed never snaps to zero when the page stops; and predicting
  from the first reading of a movement, whose speed describes neither the still page nor the
  moving one.
- Three attempts on the speed estimate have now measured worse. On this evidence what is left
  of the mid-drag error is not the speed. What the diagnostics show is that the layer carries
  nothing for the first two to four hundred milliseconds of a drag, by the rule that refuses to
  predict from a first reading, and that its carry then saturates when readings arrive too far
  apart for it to keep trusting them. Both are about how soon and how often a reading arrives.
- [ ] **Four drags of one page cannot separate a ten point difference.** One run of one build
  gave 23, 54, 57 and 72 percent. Ten drags of a page is the smallest honest comparison, and
  `scripts/proofread/android_words.py` judges the layer's own frames rather than the readings.

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
- [x] **A fling is photographed too, since that is the movement this was reported broken on.**
  Typical transcription 30px from its word, nine in ten within 57, worst 60 - about half a line
  and a line. A drag is 18px typical. Still and settled are 3px, at every font size and turned
  sideways.
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

- [x] **Nothing was painted over a real app at all.** The windows were there, visible, in the
  right places, carrying the right colours, and the screen showed the app untouched: a
  `SYSTEM_ALERT_WINDOW` overlay is hidden by the platform over any screen that asks for it,
  and the settings app asks, as does every permission dialog, so that nothing can cover what
  a reader is agreeing to. The log said eighteen transcriptions and a photograph of the
  screen said none. They are accessibility overlays now, which is the type belonging to the
  service that is already reading the screen: exempt from that hiding, and needing no
  permission of its own.
- [x] **A test suite passed against a screen with no transcriptions on it.** It measured the
  rectangles the service reported, and those rectangles are the app's own words - so legible
  text was found in every one of them whether or not anything of ours had been painted. Every
  pixel check now photographs the same screen again with the service switched off, and counts
  a transcription as painted only where the two differ (`android_ink.py`).
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

## Windows over the app, and settings a reader changes

- [x] **Transcriptions were drawn over the keyboard**, and **over the notification shade** - a
  screenful of them scattered across the notifications. A word another window now covers is
  still in the app's tree, and our windows are above everything. The system is asked which
  windows stand over the app; a bystander's window opening or closing is looked at, where its
  events used to be dropped before anything looked.
- [x] **The words came back when the shade closed.** They did not: the look taken when the
  shade's window changes finds the shade still in front, and the app underneath then sends
  nothing. It looks again shortly after, from the main thread, because the worker's queue is
  emptied by the events that arrive alongside.
- [x] **A quarter of the transcriptions were never drawn at the densest setting** - sixty-five
  chosen, forty-eight windows - and the layer drew all of them while the page moved, so they
  appeared during a scroll and vanished when it stopped.
- [x] **A theme change left them wearing the old theme's colours.** Everything read off the
  screen is forgotten when the configuration changes.
- [x] **Placement holds at 0.85 to 1.5 times the font size, at three display densities, and
  turned sideways.** Photographed at each.
- [x] **Home, recents and an app's own dialog** all clear correctly, checked.
- [ ] **Split screen is untested.** The emulator will not enter it from `am` or from the recents
  gesture, so there is no way to drive it here. Two apps side by side is the one arrangement
  where a transcription could be drawn over the wrong app's half.

## Where it cannot work

- [ ] **A third of the apps on this emulator cannot be transcribed at all.** Opened twelve and
  swiped each: settings 12 transcriptions, messages 7, files 4, youtube music 3, docs 2,
  clock/contacts/maps 1, chrome/gmail/dialer/calendar 0 - and four of them (contacts, maps,
  dialer, docs) answer the request for character positions with nothing, which is what makes
  word placement impossible. Some of the zeros are only a sign-in screen with nothing to read.
  There are two ways out and both are the author's call: place words from where their ink
  actually is in the screen capture we already take for colours (exact, but a word-splitting
  problem on a line of several words), or accept it and say so in the app when a screen cannot
  be read.
- [ ] **A browser shows nothing.** Chrome answers the request for character positions with
  success and no data, so there is nothing to place a transcription on: a whole page of English
  produces none. Nothing in the accessibility API gives word positions inside a paragraph
  without it, so this would mean laying the text out ourselves from the paragraph's rectangle
  and hoping the font matches. The browser extension is the answer for browsers.
- [x] **An app that never stands still was never measured.** Whether the screen is moving
  decides whether a line's characters may be asked for, and asking makes the app lay its text
  out again - which a page being scrolled cannot afford. An app that changes something ten
  times a second was therefore permanently moving and permanently unmeasurable: the settings
  app planned thirteen lines, measured none of them, and showed nothing at all. A change is
  no longer a movement; movement is measured by the pass that follows, which is the only
  thing that knows whether anything moved. The reason this was reverted once - a capture
  taken off a moving screen smears white text into a white page and averages out to the grey
  between them - is answered where it belongs: the colour reading waits for stillness itself
  rather than the whole overlay waiting for it.

## Shipping

- [x] **The test page is out of the release APK** (its own debug source set and manifest,
  checked by dumping both) **and refuses intents from other apps.** It writes the app's own
  settings from its extras - whether the overlay is on, the frequency, whether words take
  touches - and a release hands people the *debug* APK, so the page is on their phones either
  way. Only a shell can drive it now.
- [ ] **Releases ship a debug build.** That is what makes the point above necessary, and it
  also means no minification and every debug log running on a reader's phone. A signed release
  build needs a keystore that only the author can hold, so it is the author's call.

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
- [x] **A transcription could be painted in a shade of its own background.** The ink and the
  surface are measured off a photograph, and a pair a few dozen apart on a scale where black
  on white is 765 is not text and its surface - it is a surface measured twice. Painted, it
  looks like a word half rubbed out, which a reader reported as the transcriptions being
  semi-transparent. Such a pair is rejected now and the line takes ink that is legible on the
  surface it stands on; `android_ink.py` holds every drawn word to it, on a plain page, a
  gradient, a dark page, through a scroll and on the settings app.
- [ ] **A drag keeps about three fifths of its transcriptions, a fling and a still page more
  than four fifths.** Photographed on a recycling list, eight drags: a mean of 62% of the
  page's lines carrying one and a worst drag of 26%, against 39% and 8% before the nine
  places that decide whether the page is moving were made to ask one question. On the article
  page the same measure reads 50-82% a drag, 83-89% a fling, 78-88% standing still, and the
  typical transcription sits 8 to 14 pixels of the capture from its line, about a third of one.
- [ ] **Through a drag, a fifth to a third of what is on the screen names a word that is not
  under it.** Reported from a phone as transcriptions frozen while the text moved under them,
  and reproduced by `android_words.py` on a Compose conversation and on a page of wrapped
  paragraphs. Standing still and once settled are clean; only the movement is wrong. The
  moving layer withholds the words when it can tell it cannot place them - it asks whether the
  app answers often enough, where it was expected to, and promptly - which took the middle drag
  from 64-73% to about 20-30%. What is left is the app's own speed: that page gets eight follow
  passes to nine hundred pixels of movement because single passes take six hundred
  milliseconds, and between two passes that far apart the words are wherever the prediction put
  them. Measured and reverted for making it no better: capping how far the words are carried on
  one reading, and withholding them on the small windows as well as on the layer.
- [ ] **What each kind of container says about its own scrolling** (measured 2026-09-08, and
  read in the platform's source rather than guessed):
  - A `ScrollView` reports the delta in real pixels: 997 against 991 really travelled.
  - A `RecyclerView`, which is what most apps' lists are, also reports real pixels: 909/906,
    1062/1053, 1068/1061.
  - A `ListView` reports nothing usable - the delta is 0, and only `fromIndex`/`toIndex` move.
  - A Compose lazy list reports a delta of -1 and a position of its own. Compose builds it
    from `estimatedLazyScrollOffset`, which is
    `firstVisibleItemScrollOffset + firstVisibleItemIndex * 500`, and the source calls it a
    "best-effort pseudo-offset" because a lazy list cannot say where it is in pixels. It moves
    in real pixels within an item and jumps 500 at each item boundary whatever that item's
    height, which is why the scale between what it says and what it does wanders from 0.15 to
    39 inside one drag.
  Where the number is real it now sets the layer's speed. Where it is not, nothing can be done
  with it.
- [x] **Every suite is now answerable to a control**: `putThemWrongBy=200` puts every
  transcription two hundred pixels from the word it names, after every filter that decides
  what is drawn, and a suite that still passes cannot see the fault it claims to measure.
  Asked of each: the photographs fail 9 checks, the features 10, the motion 8 - and the ink
  suite and `android_overlay.py` pass unchanged. The overlay suite, seven thousand checks of
  it, measures whether the words moved *as far as* the text did; shifting every word by the
  same amount leaves every one of those differences untouched. Its greenness has never been
  evidence that anything is on its word.
- [ ] **The suite cannot resolve a change of thirty points on the moving case.** Three runs of
  one build gave the same page 4%, 37% and 41%; another gave 28%, 0% and 26%. Every conclusion
  about the movement drawn from a single run of it is worthless, and several were drawn that
  way before this was noticed. What it does say reliably: standing still and once settled are
  clean, and the withholding turned off reads 64-73% against 12-18% on.
- [ ] **Following from something other than the app's answers.** Two ways were measured and
  neither works. The display can be photographed by the service, but the system refuses more
  than about three a second - back to back, one frame then thirteen refusals in thirty
  milliseconds, all "interval time short"; ten of ten only at 350ms apart - against thirty
  answers a second from the app. And a scroll event carries how far the view says it moved,
  which is accurate on a ScrollView (997px reported against 991 real) and worthless on exactly
  the pages that need it: a Compose list reports -1 per event and a ListView reports 0. The
  only fast route to pixels is a screen recording, which needs the reader's consent and an
  ongoing notification, and is a decision about what the app is rather than a fix.
- [ ] **What is left of the missing ones: the lines arriving at the bottom.** What is
  missing is the lines arriving at the bottom: they are not in the plan, and putting them
  there means reading the strip of screen they arrived in, which costs 130ms typically and
  400 at worst because finding them means asking every node on the way down where it is. Two
  or three such reads fit in a drag and a drag of a screen and a half brings fourteen lines.
  Reading from the container the page scrolls in rather than the window does not help, nor
  does letting a half-arrived line keep the words still outside the window it scrolls in, nor
  reading a screen beyond each edge (there is nothing there to read: a node the app has not
  shown reports no rectangle), nor reading strips oftener - 60ms apart against 200 measured
  worse, a mean of 40% against 50%.
- [ ] **The old note, which is still true of what is left: it is the app's speed that
  decides.** Photographed through drags of a screen and a half: standing still 78-83% of
  the page's lines carry a transcription, a fling 88-94%, a drag 50-88%. What separates a good
  drag from a bad one is how often the app answers - the following managed between 18 and 121
  passes through the same 1.3s drag on the same build, and what is carried tracks that almost
  exactly (18 passes, half the lines; 121 passes, four fifths). Each pass is a round trip into
  an app busy laying out a scroll, so this is the emulator's speed rather than anything in the
  overlay, and a phone sits at the fast end of it. Nothing here is worth tuning against on this
  machine: the sweeps that came back inside that variance or worse, and were reverted, are a
  lead on the prediction, correcting the measured drift, reading strips oftener, reading them
  on a thread of their own, and measuring twenty-four new lines a pass rather than twelve.
  What did move it, each measured in a controlled pair: asking one line where it is rather
  than three while there is a speed to check the answer against, measuring twelve of the lines
  that scroll in rather than none, transcribing a recycled row where it stands, holding the
  row checks back until the page has nearly stopped, and reading the colours off a page that
  has stopped rather than one whose last pass has.
- [ ] **What is drawn still lags at speed.** Slow reading scrolls sit within a line's height;
  a brisk fling is 130-270px behind, and the emulator's 5-8px/ms flings several hundred. The
  limit is the round trip into a busy app, ten-odd milliseconds each, several per reading.
