#!/usr/bin/env bash
# Build the Safari extension and the iPhone app that carries it, and install it on the iPhone
# plugged in over USB.
#
#   ios/build.sh          build and install on the phone
#   ios/build.sh --only   build the .ipa and stop (xtool dev build)
#
# Safari wants the extension's files at the root of the app extension's bundle, and xtool puts
# each path listed under an extension's resources there, so xtool.yml is written here from
# what the build produced rather than kept by hand.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
cd "$here/.."
pnpm exec wxt build -b safari

out="$here/Extension"
rm -rf "$out"
cp -r .output/safari-mv2 "$out"
cp icon.png "$here/Support/Icon.png"

{
  echo "# Written by ios/build.sh."
  echo "version: 1"
  echo "bundleID: io.github.nanbiz.phonetix"
  echo "product: Phonetix"
  echo "iconPath: Support/Icon.png"
  echo "extensions:"
  echo "- product: PhonetixSafari"
  echo "  bundleID: io.github.nanbiz.phonetix.safari"
  echo "  infoPath: Support/PhonetixSafari-Info.plist"
  echo "  resources:"
  for entry in "$out"/*; do echo "  - Extension/$(basename "$entry")"; done
} > "$here/xtool.yml"

cd "$here"
if [[ "${1:-}" == "--only" ]]; then
  xtool dev build
else
  xtool dev
fi
