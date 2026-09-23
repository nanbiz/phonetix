#!/usr/bin/env python3
"""Split the kaikki.org dump of the English Wiktionary into one gzipped file per language.

One pass over about three gigabytes, so every pack is built from its own file afterwards
rather than from the whole dump once per language.

  uv run --with orjson python tools/split_kaikki.py <raw-wiktextract-data.jsonl.gz> <out dir>
  uv run --with orjson python tools/split_kaikki.py <dump> <out dir> sh kmr     only these

Named codes are for a second pass after the first: the languages the dump files under a code
of its own - Serbo-Croatian for Croatian, Serbian and Bosnian; Kurmanji for Kurdish. A line
that does not contain one of them anywhere cannot be an entry in it, so that pass parses only
the lines that might be and takes a minute rather than twenty.

Routed by the entry's own language, which is the top-level "lang_code" of each line. A line
carries others too - every translation it lists names its language - so the first one that
appears in the text is not the entry's: routing by it sent one entry in sixteen to the wrong
language's file, where the pack builder then threw it away.
"""
import gzip
import os
import sys
import time

# A parser in C: every line of the dump is parsed, and the standard library's takes an hour
# over it where this takes minutes.
import orjson

# The languages the product reads: the ones espeak-ng has a voice for, as the dictionaries of
# how words are said are chosen (scripts/build-dictionaries.mjs).
WANTED = frozenset(
    "af ar bg bn bs ca cs cy da de el en eo es et eu fa fi fr ga hi hr hu hy id is it ja ka "
    "kk ko ku la lt lv mk ml ms my nb nl no pl pt ro ru sk sl sq sr sv sw ta te th tr uk ur "
    "uz vi zh".split()
)


def lines(src):
    """The dump's lines, to the end of its data.

    The file as kaikki.org publishes it has bytes after its gzip stream. gzip itself says
    "trailing garbage ignored"; Python's reads them as a second member that is not one and
    raises, after every line has already been read.
    """
    with gzip.open(src, "rb") as dump:
        try:
            yield from dump
        except gzip.BadGzipFile:
            return


def main():
    src, out = sys.argv[1], sys.argv[2]
    wanted = frozenset(sys.argv[3:]) or WANTED
    # Only when the codes are named: for the whole set nearly every line names one of them.
    needles = [f'"{code}"'.encode() for code in wanted] if sys.argv[3:] else None
    files = {}
    n = 0
    started = time.time()
    try:
        for line in lines(src):
            n += 1
            if n % 1_000_000 == 0:
                print(f"{n} lines, {time.time() - started:.0f}s", flush=True)
            if needles is not None and not any(needle in line for needle in needles):
                continue
            try:
                code = orjson.loads(line).get("lang_code")
            except orjson.JSONDecodeError:
                continue
            if code not in wanted:
                continue
            sink = files.get(code)
            if sink is None:
                sink = files[code] = gzip.open(
                    os.path.join(out, f"{code}.jsonl.gz"), "wb", compresslevel=1
                )
            sink.write(line)
    finally:
        # Closed whatever happened, so each file ends as a gzip file ends rather than cut off
        # mid-stream where a pack built from it would stop reading without saying so.
        for sink in files.values():
            sink.close()
    print(f"done: {n} lines in {time.time() - started:.0f}s, {sorted(files)}", flush=True)


if __name__ == "__main__":
    main()
