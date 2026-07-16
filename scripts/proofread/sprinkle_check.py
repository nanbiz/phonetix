#!/usr/bin/env python3
"""Sprinkle mode transcribes a sparse, stable, dictionary-only subset of the words.

Sprinkle differs from the hover modes in what it transcribes, not how it shows it:
only dictionary-backed words (never espeak guesses), and only a 1-in-N fraction of
those, chosen by a stable hash so the same words are picked every render. This loads
a dense English page, and checks: every sprinkled word is dict-sourced, the fraction
tracks the density setting, the choice is identical across a reload (no flicker), and
raising the density transcribes fewer words.

  PHONETIX_CHROMIUM=<chrome> uv run python scripts/proofread/sprinkle_check.py
"""
import http.server
import json
import sys
import threading

from browser import open_browser

# A long, ordinary English paragraph — enough words that a fraction is meaningful.
PARA = (
    "The quiet morning light fell across the wooden table where an old book lay open. "
    "She read each careful sentence slowly, tracing the printed words with one finger, "
    "and wondered how many people had held this very copy before her over the years. "
    "Outside the window the garden was still wet from the rain, and a single bird sang "
    "from the tall hedge that divided the two quiet houses at the end of the street. "
) * 4
PAGE = ("<!doctype html><html lang=en><meta charset=utf-8>"
        "<body style='font:18px/1.7 system-ui;padding:24px'><p>" + PARA + "</p></body></html>").encode()

PORT = 8983

COUNT = """(() => {
  const spans = [...document.querySelectorAll('.phonetix')];
  const words = spans.map(s => s.dataset.original);
  const srcs = spans.map(s => s.dataset.src);
  // Rough count of eligible running words: every word-like token on the page.
  const text = document.body.innerText;
  const total = (text.match(/[A-Za-z]{2,}/g) || []).length;
  return JSON.stringify({n: spans.length, total, words, allDict: srcs.every(x => x === 'dict')});
})()"""


def serve():
    class H(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Length", str(len(PAGE)))
            self.end_headers()
            self.wfile.write(PAGE)

    httpd = http.server.HTTPServer(("127.0.0.1", PORT), H)
    httpd.timeout = 0.5
    threading.Thread(target=httpd.serve_forever, daemon=True).start()


def load(d, density):
    d.set_setting({"local:selectedMode": "sprinkle", "local:sprinkleDensity": str(density)})
    d.navigate(f"http://127.0.0.1:{PORT}/")
    d.wait_spans()
    return json.loads(d.eval(COUNT))


def main():
    serve()
    kind = sys.argv[1] if len(sys.argv) > 1 else "chrome"
    # Sprinkle is driven by a stored setting, and writing storage on Firefox needs the
    # popup page, which only loads for a signed add-on — not the unsigned build under
    # test. The sprinkle logic is shared, engine-independent content-script code, so
    # Chrome exercises it fully; on Firefox the manifest's web-accessible-resource entry
    # and the bundled list are what make the fetch work, and those are checked at build.
    if kind == "firefox":
        print("sprinkle: skipped on Firefox (setting-driven; needs the signed popup)")
        return
    print(f"sprinkle under {kind}:")
    d = open_browser(kind)
    failures = []

    # The most common English words — sprinkle must never mark these.
    COMMON = {"the", "to", "and", "of", "a", "in", "is", "for", "that", "was",
              "it", "on", "with", "this", "her", "she", "an", "at", "from", "how"}

    a = load(d, 4)
    # Running words = the ones we transcribed plus the ASCII ones we left as text.
    running = a["n"] + a["total"]
    frac = a["n"] / running if running else 0
    leaked = sorted(set(w.lower() for w in a["words"]) & COMMON)
    print(f"  density 4:  {a['n']}/{running} words = {frac*100:.0f}%  allDict={a['allDict']}")
    if not a["allDict"]:
        failures.append("a sprinkled word was not dictionary-sourced")
    if leaked:
        failures.append(f"common words were sprinkled: {leaked}")
    # A sparse minority of the running words, not most of them.
    if not (0.02 < frac < 0.4):
        failures.append(f"density 4 fraction {frac:.2f} outside the expected sparse range")

    # Stable across a reload: the same words, not a fresh random subset.
    b = load(d, 4)
    same = a["words"] == b["words"]
    print(f"  reload same words: {same} ({len(a['words'])} vs {len(b['words'])})")
    if not same:
        failures.append("the sprinkled set changed across a reload (should be stable)")

    # Sparser at higher density.
    c = load(d, 20)
    print(f"  density 20: {c['n']} words  (was {a['n']} at density 4)")
    if not (c["n"] < a["n"]):
        failures.append(f"density 20 ({c['n']}) not sparser than density 4 ({a['n']})")

    print(f"  common words skipped: {'yes' if not leaked else 'NO — ' + str(leaked)}")

    d.close()
    if failures:
        print(f"\nFAIL - {len(failures)} problem(s):")
        for f in failures:
            print("   ", f)
        sys.exit(1)
    print("\nPASS - sprinkle is dictionary-only, sparse, stable, and density-controlled")


if __name__ == "__main__":
    main()
