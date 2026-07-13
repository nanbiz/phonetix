#!/usr/bin/env python3
"""
Proofreading harness: drive the built chrome-mv3 extension over real pages and
extract every rendered IPA span + a screenshot, for structured analysis.

NixOS notes: Playwright's pip binaries break (libstdc++), and a chromium
--remote-debugging-*port* gets killed by the sandbox. So this speaks CDP over
--remote-debugging-*pipe* (fd 3/4, no listening socket) to the nix chromium.

Run (needs sandbox disabled for chromium + pure-python websocket not required):
  uv run python scripts/proofread/harness.py --corpus scripts/proofread/corpus.txt
"""
import argparse, base64, json, os, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
EXT = os.path.join(ROOT, ".output", "chrome-mv3")
OUT = os.path.join(ROOT, "scripts", "proofread", "out")

EXTRACT_JS = r"""
(() => {
  const spans = Array.from(document.querySelectorAll('.phonetix')).map(s => ({
    o: s.dataset.original || '',
    ipa: (s.querySelector('.px-ipa')?.textContent) || '',
    lang: s.dataset.lang || '',
    src: s.dataset.src || '',
  }));
  return { url: location.href, htmlLang: document.documentElement.lang || '',
           title: document.title, spanCount: spans.length, spans: spans.slice(0, 2500) };
})()
"""


