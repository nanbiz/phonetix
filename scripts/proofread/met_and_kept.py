#!/usr/bin/env python3
"""A language the reader keeps meeting is offered to them, and what they keep is kept.

A reader of English with no Spanish on their lists meets "¿Cómo salió todo?" on one English
page after another. The card reads "salió" as the Spanish it is, from the dictionaries already
there and nothing fetched; from the third page it offers Spanish, and "Learn it" puts Spanish on
their learning list. "Not now" on a language is never offered again. And the star on a card
keeps the word, which the settings view then lists.

Both engines, with the published dictionaries.

  pnpm build && pnpm zip:firefox && uv run python scripts/proofread/met_and_kept.py
"""
import http.server
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from harness import PipeCDP  # noqa: E402
from on_a_page import ChromeHand, evaluate  # noqa: E402

PORT = int(os.environ.get("PHONETIX_MET_PORT", "8943"))

SPANISH = (
    "<!doctype html><html lang='es'><meta charset='utf-8'><body style='font:20px serif;margin:40px'>"
    "<p>Me gusta mucho soñar despierto y, por ejemplo, aquí al lado del mar pienso en barcos.</p>"
    "</body></html>"
).encode()

PAGE = (
    "<!doctype html><html lang='en'><meta charset='utf-8'><body style='font:20px serif;margin:40px'>"
    "<p>A question you will hear every day is ¿Cómo salió todo? which asks how everything "
    "went. The answer depends on the day.</p></body></html>"
).encode()

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

CARD_JS = """
(() => {
  const root = (document.getElementById('phonetix-card-host') || {}).shadowRoot;
  const card = root && root.querySelector('.card');
  if (!card) return null;
  const at = (sel) => { const e = card.querySelector(sel); if (!e) return null;
    const b = e.getBoundingClientRect(); return {x: b.left + b.width / 2, y: b.top + b.height / 2}; };
  return {word: card.dataset.word || '',
          ipa: card.querySelector('.card-strip .ipa')?.textContent.trim() ?? '',
          says: card.querySelector('.headline .tr')?.textContent.trim() ?? '',
          parts: [...card.querySelectorAll('.parts .part')].map((p) => p.textContent.trim()),
          offer: card.querySelector('.offer')?.dataset.offer ?? '',
          learn: at('[data-does=learn]'), pass: at('[data-does=pass]'),
          keep: at('[data-does=keep]'), arrow: at('.card-arrow'), kept: card.querySelector('[data-does=keep]')?.getAttribute('aria-pressed') ?? ''};
})()
"""

BASE = {"on": True, "targetLanguage": "en", "knownLanguages": [], "learn": "", "alsoLearn": [],
        "layer": "off", "metLanguages": {}, "passedLanguages": [], "kept": []}


def serve():
    class Page(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(SPANISH if self.path.startswith("/es") else PAGE)

    server = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Page)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def press(hand, card, at):
    """Into the card the way a hand goes: up from the word through the card's arrow, then
    along to the button. A pointer that jumped straight there left the word for nothing, and the
    card went, as it does for a reader."""
    start = card["from"]
    arrow = card["arrow"]
    points = [start, arrow, {"x": arrow["x"], "y": at["y"]}, at]
    for a, b in zip(points, points[1:]):
        for i in range(1, 7):
            hand.move(a["x"] + (b["x"] - a["x"]) * i / 6, a["y"] + (b["y"] - a["y"]) * i / 6)
            time.sleep(0.02)
    hand.click(at["x"], at["y"])


def card_on(hand, word, want=lambda c: True, tries=40):
    at = hand.ask(WORD_JS % (word, len(word)))
    if not at:
        return None
    hand.move(2, 2)
    time.sleep(0.4)
    hand.move(at["x"] - 2, at["y"])
    hand.move(at["x"], at["y"])
    card = None
    for _ in range(tries):
        time.sleep(0.3)
        card = hand.ask(CARD_JS)
        if card and word in card["word"] and card["ipa"] and want(card):
            break
    if card:
        card["from"] = {"x": at["x"], "y": at["y"]}
    return card


