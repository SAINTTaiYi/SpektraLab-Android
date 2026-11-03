// The view layer's tone controls, ported from the engine's own grade kernel.
//
// `src/shaders/vulkan/spk_view_grade_body.glsl` is the reference (which is
// itself the Metal port); the constants below are its constants, in the same
// order and with the same clamps, so a value here means what it means upstream.
//
// It runs on the finished print -- the RGBA16 the renderer hands back -- not
// inside the film pipeline. That keeps every bit-exact property of the pipeline
// intact: with all controls at zero the pass is skipped entirely and the bytes
// are the ones the engine produced.
#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace spk_adjust {

struct ViewAdjust {
    bool enabled = false;
    double temperature = 0.0;   // -100..100  blue <-> amber
    double tint = 0.0;          // -100..100  green <-> magenta
    double exposure = 0.0;      // stops, -3..3
    double contrast = 0.0;      // -100..100
    double brightness = 0.0;    // -100..100
    double saturation = 0.0;    // -100..100
    double vibrance = 0.0;      // -100..100, lifts muted colours more than loud ones
    double highlights = 0.0;    // -100..100
    double shadows = 0.0;       // -100..100
    double black_point = 0.0;   // 0..50
    double white_point = 0.0;   // 0..50
    double vignette = 0.0;      // -100..100
    double vignette_mid = 50.0; // 0..100

    // Four-zone colour balance, in the kernel's own precomputed form:
    // cb[zone][channel] is `cos(hue - 2*pi*c/3) * saturation * 0.25`, and
    // cb_lum[zone] is `luminance * 0.25`. Zones are master, shadows, midtones,
    // highlights -- the same order as `u[16..31]`.
    double cb[4][3] = {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}, {0, 0, 0}};
    double cb_lum[4] = {0, 0, 0, 0};
    bool balance = false;

    // Five curves: rgb, luma, red, green, blue -- 256 entries each, built from
    // the control points with the engine's own monotone cubic.
    bool curves = false;
    float curve[5][256] = {};

    bool neutral() const { return !enabled; }

    void normalise() {
        const bool tone = std::fabs(temperature) > 1e-6 || std::fabs(tint) > 1e-6 ||
                          std::fabs(exposure) > 1e-6 || std::fabs(contrast) > 1e-6 ||
                          std::fabs(brightness) > 1e-6 || std::fabs(saturation) > 1e-6 ||
                          std::fabs(highlights) > 1e-6 || std::fabs(shadows) > 1e-6 ||
                          black_point > 1e-6 || white_point > 1e-6 || std::fabs(vignette) > 1e-6 ||
                          std::fabs(vibrance) > 1e-6;
        enabled = tone || balance || curves;
    }
};

