#!/usr/bin/env python3
"""Draw the side button's mark from the launcher icon.

The launcher icon is the glyph between two brackets. The side button wears the glyph
alone: it sits over somebody else's page at the size of a fingertip, where the brackets
are two bars with something small between them rather than a mark.

Run after changing the launcher icon:

    uv run --with pillow tools/make_mark.py
"""
from pathlib import Path

from PIL import Image

RES = Path(__file__).resolve().parent.parent / "android/app/src/main/res"
# The brackets are the red of the logo; the glyph is its amber.
BRACKET = (220, 38, 38)
# How much of the canvas the glyph spans. The brackets were most of the width of the
# launcher icon, so a glyph cropped out of it and left at its own size would halve the
# button; it is drawn back up to the width the whole mark used to have.
SPAN = 0.46


def nearer_bracket(pixel: tuple[int, int, int, int]) -> bool:
    r, g, b, _ = pixel
    return abs(r - BRACKET[0]) + abs(g - BRACKET[1]) + abs(b - BRACKET[2]) < 150


def mark(icon: Image.Image) -> Image.Image:
    glyph = icon.copy()
    out = glyph.load()
    for y in range(glyph.height):
        for x in range(glyph.width):
            if out[x, y][3] > 10 and nearer_bracket(out[x, y]):
                out[x, y] = (0, 0, 0, 0)
    box = glyph.getbbox()
    glyph = glyph.crop(box)
    side = round(icon.width * SPAN)
    scale = side / max(glyph.width, glyph.height)
    glyph = glyph.resize(
        (max(1, round(glyph.width * scale)), max(1, round(glyph.height * scale))),
        Image.LANCZOS,
    )
    canvas = Image.new("RGBA", icon.size, (0, 0, 0, 0))
    canvas.paste(glyph, ((icon.width - glyph.width) // 2, (icon.height - glyph.height) // 2))
    return canvas


for source in sorted(RES.glob("mipmap-*/ic_launcher_foreground.png")):
    target = source.with_name("ic_mark.png")
    mark(Image.open(source).convert("RGBA")).save(target)
    print(target.relative_to(RES))
