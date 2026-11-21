#ifndef FOTOFILM_HOST_SCREEN_HPP
#define FOTOFILM_HOST_SCREEN_HPP

// Digital Reference: the output medium that is not a sheet.
//
// A negative on `.screen` is developed into a receiver's relative log exposure and then read by
// one of three styles. `referenceExposure` develops along a fixed receiver curve; the other two
// hand the kernel a straight line and put the graded paper curve in the output table, where a
// variable-contrast paper's H&D shape lives.
//
// `autoLevels` -- the default -- re-times each frame the way a minilab scanner does, so its
// shadow half is not a fixed curve but a curve built from the stock's own readings. That is the
// `ScreenShadowToe` below: a log curve in the stops of scene light under mid-grey, finished onto
// the film's read near the base so black stays black at every grade.
//
// Only the table is ported here. The per-frame timing (which stop sets white) rides the kernel's
// `TONE_GRID_A/B` and is not part of this function.

#include "curve.hpp"
#include "film.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace screen_ref {

using V3 = std::array<float, 3>;

/// The receiver's mid-grey density above base, `PrintPaper.screen.midDensity`.
inline constexpr float kAnchorDensity = 0.744f;

/// `.referenceExposure`'s fixed receiver curve.
inline curve::Curve reference_curve() {
    curve::Curve c;
    c.d_min = 0.07f;
    c.gamma = 2.60f;
    c.toe = -0.52f;
    c.toe_width = 0.16f;
    c.shoulder = 0.42f;
    c.shoulder_width = 0.14f;
    return c;
}

/// The graded styles' straight line: the kernel hands the output table the receiver's relative
/// log exposure and the graded curve is applied there.
inline curve::Curve straight_curve() {
    curve::Curve c;
    c.d_min = 0.0f;
    c.gamma = 1.0f;
    c.toe = -0.8f;
    c.toe_width = 0.01f;
    c.shoulder = 0.9f;
    c.shoulder_width = 0.01f;
    return c;
}

/// A levelled positive's straight line.
inline curve::Curve positive_curve() {
    curve::Curve c;
    c.d_min = 0.0f;
    c.gamma = 1.0f;
    c.toe = -kAnchorDensity;
    c.toe_width = 0.01f;
    c.shoulder = 3.5f - kAnchorDensity;
    c.shoulder_width = 0.01f;
    return c;
}

/// CharacteristicCurve.logExposure: bisection on the analytic curve. A sampled record would
/// bracket on its own table; every curve this file builds is analytic.
inline float log_exposure_of(const curve::Curve &c, float target) {
    float lo = c.toe - 6.0f;
    float hi = c.shoulder + 6.0f;
    for (int i = 0; i < 60; ++i) {
        const float mid = (lo + hi) * 0.5f;
        if (c.density(mid) < target) lo = mid; else hi = mid;
    }
    return (lo + hi) * 0.5f;
}

/// DigitalReferenceReceiver.referenceCurve(for:) -- monochrome reads through its legacy paper
/// curve, colour through the receiver's own.
inline curve::Curve reference_curve_for(const film::Spectra &stock) {
    if (stock.monochrome && stock.curves[0].gamma != 0.0f) return stock.curves[0];
    return reference_curve();
}

/// Receiver log exposure, relative to the anchor, where `.referenceExposure` places the film
/// base: 0.03 D short of the curve's asymptote.
inline float reference_base_target(const film::Spectra &stock) {
    const curve::Curve c = reference_curve_for(stock);
    const float anchor = log_exposure_of(c, c.d_min + kAnchorDensity);
    return log_exposure_of(c, c.d_max() - 0.03f) - anchor;
}

/// Shadow-side stretch for `.referenceExposure`: 1 at the anchor, `scale` well below it.
inline float stretch_shadows(float v, float scale) {
    if (!(v > 0.0f)) return v;
    return v + (scale - 1.0f) * v * (1.0f - std::exp(-v / 0.08f));
}

