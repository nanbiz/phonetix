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
import http.server
import json
import os
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP, OFFLINE

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SHOTS = os.environ.get("PHONETIX_SHOTS", "/tmp/phonetix-popup")
PORT = int(os.environ.get("PHONETIX_POPUP_PORT", "8933"))
PACK_BYTES = 1_000_000


class Page(http.server.BaseHTTPRequestHandler):
    """A page for the popup to be about: on a site, it carries the row for that site.

    And the host its dictionary comes from, which sends four tenths of it and then holds the
    line open, so the dictionary is on its way for as long as the check looks: the progress the
    popup shows is the extension's own, not a value written into storage behind its back, which
    the first real download to end wiped.
    """

    def do_GET(self):
        if self.path == "/packs.json":
            # The page's dictionary, listed here: the check is about this host alone, and the
            # extension fetches only what a listing offers.
            body = json.dumps([{"id": "lex-de", "lang": "de", "built": 0, "entries": 0,
                                "keys": 0, "glosses": 0, "bytes": PACK_BYTES,
                                "sha256": ""}]).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if self.path.endswith(".pack"):
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(PACK_BYTES))
            self.end_headers()
            try:
                self.wfile.write(bytes(PACK_BYTES * 4 // 10))
                self.wfile.flush()
                time.sleep(600)
            except OSError:
                pass
            return
        body = b"<!doctype html><html lang='de'><body><p>Der Hund liest ein Buch.</p></body></html>"
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


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


def wrapped(cdp, session, where):
    """Every name and value in the view that runs onto a second line.

    A row says what it is and what it is set to, one line each: a value that wrapped left its
    last word alone on a line under it, "(currently: on)" split from what it belonged to.
    """
    broken = json.loads(evaluate(cdp, session, """
        JSON.stringify([...document.querySelectorAll(
            '[data-name], [data-about], .item-name, .item-value, .group-name, .density-says, ' +
            '.arriving span, .segments button, .button, kbd')]
          .filter(el => el.offsetParent !== null)
          .filter(el => {
            // The lines the text itself sits on, whatever padding its box has.
            const range = document.createRange();
            range.selectNodeContents(el);
            // Two lines are a line's height apart; a key drawn beside text sits a few pixels
            // lower on the same one.
            const bottoms = [...range.getClientRects()]
              .filter(box => box.width > 0)
              .map(box => box.bottom);
            const line = parseFloat(getComputedStyle(el).fontSize);
            return bottoms.some(b => b - Math.min(...bottoms) > line * 0.8);
          })
          .map(el => el.textContent.trim().slice(0, 40)))
    """))
    return [f"{where}: text on two lines: {text!r}" for text in broken]


def real_popup(cdp, extid):
    """Open the popup the way the toolbar button does and measure the window it got.

    Every other question here is asked of popup.html in a tab, at a viewport the check sets
    itself, and none of them can see how big the browser makes the popup: it sizes the window
    to the document, and lays the document out to find that size in a viewport a few pixels
    wide. A width relative to the viewport passed every check in a tab and opened as a line
    25px wide on the toolbar.
    """
    failures = []
    worker = next(
        t for t in cdp.send("Target.getTargets")["targetInfos"]
        if t["type"] == "service_worker" and t["url"].startswith(f"chrome-extension://{extid}/"))
    session = cdp.send(
        "Target.attachToTarget", {"targetId": worker["targetId"], "flatten": True},
    )["sessionId"]
    # Dictionaries from this check's own host, which never finishes sending one.
    evaluate(cdp, session,
             f"chrome.storage.local.set({{packBaseUrl: 'http://127.0.0.1:{PORT}'}}).then(() => 1)")
    # Over a page on a site, which is when the popup is at its tallest: the site's own row.
    page = cdp.send("Target.createTarget", {"url": f"http://127.0.0.1:{PORT}/"})["targetId"]
    cdp.send("Target.activateTarget", {"targetId": page})
    # At its tallest: with the page's dictionary on its way, which adds a row the popup has to
    # make room for without scrolling.
    coming = None
    for _ in range(30):
        time.sleep(0.5)
        coming = evaluate(cdp, session,
                          "chrome.storage.local.get('arriving').then(r => JSON.stringify(r.arriving ?? {}))")
        if coming and coming != "{}":
            break
    print(f"  on its way: {coming}")
    opened = evaluate(
        cdp, session, "chrome.action.openPopup().then(() => 'opened', e => String(e))")
    if opened != "opened":
        return [f"the popup would not open from the toolbar button: {opened}"]
    popup = None
    for _ in range(20):
        popup = next((t for t in cdp.send("Target.getTargets")["targetInfos"]
                      if t["url"].startswith(f"chrome-extension://{extid}/popup.html")), None)
        if popup:
            break
        time.sleep(0.5)
    if not popup:
        return ["the toolbar button opened no popup"]
    session = cdp.send(
        "Target.attachToTarget", {"targetId": popup["targetId"], "flatten": True},
    )["sessionId"]
    cdp.send("Page.enable", session=session)
    # The view draws once the settings are read; the size is taken once it has rows.
    for _ in range(20):
        if evaluate(cdp, session, "document.querySelectorAll('[data-name]').length"):
            break
        time.sleep(0.5)
    # And until the window has stopped growing: it is resized as the view fills in, and a
    # picture taken while it was still growing came out as two halves of different frames.
    last = None
    for _ in range(10):
        time.sleep(0.5)
        now = evaluate(cdp, session, "window.innerHeight")
        if now == last:
            break
        last = now
    size = json.loads(evaluate(cdp, session, """
        JSON.stringify({
          width: window.innerWidth,
          height: window.innerHeight,
          tall: document.documentElement.scrollHeight,
          wide: document.documentElement.scrollWidth,
          rem: parseFloat(getComputedStyle(document.documentElement).fontSize),
        })
    """))
    picture = shot(cdp, session, "toolbar-popup")
    print(f"  the toolbar popup: {size['width']}x{size['height']}, {picture}")
    if not evaluate(cdp, session, "document.querySelectorAll('[data-row=arriving]').length"):
        failures.append("a dictionary on its way is not shown in the popup")
    if not evaluate(cdp, session, "document.querySelectorAll('[data-row=site]').length"):
        failures.append("the popup over a site has no row for that site")
    # The width the stylesheet gives it, 26rem, and as tall as at least its header and a
    # few rows.
    if abs(size["width"] - 26 * size["rem"]) > 2:
        failures.append(
            f"the toolbar popup is {size['width']}px wide, not {26 * size['rem']:.0f}")
    failures += wrapped(cdp, session, "the toolbar popup")
    # And every screen behind it, each as it opens in the same window: one that wraps or runs
    # past the window is as broken as the first.
    for row, name in (("pronunciation", "pronunciation"), ("theme", "appearance")):
        evaluate(cdp, session, f"document.querySelector('[data-row={row}]').click()")
        time.sleep(1)
        failures += wrapped(cdp, session, f"the {name} screen")
        inner = json.loads(evaluate(cdp, session, """JSON.stringify({
            tall: document.documentElement.scrollHeight, height: window.innerHeight})"""))
        shot(cdp, session, f"toolbar-{name}")
        # No higher than the first screen's window could be made: a browser gives a popup
        # at most 600px less its own frame, and a headless one on a small screen less still.
        if inner["tall"] > inner["height"] or inner["tall"] > 520:
            failures.append(f"the {name} screen is {inner['tall']}px in a "
                            f"{inner['height']}px window")
        evaluate(cdp, session, "document.querySelector('[aria-label=Back]').click()")
        time.sleep(0.5)
    # The lists of languages, which scroll inside themselves and say so.
    for row in ("target", "known"):
        evaluate(cdp, session, f"document.querySelector('[data-row={row}]').click()")
        time.sleep(1)
        failures += wrapped(cdp, session, f"the {row} list")
        shot(cdp, session, f"toolbar-{row}")
        evaluate(cdp, session, "document.querySelector('[data-sheet] .icon-button').click()")
        time.sleep(0.5)
    if size["height"] < 200:
        failures.append(f"the toolbar popup is {size['height']}px tall")
    if size["wide"] > size["width"]:
        failures.append(
            f"the toolbar popup scrolls sideways: {size['wide']}px in {size['width']}px")
    # A browser gives a popup at most 600px and scrolls the rest, and a first screen that
    # scrolls hides its last rows from a reader who never thinks to.
    if size["tall"] > size["height"] or size["tall"] > 560:
        failures.append(
            f"the toolbar popup's first screen is {size['tall']}px in a {size['height']}px window")
    return failures


def main():
    server = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Page)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    cdp = PipeCDP(extra_args=[OFFLINE])
    cdp.send("Target.setDiscoverTargets", {"discover": True})
    extid = cdp.ensure_extension()
    failures = []
    try:
        failures += real_popup(cdp, extid)
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
            "width": 416, "height": 700, "deviceScaleFactor": 1, "mobile": False,
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
                  // A field a reader types into carries the text cursor, which is what says
                  // it can be typed into; everything else here is pressed rather than typed.
                  .filter(el => !(el.tagName === 'INPUT'
                    && ['text', 'url', 'search', 'email', 'number'].includes(el.type)))
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

        # On a phone the popup is a sheet the width of the device, and the view has to be that
        # width: at a fixed 26rem on a 360px screen the right edge of every row was past it.
        # And every row starts where every other row starts - a banner inset by its own padding
        # rather than the panel's was 4px left of everything under it.
        PHONE = 360
        # A finger, so the view sees the coarse pointer a phone's browser reports and lays
        # itself out for the sheet rather than for a desktop popup window.
        cdp.send("Emulation.setTouchEmulationEnabled",
                 {"enabled": True, "maxTouchPoints": 5}, session=session)
        for _ in range(6):
            cdp.send("Emulation.setDeviceMetricsOverride", {
                "width": PHONE, "height": 760, "deviceScaleFactor": 1, "mobile": True,
            }, session=session)
            time.sleep(1)
            # The window really is that size before anything is measured in it: an override
            # that did not take makes every question below a question about the wrong screen,
            # and the answers all look fine.
            if evaluate(cdp, session, "window.innerWidth") == PHONE:
                break
        else:
            # A page that will not be shown narrower than its own content is the fault this
            # asks about: on a phone the popup is a sheet the width of the device.
            failures.append(
                f"the view will not fit a {PHONE}px screen: the window stayed "
                f"{evaluate(cdp, session, 'window.innerWidth')}px")
        narrow = json.loads(evaluate(cdp, session, """
            (() => {
              const edges = [...document.querySelectorAll('.card .item [data-name]')]
                .filter(el => el.offsetParent !== null)
                .map(el => ({
                  says: el.textContent.trim().slice(0, 18),
                  left: Math.round(el.getBoundingClientRect().left),
                }));
              return JSON.stringify({
                wide: document.documentElement.scrollWidth,
                seen: window.innerWidth,
                edges,
              });
            })()
        """))
        lefts = sorted({row["left"] for row in narrow["edges"]})
        print(f"  on a {narrow['seen']}px screen it draws {narrow['wide']}px wide, "
              f"rows starting at {lefts}")
        if narrow["seen"] == PHONE and narrow["wide"] > narrow["seen"]:
            failures.append(
                f"the view is {narrow['wide']}px wide on a {narrow['seen']}px screen")
        # One edge for the rows, and the name beside the mark at the top is allowed its own:
        # it sits after an icon rather than at the panel's margin.
        if len(lefts) > 2:
            failures.append(f"rows start at {lefts}: {narrow['edges'][:6]}")
        cdp.send("Emulation.setTouchEmulationEnabled", {"enabled": False}, session=session)
        cdp.send("Emulation.setDeviceMetricsOverride", {
            "width": 416, "height": 700, "deviceScaleFactor": 1, "mobile": False,
        }, session=session)
        time.sleep(1)

        # The switch reads as on or off. Its knob is drawn against its own track, and painted
        # in the surface colour it vanished into it in both palettes.
        knob = json.loads(evaluate(cdp, session, """
            (() => {
              const el = document.querySelector('input.switch');
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
