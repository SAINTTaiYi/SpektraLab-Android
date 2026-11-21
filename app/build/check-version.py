#!/usr/bin/env python3
"""One version source: check every copy of the release identity against version.json.

Checked: AndroidManifest.xml, update.json, README.md, scripts/build-windows.ps1.
Run from anywhere: `python app/build/check-version.py`.
"""

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def read_version():
    doc = json.loads((ROOT / 'version.json').read_text(encoding='utf-8'))
    return (int(doc['versionCode']), str(doc['versionName']),
            str(doc['releaseApk']), str(doc['releaseSha256']))


def main():
    code, name, apk, sha = read_version()
    problems = []

    manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
    m_code = re.search(r'android:versionCode="(\d+)"', manifest)
    m_name = re.search(r'android:versionName="([^"]+)"', manifest)
    if not m_code or int(m_code.group(1)) != code:
        problems.append(f'AndroidManifest.xml versionCode should be {code}')
    if not m_name or m_name.group(1) != name:
        problems.append(f'AndroidManifest.xml versionName should be {name}')

    update = json.loads((ROOT / 'update.json').read_text(encoding='utf-8'))
    if update.get('latest_version_code') != code:
        problems.append(f'update.json latest_version_code should be {code}')
    if update.get('latest_version_name') != name:
        problems.append(f'update.json latest_version_name should be {name}')

    readme = (ROOT / 'README.md').read_text(encoding='utf-8')
    if not re.search(r'`' + re.escape(name) + r'\s*\(' + str(code) + r'\)`', readme):
        problems.append(f'README.md "current version" line should say `{name} ({code})`')

    script = (ROOT / 'scripts/build-windows.ps1').read_text(encoding='utf-8')
    if 'version.json' not in script:
        problems.append('build-windows.ps1 must read version.json, not hardcode the release')
    if re.search(r'SpektraLab-\d+\.\d', script):
        problems.append('build-windows.ps1 still contains a hardcoded release APK name')

    if problems:
        print('check-version: version.json is the single source of truth:')
        for problem in problems:
            print('  - ' + problem)
        return 1
    print(f'check-version: {name} ({code}) consistent in all {5} places')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
