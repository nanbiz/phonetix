#!/usr/bin/env python3
"""The translator panel's microphone, said into the way a reader says something, in both engines.

The panel is opened from the keyboard over a page and its microphone pressed. The first press
opens the extension's own page to ask for the microphone, once, and the browser's real prompt is
answered on screen; nothing is asked of the site. What is said then is written into the field,
in the language it was said in, and answered like anything typed.

What is said is a person's recording of a Spanish sentence (Tatoeba), played to Chromium as its
microphone. Firefox can only be given a tone as a microphone, so there the check is that the
recording reaches the extension through the toolbar popup, ends by itself, is written down by
the same model, and leaves the panel ready for the next question; what the model makes of real
speech is checked on Chromium.

The model is the one the product fetches, served from a copy kept in .cache/whisper and fetched
from Hugging Face the first time.

  nix shell nixpkgs#xorg.xvfb nixpkgs#xdotool nixpkgs#ffmpeg nixpkgs#imagemagick -c uv run scripts/proofread/speak.py [chrome|firefox]
"""
# /// script
# dependencies = ["pillow"]
# ///
import http.server
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import unicodedata
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import says  # noqa: E402  the packs, the translation models and the page
import panel  # noqa: E402  the two browsers, on a display of the check's own
from panel import IN_PANEL, display, focus_window, keys  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
CACHE = os.path.join(ROOT, ".cache")
MODELS = os.path.join(CACHE, "whisper")
SPEECH = os.path.join(CACHE, "speech")
MODEL_PORT = int(os.environ.get("PHONETIX_SPEECH_PORT", "8941"))
SHOTS = os.environ.get("PHONETIX_SHOTS", "/tmp/phonetix-speak")

# Sentences people said, and what they said, one in the language being learned and one in the
# reader's own: Tatoeba's recordings, by native speakers, kept in .cache and never in the
# repository. Each is answered in the other language, which is how the language it was said in
# is seen to have been heard.
SENTENCES = [
    {"audio": 444306, "text": "La línea está ocupada.", "lang": "es", "way": ["es", "en"]},
    {"audio": 39352, "text": "Where is the station?", "lang": "en", "way": ["en", "es"]},
]
# What each browser's prompt draws its allowing button in: Chromium's "Allow while visiting the
# site" is the first of its three, Firefox's "Allow" its one blue button. Found on screen by
# colour, since where the prompt puts it depends on how long the extension's name is.
ALLOW = {"chrome": (0, 74, 119), "firefox": (61, 174, 233)}


def allow_button(engine, picture):
    """The middle of the first button on screen in the allowing colour, top to bottom."""
    from PIL import Image
    image = Image.open(picture).convert("RGB")
    want = ALLOW[engine]
    width, height = image.size
    pixels = image.load()
    for y in range(0, height, 2):
        row = [x for x in range(0, width, 2)
               if sum(abs(a - b) for a, b in zip(pixels[x, y], want)) < 12]
        if len(row) > 20:
            # The button's height, from where it starts down to where the colour ends.
            x = (row[0] + row[-1]) // 2
            bottom = y
            while bottom < height and sum(abs(a - b) for a, b in zip(pixels[x, bottom], want)) < 40:
                bottom += 1
            return x, (y + bottom) // 2
    return None


def plain(text):
    """Text compared the way a listener hears it: no accents, no punctuation, no case."""
    text = unicodedata.normalize("NFD", text or "")
    text = "".join(c for c in text if not unicodedata.combining(c))
    return re.sub(r"[^a-z ]", "", text.lower()).split()


def recording(said):
    """The sentence as a WAV Chromium plays as its microphone: a second of quiet before, as a
    room has, and three after, which is the pause that ends a question."""
    os.makedirs(SPEECH, exist_ok=True)
    mp3 = os.path.join(SPEECH, f"{said['audio']}.mp3")
    wav = os.path.join(SPEECH, f"{said['audio']}.wav")
    if not os.path.exists(mp3):
        urllib.request.urlretrieve(f"https://tatoeba.org/en/audio/download/{said['audio']}", mp3)
    if not os.path.exists(wav):
        subprocess.run(
            ["ffmpeg", "-loglevel", "error", "-y", "-i", mp3, "-af",
             "adelay=1000:all=1,apad=pad_dur=3", "-ac", "1", "-ar", "48000",
             "-c:a", "pcm_s16le", wav], check=True)
    return wav


