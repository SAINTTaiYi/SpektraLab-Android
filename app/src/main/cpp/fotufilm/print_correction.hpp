#ifndef FOTU_PRINT_CORRECTION_HPP
#define FOTU_PRINT_CORRECTION_HPP

// `neutralPrintingBalance`, from `SpectralModel.swift:2492`: the per-channel print
// exposure scale a `printCorrection` control interpolates toward. A colour negative on a
// reflection paper prints neutral only when each record's exposure is scaled a little;
// the scale is the one that minimises the printed wedge's departure from grey over 41
// stops, solved channel by channel from [1,1,1].
//
// Only a print that takes the correction has a balance: a monochrome negative, a
// reversal, a viewed negative and the Digital Reference all return [1,1,1], and so does
// anything that delivers code values.

#include "film.hpp"
#include "paper.hpp"
#include "receiver.hpp"
#include "screen.hpp"
#include "xenon.hpp"
#include "film_projected.hpp"

#include <array>
#include <cmath>
#include <cstdlib>
#include <cstdio>
#include <vector>

namespace print_correction {

constexpr float kLog10Of2 = 0.30102999566398119521f;

/// `PrintStock.Process.midDensity`: a reflection print's nominal visual mid-grey, and the
/// density above base that reads at it (its 0/45 measurement, no viewing glare).
inline float mid_density(const paper::Stock &sheet) { return sheet.is_projected() ? 1.0f : 0.744f; }

/// `referenceViewingLight(for:)`: the judging booth for a reflection print, the filtered
/// 5400 K xenon of a calibrated projector for a release print.
inline spectral::Spd viewing_light(const paper::Stock &sheet) {
    return sheet.is_projected() ? spectral::xenon_projection() : spectral::daylight(5003.0f);
}

/// Whether the sheet delivers code values rather than densities (the Cineon straight line
/// of VISION 2383 and 2393). Those take their published LAD aims, not a 0.744 anchor, and
/// they do not accept a print correction.
inline bool delivers_code_values(const paper::Stock &sheet) {
    return sheet.is_projected() && sheet.lad_status_a[0] <= 0.0f && sheet.lad_status_a[1] <= 0.0f
        && sheet.lad_status_a[2] <= 0.0f;
}

/// `printCurves(for:)`: a monochrome negative prints through its timing record three
/// times, a colour negative through the sheet's own three.
inline void print_curves(const film::Spectra &stock, const paper::Stock &sheet,
                         curve::Curve out[3]) {
    for (int c = 0; c < 3; ++c) out[c] = sheet.curves[stock.monochrome ? 1 : size_t(c)];
}

/// `solveReflectionPrintSetup`: the density offset that lands the paper's mid-grey on its
/// visual mid under `light`, as a Newton solve on a numerical 3x3 Jacobian.
inline std::array<float, 3> reflection_trim(const paper::Stock &sheet, const spectral::Spd &light) {
    std::array<float, 3> offset{0.0f, 0.0f, 0.0f};
    receiver::Unmix unmix(sheet.spectra.analytical_dyes);
    const float anchor = mid_density(sheet);
    const float target = std::pow(10.0f, -anchor);
    const auto printed = [&](const std::array<float, 3> &o) {
        const receiver::V3 d = {anchor + o[0], anchor + o[1], anchor + o[2]};
        const receiver::V3 amounts = unmix.amounts_for(d);
        return receiver::transmission_rgb(amounts, sheet.spectra.analytical_dyes, light);
    };
    for (int step = 0; step < 24; ++step) {
        const receiver::V3 base = printed(offset);
        const receiver::V3 residual = {base[0] - target, base[1] - target, base[2] - target};
        if (std::max(std::fabs(residual[0]), std::max(std::fabs(residual[1]),
                                                    std::fabs(residual[2]))) < 1e-7f) break;
        const float epsilon = 1e-3f;
        receiver::V3 columns[3];
        for (int c = 0; c < 3; ++c) {
            std::array<float, 3> stepped = offset;
            stepped[size_t(c)] += epsilon;
            const receiver::V3 delta = printed(stepped);
            columns[c] = {(delta[0] - base[0]) / epsilon, (delta[1] - base[1]) / epsilon,
                          (delta[2] - base[2]) / epsilon};
        }
        const auto cross = [](const receiver::V3 &a, const receiver::V3 &b) {
            return receiver::V3{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2],
                                a[0] * b[1] - a[1] * b[0]};
        };
        const auto dot = [](const receiver::V3 &a, const receiver::V3 &b) {
            return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        };
        const float determinant = dot(columns[0], cross(columns[1], columns[2]));
        if (std::fabs(determinant) <= 1e-12f) break;
        const receiver::V3 stepv = {
            dot(residual, cross(columns[1], columns[2])) / determinant,
            dot(columns[0], cross(residual, columns[2])) / determinant,
            dot(columns[0], cross(columns[1], residual)) / determinant};
        for (int c = 0; c < 3; ++c) offset[size_t(c)] -= stepv[size_t(c)];
    }
    return offset;
}

