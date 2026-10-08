#!/usr/bin/env python3
"""The card for an inflected word, on a real Spanish page, in both engines.

A reader who stops at "anduvo" gets three lines: the word with the ending that makes it this
form in the accent and its transcription behind it, what it means in that form ("he walked"),
and the form line, "preterite · indicative · 3rd singular of andar". Each term on that line
opens a sheet as wide as the card that names its category, says what it is, and lists the word
in every value of it; a row read as the card's word turns the card into that form, and the
lemma opens its whole entry. Every one of those is checked here through a pointer, with the
geometry the design fixes (one inset, one gap, one-line rows of one height) measured rather
than looked at, and a screenshot of each state written for a person to judge.

The pack is the published Spanish one, with full verb tables, which the fixture packs do not
have; PHONETIX_ES_PACK names another:

  uv run --with pillow python scripts/proofread/grammar_card.py [chrome|firefox]

Chrome needs `pnpm build`, Firefox `pnpm zip:firefox`. Screenshots go to
scripts/proofread/out/grammar-*.png.
"""
import base64
import http.server
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP
from on_a_page import ChromeHand, evaluate, walk

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(HERE, "out")
PACK = os.environ.get("PHONETIX_ES_PACK", os.path.join(ROOT, ".cache", "release", "es.pack"))
PORT = int(os.environ.get("PHONETIX_GRAMMAR_PORT", "8931"))

PAGE = (
    "<!doctype html><html lang='es'><meta charset='utf-8'><body style='margin:0'>"
    "<p id='prose' style='margin:80px 0 0 120px;width:520px;font-size:18px;line-height:1.6'>"
    "Ayer Juan anduvo despacio por la calle hasta la casa de su madre.</p>"
    "</body></html>"
).encode()

# The same line at the bottom of the window, where the card opens over its word and a sheet
# goes over the card rather than over the word.
LOW = PAGE.replace(b"margin:80px 0 0 120px;", b"position:fixed;bottom:16px;left:120px;margin:0;")


def serve():
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def send(self, body, kind):
            self.send_response(200)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/packs.json":
                rows = [{"id": "lex-es", "lang": "es", "built": 0, "entries": 1, "keys": 1,
                         "glosses": 1, "bytes": os.path.getsize(PACK), "sha256": ""}]
                return self.send(json.dumps(rows).encode(), "application/json")
            if self.path.endswith(".pack"):
                if os.path.basename(self.path) != "es.pack":
                    return self.send_error(404)
                with open(PACK, "rb") as f:
                    return self.send(f.read(), "application/octet-stream")
            page = LOW if self.path.startswith("/low") else PAGE
            return self.send(page, "text/html; charset=utf-8")

    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()


# Where "anduvo" is on the page, painted or not.
WORD_JS = """
(() => {
  const p = document.getElementById('prose');
  for (const w of p.querySelectorAll('.px-w')) {
    const was = ((w.querySelector('.px-was') || {}).textContent || '').trim();
    if (was === 'anduvo') {
      const r = w.getBoundingClientRect();
      return {x: (r.left + r.right) / 2, y: (r.top + r.bottom) / 2, top: r.top, bottom: r.bottom};
    }
  }
  const walk = document.createTreeWalker(p, NodeFilter.SHOW_TEXT);
  for (let n = walk.nextNode(); n; n = walk.nextNode()) {
    if (n.parentElement.closest('.px-w')) continue;
    const at = n.nodeValue.indexOf('anduvo');
    if (at < 0) continue;
    const range = document.createRange();
    range.setStart(n, at);
    range.setEnd(n, at + 6);
    const r = range.getBoundingClientRect();
    return {x: (r.left + r.right) / 2, y: (r.top + r.bottom) / 2, top: r.top, bottom: r.bottom};
  }
  return null;
})()
"""

