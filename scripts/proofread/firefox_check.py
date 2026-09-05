#!/usr/bin/env python3
"""Verify the *Firefox* build's subsystems (esp. espeak, which runs in the
   background page there, not the offscreen doc). Drives real Firefox headless
   via Marionette and reads the getHealth probe.
   Run: uv run --with marionette_driver python scripts/proofread/firefox_check.py"""
import os, subprocess, tempfile, time, threading, http.server, functools, glob, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
XPI = os.environ.get("PHONETIX_XPI") or sorted(
    glob.glob(os.path.join(ROOT, ".output/signed/*.xpi")),
    key=os.path.getmtime,
)[-1]
# A fixed internal id, so the extension's own pages can be opened by URL. Firefox
# otherwise assigns a random one per profile.
EXT_UUID = "8f2b9a41-5c3d-4e7a-9b16-2d7f0c4e51aa"
FIREFOX = os.environ.get("PHONETIX_FIREFOX", "firefox")
HEALTH_HTML = (
    b"<!doctype html><html lang='en'><meta charset='utf-8'><body><p>"
    b"They dance in the bath and drive a car on a tight schedule, which is an "
    b"English sentence so detection has something to work with.</p></body></html>"
)

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
    # A signed build is sideloaded from the profile. An unsigned one (a local dev
    # build) cannot be: release Firefox refuses it whatever the signature pref
    # says, and installs it only as a temporary add-on, which Marionette can do
    # once the session is up.
    signed = "META-INF/mozilla.rsa" in zipfile.ZipFile(XPI).namelist()
    if signed:
        shutil.copy(XPI, os.path.join(prof, "extensions", f"{gecko_id}.xpi"))
    print(f"{'signed' if signed else 'unsigned'}: {os.path.basename(XPI)}")
    with open(os.path.join(prof, "user.js"), "w") as f:
        f.write('user_pref("extensions.autoDisableScopes", 0);\n')
        f.write('user_pref("marionette.port", 2828);\n')
        f.write('user_pref("xpinstall.signatures.required", false);\n')
        f.write('user_pref("extensions.webextensions.uuids", "{\\"%s\\": \\"%s\\"}");\n'
                % (gecko_id, EXT_UUID))

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
        if not signed:
            from marionette_driver.addons import Addons
            Addons(client).install(os.path.abspath(XPI), temp=True)
            time.sleep(3)
        client.navigate(f"http://127.0.0.1:{port}/?pxhealth=1")
        health = None
        for _ in range(20):
            health = client.execute_script("return document.documentElement.dataset.pxhealth || null;")
            if health: break
            time.sleep(1.5)
        print("firefox health:", health)
        ok = bool(health) and all(f'"{k}":true' in health for k in ("espeak", "eld", "dict"))

        # Switching the accent must change the page, on Firefox too. The Chrome
        # suite cannot see a Firefox-only break in this path (its messaging and
        # storage APIs differ), and a dead accent switch looks exactly like a
        # working one until the words are compared.
        def ipa_of(word):
            return client.execute_script(
                "const w = arguments[0];"
                "for (const s of document.querySelectorAll('.phonetix'))"
                "  if ((s.dataset.original || '').toLowerCase() === w) return s.dataset.ipa;"
                "return null;",
                script_args=(word,),
            )

        before = {}
        for _ in range(20):
            before = {w: ipa_of(w) for w in ("dance", "bath", "car")}
            if any(before.values()):
                break
            time.sleep(1.5)
        if not any(before.values()):
            # What the page actually holds, so a run that transcribed nothing says why
            # rather than reporting that the accent did nothing.
            print("firefox page:", client.execute_script(
                "return JSON.stringify({"
                "  marked: document.querySelectorAll('.phonetix').length,"
                "  stage: document.documentElement.dataset.pxstage || null,"
                "  host: location.hostname,"
                "  text: document.body.innerText.slice(0, 120),"
                "});"))

        # The accent is chosen the way the popup chooses it - the same storage key, the
        # same value - from a second tab served by this machine. Marionette in current
        # Firefox cannot run scripts inside the popup at all (an extension page is a
        # privileged context), so the popup's button is exercised in the Chrome suite and
        # what is checked here is the Firefox half that broke before: that a changed
        # setting reaches the pages already open.
        page = client.current_window_handle
        client.execute_script("window.open(location.pathname + '?pxaccent=en:en-us', '_blank');")
        chooser = None
        for _ in range(20):
            time.sleep(0.5)
            others = [h for h in client.window_handles if h != page]
            if others:
                chooser = others[-1]
                break
        if chooser is None:
            print("FAIL - the accent tab did not open"); return 1
        client.switch_to_window(chooser)
        for _ in range(20):
            if client.execute_script("return document.documentElement.dataset.pxaccent || null;"):
                break
            time.sleep(0.5)
        print("firefox accent chosen:", client.execute_script("return document.documentElement.dataset.pxaccent || null;"))
        client.switch_to_window(page)
        after = {}
        for _ in range(20):
            time.sleep(1.5)
            after = {w: ipa_of(w) for w in ("dance", "bath", "car")}
            if any(after.values()) and after != before:
                break

        changed = [w for w in before if before[w] and after.get(w) and before[w] != after[w]]
        print(f"firefox accent: before={before} after={after}")
        err = client.execute_script("return document.documentElement.dataset.pxerror || null;")
        if err:
            print(f"firefox reprocess error: {err}")
        if changed:
            print(f"PASS - switching to en-us changed {', '.join(changed)}")
        else:
            print("FAIL - switching the accent changed nothing on the page")
            ok = False

        client.delete_session()
        return 0 if ok else 1
    finally:
        proc.terminate()
        try: proc.wait(timeout=5)
        except Exception: proc.kill()

if __name__ == "__main__":
    sys.exit(main())
