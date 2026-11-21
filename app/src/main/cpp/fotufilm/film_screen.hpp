#ifndef FOTOFILM_HOST_FILM_SCREEN_HPP
#define FOTOFILM_HOST_FILM_SCREEN_HPP

// Digital Reference's film table: the negative read through a receiver's bands instead of a
// printing paper.
//
// What differs from the reflection path is not the light. `printingIllumination` gives `.screen`
// an equal-energy lamp, and `energyReader` still returns the relative log energy each record
// receives against mid-grey. What changes is what is done with it afterwards:
//
//   relative log energy -> unmix into the negative's dye amounts -> toe balance -> display
//
// The unmix matters because a Status A reading is three broad bands, not the dyes: the three
// amounts a reading implies are only recoverable by inverting the measured band response, and
// `partition` keeps an equal-channel exposure exactly on the neutral axis.
//
// The toe balance is Auto Levels' doing. A colour negative's last stops pile up into a sliver
// of read, so a straight read meets display black a hair under mid-grey and leaves a corner in
// the table; the balance bends the bottom half up so the shadows keep their separation down to
// where the negative stops recording, while black itself stays black.
//
// `stretchShadows` sits outside, but `screenShadowScale` is 1 for every style except
// `referenceExposure`, so on Auto Levels and Graded Print it is the identity and is not applied.

#include "film.hpp"
#include "spectral.hpp"
#include "screen.hpp"
#include "film_reversal.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace film_screen {

using V3 = std::array<float, 3>;

inline constexpr float kLog2Of10 = 3.321928f;

/// SpectralGrid.dyes(family: .kodakNegative): three Gaussian dye shapes with a floor, raised to
/// the sixth power (which narrows them to the measured dyes' skirts), then `partition` — divided
/// by their pointwise sum so equal amounts transmit 10^-d and stay neutral.
inline std::array<std::vector<float>, 3> kodak_negative_dyes() {
    constexpr float centers[3] = {665.0f, 548.0f, 445.0f};
    constexpr float widths[3] = {64.0f, 50.0f, 45.0f};
    constexpr float floor_value = 0.035f;
    std::array<std::vector<float>, 3> out;
    for (int c = 0; c < 3; ++c) {
        out[size_t(c)].resize(size_t(spectral_grid::kCount));
        for (int i = 0; i < spectral_grid::kCount; ++i) {
            const float wavelength = 380.0f + 5.0f * float(i);
            const float x = (wavelength - centers[c]) / std::max(widths[c], 1.0f);
            const float gaussian = std::exp(-0.5f * x * x) + floor_value;
            out[size_t(c)][size_t(i)] = gaussian * gaussian * gaussian
                                      * gaussian * gaussian * gaussian;
        }
    }
    for (int i = 0; i < spectral_grid::kCount; ++i) {
        const float sum = std::max(out[0][size_t(i)] + out[1][size_t(i)] + out[2][size_t(i)],
                                   1e-12f);
        for (int c = 0; c < 3; ++c) out[size_t(c)][size_t(i)] /= sum;
    }
    return out;
}

inline float cross_dot(const V3 &a, const V3 &b, int i) {
    // cross(a,b)[i]
    switch (i) {
        case 0: return a[1] * b[2] - a[2] * b[1];
        case 1: return a[2] * b[0] - a[0] * b[2];
        default: return a[0] * b[1] - a[1] * b[0];
    }
}

/// DigitalReferenceReceiver.DensityUnmix: the small-signal inverse of the receiver's band
/// response. The dyes partition unity, so equal amounts transmit a constant spectrum and the
/// derivative of the measured density is the band-weighted mean of each dye.
struct DensityUnmix {
    std::array<V3, 3> rows{};

    DensityUnmix() = default;

