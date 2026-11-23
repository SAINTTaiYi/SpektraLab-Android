#!/usr/bin/env python3
"""Build Inter-Medium.ttf and Inter-SemiBold.ttf for `app/src/main/assets/fonts/`.

`assets/` is gitignored, so a fresh clone has Inter-Regular and OPPO Sans (both
checked in elsewhere) but not the two weights this app asks for by name. Run
this once after cloning:

    python3 tools/assets/make_inter_weights.py

It downloads Inter's upstream woff2 (the same face the 400 weight comes from),
subsets it to exactly the characters `Inter-Regular.ttf` already carries, and
writes ~44 KB TrueType files beside it. `Typeface.create(face, 500)` returns
the face unchanged on a static font, so these have to be real instances.

Requires: fonttools, brotli (pip install fonttools brotli)
"""
import os
import urllib.request

from fontTools import subset
from fontTools.ttLib import TTFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
FONTS = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'fonts')
BASE = 'https://raw.githubusercontent.com/rsms/inter/master/docs/font-files/'

# Keep the features the UI actually asks for. `tnum` is what makes a slider's
# readout stop moving; dropping it turns every number back into a variable-width
# string and the row twitches as the value crosses zero.
FEATURES = ['kern', 'liga', 'tnum', 'pnum', 'case', 'calt']


def build(weight):
    src = os.path.join(FONTS, 'Inter-%s.ttf' % weight)
    if os.path.exists(src):
        print('%s already there' % src)
        return
    ref = TTFont(os.path.join(FONTS, 'Inter-Regular.ttf'))
    unicodes = set(ref['cmap'].getBestCmap().keys())
    ref.close()

    cache = os.path.join(FONTS, '.Inter-%s.woff2' % weight)
    if not os.path.exists(cache):
        url = BASE + 'Inter-%s.woff2' % weight
        print('fetching %s' % url)
        urllib.request.urlretrieve(url, cache)

    opts = subset.Options()
    opts.flavor = None
    opts.layout_features = FEATURES
    opts.name_IDs = ['*']
    opts.name_legacy = True
    opts.name_languages = ['*']
    opts.notdef_outline = True
    opts.recalc_bounds = True
    opts.recalc_timestamp = False
    opts.drop_tables += ['DSIG']
    opts.hinting = True

    font = TTFont(cache)
    sub = subset.Subsetter(options=opts)
    sub.populate(unicodes=unicodes)
    sub.subset(font)
    font.flavor = None
    font.save(src)
    font.close()
    os.remove(cache)
    print('wrote %s (%d bytes)' % (src, os.path.getsize(src)))


for w in ('Medium', 'SemiBold'):
    build(w)
