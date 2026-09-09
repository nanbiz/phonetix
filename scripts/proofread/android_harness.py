#!/usr/bin/env python3
"""Talking to a device, and reading what the overlay says it did.

Shared by the overlay test suites. Two things come off the device and they are deliberately
independent of each other:

  * what the service reports it drew, and where (its own account of itself);
  * what the screen actually shows, captured through the emulator console, which is the
    only capture that contains another app's overlay windows.

A test that only ever consults the first is trusting the thing under test. The pixel checks
exist so at least one oracle owes nothing to our own bookkeeping.
"""
import os
import re
import subprocess
import time

PKG = "io.github.nanbiz.phonetix"
SERVICE = f"{PKG}/{PKG}.service.PhonetixAccessibilityService"
SURFACE = f"{PKG}/.debug.DebugSurfaceActivity"
SERIAL = os.environ.get("PHONETIX_ANDROID_SERIAL", "emulator-5600")


def adb(*args, timeout=90):
    return subprocess.run(
        ["adb", "-s", SERIAL] + list(args),
        capture_output=True, text=True, timeout=timeout,
    ).stdout


def wait_until_quiet(limit=1.5, seconds=90):
    """Hold until the device is not still working through somebody else's load.

    A run that puts the device under load and then measures it has to start from the same
    place every time, and it does not: the load a previous run left behind decays over
    minutes, so the same build measured 97% of a movement followed after a long gap and 67%
    when run straight afterwards. Neither was about the build.
    """
    for _ in range(seconds):
        out = shell("uptime")
        m = re.search(r"load average:\s*([\d.]+)", out)
        if m and float(m.group(1)) <= limit:
            return True
        time.sleep(1)
    return False


def shell(*args, timeout=90):
    return adb("shell", *args, timeout=timeout)


