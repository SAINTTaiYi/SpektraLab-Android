#ifndef FOTU_PRINT_DETAIL_HPP
#define FOTU_PRINT_DETAIL_HPP

// Shadow and Highlight Detail: the print's own hold, one log exposure the same on every
// record. A port of `PrintDetail` (Sources/FotufilmCore/PrintDetail.swift).
//
// A print's deepest tones sit on the paper's shoulder and its brightest on its toe, where a
// stop of the negative moves the density hardly at all, so they crush and clip and lose their
// colour. Lifting them on the finished picture only greys the black and the white; instead the
// printing light moves. `FOTUFILM_CONFIG_PRINT_DETAIL` carries the two strengths, the
// luminance-weighted log exposures of the highlight knee and of the paper's white, and the film
// cube's clear and densest nodes.

#include "curve.hpp"
#include "screen.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace print_detail {

constexpr float kReach = 0.22f;
constexpr float kShadowRise = 0.7f;
constexpr float kShadowFall = 0.75f;
constexpr float kHighlightKneeStops = 1.0f;
constexpr float kWhiteDensity = 0.03f;
constexpr int kCount = 10;
constexpr float kLog10Of2 = 0.30102999566398119521f;

inline const float *weights() {
    static const float w[3] = {0.25f, 0.65f, 0.10f};
    return w;
}

inline float held(float v) { return std::min(std::max(std::isfinite(v) ? v : 0.0f, 0.0f), 1.0f); }

/// Placed through an output table rather than the curve: the knee and white as densities above
/// each curve's D-min. Digital Reference with a graded curve is the only medium that does so.
struct Shown {
    bool valid = false;
    float knee = 0.0f;
    float white = 0.0f;
};

inline Shown detail_densities(const film::Spectra &stock, screen_ref::Style style, float grade) {
    Shown out;
    if (style == screen_ref::Style::ReferenceExposure) return out;
    if (stock.reversal || stock.reflection_print) return out;
    const screen_ref::Receiver receiver(stock, style, grade);
    const double w[3] = {0.2289746, 0.6917385, 0.0792869};
    const auto luminance = [&](float d) {
        const screen_ref::V3 rgb = receiver(screen_ref::V3{d, d, d});
        return float(w[0] * rgb[0] + w[1] * rgb[1] + w[2] * rgb[2]);
    };
    const auto density_showing = [&](float target) {
        float lo = 0.0f, hi = screen_ref::kAnchorDensity;
        for (int i = 0; i < 40; ++i) {
            const float mid = 0.5f * (lo + hi);
            if (luminance(mid) > target) lo = mid; else hi = mid;
        }
        return 0.5f * (lo + hi);
    };
    const float grey = luminance(screen_ref::kAnchorDensity);
    const float knee = density_showing(std::min(grey * std::exp2(kHighlightKneeStops), 0.99f));
    const float paper_white = std::pow(10.0f, -kWhiteDensity);
    const float white = luminance(0.0f) > paper_white ? density_showing(paper_white) : 0.0f;
    if (!std::isfinite(knee) || !std::isfinite(white) || knee <= white) return out;
    out.valid = true;
    out.knee = knee;
    out.white = white;
    return out;
}

/// The ten floats: strengths, knee, white, then six zeros that `corners` fills.
inline void packed(float shadows, float highlights, const curve::Curve curves[3],
                   const float midpoints[3], const Shown &shown, float out[kCount]) {
    for (int i = 0; i < kCount; ++i) out[i] = 0.0f;
    out[0] = held(shadows);
    out[1] = held(highlights);
    float knee = 0.0f, white = 0.0f;
    for (int r = 0; r < 3; ++r) {
        const curve::Curve &c = curves[r];
        const float lightest = c.d_min + (shown.valid ? shown.white : kWhiteDensity);
        const float aim = c.density(midpoints[r]);
        const float bright = shown.valid ? (c.d_min + shown.knee)
            : std::max(aim - kHighlightKneeStops * kLog10Of2, lightest + 0.01f);
        knee += weights()[r] * screen_ref::log_exposure_of(c, bright);
        white += weights()[r] * screen_ref::log_exposure_of(c, lightest);
    }
    if (!std::isfinite(knee) || !std::isfinite(white)) return;
    out[2] = knee;
    out[3] = white;
}

/// The film cube's clear and densest nodes, RGB each: its first and last grid points.
inline void corners(const std::vector<float> &film_lut, int dimension, float out[6]) {
    for (int i = 0; i < 6; ++i) out[i] = 0.0f;
    if (dimension < 2) return;
    const long long cells = static_cast<long long>(dimension) * dimension * dimension;
    const size_t last = static_cast<size_t>((cells - 1) * 4);
    if (film_lut.size() < last + 3) return;
    for (int i = 0; i < 3; ++i) {
        out[i] = film_lut[size_t(i)];
        out[3 + i] = film_lut[last + size_t(i)];
    }
}

}  // namespace print_detail
#endif
