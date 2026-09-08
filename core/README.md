# The reading core

One crate, compiled three ways: to the host for its own tests, to `wasm32-unknown-unknown` for
the browser extension, and to a native library per Android ABI for the overlay. What a word
means, how it is said, and which words are worth annotating live here. Finding words on a screen
and drawing on them do not, because a DOM and an accessibility tree have nothing in common.

The point is not that the code is written once. It is that neither platform can answer a
question about a word any other way, so a change made on one side cannot quietly fail to happen
on the other.

## Building it

The toolchain is declared in the machine's Nix config, not installed per project: one Rust
toolchain carrying the host, wasm32 and both Android targets, plus `gcc`, `lld`,
`wasm-bindgen-cli` and `cargo-ndk`.

```sh
cargo test                                   # the core against its own tests

# the browser's copy
cargo build --release --target wasm32-unknown-unknown -p lexcore-wasm
wasm-bindgen --target nodejs --out-dir <dir> \
    target/wasm32-unknown-unknown/release/lexcore_wasm.wasm
node wasm/test/same-answers.mjs              # run from that directory

# the overlay's copy, into the app's jniLibs
ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.0.12077973 \
    cargo ndk -t x86_64 -t arm64-v8a -o ../android/app/src/main/jniLibs build --release \
    -p lexcore-android
cd ../android && ./gradlew :app:connectedDebugAndroidTest
```

`wasm-bindgen` the crate is pinned to exactly the version of the CLI the system provides. The
two share a schema that has to match, and the CLI is the fixed point when it comes from the
system config rather than from a lockfile.

## What is here

- `src/gloss.rs` - matching a word in one language to a word in another through the English
  gloss both carry. This is the join the pack format rests on, and its tests are measurements:
  `docs/merge/coverage.md` records what was measured and the two approaches that were tried and
  came out worse.
- `src/answer.rs` - the one value per word that the inline layer, the card, the lens and the
  audio all read, so that the four cannot disagree.
- `wasm/`, `android/` - the two wrappers. Neither holds any logic, on purpose: something that
  decided anything there would exist for one platform only, which is the drift this whole
  arrangement is meant to make impossible.

Both wrappers are tested against the same three words, in node and on a device, because one
implementation is only one implementation if the runtimes agree about it.
