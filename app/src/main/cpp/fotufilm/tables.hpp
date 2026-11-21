#include <cstdio>
#ifndef FOTOFILM_HOST_TABLES_HPP
#define FOTOFILM_HOST_TABLES_HPP

// The three LUTs a render needs, built here instead of baked by Swift.
//
//   exposure  -- scene colour to the three record exposures (depends on the stock only)
//   film      -- developed density to the relative log energy each paper record receives
//   paper     -- Status A density to the display RGB the finished sheet reflects
//
// `build_film` and `build_paper` are the negative film on a reflection paper, which is the
// path every colour negative takes. The other routes (a slide's levels, the direct view, a
// scan) are separate tables and land later.

#include "exposure.hpp"
#include "film.hpp"
#include "paper.hpp"
#include "receiver.hpp"
#include "film_reversal.hpp"
#include "film_projected.hpp"
#include "xenon.hpp"
#include "negative_viewing.hpp"
#include "film_direct.hpp"
#include "screen.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace tables {

constexpr size_t kCells = size_t(exposure::kLutDimension) * exposure::kLutDimension
                        * exposure::kLutDimension;

inline std::array<float, 3> d_min_of(const film::Spectra &stock) {
    return {stock.curves[0].d_min, stock.curves[1].d_min, stock.curves[2].d_min};
}

inline void cell_point(size_t cell, float p[3]) {
    const int d = exposure::kLutDimension;
    p[0] = float(cell % size_t(d)) / float(d - 1);
    p[1] = float((cell / size_t(d)) % size_t(d)) / float(d - 1);
    p[2] = float(cell / (size_t(d) * size_t(d))) / float(d - 1);
}

/// SpectralModel: a colour negative's printing table. `buildDensityLUT` walks the stock's own
/// curve range, so the cube's corners are the film's dMin and dMax in each record.
inline bool build_film_impl(const film::Spectra &stock, const paper::Stock &paper_stock,
                       std::vector<float> &out, const char *&error,
                       const spectral::Spd *lamp_override = nullptr, float bleach = 0.0f,
                       float callier = 1.0f, const spectral::Spd *printer_lamp = nullptr,
                       const std::array<float, 3> *beam_gain = nullptr) {
    // A release print is timed through an additive printer against published Status A aims, so
    // its lamp is solved for rather than fixed. That is a whole different exposure model.
    if (paper_stock.is_projected()) {
        const std::array<float, 3> gain = beam_gain ? *beam_gain
            : std::array<float, 3>{1.0f, 1.0f, 1.0f};
        return projected::build(stock, paper_stock, out, error, gain);
    }
    // A negative seen directly -- an integral instant sheet, or the `.negative` medium -- never
    // reaches a second emulsion: the table is a display-RGB cube, not a density read.
    if (stock.reflection_print) {
        // An integral sheet reads its own dyes on a light box, through the fitted cubic basis.
        return film_direct::build(stock, out, error);
    }
    if (paper_stock.is_negative_medium()) {
        return negative_viewing::build(stock, out, error);
    }
    if (!stock.curves[0].has_sampled && stock.curves[0].gamma == 0.0f) {
        error = "film: stock carries no characteristic curves";
        return false;
    }
    // A reflection paper is exposed by the enlarger's 3200 K lamp; Digital Reference balances
    // one negative exposure against a flat receiver, so its lamp is equal energy.
    // A simulated printer's filtered lamp replaces the enlarger's, and the print is still timed
    // against the printer's fixed reference setup rather than the lamp it is now shining -- that
    // is what makes a filter move the whole print instead of re-balancing it away.
    spectral::Spd lamp = printer_lamp ? *printer_lamp
        : (lamp_override ? *lamp_override : spectral::planckian(3200.0f));
    spectral::Spd reference_lamp = printer_lamp
        ? spectral::printer_lamp(3200.0f, 0.40f, 0.50f) : lamp;
    std::vector<float> offset;
    // Under a condenser head every density reads at its specular value: the image's own
    // Callier figure on the densities, silver's on the silver a bleach left behind. The offset
    // scales with them, so the print re-times through the same mid-grey and the head shows as
    // contrast rather than as a darker frame.
    const bool has_offset = film::density_offset(stock, stock.curves, callier, offset);
    const float silver_callier = callier == 1.0f ? 1.0f : 1.4f;

    // FilmStock.developedDensity: a reversal record's *developed* density is the complement of
    // the density it formed, dMin + dMax - formed. Everything the print stage reads is developed.
    std::array<float, 3> mid = {stock.curves[0].density(0.0f), stock.curves[1].density(0.0f),
                                stock.curves[2].density(0.0f)};
    if (stock.reversal) {
        for (int c = 0; c < 3; ++c) {
            mid[size_t(c)] = stock.curves[size_t(c)].d_min + stock.curves[size_t(c)].d_max()
                           - mid[size_t(c)];
        }
    }
    // SpectralModel's default branch reads a reversal's records through `neutralDensityBasis`
    // (a Status A unmix) before the paper sees them; a negative passes through untouched.
    const reversal::Basis basis(stock);
    mid = basis(mid);
    std::array<float, 3> mid_energy{};
    std::array<float, 3> mid_scaled = mid;
    for (int c = 0; c < 3; ++c) mid_scaled[size_t(c)] *= callier;
    film::paper_exposure(mid_scaled, stock, reference_lamp, paper_stock.spectra.sensitivity,
                         silver_callier * film::retained_silver(mid, d_min_of(stock), bleach),
                         has_offset ? &offset : nullptr, mid_energy);

    std::array<float, 3> d_min{}, range{};
    for (int c = 0; c < 3; ++c) {
        d_min[size_t(c)] = stock.curves[size_t(c)].d_min;
        range[size_t(c)] = stock.curves[size_t(c)].d_max() - d_min[size_t(c)];
    }
    out.assign(kCells * 4, 0.0f);
    for (size_t cell = 0; cell < kCells; ++cell) {
        float p[3];
        cell_point(cell, p);
        std::array<float, 3> density = {d_min[0] + p[0] * range[0],
                                        d_min[1] + p[1] * range[1],
                                        d_min[2] + p[2] * range[2]};
        std::array<float, 3> energy{};
        const std::array<float, 3> base = d_min_of(stock);
        std::array<float, 3> aligned = basis(density);
        for (int c = 0; c < 3; ++c) aligned[size_t(c)] *= callier;
        film::paper_exposure(aligned, stock, lamp, paper_stock.spectra.sensitivity,
                             silver_callier * film::retained_silver(density, base, bleach),
                             has_offset ? &offset : nullptr, energy);
        for (int c = 0; c < 3; ++c) {
            out[cell * 4 + size_t(c)] = std::log10(std::max(energy[size_t(c)], 1e-12f)
                                                   / std::max(mid_energy[size_t(c)], 1e-12f));
        }
        out[cell * 4 + 3] = 1.0f;
    }
    return true;
}

