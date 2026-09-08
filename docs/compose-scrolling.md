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
- For a Compose list there is no distance in the event, so distance has to be measured from the
  nodes themselves, and the event is worth only what TalkBack uses it for: that something moved,
  and which way.
- `CollectionInfo` on a node is populated properly by Compose with the real item count, and is a
  better channel than the scroll event for anything about position in a list. Nothing here uses
  it yet.

## Nobody has written this down

There is no public issue, no Stack Overflow answer and no developer guide describing any of
this. The AOSP source and that commit message are the whole public record, and the consequence
for a third-party accessibility service is only visible by reading TalkBack's fallback or by
testing it.
