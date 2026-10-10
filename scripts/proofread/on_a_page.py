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
from harness import PipeCDP, OFFLINE

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
CORE = os.path.join(ROOT, "core")
WORK = os.environ.get("PHONETIX_WORK", "/tmp/phonetix-on-a-page")
PORT = int(os.environ.get("PHONETIX_PAGE_PORT", "8924"))

SENTENCE = "El perro corre por el camino y descansa en el banco de la calle."
# The same sentence with nothing declaring what it is in: the extension has to work that out
# rather than assume, which is what the detector in the core is for.
UNDECLARED = (
    "<!doctype html><html><meta charset='utf-8'>"
    "<body><main><p id='prose'>" + SENTENCE + "</p></main></body></html>"
).encode()

# A page mostly in one language with a line in another, which is the ordinary shape of a
# video title on a foreign page.
MIXED = (
    "<!doctype html><html lang='es'><meta charset='utf-8'><body><main>"
    "<p id='prose'>" + SENTENCE + "</p>"
    "<p id='other'>The dictionary answers immediately and the page carries on reading</p>"
    "</main></body></html>"
).encode()

# A German word its dictionary transcribes twice, broad and narrow: /ˈtɛpɪç/ and the
# aspirated [ˈtʰɛ.pʰɪç], which is what a reader who asked for narrow transcriptions is shown.
GERMAN = (
    "<!doctype html><html lang='de'><meta charset='utf-8'><body><main>"
    "<p id='prose'>Der Teppich und der Hund.</p>"
    "</main></body></html>"
).encode()

# A chat: the page scrolls a box of its own rather than the window, and keeps writing lines
# above the one being read. Neither moves the word with an event the window hears.
CHAT = (
    "<!doctype html><html lang='es'><meta charset='utf-8'><body style='margin:40px'>"
    "<div id='box' style='height:300px;overflow:auto;border:1px solid #ccc'>"
    "<div id='above'></div>"
    "<p id='prose' style='margin:120px 0 0'>" + SENTENCE + "</p>"
    "<div style='height:900px'></div></div></body></html>"
).encode()

# A chat streaming an answer: a few words every 50 ms into its last line, and a new line every
# two seconds, for as long as the page is open. The page is never still, and the lines it has
# finished are drawn all the same while it writes the next.
STREAM = (
    "<!doctype html><html lang='es'><meta charset='utf-8'><body style='margin:40px'>"
    "<div id='log'></div><script>"
    "const words = " + json.dumps(SENTENCE.split()) + ";"
    "let n = 0; let line = null;"
    "setInterval(() => {"
    "  if (n % 40 === 0) { line = document.createElement('p'); document.getElementById('log').appendChild(line); }"
    "  line.textContent += (line.textContent ? ' ' : '') + words[n++ % words.length];"
    "}, 50);"
    "</script></body></html>"
).encode()

# A heading, because a page's headings are where its capitals are, and a heading is written
# in title case whatever the language does. The words in it are ordinary dictionary words
# wearing a capital they got from the page.
HEADING = "Perro y Camino"

PAGE = (
    "<!doctype html><html lang='es'><meta charset='utf-8'>"
    "<body><main><h1 id='head'>" + HEADING + "</h1>"
    "<p id='prose'>" + SENTENCE + "</p>"
    "<pre id='code'>const perro = 1;</pre>"
    "<nav><a href='#'>perro</a></nav>"
    # A version and a year, which are not words: said digit by digit they are said the way
    # nobody says them.
    "<p id='version'>Phonetix v31.55 de justinking3062 en 2013, MXXX.sqlite y useState</p>"
    "</main>"
    # Somewhere to scroll to, and nothing in it: a card is anchored to a word, and whether it
    # goes with that word when the page moves cannot be asked of a page that cannot move.
    "<div id='room' style='height: 1200px'></div>"
    "</body></html>"
).encode()


# A paragraph several lines deep, narrow enough that a card opened on one of its words covers
# the words of the lines beside it. Once near the top of the window, where a card opens under
# its word, and once pinned to the bottom, where there is no room under a word and the card
# opens over it.
LINES = (
    "El perro descansa tranquilamente en el camino y camina despacio por la calle. La calle "
    "principal es larga y el perro duerme tranquilamente en el banco del camino. Por la tarde "
    "el perro camina otra vez despacio por la calle principal y descansa en el camino."
)


