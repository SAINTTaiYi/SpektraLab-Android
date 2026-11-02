#!/data/data/com.termux/files/usr/bin/bash
# Compile every engine translation unit for Android/arm64 with one flag set.
# Kept separate from build-native.sh (which only does the JNI + link) so a
# stale engine .o can never sneak into the APK again.
set -e
cd "$HOME/SpektraLab-apk"
NDK="$HOME/Android/Sdk/ndk/28.2.13676358"
SYS="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
PREFIX=/data/data/com.termux/files/usr
ENG="$HOME/SpektraLab-android-adreno/engine"
CXX="clang++ --target=aarch64-linux-android24 -std=c++20 -O2 -fno-fast-math -ffp-contract=off -fPIC -nostdinc++"
INCS="-I$SYS/usr/include/c++/v1 -I$SYS/usr/include -I$ENG/include -I$ENG/src -I$ENG/src/core -I$ENG/src/gpu -I$ENG/src/io -I$ENG/src/pipeline -I$ENG/src/desktop -I$ENG/third_party/volk -I$ENG/third_party -I$HOME/SpektraLab-android-adreno/build/adreno/engine/generated -I$PREFIX/include"
DEFS="-D__ANDROID__ -DANDROID -DSPK_VULKAN_BACKEND=1 -DSPK_ANDROID_SYSTEM_VULKAN=1"
mkdir -p app/build/native
# `build-engine.sh engine raw_decoder` rebuilds just those translation units;
# no argument rebuilds everything. One place for the flags, either way.
ONLY="$*"
# **A header change invalidates every object.**
# Adding one virtual to `Gpu` shifts the vtable for every translation unit that
# includes `gpu.hpp`. Rebuilding only the two files that were edited then links
# fresh code against stale callers: on 2026-11-02 that made every `spk_open`
# fail with "Vulkan upload size must be 4-byte aligned" while both rebuilt
# translation units were correct on their own. Incremental builds are worth
# having; silent ABI skew is not, so the headers get a stamp.
mkdir -p app/build/native
HEADER_STAMP=app/build/native/.headers.sha
HEADERS_NOW=$(find "$ENG/include" "$ENG/src" \( -name '*.hpp' -o -name '*.h' \) -print 2>/dev/null | sort | xargs cat 2>/dev/null | sha256sum | cut -d' ' -f1)
if [ -n "$ONLY" ] && [ "$(cat "$HEADER_STAMP" 2>/dev/null)" != "$HEADERS_NOW" ]; then
  echo "headers changed since the last build -- rebuilding every translation unit"
  ONLY=""
fi
echo "$HEADERS_NOW" > "$HEADER_STAMP"
for src in \
  core/blob core/cam16 core/colour core/curves core/digital_intermediate core/hanatos \
  core/json core/latitude_fit core/numeric core/params core/print_lut core/printing \
  core/profile core/setup_cache core/spectral \
  io/image_writer io/raw_decoder io/raw_headroom \
  pipeline/blur pipeline/engine pipeline/imprint_raster pipeline/overscan pipeline/pipeline \
  pipeline/scene_latitude pipeline/view_process pipeline/windows_unported \
  gpu/vulkan_gpu ; do
  name=$(basename "$src")
  if [ -n "$ONLY" ] && ! echo " $ONLY " | grep -q " $name "; then continue; fi
  $CXX $INCS $DEFS -c "$ENG/src/$src.cpp" -o "app/build/native/$name.o"
  echo "  $name"
done
if [ -z "$ONLY" ] || echo " $ONLY " | grep -q " volk "; then
  $CXX $INCS $DEFS -c "$ENG/third_party/volk/volk.c" -o app/build/native/volk.o
fi
echo "engine objects rebuilt"
