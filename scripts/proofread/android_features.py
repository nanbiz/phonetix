#!/usr/bin/env python3
"""The parts of the app a reader decides about: which apps, the master switch, the language of
a page, the accessibility button and the app's own screen.

Nothing is painted over a page any more, so what each of these decides is what the side button
can answer: the words of a screen the service knows, which its state dump lists, and the card
the button shows over one of them. Each is checked on a device rather than in a unit test.

  PHONETIX_ANDROID_SERIAL=emulator-5600 uv run scripts/proofread/android_features.py

One part at a time, by name, for a fix to one of them:

  ... android_features.py apps language
"""
import json
import os
import sys
import time

from android_harness import Device, card_while_held, shell
import state as State
from webview import View


class Results:
    def __init__(self):
        self.passed = 0
        self.failures = []
        # Which checks actually ran, since a suite whose count drops has stopped asking
        # something rather than started passing more.
        self.asked = []

    def check(self, ok, name, detail=""):
        self.asked.append(name)
        if ok:
            self.passed += 1
        else:
            self.failures.append(f"{name}: {detail}")
        return ok

    @property
    def total(self):
        return self.passed + len(self.failures)


def believed():
    """Everything the service holds, or nothing where it has not answered yet."""
    try:
        serial = State.device()
        name = State.ask(serial)
        return State.fetch(serial, name) if name else {}
    except Exception:
        return {}


def known(package=None):
    """The words the service knows on the screen, which are the words the side button can
    answer, and the package it believes they are on."""
    told = believed()
    on = (told.get("screen") or {}).get("package")
    if package and on != package:
        return on, []
    return on, (told.get("overlay") or {}).get("boxes") or []


def known_on(dev, want=True, seconds=24, package=None, **extras):
    """Put the test page up with these settings and wait for what the service knows of it.

    Waited for rather than sampled: a page just asked for is read a moment later, and on a
    loaded machine several seconds later. [want] says which answer is being waited for, so a
    check that expects nothing waits out the page being read before it believes nothing.
    """
    extras.setdefault("enable", 1)
    mode = extras.pop("mode", "plain")
    dev.surface(mode=mode, **extras)
    until = time.time() + seconds
    words = []
    while time.time() < until:
        time.sleep(2)
        on, words = known(package or "io.github.nanbiz.phonetix")
        if bool(words) == want:
            # Once more, a moment later: a page is read as it is drawn, and the first answer
            # can be a part of it.
            time.sleep(1.5)
            on, again = known(package or "io.github.nanbiz.phonetix")
            return again if bool(again) == want else words
    return words


# --------------------------------------------------------------------------------------
# Which apps the reader chose.
# --------------------------------------------------------------------------------------

def check_scope(r, dev):
    """With no app chosen, the button answers nothing on any app.

    What the reader chose is where Phonetix works. Asked of the card rather than of the words
    the service knows: those are kept for the button to answer from, and what the reader meets
    is whether holding the button over a word answers it.
    """
    everywhere = known_on(dev, mode="spanish", density=1, target="none", allApps=1)
    r.check(bool(everywhere), "scope: with every app allowed, the button knows the words",
            "no word known")
    texts, _ = card_while_held(dev, SCOPE_WORD)
    r.check(bool(texts), "scope: with every app allowed, the button answers a word",
            f"no card for {SCOPE_WORD}")

    dev.surface(mode="spanish", density=1, target="none", allApps=0)
    time.sleep(4)
    texts, _ = card_while_held(dev, SCOPE_WORD)
    r.check(not texts, "scope: with no app chosen, the button answers no word",
            f"the card for {SCOPE_WORD} came up on an app nobody chose: {texts}")

    # And back, so the setting is not one-way.
    dev.surface(mode="spanish", density=1, target="none", allApps=1)
    time.sleep(4)
    texts, _ = card_while_held(dev, SCOPE_WORD)
    r.check(bool(texts), "scope: allowing every app again brings the answers back",
            f"no card for {SCOPE_WORD}")


# A word the Spanish page has once, so a card about it can only be about that one.
SCOPE_WORD = "silla"


