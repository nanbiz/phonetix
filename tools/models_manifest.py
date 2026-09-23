#!/usr/bin/env python3
"""Write the listing of translation models the app fetches.

The models are Mozilla's: the ones Firefox translates with, published through Firefox's own
Remote Settings with each file on Mozilla's CDN under a name derived from its content. The app
does not copy them anywhere. It reads this listing, which names for every direction the newest
released version and, for each of its files, where Mozilla publishes it and the checksum it has
to have - so what a phone downloads is exactly what was checked here, whatever Mozilla
publishes next.

  uv run python tools/models_manifest.py out/models.json

A version with a letter in it ("1.0a1") is a pre-release and is left out. A record with a
filter expression is meant for particular Firefox builds and is left out too.
"""
import json
import re
import sys
import urllib.request

RECORDS = (
    "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/"
    "translations-models/records"
)
ATTACHMENTS = "https://firefox-settings-attachments.cdn.mozilla.net/"

# The kinds of file a direction is made of, as Remote Settings names them.
KINDS = {"model", "vocab", "srcvocab", "trgvocab", "lex"}


def released(version: str):
    """The version as numbers, or nothing for a pre-release."""
    if not re.fullmatch(r"\d+(\.\d+)*", version or ""):
        return None
    return tuple(int(part) for part in version.split("."))


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: models_manifest.py <out.json>")
    with urllib.request.urlopen(RECORDS, timeout=60) as answer:
        records = json.load(answer)["data"]

    by_pair = {}
    for record in records:
        if record.get("filter_expression"):
            continue
        version = released(record.get("version", ""))
        kind = record.get("fileType")
        if version is None or kind not in KINDS:
            continue
        pair = (record["fromLang"], record["toLang"])
        by_pair.setdefault(pair, {}).setdefault(version, {})[kind] = record

    listing = []
    for (source, target), versions in sorted(by_pair.items()):
        # The newest version that is whole: a model, and either one vocabulary or both halves.
        for version in sorted(versions, reverse=True):
            files = versions[version]
            whole = "model" in files and (
                "vocab" in files or ("srcvocab" in files and "trgvocab" in files)
            )
            if not whole:
                continue
            listing.append({
                "from": source,
                "to": target,
                "version": ".".join(str(part) for part in version),
                "files": {
                    kind: {
                        "name": record["name"],
                        "url": ATTACHMENTS + record["attachment"]["location"],
                        "sha256": record["attachment"]["hash"],
                        "bytes": record["attachment"]["size"],
                    }
                    for kind, record in sorted(files.items())
                },
            })
            break

    with open(sys.argv[1], "w") as out:
        json.dump(listing, out, indent=1)
        out.write("\n")
    print(f"{len(listing)} directions written to {sys.argv[1]}")


if __name__ == "__main__":
    main()
