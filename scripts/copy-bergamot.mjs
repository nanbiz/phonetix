// The translation engine's own files, put where the extension can load them at runtime.
//
// The same arrangement as espeak: a WebAssembly module and its worker are loaded from a URL
// rather than bundled, so the build stays readable and the engine stays one file a reviewer
// can identify. Everything here is what the package ships; nothing is rebuilt.
import fs from "node:fs";
import path from "node:path";

const src = path.join(process.cwd(), "node_modules", "@browsermt", "bergamot-translator");
const dest = path.join(process.cwd(), "public", "bergamot");

fs.mkdirSync(dest, { recursive: true });

// The package's own layout, kept exactly. The translator resolves its worker against its own
// URL rather than against the option it documents, so flattening these into one directory
// leaves it asking for a file that is not there - and a worker that never loads is a
// translation that never happens, with nothing in the way of an error.
for (const file of [
  "translator.js",
  "worker/translator-worker.js",
  "worker/bergamot-translator-worker.js",
  "worker/bergamot-translator-worker.wasm",
]) {
  const to = path.join(dest, file);
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(path.join(src, file), to);
}

console.log("Copied bergamot files to public/bergamot/");