def lines_page(low):
    where = "position:fixed;left:48px;bottom:12px;margin:0;" if low else "margin:120px 0 0 48px;"
    return (
        "<!doctype html><html lang='es'><meta charset='utf-8'><body style='margin:0'>"
        f"<p id='lines' style='{where}width:320px;font-size:17px;line-height:1.6'>{LINES}</p>"
        "</body></html>"
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


def manifest():
    """The list of packs, exactly as packbuild's own rows describe them."""
    rows = []
    for lang in ("es", "de"):
        path = os.path.join(WORK, f"{lang}.pack")
        rows.append({
            "id": f"lex-{lang}", "lang": lang, "built": 0, "entries": 12, "keys": 14,
            "glosses": 12, "bytes": os.path.getsize(path) if os.path.exists(path) else 0,
            "sha256": "",
        })
    return json.dumps(rows).encode()


# Packs the host is refusing for now, so a check can have a page open before its dictionary is
# here: every pack is fetched by itself the moment a page needs it.
REFUSED: set = set()


def serve():
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            if self.path == "/packs.json":
                body = manifest()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if self.path.endswith(".pack"):
                path = os.path.join(WORK, os.path.basename(self.path))
                if os.path.basename(self.path)[:-len(".pack")] in REFUSED:
                    self.send_error(503)
                    return
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
            body = (
                lines_page(low=True) if self.path.startswith("/lines-low")
                else lines_page(low=False) if self.path.startswith("/lines")
                else UNDECLARED if self.path.startswith("/undeclared")
                else MIXED if self.path.startswith("/mixed")
                else GERMAN if self.path.startswith("/german")
                else CHAT if self.path.startswith("/chat")
                else STREAM if self.path.startswith("/stream")
                else PAGE
            )
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

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


# Where every word of the paragraph is: the painted ones by their box, the page's own text by
# the range each word covers, which is what a card on that text is anchored to.
WORDS_JS = """
(() => {
  const p = document.getElementById('lines');
  const painted = %s;
  const out = [];
  if (painted) {
    for (const w of p.querySelectorAll('.px-w')) {
      const r = w.getBoundingClientRect();
      const text = ((w.querySelector('.px-was') || {}).textContent || '').trim();
      out.push({text, left: r.left, right: r.right, top: r.top, bottom: r.bottom});
    }
  } else {
    const walk = document.createTreeWalker(p, NodeFilter.SHOW_TEXT);
    for (let n = walk.nextNode(); n; n = walk.nextNode()) {
      if (n.parentElement.closest('.px-w')) continue;
      const parts = new Intl.Segmenter('es', {granularity: 'word'}).segment(n.nodeValue);
      for (const s of parts) {
        if (!s.isWordLike) continue;
        const range = document.createRange();
        range.setStart(n, s.index);
        range.setEnd(n, s.index + s.segment.length);
        const rects = range.getClientRects();
        if (rects.length !== 1) continue;
        const r = rects[0];
        out.push({text: s.segment, left: r.left, right: r.right, top: r.top, bottom: r.bottom});
      }
    }
  }
  return {
    painted: p.querySelectorAll('.px-w').length,
    left: p.getBoundingClientRect().left,
    words: out.map(w => ({...w, x: (w.left + w.right) / 2, y: (w.top + w.bottom) / 2})),
  };
})()
"""

# The card as a reader meets it: which word it is about, which side of it, where its arrow is,
# and whether it takes the pointer yet.
CARD_JS = """
(() => {
  const host = document.getElementById('phonetix-card-host');
  const card = host && host.shadowRoot && host.shadowRoot.querySelector('.card');
  if (!card) return {open: false};
  const box = (e) => {
    const r = e.getBoundingClientRect();
    return {left: r.left, right: r.right, top: r.top, bottom: r.bottom,
            x: (r.left + r.right) / 2, y: (r.top + r.bottom) / 2,
            width: r.width, height: r.height};
  };
  const arrow = card.querySelector('.card-arrow');
  const sym = card.querySelector('.sym');
  return {
    open: true,
    word: (card.querySelector('.card-top .word') || {}).textContent || '',
    way: card.classList.contains('above') ? 'above' : 'below',
    card: box(card),
    arrow: arrow ? box(arrow) : null,
    sym: sym ? box(sym) : null,
    takes: getComputedStyle(card.parentElement).pointerEvents,
    detail: !!card.querySelector('.detail'),
  };
})()
"""

# What the page itself finds at a point: the card, a painted word, or the page's own element.
HIT_JS = """
(() => {
  const at = document.elementFromPoint(%f, %f);
  if (!at) return null;
  if (at.id === 'phonetix-card-host') return 'card';
  return at.closest('.px-w') ? 'word' : at.tagName.toLowerCase();
})()
"""


class ChromeHand:
    """A mouse and a question, over CDP, for the checks both engines share."""

    def __init__(self, cdp, session):
        self.cdp, self.session = cdp, session

    def ask(self, expression):
        got = evaluate(self.cdp, self.session,
                       f"(async () => JSON.stringify(await ({expression})))()")
        return json.loads(got) if got else None

    def move(self, x, y):
        self.cdp.send("Input.dispatchMouseEvent", {
            "type": "mouseMoved", "x": round(x), "y": round(y),
        }, session=self.session)

    def click(self, x, y):
        for kind in ("mousePressed", "mouseReleased"):
            self.cdp.send("Input.dispatchMouseEvent", {
                "type": kind, "x": round(x), "y": round(y), "button": "left", "clickCount": 1,
            }, session=self.session)


# Whether a card is open on the page.
CARD_OPEN_JS = """!!(document.getElementById('phonetix-card-host') || {}).shadowRoot?.querySelector('.card')"""

EMBER_JS = """
  (() => {
    const host = document.getElementById('phonetix-card-host-ember');
    if (!host) return null;
    const root = host.shadowRoot;
    const ball = root.querySelector('.ember'), light = root.querySelector('.ember-word');
    return {ball: ball.className, word: light.className,
            lit: light.classList.contains('on') ? light.getBoundingClientRect().toJSON() : null};
  })()
"""


def ember_checks(hand, engine, failures, off):
    """The ember goes with the pointer while Phonetix is on: just under it over nothing, into
    the word it is on, which lights, and nowhere at all once Phonetix is off. [off] switches it
    off and on again. Asked of a page with a paragraph #prose, open in the hand's tab."""
    where = hand.ask("""
        (() => {
          const prose = document.getElementById('prose').getBoundingClientRect();
          const word = [...document.querySelectorAll('#prose .px-w')][0];
          const box = word ? word.getBoundingClientRect() : null;
          // Over nothing: the window's far corner, away from any card still open.
          return {empty: {x: innerWidth - 40, y: innerHeight - 40},
                  word: box ? {x: box.left + box.width / 2, y: box.top + box.height / 2,
                               box: box.toJSON()} : null};
        })()
    """)
    hand.move(where["empty"]["x"] - 6, where["empty"]["y"])
    hand.move(where["empty"]["x"], where["empty"]["y"])
    time.sleep(0.4)
    empty = hand.ask(EMBER_JS)
    print(f"  {engine}, the ember over nothing: {empty}")
    if not empty or "on" not in empty["ball"].split() or "in" in empty["ball"].split():
        failures.append(f"{engine}: no ember under the pointer over nothing: {empty}")
    elif empty["lit"]:
        failures.append(f"{engine}: a word was lit with the pointer on none: {empty}")
    if where["word"]:
        hand.move(where["word"]["x"] - 3, where["word"]["y"])
        hand.move(where["word"]["x"], where["word"]["y"])
        time.sleep(0.5)
        on_word = hand.ask(EMBER_JS)
        print(f"  {engine}, the ember on a word: {on_word}")
        lit, box = (on_word or {}).get("lit"), where["word"]["box"]
        if not lit or not (lit["left"] <= box["left"] and lit["right"] >= box["right"]):
            failures.append(f"{engine}: the word under the pointer was not lit: {on_word}")
        if on_word and "in" not in on_word["ball"].split():
            failures.append(f"{engine}: the ember did not go into the word: {on_word}")
        # A pointer aimed at a word lands in the gap under it or beside it as often as on it,
        # and the tip sits under the word it means: a few pixels below, it is still the word.
        hand.move(where["word"]["x"], box["bottom"] + 5)
        hand.move(where["word"]["x"] + 1, box["bottom"] + 6)
        time.sleep(0.5)
        under_it = hand.ask(EMBER_JS)
        print(f"  {engine}, the ember just under a word: {under_it}")
        if not (under_it or {}).get("lit"):
            failures.append(f"{engine}: a pointer just under a word did not reach it: {under_it}")
        # Beside it, a card opens for it; and leaving for nothing takes the card down at once,
        # rather than leaving it over the line the reader went on to.
        hand.move(box["right"] + 4, where["word"]["y"])
        hand.move(box["right"] + 5, where["word"]["y"])
        opened = False
        for _ in range(20):
            time.sleep(0.25)
            opened = hand.ask(CARD_OPEN_JS)
            if opened:
                break
        print(f"  {engine}, a card for the word the pointer is just beside: {opened}")
        if not opened:
            failures.append(f"{engine}: no card for a word the pointer was just beside")
        else:
            hand.move(where["empty"]["x"], where["empty"]["y"])
            time.sleep(0.05)
            still = hand.ask(CARD_OPEN_JS)
            print(f"  {engine}, the card 50 ms after the pointer left: {'open' if still else 'gone'}")
            if still:
                failures.append(f"{engine}: the card stayed after the pointer left its word")
    off(True)
    hand.move(where["empty"]["x"] + 4, where["empty"]["y"])
    time.sleep(0.6)
    gone = hand.ask(EMBER_JS)
    print(f"  {engine}, the ember with Phonetix off: {gone}")
    if gone and "on" in gone["ball"].split():
        failures.append(f"{engine}: the ember stayed with Phonetix off: {gone}")
    off(False)


def stream_draws(hand, engine, failures):
    """Lines a streaming page has finished are drawn while it writes the next: every one but
    the newest, which may have ended since the last draw (at most a second and a little ago).
    Asked of the stream page, already open in the hand's tab."""
    time.sleep(5)
    drawn = hand.ask("""
        (() => {
          const lines = [...document.querySelectorAll('#log p')].slice(0, -1);
          return {lines: lines.length,
                  drawn: lines.filter(p => p.querySelector('.px-w')).length};
        })()
    """)
    print(f"  {engine}: streaming page, finished lines drawn: {drawn}")
    if not drawn or drawn["lines"] < 2 or drawn["drawn"] < drawn["lines"] - 1:
        failures.append(f"{engine}: a streaming page had finished lines left undrawn "
                        f"while it went on writing ({drawn})")


def chat_follows(hand, engine, failures):
    """The card on a word in a chat goes with the word as the chat moves it.

    Lines written above the word, then the page's own box scrolled, move the word with nothing
    the window hears; the box scrolled past the word takes the card down rather than leaving it
    pointing at whatever came there. Asked of the chat page, already open in the hand's tab."""
    for _ in range(20):
        if hand.ask("document.querySelectorAll('.px-w').length"):
            break
        time.sleep(1)
    spot = hand.ask("""
        (() => {
          const word = [...document.querySelectorAll('.px-w')]
            .find(w => w.textContent.includes('perro'));
          if (!word) return null;
          const r = word.getBoundingClientRect();
          return {x: r.left + r.width / 2, y: r.top + r.height / 2};
        })()
    """)
    if not spot:
        failures.append(f"{engine}: the chat's words were never drawn")
        return
    for step in (0, 1):
        hand.move(spot["x"] + step, spot["y"] + step)
        time.sleep(0.3)
    opened = False
    for _ in range(20):
        opened = hand.ask("""!!(document.getElementById('phonetix-card-host')
                                || {}).shadowRoot?.querySelector('.card')""")
        if opened:
            break
        time.sleep(0.5)
    if not opened:
        failures.append(f"{engine}: no card opened on a word in a box of the page's own")
        return
    followed = hand.ask("""
        (async () => {
          const host = document.getElementById('phonetix-card-host');
          const card = () => host.shadowRoot.querySelector('.card');
          const word = () => [...document.querySelectorAll('.px-w')]
            .find(w => w.textContent.includes('perro'));
          const at = () => ({card: card() ? Math.round(card().getBoundingClientRect().top) : null,
                             word: Math.round(word().getBoundingClientRect().top)});
          const wait = (ms) => new Promise(r => setTimeout(r, ms));
          const start = at();
          const line = document.createElement('p');
          line.textContent = 'Ran 1 command';
          line.style.height = '60px';
          document.getElementById('above').appendChild(line);
          await wait(400);
          const written = at();
          document.getElementById('box').scrollTop += 30;
          await wait(400);
          const scrolled = at();
          document.getElementById('box').scrollTop += 400;
          await wait(400);
          return {start, written, scrolled, gone: !card()};
        })()
    """) or {}
    print(f"  {engine}, in a chat: {followed}")
    for name, was, now in (("lines were written above it", "start", "written"),
                           ("its box scrolled", "written", "scrolled")):
        word_moved = followed[now]["word"] - followed[was]["word"]
        card = followed[now]["card"]
        if card is None:
            failures.append(f"{engine}: the card closed when {name}")
        elif not word_moved:
            failures.append(f"{engine}: the word did not move when {name}, so nothing was learned")
        elif abs((card - followed[was]["card"]) - word_moved) > 4:
            failures.append(f"{engine}: the card did not follow its word when {name}: the word "
                            f"moved {word_moved}px, the card {card - followed[was]['card']}px")
    if not followed.get("gone"):
        failures.append(f"{engine}: the card stayed open after its word was scrolled out of its box")


def walk(hand, start, end, step=3, pause=0.012, each=None):
    """Move the pointer along a straight line in small steps, the way a hand moves a mouse.

    `each` is told every point on the way and may stop the walk by returning True.
    """
    dx, dy = end[0] - start[0], end[1] - start[1]
    count = max(1, int(max(abs(dx), abs(dy)) / step))
    for i in range(1, count + 1):
        point = (start[0] + dx * i / count, start[1] + dy * i / count)
        hand.move(*point)
        time.sleep(pause)
        if each and each(point):
            return point
    return end


def same_word(said, written):
    return said.strip().lower() == written.strip().lower()


def arrow_checks(hand, name, painted, low, failures):
    """The card is entered through its arrow and nowhere else.

    A reader moving from a word to the line next to it crosses the card that word opened. A card
    that took the pointer anywhere along its edge caught that reader and kept the next line out
    of reach, so the card keeps a gap from its word, the arrow spans the gap, and only the arrow
    lets the pointer in. Asked of a word near the top of the window, whose card opens under it,
    and of one near the bottom, whose card opens over it; of a painted word and of the page's
    own text.
    """
    def fail(message):
        failures.append(f"{name}: {message}")

    def card():
        return hand.ask(CARD_JS) or {"open": False}

    def away():
        hand.click(2, 2)
        hand.move(2, 2)
        time.sleep(0.8)

    def open_on(word):
        away()
        for step in (0, 1):
            hand.move(word["x"] + step, word["y"])
            time.sleep(0.05)
        for _ in range(24):
            seen = card()
            if seen["open"] and same_word(seen["word"], word["text"]):
                return seen
            time.sleep(0.25)
        return None

    found = hand.ask(WORDS_JS % ("true" if painted else "false"))
    words = found["words"]
    # Lines, top to bottom, by where their words start.
    tops = []
    for word in sorted(words, key=lambda w: w["top"]):
        if not tops or word["top"] - tops[-1] > 4:
            tops.append(word["top"])
    lines = [[w for w in words if abs(w["top"] - top) <= 4] for top in tops]
    print(f"  {name}: {len(words)} words on {len(lines)} lines, {found['painted']} painted")
    if len(lines) < 4:
        fail(f"the paragraph is {len(lines)} lines deep, too few to cross")
        return
    # The word the card opens on: a second line from the top or from the bottom, so there is a
    # line on either side of it, and wide enough that a pointer can leave it beside the arrow.
    at = len(lines) - 2 if low else 1
    wanted = "above" if low else "below"
    toward = -1 if low else 1
    candidates = sorted(lines[at], key=lambda w: -(w["right"] - w["left"]))
    word = next((w for w in candidates if w["right"] - w["left"] >= 52), None)
    if not word:
        fail("no word on the line is wide enough to leave beside the arrow")
        return

    shown = open_on(word)
    if not shown:
        fail(f"no card opened on {word['text']!r}")
        return
    arrow, body = shown["arrow"], shown["card"]
    if shown["way"] != wanted or not arrow:
        fail(f"the card opened {shown['way']} its word, wanted {wanted}, arrow {arrow}")
        return
    # The arrow points at the word, reaches it, and is the whole of the gap.
    tip = arrow["top"] if not low else arrow["bottom"]
    edge = word["bottom"] if not low else word["top"]
    gap = (body["top"] - word["bottom"]) if not low else (word["top"] - body["bottom"])
    print(f"  {name}: card {wanted} {word['text']!r}, arrow {arrow['width']:.0f}x"
          f"{arrow['height']:.0f} at {arrow['x'] - word['x']:+.1f}px from the word's middle, "
          f"tip {tip - edge:+.1f}px from the word, gap {gap:.1f}px, takes {shown['takes']}")
    if abs(arrow["x"] - word["x"]) > 2:
        fail(f"the arrow is {arrow['x'] - word['x']:+.1f}px off the word's middle")
    if abs(tip - edge) > 1.5:
        fail(f"the arrow's point is {tip - edge:+.1f}px from the word")
    if not 26 <= arrow["width"] <= 36:
        fail(f"the arrow is {arrow['width']:.0f}px wide")
    if gap < 8:
        fail(f"the gap between the word and the card is {gap:.1f}px")
    if shown["takes"] != "none":
        fail(f"a card nobody has entered takes the pointer ({shown['takes']})")

    # Under the card the page is still the page: a word the card covers is what the pointer
    # finds there, not the card.
    side = [w for i, line in enumerate(lines) for w in line
            if (i - at) * toward > 0
            and body["left"] + 4 < w["x"] < body["right"] - 4
            and body["top"] + 4 < w["y"] < body["bottom"] - 4
            and abs(w["x"] - arrow["x"]) > arrow["width"] / 2 + 6]
    if not side:
        fail("no word lies under the card to check")
        return
    covered = min(side, key=lambda w: abs(w["y"] - word["y"]))
    hit = hand.ask(HIT_JS % (covered["x"], covered["y"]))
    print(f"  {name}: under the card at {covered['text']!r} the page finds {hit!r}")
    if hit == "card":
        fail(f"the card catches the pointer over {covered['text']!r}, which it covers")

    # 1. Through the arrow: the card stays, takes the pointer, and a symbol on it is pressed.
    inside_card = body["top"] + 24 if not low else body["bottom"] - 24
    walk(hand, (word["x"], word["y"]), (word["x"], inside_card))
    time.sleep(0.6)
    after = card()
    print(f"  {name}: in through the arrow: open {after['open']}, "
          f"about {after.get('word')!r}, takes {after.get('takes')}")
    if not after["open"] or not same_word(after["word"], word["text"]):
        fail("the card closed when the pointer came in through its arrow")
    else:
        if after["takes"] != "auto":
            fail(f"the card entered through its arrow does not take the pointer ({after['takes']})")
        if after["sym"]:
            # Where the symbol is once the card has stopped filling in: an answer the engine
            # guesses arrives a second or more after the card opens and adds its headline, which
            # moved the symbol out from under a click aimed at where it had been.
            still = 0
            for _ in range(30):
                time.sleep(0.3)
                settled = card()
                if not settled["open"]:
                    break
                still = still + 1 if settled.get("sym") == after["sym"] else 0
                after = settled
                if still >= 6:
                    break
            sym = after["sym"]
            walk(hand, (word["x"], inside_card), (sym["x"], sym["y"]))
            time.sleep(0.3)
            hand.click(sym["x"], sym["y"])
            time.sleep(0.6)
            pressed = card()
            print(f"  {name}: a symbol pressed in the card: open {pressed['open']}, "
                  f"described {pressed.get('detail')}")
            if not pressed["open"] or not pressed.get("detail"):
                fail("a symbol on the card could not be pressed")
        else:
            fail("the card has no symbol to press")

    # 2. Beside the arrow: the card is gone by the time the pointer is on it, and the word the
    # pointer goes on to opens a card of its own.
    shown = open_on(word)
    if not shown:
        fail(f"no card opened on {word['text']!r} a second time")
        return
    arrow, body = shown["arrow"], shown["card"]
    beside = arrow["right"] + 6
    if beside > word["right"] - 2:
        beside = arrow["left"] - 6
    if beside < word["left"] + 2:
        fail(f"{word['text']!r} is too narrow to leave beside the arrow")
        return
    walk(hand, (word["x"], word["y"]), (beside, word["y"]))
    closed = {"depth": None}

    def watch(point):
        depth = (point[1] - body["top"]) if not low else (body["bottom"] - point[1])
        if depth >= 4:
            closed["depth"] = depth if not card()["open"] else None
            return True
        return False

    deep = body["top"] + 30 if not low else body["bottom"] - 30
    stop = walk(hand, (beside, word["y"]), (beside, deep), each=watch)
    print(f"  {name}: out beside the arrow: closed "
          f"{'at ' + format(closed['depth'], '.0f') + 'px into the card' if closed['depth'] else 'NOT'}")
    if closed["depth"] is None:
        fail("the card stayed when the pointer came onto it beside the arrow")
    walk(hand, stop, (covered["x"], covered["y"]))
    reached = None
    for _ in range(16):
        reached = card()
        if reached["open"] and same_word(reached["word"], covered["text"]):
            break
        time.sleep(0.25)
    print(f"  {name}: the word it covered, {covered['text']!r}, opened "
          f"{reached.get('word')!r}")
    if not reached["open"] or not same_word(reached["word"], covered["text"]):
        fail(f"{covered['text']!r}, which the card covered, did not get a card of its own")

    # 3. The other way, onto the line on the far side of the word: that word's card, and the
    # first one gone.
    if not open_on(word):
        fail(f"no card opened on {word['text']!r} a third time")
        return
    other = min(lines[at - toward], key=lambda w: abs(w["x"] - word["x"]))
    walk(hand, (word["x"], word["y"]), (other["x"], other["y"]))
    reached = None
    for _ in range(16):
        reached = card()
        if reached["open"] and same_word(reached["word"], other["text"]):
            break
        time.sleep(0.25)
    print(f"  {name}: the other way, onto {other['text']!r}: card about {reached.get('word')!r}")
    if not reached["open"] or not same_word(reached["word"], other["text"]):
        fail(f"moving away from the card onto {other['text']!r} did not open its card")
    # And onto open page, where nothing is: the card goes.
    walk(hand, (other["x"], other["y"]), (found["left"] - 30, other["y"]))
    time.sleep(0.6)
    if card()["open"]:
        fail("the card stayed with the pointer out on the open page")
    away()


def main():
    build_packs()
    serve()
    base = f"http://127.0.0.1:{PORT}"
    failures = []

    # Only this check's host serves dictionaries: a real download arriving mid-check redrew the
    # page while it was meant to be settled and put a dictionary on a card meant to have none.
    cdp = PipeCDP(extra_args=[OFFLINE])
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
            "on:true,layer:'sound',density:1})"
        ))
        # The dictionaries, asked for the way the settings view asks: nothing is fetched
        # because a page happened to be in a language.
        for lang in ("es", "de"):
            evaluate(cdp, settings, (
                "chrome.runtime.sendMessage({phonetix:'getPack',data:{lang:'" + lang + "'}})"
                ".then(r => JSON.stringify(r))"
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
                // What replaces each word: how that word is said.
                glosses: words.map(w => (w.querySelector('.px-rep') || {}).textContent || ''),
                // The word the page wrote, which the box keeps beside the answer for the
                // reveal to show.
                spellings: words.map(
                  w => ((w.querySelector('.px-was') || w.lastChild) || {}).textContent || ''),
                inCode: document.querySelector('#code .px-w') !== null,
                inNav: document.querySelector('nav .px-w') !== null,
                // What a reader reads: the answers where there are answers, and the page's own
                // words where there are none.
                words: [...document.getElementById('prose').childNodes].map(
                  n => n.nodeType === 3
                    ? n.textContent
                    : ((n.querySelector && n.querySelector('.px-rep')) || n).textContent
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
        # What replaces a word has to be that word, said: "perro" is [pero], whatever language
        # the reader reads into, because what it means is the card's.
        pairs = dict(zip(painted["spellings"], painted["glosses"]))
        said = {"perro": "pero", "camino": "kamino"}
        for word, answer in said.items():
            if pairs.get(word) != answer:
                failures.append(f"{word} carries {pairs.get(word)!r}, not {answer!r}")
        # The heading. A page capitalises its headings whatever the language does, and while
        # the cascade compared spellings byte for byte every one of them went unanswered - on
        # a real page that is most of what a reader looks at first.
        for word, answer in (("Perro", "pero"), ("Camino", "kamino")):
            if pairs.get(word) != answer:
                failures.append(f"the heading's {word} carries {pairs.get(word)!r}, not {answer!r}")
        if painted["inCode"]:
            failures.append("code was annotated")
        if painted["inNav"]:
            failures.append("the navigation was annotated")
        # The answer takes the word's place, so what a reader reads is the sentence with the
        # words swapped and everything between them exactly as the page wrote it. The word
        # itself is still in the page, beside the answer, for the reveal to show.
        reading = painted["words"]
        print(f"  the sentence reads {reading!r}")
        for word, answer in said.items():
            if answer not in reading:
                failures.append(f"{word} was not replaced by {answer}: {reading!r}")
        if not reading.endswith(".") or reading.count(" ") != SENTENCE.count(" "):
            failures.append(f"what lies between the words was not kept: {reading!r}")

        # The page stops moving.
        #
        # Annotating a page changes the page, and the observer that watches for text arriving
        # after load was told about our own changes: it asked for another paint, which caused
        # more of them. A page redrew itself twice a second for as long as it was open, every
        # line jumping as the annotations came off and went back on - and every check here
        # passed throughout, because each of them asks the DOM a question once it has settled
        # and none of them asks whether it ever does.
        settled = evaluate(cdp, page, """
            new Promise(done => {
              let changes = 0;
              const watch = new MutationObserver(records => { changes += records.length; });
              watch.observe(document.body, {childList: true, subtree: true, characterData: true});
              const word = document.querySelector('.px-w');
              const was = word ? word.getBoundingClientRect().top : 0;
              setTimeout(() => {
                watch.disconnect();
                const now = word ? word.getBoundingClientRect().top : 0;
                done(JSON.stringify({changes, moved: Math.abs(now - was)}));
              }, 4000);
            })
        """)
        quiet = json.loads(settled)
        print(f"  in four settled seconds: {quiet['changes']} changes, "
              f"the first word moved {quiet['moved']:.0f}px")
        if quiet["changes"] > 0:
            failures.append(
                f"the page is still being redrawn after it settled: {quiet['changes']} changes")
        if quiet["moved"] > 1:
            failures.append(f"an annotated word moved {quiet['moved']:.0f}px on a still page")

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

        # And it says which word it is about: a card that opened between two words was a card
        # about either of them. The arrow is put where the word is rather than at the card's
        # middle, since a card pushed against the side of the window sits nowhere near it.
        arrow = json.loads(evaluate(cdp, page, """
            (() => {
              const host = document.getElementById('phonetix-card-host');
              const card = host.shadowRoot.querySelector('.card');
              const frame = card.parentElement;
              const word = [...document.querySelectorAll('.px-w')]
                .find(w => w.textContent.includes('perro'));
              const at = getComputedStyle(frame).getPropertyValue('--arrow-at');
              const box = card.getBoundingClientRect();
              const middle = word.getBoundingClientRect();
              return JSON.stringify({
                points: card.classList.contains('points'),
                way: card.classList.contains('below') ? 'below' : 'above',
                at: parseFloat(at),
                wanted: Math.round(middle.left + middle.width / 2 - box.left),
              });
            })()
        """) or "{}")
        print(f"  it points {arrow.get('way')} at {arrow.get('at')}px "
              f"(the word is at {arrow.get('wanted')}px)")
        if not arrow.get("points"):
            failures.append("the card does not say which word it is about")
        elif abs(arrow["at"] - arrow["wanted"]) > 12:
            failures.append(
                f"the arrow points at {arrow['at']}px, the word is at {arrow['wanted']}px")

        # A tap on a symbol says what that sound is, on a line the card grows for it. Nothing
        # describes a sound before one is asked about, and the symbol that was tapped stays
        # where it was, so the card does not move out from under the cursor.
        before = json.loads(evaluate(cdp, page, """
            (() => {
              const host = document.getElementById('phonetix-card-host');
              const sym = host.shadowRoot.querySelectorAll('.sym')[1];
              return JSON.stringify({
                described: Boolean(host.shadowRoot.querySelector('.detail')),
                top: sym ? Math.round(sym.getBoundingClientRect().top) : null,
              });
            })()
        """))
        if before["described"]:
            failures.append("the card describes a sound nobody asked about")
        symbol = evaluate(cdp, page, """
            (() => {
              const host = document.getElementById('phonetix-card-host');
              const sym = host.shadowRoot.querySelectorAll('.sym')[1];
              if (!sym) return null;
              sym.click();
              return sym.textContent;
            })()
        """)
        sheet = wait_for(cdp, page, """
            (() => {
              const host = document.getElementById('phonetix-card-host');
              const line = host.shadowRoot.querySelector('.detail');
              if (!line) return null;
              const card = host.shadowRoot.querySelector('.card').getBoundingClientRect();
              return JSON.stringify({
                symbol: (line.querySelector('.d-sym') || {}).textContent || '',
                name: (line.querySelector('.d-name') || {}).textContent || '',
                example: (line.querySelector('.d-eg') || {}).textContent || '',
                links: [...line.querySelectorAll('a, button')]
                  .map(b => b.getAttribute('aria-label') || '').filter(Boolean),
                top: Math.round(host.shadowRoot.querySelectorAll('.sym')[1].getBoundingClientRect().top),
              });
            })()
        """, lambda v: v is not None, tries=10)
        if not symbol or not sheet:
            failures.append(f"tapping a symbol said nothing ({symbol!r}, {sheet!r})")
        else:
            sound = json.loads(sheet)
            print(f"  the sound: {sound['symbol']!r} {sound['name']!r} ({sound['example']}), "
                  f"{sound['links']}")
            if sound["symbol"].strip() != (symbol or "").strip():
                failures.append(
                    f"the card describes {sound['symbol']!r}, not the {symbol!r} that was tapped")
            if not sound["name"]:
                failures.append("the sound is not named")
            if before["top"] is not None and abs(sound["top"] - before["top"]) > 1:
                failures.append(
                    f"the tapped symbol moved when its sound was read: {before['top']} -> {sound['top']}")

        # The card is entered through its arrow and nowhere else, over painted words and the
        # page's own text, under a word and over one. Each on a page of its own, so the page
        # the rest of this check reads is left as it was.
        for density, painted in ((1, True), (100000, False)):
            evaluate(cdp, settings, f"chrome.storage.local.set({{density:{density}}})")
            for low in (False, True):
                lined = cdp.send("Target.createTarget",
                                 {"url": f"{base}/{'lines-low' if low else 'lines'}.html"})
                at = cdp.send(
                    "Target.attachToTarget", {"targetId": lined["targetId"], "flatten": True},
                )["sessionId"]
                cdp.send("Runtime.enable", session=at)
                # Until the paragraph is drawn as the setting says, and then a moment for it to
                # stop moving.
                wait_for(cdp, at, """
                    document.querySelectorAll('#lines .px-w').length %s
                """ % ("> 20" if painted else "< 5"), lambda v: v, tries=25)
                time.sleep(1.5)
                arrow_checks(ChromeHand(cdp, at),
                             f"chrome, {'painted words' if painted else 'page text'}, "
                             f"card {'above' if low else 'below'}",
                             painted, low, failures)
                cdp.send("Target.closeTarget", {"targetId": lined["targetId"]})
        evaluate(cdp, settings, "chrome.storage.local.set({density:1})")
        time.sleep(1)

        # And it goes with its word when the page scrolls under it, rather than staying where
        # it was drawn and pointing at whatever has scrolled into that spot. In a window short
        # enough to have somewhere to scroll to, with the cursor put back on the word.
        # Tall enough that the card sits under its word rather than being pushed against the
        # top of the window, where a clamped card would sit still however far the page moved.
        cdp.send("Emulation.setDeviceMetricsOverride", {
            "width": 900, "height": 800, "deviceScaleFactor": 1, "mobile": False,
        }, session=page)
        time.sleep(0.5)
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
        wait_for(cdp, page, """
            (() => !!(document.getElementById('phonetix-card-host')
              || {}).shadowRoot?.querySelector('.card'))()
        """, lambda v: v)
        moved = json.loads(evaluate(cdp, page, """
            (async () => {
              const host = document.getElementById('phonetix-card-host');
              const card = () => host.shadowRoot.querySelector('.card');
              const before = card() ? card().getBoundingClientRect().top : null;
              const word = [...document.querySelectorAll('.px-w')]
                .find(w => w.textContent.includes('perro'));
              const wasAt = word.getBoundingClientRect().top;
              window.scrollBy(0, 40);
              // Long enough that a card which closes shortly after a scroll - the grace
              // period taking it down because the words moved out from under the cursor -
              // has done so by the time it is measured.
              await new Promise(r => setTimeout(r, 900));
              const now = card() ? card().getBoundingClientRect().top : null;
              return JSON.stringify({
                before, now, open: !!card(),
                wordMoved: Math.round(wasAt - word.getBoundingClientRect().top),
              });
            })()
        """) or "{}")
        print(f"  a scroll of {moved.get('wordMoved')}px: the card went "
              f"from {moved.get('before')} to {moved.get('now')}")
        if not moved.get("wordMoved"):
            failures.append("the page did not scroll, so nothing was learned about the card")
        elif not moved.get("open"):
            failures.append("the card closed when the page scrolled")
        elif abs((moved["before"] - moved["now"]) - moved["wordMoved"]) > 8:
            failures.append(
                f"the card did not follow its word: the word moved {moved['wordMoved']}px, "
                f"the card {round(moved['before'] - moved['now'])}px")
        cdp.send("Emulation.clearDeviceMetricsOverride", {}, session=page)
        time.sleep(0.4)

        # Each page picks its own words: the same text at three addresses has different words
        # replaced, and the same address loaded again has the same ones, so nothing a reader
        # is reading changes under them.
        evaluate(cdp, settings, "chrome.storage.local.set({density:3})")
        time.sleep(1)

        def replaced_at(path):
            target = cdp.send("Target.createTarget", {"url": f"{base}/{path}"})["targetId"]
            session = cdp.send("Target.attachToTarget",
                               {"targetId": target, "flatten": True})["sessionId"]
            cdp.send("Runtime.enable", session=session)
            wait_for(cdp, session, "document.querySelectorAll('#prose .px-w').length",
                     lambda v: v, tries=12)
            time.sleep(1)
            got = evaluate(cdp, session, """JSON.stringify([...document.querySelectorAll('#prose .px-w')]
                .map(w => (w.querySelector('.px-was') || {}).textContent))""")
            cdp.send("Target.closeTarget", {"targetId": target})
            return json.loads(got or "[]")

        chosen = {path: replaced_at(path) for path in ("one.html", "two.html", "three.html")}
        again = replaced_at("one.html")
        print(f"  the same text on three pages replaced {list(chosen.values())}, "
              f"the first again {again}")
        if len({tuple(words) for words in chosen.values()}) == 1:
            failures.append(f"three pages replaced the same words: {chosen['one.html']}")
        if again != chosen["one.html"]:
            failures.append(f"one page loaded twice replaced {chosen['one.html']} then {again}")
        evaluate(cdp, settings, "chrome.storage.local.set({density:1})")
        time.sleep(1)

        # And in a chat: lines written above the word, then the page's own box scrolled, move
        # the word with nothing the window hears, and the card goes with it all the same; the
        # box scrolled past the word takes the card down rather than leaving it pointing at
        # whatever came there.
        ember_checks(ChromeHand(cdp, page), "chrome", failures, lambda off: (
            evaluate(cdp, settings, f"chrome.storage.local.set({{on: {'false' if off else 'true'}}})"),
            time.sleep(1.5)))
        chat_target = cdp.send("Target.createTarget", {"url": f"{base}/chat.html"})["targetId"]
        chat = cdp.send("Target.attachToTarget",
                        {"targetId": chat_target, "flatten": True})["sessionId"]
        cdp.send("Runtime.enable", session=chat)
        cdp.send("Emulation.setDeviceMetricsOverride", {
            "width": 900, "height": 800, "deviceScaleFactor": 1, "mobile": False,
        }, session=chat)
        cdp.send("Target.activateTarget", {"targetId": chat_target})
        chat_follows(ChromeHand(cdp, chat), "chrome", failures)
        cdp.send("Target.closeTarget", {"targetId": chat_target})

        # And a chat that is never still, streaming an answer.
        stream_target = cdp.send("Target.createTarget", {"url": f"{base}/stream.html"})["targetId"]
        stream = cdp.send("Target.attachToTarget",
                          {"targetId": stream_target, "flatten": True})["sessionId"]
        cdp.send("Runtime.enable", session=stream)
        cdp.send("Target.activateTarget", {"targetId": stream_target})
        stream_draws(ChromeHand(cdp, stream), "chrome", failures)
        cdp.send("Target.closeTarget", {"targetId": stream_target})

        # A word no pack holds still gets a transcription, from the voice rather than from a
        # dictionary, and the annotation says which by its own state. Asked in the mode that
        # shows how a word is said, because that is the mode the answer belongs to: the others
        # show what a word means.
        evaluate(cdp, settings, "chrome.storage.local.set({layer:'sound'})")
        time.sleep(3)
        spoken = evaluate(cdp, page, """
            (() => {
              const words = [...document.querySelectorAll('.px-w')];
              const found = words.find(w => w.textContent.includes('calle'));
              return found ? ((found.querySelector('.px-ph') || {}).textContent || '') : 'no word';
            })()
        """)
        print(f"  calle is said {spoken!r}")
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

        # A page that says nothing about its language is read rather than assumed English:
        # its words are said the Spanish way.
        undeclared = cdp.send("Target.createTarget", {"url": f"{base}/undeclared.html"})
        other = cdp.send(
            "Target.attachToTarget", {"targetId": undeclared["targetId"], "flatten": True},
        )["sessionId"]
        cdp.send("Runtime.enable", session=other)
        found = wait_for(cdp, other, """
            (() => {
              const words = [...document.querySelectorAll('.px-w')];
              const sounds = words.map(w => (w.querySelector('.px-ph') || {}).textContent || '')
                                  .filter(Boolean);
              return sounds.length ? JSON.stringify(sounds) : null;
            })()
        """, lambda v: v is not None and "pero" in v, tries=25)
        print(f"  a page that declares nothing: {json.loads(found or '[]')[:4]}")
        if not found or "pero" not in found:
            failures.append(f"an undeclared Spanish page was not read as Spanish ({found})")
        cdp.send("Target.closeTarget", {"targetId": undeclared["targetId"]})

        # A line in another language is read as that language, rather than as the page's. Told
        # in sounds, because that is what differs between the two languages for these words.
        mixed = cdp.send("Target.createTarget", {"url": f"{base}/mixed.html"})
        other = cdp.send(
            "Target.attachToTarget", {"targetId": mixed["targetId"], "flatten": True},
        )["sessionId"]
        cdp.send("Runtime.enable", session=other)
        lines = wait_for(cdp, other, """
            (() => {
              const said = (id) => [...document.getElementById(id).querySelectorAll('.px-w')]
                .map(w => (w.querySelector('.px-ph') || {}).textContent || '')
                .filter(Boolean);
              const spanish = said('prose'), english = said('other');
              return spanish.length && english.length
                ? JSON.stringify({spanish, english}) : null;
            })()
        """, lambda v: v is not None, tries=25)
        read = json.loads(lines or '{"spanish": [], "english": []}')
        print(f"  the Spanish line: {read['spanish'][:3]}")
        print(f"  the English line: {read['english'][:3]}")
        # "the" is said one way in English and is not a Spanish word at all: a line read as
        # the page's language would come back with the page's sounds.
        if not read["english"]:
            failures.append("the English line was not transcribed at all")
        elif read["english"][:1] == read["spanish"][:1]:
            failures.append("both lines came back with the same sounds")
        cdp.send("Target.closeTarget", {"targetId": mixed["targetId"]})

        # A dictionary that arrives while the page is open is answered without the reader
        # loading it again: the card on a word says what it means the moment the meanings are
        # here. Which dictionaries are held is not a setting, so nothing else tells the page.
        def card_on(word):
            spot = json.loads(evaluate(cdp, page, """
                (() => {
                  const box = [...document.querySelectorAll('#prose .px-w')]
                    .find(w => (w.querySelector('.px-was') || {}).textContent === %r);
                  const r = box.getBoundingClientRect();
                  return JSON.stringify({x: r.left + r.width / 2, y: r.top + r.height / 2});
                })()
            """ % word))
            cdp.send("Input.dispatchMouseEvent", {"type": "mouseMoved", "x": 2, "y": 2},
                     session=page)
            time.sleep(0.6)
            for step in (0, 1):
                cdp.send("Input.dispatchMouseEvent", {
                    "type": "mouseMoved", "x": spot["x"] + step, "y": spot["y"],
                }, session=page)
            return wait_for(cdp, page, """
                (() => {
                  const host = document.getElementById('phonetix-card-host');
                  const card = host && host.shadowRoot && host.shadowRoot.querySelector('.card');
                  return card ? card.innerText.replace(/\\s+/g, ' ') : null;
                })()
            """, lambda v: v is not None and word in v, tries=8)

        REFUSED.add("es")
        evaluate(cdp, settings, (
            "chrome.runtime.sendMessage({phonetix:'forgetPack',data:{lang:'es'}})"
            ".then(r => JSON.stringify(r))"
        ))
        time.sleep(1)
        without = card_on("perro") or ""
        REFUSED.discard("es")
        evaluate(cdp, settings, (
            "chrome.runtime.sendMessage({phonetix:'getPack',data:{lang:'es'}})"
            ".then(r => JSON.stringify(r))"
        ))
        time.sleep(1)
        with_it = card_on("perro") or ""
        print(f"  a dictionary fetched with the page open: {without[:40]!r} -> {with_it[:40]!r}")
        # The engine may guess it meanwhile, which the card says is a guess: what must not be
        # there is the dictionary's own answer.
        if "Hund" in without and "guess" not in without.lower():
            failures.append(f"the card knew what perro means with no dictionary: {without!r}")
        if "Hund" not in with_it:
            failures.append("a dictionary fetched while the page was open changed nothing on it")

        # The card's own buttons, pressed where they are drawn: the play button plays the word
        # through Web Audio, and the Wiktionary link opens the word's entry in a tab.
        def press_in_card(selector):
            """Pressed the way a reader reaches it: from the word down through the arrow, which
            is the card's only way in, and along to the control."""
            where = evaluate(cdp, page, """
                (() => {
                  const host = document.getElementById('phonetix-card-host');
                  const root = host && host.shadowRoot;
                  const el = root && root.querySelector(%s);
                  const arrow = root && root.querySelector('.card-arrow');
                  if (!el || !arrow) return null;
                  const r = el.getBoundingClientRect();
                  const a = arrow.getBoundingClientRect();
                  return JSON.stringify({x: r.x + r.width / 2, y: r.y + r.height / 2,
                                         ax: a.x + a.width / 2, top: a.top, bottom: a.bottom});
                })()
            """ % json.dumps(selector))
            if not where:
                return False
            box = json.loads(where)
            path = [(box["ax"], box["top"] - 6), (box["ax"], box["bottom"] + 14), (box["x"], box["y"])]
            for (x0, y0), (x1, y1) in zip(path, path[1:]):
                for i in range(1, 9):
                    cdp.send("Input.dispatchMouseEvent", {
                        "type": "mouseMoved", "x": x0 + (x1 - x0) * i / 8,
                        "y": y0 + (y1 - y0) * i / 8}, session=page)
                    time.sleep(0.03)
            time.sleep(0.3)
            for kind in ("mousePressed", "mouseReleased"):
                cdp.send("Input.dispatchMouseEvent", {"type": kind, "x": box["x"], "y": box["y"],
                                                      "button": "left", "clickCount": 1},
                         session=page)
            return True

        cdp.send("WebAudio.enable", session=page)
        cdp.events.clear()
        pressed = press_in_card(".audio")
        played = []
        for _ in range(20):
            time.sleep(0.5)
            cdp.send("Runtime.evaluate", {"expression": "1"}, session=page)
            played = [e["params"]["node"]["nodeType"] for e in cdp.events
                      if e.get("method") == "WebAudio.audioNodeCreated"]
            if "AudioBufferSource" in played:
                break
        print(f"  the card's play button: pressed {pressed}, audio nodes {sorted(set(played))}")
        if "AudioBufferSource" not in played:
            failures.append(f"pressing the card's play button played nothing ({pressed})")
        if not card_on("perro"):
            failures.append("the card did not come back on perro for its Wiktionary link")
        cdp.events.clear()
        pressed = press_in_card("[data-does=Wiktionary]")
        opened = []
        for _ in range(20):
            time.sleep(0.5)
            opened = [t["url"] for t in cdp.send("Target.getTargets")["targetInfos"]
                      if "wiktionary.org" in t["url"]]
            if opened:
                break
        print(f"  the card's Wiktionary link: pressed {pressed}, opened {opened[:1]}")
        if not any("/wiki/perro" in url for url in opened):
            failures.append(f"the card's Wiktionary link opened no entry for perro: {opened}")

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