class PipeCDP:
    """Minimal CDP client over chromium --remote-debugging-pipe (fd 3 read, 4 write)."""

    def __init__(self, extra_args=None):
        self.tc_r, self.tc_w = os.pipe()   # parent -> chrome (chrome fd 3)
        self.fc_r, self.fc_w = os.pipe()   # chrome -> parent (chrome fd 4)
        for fd in (self.tc_r, self.fc_w):
            os.set_inheritable(fd, True)
        args = [
            os.environ.get("PHONETIX_CHROMIUM", "chromium"),
            "--headless=new", "--no-sandbox", "--disable-gpu",
            "--disable-dev-shm-usage", "--remote-debugging-pipe",
            "--no-first-run", "--no-default-browser-check",
            "--window-size=1280,2000", "--force-device-scale-factor=1",
            # Recent Chrome ignores --load-extension unless this kill switch is off.
            "--disable-features=DisableLoadExtensionCommandLineSwitch",
            f"--load-extension={EXT}", f"--disable-extensions-except={EXT}",
        ] + (extra_args or []) + ["about:blank"]
        self.proc = subprocess.Popen(
            args, close_fds=False, preexec_fn=self._dup,
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        os.close(self.tc_r); os.close(self.fc_w)
        self.buf = b""
        self._id = 0

    def _dup(self):
        os.dup2(self.tc_r, 3)
        os.dup2(self.fc_w, 4)
        os.set_inheritable(3, True)
        os.set_inheritable(4, True)

    def _send_raw(self, obj):
        os.write(self.tc_w, json.dumps(obj).encode() + b"\0")

    def send(self, method, params=None, session=None, timeout=30):
        self._id += 1
        mid = self._id
        msg = {"id": mid, "method": method, "params": params or {}}
        if session:
            msg["sessionId"] = session
        self._send_raw(msg)
        deadline = time.time() + timeout
        while time.time() < deadline:
            while b"\0" not in self.buf:
                chunk = os.read(self.fc_r, 1 << 20)
                if not chunk:
                    raise RuntimeError("cdp pipe closed")
                self.buf += chunk
            raw, self.buf = self.buf.split(b"\0", 1)
            m = json.loads(raw)
            if m.get("id") == mid:
                if "error" in m:
                    raise RuntimeError(f"{method}: {m['error']}")
                return m.get("result", {})
            # else: event or other id — drop
        raise TimeoutError(method)

    def ensure_extension(self) -> str:
        """Make sure the extension is actually loaded, and return its id.

        `--load-extension` is being removed from Chrome, so fall back to the CDP
        Extensions domain. Raises if it could not be loaded — a suite that runs
        against a browser with no extension would pass vacuously.
        """
        for _ in range(15):
            for t in self.send("Target.getTargets")["targetInfos"]:
                if t["url"].startswith("chrome-extension://"):
                    return t["url"].split("/")[2]
            time.sleep(1)
        try:
            self.send("Extensions.loadUnpacked", {"path": EXT})
        except Exception as e:
            raise RuntimeError(f"extension not loaded and Extensions.loadUnpacked failed: {e}")
        for _ in range(15):
            for t in self.send("Target.getTargets")["targetInfos"]:
                if t["url"].startswith("chrome-extension://"):
                    return t["url"].split("/")[2]
            time.sleep(1)
        raise RuntimeError("extension did not load")

    def close(self):
        try:
            self.proc.terminate(); self.proc.wait(timeout=5)
        except Exception:
            self.proc.kill()


def visit(cdp, url, settle=8.0, shot=True):
    tgt = cdp.send("Target.createTarget", {"url": "about:blank"})["targetId"]
    sess = cdp.send("Target.attachToTarget", {"targetId": tgt, "flatten": True})["sessionId"]
    cdp.send("Page.enable", session=sess)
    cdp.send("Runtime.enable", session=sess)
    out = {"url": url}
    try:
        cdp.send("Page.navigate", {"url": url}, session=sess, timeout=45)
    except Exception as e:
        out["error"] = f"navigate: {e}"
        cdp.send("Target.closeTarget", {"targetId": tgt})
        return out
    time.sleep(settle)
    data = None
    for _ in range(10):
        try:
            r = cdp.send("Runtime.evaluate",
                         {"expression": EXTRACT_JS, "returnByValue": True, "awaitPromise": True},
                         session=sess, timeout=30)
            data = r.get("result", {}).get("value")
            if data and data.get("spanCount", 0) > 0:
                break
        except Exception as e:
            data = {"url": url, "error": f"evaluate: {e}"}
        time.sleep(1.5)
    if data:
        out.update(data)
    if shot and not out.get("error"):
        try:
            img = cdp.send("Page.captureScreenshot", {"format": "png", "captureBeyondViewport": True},
                           session=sess, timeout=30)["data"]
            safe = url.split("://")[-1].replace("/", "_")[:80]
            with open(os.path.join(OUT, f"{safe}.png"), "wb") as f:
                f.write(base64.b64decode(img))
        except Exception:
            pass
    cdp.send("Target.closeTarget", {"targetId": tgt})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("urls", nargs="*")
    ap.add_argument("--corpus")
    ap.add_argument("--settle", type=float, default=8.0)
    ap.add_argument("--out", default="results.jsonl")
    ap.add_argument("--no-shots", action="store_true")
    ap.add_argument("--resume", action="store_true")
    args = ap.parse_args()

    urls = list(args.urls)
    if args.corpus:
        with open(args.corpus) as f:
            urls += [l.strip() for l in f if l.strip() and not l.startswith("#")]
    if not urls:
        print("no urls"); sys.exit(1)

    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, args.out)
    # Skip URLs already captured (resume across foreground batches).
    done = set()
    if os.path.exists(path) and args.resume:
        for l in open(path):
            try: done.add(json.loads(l)["url"])
            except Exception: pass
    fout = open(path, "a" if args.resume else "w", buffering=1)
    cdp = PipeCDP()
    cdp.send("Target.setDiscoverTargets", {"discover": True})
    n = 0
    try:
        for i, url in enumerate(urls, 1):
            if url in done:
                print(f"[{i}/{len(urls)}] skip (done)  {url}", flush=True)
                continue
            t0 = time.time()
            try:
                r = visit(cdp, url, settle=args.settle, shot=not args.no_shots)
            except Exception as e:
                r = {"url": url, "error": str(e)}
            fout.write(json.dumps(r, ensure_ascii=False) + "\n")  # stream: survives timeout
            n += 1
            print(f"[{i}/{len(urls)}] spans={r.get('spanCount',0):4d} "
                  f"lang={r.get('htmlLang','?'):5s} {int(time.time()-t0)}s  {url}  {r.get('error','')}",
                  flush=True)
    finally:
        cdp.close()
        fout.close()
    print(f"\nappended {n} pages to {path}")


if __name__ == "__main__":
    main()
