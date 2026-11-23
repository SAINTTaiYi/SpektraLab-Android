#include "print_frame.hpp"
#include "edge_printing.hpp"
#include "imprint_raster.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace print_frame {
namespace {

// `SlideMountGeometry.preset`: the universal 2 x 2 inch projector format, and
// 70 mm square for 120. The aperture is the typical mounted image area.
constexpr double kSlideCard35MM = 50.8, kSlideApW35MM = 34.5, kSlideApH35MM = 23.0;
constexpr double kSlideCard120MM = 70.0, kSlideApW120MM = 56.0, kSlideApH120MM = 56.0;
constexpr double kSlideCornerMM = 1.0;

struct Sheet {
    double w = 0, h = 0, margin = 0, rebate = 0;
};

/// `PrintFrameConfiguration.margin` is a *multiple* of the preset margin, 0 to
/// 3, not millimetres (`marginRange`). The app's slider is in millimetres with 3
/// as its neutral, so the two readings coincide at the default.
double margin_scale(const Request& req) {
    const double v = req.margin_mm / 3.0;
    return v < 0.0 ? 0.0 : (v > 3.0 ? 3.0 : v);
}

/// `PaperSheetGeometry.preset`, with the easel margin scaled. A carrier holds a
/// filed rebate inside its easel opening; a plain print has none.
bool sheet_for(Kind k, double scale, int src_w, int src_h, Sheet& s) {
    switch (k) {
        case Kind::Paper4x6:  s = {152.4, 101.6, 3.0, 0.0};   break;
        case Kind::Paper5x7:  s = {177.8, 127.0, 3.0, 0.0};   break;
        case Kind::Paper8x10: s = {254.0, 203.2, 3.0, 0.0};   break;
        case Kind::Paper5x5:  s = {127.0, 127.0, 3.0, 0.0};   break;
        case Kind::Carrier:   s = {254.0, 203.2, 12.7, 2.5};  break;
        default: return false;
    }
    s.margin *= scale;
    // A print is cut for the picture's orientation; a square sheet keeps its size.
    if (src_h > src_w && s.w > s.h) std::swap(s.w, s.h);
    return true;
}

void crop_for(int src_w, int src_h, double ratio, double fx, double fy,
              int& cx, int& cy, int& cw, int& ch) {
    const double r = double(src_w) / double(src_h);
    if (r > ratio) {
        ch = src_h;
        cw = int(std::lround(double(src_h) * ratio));
    } else {
        cw = src_w;
        ch = int(std::lround(double(src_w) / ratio));
    }
    cw = std::clamp(cw, 1, src_w);
    ch = std::clamp(ch, 1, src_h);
    const int sx = src_w - cw, sy = src_h - ch;
    cx = std::clamp(int(std::lround(double(sx) * (fx + 1.0) * 0.5)), 0, sx);
    cy = std::clamp(int(std::lround(double(sy) * (fy + 1.0) * 0.5)), 0, sy);
}

// --- the nine gauges (`FilmFormat.swift`, `docs/print-frame-model.md`) --------
//
// One frame's piece of film: x runs along the film, y across it. `135` and the
// 120 frames are drawn by the `rebate` module; the rest are drawn here.
struct Gauge {
    const char* id;
    double film_x, film_y;   // one frame's piece of film, mm
    double ap_x, ap_y;       // the camera aperture, mm
    double ap_off_x, ap_off_y;  // where it sits in the film, mm from the film's own corner
    int rows;                // perforation rows, one per long edge of transport
    double hole_x, hole_y;   // a perforation, mm: x along the film, y across it
    double hole_r;           // its corner radius, mm (0 for a Bell & Howell die)
    double pitch;            // mm between holes along the film
    double edge;             // the hole's outside edge from the film's edge, mm
    bool transport_x;        // the film runs along x (`horizontalTransport`)
    bool instax;             // an integral positive border, not a strip
    bool sheet;              // an unperforated sheet
};

constexpr Gauge kGauges[] = {
    // **Copied from `FilmBorderGeometry.preset()`** -- the same millimetres the
    // official app lays out. `ap_off_*` is the aperture's own place in the film,
    // not a centring: it is what puts 16 mm's picture against the perforations
    // and an Instax print at the head of its sheet.
    //
    // 135: KS, 2.794 across x 1.981 along, pitch 4.75, the hole's outside edge
    // 2.01 mm from the film's edge, eight a frame.
    {"135",           38.0,  35.0,  36.0,  24.0, 1.0,  5.5,  2, 1.981, 2.794, 0.510, 4.750, 2.01, true,  false, false},
    // 135 half frame / XPan: not official presets. Kept so a look saved before
    // the gauge list was cut still renders; the official app has neither.
    {"135_half",      38.0,  35.0,  24.0,  18.0, 7.0,  8.5,  2, 1.981, 2.794, 0.510, 4.750, 2.01, true,  false, false},
    {"135_xpan",      70.0,  35.0,  65.0,  24.0, 2.5,  5.5,  2, 1.981, 2.794, 0.510, 4.750, 2.01, true,  false, false},
    // 120 roll film: **one format, 6 x 6.** 61 x 60 mm, unperforated.
    {"120_6x6",       61.0,  60.0,  56.0,  56.0, 2.5,  2.0,  0, 0, 0, 0, 0, 0, false, false, false},
    // The rest of the 120 range is ours, not the official's: kept for old looks.
    {"120_645",       45.5,  61.5,  41.5,  56.0, 2.0,  2.75, 0, 0, 0, 0, 0, 0, false, false, false},
    {"120_6x7",       74.0,  61.5,  70.0,  56.0, 2.0,  2.75, 0, 0, 0, 0, 0, 0, false, false, false},
    {"120_6x8",       80.0,  61.5,  76.0,  56.0, 2.0,  2.75, 0, 0, 0, 0, 0, 0, false, false, false},
    {"120_6x9",       88.0,  61.5,  84.0,  56.0, 2.0,  2.75, 0, 0, 0, 0, 0, 0, false, false, false},
    {"120_6x12",     124.0,  61.5, 120.0,  56.0, 2.0,  2.75, 0, 0, 0, 0, 0, 0, false, false, false},
    {"120_6x17",     176.0,  61.5, 170.0,  56.0, 3.0,  2.75, 0, 0, 0, 0, 0, 0, false, false, false},
    // Super 35: BH, 2.794 x 1.854, pitch 4.74, edge 2.01.
    {"super35",       35.0,  18.96, 24.92, 18.67, 5.04, 0.145, 2, 1.854, 2.794, 0.0,   4.740, 2.01, false, false, false},
    // 16 mm: Super 16 gate, one row, holes on the frame lines (pitch 7.605).
    {"16mm",          16.0,   7.605, 12.35,  7.42, 2.85, 0.0925, 1, 1.270, 1.829, 0.250, 7.605, 0.914, false, false, false},
    // Super 8: Type S, one row, a hole at each frame's centre, edge 0.51.
    {"super8",         7.976, 4.234,  5.79,  4.01, 1.59, 0.112, 1, 1.143, 0.914, 0.130, 4.234, 0.510, false, false, false},
    // 4 x 5: an unperforated sheet, 100 x 125 mm, 95 x 120 image area.
    {"4x5",          100.0, 125.0,  95.0, 120.0, 2.5,  2.5,  0, 0, 0, 0, 0, 0, false, false, true},
    // Instax: the integral border. The official preset puts the window 20 / 19 /
    // 19.5 mm up from the sheet's *bottom* edge (y-up Core Graphics), which is
    // the chemical pod's wide band; this canvas is y-down, so the same windows
    // start 4 / 5 / 4.5 mm from the top edge.
    {"instax-mini",    54.0,  86.0,  46.0,  62.0, 4.0,  4.0,  0, 0, 0, 0, 0, 0, false, true,  false},
    {"instax-square",  72.0,  86.0,  62.0,  62.0, 5.0,  5.0,  0, 0, 0, 0, 0, 0, false, true,  false},
    {"instax-wide",   108.0,  86.0,  99.0,  62.0, 4.5,  4.5,  0, 0, 0, 0, 0, 0, false, true,  false},
};

// --- the factory's edge print, per stock (`docs/print-frame-model.md`) ------
//
// Only inscriptions the document lists as verified. A stock that is absent
// carries none -- the same rule the document states for its own pictures.
struct EdgePrint {
    const char* stock;
    const char* t135;     // 135, half frame, XPan; and 35 mm still for a motion stock
    const char* t120;     // 120 frames
    const char* tmotion;  // Super 35 and 16 mm
};

constexpr EdgePrint kEdgePrints[] = {
    {"portra400",           "KODAK PORTRA 400",  "KODAK PORTRA 400",  nullptr},
    {"ektar100",            "KODAK EKTAR 100",   "KODAK EKTAR 100",   nullptr},
    {"gold200",             "KODAK GB 200-7",    "KODAK 200",         nullptr},
    {"ultramax400",         "KODAK GC 400",      nullptr,             nullptr},
    {"kodacolor100",        "KODAK 100-8",       nullptr,             nullptr},
    {"phoenix200",          "HARMAN PHOENIX 200","HARMAN PHOENIX 200",nullptr},
    {"ektachromee100",      "KODAK E100",        "KODAK E100",        nullptr},
    {"kodachrome64",        "KODAK 5032  KR 64", nullptr,             nullptr},
    {"kodachrome25",        "KODAK 5073  KM 25", nullptr,             nullptr},
    {"kodachrome200",       "KODAK 5001  KL 200",nullptr,             nullptr},
    {"trix400",             "KODAK 400TX",       "KODAK 400TX",       nullptr},
    {"tmax100",             "KODAK 100TMX",      "KODAK 100TMX",      nullptr},
    {"hp5plus400",          "ILFORD HP5 PLUS",   nullptr,             nullptr},
    {"fp4plus125",          "ILFORD FP4 PLUS",   "ILFORD FP4 PLUS",   nullptr},
    {"delta3200",           "ILFORD DELTA 3200 PROFESSIONAL", "ILFORD DELTA 3200 PRO", nullptr},
    {"velvia50",            "FUJI  RVP50",       "FUJI  RVP50",       nullptr},
    {"provia100f",          "FUJI  RDPIII",      "FUJI  RDPIII",      nullptr},
    {"astia100f",           "FUJI  RAP100F",     "FUJI  RAP100F",     nullptr},
    {"pro400h",             "PRO400H  FUJI",     "PRO400H  FUJI",     nullptr},
    {"pro160ns",            nullptr,             "FUJI  PN160NS",     nullptr},
    {"c200",                "200  FUJI  CA24",   nullptr,             nullptr},
    {"superia400",          "S-400  FUJI  CH23", nullptr,             nullptr},
    {"superia200",          "S-200  FUJI",       nullptr,             nullptr},
    {"reala100",            "REALA  FUJI",       "REALA  FUJI",       nullptr},
    {"agfachrome100",       "AGFA RSX II 100",   "RSX II 100  AGFA",  nullptr},
    {"agfachrome200",       nullptr,             "RSX II 200  AGFA",  nullptr},
    {"lomochromemetropolis","LOMO METROPOLIS",   nullptr,             nullptr},
    {"lomochromepurple",    "LOMO PURPLE",       nullptr,             nullptr},
    {"lomochrometurquoise", "LOMO TURQUOISE",    nullptr,             nullptr},
    {"lomocolor100",        "100-2",             "LOMO  CN 100",      nullptr},
    // Motion picture: the 35 mm still gauge carries the same KEYCODE fragment.
    {"vision250d",          "5207  EN",          nullptr,             "5207  EN"},
    {"vision500t",          "5219  EJ",          nullptr,             "5219  EJ"},
    {"doublex5222",         "5222  KE",          nullptr,             "5222  KE"},
    {"verita200d",          "5206  ET",          nullptr,             "5206  ET"},
    {"eterna500",           nullptr,             nullptr,             "FN73  FUJI 500"},
    {"eternavivid500",      nullptr,             nullptr,             "FN47  FUJI V500"},
    {"f64d",                nullptr,             nullptr,             "FN22  FUJI F-64D"},
    {"reala500d",           nullptr,             nullptr,             "FN92  FUJI 500D"},
    {"cinestill800t",       "EJ",                nullptr,             nullptr},
};

const Gauge* gauge_for(const std::string& id) {
    for (const Gauge& g : kGauges) if (id == g.id) return &g;
    return nullptr;
}

inline uint32_t h32(uint32_t x) {
    x ^= x >> 16; x *= 0x7feb352du; x ^= x >> 15; x *= 0x846ca68bu; x ^= x >> 16;
    return x;
}

// A small repeating tile is enough for a paper tooth at eight bits.
const float* noise_tile() {
    static float tile[64 * 64];
    static bool ready = false;
    if (!ready) {
        for (int y = 0; y < 64; ++y)
            for (int x = 0; x < 64; ++x) {
                const uint32_t v = h32(uint32_t(y * 64 + x) * 2654435761u + 17u);
                tile[y * 64 + x] = float(int(v >> 8) % 2001 - 1000) / 1000.0f;
            }
        ready = true;
    }
    return tile;
}

inline float tooth(int x, int y) {
    const float* t = noise_tile();
    return t[((y & 63) << 6) | (x & 63)];
}

inline uint8_t to8(double v) {
    const double s = v * 255.0 + 0.5;
    return uint8_t(s <= 0.0 ? 0.0 : (s >= 255.0 ? 255.0 : s));
}

void fill(std::vector<uint8_t>& img, int w, int h, int x0, int y0, int x1, int y1,
          const double rgb[3]) {
    x0 = std::clamp(x0, 0, w); x1 = std::clamp(x1, 0, w);
    y0 = std::clamp(y0, 0, h); y1 = std::clamp(y1, 0, h);
    const uint8_t c[3] = {to8(rgb[0]), to8(rgb[1]), to8(rgb[2])};
    for (int y = y0; y < y1; ++y) {
        uint8_t* row = img.data() + (size_t(y) * size_t(w) + size_t(x0)) * 3;
        for (int x = x0; x < x1; ++x) { *row++ = c[0]; *row++ = c[1]; *row++ = c[2]; }
    }
}

// A material's own colour, flat. The official fills the card or the sheet in
// one colour and puts every bit of surface into `drawLustre`, so nothing here
// may shade the paper: a corner fall-off on a white border is a gradient the
// print does not have.
void paper_field(std::vector<uint8_t>& img, int w, int h, const double rgb[3]) {
    fill(img, w, h, 0, 0, w, h, rgb);
}

void paste(std::vector<uint8_t>& out, int ow, int oh, const uint8_t* src, int sw, int sh,
           int sx, int sy, int w, int h, int dx, int dy) {
    for (int y = 0; y < h; ++y) {
        const int ty = dy + y;
        if (ty < 0 || ty >= oh) continue;
        const int fy = sy + y;
        if (fy < 0 || fy >= sh) continue;
        std::memcpy(out.data() + (size_t(ty) * size_t(ow) + size_t(dx)) * 3,
                    src + (size_t(fy) * size_t(sw) + size_t(sx)) * 3,
                    size_t(w) * 3);
    }
}

// A ring of `mm` around the picture, drawn inside the aperture.
void ring_pixels(const Layout& L, double mm, int& x0, int& y0, int& x1, int& y1) {
    const int p = std::max(1, int(std::lround(mm * L.px_per_mm)));
    x0 = L.photo_x - p; y0 = L.photo_y - p;
    x1 = L.photo_x + L.photo_w + p; y1 = L.photo_y + L.photo_h + p;
}

/// A closed polygon filled in one colour, four sub-rows a pixel. `pts` are in
/// canvas pixels, y down, and the loop must not cross itself.
void fill_polygon(std::vector<uint8_t>& out, int W, int H,
                  const std::vector<std::pair<double, double>>& pts, const double rgb[3]) {
    if (pts.size() < 3) return;
    double ymin = 1e300, ymax = -1e300;
    for (const auto& p : pts) { ymin = std::min(ymin, p.second); ymax = std::max(ymax, p.second); }
    const int y0 = std::max(0, int(std::floor(ymin)));
    const int y1 = std::min(H, int(std::ceil(ymax)));
    const int kSub = 4;
    std::vector<double> cover(static_cast<size_t>(W), 0.0);
    std::vector<double> xs;
    for (int y = y0; y < y1; ++y) {
        std::fill(cover.begin(), cover.end(), 0.0);
        for (int sub = 0; sub < kSub; ++sub) {
            const double sy = double(y) + (double(sub) + 0.5) / double(kSub);
            xs.clear();
            for (size_t i = 0, n = pts.size(); i < n; ++i) {
                const auto& a = pts[i];
                const auto& b = pts[(i + 1) % n];
                if ((a.second <= sy) == (b.second <= sy)) continue;
                xs.push_back(a.first + (sy - a.second) * (b.first - a.first) / (b.second - a.second));
            }
            std::sort(xs.begin(), xs.end());
            for (size_t i = 0; i + 1 < xs.size(); i += 2) {
                const double xa = std::max(0.0, xs[i]), xb = std::min(double(W), xs[i + 1]);
                if (xb <= xa) continue;
                for (int x = int(std::floor(xa)); x < int(std::ceil(xb)); ++x) {
                    const double l = std::max(xa, double(x)), r = std::min(xb, double(x + 1));
                    if (r > l) cover[size_t(x)] += (r - l) / double(kSub);
                }
            }
        }
        uint8_t* row = out.data() + size_t(y) * size_t(W) * 3;
        for (int x = 0; x < W; ++x) {
            const double a = std::min(1.0, cover[size_t(x)]);
            if (a <= 0.0) continue;
            for (int k = 0; k < 3; ++k)
                row[x * 3 + k] = to8((row[x * 3 + k] / 255.0) * (1.0 - a) + rgb[k] * a);
        }
    }
}

/// Smooth value noise in about -1...1 (`filingNoise`).
double filing_noise(double x, double y, uint32_t seed) {
    auto sample = [](int32_t a, int32_t b, uint32_t sd) {
        uint32_t n = uint32_t(a) * 374761393u + uint32_t(b) * 668265263u + sd * 1013904223u;
        n = (n ^ (n >> 13)) * 1274126177u;
        n ^= n >> 16;
        return double(n & 0xFFFFu) / 32767.5 - 1.0;
    };
    const int32_t ix = int32_t(std::floor(x)), iy = int32_t(std::floor(y));
    const double fx = x - double(ix), fy = y - double(iy);
    const double u = fx * fx * (3.0 - 2.0 * fx), v = fy * fy * (3.0 - 2.0 * fy);
    const double a = sample(ix, iy, seed), b = sample(ix + 1, iy, seed);
    const double c = sample(ix, iy + 1, seed), d = sample(ix + 1, iy + 1, seed);
    return (a + (b - a) * u) * (1.0 - v) + (c + (d - c) * u) * v;
}

/// `drawCarrierRebate`: the clear rebate of the negative, printed through a
/// carrier filed wider than its aperture, at the paper's maximum density. Hand
/// filing leaves the outer edge slightly uneven; the inner edge is the camera
/// gate and stays straight. The photographic margin beyond it stays white.
void draw_carrier(std::vector<uint8_t>& out, const Layout& L, const Request& req) {
    const double px = L.px_per_mm;
    const double w = L.rebate_mm;
    if (!(px > 0.0) || !(w > 0.0)) return;
    const double x0 = double(L.photo_x) / px - w, x1 = double(L.photo_x + L.photo_w) / px + w;
    const double y0 = double(L.photo_y) / px - w, y1 = double(L.photo_y + L.photo_h) / px + w;
    // Bottom, right, top, left, as the official walks them; this canvas is
    // y-down, so the normals point the other way from Core Graphics'.
    const double corners[4][2] = {{x0, y1}, {x1, y1}, {x1, y0}, {x0, y0}};
    const double normal[4][2] = {{0.0, 1.0}, {1.0, 0.0}, {0.0, -1.0}, {-1.0, 0.0}};
    std::vector<std::pair<double, double>> poly;
    const double step = 0.5;
    for (int side = 0; side < 4; ++side) {
        const double ax = corners[side][0], ay = corners[side][1];
        const double bx = corners[(side + 1) % 4][0], by = corners[(side + 1) % 4][1];
        const double len = std::hypot(bx - ax, by - ay);
        const int count = std::max(2, int(std::ceil(len / step)));
        for (int i = 0; i <= count; ++i) {
            const double t = double(i) / double(count);
            const double along = len * t;
            const double wobble = filing_noise(along * 0.35, double(side) * 7.3, 409)
                                + 0.4 * filing_noise(along * 1.6, double(side) * 3.1, 613);
            double amount = wobble * 0.22;
            amount = std::max(-w * 0.25, std::min(w * 0.25, amount));
            poly.push_back({(ax + (bx - ax) * t + normal[side][0] * amount) * px,
                            (ay + (by - ay) * t + normal[side][1] * amount) * px});
        }
    }
    fill_polygon(out, L.canvas_w, L.canvas_h, poly, req.paper_dense);
}

/// `drawLustre`: fine resin-coated stipple -- 0.065 mm dots on a 0.17 mm pitch,
/// gray 0.12 at 3.5%, skipping the picture and its rebate. Never cotton fibres
/// or deckled paper: a restrained procedural approximation of the finish.
void draw_lustre(std::vector<uint8_t>& out, const Layout& L) {
    const double px = L.px_per_mm;
    if (!(px > 0.0)) return;
    const double pitch = 0.17 * px;
    if (pitch < 1.2) return;             // finer than the raster: nothing to see
    const double r = std::max(0.5, 0.0325 * px);
    const double grow = L.rebate_mm * px;
    const double ex0 = double(L.photo_x) - grow, ex1 = double(L.photo_x + L.photo_w) + grow;
    const double ey0 = double(L.photo_y) - grow, ey1 = double(L.photo_y + L.photo_h) + grow;
    const int W = L.canvas_w, H = L.canvas_h;
    // `MaterialRandom`: a 64-bit LCG, the same one and the same seed.
    uint64_t state = 0x5052494E54534854ull;
    auto next = [&]() {
        state = state * 6364136223846793005ull + 1442695040888963407ull;
        return double(state >> 40) / double(uint64_t(1) << 24);
    };
    const double k = 0.035, tone = 0.12;
    for (double y = 0.0; y < H; y += pitch) {
        for (double x = 0.0; x < W; x += pitch) {
            const double cx = x + next() * pitch;
            const double cy = y + next() * pitch;
            if (cx >= ex0 && cx <= ex1 && cy >= ey0 && cy <= ey1) continue;
            const int ix0 = std::max(0, int(std::floor(cx - r)));
            const int ix1 = std::min(W, int(std::ceil(cx + r)) + 1);
            const int iy0 = std::max(0, int(std::floor(cy - r)));
            const int iy1 = std::min(H, int(std::ceil(cy + r)) + 1);
            for (int yy = iy0; yy < iy1; ++yy) {
                uint8_t* row = out.data() + size_t(yy) * size_t(W) * 3;
                for (int xx = ix0; xx < ix1; ++xx) {
                    const double dx = double(xx) + 0.5 - cx, dy = double(yy) + 0.5 - cy;
                    const double cov = std::clamp(r + 0.5 - std::sqrt(dx * dx + dy * dy), 0.0, 1.0);
                    if (cov <= 0.0) continue;
                    const double a = k * cov;
                    for (int c = 0; c < 3; ++c)
                        row[xx * 3 + c] = to8(row[xx * 3 + c] / 255.0 * (1.0 - a) + tone * a);
                }
            }
        }
    }
}

void draw_emulsion(std::vector<uint8_t>& out, const Layout& L, const Request& req) {
    // The film just outside the camera gate, printed with the photograph: the
    // stock's own unexposed tone, its glow, and its grain.
    int x0, y0, x1, y1;
    ring_pixels(L, L.rebate_mm, x0, y0, x1, y1);
    const double* base = req.film_rgb;
    for (int y = y0; y < y1; ++y) {
        if (y < 0 || y >= L.canvas_h) continue;
        const bool band = (y < L.photo_y) || (y >= L.photo_y + L.photo_h);
        uint8_t* row = out.data() + size_t(y) * size_t(L.canvas_w) * 3;
        for (int x = x0; x < x1; ++x) {
            if (x < 0 || x >= L.canvas_w) continue;
            if (!band && x >= L.photo_x && x < L.photo_x + L.photo_w) continue;
            const double g = 1.0 + 0.030 * double(tooth(x, y));
            row[x * 3 + 0] = to8(base[0] * g);
            row[x * 3 + 1] = to8(base[1] * g);
            row[x * 3 + 2] = to8(base[2] * g);
        }
    }
    // The glow: the base scatters a little light back over the picture's edge.
    // The band is the film's own margin, so the reach follows it rather than a
    // nominal millimetre (this canvas is laid out at one pixel a millimetre).
    const int reach = std::max(2, int(std::lround(0.6 * L.rebate_mm)));
    for (int y = -reach; y < L.photo_h + reach; ++y) {
        const int ty = L.photo_y + y;
        if (ty < 0 || ty >= L.canvas_h) continue;
        uint8_t* row = out.data() + size_t(ty) * size_t(L.canvas_w) * 3;
        for (int x = -reach; x < L.photo_w + reach; ++x) {
            const int tx = L.photo_x + x;
            if (tx < 0 || tx >= L.canvas_w) continue;
            const int dx = std::max(0, std::max(-x, x - (L.photo_w - 1)));
            const int dy = std::max(0, std::max(-y, y - (L.photo_h - 1)));
            const double d = std::sqrt(double(dx * dx + dy * dy)) / double(reach);
            if (d >= 1.0) continue;
            const double k = 0.10 * (1.0 - d) * (1.0 - d);
            row[tx * 3 + 0] = to8(row[tx * 3 + 0] / 255.0 * (1.0 - k) + base[0] * k);
            row[tx * 3 + 1] = to8(row[tx * 3 + 1] / 255.0 * (1.0 - k) + base[1] * k);
            row[tx * 3 + 2] = to8(row[tx * 3 + 2] / 255.0 * (1.0 - k) + base[2] * k);
        }
    }
}

void rounded_rect(std::vector<uint8_t>& img, int W, int H, double x, double y,
                  double w, double h, double r, const double rgb[3]) {
    if (!(w > 0.0) || !(h > 0.0)) return;
    const int x0 = std::max(0, int(std::floor(x)) - 1);
    const int x1 = std::min(W, int(std::ceil(x + w)) + 1);
    const int y0 = std::max(0, int(std::floor(y)) - 1);
    const int y1 = std::min(H, int(std::ceil(y + h)) + 1);
    r = std::min(r, std::min(w, h) * 0.5);
    const double cx = x + w * 0.5, cy = y + h * 0.5;
    const double rx = std::max(0.0, w * 0.5 - r), ry = std::max(0.0, h * 0.5 - r);
    for (int yy = y0; yy < y1; ++yy) {
        uint8_t* row = img.data() + size_t(yy) * size_t(W) * 3;
        for (int xx = x0; xx < x1; ++xx) {
            // A box SDF: the interior must be full coverage, not the 0.5 a
            // max(0, ...) clamp leaves there.
            const double qx = std::fabs(xx + 0.5 - cx) - rx;
            const double qy = std::fabs(yy + 0.5 - cy) - ry;
            const double ox = std::max(qx, 0.0), oy = std::max(qy, 0.0);
            const double d = std::sqrt(ox * ox + oy * oy) + std::min(std::max(qx, qy), 0.0) - r;
            const double a = std::clamp(0.5 - d, 0.0, 1.0);
            if (a <= 0.0) continue;
            for (int k = 0; k < 3; ++k)
                row[xx * 3 + k] = to8((row[xx * 3 + k] / 255.0) * (1.0 - a) + rgb[k] * a);
        }
    }
}

// `em` and the position are pixels; the rasteriser only needs one unit.
void draw_text(std::vector<uint8_t>& img, int W, int H, double x, double baseline,
               double em, const std::string& text, const char* face, double rotate_deg,
               const double ink[3]) {
    if (text.empty() || em < 3.0) return;
    spk::imprint::Contours paths =
        spk::imprint::text_outlines(face, em, text, em * 0.04, 1.0, true, 0.12);
    if (paths.empty()) return;
    double xmin = 1e30, ymin = 1e30, xmax = -1e30, ymax = -1e30;
    const double rad = rotate_deg * 3.14159265358979323846 / 180.0;
    const double cs = std::cos(rad), sn = std::sin(rad);
    for (auto& path : paths) for (auto& pt : path) {
        const double gx = pt.x * cs - pt.y * sn, gy = pt.x * sn + pt.y * cs;
        pt.x = gx + x; pt.y = gy + baseline;
        xmin = std::min(xmin, pt.x); xmax = std::max(xmax, pt.x);
        ymin = std::min(ymin, pt.y); ymax = std::max(ymax, pt.y);
    }
    const int bx0 = std::max(0, int(std::floor(xmin)) - 1);
    const int by0 = std::max(0, int(std::floor(ymin)) - 1);
    const int bx1 = std::min(W, int(std::ceil(xmax)) + 1);
    const int by1 = std::min(H, int(std::ceil(ymax)) + 1);
    if (bx1 <= bx0 || by1 <= by0) return;
    std::vector<uint8_t> cov(size_t(bx1 - bx0) * size_t(by1 - by0), 0);
    for (auto& path : paths) for (auto& pt : path) { pt.x -= bx0; pt.y -= by0; }
    spk::imprint::fill(cov, bx1 - bx0, by1 - by0, paths, 1.0);
    for (int yy = by0; yy < by1; ++yy) {
        uint8_t* row = img.data() + size_t(yy) * size_t(W) * 3;
        for (int xx = bx0; xx < bx1; ++xx) {
            const uint8_t c = cov[size_t(yy - by0) * size_t(bx1 - bx0) + size_t(xx - bx0)];
            if (!c) continue;
            const double a = double(c) / 255.0;
            for (int k = 0; k < 3; ++k)
                row[xx * 3 + k] = to8((row[xx * 3 + k] / 255.0) * (1.0 - a) + ink[k] * a);
        }
    }
}

// --- sheet notches (`docs/print-frame-model.md`, "Stock-specific notches") ---
//
// Only the two Ilford stocks that the guide documents, and only on a sheet:
// FP4 Plus and HP5 Plus, sizes up to 10 x 12 in, from
// `Ilford's Notch Codes guide, page 1`. Both carry a wide shallow arc at each
// end of a 20 mm group and a smaller round notch between them; the round
// notch's position is what tells the two apart.
//
// The guide prints identification diagrams, not machining dimensions, so the
// profiles below are modelled within a representative span that ends 10 mm
// from the corner. They are not measured manufacturer tolerances. A stock with
// no documented code gets no notch at all -- an invented code would be worse
// than an empty edge.
//
// `SheetFilmNotchCode` from the stock pack: shapes and order as the manufacturer
// documents them. Only FP4 Plus and HP5 Plus have one; a sheet stock without a
// documented code carries none rather than a guessed one.
struct NotchMark { double position, width, depth; };
struct NotchCode { const char* stock; NotchMark marks[3]; int count; };
constexpr NotchCode kNotchCodes[] = {
    {"fp4plus125", {{0.0, 0.17721518987341772, 0.04430379746835443},
                    {0.2911392405063291, 0.0759493670886076, 0.0379746835443038},
                    {0.8164556962025317, 0.17721518987341772, 0.04430379746835443}}, 3},
    {"hp5plus400", {{0.0, 0.17721518987341772, 0.04430379746835443},
                    {0.6329113924050633, 0.0759493670886076, 0.0379746835443038},
                    {0.8164556962025317, 0.17721518987341772, 0.04430379746835443}}, 3},
};

/// `drawNotches`: the code sits on the upper right edge when the sheet is
/// upright, emulsion facing the viewer. Manufacturer diagrams specify shapes and
/// order, not absolute dimensions: a 20 mm span and a 10 mm corner setback are
/// presentation conventions, documented as such. Each notch is an ellipse
/// straddling the edge -- half cut out of the sheet.
void draw_sheet_notch(const Request& req, const Layout& L, std::vector<uint8_t>& out) {
    if (req.gauge != "4x5") return;
    const NotchCode* code = nullptr;
    for (const NotchCode& c : kNotchCodes) if (req.stock == c.stock) { code = &c; break; }
    if (!code) return;
    const double px = L.px_per_mm;
    if (!(px > 0.0)) return;
    const int W = L.canvas_w, H = L.canvas_h;
    const double span = 20.0;
    const double start = double(W) / px - 10.0 - span;
    const double tone[3] = {0.965, 0.960, 0.945};   // the scan's light
    for (int i = 0; i < code->count; ++i) {
        const NotchMark& n = code->marks[i];
        const double cx = (start + n.position * span + n.width * span * 0.5) * px;
        const double rx = std::max(0.5, n.width * span * 0.5 * px);
        const double ry = std::max(0.5, n.depth * span * px);
        const int ix0 = std::max(0, int(std::floor(cx - rx)) - 1);
        const int ix1 = std::min(W, int(std::ceil(cx + rx)) + 1);
        const int iy1 = std::min(H, int(std::ceil(ry)) + 1);
        for (int y = 0; y < iy1; ++y) {
            uint8_t* row = out.data() + size_t(y) * size_t(W) * 3;
            for (int x = ix0; x < ix1; ++x) {
                const double u = (double(x) + 0.5 - cx) / rx;
                const double v = (double(y) + 0.5) / ry;
                const double q = std::sqrt(u * u + v * v);
                const double cov = std::clamp((1.0 - q) * std::min(rx, ry) + 0.5, 0.0, 1.0);
                if (cov <= 0.0) continue;
                for (int k = 0; k < 3; ++k)
                    row[x * 3 + k] = to8(row[x * 3 + k] / 255.0 * (1.0 - cov) + tone[k] * cov);
            }
        }
    }
}

// --- one perforation, the die's own shape (`perforationOutline`) ----------------
//
// KS is a rounded rectangle of corner `r`; Bell & Howell is a circle of radius
// `w/2` cut by two flats `h` apart. Both are filled here with 2 x 2 coverage,
// which at a strip's millimetres is the antialiasing the official's path
// rasteriser gives.
static void hole_shape(std::vector<uint8_t>& out, int W, int H, double cx, double cy,
                       double w_along, double h_across, double r, bool bell_howell,
                       const double rgb[3]) {
    const double hx = w_along * 0.5, hy = h_across * 0.5;
    const int x0 = std::max(0, int(std::floor(cx - hx)) - 1);
    const int x1 = std::min(W, int(std::ceil(cx + hx)) + 2);
    const int y0 = std::max(0, int(std::floor(cy - hy)) - 1);
    const int y1 = std::min(H, int(std::ceil(cy + hy)) + 2);
    if (x1 <= x0 || y1 <= y0) return;
    const double radius = std::max(0.0, std::min(r, std::min(hx, hy)));
    for (int y = y0; y < y1; ++y) {
        uint8_t* row = out.data() + size_t(y) * size_t(W) * 3;
        for (int x = x0; x < x1; ++x) {
            double cover = 0.0;
            for (int sy = 0; sy < 2; ++sy) {
                for (int sx = 0; sx < 2; ++sx) {
                    const double px = double(x) + 0.25 + 0.5 * double(sx);
                    const double py = double(y) + 0.25 + 0.5 * double(sy);
                    const double dx = px - cx, dy = py - cy;
                    bool inside;
                    if (bell_howell) {
                        const double rr = hx;
                        inside = (dx * dx + dy * dy) <= rr * rr + 1e-9 && std::fabs(dy) <= hy + 1e-9;
                    } else if (radius <= 0.0) {
                        inside = std::fabs(dx) <= hx + 1e-9 && std::fabs(dy) <= hy + 1e-9;
                    } else {
                        const double qx = std::max(std::fabs(dx) - (hx - radius), 0.0);
                        const double qy = std::max(std::fabs(dy) - (hy - radius), 0.0);
                        inside = (qx * qx + qy * qy) <= radius * radius + 1e-9;
                    }
                    if (inside) cover += 0.25;
                }
            }
            if (cover <= 0.0) continue;
            for (int k = 0; k < 3; ++k)
                row[size_t(x) * 3 + size_t(k)] = to8((row[size_t(x) * 3 + size_t(k)] / 255.0) * (1.0 - cover)
                                                      + rgb[k] * cover);
        }
    }
}

// ---------------------------------------------------------------------------
// The official edge print.
//
// `FilmEdgePrinting` is a list of millimetre boxes in the *reference strip*
// (x along transport, y across, origin at the cut's lower left). The official
// takes the glyphs' tight ink outlines -- one `CTLine` of Helvetica -- and
// scales that box to fill each mark exactly, so the face's advance widths never
// reach the film: only its shapes do. Liberation Sans is metric-compatible with
// Helvetica and, normalised this way, the lettering lands where the official's
// does. The boxes themselves come from the stock packs, not from a table read
// off a photograph: see `edge_printing.hpp`.
// ---------------------------------------------------------------------------

/// Which `FilmFormat` a gauge is coated on. Null for the gauges the official
/// app does not have (the extra 120 frames) and for Instax, which carries no
/// edge print at all.
const char* edge_format_for(const std::string& gauge) {
    if (gauge == "135" || gauge == "135_half" || gauge == "135_xpan") return "35mm";
    if (gauge == "120_6x6") return "120";
    if (gauge == "super35") return "super35";
    if (gauge == "16mm") return "16mm";
    if (gauge == "super8") return "super8";
    if (gauge == "4x5") return "4x5";
    return nullptr;
}

/// The glyph outlines of `text`, and the tight box they ink.
bool ink_outlines(const char* text, spk::imprint::Contours& contours,
                  double& x0, double& y0, double& x1, double& y1) {
    // 100 is arbitrary: the box is normalised away. A fine flatness keeps the
    // curves round once the outlines are shrunk to a 1 mm cap height.
    contours = spk::imprint::text_outlines("LiberationSans", 100.0, text, 0.0, 1.0, true, 0.20);
    x0 = y0 = 1e300; x1 = y1 = -1e300;
    for (const auto& c : contours) for (const auto& pt : c) {
        x0 = std::min(x0, pt.x); x1 = std::max(x1, pt.x);
        y0 = std::min(y0, pt.y); y1 = std::max(y1, pt.y);
    }
    return x0 <= x1 && y0 <= y1;
}

/// One mark: the stock's lettering, its tight ink outlines scaled to fill the
/// mark's millimetre box in the reference strip, then mapped into the canvas.
/// `map` takes reference-strip millimetres (x along transport, y across, y-up)
/// and returns canvas pixels: it carries the quarter turn a vertically
/// transporting camera gives the strip, so the glyphs turn with it instead of
/// being squeezed into a tall thin band. `darken` is the official's blend over
/// a picture the camera exposed to the film's edges (`.darken`, per channel
/// min); a mark on the bare rebate is drawn plainly (`.normal`).
template <class Map>
void print_stamp(std::vector<uint8_t>& out, int W, int H,
                 const std::string& text, double rx, double ry, double rw, double rh,
                 Map&& map, const double rgb[3], bool darken) {
    if (text.empty()) return;
    if (!(rw > 0.0) || !(rh > 0.0)) return;
    spk::imprint::Contours contours;
    double x0, y0, x1, y1;
    if (!ink_outlines(text.c_str(), contours, x0, y0, x1, y1)) return;
    const double sx = rw / std::max(1e-9, x1 - x0);
    const double sy = rh / std::max(1e-9, y1 - y0);
    // `text_outlines` is y-down from the baseline and the official path is y-up,
    // where the box's `yMM` is the ink's *bottom*. Flip while normalising into
    // the box, then let the strip's own transform carry the glyphs: on a
    // vertically transporting camera that is a quarter turn, and the letters
    // have to turn with the film rather than be squeezed into its band.
    double px0 = 1e300, py0 = 1e300, px1 = -1e300, py1 = -1e300;
    for (auto& c : contours) {
        for (auto& pt : c) {
            const double u = rx + (pt.x - x0) * sx;
            const double v = ry + rh - (pt.y - y0) * sy;
            double cx, cy;
            map(u, v, cx, cy);
            pt.x = cx; pt.y = cy;
            px0 = std::min(px0, cx); px1 = std::max(px1, cx);
            py0 = std::min(py0, cy); py1 = std::max(py1, cy);
        }
    }
    if (!(px1 > px0) || !(py1 > py0)) return;
    const int ox = int(std::floor(px0)) - 1;
    const int oy = int(std::floor(py0)) - 1;
    const int lw = int(std::ceil(px1)) - ox + 2;
    const int lh = int(std::ceil(py1)) - oy + 2;
    if (lw < 2 || lh < 2) return;
    for (auto& c : contours) for (auto& pt : c) { pt.x -= ox; pt.y -= oy; }
    std::vector<uint8_t> mask(size_t(lw) * size_t(lh), 0);
    spk::imprint::fill(mask, lw, lh, contours, 1.0);
    for (int y = 0; y < lh; ++y) {
        const int Y = oy + y;
        if (Y < 0 || Y >= H) continue;
        for (int x = 0; x < lw; ++x) {
            const double a = double(mask[size_t(y) * size_t(lw) + size_t(x)]) / 255.0;
            if (a <= 0.0) continue;
            const int X = ox + x;
            if (X < 0 || X >= W) continue;
            uint8_t* px = out.data() + (size_t(Y) * size_t(W) + size_t(X)) * 3;
            for (int k = 0; k < 3; ++k) {
                const double dst = double(px[k]) / 255.0;
                const double src = darken ? std::min(dst, rgb[k]) : rgb[k];
                px[k] = to8(dst * (1.0 - a) + src * a);
            }
        }
    }
}

/// One cut of a stock's printing, renumbered: `numbered(_:)` in the official.
struct CutMark { std::string text; double x, y, w, h; };

void cut_marks(const EdgeSet& set, int number, std::vector<CutMark>& out) {
    out.clear();
    for (int i = 0; i < set.count; ++i) {
        const EdgeMark& m = kEdgeMarks[set.first + i];
        CutMark c{m.text, m.x, m.y, m.w, m.h};
        // The stock's marks reading "1" and "1A" are the cut's frame number and
        // half-frame number; every other mark is the stock's own and stays.
        if (c.text == "1") {
            c.text = std::to_string(number);
            c.w = m.w * double(c.text.size());
        } else if (c.text == "1A") {
            c.text = std::to_string(number) + "A";
            c.w = m.w * double(c.text.size()) / 2.0;
        }
        out.push_back(c);
    }
}

/// The 31 cells of a DX code: entry, part 1, blank, part 2, frame number,
/// half-frame flag, blank, even parity over the 18 data bits, exit. The clock
/// track is the same in every code (`DXEdgeCode.cells`).
void dx_cells(int part1, int part2, int frame, bool half,
              std::vector<int>& data, std::vector<int>& clock) {
    std::vector<int> first, second, count;
    for (int i = 0; i < 7; ++i) first.push_back((part1 >> (6 - i)) & 1);
    for (int i = 0; i < 4; ++i) second.push_back((part2 >> (3 - i)) & 1);
    for (int i = 0; i < 6; ++i) count.push_back(((frame & 63) >> (5 - i)) & 1);
    int ones = half ? 1 : 0;
    for (int b : first) ones += b;
    for (int b : second) ones += b;
    for (int b : count) ones += b;
    const int parity = ones % 2 == 1 ? 1 : 0;
    data = {1, 0, 1, 0, 1, 0};
    data.insert(data.end(), first.begin(), first.end());
    data.push_back(0);
    data.insert(data.end(), second.begin(), second.end());
    data.insert(data.end(), count.begin(), count.end());
    data.push_back(half ? 1 : 0);
    data.push_back(0);
    data.push_back(parity);
    data.push_back(0);
    data.push_back(1);
    data.push_back(0);
    data.push_back(1);
    static const char* kClock = "1111101010101010101010101010111";
    clock.clear();
    for (const char* p = kClock; *p; ++p) clock.push_back(*p == '1' ? 1 : 0);
}

/// The stock's documented edge print on this gauge, drawn in its own millimetre
/// boxes. Returns false when the gauge carries none, and the caller then falls
/// back to the older single-inscription heuristic.
bool draw_edge_print(const Request& req, const Layout& L, std::vector<uint8_t>& out) {
    const Gauge* g = gauge_for(req.gauge);
    if (!g || g->instax) return false;
    const char* fmt = edge_format_for(req.gauge);
    if (!fmt) return false;
    const EdgeSet* set = edge_set_for(req.stock.c_str(), fmt);
    if (!set || set->count <= 0) return false;
    // The gauge has documented printing and this call owns it from here: with
    // the switch off the strip stays bare rather than falling back to the
    // single-inscription heuristic, which is not this stock's lettering.
    if (!req.shows_print) return true;
    const double px = L.px_per_mm;
    if (px <= 0.0) return false;
    const int W = L.canvas_w, H = L.canvas_h;

    // The unrotated film in millimetres, recovered from the canvas this layout
    // made: `spanning` and the quarter turn are already in there.
    const double film_x_u = (L.rotated ? double(H) : double(W)) / px;
    const double film_y_u = (L.rotated ? double(W) : double(H)) / px;

    // Reference strip (x along transport, y across, y-up) -> canvas, y-down.
    // The official enters the strip with `translate(widthMM); rotate(90°)`
    // when the camera transports vertically, and the placement may have turned
    // the material again; both turns are folded in here.
    auto to_px = [&](double rx, double ry, double& cx, double& cy) {
        double fx, fy;                                  // unrotated film, y-up
        if (g->transport_x) { fx = rx; fy = ry; }
        else { fx = g->film_x - ry; fy = rx; }
        if (L.rotated) { cx = (film_y_u - fy) * px; cy = (film_x_u - fx) * px; }
        else { cx = fx * px; cy = (film_y_u - fy) * px; }
    };

    const int frames = std::max(1, std::min(L.frames, 4));
    // One frame's advance along the roll (`frameLengthMM` on the spanned cut).
    const double pitch = g->transport_x ? g->film_x : g->film_y;
    int number = 1;
    if (!req.frame_no.empty()) number = std::atoi(req.frame_no.c_str());
    number = std::max(0, std::min(38, number));

    std::vector<CutMark> cut;
    for (int index = 0; index < frames; ++index) {
        const int n = number + index;
        cut_marks(*set, n, cut);
        for (const CutMark& m : cut) {
            const double x = m.x + double(index) * pitch;
            print_stamp(out, W, H, m.text, x, m.y, m.w, m.h, to_px,
                        req.film_ink, L.exposes_edges);
        }
        // The DX number, on the rail below the lower perforations: the frame
        // and its half frame, each starting a quarter of a millimetre past the
        // number printed before it (`FilmEdgePrinting.barcode`).
        if (set->part1 >= 0 && req.draws_bars && std::string(fmt) == "35mm") {
            static const double kCellMM = 0.381;
            static const double kRailTop = 1.80;      // `clockTrackMM.upperBound`
            struct Track { double lo, hi; };
            const Track kData{0.25, 0.76}, kClock{1.30, 1.80};
            for (int k = 0; k < 2; ++k) {
                const bool half = k == 1;
                const double start = half ? 29.7 : 10.2;
                double after = start, best = -1e300;
                for (const CutMark& m : cut) {
                    if (m.y >= kRailTop) continue;
                    if (m.x < start && m.x > best) {
                        best = m.x;
                        after = std::max(start, m.x + m.w + 0.25);
                    }
                }
                // `barcodeRun`: the cut's own code where its cut puts it, moved
                // on by the frames before it.
                after += double(index) * pitch;
                std::vector<int> data, clock;
                dx_cells(set->part1, set->part2, n, half, data, clock);
                for (int pass = 0; pass < 2; ++pass) {
                    const std::vector<int>& cells = pass == 0 ? data : clock;
                    const Track& track = pass == 0 ? kData : kClock;
                    double bx0, by0, bx1, by1;
                    size_t i = 0;
                    while (i < cells.size()) {
                        if (!cells[i]) { ++i; continue; }
                        const size_t first = i;
                        while (i < cells.size() && cells[i]) ++i;
                        const double bx = after + double(first) * kCellMM;
                        const double bw = double(i - first) * kCellMM;
                        // No rotation for bars: they are axis-aligned in the
                        // reference strip, and so are they on the canvas.
                        to_px(bx, track.lo, bx0, by0);
                        to_px(bx + bw, track.hi, bx1, by1);
                        const int X0 = int(std::lround(std::min(bx0, bx1)));
                        const int Y0 = int(std::lround(std::min(by0, by1)));
                        const int X1 = int(std::lround(std::max(bx0, bx1)));
                        const int Y1 = int(std::lround(std::max(by0, by1)));
                        for (int Y = std::max(0, Y0); Y < std::min(H, Y1); ++Y) {
                            uint8_t* row = out.data() + size_t(Y) * size_t(W) * 3;
                            for (int X = std::max(0, X0); X < std::min(W, X1); ++X) {
                                for (int c = 0; c < 3; ++c) {
                                    const double dst = double(row[X * 3 + c]) / 255.0;
                                    const double src = L.exposes_edges
                                            ? std::min(dst, req.film_bar[c]) : req.film_bar[c];
                                    row[X * 3 + c] = to8(src);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    return true;
}

void compose_film(const Request& req, const Layout& L, const uint8_t* src, int sw, int sh,
                  std::vector<uint8_t>& out) {
    const Gauge* g = gauge_for(req.gauge);
    if (!g) return;
    const bool positive = req.kind == Kind::FilmPositive;
    const int W = L.canvas_w, H = L.canvas_h;
    out.assign(size_t(W) * size_t(H) * 3, 0);

    if (g->instax) {
        // An integral positive border: paper white, not a developed strip.
        paper_field(out, W, H, req.paper_rgb);
    } else {
        // **光箱下的片基，不是近黑。** 负片的片边是片基+灰雾的透射色，归一化到
        // 0.9 之后是浅暖色；反转片是最大密度，本来就是深的。这个数由 host 用
        // `pf_density_to_lightbox` 从这支胶片自己的曲线算出来（官方
        // `PrintFrame` 的 `.film` 分支同一条公式）。以前这里写死
        // `{0.227, 0.098, 0.071}`，所以我们的片边比 fotufilm 深一档。
        const double* base = req.film_light;
        for (int y = 0; y < H; ++y) {
            uint8_t* row = out.data() + size_t(y) * size_t(W) * 3;
            for (int x = 0; x < W; ++x) {
                const double v = 1.0 + 0.028 * double(tooth(x, y));
                row[x * 3 + 0] = to8(base[0] * v);
                row[x * 3 + 1] = to8(base[1] * v);
                row[x * 3 + 2] = to8(base[2] * v);
            }
        }
    }

    const double mx0 = double(L.photo_x);
    const double mx1 = double(W - L.photo_x - L.photo_w);
    const double my0 = double(L.photo_y);
    const double my1 = double(H - L.photo_y - L.photo_h);
    // Where the factory's lettering goes: the wider of the two margins. The
    // perforations have their own axis (`transport_x`), below.
    const double band_x = 0.5 * (mx0 + mx1);
    const double band_y = 0.5 * (my0 + my1);
    const bool perf_on_x = band_x > band_y;

    // **The picture, then its markings, then the holes.** A medium-format
    // back's cut is exposed to the film's edges, so the photograph lies *under*
    // the edge print and the perforations: the official draws the strip first
    // and punches and letters it afterwards. The order is the same on every
    // gauge, where the two simply never meet.
    paste(out, W, H, src, sw, sh, L.crop_x, L.crop_y, L.photo_w, L.photo_h, L.photo_x, L.photo_y);

    // The strip's own markings, in the stock's documented millimetre boxes.
    // False means this gauge carries none and the older single-inscription
    // fallback below still has work to do.
    const bool official_print = draw_edge_print(req, L, out);

    // **The die's own pitch and place.** The official app punches every hole on
    // a pitch grid that starts half a pitch in (16 mm starts on the frame
    // line), with the hole's *outside* edge `edge` mm from the film's edge --
    // 2.01 on 135, which leaves a band of base outside the holes. Centring the
    // holes in the margin (what this did) put them against the film's edge and
    // was the first thing that made our strip recognisably not the official's.
    if (g->rows > 0) {
        const double px = L.px_per_mm;
        const bool along_x = (g->transport_x != L.rotated);
        const double hole_along = g->hole_x * px;
        const double hole_across = g->hole_y * px;
        const double edge = g->edge * px;
        const double pitch = g->pitch * px;
        const double along_px = along_x ? double(W) : double(H);
        const double across_px = along_x ? double(H) : double(W);
        const double hole[3] = {positive ? 0.055 : 0.965, positive ? 0.055 : 0.960,
                                positive ? 0.055 : 0.945};
        const double first = (g->pitch > 7.0) ? 0.0 : pitch * 0.5;   // 16 mm on the frame line
        for (int rowi = 0; rowi < g->rows; ++rowi) {
            const double across0 = (rowi == 0) ? edge : (across_px - edge - hole_across);
            for (double centre = first; centre <= along_px + 1e-6; centre += pitch) {
                const double ca = centre, cc = across0 + hole_across * 0.5;
                if (along_x)
                    hole_shape(out, W, H, ca, cc, hole_along, hole_across,
                               g->hole_r * px, g->hole_r <= 0.0, hole);
                else
                    hole_shape(out, W, H, cc, ca, hole_across, hole_along,
                               g->hole_r * px, g->hole_r <= 0.0, hole);
            }
        }
    }

    // The sheet's own notch code, cut out of the film beside the picture.
    draw_sheet_notch(req, L, out);

    // The factory's edge print, in the free strip between the perforation row
    // and the frame -- not over the holes, which is where a naive centre of the
    // band lands. A long inscription shrinks, then trims.
    if (!g->instax && !official_print) {
        const double px = L.px_per_mm;
        const double inner_mm = (g->rows > 0) ? (g->edge + g->hole_y) : 0.0;
        const double inset_y = std::max(1.0, std::min(2.0 * px, band_y * 0.35));
        const double inset_x = std::max(1.0, std::min(2.0 * px, band_x * 0.35));
        if (g->rows == 1) {
            // **One row: the print goes on the edge *without* the holes.** The
            // strip between the perforation and the frame is exactly the
            // perforation's own height, so subtracting `inner_mm` here (as the
            // two-row branch below does) always came out negative. The free
            // strip is the narrower margin, on the opposite edge.
            const double strip = std::max(0.0, std::min(mx0, mx1));
            if (strip >= 0.55 * px) {
                const double room_no = req.frame_no.empty() ? 0.0 : 0.22 * double(H);
                const double avail = double(H) - 2.0 * inset_x - room_no;
                const double base = std::min(2.1 * px, strip * 0.72);
                double em = base;
                std::string text = req.inscription;
                while (em > base * 0.55 && !text.empty() &&
                       spk::imprint::text_width("LiberationSans-Bold", em, text, em * 0.04, 1.0) > avail)
                    em *= 0.93;
                while (!text.empty() &&
                       spk::imprint::text_width("LiberationSans-Bold", em, text, em * 0.04, 1.0) > avail)
                    text.pop_back();
                const double x = double(W) - strip * 0.5 + em * 0.30;
                if (!text.empty())
                    draw_text(out, W, H, x, double(H) - inset_x, em, text,
                              "LiberationSans-Bold", -90.0, req.film_ink);
                if (!req.frame_no.empty()) {
                    const double wnum = spk::imprint::text_width(
                        "LiberationSans-Bold", em, req.frame_no, em * 0.04, 1.0);
                    draw_text(out, W, H, x, inset_x + wnum, em, req.frame_no,
                              "LiberationSans-Bold", -90.0, req.film_ink);
                }
            }
        } else if (g->sheet) {
            // **A sheet carries the emulsion's name along one edge**, in the
            // border the holder leaves -- no perforations, and the notch sits at
            // the opposite corner. 2.5 mm is narrower than the 3 mm the two-row
            // branch asks for, so sheets used to come out blank.
            const double strip = std::max(0.0, std::min(my0, my1));
            if (strip >= 0.8 * px) {
                const double room_no = req.frame_no.empty() ? 0.0 : 0.22 * double(W);
                const double avail = double(W) - 2.0 * inset_y - room_no;
                const double base = std::min(2.1 * px, strip * 0.72);
                double em = base;
                std::string text = req.inscription;
                while (em > base * 0.55 && !text.empty() &&
                       spk::imprint::text_width("LiberationSans-Bold", em, text, em * 0.04, 1.0) > avail)
                    em *= 0.93;
                while (!text.empty() &&
                       spk::imprint::text_width("LiberationSans-Bold", em, text, em * 0.04, 1.0) > avail)
                    text.pop_back();
                const double baseline = double(H) - strip * 0.5 + em * 0.36;
                if (!text.empty())
                    draw_text(out, W, H, inset_y, baseline, em, text,
                              "LiberationSans-Bold", 0.0, req.film_ink);
                if (!req.frame_no.empty()) {
                    const double wnum = spk::imprint::text_width(
                        "LiberationSans-Bold", em, req.frame_no, em * 0.04, 1.0);
                    draw_text(out, W, H, double(W) - inset_y - wnum, baseline, em, req.frame_no,
                              "LiberationSans-Bold", 0.0, req.film_ink);
                }
            }
        }
        // **The band the print can live in is the wider one.** A motion
        // picture's gate very nearly fills the film across its width (Super 35:
        // 18.67 of 18.96 mm), so the free strip is on the *sides* -- 5.04 mm of
        // it -- while the top and bottom bands are 0.145 mm, less than one
        // character. Testing only `band_y >= 3 px` let that sliver win and then
        // compute a zero-height strip, so the stock's name and number vanished
        // instead of running up the side band.
        if (band_y >= 3.0 * px && band_y >= band_x) {
            const double strip = std::max(0.0, band_y - inner_mm * px);
            const double room_no = req.frame_no.empty() ? 0.0 : 0.22 * double(W);
            const double avail = double(W) - 2.0 * inset_y - room_no;
            const double base = std::min(2.1 * px, strip * 0.70);
            double em = base;
            std::string text = req.inscription;
            while (em > base * 0.55 && !text.empty() &&
                   spk::imprint::text_width("LiberationSans-Bold", em, text, em * 0.04, 1.0) > avail)
                em *= 0.93;
            while (!text.empty() &&
                   spk::imprint::text_width("LiberationSans-Bold", em, text, em * 0.04, 1.0) > avail)
                text.pop_back();
            const double baseline = inner_mm * px + strip * 0.5 + em * 0.36;
            if (!text.empty())
                draw_text(out, W, H, inset_y, baseline, em, text,
                          "LiberationSans-Bold", 0.0, req.film_ink);
            if (!req.frame_no.empty()) {
                const double wnum = spk::imprint::text_width(
                    "LiberationSans-Bold", em, req.frame_no, em * 0.04, 1.0);
                draw_text(out, W, H, double(W) - inset_y - wnum, baseline, em, req.frame_no,
                          "LiberationSans-Bold", 0.0, req.film_ink);
            }
        } else if (band_x >= 3.0 * px) {
            // A motion gauge: the print runs up the band beside the frame.
            const double strip = std::max(0.0, band_x - inner_mm * px);
            const double room_no = req.frame_no.empty() ? 0.0 : 0.22 * double(H);
            const double avail = double(H) - 2.0 * inset_x - room_no;
            const double base = std::min(2.1 * px, strip * 0.70);
            double em = base;
            std::string text = req.inscription;
            while (em > base * 0.55 && !text.empty() &&
                   spk::imprint::text_width("LiberationSans-Bold", em, text, em * 0.04, 1.0) > avail)
                em *= 0.93;
            while (!text.empty() &&
                   spk::imprint::text_width("LiberationSans-Bold", em, text, em * 0.04, 1.0) > avail)
                text.pop_back();
            const double x = inner_mm * px + strip * 0.5 + em * 0.30;
            if (!text.empty())
                draw_text(out, W, H, x, double(H) - inset_x, em, text,
                          "LiberationSans-Bold", -90.0, req.film_ink);
            if (!req.frame_no.empty()) {
                const double wnum = spk::imprint::text_width(
                    "LiberationSans-Bold", em, req.frame_no, em * 0.04, 1.0);
                draw_text(out, W, H, x, inset_x + wnum, em, req.frame_no,
                          "LiberationSans-Bold", -90.0, req.film_ink);
            }
        }
    }
}

/// `drawSlideMount`: a plain card with a die-cut rounded aperture. The
/// transparency's own rebate (its maximum density) shows through the aperture
/// around a crop that does not fill it, and the card's thickness leaves a faint
/// shadow on the film along the upper and left edges.
void draw_slide_mount(std::vector<uint8_t>& out, const Layout& L, const Request& req) {
    paper_field(out, L.canvas_w, L.canvas_h, req.card_rgb);
    // The die-cut aperture, filled with the transparency's own maximum density:
    // that is what shows around a crop that does not fill it.
    rounded_rect(out, L.canvas_w, L.canvas_h, double(L.photo_x), double(L.photo_y),
                 double(L.photo_w), double(L.photo_h), kSlideCornerMM * L.px_per_mm,
                 req.film_rgb);
}

/// The photograph into a rounded-rect aperture (`paste`, one pixel a pixel).
void paste_rounded(std::vector<uint8_t>& out, int W, int H, const uint8_t* src, int sw, int sh,
                   int sx, int sy, int w, int h, int dx, int dy, double r) {
    const double cx = dx + w * 0.5, cy = dy + h * 0.5;
    const double rx = std::max(0.0, w * 0.5 - r), ry = std::max(0.0, h * 0.5 - r);
    for (int y = 0; y < h; ++y) {
        const int ty = dy + y;
        if (ty < 0 || ty >= H) continue;
        const int fy = sy + y;
        if (fy < 0 || fy >= sh) continue;
        const uint8_t* srow = src + size_t(fy) * size_t(sw) * 3;
        uint8_t* orow = out.data() + size_t(ty) * size_t(W) * 3;
        for (int x = 0; x < w; ++x) {
            const int tx = dx + x;
            if (tx < 0 || tx >= W) continue;
            const int fx = sx + x;
            if (fx < 0 || fx >= sw) continue;
            const double qx = std::fabs(double(tx) + 0.5 - cx) - rx;
            const double qy = std::fabs(double(ty) + 0.5 - cy) - ry;
            const double ox = std::max(qx, 0.0), oy = std::max(qy, 0.0);
            const double d = std::sqrt(ox * ox + oy * oy) + std::min(std::max(qx, qy), 0.0) - r;
            const double a = std::clamp(0.5 - d, 0.0, 1.0);
            if (a <= 0.0) continue;
            const uint8_t* s3 = srow + size_t(fx) * 3;
            uint8_t* o3 = orow + size_t(tx) * 3;
            for (int k = 0; k < 3; ++k)
                o3[k] = uint8_t(std::clamp(o3[k] * (1.0 - a) + s3[k] * a, 0.0, 255.0));
        }
    }
}

/// The card is a die-cut through stock of finite thickness: a faint shadow on
/// the film along the upper and left edges of the aperture.
void draw_slide_shadow(std::vector<uint8_t>& out, const Layout& L) {
    const int W = L.canvas_w, H = L.canvas_h;
    const int t = std::max(1, int(std::lround(0.35 * L.px_per_mm)));
    // Clipped to the aperture, so the card's rounded cut is not squared off again.
    const double r = kSlideCornerMM * L.px_per_mm;
    const double cx = double(L.photo_x) + L.photo_w * 0.5;
    const double cy = double(L.photo_y) + L.photo_h * 0.5;
    const double rx = std::max(0.0, L.photo_w * 0.5 - r), ry = std::max(0.0, L.photo_h * 0.5 - r);
    auto shade = [&](int x, int y) {
        const double qx = std::fabs(double(x) + 0.5 - cx) - rx;
        const double qy = std::fabs(double(y) + 0.5 - cy) - ry;
        const double ox = std::max(qx, 0.0), oy = std::max(qy, 0.0);
        const double d = std::sqrt(ox * ox + oy * oy) + std::min(std::max(qx, qy), 0.0) - r;
        const double a = std::clamp(0.5 - d, 0.0, 1.0) * 0.18;
        if (a <= 0.0) return;
        uint8_t* p3 = out.data() + (size_t(y) * size_t(W) + size_t(x)) * 3;
        for (int c = 0; c < 3; ++c) p3[c] = to8(p3[c] / 255.0 * (1.0 - a));
    };
    for (int y = std::max(0, L.photo_y); y < std::min(H, L.photo_y + t); ++y)
        for (int x = std::max(0, L.photo_x); x < std::min(W, L.photo_x + L.photo_w); ++x)
            shade(x, y);
    for (int x = std::max(0, L.photo_x); x < std::min(W, L.photo_x + t); ++x)
        for (int y = std::max(0, L.photo_y); y < std::min(H, L.photo_y + L.photo_h); ++y)
            shade(x, y);
}

}  // namespace

const char* kind_id(Kind k) {
    switch (k) {
        case Kind::None: return "none";
        case Kind::Film: return "film";
        case Kind::FilmPositive: return "film-positive";
        case Kind::SlideMount: return "slide-mount";
        case Kind::Paper4x6: return "paper-4x6";
        case Kind::Paper5x7: return "paper-5x7";
        case Kind::Paper8x10: return "paper-8x10";
        case Kind::Paper5x5: return "paper-5x5";
        case Kind::Carrier: return "carrier";
        case Kind::Emulsion: return "emulsion";
        case Kind::WhiteMount: return "white-mount";
        case Kind::BlackMount: return "black-mount";
        case Kind::SocialSquare: return "social-square";
        case Kind::SocialPortrait: return "social-portrait";
        case Kind::SocialStory: return "social-story";
    }
    return "none";
}

const char* kind_label(Kind k) {
    switch (k) {
        case Kind::None: return "No border";
        case Kind::Film: return "Film Border";
        case Kind::FilmPositive: return "Film Border Positive";
        case Kind::SlideMount: return "Slide Mount";
        case Kind::Paper4x6: return "Paper Border 4 x 6";
        case Kind::Paper5x7: return "Paper Border 5 x 7";
        case Kind::Paper8x10: return "Paper Border 8 x 10";
        case Kind::Paper5x5: return "Paper Border 5 x 5";
        case Kind::Carrier: return "Carrier Border";
        case Kind::Emulsion: return "Emulsion Border";
        case Kind::WhiteMount: return "White Mount";
        case Kind::BlackMount: return "Black Mount";
        case Kind::SocialSquare: return "Square Post";
        case Kind::SocialPortrait: return "Portrait Post";
        case Kind::SocialStory: return "Story";
    }
    return "No border";
}

Kind kind_from_id(const std::string& id) {
    for (int i = 0; i <= int(Kind::SocialStory); ++i) {
        const Kind k = Kind(i);
        if (id == kind_id(k)) return k;
    }
    if (id == "paper" || id == "contact" || id == "baryta" || id == "cotton")
        return Kind::Paper4x6;
    if (id == "instant" || id == "film-35") return Kind::Film;
    if (id == "mount") return Kind::WhiteMount;
    if (id == "social") return Kind::SocialSquare;
    return Kind::None;
}

bool is_film_kind(Kind k) { return k == Kind::Film || k == Kind::FilmPositive; }

// **Nothing here is the rebate's any more.** fotufilm frames draw their border
// with `print_frame` on every gauge -- 135 and the 120 frames included -- and
// the first engine's rebate stays with the first engine. The function is kept
// because the host asks it (`pf_draws_film`); it answers "no" for every id, so
// a film kind always composes here.
bool film_gauge_uses_rebate(const std::string&) { return false; }

bool film_gauge_known(const std::string& id) {
    return film_gauge_uses_rebate(id) || gauge_for(id) != nullptr;
}

const char* edge_print_for(const std::string& stock, const std::string& gauge) {
    const EdgePrint* e = nullptr;
    for (const EdgePrint& one : kEdgePrints) if (stock == one.stock) { e = &one; break; }
    if (!e) return "";
    // **A motion gauge prints the motion stock's code, and any other stock's
    // own 135 inscription.** A still film in a Super 8 cartridge is a fantasy
    // the app allows; leaving the strip blank made it look like a bug. The
    // inscription is still the stock's verified one, not an invented one.
    if (gauge == "super35" || gauge == "16mm" || gauge == "super8")
        return e->tmotion ? e->tmotion : (e->t135 ? e->t135 : "");
    if (gauge.rfind("120_", 0) == 0) return e->t120 ? e->t120 : "";
    // An Instax sheet is integral: the white border is the product, and it
    // carries no film edge. A 4x5 sheet does carry the emulsion's name.
    if (gauge.rfind("instax", 0) == 0) return "";
    if (gauge == "4x5") return e->t135 ? e->t135 : "";
    return e->t135 ? e->t135 : "";
}

bool layout(const Request& req, int src_w, int src_h, Layout& out, std::string& error) {
    out = Layout{};
    error.clear();
    if (req.kind == Kind::None) return false;
    if (is_film_kind(req.kind)) {
        // **Every film gauge is drawn here now.** fotufilm frames stopped using
        // the rebate altogether (135 and the 120 frames included), so the old
        // "this gauge belongs to the rebate module" refusal would leave them
        // with no border at all. `film_gauge_uses_rebate` still answers for the
        // first engine's carrier list.
        const Gauge* g = gauge_for(req.gauge);
        if (!g) {
            error = "print frame: unknown film gauge '" + req.gauge + "'";
            return false;
        }
        // **Frames and Camera, from `FilmBorderGeometry.spanning` and
        // `.exposingEdges`.** A panoramic camera advances the film by a
        // frame's length between exposures and puts one picture across them:
        // 135 runs on by 38 mm (eight perforations), 120 by 60 mm. Sheet and
        // instant film are single, and a motion-picture gauge is a fixed gate,
        // so the count is clamped to one there.
        double fx = g->film_x, fy = g->film_y, ax = g->ap_x, ay = g->ap_y;
        double off_x = g->ap_off_x, off_y = g->ap_off_y;
        const bool spans = !g->sheet && !g->instax && (g->transport_x || g->rows == 0);
        const int frames = spans ? std::max(1, std::min(req.frames, 4)) : 1;
        if (frames > 1) {
            const double length = g->transport_x ? g->film_x : g->film_y;
            if (g->transport_x) {
                fx = g->film_x * frames;
                ax = g->ap_x + double(frames - 1) * length;
            } else {
                fy = g->film_y * frames;
                ay = g->ap_y + double(frames - 1) * length;
            }
        }
        // A medium-format camera's 135 gate is taller than the film: the
        // picture runs across the whole strip, perforations and edge print
        // included -- they are exposed over, not drawn on top of it.
        const bool opens = g->transport_x && !g->sheet && !g->instax;
        if (opens && req.medium_format_back) {
            off_y = 0.0;
            ay = fy;
        }
        // **The quarter turn is the aperture's, not the photograph's.**
        // `PrintFramePlacement.layout`: a picture is turned when it and the
        // aperture disagree about which side is longer -- so a portrait gauge
        // on an Instax sheet turns the sheet, not only a portrait file on a
        // 135. Testing `src_h > src_w` alone left a landscape picture cropped
        // into a portrait 46 x 62 window on an 86 mm tall sheet.
        const bool rotated = ax != ay && ((src_w > src_h) != (ax > ay));
        if (rotated) {
            // The official context is y-up and turns the material with
            // `translate(height) + rotate(90°)`: (x, y) -> (H - y, x). This
            // canvas is y-down, so Y = film_y - y and the same turn is
            // (X, Y) -> (Y, film_x - X). Both margins are written exactly, not
            // assumed symmetric -- Instax's are 20 mm against 4 mm.
            const double film_x_before = fx;
            const double off_x_before = off_x, off_y_before = off_y;
            const double ax_before = ax;
            std::swap(fx, fy); std::swap(ax, ay);
            off_x = off_y_before;
            off_y = film_x_before - off_x_before - ax_before;
            out.rotated = true;
        }
        out.frames = frames;
        out.exposes_edges = opens && req.medium_format_back;
        crop_for(src_w, src_h, ax / ay, req.framing_x, req.framing_y,
                 out.crop_x, out.crop_y, out.crop_w, out.crop_h);
        out.px_per_mm = double(out.crop_w) / ax;
        out.canvas_w = std::max(1, int(std::lround(fx * out.px_per_mm)));
        out.canvas_h = std::max(1, int(std::lround(fy * out.px_per_mm)));
        out.photo_w = out.crop_w;
        out.photo_h = out.crop_h;
        // **The aperture sits where the preset puts it**, not centred -- that is
        // what puts 16 mm's picture against the perforation row and an Instax
        // print at the head of its sheet with the chemical pod's band below.
        out.photo_x = std::max(0, int(std::lround(off_x * out.px_per_mm)));
        out.photo_y = std::max(0, int(std::lround(off_y * out.px_per_mm)));
        out.sheet_w_mm = fx;
        out.sheet_h_mm = fy;
        out.valid = true;
        return true;
    }
    if (src_w <= 0 || src_h <= 0) {
        error = "print frame: the picture is empty";
        return false;
    }

    Sheet sheet;
    if (sheet_for(req.kind, margin_scale(req), src_w, src_h, sheet)) {
        const double aw = sheet.w - 2.0 * sheet.margin - 2.0 * sheet.rebate;
        const double ah = sheet.h - 2.0 * sheet.margin - 2.0 * sheet.rebate;
        if (!(aw > 1.0) || !(ah > 1.0)) {
            error = "print frame: the margin leaves no aperture";
            return false;
        }
        crop_for(src_w, src_h, aw / ah, req.framing_x, req.framing_y,
                 out.crop_x, out.crop_y, out.crop_w, out.crop_h);
        out.px_per_mm = double(out.crop_w) / aw;
        out.canvas_w = std::max(1, int(std::lround(sheet.w * out.px_per_mm)));
        out.canvas_h = std::max(1, int(std::lround(sheet.h * out.px_per_mm)));
        out.photo_w = out.crop_w;
        out.photo_h = out.crop_h;
        out.photo_x = (out.canvas_w - out.photo_w) / 2;
        out.photo_y = (out.canvas_h - out.photo_h) / 2;
        out.sheet_w_mm = sheet.w;
        out.sheet_h_mm = sheet.h;
        out.margin_mm = sheet.margin;
        out.rebate_mm = sheet.rebate;
        out.square = std::fabs(sheet.w - sheet.h) < 0.01;
        out.valid = true;
        return true;
    }

    if (req.kind == Kind::SlideMount) {
        // `SlideMountGeometry.preset(formatID)`: a 2 x 2 inch card for 35 mm, a
        // 70 mm square for 120. The photograph is fitted to the aperture's shape
        // and centred in it; the card is drawn around and over it.
        const bool big = edge_format_for(req.gauge) == std::string("120");
        const double card = big ? kSlideCard120MM : kSlideCard35MM;
        const double aw = big ? kSlideApW120MM : kSlideApW35MM;
        const double ah = big ? kSlideApH120MM : kSlideApH35MM;
        crop_for(src_w, src_h, aw / ah, req.framing_x, req.framing_y,
                 out.crop_x, out.crop_y, out.crop_w, out.crop_h);
        out.px_per_mm = double(out.crop_w) / aw;
        out.canvas_w = out.canvas_h = std::max(1, int(std::lround(card * out.px_per_mm)));
        out.photo_w = out.crop_w;
        out.photo_h = out.crop_h;
        out.photo_x = (out.canvas_w - out.photo_w) / 2;
        out.photo_y = (out.canvas_h - out.photo_h) / 2;
        out.sheet_w_mm = out.sheet_h_mm = card;
        out.margin_mm = 0.5 * (card - aw);
        out.rebate_mm = kSlideCornerMM;
        out.square = true;
        out.valid = true;
        return true;
    }

    // The Emulsion Border is a crop-following mount whose band is the film just
    // outside the camera gate: `layout` gives it 7% of the short side across and
    // 11% down. We are not handed a developed band, so `draw_emulsion`
    // synthesises the stock's own unexposed margin at 2% of the short side.
    if (req.kind == Kind::Emulsion) {
        const double scale = margin_scale(req);
        const double short_side = double(std::min(src_w, src_h)) * scale;
        const int band = std::max(3, int(std::lround(0.02 * double(std::min(src_w, src_h)))));
        const int horizontal = int(std::ceil(short_side * 0.07));
        const int vertical = int(std::ceil(short_side * 0.11));
        out.crop_w = src_w; out.crop_h = src_h;
        out.canvas_w = src_w + 2 * band + 2 * horizontal;
        out.canvas_h = src_h + 2 * band + 2 * vertical;
        out.photo_x = horizontal + band;
        out.photo_y = vertical + band;
        out.photo_w = src_w; out.photo_h = src_h;
        out.rebate_mm = double(band);
        out.sheet_w_mm = double(out.canvas_w);
        out.sheet_h_mm = double(out.canvas_h);
        out.px_per_mm = 1.0;
        out.valid = true;
        return true;
    }

    // Mounts and posting canvases are pixel-relative: there is no sheet to print on.
    // `isPlainMount`: 8% of the short side on every side.
    if (req.kind == Kind::WhiteMount || req.kind == Kind::BlackMount) {
        const int m = std::max(1, int(std::ceil(
                double(std::min(src_w, src_h)) * margin_scale(req) * 0.08)));
        out.crop_w = src_w; out.crop_h = src_h;
        out.canvas_w = src_w + 2 * m;
        out.canvas_h = src_h + 2 * m;
        out.photo_x = out.photo_y = m;
        out.photo_w = src_w; out.photo_h = src_h;
        out.sheet_w_mm = double(out.canvas_w);
        out.sheet_h_mm = double(out.canvas_h);
        out.px_per_mm = 1.0;
        out.valid = true;
        return true;
    }

    // `SocialCanvasGeometry`: a fixed aspect with a 5% margin on the short side,
    // the picture fitted inside without cropping (a landscape picture on a story
    // canvas stands between white bands, as posts do).
    double aw = 0.0, ah = 0.0;
    if (req.kind == Kind::SocialSquare) { aw = 1.0; ah = 1.0; }
    else if (req.kind == Kind::SocialPortrait) { aw = 4.0; ah = 5.0; }
    else if (req.kind == Kind::SocialStory) { aw = 9.0; ah = 16.0; }
    if (aw > 0.0) {
        const double aspect = aw / ah;
        const double margin = 0.05 * margin_scale(req) * std::min(1.0, aspect);
        const double ch = std::max(double(src_w) / (aspect - 2.0 * margin),
                                   double(src_h) / (1.0 - 2.0 * margin));
        out.crop_w = src_w; out.crop_h = src_h;
        out.canvas_w = std::max(1, int(std::ceil(ch * aspect)));
        out.canvas_h = std::max(1, int(std::ceil(ch)));
        out.photo_x = (out.canvas_w - src_w) / 2;
        out.photo_y = (out.canvas_h - src_h) / 2;
        out.photo_w = src_w; out.photo_h = src_h;
        out.sheet_w_mm = double(out.canvas_w);
        out.sheet_h_mm = double(out.canvas_h);
        out.px_per_mm = 1.0;
        out.valid = true;
        return true;
    }

    error = "print frame: unknown border kind";
    return false;
}

void compose(const Request& req, const Layout& L, const uint8_t* src, int src_w, int src_h,
             std::vector<uint8_t>& out) {
    if (!L.valid) return;
    if (is_film_kind(req.kind)) { compose_film(req, L, src, src_w, src_h, out); return; }
    out.assign(size_t(L.canvas_w) * size_t(L.canvas_h) * 3, 0);

    // The official's order: the material, then what is drawn on it, then the
    // photograph on top (`drawDeveloped` is the last thing `render` does).
    const int W = L.canvas_w, H = L.canvas_h;
    const bool social = req.kind == Kind::SocialSquare || req.kind == Kind::SocialPortrait
                     || req.kind == Kind::SocialStory;
    const bool black = req.kind == Kind::BlackMount;
    const bool mount = req.kind == Kind::WhiteMount || black;
    if (req.kind == Kind::SlideMount) {
        draw_slide_mount(out, L, req);
        paste_rounded(out, W, H, src, src_w, src_h, L.crop_x, L.crop_y,
                      L.photo_w, L.photo_h, L.photo_x, L.photo_y, kSlideCornerMM * L.px_per_mm);
        draw_slide_shadow(out, L);
        return;                       // the card and the aperture are the frame
    }
    if (social) {
        // Display white, so the post's margin merges with the feed it is shown in.
        const double white[3] = {1.0, 1.0, 1.0};
        paper_field(out, W, H, white);
    } else if (black) {
        // A neutral presentation board, not the paper's own maximum density.
        paper_field(out, W, H, req.black_rgb);
    } else {
        paper_field(out, W, H, req.paper_rgb);
    }
    if (req.kind == Kind::Carrier) {
        draw_carrier(out, L, req);
        draw_lustre(out, L);
    } else if (!mount && req.kind != Kind::Emulsion) {
        draw_lustre(out, L);          // the four paper sheets
    }

    paste(out, L.canvas_w, L.canvas_h, src, src_w, src_h,
          L.crop_x, L.crop_y, L.photo_w, L.photo_h, L.photo_x, L.photo_y);

    if (req.kind == Kind::Emulsion) draw_emulsion(out, L, req);
}

}  // namespace print_frame
