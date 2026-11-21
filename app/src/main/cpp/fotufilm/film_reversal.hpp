#ifndef FOTOFILM_HOST_REVERSAL_HPP
#define FOTOFILM_HOST_REVERSAL_HPP

// A transparency is not printed: it is the finished image, and the eye reads it by transmitted
// light. `buildTables` has a branch for that (`viewsFilmDirectly`: a reversal stock on any
// medium that is not positive paper) which produces the film table alone and no paper table.
//
// What the branch needs beyond the negative path:
//   * `neutralDensityBasis` -- a slide carries no base offset, so its record densities go
//     through a Status A inversion into the image-dye amounts rather than an identity map.
//   * `reversalBalance` -- scales the three records so the stock's mid-grey sits at 18%.

#include "film.hpp"
#include "receiver.hpp"
#include "spectral.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace reversal {

using V3 = std::array<float, 3>;

/// StatusADyeUnmix: the image dye amounts behind a Status A reading, by linearized
/// nonnegative least squares. The three dyes overlap, so a reading is not three densities;
/// this solves for what the emulsion actually holds. Clear film is one of the eight active
/// sets, so an unexposed area reaches exactly zero.
struct StatusDyeUnmix {
    std::vector<V3> dye;
    std::vector<V3> band;
    static constexpr float kLog2Of10 = 3.321928f;

    explicit StatusDyeUnmix(const std::array<std::vector<float>, 3> &dyes) {
        const float *bands[3] = {receiver::kStatusA0, receiver::kStatusA1, receiver::kStatusA2};
        float integral[3] = {0.0f, 0.0f, 0.0f};
        for (int c = 0; c < 3; ++c) {
            for (int i = 0; i < spectral_grid::kCount; ++i) integral[c] += bands[c][i];
        }
        dye.resize(spectral_grid::kCount);
        band.resize(spectral_grid::kCount);
        for (int i = 0; i < spectral_grid::kCount; ++i) {
            dye[size_t(i)] = {dyes[0][size_t(i)], dyes[1][size_t(i)], dyes[2][size_t(i)]};
            band[size_t(i)] = {bands[0][i] / std::max(integral[0], 1e-12f),
                               bands[1][i] / std::max(integral[1], 1e-12f),
                               bands[2][i] / std::max(integral[2], 1e-12f)};
        }
    }

    /// The reading and its Jacobian: the e⁻¹ weighting each dye's own density contributes.
    void measurement(const V3 &amounts, V3 &density, std::array<V3, 3> &jacobian) const {
        V3 sum{0.0f, 0.0f, 0.0f};
        std::array<V3, 3> moments{};
        for (size_t i = 0; i < dye.size(); ++i) {
            const float d = amounts[0] * dye[i][0] + amounts[1] * dye[i][1] + amounts[2] * dye[i][2];
            const float transmission = std::exp2(-d * kLog2Of10);
            const V3 weight = {band[i][0] * transmission, band[i][1] * transmission,
                               band[i][2] * transmission};
            for (int c = 0; c < 3; ++c) sum[size_t(c)] += weight[size_t(c)];
            for (int c = 0; c < 3; ++c) {
                jacobian[size_t(c)][0] += dye[i][0] * weight[size_t(c)];
                jacobian[size_t(c)][1] += dye[i][1] * weight[size_t(c)];
                jacobian[size_t(c)][2] += dye[i][2] * weight[size_t(c)];
            }
        }
        for (int c = 0; c < 3; ++c) {
            const float energy = std::max(sum[size_t(c)], 1e-20f);
            density[size_t(c)] = -std::log10(energy);
            for (int k = 0; k < 3; ++k) jacobian[size_t(c)][size_t(k)] /= energy;
        }
    }

