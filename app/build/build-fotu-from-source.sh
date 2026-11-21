#!/data/data/com.termux/files/usr/bin/bash
# libfotufilmhost.so, built from the sources in THIS repository.
#
# The second engine is two halves: upstream fotufilm (the Halide AOT kernels and
# the codecs library, third-party and not redistributed here) and this port --
# the host, the rebate compositor, the print frame and the calibration headers.
# The port's sources live beside the JNI at app/src/main/cpp/fotufilm/; this
# script is what compiles them. Before 1.6.16w they lived only on the machine
# that built the release, which made the published binary impossible to rebuild.
#
# FOTU_ART points at the upstream artefacts:
#
#   $FOTU_ART/include/            fotufilm_codecs.h, fotufilm.h
#   $FOTU_ART/engine/include/     FotofilmConfigLayout.h, FotofilmHalide.h
#   $FOTU_ART/engine/             FotofilmHalideAndroid.cpp + engine headers
#   $FOTU_ART/kernels/*.a         the AOT kernel archives
#   $FOTU_ART/                    libfotufilmcodecs.so
#   $FOTU_ART/jniLibs/arm64-v8a/  libc++_shared.so
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE/../.."
ROOT=app/src/main/cpp/fotufilm
ART="${FOTU_ART:-$HOME/spk-debug/fotu/art}"
NDK="${ANDROID_NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}"
SYS="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
OUT=app/build/native/fotufilm
mkdir -p "$OUT"

for need in "$ROOT/host.cpp" "$ART/engine/include/FotofilmHalide.h" "$ART/engine/FotofilmHalideAndroid.cpp"; do
  [ -e "$need" ] || { echo "missing $need" >&2
    echo "  set FOTU_ART to the fotufilm artefact directory" >&2; exit 1; }
done

clang++ --target=aarch64-linux-android29 -std=c++17 -O2 -fPIC -ffp-contract=off -shared \
  -nostdinc++ -I"$SYS/usr/include/c++/v1" -I"$SYS/usr/include" \
  -I"$ART/include" -I"$ART/engine/include" -I"$ART/kernels" \
  -I"$ROOT" -I"$ROOT/rebate" -I"$ROOT/rebate/third_party/stb" \
  -DFOTUFILM_HALIDE_ANDROID_AOT=1 -DFH_NO_MAIN=1 \
  "$ROOT/host.cpp" "$ROOT/print_frame.cpp" \
  "$ROOT/rebate/rebate.cpp" "$ROOT/rebate/rebate_composite.cpp" "$ROOT/rebate/imprint_raster.cpp" \
  "$ART/engine/FotofilmHalideAndroid.cpp" "$ART"/kernels/*.a \
  -L"$ART" -l:libfotufilmcodecs.so \
  -L"$ART/jniLibs/arm64-v8a" -l:libc++_shared.so \
  -L"$SYS/usr/lib/aarch64-linux-android/29" -lc -lm -ldl -llog -lz \
  -o "$OUT/libfotufilmhost.so"
ls -la "$OUT/libfotufilmhost.so"
