#!/usr/bin/env python3
"""A photograph of the settings view, and what a check can say about how it looks.

Every other browser check reads the DOM: which rows exist, what a control reports, what
changed when it was pressed. All of that passed while the view had a white margin down two
edges, a switch whose knob was the colour of its own track, and a segmented control cut out of
spans that took the text cursor and dragged into a selection. None of it is visible to a
question about the DOM, and all of it is visible in a picture.

So this renders the popup at its real size and asks the things a reader would notice: that the
page is one colour to its edges, that a control is a control, and that pressing one changes
what is drawn. The picture is kept either way, so there is something to look at.

  uv run scripts/proofread/popup_view.py
"""
# /// script
# dependencies = ["pillow"]
# ///

import base64
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SHOTS = os.environ.get("PHONETIX_SHOTS", "/tmp/phonetix-popup")


def evaluate(cdp, session, expression):
    got = cdp.send("Runtime.evaluate", {
        "expression": expression, "awaitPromise": True, "returnByValue": True,
    }, session=session)
    return got.get("result", {}).get("value")


def shot(cdp, session, name):
    os.makedirs(SHOTS, exist_ok=True)
    got = cdp.send("Page.captureScreenshot", {"format": "png", "captureBeyondViewport": True},
                   session=session)
    path = os.path.join(SHOTS, f"{name}.png")
    with open(path, "wb") as f:
        f.write(base64.b64decode(got["data"]))
    return path


def edges(path, inset=3):
    """The colours along the outermost columns and rows of the picture.

    A popup is its own document, so nothing of the browser is in the picture: every pixel at
    the edge is the extension's, and a strip of a different colour there is the page showing
    around a panel that does not fill it.
    """
    from PIL import Image
    image = Image.open(path).convert("RGB")
    width, height = image.size
    seen = {}
    for x in range(0, width, 2):
        for y in (inset, height - 1 - inset):
            seen[image.getpixel((x, y))] = seen.get(image.getpixel((x, y)), 0) + 1
    for y in range(0, height, 2):
        for x in (inset, width - 1 - inset):
            seen[image.getpixel((x, y))] = seen.get(image.getpixel((x, y)), 0) + 1
    return seen, image.size


def main():
    cdp = PipeCDP()
    cdp.send("Target.setDiscoverTargets", {"discover": True})
    extid = cdp.ensure_extension()
    failures = []
    try:
        target = cdp.send("Target.createTarget",
                          {"url": f"chrome-extension://{extid}/popup.html"})
        session = cdp.send(
            "Target.attachToTarget", {"targetId": target["targetId"], "flatten": True},
        )["sessionId"]
        cdp.send("Runtime.enable", session=session)
        cdp.send("Page.enable", session=session)
        # The popup's own width, so the picture is the shape a reader sees rather than a
        # window's default.
        cdp.send("Emulation.setDeviceMetricsOverride", {
            "width": 384, "height": 700, "deviceScaleFactor": 1, "mobile": False,
        }, session=session)
        time.sleep(3)

        rendered = evaluate(cdp, session, "document.querySelectorAll('button, input').length")
        if not rendered:
            print("FAIL - the settings view drew no controls at all")
            sys.exit(1)

        picture = shot(cdp, session, "settings")
        print(f"  a picture of the settings view: {picture}")

        # One colour to the edges. A panel with its own border, radius and shadow, floating on
        # an unpainted document, showed the document at two edges as a white L.
        seen, size = edges(picture)
        ranked = sorted(seen.items(), key=lambda kv: -kv[1])
        top, count = ranked[0]
        share = count / sum(seen.values())
        print(f"  {size[0]}x{size[1]}, edge colour {top} on {share:.0%} of the border")
        if share < 0.9:
            failures.append(
                f"the edges of the view are not one colour: {ranked[:4]}")

        # A control is a control: the things a reader presses are buttons and inputs, not text.
        # A segmented control made of spans reports itself here as nothing at all.
        controls = json.loads(evaluate(cdp, session, """
            (() => {
              const pressable = [...document.querySelectorAll('button, input, select')];
              const text = [...document.querySelectorAll('span[role="button"]')];
              return JSON.stringify({
                pressable: pressable.length,
                spans: text.length,
                cursors: pressable
                  .map(el => getComputedStyle(el).cursor)
                  .filter(c => c !== 'pointer' && c !== 'default'),
                selectable: pressable
                  .filter(el => getComputedStyle(el).userSelect === 'text')
                  .map(el => el.textContent.trim().slice(0, 12))
                  .filter(t => t.length > 0),
              });
            })()
        """))
        print(f"  {controls['pressable']} real controls, {controls['spans']} spans pretending")
        if controls["spans"]:
            failures.append(f"{controls['spans']} controls are spans, not controls")
        if controls["cursors"]:
            failures.append(f"controls carrying the wrong cursor: {controls['cursors'][:4]}")
        if controls["selectable"]:
            failures.append(
                f"controls whose label drags into a selection: {controls['selectable'][:4]}")

        # The switch reads as on or off. Its knob is drawn against its own track, and painted
        # in the surface colour it vanished into it in both palettes.
        knob = json.loads(evaluate(cdp, session, """
            (() => {
              const el = document.querySelector('input.toggle');
              if (!el) return JSON.stringify({found: false});
              const box = getComputedStyle(el);
              const dot = getComputedStyle(el, '::before');
              return JSON.stringify({
                found: true,
                track: box.backgroundColor,
                knob: dot.backgroundColor || dot.color,
              });
            })()
        """))
        if not knob.get("found"):
            failures.append("there is no switch in the view")
        else:
            print(f"  the switch: knob {knob['knob']} on track {knob['track']}")
            if knob["knob"] == knob["track"]:
                failures.append(
                    f"the switch's knob is the colour of its own track ({knob['knob']})")
    finally:
        cdp.close()

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the settings view is one surface, and its controls are controls")


if __name__ == "__main__":
    main()
