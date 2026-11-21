import math
import os
import re
import struct

# Every PrintStock the engine ships, as data: its spectra module, its three curves, its process
# family and (for release print film) the published Status A setup aims.
#
# Process: 0 photoPaper, 1 releasePrintFilm, 2 positivePaper, 3 negative.
# `sensitivityAboveMinimum` only means anything for a release print.

BASE = os.path.expanduser('~/spk-debug/fotufilm-engine-main/Sources/FotufilmCore')
OUT = os.path.expanduser('~/spk-debug/cpphost')

FLOAT = re.compile(r'-?\d+\.?\d*(?:[eE][-+]?\d+)?')


def strip_comments(text):
    return re.sub(r'//[^\n]*', '', text)


def read(name):
    return open(os.path.join(BASE, name), encoding='utf-8').read()


def bracket_chunk(src, marker):
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
    raise SystemExit('unbalanced at ' + marker)


def floats_of(chunk):
    return [float(x) for x in FLOAT.findall(strip_comments(chunk))]


def spectra_arrays(module):
    src = read(module + '.swift')
    dye = floats_of(bracket_chunk(src, 'static let dyeDensity: [[Float]] ='))
    # A release print draws its dyes at the amounts that already form a neutral,
    # so the field is absent there and the multiply is one.
    try:
        neutral = floats_of(bracket_chunk(src, 'static let neutralAmounts: [Float] ='))
    except ValueError:
        neutral = [1.0, 1.0, 1.0]
    sens = floats_of(bracket_chunk(src, 'static let layerSensitivity: [[Float]] ='))
    assert len(dye) == 243 and len(sens) == 243, (module, len(dye), len(sens))
    assert len(neutral) == 3, (module, len(neutral))
    return dye, neutral, sens


# Every `static let <name> = CharacteristicCurve(dMin: ..., ...)` in the catalogue files.
def curve_literals():
    found = {}
    for module in ('PrintStockCatalogue', 'PrintPaperSpectra', 'EnduraPremierPaperSpectra',
                   'CrystalArchivePaperSpectra', 'Vision2383PrintSpectra',
                   'Vision2393PrintSpectra', 'EternaCPPrintSpectra', 'EXR5386PrintSpectra',
                   'AgfaCP30PrintSpectra', 'EternaCP3521XDPrintSpectra'):
        try:
            src = read(module + '.swift')
        except FileNotFoundError:
            continue
        for m in re.finditer(r'static let (\w+)\s*=\s*CharacteristicCurve\(', src):
            tail = src[m.end():src.index(')', m.end())]
            vals = floats_of(tail)
            if len(vals) == 6:
                found[(module, m.group(1))] = vals
    return found


def ilfochrome(range_value, contrast):
    """IlfochromeCurves.curve: a symmetric toe/shoulder, gamma corrected for the centre slope."""
    gamma = contrast / math.tanh(2.5)
    span = range_value / gamma
    return [0.0, gamma, -span / 2.0, span / 10.0, span / 2.0, span / 10.0]


ILFO = {
    'ILFO_NORMAL': ilfochrome(2.0, 1.40),
    'ILFO_MEDIUM': ilfochrome(2.05, 1.15),
}