# Everything about the card a check asks: its lines, its sheet, its entry, and the boxes of each.
CARD_JS = r"""
(() => {
  const host = document.getElementById('phonetix-card-host');
  const root = host && host.shadowRoot;
  const card = root && root.querySelector('.card');
  if (!card) return {open: false};
  const box = (e) => {
    if (!e) return null;
    const r = e.getBoundingClientRect();
    return {left: r.left, right: r.right, top: r.top, bottom: r.bottom, width: r.width,
            height: r.height, x: (r.left + r.right) / 2, y: (r.top + r.bottom) / 2};
  };
  const text = (e) => (e ? e.textContent.replace(/\s+/g, ' ').trim() : '');
  const head = card.querySelector('.card-head');
  const lines = [...head.children].filter(e => e.getBoundingClientRect().height > 0);
  const sheet = card.querySelector('.form-sheet');
  // Every element whose text runs past its own box: cut off, or wrapped onto a second line.
  const clipped = [];
  for (const e of card.querySelectorAll('.form-sheet td, .sheet-grid > *, .card-top > *, .forms')) {
    if (e.scrollWidth > e.clientWidth + 1) clipped.push(text(e));
  }
  const words = [];
  const walker = document.createTreeWalker(card, NodeFilter.SHOW_TEXT);
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    if (n.nodeValue.trim()) words.push(n.nodeValue.trim());
  }
  return {
    open: true,
    card: box(card),
    head: box(head),
    lines: lines.map(e => e.className),
    word: text(card.querySelector('.card-top .word')),
    ending: text(card.querySelector('.card-top .word .ending')),
    ipaOnTop: !!card.querySelector('.card-top .ipa'),
    top: box(card.querySelector('.card-top')),
    wordBox: box(card.querySelector('.card-top .word')),
    headline: text(card.querySelector('.headline .tr')),
    form: text(card.querySelector('[data-form]')),
    terms: [...card.querySelectorAll('[data-term]')].map(t => ({
      category: t.dataset.term, label: text(t), box: box(t)})),
    lemma: box(card.querySelector('[data-lemma]')),
    back: text(card.querySelector('[data-back]')),
    backBox: box(card.querySelector('[data-back]')),
    takes: getComputedStyle(card.parentElement).pointerEvents,
    sheet: sheet ? {
      category: sheet.dataset.sheet,
      box: box(sheet),
      over: sheet.classList.contains('over'),
      cat: text(sheet.querySelector('.sheet-cat')),
      // Where the header's text starts, which is the inset a reader sees.
      catBox: (() => {
        const range = document.createRange();
        range.selectNodeContents(sheet.querySelector('.sheet-cat'));
        return box(range);
      })(),
      def: text(sheet.querySelector('.sheet-def')),
      ask: box(sheet.querySelector('[data-ask]')),
      rows: [...sheet.querySelectorAll('tr, .g-cell')].map(r => ({
        spelling: r.dataset.spelling || '', here: r.classList.contains('here'),
        text: text(r), box: box(r)})),
      gridRows: (() => {
        const names = [...sheet.querySelectorAll('.g-name, .g-head')];
        return names.map(n => box(n).height);
      })(),
      tableCells: [...sheet.querySelectorAll('td')].map(td => box(td).left),
    } : null,
    entry: card.querySelector('[data-entry]') ? {
      senses: card.querySelectorAll('[data-entry] li').length,
      groups: [...card.querySelectorAll('.entry-pos')].map(text),
      box: box(card.querySelector('[data-entry]')),
    } : null,
    clipped,
    words,
  };
})()
"""

# What the card must never say: how to use it. It explains itself by its layout.
INSTRUCTION = re.compile(
    r"\b(click|tap|hover|point at|press|select|choose|same tense|same person|to see|to open)\b",
    re.I)
DASHES = re.compile("[–—]")


