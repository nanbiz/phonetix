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
# Which emulator is ours, by the name of the virtual device rather than by a port.
#
# A port is not an identity. This machine runs several emulators and the ports are handed out
# in the order they start, so an emulator that dies frees its port for the next one - and a
# check pinned to the port then drives whatever took it. That happened: this suite installed
# the app onto another project's emulator and drove it there, which put our debug page in the
# middle of their screen recordings. So the serial is resolved from the AVD name, and a serial
# given by hand is checked against it rather than believed.
AVD = os.environ.get("PHONETIX_AVD", "phonetix36")


def _avd_of(serial):
    """The name of the virtual device behind a serial, or an empty string."""
    try:
        out = subprocess.run(
            ["adb", "-s", serial, "emu", "avd", "name"],
            capture_output=True, text=True, timeout=20,
        ).stdout
    except Exception:
        return ""
    for line in out.splitlines():
        line = line.strip()
        if line and line != "OK":
            return line
    return ""


def _ours():
    """The serial of our own emulator, refusing anything that is somebody else's."""
    asked = os.environ.get("PHONETIX_ANDROID_SERIAL")
    if asked and not asked.startswith("emulator-"):
        # A phone, named outright. There is no AVD to check it against.
        return asked
    if asked:
        name = _avd_of(asked)
        if name and name != AVD:
            raise SystemExit(
                f"{asked} is running {name!r}, not {AVD!r}. That is somebody else's "
                f"emulator - start {AVD} or set PHONETIX_AVD."
            )
        if name == AVD:
            return asked
    listed = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=30).stdout
    for line in listed.splitlines()[1:]:
        serial = line.split("\t")[0].strip()
        if serial.startswith("emulator-") and _avd_of(serial) == AVD:
            return serial
    raise SystemExit(f"no emulator running {AVD!r}; start one before running this")


