#!/usr/bin/env bash
# espeak-ng for the phone: the library per ABI, and the data it reads.
#
# The browser has had a synthesiser since before the merge and the phone has not, so a word no
# pack holds came back with no transcription at all there, and the audio was whatever voice the
# system happened to have rather than the one that says the word everywhere else. This is the
# same engine the browser runs, built native.
#
#   scripts/build-espeak-android.sh          build into android/app/src/main
#
# What it produces:
#   jniLibs/<abi>/libespeak-ng.so   the engine, per ABI
#   assets/espeak/                  the language-independent core, plus one file per language
#
# The data is compiled by the build itself, by running the espeak-ng it just built - which
# cannot be the phone's build, since that binary is for the phone. So a host build is made
# first and used only for that. The data it writes is not architecture-specific.
set -euo pipefail

cd "$(dirname "$0")/.."
here="$(pwd)"

version="${ESPEAK_VERSION:-1.52.0}"
work="${ESPEAK_WORK:-.cache/espeak-ng}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ndk="$sdk/ndk/27.0.12077973"

# Which languages ship in the apk. The core is a megabyte and a half whatever happens; a
# dictionary is tens of kilobytes each, and all of them together are nineteen megabytes, which
# is more than the dictionary pack the app already carries. These are the languages this
# product has packs and accents for, so they are the ones a reader is likely to be reading.
#
# Russian is not among them: its dictionary alone is eight megabytes, three times the rest put
# together, so it is a language to fetch rather than one to carry everywhere.
langs=(en es de fr pt it nl pl sv da nb fi cs tr el)

if [[ ! -d "$ndk" ]]; then
  echo "no NDK at $ndk; set ANDROID_HOME" >&2
  exit 1
fi

mkdir -p "$work"
if [[ ! -d "$work/espeak-ng-$version" ]]; then
  echo "== fetching espeak-ng $version"
  curl -sL "https://github.com/espeak-ng/espeak-ng/archive/refs/tags/$version.tar.gz" \
    -o "$work/espeak.tgz"
  tar xzf "$work/espeak.tgz" -C "$work"
fi
src="$work/espeak-ng-$version"

# The data, compiled by a build that can run here.
if [[ ! -f "$src/build-host/espeak-ng-data/phontab" ]]; then
  echo "== the data, built where it can be run"
  cmake -S "$src" -B "$src/build-host" -G Ninja \
    -DBUILD_SHARED_LIBS=ON -DCMAKE_BUILD_TYPE=Release >/dev/null
  cmake --build "$src/build-host" -j"$(nproc)" >/dev/null
fi

# The library, per ABI. Only the library target: everything after it in that build wants to
# run the phone's own binary on this machine, which is not something this machine can do.
for abi in arm64-v8a x86_64; do
  echo "== the library for $abi"
  cmake -S "$src" -B "$src/build-$abi" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 \
    -DBUILD_SHARED_LIBS=ON -DCMAKE_BUILD_TYPE=Release >/dev/null
  cmake --build "$src/build-$abi" -j"$(nproc)" --target espeak-ng >/dev/null
  install -Dm755 "$src/build-$abi/src/libespeak-ng/libespeak-ng.so" \
    "$here/android/app/src/main/jniLibs/$abi/libespeak-ng.so"
done

echo "== the data"
data="$here/android/app/src/main/assets/espeak"
rm -rf "$data"
mkdir -p "$data"
cp -r "$src/build-host/espeak-ng-data/phondata" \
      "$src/build-host/espeak-ng-data/phondata-manifest" \
      "$src/build-host/espeak-ng-data/phonindex" \
      "$src/build-host/espeak-ng-data/phontab" \
      "$src/build-host/espeak-ng-data/intonations" \
      "$src/build-host/espeak-ng-data/lang" \
      "$src/build-host/espeak-ng-data/voices" \
      "$data/"
for lang in "${langs[@]}"; do
  from="$src/build-host/espeak-ng-data/${lang}_dict"
  [[ -f "$from" ]] && cp "$from" "$data/"
done

echo
echo "the engine is in android/app/src/main/jniLibs, its data in $data"
du -sh "$data"
ls -la "$here/android/app/src/main/jniLibs"/*/libespeak-ng.so
