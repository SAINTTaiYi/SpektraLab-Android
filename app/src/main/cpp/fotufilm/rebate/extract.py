import re, sys
src = open('overscan.cpp', encoding='utf-8').read().split('\n')

def find(sub, start=0, end=None):
    end = len(src) if end is None else end
    for i in range(start, end):
        if sub in src[i]:
            return i
    raise SystemExit('not found: ' + sub)

# --- slice the four pieces -------------------------------------------------------
a0 = 43                                    # line 44: "namespace {"
assert src[a0].strip() == 'namespace {', src[a0]
a1 = find('}  // namespace', a0, 520)      # closes the types/gate_shape block
A = src[a0:a1 + 1]

b0 = find('bool Pipeline::overscan_wanted() const {')
c0 = None
for i in range(b0, len(src)):
    if src[i].strip() == 'namespace {' and i + 2 < len(src) \
       and src[i + 2].startswith('void film_to_canvas_px'):
        c0 = i
        break
assert c0, 'the imprint namespace'
B = src[b0:c0]

d0 = find('std::string Pipeline::overscan_geometry_json() const {')
C = src[c0:d0]

n0 = find('// The nodes.')
D = src[d0:n0]
while D and (D[-1].strip() == '' or D[-1].startswith('// ---')):
    D.pop()
print('A %d  B %d  C %d  D %d  total %d' % (len(A), len(B), len(C), len(D), len(A)+len(B)+len(C)+len(D)))

# --- drop what needs the engine's spectral model --------------------------------
# light_weights(Colour, Blob, Vec, ...) -> the three colours are inputs here.
lw0 = None
for i, line in enumerate(A):
    if line.startswith('void light_weights(const Colour& colour, const Blob& blob'):
        lw0 = i
        break
assert lw0 is not None
lw1 = lw0
depth = 0
while True:
    depth += A[lw1].count('{') - A[lw1].count('}')
    if lw1 > lw0 and depth == 0:
        break
    lw1 += 1
STUB = [
    '// The engine derives these three colours from the film\'s edge sensitivity and',
    '// a black body through the scan\'s illuminant. Here they are inputs: stage 2',
    '// bakes them per stock from the engine itself, so the numbers stay the engine\'s.',
    'void light_weights(const Ctx& ctx, double kelvin, bool guide, const double t_layer[3],',
    '                   double out[3]) {',
    '    (void)guide;',
    '    const double* rgb = kelvin == 2700.0 ? ctx.date_rgb',
    '                       : (kelvin == 4500.0 ? ctx.edge_rgb : ctx.fog_rgb);',
    '    double m = 0.0;',
    '    for (int c = 0; c < 3; ++c) { out[c] = t_layer[c] * rgb[c]; m = std::max(m, out[c]); }',
    '    for (int c = 0; c < 3; ++c) out[c] = m > 0 ? out[c] / m : 0.0;',
    '}',
]
A = A[:lw0] + STUB + A[lw1 + 1:]

text = '\n'.join(A + B + C + D)

# Three member functions only read the engine's own state: the crop bookkeeping
# that asks for a canvas size, and the two accessors onto the pipeline's layout.
# The host drives the layout explicitly, so they go.
def drop_member(text, sig):
    f0 = text.index(sig)
    m = re.compile(r'\n(?:bool|void|double|std::string) Pipeline::').search(text, f0 + len(sig))
    f1 = m.start() if m else len(text)
    return (text[:f0] + text[f1 + 1:]).replace('\n\n\n', '\n\n')

for sig in ('bool Pipeline::full_output_size(', 'void Pipeline::overscan_frame(',
            'bool Pipeline::set_overscan_frame('):
    text = drop_member(text, sig)


# --- the six methods become free functions --------------------------------------
SIGS = [
    ('bool Pipeline::overscan_wanted() const {',
     'bool wanted(const Ctx& ctx) {'),
    ('double Pipeline::overscan_gate_long_mm() const {',
     'double gate_long_mm(const Ctx& ctx) {'),


    ('bool Pipeline::overscan_layout(uint32_t frame_w, uint32_t frame_h, std::string& error) {',
     'bool layout(Ctx& ctx, uint32_t frame_w, uint32_t frame_h, OverscanLayout& OL,\n            std::string& error) {'),
    ('void Pipeline::overscan_params_block(std::vector<float>& P) const {',
     'void params_block(const Ctx& ctx, const OverscanLayout& OL, std::vector<float>& P) {'),
    ('std::string Pipeline::overscan_geometry_json() const {',
     'std::string geometry_json(const Ctx& ctx, const OverscanLayout& OL) {'),
]
for old, new in SIGS:
    assert text.count(old) == 1, old
    text = text.replace(old, new)

