#!/usr/bin/env python3
"""Runnable regression tests for the packaging helpers.

These need no Android SDK or device: they exercise the ZIP logic that decides
which files reach an APK. Run with `python app/build/test_build_tools.py`.
"""

import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile


SCRIPT = Path(__file__).with_name('apk-contents.py')
spec = importlib.util.spec_from_file_location('apk_contents', SCRIPT)
apk_contents = importlib.util.module_from_spec(spec)
spec.loader.exec_module(apk_contents)


class PackagingTest(unittest.TestCase):
    def make_apk(self, path):
        with zipfile.ZipFile(path, 'w') as z:
            z.writestr('AndroidManifest.xml', b'manifest')
            z.writestr('assets/resources/a.bin', b'resource')
            z.writestr('assets/resources/spektrafilm_constants.bin', b'constants')
            z.writestr('assets/locales/en.json', b'{}')
            z.writestr('lib/arm64-v8a/libengine.so', b'engine')
            z.writestr('lib/arm64-v8a/libspektrafilm_engine.so', b'engine')
            z.writestr('../escape.txt', b'bad')

    def test_extract_assets_only_and_no_locales_or_traversal(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            apk = root / 'release.apk'
            stage = root / 'stage'
            self.make_apk(apk)
            apk_contents.extract_assets(apk, stage)
            self.assertTrue((stage / 'assets/resources/a.bin').is_file())
            self.assertFalse((stage / 'assets/locales/en.json').exists())
            self.assertFalse((stage / 'lib').exists())
            self.assertFalse((root / 'escape.txt').exists())

    def test_extract_libs_and_resources(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            apk = root / 'release.apk'
            stage = root / 'stage'
            self.make_apk(apk)
            apk_contents.extract(apk, stage)
            self.assertTrue((stage / 'lib/arm64-v8a/libengine.so').is_file())
            self.assertTrue((stage / 'assets/resources/a.bin').exists())

    def test_pack_deflates_new_entries_and_keeps_source_entries(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stage = root / 'stage'
            (stage / 'dex').mkdir(parents=True)
            (stage / 'lib/arm64-v8a').mkdir(parents=True)
            (stage / 'dex/classes.dex').write_bytes(b'dex' * 100)
            (stage / 'assets/resources').mkdir(parents=True, exist_ok=True)
            (stage / 'lib/arm64-v8a/libengine.so').write_bytes(b'engine' * 100)
            resources = stage / 'resources.apk'
            with zipfile.ZipFile(resources, 'w') as z:
                z.writestr('assets/resources/a.bin', b'resource' * 100,
                           compress_type=zipfile.ZIP_DEFLATED)
            out = stage / 'unsigned.apk'
            apk_contents.pack(resources, stage)
            with zipfile.ZipFile(out) as z:
                infos = {i.filename: i for i in z.infolist()}
                self.assertEqual(infos['classes.dex'].compress_type, zipfile.ZIP_DEFLATED)
                self.assertEqual(infos['lib/arm64-v8a/libengine.so'].compress_type,
                                 zipfile.ZIP_DEFLATED)
                self.assertEqual(infos['assets/resources/a.bin'].compress_type,
                                 zipfile.ZIP_DEFLATED)
                self.assertEqual(z.read('classes.dex'), b'dex' * 100)


if __name__ == '__main__':
    unittest.main(verbosity=2)
