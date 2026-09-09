#!/usr/bin/env bash
# Build a release here and put it on GitHub.
#
# A runner is for a machine that has none of this installed. This one has all of it, and a
# release is the only thing the workflow was really needed for, so the artifacts are built
# where the code is and uploaded to the release that holds them.
#
#   scripts/release.sh 0.39.0            build it, tag it, upload it
#   scripts/release.sh 0.39.0 --dry      build it and stop, nothing tagged or pushed
#   scripts/release.sh 0.39.0 --checked  skip the checks, for when they have just run on
#                                        this same tree and nothing has changed since
#
# The Firefox add-on is signed by Mozilla, which needs MOZ_API_KEY and MOZ_API_SECRET in the
# environment; without them the xpi is left unsigned and the release says which artifacts it
# has. Everything else needs nothing but this machine.
#
# The release notes are yours: this creates the release with GitHub's own generated notes and
# prints the URL to edit them.
set -euo pipefail

cd "$(dirname "$0")/.."

version="${1:-}"
dry="${2:-}"
if [[ -z "$version" ]]; then
  echo "usage: scripts/release.sh <version> [--dry]" >&2
  exit 2
fi
tag="v$version"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "the tree is not clean; commit or stash first" >&2
  exit 1
fi

if [[ "$dry" == "--checked" ]]; then
  echo "== the checks were run separately"
else
  echo "== the checks"
  scripts/ci.sh
fi

echo "== the version"
node -e '
  const fs = require("fs");
  const version = process.argv[1];
  const manifest = JSON.parse(fs.readFileSync("package.json", "utf8"));
  manifest.version = version;
  fs.writeFileSync("package.json", JSON.stringify(manifest, null, 2) + "\n");
' "$version"

echo "== building"
pnpm build
pnpm build:firefox
pnpm zip
pnpm zip:firefox
./android/gradlew --no-daemon -p android assembleDebug -q

out=".output"
apk="$out/phonetix-$tag-android-debug.apk"
cp android/app/build/outputs/apk/debug/app-debug.apk "$apk"

signed=""
if [[ -n "${MOZ_API_KEY:-}" && -n "${MOZ_API_SECRET:-}" ]]; then
  echo "== signing with Mozilla"
  pnpm dlx web-ext@latest sign \
    --channel=unlisted \
    --approval-timeout=5400000 \
    --api-key="$MOZ_API_KEY" \
    --api-secret="$MOZ_API_SECRET" \
    --source-dir="$out/firefox-mv2" \
    --artifacts-dir="$out/signed"
  signed=$(ls -t "$out"/signed/*.xpi 2>/dev/null | head -1 || true)
else
  echo "== no Mozilla keys in the environment; the Firefox build goes up unsigned"
fi

# This version's zips by name, not everything in the directory. The output directory keeps
# what earlier releases built, and a glob over all of it put three versions of the extension
# on one release for a reader to choose between.
artifacts=("$apk")
for zip in "$out/phonetix-$version-chrome.zip" "$out/phonetix-$version-firefox.zip"; do
  if [[ -e "$zip" ]]; then
    artifacts+=("$zip")
  else
    echo "the build produced no $zip" >&2
    exit 1
  fi
done
[[ -n "$signed" ]] && artifacts+=("$signed")

echo "== what would go up"
printf '  %s\n' "${artifacts[@]}"

if [[ "$dry" == "--dry" ]]; then
  echo "(dry run; nothing tagged, nothing pushed)"
  exit 0
fi

# The repository belongs to a different account than the one gh happens to be signed in as,
# so the token is asked for by name rather than assumed.
token=$(gh auth token --user nanbiz)
export GH_TOKEN="$token"

git add package.json
git commit -m "$version"
# Annotated, because --follow-tags pushes those and silently leaves a lightweight tag
# behind, which is how a release ends up with everything built and nothing to hang it on.
git tag -a "$tag" -m "$tag"
git -c credential.helper='!f(){ echo username=nanbiz; echo password=$GH_TOKEN; };f' push origin main --follow-tags

gh release create "$tag" --title "$tag" --generate-notes "${artifacts[@]}"
echo
echo "the release is up; its notes are yours to write:"
gh release view "$tag" --json url --jq .url
