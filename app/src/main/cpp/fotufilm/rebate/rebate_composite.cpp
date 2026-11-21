// The four composites, transcribed from `spk_overscan_body.glsl`.
//
// The shader and this file must stay line-for-line equivalent: same `P` block
// (indices in `enum OsP`, built by `params_block`), same float arithmetic, same
// order of operations. Anything that reads differently here is a difference in
// the picture, and there is no device in the loop to hide it.
//
//   ① canvas       the frame, sampled through the scan's registration, plus the
//                  gate's penumbra, the gate's flare, edge fog and the leaks
//   ② add_mask     a rasterised imprint (edge print, date) as extra exposure
//   ③ film_present where there is no film, the density goes to the cmy that
//                  cancels this film's base -- the scan sees clear light
//   ④ light        the scan's view of the perforations: the cut wall, its
//                  shoulder, a burr's flare, and the carrier past the strip
#include "rebate.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <thread>
#include <vector>

namespace rebate {

namespace {

// --- the parameter indices, copied from `overscan.cpp` (`enum OsP`) -----------
enum : int {
    P_CW = 0, P_CH, P_PX, P_FW, P_FH,
    P_A00, P_A01, P_A10, P_A11, P_CS, P_CT, P_CU, P_CV,
    P_VERTICAL,
    P_GS0, P_GT0, P_GW, P_GH, P_CORNER, P_PENUMBRA,
    P_WOB_A = 20, P_WOB_PH = 32,
    P_FILM_W = 44, P_FOG_AMP, P_FOG_WIDTH, P_FOG_SEED, P_FOG_PERIOD,
    P_FOG_R, P_FOG_G, P_FOG_B,
    P_N_LEAKS = 52,
    P_PERFORATED = 83, P_PERF_PITCH, P_PERF_W, P_PERF_H, P_PERF_EDGE, P_PERF_R, P_PERF_PHASE,
    P_HOLE_C = 90, P_HOLE_M, P_HOLE_Y,
    P_FLARE_AMP = 93, P_FLARE_WIDTH,
    P_GATE_R = 95, P_N_Q = 99, P_Q = 100,
    P_ROUGH_AMP = 180, P_ROUGH_PERIOD, P_ROUGH_SEED,
    P_HOLES_LIGHT = 183, P_LIGHT_R, P_LIGHT_G, P_LIGHT_B,
    P_PERF_SEED = 187, P_LIGHT_FALL, P_LIGHT_DIR,
    P_WALL_T = 190, P_WALL_VS, P_WALL_VT, P_WALL_PAR, P_BASE_R, P_BASE_G, P_BASE_B,
    P_WALL_GLOW, P_WALL_SCATTER,
    P_CARRIER = 199,
    P_LEAKS = 200,
    P_PAIR_ADV = 260,
    P_COUNT = 261
};
constexpr int kQStride = 10;
constexpr float kPi = 3.14159265358979323846f;

// Rows are independent -- every kernel reads only its own row's pixel and the
// shared parameters -- so threading them changes no value, only the clock.
// (The engine gets this from the GPU; a CPU host has to ask.)
template <class Fn>
void parallel_rows(int rows, Fn&& fn) {
    unsigned n = std::thread::hardware_concurrency();
    if (n == 0) n = 1;
    if (n > 8) n = 8;
    if (rows < 32 || n == 1) {
        for (int y = 0; y < rows; ++y) fn(y);
        return;
    }
    std::vector<std::thread> pool;
    pool.reserve(n);
    for (unsigned k = 0; k < n; ++k) {
        pool.emplace_back([&fn, k, n, rows] {
            for (int y = int(k); y < rows; y += int(n)) fn(y);
        });
    }
    for (std::thread& t : pool) t.join();
}

struct V2 { float x, y; };
struct V3 { float x, y, z; };

V2 operator-(V2 a, V2 b) { return {a.x - b.x, a.y - b.y}; }
V2 operator+(V2 a, V2 b) { return {a.x + b.x, a.y + b.y}; }
V2 operator*(V2 a, float s) { return {a.x * s, a.y * s}; }
float dot(V2 a, V2 b) { return a.x * b.x + a.y * b.y; }
float length(V2 a) { return std::sqrt(dot(a, a)); }
V3 operator+(V3 a, V3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
V3 operator*(V3 a, float s) { return {a.x * s, a.y * s, a.z * s}; }
V3 mul3(V3 a, V3 b) { return {a.x * b.x, a.y * b.y, a.z * b.z}; }
V3 mix3(V3 a, V3 b, float t) {
    return {a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t};
}
float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
float maxf(float a, float b) { return a > b ? a : b; }
float minf(float a, float b) { return a < b ? a : b; }
float mixf(float a, float b, float t) { return a + (b - a) * t; }

uint32_t hash_u(uint32_t x) {
    x ^= x >> 16; x *= 0x7feb352du; x ^= x >> 15; x *= 0x846ca68bu; x ^= x >> 16;
    return x;
}
float hash_f(int i, uint32_t seed) {
    return float(hash_u(uint32_t(i) * 0x9E3779B9u ^ seed)) * (1.0f / 4294967295.0f);
}
float value_noise(float x, uint32_t seed) {
    const float i0 = std::floor(x), f = x - i0;
    const float w = f * f * (3.0f - 2.0f * f);
    return mixf(hash_f(int(i0), seed), hash_f(int(i0) + 1, seed), w);
}

// Signed distance to a convex quad, negative inside.
float sd_quad(V2 p, const std::vector<float>& P, int qbase) {
    V2 v[4] = {{P[qbase + 0], P[qbase + 1]}, {P[qbase + 2], P[qbase + 3]},
               {P[qbase + 4], P[qbase + 5]}, {P[qbase + 6], P[qbase + 7]}};
    float d = dot(p - v[0], p - v[0]);
    float sgn = 1.0f;
    for (uint32_t i = 0, j = 3; i < 4; j = i, ++i) {
        const V2 e = v[j] - v[i], w = p - v[i];
        const float ee = dot(e, e);
        if (ee < 1e-12f) continue;
        const V2 b = w - e * clampf(dot(w, e) / ee, 0.0f, 1.0f);
        d = minf(d, dot(b, b));
        const bool c0 = p.y >= v[i].y, c1 = p.y < v[j].y, c2 = e.x * w.y > e.y * w.x;
        if ((c0 && c1 && c2) || (!c0 && !c1 && !c2)) sgn = -sgn;
    }
    return sgn * std::sqrt(d);
}

float gate_sdf(V2 st, const std::vector<float>& P) {
    const float hw = 0.5f * P[P_GW], hh = 0.5f * P[P_GH];
    const float ds = st.x - (P[P_GS0] + hw), dt = st.y - (P[P_GT0] + hh);
    const float r = ds > 0.0f ? (dt > 0.0f ? P[P_GATE_R + 2] : P[P_GATE_R + 1])
                              : (dt > 0.0f ? P[P_GATE_R + 3] : P[P_GATE_R + 0]);
    const float qs = std::fabs(ds) - (hw - r), qt = std::fabs(dt) - (hh - r);
    float d = length({qs > 0 ? qs : 0.0f, qt > 0 ? qt : 0.0f}) + minf(maxf(qs, qt), 0.0f) - r;
    uint32_t side; float along, len;
    if (qs > qt) { side = ds > 0.0f ? 1u : 3u; along = dt + hh; len = 2.0f * hh; }
    else         { side = dt > 0.0f ? 2u : 0u; along = ds + hw; len = 2.0f * hw; }
    const float u = clampf(along / len, 0.0f, 1.0f);
    float w = 0.0f;
    for (uint32_t k = 0; k < 3; ++k)
        w += P[P_WOB_A + side * 3 + k] * std::sin(float(k + 1) * kPi * u + P[P_WOB_PH + side * 3 + k]);
    d -= w * std::sin(kPi * u);
    const uint32_t nq = uint32_t(P[P_N_Q]);
    for (uint32_t k = 0; k < nq; ++k) {
        const int qbase = P_Q + kQStride * int(k);
        float dq;
        if (std::fabs(P[qbase + 9]) > 1.5f) {
            const float rx = maxf(P[qbase + 2], 1e-4f), ry = maxf(P[qbase + 3], 1e-4f);
            dq = (length({(st.x - P[qbase + 0]) / rx, (st.y - P[qbase + 1]) / ry}) - 1.0f) *
                 minf(rx, ry);
        } else {
            dq = sd_quad(st, P, qbase) - P[qbase + 8];
        }
        d = P[qbase + 9] > 0.0f ? minf(d, dq) : maxf(d, -dq);
    }
    if (P[P_ROUGH_AMP] > 0.0f) {
        const uint32_t seed = uint32_t(P[P_ROUGH_SEED]);
        const float per = maxf(P[P_ROUGH_PERIOD], 1e-3f);
        d += P[P_ROUGH_AMP] * (value_noise(st.x / per, seed) +
                               value_noise(st.y / per, seed ^ 0x5bd1e995u) - 1.0f);
    }
    return d;
}

float penumbra(float d, float r) {
    if (r <= 1e-6f) return d < 0.0f ? 1.0f : 0.0f;
    const float u = clampf(-d / r, -1.0f, 1.0f);
    return 0.5f + (u * std::sqrt(maxf(1.0f - u * u, 0.0f)) + std::asin(u)) * (1.0f / kPi);
}

V2 canvas_to_film(float x, float y, const std::vector<float>& P) {
    const float u = (x + 0.5f) * P[P_PX] - P[P_CU];
    const float v = (y + 0.5f) * P[P_PX] - P[P_CV];
    return {P[P_A00] * u + P[P_A01] * v + P[P_CS], P[P_A10] * u + P[P_A11] * v + P[P_CT]};
}

V3 sample_frame(float fx, float fy, int fw, int fh, const float* src, int stride) {
    fx = clampf(fx, 0.0f, float(fw) - 1.0f);
    fy = clampf(fy, 0.0f, float(fh) - 1.0f);
    const int x0 = int(std::floor(fx)), y0 = int(std::floor(fy));
    const int x1 = std::min(x0 + 1, fw - 1), y1 = std::min(y0 + 1, fh - 1);
    const float ax = fx - float(x0), ay = fy - float(y0);
    const auto at = [&](int x, int y) {
        const size_t i = size_t(stride) * (size_t(y) * size_t(fw) + size_t(x));
        return V3{src[i], src[i + 1], src[i + 2]};
    };
    const V3 a = at(x0, y0), b = at(x1, y0), c = at(x0, y1), d = at(x1, y1);
    return mix3(mix3(a, b, ax), mix3(c, d, ax), ay);
}

// The nearest perforation's signed distance (mm, negative inside).
float perf_sdf(V2 st, uint32_t& hole, const std::vector<float>& P) {
    const float W = P[P_FILM_W];
    const float pitch = P[P_PERF_PITCH], ph = P[P_PERF_PHASE];
    const float k = std::floor((st.x - ph - 0.5f * P[P_PERF_W]) / pitch + 0.5f);
    const bool top = st.y < 0.5f * W;
    const uint32_t seed = uint32_t(P[P_PERF_SEED]);
    hole = hash_u(uint32_t(int(k) * 2 + (top ? 0 : 1)) * 0x9E3779B9u ^ seed);
    const int h = int(hole & 0xFFFFu);
    const float j0 = hash_f(h, seed ^ 0x1u) - 0.5f, j1 = hash_f(h, seed ^ 0x2u) - 0.5f;
    const float j2 = hash_f(h, seed ^ 0x3u) - 0.5f, j3 = hash_f(h, seed ^ 0x4u) - 0.5f;
    const float j4 = hash_f(h, seed ^ 0x5u) - 0.5f;
    const float wa = P[P_PERF_W] + 0.016f * j2, hc = P[P_PERF_H] + 0.016f * j3;
    const float r = clampf(P[P_PERF_R] + 0.08f * j4, 0.3f, 0.6f);
    const float sc = ph + k * pitch + 0.5f * P[P_PERF_W] + 0.020f * j0;
    const float tc = (top ? P[P_PERF_EDGE] + 0.5f * P[P_PERF_H]
                          : W - P[P_PERF_EDGE] - 0.5f * P[P_PERF_H]) + 0.024f * j1;
    const float qx = std::fabs(st.x - sc) - (0.5f * wa - r);
    const float qy = std::fabs(st.y - tc) - (0.5f * hc - r);
    float d = length({qx > 0 ? qx : 0.0f, qy > 0 ? qy : 0.0f}) + minf(maxf(qx, qy), 0.0f) - r;
    const float rough = 0.001f + 0.003f * hash_f(h, seed ^ 0x6u);
    d += rough * (value_noise(st.x / 0.05f, seed ^ 0x77u) +
                  value_noise(st.y / 0.05f, seed ^ 0x99u) - 1.0f) * 2.0f;
    if (hash_f(h, seed ^ 0x7u) < 0.25f) {
        const float ang = 6.2831853f * hash_f(h, seed ^ 0x8u);
        const V2 pb{sc + std::cos(ang) * 0.5f * wa, tc + std::sin(ang) * 0.5f * hc};
        const float sig = 0.020f + 0.030f * hash_f(h, seed ^ 0x9u);
        const float amp = (hash_f(h, seed ^ 0xAu) < 0.5f ? -1.0f : 1.0f) *
                          (0.006f + 0.010f * hash_f(h, seed ^ 0xBu));
        d += amp * std::exp(-dot(st - pb, st - pb) / (sig * sig));
    }
    return d;
}

constexpr float kHoleEdgeSigma = 0.017f;

float erf_as(float x) {
    const float t = 1.0f / (1.0f + 0.3275911f * std::fabs(x));
    const float y = 1.0f - (((((1.061405429f * t - 1.453152027f) * t) + 1.421413741f) * t -
                             0.284496736f) * t + 0.254829592f) * t * std::exp(-x * x);
    return x < 0.0f ? -y : y;
}

float film_alpha(V2 st, const std::vector<float>& P) {
    const float W = P[P_FILM_W], px = P[P_PX];
    float a = P[P_CARRIER] > 1.5f ? 1.0f
                                  : clampf(minf(st.y, W - st.y) / px + 0.5f, 0.0f, 1.0f);
    if (P[P_PERFORATED] > 0.5f) {
        uint32_t hole = 0;
        const float sigma = std::sqrt(kHoleEdgeSigma * kHoleEdgeSigma + 0.0961f * px * px);
        a *= 0.5f + 0.5f * erf_as(perf_sdf(st, hole, P) / (1.41421356f * sigma));
    }
    return a;
}

}  // namespace

// --- ① the canvas ------------------------------------------------------------
bool canvas_pass(const std::vector<float>& P, const float* frame, int fw, int fh,
                 int stride, Canvas& out, std::vector<uint8_t>* gate,
                 std::vector<uint8_t>* film) {
    if (P.size() < size_t(P_COUNT)) return false;
    const int cw = int(P[P_CW]), ch = int(P[P_CH]);
    if (cw <= 0 || ch <= 0 || fw <= 0 || fh <= 0 || !frame) return false;
    out.w = cw;
    out.h = ch;
    out.rgb.assign(size_t(cw) * size_t(ch) * 3, 0.0f);
    if (gate) gate->assign(size_t(cw) * size_t(ch), 0);
    if (film) film->assign(size_t(cw) * size_t(ch), 0);
    const float W = P[P_FILM_W];
    const float px = P[P_PX];
    parallel_rows(ch, [&](int y) {
        for (int x = 0; x < cw; ++x) {
            const V2 st = canvas_to_film(float(x), float(y), P);
            V3 e{0, 0, 0};
            if (P[P_CARRIER] > 1.5f || (st.y >= 0.0f && st.y <= W)) {
                V2 sg = st;
                float shift = 0.0f;
                if (P[P_PAIR_ADV] > 0.0f &&
                    st.x > P[P_GS0] + 0.5f * (P[P_GW] + P[P_PAIR_ADV])) {
                    shift = P[P_PAIR_ADV];
                    sg.x -= shift;
                }
                // (the shader calls `gate_sdf` twice with the same argument --
                // once for the penumbra, once for the flare; one call here.)
                const float d_gate = gate_sdf(sg, P);
                const float cov = penumbra(d_gate, P[P_PENUMBRA]);
                if (gate) (*gate)[size_t(y) * size_t(cw) + size_t(x)] =
                    uint8_t(clampf(cov, 0.0f, 1.0f) * 255.0f + 0.5f);
                float sx = 0.0f, sy = 0.0f;
                if (cov > 0.0f) {
                    if (P[P_VERTICAL] < 0.5f) {
                        sx = (st.x - P[P_GS0]) / px - 0.5f;
                        sy = (st.y - P[P_GT0]) / px - 0.5f;
                    } else {
                        sx = (P[P_GT0] + P[P_GH] - st.y) / px - 0.5f;
                        sy = (st.x - P[P_GS0]) / px - 0.5f;
                    }
                    e = e + sample_frame(sx, sy, fw, fh, frame, stride) * cov;
                }
                if (P[P_FLARE_AMP] > 0.0f && d_gate > -0.02f &&
                    d_gate < 6.0f * P[P_FLARE_WIDTH]) {
                    const float in_s = clampf(sg.x, P[P_GS0] + 0.12f,
                                              P[P_GS0] + P[P_GW] - 0.12f) + shift;
                    const float in_t = clampf(st.y, P[P_GT0] + 0.12f,
                                              P[P_GT0] + P[P_GH] - 0.12f);
                    float ex, ey;
                    if (P[P_VERTICAL] < 0.5f) {
                        ex = (in_s - P[P_GS0]) / px - 0.5f;
                        ey = (in_t - P[P_GT0]) / px - 0.5f;
                    } else {
                        ex = (P[P_GT0] + P[P_GH] - in_t) / px - 0.5f;
                        ey = (in_s - P[P_GS0]) / px - 0.5f;
                    }
                    const V3 edge_light = sample_frame(ex, ey, fw, fh, frame, stride);
                    const float prof = std::exp(-maxf(d_gate, 0.0f) / P[P_FLARE_WIDTH]);
                    e = e + edge_light * (P[P_FLARE_AMP] * prof);
                }
                const float d_top = maxf(st.y, 0.0f), d_bot = maxf(W - st.y, 0.0f);
                const uint32_t nl = uint32_t(P[P_N_LEAKS]);
                float fog = 0.0f;
                if (P[P_FOG_AMP] > 0.0f) {
                    const uint32_t seed = uint32_t(P[P_FOG_SEED]);
                    const float period = maxf(P[P_FOG_PERIOD], 0.1f);
                    const float nz = 0.6f * value_noise(st.x / period, seed) +
                                     0.4f * value_noise(st.x / (0.37f * period), seed ^ 0xA5A5A5A5u);
                    fog = P[P_FOG_AMP] *
                          std::exp(-minf(d_top, d_bot) / maxf(P[P_FOG_WIDTH], 1e-3f)) *
                          (0.3f + 0.7f * nz);
                }
                for (uint32_t k = 0; k < nl; ++k) {
                    const int b = P_LEAKS + 5 * int(k);
                    const float dd = P[b + 1] < 0.5f ? d_top : d_bot;
                    const float ds = st.x - P[b];
                    fog += P[b + 2] * std::exp(-ds * ds / (2.0f * P[b + 3] * P[b + 3])) *
                           std::exp(-dd / P[b + 4]);
                }
                e = e + V3{P[P_FOG_R], P[P_FOG_G], P[P_FOG_B]} * fog;
            }
            if (film && P[P_PERFORATED] > 0.5f) {
                const float a = film_alpha(st, P);
                (*film)[size_t(y) * size_t(cw) + size_t(x)] =
                    uint8_t(clampf(a, 0.0f, 1.0f) * 255.0f + 0.5f);
            } else if (film) {
                (*film)[size_t(y) * size_t(cw) + size_t(x)] = 255;
            }
            const size_t o = 3u * (size_t(y) * size_t(cw) + size_t(x));
            out.rgb[o] = e.x;
            out.rgb[o + 1] = e.y;
            out.rgb[o + 2] = e.z;
        }
    });
    return true;
}

// --- ② an imprint, added as exposure ----------------------------------------
void add_mask(const Mask& m, Canvas& c) {
    for (int my = 0; my < m.h; ++my) {
        const int y = m.y0 + my;
        if (y < 0 || y >= c.h) continue;
        for (int mx = 0; mx < m.w; ++mx) {
            const int x = m.x0 + mx;
            if (x < 0 || x >= c.w) continue;
            const float cov = m.cov[size_t(my) * size_t(m.w) + size_t(mx)];
            if (cov <= 0.0f) continue;
            const size_t o = 3u * (size_t(y) * size_t(c.w) + size_t(x));
            c.rgb[o] += cov * float(m.exposure[0]);
            c.rgb[o + 1] += cov * float(m.exposure[1]);
            c.rgb[o + 2] += cov * float(m.exposure[2]);
        }
    }
}

// --- ③ where there is no film ------------------------------------------------
void film_present(const std::vector<float>& P, Canvas& c) {
    const V3 hole{P[P_HOLE_C], P[P_HOLE_M], P[P_HOLE_Y]};
    parallel_rows(c.h, [&](int y) {
        for (int x = 0; x < c.w; ++x) {
            const V2 st = canvas_to_film(float(x), float(y), P);
            const float a = film_alpha(st, P);
            const size_t o = 3u * (size_t(y) * size_t(c.w) + size_t(x));
            for (int k = 0; k < 3; ++k) {
                const float src = c.rgb[o + k];
                const float hv = k == 0 ? hole.x : (k == 1 ? hole.y : hole.z);
                c.rgb[o + k] = a * src + (1.0f - a) * hv;
            }
        }
    });
}

// --- ④ the scan's view of the film ------------------------------------------
void light_pass(const std::vector<float>& P, Canvas& c) {
    const bool white = P[P_HOLES_LIGHT] > 0.5f;
    const uint32_t seed = uint32_t(P[P_PERF_SEED]);
    const float W = P[P_FILM_W], px = P[P_PX];
    const V3 light0{P[P_LIGHT_R], P[P_LIGHT_G], P[P_LIGHT_B]};
    const V3 tint{1.0f + (P[P_BASE_R] - 1.0f) * 0.3f,
                  1.0f + (P[P_BASE_G] - 1.0f) * 0.3f,
                  1.0f + (P[P_BASE_B] - 1.0f) * 0.3f};
    const float lam = P[P_WALL_SCATTER];
    const V2 vdir{P[P_WALL_VS], P[P_WALL_VT]};
    parallel_rows(c.h, [&](int y) {
        for (int x = 0; x < c.w; ++x) {
            const V2 st = canvas_to_film(float(x), float(y), P);
            const float a = film_alpha(st, P);
            V3 light = light0;
            const size_t o = 3u * (size_t(y) * size_t(c.w) + size_t(x));
            V3 v{c.rgb[o], c.rgb[o + 1], c.rgb[o + 2]};
            if (P[P_PERFORATED] > 0.5f) {
                uint32_t hole = 0;
                const float d = perf_sdf(st, hole, P);
                const int h = int(hole & 0xFFFFu);
                if (d > -0.03f && d < 0.15f) {
                    const float e = 0.003f;
                    uint32_t hh = 0;
                    const V2 g{perf_sdf(V2{st.x + e, st.y}, hh, P) - perf_sdf(V2{st.x - e, st.y}, hh, P),
                               perf_sdf(V2{st.x, st.y + e}, hh, P) - perf_sdf(V2{st.x, st.y - e}, hh, P)};
                    const float gl = maxf(length(g), 1e-6f);
                    const V2 nrm{g.x / gl, g.y / gl};
                    const float per_hole = 0.4f + 0.6f * hash_f(h, seed ^ 0xE1u);
                    const float patch = 0.5f * (value_noise(st.x / 0.35f, seed ^ uint32_t(h)) +
                                                value_noise(st.y / 0.35f, seed ^ uint32_t(h) ^ 0x3Cu));
                    const float ps = clampf((patch - 0.25f) / 0.5f, 0.0f, 1.0f);
                    const float patch_factor = ps * ps * (3.0f - 2.0f * ps);
                    const float side = 0.6f + 0.8f * maxf(0.0f, dot(nrm, vdir));
                    const float k = per_hole * patch_factor * side;
                    if (d >= 0.0f) {
                        const float lift = P[P_WALL_GLOW] * k * std::exp(-d / lam);
                        v = v + mul3(tint, white ? light : V3{1, 1, 1}) * lift;
                    } else if (white && hash_f(h, seed ^ 0xE4u) < 0.5f) {
                        const float f = 1.0f - 0.12f * k * (1.0f - tint.x) * std::exp(d / 0.012f);
                        const float f2 = 1.0f - 0.12f * k * (1.0f - tint.y) * std::exp(d / 0.012f);
                        const float f3 = 1.0f - 0.12f * k * (1.0f - tint.z) * std::exp(d / 0.012f);
                        light = V3{light.x * f, light.y * f2, light.z * f3};
                    }
                }
                if (white && d >= 0.0f) {
                    const float fl = 0.012f * hash_f(h, seed ^ 0xC4u) *
                                     (hash_f(h, seed ^ 0xC5u) >= 0.5f ? 1.0f : 0.0f);
                    const float g = fl * std::exp(-d / 0.025f);
                    v = v + light * g;
                }
            }
            const float on_strip = P[P_CARRIER] > 1.5f
                ? 1.0f : clampf(minf(st.y, W - st.y) / px + 0.5f, 0.0f, 1.0f);
            const float in_film = on_strip > 0.0f ? a / on_strip : 0.0f;
            const V3 film_view = mix3(v, light, 1.0f - in_film);
            const float carrier = P[P_CARRIER];
            const V3 r3{carrier * (1.0f - on_strip) + film_view.x * on_strip,
                        carrier * (1.0f - on_strip) + film_view.y * on_strip,
                        carrier * (1.0f - on_strip) + film_view.z * on_strip};
            c.rgb[o] = r3.x;
            c.rgb[o + 1] = r3.y;
            c.rgb[o + 2] = r3.z;
        }
    });
}


// --- ⑤ the rebate, in the print ----------------------------------------------
//
// The engine runs `film_present` and `light` on the density plane, after the
// film has developed. This chain develops and prints in one kernel, so there is
// no plane to write; what the print shows is decided here instead, from the two
// coverage planes `canvas_pass` already computed:
//
//   in a perforation (film < 1)   the scan's light, and the burnished wall
//   the rebate (gate < 1)         the film base, as the reference medium
//                                 prints it -- measured on the first engine's
//                                 own render of the same frame: 13,16,18
//   an imprint's ink              left alone (it is the edge print, and it is
//                                 already exposure that developed)
//
// **The base is placed, not developed.** This chain's print floor is lifted --
// its screen medium keeps a grey black at 70 -- so unexposed film would print
// charcoal instead of black, and the strip would not read as film. The value
// below is the engine's, on the same frame, so the two agree.
// What this chain prints where the film carries no exposure at all: its screen
// medium keeps a lifted black (measured 70/255 on these renders). The rebate's
// base is placed relative to it so fog and leaks still show.
constexpr float kPrintFloor = 70.0f / 255.0f;

// The film base at fog 0 and at fog 4, read off the engine on this medium
// (rebate_probe, 900x600, c200 on screen: row 8 of the top band). The fog is a
// *warm* exposure there -- red climbs 7 -> 89 while blue only 17 -> 21 -- so the
// base is interpolated between the two, not lifted by a scalar.
constexpr float kBaseFog0[3] = {7.0f / 255.0f, 14.0f / 255.0f, 17.0f / 255.0f};
constexpr float kBaseFog4[3] = {89.0f / 255.0f, 39.0f / 255.0f, 21.0f / 255.0f};
constexpr float kFogFullLift = 90.0f / 255.0f;

void print_after(const std::vector<float>& P, uint8_t* rgb, int w, int h,
                 const uint8_t* gate, const uint8_t* film, const uint8_t* ink) {
    if (!rgb) return;
    // The scan's light through a punch, measured on the first engine's render of
    // this same frame (248,237,233) -- a hair warm, not white. Per-stock spectra
    // would give this exactly; the three colours are baked inputs here (see
    // `Ctx`), so the measured value stands in until they are.
    const V3 light{248.0f / 255.0f, 237.0f / 255.0f, 233.0f / 255.0f};
    const V3 base_col{13.0f / 255.0f, 16.0f / 255.0f, 18.0f / 255.0f};
    parallel_rows(h, [&](int y) {
        for (int x = 0; x < w; ++x) {
            const size_t i = size_t(y) * size_t(w) + size_t(x);
            const float a = film ? float(film[i]) / 255.0f : 1.0f;   // film, not hole
            const float c = gate ? float(gate[i]) / 255.0f : 1.0f;   // inside the gate
            const float k = ink ? float(ink[i]) / 255.0f : 0.0f;     // a mark
            if (a >= 0.999f && c >= 0.999f) continue;
            const size_t o = 3u * i;
            float th = 1.0f - a;                       // the hole / past the strip
            float tb = (1.0f - c) * (1.0f - k) * a;    // the rebate's own base
            const float sum = th + tb;
            if (sum > 1.0f) { th /= sum; tb /= sum; }
            const float keep = 1.0f - th - tb;
            for (int ch = 0; ch < 3; ++ch) {
                const float hv = ch == 0 ? light.x : (ch == 1 ? light.y : light.z);
                const float bv = ch == 0 ? base_col.x : (ch == 1 ? base_col.y : base_col.z);
                float v = float(rgb[o + ch]) / 255.0f;
                // **The fog and the leaks ride on the base.** This chain prints an
                // exposure brighter, so fog raises the print value where the film
                // is; that lift is what 片基雾 moves when the strip is scanned.
                // (The engine's base goes warm and lighter with fog too -- measured
                // there: 7,14,17 at fog 0 against 89,39,21 at fog 4.)
                const float lift = v > kPrintFloor ? (v - kPrintFloor) : 0.0f;
                const float t_fog = lift < kFogFullLift ? lift / kFogFullLift : 1.0f;
                const float bf = kBaseFog0[ch] + (kBaseFog4[ch] - kBaseFog0[ch]) * t_fog;
                v = v * keep + hv * th + bf * tb;
                rgb[o + ch] = uint8_t(clampf(v * 255.0f + 0.5f, 0.0f, 255.0f));
            }
        }
    });
}

}  // namespace rebate
