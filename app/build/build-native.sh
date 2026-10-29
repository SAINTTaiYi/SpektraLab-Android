#!/usr/bin/env bash
# Compile the JNI layer and link the engine into one .so.
set -e
PROJECT="$(cd "$(dirname "$0")/../.." && pwd)"
ENG="${ENGINE_ROOT:-$HOME/SpektraLab-android-adreno}/engine"
NDK="${ANDROID_NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}"
SYS="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
GEN="$ENG/../build/adreno/engine/generated"
CXX="clang++ --target=aarch64-linux-android24 -std=c++20 -O2 -fPIC -nostdinc++"
INCS="-I$SYS/usr/include/c++/v1 -I$SYS/usr/include -I$ENG/include -I$ENG/src -I$ENG/src/core -I$ENG/src/gpu -I$ENG/src/io -I$ENG/src/pipeline -I$ENG/src/desktop -I$ENG/third_party/volk -I$ENG/third_party -I$GEN -I$PREFIX/include"
DEFS="-D__ANDROID__ -DANDROID -DSPK_VULKAN_BACKEND=1 -DSPK_ANDROID_SYSTEM_VULKAN=1"
mkdir -p "$PROJECT/app/build/native"
for f in "$PROJECT"/app/src/main/cpp/*.cpp; do
  $CXX $INCS $DEFS -c "$f" -o "$PROJECT/app/build/native/$(basename "$f" .cpp).o"
done
$CXX -shared -o "$PROJECT/app/build/native/libspektrafilm_engine.so" "$PROJECT"/app/build/native/*.o \
  -L"$SYS/usr/lib/aarch64-linux-android/24" -L"$PREFIX/lib" \
  -llog -landroid -ljnigraphics -lvulkan -lraw -lraw_r -ljpeg -lc++_shared -lc -lm -ldl \
  -Wl,-z,max-page-size=16384
ls -la "$PROJECT/app/build/native/libspektrafilm_engine.so"
