#ifndef FOTOFILM_HOST_TONE_HPP
#define FOTOFILM_HOST_TONE_HPP

// Auto Levels, as `ToneBaseMeasurement` + `FilmEngineInvocation.setToneBase` compute it on the
// CPU: a whole-frame measurement of the scene into 64 cells, then the four slots an invocation
// rewrites from it -- MASKING, the paper mid-points, EXPOSURE_GAIN and the two tone controls.
//
// The film's own curves only: no table, no sheet, no spectral integration.

#include "film.hpp"
#include "film_reversal.hpp"

#include <algorithm>
#include <cmath>
#include <thread>
#include <vector>

namespace tone {

// ColorScience.luminanceWeights: CIE Y weights for the Rec.2020 working space.
constexpr float kLumaR = 0.2627002f, kLumaG = 0.6779981f, kLumaB = 0.0593017f;

// DigitalReferenceReceiver's constants.
constexpr float kPrintWhiteOverGrey = 3.0f;
constexpr float kPrintRange = 6.3f;
constexpr float kRetimeWhiteLow = 2.5f, kRetimeWhiteHigh = 3.5f;
constexpr float kRetimeLiftLimit = 2.5f;
constexpr float kShadowLiftShare = 0.75f;
constexpr float kAutoColourShare = 0.5f, kAutoColourReach = 2.0f;
constexpr float kGradedAnchorVal = 0.46f;
constexpr float kGradedBaseVal = 1.0f;
constexpr float kGradedWhiteVal = 0.02f;
constexpr float kGradedReadPerVal = 0.73f / 0.54f;
constexpr float kGradedBaseRead = (kGradedBaseVal - kGradedAnchorVal) * kGradedReadPerVal;
constexpr float kGradedWhiteRead = (kGradedWhiteVal - kGradedAnchorVal) * kGradedReadPerVal;
constexpr float kLitFloorStops = -12.0f;
constexpr int kGridEdge = 64;
constexpr float kLog10Of2 = 0.30102999566398f;

struct V3 { float x, y, z; };
struct SceneStops { float median, bright, dark; };
struct Levels { float scale, shift; };
struct Compression { float hold, lift; };

/// `sortedRegionStops`: p50 / p99.5 / p0.5, linearly interpolated. Empty -> not measured.
inline bool scene_stops(std::vector<float> &stops, SceneStops &out) {
    if (stops.empty()) return false;
    std::sort(stops.begin(), stops.end());
    const auto at = [&](float q) {
        const float position = q * float(stops.size() - 1);
        const int low = int(position);
        const int high = std::min(low + 1, int(stops.size()) - 1);
        return stops[size_t(low)] + (position - float(low)) * (stops[size_t(high)] - stops[size_t(low)]);
    };
    out.median = at(0.5f);
    out.bright = at(0.995f);
    out.dark = at(0.005f);
    return true;
}

/// ToneBaseMeasurement: 64-cell log-luminance sums over the frame.
struct Measurement {
    int grid_w = 0, grid_h = 0;
    float weight_r = 0, weight_g = 0, weight_b = 0;
    float channel_weight[3] = {0, 0, 0};
    bool meters_colour = false;
    std::vector<double> log_sum, channel_sum;
    std::vector<int> counts;

    /// True when the tone shift may not key to the region a pixel sits in.
    bool global_read = false;
    void init(int frame_w, int frame_h, const float balance[3], float exposure_gain,
              bool colour) {
        const int w = std::max(frame_w, 1), h = std::max(frame_h, 1);
        const int long_edge = std::max(w, h);
        const auto cells = [&](int side) {
            return std::min(side, std::max(1, (side * kGridEdge + long_edge / 2) / long_edge));
        };
        grid_w = cells(w);
        grid_h = cells(h);
        // Local Tone off: one cell for the whole frame, so the shift is global instead of
        // keyed to where the light is. Same measurement, coarser grid.
        if (global_read) { grid_w = 1; grid_h = 1; }
        const float gain = exposure_gain / 0.18f;
        weight_r = kLumaR * balance[0] * gain;
        weight_g = kLumaG * balance[1] * gain;
        weight_b = kLumaB * balance[2] * gain;
        for (int c = 0; c < 3; ++c) channel_weight[c] = balance[c] * gain;
        meters_colour = colour;
        const size_t plane = size_t(grid_w) * size_t(grid_h);
        log_sum.assign(plane, 0.0);
        counts.assign(plane, 0);
        channel_sum.assign(colour ? 3 * plane : 0, 0.0);
    }