# --------------------------------------------------------------------------------------
# The master switch.
# --------------------------------------------------------------------------------------

def check_switch(r, dev):
    on = known_on(dev, mode="spanish", density=1, target="none", enable=1, allApps=1)
    r.check(bool(on), "switch: on, the button knows the words", "nothing known while on")
    dev.surface(mode="spanish", density=1, target="none", enable=0, allApps=1)
    showing = None
    for _ in range(8):
        time.sleep(1.5)
        showing = (believed().get("mark") or {}).get("markShowing")
        if showing is False:
            break
    r.check(showing is False, "switch: off, the button is gone", f"markShowing={showing}")
    texts, _ = card_while_held(dev, SCOPE_WORD)
    r.check(not texts, "switch: off, nothing answers a word",
            f"the card for {SCOPE_WORD} came up while switched off: {texts}")
    back = known_on(dev, mode="spanish", density=1, target="none", enable=1, allApps=1)
    # Waited for, as above: a dump not written yet answers nothing, which is not "hidden".
    for _ in range(8):
        showing = (believed().get("mark") or {}).get("markShowing")
        if showing is True:
            break
        time.sleep(1.5)
    r.check(bool(back) and showing is True, "switch: it goes back on",
            f"{len(back)} words known, markShowing={showing}")


# --------------------------------------------------------------------------------------
# The language a page is in.
# --------------------------------------------------------------------------------------

# Sounds an English reading of these letters would not produce: the ach-Laut, the ich-Laut and
# the front rounded vowels. One of them on the card is enough to say which voice read the word.
GERMAN_ONLY = ("x", "ç", "yː", "ʏ", "øː", "œ")
# Words of the German page that carry one of them, in the order they are asked about.
GERMAN_WORDS = ("Nacht", "gehört", "für", "Lautstärke")


def check_language(r, dev):
    """A page in German is read as German, and the card says its words in German.

    There used to be one dictionary on the phone and it was English, generated by espeak, which
    will pronounce any string of letters put to it - so "und", "der" and "das" were all in it,
    with English vowels. What decides it now is which language's commonest words a line is
    made of, and a line too short to hold one is decided for by the screen it is on.

    What language a word is in is asked again when the card for it is built - the card looks
    the word up and says which language it is answering about - and the words used to reach it
    carrying nothing: every card on a German page was built in English. So this asks both: the
    words the service knows carry German, and the card for a word says it the way only German
    does. "Nacht" is the word that separates them: German says it with the ach-Laut, and no
    English reading of those letters has one.
    """
    german = known_on(dev, mode="german", density=1, target="none", allApps=1, seconds=40)
    carried = {}
    for box in german:
        reads = box.get("language") or ""
        carried[reads] = carried.get(reads, 0) + 1
    r.check(bool(german), "language: the words of a German page are known",
            "no word known on it")
    r.check(
        carried.get("de", 0) > 0 and list(carried) == ["de"],
        "language: the words of a German page carry German, so a card about one is German",
        f"the words say {carried or 'nothing'}",
    )
    said = {}
    for word in GERMAN_WORDS:
        texts, _ = card_while_held(dev, word)
        said[word] = [t for t in texts if t.startswith("/")]
        if any(sound in t for t in said[word] for sound in GERMAN_ONLY):
            break
    print(f"  the cards on the German page: {said}")
    r.check(
        any(sound in t for ts in said.values() for t in ts for sound in GERMAN_ONLY),
        "language: the card says a German word in German, not in English",
        f"nothing among {said} carries a sound only German has",
    )
    dev.surface(mode="unique", density=1, target="none", allApps=1, enable=1)
    english = []
    for _ in range(20):
        time.sleep(2)
        _, english = known("io.github.nanbiz.phonetix")
        # This page's words, not the German page's still held from before it was read.
        if english and not {b["word"] for b in english} & {b["word"] for b in german}:
            break
    r.check(
        bool(english) and all((b.get("language") or "en") == "en" for b in english),
        "language: an English page is still known, as English",
        f"{len(english)} words, in {sorted({b.get('language') for b in english})}",
    )


