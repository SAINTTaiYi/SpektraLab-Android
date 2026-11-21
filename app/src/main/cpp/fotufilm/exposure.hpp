#ifndef FOTOFILM_HOST_EXPOSURE_HPP
#define FOTOFILM_HOST_EXPOSURE_HPP

// SpectralModel.exposureTable: the scene side of the model, over the 33^3 cube.
//
// A colour inside the Rec.2020 cube is a reflectance recovered from the measured prior.
// Outside it the model continues by its own slope (the face mirrored through the cube's
// boundary), and past the projection margin the colour becomes a mixture with monochromatic
// light at the hue's dominant wavelength -- the spectral locus is what that branch needs.

#include "film.hpp"
#include "locus.hpp"
#include "spectral.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace exposure {

constexpr int kLutDimension = 33;
constexpr float kReconstructionAnchor = 0.25f;

/// ColorScience.linearExposureDomainToRec2020
inline void domain_to_rec2020(const float p[3], float out[3]) {
    out[0] = 1.509371119f * p[0] - 0.261310044f * p[1] - 0.248061075f * p[2];
    out[1] = -0.078854123f * p[0] + 1.187622946f * p[1] - 0.108768823f * p[2];
    out[2] = 0.002086408f * p[0] - 0.031423416f * p[1] + 1.029337008f * p[2];
}

/// ColorScience.luminanceWeights -- Rec.2020's Y row.
constexpr float kLumaWeights[3] = {0.2627002f, 0.6779981f, 0.0593017f};
constexpr float kProjectionMargin = 1.5f / 32.0f;

enum class LightKind { None, Reflectance, Extrapolated, Locus };

struct Light {
    LightKind kind = LightKind::None;
    float colour[3] = {0, 0, 0};
    float face[3] = {0, 0, 0};
    float mirror[3] = {0, 0, 0};
    float luminance = 0.0f;
    int lower_band = 0, upper_band = 0;
    float upper_share = 0.0f, share = 0.0f;
};

/// SpectralModel.sceneLight.
inline Light scene_light(const float rgb_in[3]) {
    Light result;
    const float peak = std::max(rgb_in[0], std::max(rgb_in[1], rgb_in[2]));
    const float binding = std::min(rgb_in[0], std::min(rgb_in[1], rgb_in[2]));
    if (!(binding < -1e-6f * std::max(peak, 1.0f))) {
        result.kind = LightKind::Reflectance;
        for (int i = 0; i < 3; ++i) result.colour[i] = std::max(rgb_in[i], 0.0f);
        return result;
    }
    const float luminance = kLumaWeights[0] * rgb_in[0] + kLumaWeights[1] * rgb_in[1]
        + kLumaWeights[2] * rgb_in[2];
    if (!(luminance > 0.0f)) {
        result.kind = LightKind::None;
        return result;
    }
    float cube = 1.0f;
    for (int c = 0; c < 3; ++c) {
        if (rgb_in[c] < 0.0f) {
            const float reach = luminance / (luminance - rgb_in[c]);
            if (reach < cube) cube = reach;
        }
    }
    const float neutral[3] = {luminance, luminance, luminance};
    for (int c = 0; c < 3; ++c) {
        result.face[c] = std::max(neutral[c] + cube * (rgb_in[c] - neutral[c]), 0.0f);
    }
    if (!(binding < -kProjectionMargin * peak)) {
        result.kind = LightKind::Extrapolated;
        for (int c = 0; c < 3; ++c) {
            result.mirror[c] = std::max(result.face[c] * 2.0f - rgb_in[c], 0.0f);
        }
        return result;
    }
    const std::array<double, 3> direction = {
        double(rgb_in[0]) / double(luminance) - 1.0,
        double(rgb_in[1]) / double(luminance) - 1.0,
        double(rgb_in[2]) / double(luminance) - 1.0};
    const auto hit = spectral::SpectralLocus::shared().boundary(direction);
    if (!hit) {
        result.kind = LightKind::Extrapolated;
        for (int c = 0; c < 3; ++c) {
            result.mirror[c] = std::max(result.face[c] * 2.0f - rgb_in[c], 0.0f);
        }
        return result;
    }
    int binding_channel = 0, peak_channel = 0;
    for (int c = 0; c < 3; ++c) {
        if (rgb_in[c] < 0.0f && cube == luminance / (luminance - rgb_in[c])) binding_channel = c;
        if (rgb_in[c] > rgb_in[peak_channel]) peak_channel = c;
    }
    const float depth = luminance - rgb_in[binding_channel];
    const float rise = rgb_in[peak_channel] - luminance;
    const float start = luminance * (1.0f + kProjectionMargin) / (depth - kProjectionMargin * rise);
    float margin_point[3];
    for (int c = 0; c < 3; ++c) {
        margin_point[c] = neutral[c] + start * (rgb_in[c] - neutral[c]);
    }
    const float reach = float(hit->reach);
    result.kind = LightKind::Locus;
    result.luminance = luminance;
    result.lower_band = hit->lower_band;
    result.upper_band = hit->upper_band;
    result.upper_share = float(hit->upper_share);
    result.share = reach - start > 1e-6f
        ? std::min(std::max((1.0f - start) / (reach - start), 0.0f), 1.0f) : 1.0f;
    for (int c = 0; c < 3; ++c) {
        result.mirror[c] = std::max(result.face[c] * 2.0f - margin_point[c], 0.0f);
    }
    return result;
}

