#!/usr/bin/env python3
"""A dictionary rebuilt and published again reaches a phone that already holds the old one.

The phone kept a downloaded pack for good, so the packs rebuilt with narrow transcriptions in
them would have reached nobody who had one already. This puts the first of two Spanish packs on
the phone, publishes the second at a host the phone is pointed at, starts the app again the
way a phone does after an update or a reboot, and checks that the pack the phone holds is now
the second, byte for byte.

  PHONETIX_ANDROID_SERIAL=emulator-5596 uv run python scripts/proofread/android_pack_update.py
"""
import hashlib
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pack_update as Update  # noqa: E402  the two packs and the host that serves them
from android_harness import Device, adb, shell  # noqa: E402

PKG = "io.github.nanbiz.phonetix"


def held_sha():
    out = adb("shell", f"run-as {PKG} sha256sum files/lex-es.pack", timeout=60)
    return (out or "").split()[0] if out else ""


def main():
    os.makedirs(Update.WORK, exist_ok=True)
    first, second = Update.build("first"), Update.build("second", Update.ADDED)
    Update.serving["pack"] = second
    Update.serve()
    want = hashlib.sha256(open(second, "rb").read()).hexdigest()
    had = hashlib.sha256(open(first, "rb").read()).hexdigest()
    dev = Device()
    failures = []
    adb("reverse", f"tcp:{Update.PORT}", f"tcp:{Update.PORT}")
    try:
        shell("am", "force-stop", PKG)
        adb("push", first, "/data/local/tmp/lex-es.pack", timeout=120)
        adb("shell", f"run-as {PKG} sh -c 'cat /data/local/tmp/lex-es.pack > files/lex-es.pack'",
            timeout=120)
        print(f"  held before: {held_sha()[:12]} (the first pack is {had[:12]})")
        # Pointed at the host that publishes the second, then started afresh.
        dev.surface(mode="spanish", packHost=f"http://127.0.0.1:{Update.PORT}", target="en")
        shell("am", "force-stop", PKG)
        dev.clear_log()
        if not dev.enable_service():
            raise SystemExit("the service would not start")
        dev.set_enabled(True)
        dev.surface(mode="spanish", target="en")
        now = ""
        for _ in range(60):
            now = held_sha()
            if now == want:
                break
            time.sleep(1)
        print(f"  held after a start: {now[:12]} (the published pack is {want[:12]})")
        print(f"  {dev.lines('FETCHED fresh').strip()[-120:]}")
        if now != want:
            failures.append("the phone kept the old pack after a newer one was published")
    finally:
        dev.surface(mode="spanish", packHost="none")
        adb("reverse", "--remove", f"tcp:{Update.PORT}")

    if failures:
        print("\nFAIL")
        for line in failures:
            print(f"  {line}")
        sys.exit(1)
    print("\nPASS - a pack published again replaces the one the phone holds")


if __name__ == "__main__":
    main()
