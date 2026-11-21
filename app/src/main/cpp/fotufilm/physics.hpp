#ifndef FOTUFILM_HOST_PHYSICS_HPP
#define FOTUFILM_HOST_PHYSICS_HPP

// The pixel spacing a configuration's radii were folded from, and the way back.
//
// A configuration is packed with pixel-spaced radii, so the same film on the same paper
// at another frame size needs those slots re-derived. Everything below mirrors
// FilmEngine.swift one function at a time; the arithmetic is the same order and the same
// precision, because a byte-exact host cannot afford a different rounding.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <string>
#include <vector>

namespace physics {

/// FilmEngine.grainSigmaFloorPixels.
constexpr float kGrainSigmaFloorPixels = 0.151f;
/// FilmEngine.noSpatialReachPixels.
constexpr float kNoSpatialReachPixels = 0.1f;
/// AdjacencyModel.screenedPrimarySigma / screenedSecondarySigma.
constexpr float kScreenedPrimarySigma = 0.4338526089f / 1.4142135624f;
constexpr float kScreenedSecondarySigma = 1.4816463071f / 1.4142135624f;

/// FilmEngine.gaussianRadius: the fewest taps whose dropped tail stays under 0.27%.
inline int gaussian_radius(float sigma) {
    if (!(sigma > 0.15f)) return 0;
    const float variance = 2.0f * sigma * sigma;
    const int limit = int(std::ceil(4.0f * sigma)) + 1;
    std::vector<float> tail(size_t(limit) + 1, 0.0f);
    for (int i = 1; i <= limit; ++i) {
        tail[size_t(i)] = std::exp(-float(i * i) / variance);
    }
    float total = 1.0f;
    for (int i = 1; i <= limit; ++i) total += 2.0f * tail[size_t(i)];
    float dropped = 0.0f;
    for (int radius = limit; radius >= 1; --radius) {
        dropped += 2.0f * tail[size_t(radius)];
        if (dropped > 0.0027f * total) return radius;
    }
    return 1;
}

/// FilmEngine.halationBoxRadius: the box radius whose triple pass carries this sigma.
inline int halation_box_radius(float sigma_pixels) {
    if (!(sigma_pixels > 0.3f)) return 0;
    const float box_width = std::sqrt(12.0f * sigma_pixels * sigma_pixels / 3.0f + 1.0f);
    return int((box_width - 1.0f) / 2.0f);
}

/// FilmEngine.sampledGaussianVariance: what a sampled Gaussian of this sigma delivers.
inline float sampled_gaussian_variance(float sigma) {
    const int radius = gaussian_radius(sigma);
    if (radius <= 0 || !(sigma > 0.0f)) return 0.0f;
    float total = 0.0f, moment = 0.0f;
    for (int offset = -radius; offset <= radius; ++offset) {
        const float weight = std::exp(-float(offset * offset) / (2.0f * sigma * sigma));
        total += weight;
        moment += weight * float(offset * offset);
    }
    return total > 0.0f ? moment / total : 0.0f;
}

/// FilmEngine.sampledGaussianCorrelation: the lag-1 correlation the kernel actually carries.
inline float sampled_gaussian_correlation(float sigma) {
    const int radius = gaussian_radius(sigma);
    if (radius <= 0 || !(sigma > 0.0f)) return 0.0f;
    std::vector<double> taps;
    taps.reserve(size_t(2 * radius + 1));
    for (int offset = -radius; offset <= radius; ++offset) {
        taps.push_back(std::exp(-double(offset * offset) / (2.0 * double(sigma) * double(sigma))));
    }
    double zero = 0.0, one = 0.0;
    for (size_t i = 0; i < taps.size(); ++i) zero += taps[i] * taps[i];
    for (size_t i = 0; i + 1 < taps.size(); ++i) one += taps[i] * taps[i + 1];
    return zero > 0.0 ? float(one / zero) : 0.0f;
}

/// FilmEngine.solvedSigma: bisection on a monotonic, piecewise correlation.
inline float solved_sigma(float target) {
    if (!(target > 0.0f)) return kGrainSigmaFloorPixels;
    float high = 1.0f;
    while (sampled_gaussian_correlation(high) < target && high < 64.0f) high *= 2.0f;
    float low = kGrainSigmaFloorPixels;
    if (!(sampled_gaussian_correlation(low) < target)) return low;
    for (int i = 0; i < 40; ++i) {
        const float middle = 0.5f * (low + high);
        if (sampled_gaussian_correlation(middle) < target) low = middle;
        else high = middle;
    }
    return 0.5f * (low + high);
}

/// FilmEngine.gaussianLatticeCovariance: the pixel box's triangle read through the field.
/// Kept in double, as the Swift does, until the packed parameter is needed.
inline float gaussian_lattice_covariance(float t, float sigma) {
    const double position = double(t);
    const double s = std::sqrt(std::max(2.0 * double(sigma) * double(sigma), 1e-8));
    const auto phi = [](double x) { return std::exp(-0.5 * x * x) / std::sqrt(2.0 * M_PI); };
    const auto cdf = [](double x) { return 0.5 * (1.0 + std::erf(x / std::sqrt(2.0))); };
    const auto z = [&](double x) { return (x - position) / s; };
    return float((1.0 + position) * (cdf(z(0.0)) - cdf(z(-1.0)))
               + (1.0 - position) * (cdf(z(1.0)) - cdf(z(0.0)))
               + s * (phi(z(-1.0)) - 2.0 * phi(z(0.0)) + phi(z(1.0))));
}

/// FilmEngine.foldMixture: a scan finish spreads at `fold` and hands `keep` back.
template <typename Read>
inline float fold_mixture(Read read, float fold, float keep) {
    const float kept = std::min(std::max(keep, 0.0f), 1.0f);
    return kept * kept * read(0.0f)
         + 2.0f * kept * (1.0f - kept) * read(fold)
         + (1.0f - kept) * (1.0f - kept) * read(fold * 1.4142135f);
}

/// FilmEngine.gaussianClumpCorrelation: the clump's lag-1 correlation on this lattice.
inline float gaussian_clump_correlation(float clump_sigma_pixels, float fold_sigma_pixels,
                                        float fold_keep) {
    const auto read = [&](float t, float f) {
        return gaussian_lattice_covariance(t, std::sqrt(clump_sigma_pixels * clump_sigma_pixels
                                                        + f * f / 2.0f));
    };
    const float r0 = fold_mixture([&](float f) { return read(0.0f, f); },
                                  fold_sigma_pixels, fold_keep);
    const float r1 = fold_mixture([&](float f) { return read(1.0f, f); },
                                  fold_sigma_pixels, fold_keep);
    return r0 > 0.0f ? r1 / r0 : 0.0f;
}

/// FilmEngine.discreteGrainSigma: the Gaussian sigma whose lattice correlation matches.
inline float discrete_grain_sigma(float clump_sigma_pixels, float fold_sigma_pixels,
                                  float fold_keep) {
    return solved_sigma(gaussian_clump_correlation(clump_sigma_pixels, fold_sigma_pixels,
                                                   fold_keep));
}


// --- the silver emulsion's Boolean field -----------------------------------
//
// A monochrome stock's grain is opaque discs, not a Gaussian: the render still lays a
// Gaussian, so the clump it is handed is solved for from the field's own covariance at the
// coverage the published granularity is read at. All of it is Math.swift's bessi0/bessi1
// and FilmEngine.booleanGrainCorrelation, in the same order.

/// Math.swift scaledBesselI0: the modified Bessel function, scaled by exp(-|x|).
inline float scaled_bessel_i0(float x) {
    const double ax = std::fabs(double(x));
    if (ax < 3.75) {
        const double t = (ax / 3.75) * (ax / 3.75);
        const double series = 1.0 + t * (3.5156229 + t * (3.0899424 + t * (1.2067492
            + t * (0.2659732 + t * (0.0360768 + t * 0.0045813)))));
        return float(series * std::exp(-ax));
    }
    const double t = 3.75 / ax;
    const double series = 0.39894228 + t * (0.01328592 + t * (0.00225319
        + t * (-0.00157565 + t * (0.00916281 + t * (-0.02057706
        + t * (0.02635537 + t * (-0.01647633 + t * 0.00392377)))))));
    return float(series / std::sqrt(ax));
}

/// Math.swift scaledBesselI1.
inline float scaled_bessel_i1(float x) {
    const double ax = std::fabs(double(x));
    if (ax < 3.75) {
        const double t = (ax / 3.75) * (ax / 3.75);
        const double series = ax * (0.5 + t * (0.87890594 + t * (0.51498869
            + t * (0.15084934 + t * (0.02658733 + t * (0.00301532
            + t * 0.00032411))))));
        return float(series * std::exp(-ax));
    }
    const double t = 3.75 / ax;
    const double tail = 0.02282967 + t * (-0.02895312 + t * (0.01787654 - t * 0.00420059));
    const double series = 0.39894228 + t * (-0.03988024 + t * (-0.00362018
        + t * (0.00163801 + t * (-0.01031555 + t * tail))));
    return float(series / std::sqrt(ax));
}

/// FilmStock.granularityApertureResponse: what the 48 um aperture keeps of a clump.
inline float granularity_aperture_response(float clump_sigma_mm) {
    if (!(clump_sigma_mm > 0.0f)) return 1.0f;
    const float z = 0.024f * 0.024f / (2.0f * clump_sigma_mm * clump_sigma_mm);
    const float lost = scaled_bessel_i0(z) + scaled_bessel_i1(z);
    return std::sqrt(std::max(1.0f - lost, 1e-4f));
}

/// BooleanGrain.discIntersectionArea: the area two discs of `radius` share at separation h.
inline double disc_intersection_area(double h, double radius) {
    if (!(radius > 0.0)) return 0.0;
    if (h >= 2.0 * radius) return 0.0;
    if (h <= 0.0) return M_PI * radius * radius;
    const double d = h / 2.0;
    return 2.0 * radius * radius * std::acos(d / radius)
         - 2.0 * d * std::sqrt(radius * radius - d * d);
}

/// BooleanGrain.coverageCovariance: (1-a)^2 (exp(lambda K(h)) - 1).
inline double coverage_covariance(double h, double radius, double coverage_input) {
    const double coverage = std::min(std::max(coverage_input, 1e-6), 1.0 - 1e-6);
    const double lambda = -std::log(1.0 - coverage) / (M_PI * radius * radius);
    return (1.0 - coverage) * (1.0 - coverage)
         * std::expm1(lambda * disc_intersection_area(h, radius));
}

/// FilmEngine.booleanGrainCorrelation: the field's lag-1 correlation on this lattice,
/// integrated over the pixel's area in both axes and through the enlarger fold.
inline float boolean_grain_correlation(float disc_radius_pixels, float coverage_input,
                                       float fold, float keep) {
    if (!(disc_radius_pixels > 0.0f)) return 0.0f;
    const double radius = double(disc_radius_pixels);
    const double coverage = double(std::min(std::max(coverage_input, 1e-4f), 0.99f));
    const auto covariance = [&](double h) {
        return coverage_covariance(h, radius, coverage);
    };
    const double span = std::min(2.0 * radius, 2.0 + 6.0 * double(fold));
    const int steps = std::min(std::max(int(std::ceil(2.0 * span / 0.03)), 64), 256) / 2 * 2;
    const double step = 2.0 * span / double(steps);
    const size_t n = static_cast<size_t>(steps);
    std::vector<double> axis(n);
    for (int i = 0; i < steps; ++i) axis[size_t(i)] = -span + (double(i) + 0.5) * step;
    std::vector<float> grid(n * n, 0.0f);
    for (int i = steps / 2; i < steps; ++i) {
        for (int j = steps / 2; j < steps; ++j) {
            const double x = axis[size_t(i)], y = axis[size_t(j)];
            const float value = float(covariance(std::sqrt(x * x + y * y)));
            grid[size_t(i) * size_t(steps) + size_t(j)] = value;
            grid[size_t(steps - 1 - i) * size_t(steps) + size_t(j)] = value;
            grid[size_t(i) * size_t(steps) + size_t(steps - 1 - j)] = value;
            grid[size_t(steps - 1 - i) * size_t(steps) + size_t(steps - 1 - j)] = value;
        }
    }
    const int radial_steps = 512;
    double radial = 0.0;
    for (int i = 0; i < radial_steps; ++i) {
        const double h = 2.0 * radius * (double(i) + 0.5) / double(radial_steps);
        radial += covariance(h) * h;
    }
    const float integral = float(2.0 * M_PI * radial * 2.0 * radius / double(radial_steps));
    if (!(integral > 0.0f)) return 0.0f;
    const auto response = [&](float t, float f) {
        const float sigma = f * 0.7071068f;
        std::vector<float> across(n), down(n);
        for (int i = 0; i < steps; ++i) {
            across[size_t(i)] = gaussian_lattice_covariance(t - float(axis[size_t(i)]), sigma);
            down[size_t(i)] = gaussian_lattice_covariance(-float(axis[size_t(i)]), sigma);
        }
        float total = 0.0f;
        for (int i = 0; i < steps; ++i) {
            if (across[size_t(i)] == 0.0f) continue;
            float row = 0.0f;
            for (int j = 0; j < steps; ++j) {
                row += grid[size_t(i) * size_t(steps) + size_t(j)] * down[size_t(j)];
            }
            total += across[size_t(i)] * row;
        }
        return total * float(step * step) / integral;
    };
    const float r0 = std::min(fold_mixture([&](float f) { return response(0.0f, f); }, fold, keep),
                              1.0f);
    const float r1 = fold_mixture([&](float f) { return response(1.0f, f); }, fold, keep);
    return r0 > 0.0f ? r1 / r0 : 0.0f;
}

/// FilmEngine.silverEquivalentClumpPixels: the Gaussian clump matching the Boolean field.
inline float silver_equivalent_clump_pixels(float disc_radius_pixels, float coverage,
                                            float fold, float keep) {
    const float target = boolean_grain_correlation(disc_radius_pixels, coverage, fold, keep);
    if (!(target > 0.0f)) return 0.0f;
    const auto correlation = [&](float clump) {
        return gaussian_clump_correlation(clump, fold, keep);
    };
    float high = 1.0f;
    while (correlation(high) < target && high < 64.0f) high *= 2.0f;
    float low = 0.0f;
    if (!(correlation(low) < target)) return 0.0f;
    for (int i = 0; i < 40; ++i) {
        const float middle = 0.5f * (low + high);
        if (correlation(middle) < target) low = middle;
        else high = middle;
    }
    return 0.5f * (low + high);
}

/// What a bake dump's physio.txt holds: millimetres, plus the frame's own pixel spacing.
struct FramePhysics {
    bool valid = false;
    float px_per_mm = 0.0f;
    float emulsion_diffusion_mm[3] = {0, 0, 0};
    float emulsion_diffusion_secondary_mm[3] = {0, 0, 0};
    float luma_diffusion_mm = 0.0f;
    float coupler_diffusion_mm = 0.0f;
    float adjacency_radius_mm = 0.0f;
    bool adjacency_screened = false;
    float grain_clumps_per_mm2 = 0.0f;
    float halation_sigma_mm[3] = {0, 0, 0};
    float halation_ring_mm[3] = {0, 0, 0};
    float enlarger_blur_mm = 0.0f;
    float enlarger_scan_sharpening = 0.0f;
    float rendered_clump_mm[3] = {0, 0, 0};
    float rendered_mottle_mm[3] = {0, 0, 0};
    float mottle_size_ratio = 1.0f;
    float mottle_share = 0.0f;
    float grain_amplitude[3] = {0, 0, 0};
    bool grain_amplitude_is_raw = false;
    float grain_aperture_response[3] = {1, 1, 1};
    bool is_silver = false;
    float grain_size_mm = 0.0f;
    float grain_layer_size_ratio[3] = {1, 1, 1};
    float grain_anchor_coverage = 0.0f;
};

inline bool read_physics(const std::string &path, FramePhysics &out) {
    std::FILE *file = std::fopen(path.c_str(), "r");
    if (!file) return false;
    std::map<std::string, float> values;
    char line[256];
    while (std::fgets(line, sizeof line, file)) {
        char *equals = std::strchr(line, '=');
        if (!equals) continue;
        *equals = 0;
        values[line] = std::strtof(equals + 1, nullptr);
    }
    std::fclose(file);
    auto get = [&](const char *name, float fallback = 0.0f) {
        auto it = values.find(name);
        return it == values.end() ? fallback : it->second;
    };
    out.px_per_mm = get("pxPerMM");
    if (!(out.px_per_mm > 0.0f)) return false;
    for (int i = 0; i < 3; ++i) {
        const std::string c = std::to_string(i);
        out.emulsion_diffusion_mm[i] = get(("emulsionDiffusionMM_" + c).c_str());
        out.emulsion_diffusion_secondary_mm[i] =
            get(("emulsionDiffusionSecondaryMM_" + c).c_str());
        out.halation_sigma_mm[i] = get(("halationSigmaMM_" + c).c_str());
        out.halation_ring_mm[i] = get(("halationRingRadiusMM_" + c).c_str());
        out.rendered_clump_mm[i] = get(("renderedClumpMM_" + c).c_str());
        out.rendered_mottle_mm[i] = get(("renderedMottleMM_" + c).c_str());
    }
    out.luma_diffusion_mm = get("lumaDiffusionMM");
    out.coupler_diffusion_mm = get("couplerDiffusionMM");
    out.adjacency_radius_mm = get("adjacencyRadiusMM");
    out.adjacency_screened = get("adjacencyScreened") > 0.5f;
    out.grain_clumps_per_mm2 = get("grainClumpsPerMM2");
    out.enlarger_blur_mm = get("enlargerBlurMM");
    out.enlarger_scan_sharpening = get("enlargerScanSharpening");
    out.mottle_size_ratio = get("mottleSizeRatio", 1.0f);
    out.mottle_share = get("mottleShare");
    out.is_silver = get("isSilver") > 0.5f;
    out.grain_size_mm = get("grainSizeMM");
    out.grain_anchor_coverage = get("grainAnchorCoverage");
    for (int i = 0; i < 3; ++i) {
        const std::string c = std::to_string(i);
        const float raw = get(("grainAmplitudeRaw_" + c).c_str(), -1.0f);
        if (raw >= 0.0f) {
            out.grain_amplitude[i] = raw;
            out.grain_amplitude_is_raw = true;
        } else {
            out.grain_amplitude[i] = get(("grainAmplitude_" + c).c_str());
        }
        out.grain_aperture_response[i] = get(("grainApertureResponse_" + c).c_str(), 1.0f);
        out.grain_layer_size_ratio[i] = get(("grainLayerSizeRatio_" + c).c_str(), 1.0f);
    }
    out.valid = true;
    return true;
}

}  // namespace physics

#endif