class Device:
    """The device, and whatever the overlay last said about itself."""

    def __init__(self):
        self.width, self.height = self._size()

    def _size(self):
        out = shell("wm", "size")
        m = re.search(r"Override size: (\d+)x(\d+)", out) or re.search(r"Physical size: (\d+)x(\d+)", out)
        return (int(m.group(1)), int(m.group(2))) if m else (1080, 1920)

    # ---- setup -------------------------------------------------------------

    def install(self, apk):
        return "Success" in adb("install", "-r", apk, timeout=300)

    def enable_service(self):
        """Grant both permissions. Note an install clears the accessibility one."""
        shell("appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "allow")
        shell("settings", "put", "secure", "enabled_accessibility_services", SERVICE)
        shell("settings", "put", "secure", "accessibility_enabled", "1")
        for _ in range(20):
            if "Phonetix transcriptions" in shell("dumpsys", "accessibility"):
                return True
            time.sleep(0.5)
        return False

    def set_enabled(self, on):
        """The app's own switch, so a test can compare a screen with and without us."""
        shell("am", "start", "-n", f"{PKG}/.MainActivity")
        time.sleep(1.5)

    # ---- driving the test surface -------------------------------------------

    def surface(self, mode="plain", **extras):
        """Bring the test page to the front and give it its instructions.

        Insisting that it is really in front matters: the app's own settings screen is an
        activity of the same app, and with it on top `am start` delivers the intent to the
        page behind it and reports success. A suite then measures a screen that is not the
        one under test - a whole run once passed its geometry checks against the settings
        screen - so the page is asked for again, in its own fresh task, until the device
        agrees that is what the reader is looking at.
        """
        # Reordered to the front, not merely started: the page and the app's own settings
        # screen are separate tasks, and starting a page that already exists behind another
        # task delivers the intent to it and leaves it there, out of sight.
        # A changing extra on every launch: two identical intents in a row can be treated as
        # a duplicate and never delivered, and the page then quietly keeps doing what it was
        # doing while the test waits for something new.
        args = ["am", "start", "-n", SURFACE, "--activity-reorder-to-front",
                "--es", "mode", mode, "--ei", "nonce", str(int(time.time() * 1000) % 100000)]
        for key, value in extras.items():
            # Strings go as strings: the motion profile is named, not numbered.
            flag = "--es" if isinstance(value, str) else "--ei"
            args += [flag, key, str(value)]
        shell(*args)
        # Given a moment to come forward before anything harsher is tried: starting it again
        # in a fresh task destroys the page and builds it anew, which resets where it is
        # scrolled to and leaves the old instance still reporting its own position for a
        # moment - two pages in one log, and a timeline that runs backwards.
        for _ in range(10):
            if "DebugSurfaceActivity" in self.top_activity():
                return
            time.sleep(0.3)
        shell(*(args + ["--activity-clear-task", "--activity-new-task"]))
        for _ in range(10):
            if "DebugSurfaceActivity" in self.top_activity():
                return
            time.sleep(0.3)

    def top_activity(self):
        """Which activity the device says the reader is actually looking at."""
        out = shell("dumpsys", "activity", "activities")
        m = re.search(r"topResumedActivity=ActivityRecord\{\S+ \S+ (\S+)", out)
        return m.group(1) if m else ""

    def clear_log(self):
        adb("logcat", "-c")

    def log(self):
        return adb("logcat", "-d")

    # ---- what the overlay says ----------------------------------------------

    def boxes(self, log=None):
        """The last set of transcriptions the service reported, by word."""
        frames = self.box_frames(log)
        return frames[-1][1] if frames else {}

    def box_frames(self, log=None):
        """Every reported set, as (timestamp, {word: box}) - the overlay's own timeline."""
        out = []
        for line in (log if log is not None else self.log()).splitlines():
            if "BOXES " not in line:
                continue
            rest = line.split("BOXES ", 1)[1].split()
            if not rest:
                continue
            try:
                stamp = int(rest[0])
            except ValueError:
                continue
            boxes = {}
            for token in rest[2:]:
                m = re.match(
                    r"^(.+)#(\d+):(-?\d+),(-?\d+),(-?\d+),(-?\d+),([0-9a-f]+),([0-9a-f]+),([01])$",
                    token,
                )
                if not m:
                    continue
                word, idx, l, t, r, b, bg, ink, sampled = m.groups()
                # Keyed by instance, not by word: a page repeats its words, and keying by
                # name silently compares one paragraph's "reading" against another's.
                boxes[f"{word}#{idx}"] = {
                    "word": word,
                    "rect": (int(l), int(t), int(r), int(b)),
                    "bg": int(bg, 16) & 0xFFFFFF,
                    "ink": int(ink, 16) & 0xFFFFFF,
                    # Whether the colours were read off the screen at all. Without this a
                    # box that was never sampled reports black and reads as a wrong colour
                    # rather than as a missing one.
                    "sampled": sampled == "1",
                }
            out.append((stamp, boxes))
        return out

    def scroll_timeline(self, log=None):
        """Where the page said it was, and when. Ground truth the overlay never sees."""
        out = []
        for line in (log if log is not None else self.log()).splitlines():
            m = re.search(r"SCROLLY (\d+) (-?\d+)", line)
            if m:
                out.append((int(m.group(1)), int(m.group(2))))
        return out

    def scroll_at(self, timeline, stamp):
        """Where the page was at a given instant: the last position it reported by then.

        A page moves in frames, and between two of them it is not on its way anywhere - it
        is standing exactly where the last frame put it. Reading a straight line between two
        reports says otherwise, and where the app misses a frame and then covers the whole
        distance in the next one, that line is a movement the page never made: a reading
        taken in the middle of it was measured against a position the page was never at, and
        counted as drift of hundreds of pixels.
        """
        if not timeline:
            return None
        if stamp <= timeline[0][0]:
            return timeline[0][1]
        held = timeline[0][1]
        for at, y in timeline:
            if at > stamp:
                break
            held = y
        return held

    # ---- what the screen actually shows --------------------------------------

    def screenshot(self, into):
        """The real framebuffer, including our overlay.

        Through the emulator console, which comes back at the panel's own size - 320x640 on
        this device - and takes about 27ms.

        `adb exec-out screencap` is the other way and used not to work: it omitted another
        app's overlay windows entirely, and a screenshot taken that way showed the app with no
        transcriptions on it. That was while they were application overlays; as accessibility
        overlays they are in it. It comes back at the display's full 1080x1920, which is three
        times the detail, and takes about 205ms - worth it where the question is exactly where
        something was drawn, not where it is how often the screen can be photographed.
        """
        os.makedirs(into, exist_ok=True)
        for name in os.listdir(into):
            os.remove(os.path.join(into, name))
        adb("emu", "screenrecord", "screenshot", into)
        for _ in range(20):
            files = [f for f in os.listdir(into) if f.endswith(".png")]
            if files:
                return os.path.join(into, files[0])
            time.sleep(0.3)
        return None


def rgb(value):
    return ((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF)


def near(a, b, tol):
    return all(abs(x - y) <= tol for x, y in zip(a, b))


def overlaps(a, b):
    return not (a[2] <= b[0] or b[2] <= a[0] or a[3] <= b[1] or b[3] <= a[1])