class Run:
    def __init__(self, hand, name, failures, shoot):
        self.hand, self.name, self.failures, self.shoot = hand, name, failures, shoot

    def fail(self, message):
        self.failures.append(f"{self.name}: {message}")

    def card(self):
        return self.hand.ask(CARD_JS) or {"open": False}

    def wait(self, want, tries=40, pause=0.25):
        seen = self.card()
        for _ in range(tries):
            if want(seen):
                return seen
            time.sleep(pause)
            seen = self.card()
        return seen

    def words_ok(self, seen, where):
        for said in seen.get("words", []):
            if INSTRUCTION.search(said):
                self.fail(f"{where}: instruction text {said!r}")
            if DASHES.search(said):
                self.fail(f"{where}: a dash in {said!r}")
        if seen.get("clipped"):
            self.fail(f"{where}: cut off or wrapped: {seen['clipped']}")

    def sheet_ok(self, seen, where):
        sheet, card = seen["sheet"], seen["card"]
        if abs(sheet["box"]["width"] - card["width"]) > 0.5:
            self.fail(f"{where}: sheet {sheet['box']['width']:.1f} wide, card {card['width']:.1f}")
        if abs(sheet["box"]["left"] - card["left"]) > 0.5:
            self.fail(f"{where}: sheet starts at {sheet['box']['left']:.1f}, card at "
                      f"{card['left']:.1f}")
        gap = (card["top"] - sheet["box"]["bottom"]) if sheet["over"] else (
            sheet["box"]["top"] - card["bottom"])
        if abs(gap - 8) > 0.6:
            self.fail(f"{where}: sheet {gap:.1f}px from the card, not 8")
        inset = sheet["catBox"]["left"] - sheet["box"]["left"]
        card_inset = seen["wordBox"]["left"] - card["left"]
        if abs(inset - card_inset) > 0.6:
            self.fail(f"{where}: sheet text inset {inset:.1f}, card text inset {card_inset:.1f}")
        heights = sorted({round(row["box"]["height"]) for row in sheet["rows"]})
        if any(abs(h - 30) > 1 for h in heights):
            self.fail(f"{where}: rows {heights}px high, not 30")
        # Explained only when asked: the sheet is the word's other forms, and a paragraph of
        # definitions over every one of them was a sheet to read rather than a glance.
        if sheet["def"]:
            self.fail(f"{where}: the sheet explains {sheet['cat']!r} without being asked")

    def check_low(self, word):
        """A card over its word: its sheets open over the card, the same width and gap."""
        hand = self.hand
        hand.click(2, 2)
        hand.move(2, 2)
        time.sleep(0.6)
        for step in (0, 1):
            hand.move(word["x"] + step, word["y"])
            time.sleep(0.05)
        rest = self.wait(lambda c: c["open"] and c["word"] == "anduvo" and c["headline"])
        if not rest["open"] or rest["card"]["bottom"] > word["y"]:
            self.fail(f"low: no card over anduvo: {rest.get('card')}")
            return
        inside = rest["card"]["bottom"] - 20
        walk(hand, (word["x"], word["y"]), (word["x"], inside))
        time.sleep(0.4)
        term = next(t for t in rest["terms"] if t["category"] == "mood")["box"]
        walk(hand, (word["x"], inside), (term["x"], term["y"]), step=6)
        mood = self.wait(lambda c: c.get("sheet") and c["sheet"]["category"] == "mood", tries=20)
        if not mood.get("sheet"):
            self.fail("low: pointing at indicative opened no sheet")
            return
        print(f"  {self.name}: low: card {mood['card']['top']:.0f}-{mood['card']['bottom']:.0f}, "
              f"sheet {'over' if mood['sheet']['over'] else 'under'} it at "
              f"{mood['sheet']['box']['top']:.0f}-{mood['sheet']['box']['bottom']:.0f}")
        if not mood["sheet"]["over"] or mood["sheet"]["box"]["top"] < 0:
            self.fail(f"low: the sheet is not over the card: {mood['sheet']['box']}")
        self.sheet_ok(mood, "low mood sheet")
        self.shoot("low", mood["card"], mood["sheet"]["box"])

    def check(self, word):
        hand = self.hand
        hand.click(2, 2)
        hand.move(2, 2)
        time.sleep(0.6)
        for step in (0, 1):
            hand.move(word["x"] + step, word["y"])
            time.sleep(0.05)
        rest = self.wait(lambda c: c["open"] and c["word"] == "anduvo" and c["headline"])
        if not rest["open"] or rest["word"] != "anduvo":
            self.fail(f"no card on anduvo: {rest.get('word')!r}")
            return
        print(f"  {self.name}: at rest {rest['lines']}: {rest['word']!r} "
              f"(ending {rest['ending']!r}) / {rest['headline']!r} / {rest['form']!r}")
        if rest["lines"] != ["card-top", "headline", "forms"]:
            self.fail(f"the card at rest is {rest['lines']}, not three lines")
        if rest["ending"] != "uvo":
            self.fail(f"the ending lit is {rest['ending']!r}, not 'uvo'")
        if not rest["ipaOnTop"]:
            self.fail("the transcription is not on the word's line")
        if rest["headline"] != "he walked":
            self.fail(f"the card says {rest['headline']!r}, not 'he walked'")
        if not rest["form"].endswith("of andar") or "indicative" not in rest["form"]:
            self.fail(f"the form line reads {rest['form']!r}")
        if [t["label"] for t in rest["terms"]] != ["preterite", "indicative", "3rd singular"]:
            self.fail(f"the terms are {[t['label'] for t in rest['terms']]}")
        if rest["form"].count("anduvo") or rest["words"].count("anduvo") > 1:
            self.fail("the card names anduvo twice")
        if abs((rest["wordBox"]["left"] - rest["card"]["left"]) - 17) > 0.6:
            self.fail(f"text inset {rest['wordBox']['left'] - rest['card']['left']:.1f}, not 16 "
                      "inside the border")
        self.words_ok(rest, "at rest")
        self.shoot("rest", rest["card"])

        # In through the arrow, then to each term.
        below = rest["card"]["top"] > word["y"]
        inside = rest["card"]["top"] + 20 if below else rest["card"]["bottom"] - 20
        walk(hand, (word["x"], word["y"]), (word["x"], inside))
        time.sleep(0.4)
        terms = {t["category"]: t for t in rest["terms"]}

        def point(category):
            term = terms[category]["box"]
            walk(hand, (word["x"], inside), (term["x"], term["y"]), step=6)
            return self.wait(lambda c: c.get("sheet") and c["sheet"]["category"] == category,
                             tries=20)

        mood = point("mood")
        if not mood.get("sheet"):
            self.fail("pointing at indicative opened no sheet")
            return
        print(f"  {self.name}: mood sheet {mood['sheet']['cat']!r}: "
              f"{[r['spelling'] for r in mood['sheet']['rows']]}")
        if mood["sheet"]["cat"].replace("?", "").strip().lower() != "mood · indicative":
            self.fail(f"the sheet is headed {mood['sheet']['cat']!r}")
        if "anduviera" not in [r["spelling"] for r in mood["sheet"]["rows"]]:
            self.fail("the mood sheet has no anduviera row")
        if not any(r["here"] and r["spelling"] == "anduvo" for r in mood["sheet"]["rows"]):
            self.fail("the mood sheet does not light anduvo's own row")
        self.sheet_ok(mood, "mood sheet")
        self.words_ok(mood, "mood sheet")
        self.shoot("mood", mood["card"], mood["sheet"]["box"])
        # "Indicative" is a term most readers have to look up: its "?" says what it is.
        ask = mood["sheet"].get("ask")
        if not ask:
            self.fail("the mood sheet offers no explanation of indicative")
        else:
            hand.click(ask["x"], ask["y"])
            asked = self.wait(lambda c: c.get("sheet") and c["sheet"]["def"], tries=20)
            said = (asked.get("sheet") or {}).get("def") or ""
            print(f"  {self.name}: indicative explained: {said[:70]!r}")
            if "Indicative" not in said:
                self.fail(f"pressing the mood sheet's ? explained nothing: {said!r}")
            else:
                self.shoot("mood-asked", asked["card"], asked["sheet"]["box"])
            hand.click(ask["x"], ask["y"])

        tense = point("tense")
        if tense.get("sheet"):
            self.sheet_ok(tense, "tense sheet")
            self.words_ok(tense, "tense sheet")
            self.shoot("tense", tense["card"], tense["sheet"]["box"])
        else:
            self.fail("pointing at preterite opened no sheet")

        person = point("person")
        if person.get("sheet"):
            cells = [r["spelling"] for r in person["sheet"]["rows"]]
            print(f"  {self.name}: person grid {person['sheet']['cat']!r}: {cells}")
            if len(cells) != 6:
                self.fail(f"the person grid has {len(cells)} cells")
            self.sheet_ok(person, "person grid")
            # Who and how many need no explaining.
            if person["sheet"].get("ask"):
                self.fail("the person grid offers to explain person and number")
            self.words_ok(person, "person grid")
            self.shoot("person", person["card"], person["sheet"]["box"])
        else:
            self.fail("pointing at 3rd singular opened no sheet")

        # Back to indicative, down into its sheet, and the subjunctive row picked.
        mood = point("mood")
        row = next((r for r in (mood.get("sheet") or {}).get("rows", [])
                    if r["spelling"] == "anduviera"), None)
        if row:
            term = terms["mood"]["box"]
            walk(hand, (term["x"], term["y"]), (row["box"]["x"], row["box"]["y"]), step=4)
            time.sleep(0.3)
            hand.click(row["box"]["x"], row["box"]["y"])
            picked = self.wait(lambda c: c["word"] == "anduviera" and c["top"] and c["back"])
            print(f"  {self.name}: picked: {picked.get('word')!r} (ending "
                  f"{picked.get('ending')!r}) / {picked.get('headline')!r} / "
                  f"{picked.get('form')!r} / back {picked.get('back')!r}")
            if picked.get("word") != "anduviera":
                self.fail(f"picking anduviera left the card on {picked.get('word')!r}")
            else:
                if picked["ending"] != "uviera":
                    self.fail(f"anduviera lights {picked['ending']!r}")
                if picked["headline"] != "(if) he walked":
                    self.fail(f"anduviera says {picked['headline']!r}")
                if "subjunctive" not in picked["form"]:
                    self.fail(f"anduviera's form line is {picked['form']!r}")
                if picked["back"] != "← anduvo":
                    self.fail(f"the way back reads {picked['back']!r}")
                # Its IPA arrives from a lookup.
                picked = self.wait(lambda c: c["ipaOnTop"], tries=12)
                self.words_ok(picked, "picked form")
                self.shoot("picked", picked["card"])
            page = hand.ask("document.getElementById('prose').textContent")
            if "anduviera" in (page or ""):
                self.fail("picking a form changed the page")
        else:
            self.fail("no anduviera row to pick")

        # The lemma opens its entry.
        now = self.card()
        if now.get("lemma"):
            hand.click(now["lemma"]["x"], now["lemma"]["y"])
            entry = self.wait(lambda c: c.get("entry"))
            if not entry.get("entry"):
                self.fail("andar opened no entry")
            else:
                print(f"  {self.name}: entry {entry['word']!r}: {entry['entry']['senses']} senses "
                      f"under {entry['entry']['groups']}, back {entry['back']!r}")
                if entry["entry"]["senses"] <= 4:
                    self.fail(f"the entry has {entry['entry']['senses']} senses")
                if entry["word"] != "andar" or entry["back"] != "← anduvo":
                    self.fail(f"the entry is {entry['word']!r} with back {entry['back']!r}")
                self.words_ok(entry, "entry")
                if entry["card"]["bottom"] > hand.ask("window.innerHeight") + 0.5 or \
                        entry["card"]["top"] < 0:
                    self.fail(f"the entry runs out of the window: {entry['card']}")
                self.shoot("entry", entry["card"])
                hand.click(entry["backBox"]["x"], entry["backBox"]["y"])
                home = self.wait(lambda c: c["word"] == "anduvo" and not c["back"])
                if home.get("word") != "anduvo" or home.get("back"):
                    self.fail(f"the way back left the card on {home.get('word')!r}")
        else:
            self.fail("the form line has no lemma to open")


