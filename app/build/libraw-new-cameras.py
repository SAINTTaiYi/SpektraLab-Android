#!/usr/bin/env python3
"""Add every camera LibRaw 0.22.2 predates, using upstream master's own rows.

Why this exists, in one line: a colour matrix is looked up by *prefix* against
`adobe_coeff`, and a body that is not in the table keeps `cam_xyz` at the
sRGB->XYZ default -- which is to say its colour is never corrected. The Nikon
Z 50II is the case that surfaced it: it reports its model as `Z50_2`, no space,
so it misses the `"Z 50"` row and a 20.9 MP frame rendered violet and two stops
dark while the same file looked right everywhere else.

The 23 rows below are copied verbatim from LibRaw master's `colordata.cpp`
(2026-11-06). They are appended at the end of `adobe_coeff`, so every existing
lookup still finds its own row first; the insertion is idempotent and the
script is safe to run on every build.

Usage: libraw-new-cameras.py <path to src/tables/colordata.cpp>
"""
import sys

ENTRIES = [
    '{ LIBRAW_CAMERAMAKER_Canon, "EOS C50", 0, 0,\n\t  {  8309, -1786, -1095, -4851, 12736,  2353,  -956,  1911,  5534 } },',
    '{ LIBRAW_CAMERAMAKER_Canon, "EOS R50 V", 0, 0,\n\t  {9089, -1810, -1209, -4294, 12116, 2454, -498, 1862, 4923 } },',
    '{ LIBRAW_CAMERAMAKER_Canon, "EOS R6 Mark III", 0, 0,\n    { 8309, -1786, -1095, -4851, 12736,  2353,  -956,  1911,  5534 } },',
    '{ LIBRAW_CAMERAMAKER_Canon, "PowerShot V1", 0, 0,\n    { 12600,-4747,-2027,-6110,15868,-29,110,479,6011 } },',
    '{ LIBRAW_CAMERAMAKER_Fujifilm, "GFX 100RF", 0, 0,\n\t\t{ 12806,-5779,-1110,-3546,11507,2318,-177,996,5715 } },',
    '{ LIBRAW_CAMERAMAKER_Fujifilm, "X-E5", 0, 0,\n    { 11809,-5358,-1141,-4248,12164,2343,-514,1097,5848 } },',
    '{ LIBRAW_CAMERAMAKER_Fujifilm, "X-T30 III", 0, 0,\n    { 12836, -5909, -1032, -3087, 11132, 2236, -35, 872, 5330 } },',
    '{ LIBRAW_CAMERAMAKER_Hasselblad, "100-22-Coated6", 0, 0,\n\t  { 6468,-1899,-545,-4526,12267,2542,-388,1276,6096 } },',
    '{ LIBRAW_CAMERAMAKER_Leica, "Q3", 0, 0,\n      { 6903, -824, -903, -3786, 13084, 687, -714, 1430, 3055 } },',
    '{ LIBRAW_CAMERAMAKER_Leica, "Q3 43", 0, 0,\n      { 11111, -3816, -1057, -3750, 12107, 1829, -947, 1868, 4645 } },',
    '{ LIBRAW_CAMERAMAKER_Nikon, "Z50_2", 0, 0,\n      { 11640,-4829,-1079,-5107,13006,2325,-972,1711,7380 } },',
    '{ LIBRAW_CAMERAMAKER_Nikon, "Z5_2", 0, 0,\n      {  9874, -3784,  -823, -4728, 12673,  2286,  -648,  1513,  6375 } },',
    '{ LIBRAW_CAMERAMAKER_Nikon, "ZR", 0, 0,\n      { 11206, -4286,  -941, -4879, 12847,  2251,  -745,  1654,  7374 } },',
    '{ LIBRAW_CAMERAMAKER_Olympus, "OM-1 Mark II", 0, 0,\n      { 9090, -3591, -756, -3252, 11396, 2109, -318, 1059, 5606 } },',
    '{ LIBRAW_CAMERAMAKER_Olympus, "OM-3", 0, 0,\n      { 9090, -3591, -756, -3252, 11396, 2109, -318, 1059, 5606 } },',
    '{ LIBRAW_CAMERAMAKER_Olympus, "OM-5 Mark II", 0, 0,\n      { 12008, -5230, -1123, -2265, 10496, 2046, 69, 734, 4938 } },',
    '{ LIBRAW_CAMERAMAKER_Panasonic, "DC-S1M2", 0, 0,\n      {10730, -4442, -922, -4478, 12504, 2199, -380, 1198, 6039 } },',
    '{ LIBRAW_CAMERAMAKER_Panasonic, "DC-S1M2ES", 0, 0,\n      { 11333, -4794, -1062, -3912, 12027 ,2115, 42, 784, 5904 } },',
    '{ LIBRAW_CAMERAMAKER_Panasonic, "DC-S1RM2", 0, 0,\n      {9941, -4056, -871, -3745, 11604, 2433, -254, 1132, 5526 } },',
    '{ LIBRAW_CAMERAMAKER_Sony, "DSC-RX1RM3", 0, 0,\n      { 9034,-3490,-777,-4004,11841,2448,-484,1352,5552 } },',
    '{ LIBRAW_CAMERAMAKER_Sony, "ILCE-1M2", 0, 0,\n      { 10058, -4074,  -932, -4777, 12731,  2274,  -544,  1282,  6102 } },',
    '{ LIBRAW_CAMERAMAKER_Sony, "ILCE-7M5", 0, 0,\n      { 9089,-3577,-787,-3563,11326,2557,-114,928,5904 } },',
    '{ LIBRAW_CAMERAMAKER_Sony, "ILCE-7RM6", 0, 0,\n\t    { 11765, -5595, -1192, -3689, 11507, 2485, 51, 681, 5731 } },',

]


def table_end(text):
    """Index of the newline before the `};` that closes the adobe_coeff array.

    Counting braces does not work here: the file is full of comments, and a
    comment holding a `{` shifts every count after it. The table's closing line
    is the first `\n  };` past the anchor, which no entry can produce.
    """
    anchor = text.index('} table[] = {')
    end = text.index('\n  };', anchor)
    return end + 1


def main(path):
    text = open(path).read()
    block = ''
    added = skipped = 0
    for entry in ENTRIES:
        model = entry.split('"')[1]
        if '"%s"' % model in text:
            skipped += 1
            continue
        block += '\t' + entry + '\n'
        added += 1
    if block:
        end = table_end(text)
        text = text[:end] + block + text[end:]
        open(path, 'w').write(text)
    print('libraw cameras: %d added, %d already present' % (added, skipped))


if __name__ == '__main__':
    if len(sys.argv) < 2:
        raise SystemExit('usage: libraw-new-cameras.py <colordata.cpp>')
    main(sys.argv[1])