def serve_model():
    """The model at a host of the check's own, as Hugging Face serves it: each file fetched from
    there once and kept."""

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            path = self.path.split("?")[0].lstrip("/")
            if ".." in path:
                self.send_error(404)
                return
            kept = os.path.join(MODELS, path)
            if not os.path.exists(kept):
                os.makedirs(os.path.dirname(kept), exist_ok=True)
                try:
                    urllib.request.urlretrieve(f"https://huggingface.co/{path}", kept + ".part")
                    os.replace(kept + ".part", kept)
                except Exception:
                    self.send_error(404)
                    return
            body = open(kept, "rb").read()
            try:
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(body)
            except ConnectionError:
                pass  # a request the browser gave up on, which it asks again

    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", MODEL_PORT), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()


def check(engine, said, wav, again=True):
    """One browser asked one sentence; [again] goes on to press the microphone twice more."""
    failures = []
    if engine == "chrome":
        # The recording as the microphone, played once; the prompt is the browser's own.
        browser = panel.Chrome(extra_args=[
            "--use-fake-device-for-media-stream",
            f"--use-file-for-fake-audio-capture={wav}%noloop",
        ])
    else:
        browser = panel.Firefox(prefs=[("media.navigator.streams.fake", True)])
    try:
        api = "browser" if engine == "firefox" else "chrome"
        browser.ext(
            f"{api}.storage.local.set({{on: true, packBaseUrl: '{panel.BASE}',"
            f" speechBaseUrl: 'http://127.0.0.1:{MODEL_PORT}/', targetLanguage: 'en',"
            f" learning: 'es', recentLanguages: []}})"
            f".then(() => {api}.runtime.sendMessage({{phonetix: 'openPack', data: {{lang: 'es'}}}}))")
        browser.open_page()
        if not focus_window(browser.window_class):
            raise SystemExit(f"{engine} drew no window")
        time.sleep(1)

        def in_panel(body):
            return browser.on_page(IN_PANEL % body)

        def wait(body, want, seconds=30, gap=0.5):
            got = None
            end = time.time() + seconds
            while time.time() < end:
                got = in_panel(body)
                if want(got):
                    return got
                time.sleep(gap)
            return got

        def shot(name):
            os.makedirs(SHOTS, exist_ok=True)
            path = os.path.join(SHOTS, f"{engine}-{name}.png")
            subprocess.run(["import", "-window", "root", path], check=False)
            print(f"  picture: {path}")
            return path

        def press_mic():
            box = in_panel("""(root) => {
                const el = root.querySelector('[data-does=speak]');
                if (!el) return null;
                const r = el.getBoundingClientRect();
                return {x: r.x + r.width / 2, y: r.y + r.height / 2};
            }""")
            if not box:
                return False
            browser.press(box["x"], box["y"])
            return True

        state = "(root) => (root.querySelector('[data-does=speak]') || {}).dataset?.hearing ?? null"
        field = "(root) => root.querySelector('.ask-field input').value"

        keys(browser.manifest["commands"]["translator"]["suggested_key"]["default"].lower())
        if wait("(root) => !!root.querySelector('[data-ask]')", lambda got: got is True) is not True:
            return [f"the panel did not open"]
        if in_panel(state) != "idle":
            failures.append(f"the microphone is not drawn waiting in the panel: {in_panel(state)!r}")

        # The first press: the extension's own page asks, under the extension's name, and the
        # site is asked nothing.
        press_mic()
        time.sleep(3)
        found = allow_button(engine, shot("asked"))
        if not found:
            return failures + ["no prompt to allow the microphone was drawn"]
        if in_panel(state) != "asking":
            failures.append(f"the microphone shows {in_panel(state)!r} while it is being allowed")
        subprocess.run(["xdotool", "mousemove", str(found[0]), str(found[1]), "click", "1"])
        if engine == "firefox":
            # Firefox records in the toolbar popup, which only a press can open, and the press
            # that asked is over by the time the reader answered: it ends there, and the next
            # press hears.
            back = wait(state, lambda got: got == "idle", seconds=15, gap=0.25)
            if back != "idle":
                failures.append(f"allowing the microphone left it at {back!r}")
            focus_window(browser.window_class)
            press_mic()
        listening = wait(state, lambda got: got in ("listening", "thinking"), seconds=15, gap=0.25)
        print(f"  {engine}: after allowing it once: {listening!r}")
        if listening not in ("listening", "thinking"):
            failures.append(f"the microphone did not start after it was allowed: {listening!r}")
        site = browser.on_page(
            "navigator.permissions.query({name: 'microphone'}).then(s => s.state)")
        if site != "prompt":
            failures.append(f"the site itself was given the microphone: {site!r}")

        # Then it ends by itself at the pause, and what was said is written into the field.
        shot("hearing")
        done = wait(state, lambda got: got == "idle", seconds=240, gap=1)
        heard = in_panel(field)
        print(f"  {engine}: written down: {heard!r}")
        if done != "idle":
            failures.append(f"the microphone never stopped: {done!r}")
        if engine == "firefox" and heard:
            # A tone is not speech, and what Whisper makes of a sound that is not ("you",
            # "Thank you") is not a question anybody asked.
            failures.append(f"a tone with nothing said in it was written down as {heard!r}")
        if engine == "chrome":
            want = plain(said["text"])
            got = plain(heard)
            missed = [word for word in want if word not in got]
            if len(missed) > 1:
                failures.append(f"heard {heard!r} for {said['text']!r}")
            answer = wait("(root) => (root.querySelector('[data-said]') || {}).textContent ?? null",
                          lambda got: bool(got), seconds=60)
            way = in_panel("(root) => [root.querySelector('[data-ask]').dataset.from,"
                           " root.querySelector('[data-ask]').dataset.into]")
            print(f"  {engine}: answered {way}: {answer!r}")
            if way != said["way"]:
                failures.append(f"{said['lang']} said was answered {way}, not {said['way']}")
            if not answer:
                failures.append("what was said was never answered")
        shot("answered")

        if not again:
            return failures
        # Pressed again: no page asks a second time, and a second press ends it early.
        tabs_before = browser.ext(f"{api}.tabs.query({{}}).then(t => t.length)") if engine == "firefox" else None
        press_mic()
        again = wait(state, lambda got: got == "listening", seconds=15, gap=0.25)
        if again != "listening":
            failures.append(f"a second press did not hear straight away: {again!r}")
        press_mic()
        ended = wait(state, lambda got: got == "idle", seconds=120, gap=0.5)
        print(f"  {engine}: pressed again while hearing: {ended!r}")
        if ended != "idle":
            failures.append(f"pressing the microphone while it heard did not end it: {ended!r}")
        if engine == "firefox":
            tabs_after = browser.ext(f"{api}.tabs.query({{}}).then(t => t.length)")
            if tabs_after != tabs_before:
                failures.append("the second press asked for the microphone again")
    finally:
        browser.close()
    return failures


