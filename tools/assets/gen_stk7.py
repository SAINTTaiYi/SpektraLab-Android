import glob
import json
import os
import struct

# STK6 -> STK7: each characteristic curve gains an optional second component (a shoulder-like
# layer the sheet draws separately). 29 of the 47 stocks carry one; ignoring it makes the film
# table wrong wherever the second component contributes real density.
#
# Layout per curve becomes: 6 params + 6 second-component words (present flag, gamma, toe, toeWidth,
# shoulder, shoulderWidth) + u32 points + points * (logExposure, density). Donor curves keep their
# STK6 shape; nothing else moves.

BASE = os.path.expanduser('~/spk-debug/fotu/art/assets/fotufilm/Stocks')
SRC = os.path.expanduser('~/spk-debug/cpphost/stocks6.bin')
OUT = os.path.expanduser('~/spk-debug/cpphost/stocks7.bin')

data = open(SRC, 'rb').read()
assert data[:4] == b'STK6', data[:4]
off = 4
count = struct.unpack_from('<I', data, off)[0]
off += 4

stocks = []
for _ in range(count):
    ln = struct.unpack_from('<I', data, off)[0]
    off += 4
    sid = data[off:off + ln].decode()
    off += ln
    head = data[off:off + 4 * (1 + 243 + 243 + 81)]
    off += len(head)
    curves = []
    for _l in range(3):
        params = struct.unpack_from('<6f', data, off)
        off += 24
        pts = struct.unpack_from('<I', data, off)[0]
        off += 4
        body = data[off:off + 8 * pts]
        off += 8 * pts
        curves.append((params, pts, body))
    nd = struct.unpack_from('<I', data, off)[0]
    off += 4
    donors = []
    for _d in range(nd):
        nl = struct.unpack_from('<I', data, off)[0]
        off += 4
        name = data[off:off + nl]
        off += nl
        dhead = data[off:off + 4 * (81 + 6)]
        off += len(dhead)
        p2 = struct.unpack_from('<I', data, off)[0]
        off += 4
        dbody = data[off:off + 8 * p2]
        off += 8 * p2
        donors.append((name, dhead, p2, dbody))
    flags = struct.unpack_from('<3I', data, off)
    off += 12
    stocks.append((sid, head, curves, donors, flags))
assert off == len(data), (off, len(data))

secondary = {}
floors = {}
for path in glob.glob(os.path.join(BASE, '*.json')):
    d = json.load(open(path, encoding='utf-8'))
    sid = d.get('id') or os.path.basename(path)[:-5]
    secondary[sid] = [c.get('secondary') for c in d.get('curves', [])]
    if d.get('sensitivityFloor'):
        floors[sid] = float(d['sensitivityFloor'])


def floored(head, share):
    """FilmSpectralProfile.floored(at:): every sample sits at least `share` of its layer peak.

    A pack states this where the measured tails would otherwise read as a hard zero, and the
    difference is not cosmetic: provia 100F at 0.01 lifts the blue record's dead wavelengths to
    0.12 and moves its exposure table by 0.57 stops.
    """
    vals = list(struct.unpack('<243f', head[4:4 + 243 * 4]))
    for layer in range(3):
        peak = max(vals[layer * 81:(layer + 1) * 81])
        low = share * peak
        for i in range(layer * 81, (layer + 1) * 81):
            if vals[i] < low:
                vals[i] = low
    return head[:4] + struct.pack('<243f', *vals) + head[4 + 243 * 4:]

buf = bytearray(b'STK7')
buf += struct.pack('<I', len(stocks))
carried = 0
for sid, head, curves, donors, flags in stocks:
    buf += struct.pack('<I', len(sid)) + sid.encode()
    buf += floored(head, floors[sid]) if sid in floors else head
    rows = secondary.get(sid, [])
    for i, (params, pts, body) in enumerate(curves):
        buf += struct.pack('<6f', *params)
        s = rows[i] if i < len(rows) else None
        if s:
            buf += struct.pack('<6f', 1.0, s['gamma'], s['toe'], s['toeWidth'],
                               s['shoulder'], s['shoulderWidth'])
            carried += 1
        else:
            buf += struct.pack('<6f', 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        buf += struct.pack('<I', pts) + body
    buf += struct.pack('<I', len(donors))
    for name, dhead, p2, dbody in donors:
        buf += struct.pack('<I', len(name)) + name + dhead
        buf += struct.pack('<I', p2) + dbody
    buf += struct.pack('<3I', *flags)

open(OUT, 'wb').write(buf)
print('wrote %s  %d bytes (STK6 was %d)  secondary components carried: %d'
      % (OUT, len(buf), len(data), carried))