// A monochrome negative prints through one exposure, and the engine forces its output neutral, so
// its print curves are the timing record -- the green curve -- three times. Using the sheet's three
// separate records would skew every density the film table is built from.
inline bool build_film(const film::Spectra &stock, const paper::Stock &paper_stock,
                       std::vector<float> &out, const char *&error,
                       const spectral::Spd *lamp_override = nullptr, float bleach = 0.0f,
                       float callier = 1.0f, const spectral::Spd *printer_lamp = nullptr,
                       const std::array<float, 3> *beam_gain = nullptr) {
    paper::Stock sheet = paper_stock;
    if (stock.monochrome) {
        sheet.curves = {sheet.curves[1], sheet.curves[1], sheet.curves[1]};
    }
    return build_film_impl(stock, sheet, out, error, lamp_override, bleach, callier,
                           printer_lamp, beam_gain);
}


/// SpectralModel.buildTables, the `paper == .screen` branch: Digital Reference. The table's
/// range is the style's own curve, and the reading is the receiver's.
inline bool build_screen(const film::Spectra &stock, screen_ref::Style style, float grade,
                         std::vector<float> &out, const char *&error) {
    const curve::Curve curve = stock.reversal ? screen_ref::positive_curve()
        : (style == screen_ref::Style::ReferenceExposure ? screen_ref::reference_curve_for(stock)
                                                         : screen_ref::straight_curve());
    const float range = curve.d_max() - curve.d_min;
    const screen_ref::Receiver receiver(stock, style, grade);
    out.assign(kCells * 4, 0.0f);
    for (size_t cell = 0; cell < kCells; ++cell) {
        float p[3];
        cell_point(cell, p);
        const screen_ref::V3 density = {p[0] * range, p[1] * range, p[2] * range};
        const screen_ref::V3 rgb = receiver(density);
        for (int c = 0; c < 3; ++c) out[cell * 4 + size_t(c)] = rgb[size_t(c)];
        out[cell * 4 + 3] = 1.0f;
    }
    (void)error;
    return true;
}

