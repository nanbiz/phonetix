#!/usr/bin/env python3
"""
Behavioral test suite for the built extension, driven through CDP.

  integration  controlled local fixtures with hard assertions (health, per-title
               language, garbage gating, non-Latin coverage). These would have
               caught the silently-dead language detector.
  e2e          a few real sites, asserting invariants (no letter-name garbage,
               detection produces more than the page language).

Run: uv run python scripts/proofread/suite.py [integration|e2e|all]
"""
import json, os, sys, time, threading, http.server, functools
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP

PASS, FAIL = [], []
def ok(name):   PASS.append(name); print(f"  PASS  {name}", flush=True)
def bad(name, d=""): FAIL.append(name); print(f"  FAIL  {name}  {d}", flush=True)
def expect(name, cond, d=""): ok(name) if cond else bad(name, d)

# ── fixtures served locally ──────────────────────────────────────────
FIXTURES = {
  "/mixed.html": """<!doctype html><html lang="de"><head><meta charset="utf-8"><title>x</title></head><body><nav>Startseite Verlauf</nav><main>
   <div class="t" data-exp="en">Breaking Bad Final Season Explained In Great Detail</div>
   <div class="t" data-exp="de">Die Tagesschau von heute Abend mit den wichtigsten Nachrichten</div>
   <div class="t" data-exp="fr">La cuisine française traditionnelle avec des recettes simples</div>
   <div class="t" data-exp="es">El clásico entre Real Madrid y Barcelona de esta noche</div>
   <div class="t" data-exp="it">La ricetta della pasta italiana tradizionale fatta in casa</div>
   <div class="t" data-exp="nl">De nieuwe aflevering van de populaire Nederlandse serie</div>
   <div class="t" data-exp="pt">Como fazer o melhor bolo de chocolate caseiro</div>
   <div class="t" data-exp="en">How To Build A Simple Website From Scratch Today</div>
   <div class="t" data-exp="de">Das Wetter für morgen früh in ganz Deutschland heute</div>
   <div class="t" data-exp="fr">Les meilleures destinations de voyage pour cet été prochain</div>
  </main></body></html>""",

  # A German page whose sidebar lists other-script language names — the classic
  # "letter-name garbage" trap (espeak spelling out foreign characters).
  "/garbage.html": """<!doctype html><html lang="de"><head><meta charset="utf-8"><title>x</title></head><body><main>
   <p>Dies ist ein deutscher Absatz mit ganz normalem Text zum Testen der Aussprache.</p>
   <ul>
    <li>العربية</li><li>中文</li><li>עברית</li><li>Башҡортса</li><li>Ελληνικά</li><li>日本語</li><li>한국어</li>
   </ul></main></body></html>""",

  # Non-Latin content must actually translate (was zero before Intl.Segmenter).
  "/nonlatin.html": """<!doctype html><html lang="ru"><head><meta charset="utf-8"><title>x</title></head><body><main>
   <p>Кошка это домашнее животное которое живёт рядом с человеком очень давно.</p></main></body></html>""",

  # Text the user is editing must never be rewritten: rich editors are
  # contenteditable elements, not <textarea>, and transforming them corrupts typing.
  "/hidden.html": """<!doctype html><html lang="en"><head><meta charset="utf-8"><title>x</title></head><body><main>
    <p id="prose">The quick brown fox jumps over the lazy dog while the river runs quietly past the old stone bridge.</p>
    <div id="panel" style="visibility:hidden"><p id="secret">Collapsed panel text that the page keeps hidden from the reader.</p></div>
  </main></body></html>""",
  "/truncate.html": """<!doctype html><html lang="en"><head><meta charset="utf-8"><title>x</title></head><body><main>
    <div id="clip" style="width:120px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font:16px sans-serif">5-hour limit and Fable model here</div>
  </main></body></html>""",
  "/editable.html": """<!doctype html><html lang="en"><head><meta charset="utf-8"><title>x</title></head><body><main>
   <p id="prose">This is ordinary readable prose that should be transcribed normally.</p>
   <div id="editor" contenteditable="true">I am typing a private message here right now</div>
   <div id="outer"><span id="nested">nested inside an editable region</span></div>
   <textarea id="ta">Some textarea content that must stay untouched</textarea>
  </main><script>document.getElementById('outer').contentEditable = 'true';</script></body></html>""",

  # A feed that appends foreign-language titles after load (the YouTube shape):
  # the MutationObserver must process the new subtree and language it correctly.
  "/dynamic.html": """<!doctype html><html lang="de"><head><meta charset="utf-8"><title>x</title></head><body><main id="feed">
   <div class="t" data-exp="de">Die aktuelle Nachrichtenlage in Deutschland und der Welt heute</div>
  </main><script>
   setTimeout(() => {
     const feed=document.getElementById('feed');
     const add=(lang,txt)=>{const d=document.createElement('div');d.className='t';d.dataset.exp=lang;d.textContent=txt;feed.appendChild(d);};
     add('en','Breaking News Live Coverage Of The Election Results Tonight');
     add('fr','Les meilleures recettes de cuisine française pour le dîner');
     add('es','El resumen completo del partido de fútbol de anoche aquí');
   }, 2000);
  </script></body></html>""",
}

