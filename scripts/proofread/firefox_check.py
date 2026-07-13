#!/usr/bin/env python3
"""Verify the *Firefox* build's subsystems (esp. espeak, which runs in the
   background page there, not the offscreen doc). Drives real Firefox headless
   via Marionette and reads the getHealth probe.
   Run: uv run --with marionette_driver python scripts/proofread/firefox_check.py"""
import os, subprocess, tempfile, time, threading, http.server, functools, glob, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
XPI = sorted(glob.glob(os.path.join(ROOT, ".output/signed/*.xpi")))[-1]
FIREFOX = os.environ.get("PHONETIX_FIREFOX", "firefox")
HEALTH_HTML = b"<!doctype html><html lang='en'><meta charset='utf-8'><body><p>This is an English sentence so detection has something to work with.</p></body></html>"

def serve(port):
    class H(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def do_GET(self):
            self.send_response(200); self.send_header("Content-Type", "text/html"); self.end_headers()
            self.wfile.write(HEALTH_HTML)
    httpd = http.server.HTTPServer(("127.0.0.1", port), H); httpd.timeout = 0.5
    threading.Thread(target=httpd.serve_forever, daemon=True).start()

def main():
    port = 8912; serve(port)
    prof = tempfile.mkdtemp(prefix="ff-phonetix-")
    os.makedirs(os.path.join(prof, "extensions"), exist_ok=True)
    # Sideload the signed xpi. Firefox requires the file to be named after the
    # add-on id, so read it out of the manifest rather than assuming it.
    import shutil, zipfile, json as _json
    gecko_id = _json.loads(zipfile.ZipFile(XPI).read("manifest.json"))[
        "browser_specific_settings"]["gecko"]["id"]
    shutil.copy(XPI, os.path.join(prof, "extensions", f"{gecko_id}.xpi"))
    with open(os.path.join(prof, "user.js"), "w") as f:
        f.write('user_pref("extensions.autoDisableScopes", 0);\n')
        f.write('user_pref("marionette.port", 2828);\n')
        f.write('user_pref("xpinstall.signatures.required", false);\n')

    proc = subprocess.Popen(
        [FIREFOX, "--headless", "--marionette", "--profile", prof, "--no-remote"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        from marionette_driver.marionette import Marionette
        client = None
        for _ in range(40):
            try:
                client = Marionette(host="127.0.0.1", port=2828); client.start_session(); break
            except Exception:
                time.sleep(1)
        if not client:
            print("could not connect to marionette"); return 2
        client.timeout.page_load = 30
        client.navigate(f"http://127.0.0.1:{port}/?pxhealth=1")
        health = None
        for _ in range(20):
            health = client.execute_script("return document.documentElement.dataset.pxhealth || null;")
            if health: break
            time.sleep(1.5)
        print("firefox health:", health)
        client.delete_session()
        return 0 if (health and '"espeak":true' in health and '"eld":true' in health and '"dict":true' in health) else 1
    finally:
        proc.terminate()
        try: proc.wait(timeout=5)
        except Exception: proc.kill()

if __name__ == "__main__":
    sys.exit(main())