/// The stock's base-to-mid-grey distance on the green record.
inline float base_read(const film::Spectra &stock) {
    if (stock.monochrome) {
        const float d_min = (stock.curves[0].d_min + stock.curves[1].d_min
                             + stock.curves[2].d_min) / 3.0f;
        const float neutral = (stock.curves[0].density(0.0f) + stock.curves[1].density(0.0f)
                               + stock.curves[2].density(0.0f)) / 3.0f;
        return std::max(neutral - d_min, 0.05f);
    }
    const curve::Curve &green = stock.curves[1];
    return std::max(green.density(0.0f) - green.d_min, 0.05f);
}

inline constexpr float kLog10Of2 = 0.30103f;

/// Receiver read at a scene exposure `stops` over mid-grey: denser negative, less light.
inline float read_at(const film::Spectra &stock, float stops) {
    if (stock.monochrome) {
        auto neutral = [&](float x) {
            return (stock.curves[0].density(x) + stock.curves[1].density(x)
                    + stock.curves[2].density(x)) / 3.0f;
        };
        return neutral(0.0f) - neutral(stops * kLog10Of2);
    }
    const curve::Curve &green = stock.curves[1];
    return green.density(0.0f) - green.density(stops * kLog10Of2);
}

// --- the graded curve -------------------------------------------------------

inline constexpr float kGradedAnchorVal = 0.46f;
inline constexpr float kGradedBaseVal = 1.0f;
inline constexpr float kGradedWhiteVal = 0.02f;
inline constexpr float kGradedReadPerVal = 0.73f / 0.54f;
inline constexpr float kReferenceGrade = 2.0f;
inline constexpr float kGradeLow = 0.0f;
inline constexpr float kGradeHigh = 5.0f;

inline float graded_base_read() { return (kGradedBaseVal - kGradedAnchorVal) * kGradedReadPerVal; }
inline float graded_white_read() { return (kGradedWhiteVal - kGradedAnchorVal) * kGradedReadPerVal; }

inline float graded_slope(float grade) {
    const float g = std::min(std::max(grade, kGradeLow), kGradeHigh);
    return 2.894f * std::exp(0.225f * (g - kReferenceGrade));
}

inline float softplus_open(float x) {
    return x > 0.0f ? x + std::log1p(std::exp(-x)) : std::log1p(std::exp(x));
}

/// A graded paper's H&D curve from the unit stretch to display-linear transmittance.
inline float graded_transmittance(float val, float grade = kReferenceGrade) {
    const float k = graded_slope(grade);
    const float v_star = 0.6955f;
    const float pivot = kGradedAnchorVal - v_star / k;
    constexpr float d_min_eff = 0.004f, d_max_eff = 2.295f, a_hl = 3.0f, a_sh = 6.0f;
    float v = k * (val - pivot);
    v += 0.05f * 0.6f * std::tanh((v - v_star) / 0.6f);
    const float v1 = d_min_eff + softplus_open(a_hl * (v - d_min_eff)) / a_hl;
    const float d = d_max_eff - softplus_open(a_sh * (d_max_eff - v1)) / a_sh;
    const float black = std::pow(10.0f, -2.3f);
    return std::min(std::max((std::pow(10.0f, -d) - black) / (1.0f - black), 0.0f), 1.0f);
}

/// The graded styles' shadow half at a paper grade, from the levelled read `relative`.
inline float graded_shadow_read(float relative, float black, float grade) {
    const float ratio = graded_slope(grade) / graded_slope(kReferenceGrade);
    if (ratio == 1.0f) return relative;
    if (!(relative > 0.0f)) return ratio * relative;
    if (!(relative < black) || !(black > 0.0f)) return relative;
    const float u = relative / black;
    return black * ratio * u / (1.0f + (ratio - 1.0f) * u);
}

// --- Auto Levels' shadow half ----------------------------------------------