def serve(port):
    class H(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *a): pass
        def do_GET(self):
            path = self.path.split("?")[0]
            body = FIXTURES.get(path)
            if body is None: self.send_error(404); return
            b = body.encode()
            self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    httpd = http.server.HTTPServer(("127.0.0.1", port), H); httpd.timeout = 0.5
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd


class Driver:
    def __init__(self):
        self.cdp = PipeCDP(); self.cdp.send("Target.setDiscoverTargets", {"discover": True})
        # Fail loudly if the extension is not actually loaded, rather than running
        # every assertion against a browser with no extension and "passing".
        self.extid = self.cdp.ensure_extension()
        print(f"  (extension {self.extid} via {getattr(self.cdp, 'how', '?')})", flush=True)
    def load(self, url, settle=9):
        c = self.cdp
        tid = c.send("Target.createTarget", {"url": "about:blank"})["targetId"]
        s = c.send("Target.attachToTarget", {"targetId": tid, "flatten": True})["sessionId"]
        c.send("Page.enable", session=s); c.send("Runtime.enable", session=s)
        c.send("Page.navigate", {"url": url}, session=s, timeout=45)
        time.sleep(settle)
        self.s, self.tid = s, tid
        return s
    def js(self, expr):
        r = self.cdp.send("Runtime.evaluate", {"expression": expr, "returnByValue": True}, session=self.s)
        return r.get("result", {}).get("value")
    def hover(self, x, y):
        """Move the real mouse there. :hover is a state of the browser, and no event
           dispatched from JavaScript puts an element into it — which is how a rule
           that only applies while hovered went unmeasured."""
        self.cdp.send("Input.dispatchMouseEvent",
                      {"type": "mouseMoved", "x": x, "y": y, "buttons": 0}, session=self.s)
        time.sleep(0.35)
    def poll(self, expr, ok, tries=25, delay=2.0):
        """Wait for the extension to finish; a cold start loads the ngram model,
           espeak and dictionaries, which is slow on a CI runner."""
        v = None
        for _ in range(tries):
            v = self.js(expr)
            if ok(v):
                return v
            time.sleep(delay)
        return v
    def close_tab(self):
        try: self.cdp.send("Target.closeTarget", {"targetId": self.tid})
        except Exception: pass
    def close(self): self.cdp.close()

PORT = 8901
SPANS_BY = """(sel => [...document.querySelectorAll(sel)].map(d => {
  const langs={}, srcs={}; let space=0;
  for (const s of d.querySelectorAll('.phonetix')) {
    const l=s.dataset.lang||'?'; langs[l]=(langs[l]||0)+1;
    const src=s.dataset.src||'?'; srcs[src]=(srcs[src]||0)+1;
    if (src==='espeak' && (s.querySelector('.px-ipa')?.textContent||'').trim().includes(' ')) space++;
  }
  const top=Object.entries(langs).sort((a,b)=>b[1]-a[1])[0];
  return { exp:d.dataset.exp, n:Object.values(langs).reduce((a,b)=>a+b,0), lang: top?top[0]:'none', langs, srcs, space };
}))"""


