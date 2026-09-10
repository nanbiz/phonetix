#!/usr/bin/env bash
# The translation engine for the phone, built native, per ABI.
#
# The browser runs the published WebAssembly build of this same engine. The phone had nothing,
# so a word no dictionary held came back with no meaning at all there - on a product whose
# point is telling a reader what a word means. DR-3 has always said Bergamot on both surfaces.
#
#   scripts/build-bergamot-android.sh
#
# What it produces, under .cache/bergamot/build-<abi>:
#   libbergamot-translator.a, libmarian.a, libssplit.a and the rest
# which the app's own native library links against.
#
# Three things this build needs that its own instructions do not say:
#
#  - CMAKE_POLICY_VERSION_MINIMUM, because several vendored trees still declare a minimum this
#    cmake removed support for. It has to be in the environment as well as on the command line:
#    the sentence splitter builds pcre2 as a project of its own and passes its own argument
#    list, so nothing given here reaches it.
#  - RUY as the matrix backend. Marian refuses to build without a BLAS, and RUY is the one that
#    has both an arm64 and an x86-64 path.
#  - a patch to faiss, whose SSE code is guarded on a macro every x86-64 compiler defines while
#    using intrinsics it never includes. It builds where a toolchain includes them for it, and
#    the NDK does not.
set -euo pipefail

cd "$(dirname "$0")/.."
here="$(pwd)"
work="${BERGAMOT_WORK:-.cache/bergamot}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ndk="$sdk/ndk/27.0.12077973"

if [[ ! -d "$ndk" ]]; then
  echo "no NDK at $ndk; set ANDROID_HOME" >&2
  exit 1
fi

if [[ ! -d "$work" ]]; then
  echo "== fetching bergamot-translator"
  git clone -q --recursive --depth 1 \
    https://github.com/browsermt/bergamot-translator.git "$work"
fi

faiss="$work/3rd_party/marian-dev/src/3rd_party/faiss/VectorTransform.cpp"
if [[ -f "$faiss" ]] && ! grep -q immintrin.h "$faiss"; then
  echo "== the intrinsics faiss uses without including"
  python3 - "$faiss" <<'PY'
import sys, pathlib
path = pathlib.Path(sys.argv[1])
text = path.read_text()
text = text.replace(
    "#include <faiss/VectorTransform.h>",
    "#include <faiss/VectorTransform.h>\n\n"
    "// The SSE path below is guarded on __SSE__, which every x86-64 compiler defines, and\n"
    "// uses the intrinsics without including them.\n"
    "#if defined(__SSE__) || defined(__x86_64__)\n#include <immintrin.h>\n#endif",
)
path.write_text(text)
PY
fi

export CMAKE_POLICY_VERSION_MINIMUM=3.5

for abi in arm64-v8a x86_64; do
  echo "== the engine for $abi"
  # RUY on both, which is the only matrix backend either of these has. Marian offers it by
  # default on ARM and expects MKL or OpenBLAS on x86, and Android has neither: without it an
  # x86-64 build compiles, opens a model, and aborts on the first multiplication with "Marian
  # must be compiled with a BLAS library". USE_RUY is what adds ruy to the build at all;
  # USE_RUY_SGEMM is what makes Marian call it.
  extra=(-DUSE_RUY=ON -DUSE_RUY_SGEMM=ON)
  if [[ "$abi" == x86_64 ]]; then
    # The x86-64 build compiles faiss's vector code, which wants the instruction sets named.
    extra+=(-DBUILD_ARCH=x86-64 -DCMAKE_CXX_FLAGS="-msse4.2 -mavx")
  else
    extra+=(-DBUILD_ARCH=armv8-a)
  fi
  cmake -S "$work" -B "$work/build-$abi" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-28 \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DSSPLIT_USE_INTERNAL_PCRE2=ON -DCOMPILE_TESTS=OFF \
    -DUSE_STATIC_LIBS=ON "${extra[@]}" >/dev/null
  # The library only. What comes after it in that build is a command-line program for this
  # machine, which is not something this machine can run for a phone.
  cmake --build "$work/build-$abi" -j"$(nproc)" --target bergamot-translator >/dev/null
  echo "   $(ls -la "$work/build-$abi/src/translator/libbergamot-translator.a" | awk '{print $5}') bytes"
done

echo
echo "the engine is in $here/$work/build-<abi>; the app's own library links against it"
