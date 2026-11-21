#!/usr/bin/env python3
"""Enforce the package boundaries from docs/android-skill-adoption.md.

app → ui → domain → data is not the rule: domain is pure, data implements it,
ui consumes domain, and the root package wires everything. The checker stops
the accidental imports that turn this into one big package again.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/app/spektralab'
IMPORT = re.compile(r'^\s*import\s+(?:static\s+)?([\w.]+)\s*;', re.M)


def main():
    problems = []
    for path in sorted(JAVA.rglob('*.java')):
        rel = path.relative_to(JAVA).as_posix()
        package = rel.split('/')[0] if '/' in rel else 'app'
        text = path.read_text(encoding='utf-8')
        imports = IMPORT.findall(text)
        for name in imports:
            if package == 'domain':
                if name.startswith('android.') or name.endswith('MainActivity'):
                    problems.append(f'{rel}: domain must not import {name}')
                if '.data.' in name or '.ui.' in name:
                    problems.append(f'{rel}: domain must not depend on data/ui ({name})')
            elif package == 'ui':
                if '.data.' in name or name.endswith('MainActivity'):
                    problems.append(f'{rel}: ui must not import {name}')
            elif package == 'data':
                if '.ui.' in name or name.endswith('MainActivity'):
                    problems.append(f'{rel}: data must not import {name}')
    if problems:
        print('check-package-rules: forbidden imports:')
        for problem in problems:
            print('  - ' + problem)
        return 1
    print('check-package-rules: app/ui/domain/data boundaries respected')
    return 0


if __name__ == '__main__':
    sys.exit(main())
