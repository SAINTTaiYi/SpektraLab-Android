#ifndef FOTUFILM_HOST_LOCUS_HPP
#define FOTUFILM_HOST_LOCUS_HPP

// SpectralModel.SpectralLocus: the observer's own boundary in the exposure domain.
//
// Every vertex is a band's unit-luminance colour less the neutral axis, so the polygon is
// built from the CIE tables rather than carried as a table of its own. A colour beyond the
// cube's margin is a mixture of the model's continuation and monochromatic light at the
// hue's dominant wavelength; where that ray leaves this polygon is the mixture's ceiling.

#include "spectral_data.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <optional>
#include <vector>

namespace spectral {

struct SpectralLocus {
    /// Grid indices of the bands on the polygon, in wavelength order. The observer's
    /// luminous efficiency floor trims the tips, where a stated luminance would ask for a
    /// radiometric power no table cell can carry.
    std::vector<int> bands;
    /// Each vertex: the band's unit-luminance Rec.2020 colour, less the neutral axis.
    std::vector<std::array<double, 3>> chroma;

    struct Boundary {
        double reach;
        int lower_band;
        int upper_band;
        double upper_share;
    };

    SpectralLocus() {
        for (int i = 0; i < spectral_grid::kCount; ++i) {
            if (spectral_grid::kYBar[i] >= 0.01f) bands.push_back(i);
        }
        for (int band : bands) {
            const float xyz[3] = {spectral_grid::kXBar[band], spectral_grid::kYBar[band],
                                  spectral_grid::kZBar[band]};
            // SpectralGrid.linearRec2020(fromXYZ:) -- through Display P3, in two rounded steps.
            const float p3[3] = {
                2.4934969f * xyz[0] - 0.9313836f * xyz[1] - 0.4027108f * xyz[2],
                -0.8294890f * xyz[0] + 1.7626641f * xyz[1] + 0.0236247f * xyz[2],
                0.0358458f * xyz[0] - 0.0761724f * xyz[1] + 0.9568845f * xyz[2],
            };
            const float rec[3] = {
                0.753833034f * p3[0] + 0.198597369f * p3[1] + 0.047569597f * p3[2],
                0.045743849f * p3[0] + 0.941777220f * p3[1] + 0.012478931f * p3[2],
                -0.001210340f * p3[0] + 0.017601717f * p3[1] + 0.983608623f * p3[2],
            };
            const double y = double(spectral_grid::kYBar[band]);
            chroma.push_back({double(rec[0]) / y - 1.0, double(rec[1]) / y - 1.0,
                              double(rec[2]) / y - 1.0});
        }
    }

    static const SpectralLocus &shared() {
        static const SpectralLocus locus;
        return locus;
    }

    /// SpectralLocus.boundary: where the ray from the neutral axis along `direction` leaves
    /// the polygon, with the two bands bracketing the crossing and the upper one's share.
    std::optional<Boundary> boundary(const std::array<double, 3> &direction) const {
        std::optional<Boundary> best;
        const size_t count = chroma.size();
        for (size_t lower = 0; lower < count; ++lower) {
            const size_t upper = (lower + 1) % count;
            const auto &from = chroma[lower];
            // alpha * direction - beta * edge = from, solved in x and y: the zero-luminance
            // plane is not parallel to z, so those two coordinates fix it.
            const double edge[2] = {chroma[upper][0] - from[0], chroma[upper][1] - from[1]};
            const double determinant = -direction[0] * edge[1] + edge[0] * direction[1];
            if (!(std::fabs(determinant) > 1e-18)) continue;
            const double alpha = (-from[0] * edge[1] + edge[0] * from[1]) / determinant;
            const double beta = (direction[0] * from[1] - direction[1] * from[0]) / determinant;
            if (!(alpha > 0.0) || !(beta >= -1e-9) || !(beta <= 1.0 + 1e-9)) continue;
            if (!best || alpha < best->reach) {
                best = Boundary{alpha, bands[lower], bands[upper],
                                std::min(std::max(beta, 0.0), 1.0)};
            }
        }
        return best;
    }
};

}  // namespace spectral

#endif
