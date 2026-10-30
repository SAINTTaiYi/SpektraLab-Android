#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$HOME/SpektraLab-apk"
SDK="$HOME/Android/Sdk"
STAGE="$ROOT/app/build/apk"
OUT="$ROOT/app/build/SpektraLab-debug.apk"
TMP="$ROOT/app/build/SpektraLab-complete.apk"
cp "$ROOT/app/src/main/AndroidManifest.xml" "$STAGE/AndroidManifest.xml"
cd "$STAGE"
aapt package -f -m -M AndroidManifest.xml -S res -A assets -I "$SDK/platforms/android-36/android.jar" -F "$OUT" >/dev/null
python3 - <<'PY'
from pathlib import Path
import zipfile, os
root=Path.home()/'SpektraLab-apk'
src=root/'app/build/SpektraLab-debug.apk'
out=root/'app/build/SpektraLab-complete.apk'
lib=root/'app/build/apk/lib/arm64-v8a'
with zipfile.ZipFile(src) as a, zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
    for i in a.infolist():
        if i.filename=='classes.dex': continue
        z.writestr(i,a.read(i.filename))
    z.write(root/'app/build/dex/classes.dex','classes.dex')
    for f in sorted(lib.iterdir()):
        if f.is_file():
            print('pack',f.name,f.stat().st_size)
            z.write(f,'lib/arm64-v8a/'+f.name)
PY
# v1 as well as v2/v3: apksigner skips JAR signing at minSdk 24 by default,
# and a few OEM installers still refuse a v2-only package.
apksigner sign --ks "$ROOT/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --v1-signing-enabled true "$TMP"
cp "$TMP" /sdcard/Download/SpektraLab-dev.apk
python3 - <<'PY'
import zipfile
with zipfile.ZipFile('/sdcard/Download/SpektraLab-dev.apk') as z:
    print('APK native entries:')
    for n in z.namelist():
        if n.endswith('.so') or n=='classes.dex':
            print(n,z.getinfo(n).file_size)
PY
