import json
import os
import re
import struct

BASE = os.path.expanduser('~/spk-debug/fotufilm-engine-main/Sources/FotufilmCore')
OUT = os.path.expanduser('~/spk-debug/cpphost')

FLOAT = re.compile(r'-?\d+\.?\d*(?:[eE][-+]?\d+)?')


def strip_comments(text):
    # Commentary carries literals like "E-7020" and "1.0"; they must not reach the reader.
    return re.sub(r'//[^\n]*', '', text)


def floats_of(chunk):
    return [float(x) for x in FLOAT.findall(strip_comments(chunk))]


def read(name):
    return open(os.path.join(BASE, name), encoding='utf-8').read()


def slice_between(text, start, stops):
    i = text.index(start)
    j = len(text)
    for s in stops:
        k = text.find(s, i + len(start))
        if k != -1 and k < j:
            j = k
    return text[i + len(start):j]


# --- every CharacteristicCurve literal in the tree --------------------------
curves = {}
for name in os.listdir(BASE):
    if not name.endswith('.swift'):
        continue
    src = read(name)
    for match in re.finditer(r'static let (\w+) = CharacteristicCurve\(', src):
        key = match.group(1)
        # Balance the parentheses: a curve literal spans lines and its arguments may carry a
        # default, so neither a fixed stop nor a value count identifies the end.
        start = src.index('(', match.end() - 1)
        depth = 0
        end = start
        for i in range(start, len(src)):
            if src[i] == '(':
                depth += 1
            elif src[i] == ')':
                depth -= 1
                if depth == 0:
                    end = i
                    break
        vals = floats_of(src[start + 1:end])
        if len(vals) == 6:
            # Several sheets name their records redCurve; the file is part of the key.
            curves[(name[:-6], key)] = vals
print('curve literals:', len(curves))


def bracket_chunk(src, marker):
    """The text inside the first balanced [ ... ] after `marker`."""
    # Start the scan after the marker: `[[Float]]` in the declaration itself carries brackets.
    i = src.index(marker) + len(marker)
    start = src.index('[', i)
    depth = 0
    for j in range(start, len(src)):
        if src[j] == '[':
            depth += 1
        elif src[j] == ']':
            depth -= 1
            if depth == 0:
                return src[start + 1:j]
    raise SystemExit('unbalanced ' + marker)


def spectra_arrays(module):
    src = read(module + '.swift')
    dye = floats_of(bracket_chunk(src, 'static let dyeDensity: [[Float]] ='))
    neutral = floats_of(bracket_chunk(src, 'static let neutralAmounts: [Float] ='))
    sens = floats_of(bracket_chunk(src, 'static let layerSensitivity: [[Float]] ='))
    assert len(dye) == 243 and len(neutral) == 3 and len(sens) == 243, \
        (module, len(dye), len(neutral), len(sens))
    return dye, neutral, sens


# id, spectra module, the three curve literal names
PAPERS = [
    # id, spectra module, (curve source module, curve literal) x3
    ('ektacolor-edge', 'PrintPaperSpectra',
     [('PrintStockCatalogue', 'red'), ('PrintStockCatalogue', 'green'),
      ('PrintStockCatalogue', 'blue')]),
    ('endura-premier', 'EnduraPremierPaperSpectra',
     [('EnduraPremierPaperSpectra', 'redCurve'),
      ('EnduraPremierPaperSpectra', 'greenCurve'),
      ('EnduraPremierPaperSpectra', 'blueCurve')]),
    ('crystal-archive', 'CrystalArchivePaperSpectra',
     [('PrintStockCatalogue', 'green'), ('PrintStockCatalogue', 'green'),
      ('PrintStockCatalogue', 'green')]),
]


def write_pap1(path, dye, neutral, sens, triple):
    # The loader reads an 8-byte magic (PAP1 + four NULs); a 4-byte one shifts every float.
    buf = bytearray(b'PAP1\x00\x00\x00\x00')
    for layer in range(3):
        buf += struct.pack('<81f', *dye[layer * 81:(layer + 1) * 81])
    buf += struct.pack('<3f', *neutral)
    for layer in range(3):
        buf += struct.pack('<81f', *sens[layer * 81:(layer + 1) * 81])
    for params in triple:
        buf += struct.pack('<6f', *params)
    open(path, 'wb').write(buf)
    return len(buf)


for paper_id, module, names in PAPERS:
    dye, neutral, sens = spectra_arrays(module)
    triple = [curves[n] for n in names]
    path = os.path.join(OUT, 'paper_%s.bin' % paper_id.replace('-', '_'))
    n = write_pap1(path, dye, neutral, sens, triple)
    print('%-16s %-28s %d bytes  neutral=%s  red=%s' %
          (paper_id, module, n, ['%.6f' % x for x in neutral], ['%.4f' % x for x in triple[0]]))
