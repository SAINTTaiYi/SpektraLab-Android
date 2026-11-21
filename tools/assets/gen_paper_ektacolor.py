import re, struct, os

BASE = os.path.expanduser('~/spk-debug/fotufilm-engine-main/Sources/FotufilmCore')

def read(p):
    return open(os.path.join(BASE, p), encoding='utf-8').read()

FLOAT = re.compile(r'-?\d+\.?\d*(?:[eE][-+]?\d+)?')

def block_after(text, marker, stops):
    """Text from marker up to the first of `stops` that follows it."""
    i = text.index(marker)
    j = len(text)
    for s in stops:
        k = text.find(s, i + len(marker))
        if k != -1 and k < j:
            j = k
    return text[i + len(marker):j]

def floats_of(chunk):
    # Commentary carries literals like "E-7020" and "1.0"; strip it before reading numbers.
    return [float(x) for x in FLOAT.findall(re.sub(r'//[^\n]*', '', chunk))]

# --- PrintPaperSpectra: three 3x81 arrays -----------------------------------
src = read('PrintPaperSpectra.swift')
dye = floats_of(block_after(src, 'static let dyeDensity: [[Float]] = [',
                            ['static let neutralAmounts']))
neutral = floats_of(block_after(src, 'static let neutralAmounts: [Float] = [',
                                ['static let layerSensitivity']))
sens = floats_of(block_after(src, 'static let layerSensitivity: [[Float]] = [',
                             ['\n}']))
print('dyeDensity', len(dye), 'neutral', len(neutral), 'layerSensitivity', len(sens))
assert len(dye) == 243 and len(neutral) == 3 and len(sens) == 243

# --- EktacolorEdgeCurves: three 6-parameter curves ---------------------------
cat = read('PrintStockCatalogue.swift')
curves = {}
for name in ('red', 'green', 'blue'):
    chunk = block_after(cat, 'static let %s = CharacteristicCurve(' % name, ['\n    }', '\n    static let'])
    vals = floats_of(chunk)
    assert len(vals) == 6, (name, vals)
    curves[name] = vals
    print('curve', name, vals)

# --- a single-sheet PAP1 ----------------------------------------------------
def write_pap1(path, dye, neutral, sens, curve_triple):
    # The loader reads an 8-byte magic (PAP1 + four NULs); a 4-byte one shifts every float by one.
    buf = bytearray(b'PAP1\x00\x00\x00\x00')
    for layer in range(3):
        buf += struct.pack('<81f', *dye[layer * 81:(layer + 1) * 81])
    buf += struct.pack('<3f', *neutral)
    for layer in range(3):
        buf += struct.pack('<81f', *sens[layer * 81:(layer + 1) * 81])
    for triple in curve_triple:
        buf += struct.pack('<6f', *triple)
    open(path, 'wb').write(buf)
    return len(buf)

out = '/data/data/com.termux/files/home/spk-debug/cpphost/paper_ektacolor.bin'
n = write_pap1(out, dye, neutral, sens,
               [curves['red'], curves['green'], curves['blue']])
print('wrote', out, n, 'bytes')
