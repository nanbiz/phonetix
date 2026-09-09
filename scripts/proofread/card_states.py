#!/usr/bin/env python3
"""Every state the card has, drawn by the extension and read back.

The card decides what a reader is told about a word: which answer leads, whether it is
labelled a guess, what is said about how the word sounds, and what is simply absent. None of
that is visible in a unit test of the component, because half of it is layout and colour that
only exist once a browser has applied the generated tokens.

So this opens the viewbook page inside the built extension, where every state is drawn by the
same component a page gets, and checks each one against what that state is supposed to show.

  uv run python scripts/proofread/card_states.py
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP

# What each state has to show, and what it must not. A state naming a piece it does not have
# is how a card ends up with a row that says only that a row was drawn.
WANT = {
    "entry": {"headline": "Hund", "badges": [], "example": "El perro ladra.", "symbols": 6},
    "form": {"headline": "Hund", "badges": [], "lemma": "perro", "form": "form: perros"},
    "homograph": {"headline": "banco is more than one word", "readings": ["Bank", "buchen"]},
    "guess": {"headline": "Regenschauer", "badges": ["guess"], "symbols": 10},
    "anchored": {"headline": "way, route", "badges": ["in English"]},
    "senses": {"headline": "Punkt", "others": ["dot", "stitch"], "more": "1 more sense"},
    "ipa-only": {"headline": "perro", "foot": "", "symbols": 6},
    "no-pack": {"note": "No dictionary for Spanish yet"},
    "none": {"note": "Nothing found for perro"},
}


def read(cdp, session, expression):
    got = cdp.send("Runtime.evaluate", {
        "expression": expression, "awaitPromise": True, "returnByValue": True,
    }, session=session)
    return got.get("result", {}).get("value")


def main():
    cdp = PipeCDP()
    cdp.send("Target.setDiscoverTargets", {"discover": True})
    extid = cdp.ensure_extension()
    failures = []
    try:
        target = cdp.send("Target.createTarget", {"url": f"chrome-extension://{extid}/viewbook.html"})
        session = cdp.send(
            "Target.attachToTarget", {"targetId": target["targetId"], "flatten": True},
        )["sessionId"]
        cdp.send("Runtime.enable", session=session)

        drawn = None
        for _ in range(15):
            drawn = read(cdp, session, """
                (() => {
                  const light = document.querySelector('[data-mode="light"]');
                  if (!light) return null;
                  const out = {};
                  for (const slot of light.querySelectorAll('.slot')) {
                    const card = slot.querySelector('.card');
                    if (!card) continue;
                    const box = card.getBoundingClientRect();
                    const style = getComputedStyle(card);
                    out[slot.dataset.uid] = {
                      headline: (card.querySelector('.tr') || {}).textContent || '',
                      note: (card.querySelector('.note') || {}).textContent || '',
                      badges: [...card.querySelectorAll('.badge')].map(b => b.textContent),
                      symbols: [...card.querySelectorAll('.sym')].map(s => s.textContent),
                      marks: [...card.querySelectorAll('.ipa-mark')].map(m => m.getAttribute('aria-label')),
                      play: [...card.querySelectorAll('.audio')].length,
                      lemma: (card.querySelector('.lemma') || {}).textContent || '',
                      form: (card.querySelector('.g-sub') || {}).textContent || '',
                      readings: [...card.querySelectorAll('.card-body .gram .lemma')].map(r => r.textContent),
                      others: [...card.querySelectorAll('.card-body .note')].map(n => n.textContent),
                      more: (card.querySelector('.card-body .btn-text') || {}).textContent || '',
                      example: (card.querySelector('.ex') || {}).textContent || '',
                      foot: (card.querySelector('.card-foot span') || {}).textContent || '',
                      width: Math.round(box.width),
                      height: Math.round(box.height),
                      surface: style.backgroundColor,
                    };
                  }
                  return JSON.stringify(out);
                })()
            """)
            if drawn:
                break
            time.sleep(1)
        if not drawn:
            print("FAIL - the viewbook drew no cards at all")
            sys.exit(1)
        cards = json.loads(drawn)

        for uid, want in WANT.items():
            card = cards.get(uid)
            if card is None:
                failures.append(f"{uid}: not drawn")
                continue
            for field, expected in want.items():
                if field == "symbols":
                    if len(card["symbols"]) != expected:
                        failures.append(
                            f"{uid}: {len(card['symbols'])} symbols, expected {expected}")
                elif field in ("readings", "others"):
                    for item in expected:
                        if item not in card[field]:
                            failures.append(f"{uid}: {item!r} missing from {card[field]}")
                elif card.get(field) != expected:
                    failures.append(f"{uid}: {field} is {card.get(field)!r}, expected {expected!r}")

            # Every card is a card: measured, painted, and never a bare strip of text.
            if card["width"] < 200 or card["height"] < 40:
                failures.append(f"{uid}: measured {card['width']}x{card['height']}")
            if "rgba(0, 0, 0, 0)" in card["surface"]:
                failures.append(f"{uid}: no surface colour, so the tokens did not reach it")
            # A transcription always comes with a way to hear it and with what made the sound.
            if card["symbols"] and (card["play"] != 1 or card["marks"] != ["synthesised voice"]):
                failures.append(
                    f"{uid}: {card['play']} play buttons and marks {card['marks']}")
            if not card["symbols"] and (card["play"] or card["marks"]):
                failures.append(f"{uid}: a sound offered for a card with no transcription")

        # The dark mode is the same cards in different colours, not fewer cards.
        dark = read(cdp, session, """
            (() => {
              const dark = document.querySelector('[data-mode="dark"]');
              const cards = dark ? dark.querySelectorAll('.card') : [];
              const first = cards[0];
              return JSON.stringify({
                count: cards.length,
                surface: first ? getComputedStyle(first).backgroundColor : '',
              });
            })()
        """)
        night = json.loads(dark or "{}")
        if night.get("count") != len(WANT):
            failures.append(f"dark mode drew {night.get('count')} of {len(WANT)} cards")
        light_surface = cards["entry"]["surface"]
        if night.get("surface") == light_surface:
            failures.append("dark mode is painted in the light mode's colours")

        print(f"  {len(cards)} states drawn, light on {light_surface}, "
              f"dark on {night.get('surface')}")
        for uid in WANT:
            card = cards.get(uid, {})
            print(f"  {uid:10} {card.get('width')}x{card.get('height'):<4} "
                  f"{(card.get('headline') or card.get('note') or '')[:38]}")
        cdp.send("Target.closeTarget", {"targetId": target["targetId"]})
    finally:
        cdp.close()

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print(f"\nPASS - all {len(WANT)} card states draw what that state means")


if __name__ == "__main__":
    main()
