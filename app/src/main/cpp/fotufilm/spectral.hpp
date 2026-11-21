#ifndef FOTUFILM_HOST_SPECTRAL_HPP
#define FOTUFILM_HOST_SPECTRAL_HPP

// The spectral front end: illuminants, the measured reflectance prior, and the film
// layers' exposure. Each function mirrors its Swift original in SpectralModel.swift and
// Illuminant.swift -- same order, same precision, because a byte-exact host cannot
// afford a different rounding.

#include "spectral_data.hpp"

#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace spectral {

using Spd = std::array<float, spectral_grid::kCount>;
constexpr int kCount = spectral_grid::kCount;

inline float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

// --- illuminants -----------------------------------------------------------

/// SpectralGrid.blackbody: Planck's law on the grid, normalised to its own peak.
inline Spd blackbody(float kelvin) {
    const float c2 = 14387769.0f;
    const float temperature = kelvin > 1.0f ? kelvin : 1.0f;
    Spd values{};
    float peak = 0.0f;
    for (int i = 0; i < kCount; ++i) {
        const float wavelength = spectral_grid::kWavelengthStartNM
            + float(i) * spectral_grid::kWavelengthStepNM;
        const float v = 1.0f / (std::pow(wavelength, 5.0f)
                                * (std::exp(c2 / (wavelength * temperature)) - 1.0f));
        values[size_t(i)] = v;
        if (v > peak) peak = v;
    }
    if (peak > 0.0f) {
        for (int i = 0; i < kCount; ++i) values[size_t(i)] /= peak;
    }
    return values;
}

/// Illuminant.planckian: the black body, normalised at 560 nm.
inline Spd planckian(float kelvin) {
    Spd values = blackbody(kelvin);
    const float anchor = values[spectral_grid::kAnchorIndex];
    if (anchor > 0.0f) {
        for (int i = 0; i < kCount; ++i) values[size_t(i)] /= anchor;
    }
    return values;
}

/// WhiteBalance.daylightXY: the CIE daylight locus. The Swift hands the pair back as
/// SIMD2<Float>, so both coordinates are rounded to single before the daylight basis is
/// weighted with them -- the rounding is part of the answer, not a loss.
inline void daylight_xy(float kelvin, double &x, double &y) {
    const double t = double(clampf(kelvin, 4000.0f, 25000.0f));
    double xd = 0.0;
    if (t <= 7000.0) {
        xd = 0.244063 + 0.09911e3 / t + 2.9678e6 / (t * t) - 4.6070e9 / (t * t * t);
    } else {
        xd = 0.237040 + 0.24748e3 / t + 1.9018e6 / (t * t) - 2.0064e9 / (t * t * t);
    }
    const double yd = -3.000 * xd * xd + 2.870 * xd - 0.275;
    x = double(float(xd));
    y = double(float(yd));
}

/// Illuminant.daylight: S0 + M1 S1 + M2 S2, normalised at 560 nm.
/// PrinterProfile.filteredSpectrum: the bare lamp normalized at 560 nm, times the two
/// synthetic dichroic filters. `magenta` is a Gaussian at 550 nm, `yellow` cuts the blue end.
inline Spd printer_lamp(float kelvin, float magenta, float yellow) {
    Spd lamp = planckian(kelvin);
    if (lamp[size_t((560 - 380) / 5)] > 0.0f) {
        const float scale = 1.0f / lamp[size_t((560 - 380) / 5)];
        for (float &v : lamp) v *= scale;
    }
    for (int i = 0; i < spectral_grid::kCount; ++i) {
        const float wavelength = 380.0f + 5.0f * float(i);
        const float m = std::exp(-0.5f * std::pow((wavelength - 550.0f) / 45.0f, 2.0f));
        const float y = 1.0f / (1.0f + std::exp((wavelength - 500.0f) / 12.0f));
        lamp[size_t(i)] *= std::pow(10.0f, -magenta * m - yellow * y);
    }
    return lamp;
}

inline Spd daylight(float kelvin) {
    double x = 0.0, y = 0.0;
    daylight_xy(clampf(kelvin, 4000.0f, 25000.0f), x, y);
    const double m = 0.0241 + 0.2562 * x - 0.7341 * y;
    const float m1 = float((-1.3515 - 1.7703 * x + 5.9114 * y) / m);
    const float m2 = float((0.0300 - 31.4424 * x + 30.0717 * y) / m);
    Spd values{};
    for (int i = 0; i < kCount; ++i) {
        values[size_t(i)] = spectral_grid::kS0[i] + m1 * spectral_grid::kS1[i]
            + m2 * spectral_grid::kS2[i];
    }
    const float anchor = values[spectral_grid::kAnchorIndex];
    if (anchor > 0.0f) {
        for (int i = 0; i < kCount; ++i) values[size_t(i)] /= anchor;
    }
    return values;
}

/// Illuminant.atLocus: Planckian through 4000 K, daylight from 5000 K, smooth between.
inline Spd at_locus(float kelvin) {
    const float t = clampf(kelvin, 1000.0f, 25000.0f);
    if (t <= 4000.0f) return planckian(t);
    if (t >= 5000.0f) return daylight(t);
    const float s = (t - 4000.0f) / 1000.0f;
    const float mix = s * s * (3.0f - 2.0f * s);
    const Spd warm = planckian(t);
    const Spd cool = daylight(t);
    Spd values{};
    for (int i = 0; i < kCount; ++i) {
        values[size_t(i)] = (1.0f - mix) * warm[size_t(i)] + mix * cool[size_t(i)];
    }
    return values;
}

