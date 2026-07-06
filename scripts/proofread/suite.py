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
    d.load(f"http://127.0.0.1:{PORT}/mixed.html?pxhealth=1")
    h = d.js("document.documentElement.dataset.pxhealth||'{}'")
    health = json.loads(h)
    expect("health.eld (language detection alive)", health.get("eld") is True, str(health.get("errors")))
    expect("health.dict (dictionary loaded)", health.get("dict") is True, str(health.get("errors")))
    expect("health.espeak (espeak alive)", health.get("espeak") is True, str(health.get("errors")))
    # per-title language
    rows = d.js(f"{SPANS_BY}('div.t')")
    right = sum(1 for r in rows if r["lang"] == r["exp"])
    for r in rows:
        expect(f"lang {r['exp']}: '{'' }'", r["lang"] == r["exp"], f"got={r['lang']} langs={r['langs']}")
    expect("mixed-language >=80%", right >= 0.8 * len(rows), f"{right}/{len(rows)}")
    d.close_tab()

    # garbage gating: no espeak span may be letter-spelling (contain a space)
    d.load(f"http://127.0.0.1:{PORT}/garbage.html")
    g = d.js(f"{SPANS_BY}('main')")[0]
    expect("no letter-name garbage", g["space"] == 0, f"space-spans={g['space']} srcs={g['srcs']}")
    d.close_tab()

    # non-Latin actually translates
    d.load(f"http://127.0.0.1:{PORT}/nonlatin.html")
    nl = d.js(f"{SPANS_BY}('main')")[0]
    expect("non-Latin translated (ru)", nl["n"] > 5 and nl["lang"] == "ru", f"n={nl['n']} lang={nl['lang']}")
    d.close_tab()

    # dynamically-added titles (feed) get processed and languaged correctly
    d.load(f"http://127.0.0.1:{PORT}/dynamic.html", settle=6)
    time.sleep(6)  # titles append at 2s, observer debounces + processes
    dyn = d.js(f"{SPANS_BY}('div.t')")
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
