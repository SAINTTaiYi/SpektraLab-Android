#!/usr/bin/env python3
"""Regression tests for the release-integrity checkers.

Each checker is copied into a temporary repository fixture together with the
files it reads, so a failure here means a checker stopped catching the exact
mismatch it exists to catch.
"""

import json
import shutil
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[2]
CHECKERS = REPO / 'app' / 'build'


class CheckerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / 'app/build').mkdir(parents=True)
        (self.root / 'app/src/main').mkdir(parents=True)
        (self.root / 'scripts').mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def copy_checker(self, name):
        src = CHECKERS / name
        dst = self.root / 'app/build' / name
        shutil.copyfile(src, dst)
        return dst

    def run_checker(self, script, *args):
        return subprocess.run([sys.executable, str(script), *args],
                              capture_output=True, text=True)

    def fixture_version(self, code=385, name='1.6.2m', sha='abc123'):
        (self.root / 'version.json').write_text(json.dumps({
            'versionCode': code, 'versionName': name,
            'releaseApk': f'SpektraLab-{name}-arm64-v8a.apk',
            'releaseSha256': sha}, indent=2), encoding='utf-8')

    def test_version_checker_accepts_a_matching_tree(self):
        script = self.copy_checker('check-version.py')
        self.fixture_version()
        (self.root / 'app/src/main/AndroidManifest.xml').write_text(
            'android:versionCode="385"\nandroid:versionName="1.6.2m">\n', encoding='utf-8')
        (self.root / 'update.json').write_text(json.dumps({
            'latest_version_code': 385, 'latest_version_name': '1.6.2m'}), encoding='utf-8')
        (self.root / 'README.md').write_text('`1.6.2m (385)`', encoding='utf-8')
        (self.root / 'scripts/build-windows.ps1').write_text(
            '$version = Get-Content version.json | ConvertFrom-Json', encoding='utf-8')
        result = self.run_checker(script)
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_version_checker_rejects_a_mismatched_manifest(self):
        script = self.copy_checker('check-version.py')
        self.fixture_version()
        (self.root / 'app/src/main/AndroidManifest.xml').write_text(
            'android:versionCode="384"\nandroid:versionName="1.6.2m">\n', encoding='utf-8')
        (self.root / 'update.json').write_text(json.dumps({
            'latest_version_code': 385, 'latest_version_name': '1.6.2m'}), encoding='utf-8')
        (self.root / 'README.md').write_text('`1.6.2m (385)`', encoding='utf-8')
        (self.root / 'scripts/build-windows.ps1').write_text('version.json', encoding='utf-8')
        result = self.run_checker(script)
        self.assertEqual(result.returncode, 1)
        self.assertIn('versionCode', result.stdout)

    def test_prebuilt_checker_detects_a_corrupted_library(self):
        script = self.copy_checker('check-prebuilt.py')
        self.fixture_version(sha='0' * 64)
        (self.root / 'app/prebuilt/arm64-v8a').mkdir(parents=True)
        (self.root / 'app/prebuilt/sha256.json').write_text(json.dumps({
            'apk': '0' * 64,
            'libs': {'libengine.so': '1' * 64}}), encoding='utf-8')
        # ELF64 ARM64 header: magic, class 2, little endian, version 1, machine 0xB7.
        elf = bytearray(20)
        elf[:4] = b'\x7fELF'
        elf[4] = 2
        elf[18:20] = (0xB7).to_bytes(2, 'little')
        (self.root / 'app/prebuilt/arm64-v8a/libengine.so').write_bytes(elf)
        good = self.run_checker(script)
        self.assertEqual(good.returncode, 1)  # hash mismatch is expected here
        (self.root / 'app/prebuilt/sha256.json').write_text(json.dumps({
            'apk': '0' * 64,
            'libs': {'libengine.so': __import__('hashlib').sha256(elf).hexdigest()}}),
            encoding='utf-8')
        good = self.run_checker(script)
        self.assertEqual(good.returncode, 0, good.stdout)

    def test_prebuilt_checker_rejects_a_non_arm64_binary(self):
        script = self.copy_checker('check-prebuilt.py')
        self.fixture_version(sha='0' * 64)
        (self.root / 'app/prebuilt/arm64-v8a').mkdir(parents=True)
        elf = bytearray(20)
        elf[:4] = b'\x7fELF'
        elf[4] = 2
        elf[18:20] = (0x3E).to_bytes(2, 'little')  # x86-64
        (self.root / 'app/prebuilt/arm64-v8a/libengine.so').write_bytes(elf)
        digest = __import__('hashlib').sha256(elf).hexdigest()
        (self.root / 'app/prebuilt/sha256.json').write_text(json.dumps({
            'apk': '0' * 64, 'libs': {'libengine.so': digest}}), encoding='utf-8')
        result = self.run_checker(script)
        self.assertEqual(result.returncode, 1)
        self.assertIn('arm64', result.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