SERIAL = _ours()


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

    def hide_error_dialogs(self):
        """Stop the system putting an "isn't responding" dialog over the screen.

        An ANR dialog is a window of the system's own, with its own words, and it is what the
        service then reads: a whole run reports three lines and a handful of words while the
        page it was meant to measure sits behind it, and the dialog outlives the fixture that
        follows. The ANR itself still happens and is still worth finding - it is in the log
        and in /data/anr - but it no longer decides what every later check measures.
        """
        shell("settings", "put", "global", "hide_error_dialogs", "1")

    def enable_service(self):
        """Grant both permissions. Note an install clears the accessibility one.

        Written as an off-then-on, because naming the service while accessibility is already
        on leaves the setting saying the service is enabled and the system never binding it:
        a suite then measures a screen nothing is annotating and reports the app as doing
        nothing, which is a fault in the suite and not in the app.
        """
        shell("appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "allow")
        self.hide_error_dialogs()
        # Tried more than once: after an install the system will accept the setting, report
        # the service as enabled, and never bind it. What says it is really running is the
        # bound list, so that is what is waited for, and the toggle is done again when it
        # does not appear.
        for attempt in range(4):
            shell("settings", "put", "secure", "accessibility_enabled", "0")
            time.sleep(1)
            shell("settings", "put", "secure", "enabled_accessibility_services", SERVICE)
            shell("settings", "put", "secure", "accessibility_enabled", "1")
            for _ in range(16):
                dump = shell("dumpsys", "accessibility")
                bound = dump.split("Bound services:", 1)[-1].split("\n", 1)[0]
                if "Phonetix transcriptions" in bound:
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
        # Given time to come forward: a machine with nothing to spare takes seconds over an
        # activity that a quiet one shows in a frame, and the harsher way below destroys the
        # page and builds it anew, which loses where it was scrolled to.
        for _ in range(20):
            if "DebugSurfaceActivity" in self.top_activity():
                return self._became(mode)
            time.sleep(0.5)
        shell(*(args + ["--activity-clear-task", "--activity-new-task"]))
        for _ in range(10):
            if "DebugSurfaceActivity" in self.top_activity():
                return self._became(mode)
            time.sleep(0.3)

    def _became(self, mode, seconds=20):
        """Wait for the page to say which mode it is in.

        The activity is the same one in every mode and rebuilds itself when the mode changes,
        so the name of what is in front says nothing about which page it is. A check that went
        on without waiting measured the page before it - and on a loaded device, where the
        rebuild takes seconds, asking again each time only made the rebuilding worse.
        """
        for _ in range(int(seconds * 2)):
            if f"mode={mode} " in self.lines(f"SETTINGS "):
                return
            time.sleep(0.5)

    def top_activity(self):
        """Which activity the device says the reader is actually looking at."""
        out = shell("dumpsys", "activity", "activities")
        m = re.search(r"topResumedActivity=ActivityRecord\{\S+ \S+ (\S+)", out)
        return m.group(1) if m else ""

    def annotated(self, seconds=60):
        """Wait until the overlay has actually drawn something, and say what.

        A service that has just been bound has neither unpacked the synthesiser nor opened its
        dictionaries, and both take seconds: the first passes over a screen answer nothing and
        draw nothing. A check that measures then measures a screen that is still getting ready
        and reports the app as doing nothing at all.
        """
        until = time.time() + seconds
        nudged = False
        while time.time() < until:
            # A device under load puts up "System UI isn't responding" over whatever is being
            # measured, and everything under it is then a screen nothing can annotate. Waited
            # for once rather than dismissed at once: the dialog usually goes by itself, and a
            # check that taps blindly at a screen it has not looked at taps at the app.
            if not nudged and time.time() > until - seconds / 2:
                nudged = True
                self.dismiss_anr()
            # The lines that name the boxes, filtered on the device. A tail cannot do it: the
            # overlay writes one long line per pass and the device drops the odd one under
            # load, so a window of the last six hundred lines can hold a reading in which
            # nothing was drawn while the screen in front of the reader is covered in
            # transcriptions - which reads exactly like an app doing nothing at all.
            boxes = self.boxes(self.lines("BOXES ", keep=8))
            if boxes:
                return boxes
            time.sleep(2)
        return {}

    def give(self, local, name, into):
        """Put a file in the app's own storage, unless the same bytes are already there.

        Pushing twenty megabytes of translation model into a running emulator is the heaviest
        thing these checks do to it, and they do it on every run for files that have not
        changed. Comparing sizes first takes a moment and skips it.
        """
        want = os.path.getsize(local)
        listed = adb("shell", f"run-as {PKG} ls -l {into}/{name}", timeout=120)
        found = re.search(r"\s(\d{3,})\s", listed)
        if found and int(found.group(1)) == want:
            return False
        adb("push", local, f"/data/local/tmp/{name}", timeout=900)
        adb("shell", f"run-as {PKG} sh -c "
                     f"'cat \"/data/local/tmp/{name}\" > \"{into}/{name}\"'", timeout=900)
        return True

    def dismiss_anr(self):
        """Send away a system "isn't responding" dialog, if one is in the way."""
        adb("shell", "uiautomator", "dump", "/sdcard/anr.xml", timeout=120)
        dump = adb("shell", "cat", "/sdcard/anr.xml", timeout=120)
        if "responding" not in dump:
            return False
        for node in re.finditer(r'<node[^>]*text="(Wait|Close app)"[^>]*'
                                r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump):
            if node.group(1) != "Wait":
                continue
            left, top, right, bottom = (int(v) for v in node.groups()[1:])
            shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))
            time.sleep(2)
            return True
        return False

    def clear_log(self):
        adb("logcat", "-c")

    def log(self, lines=1500):
        # Bounded, and tightly: this project's debug build writes a line naming every box on
        # screen on every pass, and those lines are long. Reading six thousand of them took
        # over three minutes on a busy emulator - longer than the read's own patience - while
        # fifteen hundred comes back in a moment. Every check clears the log immediately before
        # the thing it measures, so that is all any of them needs.
        return adb("logcat", "-d", "-t", str(lines), timeout=180)

    def lines(self, naming, keep=40):
        """Every line of the log that names this, filtered on the device.

        The tail above is bounded because this build writes a line naming every box on screen
        on every pass, and those lines are long. That bound is also how a check that waited a
        while for something to be drawn ends up reading a log in which it has scrolled past:
        this asks the device for the lines that matter instead, so how long the waiting took
        stops deciding what can be read.
        """
        return adb(
            "shell", f"logcat -d | grep -F '{naming}' | tail -{keep}", timeout=180,
        )

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
        # Asked for again when it does not arrive, rather than once.
        #
        # The emulator's renderer drops a capture now and then under load, and a check that
        # took that for an answer reported the app as having drawn nothing - "nothing
        # transcribed" on a screen that was full of transcriptions. One missing photograph is
        # evidence about the photograph, not about the app.
        for attempt in range(3):
            for name in os.listdir(into):
                os.remove(os.path.join(into, name))
            adb("emu", "screenrecord", "screenshot", into)
            for _ in range(20):
                files = [f for f in os.listdir(into) if f.endswith(".png")]
                if files:
                    return os.path.join(into, files[0])
                time.sleep(0.3)
            time.sleep(1.0 + attempt)
        return None


