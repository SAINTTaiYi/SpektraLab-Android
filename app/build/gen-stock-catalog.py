#!/usr/bin/env python3
"""Regenerate assets/resources/stock_catalog.json from the packed profiles.

The list the UI shows must be the list the APK ships, not the engine source
tree: the bundle carries a few profiles (B&W films, CineStill, Ilford paper)
that the open-source engine resources do not.

Run from the APK project root; it writes both the source asset and the staging
copy that aapt packages.
"""
import glob
import json
import os
import shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # app/
PROJECT = os.path.dirname(ROOT)
STAGED = os.path.join(PROJECT, 'app/build/apk/assets/resources/profiles')
SRC = os.path.join(PROJECT, 'app/src/main/assets/resources/profiles')
OUT = os.path.join(PROJECT, 'app/src/main/assets/resources/stock_catalog.json')
OUT_STAGED = os.path.join(PROJECT, 'app/build/apk/assets/resources/stock_catalog.json')

profiles = STAGED if os.path.isdir(STAGED) else SRC
films, prints = [], []
for path in sorted(glob.glob(os.path.join(profiles, '*.json'))):
    info = json.load(open(path)).get('info', {})
    rec = {
        'id': info.get('stock') or os.path.basename(path)[:-5],
        'name': info.get('name') or info.get('stock') or os.path.basename(path)[:-5],
        'type': info.get('type') or 'negative',
        'use': info.get('use') or 'still',
        'support': info.get('support') or 'film',
        'stage': info.get('stage') or 'filming',
    }
    (prints if rec['stage'] == 'printing' else films).append(rec)

films.sort(key=lambda r: (0 if r['use'] == 'still' else 1, r['name']))
prints.sort(key=lambda r: (0 if r['use'] == 'still' else 1, r['name']))

payload = {'schema_version': 1, 'films': films, 'prints': prints}
for dest in (OUT, OUT_STAGED):
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, 'w') as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=1)
print('stocks: %d films, %d prints -> %s' % (len(films), len(prints), OUT))
