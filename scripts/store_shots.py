#!/usr/bin/env python3
"""The Chrome Web Store's screenshots and promo tile, taken from the built extension.

Each scene is a page served here, read by a reader of English learning Spanish, in a headless
Chrome running .output/chrome-mv3 with the published dictionaries; what the screenshot shows is
what the extension drew, nothing composed by hand but the frame around the settings view.

  pnpm build && uv run --with pillow python scripts/store_shots.py

Writes store/screenshot-*.png (1280x800) and store/promo-small-440x280.png.
"""
import base64
import http.server
import io
import os
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "proofread"))
from harness import PipeCDP  # noqa: E402
from on_a_page import ChromeHand, evaluate  # noqa: E402
from PIL import Image, ImageDraw, ImageFilter, ImageFont  # noqa: E402

ROOT = os.path.dirname(HERE)
STORE = os.path.join(ROOT, "store")
PORT = int(os.environ.get("PHONETIX_SHOTS_PORT", "8937"))
HOST = "viaje.example"
W, H = 1280, 800

STYLE = (
    "<style>body{margin:0;background:#faf8f4;color:#222;font:16px/1.7 Georgia,serif}"
    "main{width:540px;margin:28px auto}h1{font:600 26px/1.2 Georgia,serif;margin:0 0 4px}"
    ".by{font:12px sans-serif;color:#888;margin-bottom:16px}p{margin:0 0 14px}</style>"
)

SPANISH = (
    "<!doctype html><html lang='es'><meta charset='utf-8'><title>Un paseo por Sevilla</title>"
    + STYLE + "<main><h1>Un paseo por Sevilla</h1><div class='by'>Diario de viaje</div>"
    "<p>Ayer anduvimos por el barrio de Santa Cruz hasta la catedral. Las calles son estrechas "
    "y llenas de flores, y en cada plaza hay una fuente que suena bajo los naranjos.</p>"
    "<p>A mediodía comimos en una taberna pequeña cerca del río. El camarero nos recomendó "
    "las espinacas con garbanzos, y mi hermana pidió un gazpacho tan frío que parecía nieve.</p>"
    "<p>Por la tarde subimos a la Giralda. Desde arriba se ve toda la ciudad, los tejados "
    "blancos, el puente de Triana y, a lo lejos, las colinas que rodean el valle.</p>"
    "</main></html>"
).encode()

ENGLISH = (
    "<!doctype html><html lang='en'><meta charset='utf-8'><title>A walk through Seville</title>"
    + STYLE + "<main><h1>A walk through Seville</h1><div class='by'>Travel diary</div>"
    "<p>Yesterday we walked through the old quarter as far as the cathedral. The streets are "
    "narrow and full of flowers, and in every square there is a fountain under the orange "
    "trees.</p><p>At noon we ate in a small tavern near the river. The waiter recommended the "
    "spinach with chickpeas, and my sister ordered a soup so cold it seemed like snow.</p>"
    "<p>In the afternoon we climbed the tower. From the top you can see the whole city, the "
    "white roofs, the old bridge and, far away, the hills around the valley.</p>"
    "</main></html>"
).encode()

PAGES = {"/es.html": SPANISH, "/en.html": ENGLISH}

WORD_JS = """
(() => {
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  for (let n; (n = walker.nextNode());) {
    const at = n.textContent.indexOf(%r);
    if (at < 0) continue;
    const r = document.createRange();
    r.setStart(n, at); r.setEnd(n, at + %d);
    const b = r.getBoundingClientRect();
    return {x: b.left + b.width / 2, y: b.top + b.height / 2};
  }
  return null;
})()
"""


def serve():
    class Page(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            body = PAGES.get(self.path.split("?")[0])
            self.send_response(200 if body else 404)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(body or b"")

    server = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Page)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


