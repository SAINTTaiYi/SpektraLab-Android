#!/usr/bin/env bash
# Publish an update announcement.
#
#   CF_TOKEN=xxxx ./scripts/announce.sh 22 1.3.0 "第一条说明" "第二条说明"
#
# Writes update.json (the file every client reads first through jsDelivr / raw),
# commits and pushes it, purges the CDN, and — when CF_TOKEN is set — writes the
# same payload to the Cloudflare KV behind the worker, so clients that can reach
# workers.dev see it immediately instead of waiting for a CDN refresh.
#
# The token is never stored here: this repository is public.
set -euo pipefail

if [ $# -lt 2 ]; then
  echo "usage: [CF_TOKEN=...] $0 <version_code> <version_name> [note...]" >&2
  exit 2
fi

CODE=$1
NAME=$2
shift 2

NOTES=$(printf '%s\n' "$@" | python3 -c 'import json,sys
print(json.dumps([l.rstrip("\n") for l in sys.stdin if l.strip()], ensure_ascii=False))')
TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)

cat > update.json <<JSON
{
  "latest_version_code": $CODE,
  "latest_version_name": "$NAME",
  "url": "https://github.com/SAINTTaiYi/SpektraLab-Android/releases/latest",
  "notes": $NOTES,
  "updated_at": "$TS",
  "channel": "stable"
}
JSON
echo "update.json:"
cat update.json

git add update.json
git commit -m "chore(update): announce $NAME ($CODE)"
git push origin HEAD

# jsDelivr caches a moving ref; purge so the new announcement is served now.
curl -s "https://purge.jsdelivr.net/gh/SAINTTaiYi/SpektraLab-Android@master/update.json" >/dev/null \
  && echo "jsDelivr purged" || echo "jsDelivr purge failed (harmless, it expires on its own)"

if [ -n "${CF_TOKEN:-}" ]; then
  echo "Cloudflare:"
  curl -s -X PUT "https://spektralab-update.geeklightonefish.workers.dev/admin/latest" \
    -H "authorization: Bearer $CF_TOKEN" \
    -H "content-type: application/json" \
    --data-binary @update.json
  echo
else
  echo "CF_TOKEN unset: Cloudflare copy not updated (clients fall back to the CDN)."
fi
