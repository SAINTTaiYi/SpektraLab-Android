#!/data/data/com.termux/files/usr/bin/bash
# LibRaw, with one change: HE/HE* is recognised by the bits in the file, not
# by a whitelist of four camera names.
#
# LibRaw 0.22.2 decides what a Nikon NEF holds by asking whether the model is
# one of "NIKON Z 9", "NIKON Z 8", "NIKON Z f", "NIKON Z6_3". Every body
# released after that list was written -- Z50II included -- falls through to
# the lossless decoder, which then reads a JPEG-XS bitstream as Huffman codes.
# It does not always throw: on this bench it returned "No error" with
# error_count = 34,353,576, which is the app's "decoder reported corrupt data".
#
# The file itself says what it is -- JPEG-XS starts FF 10 FF 50 -- so the test
# reads four bytes at the strip offset instead of comparing names. Same build
# decodes lossless NEFs to the identical samples (hash 8ce206ae9c0e676e on the
# Z 50 bench file before and after).
set -e
TREE="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$TREE"
PREFIX=/data/data/com.termux/files/usr
ROOT=app/build/libraw
SRC="$ROOT/LibRaw-0.22.2"
TARBALL="$ROOT/LibRaw-0.22.2.tar.gz"
OUT=app/build/native
mkdir -p "$ROOT" "$OUT"

if [ ! -f "$TARBALL" ]; then
  echo "  fetching $TARBALL"
  curl -fsSL -o "$TARBALL" https://www.libraw.org/data/LibRaw-0.22.2.tar.gz
fi
if [ ! -d "$SRC" ]; then
  tar xzf "$TARBALL" -C "$ROOT"
fi
if ! grep -q "JPEG-XS bitstream" "$SRC/src/metadata/tiff.cpp"; then
  patch -p1 -d "$ROOT" < app/build/libraw-he-sniff.patch
fi

# **Every body upstream knows and this tarball does not.** `adobe_coeff` is a
# prefix table: a body with no row keeps `cam_xyz` at the sRGB->XYZ default, so
# its colour is never corrected at all. The Nikon Z 50II is what surfaced it --
# it reports `Z50_2`, with no space, and misses the `"Z 50"` row. The rows come
# from LibRaw master and are appended at the end of the table, so every existing
# lookup is unchanged. Idempotent: safe on every build.
python3 app/build/libraw-new-cameras.py "$SRC/src/tables/colordata.cpp"

# *_ph.cpp are placeholder translation units; they duplicate symbols that the
# real files define.
build_one() {   # $1 = extra flags, $2 = output, $3 = object dir
  local extra="$1" out="$TREE/$2" obj="$TREE/$3"
  rm -rf "$obj"; mkdir -p "$obj"
  ( cd "$SRC" && find src -name '*.cpp' | grep -v '_ph\.cpp$' | sort | \
      xargs -P 8 -I{} sh -c 'o="'"$obj"'/$(echo "$1" | tr "/" "_").o"; exec clang++ -std=c++17 -O2 -fPIC -I. -DUSE_ZLIB -DUSE_JPEG -DUSE_JPEG8 -DUSE_LCMS2 '"$extra"' -w -c "$1" -o "$o"' _ {} )
  clang++ -shared -o "$out" "$obj"/*.o -L"$PREFIX/lib" -ljpeg -lz -llcms2 -Wl,-soname,libraw.so
}

build_one -DLIBRAW_NOTHREADS "$OUT/libraw.so"   "$ROOT/obj-nothreads"
build_one ""                 "$OUT/libraw_r.so" "$ROOT/obj-threads"
ls -l "$OUT/libraw.so" "$OUT/libraw_r.so"
