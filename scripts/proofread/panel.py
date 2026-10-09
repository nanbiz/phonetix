#!/usr/bin/env python3
"""The translator panel and the keyboard's two commands, driven with real keys, in both engines.

A check that opened the panel by sending the content script a message passed while the panel
opened from the keyboard with nothing focused, so a reader pressed the shortcut, typed, and
typed into the page. So this runs the browser with a window, on an X display of its own
(Xvfb), and presses the keys the way a reader does (xdotool): the browser's own shortcut
handling decides what happens, and what is typed goes wherever focus really is.

What it asks of the panel is everything a reader does with it: open it from the keyboard and
type straight away; a word in their own language answered with every meaning in the other, a
word in the other answered back, and the arrow turning the way; a phrase answered as one line
with how it is said; another language chosen from a list that can be searched, and still
chosen when the panel comes back; Escape, a press outside it and the shortcut again each
taking it down. And of the other command: that it switches Phonetix off and on, which the
page's own words show, and that switched off a selection opens no card.

Needs Xvfb and xdotool, which the nix shell below provides. Nothing is drawn on the reader's
own screen: the display is the check's, and a Wayland socket inherited from the desktop is
dropped so the browser cannot prefer it.

  nix shell nixpkgs#xorg.xvfb nixpkgs#xdotool -c uv run python scripts/proofread/panel.py [chrome|firefox]
"""
import base64
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import says  # noqa: E402  the packs, the models and the server, shared with the say check
from harness import EXT, OFFLINE, PipeCDP, QuietAudio  # noqa: E402
from on_firefox import ADDON_ID, UUID, Marionette, newest_zip  # noqa: E402

SHOTS = os.environ.get("PHONETIX_SHOTS", "/tmp/phonetix-panel")
PORT = says.PORT
BASE = f"http://127.0.0.1:{PORT}"

IN_PANEL = """
  (() => {
    const host = document.getElementById('phonetix-card-host-ask');
    const root = host && host.shadowRoot;
    if (!root) return null;
    return (%s)(root);
  })()
"""