/// `paper.printExposureMidpoints(for:digitalReference:)` for the media a correction exists on.
inline void exposure_midpoints(const film::Spectra &stock, const paper::Stock &sheet,
                               const std::array<float, 3> &trim, float out[3]) {
    curve::Curve curves[3];
    print_curves(stock, sheet, curves);
    if (delivers_code_values(sheet)) {
        out[0] = out[1] = out[2] = 0.0f;
        return;
    }
    if (sheet.is_projected() && sheet.lad_status_a[0] > 0.0f && !stock.monochrome
            && !stock.reversal) {
        for (int c = 0; c < 3; ++c) {
            out[c] = screen_ref::log_exposure_of(curves[c], sheet.lad_status_a[c]);
        }
        return;
    }
    const float anchor = mid_density(sheet);
    for (int c = 0; c < 3; ++c) {
        const float target = curves[c].d_min + anchor + (sheet.is_projected() ? 0.0f : trim[size_t(c)]);
        out[c] = screen_ref::log_exposure_of(curves[c], target);
    }
}

/// The engine's own structural gate: a monochrome negative, a reversal, a viewed negative,
/// a positive paper and anything with code values prints its records already balanced.
/// A release print *does* take one -- its beams are re-solved against the LAD aims, which
/// is exactly a per-channel exposure scale.
inline bool accepts_correction(const film::Spectra &stock, const paper::Stock &sheet) {
    return !stock.monochrome && !stock.reversal && !stock.reflection_print
        && !sheet.is_positive_paper() && !delivers_code_values(sheet);
}

/// `printingIllumination` for a projected print: the three printer beams solved for the
/// sheet's published Status A aims, normalised by the peak, with the aims over that peak
/// as the reference energy.
inline void projected_illumination(const film::Spectra &stock, const paper::Stock &sheet,
                                   const std::vector<float> *offset, spectral::Spd &lamp,
                                   std::array<float, 3> &mid_energy) {
    const std::array<float, 3> mid = {stock.curves[0].density(0.0f), stock.curves[1].density(0.0f),
                                      stock.curves[2].density(0.0f)};
    const projected::V3 aim = projected::printing_aim(sheet);
    const spectral::Spd raw =
        projected::release_printer_lamp(mid, stock, sheet.spectra.sensitivity, aim, offset);
    float peak = 0.0f;
    for (float v : raw) peak = std::max(peak, v);
    peak = std::max(peak, 1e-12f);
    for (int i = 0; i < spectral_grid::kCount; ++i) lamp[size_t(i)] = raw[size_t(i)] / peak;
    mid_energy = {aim[0] / peak, aim[1] / peak, aim[2] / peak};
}

