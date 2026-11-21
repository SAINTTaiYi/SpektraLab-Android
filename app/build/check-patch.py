#!/usr/bin/env python3
"""Check that the published engine patch can actually produce the shipped JNI.

The README tells a user to clone upstream 445e314, apply patches/engine-android.patch,
and build.  That instruction was wrong for months: the patch references a header it
does not add (tiling.hpp) and calls public spk_* APIs it never declares.  This check
turns that silent failure into a build-time error.

Usage:
    python app/build/check-patch.py <upstream-engine-root> [patch-file]

`<upstream-engine-root>` is a checkout of the upstream commit (the directory that
contains `engine/`).  No files are modified.
"""

from pathlib import Path
import re
import sys


DIFF = re.compile(r'^diff --git a/(.+?) b/(.+)$', re.M)
INCLUDE = re.compile(r'^\s*#include\s+"([^"]+)"', re.M)
SPK_CALL = re.compile(r'\b(spk_[A-Za-z0-9_]+)\s*\(')


def diff_sections(patch):
    """Yield (target path, section text) for every `diff --git` block."""
    for section in re.split(r'(?m)^diff --git ', patch)[1:]:
        head = section.splitlines()[0] if section.splitlines() else ''
        target = re.search(r'\S+ b/(\S+)', head)
        if target:
            yield target.group(1), section


def added_lines(patch, path):
    """Lines added to `path` by the patch (only its diff section)."""
    for target, section in diff_sections(patch):
        if target == path:
            return [line[1:] for line in section.splitlines()
                    if line.startswith('+') and not line.startswith('+++')]
    return []


def main(engine_root, patch_path):
    root = Path(engine_root)
    patch = Path(patch_path).read_text(encoding='utf-8')
    problems = []

    targets = [b for _, b in DIFF.findall(patch)]
    added = {target for target, section in diff_sections(patch)
             if 'new file mode' in section.split('---', 1)[0]}

    for target in targets:
        if target not in added and not (root / target).is_file():
            problems.append(f'patch modifies missing upstream file: {target}')

    for target in targets:
        for inc in INCLUDE.findall('\n'.join(added_lines(patch, target))):
            here = (root / target).parent / inc
            if here.is_file() or inc in added or any(p.name == inc for p in (root / 'engine').rglob(inc)):
                continue
            problems.append(f'{target}: patched include cannot be resolved: "{inc}"')

    header = 'engine/include/spektrafilm/spk_engine.h'
    patched_header = (root / header).read_text(encoding='utf-8') if (root / header).is_file() else ''
    declared = set(SPK_CALL.findall(patched_header))
    declared.update(SPK_CALL.findall('\n'.join(added_lines(patch, header))))
    jni_dir = Path(__file__).resolve().parents[1] / 'src' / 'main' / 'cpp'
    used = set()
    for cpp in sorted(jni_dir.glob('*.cpp')):
        used.update(SPK_CALL.findall(cpp.read_text(encoding='utf-8')))
    missing = sorted(used - declared)
    for name in missing:
        problems.append(f'JNI calls {name}() but the patched public header does not declare it')

    if problems:
        print('check-patch: the published source tree cannot build the JNI:')
        for problem in problems:
            print('  - ' + problem)
        print('  -> add the missing files/declarations to patches/engine-android.patch')
        return 1
    print(f'check-patch: {len(targets)} patched files, {len(used)} JNI spk_* calls all declared')
    return 0


if __name__ == '__main__':
    if len(sys.argv) < 2:
        raise SystemExit('usage: check-patch.py <upstream-engine-root> [patch-file]')
    patch_file = sys.argv[2] if len(sys.argv) > 2 else (
        str(Path(__file__).resolve().parents[2] / 'patches' / 'engine-android.patch'))
    sys.exit(main(sys.argv[1], patch_file))
