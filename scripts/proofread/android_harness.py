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
        args = ["am", "start", "-n", SURFACE, "--activity-reorder-to-front",
                "--es", "mode", mode]
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
        """Where the page was at a given instant, interpolated between its own reports."""
        if not timeline:
            return None
        if stamp <= timeline[0][0]:
            return timeline[0][1]
        if stamp >= timeline[-1][0]:
            return timeline[-1][1]
        for (t0, y0), (t1, y1) in zip(timeline, timeline[1:]):
            if t0 <= stamp <= t1:
                if t1 == t0:
                    return y0
                return y0 + (y1 - y0) * (stamp - t0) / (t1 - t0)
        return timeline[-1][1]

    # ---- what the screen actually shows --------------------------------------

    def screenshot(self, into):
        """The real framebuffer, including our overlay.

        `adb shell screencap` omits another app's overlay windows entirely - a screenshot
        taken that way shows the app with no transcriptions on it at all - so the capture
        has to come through the emulator console.
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
