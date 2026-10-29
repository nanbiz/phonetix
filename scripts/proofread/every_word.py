#!/usr/bin/env python3
"""Every word of real pages, pointed at one by one, and what each card says.

The other checks ask about the words they were written for. A reader points at whatever is on
the page, and on a video's subtitles or a flashcard that is every word: the ones nobody wrote a
check for are where the card said "Second-Person-Singular-Gegensprechant", "Schiff, Schiff." or
nothing at all. So this opens pages the way a reader meets them, in a fresh browser with the
dictionaries and models the extension fetches for itself, points at each word in turn and
writes down the card: what it says, how long it took and where it stood.

  uv run scripts/proofread/every_word.py [pages...]       default: every page below
  PHONETIX_PROFILE=/tmp/x keeps the browser's profile, so the downloads happen once.
  PHONETIX_PACKS_DIR=.cache/release serves packs not published yet as the reader's own host.

It writes one JSON line a word to /tmp/phonetix-every-word/<page>.jsonl and prints the cards
whose shape is wrong on its face: none opened, opened away from its word, slow, a line said
twice, a sentence's full stop on a word. What a card means is for a reader to judge from the
file; the shapes are failures.
"""
import http.server
import json
import os
import re
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.environ.get("PHONETIX_EVERY", "/tmp/phonetix-every-word")
PORT = int(os.environ.get("PHONETIX_EVERY_PORT", "8931"))
PAGES_DIR = os.path.join(HERE, "pages")
# Packs to serve as the reader's own host, where they are not published yet: a directory of
# .pack files with packbuild's .pack.json beside each.
PACKS = os.environ.get("PHONETIX_PACKS_DIR", "")

# The reader: German their own, learning Spanish, every word described.
READER = {
    "targetLanguage": "de",
    "knownLanguages": ["de"],
    "learn": "es",
    "describeAll": True,
    "on": True,
    "cardsOnPoint": True,
}

CARD_JS = r"""
(() => {
  const host = document.getElementById('phonetix-card-host');
  const card = host && host.shadowRoot && host.shadowRoot.querySelector('.card');
  if (!card) return null;
  const r = card.getBoundingClientRect();
  const part = (sel) => [...card.querySelectorAll(sel)].map(
    e => e.textContent.replace(/\s+/g, ' ').trim()).filter(Boolean);
  return {
    word: card.dataset.word || '',
    text: card.textContent.replace(/\s+/g, ' ').trim(),
    ipa: part('.card-strip .ipa').join(' '),
    accent: part('.card-strip .accent, .card-strip select, .card-strip [data-accent]').join(' '),
    headline: part('.headline .tr').join(' | '),
    lines: part('.card-head > *'),
    box: {left: r.left, top: r.top, right: r.right, bottom: r.bottom},
  };
})()
"""

WORDS_JS = r"""
(() => {
  const out = [];
  const root = document.querySelector('main') || document.body;
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    if (n.parentElement.closest('#phonetix-card-host, script, style')) continue;
    const text = n.nodeValue;
    const re = /[\p{L}\p{M}]+(?:['’-][\p{L}\p{M}]+)*/gu;
    for (let m = re.exec(text); m; m = re.exec(text)) {
      const range = document.createRange();
      range.setStart(n, m.index);
      range.setEnd(n, m.index + m[0].length);
      const r = range.getBoundingClientRect();
      if (r.width === 0) continue;
      // Not a word the page means as one: a transcription, or letters in a number ("1.2M").
      let from = m.index, to = m.index + m[0].length;
      while (from > 0 && !/\s/.test(text[from - 1])) from--;
      while (to < text.length && !/\s/.test(text[to])) to++;
      if (/[\u0250-\u02FF\d]/u.test(text.slice(from, to))) continue;
      out.push({text: m[0], x: r.left + r.width / 2, y: r.top + r.height / 2,
                left: r.left, right: r.right, top: r.top, bottom: r.bottom,
                line: (n.parentElement.closest('p, div, li, h1, h2, a, section') || n.parentElement)
                  .textContent.trim()});
    }
  }
  return out;
})()
"""


def listing():
    """The packs in PACKS as a host lists them, from what packbuild wrote beside each."""
    packs = []
    for name in sorted(os.listdir(PACKS)):
        if not name.endswith(".pack.json"):
            continue
        said = json.load(open(os.path.join(PACKS, name)))
        packs.append({**said, "lang": name[: -len(".pack.json")]})
    return json.dumps(packs).encode()


def serve():
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            name = os.path.basename(self.path)
            kind = "text/html; charset=utf-8"
            if PACKS and name == "packs.json":
                body, kind = listing(), "application/json"
            elif PACKS and name.endswith(".pack"):
                path = os.path.join(PACKS, name)
                if not os.path.isfile(path):
                    self.send_error(404)
                    return
                body, kind = open(path, "rb").read(), "application/octet-stream"
            else:
                path = os.path.join(PAGES_DIR, name)
                if not os.path.isfile(path):
                    self.send_error(404)
                    return
                body = open(path, "rb").read()
            self.send_response(200)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()


