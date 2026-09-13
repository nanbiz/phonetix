// Put the dictionaries where the build will carry them.
//
// They are fetched into assets/dictionaries (`pnpm fetch:dict`) and are the same files the app
// carries, so assets stays the one place they live and this copies them next to the build's
// other data. They are the product's own data rather than something a reader has to go and
// find: a fresh install answers how a word is said in any of them, with nothing configured.
//
//   node scripts/bundle-dictionaries.mjs

import fs from "node:fs";
import path from "node:path";

const from = path.join(process.cwd(), "assets", "dictionaries");
const to = path.join(process.cwd(), "public", "dictionaries");

if (!fs.existsSync(from)) {
    console.error(`no ${from}; run \`pnpm fetch:dict\` first`);
    process.exit(1);
}

fs.mkdirSync(to, { recursive: true });
let copied = 0;
let bytes = 0;
for (const name of fs.readdirSync(from)) {
    if (!name.endsWith(".json.gz")) continue;
    const source = path.join(from, name);
    const target = path.join(to, name);
    const already = fs.existsSync(target) && fs.statSync(target).size === fs.statSync(source).size;
    if (!already) fs.copyFileSync(source, target);
    copied += 1;
    bytes += fs.statSync(target).size;
}
console.log(`${copied} dictionaries, ${(bytes / 1024 / 1024).toFixed(1)} MB`);
