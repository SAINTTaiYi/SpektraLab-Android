#!/usr/bin/env python3
"""Verify the native payload tracked under app/prebuilt/arm64-v8a/.

The libraries are copied byte-for-byte out of the published release APK named
in version.json.  A mismatched hash here means the binary nobody can rebuild
from source has been replaced by something untested.
"""

import hashlib
import json
from pathlib import Path
import struct
import sys


ROOT = Path(__file__).resolve().parents[2]
PREBUILT = ROOT / 'app' / 'prebuilt'
ARM64_E_MACHINE = 0xB7


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    manifest = json.loads((PREBUILT / 'sha256.json').read_text(encoding='utf-8'))
    version = json.loads((ROOT / 'version.json').read_text(encoding='utf-8'))
    problems = []

    if manifest['apk'].lower() != version['releaseSha256'].lower():
        problems.append('sha256.json names a different release APK than version.json')

    for name, want in sorted(manifest['libs'].items()):
        path = PREBUILT / 'arm64-v8a' / name
        if not path.is_file():
            problems.append(f'{name}: missing')
            continue
        got = sha256(path)
        if got.lower() != want.lower():
            problems.append(f'{name}: SHA-256 {got} != {want}')
        with path.open('rb') as fh:
            head = fh.read(20)
        if len(head) < 20 or head[:4] != b'\x7fELF' or head[4] != 2:
            problems.append(f'{name}: not a 64-bit ELF')
            continue
        machine = struct.unpack_from('<H', head, 18)[0]
        if machine != ARM64_E_MACHINE:
            problems.append(f'{name}: e_machine 0x{machine:x}, expected arm64 (0xb7)')

    if problems:
        print('check-prebuilt: the tracked native payload is broken:')
        for problem in problems:
            print('  - ' + problem)
        return 1
    print(f'check-prebuilt: {len(manifest["libs"])} arm64 libraries match the '
          f'released APK ({version["versionName"]})')
    return 0


if __name__ == '__main__':
    sys.exit(main())
