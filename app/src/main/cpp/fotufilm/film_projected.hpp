#ifndef FOTOFILM_HOST_FILM_PROJECTED_HPP
#define FOTOFILM_HOST_FILM_PROJECTED_HPP

// Release print film: a cinema print stock is not exposed by a white enlarger lamp, it is timed
// through an additive printer whose three beams carry a published Status A aim.
//
// `printingIllumination` grows a second branch for it. The lamp is no longer a fixed black body:
// three broad passbands on a 3200 K source are solved — coordinate descent on non-negative beam
// exposures — so that the mid-grey negative comes out at the sheet's LAD aims. `referenceEnergy`
// is then those aims over the solved peak, not the mid-grey's own energy, because the aim *is*
// the timing.
//
// The rest is the same reflection path: relative log energy against that reference, per record.

#include "film.hpp"
#include "paper.hpp"
#include "spectral.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace projected {

using V3 = std::array<float, 3>;

/// SpectralGrid.releasePrinterBeams: broad, tapered additive passbands on a 3200 K source.
/// H-1-2383/2393 specify 2043 heat-absorbing glass with UV filtration; Fuji 3513DI specifies
/// SC-41 plus 2043 on a Bell & Howell Model C. The published transmission curves are not
/// available, so these are an approximation and not measured 2043 / Series 1700 / SC-41.
inline std::array<spectral::Spd, 3> printer_beams() {
    const spectral::Spd source = spectral::blackbody(3200.0f);
    constexpr float bounds[3][4] = {
        {590.0f, 610.0f, 700.0f, 730.0f},
        {490.0f, 510.0f, 580.0f, 610.0f},
        {400.0f, 410.0f, 480.0f, 500.0f},
    };
    std::array<spectral::Spd, 3> beams{};
    for (int b = 0; b < 3; ++b) {
        const float low = bounds[b][0], rise = bounds[b][1];
        const float fall = bounds[b][2], high = bounds[b][3];
        for (int i = 0; i < spectral_grid::kCount; ++i) {
            const float lambda = 380.0f + 5.0f * float(i);
            const float t = std::min((lambda - low) / (rise - low),
                                     (high - lambda) / (high - fall));
            beams[size_t(b)][size_t(i)] = source[size_t(i)] * std::min(std::max(t, 0.0f), 1.0f);
        }
    }
    return beams;
}

/// PrintPaper.printingAim: the sensitivity-weighted energy the setup needs, relative to each
/// record's published sensitivity reference. Only a common multiplier is arbitrary.
inline V3 printing_aim(const paper::Stock &sheet) {
    V3 aim{};
    for (int c = 0; c < 3; ++c) {
        const curve::Curve &curve = sheet.curves[size_t(c)];
        const float midpoint = curve::log_exposure(curve, sheet.lad_status_a[c]);
        // A sheet that states its sensitivity 1.0 above minimum density is read at dMin + 1;
        // every other publication labels a gross density of 1.0.
        const float target = sheet.sensitivity_above_minimum ? curve.d_min + 1.0f : 1.0f;
        const float reference = curve::log_exposure(curve, target);
        aim[size_t(c)] = std::pow(10.0f, midpoint - reference);
    }
    return aim;
}