    static float dot3(const V3 &a, const V3 &b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /// Gaussian elimination on the active subset, in place.
public:
    static bool solve_active(std::array<V3, 3> a, V3 b, const int *active, int n, V3 &out) {
        for (int index = 0; index < n; ++index) {
            const int column = active[index];
            if (!(a[size_t(column)][size_t(column)] > 1e-10f)) return false;
            for (int row_index = index + 1; row_index < n; ++row_index) {
                const int row = active[row_index];
                const float factor = a[size_t(row)][size_t(column)] / a[size_t(column)][size_t(column)];
                for (int k_index = index; k_index < n; ++k_index) {
                    const int k = active[k_index];
                    a[size_t(row)][size_t(k)] -= factor * a[size_t(column)][size_t(k)];
                }
                b[size_t(row)] -= factor * b[size_t(column)];
            }
        }
        out = {0.0f, 0.0f, 0.0f};
        for (int index = n - 1; index >= 0; --index) {
            const int row = active[index];
            float value = b[size_t(row)];
            for (int k_index = index + 1; k_index < n; ++k_index) {
                const int k = active[k_index];
                value -= a[size_t(row)][size_t(k)] * out[size_t(k)];
            }
            out[size_t(row)] = value / a[size_t(row)][size_t(row)];
        }
        return true;
    }

public:
    V3 amounts_for(const V3 &target) const {
        V3 amounts{0.0f, 0.0f, 0.0f};
        constexpr float regularization = 1e-7f;
        for (int step = 0; step < 24; ++step) {
            V3 density;
            std::array<V3, 3> jacobian{};
            measurement(amounts, density, jacobian);
            const V3 residual = {density[0] - target[0], density[1] - target[1],
                                 density[2] - target[2]};
            if (std::max(std::fabs(residual[0]),
                         std::max(std::fabs(residual[1]), std::fabs(residual[2]))) < 1e-6f) break;
            V3 b{};
            for (int c = 0; c < 3; ++c) b[size_t(c)] = dot3(jacobian[size_t(c)], amounts)
                                                      - residual[size_t(c)];
            std::array<V3, 3> normal{};
            V3 rhs{0.0f, 0.0f, 0.0f};
            for (int j = 0; j < 3; ++j) {
                rhs[size_t(j)] = regularization * amounts[size_t(j)];
                for (int k = 0; k < 3; ++k) {
                    normal[size_t(j)][size_t(k)] = (j == k) ? regularization : 0.0f;
                    for (int c = 0; c < 3; ++c) {
                        normal[size_t(j)][size_t(k)] += jacobian[size_t(c)][size_t(j)]
                                                      * jacobian[size_t(c)][size_t(k)];
                    }
                }
                for (int c = 0; c < 3; ++c) rhs[size_t(j)] += jacobian[size_t(c)][size_t(j)] * b[size_t(c)];
            }
            V3 best{0.0f, 0.0f, 0.0f};
            float best_error = dot3(b, b) + regularization * dot3(amounts, amounts);
            for (int mask = 1; mask < 8; ++mask) {
                int active[3] = {0, 0, 0};
                int n = 0;
                for (int k = 0; k < 3; ++k) {
                    if (mask & (1 << k)) active[n++] = k;
                }
                V3 candidate;
                if (!solve_active(normal, rhs, active, n, candidate)) continue;
                if (!(candidate[0] >= 0 && candidate[1] >= 0 && candidate[2] >= 0)) continue;
                V3 error{};
                for (int c = 0; c < 3; ++c) error[size_t(c)] = dot3(jacobian[size_t(c)], candidate)
                                                            - b[size_t(c)];
                const V3 distance = {candidate[0] - amounts[0], candidate[1] - amounts[1],
                                     candidate[2] - amounts[2]};
                const float score = dot3(error, error) + regularization * dot3(distance, distance);
                if (score < best_error) {
                    best_error = score;
                    best = candidate;
                }
            }
            const V3 move = {best[0] - amounts[0], best[1] - amounts[1], best[2] - amounts[2]};
            if (std::max(std::fabs(move[0]), std::max(std::fabs(move[1]), std::fabs(move[2]))) < 2e-7f) break;
            float scale = 1.0f;
            const float error = dot3(residual, residual);
            bool accepted = false;
            for (int attempt = 0; attempt < 12; ++attempt) {
                const V3 candidate = {amounts[0] + scale * move[0], amounts[1] + scale * move[1],
                                      amounts[2] + scale * move[2]};
                V3 next_density;
                std::array<V3, 3> unused{};
                measurement(candidate, next_density, unused);
                const V3 next = {next_density[0] - target[0], next_density[1] - target[1],
                                 next_density[2] - target[2]};
                if (dot3(next, next) < error) {
                    amounts = candidate;
                    accepted = true;
                    break;
                }
                scale *= 0.5f;
            }
            if (!accepted) break;
        }
        return amounts;
    }
};

/// NeutralDensityBasis, reversal only: densities go through the Status A inversion into the
/// image-dye amounts.
struct Basis {
    bool identity = false;
    StatusDyeUnmix unmix;

