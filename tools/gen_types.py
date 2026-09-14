"""The authored tables, written out in the form each platform can read.

Everything a component needs to know that is not code - the design tokens, the language names -
is authored once and generated into the shapes the two platforms consume, so a colour or a name
cannot exist on one side and not the other.

The surface page is where every colour, size, radius and spacing is declared, because it is
the thing a person looks at when deciding them. The phone cannot read a stylesheet, so the
same declarations are written out as data and as a Kotlin object, and a check says when the
three have drifted apart. Editing the page and forgetting the phone is then a failing build
rather than a card that looks wrong on one platform only.

  uv run python tools/gen_types.py          # write what both platforms read
  uv run python tools/gen_types.py --check  # say whether what is written is still current
"""
import json
import math
import os
import pathlib
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SURFACE = os.path.join(ROOT, "docs", "merge", "surface.html")
TOKENS = os.path.join(ROOT, "data", "tokens.json")
LANGUAGES = os.path.join(ROOT, "data", "languages.json")
SYMBOLS = os.path.join(ROOT, "data", "ipa-symbols.json")
ACCENTS = os.path.join(ROOT, "data", "accents.json")
WORDING = os.path.join(ROOT, "data", "wording.json")
WORDING_TS = os.path.join(ROOT, "src", "data", "wording.ts")
WORDING_KT = os.path.join(
    ROOT, "android", "app", "src", "main", "java", "io", "github", "nanbiz", "phonetix",
    "core", "Wording.kt",
)
ACCENTS_TS = os.path.join(ROOT, "src", "data", "accents.ts")
ACCENTS_KT = os.path.join(
    ROOT, "android", "app", "src", "main", "java", "io", "github", "nanbiz", "phonetix",
    "core", "Accents.kt",
)
SYMBOLS_RS = os.path.join(ROOT, "core", "src", "symbols", "table.rs")
LANGUAGES_RS = os.path.join(ROOT, "core", "src", "languages.rs")
NEIGHBOURS = os.path.join(ROOT, "data", "neighbours.json")
NEIGHBOURS_RS = os.path.join(ROOT, "core", "src", "neighbours.rs")
LANGUAGES_KT = os.path.join(
    ROOT, "android", "app", "src", "main", "java", "io", "github", "nanbiz", "phonetix",
    "core", "Languages.kt",
)
KOTLIN = os.path.join(
    ROOT, "android", "app", "src", "main", "java", "io", "github", "nanbiz", "phonetix",
    "ui", "Tokens.kt",
)
CSS = os.path.join(ROOT, "src", "ui", "tokens.css")
CARD_CSS = os.path.join(ROOT, "src", "ui", "card", "card.css")
INLINE_CSS = os.path.join(ROOT, "src", "ui", "inline.css")
# The same tokens, defined on the extension's own elements rather than on a page's root.
INLINE_TOKENS_CSS = os.path.join(ROOT, "src", "ui", "inline-tokens.css")
SETTINGS_CSS = os.path.join(ROOT, "src", "ui", "settings", "settings.css")
LANGUAGES_TS = os.path.join(ROOT, "src", "data", "languages.ts")

# The sections of the surface page's stylesheet the extension's card is drawn by. Named
# rather than pattern-matched: the page also styles its own showcase, and a card that picked
# up the showcase's rules would be styled by the exhibition it is displayed in.
CARD_SECTIONS = ("Card (answer surface)", "Density tiers")

# The settings surfaces: the panel, its rows and the controls in them. Same page, same
# reasoning as the card, so the view a reader changes things in is not a second design.
SETTINGS_SECTIONS = ("Packs and home (settings surfaces)",)
# Rules the settings surfaces use that are declared beside the card, because the same small
# element appears on both. Named one by one rather than swept in, so that what a settings view
# is styled by stays something a person can read off this list.
SETTINGS_ALSO = (".chip", ".btn-text", ".btn", ".btn.secondary", ".btn.danger")

# The inline layer is drawn into a page the extension does not own, where a class called "w"
# would collide with the page's own. The rules are the page's; the names they are written
# under are prefixed on the way out, and this is the whole of the mapping so that a rule and
# the markup that uses it cannot drift apart.
INLINE_SECTION = "Page (inline layer)"
INLINE_CLASSES = {
    "page": "px",
    "w": "px-w",
    "gl": "px-gl",
    "ph": "px-ph",
    "rep": "px-rep",
    "keep": "px-keep",
    "seen": "px-seen",
    "was": "px-was",
    "showing": "px-showing",
    "guess": "px-guess",
}
# The showcase's own furniture, which is not part of what a reader gets.
INLINE_SKIP = ("page-bar", "scan-pill", "hover-cursor")
# The mock page those rules are drawn inside: a surface, a border and a line height belonging
# to the exhibit rather than to the annotation. A real page brings its own, and painting over
# it would be the extension redecorating what it was asked to annotate.
INLINE_SKIP_EXACT = (".page", ".page p", ".page.ruby p, .page.ruby-2 p")


def blocks(css):
    """Every rule in the stylesheet, as (selector, declarations)."""
    out = []
    for match in re.finditer(r"([^{}]+)\{([^{}]*)\}", css):
        selector = match.group(1).strip().splitlines()[-1].strip()
        body = {}
        for line in match.group(2).split(";"):
            line = re.sub(r"/\*.*?\*/", "", line).strip()
            if not line.startswith("--") or ":" not in line:
                continue
            name, value = line.split(":", 1)
            body[name.strip()] = value.strip()
        if body:
            out.append((selector, body))
    return out


def sections(style):
    """The stylesheet split by its own section banners, as {title: css}."""
    parts = re.split(r"/\* -+\s*\n\s*(.+?)\s*\n\s*-+ \*/", style, flags=re.S)
    out = {}
    for i in range(1, len(parts) - 1, 2):
        out[parts[i].strip()] = parts[i + 1]
    return out