/// SpectralModel.buildTables, the `viewsFilmDirectly` branch: a transparency read by
/// transmitted light. There is no paper table at all -- the film table is the output, so a
/// caller that expects three tables gets one and a null.
inline bool build_film_direct(const film::Spectra &stock, std::vector<float> &out,
                              const char *&error) {
    if (!stock.reversal || stock.reflection_print) {
        error = "reversal: this stock is printed, not viewed";
        return false;
    }
    reversal::Basis basis(stock);
    float balance[3];
    reversal::balance_for(stock, basis, balance);
    std::array<float, 3> d_min{}, range{};
    for (int c = 0; c < 3; ++c) {
        d_min[size_t(c)] = stock.curves[size_t(c)].d_min;
        range[size_t(c)] = stock.curves[size_t(c)].d_max() - d_min[size_t(c)];
    }
    out.assign(kCells * 4, 0.0f);
    for (size_t cell = 0; cell < kCells; ++cell) {
        float p[3];
        cell_point(cell, p);
        const reversal::V3 density = {d_min[0] + p[0] * range[0],
                                      d_min[1] + p[1] * range[1],
                                      d_min[2] + p[2] * range[2]};
        const reversal::V3 rgb = reversal::transmission_rgb_stock(basis(density), stock);
        for (int c = 0; c < 3; ++c) {
            out[cell * 4 + size_t(c)] = std::max(rgb[size_t(c)], 0.0f) * balance[c];
        }
        out[cell * 4 + 3] = 1.0f;
    }
    return true;
}

/// The display RGB a finished sheet reflects: its Status A density is unmixed back into the
/// dye amounts the sheet actually holds, then read under the viewing lamp. `use_unmix` is
/// false for the tables that are not a sheet (a scan, the digital reference, monochrome).
inline bool build_paper(const paper::Stock &paper_stock, bool use_unmix,
                        std::vector<float> &out, const char *&error,
                        const spectral::Spd *viewing_override = nullptr,
                        bool monochrome = false) {
    // The lamp the finished positive is read under. A reflection print defaults to the D50
    // judging booth; a release print to the filtered 5400 K xenon of a calibrated projector.
    const spectral::Spd d50 = viewing_override ? *viewing_override
        : (paper_stock.is_projected() ? spectral::xenon_projection()
                                      : spectral::daylight(5003.0f));
    // A monochrome negative develops all three print records along one curve (the timing record,
    // i.e. the sheet's green one), so the activation axis is the same width in every channel.
    // Taking the sheet's three published records here would stretch the cube per record.
    const size_t timing = monochrome ? 1u : 0u;
    std::array<float, 3> ranges{};
    for (int c = 0; c < 3; ++c) {
        const curve::Curve &cc = paper_stock.curves[monochrome ? timing : size_t(c)];
        ranges[size_t(c)] = cc.d_max() - cc.d_min;
    }
    receiver::Unmix unmix(paper_stock.spectra.analytical_dyes);
    out.assign(kCells * 4, 0.0f);
    for (size_t cell = 0; cell < kCells; ++cell) {
        float p[3];
        cell_point(cell, p);
        const receiver::V3 density = {p[0] * ranges[0], p[1] * ranges[1], p[2] * ranges[2]};
        receiver::V3 rgb;
        if (monochrome) {
            // A monochrome negative's print holds silver through one exposure and the output is
            // forced neutral, so the sheet is read as its measured dye set with the density taken
            // directly -- there is no colour unmix to do. The colour path's analytic dyes and
            // inverse would invent a chroma the frame never had.
            rgb = receiver::transmission_rgb(density, paper_stock.spectra.dyes, d50);
        } else if (use_unmix) {
            const receiver::V3 amounts = unmix.amounts_for(density);
            rgb = receiver::transmission_rgb(amounts, paper_stock.spectra.analytical_dyes, d50);
        } else {
            rgb = receiver::transmission_rgb(
                {std::pow(10.0f, -density[0]), std::pow(10.0f, -density[1]),
                 std::pow(10.0f, -density[2])},
                paper_stock.spectra.analytical_dyes, d50);
        }
        for (int c = 0; c < 3; ++c) out[cell * 4 + size_t(c)] = rgb[size_t(c)];
        out[cell * 4 + 3] = 1.0f;
    }
    (void)error;
    return true;
}

}  // namespace tables

#endif