namespace detail {

// Fritsch-Carlson monotone cubic, the same interpolation the engine uses for
// curve LUTs (`view_process.cpp`, shared with CurveMath.swift). Points arrive
// sorted by x and inside 0..1; fewer than two means "identity".
inline double curve_at(const double* xs, const double* ys, int n, double x) {
    if (n < 2) return x;
    if (x <= xs[0]) return ys[0];
    if (x >= xs[n - 1]) return ys[n - 1];
    double d[16], m[16];
    const int segs = n - 1;
    for (int i = 0; i < segs; ++i)
        d[i] = (ys[i + 1] - ys[i]) / std::max(xs[i + 1] - xs[i], 1e-6);
    m[0] = d[0];
    m[segs] = d[segs - 1];
    for (int i = 1; i < segs; ++i)
        m[i] = d[i - 1] * d[i] <= 0 ? 0.0 : (d[i - 1] + d[i]) / 2.0;
    for (int i = 0; i < segs; ++i) {
        if (d[i] == 0.0) continue;
        const double a = m[i] / d[i], b = m[i + 1] / d[i];
        const double s2 = a * a + b * b;
        if (s2 > 9.0) {
            const double t = 3.0 / std::sqrt(s2);
            m[i] = t * a * d[i];
            m[i + 1] = t * b * d[i];
        }
    }
    int i = 0;
    while (i < n - 2 && x > xs[i + 1]) ++i;
    const double h = std::max(xs[i + 1] - xs[i], 1e-6), t = (x - xs[i]) / h;
    const double t2 = t * t, t3 = t2 * t;
    const double v = (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i] +
                     (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1];
    return std::clamp(v, 0.0, 1.0);
}

// Lays out the 256-entry table the shader version samples.
// Linear sample of a 256-entry table, the way the shader reads it.
inline double curve_sample(const float table[256], double v) {
    const double x = std::clamp(v, 0.0, 1.0) * 255.0;
    const int i = int(std::floor(x));
    const double f = x - i;
    const int j = i < 255 ? i + 1 : 255;
    return table[i] * (1.0 - f) + table[j] * f;
}

inline void build_curve(float table[256], const double* xs, const double* ys, int n) {
    for (int i = 0; i < 256; ++i) table[i] = float(curve_at(xs, ys, n, i / 255.0));
}


inline double luma(double r, double g, double b) {
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

}  // namespace detail

// One pixel. `c` is the encoded [0,1] triple the print already carries.
inline void apply_pixel(double c[3], int x, int y, int w, int h, const ViewAdjust& a) {
    constexpr double kPivot = 0.3857;   // u[12] in the kernel
    const double t = a.temperature / 100.0;
    const double g = a.tint / 100.0;
    const double gain[3] = {1.0 + 0.35 * t, 1.0 - 0.22 * g, 1.0 - 0.35 * t};
    const double ev = std::pow(2.0, a.exposure);
    const double contrast = a.contrast / 100.0;
    const double brightness = a.brightness / 100.0;
    const double saturation = 1.0 + a.saturation / 100.0;
    const double highlights = a.highlights / 100.0;
    const double shadows = a.shadows / 100.0;
    // The slider is 0..50 on a percent scale; treating that as a 0..0.5 offset
    // clips half the picture and reads as a brightness slider. A third of it
    // is the range a level control actually wants.
    const double black = a.black_point / 100.0 * 0.3;
    const double white = a.white_point / 100.0 * 0.3;

    for (int i = 0; i < 3; ++i) c[i] *= gain[i];
    if (ev != 1.0) {
        for (int i = 0; i < 3; ++i)
            c[i] = std::pow(std::pow(std::max(c[i], 0.0), 2.2) * ev, 1.0 / 2.2);
    }
    if (contrast != 0.0) {
        for (int i = 0; i < 3; ++i) c[i] = (c[i] - kPivot) * (1.0 + contrast * 1.2) + kPivot;
    }
    if (brightness != 0.0) {
        for (int i = 0; i < 3; ++i) c[i] = std::pow(std::max(c[i], 0.0), 1.0 / (1.0 + brightness * 0.8));
    }
    double l = detail::luma(c[0], c[1], c[2]);
    const double toned = l <= kPivot ? 0.5 * l / kPivot : 0.5 + 0.5 * (l - kPivot) / (1.0 - kPivot);
    if (shadows != 0.0) {
        const double w = 0.25 * (1.0 - toned) * (1.0 - toned);
        for (int i = 0; i < 3; ++i) c[i] += shadows * w * (1.0 - c[i]);
    }
    if (highlights != 0.0) {
        const double w = 0.45 * toned * toned;
        for (int i = 0; i < 3; ++i) c[i] += highlights * w * (highlights > 0.0 ? 1.0 - c[i] : c[i]);
    }
    // Levels, one end at a time: the black point moves the floor and leaves
    // white where it is, the white point does the mirror. Dividing by
    // `1 - black - white` -- what this used to do -- moved *both* ends and read
    // as a second brightness slider.
    if (black > 0.0) {
        const double d = std::max(1.0 - black, 0.05);
        for (int i = 0; i < 3; ++i) c[i] = (c[i] - black) / d;
    }
    if (white > 0.0) {
        const double d = std::max(1.0 - white, 0.05);
        for (int i = 0; i < 3; ++i) c[i] = c[i] / d;
    }
    l = detail::luma(c[0], c[1], c[2]);
    for (int i = 0; i < 3; ++i) c[i] = l + (c[i] - l) * saturation;

    // **Vibrance**: the same pull towards or away from grey, weighted by how
    // little colour a pixel already has -- a muted wall moves, a saturated sign
    // does not. Upstream has no such control, so the curve is ours: chroma is
    // `max-min` before the pull, and the weight is `1 - chroma`.
    if (a.vibrance != 0.0) {
        const double chroma = std::max({c[0], c[1], c[2]}) - std::min({c[0], c[1], c[2]});
        const double k = 1.0 + (a.vibrance / 100.0) * (1.0 - std::clamp(chroma, 0.0, 1.0));
        for (int i = 0; i < 3; ++i) c[i] = l + (c[i] - l) * k;
    }

    // Four-zone colour balance, weighted by tone: shadows and highlights are
    // the two ends, midtones the remainder. Same weights as the kernel.
    if (a.balance) {
        const double lt = l <= kPivot ? 0.5 * l / kPivot : 0.5 + 0.5 * (l - kPivot) / (1.0 - kPivot);
        const double ls = std::clamp(lt, 0.0, 1.0);
        const double ws = (1.0 - ls) * (1.0 - ls), wh = ls * ls;
        const double wm = std::max(1.0 - ws - wh, 0.0);
        for (int i = 0; i < 3; ++i)
            c[i] += a.cb[0][i] + a.cb[1][i] * ws + a.cb[2][i] * wm + a.cb[3][i] * wh;
        const double k = 1.0 + a.cb_lum[0] + a.cb_lum[1] * ws + a.cb_lum[2] * wm + a.cb_lum[3] * wh;
        for (int i = 0; i < 3; ++i) c[i] *= k;
    }

    // Curves, in the kernel's order: luma first (as a ratio), then the RGB
    // master, then each channel's own table.
    if (a.curves) {
        for (int i = 0; i < 3; ++i) c[i] = std::clamp(c[i], 0.0, 1.0);
        const double ly = detail::luma(c[0], c[1], c[2]);
        const double ly2 = detail::curve_sample(a.curve[1], ly);
        const double scale = ly > 1e-4 ? ly2 / ly : 1.0;
        for (int i = 0; i < 3; ++i) c[i] = std::clamp(c[i] * scale, 0.0, 1.0);
        for (int i = 0; i < 3; ++i) c[i] = detail::curve_sample(a.curve[0], c[i]);
        for (int i = 0; i < 3; ++i) c[i] = detail::curve_sample(a.curve[2 + i], c[i]);
    }
    if (a.vignette != 0.0 && w > 0 && h > 0) {
        const double u = (x + 0.5) / double(w), v = (y + 0.5) / double(h);
        const double dx = (u - 0.5) * 2.0, dy = (v - 0.5) * 2.0;
        const double r = std::sqrt(dx * dx + dy * dy);
        const double lo = a.vignette_mid / 100.0 * 1.2, hi = 1.5;
        double f = (r - lo) / std::max(hi - lo, 1e-6);
        f = std::clamp(f, 0.0, 1.0);
        f = f * f * (3.0 - 2.0 * f);   // smoothstep
        const double k = 1.0 + (a.vignette / 100.0) * f * 0.9;
        for (int i = 0; i < 3; ++i) c[i] *= k;
    }
}

// The whole frame, in place. `px` is RGBA16 with `stride_px` pixels per row.
inline void apply(uint16_t* px, uint32_t w, uint32_t h, uint32_t stride_px, const ViewAdjust& a) {
    if (!a.enabled || !px || !w || !h) return;
    for (uint32_t y = 0; y < h; ++y) {
        uint16_t* row = px + size_t(y) * stride_px * 4;
        for (uint32_t x = 0; x < w; ++x) {
            double c[3] = {row[x * 4 + 0] / 65535.0, row[x * 4 + 1] / 65535.0,
                           row[x * 4 + 2] / 65535.0};
            apply_pixel(c, int(x), int(y), int(w), int(h), a);
            for (int i = 0; i < 3; ++i)
                row[x * 4 + i] = uint16_t(std::clamp(c[i], 0.0, 1.0) * 65535.0 + 0.5);
        }
    }
}

}  // namespace spk_adjust