/// Auto Levels' shadow half: a log curve in the stops of scene light under mid-grey, finished
/// onto the film's read near the base so black stays black at every grade.
struct ShadowToe {
    /// The levelled read at each film stop from `lowest` up, falling as the stops rise.
    std::vector<float> reads;
    float anchor_stops = 0.0f;
    float depth = 0.0f;
    float width = 1.0f;
    float grey = 0.0f;
    float finish_read = 0.0f;
    float black_read = 0.0f;
    float finish_power = 1.0f;

    static constexpr float kGreySlope = 30.0f;
    static constexpr float kRecordedShare = 0.9f;
    static constexpr float kFinishLightness = 6.0f;
    static constexpr float kBlackShare = 0.92f;
    static constexpr float kStride = 0.02f;
    static constexpr float kLowest = -16.0f;

    /// The film stop whose levelled read is `relative`, held at the ends of the table.
    float stops_at(float relative) const {
        if (!(relative < reads.front())) return kLowest;
        if (!(relative > reads.back())) return kLowest + float(reads.size() - 1) * kStride;
        size_t low = 0, high = reads.size() - 1;
        while (high - low > 1) {
            const size_t mid = (low + high) / 2;
            if (reads[mid] > relative) low = mid; else high = mid;
        }
        const float span = reads[low] - reads[high];
        const float fraction = span > 0.0f ? (reads[low] - relative) / span : 0.0f;
        return kLowest + (float(low) + fraction) * kStride;
    }

    /// The levelled read at an arbitrary stop, linearly interpolated in the table.
    float read_at_stop(float stop) const {
        const float position = (stop - kLowest) / kStride;
        const size_t index = size_t(std::min(std::max(int(position), 0), int(reads.size()) - 2));
        return reads[index] + (position - float(index)) * (reads[index + 1] - reads[index]);
    }

    /// Display-linear output for a levelled read on the shadow side of mid-grey.
    float transmittance(float relative) const {
        float lightness;
        if (relative >= finish_read) {
            const float to_black = std::max(black_read - relative, 0.0f)
                                 / std::max(black_read - finish_read, 1e-6f);
            lightness = kFinishLightness * std::pow(std::min(to_black, 1.0f), finish_power);
        } else {
            const float under = std::max(anchor_stops - stops_at(relative), 0.0f);
            lightness = grey * (1.0f - std::log(1.0f + under / width)
                                       / std::log(1.0f + depth / width));
        }
        return lightness > 8.0f ? std::pow((lightness + 16.0f) / 116.0f, 3.0f)
                                : lightness / (24389.0f / 27.0f);
    }
};

/// The shift that lands a highlight `highlight_stops` over mid-grey on the graded white.
inline float retime_shift(const film::Spectra &stock, float highlight_stops) {
    const float scale = graded_base_read() / base_read(stock);
    const float clamped = std::min(std::max(highlight_stops, -12.0f), 12.0f);
    return graded_white_read() - scale * read_at(stock, clamped);
}

inline constexpr float kPrintWhiteOverGrey = 3.0f;