/// PrintPaper.releasePrinterLamp: non-negative beam exposures for a stated sensitivity-weighted
/// energy target. Coordinate descent rather than a matrix inverse, so an unreachable aim closes a
/// light instead of asking for negative photons, and overlapping bands stay well posed.
inline spectral::Spd release_printer_lamp(
        const V3 &density, const film::Spectra &stock,
        const std::array<std::vector<float>, 3> &sensitivity, const V3 &target,
        const std::vector<float> *offset, const V3 &beam_gain = V3{1.0f, 1.0f, 1.0f}) {
    const auto beams = printer_beams();
    std::array<V3, 3> response{};
    V3 norms{};
    for (int b = 0; b < 3; ++b) {
        film::paper_exposure(density, stock, beams[size_t(b)], sensitivity, 0.0f, offset,
                             response[size_t(b)]);
        norms[size_t(b)] = std::max(std::max(response[size_t(b)][0],
                                             std::max(response[size_t(b)][1],
                                                      response[size_t(b)][2])), 1e-12f);
        for (int c = 0; c < 3; ++c) response[size_t(b)][size_t(c)] /= norms[size_t(b)];
    }
    V3 lights = {1.0f, 1.0f, 1.0f};
    V3 residual{};
    for (int c = 0; c < 3; ++c) {
        residual[size_t(c)] = response[0][size_t(c)] + response[1][size_t(c)]
                            + response[2][size_t(c)] - target[size_t(c)];
    }
    for (int iter = 0; iter < 64; ++iter) {
        for (int channel = 0; channel < 3; ++channel) {
            float dot_cr = 0.0f, dot_cc = 0.0f;
            for (int c = 0; c < 3; ++c) {
                dot_cr += response[size_t(channel)][size_t(c)] * residual[size_t(c)];
                dot_cc += response[size_t(channel)][size_t(c)] * response[size_t(channel)][size_t(c)];
            }
            const float next = std::max(0.0f, lights[size_t(channel)]
                                        - dot_cr / std::max(dot_cc, 1e-12f));
            const float step = next - lights[size_t(channel)];
            for (int c = 0; c < 3; ++c) {
                residual[size_t(c)] += response[size_t(channel)][size_t(c)] * step;
            }
            lights[size_t(channel)] = next;
        }
    }
    // `PrinterLights.beamGain` scales each solved beam after the solve, as a printer's three
    // lights do: one printer point is 0.025 log exposure, and 25 points is the stock's aim.
    const V3 weights = {lights[0] / norms[0] * beam_gain[0],
                        lights[1] / norms[1] * beam_gain[1],
                        lights[2] / norms[2] * beam_gain[2]};
    spectral::Spd lamp{};
    for (int i = 0; i < spectral_grid::kCount; ++i) {
        lamp[size_t(i)] = beams[0][size_t(i)] * weights[0]
                        + beams[1][size_t(i)] * weights[1]
                        + beams[2][size_t(i)] * weights[2];
    }
    return lamp;
}

/// The film table for a release print: the same relative-log-energy read a reflection paper gets,
/// against the aims rather than against mid-grey's own energy.
inline bool build(const film::Spectra &stock, const paper::Stock &sheet,
                  std::vector<float> &out, const char *&error,
                  const V3 &beam_gain = V3{1.0f, 1.0f, 1.0f}) {
    if (!sheet.is_projected()) {
        error = "projected: not a release print stock";
        return false;
    }
    std::vector<float> offset;
    const bool has_offset = film::density_offset(stock, stock.curves, 1.0f, offset);
    const std::vector<float> *off = has_offset ? &offset : nullptr;

    const V3 mid = {stock.curves[0].density(0.0f), stock.curves[1].density(0.0f),
                    stock.curves[2].density(0.0f)};
    const V3 aim = printing_aim(sheet);
    const spectral::Spd raw = release_printer_lamp(mid, stock, sheet.spectra.sensitivity, aim, off,
                                                   beam_gain);
    float peak = 0.0f;
    for (float v : raw) peak = std::max(peak, v);
    peak = std::max(peak, 1e-12f);
    spectral::Spd lamp{};
    for (int i = 0; i < spectral_grid::kCount; ++i) lamp[size_t(i)] = raw[size_t(i)] / peak;
    // `printingIllumination` hands back the aims over the solved peak as the reference energy.
    const V3 mid_energy = {aim[0] / peak, aim[1] / peak, aim[2] / peak};

    V3 d_min{}, range{};
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
        V3 energy{};
        film::paper_exposure(density, stock, lamp, sheet.spectra.sensitivity, 0.0f, off, energy);
        for (int c = 0; c < 3; ++c) {
            out[cell * 4 + size_t(c)] = std::log10(std::max(energy[size_t(c)], 1e-12f)
                                                   / std::max(mid_energy[size_t(c)], 1e-12f));
        }
        out[cell * 4 + 3] = 1.0f;
    }
    return true;
}

}  // namespace projected

#endif