def integration(d):
    print("[integration]")
    # health: subsystems alive
    d.load(f"http://127.0.0.1:{PORT}/mixed.html?pxhealth=1", settle=3)
    h = d.poll("document.documentElement.dataset.pxhealth||''", ok=lambda v: bool(v)) or "{}"
    health = json.loads(h)
    expect("health.eld (language detection alive)", health.get("eld") is True, str(health.get("errors")))
    expect("health.dict (dictionary loaded)", health.get("dict") is True, str(health.get("errors")))
    expect("health.espeak (espeak alive)", health.get("espeak") is True, str(health.get("errors")))
    if health.get("eld") is not True:
        diag = d.js("JSON.stringify({styles: !!document.getElementById('phonetix-styles'),"
                    " spans: document.querySelectorAll('.phonetix').length, ready: document.readyState})")
        print("  DIAG:", diag, flush=True)
        for e in d.cdp.events[-20:]:
            p = e.get("params", {})
            if e["method"] == "Runtime.exceptionThrown":
                ex = p.get("exceptionDetails", {})
                print("  EXC:", str(ex.get("text"))[:120],
                      str(ex.get("exception", {}).get("description"))[:200], flush=True)
            else:
                args = " ".join(str(a.get("value"))[:100] for a in p.get("args", []))
                print("  LOG:", p.get("type"), args[:200], flush=True)
    # per-title language
    rows = d.poll(f"{SPANS_BY}('div.t')", ok=lambda r: bool(r) and all(x["n"] > 0 for x in r)) or []
    right = sum(1 for r in rows if r["lang"] == r["exp"])
    for r in rows:
        expect(f"lang {r['exp']}: '{'' }'", r["lang"] == r["exp"], f"got={r['lang']} langs={r['langs']}")
    expect("mixed-language >=80%", right >= 0.8 * len(rows), f"{right}/{len(rows)}")
    d.close_tab()

    # garbage gating: no espeak span may be letter-spelling (contain a space)
    d.load(f"http://127.0.0.1:{PORT}/garbage.html", settle=3)
    g = (d.poll(f"{SPANS_BY}('main')", ok=lambda r: bool(r) and r[0]["n"] > 0) or [{"space": 0, "srcs": {}, "n": 0}])[0]
    expect("no letter-name garbage", g["n"] > 0 and g["space"] == 0, f"n={g['n']} space={g['space']} srcs={g['srcs']}")
    d.close_tab()

    # non-Latin actually translates
    d.load(f"http://127.0.0.1:{PORT}/nonlatin.html", settle=3)
    nl = (d.poll(f"{SPANS_BY}('main')", ok=lambda r: bool(r) and r[0]["n"] > 5) or [{"n": 0, "lang": "none"}])[0]
    expect("non-Latin translated (ru)", nl["n"] > 5 and nl["lang"] == "ru", f"n={nl['n']} lang={nl['lang']}")
    d.close_tab()

    # editable regions must never be transformed
    d.load(f"http://127.0.0.1:{PORT}/editable.html", settle=3)
    ed = d.poll("JSON.stringify({prose: document.querySelectorAll('#prose .phonetix').length,"
                " editor: document.querySelectorAll('#editor .phonetix').length,"
                " nested: document.querySelectorAll('#outer .phonetix').length,"
                " editorText: document.getElementById('editor').textContent.trim()})",
                ok=lambda v: bool(v) and json.loads(v)["prose"] > 0)
    e = json.loads(ed or "{}")
    expect("prose is transcribed", e.get("prose", 0) > 0, str(e))
    expect("contenteditable untouched", e.get("editor") == 0, str(e))
    expect("nested contenteditable untouched", e.get("nested") == 0, str(e))
    expect("editable text unchanged",
           e.get("editorText") == "I am typing a private message here right now", str(e.get("editorText")))
    d.close_tab()

    # In the hover modes the running text must lay out exactly as the page would
    # without us: the layer that is hidden may not reserve width, or every word is
    # padded by the difference and the text reads as broken ("is  a  branch of").
    d.load(f"http://127.0.0.1:{PORT}/editable.html", settle=3)
    d.poll("document.querySelectorAll('#prose .phonetix').length", ok=lambda v: bool(v) and v > 0)
    SPACING = """(() => {
      const h = document.documentElement;
      const out = {};
      for (const mode of ['px-mode-hover', 'px-mode-reveal']) {
        h.classList.remove('px-mode-hover', 'px-mode-reveal');
        h.classList.add(mode);
        let worst = 0;
        for (const span of document.querySelectorAll('#prose .phonetix')) {
          const shown = [...span.children].find(c => getComputedStyle(c).visibility !== 'hidden');
          if (!shown) continue;
          // The span may be no wider than the layer it is showing.
          const slack = span.getBoundingClientRect().width - shown.getBoundingClientRect().width;
          worst = Math.max(worst, slack);
        }
        out[mode] = Math.round(worst);
      }
      return JSON.stringify(out);
    })()"""
    slack = json.loads(d.js(SPACING) or "{}")
    for mode, worst in slack.items():
        expect(f"no reserved width in {mode}", worst <= 1, f"words padded by up to {worst}px")
    d.close_tab()

    # The tooltip is a fixed box. Symbol descriptions differ in length, and a card
    # that grew with them would move under the cursor exactly while being read —
    # which is what shipped, twice.
    d.load(f"http://127.0.0.1:{PORT}/editable.html", settle=3)
    d.js("(() => { const s = document.querySelector('#prose .phonetix');"
         " s.dispatchEvent(new MouseEvent('mouseover', {bubbles:true, composed:true})); return 1; })()")
    CARD = ("(() => { const h = document.getElementById('phonetix-tooltip-host');"
            " const c = h && h.shadowRoot && h.shadowRoot.querySelector('.px-tt');"
            " if (!c) return null; const r = c.getBoundingClientRect();"
            " return JSON.stringify({w: Math.round(r.width), h: Math.round(r.height),"
            "   syms: h.shadowRoot.querySelectorAll('.px-sym').length,"
            "   detail: (h.shadowRoot.querySelector('.px-detail')||{}).textContent || '',"
            "   tags: [...h.shadowRoot.querySelectorAll('.px-lang, .px-src')].every(e => !!e.title),"
            "   speakable: !!h.shadowRoot.querySelector('button.px-detail-spk')}); })()")
    def rendered(v):
        try:
            return bool(v) and json.loads(v)["syms"] > 1
        except Exception:
            return False

    first = d.poll(CARD, ok=rendered)
    card = json.loads(first or "{}")
    expect("tooltip has symbols to explore", card.get("syms", 0) > 1, str(card))
    expect("tooltip describes a symbol on open", len(card.get("detail", "")) > 3, str(card))
    expect("language and source tags explain themselves", card.get("tags") is True, str(card))
    expect("the symbol can be heard from the detail line", card.get("speakable") is True, str(card))

    # hover every symbol in turn; the card may not change size, and the detail must
    # stay on the symbol asked about rather than blanking when the cursor leaves
    sizes = d.js("""(() => {
      const h = document.getElementById('phonetix-tooltip-host');
      const root = h.shadowRoot;
      const card = root.querySelector('.px-tt');
      const seen = [];
      for (const sym of root.querySelectorAll('.px-sym')) {
        sym.dispatchEvent(new MouseEvent('mouseenter', {bubbles: true}));
        sym.dispatchEvent(new MouseEvent('mouseleave', {bubbles: true}));
        const r = card.getBoundingClientRect();
        seen.push({w: Math.round(r.width), h: Math.round(r.height),
                   detail: (root.querySelector('.px-detail')||{}).textContent || ''});
      }
      return JSON.stringify(seen);
    })()""")
    seen = json.loads(sizes or "[]")
    widths = {s["w"] for s in seen}
    heights = {s["h"] for s in seen}
    expect("tooltip width is constant across symbols", len(widths) <= 1, f"widths={sorted(widths)}")
    expect("tooltip height is constant across symbols", len(heights) <= 1, f"heights={sorted(heights)}")
    expect("the description stays after the cursor leaves the symbol",
           bool(seen) and all(len(s["detail"]) > 3 for s in seen),
           str([s["detail"][:20] for s in seen][:3]))
    d.close_tab()

    # The layer revealed on hover must sit exactly where the word sat, at the same
    # size: an absolutely positioned child of an inline box is placed against the
    # line box rather than the word, which drops it below its own text and reads as
    # the page moving and changing font under the cursor.
    d.load(f"http://127.0.0.1:{PORT}/editable.html", settle=3)
    d.poll("document.querySelectorAll('#prose .phonetix').length", ok=lambda v: bool(v) and v > 0)
    ALIGN = """(() => {
      const h = document.documentElement;
      const out = {};
      for (const mode of ['px-mode-hover', 'px-mode-reveal']) {
        h.classList.remove('px-mode-hover', 'px-mode-reveal');
        h.classList.add(mode);
        let worstTop = 0, sizeMismatch = 0, worstCentre = 0;
        for (const span of document.querySelectorAll('#prose .phonetix')) {
          const [a, b] = span.children;
          if (!a || !b) continue;
          const ra = a.getBoundingClientRect(), rb = b.getBoundingClientRect();
          worstTop = Math.max(worstTop, Math.abs(ra.top - rb.top));
          // The revealed layer is centred on the word it replaces, so the two share
          // a centre however much wider one of them is.
          const ca = ra.left + ra.width / 2, cb = rb.left + rb.width / 2;
          worstCentre = Math.max(worstCentre, Math.abs(ca - cb));
          if (getComputedStyle(a).fontSize !== getComputedStyle(b).fontSize) sizeMismatch++;
        }
        out[mode] = {top: Math.round(worstTop), sizeMismatch, centre: Math.round(worstCentre)};
      }
      return JSON.stringify(out);
    })()"""
    align = json.loads(d.js(ALIGN) or "{}")
    for mode, r in align.items():
        expect(f"revealed layer sits on the word in {mode}", r["top"] <= 1, f"off by {r['top']}px")
        expect(f"revealed layer keeps the font size in {mode}", r["sizeMismatch"] == 0,
               f"{r['sizeMismatch']} spans change size")
        expect(f"revealed layer is centred on the word in {mode}", r["centre"] <= 1,
               f"off centre by {r['centre']}px")
    d.close_tab()

    # Hovering a word may change what is painted and nothing else. The layer that
    # appears must land exactly on the word it replaces — same top, same centre, same
    # size — or the text jumps under the cursor at the moment of being read.
    #
    # This is measured while the browser really has the word hovered: a :hover rule
    # does not apply otherwise, so the earlier check, which compared the two layers
    # at rest, could not see any of it.
    d.load(f"http://127.0.0.1:{PORT}/editable.html", settle=3)
    d.poll("document.querySelectorAll('#prose .phonetix').length", ok=lambda v: bool(v) and v > 0)

    for mode, hidden, shown in [
        ("px-mode-hover", ".px-orig", ".px-ipa"),
        ("px-mode-reveal", ".px-ipa", ".px-orig"),
    ]:
        d.js(f"(() => {{ const h = document.documentElement;"
             f" h.classList.remove('px-mode-hover','px-mode-reveal');"
             f" h.classList.add('{mode}'); return 1; }})()")

        # what the word looks like before anyone touches it
        before = json.loads(d.js(f"""(() => {{
          const span = [...document.querySelectorAll('#prose .phonetix')][3];
          span.scrollIntoView({{block: 'center'}});
          // The glyphs, not the box around them: padding grows the box without
          // moving its edge, while the text inside it shifts — which is exactly what
          // the reader sees and what an element rect cannot show.
          const range = document.createRange();
          range.selectNodeContents(span.querySelector('{hidden}'));
          const at = range.getBoundingClientRect();
          const box = span.getBoundingClientRect();
          return JSON.stringify({{
            top: at.top, height: at.height, centre: at.left + at.width / 2,
            font: getComputedStyle(span.querySelector('{hidden}')).fontSize,
            x: box.left + box.width / 2, y: box.top + box.height / 2,
          }});
        }})()""") or "{}")

        d.hover(before["x"], before["y"])

        after = json.loads(d.js(f"""(() => {{
          const span = [...document.querySelectorAll('#prose .phonetix')][3];
          const el = span.querySelector('{shown}');
          const range = document.createRange();
          range.selectNodeContents(el);
          const r = range.getBoundingClientRect();
          return JSON.stringify({{
            hovered: span.matches(':hover'),
            top: r.top, height: r.height, centre: r.left + r.width / 2,
            font: getComputedStyle(el).fontSize,
          }});
        }})()""") or "{}")

        expect(f"the word is really hovered in {mode}", after.get("hovered") is True,
               "the browser never entered :hover, so nothing below was measured")
        expect(f"revealed layer keeps the top in {mode}",
               abs(after["top"] - before["top"]) <= 1,
               f"moved {after['top'] - before['top']:.1f}px down")
        expect(f"revealed layer keeps the height in {mode}",
               abs(after["height"] - before["height"]) <= 1,
               f"grew {after['height'] - before['height']:.1f}px")
        expect(f"revealed layer keeps the centre in {mode}",
               abs(after["centre"] - before["centre"]) <= 1,
               f"moved {after['centre'] - before['centre']:.1f}px sideways")
        expect(f"revealed layer keeps the font size in {mode}",
               after["font"] == before["font"], f"{before['font']} -> {after['font']}")

        d.hover(5, 5)   # leave the word

    d.close_tab()

    # A truncated label (text-overflow: ellipsis) must keep its words: an inline-block
    # span is an atomic box the ellipsis swallows whole, so "5-hour limit" lost "limit"
    # to the "…". The word's characters must survive in the transformed spans.
    d.load(f"http://127.0.0.1:{PORT}/truncate.html", settle=3)
    d.js("(() => { const h = document.documentElement;"
         " h.classList.remove('px-mode-reveal'); h.classList.add('px-mode-hover'); return 1; })()")
    kept = d.poll(
        "(() => { const t = [...document.querySelectorAll('#clip .phonetix')].map(s => s.dataset.original);"
        " return JSON.stringify(t); })()",
        ok=lambda v: bool(v) and 'hour' in v)
    words = json.loads(kept or "[]")
    # the first words fit and are transformed; none is collapsed into an ellipsis
    expect("truncated label keeps its transformed words", 'hour' in words and 'limit' in words,
           f"words present: {words}")
    d.close_tab()

    # tooltip lifecycle: a press inside pins it, so dragging out a selection to
    # copy the IPA cannot dismiss it; a press outside still does.
    d.load(f"http://127.0.0.1:{PORT}/editable.html", settle=3)
    VISIBLE = "(document.getElementById('phonetix-tooltip-host')||{style:{}}).style.pointerEvents === 'auto'"
    d.js("(() => { const s = document.querySelector('#prose .phonetix');"
         " s.dispatchEvent(new MouseEvent('mouseover', {bubbles:true, composed:true})); return 1; })()")
    expect("tooltip opens on hover", d.poll(VISIBLE, ok=lambda v: v is True) is True, "never appeared")

    # press inside the tooltip (a selection drag starting), then leave the word
    d.js("(() => { const h = document.getElementById('phonetix-tooltip-host');"
         " h.dispatchEvent(new MouseEvent('mousedown', {bubbles:true, composed:true}));"
         " const s = document.querySelector('#prose .phonetix');"
         " s.dispatchEvent(new MouseEvent('mouseout', {bubbles:true, composed:true, relatedTarget: document.body}));"
         " return 1; })()")
    time.sleep(1.0)  # longer than the 350ms hide timer
    still = d.js(VISIBLE)
    expect("pinned tooltip survives a selection drag", still is True, f"visible={still}")

    d.js("(() => { document.body.dispatchEvent(new MouseEvent('mousedown', {bubbles:true, composed:true})); return 1; })()")
    time.sleep(0.4)
    gone = d.js(VISIBLE)
    expect("press outside dismisses the tooltip", gone is False, f"visible={gone}")
    d.close_tab()

    # text the page hides must stay hidden in every display mode: our spans may
    # not override an ancestor's visibility.
    d.load(f"http://127.0.0.1:{PORT}/hidden.html", settle=3)
    d.poll("document.querySelectorAll('#prose .phonetix').length", ok=lambda v: bool(v) and v > 0)
    HIDDEN = ("(() => { const s = document.querySelector('#secret .phonetix .px-orig')"
              "  || document.querySelector('#secret .phonetix');"
              " if (!s) return 'no-span';"
              " return getComputedStyle(s).visibility; })()")
    for mode in ("px-mode-hover", "px-mode-reveal"):
        d.js(f"(() => {{ const h = document.documentElement;"
             f" h.classList.remove('px-mode-hover','px-mode-reveal');"
             f" h.classList.add('{mode}'); return 1; }})()")
        vis = d.js(HIDDEN)
        expect(f"hidden panel stays hidden ({mode})", vis == "hidden", f"visibility={vis}")
    d.close_tab()

    # dynamically-added titles (feed) get processed and languaged correctly
    d.load(f"http://127.0.0.1:{PORT}/dynamic.html", settle=3)
    dyn = d.poll(f"{SPANS_BY}('div.t')",
                 ok=lambda r: bool(r) and len(r) == 4 and all(x["n"] > 0 for x in r)) or []
    right = sum(1 for r in dyn if r["lang"] == r["exp"])
    expect("dynamic feed: all titles added + languaged", len(dyn) == 4 and right == 4,
           f"{right}/{len(dyn)} " + str([(r['exp'], r['lang']) for r in dyn]))
    d.close_tab()