# --------------------------------------------------------------------------------------
# An app nobody wrote for this test.
# --------------------------------------------------------------------------------------

def check_a_real_app(r, dev):
    """An app nobody wrote for this test has its words known, and keeps them through a scroll.

    Every other page here is one this repository draws, and a page this repository draws is a
    page whose every quirk has been designed around. The settings app is not: it is a real
    list, laid out by someone else, and it was invisible to the service for a long time. Its
    events were dropped as a bystander's - the device's home intent is answered by that same
    app's placeholder activity, so the launcher lookup named it - and a bystander is dropped
    before anything is read or logged, so nothing anywhere said so.
    """
    # From the app's own front page, not from wherever it was left. Its search screen belongs
    # to a second package, so it survives stopping the settings app and it is what
    # `am start` restores - a screen with two words on it, which reads as the service having
    # stopped working.
    shell("cmd", "statusbar", "collapse")
    shell("am", "force-stop", "com.android.settings")
    shell("am", "force-stop", "com.google.android.settings.intelligence")
    time.sleep(1.5)
    shell("am", "start", "-a", "android.settings.SETTINGS")
    time.sleep(4)
    if not r.check("settings" in dev.top_activity().lower(),
                   "a real app: the settings app is in front", dev.top_activity()):
        return
    # What the service knows, once it has stopped growing: a screen is read as it is drawn,
    # and the first answer can be one word of a screenful.
    words, steady = [], 0
    for _ in range(10):
        time.sleep(2.0)
        _, now = known("com.android.settings")
        if now and len(now) <= len(words):
            steady += 1
            if steady >= 2:
                break
        else:
            steady = 0
        words = now or words
    if not r.check(bool(words), "a real app: its words are known",
                   "nothing at all on a screen full of English"):
        return
    # The words a real app's screen is actually made of. Almost all of its text is a heading
    # or a label in title case, and while the cascade compared spellings byte for byte every
    # one of those missed the dictionary: the screen came back nearly bare and every check
    # here still passed, because each of them asks about words written the way a dump keys
    # them. So this asks about the capitalised ones specifically.
    capitalised = [b["word"] for b in words if b["word"][:1].isupper()]
    r.check(
        len(capitalised) >= 3,
        "a real app: the words it capitalises are known too",
        f"only {len(capitalised)} of {len(words)} known words begin with a capital, "
        f"on a screen whose every label is title case: {sorted(capitalised)[:8]}",
    )
    # And after it is scrolled, which is a different list implementation from any of the
    # pages here: the words now on the screen, not the ones that scrolled away.
    before = {b["word"] for b in words}
    shell("input", "swipe", "540", "1400", "540", "600", "400")
    after = []
    for _ in range(8):
        time.sleep(2.0)
        _, after = known("com.android.settings")
        if after and {b["word"] for b in after} != before:
            break
    r.check(bool(after) and {b["word"] for b in after} != before,
            "a real app: after a scroll it knows the words that came into sight",
            f"{len(after)} words known, the same as before: {sorted(before)[:8]}")


def check_accessibility_button(r, dev):
    """The service asks for the button that switches it off without leaving the page.

    It is the one control a reader can reach while reading, and whether it exists at all comes
    down to one flag in the service's configuration - which nothing else would notice the loss
    of. The system draws it as a floating button, or in the navigation bar, depending on how
    the device is set up, so this asks the service rather than hunting for it on the screen.
    """
    # A fresh process is what makes it say what it asked for. Switching the service off and
    # on again is not enough: it reconnects in the process it was already running in, and
    # says nothing the second time.
    shell("am", "force-stop", "io.github.nanbiz.phonetix")
    time.sleep(2)
    dev.clear_log()
    dev.enable_service()
    time.sleep(6)
    log = dev.log()
    r.check(
        "BUTTON registered" in log,
        "the button: the service asks for it",
        "no registration in the log",
    )
    r.check(
        "no accessibility button" not in log,
        "the button: the platform did not refuse it",
        "registering the callback threw",
    )
    # Whether the button is showing is the reader's business - it depends on how they
    # navigate and on what they have assigned it to - so that is not asserted here.


