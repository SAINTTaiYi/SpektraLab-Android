#!/usr/bin/env python3
"""Every DT_NEEDED of every shipped .so must resolve inside the APK.

`libjpeg.so.8` and `libz.so.1` cannot travel under their own names -- the
package installer only extracts entries under lib/ that end in `.so`, so they
are shipped as `libjpeg_8.so` / `libz_1.so` and every DT_NEEDED that names them
is rewritten in place (see build-libs.sh). Skipping that rewrite -- which is
exactly what happens when a freshly built libspektrafilm_engine.so is copied
into app/build/apk/lib by hand instead of through build-libs.sh -- ships a
library that cannot load, and the app dies with E-9000 on the first import.

This runs after staging, so the mistake is a build failure instead of a
release. Exit code 1 means the APK would not have loaded.
"""
import pathlib
import subprocess
import sys

# What Android itself provides. Anything else has to be in lib/.
PLATFORM = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
    "libjnigraphics.so", "libvulkan.so", "libEGL.so", "libGLESv3.so",
    "libGLESv2.so", "libz.so", "libstdc++.so",
}


def needed(path):
    out = subprocess.run(["readelf", "-d", str(path)],
                         capture_output=True, text=True).stdout
    return [line.split("[")[1].split("]")[0]
            for line in out.splitlines() if "(NEEDED)" in line]


def main(directory):
    libs = {p.name: p for p in sorted(pathlib.Path(directory).glob("*.so"))}
    if not libs:
        print(f"check-libs: no .so under {directory}", file=sys.stderr)
        return 1
    broken = {}
    for name, path in libs.items():
        missing = [n for n in needed(path)
                   if n not in libs and n not in PLATFORM]
        if missing:
            broken[name] = missing
    if broken:
        print("check-libs: these libraries cannot load as packaged:",
              file=sys.stderr)
        for name, missing in broken.items():
            print(f"  {name} needs {', '.join(missing)}", file=sys.stderr)
        print("  -> stage through build-libs.sh (it renames and rewrites "
              "libjpeg.so.8 / libz.so.1)", file=sys.stderr)
        return 1
    print(f"check-libs: {len(libs)} libraries, every DT_NEEDED resolves")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1
                  else "app/build/apk/lib/arm64-v8a"))