def main():
    for tool in ("Xvfb", "xdotool", "ffmpeg", "import"):
        if not shutil.which(tool):
            raise SystemExit(f"{tool} is not on PATH: run this inside `nix shell nixpkgs#xorg.xvfb "
                             "nixpkgs#xdotool nixpkgs#ffmpeg nixpkgs#imagemagick`")
    engines = sys.argv[1:] or ["chrome", "firefox"]
    wavs = [recording(said) for said in SENTENCES]
    says.fetch_models()
    says.build_packs()
    says.serve()
    serve_model()
    shown, server = display()
    os.environ["DISPLAY"] = shown
    os.environ.pop("WAYLAND_DISPLAY", None)
    failures = []
    try:
        for engine in engines:
            # Chromium hears each sentence, in a browser of its own since the recording is the
            # browser's microphone; Firefox hears its tone once.
            for at, (said, wav) in enumerate(zip(SENTENCES, wavs)):
                if engine == "firefox" and at > 0:
                    break
                failures += [f"{engine}: {line}" for line in check(engine, said, wav, again=at == 0)]
    finally:
        server.kill()
    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print(f"\nPASS - {', '.join(engines)}: the panel's microphone is allowed once on the"
          " extension's own page, never by the site, hears a question, ends it at the pause or"
          " at a second press, and writes it into the panel, which answers it")


if __name__ == "__main__":
    main()
