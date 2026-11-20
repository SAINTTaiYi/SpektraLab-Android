#!/usr/bin/env python3
"""Validate the UI catalogs before putting them into an APK."""

from collections import Counter
import json
from pathlib import Path
import re

MAIN = Path(__file__).resolve().parents[1] / 'src' / 'main'
LANG_TAG = re.compile(r'[a-z]{2,3}(?:-[A-Z][a-z]{3})?(?:-(?:[A-Z]{2}|\d{3}))?(?:-[a-z0-9]{4,8})*')
KEY = re.compile(r'[a-z0-9_]+(?:\.[a-z0-9_]+)+')
# The supported Java format tokens for translated UI copy.
FORMAT = re.compile(r'%(?:(\d+)\$)?[-+#0,(]*\d*(?:\.\d+)?([dsf%])')


def unique_object(items):
    obj = {}
    for key, value in items:
        if key in obj:
            raise ValueError(f'duplicate field {key!r}')
        obj[key] = value
    return obj


def read_catalog(path):
    try:
        doc = json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique_object)
        tag = doc['tag']
        name = doc['name']
        strings = doc['strings']
        if not isinstance(tag, str) or not LANG_TAG.fullmatch(tag) or tag == 'und' or tag != path.stem:
            raise ValueError('tag must be a canonical language tag matching the filename')
        if not isinstance(name, str) or not name.strip():
            raise ValueError('name must be the nonempty display name of the language')
        if not isinstance(strings, dict) or any(not KEY.fullmatch(k) or not isinstance(v, str)
                                                for k, v in strings.items()):
            raise ValueError('strings must map dotted message IDs to strings')
        return strings
    except (OSError, KeyError, TypeError, ValueError) as exc:
        raise ValueError(f'{path.name}: {exc}') from exc


def format_args(text):
    arguments = Counter()
    offset = 0
    implicit_index = 1
    while (at := text.find('%', offset)) != -1:
        match = FORMAT.match(text, at)
        if not match:
            raise ValueError(f'invalid format placeholder: {text[at:]}')
        index, spec = match.groups()
        if spec != '%':
            arguments[int(index) if index else implicit_index, spec] += 1
            if index is None:
                implicit_index += 1
        offset = match.end()
    return arguments


def validate(directory, code):
    calls = re.compile(r'\b(tr|AppLanguage\.format|knob)\(\s*"([a-z0-9_.]+)"')
    used = set()
    formatted = set()
    for path in code.rglob('*.java'):
        for method, key in calls.findall(path.read_text(encoding='utf-8')):
            used.add(key)
            if method == 'AppLanguage.format':
                formatted.add(key)

    english = read_catalog(directory / 'en.json')
    if missing := used - english.keys():
        raise ValueError('en.json: missing message IDs: ' + ', '.join(sorted(missing)))
    signatures = {key: format_args(english[key]) for key in formatted}

    catalogs = sorted(directory.glob('*.json'))
    for path in catalogs:
        strings = read_catalog(path)
        if extra := strings.keys() - english.keys():
            raise ValueError(f'{path.name}: unknown message IDs: ' + ', '.join(sorted(extra)))
        for key in strings.keys() & signatures.keys():
            try:
                if format_args(strings[key]) != signatures[key]:
                    raise ValueError('format arguments differ from English')
            except ValueError as exc:
                raise ValueError(f'{path.name}: {key}: {exc}') from exc
    print(f'Locales: {len(catalogs)} catalogs, {len(english)} message IDs')


if __name__ == '__main__':
    try:
        validate(MAIN / 'locales', MAIN / 'java')
    except ValueError as exc:
        raise SystemExit(f'check-locales: {exc}')
