#ifndef FOTUFILM_HOST_PAPER_HPP
#define FOTUFILM_HOST_PAPER_HPP

// PrintStock and its Spectra: what a chosen paper is made of.
//
// The datasheet publishes dye shapes at their own peaks and, when it says so, the ratio of the
// three that forms a visual neutral. The engine multiplies the ratio in, extends the layer
// sensitivity's tails by the published decay, then divides the dyes by their pointwise sum so a
// neutral still transmits 10^-d and the neutral axis is preserved.

#include "curve.hpp"
#include "spectral_data.hpp"

#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

namespace paper {

constexpr float kStepNM = 5.0f;
constexpr float kTailDecades = 3.0f;
constexpr float kMinTailDecayPerNM = 0.05f;

/// SpectralModel.continuedTails: the record's published decay carried past its support, cut
/// off once it falls `kTailDecades` below the peak.
inline std::vector<float> continued_tails(const std::vector<float> &layer) {
    float peak = 0.0f;
    for (float v : layer) peak = std::max(peak, v);
    if (!(peak > 0.0f)) return layer;
    size_t low = layer.size(), high = 0;
    for (size_t i = 0; i < layer.size(); ++i) {
        if (layer[i] > 0.0f) {
            if (low == layer.size()) low = i;
            high = i;
        }
    }
    if (low == layer.size() || high <= low) return layer;
    const float cutoff = std::log10(peak) - kTailDecades;
    std::vector<float> result = layer;
    const size_t reach = size_t(std::lround(30.0f / kStepNM));

    // Terminal slope in log10 per nm over up to 30 nm of trace, signed so that a positive
    // value means the record is falling outward.
    auto decay = [&](size_t edge, bool inward) -> float {
        const size_t anchor = inward
            ? std::min(high, edge + reach)
            : (edge > low + reach ? edge - reach : low);
        if (anchor == edge) return kMinTailDecayPerNM;
        const float rise = std::log10(layer[edge]) - std::log10(layer[anchor]);
        const float span = float(edge > anchor ? edge - anchor : anchor - edge) * kStepNM;
        return std::max(-rise / span, kMinTailDecayPerNM);
    };

    if (high + 1 < layer.size()) {
        const float rate = decay(high, false);
        float value = std::log10(layer[high]);
        for (size_t i = high + 1; i < layer.size(); ++i) {
            value -= rate * kStepNM;
            result[i] = value > cutoff ? std::pow(10.0f, value) : 0.0f;
        }
    }
    if (low > 0) {
        const float rate = decay(low, true);
        float value = std::log10(layer[low]);
        for (size_t i = low; i-- > 0;) {
            value -= rate * kStepNM;
            result[i] = value > cutoff ? std::pow(10.0f, value) : 0.0f;
        }
    }
    return result;
}

/// SpectralGrid.partition: the dye sum normalized at every wavelength.
inline std::array<std::vector<float>, 3> partition(const std::array<std::vector<float>, 3> &d) {
    auto out = d;
    for (size_t i = 0; i < out[0].size(); ++i) {
        const float sum = d[0][i] + d[1][i] + d[2][i];
        for (int layer = 0; layer < 3; ++layer) out[size_t(layer)][i] /= std::max(sum, 1e-12f);
    }
    return out;
}

/// SpectralGrid.normalizeSensitivities: each layer divided by its D65-weighted integral.
inline std::array<std::vector<float>, 3> normalize_sensitivities(
    const std::array<std::vector<float>, 3> &layers) {
    auto out = layers;
    for (int layer = 0; layer < 3; ++layer) {
        float integral = 0.0f;
        for (int i = 0; i < spectral_grid::kCount; ++i) {
            integral += out[size_t(layer)][size_t(i)] * spectral_grid::kD65[i];
        }
        const float scale = std::max(integral, 1e-12f);
        for (int i = 0; i < spectral_grid::kCount; ++i) out[size_t(layer)][size_t(i)] /= scale;
    }
    return out;
}

struct Spectra {
    std::array<std::vector<float>, 3> analytical_dyes;
    std::array<std::vector<float>, 3> dyes;
    std::array<std::vector<float>, 3> sensitivity;
};

/// PrintStock.Process: what family of material the sheet is, which decides whether its layer
/// sensitivities keep the sheet's published inter-layer speed scale.
enum class Process : int { PhotoPaper = 0, ReleasePrintFilm = 1, PositivePaper = 2, Negative = 3 };

struct Stock {
    std::string name;
    std::array<curve::Curve, 3> curves;
    Spectra spectra;
    Process process = Process::PhotoPaper;
    bool keeps_layer_speed_scale = false;
    /// Release print film only: the published gross Status A setup aims, and whether the sheet's
    /// sensitivity was measured 1.0 above minimum density rather than at a gross density of 1.
    float lad_status_a[3] = {0.0f, 0.0f, 0.0f};
    bool sensitivity_above_minimum = false;