/// Illuminant.luminance: the SPD's Y, integrated against the observer. In double, as the
/// Swift accumulates it -- the sum of eighty-one products loses bits in single.
inline float luminance(const Spd &spd) {
    double total = 0.0;
    for (int i = 0; i < kCount; ++i) {
        total += double(spd[size_t(i)]) * double(spectral_grid::kYBar[i]);
    }
    return float(total);
}

// --- the measured reflectance prior ----------------------------------------

/// MeasuredReflectanceTable: RPR1, then u32 header, an anchor, and the packed basis.
struct ReflectanceTable {
    int dimension = 0;
    int band_count = 0;
    float anchor = 0.0f;
    std::vector<float> data;
    bool loaded = false;

    bool load(const std::string &path) {
        std::FILE *file = std::fopen(path.c_str(), "rb");
        if (!file) return false;
        std::fseek(file, 0, SEEK_END);
        const long size = std::ftell(file);
        std::fseek(file, 0, SEEK_SET);
        std::vector<unsigned char> bytes(static_cast<size_t>(size));
        const bool read_ok = std::fread(bytes.data(), 1, bytes.size(), file) == bytes.size();
        std::fclose(file);
        if (!read_ok || bytes.size() < 24) return false;
        if (std::memcmp(bytes.data(), "RPR1", 4) != 0) return false;
        auto u32 = [&](size_t offset) {
            uint32_t v = 0;
            std::memcpy(&v, bytes.data() + offset, 4);
            return v;
        };
        auto f32 = [&](size_t offset) {
            float v = 0.0f;
            std::memcpy(&v, bytes.data() + offset, 4);
            return v;
        };
        dimension = int(u32(4));
        band_count = int(u32(8));
        const int source_count = int(u32(12));
        anchor = f32(16);
        const int value_count = int(u32(20));
        const long expected = 3L * dimension * dimension * band_count;
        if (dimension < 2 || band_count != kCount || source_count <= 0 || !(anchor > 0.0f)
            || long(value_count) != expected || long(bytes.size()) != 24 + long(value_count) * 4) {
            return false;
        }
        data.resize(size_t(value_count));
        size_t offset = 24;
        for (int i = 0; i < value_count; ++i) {
            const float v = f32(offset);
            if (!std::isfinite(v) || v < 0.0f || v > 1.0f) return false;
            data[size_t(i)] = v;
            offset += 4;
        }
        loaded = true;
        return true;
    }

    /// MeasuredReflectanceTable.reflectance: the prior at the cell's chromaticity, then the
    /// engine's own brightness ray back out.
    void reflectance(const float rgb_in[3], float out[kCount]) const {
        float rgb[3] = {rgb_in[0] > 0.0f ? rgb_in[0] : 0.0f,
                        rgb_in[1] > 0.0f ? rgb_in[1] : 0.0f,
                        rgb_in[2] > 0.0f ? rgb_in[2] : 0.0f};
        const float peak = std::max(rgb[0], std::max(rgb[1], rgb[2]));
        if (!(peak > 0.0f)) {
            for (int i = 0; i < kCount; ++i) out[i] = 0.0f;
            return;
        }
        const float ray_scale = peak / anchor;
        float anchored[3] = {rgb[0] / ray_scale, rgb[1] / ray_scale, rgb[2] / ray_scale};
        int dominant = 0;
        if (anchored[1] >= anchored[dominant]) dominant = 1;
        if (anchored[2] >= anchored[dominant]) dominant = 2;

        const float extent = float(dimension - 1);
        const float x = clampf(anchored[(dominant + 1) % 3] / anchor * extent, 0.0f, extent);
        const float y = clampf(anchored[(dominant + 2) % 3] / anchor * extent, 0.0f, extent);
        const int xi = std::min(int(x), dimension - 2);
        const int yi = std::min(int(y), dimension - 2);
        const float xf = x - float(xi);
        const float yf = y - float(yi);

        auto value = [&](int dx, int dy, int band) {
            const size_t index = size_t(((dominant * dimension + yi + dy) * dimension + xi + dx)
                                        * band_count + band);
            return data[index];
        };
        for (int band = 0; band < band_count; ++band) {
            const float lower = value(0, 0, band) + xf * (value(1, 0, band) - value(0, 0, band));
            const float upper = value(0, 1, band) + xf * (value(1, 1, band) - value(0, 1, band));
            out[band] = std::max((lower + yf * (upper - lower)) * ray_scale, 0.0f);
        }
    }
};

// --- the film layers' exposure ---------------------------------------------

/// SpectralModel.spectralExposure: each layer's integral through the scene light, divided by
/// what the same layer reads under the stock's own reference light.
inline void spectral_exposure(const float rgb[3], const float *const sensitivity[3],
                              const ReflectanceTable &model,
                              const Spd &illuminant, const Spd &reference, float out[3]) {
    float reflectance[kCount];
    model.reflectance(rgb, reflectance);
    const float scene_y = luminance(illuminant);
    const float reference_y = luminance(reference);
    for (int layer = 0; layer < 3; ++layer) {
        float exposure = 0.0f;
        float reference_exposure = 0.0f;
        for (int i = 0; i < kCount; ++i) {
            const float light = illuminant[size_t(i)] / scene_y;
            const float reference_light = reference[size_t(i)] / reference_y;
            exposure += reflectance[i] * light * sensitivity[layer][i];
            reference_exposure += reference_light * sensitivity[layer][i];
        }
        out[layer] = exposure / std::max(reference_exposure, 1e-12f);
    }
}

}  // namespace spectral

#endif