def chrome(failures):
    cdp = PipeCDP()
    cdp.send("Target.setDiscoverTargets", {"discover": True})
    extid = cdp.ensure_extension()
    base = f"http://127.0.0.1:{PORT}"
    try:
        book = cdp.send("Target.createTarget", {"url": f"chrome-extension://{extid}/viewbook.html"})
        settings = cdp.send("Target.attachToTarget",
                            {"targetId": book["targetId"], "flatten": True})["sessionId"]
        cdp.send("Runtime.enable", session=settings)
        time.sleep(2)
        evaluate(cdp, settings, (
            f"chrome.storage.local.set({{packBaseUrl:'{base}',targetLanguage:'en',"
            "on:true,layer:'sound',density:1})"))
        got = evaluate(cdp, settings,
                       "chrome.runtime.sendMessage({phonetix:'getPack',data:{lang:'es'}})"
                       ".then(r => JSON.stringify(r))")
        print(f"  chrome: the pack: {str(got)[:80]}")
        target = cdp.send("Target.createTarget", {"url": f"{base}/page.html"})
        page = cdp.send("Target.attachToTarget",
                        {"targetId": target["targetId"], "flatten": True})["sessionId"]
        cdp.send("Runtime.enable", session=page)
        cdp.send("Page.enable", session=page)
        cdp.send("Emulation.setDeviceMetricsOverride", {
            "width": 1280, "height": 900, "deviceScaleFactor": 1, "mobile": False}, session=page)
        hand = ChromeHand(cdp, page)
        word = None
        for _ in range(30):
            word = hand.ask(WORD_JS)
            if word and hand.ask("document.querySelectorAll('.px-w').length"):
                break
            time.sleep(1)
        time.sleep(2)
        word = hand.ask(WORD_JS)

        def shoot(name, *boxes):
            left = min(b["left"] for b in boxes) - 16
            top = min(b["top"] for b in boxes) - 16
            right = max(b["right"] for b in boxes) + 16
            bottom = max(b["bottom"] for b in boxes) + 16
            img = cdp.send("Page.captureScreenshot", {"format": "png", "clip": {
                "x": max(0, left), "y": max(0, top), "width": right - left,
                "height": bottom - top, "scale": 2}}, session=page)["data"]
            path = os.path.join(OUT, f"grammar-chrome-{name}.png")
            with open(path, "wb") as f:
                f.write(base64.b64decode(img))

        run = Run(hand, "chrome", failures, shoot)
        run.check(word)
        cdp.send("Page.navigate", {"url": f"{base}/low.html"}, session=page)
        time.sleep(4)
        run.check_low(hand.ask(WORD_JS))
    finally:
        cdp.close()


