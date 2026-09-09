#!/usr/bin/env bash
# Everything CI checks, run here.
#
# The workflow exists for a pull request and for a runner that has none of this installed.
# This machine has all of it, so the same checks run in a fraction of the time and without
# spending a month of runner minutes in a week.
#
#   scripts/ci.sh            everything
#   scripts/ci.sh core       the reading core alone
#   scripts/ci.sh browser    the extension and the checks that drive it
#   scripts/ci.sh android    the app and its unit tests
#
# `act -j build` runs the workflow itself in Docker, which is what to use after editing it.
set -euo pipefail

cd "$(dirname "$0")/.."
what="${1:-all}"
failed=()

step() {
  local name="$1"
  shift
  printf '\n\033[1m%s\033[0m\n' "$name"
  if "$@"; then
    printf '  ok\n'
  else
    printf '  FAILED\n'
    failed+=("$name")
  fi
}

if [[ "$what" == all || "$what" == core ]]; then
  step "the tables both platforms read are current" uv run python tools/gen_types.py --check
  step "the core's tests" cargo test --workspace --manifest-path core/Cargo.toml
  step "the core's formatting" cargo fmt --all --check --manifest-path core/Cargo.toml
  step "the core's lints" \
    cargo clippy --workspace --all-targets --manifest-path core/Cargo.toml -- -D warnings
  step "the core builds for a browser" \
    cargo build -p lexcore --target wasm32-unknown-unknown --manifest-path core/Cargo.toml
  step "the core builds for a phone" \
    cargo build -p lexcore --target aarch64-linux-android --manifest-path core/Cargo.toml
fi

if [[ "$what" == all || "$what" == browser ]]; then
  step "the core, compiled for the browser" node scripts/build-core-wasm.mjs
  step "the extension typechecks" pnpm check
  step "the extension builds" pnpm build
  step "the core answers inside the extension" uv run python scripts/proofread/lex_extension.py
  step "every card state draws what it means" uv run python scripts/proofread/card_states.py
  step "a page is annotated and comes back" uv run python scripts/proofread/on_a_page.py
  step "every setting changes what is seen" uv run python scripts/proofread/settings_view.py
fi

if [[ "$what" == all || "$what" == android ]]; then
  step "the app's tests" ./gradlew --no-daemon -p android testDebugUnitTest -q
  step "the app builds" ./gradlew --no-daemon -p android assembleDebug -q
fi

printf '\n'
if [[ ${#failed[@]} -gt 0 ]]; then
  printf '\033[1mFAILED:\033[0m %s\n' "${failed[*]}"
  exit 1
fi
printf '\033[1mall of it passed\033[0m\n'
