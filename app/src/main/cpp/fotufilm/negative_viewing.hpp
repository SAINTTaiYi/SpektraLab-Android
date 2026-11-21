#ifndef FOTOFILM_HOST_NEGATIVE_VIEWING_HPP
#define FOTOFILM_HOST_NEGATIVE_VIEWING_HPP

// A negative viewed directly: no paper, no second emulsion. The developed dyes are the picture,
// integrated through the observer and shown on a light box.
//
// `negativeViewing(for:look:)` is what a reflection print (Instax) and the `.negative` medium
// both reach. It is not the reversal path: a slide's own transmittance is balanced to 18%
// mid-grey, while a negative is shown *as* a negative and divided by its base so that the base's
// brightest channel lands on `lightBoxBaseLevel`.
//
// The table is a display-RGB cube, 0..1, not a density read: the kernel hands it the negative's
// own activation and nothing is inverted upstream.

#include "film.hpp"
#include "film_reversal.hpp"

#include <algorithm>
#include <array>
#include <vector>

namespace negative_viewing {

using V3 = std::array<float, 3>;

/// SpectralRuntime.lightBoxBaseLevel: where a negative's clear base sits on a light box.
constexpr float kLightBoxBaseLevel = 0.9f;

/// `NegativeViewing`. Only `.lightBox` reaches an Instax sheet or the `.negative` medium.
enum class Look { LightBox, Scanner };

/// `SpectralRuntime.transmissionRGB(density:stock:)` — the film read through the observer, with
/// the stock's own **density offset**.
///
/// <p>The offset is not decoration: it is the difference between the dyes' own absorption at
/// `dMin` and the densities the densitometer actually measured, per wavelength. Without it the
/// clear base reads far less saturated than the engine's — measured on c200, base
/// (0.900, 0.594, 0.377) against the engine's (0.900, 0.426, 0.228) — and every frame developed
/// through the table comes out with its green and blue 25–40 per cent too high.
inline V3 transmission_with_offset(const V3 &density, const film::Spectra &stock,
                                  const std::vector<float> &offset) {
    spectral::Spd spectrum{};
    for (int i = 0; i < spectral_grid::kCount; ++i) {
        float d = density[0] * stock.dye[0][size_t(i)]
                + density[1] * stock.dye[1][size_t(i)]
                + density[2] * stock.dye[2][size_t(i)];
        if (!offset.empty()) d += offset[size_t(i)];
        spectrum[size_t(i)] = std::pow(10.0f, -d);
    }
    spectral::Spd d65{};
    for (int i = 0; i < spectral_grid::kCount; ++i) d65[size_t(i)] = spectral_grid::kD65[i];
    return receiver::to_linear_display_p3(spectrum, d65);
}

inline bool build(const film::Spectra &stock, std::vector<float> &out, const char *&error,
                  Look look = Look::LightBox) {
    if (!stock.curves[0].has_sampled && stock.curves[0].gamma == 0.0f) {
        error = "negative viewing: stock carries no characteristic curves";
        return false;
    }
    std::array<float, 3> d_min{}, range{};
    for (int c = 0; c < 3; ++c) {
        d_min[size_t(c)] = stock.curves[size_t(c)].d_min;
        range[size_t(c)] = stock.curves[size_t(c)].d_max() - d_min[size_t(c)];
    }
    // The base carries no developed silver, so both readings measure it without a bleach's
    // retained silver; only the image inverts.
    // The stock's own density offset, exactly as `transmissionRGB(density:stock:)` applies it.
    std::vector<float> offset;
    if (!film::density_offset(stock, stock.curves, 1.0f, offset)) offset.clear();
    const V3 base = transmission_with_offset(d_min, stock, offset);
    V3 divisor{};
    if (look == Look::Scanner) {
        for (int c = 0; c < 3; ++c) divisor[size_t(c)] = std::max(base[size_t(c)], 1e-6f);
    } else {
        // One gain for all three channels: the brightest channel lands on the base level and the
        // other two keep their ratio to it.
        const float brightest = std::max(std::max(base[0], base[1]),
                                         std::max(base[2], 1e-6f));
        const float gain = brightest / kLightBoxBaseLevel;
        divisor = {gain, gain, gain};
    }

    out.assign(size_t(33) * 33 * 33 * 4, 0.0f);
    for (size_t cell = 0; cell < out.size() / 4; ++cell) {
        const size_t z = cell / (33u * 33u), y = (cell / 33u) % 33u, x = cell % 33u;
        const V3 density = {d_min[0] + float(x) / 32.0f * range[0],
                            d_min[1] + float(y) / 32.0f * range[1],
                            d_min[2] + float(z) / 32.0f * range[2]};
        const V3 rgb = transmission_with_offset(density, stock, offset);
        for (int c = 0; c < 3; ++c) {
            out[cell * 4 + size_t(c)] = std::max(rgb[size_t(c)] / divisor[size_t(c)], 0.0f);
        }
        out[cell * 4 + 3] = 1.0f;
    }
    return true;
}

}  // namespace negative_viewing

#endif
