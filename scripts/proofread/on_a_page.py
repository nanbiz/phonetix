#!/usr/bin/env python3
"""The extension over a real page: what it draws, what it opens, and what it leaves behind.

Three things have to be true at once and none of them is visible from a message log. The
words a reader sees have to carry what the core decided, in the places the core said. The
card has to open on the word the reader stopped at and be about that word. And switching the
extension off has to give back the page that was there, character for character, because a
page restored approximately is a page quietly rewritten.

  uv run python scripts/proofread/on_a_page.py
"""
import http.server
import json
import os
import subprocess
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
CORE = os.path.join(ROOT, "core")
WORK = os.environ.get("PHONETIX_WORK", "/tmp/phonetix-on-a-page")
PORT = int(os.environ.get("PHONETIX_PAGE_PORT", "8924"))

SENTENCE = "El perro corre por el camino y descansa en el banco del parque."
PAGE = (
    "<!doctype html><html lang='es'><meta charset='utf-8'>"
    "<body><main><p id='prose'>" + SENTENCE + "</p>"
    "<pre id='code'>const perro = 1;</pre>"
    "<nav><a href='#'>perro</a></nav>"
    "</main></body></html>"
).encode()


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=900, **kw)


def build_packs():
    os.makedirs(WORK, exist_ok=True)
    for lang in ("es", "de"):
        source = os.path.join(CORE, "packbuild", "fixtures", f"{lang}.jsonl")
        got = run(["cargo", "run", "-q", "-p", "packbuild", "--", lang, source,
                   os.path.join(WORK, f"{lang}.pack")], cwd=CORE)
        if got.returncode != 0:
            raise SystemExit(f"packbuild failed for {lang}: {got.stderr[-400:]}")


def serve():
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            if self.path.startswith("/packs/"):
                path = os.path.join(WORK, os.path.basename(self.path))
                if os.path.exists(path):
                    body = open(path, "rb").read()
                    self.send_response(200)
                    self.send_header("Content-Type", "application/octet-stream")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(PAGE)))
            self.end_headers()
            self.wfile.write(PAGE)

    # Threaded, because the page and the pack are fetched at the same time: the browser holds
    # the page's connection open while the service worker asks for the pack, and a
    # single-threaded server answers neither until the other lets go.
    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    httpd.timeout = 0.5
    threading.Thread(target=httpd.serve_forever, daemon=True).start()


def evaluate(cdp, session, expression):
    got = cdp.send("Runtime.evaluate", {
        "expression": expression, "awaitPromise": True, "returnByValue": True,
    }, session=session)
    return got.get("result", {}).get("value")


def wait_for(cdp, session, expression, want, tries=20):
    value = None
    for _ in range(tries):
        value = evaluate(cdp, session, expression)
        if want(value):
            return value
        time.sleep(1)
    return value


