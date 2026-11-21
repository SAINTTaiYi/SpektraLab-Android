#!/data/data/com.termux/files/usr/bin/bash
# The first-engine regression: one render, one hash.
#
# `fx_test` runs the whole first engine over one RAW file and prints an FNV hash
# of the 16-bit result beside its three channel means. Any change to the engine
# that moves a pixel moves the hash, so "the .so did not change" and "the picture
# did not change" are the same statement, and a rebuild that was supposed to be
# cosmetic can be checked in one command.
#
# **The baseline belongs to one file.** These numbers are what
# `nef/z50_lossless.nef` (Nikon Z50, lossless NEF, 26 MB -- not redistributed)
# produced; with any other photograph the tool still works but the hash is not a
# baseline, it is just a number.
#
#   f3771e9d...  the packaged .so of the release being tested
#   mean 77.03 / 90.02 / 86.64      -> hash 913ed9890ef57520
#
# Usage:
#   SPK_RAW=/path/to/z50_lossless.nef bash tools/regress.sh
#   SPK_RAW=... SPK_EXPECT=... bash tools/regress.sh     # a different frame
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENG="${SPK_ENGINE:-$HOME/SpektraLab-android-adreno/engine}"
LIB="${SPK_LIB:-$ROOT/app/build/apk/lib/arm64-v8a}"
RES="${SPK_RES:-$ROOT/app/build/apk/assets/resources}"
RAW="${SPK_RAW:-$HOME/spk-debug/nef/z50_lossless.nef}"
EXPECT="${SPK_EXPECT:-913ed9890ef57520}"
NDK="${ANDROID_NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}"
SYS="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
OUT="${TMPDIR:-$HOME}/fx_test_regen"

for need in "$ENG/include" "$LIB/libspektrafilm_engine.so" "$RES/profiles" "$RAW"; do
  [ -e "$need" ] || { echo "missing $need" >&2; exit 1; }
done

clang++ --target=aarch64-linux-android29 -std=c++17 -O2 -fPIC -ffp-contract=off -nostdinc++ \
  -I"$SYS/usr/include/c++/v1" -I"$SYS/usr/include" \
  -I"$ENG/include" -I"$ENG/src" -I"$ENG/src/core" -I"$ENG/src/gpu" -I"$ENG/src/io" \
  -I"$ENG/src/pipeline" -I"$ENG/src/desktop" -I"$ENG/third_party/volk" -I"$ENG/third_party" \
  "$ROOT/tools/fx_test.cpp" -o "$OUT" \
  -L"$LIB" -l:libspektrafilm_engine.so \
  -L"$SYS/usr/lib/aarch64-linux-android/29" -l:libc++_shared.so -lc -lm -ldl

LINE=$(LD_LIBRARY_PATH="$LIB:/system/lib64:$PREFIX/lib" stdbuf -o0 -e0 \
       "$OUT" "$RES" "$RAW" - 2>&1 | grep -E '^[-] +st=' | tail -1)
echo "$LINE"
GOT=$(echo "$LINE" | awk '{print $NF}')
if [ "$GOT" != "$EXPECT" ]; then
  echo "REGRESSION: expected $EXPECT, got $GOT" >&2
  exit 1
fi
echo "ok  $GOT"