/// `neutralPrintingBalance(for:paper:)`: minimise the printed wedge's departure from grey
/// over 41 stops, one channel at a time, the green held at one.
inline void neutral_printing_balance(const film::Spectra &stock, const paper::Stock &sheet,
                                     float out[3]) {
    std::array<float, 3> scale{1.0f, 1.0f, 1.0f};
    out[0] = out[1] = out[2] = 1.0f;
    if (!accepts_correction(stock, sheet)) return;

    const spectral::Spd light = viewing_light(sheet);
    std::vector<float> film_offset;
    const bool has_offset = film::density_offset(stock, stock.curves, 1.0f, film_offset);
    const std::vector<float> *off = has_offset ? &film_offset : nullptr;
    std::array<float, 3> mid_density_of{stock.curves[0].density(0.0f),
                                        stock.curves[1].density(0.0f),
                                        stock.curves[2].density(0.0f)};
    spectral::Spd lamp{};
    std::array<float, 3> mid_energy{};
    if (sheet.is_projected()) {
        projected_illumination(stock, sheet, off, lamp, mid_energy);
    } else {
        lamp = spectral::planckian(3200.0f);
        film::paper_exposure(mid_density_of, stock, lamp, sheet.spectra.sensitivity, 0.0f, off,
                             mid_energy);
    }

    curve::Curve curves[3];
    print_curves(stock, sheet, curves);
    const std::array<float, 3> trim = sheet.is_projected()
        ? std::array<float, 3>{0.0f, 0.0f, 0.0f} : reflection_trim(sheet, light);
    float x_mids[3];
    exposure_midpoints(stock, sheet, trim, x_mids);

    const int kStops = 41;
    std::vector<receiver::V3> relatives(static_cast<size_t>(kStops));
    for (int i = 0; i < kStops; ++i) {
        const float stops = -5.0f + 0.25f * float(i);
        std::array<float, 3> density{};
        for (int c = 0; c < 3; ++c) {
            density[size_t(c)] = stock.curves[size_t(c)].density(stops * kLog10Of2);
        }
        std::array<float, 3> energy{};
        film::paper_exposure(density, stock, lamp, sheet.spectra.sensitivity, 0.0f,
                             has_offset ? &film_offset : nullptr, energy);
        relatives[size_t(i)] = {
            std::log10(std::max(energy[0], 1e-12f) / std::max(mid_energy[0], 1e-12f)),
            std::log10(std::max(energy[1], 1e-12f) / std::max(mid_energy[1], 1e-12f)),
            std::log10(std::max(energy[2], 1e-12f) / std::max(mid_energy[2], 1e-12f))};
    }

    const bool unmix = !stock.monochrome;
    receiver::Unmix dye_unmix(sheet.spectra.analytical_dyes);
    const auto viewed = [&](const receiver::V3 &relative, const std::array<float, 3> &scale) {
        receiver::V3 printed{};
        for (int c = 0; c < 3; ++c) {
            printed[size_t(c)] = curves[size_t(c)].density(x_mids[c] + scale[size_t(c)]
                                                           * relative[size_t(c)])
                               - curves[size_t(c)].d_min;
        }
        if (unmix) {
            const receiver::V3 amounts = dye_unmix.amounts_for(printed);
            return receiver::transmission_rgb(amounts, sheet.spectra.analytical_dyes, light);
        }
        return receiver::transmission_rgb(printed, sheet.spectra.dyes, light);
    };
    const auto cost = [&](const std::array<float, 3> &scale) {
        float total = 0.0f;
        for (const receiver::V3 &relative : relatives) {
            const receiver::V3 rgb = viewed(relative, scale);
            const float g = std::log10(std::max(rgb[1], 1e-6f));
            const float dr = std::log10(std::max(rgb[0], 1e-6f)) - g;
            const float db = std::log10(std::max(rgb[2], 1e-6f)) - g;
            total += dr * dr + db * db;
        }
        return total;
    };

    // Golden-section refine inside a coarse bracket, the green held at one: the cost does
    // not see a common scale, so it would stay wherever it started.
    const auto refine = [&](int channel, float low, float high) {
        const float phi = 0.6180339887498949f;
        std::array<float, 3> trial = scale;
        auto at = [&](float v) {
            trial[size_t(channel)] = v;
            return cost(trial);
        };
        float a = low, b = high;
        float c = b - phi * (b - a), d = a + phi * (b - a);
        float fc = at(c), fd = at(d);
        for (int i = 0; i < 40; ++i) {
            if (fc < fd) { b = d; d = c; fd = fc; c = b - phi * (b - a); fc = at(c); }
            else { a = c; c = d; fc = fd; d = a + phi * (b - a); fd = at(d); }
        }
        out[size_t(channel)] = 0.5f * (a + b);
    };

    for (int channel = 0; channel < 3; ++channel) {
        if (channel == 1) continue;
        // A coarse sweep finds the basin; the golden section refines it.
        const float start = scale[size_t(channel)];
        float best = start, best_cost = cost(scale);
        const bool trace = std::getenv("PC_DEBUG_CURVE") != nullptr;
        for (int k = 0; k <= 200; ++k) {
            const float v = 0.5f + 0.005f * float(k);
            std::array<float, 3> trial = scale;
            trial[size_t(channel)] = v;
            const float c = cost(trial);
            if (trace) std::printf("curve ch%d %.4f %.8f\n", channel, v, c);
            if (c < best_cost) { best_cost = c; best = v; }
        }
        scale[size_t(channel)] = best;
        refine(channel, std::max(0.2f, best - 0.006f), std::min(2.0f, best + 0.006f));
    }
    scale[1] = 1.0f;
    out[0] = scale[0];
    out[1] = scale[1];
    out[2] = scale[2];
}

}  // namespace print_correction
#endif
