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
# Both JNI units, every time. Raw decoding has its own file, and a stale
# spektralab_raw_jni.o links silently: the app then calls into a native
# signature that no longer matches and dies at the first RAW open.
$CXX $INCS $DEFS -c app/src/main/cpp/spektralab_jni.cpp -o app/build/native/spektralab_jni.o
$CXX $INCS $DEFS -c app/src/main/cpp/spektralab_raw_jni.cpp -o app/build/native/spektralab_raw_jni.o
$CXX -shared -o app/build/native/libspektrafilm_engine.so app/build/native/*.o \
  -L"$SYS/usr/lib/aarch64-linux-android/24" -L"$PREFIX/lib" \
  -llog -landroid -ljnigraphics -lvulkan -lraw -lraw_r -ljpeg -lc++_shared -lc -lm -ldl \
  -Wl,-z,max-page-size=16384
ls -la app/build/native/libspektrafilm_engine.so