def named_rules(style, wanted):
    """Rules declared elsewhere on the page that a surface needs by name."""
    out = []
    for rule in re.findall(r"[^{}]+\{[^{}]*\}", style):
        if rule.split("{", 1)[0].strip() in wanted:
            out.append(rule.strip())
    return out


def cut_sections(style, wanted, what):
    """The rules of the named sections, copied from the page that declares them."""
    lines = [
        f"/* {what}, as the surface page draws it.",
        " *",
        " * Generated by tools/gen_types.py from docs/merge/surface.html; edit that and run",
        " * it again. What a reader sees here and what they see on a phone are one design.",
        " */",
    ]
    found = sections(style)
    for title in wanted:
        body = next((css for name, css in found.items() if name.startswith(title)), None)
        if body is None:
            raise SystemExit(f"the surface page no longer has a {title!r} section")
        lines.append("")
        lines.append(f"/* {title} */")
        lines.append(body.strip())
    return "\n".join(lines) + "\n"


def card_stylesheet(style):
    """The card's own rules, taken from the page that declares them.

    Copied rather than rewritten, because a card in a page and a card in the showcase have
    to be the same card: a rule edited on the page and forgotten here would be a design that
    exists only where nobody uses it.
    """
    lines = [
        "/* The answer card, as the surface page draws it.",
        " *",
        " * Generated by tools/gen_types.py from docs/merge/surface.html; edit that and run",
        " * it again. The card in a page and the card in the showcase are the same card.",
        " */",
    ]
    found = sections(style)
    for title in CARD_SECTIONS:
        # By the opening words of the banner: several of them carry a paragraph of
        # reasoning after the name, and that reasoning is edited more often than the name.
        body = next((css for name, css in found.items() if name.startswith(title)), None)
        if body is None:
            raise SystemExit(f"the surface page no longer has a {title!r} section")
        lines.append("")
        lines.append(f"/* {title} */")
        lines.append(body.strip())
    return "\n".join(lines) + "\n"


def inline_stylesheet(style):
    """The inline layer's rules, under names that cannot collide with a page's own.

    A page defines its own classes and some of them are single letters. Every class in this
    section is therefore renamed on the way out, by the table above, and the content script
    writes the same names into the markup it draws.
    """
    found = sections(style)
    body = next((css for name, css in found.items() if name.startswith(INLINE_SECTION)), None)
    if body is None:
        raise SystemExit(f"the surface page no longer has a {INLINE_SECTION!r} section")

    def rename(match):
        name = match.group(1)
        return "." + INLINE_CLASSES.get(name, name)

    lines = [
        "/* The inline layer, as the surface page draws it.",
        " *",
        " * Generated by tools/gen_types.py from docs/merge/surface.html; edit that and run",
        " * it again. The class names are prefixed here because these rules are drawn into a",
        " * page that has its own.",
        " */",
        "",
    ]
    for rule in re.findall(r"[^{}]+\{[^{}]*\}", body):
        selector = rule.split("{", 1)[0].strip()
        if any(skip in selector for skip in INLINE_SKIP):
            continue
        if selector in INLINE_SKIP_EXACT:
            continue
        lines.append(re.sub(r"\.([a-zA-Z][\w-]*)", rename, rule.strip()))
    return "\n".join(lines) + "\n"


# Which of daisyUI's themes this product offers, and what each palette entry of ours is worth
# in it. The library ships thirty-five of them as plain custom properties, so they are data
# this generator can read rather than a second stylesheet to keep in step by hand. It is a
# build input and nothing else: no surface is drawn by that library, and none of its CSS ships.
#
# The mapping is the whole point: a theme is a set of values, and the vocabulary stays ours.
# Everything drawn on either platform names a role - surface, ink, accent - so a theme from a
# library reaches the phone's card without a single Kotlin file knowing the library exists.
DAISY = pathlib.Path(ROOT) / "node_modules" / "daisyui" / "theme"

DAISY_THEMES = ["nord", "dracula", "winter", "silk"]

DAISY_ROLES = {
    "--p-surface": "--color-base-100",
    "--p-surface-raised": "--color-base-200",
    "--p-border": "--color-base-300",
    "--p-ink": "--color-base-content",
    "--p-ink-muted": "--color-base-content",
    "--p-ink-faint": "--color-base-content",
    "--p-accent": "--color-primary",
    "--p-accent-ink": "--color-primary-content",
    "--p-accent-bg": "--color-primary",
    "--p-chip-bg": "--color-base-200",
    "--p-chip-ink": "--color-base-content",
    "--p-danger": "--color-error",
    "--p-danger-bg": "--color-error-content",
}


def from_oklch(value):
    """An oklch() colour as #rrggbb. daisyUI writes every colour that way."""
    got = re.fullmatch(
        r"oklch\(\s*([\d.]+)%?\s+([\d.]+)\s+([\d.]+)\s*\)", value.strip())
    if not got:
        return None
    lightness, chroma, hue = (float(g) for g in got.groups())
    if lightness > 1.5:
        lightness /= 100.0
    radians = math.radians(hue)
    a, b = chroma * math.cos(radians), chroma * math.sin(radians)
    # Oklab to linear sRGB, as the colour space defines it.
    l_ = (lightness + 0.3963377774 * a + 0.2158037573 * b) ** 3
    m_ = (lightness - 0.1055613458 * a - 0.0638541728 * b) ** 3
    s_ = (lightness - 0.0894841775 * a - 1.2914855480 * b) ** 3
    linear = (
        4.0767416621 * l_ - 3.3077115913 * m_ + 0.2309699292 * s_,
        -1.2684380046 * l_ + 2.6097574011 * m_ - 0.3413193965 * s_,
        -0.0041960863 * l_ - 0.7034186147 * m_ + 1.7076147010 * s_,
    )
    out = []
    for channel in linear:
        channel = max(0.0, min(1.0, channel))
        srgb = 12.92 * channel if channel <= 0.0031308 else 1.055 * channel ** (1 / 2.4) - 0.055
        out.append(round(max(0.0, min(1.0, srgb)) * 255))
    return "#%02x%02x%02x" % tuple(out)