inline ShadowToe make_shadow_toe(const film::Spectra &stock) {
    ShadowToe toe;
    const float base = base_read(stock);
    const float scale = graded_base_read() / base;
    const float shift = retime_shift(stock, kPrintWhiteOverGrey);
    const int count = int((6.0f - ShadowToe::kLowest) / ShadowToe::kStride) + 1;
    toe.reads.resize(size_t(count));
    for (int i = 0; i < count; ++i) {
        toe.reads[size_t(i)] = scale * read_at(stock, ShadowToe::kLowest + float(i) * ShadowToe::kStride)
                             + shift;
    }
    toe.anchor_stops = toe.stops_at(0.0f);
    toe.depth = toe.anchor_stops
              - toe.stops_at(scale * ShadowToe::kRecordedShare * base + shift);
    const float y = graded_transmittance(kGradedAnchorVal);
    toe.grey = 116.0f * std::cbrt(y) - 16.0f;
    // The width that gives the toe `greySlope` at mid-grey.
    float low = 1e-3f, high = 20.0f;
    for (int i = 0; i < 60; ++i) {
        const float mid = (low + high) * 0.5f;
        if (toe.grey / (mid * std::log(1.0f + toe.depth / mid)) > ShadowToe::kGreySlope) low = mid;
        else high = mid;
    }
    toe.width = (low + high) * 0.5f;
    const float span = std::log(1.0f + toe.depth / toe.width);
    const float under = toe.width
        * (std::exp((1.0f - ShadowToe::kFinishLightness / toe.grey) * span) - 1.0f);
    const float stop = toe.anchor_stops - under;
    toe.finish_read = toe.read_at_stop(stop);
    toe.black_read = scale * ShadowToe::kBlackShare * base + shift;
    const float lightness_per_stop = toe.grey / ((toe.width + under) * span);
    const float read_per_stop = (toe.read_at_stop(stop - 0.01f) - toe.read_at_stop(stop + 0.01f)) / 0.02f;
    toe.finish_power = std::max(lightness_per_stop / read_per_stop
                                * (toe.black_read - toe.finish_read) / ShadowToe::kFinishLightness, 1.0f);
    return toe;
}

// --- the output table -------------------------------------------------------

enum class Style { ReferenceExposure, GradedPrint, AutoLevels };

/// DigitalReferenceReceiver.rgb(density:style:stock:grade:). `density` is already the activation
/// times the medium's own curve range.
struct Receiver {
    const film::Spectra &stock;
    Style style;
    float grade;
    curve::Curve reference;
    float floor = 0.0f;
    float mixed_anchor = 0.0f;
    float base_target = 0.0f;
    ShadowToe toe;
    bool have_toe = false;

    Receiver(const film::Spectra &s, Style st, float g)
        : stock(s), style(st), grade(g), reference(reference_curve_for(s)) {
        const float range = reference.d_max() - reference.d_min;
        floor = std::pow(10.0f, -range);
        if (style != Style::ReferenceExposure && !stock.reversal) {
            mixed_anchor = log_exposure_of(reference, reference.d_min + kAnchorDensity);
            base_target = reference_base_target(stock);
            if (style == Style::AutoLevels) {
                toe = make_shadow_toe(stock);
                have_toe = true;
            }
        }
    }

    static float blend_at(float relative) {
        const float t = std::min(std::max((relative + 0.04f) / 0.08f, 0.0f), 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    V3 operator()(const V3 &density) const {
        if (stock.reversal) {
            return {std::pow(10.0f, -density[0]), std::pow(10.0f, -density[1]),
                    std::pow(10.0f, -density[2])};
        }
        if (style == Style::ReferenceExposure) {
            V3 out{};
            for (int c = 0; c < 3; ++c) {
                const float t = std::pow(10.0f, -density[size_t(c)]);
                out[size_t(c)] = std::max((t - floor) / (1.0f - floor), 0.0f);
            }
            return out;
        }
        V3 out{};
        for (int c = 0; c < 3; ++c) {
            const float relative = density[size_t(c)] - kAnchorDensity;
            const float val = relative / kGradedReadPerVal + kGradedAnchorVal;
            const float highlight = graded_transmittance(val, grade);
            float shadow;
            if (have_toe) {
                shadow = toe.transmittance(graded_shadow_read(relative, toe.black_read, grade));
            } else {
                const float graded = graded_shadow_read(relative, graded_base_read(), grade);
                const float shadow_read = stretch_shadows(graded, base_target / graded_base_read());
                const float shadow_density =
                    reference.density(mixed_anchor + shadow_read) - reference.d_min;
                shadow = std::max((std::pow(10.0f, -shadow_density) - floor) / (1.0f - floor), 0.0f);
            }
            const float blend = blend_at(relative);
            out[size_t(c)] = highlight * (1.0f - blend) + shadow * blend;
        }
        return out;
    }
};

}  // namespace screen_ref

#endif
