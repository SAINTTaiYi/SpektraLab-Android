#ifndef FOTOFILM_HOST_CINEON_LOG_HPP
#define FOTOFILM_HOST_CINEON_LOG_HPP

// `CineonLog` + `PrintPaper.cineonLine`: the output table for the `.cineon-log` medium.
//
// The medium has no print material. Its paper stage carries the relative printing density
// unchanged -- a unit-slope line across the whole table, its corners outside it -- and the table
// writes Kodak's Cineon code values: 0.002 density per code value, the scanner set up so the
// stock's laboratory aim density lands on 445 in every record.
//
//   read    = activation * (dMax - dMin) / gamma + toe     (cineonLine: gamma 1, toe -9, 18 D)
//   signal  = (445 - read / 0.002) / 1023
//
// Not clipped: a float delivery keeps what a 10-bit file would clip. Swift's
// `CineonLog.signal(densityAboveGrey:)` is the same expression, and `-read` is the density above
// the stock's grey.

#include <cmath>
#include <cstddef>
#include <vector>

namespace cineon_log {

constexpr float kDensityPerCodeValue = 0.002f;
constexpr float kGreyCodeValue = 445.0f;
constexpr float kMaximumCodeValue = 1023.0f;
/// `PrintPaper.cineonLine`: dMin 0, gamma 1, toe -9, shoulder 9 -> a 18 D range, unit slope.
constexpr float kLineRange = 18.0f;
constexpr float kLineToe = -9.0f;
constexpr int kDimension = 33;

inline bool build(std::vector<float> &out, const char *&error) {
    (void)error;
    out.assign(size_t(kDimension) * kDimension * kDimension * 4, 0.0f);
    for (size_t cell = 0; cell < out.size() / 4; ++cell) {
        const size_t z = cell / size_t(kDimension * kDimension);
        const size_t y = (cell / size_t(kDimension)) % size_t(kDimension);
        const size_t x = cell % size_t(kDimension);
        const float activation[3] = {float(x) / float(kDimension - 1),
                                     float(y) / float(kDimension - 1),
                                     float(z) / float(kDimension - 1)};
        for (int c = 0; c < 3; ++c) {
            const float read = activation[c] * kLineRange + kLineToe;
            out[cell * 4 + size_t(c)] =
                    (kGreyCodeValue + (-read) / kDensityPerCodeValue) / kMaximumCodeValue;
        }
        out[cell * 4 + 3] = 1.0f;
    }
    return true;
}

}  // namespace cineon_log

#endif
