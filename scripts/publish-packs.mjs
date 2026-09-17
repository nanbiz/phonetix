// Publish the meaning packs to the release the app fetches them from.
//
// A pack is built once out of a dump of gigabytes (see core/packbuild) and does not change
// again, so it belongs in a release rather than on a host somebody has to keep running: the
// pronunciations the app carries are already fetched from one of these at build time. The app
// asks the release for packs.json and then for <lang>.pack, which is what this uploads.
//
//   node scripts/publish-packs.mjs <dir with the built .pack files>
//
// Every file in that directory named <lang>.pack is published, and packs.json is written from
// what is there: the language, how many words it holds, how big it is, and its checksum.
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { execFileSync } from "node:child_process";

const REPO = "nanbiz/phonetix";
const TAG = "packs-v1";

const from = process.argv[2];
if (!from) {
    console.error("usage: node scripts/publish-packs.mjs <dir with the built .pack files>");
    process.exit(2);
}

const packs = fs
    .readdirSync(from)
    .filter((name) => name.endsWith(".pack"))
    .map((name) => {
        const file = path.join(from, name);
        const bytes = fs.readFileSync(file);
        return {
            lang: name.slice(0, -".pack".length),
            // What the pack says it holds, taken from the manifest packbuild printed beside
            // it where there is one: a count nothing wrote is not invented here.
            entries: entriesOf(file),
            bytes: bytes.length,
            sha256: crypto.createHash("sha256").update(bytes).digest("hex"),
        };
    });
if (!packs.length) throw new Error(`no .pack files in ${from}`);

/** What packbuild wrote beside the pack, where it did. */
function entriesOf(file) {
    const beside = `${file}.json`;
    if (!fs.existsSync(beside)) return 0;
    const said = JSON.parse(fs.readFileSync(beside, "utf8"));
    return Number(said.entries ?? 0);
}

const listing = path.join(from, "packs.json");
fs.writeFileSync(listing, JSON.stringify(packs, null, 2) + "\n");
console.log(`publishing ${packs.map((p) => p.lang).join(", ")} to ${TAG}`);

// The release is made once and added to afterwards, so a missing one is not an error.
try {
    execFileSync("gh", ["release", "view", TAG, "--repo", REPO], { stdio: "ignore" });
} catch {
    execFileSync(
        "gh",
        ["release", "create", TAG, "--repo", REPO, "--title", "Dictionary packs",
         "--notes", "The packs the app fetches: packs.json and one file per language."],
        { stdio: "inherit" },
    );
}
execFileSync(
    "gh",
    ["release", "upload", TAG, "--repo", REPO, "--clobber", listing,
     ...packs.map((p) => path.join(from, `${p.lang}.pack`))],
    { stdio: "inherit" },
);
console.log("published");
