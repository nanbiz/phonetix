#!/usr/bin/env python3
"""The translator panel's microphone, said into the way a reader says something, in both engines.

The panel is opened from the keyboard over a page and its microphone pressed. The first press
opens the extension's own page to ask for the microphone, once, and the browser's real prompt is
answered on screen; nothing is asked of the site. What is said then is written into the field,
in the language it was said in, and answered like anything typed.

What is said is people's recordings of sentences (Tatoeba). Chromium plays one as its microphone.
Firefox has no such switch, so it is given a microphone of the check's own: a PulseAudio server
started for the check alone, on a socket under /tmp and never the desktop's, whose one source is
a pipe the recording is written into once Firefox is listening. Whatever either browser plays
goes to that server's silent sink.

The model is the one the product fetches, served from a copy kept in .cache/whisper and fetched
from Hugging Face the first time.

  nix shell nixpkgs#xorg.xvfb nixpkgs#xdotool nixpkgs#ffmpeg nixpkgs#imagemagick nixpkgs#pulseaudio -c uv run scripts/proofread/speak.py [chrome|firefox]
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
import tempfile
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
# repository. Each is said in the language the arrow comes from, turned first for the one being
# learned, and answered in the other.
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


# How long writing a short question down may take once the reader has stopped, the model
# already held: what the panel was measured at with room to spare, on Chromium's threads and on
# Firefox's one.
QUICK = {"chrome": 4.0, "firefox": 8.0}


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
    """The model at a host of the check's own, as Hugging Face serves it, and the encoder as the
    speech-v1 release does: each file fetched from there once and kept."""

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
                origin = ("https://github.com/nanbiz/phonetix/releases/download"
                          if path.startswith("speech-v1/") else "https://huggingface.co")
                try:
                    urllib.request.urlretrieve(f"{origin}/{path}", kept + ".part")
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


# The model's large files, fetched into the check's copy before any browser asks, so the time a
# question takes is the panel's and not the time a first download takes.
LARGE = [
    "onnx-community/whisper-small/resolve/36050c46d777d46dc4b5f43f6d90574fc38f8732/onnx/decoder_model_merged_quantized.onnx",
    "speech-v1/whisper-small-encoder-q8.onnx",
]


class Microphone:
    """A PulseAudio server of the check's own whose microphone says what it is told to.

    Silence, written at the pace it is played, until [say] is called; then the recording; then
    silence again. Started without the desktop's session bus or runtime directory, so nothing
    the reader's own audio uses is touched."""

    RATE = 48000
    PIECE = RATE // 50  # twenty milliseconds

    def __init__(self):
        self.where = tempfile.mkdtemp(prefix="phonetix-pulse-")
        socket_path = os.path.join(self.where, "pulse.sock")
        self.pipe = os.path.join(self.where, "speech")
        config = os.path.join(self.where, "default.pa")
        with open(config, "w") as f:
            f.write(f"load-module module-native-protocol-unix socket={socket_path} auth-anonymous=1\n"
                    f"load-module module-pipe-source source_name=speech file={self.pipe}"
                    f" format=s16le rate={self.RATE} channels=1\n"
                    "load-module module-null-sink sink_name=quiet\n"
                    "set-default-source speech\nset-default-sink quiet\n")
        env = {k: v for k, v in os.environ.items()
               if k not in ("PULSE_SERVER", "DBUS_SESSION_BUS_ADDRESS")}
        env["XDG_RUNTIME_DIR"] = self.where
        env["HOME"] = self.where
        self.server = subprocess.Popen(
            ["pulseaudio", "-n", "--daemonize=no", "--exit-idle-time=-1", "--disallow-exit",
             "-F", config, "--use-pid-file=no", "--log-target=stderr"],
            env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(100):
            if os.path.exists(self.pipe) and os.path.exists(socket_path):
                break
            time.sleep(0.1)
        else:
            raise SystemExit("the check's own audio server did not start")
        self.address = f"unix:{socket_path}"
        self.queue = []
        self.lock = threading.Lock()
        self.running = True
        self.player = threading.Thread(target=self._play, daemon=True)
        self.player.start()

    def say(self, wav):
        """Say this recording, once."""
        with open(wav, "rb") as f:
            data = f.read()
        # The samples after the WAV header, which ffmpeg wrote as 48 kHz mono 16-bit.
        at = data.index(b"data") + 8
        with self.lock:
            self.queue.append(data[at:])

    def _play(self):
        silence = bytes(self.PIECE * 2)
        with open(self.pipe, "wb", buffering=0) as out:
            start = time.time()
            sent = 0
            while self.running:
                with self.lock:
                    speech = self.queue.pop(0) if self.queue else None
                for at in range(0, len(speech), self.PIECE * 2) if speech else [None]:
                    piece = speech[at:at + self.PIECE * 2] if speech else silence
                    # The server gone is the end of playing, not an error: written to after it
                    # quit, the pipe broke while the interpreter was shutting down, and the
                    # traceback it printed then crashed it after the check had passed.
                    try:
                        out.write(piece)
                    except BrokenPipeError:
                        return
                    sent += len(piece) // 2
                    # At the pace it is played, so what is said arrives when it is said.
                    ahead = sent / self.RATE - (time.time() - start)
                    if ahead > 0.1:
                        time.sleep(ahead - 0.1)

    def close(self):
        self.running = False
        self.player.join(timeout=2)
        self.server.terminate()
        shutil.rmtree(self.where, ignore_errors=True)


def warm():
    for path in LARGE:
        urllib.request.urlopen(f"http://127.0.0.1:{MODEL_PORT}/{path}", timeout=1800).read()


def check(engine, said, wav, microphone, again=True, by_key=False):
    """One browser asked one sentence; [again] goes on to press the microphone twice more.

    [by_key] asks it with the microphone's own key rather than the panel's key and a press on
    its microphone: the key opens the panel hearing."""
    failures = []
    if engine == "chrome":
        # The recording as the microphone, played once; the prompt is the browser's own.
        browser = panel.Chrome(extra_args=[
            "--use-fake-device-for-media-stream",
            f"--use-file-for-fake-audio-capture={wav}%noloop",
        ])
    else:
        browser = panel.Firefox()
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

        def press_on(does):
            box = in_panel("""(root) => {
                const el = root.querySelector('[data-does=""" + does + """]');
                if (!el) return null;
                const r = el.getBoundingClientRect();
                return {x: r.x + r.width / 2, y: r.y + r.height / 2};
            }""")
            if not box:
                return False
            browser.press(box["x"], box["y"])
            return True

        def press_mic():
            return press_on("speak")

        state = "(root) => (root.querySelector('[data-does=speak]') || {}).dataset?.hearing ?? null"
        field = "(root) => root.querySelector('.ask-field input').value"

        command = "speak" if by_key else "translator"
        # The translator's key is heard by the page (content/index.ts), the microphone's is the
        # browser's own.
        key = "alt+shift+t" if command == "translator" else \
            browser.manifest["commands"][command]["suggested_key"]["default"].lower()
        keys(key)
        if wait("(root) => !!root.querySelector('[data-ask]')", lambda got: got is True) is not True:
            return [f"the panel did not open from {key}"]
        if not by_key and in_panel(state) != "idle":
            failures.append(f"the microphone is not drawn waiting in the panel: {in_panel(state)!r}")

        # Said in the language the arrow comes from: turned to the one being learned first.
        if said["way"][0] != "en":
            press_on("turn")
            time.sleep(0.5)

        # The first press: the extension's own page asks, under the extension's name, and the
        # site is asked nothing. The microphone's key has pressed it already.
        if not by_key:
            press_mic()
        # Looked for until it is drawn: a browser busy with the rest of a run takes longer to
        # put its prompt up than one doing nothing else.
        found = None
        for _ in range(12):
            time.sleep(1)
            found = allow_button(engine, shot("asked"))
            if found:
                break
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
            if by_key:
                keys(key)
            else:
                press_mic()
        listening = wait(state, lambda got: got in ("listening", "thinking"), seconds=15, gap=0.25)
        if engine == "firefox":
            microphone.say(wav)
        print(f"  {engine}: after allowing it once: {listening!r}")
        if listening not in ("listening", "thinking"):
            failures.append(f"the microphone did not start after it was allowed: {listening!r}")
        site = browser.on_page(
            "navigator.permissions.query({name: 'microphone'}).then(s => s.state)")
        if site != "prompt":
            failures.append(f"the site itself was given the microphone: {site!r}")

        # Then it ends by itself at the pause, and what was said is written into the field, in
        # about as long as a reader waits for an answer.
        # One watch over the whole question: the last moment it was still listening is the
        # pause, and the first moment it is idle again is the words being there.
        heard_until = time.time()
        done = None
        end = time.time() + 240
        seen = []
        while time.time() < end:
            done = in_panel(state)
            if not seen or seen[-1][1] != done:
                seen.append((round(time.time() - heard_until, 2), done))
            if done == "listening":
                heard_until = time.time()
                if len(seen) == 1:
                    shot("hearing")
                    seen.append((0, "pictured"))
            if done == "idle":
                break
            time.sleep(0.05)
        took = time.time() - heard_until
        print(f"  {engine}: states {seen}")
        print(f"  {engine}: from the pause to the words: {took:.1f}s")
        if took > QUICK[engine]:
            failures.append(f"writing it down took {took:.1f}s, more than {QUICK[engine]}s")
        heard = in_panel(field)
        print(f"  {engine}: written down: {heard!r}")
        if done != "idle":
            failures.append(f"the microphone never stopped: {done!r}")
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
            # Nothing was said that time, only the check's silence: nothing is written over
            # the question already there.
            if in_panel(field) != heard:
                failures.append(f"silence was written down as {in_panel(field)!r}")
            tabs_after = browser.ext(f"{api}.tabs.query({{}}).then(t => t.length)")
            if tabs_after != tabs_before:
                failures.append("the second press asked for the microphone again")
    finally:
        browser.close()
    return failures


def main():
    for tool in ("Xvfb", "xdotool", "ffmpeg", "import", "pulseaudio"):
        if not shutil.which(tool):
            raise SystemExit(f"{tool} is not on PATH: run this inside `nix shell nixpkgs#xorg.xvfb "
                             "nixpkgs#xdotool nixpkgs#ffmpeg nixpkgs#imagemagick nixpkgs#pulseaudio`")
    engines = sys.argv[1:] or ["chrome", "firefox"]
    wavs = [recording(said) for said in SENTENCES]
    says.fetch_models()
    says.build_packs()
    says.serve()
    serve_model()
    warm()
    microphone = Microphone()
    # Every browser started from here plays into the check's silent sink, and Firefox hears the
    # check's microphone.
    os.environ["PULSE_SERVER"] = microphone.address
    shown, server = display()
    os.environ["DISPLAY"] = shown
    os.environ.pop("WAYLAND_DISPLAY", None)
    failures = []
    try:
        for engine in engines:
            # Each sentence in a browser of its own, since a first question in each is asked
            # for the microphone the way a reader's first is.
            for at, (said, wav) in enumerate(zip(SENTENCES, wavs)):
                found = check(engine, said, wav, microphone, again=at == 0, by_key=at == 1)
                failures += [f"{engine}: {line}" for line in found]
    finally:
        panel.close_display(server)
        microphone.close()
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
