#!/usr/bin/env python3
"""The extension, in the other engine.

Everything else here drives Chrome, and the two engines differ in the one place that decides
whether anything works at all: on Gecko the `chrome` namespace is the callback-style one, so a
call awaited through it returns nothing and the surface that made it quietly concludes there is
nothing there. That is not a thing a Chrome check can fail on, and CI is where it used to be
caught until CI stopped running.

So this launches a real Firefox, headless, on a profile of its own, installs the built add-on
as a temporary one and asks the same questions the Chrome checks ask: is a page annotated, does
the card open on a word and let the pointer in only through its arrow, and does the settings
view know which site it is looking at.

  uv run python scripts/proofread/on_firefox.py

Nothing of the reader's own Firefox is touched: the profile is made in a temporary directory
and thrown away, and the add-on is temporary, which is why signing does not come into it.
"""
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import QuietAudio  # noqa: E402
from on_a_page import CARD_JS, PORT, arrow_checks, build_packs, serve, walk

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ADDON = os.path.join(ROOT, ".output")
# Pinned, because an extension page's address is moz-extension://<uuid>/ and the uuid is made
# per profile: told what it is beforehand, the check can open the settings view by name.
UUID = "9f4a6b1e-5c3d-4a7b-8e2f-1d0c9b8a7654"
ADDON_ID = "phonetix@nanbiz.github.io"


def newest_zip():
    """The add-on this build made, which is what is being checked."""
    zips = [
        os.path.join(ADDON, name)
        for name in os.listdir(ADDON)
        if name.endswith("-firefox.zip")
    ]
    if not zips:
        raise SystemExit("no firefox build to check: run pnpm zip:firefox")
    return max(zips, key=os.path.getmtime)


class Marionette:
    """Firefox's own remote protocol, which is a length-prefixed JSON packet on a socket."""

    def __init__(self, port):
        self.socket = socket.create_connection(("127.0.0.1", port), timeout=90)
        self.socket.settimeout(90)
        self.rest = b""
        self.at = 0
        self.read()   # the handshake the browser sends first

    def read(self):
        while True:
            head, sep, tail = self.rest.partition(b":")
            if sep and head.isdigit():
                want = int(head)
                if len(tail) >= want:
                    self.rest = tail[want:]
                    return json.loads(tail[:want])
            more = self.socket.recv(65536)
            if not more:
                raise SystemExit("firefox closed the connection")
            self.rest += more

    def send(self, name, params=None):
        self.at += 1
        body = json.dumps([0, self.at, name, params or {}]).encode()
        self.socket.sendall(str(len(body)).encode() + b":" + body)
        while True:
            got = self.read()
            if got[0] == 1 and got[1] == self.at:
                if got[2]:
                    raise SystemExit(f"{name} failed: {got[2]}")
                return got[3]

    def script(self, source, timeout=30000):
        return self.send(
            "WebDriver:ExecuteAsyncScript",
            {"script": source, "args": [], "scriptTimeout": timeout},
        )["value"]


class FirefoxHand:
    """A mouse and a question, over Marionette, for the checks both engines share.

    The pointer is moved through WebDriver actions, which Gecko turns into the same mouse
    events a hand makes, hit tested and with their boundary events, rather than events
    dispatched at an element.
    """

    def __init__(self, driver):
        self.driver = driver

    def ask(self, expression):
        got = self.driver.script(
            "const done = arguments[0];"
            f"(async () => JSON.stringify(await ({expression})))()"
            ".then(done, e => done(JSON.stringify({failed: String(e)})));"
        )
        return json.loads(got) if got else None

    def act(self, steps):
        self.driver.send("WebDriver:PerformActions", {"actions": [{
            "type": "pointer", "id": "mouse", "parameters": {"pointerType": "mouse"},
            "actions": steps,
        }]})

    def move(self, x, y):
        self.act([{"type": "pointerMove", "duration": 0, "origin": "viewport",
                   "x": round(x), "y": round(y)}])

    def click(self, x, y):
        self.act([
            {"type": "pointerMove", "duration": 0, "origin": "viewport",
             "x": round(x), "y": round(y)},
            {"type": "pointerDown", "button": 0},
            {"type": "pointerUp", "button": 0},
        ])


