import fs from "node:fs";
import path from "node:path";

const src = path.join(process.cwd(), "node_modules", "@echogarden", "espeak-ng-emscripten");
const dest = path.join(process.cwd(), "public", "espeak");

fs.mkdirSync(dest, { recursive: true });

for (const file of ["espeak-ng.js", "espeak-ng.data"]) {
    fs.copyFileSync(path.join(src, file), path.join(dest, file));
}

console.log("Copied espeak-ng files to public/espeak/");

// The homograph classifiers, built from the trained tables by tools/build_homographs.py.
// They ship with the extension rather than being fetched: they are a hundred kilobytes for
// every language together, and a reader should not have to fetch one to be told which word
// they are looking at.
const classifiers = path.join(process.cwd(), "assets", "homographs");
const into = path.join(process.cwd(), "public", "homographs");
if (fs.existsSync(classifiers)) {
    fs.mkdirSync(into, { recursive: true });
    let taken = 0;
    for (const name of fs.readdirSync(classifiers)) {
        if (!name.endsWith(".hg")) continue;
        fs.copyFileSync(path.join(classifiers, name), path.join(into, name));
        taken += 1;
    }
    console.log(`Copied ${taken} homograph classifiers to public/homographs/`);
}
