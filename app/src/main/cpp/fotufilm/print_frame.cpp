#include "print_frame.hpp"
#include "imprint_raster.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace print_frame {
namespace {

constexpr double kSlideCardMM = 50.0;     // a 2 x 2 inch mount
constexpr double kSlideApertureMM = 36.0; // 135's long side

struct Sheet {
    double w = 0, h = 0, margin = 0, rebate = 0;
};

bool sheet_for(Kind k, double margin_mm, int src_w, int src_h, Sheet& s) {
    switch (k) {
        case Kind::Paper4x6:  s = {152.4, 101.6, margin_mm, 0.0}; break;
        case Kind::Paper5x7:  s = {177.8, 127.0, margin_mm, 0.0}; break;
        case Kind::Paper8x10: s = {254.0, 203.2, margin_mm, 0.0}; break;
        case Kind::Paper5x5:  s = {127.0, 127.0, margin_mm, 0.0}; break;
        case Kind::Carrier:   s = {254.0, 203.2, 12.7, 2.5};      break;
        case Kind::Emulsion:  s = {254.0, 203.2, 12.7, 2.5};      break;
        default: return false;
    }
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
    int rows;                // perforation rows along the long edges
    double hole_x, hole_y;   // a perforation, mm (x along the film)
    double hole_r;           // its corner radius, mm
    double pitch;            // mm between holes along the film
    double edge;             // the hole's outside edge from the film's edge, mm
    bool instax;             // an integral positive border, not a strip
    bool sheet;              // an unperforated sheet
};

constexpr Gauge kGauges[] = {
    // 135: KS, 2.794 across x 1.981 along, 4.75 mm pitch, eight a frame.
    {"135",           38.0,  35.0,  36.0,  24.0, 2, 1.981, 2.794, 0.510, 4.750, 0.55, false, false},
    // Super 35: BH, 2.794 x 1.854, 4.74 mm pitch, four a frame.
    {"super35",       35.0,  18.96, 24.92, 18.67, 2, 1.854, 2.794, 0.0,   4.740, 0.55, false, false},
    // 16 mm: a single row at the frame lines.
    {"16mm",          16.0,   7.605, 12.35,  7.42, 1, 1.270, 1.829, 0.250, 7.620, 0.60, false, false},
    // Super 8: Type S (SMPTE ST 149), a single row, a hole at each frame centre.
    {"super8",         7.976, 4.234,  5.79,  4.01, 1, 1.143, 0.914, 0.0,   4.234, 0.35, false, false},
    // 4 x 5: an unperforated sheet (notch codes are not drawn).
    {"4x5",          125.0, 100.0, 120.0,  95.0, 0, 0, 0, 0, 0, 0, false, true},
    // Instax: the integral border, wider at the foot.
    {"instax-mini",    54.0,  86.0,  46.0,  62.0, 0, 0, 0, 0, 0, 0, true, false},
    {"instax-square",  72.0,  86.0,  62.0,  62.0, 0, 0, 0, 0, 0, 0, true, false},
    {"instax-wide",   108.0,  86.0,  99.0,  62.0, 0, 0, 0, 0, 0, 0, true, false},
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

// The sheet's own surface: a wide, very soft fall-off and a paper tooth. Both
// are deliberately below the visibility threshold at a normal viewing distance.
void paper_field(std::vector<uint8_t>& img, int w, int h, const double rgb[3], bool stipple) {
    const double cx = 0.5 * double(w), cy = 0.5 * double(h);
    const double nx = 1.0 / std::max(1.0, cx), ny = 1.0 / std::max(1.0, cy);
    for (int y = 0; y < h; ++y) {
        uint8_t* row = img.data() + size_t(y) * size_t(w) * 3;
        const double dy = (double(y) - cy) * ny;
        for (int x = 0; x < w; ++x) {
            const double dx = (double(x) - cx) * nx;
            const double r2 = dx * dx + dy * dy;
            double v = 1.0 - 0.030 * r2 * r2;                 // a gentle corrner fall-off
            if (stipple) v += 0.0045 * double(tooth(x, y));
            row[x * 3 + 0] = to8(rgb[0] * v);
            row[x * 3 + 1] = to8(rgb[1] * v);
            row[x * 3 + 2] = to8(rgb[2] * v);
        }
    }
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

void draw_carrier(std::vector<uint8_t>& out, const Layout& L) {
    // The clear film base, printed through a filed-out carrier: near black, a
    // trace of warmth from the base's own colour.
    const double ink[3] = {0.055, 0.050, 0.046};
    int x0, y0, x1, y1;
    ring_pixels(L, L.rebate_mm, x0, y0, x1, y1);
    fill(out, L.canvas_w, L.canvas_h, x0, y0, x1, y0 + (L.photo_y - y0), ink);
    fill(out, L.canvas_w, L.canvas_h, x0, L.photo_y + L.photo_h, x1, y1, ink);
    fill(out, L.canvas_w, L.canvas_h, x0, L.photo_y, L.photo_x, L.photo_y + L.photo_h, ink);
    fill(out, L.canvas_w, L.canvas_h, L.photo_x + L.photo_w, L.photo_y, x1,
         L.photo_y + L.photo_h, ink);
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
    const int reach = std::max(2, int(std::lround(1.4 * L.px_per_mm)));
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
               double tone) {
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
                row[xx * 3 + k] = to8((row[xx * 3 + k] / 255.0) * (1.0 - a) + tone * a);
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
struct SheetNotch {
    const char* stock;
    double round_at_mm;   // the round notch's centre, measured from the corner
};
constexpr SheetNotch kSheetNotches[] = {
    {"fp4plus125", 17.0},
    {"hp5plus400", 22.0},
};

/// The notch group runs s = 10..30 mm from the corner, along the long edge.
/// `depth_at` returns how far the material is cut away at `s`, in mm.
double notch_depth(const SheetNotch& n, double s) {
    auto arc = [](double d, double half_width, double depth) {
        const double u = d / half_width;
        if (u <= -1.0 || u >= 1.0) return 0.0;
        return depth * std::sqrt(1.0 - u * u);
    };
    const double corner_arc = arc(s - 12.5, 2.5, 1.0);    // s = 10..15
    const double far_arc = arc(s - 27.5, 2.5, 1.0);       // s = 25..30
    const double round = arc(s - n.round_at_mm, 1.2, 1.2); // next to one arc
    return std::max(corner_arc, std::max(far_arc, round));
}

/// Cut the code out of the sheet: the notch is empty film, so the scan's light
/// shows through. Drawn along the sheet's upper edge, at its right-hand corner
/// (`docs/print-frame-model.md`: "at the upper right with the sheet upright and
/// the emulsion facing the viewer, and rotate with the sheet").
void draw_sheet_notch(const Request& req, const Layout& L, std::vector<uint8_t>& out) {
    if (req.gauge != "4x5") return;
    const SheetNotch* code = nullptr;
    for (const SheetNotch& n : kSheetNotches)
        if (req.stock == n.stock) { code = &n; break; }
    if (!code) return;
    const double px = L.px_per_mm;
    if (!(px > 0.0)) return;
    const int W = L.canvas_w, H = L.canvas_h;
    const double tone[3] = {0.965, 0.960, 0.945};   // the scan's light
    for (int x = 0; x < W; ++x) {
        const double s = (double(W) - double(x)) / px;   // mm from the corner
        if (s < 9.0 || s > 31.0) continue;
        const double depth = notch_depth(*code, s) * px;
        if (depth <= 0.0) continue;
        const int last = std::min(H, int(std::ceil(depth)));
        for (int y = 0; y < last; ++y) {
            // One pixel of coverage at the notch's wall, so the cut is not a
            // stair-step at 6 px/mm.
            const double cover = std::clamp(depth - double(y), 0.0, 1.0);
            if (cover <= 0.0) break;
            uint8_t* p = out.data() + (size_t(y) * size_t(W) + size_t(x)) * 3;
            for (int k = 0; k < 3; ++k)
                p[k] = to8((p[k] / 255.0) * (1.0 - cover) + tone[k] * cover);
        }
    }
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
        paper_field(out, W, H, req.paper_rgb, true);
    } else {
        const double hi = std::max(req.film_rgb[0], std::max(req.film_rgb[1], req.film_rgb[2]));
        const double lo = std::min(req.film_rgb[0], std::min(req.film_rgb[1], req.film_rgb[2]));
        const bool mono = (hi - lo) < 0.12;
        const double base[3] = {mono ? 0.140 : 0.227, mono ? 0.140 : 0.098, mono ? 0.145 : 0.071};
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
    // The perforations sit on the axis with the wider margin: above and below a
    // 135 frame, beside a motion-picture frame (its gate is nearly the full film
    // width). A one-row gauge is offset in `layout`, so its two margins differ
    // and only their sum is symmetric.
    const double band_x = 0.5 * (mx0 + mx1);
    const double band_y = 0.5 * (my0 + my1);
    const bool perf_on_x = band_x > band_y;

    if (g->rows > 0) {
        const double px = L.px_per_mm;
        const double hole_along = g->hole_x * px;
        const double hole_across = g->hole_y * px;
        const double r = g->hole_r * px;
        const double pitch = g->pitch * px;
        const double edge = g->edge * px;
        const double hole[3] = {positive ? 0.055 : 0.965, positive ? 0.055 : 0.960,
                                positive ? 0.055 : 0.945};
        const int long_px = perf_on_x ? H : W;
        const int short_px = perf_on_x ? W : H;
        const int n = std::max(1, int(std::floor(double(long_px) / pitch)));
        const double start = (double(long_px) - double(n) * pitch) * 0.5;
        for (int rowi = 0; rowi < g->rows; ++rowi) {
            const double across = (rowi == 0) ? edge : (double(short_px) - edge - hole_across);
            for (int i = 0; i < n; ++i) {
                const double along = start + double(i) * pitch + (pitch - hole_along) * 0.5;
                if (perf_on_x) rounded_rect(out, W, H, across, along, hole_across, hole_along, r, hole);
                else rounded_rect(out, W, H, along, across, hole_along, hole_across, r, hole);
            }
        }
    }

    paste(out, W, H, src, sw, sh, L.crop_x, L.crop_y, L.photo_w, L.photo_h, L.photo_x, L.photo_y);

    // The sheet's own notch code, cut out of the film beside the picture.
    draw_sheet_notch(req, L, out);

    // The factory's edge print, in the free strip between the perforation row
    // and the frame -- not over the holes, which is where a naive centre of the
    // band lands. A long inscription shrinks, then trims.
    if (!g->instax) {
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
                              "LiberationSans-Bold", -90.0, 0.94);
                if (!req.frame_no.empty()) {
                    const double wnum = spk::imprint::text_width(
                        "LiberationSans-Bold", em, req.frame_no, em * 0.04, 1.0);
                    draw_text(out, W, H, x, inset_x + wnum, em, req.frame_no,
                              "LiberationSans-Bold", -90.0, 0.94);
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
                              "LiberationSans-Bold", 0.0, 0.94);
                if (!req.frame_no.empty()) {
                    const double wnum = spk::imprint::text_width(
                        "LiberationSans-Bold", em, req.frame_no, em * 0.04, 1.0);
                    draw_text(out, W, H, double(W) - inset_y - wnum, baseline, em, req.frame_no,
                              "LiberationSans-Bold", 0.0, 0.94);
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
                          "LiberationSans-Bold", 0.0, 0.94);
            if (!req.frame_no.empty()) {
                const double wnum = spk::imprint::text_width(
                    "LiberationSans-Bold", em, req.frame_no, em * 0.04, 1.0);
                draw_text(out, W, H, double(W) - inset_y - wnum, baseline, em, req.frame_no,
                          "LiberationSans-Bold", 0.0, 0.94);
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
                          "LiberationSans-Bold", -90.0, 0.94);
            if (!req.frame_no.empty()) {
                const double wnum = spk::imprint::text_width(
                    "LiberationSans-Bold", em, req.frame_no, em * 0.04, 1.0);
                draw_text(out, W, H, x, inset_x + wnum, em, req.frame_no,
                          "LiberationSans-Bold", -90.0, 0.94);
            }
        }
    }
}

void draw_slide_bevel(std::vector<uint8_t>& out, const Layout& L) {
    const int p = std::max(1, int(std::lround(0.35 * L.px_per_mm)));
    for (int i = 0; i < p; ++i) {
        const double k = 1.0 - double(i) / double(p);
        const double light = 1.0 + 0.10 * k, dark = 1.0 - 0.22 * k;
        const int x0 = L.photo_x - p + i, y0 = L.photo_y - p + i;
        const int x1 = L.photo_x + L.photo_w + p - i, y1 = L.photo_y + L.photo_h + p - i;
        auto mix = [&](int x, int y, double f) {
            if (x < 0 || y < 0 || x >= L.canvas_w || y >= L.canvas_h) return;
            uint8_t* px = out.data() + (size_t(y) * size_t(L.canvas_w) + size_t(x)) * 3;
            px[0] = to8(px[0] / 255.0 * f); px[1] = to8(px[1] / 255.0 * f);
            px[2] = to8(px[2] / 255.0 * f);
        };
        for (int x = x0; x < x1; ++x) { mix(x, y0, light); mix(x, y1 - 1, dark); }
        for (int y = y0; y < y1; ++y) { mix(x0, y, light); mix(x1 - 1, y, dark); }
    }
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

// The gates the `rebate` module lays out. 135 and the 120 frames keep it --
// it draws a developed strip with fog, leaks and the factory's imprint.
bool film_gauge_uses_rebate(const std::string& id) {
    static const char* kRebate[] = {"135", "135_half", "135_xpan", "120_645", "120_6x6",
                                    "120_6x7", "120_6x8", "120_6x9", "120_6x12", "120_6x17"};
    for (const char* r : kRebate) if (id == r) return true;
    return false;
}

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
        if (film_gauge_uses_rebate(req.gauge)) {
            error = "film borders on this gauge are drawn by the rebate module";
            return false;
        }
        const Gauge* g = gauge_for(req.gauge);
        if (!g) {
            error = "print frame: unknown film gauge '" + req.gauge + "'";
            return false;
        }
        const bool portrait = src_h > src_w;
        double fx = g->film_x, fy = g->film_y, ax = g->ap_x, ay = g->ap_y;
        if (portrait) { std::swap(fx, fy); std::swap(ax, ay); }
        crop_for(src_w, src_h, ax / ay, req.framing_x, req.framing_y,
                 out.crop_x, out.crop_y, out.crop_w, out.crop_h);
        out.px_per_mm = double(out.crop_w) / ax;
        out.canvas_w = std::max(1, int(std::lround(fx * out.px_per_mm)));
        out.canvas_h = std::max(1, int(std::lround(fy * out.px_per_mm)));
        out.photo_w = out.crop_w;
        out.photo_h = out.crop_h;
        out.photo_x = (out.canvas_w - out.photo_w) / 2;
        // An Instax sheet puts the picture at its head: the side margin above,
        // the chemical pod's band below.
        out.photo_y = g->instax
            ? int(std::lround(0.5 * (fx - ax) * out.px_per_mm))
            : (out.canvas_h - out.photo_h) / 2;
        // **One row of holes is not a symmetric frame.** 16 mm and Super 8 are
        // perforated along one edge only, so the aperture is offset away from
        // it: that edge keeps exactly `edge + hole` of film and the opposite one
        // keeps the remainder -- which is where the factory printed the stock's
        // name. Centring instead made the holes cut into the picture (16 mm
        // wants 2.43 mm of the 1.82 mm it had) and left the inscription a
        // negative strip, so both gauges came out with no lettering at all.
        if (g->rows == 1) {
            const double free_x = fx - ax, free_y = fy - ay;
            const bool perf_on_x = free_x > free_y;        // same rule as compose_film
            const double perf_side = g->edge + g->hole_y;  // hole_y is the across size
            if (perf_on_x) {
                out.photo_x = int(std::lround(perf_side * out.px_per_mm));
                out.photo_y = (out.canvas_h - out.photo_h) / 2;
            } else {
                out.photo_y = int(std::lround(perf_side * out.px_per_mm));
                out.photo_x = (out.canvas_w - out.photo_w) / 2;
            }
        }
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
    if (sheet_for(req.kind, req.margin_mm, src_w, src_h, sheet)) {
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
        const bool portrait = src_h > src_w;
        const double aw = portrait ? 24.0 : kSlideApertureMM;
        const double ah = portrait ? kSlideApertureMM : 24.0;
        crop_for(src_w, src_h, aw / ah, req.framing_x, req.framing_y,
                 out.crop_x, out.crop_y, out.crop_w, out.crop_h);
        out.px_per_mm = double(out.crop_w) / aw;
        out.canvas_w = out.canvas_h = std::max(1, int(std::lround(kSlideCardMM * out.px_per_mm)));
        out.photo_w = out.crop_w;
        out.photo_h = out.crop_h;
        out.photo_x = (out.canvas_w - out.photo_w) / 2;
        out.photo_y = (out.canvas_h - out.photo_h) / 2;
        out.sheet_w_mm = out.sheet_h_mm = kSlideCardMM;
        out.margin_mm = 0.5 * (kSlideCardMM - aw);
        out.square = true;
        out.valid = true;
        return true;
    }

    // Mounts and posting canvases are pixel-relative: there is no sheet to print on.
    if (req.kind == Kind::WhiteMount || req.kind == Kind::BlackMount) {
        const int m = std::max(1, int(std::lround(0.04 * double(std::max(src_w, src_h)))));
        out.crop_w = src_w; out.crop_h = src_h;
        out.canvas_w = src_w + 2 * m;
        out.canvas_h = src_h + 2 * m;
        out.photo_x = out.photo_y = m;
        out.photo_w = src_w; out.photo_h = src_h;
        out.px_per_mm = 1.0;
        out.valid = true;
        return true;
    }

    double target = 0.0;
    if (req.kind == Kind::SocialSquare) target = 1.0;
    else if (req.kind == Kind::SocialPortrait) target = 4.0 / 5.0;
    else if (req.kind == Kind::SocialStory) target = 9.0 / 16.0;
    if (target > 0.0) {
        const int m = std::max(1, int(std::lround(0.06 * double(std::max(src_w, src_h)))));
        int cw = src_w + 2 * m, ch = src_h + 2 * m;
        if (double(cw) / double(ch) < target) cw = int(std::lround(double(ch) * target));
        else ch = int(std::lround(double(cw) / target));
        out.crop_w = src_w; out.crop_h = src_h;
        out.canvas_w = cw; out.canvas_h = ch;
        out.photo_x = (cw - src_w) / 2;
        out.photo_y = (ch - src_h) / 2;
        out.photo_w = src_w; out.photo_h = src_h;
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

    const bool card = req.kind == Kind::SlideMount;
    const bool black = req.kind == Kind::BlackMount;
    if (card) {
        paper_field(out, L.canvas_w, L.canvas_h, req.card_rgb, false);
    } else if (black) {
        paper_field(out, L.canvas_w, L.canvas_h, req.black_rgb, false);
    } else {
        paper_field(out, L.canvas_w, L.canvas_h, req.paper_rgb, req.stipple);
    }

    paste(out, L.canvas_w, L.canvas_h, src, src_w, src_h,
          L.crop_x, L.crop_y, L.photo_w, L.photo_h, L.photo_x, L.photo_y);

    if (req.kind == Kind::Carrier) draw_carrier(out, L);
    else if (req.kind == Kind::Emulsion) draw_emulsion(out, L, req);
    else if (req.kind == Kind::SlideMount) draw_slide_bevel(out, L);
}

}  // namespace print_frame
