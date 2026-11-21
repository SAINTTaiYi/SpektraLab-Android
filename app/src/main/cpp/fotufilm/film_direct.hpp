#ifndef FOTOFILM_HOST_FILM_DIRECT_HPP
#define FOTOFILM_HOST_FILM_DIRECT_HPP

// `SpectralModel`'s `paper.viewsFilmDirectly(for:)` branch: an integral instant sheet (Instax)
// or a transparency on Digital Reference, whose film table is a display-RGB cube rather than a
// density read. No paper takes part; the base is divided out by `reversalBalance`.
//
// The basis is `neutralDensityBasis`: a reflection print takes the fitted cubic, everything else
// the Status A unmix.

#include "film.hpp"
#include "film_reversal.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace film_direct {

using V3 = std::array<float, 3>;

/// `NeutralDensityBasis`'s polynomial arm: per layer, c0 + c1·d + c2·d² + c3·d³ against the
/// published density, least squares weighted by the neutral transmission.
struct PolyBasis {
    float c[3][4] = {{0, 1, 0, 0}, {0, 1, 0, 0}, {0, 1, 0, 0}};

    explicit PolyBasis(const film::Spectra &stock) {
        constexpr int kTerms = 4;
        double normal[3][16] = {};
        double moment[3][4] = {};
        // FilmStock.developedDensity: a reversal develops by clearing.
        const auto developed = [&](int layer, float log_exposure) {
            const curve::Curve &cv = stock.curves[size_t(layer)];
            const float formed = cv.density(log_exposure);
            return stock.reversal ? cv.d_min + cv.d_max() - formed : formed;
        };
        // stride(from: -1.5, through: 0.75, by: 0.025) -- 91 samples.
        for (int i = 0; i <= 90; ++i) {
            const float log_exposure = -1.5f + 0.025f * float(i);
            float density[3];
            for (int c = 0; c < 3; ++c) density[c] = developed(c, log_exposure);
            const double neutral = (double(density[0]) + double(density[1]) + double(density[2])) / 3.0;
            const double weight = std::pow(10.0, -neutral);
            for (int layer = 0; layer < 3; ++layer) {
                const double d = double(density[layer]);
                const double terms[4] = {1.0, d, d * d, d * d * d};
                for (int row = 0; row < kTerms; ++row) {
                    for (int column = 0; column < kTerms; ++column)
                        normal[layer][row * 4 + column] += weight * terms[row] * terms[column];
                    moment[layer][row] += weight * terms[row] * neutral;
                }
            }
        }
        for (int layer = 0; layer < 3; ++layer) {
            double solved[4];
            if (solve4(normal[layer], moment[layer], solved))
                for (int k = 0; k < 4; ++k) c[layer][k] = float(solved[k]);
        }
    }

    V3 operator()(const V3 &density) const {
        V3 out{0, 0, 0};
        for (int layer = 0; layer < 3; ++layer) {
            const float d = density[size_t(layer)];
            const float v = c[layer][0] + c[layer][1] * d + c[layer][2] * d * d
                          + c[layer][3] * d * d * d;
            out[size_t(layer)] = std::max(v, 0.0f);
        }
        return out;
    }

private:
    /// Gaussian elimination with partial pivoting on the 4x4 normal equations.
    static bool solve4(const double *matrix, const double *rhs, double *out) {
        double a[4][4], b[4];
        for (int r = 0; r < 4; ++r) {
            b[r] = rhs[r];
            for (int k = 0; k < 4; ++k) a[r][k] = matrix[r * 4 + k];
        }
        for (int column = 0; column < 4; ++column) {
            int pivot = column;
            for (int r = column + 1; r < 4; ++r)
                if (std::fabs(a[r][column]) > std::fabs(a[pivot][column])) pivot = r;
            if (std::fabs(a[pivot][column]) < 1e-18) return false;
            if (pivot != column) {
                for (int k = 0; k < 4; ++k) std::swap(a[pivot][k], a[column][k]);
                std::swap(b[pivot], b[column]);
            }
            for (int r = column + 1; r < 4; ++r) {
                const double factor = a[r][column] / a[column][column];
                for (int k = column; k < 4; ++k) a[r][k] -= factor * a[column][k];
                b[r] -= factor * b[column];
            }
        }
        for (int r = 3; r >= 0; --r) {
            double value = b[r];
            for (int k = r + 1; k < 4; ++k) value -= a[r][k] * out[k];
            out[r] = value / a[r][r];
        }
        return true;
    }
};

/// `reversalBalance` with a polynomial basis: one gain per record so the mid-grey reads 18%.
inline void balance_with(const film::Spectra &stock, const PolyBasis &basis, float out[3]) {
    const auto developed = [&](int layer, float log_exposure) {
        const curve::Curve &cv = stock.curves[size_t(layer)];
        const float formed = cv.density(log_exposure);
        return stock.reversal ? cv.d_min + cv.d_max() - formed : formed;
    };
    const V3 mid = {developed(0, 0.0f), developed(1, 0.0f), developed(2, 0.0f)};
    const V3 raw = reversal::transmission_rgb_stock(basis(mid), stock);
    for (int c = 0; c < 3; ++c) out[c] = 0.18f / std::max(raw[size_t(c)], 1e-6f);
    if (!stock.reflection_print) return;
    // An integral sheet divides its base out by *luminance*: the clear base's P3 Y lands on
    // one, so the three records keep their ratio to it rather than each landing on 18%.
    constexpr float kP3Luma0 = 0.2289746f, kP3Luma1 = 0.6917385f, kP3Luma2 = 0.0792869f;
    const V3 d_min = {stock.curves[0].d_min, stock.curves[1].d_min, stock.curves[2].d_min};
    const V3 white = reversal::transmission_rgb_stock(basis(d_min), stock);
    const float level = kP3Luma0 * white[0] * out[0] + kP3Luma1 * white[1] * out[1]
                      + kP3Luma2 * white[2] * out[2];
    for (int c = 0; c < 3; ++c) out[c] /= std::max(level, 1e-6f);
}

/// `paper.viewsFilmDirectly`'s film table: display RGB, 0..1, base divided out.
inline bool build(const film::Spectra &stock, std::vector<float> &out, const char *&error) {
    if (!stock.curves[0].has_sampled && stock.curves[0].gamma == 0.0f) {
        error = "film direct: stock carries no characteristic curves";
        return false;
    }
    const PolyBasis basis(stock);
    float balance[3];
    balance_with(stock, basis, balance);
    std::array<float, 3> d_min{}, range{};
    for (int c = 0; c < 3; ++c) {
        d_min[size_t(c)] = stock.curves[size_t(c)].d_min;
        range[size_t(c)] = stock.curves[size_t(c)].d_max() - d_min[size_t(c)];
    }
    out.assign(size_t(33) * 33 * 33 * 4, 0.0f);
    for (size_t cell = 0; cell < out.size() / 4; ++cell) {
        const size_t z = cell / (33u * 33u), y = (cell / 33u) % 33u, x = cell % 33u;
        const V3 density = {d_min[0] + float(x) / 32.0f * range[0],
                            d_min[1] + float(y) / 32.0f * range[1],
                            d_min[2] + float(z) / 32.0f * range[2]};
        const V3 rgb = reversal::transmission_rgb_stock(basis(density), stock);
        for (int c = 0; c < 3; ++c)
            out[cell * 4 + size_t(c)] = std::max(rgb[size_t(c)] * balance[c], 0.0f);
        out[cell * 4 + 3] = 1.0f;
    }
    (void)error;
    return true;
}

}  // namespace film_direct

#endif