def lightness_of(value):
    """How light an oklch() colour is, from 0 to 1. Anything unparsed reads as light."""
    got = re.fullmatch(r"oklch\(\s*([\d.]+)%?\s+.*", value.strip())
    if not got:
        return 1.0
    light = float(got.group(1))
    return light / 100.0 if light > 1.5 else light


def read_daisy():
    """The offered daisyUI themes, as palettes in this product's own vocabulary."""
    palettes = {}
    for name in DAISY_THEMES:
        path = DAISY / f"{name}.css"
        if not path.exists():
            continue
        declared = dict(re.findall(r"(--[a-z0-9-]+)\s*:\s*([^;]+);", path.read_text()))
        # Which mode a theme is, taken from the colour it paints its surface rather than from
        # what it says: daisyUI's nord declares "color-scheme: dark" and has a surface at 95%
        # lightness, and believing the declaration filed a light theme as a dark one.
        mode = "dark" if lightness_of(declared.get("--color-base-100", "")) < 0.5 else "light"
        palette = {}
        for ours, theirs in DAISY_ROLES.items():
            colour = from_oklch(declared.get(theirs, ""))
            if colour:
                palette[ours] = colour
        # The two quieter inks are the body colour stepped towards the surface, since a theme
        # states one text colour and this product draws three weights of it.
        for role, mix in (("--p-ink-muted", 0.35), ("--p-ink-faint", 0.55)):
            ink, surface = palette.get("--p-ink"), palette.get("--p-surface")
            if ink and surface:
                palette[role] = blend(ink, surface, mix)
        # The tint a coloured word sits on, which these themes do not state: the colour itself
        # stepped most of the way to the surface. Mapped to the colour it belongs to, the
        # frequency bar's reading was painted in the accent on the accent - a filled pill with
        # nothing readable in it - and an alert was its own text colour on itself.
        surface = palette.get("--p-surface")
        for role, of in (("--p-accent-bg", "--p-accent"), ("--p-danger-bg", "--p-danger")):
            colour = palette.get(of)
            if colour and surface:
                palette[role] = blend(colour, surface, 0.85)
        if palette:
            palettes[f"{name}-{mode}"] = palette
    return palettes


def blend(one, other, amount):
    """One colour moved towards another, as #rrggbb."""
    a = [int(one[i:i + 2], 16) for i in (1, 3, 5)]
    b = [int(other[i:i + 2], 16) for i in (1, 3, 5)]
    return "#%02x%02x%02x" % tuple(
        round(x + (y - x) * amount) for x, y in zip(a, b))


def read_surface():
    """The scalars, the palettes and the roles, as the page declares them."""
    with open(SURFACE) as f:
        page = f.read()
    style = "\n".join(re.findall(r"<style>(.*?)</style>", page, re.S))
    # The comments carry the reasoning and would otherwise be parsed as declarations.
    style = re.sub(r"/\*.*?\*/", "", style, flags=re.S)

    scalars, palettes, roles = {}, {}, {}
    for selector, body in blocks(style):
        if selector == ":root":
            scalars.update({k: v for k, v in body.items() if not k.startswith("--p-")})
        elif selector.startswith(".theme-"):
            match = re.match(r"\.theme-([a-z]+)\.mode-([a-z]+)", selector)
            if match:
                palettes[f"{match.group(1)}-{match.group(2)}"] = body
        elif "theme-" in selector:
            # The semantic layer: a role names a palette entry and never a colour.
            for role, value in body.items():
                seen = re.match(r"var\((--p-[a-z0-9-]+)\)", value)
                if seen:
                    roles[role] = seen.group(1)
    # The themes a library gives us, in the same vocabulary, so every surface on both
    # platforms can offer them without knowing where they came from.
    palettes.update(read_daisy())
    return scalars, palettes, roles


def as_argb(value):
    """A CSS colour as the number a phone wants, or nothing when it is not one."""
    value = value.strip()
    hexed = re.fullmatch(r"#([0-9a-fA-F]{3,8})", value)
    if hexed:
        digits = hexed.group(1)
        if len(digits) == 3:
            digits = "".join(c * 2 for c in digits)
        if len(digits) == 6:
            return 0xFF000000 | int(digits, 16)
        if len(digits) == 8:
            # CSS writes the alpha last and a phone wants it first.
            rgb, alpha = int(digits[:6], 16), int(digits[6:], 16)
            return (alpha << 24) | rgb
        return None
    rgba = re.fullmatch(r"rgba?\(([^)]+)\)", value)
    if rgba:
        parts = [p.strip() for p in rgba.group(1).replace("/", ",").split(",")]
        if len(parts) < 3:
            return None
        try:
            r, g, b = (int(float(p)) for p in parts[:3])
            alpha = float(parts[3]) if len(parts) > 3 else 1.0
        except ValueError:
            return None
        return (int(round(alpha * 255)) << 24) | (r << 16) | (g << 8) | b
    return None


def camel(name):
    parts = name.removeprefix("--").removeprefix("p-").removeprefix("color-").split("-")
    return parts[0] + "".join(p.capitalize() for p in parts[1:])


