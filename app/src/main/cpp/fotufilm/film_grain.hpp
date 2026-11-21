// The grain's tile architecture, in C++: a straight port of FilmGrain.swift's reference
// render, which is the path the Android engine takes (its Halide tile builder is compiled
// out, so `builtLights` returns nil and `referenceTileLight` lays every tile).
//
// A tile is 256 texels of 0.001 mm a side, rendered at 17 gross densities from D-min to
// D-max. The crystals are the same at every level: a denser level is the lighter one with
// more of its crystals developed, so one draw serves all levels.
//
// Float arithmetic throughout: the tiles must land on the reference's own numbers, and a
// double in the wrong place moves the last bit of every texel.
#pragma once

#include <cmath>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <fstream>
#include <string>
#include <atomic>
#include <thread>
#include <utility>
#include <vector>

namespace grain {

// --- draws -------------------------------------------------------------------

inline uint32_t pcg_hash(uint32_t v) {
    const uint32_t state = v * 747796405u + 2891336453u;
    const uint32_t word = ((state >> ((state >> 28) + 4)) ^ state) * 277803737u;
    return (word >> 22) ^ word;
}

/// `FilmRandom`: one cell of one sublayer always draws the same numbers.
struct Random {
    uint32_t key;
    uint32_t counter = 0;

