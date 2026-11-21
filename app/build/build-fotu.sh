#!/data/data/com.termux/files/usr/bin/bash
# Stage the second engine's libraries and build its JNI.
#
# Two libraries come from the C++ port (they are the same bytes the validation
# shell shipped): libfotufilmhost.so carries the chain, libfotufilmcodecs.so the
# decoders. The third, libfotufilm_jni.so, is this tree's own bridge -- it is a
# separate .so on purpose, so a fault in one engine's JNI cannot take the other
# engine's symbols with it.
#
# Stage order is the release order: build-fotu -> build-libs -> build-dex ->
# build-apk. build-libs copies from app/build/native/fotufilm/.
set -euo pipefail
cd "$HOME/SpektraLab-apk"
NDK="${ANDROID_NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}"
SYS="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
SRC="${FOTU_LIBS:-$HOME/spk-debug/fotu-apk/lib/arm64-v8a}"
OUT=app/build/native
mkdir -p "$OUT/fotufilm"
# **Where libfotufilmhost.so comes from.**
#
# Its sources are in this repository (`app/src/main/cpp/fotufilm/`), but they
# only compile against upstream fotufilm's Halide AOT kernels and codecs
# library -- third-party artefacts, not redistributed here. With `FOTU_ART`
# pointing at them, `FOTU_FROM_SOURCE=1` compiles the host from source; that is
# the honest build and the one a fork should use. Without them the release
# stages the binary those same sources produced, so a machine without the SDK
# can still reproduce the shipped bytes.
if [ "${FOTU_FROM_SOURCE:-0}" = "1" ]; then
  echo "libfotufilmhost.so: compiling app/src/main/cpp/fotufilm/ (FOTU_ART=${FOTU_ART:-$HOME/spk-debug/fotu/art})"
  bash app/build/build-fotu-from-source.sh
  cp "$SRC/libfotufilmcodecs.so" "$OUT/fotufilm/"
else
  echo "libfotufilmhost.so: staged from $SRC (FOTU_FROM_SOURCE=1 compiles app/src/main/cpp/fotufilm/)"
  cp "$SRC/libfotufilmhost.so" "$SRC/libfotufilmcodecs.so" "$OUT/fotufilm/"
fi

# The port was linked on a Termux host, so its RUNPATH points at
# /data/data/com.termux/files/usr/lib. On a phone that path belongs to a
# different uid (and to an app that may not exist at all); leaving it in makes
# the loader try a directory it must not read. Blank the string in place: the
# replacement is the same length, so no offset moves.
python3 app/build/clear-runpath.py \
  "$OUT/fotufilm/libfotufilmhost.so" "$OUT/fotufilm/libfotufilmcodecs.so"

# **Termux's own `strip`, not the NDK's.** The NDK ships x86_64 hosts that cannot run here,
# and `|| true` swallowed the failure: the library went out with its symbol table, 57 KB a copy.
strip --strip-all "$OUT/fotufilm/libfotufilmhost.so" "$OUT/fotufilm/libfotufilmcodecs.so" 2>/dev/null || true

clang++ --target=aarch64-linux-android24 -std=c++17 -O2 -fPIC -shared \
  -fvisibility=hidden -ffp-contract=off \
  -nostdinc++ -I"$SYS/usr/include/c++/v1" -I"$SYS/usr/include" \
  app/src/main/cpp/fotufilm_jni.cpp \
  -L"$OUT/fotufilm" -l:libfotufilmhost.so -l:libfotufilmcodecs.so \
  -L"$SYS/usr/lib/aarch64-linux-android/24" -l:libc++_shared.so \
  -Wl,--allow-shlib-undefined -Wl,-soname,libfotufilm_jni.so \
  -lc -lm -ldl -llog -o "$OUT/libfotufilm_jni.so"
strip --strip-all "$OUT/libfotufilm_jni.so" 2>/dev/null || true

# **The bridge needs it too, and it is the one this script compiles.**
# The two staged libraries get theirs blanked above because the port was linked
# on a Termux host; this one is linked here, on the same host, and inherits the
# same runpath. It was blanked by hand once and came back the moment the
# library was rebuilt -- on a phone `/data/data/com.termux/files/usr/lib`
# belongs to another uid, so the loader spends the first lookup on a directory
# it may not read.
python3 app/build/clear-runpath.py "$OUT/libfotufilm_jni.so"

# And a check, because "blanked by hand once and came back" is the whole story.
# It looks at the dynamic section, not at the file: the build leaves its own
# source directory in .rodata (a `__FILE__` or two), which is harmless and would
# make a whole-file grep fail on a good library.
for lib in "$OUT/fotufilm/libfotufilmhost.so" "$OUT/fotufilm/libfotufilmcodecs.so" \
           "$OUT/libfotufilm_jni.so"; do
  if readelf -d "$lib" 2>/dev/null | grep -E 'RUNPATH|RPATH' | grep -q 'com.termux'; then
    echo "!! $lib still carries a Termux runpath" >&2
    exit 1
  fi
done

echo '--- fotufilm staged ---'
ls -l "$OUT/fotufilm" "$OUT/libfotufilm_jni.so"
readelf -d "$OUT/fotufilm/libfotufilmhost.so" | grep -E 'RUNPATH|NEEDED' || true
nm -D --defined-only "$OUT/libfotufilm_jni.so" | grep Java_app_spektralab_engine_FotuFilm || true
