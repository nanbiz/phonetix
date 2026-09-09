# What a Compose list tells an accessibility service

Read out of the androidx and platform source on 2026-09-08, because the overlay has to follow
apps built with Compose and the behaviour is documented nowhere else. Every claim here is from
a file that can be opened, not from observation alone, though the observations agree.

## A Compose scroll event carries no distance

`AndroidComposeViewAccessibilityDelegateCompat.android.kt`, in
`sendTypeViewScrolledAccessibilityEvent`, sets `scrollX`, `maxScrollX`, `scrollY` and
`maxScrollY` on a `TYPE_VIEW_SCROLLED` event, and nothing else. It never sets
`scrollDeltaX`/`scrollDeltaY`, and never sets `fromIndex`, `toIndex` or `itemCount` on a scroll
event at all.

Those fields therefore arrive holding the platform's own default. From
`AccessibilityRecord.java`: `private static final int UNDEFINED = -1;` with `mScrollDeltaX`,
`mScrollDeltaY`, `mFromIndex`, `mToIndex` and `mItemCount` all initialised to it. So the -1 an
overlay reads is not a value Compose chose. It is the absence of one.

**It used to be different and was deliberately removed.** Commit `5a627be512` in
`platform/frameworks/support`, July 2023, bug `b/286202600`, deleted a working
`Api28Impl.setScrollEventDelta` call that had set real pixel deltas on API 28 and above. The
commit message gives the reason: the call "does not have a known benefit and these deltas were
far too noisy to be usable for anything". So Compose before roughly 1.5.0 reported real
deltas, and everything since reports none.

## What it reports instead is not pixels

`LazyLayoutSemantics.kt` computes the scroll position a lazy list reports:

    estimatedLazyScrollOffset = firstVisibleItemScrollOffset + firstVisibleItemIndex * 500

with a comment above it saying it is "impossible for lazy lists to provide an absolute scroll
offset because the size of the items above the viewport is not known, but the AccessibilityEvent
system API expects one anyway", and calling what it returns a "best-effort pseudo-offset". The
comment names the three properties it is trying to preserve: that a scroll generates an event,
that the number differs from the last one, and that being exactly 0 or exactly the maximum
indicates whether there is anywhere left to scroll. It says outright that "the magnitude and
direction of the change does not matter for the known use cases".

LazyColumn, LazyRow and the lazy grids share that formula. A Pager is the exception and reports
a real pixel offset, because a pager's geometry is known in advance.

A classic list is the opposite on both counts. `View.onScrollChanged` posts real per-step pixel
deltas, and `RecyclerView.dispatchOnScrolled` deliberately fabricates old scroll values so that
mechanism carries the real amount consumed this step; `LinearLayoutManager` sets `fromIndex` and
`toIndex` to the first and last visible positions and the base layout manager sets `itemCount`
to the adapter's real count.

## What TalkBack does about it

TalkBack's `ScrollEventInterpreter.java` decides scroll direction in three tiers: first
`fromIndex` against the previous one, then `scrollX`/`scrollY` against the previous with a
`SCROLL_NOISE_RANGE` of 15, and last the raw deltas. Compose fails the first, because
`fromIndex` is always -1, and fails the last, because -1 is inside the noise range. What
carries a Compose list is the middle tier, comparing the pseudo-offset between events. That
yields a direction and nothing else: not how far, not which item, not how many there are.

## The bounds do not move while the page does

Measured on 2026-09-09, on a LazyColumn of fixed-height rows driven by a real finger swipe,
with the page's own `firstVisibleItemIndex * itemHeight + firstVisibleItemScrollOffset`
recorded as ground truth at every change.

Through a fling in which the page travelled from 5 to 770 pixels, every reading the service
took reported the same word at exactly the same pixel: twenty consecutive readings of
`sapphire` at `330,289`, and then, once the fling was over, a different set of words at a
different fixed position. Asking a line where it is with `refresh()` returned the bounds it had
when the plan was made, and went on doing so for lines that had left the screen altogether -
`3 almond blanket` answered from `44,107..1036,281` while a `uiautomator` dump of the same
moment showed it gone. Eighteen follow passes of one fling each measured a shift of exactly
zero.