# --- the base's colour is a baked input, like the three edge colours -----------
bs = None
for i, line in enumerate(text.split('\n')):
    if 'const Profile& scanned = params_.io.scan_film' in line:
        bs = i
        break
lines = text.split('\n')
assert bs is not None
be = bs
# exactly eight spaces: the inner `for` bodies close at twelve.
while lines[be] != '        }':
    be += 1
STUB_BASE = [
    '        // The base\'s own colour is a baked input here (`Ctx::base_rgb`): the',
    '        // engine derives it from the film\'s base density through the scan\'s',
    '        // illuminant, which needs the spectral blob. Stage 2 bakes it per stock.',
    '        for (int k = 0; k < 3; ++k) L.base_rgb[k] = std::clamp(ctx.base_rgb[k], 0.0, 1.0);',
]
lines = lines[:bs] + STUB_BASE + lines[be + 1:]
text = '\n'.join(lines)

# The striped-executor refusal is the engine's own; this module has no executor.
text = re.sub(r'    if \(params_\.settings\.striped\) \{.*?\n    \}\n', '', text, flags=re.S)

# --- parameters: the engine's struct chain -> this module's Ctx ----------------
RENAMES = [
    ('params_.film_render.overscan', 'ctx.os'),
    ('params.film_render.overscan', 'ctx.os'),
    ('params_.film_render.date_imprint', 'ctx.di'),
    ('params.film_render.date_imprint', 'ctx.di'),
    ('params_.film.info.stock', 'ctx.stock'),
    ('params.film.info.stock', 'ctx.stock'),
    ('params_.film.info.reference_illuminant', 'ctx.reference_illuminant'),
    ('params.film.info.reference_illuminant', 'ctx.reference_illuminant'),
    ('params_.camera.film_format_mm', 'ctx.film_format_mm'),
    ('light_weights(*colour_, *blob_, film_sensitivity_edge_, ref, ', 'light_weights(ctx, '),
    ('const Vec& chd = params_.film.data.channel_density;',
     'const std::vector<double>& chd = ctx.channel_density;'),
    ('const Vec& base = params_.film.data.base_density;',
     'const std::vector<double>& base = ctx.base_density;'),
    ('film_sensitivity_edge_', 'ctx.edge_sens'),
    ('pixel_size_um_', 'ctx.px_um'),
    ('overscan_mark_text_ops_', 'ctx.marks.text'),
    ('overscan_mark_ops_', 'ctx.marks.ops'),
    ('overscan_mark_box_', 'ctx.marks.box'),
    ('overscan_wanted()', 'wanted(ctx)'),
    ('overscan_gate_long_mm()', 'gate_long_mm(ctx)'),
    ('overscan_frame(frame_w, frame_h)', 'frame_px(ctx, frame_w, frame_h)'),
    ('Params pk = params_;', 'Ctx pk = ctx;'),
    ('Params pb_ = params_;', 'Ctx pb_ = ctx;'),
    ('const Params& params', 'const Ctx& ctx'),
    ('overscan_', 'OL'),
    ('const uint64_t limit = gpu_ ? gpu_->max_storage_buffer_range() : 0;',
     'const uint64_t limit = ctx.max_binding_bytes;\n        (void)limit;'),
    ('const uint64_t heap = gpu_ ? gpu_->heap_device_local() : 0;',
     'const uint64_t heap = ctx.heap_bytes;'),
]
for old, new in RENAMES:
    if old == 'overscan_':
        # `overscan_layout`, `overscan_wanted`, ... share the prefix.
        text = re.sub(r'overscan_(?!layout|wanted|frame|gate|mark|params|geometry|band|params_)', 'OL', text)
    else:
        text = text.replace(old, new)

# "params." must be gone entirely, or something still reaches the engine's tree.
left = [l for l in text.split('\n') if re.search(r'\bparams\b|\bParams\b|Pipeline::|\bcolour_\b|\bblob_\b|\bcolor_', l)]
if left:
    print('LEFTOVERS:')
    for l in left[:20]:
        print('   ', l[:150])
    sys.exit(1)



HEAD = r'''// The film outside the frame: geometry, the imprint, the masks.
//
// GENERATED from the first engine's `overscan.cpp` by `extract.py` -- do not
// hand-edit. 2,551 of that file's 2,984 lines are plain CPU code with no GPU
// anywhere in them; this is those lines, with the engine's `Pipeline` members
// replaced by the `Ctx` in `rebate.hpp` and the three spectral colours taken as
// inputs. The four composite kernels stay in the shader and are transcribed
// separately; nothing here needs a device.
//
// Re-running extract.py after an engine change is the way to pick its fixes up.
#include "rebate.hpp"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <cstdlib>

#include "imprint_raster.hpp"

#define SPK_MAX_CANVAS_PIXELS 84000000ull

namespace rebate {

// The bundled-outline rasteriser is the engine's (`imprint_raster.hpp`), shared
// verbatim -- the same function the Apple and Vulkan builds both call.
namespace imprint = spk::imprint;

'''