def finger_for(target, home, dpi, width, height):
    """Where the finger goes for the circle to settle on [target], by the controller's own
    arithmetic.

    The circle is carried away from where the mark waits, along the line from there to the
    finger: by nothing at the mark itself, growing to the full lift a fifth of the screen
    away. The lift is half what a fingertip covers, plus the mark's radius, plus a clear
    width more; a motion event driven from here reports no contact patch, so the fallback
    finger pad of 0.43 inches is what it uses. The weight on the thread lets the circle hang
    a little below where it is held.
    """
    density = dpi / 160.0
    size = 40 * density
    lift = 0.43 * dpi / 2 + size / 2 + size * 1.1
    grows = min(width, height) * 0.2
    sag = 900 * density / 260
    tx, ty = target[0], target[1] - sag
    dx, dy = tx - home[0], ty - home[1]
    far = (dx * dx + dy * dy) ** 0.5
    if far == 0:
        return int(tx), int(ty)
    # The finger is on the same line, nearer home: past the growing part the carry is the
    # whole lift, inside it the carry is in proportion to the distance.
    near = far - lift if far >= grows + lift else far / (1 + lift / grows)
    return int(home[0] + dx / far * near), int(home[1] + dy / far * near)


def drag_and_dwell(start, end, steps=10, dwell=1.5):
    """Drag from [start] to [end] with real motion events, and stay there before lifting.

    `input swipe` lifts the moment it arrives, and the circle is on a leash: on a machine
    drawing a few frames a second it is still catching up when the finger goes, and the drag
    reports the words between. Held at the end, it settles on the word that was aimed at.
    """
    shell("input", "motionevent", "DOWN", str(start[0]), str(start[1]))
    for i in range(1, steps + 1):
        x = start[0] + (end[0] - start[0]) * i // steps
        y = start[1] + (end[1] - start[1]) * i // steps
        shell("input", "motionevent", "MOVE", str(x), str(y))
    for _ in range(3):
        time.sleep(dwell / 3)
        shell("input", "motionevent", "MOVE", str(end[0]), str(end[1]))
    shell("input", "motionevent", "UP", str(end[0]), str(end[1]))


def carried_along(lines, home):
    """How far the circle is carried beyond the finger, away from where the mark waits.

    Read from what the service says it holds the circle at, against where the finger is,
    rather than from where the circle has swung to: on a machine drawing a few frames a
    second the leash lags a finger's width behind, and the sign of that lag is what a check
    reading the circle measured. The middle of the samples is returned; positive is away
    from home, which is the side the hand is not on. Samples with the finger still on the
    mark are left out, since there is nothing yet to be carried away from.
    """
    offsets = []
    for fx, fy, wx, wy in re.findall(
            r"LENSCARRY finger=(-?\d+),(-?\d+) want=(-?\d+),(-?\d+)", lines):
        fx, fy, wx, wy = int(fx), int(fy), int(wx), int(wy)
        dx, dy = fx - home[0], fy - home[1]
        far = (dx * dx + dy * dy) ** 0.5
        if far < 40:
            continue
        offsets.append(((wx - fx) * dx + (wy - fy) * dy) / far)
    return int(sorted(offsets)[len(offsets) // 2]) if offsets else 0


def rgb(value):
    return ((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF)


def near(a, b, tol):
    return all(abs(x - y) <= tol for x, y in zip(a, b))


def overlaps(a, b):
    return not (a[2] <= b[0] or b[2] <= a[0] or a[3] <= b[1] or b[3] <= a[1])
