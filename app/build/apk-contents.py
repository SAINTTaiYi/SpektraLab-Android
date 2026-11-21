#!/usr/bin/env python3
"""Stage release assets/libs or combine AAPT resources with new dex and ELF entries."""
from pathlib import Path, PurePosixPath
import shutil
import sys
import zipfile


def extract(apk, stage):
    with zipfile.ZipFile(apk) as source:
        for entry in source.infolist():
            path = PurePosixPath(entry.filename)
            if entry.is_dir() or not path.parts or path.is_absolute() or '..' in path.parts:
                continue
            if path.parts[:2] == ('assets', 'locales'):
                continue  # The catalog must match this checkout's Java, not the donor APK.
            if path.parts[0] == 'assets' or (
                len(path.parts) == 3 and path.parts[:2] == ('lib', 'arm64-v8a')
                and path.suffix == '.so'
            ):
                source.extract(entry, stage)
    for name in ('assets/resources/spektrafilm_constants.bin',
                 'lib/arm64-v8a/libspektrafilm_engine.so'):
        if not (stage / name).is_file():
            raise SystemExit(f'Release APK is missing {name}')


def extract_assets(apk, stage):
    """Copy only assets/ from a release APK (used by -UsePrebuiltNative)."""
    with zipfile.ZipFile(apk) as source:
        for entry in source.infolist():
            path = PurePosixPath(entry.filename)
            if entry.is_dir() or path.parts[0] != 'assets' or '..' in path.parts:
                continue
            if path.parts[:2] == ('assets', 'locales'):
                continue  # catalogs must match this checkout's Java
            source.extract(entry, stage)


def pack(apk, stage):
    if not (stage / 'dex/classes.dex').is_file():
        raise SystemExit('No classes.dex to package')
    # Regenerate ZIP headers before zipalign. Appending directly to the AAPT2
    # ZIP leaves local and central-directory headers that zipalign warns about.
    with zipfile.ZipFile(apk) as source, \
            zipfile.ZipFile(stage / 'unsigned.apk', 'w') as target:
        for entry in source.infolist():
            target_info = zipfile.ZipInfo(entry.filename, entry.date_time)
            target_info.compress_type = entry.compress_type
            with source.open(entry) as data, target.open(target_info, 'w') as out:
                shutil.copyfileobj(data, out)
        def add_deflated(path, name):
            # The published APK deflates its native libraries (compress_type 8).
            # zipfile.write() defaults to ZIP_STORED, which made local builds
            # 27 MB instead of 15.9 MB. Keep the original timestamp too.
            info = zipfile.ZipInfo(name, __import__('datetime').datetime.fromtimestamp(
                path.stat().st_mtime).timetuple()[:6])
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with path.open('rb') as data, target.open(info, 'w') as out:
                shutil.copyfileobj(data, out)

        for dex in sorted((stage / 'dex').glob('classes*.dex')):
            add_deflated(dex, dex.name)
        for lib in sorted((stage / 'lib/arm64-v8a').glob('*.so')):
            add_deflated(lib, lib.relative_to(stage).as_posix())


if __name__ == '__main__':
    action, directory, apk_path = sys.argv[1:]
    {'extract': extract, 'assets': extract_assets, 'pack': pack}[action](
        Path(apk_path), Path(directory))