# --------------------------------------------------------------------------------------
# The app's own screen, which unlike the card is an ordinary window a dump can see.
# --------------------------------------------------------------------------------------

def check_settings_screen(r, dev):
    """The app's own screen, which is the extension's own screen.

    One set of components, built into the app's assets and drawn in a web view, so it is asked
    the same questions the browser suite asks it: which rows are there, and whether the
    controls on them are controls. An accessibility dump sees a web view as one blank view and
    would report every row of it missing.
    """
    # Anything the previous checks left open - a card, most of all - would swallow the tap.
    shell("input", "tap", "20", "20")
    time.sleep(1.0)
    # Brought forward and waited for, not started and hoped about. The test page is an
    # activity of this same app, so starting the settings screen behind it delivers the
    # intent and leaves the page where it is.
    for _ in range(6):
        shell(
            "am", "start", "-n", "io.github.nanbiz.phonetix/.MainActivity",
            "--activity-reorder-to-front",
        )
        time.sleep(2.0)
        if "MainActivity" in dev.top_activity():
            break
        shell("am", "start", "-n", "io.github.nanbiz.phonetix/.MainActivity",
              "--activity-clear-task", "--activity-new-task")
        time.sleep(2.0)
    if not r.check("MainActivity" in dev.top_activity(),
                   "settings: the app's own screen comes to the front",
                   f"the device is showing {dev.top_activity()}"):
        return

    words = wording()
    try:
        with View() as view:
            for _ in range(20):
                if view.evaluate("Boolean(document.querySelector('main [data-row]'))"):
                    break
                time.sleep(1)
            drawn = None
            for _ in range(20):
                drawn = view.evaluate("""
                    (() => {
                      const panel = document.querySelector('main');
                      if (!panel || !panel.querySelector('[data-row]')) return null;
                      return JSON.stringify({
                        rows: [...panel.querySelectorAll('[data-row]')]
                          .map(r => r.getAttribute('data-row')),
                        names: [...panel.querySelectorAll('[data-row] [data-name]')]
                          .map(r => r.textContent.trim()),
                        on: Boolean(panel.querySelector('[data-row=on] input')),
                        ground: getComputedStyle(document.body).backgroundColor,
                      });
                    })()
                """)
                if drawn:
                    break
                time.sleep(1)
            if not r.check(drawn, "settings: the screen draws itself", "nothing in the view"):
                return
            screen = json.loads(drawn)
    except Exception as e:  # noqa: BLE001 - the reason is what a reader of the run needs
        r.check(False, "settings: the screen draws itself", str(e))
        return

    # The rows the first screen carries, under the names data/wording.json gives them: the
    # languages the reader knows and how the card writes a sound, and nothing that chooses
    # what the card shows, since it always shows both.
    for row in ("on", "narrow", "mine", "known", "side", "apps", "accent", "theme"):
        r.check(row in screen["rows"], f"settings: the screen has the {row} row",
                str(screen["rows"]))
    for row in ("learning", "ipa", "translate", "layer", "density", "stress"):
        r.check(row not in screen["rows"], f"settings: the screen has no {row} row",
                str(screen["rows"]))
    for row in ("mine", "known", "narrow"):
        r.check(words["rows"][row]["name"] in screen["names"],
                f"settings: the {row} row is called {words['rows'][row]['name']}",
                str(screen["names"][:12]))

    # The palettes that have the side in force, which is never none and never all eight: four
    # of them carry one side only, and offering a light one to a reader reading in the dark is
    # offering a choice that cannot be honoured.
    themes = 0
    try:
        with View() as view:
            view.evaluate("document.querySelector('[data-row=theme]').click()")
            time.sleep(1)
            themes = view.evaluate(
                "document.querySelectorAll('[data-row=palettes] [data-choice]').length") or 0
            view.evaluate("window.phonetixBack()")
    except Exception as e:  # noqa: BLE001
        themes = f"the appearance screen did not open ({e})"
    r.check(4 <= themes <= 7, "settings: the palettes for this side are offered", str(themes))
    # And the tokens reached it: a screen with no ground colour is a screen drawn in nothing,
    # which is what a missing stylesheet looks like.
    r.check("rgba(0, 0, 0, 0)" not in screen["ground"],
            "settings: the screen is painted in the product's own colours",
            screen["ground"])

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))