    explicit Basis(const film::Spectra &stock)
        : identity(!stock.reversal || stock.reflection_print),
          unmix(stock.dye) {}

    V3 operator()(const V3 &density) const {
        if (identity) return density;
        return unmix.amounts_for(density);
    }
};

/// SpectralModel.transmissionRGB(density:stock:) with no stated lamp: the print side's basis is
/// Display P3 under the renderer's own D65, not a viewing booth's D50.
inline V3 transmission_rgb_stock(const V3 &density, const film::Spectra &stock) {
    spectral::Spd spectrum{};
    for (int i = 0; i < spectral_grid::kCount; ++i) {
        const float d = density[0] * stock.dye[0][size_t(i)]
                      + density[1] * stock.dye[1][size_t(i)]
                      + density[2] * stock.dye[2][size_t(i)];
        spectrum[size_t(i)] = std::pow(10.0f, -d);
    }
    // kD65 is a bare table; the transform wants the grid's own array type.
    spectral::Spd d65{};
    for (int i = 0; i < spectral_grid::kCount; ++i) d65[size_t(i)] = spectral_grid::kD65[i];
    return receiver::to_linear_display_p3(spectrum, d65);
}

/// SpectralModel.reversalBalance: one gain per record so the stock's mid-grey reads 18%.
///
/// The balancing sample is not taken on the analytic midpoint: the wavelength integral is
/// curved between LUT nodes, so a host that balances the midpoint and then interpolates reads a
/// small cast. The engine samples the containing cell the same way the table will be read.
inline void balance_for(const film::Spectra &stock, const Basis &basis, float out[3]) {
    // FilmStock.developedDensity: a reversal develops by clearing, so the density it holds at a
    // given log exposure is the distance from the curve's dense end, not the curve itself.
    const auto developed = [&](int layer, float log_exposure) {
        const curve::Curve &c = stock.curves[size_t(layer)];
        const float formed = c.density(log_exposure);
        return stock.reversal ? c.d_min + c.d_max() - formed : formed;
    };
    const V3 mid = {developed(0, 0.0f), developed(1, 0.0f), developed(2, 0.0f)};
    V3 raw = transmission_rgb_stock(basis(mid), stock);
    if (!stock.reflection_print) {
        const std::array<float, 3> ranges = {
            stock.curves[0].d_max() - stock.curves[0].d_min,
            stock.curves[1].d_max() - stock.curves[1].d_min,
            stock.curves[2].d_max() - stock.curves[2].d_min};
        float q[3], origin[3];
        for (int c = 0; c < 3; ++c) {
            const float normalized = (mid[size_t(c)] - stock.curves[size_t(c)].d_min)
                                   / std::max(ranges[size_t(c)], 1e-6f);
            const float clamped = std::min(std::max(normalized, 0.0f), 1.0f);
            q[c] = clamped * 32.0f;
            origin[c] = float(std::min(int(q[c]), 31));
        }
        float values[8 * 4];
        int slot = 0;
        for (int z = 0; z < 2; ++z) {
            for (int y = 0; y < 2; ++y) {
                for (int x = 0; x < 2; ++x) {
                    const int offsets[3] = {x, y, z};
                    V3 density{};
                    for (int c = 0; c < 3; ++c) {
                        density[size_t(c)] = stock.curves[size_t(c)].d_min
                            + float(int(origin[c]) + offsets[c]) / 32.0f * ranges[size_t(c)];
                    }
                    const V3 rgb = transmission_rgb_stock(basis(density), stock);
                    values[slot++] = std::max(rgb[0], 0.0f);
                    values[slot++] = std::max(rgb[1], 0.0f);
                    values[slot++] = std::max(rgb[2], 0.0f);
                    values[slot++] = 1.0f;
                }
            }
        }
        // SpectralLUT.sample with dimension 2: tetrahedral interpolation, which is what the
        // renderer's 33^3 lookup does. A trilinear read would answer a different number and the
        // balance would then over-correct against the table it is meant to match.
        const float fx = q[0] - origin[0], fy = q[1] - origin[1], fz = q[2] - origin[2];
        auto load = [&](int x, int y, int z, int c) {
            return values[((z * 2 + y) * 2 + x) * 4 + c];
        };
        for (int c = 0; c < 3; ++c) {
            const float c000 = load(0, 0, 0, c), c111 = load(1, 1, 1, c);
            float v;
            if (fx >= fy) {
                if (fy >= fz) {
                    const float c100 = load(1, 0, 0, c), c110 = load(1, 1, 0, c);
                    v = c000 + fx * (c100 - c000) + fy * (c110 - c100) + fz * (c111 - c110);
                } else if (fx >= fz) {
                    const float c100 = load(1, 0, 0, c), c101 = load(1, 0, 1, c);
                    v = c000 + fx * (c100 - c000) + fz * (c101 - c100) + fy * (c111 - c101);
                } else {
                    const float c001 = load(0, 0, 1, c), c101 = load(1, 0, 1, c);
                    v = c000 + fz * (c001 - c000) + fx * (c101 - c001) + fy * (c111 - c101);
                }
            } else if (fx >= fz) {
                const float c010 = load(0, 1, 0, c), c110 = load(1, 1, 0, c);
                v = c000 + fy * (c010 - c000) + fx * (c110 - c010) + fz * (c111 - c110);
            } else if (fy >= fz) {
                const float c010 = load(0, 1, 0, c), c011 = load(0, 1, 1, c);
                v = c000 + fy * (c010 - c000) + fz * (c011 - c010) + fx * (c111 - c011);
            } else {
                const float c001 = load(0, 0, 1, c), c011 = load(0, 1, 1, c);
                v = c000 + fz * (c001 - c000) + fy * (c011 - c001) + fx * (c111 - c011);
            }
            raw[size_t(c)] = v;
        }
    }
    for (int c = 0; c < 3; ++c) out[c] = 0.18f / std::max(raw[size_t(c)], 1e-6f);
}


// --- a transparency's levels -----------------------------------------------------
//
// `PositiveScreenRead` + `positiveShift`: a slide has no graded curve to stretch, only a gain.
// `.autoLevels` brings the frame's metered highlight to display white, never darker than the
// clear base and never more than `positiveLiftLimit` brighter than it.

inline float positive_shift(const film::Spectra &stock, bool has_highlight, float highlight) {
    constexpr float kP3Luma0 = 0.2289746f, kP3Luma1 = 0.6917385f, kP3Luma2 = 0.0792869f;
    constexpr float kPositiveLiftLimit = 0.9f;
    Basis basis(stock);
    float balance[3];
    balance_for(stock, basis, balance);
    const V3 dmin = {stock.curves[0].d_min, stock.curves[1].d_min, stock.curves[2].d_min};
    const V3 white = transmission_rgb_stock(basis(dmin), stock);
    const float wx = std::max(white[0] * balance[0], 0.0f);
    const float wy = std::max(white[1] * balance[1], 0.0f);
    const float wz = std::max(white[2] * balance[2], 0.0f);
    const float base = -std::log10(std::max(std::max(wx, wy), std::max(wz, 1e-6f)));
    const float base_white = -base;
    if (!has_highlight || !std::isfinite(highlight)) return base_white;
    const float stops = std::min(std::max(highlight, 0.5f), 12.0f);
    V3 density;
    for (int c = 0; c < 3; ++c) {
        const curve::Curve &cv = stock.curves[size_t(c)];
        const float formed = cv.density(stops * 0.30102999566398f);
        density[c] = cv.d_min + cv.d_max() - formed;
    }
    const V3 rgb = transmission_rgb_stock(basis(density), stock);
    const float r = std::max(rgb[0] * balance[0], 0.0f);
    const float g = std::max(rgb[1] * balance[1], 0.0f);
    const float b = std::max(rgb[2] * balance[2], 0.0f);
    const float y = kP3Luma0 * r + kP3Luma1 * g + kP3Luma2 * b;
    const float luminance = -std::log10(std::max(y, 1e-6f));
    return std::min(std::max(-luminance, base_white - kPositiveLiftLimit), base_white);
}

}  // namespace reversal

#endif