def main():
    build_packs()
    serve()
    base = f"http://127.0.0.1:{PORT}"
    failures = []

    cdp = PipeCDP()
    cdp.send("Target.setDiscoverTargets", {"discover": True})
    extid = cdp.ensure_extension()
    try:
        # The reader's choices, written the way the settings view writes them.
        book = cdp.send("Target.createTarget", {"url": f"chrome-extension://{extid}/viewbook.html"})
        settings = cdp.send(
            "Target.attachToTarget", {"targetId": book["targetId"], "flatten": True},
        )["sessionId"]
        cdp.send("Runtime.enable", session=settings)
        time.sleep(2)
        evaluate(cdp, settings, (
            f"chrome.storage.local.set({{packBaseUrl:'{base}',targetLanguage:'de',"
            "on:true,layer:'gloss+ipa',density:1})"
        ))
        target = cdp.send("Target.createTarget", {"url": f"{base}/page.html"})
        page = cdp.send(
            "Target.attachToTarget", {"targetId": target["targetId"], "flatten": True},
        )["sessionId"]
        cdp.send("Runtime.enable", session=page)
        cdp.send("Page.enable", session=page)

        # What was drawn, and over which words.
        drawn = wait_for(cdp, page, """
            (() => {
              const words = [...document.querySelectorAll('.px-w')];
              return JSON.stringify({
                count: words.length,
                glosses: words.map(w => (w.querySelector('.px-gl') || {}).textContent || ''),
                spellings: words.map(w => w.lastChild ? w.lastChild.textContent : ''),
                inCode: document.querySelector('#code .px-w') !== null,
                inNav: document.querySelector('nav .px-w') !== null,
                words: [...document.getElementById('prose').childNodes].map(
                  n => n.nodeType === 3 ? n.textContent
                     : [...n.childNodes].filter(c => c.nodeType === 3)
                                        .map(c => c.textContent).join('')
                ).join(''),
              });
            })()
        """, lambda v: v and json.loads(v)["count"] > 0)
        if not drawn:
            print("FAIL - nothing was drawn on the page")
            sys.exit(1)
        painted = json.loads(drawn)
        print(f"  {painted['count']} words annotated of "
              f"{len(SENTENCE.rstrip('.').split())} in the sentence")

        if painted["count"] < 5:
            failures.append(f"only {painted['count']} words were annotated")
        # The annotation over a word has to be about that word.
        pairs = dict(zip(painted["spellings"], painted["glosses"]))
        if pairs.get("perro") != "Hund":
            failures.append(f"perro carries {pairs.get('perro')!r}, not its answer")
        if pairs.get("camino") != "Weg":
            failures.append(f"camino carries {pairs.get('camino')!r}, not its answer")
        # A word the packs cannot answer is left plain rather than given an empty annotation.
        if pairs.get("parque"):
            failures.append(f"parque was given {pairs['parque']!r} from nowhere")
        if painted["inCode"]:
            failures.append("code was annotated")
        if painted["inNav"]:
            failures.append("the navigation was annotated")
        # The words themselves are untouched: the annotation is a box over the word, so the
        # text a reader reads has to still be the sentence with nothing inserted into it.
        if painted["words"] != SENTENCE:
            failures.append(f"the words now read {painted['words']!r}")

        # The card, opened at the word the cursor rests on.
        spot = json.loads(evaluate(cdp, page, """
            (() => {
              const word = [...document.querySelectorAll('.px-w')]
                .find(w => w.textContent.includes('perro'));
              const r = word.getBoundingClientRect();
              return JSON.stringify({x: r.left + r.width / 2, y: r.top + r.height / 2});
            })()
        """))
        for step in (0, 1):
            cdp.send("Input.dispatchMouseEvent", {
                "type": "mouseMoved", "x": spot["x"] + step, "y": spot["y"] + step,
            }, session=page)
            time.sleep(0.3)
        card = wait_for(cdp, page, """
            (() => {
              const host = document.getElementById('phonetix-card-host');
              const card = host && host.shadowRoot && host.shadowRoot.querySelector('.card');
              if (!card) return null;
              const box = card.getBoundingClientRect();
              return JSON.stringify({
                headline: (card.querySelector('.tr') || {}).textContent || '',
                symbols: [...card.querySelectorAll('.sym')].map(s => s.textContent).join(''),
                top: Math.round(box.top), left: Math.round(box.left),
                width: Math.round(box.width), height: Math.round(box.height),
              });
            })()
        """, lambda v: v is not None)
        if not card:
            failures.append("no card opened on the word the cursor rested on")
        else:
            open_card = json.loads(card)
            print(f"  card {open_card['width']}x{open_card['height']} at "
                  f"{open_card['left']},{open_card['top']}: {open_card['headline']!r} "
                  f"/{open_card['symbols']}/")
            if open_card["headline"] != "Hund":
                failures.append(f"the card says {open_card['headline']!r}")
            if open_card["width"] < 200:
                failures.append(f"the card measured {open_card['width']} wide")
            # A card off the screen is a card nobody can read.
            if open_card["left"] < 0 or open_card["top"] < 0:
                failures.append(f"the card is off screen at {open_card['left']},{open_card['top']}")

        # A word no pack holds still gets a transcription, from the voice rather than from a
        # dictionary, and the annotation says which by its own state.
        spoken = evaluate(cdp, page, """
            (() => {
              const words = [...document.querySelectorAll('.px-w')];
              const found = words.find(w => w.textContent.includes('parque'));
              return found ? ((found.querySelector('.px-ph') || {}).textContent || '') : 'no word';
            })()
        """)
        print(f"  parque is said {spoken!r}")
        if not spoken or spoken == "no word":
            failures.append(f"a word no pack holds got no transcription ({spoken!r})")

        # The play button on the card makes bytes: what a reader hears is synthesised where
        # the engine is and played where there is a page. Asked from an extension page,
        # because a page's own world has no way to reach the host and should not have one.
        heard = evaluate(cdp, settings, """
            (async () => {
              try {
                const bytes = await chrome.runtime.sendMessage(
                  {phonetix: 'speak', data: {word: 'perro', lang: 'es'}});
                return JSON.stringify({length: (bytes.ok || []).length});
              } catch (e) { return JSON.stringify({failed: String(e)}); }
            })()
        """)
        said = json.loads(heard) if heard else {"failed": "no answer"}
        print(f"  the voice made {said.get('length', 0)} bytes for perro")
        if not said.get("length"):
            failures.append(f"the voice said nothing ({said})")

        # And switched off, the page is the page again.
        evaluate(cdp, settings, "chrome.storage.local.set({on:false})")
        after = wait_for(cdp, page, "document.querySelectorAll('.px-w').length",
                         lambda v: v == 0)
        if after != 0:
            failures.append(f"{after} annotations survived switching it off")
        restored = evaluate(cdp, page, "document.getElementById('prose').textContent")
        if restored != SENTENCE:
            failures.append(f"the sentence came back as {restored!r}")
        void = evaluate(cdp, page, "document.getElementById('prose').childNodes.length")
        if void != 1:
            failures.append(f"the paragraph came back as {void} nodes rather than its text")
        cdp.send("Target.closeTarget", {"targetId": target["targetId"]})
        cdp.send("Target.closeTarget", {"targetId": book["targetId"]})
    finally:
        cdp.close()

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the page is annotated, the card opens on the word, and it all comes back")


if __name__ == "__main__":
    main()