def shape(seen, word, took):
    """What is wrong with a card on its face, whatever it means."""
    wrong = []
    if seen is None:
        return ["no card"]
    box = seen["box"]
    near = (box["left"] - 40 <= word["x"] <= box["right"] + 40
            and (abs(box["bottom"] - word["top"]) < 40 or abs(box["top"] - word["bottom"]) < 40))
    if not near:
        wrong.append(f"card at {box['left']:.0f},{box['top']:.0f}, word at "
                     f"{word['x']:.0f},{word['y']:.0f}")
    if took > 0.6:
        wrong.append(f"opened after {took:.2f}s")
    parts = [p for p in seen["lines"] if p]
    folded = [re.sub(r"[\W_]+", " ", p).strip().lower() for p in parts]
    for i, a in enumerate(folded):
        for b in folded[i + 1:]:
            if a and a == b:
                wrong.append(f"says {a!r} twice")
    for p in parts:
        # A word's meaning is not a sentence: a full stop after one or two words is the
        # translator's, put there because it was handed a sentence.
        if re.fullmatch(r"[^.!?]{1,40}\.", p) and len(p.split()) <= 2:
            wrong.append(f"full stop on {p!r}")
        items = [i.strip().lower() for i in re.split(r"[,;]", p.rstrip("."))]
        if len(items) > 1 and len(set(items)) < len(items):
            wrong.append(f"repeats itself: {p!r}")
    if not seen["ipa"]:
        wrong.append("no transcription")
    return wrong


def main():
    pages = sys.argv[1:] or sorted(p for p in os.listdir(PAGES_DIR) if p.endswith(".html"))
    os.makedirs(OUT, exist_ok=True)
    serve()
    profile = os.environ.get("PHONETIX_PROFILE")
    cdp = PipeCDP(profile=profile) if profile else PipeCDP()
    cdp.send("Target.setDiscoverTargets", {"discover": True})
    extid = cdp.ensure_extension()
    problems = 0
    try:
        book = cdp.send("Target.createTarget", {"url": f"chrome-extension://{extid}/viewbook.html"})
        settings = cdp.send("Target.attachToTarget",
                            {"targetId": book["targetId"], "flatten": True})["sessionId"]
        cdp.send("Runtime.enable", session=settings)
        time.sleep(2)
        reader = {**READER, **({"packBaseUrl": f"http://127.0.0.1:{PORT}"} if PACKS else {})}
        cdp.send("Runtime.evaluate", {
            "expression": f"chrome.storage.local.set({json.dumps(reader)})",
            "awaitPromise": True, "returnByValue": True}, session=settings)
        # The dictionaries and models for those languages, fetched the way the extension
        # fetches them for a new reader, before any page is read.
        for lang in ("es", "de", "en", "es-in-de", "en-in-de"):
            cdp.send("Runtime.evaluate", {
                "expression": "chrome.runtime.sendMessage({phonetix:'getPack',data:{lang:'"
                              + lang + "'}}).then(r => JSON.stringify(r))",
                "awaitPromise": True, "returnByValue": True}, session=settings, timeout=600)

        for name in pages:
            target = cdp.send("Target.createTarget", {"url": f"http://127.0.0.1:{PORT}/{name}"})
            page = cdp.send("Target.attachToTarget",
                            {"targetId": target["targetId"], "flatten": True})["sessionId"]
            cdp.send("Runtime.enable", session=page)
            cdp.send("Emulation.setDeviceMetricsOverride", {
                "width": 1280, "height": 900, "deviceScaleFactor": 1, "mobile": False},
                session=page)

            def ask(expression):
                got = cdp.send("Runtime.evaluate", {
                    "expression": expression, "returnByValue": True}, session=page, timeout=60)
                return got.get("result", {}).get("value")

            def move(x, y):
                cdp.send("Input.dispatchMouseEvent",
                         {"type": "mouseMoved", "x": x, "y": y}, session=page)

            # Drawn, and the lines the engine reads behind it settled.
            time.sleep(8)
            out = open(os.path.join(OUT, name.replace(".html", ".jsonl")), "w")
            done = 0
            for at in range(10_000):
                words = ask(WORDS_JS) or []
                if at >= len(words):
                    break
                word = words[at]
                if word["top"] < 0 or word["bottom"] > 900:
                    ask(f"window.scrollBy(0, {word['top'] - 300})")
                    time.sleep(0.4)
                    word = (ask(WORDS_JS) or words)[at]
                move(2, 2)
                time.sleep(0.35)
                began = time.monotonic()
                move(word["x"], word["y"])
                move(word["x"] + 1, word["y"])
                seen, first, steady = None, None, 0
                # Until the card has been still two seconds: what the translated line adds
                # arrives after the card is up, and the card a reader ends with is what counts.
                while time.monotonic() - began < 8:
                    now = ask(CARD_JS)
                    if now and first is None:
                        first = time.monotonic() - began
                    if now and seen and now["text"] == seen["text"]:
                        steady += 1
                        if steady >= 25:
                            break
                    else:
                        steady = 0
                    seen = now or seen
                    time.sleep(0.08)
                took = first if first is not None else 99
                wrong = shape(seen, word, took)
                out.write(json.dumps({"word": word["text"], "line": word["line"], "took": took,
                                      "card": seen, "wrong": wrong}, ensure_ascii=False) + "\n")
                out.flush()
                done += 1
                if wrong:
                    problems += 1
                    said = (seen or {}).get("text", "")[:90]
                    print(f"  {name} {word['text']!r}: {'; '.join(wrong)} | {said}")
            out.close()
            print(f"{name}: {done} words")
    finally:
        cdp.close()
    print(f"\n{problems} cards wrong on their face; every card in {OUT}")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
