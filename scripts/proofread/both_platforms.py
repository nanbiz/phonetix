"""The same word, through the browser's core and the phone's, compared.

The whole arrangement rests on one claim: what a word means is decided in one place, compiled
twice, so a reader on a phone and a reader in a browser cannot be told different things. That
claim is not proved by either side working. It is proved by both being asked the same question
about the same bytes and giving the same answer, which is what this does.

It builds two packs from lines of the dump, runs them through the WebAssembly build under
node and through the native library on a device, and compares the answers character for
character.

  PHONETIX_ANDROID_SERIAL=emulator-5600 uv run python scripts/proofread/both_platforms.py
"""
import json
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from android_harness import SERIAL, Device, adb, shell

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
CORE = os.path.join(ROOT, "core")
WORK = os.environ.get("PHONETIX_WORK", "/tmp/phonetix-both")
# Where the app can actually read a file, which is not the shared storage.
ON_DEVICE = "/data/user/0/io.github.nanbiz.phonetix/files"

# One word per branch of the cascade that two packs can reach on their own: a lemma that
# joins, a form of it, a gloss of several terms, a spelling that is two words, and a word
# neither pack holds.
WORDS = ["perro", "perros", "camino", "banco", "murciélago"]

# A control. Asking the browser about a different reader's language makes the two sides
# genuinely disagree, and a check that cannot be made to fail is not evidence: with this set,
# every word has to be reported as a disagreement.
DRIFT = "en" if os.environ.get("PHONETIX_DRIFT") else "de"


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=900, **kw)


def build_packs():
    """Two packs, from lines of the dump, exactly as CI would make them."""
    os.makedirs(WORK, exist_ok=True)
    for lang in ("es", "de"):
        source = os.path.join(CORE, "packbuild", "fixtures", f"{lang}.jsonl")
        out = os.path.join(WORK, f"{lang}.lexpack")
        got = run(["cargo", "run", "-q", "-p", "packbuild", "--", lang, source, out], cwd=CORE)
        if got.returncode != 0:
            raise SystemExit(f"packbuild failed for {lang}: {got.stderr[-400:]}")
    return os.path.join(WORK, "es.lexpack"), os.path.join(WORK, "de.lexpack")


def in_the_browser(es, de):
    """What the WebAssembly build says, run under node."""
    out = os.path.join(WORK, "wasm")
    got = run(["cargo", "build", "-q", "-p", "lexcore-wasm",
               "--target", "wasm32-unknown-unknown", "--release"], cwd=CORE)
    if got.returncode != 0:
        raise SystemExit(f"the wasm build failed: {got.stderr[-400:]}")
    wasm = os.path.join(CORE, "target/wasm32-unknown-unknown/release/lexcore_wasm.wasm")
    got = run(["wasm-bindgen", "--target", "nodejs", "--out-dir", out, wasm])
    if got.returncode != 0:
        raise SystemExit(f"wasm-bindgen failed: {got.stderr[-400:]}")
    script = os.path.join(WORK, "ask.js")
    with open(script, "w") as f:
        f.write(
            "const fs = require('fs');\n"
            f"const {{ Core }} = require({out + '/lexcore_wasm.js'!r});\n"
            "const core = new Core();\n"
            "core.openPack(fs.readFileSync(process.argv[2]));\n"
            "core.openPack(fs.readFileSync(process.argv[3]));\n"
            "for (const word of process.argv.slice(4)) {\n"
            # The whole question the cascade is asked: which accent to read in and what the
            # word before it was. Both empty here, so the two sides are asked the same thing
            # and neither is answering a narrower question than the other.
            "  console.log(word + '\\t' + core.lookUp(word, 'es', "
            f"{DRIFT!r}, '', ''));\n"
            "}\n"
        )
    got = run(["node", script, es, de, *WORDS])
    if got.returncode != 0:
        raise SystemExit(f"node failed: {got.stderr[-400:]}")
    return dict(line.split("\t", 1) for line in got.stdout.strip().splitlines() if "\t" in line)


def on_the_phone(dev, es, de):
    """What the native library says, asked through the same JNI the overlay uses."""
    for path, name in ((es, "es.lexpack"), (de, "de.lexpack")):
        adb("push", path, f"/data/local/tmp/{name}", timeout=120)
        adb("shell", f"run-as io.github.nanbiz.phonetix sh -c "
                     f"'cat /data/local/tmp/{name} > files/{name}'", timeout=120)
    answers = {}
    for word in WORDS:
        shell("am", "force-stop", "io.github.nanbiz.phonetix")
        time.sleep(1)
        dev.clear_log()
        shell("am", "start", "-n", "io.github.nanbiz.phonetix/.debug.DebugSurfaceActivity",
              "--es", "lexPack", f"{ON_DEVICE}/es.lexpack",
              "--es", "lexPack2", f"{ON_DEVICE}/de.lexpack",
              "--es", "lexWord", word, "--es", "lexSource", "es", "--es", "lexTarget", "de")
        time.sleep(2.5)
        found = re.findall(r"LEXWORD (\{.*\})", dev.log())
        if found:
            answers[word] = found[-1]
    return answers


def main():
    es, de = build_packs()
    browser = in_the_browser(es, de)
    dev = Device()
    phone = on_the_phone(dev, es, de)

    wrong = 0
    for word in WORDS:
        here, there = browser.get(word), phone.get(word)
        if here is None or there is None:
            print(f"  {word}: no answer from "
                  f"{'the browser' if here is None else 'the phone'}")
            wrong += 1
            continue
        if here == there:
            state = json.loads(here).get("state")
            says = ", ".join(json.loads(here).get("says") or []) or "nothing"
            print(f"  {word:8} both say {state:9} {says}")
        else:
            print(f"  {word}: the two disagree\n      browser {here}\n      phone   {there}")
            wrong += 1
    print(f"\n{len(WORDS) - wrong} of {len(WORDS)} words answered the same on both")
    return 1 if wrong else 0


if __name__ == "__main__":
    sys.exit(main())