So a Compose list cannot be watched moving. The consequence for anything that carries words
between readings is total rather than partial: the speed measured by comparing two readings is
always nought, so a layer that carries at that speed carries at nothing, and the words stand
still on a page travelling eight hundred pixels. Measured, ten flings: 0% of the movement
followed, on every one.

Two things do not explain it and were tested rather than assumed. `AccessibilityService.
clearCache()` before each pass changes nothing, so it is not the client-side node cache.
Fetching `rootInActiveWindow` before asking changes nothing either. A full walk of the tree
*does* return current positions, which is why placement at rest is unaffected: it is `refresh()`
on a node already held that answers with the old screen.

What is left is the pseudo-offset. It moves continuously - 1510, 1608, 3083, 3176, 3219, 3238,
3501 through one fling - and it can be turned back into pixels, because the formula above is
known: an item boundary in it is worth five hundred units and one real item height, and the
remainder within an item is already in pixels. The item height is on the screen to be measured.
Done that way, a fling came out in pieces of 118, 646, 75, 47, 14 and 2 pixels against 785
really travelled.

## What follows for this overlay

- A reported delta of one pixel or less is the absence of a delta and has to be ignored rather
  than believed, which is what `SAID_TOO_SMALL` does. Believing it puts the speed at a fifth of
  a pixel a millisecond while a page is doing six.
- A framework list or a scroll view hands over the real thing for free: through one fling the
  settings app reported 133, then 72, then 33, then 7, which is the deceleration itself.
  Whether using it helps is **not established**. It was committed on a measurement of 26 of 189
  transcriptions off their word with the deltas ignored against 3 of 171 with them used, and
  that measurement came from a judge later found to have three faults in it. Measured again
  once the judge could be made to fail on demand, both settings are clean on these apps: 0 of
  679 and 0 of 682 with the deltas used, 0 of 700 without. The floor below stands on the source
  rather than on that measurement.
- For a Compose list there is no distance in the event, and the nodes cannot supply one either
  while the page is moving, for the reason above. The pseudo-offset converted with a measured
  item height is the only continuous signal there is. It is an estimate and is treated as one:
  it carries the words only where the layer has already stopped believing its own speed, and a
  reported distance from an app that gives one is treated as an account instead and replaces
  what was guessed for the same stretch.
- Which of the two applies is decided by the event's own delta being exactly undefined rather
  than merely small. An app that fills the field in reports its offset in real pixels too, and
  putting that through the conversion inflates it: the settings app's words were carried 124%
  of what it moved.
- `CollectionInfo` on a node is populated properly by Compose with the real item count, and is a
  better channel than the scroll event for anything about position in a list. Nothing here uses
  it yet.

## How this is measured, after a day of measuring it wrongly

Every instrument here reads the service's own log, and the log records where the service
believes it put the words. Where that belief is wrong the measurement agrees with the mistake.
Measured on 2026-09-09: one suite reported nothing wrong in 1024 judgements while a photograph
of the same moment carried two transcriptions sitting on the wrong paragraph, and another
judged fifteen hundred transcriptions through a drag of a Compose page whose screen was
carrying none at all.

`android_pixels.py` asks the screen instead. The page paints each line's number into its own
background; the overlay paints, on each word it draws, the number of the line it believes that
word came from; a screenshot holds both and the check compares two colours. Nothing is read
from the log and no text is recognised.

It disagrees with the log-based measures where it matters. Carrying the words by a fit of the
page's last few hundred milliseconds against carrying them at the speed of the last interval,
ten looks part way into a drag of each page: 99% against 84% of transcriptions over their own
line on one list, 89% against 100% on another, 95% against 92% across both. The log-based
drift measure had called the same two builds level to slightly worse.

## Nobody has written this down

There is no public issue, no Stack Overflow answer and no developer guide describing any of
this. The AOSP source and that commit message are the whole public record, and the consequence
for a third-party accessibility service is only visible by reading TalkBack's fallback or by
testing it.
