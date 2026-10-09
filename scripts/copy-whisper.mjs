// The speech engine's own files, put where the extension can load them at runtime.
//
// The same arrangement as the voice and the translator: the library and the WebAssembly runtime
// it runs the model in are loaded from a URL rather than bundled, so the build stays readable
// and each file is one a reviewer can identify as the package's own. The model itself is not
// here: it is fetched the first time a reader speaks to the panel.
import fs from "node:fs";
import path from "node:path";

const library = path.join(process.cwd(), "node_modules", "@huggingface", "transformers");
// The runtime the library was built against, wherever the package manager put it.
const runtime = path.join(fs.realpathSync(library), "..", "..", "onnxruntime-web", "dist");
const dest = path.join(process.cwd(), "public", "whisper");

fs.mkdirSync(dest, { recursive: true });
// The library as one file with its runtime's JavaScript inside it, unminified.
fs.copyFileSync(path.join(library, "dist", "transformers.js"), path.join(dest, "transformers.js"));
// The plain WebAssembly build: the model runs on the processor, which every browser has.
for (const file of ["ort-wasm-simd-threaded.mjs", "ort-wasm-simd-threaded.wasm"]) {
  fs.copyFileSync(path.join(runtime, file), path.join(dest, file));
}

console.log("Copied the speech engine to public/whisper/");
