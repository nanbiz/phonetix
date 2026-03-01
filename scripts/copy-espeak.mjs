import fs from "node:fs";
import path from "node:path";

const src = path.join(process.cwd(), "node_modules", "@echogarden", "espeak-ng-emscripten");
const dest = path.join(process.cwd(), "public", "espeak");

fs.mkdirSync(dest, { recursive: true });

for (const file of ["espeak-ng.js", "espeak-ng.data"]) {
    fs.copyFileSync(path.join(src, file), path.join(dest, file));
}

console.log("Copied espeak-ng files to public/espeak/");