def scenario(engine, hand, go, store, read, failures):
    """The whole story on one engine: [go] opens a path, [store] sets settings, [read] reads
    one storage key."""
    store(BASE)
    for n in (1, 2, 3):
        go(f"/page{n}.html")
        card = card_on(hand, "salió", (lambda c: c["offer"]) if n == 3 else (lambda c: True))
        print(f"  {engine}, page {n}: {card and {k: card[k] for k in ('word', 'ipa', 'offer')}}")
        if not card or not card["ipa"].endswith("ES"):
            failures.append(f"{engine}: salió on page {n} is not read as Spanish: {card}")
        if n < 3 and card and card["offer"]:
            failures.append(f"{engine}: Spanish offered after only {n} page(s)")
        if n == 3:
            if not card or card["offer"] != "es" or not card["learn"]:
                failures.append(f"{engine}: no offer of Spanish on the third page: {card}")
            else:
                press(hand, card, card["learn"])
                time.sleep(1.5)
                also = read("alsoLearn") or []
                print(f"  {engine}, learning after Learn it: {also}")
                if "es" not in also:
                    failures.append(f"{engine}: Learn it did not put Spanish on the learning list: {also}")
    held = read("metLanguages") or {}
    if len(held.get("es", [])) != 3:
        failures.append(f"{engine}: pages met not counted three: {held}")
    # Not now: never offered again.
    store({**BASE, "metLanguages": {"es": ["a/1", "a/2", "a/3"]}})
    go("/page4.html")
    card = card_on(hand, "salió", lambda c: c["offer"])
    if not card or not card["pass"]:
        failures.append(f"{engine}: no offer to pass over: {card}")
    else:
        press(hand, card, card["pass"])
        time.sleep(1.5)
        passed = read("passedLanguages") or []
        go("/page5.html")
        again = card_on(hand, "salió", tries=20)
        print(f"  {engine}, passed {passed}, offer after: {again and again['offer']!r}")
        if "es" not in passed or (again and again["offer"]):
            failures.append(f"{engine}: Not now did not stop the offer: passed={passed} card={again}")
    # The star keeps the word the card is about.
    store({**BASE, "knownLanguages": [], "learn": "es"})
    go("/page6.html")
    card = card_on(hand, "question", lambda c: c["keep"])
    if not card or not card["keep"]:
        failures.append(f"{engine}: no star on the card: {card}")
    else:
        press(hand, card, card["keep"])
        time.sleep(1.5)
        after = hand.ask(CARD_JS)
        kept = read("kept") or []
        print(f"  {engine}, kept: {[(k['text'], k['says']) for k in kept]}, star pressed: {after and after['kept']}")
        if not kept or kept[0]["text"] != "question" or not kept[0]["line"]:
            failures.append(f"{engine}: the star did not keep the word with its line: {kept}")
        if not after or after["kept"] != "true":
            failures.append(f"{engine}: the star does not show the word as kept: {after}")
    # A pronoun and its verb read as one: "Me gusta" is "I like", each word under it.
    store({**BASE, "knownLanguages": [], "learn": ""})
    go("/es.html")
    card = card_on(hand, "gusta", lambda c: c["says"], tries=40)
    print(f"  {engine}, gusta in 'Me gusta mucho': {card and {k: card[k] for k in ('word', 'says', 'parts')}}")
    if not card or card["word"].lower() != "me gusta" or card["says"] != "I like" or len(card["parts"]) != 2:
        failures.append(f"{engine}: 'Me gusta' is not read as one expression meaning I like: {card}")


def on_chrome(failures):
    cdp = PipeCDP()
    try:
        cdp.send("Target.setDiscoverTargets", {"discover": True})
        ext = cdp.ensure_extension()

        def attach(url):
            target = cdp.send("Target.createTarget", {"url": url})["targetId"]
            session = cdp.send("Target.attachToTarget", {"targetId": target, "flatten": True})["sessionId"]
            cdp.send("Runtime.enable", session=session)
            return session

        book = attach(f"chrome-extension://{ext}/viewbook.html")
        time.sleep(2)
        evaluate(cdp, book, "chrome.runtime.sendMessage({phonetix:'getPack',data:{lang:'en'}})")
        page = attach("about:blank")
        hand = ChromeHand(cdp, page)

        def go(path):
            cdp.send("Page.navigate", {"url": f"http://127.0.0.1:{PORT}{path}"}, session=page)
            time.sleep(4)

        def store(values):
            evaluate(cdp, book, f"chrome.storage.local.set({json.dumps(values)})")
            time.sleep(0.5)

        def read(key):
            return evaluate(cdp, book, f"chrome.storage.local.get('{key}').then(r => r['{key}'])")

        scenario("chrome", hand, go, store, read, failures)
    finally:
        cdp.close()


def on_firefox(failures):
    from on_firefox import UUID, FirefoxHand, Marionette, newest_zip, profile

    where = tempfile.mkdtemp(prefix="phonetix-met-")
    profile(where)
    firefox = subprocess.Popen(
        ["firefox", "--headless", "--no-remote", "--marionette", "-remote-allow-system-access",
         "--profile", where, "about:blank"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        env={**os.environ, "MOZ_MARIONETTE_PORT": "2828"})
    try:
        driver = None
        for _ in range(60):
            try:
                driver = Marionette(2828)
                break
            except OSError:
                time.sleep(1)
        if driver is None:
            failures.append("firefox: never opened its remote port")
            return
        driver.send("WebDriver:NewSession", {"capabilities": {}})
        driver.send("Addon:Install", {"path": newest_zip(), "temporary": True})
        view = f"moz-extension://{UUID}/viewbook.html"
        hand = FirefoxHand(driver)

        def in_view(script):
            driver.send("WebDriver:Navigate", {"url": view})
            time.sleep(1)
            return driver.script("const done = arguments[0];" + script, timeout=120000)

        page_at = {"path": "/"}

        def go(path):
            page_at["path"] = path
            driver.send("WebDriver:Navigate", {"url": f"http://127.0.0.1:{PORT}{path}"})
            time.sleep(4)

        def store(values):
            in_view(f"browser.storage.local.set({json.dumps(values)}).then(() => done('ok'));")

        def read(key):
            got = in_view(f"browser.storage.local.get('{key}').then(r => done(JSON.stringify(r['{key}'] ?? null)));")
            # Reading meant leaving the page; the scenario goes on from where it was.
            driver.send("WebDriver:Navigate", {"url": f"http://127.0.0.1:{PORT}{page_at['path']}"})
            time.sleep(3)
            return json.loads(got) if got else None

        in_view("browser.runtime.sendMessage({phonetix: 'openPack', data: {lang: 'en'}}).then(() => done('ok'));")
        scenario("firefox", hand, go, store, read, failures)
    finally:
        firefox.terminate()
        firefox.wait()
        shutil.rmtree(where, ignore_errors=True)


def main():
    server = serve()
    failures = []
    try:
        on_chrome(failures)
        on_firefox(failures)
    finally:
        server.shutdown()
    if failures:
        print("FAIL - " + "; ".join(failures))
        sys.exit(1)
    print("PASS - a language met on three pages is offered, learnt or passed over for good, and the star keeps a word")


if __name__ == "__main__":
    main()
