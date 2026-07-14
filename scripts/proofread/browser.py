#!/usr/bin/env python3
"""One browser interface, two engines.

Chrome and Firefox are driven over different protocols (CDP over a pipe, and
Marionette), because nothing else works on this host: a CDP TCP port is killed by
the sandbox, and Playwright's binaries break on NixOS. That protocol difference is
the only thing that should differ between them, so it is hidden behind one Driver:
a test writes its logic against `navigate`, `eval`, `hover`, `set_setting` once and
runs it on either engine by name.
"""
import glob
import json
import os
import shutil
import subprocess
import tempfile
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))


class Driver:
    """A loaded extension in a running browser. Subclasses supply the protocol."""

    def navigate(self, url):
        raise NotImplementedError

    def eval(self, expr):
        """Evaluate a JS expression in the page and return its value."""
        raise NotImplementedError

    def hover(self, x, y):
        """Put the mouse over (x, y) so :hover and the reveal fire, then settle."""
        raise NotImplementedError

    def set_setting(self, obj):
        """Write keys into the extension's storage (as the popup would)."""
        raise NotImplementedError

    def wait_spans(self, selector=".phonetix", tries=20, delay=1.5):
        for _ in range(tries):
            if self.eval(f"document.querySelectorAll('{selector}').length"):
                return True
            time.sleep(delay)
        return False

    def close(self):
        pass


class ChromeDriver(Driver):
    def __init__(self):
        import sys
        sys.path.insert(0, HERE)
        from harness import PipeCDP

        self.cdp = PipeCDP()
        self.cdp.send("Target.setDiscoverTargets", {"discover": True})
        self.extid = self.cdp.ensure_extension()
        self._sw = None
        self.s = None

    def _sw_session(self):
        if self._sw:
            return self._sw
        sw = next(t for t in self.cdp.send("Target.getTargets")["targetInfos"]
                  if t["type"] == "service_worker" and self.extid in t["url"])
        self._sw = self.cdp.send("Target.attachToTarget", {"targetId": sw["targetId"], "flatten": True})["sessionId"]
        self.cdp.send("Runtime.enable", session=self._sw)
        return self._sw

    def set_setting(self, obj):
        self.cdp.send("Runtime.evaluate",
                      {"expression": f"chrome.storage.local.set({json.dumps(obj)})",
                       "awaitPromise": True, "returnByValue": True},
                      session=self._sw_session())
        time.sleep(1)

    def navigate(self, url):
        tid = self.cdp.send("Target.createTarget", {"url": "about:blank"})["targetId"]
        self.s = self.cdp.send("Target.attachToTarget", {"targetId": tid, "flatten": True})["sessionId"]
        self.cdp.send("Page.enable", session=self.s)
        self.cdp.send("Runtime.enable", session=self.s)
        self.cdp.send("Page.navigate", {"url": url}, session=self.s, timeout=45)

    def eval(self, expr):
        r = self.cdp.send("Runtime.evaluate", {"expression": expr, "returnByValue": True}, session=self.s)
        return r.get("result", {}).get("value")

    def hover(self, x, y):
        self.cdp.send("Input.dispatchMouseEvent", {"type": "mouseMoved", "x": x, "y": y, "buttons": 0}, session=self.s)
        time.sleep(0.3)

    def close(self):
        self.cdp.close()


class FirefoxDriver(Driver):
    def __init__(self, port=2830):
        from marionette_driver.marionette import Marionette
        from marionette_driver.addons import Addons

        xpi = os.environ.get("PHONETIX_XPI") or sorted(
            glob.glob(os.path.join(ROOT, ".output/*firefox*.zip")), key=os.path.getmtime)[-1]
        firefox = os.environ.get("PHONETIX_FIREFOX", "firefox")
        signed = "META-INF/mozilla.rsa" in zipfile.ZipFile(xpi).namelist()
        gecko = json.loads(zipfile.ZipFile(xpi).read("manifest.json"))["browser_specific_settings"]["gecko"]["id"]

        # Pin the extension's internal uuid, so its own pages (popup.html) have a
        # reachable moz-extension:// URL. Firefox otherwise assigns a random one.
        self._cached_uuid = "8f2b9a41-5c3d-4e7a-9b16-2d7f0c4e51aa"

        prof = tempfile.mkdtemp(prefix="ff-phonetix-")
        os.makedirs(os.path.join(prof, "extensions"), exist_ok=True)
        if signed:
            shutil.copy(xpi, os.path.join(prof, "extensions", f"{gecko}.xpi"))
        with open(os.path.join(prof, "user.js"), "w") as f:
            f.write('user_pref("extensions.autoDisableScopes", 0);\n')
            f.write(f'user_pref("marionette.port", {port});\n')
            f.write('user_pref("xpinstall.signatures.required", false);\n')
            f.write('user_pref("extensions.webextensions.uuids", "{\\"%s\\": \\"%s\\"}");\n'
                    % (gecko, self._cached_uuid))

        self.proc = subprocess.Popen(
            [firefox, "--headless", "--marionette", "--profile", prof, "--no-remote"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.client = None
        for _ in range(40):
            try:
                self.client = Marionette(host="127.0.0.1", port=port)
                self.client.start_session()
                break
            except Exception:
                time.sleep(1)
        if not self.client:
            raise RuntimeError("could not connect to Marionette")
        if not signed:
            Addons(self.client).install(os.path.abspath(xpi), temp=True)
            time.sleep(3)
        self.client.timeout.page_load = 30
        self.extid = gecko

    def set_setting(self, obj):
        # Marionette's sandbox has no extension APIs, so the popup page (which does)
        # writes the storage. The popup keeps async work running, so its load event
        # never fires; the DOM is ready anyway, so a short page-load timeout is
        # expected and ignored.
        from marionette_driver import errors

        self.client.timeout.page_load = 6
        try:
            self.client.navigate(f"moz-extension://{self._cached_uuid}/popup.html")
        except errors.TimeoutException:
            pass
        self.client.timeout.page_load = 30
        time.sleep(0.5)
        self.client.execute_async_script(
            "const done = arguments[0];"
            "browser.storage.local.set(arguments[1]).then(() => done('ok')).catch(e => done('err'+e));",
            script_args=(obj,), script_timeout=10000)

    def navigate(self, url):
        self.client.navigate(url)

    def eval(self, expr):
        return self.client.execute_script(f"return ({expr});")

    def hover(self, x, y):
        # Marionette has no low-level mouse move; the reveal is driven by mouseover in
        # content.ts, so a real mouseover on the element under (x, y) exercises the same
        # path. Every phonetix span is cleared first so switching words is realistic.
        self.client.execute_script(
            "const el = document.elementFromPoint(arguments[0], arguments[1]);"
            "document.querySelectorAll('.phonetix').forEach(s => s.dispatchEvent("
            "  new MouseEvent('mouseout', {bubbles:true, composed:true, relatedTarget: document.body})));"
            "const sp = el && el.closest('.phonetix');"
            "if (sp) sp.dispatchEvent(new MouseEvent('mouseover', {bubbles:true, composed:true}));",
            script_args=(x, y))
        time.sleep(0.2)

    def close(self):
        try:
            self.client.delete_session()
        finally:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except Exception:
                self.proc.kill()


def open_browser(kind):
    return FirefoxDriver() if kind == "firefox" else ChromeDriver()