def kotlin(scalars, palettes, roles):
    """The same tokens as a Kotlin object, so a Compose card cannot invent its own."""
    themes = sorted({name.rsplit("-", 1)[0] for name in palettes})
    named = sorted(roles)
    lines = [
        "package io.github.nanbiz.phonetix.ui",
        "",
        "/**",
        " * The design tokens, as the surface page declares them.",
        " *",
        " * Generated by tools/tokens.py from docs/merge/surface.html; edit that and run it",
        " * again. A colour written here by hand is a colour the browser does not have, which",
        " * is the drift one set of tokens exists to prevent.",
        " *",
        " * Colours are ARGB, which is what Compose's Color takes. A role names what a thing is",
        " * for and never what colour it is, so a new theme is a new palette and nothing here",
        " * changes.",
        " */",
        "object Tokens {",
        "",
        "    /** Sizes, spacings and radii, which no theme changes. */",
        "    object Scale {",
    ]
    for name, value in sorted(scalars.items()):
        number = re.fullmatch(r"(-?[\d.]+)(px|em|rem)?", value.strip())
        if number and number.group(2) in (None, "px"):
            lines.append(f"        const val {camel(name)} = {float(number.group(1))}f")
    lines += [
        "    }",
        "",
        "    /** Colours a theme deliberately does not change, as the page declares them. */",
        "    object Fixed {",
    ]
    for name, value in sorted(scalars.items()):
        hex_colour = re.fullmatch(r"#([0-9a-fA-F]{6})", value.strip())
        if hex_colour:
            lines.append(f"        const val {camel(name)} = 0xFF{hex_colour.group(1).upper()}")
    lines += [
        "    }",
        "",
        "    /** Which set of colours a reader has chosen. */",
        "    enum class Theme { " + ", ".join(t.upper() for t in themes) + " }",
        "",
        "    /** What each role is worth in one theme and mode. */",
        "    data class Palette(",
    ]
    for role in named:
        lines.append(f"        val {camel(role)}: Long,")
    lines += ["    )", "", "    fun palette(theme: Theme, dark: Boolean): Palette = when {"]
    for theme in themes:
        for mode in ("light", "dark"):
            key = f"{theme}-{mode}"
            if key not in palettes:
                continue
            test = f"theme == Theme.{theme.upper()} && {'dark' if mode == 'dark' else '!dark'}"
            lines.append(f"        {test} -> Palette(")
            for role in named:
                value = palettes[key].get(roles[role], "")
                argb = as_argb(value)
                lines.append(f"            {camel(role)} = 0x{argb:08X}L,"
                             if argb is not None else
                             f"            {camel(role)} = 0x00000000L,")
            lines.append("        )")
    # A theme offered in one mode only keeps its own colours in the other. Many of the themes
    # a library ships are designed as one or the other, and falling through to a different
    # theme would answer a reader who chose Dracula with somebody else's palette.
    lines.append("        // A theme designed for one mode keeps its own colours in the other.")
    for theme in themes:
        has = [mode for mode in ("dark", "light") if f"{theme}-{mode}" in palettes]
        if len(has) == 1:
            lines.append(
                f"        theme == Theme.{theme.upper()} -> "
                f"palette(Theme.{theme.upper()}, {'true' if has[0] == 'dark' else 'false'})")
    lines += [
        f"        else -> palette(Theme.{themes[0].upper()}, dark)",
        "    }",
        "}",
        "",
    ]
    return "\n".join(lines)


def languages_kotlin():
    """The language table as a Kotlin object."""
    with open(LANGUAGES) as f:
        table = json.load(f)["languages"]
    lines = [
        "package io.github.nanbiz.phonetix.core",
        "",
        "/**",
        " * What each language is called.",
        " *",
        " * Generated by tools/gen_types.py from data/languages.json; edit that and run it",
        " * again. A code the table does not hold is shown as the code: inventing a name for a",
        " * language is worse than admitting the table is short.",
        " */",
        "object Languages {",
        "",
        "    data class Named(val english: String, val native: String, val rtl: Boolean)",
        "",
        "    private val TABLE: Map<String, Named> = mapOf(",
    ]
    for code in sorted(table):
        row = table[code]
        rtl = "true" if row.get("rtl") else "false"
        lines.append(
            f'        "{code}" to Named({row["english"]!r}, {row["native"]!r}, {rtl}),'
            .replace("'", '"')
        )
    lines += [
        "    )",
        "",
        "    /** What to call this language to a reader, or the code when it is not named. */",
        "    fun english(code: String): String = TABLE[code.lowercase()]?.english ?: code",
        "",
        "    /** What its own speakers call it. */",
        "    fun native(code: String): String = TABLE[code.lowercase()]?.native ?: code",
        "",
        "    /** Whether it is written right to left, which decides how a card lays a line out. */",
        "    fun rtl(code: String): Boolean = TABLE[code.lowercase()]?.rtl ?: false",
        "",
        "    /** Every language the interface can name. */",
        "    fun all(): List<String> = TABLE.keys.sorted()",
        "}",
        "",
    ]
    return "\n".join(lines)


def stylesheet(scalars, palettes, roles, scope=None):
    """The same tokens as a stylesheet, for a card drawn in a page.

    The extension cannot import the surface page: it is a document, with a showcase around
    the declarations. What it needs is the declarations, in a sheet a shadow root can adopt,
    with one class per theme and mode so a card switches theme by changing an attribute
    rather than by recomputing colours.
    """
    # Where the variables are defined. In a page the extension does not own, they are defined
    # on the extension's own elements: a page with its own --space-4 must not have it
    # redefined under it, and a variable on the page root is a variable over the page's.
    root = scope or ":root, :host"
    theme = (lambda name: f"{scope}{name}") if scope else (lambda name: name)
    lines = [
        "/* The design tokens, as the surface page declares them.",
        " *",
        " * Generated by tools/gen_types.py from docs/merge/surface.html; edit that and run",
        " * it again. A colour written here by hand is a colour the phone does not have,",
        " * which is the drift one set of tokens exists to prevent.",
        " */",
        # A card lives in a shadow root, where :root is the page's document element and
        # matches nothing inside. Both selectors, so the same sheet serves a page and a
        # shadow tree.
        f"{root} {{",
    ]
    for name, value in sorted(scalars.items()):
        lines.append(f"  {name}: {value};")
    lines.append("}")
    for key in sorted(palettes):
        name, mode = key.rsplit("-", 1)
        lines.append("")
        # A theme that carries one side answers for the other as well. Every palette is named
        # by a surface as a theme and a mode, and the two are chosen separately: a theme with
        # no rule for the mode in force leaves every colour undefined, which is not a surface
        # in the wrong colours but a surface with none - a settings screen in the browser's
        # own type with no palette, no controls, and sheets with no background over it. The
        # Kotlin below has always answered this way.
        other = "light" if mode == "dark" else "dark"
        both = f"{name}-{other}" not in palettes
        selector = theme(f".theme-{name}.mode-{mode}")
        if both:
            selector += ", " + theme(f".theme-{name}.mode-{other}")
        lines.append(selector + " {")
        for name, value in sorted(palettes[key].items()):
            lines.append(f"  {name}: {value};")
        lines.append("}")
    lines.append("")
    lines.append("/* What a thing is for, never what colour it is. */")
    lines.append(theme("[class*='theme-']") + " {")
    for role in sorted(roles):
        lines.append(f"  {role}: var({roles[role]});")
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def rust(text):
    """A Rust string literal for an authored value."""
    return json.dumps(text, ensure_ascii=False)


