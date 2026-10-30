#!/usr/bin/env bash
# Publish a release: announcement + APK, in one command.
#
#   CF_TOKEN=xxxx ./scripts/announce.sh <version_code> <version_name> <apk_path|-> "note" "note" ...
#
# What it does, in order:
#   1. writes update.json (the file every client can read through jsDelivr / raw)
#   2. commits and pushes it, and purges the CDN
#   3. uploads the APK to the worker's KV, so https://spektralab.workshop.skin/download
#      serves it from Cloudflare instead of GitHub (which is slow or blocked in CN)
#   4. writes the announcement to the worker's KV, so the app sees it immediately
#
# The token is never stored here: this repository is public.
set -euo pipefail

if [ $# -lt 3 ]; then
  echo "usage: [CF_TOKEN=...] $0 <version_code> <version_name> <apk_path|-> [note...]" >&2
  exit 2
fi

CODE=$1
NAME=$2
APK=$3
shift 3

SITE="https://spektralab.workshop.skin"
UPD="https://update.workshop.skin"

NOTES=$(printf '%s\n' "$@" | python3 -c 'import json,sys
print(json.dumps([l.rstrip("\n") for l in sys.stdin if l.strip()], ensure_ascii=False))')
TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)

cat > update.json <<JSON
{
  "latest_version_code": $CODE,
  "latest_version_name": "$NAME",
  "url": "$SITE/",
  "notes": $NOTES,
  "updated_at": "$TS",
  "channel": "stable"
}
JSON
echo "--- update.json"; cat update.json

git add update.json
git commit -m "chore(update): announce $NAME ($CODE)"
git push origin HEAD

# jsDelivr caches a moving ref; purge so the new announcement is served now.
curl -s "https://purge.jsdelivr.net/gh/SAINTTaiYi/SpektraLab-Android@master/update.json" >/dev/null \
  && echo "--- jsDelivr purged" || echo "--- jsDelivr purge failed (harmless)"

if [ -z "${CF_TOKEN:-}" ]; then
  echo "--- CF_TOKEN unset: KV not updated (clients fall back to the CDN copy)."
  exit 0
fi

if [ "$APK" != "-" ]; then
  [ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 1; }
  SIZE=$(stat -c %s "$APK")
  if [ "$SIZE" -gt $((24 * 1024 * 1024)) ]; then
    echo "APK is $SIZE bytes; the KV value limit is 24 MiB. Use R2 or the GitHub asset." >&2
  else
    SHA=$(sha256sum "$APK" | cut -d' ' -f1)
    FILE="SpektraLab-$NAME-arm64-v8a.apk"
    echo "--- uploading $FILE ($SIZE bytes)"
    curl -s -X PUT "$UPD/admin/apk?name=$FILE&sha256=$SHA" \
      -H "authorization: Bearer $CF_TOKEN" \
      -H "content-type: application/vnd.android.package-archive" \
      --data-binary "@$APK"
    echo
  fi
fi

echo "--- announcement"
curl -s -X PUT "$UPD/admin/latest" \
  -H "authorization: Bearer $CF_TOKEN" \
  -H "content-type: application/json" \
  --data-binary @update.json
echo
echo "--- live: $SITE/"
