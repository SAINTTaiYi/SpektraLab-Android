#!/data/data/com.termux/files/usr/bin/bash
# Stage the app's native libraries, under names an APK is allowed to carry.
#
# `libjpeg.so.8` and `libz.so.1` are what upstream calls jpeg-turbo and zlib,
# and the package installer will not extract either one: NativeLibraryHelper
# only copies entries under lib/ whose file name ends in ".so". Shipped under
# those names they never reach nativeLibraryDir, and libraw -- whose DT_NEEDED
# still says "libjpeg.so.8" -- then fails with "library libjpeg.so.8 not found",
# which takes the engine with it. That is the E-9000 every device reported, and
# reinstalling could never fix it.
#
# They are copied out as libjpeg_8.so / libz_1.so. The new names are the same
# length as the old ones, so rewriting the DT_NEEDED and SONAME strings inside
# .dynstr is a byte-for-byte replacement: no offset moves, no section grows, and
# the result is still the vendor's library. The names avoid the ones the system
# already owns -- calling ours "libjpeg.so" shadows the platform's copy for any
# system library that asks for it, which fails with a symbol mismatch instead of
# a clean error.
set -e
cd "$HOME/SpektraLab-apk"
PREFIX=/data/data/com.termux/files/usr
NDK="${ANDROID_NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}"
CXXLIB="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
DST=app/build/apk/lib/arm64-v8a

rm -rf "$DST"
mkdir -p "$DST"
# libc++ must be the NDK's copy, not Termux's: the engine's objects were
# compiled against the NDK's headers, and Termux ships a different (1.3 MB vs
# 9.2 MB) build of the same soname. Swapping it is a silent ABI bet.
cp "$CXXLIB" "$DST"/
cp "$PREFIX/lib/liblcms2.so"      "$DST"/
cp "$PREFIX/lib/libraw.so"        "$DST"/
cp "$PREFIX/lib/libraw_r.so"      "$DST"/
cp "$PREFIX/lib/libjpeg.so.8"     "$DST"/libjpeg_8.so
cp "$PREFIX/lib/libz.so.1"        "$DST"/libz_1.so
cp app/build/native/libspektrafilm_engine.so "$DST"/

# The five vendor libraries must be the ones 1.2.7 shipped -- byte for byte.
# A different libraw here would be a different decoder in an emergency build.
python3 - "$DST" <<'PY'
import sys, pathlib

dst = pathlib.Path(sys.argv[1])
subs = {b'libjpeg.so.8': b'libjpeg_8.so',
        b'libz.so.1':    b'libz_1.so'}
for old, new in subs.items():
    assert len(old) == len(new), (old, len(old), len(new))

# Every object that names them: the two libraries' own SONAME, and the
# DT_NEEDED of everything that links against them.
for name in ('libjpeg_8.so', 'libz_1.so',
             'libraw.so', 'libraw_r.so', 'libspektrafilm_engine.so'):
    p = dst / name
    d = p.read_bytes()
    size = len(d)
    hits = 0
    for old, new in subs.items():
        hits += d.count(old)
        d = d.replace(old, new)
    assert len(d) == size, 'a rewrite changed the file size'
    p.write_bytes(d)
    print('  %-26s %d rewrite(s)' % (name, hits))
PY

echo '--- staged ---'
ls -l "$DST"