    /// `sensitivity` is the receiver's three layer sensitivities (`SpectralGrid.paperSensitivity`
    /// at the paper's own bands). The illuminant is equal energy, so the weight is the
    /// sensitivity alone.
    explicit DensityUnmix(const std::array<std::vector<float>, 3> &sensitivity) {
        const auto dyes = kodak_negative_dyes();
        std::array<V3, 3> response{};
        for (int band = 0; band < 3; ++band) {
            float total = 0.0f;
            V3 weighted{};
            for (int i = 0; i < spectral_grid::kCount; ++i) {
                const float weight = sensitivity[size_t(band)][size_t(i)];
                total += weight;
                for (int dye = 0; dye < 3; ++dye) {
                    weighted[size_t(dye)] += weight * dyes[size_t(dye)][size_t(i)];
                }
            }
            const float divisor = std::max(total, 1e-12f);
            for (int dye = 0; dye < 3; ++dye) response[size_t(band)][size_t(dye)] =
                weighted[size_t(dye)] / divisor;
        }
        // Transpose the cofactors into inverse rows; normalizing their sums cancels the common
        // determinant and holds an equal-channel exposure exactly on the neutral axis.
        for (int row = 0; row < 3; ++row) {
            V3 r{};
            r[0] = cross_dot(response[1], response[2], row);
            r[1] = cross_dot(response[2], response[0], row);
            r[2] = cross_dot(response[0], response[1], row);
            const float sum = r[0] + r[1] + r[2];
            for (int c = 0; c < 3; ++c) rows[size_t(row)][size_t(c)] = r[size_t(c)] / sum;
        }
    }

    V3 read(const V3 &relative_log_energy) const {
        V3 out{};
        for (int r = 0; r < 3; ++r) {
            out[size_t(r)] = rows[size_t(r)][0] * relative_log_energy[0]
                           + rows[size_t(r)][1] * relative_log_energy[1]
                           + rows[size_t(r)][2] * relative_log_energy[2];
        }
        return out;
    }
};

/// SpectralModel.ScreenToeBalance: bends the shadow half of the read up so the negative's last
/// stops keep their separation, and lands on the same black.
struct ScreenToeBalance {
    /// `(x = read[channel], y = read.green - read[channel])` along the neutral wedge.
    std::array<std::vector<std::array<float, 2>>, 3> wedges;
    bool active = false;

    static constexpr float kStart = 0.5f;

    ScreenToeBalance() = default;

    /// `read_density` maps a developed density triple to the unmixed read. The wedge is the
    /// stock's own: `-8` to `0` log exposure in 257 steps, exactly as `screenToeBalance` walks it.
    template <typename ReadFn>
    static ScreenToeBalance build(const film::Spectra &stock, ReadFn &&read_density) {
        ScreenToeBalance balance;
        if (stock.reversal || stock.monochrome) return balance;
        std::vector<V3> wedge;
        wedge.reserve(257);
        for (int i = 0; i <= 256; ++i) {
            const float log_exposure = -8.0f * float(i) / 256.0f;
            V3 density{};
            for (int layer = 0; layer < 3; ++layer) {
                density[size_t(layer)] = stock.curves[size_t(layer)].density(log_exposure);
            }
            wedge.push_back(read_density(density));
        }
        for (int channel = 0; channel < 3; ++channel) {
            auto &samples = balance.wedges[size_t(channel)];
            for (const V3 &r : wedge) {
                const float x = r[size_t(channel)];
                if (!samples.empty() && !(x > samples.back()[0] + 1e-5f)) continue;
                samples.push_back({x, r[1] - x});
            }
        }
        balance.active = true;
        return balance;
    }

    static float apply_channel(const std::vector<std::array<float, 2>> &samples, float v) {
        if (samples.size() < 2) return v;
        const float first_x = samples.front()[0], last_x = samples.back()[0];
        if (!(last_x > first_x)) return v;
        const float from = first_x + kStart * (last_x - first_x);
        if (!(v > from)) return v;
        float offset = samples.back()[1];
        if (v <= last_x) {
            size_t upper = 1;
            while (upper < samples.size() && !(samples[upper][0] >= v)) ++upper;
            if (upper < samples.size()) {
                const auto &a = samples[upper - 1];
                const auto &b = samples[upper];
                const float span = b[0] - a[0];
                offset = a[1] + (span != 0.0f ? (b[1] - a[1]) * (v - a[0]) / span : 0.0f);
            }
        }
        const float t = std::min(std::max((v - from) / (last_x - from), 0.0f), 1.0f);
        return v + t * t * (3.0f - 2.0f * t) * (offset - samples.front()[1]);
    }

    V3 operator()(const V3 &read) const {
        if (!active) return read;
        V3 out{};
        for (int c = 0; c < 3; ++c) {
            out[size_t(c)] = apply_channel(wedges[size_t(c)], read[size_t(c)]);
        }
        return out;
    }
};

