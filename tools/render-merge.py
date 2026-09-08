#!/usr/bin/env python3
"""Render every declared state of the merge model out of surface.html.

viewbook maps a render to a shape by the end of its filename, so each state is cut out at
each shape's width and written as <slug>-<shape>.png. The states all live on one page, in a
light block and a dark block; the light one is the render, and the page itself is where both
are compared.
"""
import base64, json, os, re, subprocess, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
BOOK = os.path.join(HERE, "..", "docs", "merge")
PAGE = os.path.abspath(os.path.join(BOOK, "surface.html"))
IMG = os.path.abspath(os.path.join(BOOK, "img"))
PORT = 9412


def cdp(ws, mid, method, params=None):
    ws.send(json.dumps({"id": mid, "method": method, "params": params or {}}))
    while True:
        m = json.loads(ws.recv())
        if m.get("id") == mid:
            return m.get("result", {})


def main():
    from websocket import create_connection
    os.makedirs(IMG, exist_ok=True)
    chrome = subprocess.Popen(
        [os.environ.get("PHONETIX_CHROMIUM", "chromium"), "--headless", "--disable-gpu", "--no-sandbox", "--hide-scrollbars",
         f"--remote-debugging-port={PORT}", "--remote-allow-origins=*",
         "--window-size=1280,1200", "about:blank"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        url = None
        for _ in range(80):
            try:
                tabs = json.load(urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json", timeout=2))
                url = next(t["webSocketDebuggerUrl"] for t in tabs if t.get("type") == "page")
                break
            except Exception:
                time.sleep(0.25)
        if not url:
            print("chromium did not come up"); return 1
        ws = create_connection(url, timeout=60)
        n = [0]
        def call(method, params=None):
            n[0] += 1
            return cdp(ws, n[0], method, params)
        call("Page.enable")
        made = 0
        wrote = set()
        for shape, width in (("phone", 390), ("desktop", 1280)):
            call("Emulation.setDeviceMetricsOverride", {
                "width": width, "height": 1200, "deviceScaleFactor": 2, "mobile": False})
            call("Page.navigate", {"url": "file://" + PAGE})
            time.sleep(3.5)
            drawn, count = cut(call, shape)
            wrote |= drawn
            made += count
        # Anything in the gallery this run did not draw is left over from a state that has been
        # renamed or removed, so it goes. Swept after drawing rather than cleared before it, so
        # a run that dies half way leaves the old pictures rather than none.
        stale = sorted(f for f in os.listdir(IMG) if f.endswith(".png") and f not in wrote)
        for f in stale:
            os.remove(os.path.join(IMG, f))
            print(f"swept, nothing draws it any more: {f}")
        print(f"wrote {made} images to {IMG}")
        return check()
    finally:
        chrome.terminate()


def check():
    """The page and the model have to name the same states.

    Pixels cannot be compared between machines, because the page asks for system font stacks
    and a runner resolves them differently from this one, so every box comes out a different
    size. What can be compared is which states exist: a state drawn on the page and never
    declared, or declared and never drawn, is the mistake this catches, and it is the one that
    left six views naming twelve pictures nobody produced.
    """
    with open(os.path.join(BOOK, "model.json"), encoding="utf-8") as f:
        model = json.load(f)
    declared = {r for kind in ("views", "states") for it in model[kind]
                for r in it.get("renders", [])}
    drawn = {n for n in os.listdir(IMG) if n.endswith(".png")}
    missing = sorted(declared - drawn)
    extra = sorted(drawn - declared)
    for n in missing:
        print(f"declared and not drawn: {n}")
    for n in extra:
        print(f"drawn and not declared: {n}")
    if missing or extra:
        print(f"{len(missing)} declared without a picture, {len(extra)} pictures nothing declares")
        return 1
    print(f"the page and the model name the same {len(declared)} renders")
    return 0


def cut(call, shape):
    import base64 as b64, re as _re, os as _os
    boxes = call("Runtime.evaluate", {
            "returnByValue": True,
            "expression": """
              (() => {
                const out = {};
                for (const el of document.querySelectorAll('[data-uid]')) {
                  const uid = el.getAttribute('data-uid');
                  const r = el.getBoundingClientRect();
                  (out[uid] = out[uid] || []).push(
                      {x: r.x + scrollX, y: r.y + scrollY, w: r.width, h: r.height});
                }
                return out;
              })()
            """})["result"]["value"]
    made = 0
    drawn = set()
    for uid, rects in boxes.items():
        slug = _re.sub(r"^(STATE|VIEW)-", "", uid).lower()
        rect = rects[0]
        if rect["w"] < 4 or rect["h"] < 4:
            continue
        shot = call("Page.captureScreenshot", {
            "format": "png", "captureBeyondViewport": True,
            "clip": {"x": rect["x"], "y": rect["y"], "width": rect["w"],
                     "height": min(rect["h"], 4000), "scale": 1}})
        name = f"{slug}-{shape}.png"
        with open(_os.path.join(IMG, name), "wb") as f:
            f.write(b64.b64decode(shot["data"]))
        drawn.add(name)
        made += 1
    return drawn, made


if __name__ == "__main__":
    sys.exit(main())
