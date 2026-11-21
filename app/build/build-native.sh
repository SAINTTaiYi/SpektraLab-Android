#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$HOME/SpektraLab-apk"
NDK="$HOME/Android/Sdk/ndk/28.2.13676358"
SYS="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
PREFIX=/data/data/com.termux/files/usr
ENG="$HOME/SpektraLab-android-adreno/engine"
mkdir -p app/build/native
CXX="clang++ --target=aarch64-linux-android24 -std=c++20 -O2 -fPIC -nostdinc++"
INCS="-I$SYS/usr/include/c++/v1 -I$SYS/usr/include -I$ENG/include -I$ENG/src -I$ENG/src/core -I$ENG/src/gpu -I$ENG/src/io -I$ENG/src/pipeline -I$ENG/src/desktop -I$ENG/third_party/volk -I$ENG/third_party -I$PREFIX/include"
DEFS="-D__ANDROID__ -DANDROID -DSPK_VULKAN_BACKEND=1 -DSPK_ANDROID_SYSTEM_VULKAN=1"

# **A header edit invalidates every engine object, not just the JNI.**
# This script only compiles the two JNI units and relinks; the engine objects
# beside them keep whatever layout they were built with. Mixing the two halves
# of one .so is silent and fatal: the shared structs get different offsets, and
# a std::vector read back at the wrong offset holds pixel data instead of a
# pointer. The destructor then frees that, and the app aborts right after the
# import that triggered it. build-engine.sh records a stamp of every header; if
# it no longer matches, refuse to link a .so whose halves disagree.
HEADER_STAMP=app/build/native/.headers.sha
HEADERS_NOW=$(find "$ENG/include" "$ENG/src" \( -name '*.hpp' -o -name '*.h' \) -print 2>/dev/null | sort | xargs cat 2>/dev/null | sha256sum | cut -d' ' -f1)
if [ -f "$HEADER_STAMP" ] && [ "$(cat "$HEADER_STAMP")" != "$HEADERS_NOW" ]; then
  echo "build-native: engine headers changed since the last build-engine.sh run." >&2
  echo "              The engine objects and this JNI would disagree about every shared" >&2
  echo "              struct. Run: bash app/build/build-engine.sh   (then retry)" >&2
  exit 1
fi
# Both JNI units, every time. Raw decoding has its own file, and a stale
# spektralab_raw_jni.o links silently: the app then calls into a native
# signature that no longer matches and dies at the first RAW open.
$CXX $INCS $DEFS -c app/src/main/cpp/spektralab_jni.cpp -o app/build/native/spektralab_jni.o
$CXX $INCS $DEFS -c app/src/main/cpp/spektralab_raw_jni.cpp -o app/build/native/spektralab_raw_jni.o
# Lightroom-style glow: a pure-CPU post pass, no engine dependency.
$CXX $INCS $DEFS -c app/src/main/cpp/lrglow.cpp -o app/build/native/lrglow.o
# Ultra HDR: the gain curve, the map, and the container around them. CPU only;
# the curve comes from the engine's own medium ramp, so no shader is involved.
$CXX $INCS $DEFS -c app/src/main/cpp/ultrahdr.cpp -o app/build/native/ultrahdr.o
$CXX -shared -o app/build/native/libspektrafilm_engine.so app/build/native/*.o \
  -L"$SYS/usr/lib/aarch64-linux-android/24" -L"$PREFIX/lib" \
  -llog -landroid -ljnigraphics -lvulkan -lraw -lraw_r -ljpeg -lz -lc++_shared -lc -lm -ldl \
  -Wl,-z,max-page-size=16384
ls -la app/build/native/libspektrafilm_engine.so
