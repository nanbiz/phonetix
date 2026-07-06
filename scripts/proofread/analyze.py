#!/usr/bin/env python3
"""Quantified failure taxonomy. Loads the shipped dicts to judge each span."""
import json, os, re, sys, gzip, collections

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(OUT, "baseline.jsonl")

DICTS = {}
def dic(lang):
    if lang not in DICTS:
        p = os.path.join(ROOT, "public", "dictionaries", f"{lang}.json.gz")
        DICTS[lang] = json.loads(gzip.open(p).read()) if os.path.exists(p) else {}
    return DICTS[lang]

MARKER = re.compile(r"\([a-zʔ]{1,3}\)|_|[0-9]")
NONWORD = re.compile(r"^[\W\d_]+$|^@|^#")
ASCII_STRESS = "'"      # espeak fallback signature
MODERN_STRESS = "ˈ"

pages = [json.loads(l) for l in open(path) if l.strip()]
agg = collections.Counter()
foreign = []      # not in page-lang dict but in English dict (Renault-class)
espeak = []       # ascii-' stress → espeak fired
markers = []
nonwords = []
blobs = []

for pg in pages:
    plang = (pg.get("htmlLang", "") or "en")[:2].lower()
    pd = dic(plang)
    end = dic("en")
    spans = pg.get("spans", [])
    agg["spans"] += len(spans)
    for sp in spans:
        o, ipa, lang = sp["o"], sp["ipa"], sp["lang"]
        lo = o.lower()
        if MARKER.search(ipa):
            agg["marker"] += 1; markers.append((o, ipa, lang))
        if NONWORD.search(o):
            agg["nonword"] += 1; nonwords.append((o, ipa))
        if len(o) > 28:
            agg["blob"] += 1; blobs.append((o[:70], ipa[:40]))
        if ASCII_STRESS in ipa and MODERN_STRESS not in ipa:
            agg["espeak"] += 1; espeak.append((o, ipa, lang))
        in_page = lo in pd or o in pd
        if in_page:
            agg["dict_hit"] += 1
        else:
            agg["dict_miss"] += 1
            if plang != "en" and (lo in end or o in end):
                agg["foreign_in_en"] += 1
                if lang == plang:
                    agg["foreign_WRONG"] += 1   # still rendered in the page language
                    foreign.append((o, ipa, lang, plang))
                elif lang == "en":
                    agg["foreign_FIXED"] += 1   # cross-dict resolved to English

n = max(1, agg["spans"])
def pct(k): return f"{agg[k]}  ({100*agg[k]/n:.1f}%)"
print(f"pages={len(pages)}  spans={agg['spans']}")
print(f"dict hit:            {pct('dict_hit')}")
print(f"dict MISS:           {pct('dict_miss')}")
print(f"espeak-fired (ascii '): {pct('espeak')}")
print(f"FOREIGN candidates (not in page dict, IS in en dict): {pct('foreign_in_en')}")
print(f"   -> STILL WRONG (rendered in page lang): {pct('foreign_WRONG')}")
print(f"   -> FIXED (cross-dict -> English):        {pct('foreign_FIXED')}")
print(f"marker leaks (xx)/_/digit:  {pct('marker')}")
print(f"nonword phonemized:  {pct('nonword')}")
print(f"concat blobs (>28ch):{pct('blob')}")

def samp(lst, n=15):
    for x in lst[:n]: print("   ", "  ".join(repr(v) for v in x))
print("\n### FOREIGN mis-render samples (Renault-class):"); samp(foreign)
print("\n### espeak samples:"); samp(espeak)
print("\n### marker samples:"); samp(markers)
print("\n### nonword samples:"); samp(nonwords)
print("\n### blob samples:"); samp(blobs)

# per-page espeak + foreign rate
print("\n### per-page:")
for pg in pages:
    sp = pg.get("spans", [])
    if not sp:
        print(f"   {pg.get('spanCount',0):6d}  {pg['url']}"); continue
    esk = sum(1 for s in sp if ASCII_STRESS in s['ipa'] and MODERN_STRESS not in s['ipa'])
    print(f"   n={len(sp):5d} espeak={100*esk/len(sp):4.0f}%  {pg.get('htmlLang','?'):6s} {pg['url']}")