def wording():
    """The words both platforms are written out of, read where they are authored.

    A check that spells a label itself is a third copy of it, and the one that goes stale
    without anything failing.
    """
    with open(os.path.join(ROOT, "data", "wording.json")) as f:
        return json.load(f)


def check_switch_in_ui(r, dev):
    """The switch on the screen is the switch the overlay obeys.

    Worked the way a reader works it - pressed, not stored behind its back - and then asked of
    the settings the service reads, because a switch that moves and changes nothing is the
    failure this is here to catch.
    """
    shell("am", "start", "-n", "io.github.nanbiz.phonetix/.MainActivity",
          "--activity-reorder-to-front")
    time.sleep(3)
    try:
        with View() as view:
            before = view.evaluate(
                "(document.querySelector('[data-row=on] input') || {}).checked")
            if not r.check(before is not None, "settings: the screen carries the switch",
                           "no switch in the view"):
                return
            view.evaluate("(document.querySelector('[data-row=on] input') || {}).click?.()")
            time.sleep(2)
            after = view.evaluate(
                "(document.querySelector('[data-row=on] input') || {}).checked")
            r.check(after is not None and after != before,
                    "settings: the switch moves when it is pressed", f"{before} -> {after}")
            # And the app kept it: the screen writes through the bridge to the same store the
            # service reads, so what the reader pressed is what the overlay is told.
            stored = shell("run-as", "io.github.nanbiz.phonetix", "cat",
                           "shared_prefs/phonetix.settings.xml")
            r.check(
                f'name="enabled" value="{str(bool(after)).lower()}"' in stored,
                "settings: the switch is written where the service reads it",
                stored[-200:] if stored else "nothing stored",
            )
            # Left as it was found, so the checks after this one read the screen they expect.
            if after != before:
                view.evaluate("(document.querySelector('[data-row=on] input') || {}).click?.()")
                time.sleep(1)
    except Exception as e:  # noqa: BLE001
        r.check(False, "settings: the screen carries the switch", str(e))


def main():
    dev = Device()
    if not dev.enable_service():
        print("FAIL - the accessibility service will not start")
        sys.exit(1)
    # The reader's own choices, put back to what this suite measures against: a run that
    # followed one leaving a language to read into behind is measuring that run's settings.
    if not known_on(dev, density=1, target="none", allApps=1, seconds=60):
        print("FAIL - the service knows no word on the test page, so there is nothing to measure")
        sys.exit(1)

    r = Results()
    # Each part of the product, under the name it is printed by, so one of them can be run on
    # its own.
    parts = (
        ("apps", "which apps", (check_scope,)),
        ("switch", "the master switch", (check_switch,)),
        ("language", "the language of the page", (check_language,)),
        ("real", "an app nobody wrote for this test", (check_a_real_app,)),
        ("button", "the button that switches it off", (check_accessibility_button,)),
        ("screen", "the app's own screen", (check_settings_screen, check_switch_in_ui)),
    )
    wanted = [a for a in sys.argv[1:] if not a.startswith("-")]
    unknown = [a for a in wanted if a not in {name for name, _, _ in parts}]
    if unknown:
        raise SystemExit(
            f"no such part: {', '.join(unknown)}. "
            f"There is {', '.join(name for name, _, _ in parts)}."
        )
    for name, said, checks in parts:
        if wanted and name not in wanted:
            continue
        print(said)
        for check in checks:
            check(r, dev)

    if os.environ.get("PHONETIX_LIST"):
        for name in r.asked:
            print(f"  ran: {name}")
    print(f"\n{r.passed}/{r.total} checks passed")
    if r.failures:
        print(f"\nFAIL - {len(r.failures)} problem(s):")
        for f in r.failures:
            print("   ", f)
        sys.exit(1)
    print("\nPASS - the apps, the switch, the language, the button and the screen all do as they say")


if __name__ == "__main__":
    main()
