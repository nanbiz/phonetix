#!/usr/bin/env python3
"""A word of another of the reader's languages quoted in a page: its card is in its language.

An English page that teaches Spanish writes "¿Cómo salió todo?" in the middle of English
prose. The page is English, so every word on it was once looked up as English, and "salió"
came out as a made-up English reading (/sælioʊ/). The card has to find the word in the
dictionaries of the reader's own languages and read it as the Spanish it is, and an English
word on the same page still has to read as English.

Runs .output/chrome-mv3 in a headless Chrome and the Firefox zip in a headless Firefox, with\nthe published dictionaries.

  pnpm build && pnpm zip:firefox && uv run python scripts/proofread/quoted_word.py
"""
import http.server
import json
import os
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from harness import PipeCDP  # noqa: E402
from on_a_page import ChromeHand, evaluate  # noqa: E402

PORT = int(os.environ.get("PHONETIX_QUOTED_PORT", "8941"))

PAGE = (
    "<!doctype html><html lang='en'><meta charset='utf-8'><body style='font:20px serif;margin:40px'>"
    "<p>A question you will hear every day is ¿Cómo salió todo? which literally asks how "
    "everything came out.</p></body></html>"
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
  const card = (document.getElementById('phonetix-card-host') || {}).shadowRoot?.querySelector('.card');
  return card && {word: card.dataset.word || '',
                  ipa: card.querySelector('.card-strip .ipa')?.textContent.trim() ?? '',
                  says: card.querySelector('.headline .tr')?.textContent.trim() ?? ''};
})()
"""


def serve():
    class Page(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(PAGE)

    server = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Page)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def probe(hand, failures, engine):
    """Rest on the quoted Spanish word and on an English one, and read each card."""
    for word, lang_word, want in (("salió", "Spanish", lambda c: "æ" not in c["ipa"] and c["ipa"].endswith("ES")),
                                  ("question", "English", lambda c: c["ipa"].endswith(("US", "UK", "GB")))):
        at = hand.ask(WORD_JS % (word, len(word)))
        hand.move(2, 2)
        time.sleep(0.4)
        hand.move(at["x"] - 2, at["y"])
        hand.move(at["x"], at["y"])
        card = None
        for _ in range(30):
            time.sleep(0.3)
            card = hand.ask(CARD_JS)
            if card and card["word"] == word and card["ipa"]:
                break
        print(f"  {engine}, {word}: {card}")
        if not card or card["word"] != word:
            failures.append(f"{engine}: no card on {word!r}")
        elif not want(card):
            failures.append(f"{engine}: {word!r} is not read as {lang_word}: {card}")


def on_firefox(failures, values):
    """The same, in a headless Firefox on a throwaway profile with the built add-on."""
    import shutil
    import subprocess
    import tempfile
    from on_firefox import UUID, FirefoxHand, Marionette, newest_zip, profile

    where = tempfile.mkdtemp(prefix="phonetix-quoted-")
    profile(where)
    firefox = subprocess.Popen(
        ["firefox", "--headless", "--no-remote", "--marionette", "-remote-allow-system-access", "--profile", where, "about:blank"],
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
        driver.send("WebDriver:Navigate", {"url": f"moz-extension://{UUID}/viewbook.html"})
        time.sleep(3)
        driver.script(
            "const done = arguments[0];"
            f"browser.storage.local.set({json.dumps(values)})"
            "  .then(() => Promise.all(['en', 'es'].map(lang =>"
            "     browser.runtime.sendMessage({phonetix: 'openPack', data: {lang}}))))"
            "  .then(() => done('ok'), e => done('failed: ' + e));",
            timeout=300000)
        driver.send("WebDriver:Navigate", {"url": f"http://127.0.0.1:{PORT}/"})
        time.sleep(6)
        probe(FirefoxHand(driver), failures, "firefox")
    finally:
        firefox.terminate()
        firefox.wait()
        shutil.rmtree(where, ignore_errors=True)


def main():
    server = serve()
    cdp = PipeCDP()
    failures = []
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
        values = {"on": True, "targetLanguage": "en", "knownLanguages": ["en"], "learn": "es",
                  "layer": "off"}
        if os.environ.get("PHONETIX_PACKS"):
            values["packBaseUrl"] = os.environ["PHONETIX_PACKS"]
        evaluate(cdp, book, f"chrome.storage.local.set({json.dumps(values)})")
        for lang in ("en", "es"):
            evaluate(cdp, book, f"chrome.runtime.sendMessage({{phonetix:'getPack',data:{{lang:'{lang}'}}}})")
        page = attach(f"http://127.0.0.1:{PORT}/")
        time.sleep(6)
        hand = ChromeHand(cdp, page)

        probe(hand, failures, "chrome")
    finally:
        cdp.close()
    try:
        on_firefox(failures, values)
    finally:
        server.shutdown()
    if failures:
        print("FAIL - " + "; ".join(failures))
        sys.exit(1)
    print("PASS - a Spanish word quoted in an English page is read as Spanish, the English round it as English")


if __name__ == "__main__":
    main()