/// The relative log energy each record receives against mid-grey, through `sensitivity` and
/// `lamp` — `energyReader` with `castOffset` zero, which it is for every medium but Lab Scan.
inline void relative_energy(const film::Spectra &stock,
                            const std::array<std::vector<float>, 3> &sensitivity,
                            const std::vector<float> &offset,
                            const float mid_density[3], const spectral::Spd &lamp,
                            const float density[3], float out[3]) {
    auto exposure = [&](const float d[3], int channel) {
        float total = 0.0f;
        for (int i = 0; i < spectral_grid::kCount; ++i) {
            // `filmDensityOffset` is nil for a stock that states no minimum density, and the
            // caller then passes an empty vector.
            const float base = offset.size() == size_t(spectral_grid::kCount) ? offset[size_t(i)]
                                                                            : 0.0f;
            const float absorbed = d[0] * stock.dye[0][size_t(i)]
                                 + d[1] * stock.dye[1][size_t(i)]
                                 + d[2] * stock.dye[2][size_t(i)]
                                 + base;
            total += std::pow(10.0f, -absorbed) * lamp[size_t(i)]
                   * sensitivity[size_t(channel)][size_t(i)];
        }
        return total;
    };
    for (int c = 0; c < 3; ++c) {
        const float energy = exposure(density, c);
        const float mid = exposure(mid_density, c);
        out[c] = std::log10(std::max(energy, 1e-12f) / std::max(mid, 1e-12f));
    }
}

/// The `.screen` film table for a colour negative. `sensitivity` is the receiver's own
/// (`SpectralGrid.paperSensitivity`), `lamp` is equal energy.
inline bool build(const film::Spectra &stock,
                  const std::array<std::vector<float>, 3> &sensitivity,
                  const std::vector<float> &offset,
                  const spectral::Spd &lamp,
                  std::vector<float> &out, const char *&error,
                  int style = 2 /* 0 reference-exposure, 1 graded-print, 2 auto-levels */) {
    if (stock.reversal || stock.monochrome) {
        error = "film_screen: reversal and monochrome take other branches";
        return false;
    }
    float mid_density[3];
    float ranges[3];
    float d_min[3];
    for (int c = 0; c < 3; ++c) {
        mid_density[c] = stock.curves[size_t(c)].density(0.0f);
        d_min[c] = stock.curves[size_t(c)].d_min;
        ranges[c] = stock.curves[size_t(c)].d_max() - stock.curves[size_t(c)].d_min;
    }

    const DensityUnmix unmix(sensitivity);
    const auto read_density = [&](const float density[3]) {
        float rel[3];
        relative_energy(stock, sensitivity, offset, mid_density, lamp, density, rel);
        return unmix.read(V3{rel[0], rel[1], rel[2]});
    };
    // The toe balance is Auto Levels' per-record black point. Every other style keeps the stock's
    // own shadow balance and levels the whole read in the kernel instead.
    ScreenToeBalance toe;
    if (style == 2) {
        toe = ScreenToeBalance::build(stock, [&](const V3 &d) { return read_density(&d[0]); });
    }
    // `screenShadowScale`: only Reference Exposure stretches the shadow side, carrying the film
    // base onto display black without touching the highlight contrast.
    float shadow_scale = 1.0f;
    if (style == 0) {
        const curve::Curve ref = curve::Curve{0.07f, 2.60f, -0.52f, 0.16f, 0.42f, 0.14f};
        const float anchor = curve::log_exposure(ref, ref.d_min + 0.744f);
        const float base_target = curve::log_exposure(ref, ref.d_max() - 0.03f) - anchor;
        const curve::Curve &green = stock.curves[1];
        const float base_read = std::max(green.density(0.0f) - green.d_min, 0.05f);
        shadow_scale = base_target / base_read;
    }

    out.assign(size_t(33) * 33 * 33 * 4, 0.0f);
    for (size_t cell = 0; cell < out.size() / 4; ++cell) {
        const size_t z = cell / (33u * 33u), y = (cell / 33u) % 33u, x = cell % 33u;
        const float density[3] = {d_min[0] + float(x) / 32.0f * ranges[0],
                                  d_min[1] + float(y) / 32.0f * ranges[1],
                                  d_min[2] + float(z) / 32.0f * ranges[2]};
        const V3 read = read_density(density);
        V3 balanced = toe(read);
        if (style == 0) {
            for (int c = 0; c < 3; ++c) {
                const float v = balanced[size_t(c)];
                balanced[size_t(c)] = v > 0.0f
                    ? v + (shadow_scale - 1.0f) * v * (1.0f - std::exp(-v / 0.08f)) : v;
            }
        }
        for (int c = 0; c < 3; ++c) out[cell * 4 + size_t(c)] = balanced[size_t(c)];
        out[cell * 4 + 3] = 1.0f;
    }
    (void)error;
    return true;
}