    Random() : key(0) {}
    Random(uint64_t seed, int record, int sublayer, int stream, int x, int y) {
        const uint32_t base = seed32(seed)
            ^ (uint32_t(record * 8 + sublayer * 2 + stream) * 0x9E3779B9u);
        key = pcg_hash(uint32_t(x) ^ pcg_hash(uint32_t(y) ^ pcg_hash(base)));
    }
    static uint32_t seed32(uint64_t seed) {
        return pcg_hash(uint32_t(seed)) ^ uint32_t(seed >> 32);
    }
    uint32_t next() {
        const uint32_t h = pcg_hash(key ^ (counter * 0x9E3779B9u));
        ++counter;
        return h;
    }
    /// Uniform in (0, 1).
    float uniform() { return (float(next() >> 8) + 0.5f) * (1.0f / 16777216.0f); }
};

constexpr int kMaxCount = 48;

/// `P(N <= k)` of a Poisson count of `mean`, as floats.
inline void count_thresholds(float mean, float *out) {
    const double m = double(mean > 0 ? mean : 0);
    double term = std::exp(-m), total = term;
    for (int k = 0; k < kMaxCount; ++k) {
        if (k > 0) { term *= m / double(k); total += term; }
        out[k] = float(total < 1.0 ? total : 1.0);
    }
}

inline int count_of(float u, const float *thresholds) {
    int n = 0;
    for (int k = 0; k < kMaxCount; ++k) if (thresholds[k] < u) ++n;
    return n;
}

// --- the population ----------------------------------------------------------

enum Profile { kSilverGrain = 0, kDyeCloud = 1 };

struct Sublayer {
    int profile = kDyeCloud;
    float coated_per_mm2 = 0;
    float sigma_mm = 0;
    float peak_demand = 0;
    float capacity = 0;
    float edge = 0;
    float smallest_sigma_mm = 0;
    float dye_per_cloud_mm2 = 0;
    float cell_mm = 0;
    float void_integral_mm2 = 0;
    std::vector<float> forming;   // 129, D-min to D-max
};

struct Record {
    std::vector<Sublayer> sublayers;
    float d_min = 0;
    float d_max = 0;
};

/// `dyeCloudTerms`: Jarvis's cloud as four Gaussians, `weight * exp(-rho^2 / 2 sigma^2)`.
struct CloudTerm { float sigma, weight; };
constexpr CloudTerm kDyeCloudTerms[4] = {
    {0.1162f, 0.1096f}, {0.3089f, 0.2167f}, {0.7469f, 0.4030f}, {1.732f, 0.2510f},
};

constexpr int kTableSamples = 129;
constexpr int kTileSide = 256;
constexpr int kTileLevels = 17;
constexpr float kTileTexelMM = 0.001f;
constexpr uint64_t kTileSeed = 0xA11C40575EED0001ull;
constexpr int kMarkShape = 4;   // 1 / markDispersion^2, markDispersion = 0.5

inline int supersample(float px_per_mm) {
    const float pitch = 1.0f / (px_per_mm > 1e-6f ? px_per_mm : 1e-6f);
    const float n = std::ceil(pitch / 0.00035f);
    int s = int(n);
    if (s < 1) s = 1;
    if (s > 8) s = 8;
    return s;
}

// --- profiles ----------------------------------------------------------------

inline double profile_value(int profile, double rho) {
    if (profile == kSilverGrain) return std::exp(-rho * rho / 2);
    double total = 0;
    for (const CloudTerm &term : kDyeCloudTerms) {
        const double s = double(term.sigma);
        total += double(term.weight) * std::exp(-rho * rho / (2 * s * s));
    }
    return total;
}

inline double profile_reach(int profile, double peak) {
    const double widest = profile == kSilverGrain ? 1.0 : double(kDyeCloudTerms[3].sigma);
    const double inside = peak * 8 > 1 ? peak * 8 : 1;
    return widest * std::sqrt(2 * std::log(inside / 1e-7));
}

/// `int (1 - exp(-peak * profile(rho))) d^2 rho`: the capacity a crystal of unit demand fills.
inline float occupied_area(float peak, int profile) {
    const double p = double(peak > 0 ? peak : 0);
    if (profile != kSilverGrain) {
        const int steps = 800;
        const double dr = profile_reach(profile, p) / double(steps);
        double area = 0;
        for (int i = 0; i < steps; ++i) {
            const double rho = (double(i) + 0.5) * dr;
            area += (1 - std::exp(-p * profile_value(profile, rho))) * rho * dr;
        }
        return float(2 * 3.14159265358979323846 * area);
    }
    double ein;
    if (p < 6) {
        double term = p, sum = 0;
        for (int k = 1; k <= 60; ++k) {
            sum += term / double(k);
            term *= -p / double(k + 1);
            if (std::fabs(term) < 1e-14) break;
        }
        ein = sum;
    } else {
        const double e1 = std::exp(-p) / p * (1 - 1 / p + 2 / (p * p) - 6 / (p * p * p));
        ein = e1 + std::log(p) + 0.5772156649;
    }
    return float(2 * 3.14159265358979323846 * ein);
}

inline float gamma_quantile(double p, double shape) {
    const int k = int(shape);
    auto cdf = [k](double x) {
        double term = 1.0, sum = 1.0;
        if (k > 1) for (int n = 1; n < k; ++n) { term *= x / double(n); sum += term; }
        return 1 - std::exp(-x) * sum;
    };
    double lo = 0.0, hi = shape * 20;
    for (int i = 0; i < 80; ++i) {
        const double mid = (lo + hi) / 2;
        if (cdf(mid) < p) lo = mid; else hi = mid;
    }
    return float((lo + hi) / 2);
}

// --- the blurs ---------------------------------------------------------------

inline std::vector<float> gaussian_kernel(float sigma) {
    int radius = int(std::ceil(3.5f * sigma));
    if (radius < 1) radius = 1;
    std::vector<float> kernel((static_cast<size_t>(2 * radius + 1)));
    float total = 0;
    for (int i = -radius; i <= radius; ++i) {
        const float v = std::exp(-float(i * i) / (2 * sigma * sigma));
        kernel[size_t(i + radius)] = v;
        total += v;
    }
    for (float &v : kernel) v /= total;
    return kernel;
}

/// Each of `rows` rows blurred along x: output column i reads input columns
/// `first_column + i ...` through the kernel.
inline void blur_rows(const float *input, int row_stride, int first_column, int rows, int columns,
                      const std::vector<float> &kernel, float *output, int output_stride,
                      int output_offset) {
    const int taps = int(kernel.size());
    for (int j = 0; j < rows; ++j) {
        float *out = output + size_t(j) * output_stride + output_offset;
        std::memset(out, 0, size_t(columns) * sizeof(float));
        const float *row = input + size_t(j) * row_stride + first_column;
        for (int t = 0; t < taps; ++t) {
            const float weight = kernel[size_t(t)];
            const float *source = row + t;
            for (int i = 0; i < columns; ++i) out[i] += weight * source[i];
        }
    }
}

/// `rows` output rows blurred along y from `input` rows `first_row + j ...`, times `scale`,
/// added to (or written into) `output`.
inline void blur_columns(const float *input, int columns, int first_row, int rows,
                         const std::vector<float> &kernel, float scale, float *output,
                         bool accumulate) {
    const int taps = int(kernel.size());
    std::vector<float> sum(static_cast<size_t>(columns));
    for (int j = 0; j < rows; ++j) {
        std::fill(sum.begin(), sum.end(), 0.0f);
        for (int t = 0; t < taps; ++t) {
            const float weight = kernel[size_t(t)];
            const float *source = input + size_t(first_row + j + t) * columns;
            for (int i = 0; i < columns; ++i) sum[size_t(i)] += weight * source[i];
        }
        float *out = output + size_t(j) * columns;
        if (accumulate) for (int i = 0; i < columns; ++i) out[i] += scale * sum[size_t(i)];
        else            for (int i = 0; i < columns; ++i) out[i] = scale * sum[size_t(i)];
    }
}

/// Adds one of the cloud's terms to the core of `demand`: the deposits blurred by a
/// normalised Gaussian of `sigma` samples, times `weight * 2 pi sigma^2`.
inline void lay_cloud_term(const float *deposits, int gw, int gh, int margin, float *demand,
                          int sw, int sh, float sigma, float weight) {
    const float scale = weight * 2 * 3.14159265358979323846f * sigma * sigma;
    int factor = int(sigma / 1.6f);
    if (factor < 1) factor = 1;
    if (factor <= 1) {
        const std::vector<float> kernel = gaussian_kernel(sigma);
        const int radius = int(kernel.size()) / 2;
        std::vector<float> across(static_cast<size_t>(gh) * static_cast<size_t>(sw), 0.0f);
        blur_rows(deposits, gw, margin - radius, gh, sw, kernel, across.data(), sw, 0);
        blur_columns(across.data(), sw, margin - radius, sh, kernel, scale, demand, true);
        return;
    }
    const float f = float(factor);
    const float coarse = std::sqrt(sigma * sigma - f * f / 3) / f;
    const std::vector<float> kernel = gaussian_kernel(coarse);
    const int radius = int(kernel.size()) / 2;
    const int cw = (gw + factor - 1) / factor + 2 * radius + 1;
    const int ch = (gh + factor - 1) / factor + 2 * radius + 1;
    std::vector<float> cells(size_t(cw) * size_t(ch), 0.0f);
    // A sample's centre sits at `(x + 0.5) / factor - 0.5` cells, the cells' centres at
    // integers; `radius` cells of zeros lead each side.
    const int placeCount = gw > gh ? gw : gh;
    std::vector<int> placeIndex(static_cast<size_t>(placeCount));
    std::vector<float> placeFrac(static_cast<size_t>(placeCount));
    for (int x = 0; x < placeCount; ++x) {
        const float u = (float(x) + 0.5f) / f - 0.5f;
        const int i = int(std::floor(u));
        placeIndex[size_t(x)] = i + radius;
        placeFrac[size_t(x)] = u - float(i);
    }
    for (int y = 0; y < gh; ++y) {
        const int j = placeIndex[size_t(y)];
        const float av = placeFrac[size_t(y)];
        for (int x = 0; x < gw; ++x) {
            const float mass = deposits[size_t(y) * gw + x];
            if (mass == 0) continue;
            const int i = placeIndex[size_t(x)];
            const float au = placeFrac[size_t(x)];
            cells[size_t(j) * cw + i] += mass * (1 - au) * (1 - av);
            cells[size_t(j) * cw + i + 1] += mass * au * (1 - av);
            cells[size_t(j + 1) * cw + i] += mass * (1 - au) * av;
            cells[size_t(j + 1) * cw + i + 1] += mass * au * av;
        }
    }
    std::vector<float> across(size_t(ch) * size_t(cw), 0.0f);
    std::vector<float> blurred(size_t(ch) * size_t(cw), 0.0f);
    blur_rows(cells.data(), cw, 0, ch, cw - 2 * radius, kernel, across.data(), cw, radius);
    blur_columns(across.data(), cw, 0, ch - 2 * radius, kernel, 1.0f,
                 blurred.data() + size_t(radius) * cw, false);
    // Read back linearly at each core sample's centre; a cell holds `factor^2` samples' mass.
    const float perSample = scale / (f * f);
    for (int j = 0; j < sh; ++j) {
        const float v = (float(j + margin) + 0.5f) / f - 0.5f + float(radius);
        int j0 = int(std::floor(v));
        if (j0 < 0) j0 = 0;
        if (j0 > ch - 2) j0 = ch - 2;
        const float av = v - float(j0);
        for (int i = 0; i < sw; ++i) {
            const float u = (float(i + margin) + 0.5f) / f - 0.5f + float(radius);
            int i0 = int(std::floor(u));
            if (i0 < 0) i0 = 0;
            if (i0 > cw - 2) i0 = cw - 2;
            const float au = u - float(i0);
            const float top = blurred[size_t(j0) * cw + i0] * (1 - au)
                            + blurred[size_t(j0) * cw + i0 + 1] * au;
            const float bottom = blurred[size_t(j0 + 1) * cw + i0] * (1 - au)
                               + blurred[size_t(j0 + 1) * cw + i0 + 1] * au;
            demand[size_t(j) * sw + i] += perSample * (top * (1 - av) + bottom * av);
        }
    }
}

// --- the render --------------------------------------------------------------

/// One tile of `record` at one gross density, `side^2` texels of transmittance.
///
/// `gross` stands for the profile's `grossAt` closure, which the tile build always answers
/// with a constant: the tile is one developed density, not a frame.
inline void render_tile_const(const Record &record, int record_index, int x0, int y0, int tw, int th,
                              float px_per_mm, int s, uint64_t seed, float gross, float *output,
                              float *means, int width, float period_mm, bool has_period) {
    const float pitch = 1.0f / px_per_mm;
    const float h = pitch / float(s);
    const int sw = tw * s, sh = th * s;
    const float fx0 = float(x0) * pitch, fy0 = float(y0) * pitch;
    const float ln10 = 2.302585093f;

    std::vector<float> log_transmit(size_t(sw) * size_t(sh), 0.0f);
    std::vector<float> density(size_t(sw) * size_t(sh), 0.0f);
    std::vector<float> demand(size_t(sw) * size_t(sh), 0.0f);
    std::vector<float> log_uncovered(size_t(sw) * size_t(sh), 0.0f);

    const float table_scale = float(kTableSamples - 1)
        / std::max(record.d_max - record.d_min, 1e-6f);

    for (size_t b = 0; b < record.sublayers.size(); ++b) {
        const Sublayer &layer = record.sublayers[b];
        std::fill(demand.begin(), demand.end(), 0.0f);
        std::fill(log_uncovered.begin(), log_uncovered.end(), 0.0f);

        const float sigma = layer.sigma_mm;
        const float edge = layer.peak_demand / layer.capacity;
        // A cloud two samples wide is point-sampled; under half a sample it lays the area it
        // fills; between, both weighted, so a cloud growing through the sample size is smooth.
        float resolved_share = (sigma / h - 0.5f) / 1.5f;
        if (resolved_share < 0) resolved_share = 0;
        if (resolved_share > 1) resolved_share = 1;
        const bool dye = layer.profile == kDyeCloud;
        const int margin = dye
            ? int(std::ceil(3.5f * kDyeCloudTerms[3].sigma * sigma / h)) + 1 : 0;
        const int gw = sw + 2 * margin, gh = sh + 2 * margin;
        std::vector<float> deposits(dye ? size_t(gw) * size_t(gh) : 0, 0.0f);
        const float reach = dye ? float(margin) * h
            : resolved_share > 0 ? 4.2f * sigma : 3 * sigma + h;
        // A periodic film repeats its crystals every `period`, so its cells must divide it.
        int period_cells = 0;
        float cell = layer.cell_mm;
        if (has_period) {
            period_cells = int(std::floor(period_mm / layer.cell_mm + 0.5f));
            if (period_cells < 1) period_cells = 1;
            cell = period_mm / float(period_cells);
        }
        float thresholds[kMaxCount];
        count_thresholds(layer.coated_per_mm2 * cell * cell, thresholds);
        const int cx0 = int(std::floor((fx0 - reach) / cell));
        const int cx1 = int(std::floor((fx0 + float(sw) * h + reach) / cell));
        const int cy0 = int(std::floor((fy0 - reach) / cell));
        const int cy1 = int(std::floor((fy0 + float(sh) * h + reach) / cell));
        const float inverse_two_sigma2 = 1.0f / (2 * sigma * sigma);

        for (int cy = cy0; cy <= cy1; ++cy) {
            for (int cx = cx0; cx <= cx1; ++cx) {
                int hx = cx, hy = cy;
                if (has_period) {
                    hx = ((cx % period_cells) + period_cells) % period_cells;
                    hy = ((cy % period_cells) + period_cells) % period_cells;
                }
                Random counter(seed, record_index, int(b), 1, hx, hy);
                const int crystals = count_of(counter.uniform(), thresholds);
                Random rng(seed, record_index, int(b), 0, hx, hy);
                for (int k = 0; k < crystals; ++k) {
                    const float px = (float(cx) + rng.uniform()) * cell;
                    const float py = (float(cy) + rng.uniform()) * cell;
                    const float develop = rng.uniform();
                    float mark = 0;
                    for (int m = 0; m < kMarkShape; ++m) {
                        const float u = rng.uniform();
                        mark -= std::log(u > 1e-7f ? u : 1e-7f);
                    }
                    mark /= float(kMarkShape);
                    float t = (gross - record.d_min) * table_scale;
                    if (t < 0) t = 0;
                    if (t > float(kTableSamples - 1)) t = float(kTableSamples - 1);
                    int ti = int(t);
                    if (ti > kTableSamples - 2) ti = kTableSamples - 2;
                    const float tf = t - float(ti);
                    const float fraction = layer.forming[size_t(ti)] * (1 - tf)
                                         + layer.forming[size_t(ti) + 1] * tf;
                    if (!(develop < fraction)) continue;

                    const float lx = (px - fx0) / h, ly = (py - fy0) / h;
                    if (dye) {
                        const float u = lx - 0.5f + float(margin);
                        const float v = ly - 0.5f + float(margin);
                        const int i0 = int(std::floor(u)), j0 = int(std::floor(v));
                        const float au = u - float(i0), av = v - float(j0);
                        const float amount = edge * mark;
                        for (int dj = 0; dj < 2; ++dj) {
                            const int j = j0 + dj;
                            if (j < 0 || j >= gh) continue;
                            const float wy = dj ? av : 1 - av;
                            for (int di = 0; di < 2; ++di) {
                                const int i = i0 + di;
                                if (i < 0 || i >= gw) continue;
                                const float wx = di ? au : 1 - au;
                                deposits[size_t(j) * gw + i] += amount * wx * wy;
                            }
                        }
                        continue;
                    }
                    if (resolved_share > 0) {
                        const float span = reach / h;
                        int ix0 = int(std::floor(lx - span)); if (ix0 < 0) ix0 = 0;
                        int ix1 = int(std::ceil(lx + span)); if (ix1 > sw - 1) ix1 = sw - 1;
                        int iy0 = int(std::floor(ly - span)); if (iy0 < 0) iy0 = 0;
                        int iy1 = int(std::ceil(ly + span)); if (iy1 > sh - 1) iy1 = sh - 1;
                        if (ix0 <= ix1 && iy0 <= iy1) {
                            const float peak = edge * mark * resolved_share;
                            for (int j = iy0; j <= iy1; ++j) {
                                const float dy = (float(j) + 0.5f - ly) * h;
                                const float row_factor = peak * std::exp(-dy * dy * inverse_two_sigma2);
                                if (row_factor < 1e-5f) continue;
                                const size_t base = size_t(j) * sw;
                                for (int i = ix0; i <= ix1; ++i) {
                                    const float dx = (float(i) + 0.5f - lx) * h;
                                    demand[base + size_t(i)] +=
                                        row_factor * std::exp(-dx * dx * inverse_two_sigma2);
                                }
                            }
                        }
                    }
                    if (resolved_share < 1) {
                        const float covering = 1 - resolved_share;
                        const float side = sigma * std::sqrt(occupied_area(edge * mark, layer.profile))
                                         / h;
                        const float left = lx - side / 2, right = lx + side / 2;
                        const float top = ly - side / 2, bottom = ly + side / 2;
                        int ix0 = int(std::floor(left)); if (ix0 < 0) ix0 = 0;
                        int ix1 = int(std::floor(right)); if (ix1 > sw - 1) ix1 = sw - 1;
                        int iy0 = int(std::floor(top)); if (iy0 < 0) iy0 = 0;
                        int iy1 = int(std::floor(bottom)); if (iy1 > sh - 1) iy1 = sh - 1;
                        if (!(ix0 <= ix1 && iy0 <= iy1)) continue;
                        for (int j = iy0; j <= iy1; ++j) {
                            const float upper = bottom < float(j + 1) ? bottom : float(j + 1);
                            const float lower = top > float(j) ? top : float(j);
                            const float oy = upper - lower;
                            if (oy <= 0) continue;
                            const size_t base = size_t(j) * sw;
                            for (int i = ix0; i <= ix1; ++i) {
                                const float right_edge = right < float(i + 1) ? right : float(i + 1);
                                const float left_edge = left > float(i) ? left : float(i);
                                const float ox = right_edge - left_edge;
                                if (ox <= 0) continue;
                                const float keep = 1 - covering * ox * oy;
                                log_uncovered[base + size_t(i)] += std::log(keep > 1e-6f ? keep : 1e-6f);
                            }
                        }
                    }
                }
            }
        }
        if (dye) {
            for (const CloudTerm &term : kDyeCloudTerms) {
                lay_cloud_term(deposits.data(), gw, gh, margin, demand.data(), sw, sh,
                               term.sigma * sigma / h, term.weight);
            }
        }
        // The sublayer at each sample: resolved dye saturates against the capacity; where
        // sub-sample grains cover the cell, it is at capacity.
        const float capacity = layer.capacity;
        const float full = std::exp(-ln10 * capacity);
        for (size_t i = 0; i < density.size(); ++i) {
            const float resolved_dye = demand[i] > 0 ? capacity * (1 - std::exp(-demand[i])) : 0;
            const float uncovered = log_uncovered[i] < 0 ? std::exp(log_uncovered[i]) : 1;
            if (resolved_dye == 0 && uncovered == 1) continue;
            density[i] += resolved_dye * uncovered + capacity * (1 - uncovered);
            const float passed = uncovered * std::exp(-ln10 * resolved_dye) + (1 - uncovered) * full;
            log_transmit[i] += std::log(passed > 1e-12f ? passed : 1e-12f);
        }
    }

    // Light averaged over each pixel's samples; density averaged too, for the mean.
    const float norm = 1.0f / float(s * s);
    for (int py = 0; py < th; ++py) {
        for (int px = 0; px < tw; ++px) {
            float transmitted = 0, averaged = 0;
            for (int j = 0; j < s; ++j) {
                const size_t base = size_t(py * s + j) * size_t(sw) + size_t(px * s);
                for (int i = 0; i < s; ++i) {
                    transmitted += std::exp(log_transmit[base + size_t(i)]);
                    averaged += density[base + size_t(i)];
                }
            }
            const float light = transmitted * norm;
            output[size_t(y0 + py) * width + size_t(x0 + px)] =
                -std::log10(light > 1e-6f ? light : 1e-6f);
            if (means) means[size_t(y0 + py) * width + size_t(x0 + px)] = averaged * norm;
        }
    }
}

/// A whole frame of `record`'s grain, laid tile by tile as `render` does.
inline std::vector<float> render_const(const Record &record, int record_index, int width, int height,
                                       float px_per_mm, int s, uint64_t seed, float gross,
                                       float period_mm, bool has_period) {
    std::vector<float> output(size_t(width) * size_t(height), 0.0f);
    const int tile = 48;
    const int tiles_x = (width + tile - 1) / tile;
    const int tiles_y = (height + tile - 1) / tile;
    for (int ty = 0; ty < tiles_y; ++ty) {
        for (int tx = 0; tx < tiles_x; ++tx) {
            const int x0 = tx * tile, y0 = ty * tile;
            const int tw = std::min(tile, width - x0), th = std::min(tile, height - y0);
            render_tile_const(record, record_index, x0, y0, tw, th, px_per_mm, s, seed, gross,
                              output.data(), nullptr, width, period_mm, has_period);
        }
    }
    return output;
}

// --- the bank ----------------------------------------------------------------

struct TileLevel {
    std::vector<float> sums;   // (side + 1)^2 running sums of transmittance less mean
    float mean_light = 0;
};

inline TileLevel tile_level(const std::vector<float> &light, int side) {
    TileLevel level;
    const int n = side;
    double total = 0;
    for (float v : light) total += double(v);
    const double mean = total / double(light.size());
    level.sums.assign(size_t(n + 1) * size_t(n + 1), 0.0f);
    std::vector<double> column(size_t(n), 0.0);
    for (int y = 0; y < n; ++y) {
        double run = 0;
        for (int x = 0; x < n; ++x) {
            column[size_t(x)] += double(light[size_t(y) * n + x]) - mean;
            run += column[size_t(x)];
            level.sums[size_t(y + 1) * size_t(n + 1) + size_t(x + 1)] = float(run);
        }
    }
    level.mean_light = float(mean);
    return level;
}

/// Transmittance of one record's tile at `gross`, `tileSide^2` texels.
inline std::vector<float> tile_light(const Record &record, int record_index, float gross) {
    const int side = kTileSide;
    const float px_per_mm = 1.0f / kTileTexelMM;
    // A periodic film repeats its crystals every `period`, so its cells must divide it.
    std::vector<float> field = render_const(record, record_index, side, side, px_per_mm,
                                            supersample(px_per_mm), kTileSeed, gross,
                                            float(side) * kTileTexelMM, true);
    std::vector<float> light(static_cast<size_t>(side) * static_cast<size_t>(side), 0.0f);
    for (size_t i = 0; i < field.size(); ++i) light[i] = std::pow(10.0f, -field[i]);
    return light;
}

/// Every level's running sums as the kernel indexes them: `(side + 1)^2` entries, then
/// `tileLevels` levels, then three records, innermost first.
inline std::vector<float> build_tiles(const std::vector<Record> &records, bool monochrome,
                                      std::vector<TileLevel> *levels_out = nullptr) {
    const size_t entries = size_t(kTileSide + 1) * size_t(kTileSide + 1);
    std::vector<float> out(entries * size_t(kTileLevels) * 3, 0.0f);
    std::vector<int> active;
    if (monochrome) { if (!records[1].sublayers.empty()) active.push_back(1); }
    else for (int r = 0; r < 3; ++r) if (!records[size_t(r)].sublayers.empty()) active.push_back(r);
    // Per record, not flat: the configuration block, the pitch tables and the kernel all
    // index one record's levels at a time.
    // Only the records that were laid get levels: a monochrome stock leaves records 0 and 2
    // empty, and a reader decides "this record has tiles" by the array being empty. Seventeen
    // blank levels look like tiles and send it into `mean_density`, which has nothing in it.
    if (levels_out) {
        for (int r = 0; r < 3; ++r) levels_out[r].clear();
        for (int r : active) levels_out[r].assign(size_t(kTileLevels), TileLevel{});
    }
    // Every level is an independent render of the same crystals, so the levels go wide.
    std::vector<std::pair<int, int>> jobs;
    for (int r : active) for (int k = 0; k < kTileLevels; ++k) jobs.emplace_back(r, k);
    std::atomic<size_t> next{0};
    auto work = [&]() {
        for (;;) {
            const size_t index = next.fetch_add(1);
            if (index >= jobs.size()) return;
            const int r = jobs[index].first, k = jobs[index].second;
            const Record &record = records[size_t(r)];
            const float gross = record.d_min
                + (record.d_max - record.d_min) * float(k) / float(kTileLevels - 1);
            const TileLevel level = tile_level(tile_light(record, r, gross), kTileSide);
            std::memcpy(&out[(size_t(r) * size_t(kTileLevels) + size_t(k)) * entries],
                        level.sums.data(), entries * sizeof(float));
            if (levels_out) levels_out[r][size_t(k)] = level;
        }
    };
    unsigned threads = std::thread::hardware_concurrency();
    if (threads == 0) threads = 1;
    if (threads > jobs.size()) threads = unsigned(jobs.size());
    if (threads <= 1) { work(); return out; }
    std::vector<std::thread> pool;
    pool.reserve(threads);
    for (unsigned i = 0; i < threads; ++i) pool.emplace_back(work);
    for (std::thread &thread : pool) thread.join();
    return out;
}


// --- the population, off the wire --------------------------------------------
//
// The Swift engine writes this beside the tiles it lays; a host that would lay them itself
// reads it here. Ten kilobytes a stock, against thirteen megabytes of tiles.

inline bool read_records_text(const char *path, std::vector<Record> &records, bool &monochrome,
                             int &mark_shape, int &table_samples, std::string &tag) {
    std::ifstream in(path);
    if (!in) { std::fprintf(stderr, "grain: cannot open %s\n", path); return false; }
    std::string word;
    int count = 0;
    in >> word >> tag >> word >> monochrome >> word >> count;
    in >> word >> mark_shape >> word >> table_samples;
    if (!in || count <= 0) { std::fprintf(stderr, "grain: bad header in %s\n", path); return false; }
    records.assign(size_t(count), Record{});
    for (int i = 0; i < count; ++i) {
        int index = 0, sublayers = 0;
        in >> word >> index >> word >> records[size_t(i)].d_min
           >> word >> records[size_t(i)].d_max >> word >> sublayers;
        if (!in) { std::fprintf(stderr, "grain: bad record %d\n", i); return false; }
        records[size_t(i)].sublayers.assign(size_t(sublayers), Sublayer{});
        for (int j = 0; j < sublayers; ++j) {
            Sublayer &layer = records[size_t(i)].sublayers[size_t(j)];
            in >> word >> word >> layer.profile >> word >> layer.coated_per_mm2
               >> word >> layer.sigma_mm >> word >> layer.peak_demand
               >> word >> layer.capacity >> word >> layer.edge
               >> word >> layer.smallest_sigma_mm >> word >> layer.dye_per_cloud_mm2
               >> word >> layer.cell_mm >> word >> layer.void_integral_mm2 >> word;
            if (!in) { std::fprintf(stderr, "grain: bad sublayer %d.%d\n", i, j); return false; }
            layer.forming.assign(size_t(table_samples), 0.0f);
            for (int k = 0; k < table_samples; ++k) in >> layer.forming[size_t(k)];
            if (!in) { std::fprintf(stderr, "grain: bad forming table %d.%d\n", i, j); return false; }
        }
    }
    return true;
}

// --- what the kernel reads ---------------------------------------------------
//
// `FilmTiles.h` blends two neighbouring levels' grain at a pixel's developed density. It
// needs, per level, the mean light through the frame's own footprint, the mean density that
// footprint reads, and the correlation of neighbouring levels' grain at that pitch — all of
// which depend on how a pixel samples the tile, so the host measures them for the frame's
// pitch rather than shipping one set.

struct PitchTables {
    std::vector<float> mean_density[3];
    std::vector<float> blend_correlation[3];
};

constexpr int kBlockTexels = 128;
constexpr int kMarginTexels = 64;
constexpr int kSeamTexels = 16;
constexpr uint32_t kTileStream = 200;
constexpr uint32_t kMeasureSeed = 0x5A3F1E1Du;

inline void block_placement(uint32_t seed, int layer, int record, int bx, int by,
                            double &ox, double &oy, int &turn) {
    const uint32_t stream = (kTileStream + uint32_t(3 * layer + record)) * 0x9E3779B9u;
    const uint32_t h = pcg_hash(uint32_t(bx) ^ pcg_hash(uint32_t(by) ^ pcg_hash(seed ^ stream)));
    const uint32_t period = uint32_t(kTileSide);
    ox = double(h % period);
    oy = double((h >> 8) % period);
    turn = int((h >> 16) & 7);
}

/// Running sum at a real point of the tile, texels from its corner, within one period:
/// bilinear within a texel, which is exact for texels of constant light.
inline double sum_at(const std::vector<float> &sums, double x, double y) {
    const int n = kTileSide, stride = n + 1;
    int ix = int(x); if (ix < 0) ix = 0; if (ix > n - 1) ix = n - 1;
    int iy = int(y); if (iy < 0) iy = 0; if (iy > n - 1) iy = n - 1;
    const double ax = x - double(ix), ay = y - double(iy);
    const size_t base = size_t(iy) * size_t(stride) + size_t(ix);
    const double s00 = double(sums[base]), s10 = double(sums[base + 1]);
    const double s01 = double(sums[base + size_t(stride)]), s11 = double(sums[base + size_t(stride) + 1]);
    return s00 + (s10 - s00) * ax + (s01 - s00) * ay + (s11 - s10 - s01 + s00) * ax * ay;
}

/// Running sum at a point up to a period past the tile's far edges: the tile repeats, so a sum
/// reaching into the next period adds the whole columns or rows it crossed.
inline double wrapped_sum_at(const std::vector<float> &sums, double x, double y) {
    const double n = double(kTileSide);
    const double px = x >= n ? x - n : x, py = y >= n ? y - n : y;
    double sum = sum_at(sums, px, py);
    if (x >= n) sum += sum_at(sums, n, py);
    if (y >= n) sum += sum_at(sums, px, n);
    if (x >= n && y >= n) sum += sum_at(sums, n, n);
    return sum;
}

inline double wrapped_box_sum(const std::vector<float> &sums, double x0, double x1, double y0, double y1) {
    return wrapped_sum_at(sums, x1, y1) - wrapped_sum_at(sums, x0, y1)
         - wrapped_sum_at(sums, x1, y0) + wrapped_sum_at(sums, x0, y0);
}

/// Mean light at two levels through the film rectangle `[x0, x1) x [y0, y1)` (texels, in
/// laying `layer`'s grid) as block `(bx, by)`'s placement of the tile holds it.
struct TwoLights { float a, b; };

inline TwoLights block_light(const TileLevel &a, const TileLevel &b, double x0, double x1,
                             double y0, double y1, int bx, int by, uint32_t seed, int layer,
                             int record) {
    const double block = double(kBlockTexels), margin = double(kMarginTexels);
    const double left = double(bx) * block, top = double(by) * block;
    const double u0 = (x0 > left - margin ? x0 : left - margin) - left;
    const double u1 = (x1 < left + block + margin ? x1 : left + block + margin) - left;
    const double v0 = (y0 > top - margin ? y0 : top - margin) - top;
    const double v1 = (y1 < top + block + margin ? y1 : top + block + margin) - top;
    const double area = std::max((u1 - u0) * (v1 - v0), 1e-6);
    double ox, oy;
    int turn;
    block_placement(seed, layer, record, bx, by, ox, oy, turn);
    double p0 = u0, p1 = u1, q0 = v0, q1 = v1;
    if (turn & 1) { p0 = block - u1; p1 = block - u0; }
    if (turn & 2) { q0 = block - v1; q1 = block - v0; }
    if (turn & 4) { const double t0 = p0, t1 = p1; p0 = q0; p1 = q1; q0 = t0; q1 = t1; }
    const double sx = margin + ox, sy = margin + oy;
    const double sum_a = wrapped_box_sum(a.sums, p0 + sx, p1 + sx, q0 + sy, q1 + sy);
    const double sum_b = wrapped_box_sum(b.sums, p0 + sx, p1 + sx, q0 + sy, q1 + sy);
    return {a.mean_light + float(sum_a / area), b.mean_light + float(sum_b / area)};
}

/// Each level read through footprints of `pitch` texels laid as a frame lays them, at `side^2`
/// footprints scattered over many blocks.
inline PitchTables measure_pitch(const std::vector<TileLevel> *levels, double pitch, int side = 128) {
    PitchTables tables;
    const int pixels = side * side;
    std::vector<double> px(static_cast<size_t>(pixels)), py(static_cast<size_t>(pixels));
    for (int i = 0; i < pixels; ++i) {
        px[size_t(i)] = double((i % side) * 7919 % 4096) * pitch;
        py[size_t(i)] = double((i / side) * 104729 % 4096) * pitch;
    }
    for (int r = 0; r < 3; ++r) {
        const std::vector<TileLevel> &bank = levels[r];
        if (bank.empty()) continue;
        const int count = int(bank.size());
        std::vector<std::vector<double>> fields((static_cast<size_t>(count)));
        for (int k = 0; k < count; k += 2) {
            const int next = std::min(k + 1, count - 1);
            std::vector<double> a((static_cast<size_t>(pixels)));
            std::vector<double> b((static_cast<size_t>(pixels)));
            for (int i = 0; i < pixels; ++i) {
                const double x = px[size_t(i)], y = py[size_t(i)];
                const int bx = int(std::floor((x + pitch / 2) / double(kBlockTexels)));
                const int by = int(std::floor((y + pitch / 2) / double(kBlockTexels)));
                const TwoLights light = block_light(bank[size_t(k)], bank[size_t(next)],
                                                    x, x + pitch, y, y + pitch, bx, by,
                                                    kMeasureSeed, 0, r);
                a[size_t(i)] = -std::log10(std::max(double(light.a), 1e-9));
                b[size_t(i)] = -std::log10(std::max(double(light.b), 1e-9));
            }
            fields[size_t(k)] = std::move(a);
            fields[size_t(next)] = std::move(b);
        }
        std::vector<double> means(size_t(count), 0.0);
        for (int k = 0; k < count; ++k) {
            double total = 0;
            for (double v : fields[size_t(k)]) total += v;
            means[size_t(k)] = total / double(pixels);
        }
        tables.mean_density[r].resize(size_t(count));
        for (int k = 0; k < count; ++k) tables.mean_density[r][size_t(k)] = float(means[size_t(k)]);
        tables.blend_correlation[r].resize(size_t(count > 0 ? count - 1 : 0));
        for (int k = 0; k + 1 < count; ++k) {
            double aa = 0, bb = 0, ab = 0;
            for (int i = 0; i < pixels; ++i) {
                const double a = fields[size_t(k)][size_t(i)] - means[size_t(k)];
                const double b = fields[size_t(k + 1)][size_t(i)] - means[size_t(k + 1)];
                aa += a * a; bb += b * b; ab += a * b;
            }
            double rho = 1.0;
            if (aa > 0 && bb > 0) {
                rho = ab / std::sqrt(aa * bb);
                if (rho < -1) rho = -1;
                if (rho > 1) rho = 1;
            }
            tables.blend_correlation[r][size_t(k)] = float(rho);
        }
    }
    return tables;
}

/// The grain as a frame lays it.
struct Look {
    float layers[3] = {1, 1, 1};
    float colour = 1;
    float size = 1;
    float softness = 1;
};

/// The configuration's FILM_TILE block: the pitch and footprint, the tiles' id, each record's
/// amount, the colour mix, each record's density range, then per record the levels' mean light,
/// the mean density their footprint reads and the correlation of neighbouring levels' grain.
inline std::vector<float> configuration_block(const std::vector<Record> &records, bool monochrome,
                                             const std::vector<TileLevel> *levels,
                                             float px_per_mm, float amount, const Look &look,
                                             float tile_id) {
    const float size = std::min(std::max(look.size, 0.5f), 4.0f);
    const float softness = std::min(std::max(look.softness, 0.5f), 4.0f);
    const float pitch = (1.0f / px_per_mm) / kTileTexelMM / size;
    const float footprint = pitch * softness;

    std::vector<float> block;
    block.reserve(166);
    block.push_back(pitch);
    block.push_back(footprint);
    block.push_back(tile_id);
    for (int i = 0; i < 3; ++i) block.push_back(amount * std::max(look.layers[i], 0.0f) / size);
    block.push_back(monochrome ? 1.0f : look.colour);
    for (int r = 0; r < 3; ++r) block.push_back(records[size_t(r)].d_min);
    for (int r = 0; r < 3; ++r) block.push_back(records[size_t(r)].d_max);

    PitchTables tables;
    bool measured = false;
    for (int r = 0; r < 3; ++r) {
        if (levels[r].empty()) {
            block.insert(block.end(), size_t(kTileLevels), 1.0f);
            block.insert(block.end(), size_t(kTileLevels), 0.0f);
            block.insert(block.end(), size_t(kTileLevels), 1.0f);
            continue;
        }
        if (!measured) {
            tables = measure_pitch(levels, double(footprint));
            measured = true;
        }
        for (const TileLevel &level : levels[r]) block.push_back(level.mean_light);
        block.insert(block.end(), tables.mean_density[r].begin(), tables.mean_density[r].end());
        block.insert(block.end(), tables.blend_correlation[r].begin(), tables.blend_correlation[r].end());
        block.push_back(1.0f);
    }
    return block;
}

}  // namespace grain
