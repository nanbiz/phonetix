// Download the prebuilt IPA dictionaries into public/dictionaries.
//
// The dictionaries are large binary data, so they are not committed. They are
// published as a release asset instead. Building them from scratch needs the
// ~2.3GB kaikki dump (see build-dictionaries.mjs); this just fetches the result.
//
//   node scripts/fetch-dictionaries.mjs [--force]

import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";

const REPO = "nanbiz/phonetix";
const TAG = "data-v3";
const ASSET = "dictionaries.tar.gz";
const URL = `https://github.com/nanbiz/phonetix/releases/download/${TAG}/${ASSET}`;

const outDir = path.join(process.cwd(), "public", "dictionaries");
const force = process.argv.includes("--force");

if (!force && fs.existsSync(outDir) && fs.readdirSync(outDir).some((f) => f.endsWith(".json.gz"))) {
    console.log("dictionaries already present, skipping (use --force to refetch)");
    process.exit(0);
}

const tmp = path.join(process.cwd(), ".cache", ASSET);
fs.mkdirSync(path.dirname(tmp), { recursive: true });

// The release asset needs authentication while the repository is private, so
// prefer the GitHub CLI (it carries the token in CI and locally) and fall back
// to a plain download.
try {
    console.log(`downloading ${ASSET} from release ${TAG}`);
    execFileSync(
        "gh",
        ["release", "download", TAG, "--repo", REPO, "--pattern", ASSET, "--output", tmp, "--clobber"],
        { stdio: "inherit" },
    );
} catch {
    console.log(`gh unavailable, downloading ${URL}`);
    const res = await fetch(URL, { redirect: "follow" });
    if (!res.ok) throw new Error(`download failed: ${res.status} ${res.statusText}`);
    fs.writeFileSync(tmp, Buffer.from(await res.arrayBuffer()));
}

fs.mkdirSync(path.join(process.cwd(), "public"), { recursive: true });
execFileSync("tar", ["xzf", tmp, "-C", path.join(process.cwd(), "public")], { stdio: "inherit" });
fs.rmSync(tmp, { force: true });

const accentDir = path.join(outDir, "accents");
const accents = fs.existsSync(accentDir)
    ? fs.readdirSync(accentDir).filter((f) => f.endsWith(".json.gz")).length
    : 0;
if (!accents) {
    throw new Error(
        "the asset carries no accent overlays; choosing an accent would leave the page unchanged",
    );
}
console.log(`${accents} accent overlays`);

const n = fs.readdirSync(outDir).filter((f) => f.endsWith(".json.gz")).length;
console.log(`extracted ${n} dictionaries into public/dictionaries`);