/// SpectralModel.monochromaticExposure.
inline void monochromatic_exposure(const Light &light, const film::Spectra &stock,
                                   const spectral::Spd &illuminant, const spectral::Spd &reference,
                                   float out[3]) {
    const float scene_y = spectral::luminance(illuminant);
    const float reference_y = spectral::luminance(reference);
    float white = 0.0f;
    for (int i = 0; i < spectral::kCount; ++i) {
        white += illuminant[size_t(i)] / scene_y * spectral_grid::kYBar[i];
    }
    const float target = light.luminance * white;
    const int lower = light.lower_band, upper = light.upper_band;
    const float lower_power = (1.0f - light.upper_share) * target
        / std::max(spectral_grid::kYBar[lower], 1e-12f);
    const float upper_power = light.upper_share * target
        / std::max(spectral_grid::kYBar[upper], 1e-12f);
    for (int layer = 0; layer < 3; ++layer) {
        const float exposure = lower_power * stock.sensitivity[size_t(layer)][size_t(lower)]
            + upper_power * stock.sensitivity[size_t(layer)][size_t(upper)];
        float reference_exposure = 0.0f;
        for (int i = 0; i < spectral::kCount; ++i) {
            reference_exposure += reference[size_t(i)] / reference_y
                * stock.sensitivity[size_t(layer)][size_t(i)];
        }
        out[layer] = exposure / std::max(reference_exposure, 1e-12f);
    }
}

inline void exposure_at(const float point[3], const film::Spectra &stock,
                        const spectral::Spd &illuminant, const spectral::Spd &reference,
                        const spectral::ReflectanceTable &model, float out[3]) {
    float rgb[3];
    domain_to_rec2020(point, rgb);
    const Light light = scene_light(rgb);
    const float *sensitivity[3] = {stock.sensitivity[0].data(), stock.sensitivity[1].data(),
                                   stock.sensitivity[2].data()};
    switch (light.kind) {
    case LightKind::None:
        out[0] = out[1] = out[2] = 0.0f;
        return;
    case LightKind::Reflectance:
        spectral::spectral_exposure(light.colour, sensitivity, model, illuminant, reference, out);
        return;
    case LightKind::Extrapolated: {
        float face[3], mirror[3];
        spectral::spectral_exposure(light.face, sensitivity, model, illuminant, reference, face);
        spectral::spectral_exposure(light.mirror, sensitivity, model, illuminant, reference, mirror);
        for (int c = 0; c < 3; ++c) out[c] = std::max(face[c] * 2.0f - mirror[c], 0.0f);
        return;
    }
    case LightKind::Locus: {
        float face[3], mirror[3];
        spectral::spectral_exposure(light.face, sensitivity, model, illuminant, reference, face);
        spectral::spectral_exposure(light.mirror, sensitivity, model, illuminant, reference, mirror);
        float edge[3];
        for (int c = 0; c < 3; ++c) edge[c] = std::max(face[c] * 2.0f - mirror[c], 0.0f);
        float mono[3];
        monochromatic_exposure(light, stock, illuminant, reference, mono);
        for (int c = 0; c < 3; ++c) out[c] = (1.0f - light.share) * edge[c] + light.share * mono[c];
        return;
    }
    }
}

