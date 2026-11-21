#ifndef FOTOFILM_HOST_RECEIVER_HPP
#define FOTOFILM_HOST_RECEIVER_HPP

// The print's density-to-display step: a Status A reading, unmixed back into the sheet's own
// dye amounts, then read under the viewing lamp through the observer and the P3 primaries.
//
// The unmix matters because a Status A reading is an instrument's broad bands, not the dyes:
// subtracting three numbers assumes the dyes do not overlap, and they do. Newton on the exact
// reading recovers the amounts the sheet actually holds.

#include "receiver_data.hpp"
#include "spectral.hpp"
#include "spectral_data.hpp"

#include <array>
#include <cmath>
#include <vector>

namespace receiver {

using V3 = std::array<float, 3>;
using Mat3 = std::array<V3, 3>;

inline V3 mul(const Mat3 &m, const V3 &v) {
    return {m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
            m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
            m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
}

inline bool invert(const Mat3 &m, Mat3 &out) {
    const float a = m[0][0], b = m[0][1], c = m[0][2];
    const float d = m[1][0], e = m[1][1], f = m[1][2];
    const float g = m[2][0], h = m[2][1], i = m[2][2];
    const float det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    if (!(std::fabs(det) > 1e-12f)) return false;
    (void)det;
    return true;
}

/// PrintDyeUnmix.invert: cross products of the columns, then recomposed by row.
inline bool invert_columns(const Mat3 &columns, Mat3 &out) {
    auto cross = [](const V3 &a, const V3 &b) -> V3 {
        return {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    };
    auto dot = [](const V3 &a, const V3 &b) -> float {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    };
    const float determinant = dot(columns[0], cross(columns[1], columns[2]));
    if (!(std::fabs(determinant) > 1e-9f)) return false;
    const V3 c0 = cross(columns[1], columns[2]);
    const V3 c1 = cross(columns[2], columns[0]);
    const V3 c2 = cross(columns[0], columns[1]);
    const V3 r0 = {c0[0] / determinant, c0[1] / determinant, c0[2] / determinant};
    const V3 r1 = {c1[0] / determinant, c1[1] / determinant, c1[2] / determinant};
    const V3 r2 = {c2[0] / determinant, c2[1] / determinant, c2[2] / determinant};
    out = {{{r0[0], r1[0], r2[0]}, {r0[1], r1[1], r2[1]}, {r0[2], r1[2], r2[2]}}};
    return true;
}

inline V3 xyz_of(const float *spectrum) {
    V3 out{0.0f, 0.0f, 0.0f};
    for (int i = 0; i < spectral_grid::kCount; ++i) {
        out[0] += spectrum[i] * spectral_grid::kXBar[i];
        out[1] += spectrum[i] * spectral_grid::kYBar[i];
        out[2] += spectrum[i] * spectral_grid::kZBar[i];
    }
    return out;
}

inline V3 bradford_lms(const V3 &v) {
    return {0.8951f * v[0] + 0.2664f * v[1] - 0.1614f * v[2],
            -0.7502f * v[0] + 1.7135f * v[1] + 0.0367f * v[2],
            0.0389f * v[0] - 0.0685f * v[1] + 1.0296f * v[2]};
}

inline V3 from_bradford_lms(const V3 &v) {
    return {0.9869929f * v[0] - 0.1470543f * v[1] + 0.1599627f * v[2],
            0.4323053f * v[0] + 0.5183603f * v[1] + 0.0492912f * v[2],
            -0.0085287f * v[0] + 0.0400428f * v[1] + 0.9684867f * v[2]};
}

inline V3 linear_display_p3_from_xyz(const V3 &v) {
    return {2.4934969f * v[0] - 0.9313836f * v[1] - 0.4027108f * v[2],
            -0.8294890f * v[0] + 1.7626641f * v[1] + 0.0236247f * v[2],
            0.0358458f * v[0] - 0.0761724f * v[1] + 0.9568845f * v[2]};
}

/// The D65 white in P3, used to keep a flat reflector exactly neutral on the sampled grid.
inline V3 d65_display_p3_white() {
    V3 white = xyz_of(spectral_grid::kD65);
    const float y = std::max(white[1], 1e-12f);
    for (float &v : white) v /= y;
    return linear_display_p3_from_xyz(white);
}

inline V3 d65_white_xyz() {
    V3 white = xyz_of(spectral_grid::kD65);
    const float y = std::max(white[1], 1e-12f);
    for (float &v : white) v /= y;
    return white;
}

/// SpectralGrid.toLinearDisplayP3(reflectance:under:) -- Bradford chromatic adaptation carries
/// the lamp's white to D65 in cone-response space, keeping the dyes' metamerism.
inline V3 to_linear_display_p3(const spectral::Spd &reflectance, const spectral::Spd &illuminant) {
    static const V3 target = bradford_lms(d65_white_xyz());
    static const V3 p3_white = d65_display_p3_white();
    std::array<float, spectral_grid::kCount> reflected{};
    for (int i = 0; i < spectral_grid::kCount; ++i) {
        reflected[size_t(i)] = illuminant[size_t(i)] * reflectance[size_t(i)];
    }
    V3 reflected_xyz = xyz_of(reflected.data());
    V3 white_xyz = xyz_of(illuminant.data());
    // Both are divided by the *white's* Y, not the reflection's: the step is a chromatic
    // adaptation, so absolute reflectance must survive it. Dividing the reflection by its own
    // Y would renormalize every cell to unit luminance and scale each density by its own
    // reflectance -- one scalar per grid cell, exactly what an 80x error looks like.
    const float wy = std::max(white_xyz[1], 1e-12f);
    for (float &v : reflected_xyz) v /= wy;
    for (float &v : white_xyz) v /= wy;
    const V3 source = bradford_lms(white_xyz);
    const V3 reflected_lms = bradford_lms(reflected_xyz);
    const V3 adapted = {reflected_lms[0] * target[0] / std::max(source[0], 1e-12f),
                        reflected_lms[1] * target[1] / std::max(source[1], 1e-12f),
                        reflected_lms[2] * target[2] / std::max(source[2], 1e-12f)};
    V3 rgb = linear_display_p3_from_xyz(from_bradford_lms(adapted));
    for (int c = 0; c < 3; ++c) rgb[size_t(c)] /= p3_white[size_t(c)];
    return rgb;
}

/// SpectralModel.transmissionRGB: the spectrum the sheet reflects under `illuminant`, read
/// through the observer.
inline V3 transmission_rgb(const V3 &density,
                          const std::array<std::vector<float>, 3> &dyes,
                          const spectral::Spd &illuminant) {
    spectral::Spd spectrum{};
    for (int i = 0; i < spectral_grid::kCount; ++i) {
        const float d = density[0] * dyes[0][size_t(i)] + density[1] * dyes[1][size_t(i)]
                      + density[2] * dyes[2][size_t(i)];
        spectrum[size_t(i)] = std::pow(10.0f, -d);
    }
    return to_linear_display_p3(spectrum, illuminant);
}

/// Densitometry.PrintDyeUnmix: the sheet's own dye amounts behind a Status A reading.
struct Unmix {
    std::array<std::vector<float>, 3> dyes;
    std::array<std::vector<float>, 3> band;
    Mat3 inverse{};
    Mat3 columns_debug{};

    static constexpr float kLog2Of10 = 3.321928f;

    explicit Unmix(const std::array<std::vector<float>, 3> &analytical_dyes) {
        dyes = analytical_dyes;
        float integral[3] = {0.0f, 0.0f, 0.0f};
        const float *bands[3] = {receiver::kStatusA0, receiver::kStatusA1, receiver::kStatusA2};
        for (int c = 0; c < 3; ++c) {
            for (int i = 0; i < spectral_grid::kCount; ++i) integral[c] += bands[c][i];
        }
        // Every band is filled before any reading: a half-built set would read the
        // others as zero transmittance and put a 12.0 where a density belongs.
        for (auto &one : band) one.resize(spectral_grid::kCount);
        for (int c = 0; c < 3; ++c) {
            for (int i = 0; i < spectral_grid::kCount; ++i) {
                band[size_t(c)][size_t(i)] = bands[c][i] / std::max(integral[c], 1e-12f);
            }
        }
        Mat3 matrix{};
        for (int c = 0; c < 3; ++c) {
            V3 unit{0.0f, 0.0f, 0.0f};
            unit[size_t(c)] = 1.0f;
            matrix[size_t(c)] = read(unit);
        }
        // The columns are the reading of each unit amount; invert to go back.
        Mat3 columns = {{{matrix[0][0], matrix[1][0], matrix[2][0]},
                         {matrix[0][1], matrix[1][1], matrix[2][1]},
                         {matrix[0][2], matrix[1][2], matrix[2][2]}}};
        columns_debug = columns;
        if (!invert_columns(columns, inverse)) {
            inverse = {{{1.0f, 0.0f, 0.0f}, {0.0f, 1.0f, 0.0f}, {0.0f, 0.0f, 1.0f}}};
        }
    }

    V3 read(const V3 &amounts) const {
        V3 sum{0.0f, 0.0f, 0.0f};
        for (int i = 0; i < spectral_grid::kCount; ++i) {
            const float density = amounts[0] * dyes[0][size_t(i)]
                                + amounts[1] * dyes[1][size_t(i)]
                                + amounts[2] * dyes[2][size_t(i)];
            const float transmittance = std::exp2(-density * kLog2Of10);
            for (int c = 0; c < 3; ++c) sum[size_t(c)] += band[size_t(c)][size_t(i)] * transmittance;
        }
        return {-std::log10(std::max(sum[0], 1e-12f)),
                -std::log10(std::max(sum[1], 1e-12f)),
                -std::log10(std::max(sum[2], 1e-12f))};
    }

    V3 amounts_for(const V3 &target) const {
        V3 amounts = mul(inverse, target);
        for (int step = 0; step < 16; ++step) {
            const V3 reading = read(amounts);
            V3 residual = {reading[0] - target[0], reading[1] - target[1], reading[2] - target[2]};
            const float biggest = std::max(std::fabs(residual[0]),
                                           std::max(std::fabs(residual[1]), std::fabs(residual[2])));
            if (biggest < 1e-6f) break;
            const V3 step_v = mul(inverse, residual);
            for (int c = 0; c < 3; ++c) amounts[size_t(c)] -= step_v[size_t(c)];
        }
        // Clear paper is no dye at all: zero density above base unmixes to exactly zero, so
        // paper white reaches display white. A softer floor would lift every highlight.
        for (float &v : amounts) v = std::max(v, 0.0f);
        return amounts;
    }
};

}  // namespace receiver

#endif