    bool is_projected() const { return process == Process::ReleasePrintFilm; }
    bool is_positive_paper() const { return process == Process::PositivePaper; }
    bool is_negative_medium() const { return process == Process::Negative; }
};

inline bool load(const std::string &path, Stock &out, std::string &error) {
    std::FILE *file = std::fopen(path.c_str(), "rb");
    if (!file) {
        error = "cannot open " + path;
        return false;
    }
    char magic[8] = {0};
    std::fread(magic, 1, 8, file);
    const bool pap2 = std::memcmp(magic, "PAP2", 4) == 0;
    if (!pap2 && std::memcmp(magic, "PAP1", 4) != 0) {
        std::fclose(file);
        error = "bad magic in " + path;
        return false;
    }
    if (pap2) {
        uint32_t process = 0, above_minimum = 0;
        if (std::fread(&process, 4, 1, file) != 1 || std::fread(&above_minimum, 4, 1, file) != 1) {
            std::fclose(file);
            error = "short header in " + path;
            return false;
        }
        std::fread(out.lad_status_a, 4, 3, file);
        out.process = Process(process);
        out.sensitivity_above_minimum = above_minimum != 0;
    }
    std::array<std::vector<float>, 3> dye, sens;
    for (auto &layer : dye) {
        layer.resize(spectral_grid::kCount);
        std::fread(layer.data(), sizeof(float), layer.size(), file);
    }
    float neutral[3];
    std::fread(neutral, sizeof(float), 3, file);
    for (auto &layer : sens) {
        layer.resize(spectral_grid::kCount);
        std::fread(layer.data(), sizeof(float), layer.size(), file);
    }
    float params[18];
    std::fread(params, sizeof(float), 18, file);
    std::fclose(file);

    // The published ratio of the three that forms a neutral; absent means the sheet drew them
    // at the amounts that already do.
    std::array<std::vector<float>, 3> amounts = dye;
    for (int layer = 0; layer < 3; ++layer) {
        for (float &v : amounts[size_t(layer)]) v *= neutral[layer];
    }
    std::array<std::vector<float>, 3> tailed;
    for (int layer = 0; layer < 3; ++layer) tailed[size_t(layer)] = continued_tails(sens[size_t(layer)]);

    out.spectra.analytical_dyes = amounts;
    out.spectra.dyes = partition(amounts);
    // Process.keepsLayerSpeedScale: a projected print keeps its published inter-layer
    // speed scale, because that scale is what the additive printer solves against.
    out.keeps_layer_speed_scale = out.is_projected();
    out.spectra.sensitivity = out.keeps_layer_speed_scale ? tailed : normalize_sensitivities(tailed);
    for (int layer = 0; layer < 3; ++layer) {
        curve::Curve &c = out.curves[size_t(layer)];
        c.d_min = params[layer * 6 + 0];
        c.gamma = params[layer * 6 + 1];
        c.toe = params[layer * 6 + 2];
        c.toe_width = params[layer * 6 + 3];
        c.shoulder = params[layer * 6 + 4];
        c.shoulder_width = params[layer * 6 + 5];
        c.has_secondary = false;
        c.has_sampled = false;
    }
    return true;
}

}  // namespace paper

#endif