// --- monochrome: the legacy direct read -------------------------------------

inline float neutral_density(const film::Spectra &stock, float log_exposure) {
    return (stock.curves[0].density(log_exposure) + stock.curves[1].density(log_exposure)
            + stock.curves[2].density(log_exposure)) / 3.0f;
}

/// `buildTables`'s `readsLayersDirectly` branch: a monochrome negative read on its own neutral
/// wedge rather than through a receiver. `screenReading` tabulates, per record, how far the
/// stock's neutral density has fallen from mid-grey at each density along that record's curve;
/// the table then interpolates those readings by activation. `stretchShadows` is the identity
/// on every style but `referenceExposure`.
inline bool build_monochrome(const film::Spectra &stock, std::vector<float> &out,
                             const char *&error) {
    if (!stock.monochrome) {
        error = "film_screen: not a monochrome stock";
        return false;
    }
    constexpr int kSamples = 1024;
    std::array<std::vector<float>, 3> reading;
    const float mid = neutral_density(stock, 0.0f);
    for (int layer = 0; layer < 3; ++layer) {
        const curve::Curve &c = stock.curves[size_t(layer)];
        const float range = c.d_max() - c.d_min;
        reading[size_t(layer)].resize(size_t(kSamples) + 1);
        for (int i = 0; i <= kSamples; ++i) {
            const float density = c.d_min + range * float(i) / float(kSamples);
            reading[size_t(layer)][size_t(i)] =
                mid - neutral_density(stock, curve::log_exposure(c, density));
        }
    }
    auto interpolate = [](const std::vector<float> &table, float t) {
        const float x = std::min(std::max(t, 0.0f), 1.0f) * float(table.size() - 1);
        const size_t low = size_t(x);
        const size_t high = std::min(low + 1, table.size() - 1);
        return table[low] + (x - float(low)) * (table[high] - table[low]);
    };
    out.assign(size_t(33) * 33 * 33 * 4, 0.0f);
    for (size_t cell = 0; cell < out.size() / 4; ++cell) {
        const size_t z = cell / (33u * 33u), y = (cell / 33u) % 33u, x = cell % 33u;
        out[cell * 4 + 0] = interpolate(reading[0], float(x) / 32.0f);
        out[cell * 4 + 1] = interpolate(reading[1], float(y) / 32.0f);
        out[cell * 4 + 2] = interpolate(reading[2], float(z) / 32.0f);
        out[cell * 4 + 3] = 1.0f;
    }
    (void)error;
    return true;
}

// --- reversal: the levelled positive ----------------------------------------

/// `buildTables`'s `levelsPositive` branch: a slide carries no print stage, so the film table is
/// the slide's own spectral transmittance with its mid-grey balanced to 18%, handed over as the
/// negative of the log so the straight positive curve in `positive_curve()` can add it back.
inline bool build_reversal(const film::Spectra &stock, std::vector<float> &out,
                           const char *&error) {
    if (!stock.reversal) {
        error = "film_screen: not a reversal stock";
        return false;
    }
    const reversal::Basis basis(stock);
    float balance[3];
    reversal::balance_for(stock, basis, balance);
    float d_min[3], ranges[3];
    for (int c = 0; c < 3; ++c) {
        d_min[c] = stock.curves[size_t(c)].d_min;
        ranges[c] = stock.curves[size_t(c)].d_max() - stock.curves[size_t(c)].d_min;
    }
    out.assign(size_t(33) * 33 * 33 * 4, 0.0f);
    for (size_t cell = 0; cell < out.size() / 4; ++cell) {
        const size_t z = cell / (33u * 33u), y = (cell / 33u) % 33u, x = cell % 33u;
        const V3 density = {d_min[0] + float(x) / 32.0f * ranges[0],
                            d_min[1] + float(y) / 32.0f * ranges[1],
                            d_min[2] + float(z) / 32.0f * ranges[2]};
        V3 rgb = reversal::transmission_rgb_stock(basis(density), stock);
        for (int c = 0; c < 3; ++c) {
            rgb[size_t(c)] = std::max(rgb[size_t(c)] * balance[c], 0.0f);
            out[cell * 4 + size_t(c)] = -std::log10(std::max(rgb[size_t(c)], 1e-6f) / 0.18f);
        }
        out[cell * 4 + 3] = 1.0f;
    }
    (void)error;
    return true;
}

}  // namespace film_screen

#endif