def rustfmt(text):
    """Rust as rustfmt would leave it.

    The file is generated and then formatted by the same tool the rest of the crate is, so a
    `cargo fmt` does not rewrite it into something the generator would not produce and the
    check for a stale table does not fail on whitespace forever.
    """
    try:
        done = subprocess.run(
            ["rustfmt", "--edition", "2021", "--emit", "stdout", "--quiet"],
            input=text, capture_output=True, text=True, check=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return text
    return done.stdout


def symbols_rust():
    """The IPA table as Rust, so the core needs no JSON parser to hold it.

    Both platforms ask the core what a symbol is, so the table is compiled into it rather
    than shipped beside it: a copy per platform is how one sound ends up with two names.
    """
    with open(SYMBOLS) as f:
        table = json.load(f)
    symbols = table["symbols"]
    lines = [
        "// The IPA table, as data/ipa-symbols.json declares it.",
        "//",
        "// Generated by tools/gen_types.py; edit the JSON and run it again.",
        "",
        "/// One symbol: what it is called, what kind of sound it is, and where to read more.",
        "pub struct Row {",
        "    pub token: &'static str,",
        "    pub name: &'static str,",
        "    pub kind: &'static str,",
        "    pub example: &'static str,",
        "    pub audio: &'static str,",
        "    pub wiki: &'static str,",
        "    pub diagram: &'static str,",
        "    pub seeing: &'static str,",
        "}",
        "",
        f"pub static SYMBOLS: [Row; {len(symbols)}] = [",
    ]
    for token in symbols:
        row = symbols[token]
        lines.append(
            "    Row { token: %s, name: %s, kind: %s, example: %s, audio: %s, wiki: %s, "
            "diagram: %s, seeing: %s },"
            % (
                rust(token),
                rust(row.get("name", "")),
                rust(row.get("type", "")),
                rust(row.get("example", "")),
                rust(row.get("audio") or ""),
                rust(row.get("wiki") or ""),
                rust(row.get("diagram") or ""),
                rust(row.get("seeing") or ""),
            )
        )
    lines += ["];", ""]

    marks = table["diacritics"]
    lines += [
        "/// A mark that modifies a sound, and what it is called when it does.",
        f"pub static DIACRITICS: [(&str, &str); {len(marks)}] = [",
    ]
    for mark in marks:
        lines.append(f"    ({rust(mark)}, {rust(marks[mark])}),")
    lines += ["];", ""]

    standalone = table["standalone"]
    lines += [
        "/// Marks that are their own symbol rather than something hanging off one.",
        f"pub static STANDALONE: [&str; {len(standalone)}] = [",
    ]
    for mark in standalone:
        lines.append(f"    {rust(mark)},")
    lines += ["];", ""]

    terms = table["terms"]
    lines += [
        "/// The phonetic terms a name is made of, and the article each one has.",
        f"pub static TERMS: [(&str, &str); {len(terms)}] = [",
    ]
    for term in sorted(terms):
        lines.append(f"    ({rust(term)}, {rust(terms[term])}),")
    lines += ["];", ""]
    return rustfmt("\n".join(lines))


def neighbours_rust():
    """Which reading of a homograph its neighbour makes likely, for the cascade."""
    with open(NEIGHBOURS) as f:
        table = json.load(f)
    lines = [
        "//! Which reading of a homograph the word before it makes likely.",
        "//!",
        "//! Generated by tools/gen_types.py from data/neighbours.json; edit that and run it",
        "//! again. The rules are authored rather than trained, and they are the signal that",
        "//! works in every language: a determiner is followed by a noun, an infinitive marker",
        "//! by a verb. Where a language has a trained classifier it outranks this.",
        "",
        "/// One rule: the part of speech before, and what it makes the word after it.",
        "pub struct Neighbour {",
        "    pub after: &'static str,",
        "    pub prefers: &'static str,",
        "}",
        "",
        "/// How much more the preferred reading has to be worth than the runner-up before the",
        "/// card stops offering the choice.",
        f"pub const MARGIN: u32 = {int(table.get('margin', 1))};",
        "",
        f"pub static RULES: [Neighbour; {len(table['rules'])}] = [",
    ]
    for rule in table["rules"]:
        lines.append(
            "    Neighbour { after: %s, prefers: %s }, // %s"
            % (json.dumps(rule["after"]), json.dumps(rule["prefers"]), rule.get("why", ""))
        )
    lines += ["];", ""]
    # Through rustfmt, like the other generated Rust: otherwise the first cargo fmt reformats
    # it and the generated file is permanently out of date with its own generator.
    return rustfmt("\n".join(lines))


def languages_rust():
    """The language table for the core, which needs the names as well as the codes.

    Wiktionary heads a section with a language's English name, so reading one means turning
    "Spanish" back into "es"; that mapping is the same table the interfaces name languages
    from, and having it twice is how a name ends up meaning two codes.
    """
    with open(LANGUAGES) as f:
        table = json.load(f)["languages"]
    lines = [
        "// What each language is called, as data/languages.json declares it.",
        "//",
        "// Generated by tools/gen_types.py; edit the JSON and run it again.",
        "",
        "/// One language: its code, and what it is called in English and by its speakers.",
        "pub struct Named {",
        "    pub code: &'static str,",
        "    pub english: &'static str,",
        "    pub native: &'static str,",
        "    pub rtl: bool,",
        "}",
        "",
        f"pub static LANGUAGES: [Named; {len(table)}] = [",
    ]
    for code in sorted(table):
        row = table[code]
        rtl = "true" if row.get("rtl") else "false"
        lines.append(
            "    Named { code: %s, english: %s, native: %s, rtl: %s },"
            % (rust(code), rust(row["english"]), rust(row["native"]), rtl)
        )
    lines += [
        "];",
        "",
        "/// The code a language's English name belongs to, for reading a page that heads its",
        "/// sections with names rather than codes.",
        "pub fn code_of(english: &str) -> Option<&'static str> {",
        "    LANGUAGES",
        "        .iter()",
        "        .find(|row| row.english.eq_ignore_ascii_case(english.trim()))",
        "        .map(|row| row.code)",
        "}",
        "",
        "/// What to call this language to a reader, or the code when it is not named.",
        "pub fn english(code: &str) -> &str {",
        "    LANGUAGES",
        "        .iter()",
        "        .find(|row| row.code == code)",
        "        .map(|row| row.english)",
        "        .unwrap_or(code)",
        "}",
        "",
    ]
    return rustfmt("\n".join(lines))


def accents_kt():
    """The same table for the phone, so both platforms ask the same voice for a word.

    The browser has had this since the accents existed and the phone had nothing, so the
    synthesiser there had no way to be told which voice a reader had chosen - and no way even
    to be told the language, since the voice for a plain language is the language's own name.
    """
    with open(ACCENTS) as f:
        table = json.load(f)["accents"]
    lines = [
        "package io.github.nanbiz.phonetix.core",
        "",
        "// The accents each language offers.",
        "//",
        "// Generated by tools/gen_types.py from data/accents.json; edit that and run it",
        "// again. An accent is offered only where there is a voice that really exists or a",
        "// rule that holds for a whole vocabulary.",
        "",
        "data class Accent(",
        "    val id: String,",
        "    val name: String,",
        "    /** The espeak voice, which is what says a word nothing has a recording of. */",
        "    val voice: String,",
        "    /** Whether the core rewrites a standard transcription into this accent. */",
        "    val rule: Boolean,",
        ")",
        "",
        "object Accents {",
        "",
        "    val all: Map<String, List<Accent>> = mapOf(",
    ]
    for lang in sorted(table):
        rows = ", ".join(
            'Accent(%s, %s, %s, %s)'
            % (
                json.dumps(row["id"]),
                json.dumps(row["name"], ensure_ascii=False),
                json.dumps(row["voice"]),
                "true" if row.get("rule") else "false",
            )
            for row in table[lang]
        )
        lines.append(f'        {json.dumps(lang)} to listOf({rows}),')
    lines += [
        "    )",
        "",
        "    /** What a language offers, which is nothing for most of them. */",
        "    fun of(lang: String): List<Accent> = all[lang] ?: emptyList()",
        "",
        "    /** The voice that says a word in this accent, or the language's own. */",
        "    fun voiceOf(lang: String, accent: String): String =",
        "        of(lang).firstOrNull { it.id == accent }?.voice ?: lang",
        "}",
        "",
    ]
    return "\n".join(lines)


def accents_ts():
    """The accents each language offers, for the view that lets a reader pick one."""
    with open(ACCENTS) as f:
        table = json.load(f)["accents"]
    lines = [
        "// The accents each language offers.",
        "//",
        "// Generated by tools/gen_types.py from data/accents.json; edit that and run it",
        "// again. An accent is offered only where there is a voice that really exists or a",
        "// rule that holds for a whole vocabulary.",
        "",
        "export interface Accent {",
        "  id: string;",
        "  name: string;",
        "  /** The espeak voice, which is what says a word nothing has a recording of. */",
        "  voice: string;",
        "  /** Whether the core rewrites a standard transcription into this accent. */",
        "  rule: boolean;",
        "}",
        "",
        "export const ACCENTS: Record<string, Accent[]> = {",
    ]
    for lang in sorted(table):
        rows = ", ".join(
            "{ id: %s, name: %s, voice: %s, rule: %s }"
            % (
                json.dumps(row["id"]),
                json.dumps(row["name"], ensure_ascii=False),
                json.dumps(row["voice"]),
                "true" if row.get("rule") else "false",
            )
            for row in table[lang]
        )
        lines.append(f"  {lang}: [{rows}],")
    lines += [
        "};",
        "",
        "/** What a language offers, which is nothing for most of them. */",
        "export function accentsOf(lang: string): Accent[] {",
        "  return ACCENTS[lang] ?? [];",
        "}",
        "",
        "/** The voice that says a word in this accent, or the language's own. */",
        "export function voiceOf(lang: string, accent: string): string {",
        "  return accentsOf(lang).find((row) => row.id === accent)?.voice ?? lang;",
        "}",
        "",
    ]
    return "\n".join(lines)


def choices_ts():
    """The words the settings surfaces are made of, for the extension."""
    with open(WORDING) as f:
        said = json.load(f)
    table, rows, ends, says = said["choices"], said["rows"], said["ends"], said["says"]
    lines = [
        "// The words the settings surfaces are made of.",
        "//",
        "// Generated by tools/gen_types.py from data/wording.json; edit that and run it again.",
        "// A choice a surface cannot draw is not offered on it: a browser owns the page and can",
        "// make room above a line, an overlay on a phone can only paint over what is already",
        "// there, so the phone has no \"both\" and no separate \"in place\" - its chip is the",
        "// replacement. The table says which platform each choice is for.",
        "// Both platforms say them: a row named one thing here and another on the phone is one",
        "// product speaking with two voices, and a one-word label is only a choice for somebody",
        "// who already knows what the other options are.",
        "",
        "export interface Choice {",
        "  /** What the setting is stored as. */",
        "  value: string;",
        "  /** What it is called on screen. */",
        "  label: string;",
        "  /** What it does, in one line. */",
        "  about: string;",
        "}",
        "",
        "/** What one row is called, and what it says under its name. */",
        "export interface Words {",
        "  name: string;",
        "  about: string;",
        "}",
        "",
    ]
    for name in sorted(table):
        lines.append(f"/** {table[name]['name']}, as a browser can draw them. */")
        lines.append(f"export const {name.upper()}_CHOICES: Choice[] = [")
        for row in table[name]["options"]:
            if "browser" not in row["on"]:
                continue
            lines.append(
                "  { value: %s, label: %s, about: %s },"
                % (
                    json.dumps(row["value"]),
                    json.dumps(row["label"], ensure_ascii=False),
                    json.dumps(row["about"]["browser"], ensure_ascii=False),
                )
            )
        lines.append("];")
        lines.append("")
    lines.append("/** Every row of the settings surfaces, by the name it reports itself under. */")
    lines.append("export const ROWS: Record<string, Words> = {")
    for name in sorted(rows):
        lines.append(
            "  %s: { name: %s, about: %s },"
            % (
                json.dumps(name),
                json.dumps(rows[name]["name"], ensure_ascii=False),
                json.dumps(rows[name]["about"], ensure_ascii=False),
            )
        )
    lines += ["};", ""]
    lines.append("/** What the two ends of a bar mean. */")
    lines.append("export const ENDS: Record<string, [string, string]> = {")
    for name in sorted(ends):
        lines.append(
            "  %s: [%s, %s],"
            % (
                json.dumps(name),
                json.dumps(ends[name][0], ensure_ascii=False),
                json.dumps(ends[name][1], ensure_ascii=False),
            )
        )
    lines += ["};", ""]
    lines.append("/** Phrases both surfaces use, so neither invents its own wording. */")
    lines.append("export const SAYS: Record<string, string> = {")
    for name in sorted(says):
        lines.append(
            "  %s: %s," % (json.dumps(name), json.dumps(says[name], ensure_ascii=False))
        )
    lines += [
        "};",
        "",
        "/** What one of them is called, for a row that says what it is set to. */",
        "export function labelOf(choices: Choice[], value: string): string {",
        "  return choices.find((row) => row.value === value)?.label ?? value;",
        "}",
        "",
        "/** What one of them does, for a row that explains itself. */",
        "export function aboutOf(choices: Choice[], value: string): string {",
        "  return choices.find((row) => row.value === value)?.about ?? '';",
        "}",
        "",
    ]
    return "\n".join(lines)


def choices_kotlin():
    """The same words for the phone's screen."""
    with open(WORDING) as f:
        said = json.load(f)
    table, rows, ends, says = said["choices"], said["rows"], said["ends"], said["says"]
    lines = [
        "package io.github.nanbiz.phonetix.core",
        "",
        "/**",
        " * The words the settings surfaces are made of.",
        " *",
        " * Generated by tools/gen_types.py from data/wording.json; edit that and run it again.",
        " * A choice this surface cannot draw is not offered here: an overlay paints over what an",
        " * app has already drawn, so there is no room above a word for a second line and the chip",
        " * over it is itself the replacement.",
        " *",
        " * Both platforms say them: a row named one thing here and another in the extension is",
        " * one product speaking with two voices, and a one-word label is only a choice for",
        " * somebody who already knows what the other options are.",
        " */",
        "data class Choice(val value: String, val label: String, val about: String)",
        "",
        "/** What one row is called, and what it says under its name. */",
        "data class Words(val name: String, val about: String)",
        "",
        "object Wording {",
    ]
    for name in sorted(table):
        lines.append(f"    /** {table[name]['name']}, as an overlay can draw them. */")
        lines.append(f"    val {name} = listOf(")
        for row in table[name]["options"]:
            if "phone" not in row["on"]:
                continue
            lines.append(
                "        Choice(%s, %s, %s),"
                % (
                    json.dumps(row["value"]),
                    json.dumps(row["label"], ensure_ascii=False),
                    json.dumps(row["about"]["phone"], ensure_ascii=False),
                )
            )
        lines += ["    )", ""]
    lines.append("    /** Every row of the settings surfaces, by the name it reports itself under. */")
    lines.append("    val rows = mapOf(")
    for name in sorted(rows):
        lines.append(
            "        %s to Words(%s, %s),"
            % (
                json.dumps(name),
                json.dumps(rows[name]["name"], ensure_ascii=False),
                json.dumps(rows[name]["about"], ensure_ascii=False),
            )
        )
    lines += ["    )", ""]
    lines.append("    /** What the two ends of a bar mean. */")
    lines.append("    val ends = mapOf(")
    for name in sorted(ends):
        lines.append(
            "        %s to Pair(%s, %s),"
            % (
                json.dumps(name),
                json.dumps(ends[name][0], ensure_ascii=False),
                json.dumps(ends[name][1], ensure_ascii=False),
            )
        )
    lines += ["    )", ""]
    lines.append("    /** Phrases both surfaces use, so neither invents its own wording. */")
    lines.append("    val says = mapOf(")
    for name in sorted(says):
        lines.append(
            "        %s to %s," % (json.dumps(name), json.dumps(says[name], ensure_ascii=False))
        )
    lines += [
        "    )",
        "",
        "    /** What one row is called, or the name it is asked for when the table is short. */",
        "    fun row(name: String): Words = rows[name] ?: Words(name, \"\")",
        "",
        "    /** What one choice is called, for a row that says what it is set to. */",
        "    fun labelOf(choices: List<Choice>, value: String): String =",
        "        choices.firstOrNull { it.value == value }?.label ?: value",
        "",
        "    /** What one choice does, for a row that explains itself. */",
        "    fun aboutOf(choices: List<Choice>, value: String): String =",
        "        choices.firstOrNull { it.value == value }?.about ?: \"\"",
        "}",
        "",
    ]
    return "\n".join(lines)


def languages_ts():
    """The same language table for the browser."""
    with open(LANGUAGES) as f:
        table = json.load(f)["languages"]
    lines = [
        "// What each language is called.",
        "//",
        "// Generated by tools/gen_types.py from data/languages.json; edit that and run it",
        "// again. A code the table does not hold is shown as the code: inventing a name for a",
        "// language is worse than admitting the table is short.",
        "",
        "export interface Named {",
        "  english: string;",
        "  /** What a speaker of it calls it. */",
        "  native: string;",
        "  rtl: boolean;",
        "}",
        "",
        "export const LANGUAGES: Record<string, Named> = {",
    ]
    for code in sorted(table):
        row = table[code]
        rtl = "true" if row.get("rtl") else "false"
        english = json.dumps(row["english"], ensure_ascii=False)
        native = json.dumps(row["native"], ensure_ascii=False)
        lines.append(f"  {code}: {{ english: {english}, native: {native}, rtl: {rtl} }},")
    lines += [
        "};",
        "",
        "/** What to call this language to a reader, or the code when it is not named. */",
        "export function named(code: string): string {",
        "  return LANGUAGES[code]?.english ?? code;",
        "}",
        "",
    ]
    return "\n".join(lines)


def main():
    with open(SURFACE) as f:
        page = f.read()
    style = "\n".join(re.findall(r"<style>(.*?)</style>", page, re.S))
    card = card_stylesheet(style)
    settings_css = cut_sections(style, SETTINGS_SECTIONS, "The settings surfaces")
    shared = named_rules(style, SETTINGS_ALSO)
    if len(shared) != len(SETTINGS_ALSO):
        raise SystemExit(f"the surface page no longer declares all of {SETTINGS_ALSO}")
    settings_css += "\n/* Shared with the card, where they are declared. */\n"
    settings_css += "\n".join(shared) + "\n"
    inline = inline_stylesheet(style)
    scalars, palettes, roles = read_surface()
    if not palettes or not roles:
        print("the surface page declares no palettes or no roles; nothing to write")
        return 1
    data = {
        "note": "Generated from docs/merge/surface.html by tools/tokens.py. Edit the page.",
        "scale": scalars,
        "roles": roles,
        "palettes": palettes,
    }
    written = json.dumps(data, indent=2, ensure_ascii=False, sort_keys=True) + "\n"
    code = kotlin(scalars, palettes, roles)
    sheet = stylesheet(scalars, palettes, roles)
    scoped = stylesheet(scalars, palettes, roles, scope=".px-w")
    named = languages_kotlin()
    named_ts = languages_ts()
    accents = accents_ts()
    accents_for_phone = accents_kt()
    chosen_ts = choices_ts()
    chosen_kt = choices_kotlin()
    symbols_rs = symbols_rust()
    languages_rs = languages_rust()
    neighbours_rs = neighbours_rust()

    if "--check" in sys.argv:
        stale = []
        for path, want in (
            (TOKENS, written), (KOTLIN, code), (CSS, sheet), (CARD_CSS, card), (INLINE_CSS, inline), (INLINE_TOKENS_CSS, scoped), (SETTINGS_CSS, settings_css),
            (LANGUAGES_KT, named), (LANGUAGES_TS, named_ts), (ACCENTS_TS, accents),
            (ACCENTS_KT, accents_for_phone), (SYMBOLS_RS, symbols_rs), (LANGUAGES_RS, languages_rs),
            (NEIGHBOURS_RS, neighbours_rs), (WORDING_TS, chosen_ts), (WORDING_KT, chosen_kt),
        ):
            have = open(path).read() if os.path.exists(path) else None
            if have != want:
                stale.append(os.path.relpath(path, ROOT))
        if stale:
            print("out of date with the surface page: " + ", ".join(stale))
            print("run: uv run python tools/gen_types.py")
            return 1
        print(f"{len(palettes)} palettes, {len(roles)} roles and the language table, "
              "and both platforms have them")
        return 0

    os.makedirs(os.path.dirname(TOKENS), exist_ok=True)
    with open(TOKENS, "w") as f:
        f.write(written)
    os.makedirs(os.path.dirname(KOTLIN), exist_ok=True)
    with open(KOTLIN, "w") as f:
        f.write(code)
    os.makedirs(os.path.dirname(CSS), exist_ok=True)
    with open(CSS, "w") as f:
        f.write(sheet)
    with open(CARD_CSS, "w") as f:
        f.write(card)
    with open(INLINE_CSS, "w") as f:
        f.write(inline)
    with open(INLINE_TOKENS_CSS, "w") as f:
        f.write(scoped)
    os.makedirs(os.path.dirname(SETTINGS_CSS), exist_ok=True)
    with open(SETTINGS_CSS, "w") as f:
        f.write(settings_css)
    with open(LANGUAGES_KT, "w") as f:
        f.write(named)
    os.makedirs(os.path.dirname(LANGUAGES_TS), exist_ok=True)
    with open(LANGUAGES_TS, "w") as f:
        f.write(named_ts)
    with open(ACCENTS_TS, "w") as f:
        f.write(accents)
    with open(ACCENTS_KT, "w") as f:
        f.write(accents_for_phone)
    with open(NEIGHBOURS_RS, "w") as f:
        f.write(neighbours_rs)
    with open(WORDING_TS, "w") as f:
        f.write(chosen_ts)
    with open(WORDING_KT, "w") as f:
        f.write(chosen_kt)
    os.makedirs(os.path.dirname(SYMBOLS_RS), exist_ok=True)
    with open(SYMBOLS_RS, "w") as f:
        f.write(symbols_rs)
    with open(LANGUAGES_RS, "w") as f:
        f.write(languages_rs)
    print(f"{len(palettes)} palettes, {len(roles)} roles, {len(scalars)} scalars")
    print(f"wrote {os.path.relpath(TOKENS, ROOT)}, {os.path.relpath(KOTLIN, ROOT)} "
          f"and {os.path.relpath(CSS, ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
