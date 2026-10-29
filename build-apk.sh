#!/usr/bin/env bash
# aapt + d8 output + apksigner. Produces SpektraLab-debug.apk.
#
#   ANDROID_SDK   default: $HOME/Android/Sdk
#   KEYSTORE      default: ./debug.keystore (create with keytool, alias androiddebugkey)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK:-$HOME/Android/Sdk}"
STAGE="$ROOT/app/build/apk"
OUT="$ROOT/app/build/SpektraLab-debug.apk"
TMP="$ROOT/app/build/SpektraLab-complete.apk"
KS="${KEYSTORE:-$ROOT/debug.keystore}"

cp "$ROOT/app/src/main/AndroidManifest.xml" "$STAGE/AndroidManifest.xml"
cd "$STAGE"
aapt package -f -m -M AndroidManifest.xml -S res -A assets \
  -I "$SDK/platforms/android-36/android.jar" -F "$OUT" >/dev/null

python3 - "$ROOT" <<'PY'
import sys, zipfile
from pathlib import Path
root = Path(sys.argv[1])
src = root / 'app/build/SpektraLab-debug.apk'
out = root / 'app/build/SpektraLab-complete.apk'
lib = root / 'app/build/apk/lib/arm64-v8a'
with zipfile.ZipFile(src) as a, zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for i in a.infolist():
        if i.filename == 'classes.dex':
            continue
        z.writestr(i, a.read(i.filename))
    z.write(root / 'app/build/dex/classes.dex', 'classes.dex')
    for f in sorted(lib.iterdir()):
        if f.is_file():
            print('pack', f.name, f.stat().st_size)
            z.write(f, 'lib/arm64-v8a/' + f.name)
PY

apksigner sign --ks "$KS" --ks-pass pass:android --ks-key-alias androiddebugkey "$TMP"
cp "$TMP" "$ROOT/app/build/SpektraLab-signed.apk"
echo "signed: $ROOT/app/build/SpektraLab-signed.apk"
