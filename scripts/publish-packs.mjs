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
        const said = besideOf(file);
        return {
            id: said.id ?? `lex-${name.slice(0, -".pack".length)}`,
            lang: name.slice(0, -".pack".length),
            built: Number(said.built ?? 0),
            // What the pack says it holds, taken from the manifest packbuild printed beside
            // it where there is one: a count nothing wrote is not invented here.
            entries: Number(said.entries ?? 0),
            keys: Number(said.keys ?? 0),
            glosses: Number(said.glosses ?? 0),
            bytes: bytes.length,
            // Worked out here from the bytes being published, not copied: the checksum is what
            // a phone refuses a download by, so it has to be of this file.
            sha256: crypto.createHash("sha256").update(bytes).digest("hex"),
        };
    })
    .sort((a, b) => a.lang.localeCompare(b.lang));
if (!packs.length) throw new Error(`no .pack files in ${from}`);

/** What packbuild wrote beside the pack, where it did. */
function besideOf(file) {
    const beside = `${file}.json`;
    if (!fs.existsSync(beside)) return {};
    try {
        return JSON.parse(fs.readFileSync(beside, "utf8"));
    } catch {
        return {};
    }
}

// Whatever else belongs beside the packs: the listing of translation models, and where the
// words came from and under what licence, which has to travel with them.
const alongside = ["models.json", "ATTRIBUTION.md"]
    .map((name) => path.join(from, name))
    .filter((file) => fs.existsSync(file));

// The translation models the listing names, copied by tools/mirror_models.py: Mozilla's CDN
// refuses Chromium browsers, so they are served from here.
const modelsDir = path.join(from, "models");
const models = fs.existsSync(modelsDir)
    ? fs.readdirSync(modelsDir)
        .filter((name) => !name.endsWith(".part") && name !== "models.json")
        .map((name) => path.join(modelsDir, name))
    : [];

const listing = path.join(from, "packs.json");
fs.writeFileSync(listing, JSON.stringify(packs, null, 2) + "\n");
console.log(`publishing ${packs.map((p) => p.lang).join(", ")} to ${TAG}`);

// The release is made once and added to afterwards, so a missing one is not an error.
try {
    execFileSync("gh", ["release", "view", TAG, "--repo", REPO], { stdio: "ignore" });
} catch {
    execFileSync(
        "gh",
        // Not the latest release: that is the product, and this is data it downloads.
        ["release", "create", TAG, "--repo", REPO, "--title", "Dictionary packs",
         "--latest=false",
         "--notes", "The packs the app fetches: packs.json and one file per language. " +
            "See ATTRIBUTION.md for the source and licence of the data."],
        { stdio: "inherit" },
    );
}
// What the release holds already, by checksum: a pack or model that has not changed is not
// sent again, which over a home connection is the difference between minutes and hours.
const release = execFileSync(
    "gh", ["api", `repos/${REPO}/releases/tags/${TAG}`, "--jq", ".id"], { encoding: "utf8" },
).trim();
const held = new Map(
    execFileSync(
        "gh",
        ["api", "--paginate", `repos/${REPO}/releases/${release}/assets?per_page=100`,
         "--jq", ".[] | [.name, .digest]"],
        { encoding: "utf8", maxBuffer: 1 << 26 },
    ).trim().split("\n").filter(Boolean).map((row) => JSON.parse(row)),
);
const changed = (file) =>
    held.get(path.basename(file)) !==
        `sha256:${crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex")}`;

// The files first and the listings last, so a listing is never published naming a file that
// is not there yet; a few at a time, so a dropped connection costs one batch.
const files = [...packs.map((p) => path.join(from, `${p.lang}.pack`)), ...models].filter(changed);
console.log(`${files.length} files changed`);
for (let at = 0; at < files.length; at += 10) {
    execFileSync(
        "gh",
        ["release", "upload", TAG, "--repo", REPO, "--clobber", ...files.slice(at, at + 10)],
        { stdio: "inherit" },
    );
    console.log(`${Math.min(at + 10, files.length)}/${files.length}`);
}
execFileSync(
    "gh",
    ["release", "upload", TAG, "--repo", REPO, "--clobber", ...alongside, listing],
    { stdio: "inherit" },
);
console.log("published");