JUNK_PROBE = """(() => {
  const bad = [];
  for (const s of document.querySelectorAll('.phonetix .px-ipa')) {
    const t = (s.textContent || '').trim();
    if (/[,;~\\/()\\[\\]]/.test(t)) bad.push(t);
  }
  return JSON.stringify({n: document.querySelectorAll('.phonetix .px-ipa').length, bad: bad.slice(0, 5)});
})()"""


def no_variant_junk(d, label):
    """A rendered IPA may never carry variant punctuation: dictionary entries list
       alternatives ("the" -> "ðə, ði") and mark optional sounds, and those must be
       reduced to one pronunciation before they reach the page."""
    r = json.loads(d.js(JUNK_PROBE) or '{"n": 0, "bad": ["probe failed"]}')
    expect(f"{label}: one pronunciation per word", r["n"] > 0 and not r["bad"],
           f"n={r['n']} bad={r['bad']}")


def e2e(d):
    print("[e2e]")
    cases = [
        ("https://de.wikipedia.org/wiki/Deutschland", "de"),
        ("https://ar.wikipedia.org/wiki/%D9%81%D9%8A%D8%B2%D9%8A%D8%A7%D8%A1", "ar"),
        ("https://ja.wikipedia.org/wiki/%E6%97%A5%E6%9C%AC", "ja"),
    ]
    PROBE = """(() => {
      let space=0,n=0; for (const s of document.querySelectorAll('.phonetix')) {
        n++; if ((s.dataset.src==='espeak') && (s.querySelector('.px-ipa')?.textContent||'').trim().includes(' ')) space++;
      } return {n, space};
    })()"""
    for url, lang in cases:
        d.load(url, settle=6)
        r = {"n": 0, "space": 0}
        for _ in range(12):  # poll: cold start loads ngram + espeak + dicts
            r = d.js(PROBE)
            if r and r["n"] > 50: break
            time.sleep(2)
        expect(f"e2e {lang}: has spans", r and r["n"] > 50, str(r))
        expect(f"e2e {lang}: no letter-name garbage", r and r["space"] == 0, str(r))
        d.close_tab()


def main():
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    serve(PORT)
    d = Driver()
    try:
        if which in ("integration", "all"): integration(d)
        if which in ("e2e", "all"): e2e(d)
    finally:
        d.close()
    print(f"\n{len(PASS)} passed, {len(FAIL)} failed")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
