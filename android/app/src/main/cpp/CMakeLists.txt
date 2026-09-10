# The app's own native piece: a way into the translation engine, and nothing else.
#
# The engine itself is built by scripts/build-bergamot-android.sh, per ABI, out of a checkout
# under .cache. It is not committed and not rebuilt here: it takes twenty minutes and does not
# change between releases, so this links what that produced.
cmake_minimum_required(VERSION 3.22)
project(phonetix_translate CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(BERGAMOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../.cache/bergamot")
set(BUILD "${BERGAMOT}/build-${ANDROID_ABI}")

add_library(phonetix_translate SHARED translate.cpp)

# The headers and the defines the engine's own translation unit is compiled with, taken from
# its build rather than guessed. Its headers carry SIMD code that only compiles when the
# architecture is named the way its build names it, so a shim that includes them and not these
# fails inside somebody else's header for reasons of its own build.
target_include_directories(phonetix_translate PRIVATE
  "${BERGAMOT}"
  "${BERGAMOT}/src"
  "${BERGAMOT}/3rd_party/marian-dev/src"
  "${BERGAMOT}/3rd_party/marian-dev/src/3rd_party"
  "${BERGAMOT}/3rd_party/marian-dev/src/3rd_party/fbgemm/include"
  "${BERGAMOT}/3rd_party/marian-dev/src/3rd_party/intgemm"
  "${BERGAMOT}/3rd_party/marian-dev/src/3rd_party/ruy"
  "${BERGAMOT}/3rd_party/marian-dev/src/3rd_party/ruy/third_party/cpuinfo/include"
  "${BERGAMOT}/3rd_party/marian-dev/src/3rd_party/sentencepiece"
  "${BERGAMOT}/3rd_party/marian-dev/src/3rd_party/SQLiteCpp/include"
  "${BERGAMOT}/3rd_party/ssplit-cpp/src"
  "${BERGAMOT}/3rd_party/ssplit-cpp/src/ssplit"
  "${BUILD}"
  "${BUILD}/include"
  "${BUILD}/3rd_party/marian-dev/src/3rd_party"
  "${BUILD}/3rd_party/marian-dev/src/3rd_party/intgemm"
)

target_compile_definitions(phonetix_translate PRIVATE
  COMPILE_CPU=1
  USE_RUY_SGEMM=1
  USE_SENTENCEPIECE
  USE_PTHREADS
  _USE_INTERNAL_STRING_VIEW
  NDEBUG
)

# Per ABI, exactly as the engine compiles its own sources. On arm64 it defines SSE and FMA as
# well as ARM: the vendored SIMD header offers the SSE names and implements them in NEON, so
# a build that defines only ARM gets a header that declares nothing.
if(ANDROID_ABI STREQUAL "arm64-v8a")
  target_compile_definitions(phonetix_translate PRIVATE ARM FMA SSE CPUINFO_SUPPORTED_PLATFORM=1)
  target_compile_options(phonetix_translate PRIVATE -march=armv8-a)
elseif(ANDROID_ABI STREQUAL "x86_64")
  target_compile_definitions(phonetix_translate PRIVATE USE_INTGEMM=1)
  target_compile_options(phonetix_translate PRIVATE -march=x86-64 -msse4.1)
endif()

target_compile_options(phonetix_translate PRIVATE
  -flax-vector-conversions
  -fno-strict-aliasing
  # The vendored SIMD header names the rounding modes without including the header that
  # declares them. It compiles where something else in the translation unit happened to pull
  # that in first, which is what the engine's own sources do.
  -include cfenv
)

# What to link, gathered from the engine's own build rather than listed here.
#
# The set differs by ABI - the arm64 build uses ruy for its matrix multiplication and the
# x86-64 build uses intgemm - so a list written down here is right for one of them and wrong
# for the other, silently. A group is used rather than an order because a static link is
# order-sensitive and nothing here knows the order.
#
# The translator itself is whole: it registers its layers and operators through static
# initialisers, and a linker that keeps only what is referenced drops every one of them, which
# comes out as a model that opens and translates nothing.
file(GLOB_RECURSE ENGINE "${BUILD}/*.a")
list(REMOVE_ITEM ENGINE "${BUILD}/src/translator/libbergamot-translator.a")

target_link_libraries(phonetix_translate
  -Wl,--whole-archive
  "${BUILD}/src/translator/libbergamot-translator.a"
  -Wl,--no-whole-archive
  -Wl,--start-group
  ${ENGINE}
  -Wl,--end-group
  log
  android
)
