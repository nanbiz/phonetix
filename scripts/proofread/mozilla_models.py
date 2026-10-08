#!/usr/bin/env python3
"""Chrome fetching a translation model from Mozilla, the way the extension does.

Mozilla's server for the models refuses a request that names Chrome as its browser (406) and
serves the same file to Firefox. The Chromium build carries one declarativeNetRequest rule
that names Firefox to that server alone; without it every Chromium browser had no
translation at all. This asks the extension's own service worker, in a real headless Chrome,
for the first bytes of a model the published listing names, and wants them.

  uv run python scripts/proofread/mozilla_models.py
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import PipeCDP  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LISTING = os.path.join(ROOT, ".cache", "release", "models.json")


def first_model_url():
    """A model file the listing names on Mozilla's server."""
    with open(LISTING) as file:
        listing = json.load(file)
    for model in listing:
        for entry in model["files"].values():
            if "cdn.mozilla.net" in entry.get("url", ""):
                return entry["url"]
    raise SystemExit(f"no model on Mozilla's server in {LISTING}")


def main():
    url = first_model_url()
    cdp = PipeCDP()
    try:
        extid = cdp.ensure_extension()
        worker = next(
            t for t in cdp.send("Target.getTargets")["targetInfos"]
            if t["type"] == "service_worker" and t["url"].startswith(f"chrome-extension://{extid}/")
        )
        session = cdp.send("Target.attachToTarget",
                           {"targetId": worker["targetId"], "flatten": True})["sessionId"]
        said = cdp.send("Runtime.evaluate", {
            "expression": f"""
                fetch({json.dumps(url)}, {{ headers: {{ Range: 'bytes=0-99' }} }})
                  .then(r => r.arrayBuffer().then(b => JSON.stringify([r.status, b.byteLength])))
                  .catch(e => JSON.stringify([0, String(e)]))
            """,
            "awaitPromise": True,
            "returnByValue": True,
        }, session=session, timeout=60)
        status, got = json.loads(said["result"]["value"])
    finally:
        cdp.close()
    print(f"  {url}: {status}, {got}")
    if status not in (200, 206) or not isinstance(got, int) or got == 0:
        print("\nFAIL - Chrome could not fetch a translation model from Mozilla")
        sys.exit(1)
    print("\nPASS - Chrome fetches the translation models from Mozilla")


if __name__ == "__main__":
    main()