class Shots:
    def __init__(self):
        # The page is served under a name, so the settings view names a site, not an address.
        self.cdp = PipeCDP(extra_args=[f"--host-resolver-rules=MAP {HOST} 127.0.0.1"])
        self.cdp.send("Target.setDiscoverTargets", {"discover": True})
        self.ext = self.cdp.ensure_extension()
        book = self.cdp.send("Target.createTarget",
                             {"url": f"chrome-extension://{self.ext}/viewbook.html"})
        self.book = self.attach(book["targetId"])
        time.sleep(2)
        target = self.cdp.send("Target.createTarget", {"url": "about:blank"})
        self.page = self.attach(target["targetId"])
        self.zoomed(True)
        self.hand = ChromeHand(self.cdp, self.page)

    def zoomed(self, near):
        """Half the screenshot's size at twice the scale, so the words read at store size; the
        settings view is shown at the scale it has in a browser, over a page at that scale."""
        scale = 2 if near else 1.5
        self.scale = scale
        self.size(self.page, round(W / scale), round(H / scale), scale)

    def attach(self, target):
        session = self.cdp.send("Target.attachToTarget",
                                {"targetId": target, "flatten": True})["sessionId"]
        self.cdp.send("Runtime.enable", session=session)
        self.cdp.send("Page.enable", session=session)
        return session

    def size(self, session, width, height, scale=1):
        self.cdp.send("Emulation.setDeviceMetricsOverride", {
            "width": width, "height": height, "deviceScaleFactor": scale, "mobile": False},
            session=session)

    def settings(self, **values):
        import json
        evaluate(self.cdp, self.book, f"chrome.storage.local.set({json.dumps(values)})")

    def packs(self, *langs):
        for lang in langs:
            evaluate(self.cdp, self.book,
                     f"chrome.runtime.sendMessage({{phonetix:'getPack',data:{{lang:'{lang}'}}}})")

    def open(self, path, settle=6):
        self.cdp.send("Page.navigate", {"url": f"http://{HOST}:{PORT}{path}"},
                      session=self.page)
        time.sleep(settle)

    def word(self, text):
        return self.hand.ask(WORD_JS % (text, len(text)))

    def point(self, text, wait=3):
        at = self.word(text)
        if not at:
            raise RuntimeError(f"no {text!r} on the page")
        self.hand.move(2, 2)
        time.sleep(0.4)
        for step in (0, 1):
            self.hand.move(at["x"] + step, at["y"])
            time.sleep(0.05)
        time.sleep(wait)

    def key(self, key, code, mods):
        for kind in ("rawKeyDown", "keyUp"):
            self.cdp.send("Input.dispatchKeyEvent", {
                "type": kind, "key": key, "code": code, "modifiers": mods,
                "windowsVirtualKeyCode": ord(key.upper())}, session=self.page)

    def shot(self, session=None):
        data = self.cdp.send("Page.captureScreenshot", {"format": "png"},
                             session=session or self.page)["data"]
        return Image.open(io.BytesIO(base64.b64decode(data))).convert("RGB")

    def popup(self):
        """The settings view as the toolbar shows it, at the size it takes there."""
        target = self.cdp.send("Target.createTarget",
                               {"url": f"chrome-extension://{self.ext}/popup.html"})
        session = self.attach(target["targetId"])
        self.size(session, 420, 520, self.scale)
        time.sleep(3)
        box = evaluate(self.cdp, session,
                       "(() => { const r = document.body.getBoundingClientRect();"
                       " return [Math.ceil(r.width), Math.ceil(r.height)]; })()")
        image = self.shot(session).crop(
            (0, 0, round(min(box[0], 420) * self.scale), round(min(box[1], 520) * self.scale)))
        self.cdp.send("Target.closeTarget", {"targetId": target["targetId"]})
        return image

    def close(self):
        self.cdp.close()


def save(image, name):
    path = os.path.join(STORE, name)
    image.save(path, "PNG")
    print(f"  {path} {image.size}")