TAIL = r'''
// ---------------------------------------------------------------------------
// The masks, placed: what the composite needs to draw the imprint.
// ---------------------------------------------------------------------------
std::vector<Mask> masks(Ctx& ctx, const OverscanLayout& OL, std::string& error) {
    std::vector<Mask> out;
    ctx.marks = Ctx::Marks{};
    if (!OL.valid) { error = "overscan: no layout"; return out; }
    std::vector<Group> groups;
    imprint_groups(OL, ctx, ctx.frame_w_mm, ctx.frame_h_mm, groups);
    // A pair's second frame was its own exposure and carries its own date: the
    // same back, one advance along, laid out against the second gate.
    if (OL.pair_adv > 0.0 && ctx.di.active && !ctx.di.text_b.empty()) {
        OverscanLayout second = OL;
        second.gate_s0 += second.pair_adv;
        Ctx without = ctx;
        without.di.active = false;
        Ctx with = ctx;
        with.di.text = ctx.di.text_b;
        with.di.data_text.clear();       // the data line is the first frame's
        std::vector<Group> a, b;
        imprint_groups(second, without, ctx.frame_w_mm, ctx.frame_h_mm, a);
        imprint_groups(second, with, ctx.frame_w_mm, ctx.frame_h_mm, b);
        if (b.size() > a.size()) groups.push_back(b.back());
    }
    if (std::getenv("REBATE_DEBUG")) {
        for (size_t gi = 0; gi < groups.size(); ++gi) {
            const Group& g = groups[gi];
            double s0 = 1e30, s1 = -1e30, t0 = 1e30, t1 = -1e30;
            int kinds[3] = {0, 0, 0};
            for (const Op& op : g.ops) {
                ++kinds[int(op.kind)];
                if (op.kind == Op::Text) {
                    s0 = std::min(s0, op.s); s1 = std::max(s1, op.s + 40.0);
                    t0 = std::min(t0, op.t - 3.0); t1 = std::max(t1, op.t + 3.0);
                } else {
                    for (size_t k = 0; k + 1 < op.pts.size(); k += 2) {
                        s0 = std::min(s0, op.pts[k]); s1 = std::max(s1, op.pts[k]);
                        t0 = std::min(t0, op.pts[k + 1]); t1 = std::max(t1, op.pts[k + 1]);
                    }
                }
            }
            std::fprintf(stderr, "group %zu: %zu ops (text %d poly %d circle %d)  film s %.2f..%.2f  t %.2f..%.2f  exp %.3f\n",
                         gi, g.ops.size(), kinds[0], kinds[1], kinds[2], s0, s1, t0, t1, g.exposure[0]);
            if (std::getenv("REBATE_DEBUG")[0] == '2') {
                for (size_t oi = 0; oi < g.ops.size() && oi < 24; ++oi) {
                    const Op& op = g.ops[oi];
                    double px = 0, py = 0;
                    film_to_canvas_px(OL, op.s, op.t, px, py);
                    std::fprintf(stderr, "   op %2zu kind %d s %8.2f t %6.2f size %4.2f canvas (%.0f,%.0f) '%s'\n",
                                 oi, int(op.kind), op.s, op.t, op.size_mm, px, py, op.text.c_str());
                }
            }
        }
    }
    for (size_t gi = 0; gi < groups.size(); ++gi) {
        const Group& g = groups[gi];
        Mask m;
        if (!rasterise(OL, g, m.cov, m.x0, m.y0, m.w, m.h)) continue;
        for (const Op& op : g.ops) {
            ++ctx.marks.ops;
            if (op.kind == Op::Text) ++ctx.marks.text;
        }
        if (ctx.marks.box[2] <= ctx.marks.box[0]) {
            ctx.marks.box[0] = m.x0; ctx.marks.box[1] = m.y0;
            ctx.marks.box[2] = m.x0 + m.w; ctx.marks.box[3] = m.y0 + m.h;
        } else {
            ctx.marks.box[0] = std::min(ctx.marks.box[0], m.x0);
            ctx.marks.box[1] = std::min(ctx.marks.box[1], m.y0);
            ctx.marks.box[2] = std::max(ctx.marks.box[2], m.x0 + m.w);
            ctx.marks.box[3] = std::max(ctx.marks.box[3], m.y0 + m.h);
        }
        m.exposure[0] = g.exposure[0];
        m.exposure[1] = g.exposure[1];
        m.exposure[2] = g.exposure[2];
        out.push_back(std::move(m));
    }
    return out;
}

}  // namespace rebate
'''

open('rebate.cpp', 'w', encoding='utf-8').write(HEAD + text + TAIL)
print('wrote rebate.cpp', len(HEAD + text + TAIL))