    /// @param parallel the frame is measured on every render of a fotufilm frame,
    ///        and the cells are independent: each one sums its own block in its own
    ///        order, so the split is over cells and never over a sum. 36 ms of
    ///        `log2` on a 1536x2048 frame becomes 6.
    void add(const float *red, const float *green, const float *blue, int width, int height,
             bool parallel = false) {
        const size_t plane = size_t(grid_w) * size_t(grid_h);
        const auto row = [&](int cy) {
            const int y_low = (cy * height + grid_h - 1) / grid_h;
            const int y_high = ((cy + 1) * height + grid_h - 1) / grid_h;
            for (int cx = 0; cx < grid_w; ++cx) {
                const int x_low = (cx * width + grid_w - 1) / grid_w;
                const int x_high = ((cx + 1) * width + grid_w - 1) / grid_w;
                double sum = 0.0;
                double each[3] = {0.0, 0.0, 0.0};
                for (int y = y_low; y < y_high; ++y) {
                    for (int x = x_low; x < x_high; ++x) {
                        const size_t i = size_t(y) * size_t(width) + size_t(x);
                        const float r = std::max(red[i], 0.0f);
                        const float g = std::max(green[i], 0.0f);
                        const float b = std::max(blue[i], 0.0f);
                        const float metered = weight_r * r + weight_g * g + weight_b * b;
                        sum += double(std::log2(std::max(metered, 1e-6f)));
                        if (meters_colour) {
                            each[0] += double(std::log2(std::max(channel_weight[0] * r, 1e-6f)));
                            each[1] += double(std::log2(std::max(channel_weight[1] * g, 1e-6f)));
                            each[2] += double(std::log2(std::max(channel_weight[2] * b, 1e-6f)));
                        }
                    }
                }
                const size_t cell = size_t(cy) * size_t(grid_w) + size_t(cx);
                log_sum[cell] += sum;
                counts[cell] += (y_high - y_low) * (x_high - x_low);
                if (meters_colour)
                    for (int c = 0; c < 3; ++c)
                        channel_sum[size_t(c) * plane + cell] += each[c];
            }
        };
        if (!parallel || grid_h < 8) {
            for (int cy = 0; cy < grid_h; ++cy) row(cy);
            return;
        }
        unsigned hw = std::thread::hardware_concurrency();
        if (hw == 0) hw = 1;
        int n = int(std::min<unsigned>(hw, 8u));
        if (n > grid_h) n = grid_h;
        if (n <= 1) {
            for (int cy = 0; cy < grid_h; ++cy) row(cy);
            return;
        }
        std::vector<std::thread> pool;
        pool.reserve(size_t(n - 1));
        for (int t = 1; t < n; ++t) {
            pool.emplace_back([&row, t, n, this]() {
                for (int cy = t; cy < grid_h; cy += n) row(cy);
            });
        }
        for (int cy = 0; cy < grid_h; cy += n) row(cy);
        for (std::thread &t : pool) t.join();
    }

    /// One percentile of a sample, linearly interpolated -- the engine's
    /// `ToneBaseMeasurement.percentile`. `scene_stops` only ever answers p50/p99.5/p0.5,
    /// and using its p50 as the *lower quartile* silently drops every cell below the
    /// frame's median out of the colour read: 0.037 of blue mid-point on one dark frame.
    static float percentile(std::vector<float> &values, float q) {
        if (values.empty()) return 0.0f;
        std::sort(values.begin(), values.end());
        const float position = q * float(values.size() - 1);
        const int low = int(position);
        const int high = std::min(low + 1, int(values.size()) - 1);
        return values[size_t(low)]
            + (position - float(low)) * (values[size_t(high)] - values[size_t(low)]);
    }

