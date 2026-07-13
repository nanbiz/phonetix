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

const TAG = "data-v1";
const ASSET = "dictionaries.tar.gz";
const URL = `https://github.com/nanbiz/phonetix/releases/download/${TAG}/${ASSET}`;

const outDir = path.join(process.cwd(), "public", "dictionaries");
const force = process.argv.includes("--force");

if (!force && fs.existsSync(outDir) && fs.readdirSync(outDir).some((f) => f.endsWith(".json.gz"))) {
    console.log("dictionaries already present, skipping (use --force to refetch)");
    process.exit(0);
}

console.log(`downloading ${URL}`);
const res = await fetch(URL, { redirect: "follow" });
if (!res.ok) throw new Error(`download failed: ${res.status} ${res.statusText}`);

const tmp = path.join(process.cwd(), ".cache", ASSET);
fs.mkdirSync(path.dirname(tmp), { recursive: true });
fs.writeFileSync(tmp, Buffer.from(await res.arrayBuffer()));

fs.mkdirSync(path.join(process.cwd(), "public"), { recursive: true });
execFileSync("tar", ["xzf", tmp, "-C", path.join(process.cwd(), "public")], { stdio: "inherit" });
fs.rmSync(tmp, { force: true });

const n = fs.readdirSync(outDir).filter((f) => f.endsWith(".json.gz")).length;
console.log(`extracted ${n} dictionaries into public/dictionaries`);
