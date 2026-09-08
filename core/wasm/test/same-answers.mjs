// The core's own tests prove the Rust answers these three words correctly. This asks the
// browser's copy the same question, because one implementation is only one implementation if
// both runtimes agree, and a binding that quietly drops or reorders arguments would not be
// caught by anything else here.
//
// Built by: cargo build --release --target wasm32-unknown-unknown -p lexcore-wasm
//           wasm-bindgen --target nodejs --out-dir <dir> <the .wasm>
// then run with node against that directory.
import { overlap, best_of } from './lexcore_wasm.js';

// The same three words the Rust tests use, so the two runtimes are being asked one question.
const cases = [
  ['dog (the species Canis familiaris)', ['male dog', 'dog, hound'], 1, 'perro'],
  ['chair', ['armchair, easy chair (comfortable chair with arms)', 'a chair (to sit on)'], 1, 'silla'],
  ['way, route', ['way, manner', 'route, way (to get from one place to another)'], 1, 'camino'],
];
let ok = true;
for (const [source, candidates, margin, word] of cases) {
  const scores = candidates.map((c) => overlap(source, c));
  const pick = best_of(source, candidates, margin);
  const right = pick === candidates.length - 1;
  ok &&= right;
  console.log(`${word.padEnd(7)} scores ${JSON.stringify(scores)} -> picked ${pick} ${right ? 'ok' : 'WRONG'}`);
}
console.log(`\nambiguous returns nothing: ${best_of('chair', ['chair', 'a chair (to sit on)'], 1)} (want -1)`);
console.log(ok ? 'the browser gets the same answers as the core' : 'MISMATCH');
