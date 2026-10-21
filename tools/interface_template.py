#!/usr/bin/env python3
"""Everything the interface says, in English, as one table: what a language's own table in
public/interface/<lang>.json translates, under the same names.

Read from data/wording.json, which the surfaces are generated from, and data/grammar-plain.json,
which says what each grammatical term is; the names of the terms themselves are added as phrases
named term-<value>, and the accents' names as accent-<name>. Written to stdout, or to the path given.

  uv run python tools/interface_template.py [out.json]
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def template():
    with open(os.path.join(ROOT, "data", "wording.json")) as f:
        wording = json.load(f)
    with open(os.path.join(ROOT, "data", "grammar-plain.json")) as f:
        plain = json.load(f)
    says = dict(wording["says"])
    for name in list(plain["categories"]) + list(plain["values"]):
        says.setdefault(f"term-{name}", name.replace("-", " "))
    # And the accents by the place or people they are named for: "Spain", "American".
    with open(os.path.join(ROOT, "data", "accents.json")) as f:
        accents = json.load(f)["accents"]
    for rows in accents.values():
        for row in rows if isinstance(rows, list) else []:
            if row.get("name"):
                says.setdefault(f"accent-{row['name']}", row["name"])
    return {
        "says": says,
        "rows": {name: {"name": row["name"], "about": row["about"]}
                 for name, row in wording["rows"].items()},
        "ends": wording["ends"],
        "choices": {
            table: {row["value"]: {"label": row["label"], "about": row["about"]}
                    for row in spec["options"]}
            for table, spec in wording["choices"].items()
        },
        "plain": {"categories": plain["categories"], "values": plain["values"]},
    }


if __name__ == "__main__":
    out = json.dumps(template(), ensure_ascii=False, indent=2) + "\n"
    if len(sys.argv) > 1:
        with open(sys.argv[1], "w") as f:
            f.write(out)
    else:
        sys.stdout.write(out)