PAPERS = [
    # id, spectra module, [(module, literal) x3], process, ladStatusA, sensAboveMinimum
    ('ektacolor-edge', 'PrintPaperSpectra',
     [('PrintStockCatalogue', 'red'), ('PrintStockCatalogue', 'green'),
      ('PrintStockCatalogue', 'blue')], 0, None, 0),
    ('endura-premier', 'EnduraPremierPaperSpectra',
     [('EnduraPremierPaperSpectra', 'redCurve'),
      ('EnduraPremierPaperSpectra', 'greenCurve'),
      ('EnduraPremierPaperSpectra', 'blueCurve')], 0, None, 0),
    ('crystal-archive', 'CrystalArchivePaperSpectra',
     [('PrintStockCatalogue', 'green'), ('PrintStockCatalogue', 'green'),
      ('PrintStockCatalogue', 'green')], 0, None, 0),

    ('vision-2383', 'Vision2383PrintSpectra',
     [('Vision2383PrintSpectra', 'redCurve'), ('Vision2383PrintSpectra', 'greenCurve'),
      ('Vision2383PrintSpectra', 'blueCurve')], 1, (1.09, 1.06, 1.03), 0),
    ('vision-2393', 'Vision2393PrintSpectra',
     [('Vision2393PrintSpectra', 'redCurve'), ('Vision2393PrintSpectra', 'greenCurve'),
      ('Vision2393PrintSpectra', 'blueCurve')], 1, (1.09, 1.06, 1.03), 0),
    ('eterna-cp', 'EternaCPPrintSpectra',
     [('EternaCPPrintSpectra', 'cyanCurve'), ('EternaCPPrintSpectra', 'magentaCurve'),
      ('EternaCPPrintSpectra', 'yellowCurve')], 1, (1.10, 1.05, 1.05), 1),
    ('exr-5386', 'EXR5386PrintSpectra',
     [('EXR5386PrintSpectra', 'redCurve'), ('EXR5386PrintSpectra', 'greenCurve'),
      ('EXR5386PrintSpectra', 'blueCurve')], 1, (1.10, 1.06, 1.03), 0),
    ('agfa-cp30', 'AgfaCP30PrintSpectra',
     [('AgfaCP30PrintSpectra', 'redCurve'), ('AgfaCP30PrintSpectra', 'greenCurve'),
      ('AgfaCP30PrintSpectra', 'blueCurve')], 1, (1.15, 1.05, 1.05), 0),
    # 3521XD borrows 3513DI's spectra; only its own curves and setup aims are its own.
    ('eterna-cp-3521xd', 'EternaCPPrintSpectra',
     [('EternaCP3521XDPrintSpectra', 'redCurve'),
      ('EternaCP3521XDPrintSpectra', 'greenCurve'),
      ('EternaCP3521XDPrintSpectra', 'blueCurve')], 1, (1.10, 1.05, 1.05), 1),

    # Ilfochrome borrows Ektacolor Edge's spectra and draws its own symmetric curve.
    ('ilfochrome-cps1k', 'PrintPaperSpectra',
     [('ILFO', 'ILFO_NORMAL'), ('ILFO', 'ILFO_NORMAL'), ('ILFO', 'ILFO_NORMAL')], 2, None, 0),
    ('ilfochrome-clm1k', 'PrintPaperSpectra',
     [('ILFO', 'ILFO_MEDIUM'), ('ILFO', 'ILFO_MEDIUM'), ('ILFO', 'ILFO_MEDIUM')], 2, None, 0),
]

# PAP2: 8-byte magic, process, sensitivityAboveMinimum, ladStatusA (3), then the PAP1 payload.
HEADER = 8 + 4 + 4 + 12


def main():
    literals = curve_literals()
    print('curve literals:', len(literals))
    for paper in PAPERS:
        pid, module, curves, process, lad, above_min = paper
        dye, neutral, sens = spectra_arrays(module)
        params = []
        for cm, cl in curves:
            row = ILFO[cl] if cm == 'ILFO' else literals[(cm, cl)]
            params += row
        assert len(params) == 18, (pid, len(params))
        payload = struct.pack('<4s', b'PAP2') + struct.pack('<I', 0)
        payload += struct.pack('<II', process, above_min)
        payload += struct.pack('<3f', *(lad if lad else (0.0, 0.0, 0.0)))
        for layer in range(3):
            payload += struct.pack('<81f', *dye[layer * 81:(layer + 1) * 81])
        payload += struct.pack('<3f', *neutral)
        for layer in range(3):
            payload += struct.pack('<81f', *sens[layer * 81:(layer + 1) * 81])
        payload += struct.pack('<18f', *params)
        path = os.path.join(OUT, 'paper_%s.bin' % pid.replace('-', '_'))
        open(path, 'wb').write(payload)
        print('%-20s %-30s %5d bytes  process=%d lad=%s aboveMin=%d  neutral=%s' % (
            pid, module, len(payload), process, lad, above_min,
            ['%.4f' % v for v in neutral]))


main()
