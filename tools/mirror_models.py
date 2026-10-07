#!/usr/bin/env python3
"""Copy the translation models a listing names into a directory, for the release to serve.

Mozilla's CDN refuses a Chromium browser (406), so a listing that points at it leaves Chrome
and its kin without translation. The models are published under the Mozilla Public License
2.0 (https://github.com/mozilla/firefox-translations-models), which lets them be served from
the release beside the packs:

  uv run python tools/mirror_models.py <models.json> <dir> <release url>

Every file is fetched from where the listing says, refused unless its checksum is the one the
listing names, and written to <dir> as "<from>-<to>.<name>": two directions can name their
files alike (English into both Chinese scripts does). The listing is then rewritten into
<dir>/models.json with each file's address in the release. A file already there whole is not
fetched again, so an interrupted run picks up where it stopped.
"""
import hashlib
import json
import os
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

AT_ONCE = 6
TRIES = 4


def asset(model, name):
    return f"{model['from']}-{model['to']}.{name}"


def checksum(path):
    digest = hashlib.sha256()
    with open(path, "rb") as file:
        for block in iter(lambda: file.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch(file, path):
    if os.path.exists(path) and checksum(path) == file["sha256"]:
        return False
    # Its own name per run, so two runs filling the same directory never write one file.
    part = f"{path}.{os.getpid()}.part"
    # A few tries: a CDN resets a connection now and then, and one reset among three hundred
    # files is no reason to stop the other two hundred and ninety-nine.
    for attempt in range(TRIES):
        try:
            with urllib.request.urlopen(file["url"], timeout=120) as answer, \
                    open(part, "wb") as out:
                for block in iter(lambda: answer.read(1 << 20), b""):
                    out.write(block)
            break
        except OSError:
            if os.path.exists(part):
                os.remove(part)
            if attempt == TRIES - 1:
                raise
            time.sleep(5 * (attempt + 1))
    got = checksum(part)
    if got != file["sha256"]:
        os.remove(part)
        raise RuntimeError(f"{file['url']} arrived as {got}, listed as {file['sha256']}")
    os.replace(part, path)
    return True


def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: mirror_models.py <models.json> <dir> <release url>")
    listing_path, into, release = sys.argv[1], sys.argv[2], sys.argv[3].rstrip("/")
    with open(listing_path) as file:
        listing = json.load(file)
    os.makedirs(into, exist_ok=True)

    jobs = [
        (file, os.path.join(into, asset(model, file["name"])))
        for model in listing
        for file in model["files"].values()
        if file.get("url")
    ]
    total = sum(file["bytes"] for file, _ in jobs)
    done = 0
    with ThreadPoolExecutor(AT_ONCE) as pool:
        for (file, path), fetched in zip(jobs, pool.map(lambda job: fetch(*job), jobs)):
            done += file["bytes"]
            print(f"{done / total:6.1%} {'fetched' if fetched else 'had'} {os.path.basename(path)}",
                  flush=True)

    for model in listing:
        for file in model["files"].values():
            file["url"] = f"{release}/{asset(model, file['name'])}"
    with open(os.path.join(into, "models.json"), "w") as out:
        json.dump(listing, out, indent=1)
        out.write("\n")
    print(f"{len(jobs)} files in {into}, listing rewritten to {release}")


if __name__ == "__main__":
    main()