def framed(page, popup):
    """The settings view over the page it was opened on, under the toolbar's corner."""
    out = page.copy()
    x, y = W - popup.width - 24, 12
    shadow = Image.new("RGBA", (popup.width + 40, popup.height + 40), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((20, 20, popup.width + 20, popup.height + 20), 10,
                                             fill=(0, 0, 0, 90))
    shadow = shadow.filter(ImageFilter.GaussianBlur(10))
    out.paste(shadow, (x - 20, y - 16), shadow)
    out.paste(popup, (x, y))
    return out


def face(name):
    """The file of an installed font, as fontconfig finds it."""
    import subprocess
    return subprocess.run(["fc-match", "-f", "%{file}", name], capture_output=True,
                          text=True, check=True).stdout


def tile(card):
    """The small promo tile: the name over the card, on the pages' own ground."""
    out = Image.new("RGB", (440, 280), (250, 248, 244))
    icon = Image.open(os.path.join(ROOT, "public", "icon", "128.png")).convert("RGBA")
    icon = icon.resize((56, 56), Image.LANCZOS)
    out.paste(icon, (32, 30), icon)
    draw = ImageDraw.Draw(out)
    font = ImageFont.truetype(face("DejaVu Sans:bold"), 32)
    small = ImageFont.truetype(face("DejaVu Sans"), 16)
    draw.text((102, 30), "Phonetix", font=font, fill=(34, 34, 34))
    draw.text((104, 70), "Read a language as you learn it", font=small, fill=(110, 110, 110))
    card.thumbnail((376, 150), Image.LANCZOS)
    # The card's own rounded outline, so the page beside its corners stays out.
    mask = Image.new("L", card.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, card.width - 1, card.height - 1), 10, fill=255)
    out.paste(card, ((440 - card.width) // 2, 112 + (150 - card.height) // 2), mask)
    return out


def card_box(shots):
    return shots.hand.ask("""(() => {
      const host = document.getElementById('phonetix-card-host');
      const card = host && host.shadowRoot && host.shadowRoot.querySelector('.card');
      if (!card) return null;
      const r = card.getBoundingClientRect();
      return [r.left, r.top, r.right, r.bottom];
    })()""")


def main():
    os.makedirs(STORE, exist_ok=True)
    server = serve()
    shots = Shots()
    try:
        # PHONETIX_PACKS names another host for the dictionaries than the published one.
        if os.environ.get("PHONETIX_PACKS"):
            shots.settings(packBaseUrl=os.environ["PHONETIX_PACKS"])
        shots.settings(on=True, targetLanguage="en", knownLanguages=["en"], learn="es",
                       alsoLearn=[], layer="off", density=1, theme="system", dark="light")
        shots.packs("es", "en")
        time.sleep(3)

        # 1. A card on a Spanish page: how it is said, what it means here, which form it is.
        shots.open("/es.html")
        shots.point("anduvimos", wait=5)
        save(shots.shot(), "screenshot-1.png")
        box = card_box(shots)
        card = shots.shot().crop(tuple(round(v * 2) for v in box)) if box else None

        # 2. How the words of the page are said, written above them.
        shots.settings(layer="sound", density=3)
        shots.open("/es.html", settle=10)
        shots.point("recomendó", wait=5)
        save(shots.shot(), "screenshot-2.png")

        # 3. An English page with some of its words in the Spanish being learnt.
        shots.settings(layer="meaning", density=3)
        shots.open("/en.html", settle=12)
        save(shots.shot(), "screenshot-3.png")

        # 4. The translator, over the page.
        shots.settings(layer="off")
        shots.open("/es.html")
        shots.hand.click(40, 40)
        shots.key("T", "KeyT", 1 | 8)
        time.sleep(2)
        shots.cdp.send("Input.insertText", {"text": "station"}, session=shots.page)
        time.sleep(6)
        save(shots.shot(), "screenshot-4.png")

        # 5. The settings, from the toolbar.
        shots.zoomed(False)
        shots.open("/es.html")
        save(framed(shots.shot(), shots.popup()), "screenshot-5.png")

        if card:
            save(tile(card), "promo-small-440x280.png")
    finally:
        shots.close()
        server.shutdown()


if __name__ == "__main__":
    main()
