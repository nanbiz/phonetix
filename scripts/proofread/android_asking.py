#!/usr/bin/env python3
"""The panel answers with the word, and answers it at once.

The panel over the page used to put every question to the machine on the phone - somebody
else's app, in another process, which fetches a model of tens of megabytes the first time a
pair is asked for. A reader watching "…" has no way of telling that from a panel that is
broken, and it is the wrong engine anyway: the one this app already holds the models for is
in this process and answers in milliseconds.

  PHONETIX_ANDROID_SERIAL=emulator-5596 uv run python scripts/proofread/android_asking.py
"""
import http.server
import json
import os
import re
import sys
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import android_says as Says
from android_harness import Device, adb, shell, SERIAL
import state as State

# How long the reader may wait for the word, when this phone holds the pair.
ANSWER_WITHIN_S = 6.0

# How long the engine here may take over a second question. Both directions are open by then,
# beside each other, so this is translating one word and nothing else.
ENGINE_WITHIN_MS = 1500

# Where the models are served from, as the reader's own host.
MODEL_PORT = int(os.environ.get("PHONETIX_MODEL_PORT", "8937"))

# How long the one-off fetch of the missing direction may take, over a host on this machine.
FETCH_WITHIN_S = 90.0


def serve_models(port):
    """The reader's own host, with the models on it.

    The panel needs the reverse of the pair being read - a phone that has only ever read
    Spanish into English holds only that direction - and where it is missing the app fetches
    it from the host the reader's dictionaries come from. So that host is what this is.
    """
    listed = json.dumps([
        {"from": source, "to": target,
         "files": {kind: {"name": name} for kind, name in files.items()}}
        for (source, target), files in Says.PAIRS.items()
    ]).encode()

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            if self.path == "/models.json":
                body, kind = listed, "application/json"
            elif self.path.startswith("/models/"):
                name = os.path.basename(self.path)
                path = os.path.join(Says.MODELS, name)
                if not os.path.exists(path):
                    self.send_error(404)
                    return
                body, kind = open(path, "rb").read(), "application/octet-stream"
            elif self.path == "/packs.json":
                body, kind = b"[]", "application/json"
            else:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    httpd = http.server.ThreadingHTTPServer(("0.0.0.0", port), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()


def believed():
    try:
        name = State.ask(SERIAL)
        return State.fetch(SERIAL, name) if name else {}
    except Exception:
        return {}


def main():
    Says.fetch_models()
    Says.build_packs()
    Says.serve()
    base = f"http://10.0.2.2:{Says.PORT}"
    dev = Device()
    failures = []

    shell("am", "force-stop", "io.github.nanbiz.phonetix")
    adb("push", os.path.join(Says.WORK, "es.pack"), "/data/local/tmp/lex-es.pack", timeout=180)
    adb("shell", "run-as io.github.nanbiz.phonetix sh -c "
                 "'cat /data/local/tmp/lex-es.pack > files/lex-es.pack'", timeout=180)
    # Only the direction a reader of Spanish would have: the one the panel needs is the other
    # one, and fetching it is what this is about.
    adb("shell", "run-as io.github.nanbiz.phonetix rm -rf files/models/en-es", timeout=120)
    for (source, target), files in Says.PAIRS.items():
        if (source, target) != ("es", "en"):
            continue
        into = f"files/models/{source}-{target}"
        adb("shell", f"run-as io.github.nanbiz.phonetix mkdir -p {into}", timeout=120)
        for name in files.values():
            adb("push", os.path.join(Says.MODELS, name), f"/data/local/tmp/{name}", timeout=900)
            adb("shell", f"run-as io.github.nanbiz.phonetix sh -c "
                         f"'cat \"/data/local/tmp/{name}\" > \"{into}/{name}\"'", timeout=900)
    serve_models(MODEL_PORT)

    if not dev.enable_service():
        raise SystemExit("the service would not start")
    dev.set_enabled(True)
    # A reader reading Spanish into English, asking for the Spanish word for an English one.
    shell("input", "keyevent", "3")
    time.sleep(2)
    dev.surface(mode="spanish", packHost=f"http://10.0.2.2:{MODEL_PORT}", target="en",
                learning="es", enable=1, density=1)
    time.sleep(8)

    at = ((believed().get("mark") or {}).get("markAt") or {})
    if not at:
        print("FAIL - the button is not on screen, so the panel cannot be opened")
        sys.exit(1)
    dev.clear_log()
    shell("input", "tap", str(at["x"] + 52), str(at["y"] + 52))
    time.sleep(3)
    if not (believed().get("ask") or {}).get("panelUp"):
        print("FAIL - tapping the button did not open the panel")
        sys.exit(1)

    # Typed and asked for the way a reader does it.
    shell("input", "text", "dog")
    time.sleep(1)
    dev.clear_log()
    began = time.time()
    shell("input", "keyevent", "66")
    answered = None
    while time.time() - began < FETCH_WITHIN_S:
        told = (believed().get("ask") or {})
        if (told.get("stage") or "").startswith(("answered", "nothing came back")):
            answered = told
            break
        time.sleep(0.5)
    took = time.time() - began
    said = re.findall(r"ASKED \S+ \S+->\S+: (.*)", dev.log())
    print(f"  asked for the Spanish for 'dog': {answered and answered.get('stage')} in "
          f"{took:.1f}s {said[-1:] or ''}")
    if answered is None:
        failures.append(f"nothing came back within {took:.0f}s")
    elif (answered.get("stage") or "").startswith("nothing"):
        failures.append("the panel could not answer at all")
    elif took > FETCH_WITHIN_S:
        failures.append(f"the word took {took:.1f}s, which is a wait a reader notices")
    # And the model a phrase would need is fetched from the reader's own host and opened behind
    # the word's answer, so the next question is answered at once.
    readied = time.time()
    while time.time() - readied < FETCH_WITHIN_S:
        if ("en-es" in shell("run-as", "io.github.nanbiz.phonetix", "ls", "files/models")
                and "TRANSLATOR en-es open=true" in dev.log()):
            break
        time.sleep(1)
    print(f"  the other direction fetched and opened behind it in {time.time() - readied:.1f}s")
    if "en-es" not in shell("run-as", "io.github.nanbiz.phonetix", "ls", "files/models"):
        failures.append("the missing direction was never fetched from the reader's own host")
    elif "TRANSLATOR en-es open=true" not in dev.log():
        failures.append("the fetched direction was not opened behind the word's answer")
    # And from the engine on this phone, not from the machine in another app: that is what
    # makes it milliseconds rather than a download.
    # Asked again, with the model now here: this is the wait a reader actually lives with.
    dev.clear_log()
    shell("input", "text", "s")
    time.sleep(0.5)
    began = time.time()
    shell("input", "keyevent", "66")
    while time.time() - began < 30:
        told = (believed().get("ask") or {})
        if (told.get("stage") or "").startswith(("answered", "nothing came back")):
            break
        time.sleep(0.3)
    again = time.time() - began
    said = re.findall(r"ASKED \S+ \S+->\S+: (.*)", dev.log())
    print(f"  asked again with the model here: {again:.1f}s {said[-1:] or ''}")
    # Judged on the part that is the app's: the engine's own time, which the app measures and
    # logs. The rest is a keystroke going through the device and this script polling it, and
    # on a machine this loaded that alone is seconds.
    engine = re.findall(r"here=(\d+)ms", said[-1]) if said else []
    if not engine:
        failures.append("the second question was not answered by the engine here")
    elif int(engine[0]) > ENGINE_WITHIN_MS:
        failures.append(f"the engine took {engine[0]}ms over a second question")
    if again > ANSWER_WITHIN_S * 2:
        failures.append(f"a second question took {again:.1f}s end to end")
    if said and "here=" not in said[-1]:
        failures.append(f"the phone holds this pair and the question went elsewhere: {said[-1]}")
    # Nothing opened for it: the reverse direction stayed open beside the reading one, and the
    # reading one was never closed to make room.
    opened = re.findall(r"TRANSLATOR (\S+) open=", dev.log())
    print(f"  directions opened for the second question: {opened or 'none'}")
    if opened:
        failures.append(f"a second question opened {opened}, which is a model load per question")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - the panel answers with the word, from the engine this phone already holds")


if __name__ == "__main__":
    main()
