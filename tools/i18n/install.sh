#!/data/data/com.termux/files/usr/bin/bash
# Copy the canonical tables into the build tree. `assets/` is gitignored, so the
# APK gets them through this step (build-apk.sh does the same thing for the
# staging tree).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
for d in "$ROOT" "$HOME/spektralab-android"; do
    [ -d "$d" ] || continue
    mkdir -p "$d/app/src/main/assets/i18n"
    cp "$ROOT/tools/i18n/en.tsv" "$d/app/src/main/assets/i18n/en.tsv"
    cp "$ROOT/tools/i18n/phrases.tsv" "$d/app/src/main/assets/i18n/phrases.tsv"
    echo "i18n -> $d/app/src/main/assets/i18n"
done
