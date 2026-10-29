#!/usr/bin/env bash
# Compile every engine translation unit for Android/arm64 with one flag set.
#
#   ENGINE_ROOT  upstream SpektraLab checkout (patched)   default: $HOME/SpektraLab-android-adreno
#   ANDROID_NDK  NDK root                                 default: $HOME/Android/Sdk/ndk/28.2.13676358
#   PREFIX       Termux prefix (for libraw/libjpeg)       default: /data/data/com.termux/files/usr
set -e
PROJECT="$(cd "$(dirname "$0")/../.." && pwd)"
ENG="${ENGINE_ROOT:-$HOME/SpektraLab-android-adreno}/engine"
NDK="${ANDROID_NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}"
SYS="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
GEN="$ENG/../build/adreno/engine/generated"
CXX="clang++ --target=aarch64-linux-android24 -std=c++20 -O2 -fno-fast-math -ffp-contract=off -fPIC -nostdinc++"
INCS="-I$SYS/usr/include/c++/v1 -I$SYS/usr/include -I$ENG/include -I$ENG/src -I$ENG/src/core -I$ENG/src/gpu -I$ENG/src/io -I$ENG/src/pipeline -I$ENG/src/desktop -I$ENG/third_party/volk -I$ENG/third_party -I$GEN -I$PREFIX/include"
DEFS="-D__ANDROID__ -DANDROID -DSPK_VULKAN_BACKEND=1 -DSPK_ANDROID_SYSTEM_VULKAN=1"
mkdir -p "$PROJECT/app/build/native"
for src in \
  core/blob core/cam16 core/colour core/curves core/digital_intermediate core/hanatos \
  core/json core/latitude_fit core/numeric core/params core/print_lut core/printing \
  core/profile core/setup_cache core/spectral \
  io/image_writer io/raw_decoder io/raw_headroom \
  pipeline/blur pipeline/engine pipeline/imprint_raster pipeline/overscan pipeline/pipeline \
  pipeline/scene_latitude pipeline/view_process pipeline/windows_unported \
  gpu/vulkan_gpu ; do
  name=$(basename "$src")
  $CXX $INCS $DEFS -c "$ENG/src/$src.cpp" -o "$PROJECT/app/build/native/$name.o"
  echo "  $name"
done
clang -O2 -fno-fast-math -DSPK_VULKAN_BACKEND=1 -DSPK_ANDROID_SYSTEM_VULKAN=1 \
  -c "$ENG/third_party/volk/volk.c" -o "$PROJECT/app/build/native/volk.o"
echo "engine objects rebuilt"