def firefox(failures):
    from on_firefox import FirefoxHand, Marionette, UUID, newest_zip, profile
    base = f"http://127.0.0.1:{PORT}"
    where = tempfile.mkdtemp(prefix="phonetix-firefox-")
    profile(where)
    proc = subprocess.Popen(
        ["firefox", "--headless", "--no-remote", "--marionette", "--profile", where,
         "--width", "1280", "--height", "900", "about:blank"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        env={**os.environ, "MOZ_MARIONETTE_PORT": "2828"})
    driver = None
    try:
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
        driver.send("WebDriver:SetWindowRect", {"width": 1280, "height": 900})
        driver.send("WebDriver:Navigate", {"url": f"moz-extension://{UUID}/viewbook.html"})
        time.sleep(3)
        told = driver.script(
            "const done = arguments[0];"
            f"browser.storage.local.set({{packBaseUrl: '{base}', targetLanguage: 'en',"
            " density: 1, layer: 'sound', on: true})"
            "  .then(() => browser.runtime.sendMessage({phonetix: 'openPack', data: {lang: 'es'}}))"
            "  .then(r => done(JSON.stringify(r)), e => done('failed: ' + e));",
            timeout=180000)
        print(f"  firefox: the pack: {str(told)[:80]}")
        driver.send("WebDriver:Navigate", {"url": f"{base}/page.html"})
        hand = FirefoxHand(driver)
        for _ in range(30):
            if hand.ask("document.querySelectorAll('.px-w').length"):
                break
            time.sleep(1)
        time.sleep(2)
        word = hand.ask(WORD_JS)

        def shoot(name, *boxes):
            shot = driver.send("WebDriver:TakeScreenshot", {"full": False})["value"]
            path = os.path.join(OUT, f"grammar-firefox-{name}.png")
            crop(base64.b64decode(shot), boxes, path)

        run = Run(hand, "firefox", failures, shoot)
        run.check(word)
        driver.send("WebDriver:Navigate", {"url": f"{base}/low.html"})
        time.sleep(4)
        run.check_low(hand.ask(WORD_JS))
    finally:
        if driver is not None:
            try:
                driver.send("Marionette:Quit")
            except SystemExit:
                pass
        proc.terminate()
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
        shutil.rmtree(where, ignore_errors=True)


def crop(png, boxes, path):
    """The part of a whole-window screenshot the boxes are in, where Pillow is at hand, and the
    whole window, which is under 2000px either way, where it is not."""
    try:
        from PIL import Image
    except ImportError:
        with open(path, "wb") as f:
            f.write(png)
        return
    import io
    image = Image.open(io.BytesIO(png))
    left = int(max(0, min(b["left"] for b in boxes) - 16))
    top = int(max(0, min(b["top"] for b in boxes) - 16))
    right = int(min(image.width, max(b["right"] for b in boxes) + 16))
    bottom = int(min(image.height, max(b["bottom"] for b in boxes) + 16))
    image.crop((left, top, right, bottom)).save(path)


def main():
    os.makedirs(OUT, exist_ok=True)
    if not os.path.exists(PACK):
        raise SystemExit(f"no pack at {PACK}: set PHONETIX_ES_PACK")
    serve()
    engines = sys.argv[1:] or ["chrome", "firefox"]
    failures = []
    for engine in engines:
        {"chrome": chrome, "firefox": firefox}[engine](failures)
    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print(f"\nPASS - {', '.join(engines)}: anduvo reads in three lines, its terms open sheets "
          "as wide as the card, a row reads the card as that form, and andar opens its entry")


if __name__ == "__main__":
    main()
