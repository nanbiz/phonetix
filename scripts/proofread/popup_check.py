#!/usr/bin/env python3
"""The popup must be readable, not merely present.

Nine English accents once shipped as a row of buttons reading "S... B... A...":
every assertion about the popup passed, because none of them looked at whether a
control could actually show its own label. This renders the popup and fails when
text is clipped, when the page scrolls sideways, or when a control has collapsed
below a legible width.

Run: uv run python scripts/proofread/popup_check.py
"""
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP

# Width below which a control cannot show a word, only an ellipsis.
MIN_CONTROL_WIDTH = 44

PROBE = """(() => {
  const clipped = [];
  for (const el of document.querySelectorAll('button, select, option, h1, h2, p, span, label')) {
    const text = (el.textContent || '').trim();
    if (!text) continue;
    const style = getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden') continue;

    // Text the element is too small to show: the browser truncates or overflows it.
    const overflows = el.scrollWidth > el.clientWidth + 1 && el.clientWidth > 0;
    const tiny = el.tagName === 'BUTTON' && el.clientWidth > 0 && el.clientWidth < %d;
    if (overflows || tiny) {
      clipped.push({tag: el.tagName, text: text.slice(0, 30), width: el.clientWidth, needs: el.scrollWidth});
    }
  }
  // Content taller than the popup must live in something that scrolls, or its
  // bottom is simply unreachable.
  const unreachable = [];
  const root = document.documentElement;
  if (root.scrollHeight > root.clientHeight + 1 && getComputedStyle(document.body).overflowY === 'hidden') {
    unreachable.push('the page is taller than the popup but does not scroll');
  }
  for (const el of document.querySelectorAll('main, main > div')) {
    const overflowsDown = el.scrollHeight > el.clientHeight + 1;
    const scrolls = ['auto', 'scroll'].includes(getComputedStyle(el).overflowY);
    if (overflowsDown && !scrolls) {
      unreachable.push(`${el.tagName}.${el.className.slice(0, 24)} clips ${el.scrollHeight - el.clientHeight}px it cannot scroll to`);
    }
  }

  return JSON.stringify({
    clipped,
    unreachable,
    bodyScrollsSideways: document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
    controls: document.querySelectorAll('button, select, input').length,
  });
})()""" % MIN_CONTROL_WIDTH


def main():
    cdp = PipeCDP()
    cdp.send("Target.setDiscoverTargets", {"discover": True})
    extid = cdp.ensure_extension()

    tid = cdp.send("Target.createTarget", {"url": f"chrome-extension://{extid}/popup.html"})["targetId"]
    s = cdp.send("Target.attachToTarget", {"targetId": tid, "flatten": True})["sessionId"]
    cdp.send("Page.enable", session=s)
    cdp.send("Runtime.enable", session=s)
    time.sleep(4)

    raw = cdp.send("Runtime.evaluate",
                   {"expression": PROBE, "returnByValue": True},
                   session=s)["result"].get("value")
    cdp.send("Target.closeTarget", {"targetId": tid})
    cdp.close()

    if not raw:
        print("FAIL - the popup did not render")
        sys.exit(1)

    r = json.loads(raw)
    failures = []

    if r["controls"] < 4:
        failures.append(f"only {r['controls']} controls rendered; the popup is not built")
    if r["bodyScrollsSideways"]:
        failures.append("the popup scrolls sideways")
    for c in r["clipped"]:
        failures.append(f"{c['tag']} {c['text']!r} is {c['width']}px wide but needs {c['needs']}px")
    failures.extend(r.get("unreachable", []))

    print(f"{r['controls']} controls rendered")
    if failures:
        print(f"\nFAIL - {len(failures)} element(s) cannot show their own text:")
        for f in failures:
            print("   ", f)
        sys.exit(1)
    print("PASS - every control shows its label, nothing is clipped")


if __name__ == "__main__":
    main()