def display():
    """An X display nothing else is using, with a server on it.

    A display whose lock names a server that is no longer running is free: a check that was
    killed leaves its lock behind, and thirty of them used every number this looks at."""
    for number in range(91, 120):
        lock, socket_path = f"/tmp/.X{number}-lock", f"/tmp/.X11-unix/X{number}"
        if os.path.exists(lock):
            try:
                os.kill(int(open(lock).read().strip()), 0)
                continue
            except (OSError, ValueError):
                for stale in (lock, socket_path):
                    try:
                        os.remove(stale)
                    except OSError:
                        pass
        if os.path.exists(socket_path):
            continue
        server = subprocess.Popen(
            ["Xvfb", f":{number}", "-screen", "0", "1280x900x24", "-nolisten", "tcp"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(50):
            if os.path.exists(socket_path):
                return f":{number}", server
            time.sleep(0.1)
        close_display(server)
    raise SystemExit("no X display could be started")


def close_display(server):
    """Stop a display's server the way that lets it take its lock with it."""
    server.terminate()
    try:
        server.wait(timeout=10)
    except subprocess.TimeoutExpired:
        server.kill()


def keys(*combo):
    subprocess.run(["xdotool", "key", "--clearmodifiers", *combo], check=True)


def typed(text):
    subprocess.run(["xdotool", "type", "--delay", "30", text], check=True)


def focus_window(window_class):
    """The browser's window, focused the way a window manager would focus it."""
    for _ in range(100):
        found = subprocess.run(["xdotool", "search", "--onlyvisible", "--class", window_class],
                               capture_output=True, text=True).stdout.split()
        if found:
            for window in found:
                subprocess.run(["xdotool", "windowfocus", "--sync", window],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return True
        time.sleep(0.2)
    return False


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


class Chrome:
    """Chromium over CDP, with a window on the check's display."""

    name = "chrome"
    window_class = "Chromium"

    def __init__(self, extra_args=()):
        self.cdp = PipeCDP(headless=False, extra_args=[OFFLINE, *extra_args])
        self.cdp.send("Target.setDiscoverTargets", {"discover": True})
        self.extid = self.cdp.ensure_extension()
        self.manifest = json.load(open(os.path.join(EXT, "manifest.json")))
        view = self.cdp.send("Target.createTarget",
                             {"url": f"chrome-extension://{self.extid}/viewbook.html"})
        self.settings = self.cdp.send("Target.attachToTarget",
                                      {"targetId": view["targetId"], "flatten": True})["sessionId"]
        self.cdp.send("Runtime.enable", session=self.settings)
        time.sleep(2)

    def _eval(self, session, expression, timeout=120):
        got = self.cdp.send("Runtime.evaluate", {
            "expression": f"(async () => JSON.stringify(await ({expression})))()",
            "awaitPromise": True, "returnByValue": True,
        }, session=session, timeout=timeout)
        value = got.get("result", {}).get("value")
        return json.loads(value) if value else None

    def ext(self, expression):
        """Asked of an extension page, where the extension's own storage and host are."""
        return self._eval(self.settings, expression)

    def open_page(self):
        target = self.cdp.send("Target.createTarget", {"url": f"{BASE}/page.html"})
        self.page = self.cdp.send("Target.attachToTarget",
                                  {"targetId": target["targetId"], "flatten": True})["sessionId"]
        self.cdp.send("Runtime.enable", session=self.page)
        self.cdp.send("Page.enable", session=self.page)
        self.cdp.send("Target.activateTarget", {"targetId": target["targetId"]})
        time.sleep(3)

    def on_page(self, expression):
        return self._eval(self.page, expression, timeout=60)

    def press(self, x, y):
        for kind in ("mousePressed", "mouseReleased"):
            self.cdp.send("Input.dispatchMouseEvent", {"type": kind, "x": x, "y": y,
                                                       "button": "left", "clickCount": 1},
                          session=self.page)

    def shot(self):
        got = self.cdp.send("Page.captureScreenshot", {"format": "png"}, session=self.page)
        return base64.b64decode(got["data"])

    def close(self):
        self.cdp.close()


class Firefox:
    """Firefox over Marionette, with a window on the check's display, the add-on installed as a
    temporary one on a profile of its own."""

    name = "firefox"
    window_class = "firefox"

    def __init__(self, prefs=()):
        self.where = tempfile.mkdtemp(prefix="phonetix-firefox-")
        port = free_port()
        with open(os.path.join(self.where, "user.js"), "w") as f:
            f.write("".join(f'user_pref("{name}", {json.dumps(value)});\n' for name, value in prefs))
            if os.environ.get("PHONETIX_FIREFOX_LOG"):
                f.write('user_pref("devtools.console.stdout.chrome", true);\n'
                        'user_pref("devtools.console.stdout.content", true);\n')
            f.write(
                # The port is the profile's to say: the environment variable is not read.
                f'user_pref("marionette.port", {port});\n'
                'user_pref("xpinstall.signatures.required", false);\n'
                'user_pref("extensions.autoDisableScopes", 0);\n'
                'user_pref("browser.shell.checkDefaultBrowser", false);\n'
                'user_pref("datareporting.policy.dataSubmissionEnabled", false);\n'
                'user_pref("browser.aboutwelcome.enabled", false);\n'
                f'user_pref("extensions.webextensions.uuids", '
                f'"{{\\"{ADDON_ID}\\":\\"{UUID}\\"}}");\n'
            )
        env = {k: v for k, v in os.environ.items() if k != "WAYLAND_DISPLAY"}
        env["MOZ_ENABLE_WAYLAND"] = "0"
        # Sound into a server of the check's own: the one a check that hears set up, or one
        # whose output goes nowhere.
        self.quiet = None
        if "PULSE_SERVER" not in os.environ:
            self.quiet = QuietAudio()
            env = self.quiet.env(env)
        # What the add-on writes to its console, kept where PHONETIX_FIREFOX_LOG says.
        log = os.environ.get("PHONETIX_FIREFOX_LOG")
        out = open(log, "w") if log else subprocess.DEVNULL
        self.browser = subprocess.Popen(
            ["firefox", "--no-remote", "--marionette", "--profile", self.where, "about:blank"],
            stdout=out, stderr=out, env=env)
        self.driver = None
        for _ in range(60):
            try:
                self.driver = Marionette(port)
                break
            except OSError:
                time.sleep(1)
        if self.driver is None:
            raise SystemExit("firefox never opened its remote port")
        self.driver.send("WebDriver:NewSession", {"capabilities": {}})
        self.driver.send("WebDriver:SetWindowRect", {"x": 0, "y": 0, "width": 1280, "height": 900})
        self.driver.send("Addon:Install", {"path": newest_zip(), "temporary": True})
        self.driver.send("WebDriver:Navigate", {"url": f"moz-extension://{UUID}/viewbook.html"})
        time.sleep(3)
        # The manifest the add-on was built with, read from the add-on itself.
        self.manifest = self.ext("browser.runtime.getManifest()")

    def _async(self, expression, timeout=120000):
        got = self.driver.send("WebDriver:ExecuteAsyncScript", {
            "script": "const done = arguments[0];"
                      f"(async () => JSON.stringify(await ({expression})))()"
                      ".then(done, e => done(JSON.stringify({failed: String(e)})));",
            "args": [], "scriptTimeout": timeout})["value"]
        return json.loads(got) if got else None

    def ext(self, expression):
        """Asked of the extension page the tab is on, before it goes to the page: a second tab
        would take focus from the page the keys are meant for."""
        return self._async(expression)

    def open_page(self):
        self.driver.send("WebDriver:Navigate", {"url": f"{BASE}/page.html"})
        time.sleep(3)

    def on_page(self, expression):
        return self._async(expression, timeout=60000)

    def press(self, x, y):
        self.driver.send("WebDriver:PerformActions", {"actions": [{
            "type": "pointer", "id": "mouse", "parameters": {"pointerType": "mouse"},
            "actions": [
                {"type": "pointerMove", "duration": 0, "origin": "viewport",
                 "x": round(x), "y": round(y)},
                {"type": "pointerDown", "button": 0},
                {"type": "pointerUp", "button": 0},
            ]}]})
        self.driver.send("WebDriver:ReleaseActions")

    def shot(self):
        got = self.driver.send("WebDriver:TakeScreenshot", {"full": False})["value"]
        return base64.b64decode(got)

    def close(self):
        try:
            self.driver.send("Marionette:Quit")
        except SystemExit:
            pass
        self.browser.terminate()
        try:
            self.browser.wait(timeout=20)
        except subprocess.TimeoutExpired:
            self.browser.kill()
        if self.quiet:
            self.quiet.close()
        shutil.rmtree(self.where, ignore_errors=True)


def check(engine):
    failures = []
    shots = []
    browser = Chrome() if engine == "chrome" else Firefox()
    try:
        api = "browser" if engine == "firefox" else "chrome"
        bound = browser.ext(f"{api}.commands.getAll()") or []
        bound = {c["name"]: c.get("shortcut", "") for c in bound}
        print(f"  {engine}: the keyboard: {bound}")
        for name, command in browser.manifest.get("commands", {}).items():
            want = command["suggested_key"]["default"]
            if bound.get(name, "").replace(" ", "") != want:
                failures.append(f"{name} is bound to {bound.get(name)!r}, not {want}")
        open_key = browser.manifest["commands"]["translator"]["suggested_key"]["default"].lower()
        switch_key = browser.manifest["commands"]["switch-on-off"]["suggested_key"]["default"].lower()

        opened = browser.ext(
            f"{api}.storage.local.set({{on: true, packBaseUrl: '{BASE}', targetLanguage: 'en',"
            f" learning: 'es', recentLanguages: [], density: 1}})"
            f".then(() => {api}.runtime.sendMessage({{phonetix: 'openPack', data: {{lang: 'es'}}}}))")
        if "es" not in json.dumps(opened):
            failures.append(f"the Spanish pack did not open: {opened}")

        browser.open_page()
        if not focus_window(browser.window_class):
            raise SystemExit(f"{engine} drew no window")
        time.sleep(1)

        def panel(body):
            return browser.on_page(IN_PANEL % body)

        def wait(body, want, tries=40, gap=0.25):
            got = None
            for _ in range(tries):
                got = panel(body)
                if want(got):
                    return got
                time.sleep(gap)
            return got

        def shot(name):
            os.makedirs(SHOTS, exist_ok=True)
            path = os.path.join(SHOTS, f"{engine}-{name}.png")
            with open(path, "wb") as f:
                f.write(browser.shot())
            shots.append(path)
            return path

        def click(selector):
            """A real press on something in the panel, where the browser draws it."""
            box = panel(f"""(root) => {{
                const el = root.querySelector({json.dumps(selector)});
                if (!el) return null;
                const r = el.getBoundingClientRect();
                return {{x: r.x + r.width / 2, y: r.y + r.height / 2}};
            }}""")
            if not box:
                return False
            browser.press(box["x"], box["y"])
            return True

        def focused():
            return panel("(root) => document.hasFocus() && root.activeElement ? "
                         "root.activeElement.closest('[data-sheet]') ? 'filter' : "
                         "root.activeElement.closest('.ask-field') ? 'field' : "
                         "root.activeElement.tagName : null")

        def up():
            return panel("(root) => !!root.querySelector('[data-ask]')") is True

        def clear():
            keys("ctrl+a")
            keys("BackSpace")

        drawn = 0
        for _ in range(30):
            drawn = browser.on_page("document.querySelectorAll('.px-w').length") or 0
            if drawn:
                break
            time.sleep(0.5)
        print(f"  {engine}: the page draws {drawn} words")

        # Opened from the keyboard, and typed into straight away.
        keys(open_key)
        if wait("(root) => !!root.querySelector('[data-ask]')", lambda got: got is True) is not True:
            failures.append(f"{open_key} opened no panel")
            return failures, shots
        where = focused()
        print(f"  {engine}: opened from the keyboard, focus on: {where}")
        if where != "field":
            failures.append(f"the panel opened with focus on {where!r}, not its field")
        typed("bench")
        got = wait("(root) => ({value: root.querySelector('.ask-field input').value,"
                   " words: [...root.querySelectorAll('.ask-mean')].map(m => m.innerText),"
                   " from: root.querySelector('[data-ask]').dataset.from})",
                   lambda got: got and got["words"], tries=80) or {}
        print(f"  {engine}: typed 'bench': {got} {shot('word')}")
        if got.get("value") != "bench":
            failures.append(f"the keys typed went elsewhere: the field holds {got.get('value')!r}")
        if not got.get("words") or "banco" not in got["words"][0]:
            failures.append(f"'bench' was not answered with banco: {got.get('words')}")
        elif "/" not in got["words"][0]:
            failures.append(f"banco came without how it is said: {got['words'][0]!r}")
        if got.get("from") != "en":
            failures.append(f"'bench' was read as {got.get('from')!r}, not English")

        # A word of the other language comes back in the reader's.
        clear()
        typed("perro")
        got = wait("(root) => ({words: [...root.querySelectorAll('.ask-mean .m-word')]"
                   ".map(m => m.textContent), from: root.querySelector('[data-ask]').dataset.from,"
                   " back: root.querySelector('[data-does=turn]').classList.contains('back')})",
                   lambda got: got and got["from"] == "es" and got["words"], tries=80) or {}
        # Once the arrow has finished turning.
        time.sleep(0.4)
        print(f"  {engine}: typed 'perro': {got} {shot('back')}")
        if got.get("from") != "es":
            failures.append(f"'perro' was read as {got.get('from')!r}, not Spanish")
        if "dog" not in " ".join(got.get("words") or []):
            failures.append(f"'perro' was not answered with dog: {got.get('words')}")
        if not got.get("back"):
            failures.append("the arrow does not point back for a word answered backwards")

        # The arrow turns the way round.
        if not click("[data-does=turn]"):
            failures.append("the panel has no arrow to turn")
        got = wait("(root) => root.querySelector('[data-ask]').dataset.from",
                   lambda got: got == "en", tries=40)
        print(f"  {engine}: turned: from {got}")
        if got != "en":
            failures.append(f"the arrow left the question read as {got!r}")

        # A phrase, answered as one line with how it is said.
        click(".ask-field input")
        clear()
        typed("where is the station")
        got = wait("(root) => { const l = root.querySelector('[data-said=line]');"
                   " return l ? {line: l.firstChild.textContent,"
                   " ipa: (l.querySelector('.l-ipa') || {}).textContent || ''} : null; }",
                   lambda got: bool(got), tries=120, gap=0.5) or {}
        print(f"  {engine}: a phrase: {got} {shot('phrase')}")
        if not got.get("line"):
            failures.append("the phrase came back with no translation")
        if not got.get("ipa"):
            failures.append(f"the translation {got.get('line')!r} came without how it is said")

        # Another language, from a list that is searched by typing.
        click("[data-lang=learning]")
        where = focused()
        if where != "filter":
            failures.append(f"the language list opened with focus on {where!r}, not its search")
        typed("germ")
        time.sleep(0.4)
        listed = panel("(root) => [...root.querySelectorAll('[data-sheet] .choice')]"
                       ".map(c => c.dataset.choice)")
        print(f"  {engine}: searching 'germ': {listed} {shot('list')}")
        if listed != ["de"]:
            failures.append(f"searching 'germ' leaves {listed}")
        click("[data-sheet] [data-choice=de]")
        time.sleep(0.6)
        after = panel("(root) => ({sheet: !!root.querySelector('[data-sheet]'),"
                      " name: root.querySelector('[data-lang=learning]').textContent.trim()})") or {}
        print(f"  {engine}: picked German: {after}, focus on {focused()}")
        if after.get("sheet") or after.get("name") != "German":
            failures.append(f"picking German left the panel at {after}")
        if focused() != "field":
            failures.append("after a language is picked the field does not have focus")

        # Escape takes away the list first, then the panel.
        click("[data-lang=learning]")
        time.sleep(0.3)
        keys("Escape")
        time.sleep(0.3)
        state = panel("(root) => root.querySelector('[data-sheet]') ? 'list' : 'panel'")
        if state != "panel":
            failures.append(f"Escape over the open list left {state!r}")
        keys("Escape")
        time.sleep(0.4)
        if up():
            failures.append("Escape did not take the panel down")

        # Back again, still in German: the language picked is remembered.
        keys(open_key)
        name = wait("(root) => root.querySelector('[data-lang=learning]').textContent.trim()",
                    lambda got: bool(got))
        print(f"  {engine}: opened again in {name}")
        if name != "German":
            failures.append(f"the panel came back in {name!r}, not the German picked last time")
        # The shortcut takes down what it put up, and a press outside does too.
        keys(open_key)
        time.sleep(0.6)
        if up():
            failures.append("the shortcut does not take the panel down again")
        keys(open_key)
        wait("(root) => !!root.querySelector('[data-ask]')", lambda got: got is True)
        browser.press(20, 700)
        time.sleep(0.4)
        if up():
            failures.append("a press outside the panel did not take it down")

        # The other command: Phonetix off, which takes the page's words away, and on again,
        # which brings them back.
        keys(switch_key)
        off = None
        for _ in range(20):
            time.sleep(0.5)
            off = browser.on_page("document.querySelectorAll('.px-w').length")
            if off == 0:
                break
        # Switched off, a selection opens no card.
        browser.on_page("""(() => {
            const p = document.querySelector('p');
            const range = document.createRange();
            range.selectNodeContents(p);
            const sel = document.getSelection();
            sel.removeAllRanges();
            sel.addRange(range);
            document.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));
        })()""")
        time.sleep(4)
        card = browser.on_page(
            "!!document.getElementById('phonetix-card-host')"
            " && !!document.getElementById('phonetix-card-host').shadowRoot.querySelector('.card')")
        browser.on_page("document.getSelection().removeAllRanges()")
        keys(switch_key)
        on = None
        for _ in range(20):
            time.sleep(0.5)
            on = browser.on_page("document.querySelectorAll('.px-w').length")
            if on:
                break
        print(f"  {engine}: {switch_key}: the page's words went from {drawn} to {off} and back"
              f" to {on}; switched off, a selection drew a card: {card}")
        if not drawn:
            failures.append("the page drew no words to switch off")
        elif off != 0 or not on:
            failures.append(f"{switch_key} did not switch Phonetix off and on: {drawn}, {off}, {on}")
        if card:
            failures.append("switched off, a selection still opened a card")
    finally:
        browser.close()
    return failures, shots


def main():
    for tool in ("Xvfb", "xdotool"):
        if not shutil.which(tool):
            raise SystemExit(f"{tool} is not on PATH: run this inside "
                             "`nix shell nixpkgs#xorg.xvfb nixpkgs#xdotool`")
    engines = sys.argv[1:] or ["chrome", "firefox"]
    says.fetch_models()
    says.build_packs()
    says.serve()
    shown, server = display()
    os.environ["DISPLAY"] = shown
    os.environ.pop("WAYLAND_DISPLAY", None)
    failures = []
    try:
        for engine in engines:
            found, shots = check(engine)
            failures += [f"{engine}: {line}" for line in found]
            for path in shots:
                print(f"  picture: {path}")
    finally:
        close_display(server)
    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print(f"\nPASS - {', '.join(engines)}: the panel opens from the keyboard ready to type,"
          " answers both ways, a phrase with how it is said, takes another language from a"
          " searched list and keeps it, and goes away three ways; the other key switches"
          " Phonetix off and on, and off, a selection is left alone")


if __name__ == "__main__":
    main()
