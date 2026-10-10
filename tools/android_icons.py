#!/usr/bin/env python3
"""The icons the phone draws that the extension draws too, from the same installed icon sets.

The extension takes its icons from @iconify-json (lucide, ooui) through unplugin-icons; the
phone cannot, so each one it needs is written here as an Android vector drawable from the same
set's own path data, rather than drawn again by hand.

  uv run python tools/android_icons.py

Only paths are carried over, filled or stroked as the set has them, in white: the phone tints
every icon to the palette it is drawn in.
"""
import json
import os
import re
import sys
from xml.sax.saxutils import quoteattr

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SETS = os.path.join(ROOT, "node_modules", "@iconify-json")
INTO = os.path.join(ROOT, "android", "app", "src", "main", "res", "drawable")

# The drawable, and the set and icon it is.
ICONS = {
    "ic_play": ("lucide", "play"),
    "ic_wiktionary": ("ooui", "logo-wiktionary"),
}


def attributes(tag):
    return dict(re.findall(r'([a-z-]+)="([^"]*)"', tag))


def drawable(collection, name):
    with open(os.path.join(SETS, collection, "icons.json")) as f:
        data = json.load(f)
    icon = data["icons"][name]
    width = icon.get("width", data.get("width", 16))
    height = icon.get("height", data.get("height", 16))
    body = icon["body"]
    group = attributes(re.match(r"<g ([^>]*)>", body).group(1)) if body.startswith("<g ") else {}
    paths = []
    for tag in re.findall(r"<path ([^>]*)/>", body):
        a = {**group, **attributes(tag)}
        stroked = a.get("fill") == "none"
        line = [f"android:pathData={quoteattr(a['d'])}"]
        if stroked:
            line += [
                'android:strokeColor="#FFFFFFFF"',
                f'android:strokeWidth="{a.get("stroke-width", "1")}"',
                f'android:strokeLineCap="{a.get("stroke-linecap", "butt")}"',
                f'android:strokeLineJoin="{a.get("stroke-linejoin", "miter")}"',
            ]
        else:
            line.append('android:fillColor="#FFFFFFFF"')
            if a.get("fill-rule") == "evenodd":
                line.append('android:fillType="evenOdd"')
        paths.append("    <path " + " ".join(line) + " />")
    if not paths:
        raise SystemExit(f"{collection}:{name} has no paths to carry over")
    return (
        f"<!-- Written by tools/android_icons.py from @iconify-json/{collection}: {name}. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="24dp" android:height="24dp"'
        f' android:viewportWidth="{width}" android:viewportHeight="{height}">\n'
        + "\n".join(paths)
        + "\n</vector>\n"
    )


def main():
    check = "--check" in sys.argv
    stale = []
    for out, (collection, name) in ICONS.items():
        text = drawable(collection, name)
        path = os.path.join(INTO, f"{out}.xml")
        if check:
            if not os.path.exists(path) or open(path).read() != text:
                stale.append(path)
            continue
        with open(path, "w") as f:
            f.write(text)
        print(f"{path}: {collection}:{name}")
    if stale:
        raise SystemExit("stale, run tools/android_icons.py: " + ", ".join(stale))


if __name__ == "__main__":
    main()