    std::vector<float> region_stops() const {
        std::vector<float> stops;
        stops.reserve(log_sum.size());
        for (size_t i = 0; i < log_sum.size(); ++i)
            if (counts[i] > 0) stops.push_back(float(log_sum[i] / double(counts[i])));
        return stops;
    }

    /// The frame's colour as Auto Levels reads it, in stops from metered mid-grey.
    bool channel_medians(V3 &out) const {
        if (!meters_colour) return false;
        const size_t plane = log_sum.size();
        std::vector<float> luma;
        std::vector<V3> cells;
        for (size_t i = 0; i < plane; ++i) {
            if (counts[i] <= 0) continue;
            luma.push_back(float(log_sum[i] / double(counts[i])));
            V3 c;
            c.x = float(channel_sum[i] / double(counts[i]));
            c.y = float(channel_sum[plane + i] / double(counts[i]));
            c.z = float(channel_sum[2 * plane + i] / double(counts[i]));
            cells.push_back(c);
        }
        if (luma.empty()) return false;
        // A cell is lit above the frame's *lower quartile* and clear of the meter's floor.
        const float floor = std::max(percentile(luma, 0.25f), kLitFloorStops);
        std::vector<float> greens, reds, blues;
        for (size_t i = 0; i < luma.size(); ++i) {
            if (luma[i] < floor) continue;
            greens.push_back(cells[i].y);
            reds.push_back(cells[i].x - cells[i].y);
            blues.push_back(cells[i].z - cells[i].y);
        }
        if (greens.empty()) return false;
        SceneStops g, r, b;
        if (!scene_stops(greens, g) || !scene_stops(reds, r) || !scene_stops(blues, b)) return false;
        out.x = g.median + r.median;
        out.y = g.median;
        out.z = g.median + b.median;
        return true;
    }
};

// --- the film's own read ---------------------------------------------------------

inline float green_dmin(const film::Spectra &stock) { return stock.curves[1].d_min; }
inline float green_at_zero(const film::Spectra &stock) { return stock.curves[1].density(0.0f); }
inline float neutral_density(const film::Spectra &stock, float log_exposure) {
    return (stock.curves[0].density(log_exposure) + stock.curves[1].density(log_exposure)
            + stock.curves[2].density(log_exposure)) / 3.0f;
}

inline float base_read(const film::Spectra &stock) {
    if (stock.monochrome) {
        float dmin = 0.0f;
        for (int c = 0; c < 3; ++c) dmin += stock.curves[c].d_min;
        dmin /= 3.0f;
        return std::max(neutral_density(stock, 0.0f) - dmin, 0.05f);
    }
    return std::max(green_at_zero(stock) - green_dmin(stock), 0.05f);
}

inline float read_stops(const film::Spectra &stock, float stops) {
    const float log_exposure = stops * kLog10Of2;
    if (stock.monochrome)
        return neutral_density(stock, 0.0f) - neutral_density(stock, log_exposure);
    return green_at_zero(stock) - stock.curves[1].density(log_exposure);
}

inline bool keys_tone(const film::Spectra &stock) {
    return !stock.reversal && !stock.reflection_print;
}

inline float retime_highlight(const SceneStops &scene, float exposure_ev) {
    const float white = std::min(std::max(scene.bright, scene.median + kRetimeWhiteLow),
                                 scene.median + kRetimeWhiteHigh);
    return std::max(white, exposure_ev + kPrintWhiteOverGrey - kRetimeLiftLimit);
}

inline float tone_key(float white) { return white - kPrintWhiteOverGrey; }

inline float film_boost(const film::Spectra &stock, float white, float exposure_ev) {
    if (!keys_tone(stock)) return 0.0f;
    return exposure_ev - tone_key(white);
}

inline Compression tone_compression(const film::Spectra &stock, const SceneStops &scene,
                                   float white) {
    Compression out{0.0f, 0.0f};
    if (!keys_tone(stock)) return out;
    const auto amount = [](float overflow, float room) {
        if (!(overflow > 0.0f)) return 0.0f;
        const float t = std::min((room + overflow) / 6.0f, 1.0f);
        const float curve = 3.0f * t * t * (3.0f - 2.0f * t);
        return std::min(overflow / curve, 1.0f);
    };
    out.hold = amount(scene.bright - white, kPrintWhiteOverGrey);
    out.lift = kShadowLiftShare * amount(white - kPrintRange - scene.dark,
                                         kPrintRange - kPrintWhiteOverGrey);
    return out;
}

/// `.autoLevels`'s per-record read: the graded base scale times the receiver's read.
inline float auto_levels_colour_read(const film::Spectra &stock, float stops) {
    if (stock.reversal || stock.monochrome || stock.reflection_print) return 0.0f;
    return kGradedBaseRead / base_read(stock) * read_stops(stock, stops);
}

inline V3 auto_colour_shift(const film::Spectra &stock, const V3 *medians, float exposure_ev) {
    V3 out{0.0f, 0.0f, 0.0f};
    if (stock.reversal || stock.monochrome || stock.reflection_print || !medians) return out;
    const auto read = [&](float stops) { return auto_levels_colour_read(stock, stops); };
    const float green = medians->y - exposure_ev;
    const auto shift = [&](float median) {
        const float cast = std::min(std::max(median - exposure_ev - green, -kAutoColourReach),
                                    kAutoColourReach);
        return read(green) - read(green + cast);
    };
    out.x = kAutoColourShare * shift(medians->x);
    out.z = kAutoColourShare * shift(medians->z);
    return out;
}

/// `DigitalReferenceReceiver.levels` for `.autoLevels`: the base scale, and the re-time shift.
inline Levels levels_for(const film::Spectra &stock, bool has_highlight, float highlight,
                         float exposure_ev) {
    Levels out{1.0f, 0.0f};
    if (stock.reflection_print) return out;
    const float measured = has_highlight ? (highlight - exposure_ev) : 0.0f;
    if (stock.reversal) {
        // A transparency's levels are a shift alone, on the read's own luminance.
        out.shift = reversal::positive_shift(stock, has_highlight, measured);
        return out;
    }
    out.scale = kGradedBaseRead / base_read(stock);
    if (!has_highlight || !std::isfinite(measured)) return out;
    const float stops = std::min(std::max(measured, -12.0f), 12.0f);
    out.shift = kGradedWhiteRead - out.scale * read_stops(stock, stops);
    return out;
}

/// The whole Auto Levels answer for one frame, in the terms `applyMeteredLevels` writes.
struct Metered {
    float scale[3] = {1, 1, 1};
    float shift[3] = {0, 0, 0};
    float film_boost = 0.0f;
    float tone_key = 0.0f;
    bool has_tone_key = false;
    float shadow_lift = 0.0f;
    float highlight_hold = 0.0f;
    bool valid = false;
};

/// `FilmEngineInvocation.setToneBase`'s screen branch, for a negative on Digital Reference.
/// ToneBase.boxMean: an integral image with the window clipped at the edges, so the divisor is
/// the window's real coverage rather than its nominal size.
inline std::vector<double> box_mean(const std::vector<double> &v, int w, int h, int radius) {
    std::vector<double> sat(size_t(w + 1) * size_t(h + 1), 0.0);
    for (int y = 0; y < h; ++y) {
        double row = 0.0;
        for (int x = 0; x < w; ++x) {
            row += v[size_t(y) * size_t(w) + size_t(x)];
            sat[size_t(y + 1) * size_t(w + 1) + size_t(x + 1)] =
                sat[size_t(y) * size_t(w + 1) + size_t(x + 1)] + row;
        }
    }
    std::vector<double> out(size_t(w) * size_t(h), 0.0);
    for (int y = 0; y < h; ++y) {
        const int y0 = std::max(0, y - radius), y1 = std::min(h - 1, y + radius);
        for (int x = 0; x < w; ++x) {
            const int x0 = std::max(0, x - radius), x1 = std::min(w - 1, x + radius);
            const double sum = sat[size_t(y1 + 1) * size_t(w + 1) + size_t(x1 + 1)]
                             - sat[size_t(y0) * size_t(w + 1) + size_t(x1 + 1)]
                             - sat[size_t(y1 + 1) * size_t(w + 1) + size_t(x0)]
                             + sat[size_t(y0) * size_t(w + 1) + size_t(x0)];
            out[size_t(y) * size_t(w) + size_t(x)] =
                sum / double((y1 - y0 + 1) * (x1 - x0 + 1));
        }
    }
    return out;
}

/// ToneBase.solvedCoefficients: how much of each cell's own log-luminance to keep (a) against
/// how much of its neighbourhood to take (b). Empty cells contribute zero, as in the engine.
inline void solved_coefficients(const Measurement &m, std::vector<float> &a_out,
                                std::vector<float> &b_out) {
    constexpr int kRadius = 12;
    constexpr double kEpsilon = 0.25;
    const int gw = m.grid_w, gh = m.grid_h;
    const size_t cells = size_t(gw) * size_t(gh);
    std::vector<double> g(cells, 0.0);
    for (size_t i = 0; i < cells; ++i)
        if (m.counts[i] > 0) g[i] = m.log_sum[i] / double(m.counts[i]);
    const int radius = std::min(kRadius, std::max(gw, gh) - 1);
    const std::vector<double> mean_g = box_mean(g, gw, gh, radius);
    std::vector<double> sq(cells, 0.0);
    for (size_t i = 0; i < cells; ++i) sq[i] = g[i] * g[i];
    const std::vector<double> mean_gg = box_mean(sq, gw, gh, radius);
    std::vector<double> a(cells, 0.0), b(cells, 0.0);
    for (size_t i = 0; i < cells; ++i) {
        const double variance = std::max(0.0, mean_gg[i] - mean_g[i] * mean_g[i]);
        a[i] = variance / (variance + kEpsilon);
        b[i] = (1.0 - a[i]) * mean_g[i];
    }
    const std::vector<double> smooth_a = box_mean(a, gw, gh, radius);
    const std::vector<double> smooth_b = box_mean(b, gw, gh, radius);
    a_out.assign(cells, 0.0f);
    b_out.assign(cells, 0.0f);
    for (size_t i = 0; i < cells; ++i) {
        a_out[i] = float(smooth_a[i]);
        b_out[i] = float(smooth_b[i]);
    }
}

inline Metered meter(const Measurement &measurement, const film::Spectra &stock,
                     float exposure_ev) {
    Metered out;
    auto stops = measurement.region_stops();
    SceneStops scene;
    if (!scene_stops(stops, scene)) return out;
    const float white = retime_highlight(scene, exposure_ev);
    const float boost = film_boost(stock, white, exposure_ev);
    const Levels levels = levels_for(stock, true, white + boost, exposure_ev);
    V3 medians{0, 0, 0};
    const bool has_medians = measurement.channel_medians(medians);
    V3 shifted = medians;
    shifted.x += boost; shifted.y += boost; shifted.z += boost;
    const V3 colour = auto_colour_shift(stock, has_medians ? &shifted : nullptr, exposure_ev);
    const Compression compression = tone_compression(stock, scene, white);
    for (int c = 0; c < 3; ++c) {
        out.scale[c] = levels.scale;
        out.shift[c] = levels.shift + (c == 0 ? colour.x : (c == 2 ? colour.z : 0.0f));
    }
    out.film_boost = boost;
    if (!stock.reversal && !stock.reflection_print) {
        out.tone_key = tone_key(white) + boost;
        out.has_tone_key = true;
    }
    out.shadow_lift = compression.lift;
    out.highlight_hold = compression.hold;
    out.valid = true;
    return out;
}

}  // namespace tone

#endif
