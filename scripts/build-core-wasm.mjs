// The core, compiled for the browser.
//
// The same crate the phone links as a native library is compiled to WebAssembly here, so the
// extension answers a word with the code the phone answers it with rather than with a second
// implementation of the same cascade.
//
// Two artefacts come out: the glue, which is imported like any module, and the binary, which
// is fetched at runtime from the extension's own package. The binary goes to the public
// directory rather than through the bundler, because a bundler that inlines it would put a
// multi-megabyte base64 string into a script the browser has to parse before anything runs.
//
//   node scripts/build-core-wasm.mjs
//
// Needs the wasm32 target and the wasm-bindgen CLI, both declared in the system config; the
// CLI's version has to match the crate's wasm-bindgen dependency exactly, and this refuses to
// build when it does not rather than producing a module that fails at instantiation.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const core = path.join(root, 'core');
const glueDir = path.join(root, 'src', 'core', 'wasm');
const binaryDir = path.join(root, 'public', 'core');

function run(command, args, options = {}) {
  return execFileSync(command, args, { stdio: 'inherit', ...options });
}

function pinnedVersion() {
  const manifest = fs.readFileSync(path.join(core, 'wasm', 'Cargo.toml'), 'utf-8');
  const pin = manifest.match(/wasm-bindgen\s*=\s*"=([\d.]+)"/);
  if (!pin) throw new Error('core/wasm/Cargo.toml no longer pins a wasm-bindgen version');
  return pin[1];
}

function installedVersion() {
  const out = execFileSync('wasm-bindgen', ['--version'], { encoding: 'utf-8' });
  return out.trim().split(/\s+/).pop();
}

const wanted = pinnedVersion();
const have = installedVersion();
if (wanted !== have) {
  throw new Error(
    `wasm-bindgen ${have} cannot generate glue for a crate built against ${wanted}. ` +
      'The two carry a schema version that has to match exactly. Change the pin in ' +
      'core/wasm/Cargo.toml or the CLI in the system config, not one of them alone.'
  );
}

run('cargo', ['build', '--release', '--target', 'wasm32-unknown-unknown', '-p', 'lexcore-wasm'], {
  cwd: core,
});

fs.mkdirSync(glueDir, { recursive: true });
fs.mkdirSync(binaryDir, { recursive: true });
run('wasm-bindgen', [
  // The web target: an ordinary ES module, with the binary fetched from a URL the caller
  // gives it. A bundler target would have the extension's own bundler decide how the binary
  // is loaded, and both stores refuse the ways it decides to do that.
  '--target',
  'web',
  '--out-dir',
  glueDir,
  '--out-name',
  'lexcore',
  path.join(core, 'target', 'wasm32-unknown-unknown', 'release', 'lexcore_wasm.wasm'),
]);

// The binary is served from the extension package, so it lives with the other assets rather
// than beside its glue.
const built = path.join(glueDir, 'lexcore_bg.wasm');
fs.renameSync(built, path.join(binaryDir, 'lexcore_bg.wasm'));
fs.rmSync(path.join(glueDir, 'lexcore_bg.wasm.d.ts'), { force: true });

// The language model the core reads, unpacked into the extension's own package: the core
// takes it as it is, and a browser fetching it from the network would be a browser asking
// somebody about what its reader is reading.
const model = path.join(root, 'assets', 'eld.bin.gz');
if (fs.existsSync(model)) {
  fs.writeFileSync(path.join(binaryDir, 'eld.bin'), zlib.gunzipSync(fs.readFileSync(model)));
} else {
  throw new Error(`Missing ${model}. Run node scripts/build-eld-model.mjs first.`);
}

const size = fs.statSync(path.join(binaryDir, 'lexcore_bg.wasm')).size;
console.log(`core: ${(size / 1024).toFixed(0)} KB of WebAssembly, glue in src/core/wasm`);
