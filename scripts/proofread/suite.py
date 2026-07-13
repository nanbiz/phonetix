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
    for mode in ("px-mode-whole", "px-mode-hover", "px-mode-reveal"):
        d.js(f"(() => {{ const h = document.documentElement;"
             f" h.classList.remove('px-mode-whole','px-mode-hover','px-mode-reveal');"
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
