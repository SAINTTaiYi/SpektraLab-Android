import json, os, subprocess, sys
RES = os.path.expanduser('~/SpektraLab-apk/app/build/apk/assets/resources')
LIB = os.path.expanduser('~/spk-debug/p444/lib/arm64-v8a')
ENV = dict(os.environ, LD_LIBRARY_PATH=LIB + ':/system/lib64:' + os.environ.get('PREFIX', '/data/data/com.termux/files/usr') + '/lib')

CASES = [
    ('135 landscape', 3000, 2000, '135', '',
     ['--edge-text', 'FUJIFILM C200', '--frame-number', '2']),
    ('135 portrait', 2000, 3000, '135', '',
     ['--edge-text', 'FUJIFILM C200', '--frame-number', '2']),
    ('135 half', 3000, 2250, '135_half', '',
     ['--edge-text', 'FUJIFILM C200', '--frame-number', '2']),
    ('120 6x6', 2000, 2000, '120_6x6', '',
     ['--edge-text', 'FUJIFILM C200', '--frame-number', '2']),
    ('120 6x7', 2400, 1934, '120_6x7', '',
     ['--edge-text', 'FUJIFILM C200', '--frame-number', '2']),
    ('135 leaks+seeds', 3000, 2000, '135',
     ',"overscan_leaks":1.0,"overscan_camera_seed":7,"overscan_frame_seed":9',
     ['--edge-text', 'FUJIFILM C200', '--frame-number', '2', '--leaks', '1',
      '--camera-seed', '7', '--frame-seed', '9']),
]

def engine(w, h, gate, extra):
    out = subprocess.run(['./geom_probe', RES, str(w), str(h), gate, extra, '36'],
                         capture_output=True, text=True, env=ENV, timeout=900).stdout
    for line in out.splitlines():
        if line.startswith('{'):
            return json.loads(line)
    raise SystemExit('engine said: ' + out)

def mine(w, h, gate, args):
    out = subprocess.run(['./rebate_dump', '--frame', '%dx%d' % (w, h), '--format', gate,
                          '--stock', 'fujifilm_c200'] + args,
                         capture_output=True, text=True, timeout=900).stdout
    return json.loads(out.splitlines()[0])

KEYS = ['canvas_w', 'canvas_h', 'mm_per_px', 'vertical', 'format', 'gate_mm',
        'penumbra_mm', 'gates', 'mark_ops', 'mark_text_ops', 'mark_box']

def same(a, b):
    if isinstance(a, float) or isinstance(b, float):
        return abs(float(a) - float(b)) <= 1e-9 * max(1.0, abs(float(a)))
    if isinstance(a, list):
        return len(a) == len(b) and all(same(x, y) for x, y in zip(a, b))
    return a == b

bad = 0
for name, w, h, gate, extra, args in CASES:
    e = engine(w, h, gate, extra)
    m = mine(w, h, gate, args)
    diffs = [k for k in KEYS if k in e and not same(e.get(k), m.get(k))]
    ok = not diffs
    bad += 0 if ok else 1
    print('%-16s %s  canvas %sx%s  ops %s/%s  text %s/%s%s' % (
        name, 'MATCH' if ok else 'DIFF ', e.get('canvas_w'), e.get('canvas_h'),
        m.get('mark_ops'), e.get('mark_ops'), m.get('mark_text_ops'), e.get('mark_text_ops'),
        '' if ok else '   differs: ' + ', '.join(diffs)))
sys.exit(1 if bad else 0)