/// SpectralModel.donorAnchoredExposure: the fourth record's exposure for a scene colour.
/// Same anchored reconstruction as the dye-forming layers, then the donor's own sensitivity
/// integrates it. The radiance scales back out, so the cube's coordinates stay the scene's.
inline float donor_anchored_exposure(const float rgb[3], const film::Donor &donor,
                                     const spectral::Spd &illuminant,
                                     const spectral::Spd &reference,
                                     const spectral::ReflectanceTable &model) {
    const float peak = std::max(rgb[0], std::max(rgb[1], rgb[2]));
    if (!(peak > 0.0f)) return 0.0f;
    const float radiance = peak / kReconstructionAnchor;
    const float normalized[3] = {rgb[0] / radiance, rgb[1] / radiance, rgb[2] / radiance};
    float reflectance[spectral::kCount];
    model.reflectance(normalized, reflectance);
    const float scene_y = spectral::luminance(illuminant);
    const float reference_y = spectral::luminance(reference);
    float exposure = 0.0f;
    float reference_exposure = 0.0f;
    for (int i = 0; i < spectral::kCount; ++i) {
        const float light = illuminant[size_t(i)] / scene_y;
        exposure += reflectance[i] * light * donor.sensitivity[size_t(i)];
        reference_exposure += reference[size_t(i)] / reference_y * donor.sensitivity[size_t(i)];
    }
    return exposure / std::max(reference_exposure, 1e-12f) * radiance;
}

/// SpectralModel.domainDonorExposure: the same reading of the cube cell the dye table takes.
inline float domain_donor_exposure(const float point[3], const film::Donor &donor,
                                   const spectral::Spd &illuminant,
                                   const spectral::Spd &reference,
                                   const spectral::ReflectanceTable &model) {
    float rgb[3];
    domain_to_rec2020(point, rgb);
    const Light light = scene_light(rgb);
    switch (light.kind) {
    case LightKind::None:
        return 0.0f;
    case LightKind::Reflectance:
        return donor_anchored_exposure(light.colour, donor, illuminant, reference, model);
    case LightKind::Extrapolated: {
        const float face = donor_anchored_exposure(light.face, donor, illuminant, reference, model);
        const float mirror = donor_anchored_exposure(light.mirror, donor, illuminant, reference, model);
        return std::max(face * 2.0f - mirror, 0.0f);
    }
    case LightKind::Locus: {
        const float face = donor_anchored_exposure(light.face, donor, illuminant, reference, model);
        const float mirror = donor_anchored_exposure(light.mirror, donor, illuminant, reference, model);
        const float edge = std::max(face * 2.0f - mirror, 0.0f);
        // monochromaticExposure on the donor's single sensitivity: same white, same target,
        // same reference integral, one layer instead of three.
        const float scene_y = spectral::luminance(illuminant);
        const float reference_y = spectral::luminance(reference);
        float white = 0.0f;
        for (int i = 0; i < spectral::kCount; ++i) {
            white += illuminant[size_t(i)] / scene_y * spectral_grid::kYBar[i];
        }
        const float target = light.luminance * white;
        const float lower_power = (1.0f - light.upper_share) * target
            / std::max(spectral_grid::kYBar[light.lower_band], 1e-12f);
        const float upper_power = light.upper_share * target
            / std::max(spectral_grid::kYBar[light.upper_band], 1e-12f);
        const float exposure = lower_power * donor.sensitivity[size_t(light.lower_band)]
            + upper_power * donor.sensitivity[size_t(light.upper_band)];
        float reference_exposure = 0.0f;
        for (int i = 0; i < spectral::kCount; ++i) {
            reference_exposure += reference[size_t(i)] / reference_y * donor.sensitivity[size_t(i)];
        }
        const float mono = exposure / std::max(reference_exposure, 1e-12f);
        return (1.0f - light.share) * edge + light.share * mono;
    }
    }
    return 0.0f;
}

/// The whole cube, laid out as buildLUT does: x fastest, four floats a node.
inline void build(const film::Spectra &stock, const spectral::ReflectanceTable &model,
                  std::vector<float> &out) {
    const spectral::Spd reference = spectral::at_locus(stock.kelvin);
    const size_t cells = size_t(kLutDimension) * kLutDimension * kLutDimension;
    // A donor stock partitions its fourth record; without one the slot keeps the constant 1
    // every table has always carried.
    const film::Donor *donor = stock.donors.empty() ? nullptr : &stock.donors.front();
    out.assign(cells * 4, 0.0f);
    for (int z = 0; z < kLutDimension; ++z) {
        for (int y = 0; y < kLutDimension; ++y) {
            for (int x = 0; x < kLutDimension; ++x) {
                const float point[3] = {float(x) / float(kLutDimension - 1),
                                        float(y) / float(kLutDimension - 1),
                                        float(z) / float(kLutDimension - 1)};
                float v[3];
                exposure_at(point, stock, reference, reference, model, v);
                const size_t i = ((size_t(z) * size_t(kLutDimension) + size_t(y))
                                  * size_t(kLutDimension) + size_t(x)) * 4;
                out[i] = v[0];
                out[i + 1] = v[1];
                out[i + 2] = v[2];
                out[i + 3] = donor ? domain_donor_exposure(point, *donor, reference, reference, model)
                                   : 1.0f;
            }
        }
    }
}

}  // namespace exposure

#endif
