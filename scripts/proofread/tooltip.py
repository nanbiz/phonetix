#!/usr/bin/env python3
"""Hover words and screenshot the tooltip, to visually verify tooltip coherence.
   uv run python scripts/proofread/tooltip.py"""
import base64, json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP, OUT

# (url, word-to-hover) — pick words that exercise the fixes.
CASES = [
    ("https://en.wikipedia.org/wiki/Cat", "content"),   # espeak/homograph → source tag
    ("https://de.wikipedia.org/wiki/Deutschland", "Software"),  # English word on DE page → cross-dict, lang EN
    ("https://en.wikipedia.org/wiki/Bass_(sound)", "bass"),     # homograph
]

FIND_SPAN = """
(word => {
  const spans = [...document.querySelectorAll('.phonetix')];
  const t = spans.find(s => (s.dataset.original||'').toLowerCase() === word.toLowerCase()) || spans[0];
  if (!t) return null;
  t.scrollIntoView({block:'center'});
  const r = t.getBoundingClientRect();
  return { x: r.left + r.width/2, y: r.top + r.height/2,
           original: t.dataset.original, ipa: t.querySelector('.px-ipa')?.textContent,
           lang: t.dataset.lang, src: t.dataset.src };
})
"""

def hover_shot(cdp, url, word):
    tgt = cdp.send("Target.createTarget", {"url": "about:blank"})["targetId"]
    sess = cdp.send("Target.attachToTarget", {"targetId": tgt, "flatten": True})["sessionId"]
    cdp.send("Page.enable", session=sess)
    cdp.send("Runtime.enable", session=sess)
    cdp.send("Input", session=sess) if False else None
    cdp.send("Page.navigate", {"url": url}, session=sess, timeout=45)
    time.sleep(9)
    info = None
    for _ in range(8):
        r = cdp.send("Runtime.evaluate",
                     {"expression": f"({FIND_SPAN})({json.dumps(word)})", "returnByValue": True},
                     session=sess)
        info = r.get("result", {}).get("value")
        if info: break
        time.sleep(1.5)
    if not info:
        print(f"  no span for {word} on {url}"); cdp.send("Target.closeTarget", {"targetId": tgt}); return
    x, y = info["x"], info["y"]
    # Fire a real mouseover on the span; the content script's document-level
    # listener then arms its 700ms hover timer and shows the tooltip.
    cdp.send("Runtime.evaluate", {"expression": f"""
      (() => {{
        const spans=[...document.querySelectorAll('.phonetix')];
        const t=spans.find(s=>(s.dataset.original||'').toLowerCase()==={json.dumps(word)}.toLowerCase())||spans[0];
        const r=t.getBoundingClientRect();
        t.dispatchEvent(new MouseEvent('mouseover',{{bubbles:true,clientX:r.left+r.width/2,clientY:r.top+r.height/2}}));
      }})()
    """}, session=sess)
    time.sleep(1.1)
    img = cdp.send("Page.captureScreenshot", {"format": "png", "clip": {
        "x": max(0, x - 240), "y": max(0, y - 260), "width": 480, "height": 300, "scale": 2}},
        session=sess)["data"]
    name = f"tooltip_{word}.png"
    with open(os.path.join(OUT, name), "wb") as f:
        f.write(base64.b64decode(img))
    print(f"  {word}: page ipa=/{info['ipa']}/ lang={info['lang']} src={info['src']} -> {name}")
    cdp.send("Target.closeTarget", {"targetId": tgt})

def main():
    os.makedirs(OUT, exist_ok=True)
    cdp = PipeCDP()
    cdp.send("Target.setDiscoverTargets", {"discover": True})
    try:
        for url, word in CASES:
            print(url)
            try: hover_shot(cdp, url, word)
            except Exception as e: print("  err", e)
    finally:
        cdp.close()

if __name__ == "__main__":
    main()