def profile(into):
    """A profile that talks Marionette, takes an unsigned add-on, and knows its uuid."""
    with open(os.path.join(into, "user.js"), "w") as f:
        f.write(
            'user_pref("xpinstall.signatures.required", false);\n'
            'user_pref("extensions.autoDisableScopes", 0);\n'
            'user_pref("extensions.experiments.enabled", true);\n'
            'user_pref("browser.shell.checkDefaultBrowser", false);\n'
            'user_pref("datareporting.policy.dataSubmissionEnabled", false);\n'
            'user_pref("browser.aboutwelcome.enabled", false);\n'
            f'user_pref("extensions.webextensions.uuids", '
            f'"{{\\"{ADDON_ID}\\":\\"{UUID}\\"}}");\n'
        )


def main():
    build_packs()
    serve()
    base = f"http://127.0.0.1:{PORT}"
    failures = []

    where = tempfile.mkdtemp(prefix="phonetix-firefox-")
    profile(where)
    quiet = QuietAudio()
    firefox = subprocess.Popen(
        [
            # System access lets the check read whether the tab is making sound, which is
            # browser state no page can see. The profile is a throwaway one.
            "firefox", "--headless", "--no-remote", "--marionette", "-remote-allow-system-access",
            "--profile", where, "about:blank",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        # Sound into a server of the check's own whose output goes nowhere: the card's play
        # button is pressed, and it spoke through the reader's speakers.
        env={**quiet.env(), "MOZ_MARIONETTE_PORT": "2828"},
    )
    driver = None
    try:
        for _ in range(60):
            try:
                driver = Marionette(2828)
                break
            except OSError:
                time.sleep(1)
        if driver is None:
            print("FAIL - firefox never opened its remote port")
            sys.exit(1)
        driver.send("WebDriver:NewSession", {"capabilities": {}})
        driver.send("Addon:Install", {"path": newest_zip(), "temporary": True})
        print(f"  installed {os.path.basename(newest_zip())}")

        view = f"moz-extension://{UUID}"
        # The reader's choices, written the way the settings view writes them, and the packs
        # asked for by name. Both go through the messaging this check exists to exercise.
        driver.send("WebDriver:Navigate", {"url": f"{view}/viewbook.html"})
        time.sleep(3)
        told = driver.script(
            "const done = arguments[0];"
            f"browser.storage.local.set({{packBaseUrl: '{base}', targetLanguage: 'de',"
            " density: 1, layer: 'sound'})"
            "  .then(() => Promise.all(['es', 'de'].map(lang =>"
            "     browser.runtime.sendMessage({phonetix: 'openPack', data: {lang}}))))"
            "  .then(r => done(JSON.stringify(r)), e => done('failed: ' + e));",
            timeout=120000,
        )
        print(f"  the packs opened: {told}")
        if not told or "failed" in told or "null" in told:
            failures.append(f"the host did not open the packs: {told}")

        # A page, annotated. Everything the content script needs travels over the same
        # messaging, so a page with words on it is the whole boundary working.
        driver.send("WebDriver:Navigate", {"url": f"{base}/page.html"})
        words = None
        for _ in range(20):
            time.sleep(2)
            words = driver.script(
                "const done = arguments[0];"
                "done(JSON.stringify({"
                "  count: document.querySelectorAll('.px-w').length,"
                "  said: [...document.querySelectorAll('.px-ph')].map(g => g.textContent)"
                "    .slice(0, 4),"
                "  state: document.documentElement.dataset.phonetix || ''}));"
            )
            if json.loads(words or "{}").get("count"):
                break
        drawn = json.loads(words or "{}")
        print(f"  {drawn.get('count')} words annotated, saying {drawn.get('said')} "
              f"[{drawn.get('state')}]")
        if not drawn.get("count"):
            failures.append(f"nothing was annotated on Gecko: {drawn}")
        elif "pero" not in (drawn.get("said") or []):
            failures.append(f"the words are said {drawn.get('said')}, not the Spanish way")

        # The card is entered through its arrow and nowhere else, the same four ways the Chrome
        # check asks it: hit testing a card the pointer passes through, and finding the text
        # under it, are each engine's own.
        hand = FirefoxHand(driver)
        for density, painted in ((1, True), (100000, False)):
            driver.send("WebDriver:Navigate", {"url": f"{view}/viewbook.html"})
            time.sleep(1)
            driver.script(
                "const done = arguments[0];"
                f"browser.storage.local.set({{density: {density}}}).then(() => done('ok'));"
            )
            for low in (False, True):
                driver.send("WebDriver:Navigate",
                            {"url": f"{base}/{'lines-low' if low else 'lines'}.html"})
                for _ in range(25):
                    count = hand.ask("document.querySelectorAll('#lines .px-w').length")
                    if (count > 20) if painted else (count < 5):
                        break
                    time.sleep(1)
                time.sleep(1.5)
                arrow_checks(hand,
                             f"firefox, {'painted words' if painted else 'page text'}, "
                             f"card {'above' if low else 'below'}",
                             painted, low, failures)
        # The card's Wiktionary link, reached the way a reader reaches it: rest on a word until
        # its card opens, in through the arrow, along to the link, and pressed.
        driver.send("WebDriver:Navigate", {"url": f"{view}/viewbook.html"})
        time.sleep(1)
        driver.script("const done = arguments[0];"
                      "browser.storage.local.set({density: 1}).then(() => done('ok'));")
        driver.send("WebDriver:Navigate", {"url": f"{base}/lines.html"})
        word = None
        for _ in range(25):
            word = hand.ask("""(() => {
                const w = document.querySelector('#lines .px-w');
                if (!w) return null;
                const r = w.getBoundingClientRect();
                return {x: r.left + r.width / 2, y: r.top + r.height / 2};
            })()""")
            if word:
                break
            time.sleep(1)
        handles = driver.send("WebDriver:GetWindowHandles")
        before = set(handles["value"] if isinstance(handles, dict) else handles)
        opened = []
        if word:
            hand.move(2, 2)
            time.sleep(0.5)
            hand.move(word["x"], word["y"])
            seen = None
            for _ in range(20):
                time.sleep(0.3)
                seen = hand.ask(CARD_JS)
                if seen and seen.get("open") and seen.get("arrow"):
                    break
            link = hand.ask("""(() => {
                const host = document.getElementById('phonetix-card-host');
                const el = host && host.shadowRoot.querySelector('[data-does=Wiktionary]');
                if (!el) return null;
                const r = el.getBoundingClientRect();
                return {x: r.left + r.width / 2, y: r.top + r.height / 2};
            })()""")
            play = hand.ask("""(() => {
                const host = document.getElementById('phonetix-card-host');
                const el = host && host.shadowRoot.querySelector('.audio');
                if (!el) return null;
                const r = el.getBoundingClientRect();
                return {x: r.left + r.width / 2, y: r.top + r.height / 2};
            })()""")
            if seen and seen.get("arrow") and link:
                arrow = seen["arrow"]
                inside = arrow["bottom"] + 14 if seen["way"] == "below" else arrow["top"] - 14
                walk(hand, (word["x"], word["y"]), (arrow["x"], inside))
                # The play button first: Gecko plays only what a press asked for, which is
                # where its audio has gone silent before. The browser marks a tab that is
                # making sound, which is what is read here.
                def sounding():
                    driver.send("Marionette:SetContext", {"value": "chrome"})
                    try:
                        got = driver.send("WebDriver:ExecuteScript", {
                            "script": "return gBrowser.selectedTab.hasAttribute('soundplaying')",
                            "args": []})
                        return (got or {}).get("value") is True
                    finally:
                        driver.send("Marionette:SetContext", {"value": "content"})

                quiet_before = not sounding()
                sounded = False
                if play:
                    walk(hand, (arrow["x"], inside), (play["x"], play["y"]))
                    time.sleep(0.3)
                    hand.click(play["x"], play["y"])
                    for _ in range(20):
                        time.sleep(0.25)
                        if sounding():
                            sounded = True
                            break
                print(f"  the card's play button: quiet before {quiet_before}, "
                      f"{'sound' if sounded else 'silence'} after (button {bool(play)})")
                if not quiet_before:
                    failures.append("the tab was already making sound before play was pressed")
                elif not sounded:
                    failures.append("the card's play button made no sound on Gecko")
                start = (play["x"], play["y"]) if play else (arrow["x"], inside)
                walk(hand, start, (link["x"], link["y"]))
                time.sleep(0.3)
                hand.click(link["x"], link["y"])
                for _ in range(20):
                    time.sleep(0.5)
                    handles = driver.send("WebDriver:GetWindowHandles")
                    handles = handles["value"] if isinstance(handles, dict) else handles
                    if set(handles) - before:
                        opened = list(set(handles) - before)
                        break
        print(f"  the card's Wiktionary link: {len(opened)} tab opened"
              f" (word {bool(word)}, card {bool(word and seen and seen.get('open'))},"
              f" link {bool(word and link)})")
        if not opened:
            failures.append("the card's Wiktionary link opened nothing on Gecko")
        else:
            here = driver.send("WebDriver:GetWindowHandle")
            here = here["value"] if isinstance(here, dict) else here
            driver.send("WebDriver:SwitchToWindow", {"handle": opened[0]})
            for _ in range(10):
                url = driver.send("WebDriver:GetCurrentURL")
                url = url["value"] if isinstance(url, dict) else url
                if "wiktionary.org" in url:
                    break
                time.sleep(0.5)
            print(f"  ... on {url}")
            if "wiktionary.org/wiki/" not in url:
                failures.append(f"the card's Wiktionary link opened {url!r}")
            driver.send("WebDriver:CloseWindow")
            driver.send("WebDriver:SwitchToWindow", {"handle": here})

        driver.send("WebDriver:Navigate", {"url": f"{base}/page.html"})
        time.sleep(2)

        # And the settings view knows which site it is looking at. In a tab of its own, with
        # the page left open in the one behind it: that is the shape a reader opens it in, and
        # a view navigated on top of the page would have no page left to be about. Asked
        # through the tabs API, which is the one that returns nothing when it is awaited on the
        # chrome namespace.
        fresh = driver.send("WebDriver:NewWindow", {"type": "tab", "focus": True})
        driver.send("WebDriver:SwitchToWindow", {"handle": fresh["handle"]})
        driver.send("WebDriver:Navigate", {"url": f"{view}/popup.html"})
        time.sleep(6)
        seen = driver.script(
            "const done = arguments[0];"
            "done(JSON.stringify({"
            "  rows: [...document.querySelectorAll('[data-row]')].map(r => r.dataset.row),"
            "  site: (document.querySelector('[data-row=site] [data-name]') || {})"
            "    .textContent || ''}));"
        )
        view_rows = json.loads(seen or "{}")
        print(f"  the settings view says it is on {view_rows.get('site')!r}")
        if "site" not in (view_rows.get("rows") or []):
            failures.append(
                f"the view has no site row, so it saw no page: {view_rows.get('rows')}")
    finally:
        if driver is not None:
            try:
                driver.send("Marionette:Quit")
            except SystemExit:
                pass
        firefox.terminate()
        try:
            firefox.wait(timeout=20)
        except subprocess.TimeoutExpired:
            firefox.kill()
        quiet.close()
        shutil.rmtree(where, ignore_errors=True)

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the other engine annotates a page, lets the pointer into a card only "
          "through its arrow, and knows which site it is on")


if __name__ == "__main__":
    main()
