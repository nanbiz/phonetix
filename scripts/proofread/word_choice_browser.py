#!/usr/bin/env python3
"""Word choice measured through the extension itself, with the engine translating each line.

word_choice.py hands the core people's own translations, which is the most a card could know;
this asks the extension's host for each word the way a card does, with the sentence for its
engine to translate, so what is measured is what a reader gets. Every word of a sentence is
asked, the little ones too, so its shares run below word_choice.py's.

  uv run python scripts/proofread/word_choice_browser.py spa es 100
"""
import os
import sys, time, json, threading, http.server, functools  # noqa: E401
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP
from on_a_page import evaluate
from word_choice import agrees, pairs_for
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REL = os.path.join(ROOT, ".cache", "release")
lang, into, n = sys.argv[1], sys.argv[2], int(sys.argv[3])
pairs = pairs_for(lang)[:n]
class H(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *a): pass
srv = http.server.ThreadingHTTPServer(("127.0.0.1", 8945), functools.partial(H, directory=REL))
threading.Thread(target=srv.serve_forever, daemon=True).start()
cdp = PipeCDP(); cdp.send("Target.setDiscoverTargets", {"discover": True})
ext = cdp.ensure_extension()
t = cdp.send("Target.createTarget", {"url": f"chrome-extension://{ext}/viewbook.html"})
s = cdp.send("Target.attachToTarget", {"targetId": t["targetId"], "flatten": True})["sessionId"]
time.sleep(2)
evaluate(cdp, s, "chrome.storage.local.set({packBaseUrl:'http://127.0.0.1:8945'}).then(()=>1)")
JS = """(async () => {
  const pairs = %s; const out = [];
  const ask = (data) => chrome.runtime.sendMessage({phonetix: 'lookUp', data});
  await ask({word: 'warm', source: 'en', target: '%s', line: 'A warm day.'}).catch(() => null);
  for (let at = 0; at < pairs.length; at++) {
    const words = pairs[at][0].split(/[^\\p{L}\\p{N}'-]+/u).filter(Boolean);
    for (let i = 0; i < words.length; i++) {
      const got = await ask({word: words[i], source: 'en', target: '%s', before: words[i-1] || '', line: %s ? pairs[at][0] : ''}).catch(e => ({error: String(e)}));
      const r = got && (got.ok !== undefined ? got.ok : got);
      out.push({at, word: words[i], lead: (r && (r.lead || (r.says||[])[0])) || '', pos: r && r.pos});
    }
  }
  return JSON.stringify(out);
})()"""
for with_line in (False, True):
    t0 = time.time()
    got = evaluate(cdp, s, JS % (json.dumps(pairs), into, into, "true" if with_line else "false"))
    rows = json.loads(got)
    answered = [r for r in rows if r["lead"]]
    good = [r for r in answered if agrees(r["lead"], pairs[r["at"]][1])]
    print(f"{lang} {'machine line' if with_line else 'alone'}: {len(answered)} answered, {len(good)} agree ({100*len(good)/max(1,len(answered)):.1f}%) in {time.time()-t0:.0f}s")
cdp.close()
