#ifndef FOTUFILM_HOST_FILM_HPP
#define FOTUFILM_HOST_FILM_HPP

// The film side of the print: what a developed density becomes on the paper.
//
// A colour record's dye set has its own minimum density, which the characteristic curves
// already carry as `dMin`. The spectrum the paper sees is therefore the measured base plus the
// three records' densities above their own minima through their dye shapes -- not the raw
// densities, which would double-count the base.

#include "paper.hpp"
#include "spectral.hpp"

#include <array>
#include <cmath>
#include <cstring>
#include <vector>

namespace film {

/// A coated record beyond the dye-forming three: it develops and releases inhibitor but
/// forms no image dye. Fuji's fourth layer, and the one the exposure cube's fourth channel
/// carries. Seven of the forty-seven stocks have one.
struct Donor {
    std::string name;
    std::vector<float> sensitivity;
    curve::Curve curve;
};

/// SpectralModel.retainedSilverDensity: a spectrally flat density, proportional to how far each
/// record sits above its own base. `fraction` is the share a bleach bypass leaves behind.
inline float retained_silver(const std::array<float, 3> &density,
                             const std::array<float, 3> &d_min, float fraction) {
    if (fraction <= 0.0f) return 0.0f;
    float silver = 0.0f;
    for (int c = 0; c < 3; ++c) silver += std::max(density[size_t(c)] - d_min[size_t(c)], 0.0f);
    return fraction * silver;    // retainedSilverPerDye is exactly 1
}

struct Spectra {
    float kelvin = 5500.0f;
    std::array<std::vector<float>, 3> sensitivity;
    std::array<std::vector<float>, 3> dye;
    std::vector<float> minimum_density;
    bool has_minimum_density = false;
    std::array<curve::Curve, 3> curves;
    std::vector<Donor> donors;
    // The three facts that pick a branch in buildTables. They are not derivable from the
    // spectra: a reversal stock and a negative can share a dye set.
    bool monochrome = false;
    bool reversal = false;
    bool reflection_print = false;
};

/// FilmStock.expired(years:): one stop of speed loss per decade, weighted per record, with fog
/// proportional to the loss. The grain the ageing adds lives in the configuration's slots, not
/// here, so this is the curve half.
inline Spectra aged(const Spectra &stock, float years) {
    if (!(years > 0.0f)) return stock;
    const float decades = years / 10.0f;
    const float per_stop = std::log10(2.0f);           // one stop on the log-exposure axis
    const float weights[3] = {0.7f, 1.0f, 1.3f};        // R, G, B: blue ages fastest
    Spectra out = stock;
    for (int c = 0; c < 3; ++c) {
        const float lost = decades * weights[c];
        const float fog = 0.08f * lost;
        curve::Curve &curve = out.curves[size_t(c)];
        curve.toe += lost * per_stop;
        curve.shoulder += lost * per_stop;
        curve.d_min += fog;
        if (curve.has_sampled) curve.sampled = curve.sampled.shifted(lost * per_stop, fog);
        if (out.has_minimum_density && out.dye[size_t(c)].size() == out.minimum_density.size()) {
            for (size_t i = 0; i < out.minimum_density.size(); ++i)
                out.minimum_density[i] += fog * out.dye[size_t(c)][i];
        }
    }
    return out;
}

// --- push / pull -------------------------------------------------------------
//
// `FilmStock.pushed(stops:)` replaces the stock's three layer curves with the ones the pack
// records at that development condition. `CharacteristicCurve.sampled` takes precedence over the
// analytic fit, so the 483-point record is what the engine actually reads; the six parameters
// come along for `dMax` and for readers that prefer them.

/// The four configuration blocks a development condition moves, packed by the engine so the
/// port never has to re-derive them: the six curve parameters per layer, the packed sampled
/// records, the coupler warp's solved table and the donor's curve.
struct PushBlocks {
    std::array<std::array<float, 6>, 3> curves{};
    std::array<std::vector<float>, 3> sampled;   // [count, (logE, density, slope) * count]
    std::vector<float> warp;
    std::array<float, 6> donor{};
};

inline bool read_push(const std::string &path, const std::string &wanted, float stops,
                      PushBlocks &out) {
    std::FILE *file = std::fopen(path.c_str(), "rb");
    if (!file) return false;
    char magic[4];
    if (std::fread(magic, 1, 4, file) != 4 || std::memcmp(magic, "PCH1", 4) != 0) {
        std::fclose(file);
        return false;
    }
    uint32_t stocks = 0;
    std::fread(&stocks, 4, 1, file);
    bool found = false;
    std::vector<float> scratch;
    for (uint32_t i = 0; i < stocks; ++i) {
        uint32_t idlen = 0;
        std::fread(&idlen, 4, 1, file);
        std::string id(idlen, '\0');
        std::fread(&id[0], 1, idlen, file);
        uint32_t ncond = 0;
        std::fread(&ncond, 4, 1, file);
        for (uint32_t k = 0; k < ncond; ++k) {
            float cond_stops = 0.0f;
            uint32_t nblocks = 0;
            std::fread(&cond_stops, 4, 1, file);
            std::fread(&nblocks, 4, 1, file);
            const bool hit = !found && id == wanted && std::fabs(cond_stops - stops) <= 1e-4f;
            for (uint32_t b = 0; b < nblocks; ++b) {
                uint32_t offset = 0, count = 0;
                std::fread(&offset, 4, 1, file);
                std::fread(&count, 4, 1, file);
                scratch.assign(count, 0.0f);
                if (count) std::fread(scratch.data(), 4, count, file);
                if (!hit) continue;
                // Identified by offset, the layout's own keys: 18 curve parameters, the sampled
                // records (stride 3073 each), the warp table at 66 and the donor at 8743.
                if (offset == 0 && count == 18) {
                    for (int c = 0; c < 3; ++c) {
                        for (int f = 0; f < 6; ++f) out.curves[size_t(c)][size_t(f)] = scratch[size_t(c * 6 + f)];
                    }
                } else if (count == 3073u * 3u) {
                    for (int c = 0; c < 3; ++c) {
                        out.sampled[size_t(c)].assign(scratch.begin() + c * 3073,
                                                      scratch.begin() + (c + 1) * 3073);
                    }
                } else if (offset == 66 && count == 384) {
                    out.warp = scratch;
                } else if (offset == 8743 && count == 6) {
                    for (int f = 0; f < 6; ++f) out.donor[size_t(f)] = scratch[size_t(f)];
                }
            }
            if (hit) found = true;
        }
    }
    std::fclose(file);
    return found;
}

/// Rebuilds the stock's three layer curves from a packed condition so the table builders read
/// the pushed characteristic: the six parameters, plus the sampled record where the pack has one
/// (`CharacteristicCurve.sampled` takes precedence over the analytic fit).
inline void apply_push_curves(const PushBlocks &blocks, std::array<curve::Curve, 3> &out) {
    for (int c = 0; c < 3; ++c) {
        curve::Curve &cv = out[size_t(c)];
        cv.d_min = blocks.curves[size_t(c)][0];
        cv.gamma = blocks.curves[size_t(c)][1];
        cv.toe = blocks.curves[size_t(c)][2];
        cv.toe_width = blocks.curves[size_t(c)][3];
        cv.shoulder = blocks.curves[size_t(c)][4];
        cv.shoulder_width = blocks.curves[size_t(c)][5];
        cv.has_secondary = false;
        const std::vector<float> &rec = blocks.sampled[size_t(c)];
        cv.has_sampled = false;
        if (rec.size() >= 7) {
            const size_t count = size_t(std::max(2.0f, rec[0]));
            if (rec.size() >= 1 + count * 3) {
                std::vector<float> le(count), dn(count);
                for (size_t i = 0; i < count; ++i) {
                    le[i] = rec[1 + i * 3];
                    dn[i] = rec[2 + i * 3];
                }
                cv.has_sampled = true;
                cv.sampled = curve::Sampled(std::move(le), std::move(dn));
            }
        }
    }
}

/// The conditions a stock lists, for a UI that has to disable an unlisted step. Reads the same
/// PCH1 payload `read_push` does, block by block, and never trusts a length it has not bounded:
/// a mis-read block count would otherwise become a multi-gigabyte allocation.
inline std::vector<float> push_stops(const std::string &path, const std::string &wanted) {
    std::vector<float> out;
    std::FILE *file = std::fopen(path.c_str(), "rb");
    if (!file) return out;
    char magic[4];
    if (std::fread(magic, 1, 4, file) != 4 || std::memcmp(magic, "PCH1", 4) != 0) {
        std::fclose(file);
        return out;
    }
    uint32_t stocks = 0;
    if (std::fread(&stocks, 4, 1, file) != 1 || stocks > 4096u) { std::fclose(file); return out; }
    std::fseek(file, 0, SEEK_END);
    const long file_bytes = std::ftell(file);
    std::fseek(file, 8, SEEK_SET);
    for (uint32_t i = 0; i < stocks; ++i) {
        uint32_t idlen = 0;
        if (std::fread(&idlen, 4, 1, file) != 1 || idlen > 4096u) { std::fclose(file); return std::vector<float>(); }
        std::string id(idlen, '\0');
        if (std::fread(&id[0], 1, idlen, file) != idlen) { std::fclose(file); return std::vector<float>(); }
        uint32_t ncond = 0;
        if (std::fread(&ncond, 4, 1, file) != 1 || ncond > 4096u) { std::fclose(file); return std::vector<float>(); }
        for (uint32_t k = 0; k < ncond; ++k) {
            float cond_stops = 0.0f;
            uint32_t nblocks = 0;
            if (std::fread(&cond_stops, 4, 1, file) != 1) { std::fclose(file); return std::vector<float>(); }
            if (std::fread(&nblocks, 4, 1, file) != 1 || nblocks > 64u) { std::fclose(file); return std::vector<float>(); }
            if (id == wanted) out.push_back(cond_stops);
            for (uint32_t b = 0; b < nblocks; ++b) {
                uint32_t offset = 0, count = 0;
                if (std::fread(&offset, 4, 1, file) != 1 || std::fread(&count, 4, 1, file) != 1) {
                    std::fclose(file); return std::vector<float>();
                }
                if (count > 32768u || long(count) * 4 > file_bytes) { std::fclose(file); return std::vector<float>(); }
                if (std::fseek(file, long(count) * 4, SEEK_CUR) != 0) { std::fclose(file); return std::vector<float>(); }
            }
        }
    }
    std::fclose(file);
    std::sort(out.begin(), out.end());
    return out;
}

inline bool read_stocks(const std::string &path, const std::string &wanted, Spectra &out) {
    std::FILE *file = std::fopen(path.c_str(), "rb");
    if (!file) return false;
    char magic[4];
    if (std::fread(magic, 1, 4, file) != 4) { std::fclose(file); return false; }
    const bool has_flags = std::memcmp(magic, "STK6", 4) == 0
                        || std::memcmp(magic, "STK7", 4) == 0;
    // STK7 carries each record's optional second density component.
    const bool has_secondary = std::memcmp(magic, "STK7", 4) == 0;
    const bool has_donors = has_flags || std::memcmp(magic, "STK5", 4) == 0;
    const bool has_curves = has_donors || std::memcmp(magic, "STK4", 4) == 0;
    const bool has_minimum = has_curves || std::memcmp(magic, "STK3", 4) == 0;
    if (!has_minimum && std::memcmp(magic, "STK2", 4) != 0) { std::fclose(file); return false; }
    uint32_t count = 0;
    std::fread(&count, 4, 1, file);
    for (uint32_t i = 0; i < count; ++i) {
        uint32_t length = 0;
        std::fread(&length, 4, 1, file);
        std::string id(length, '\0');
        std::fread(&id[0], 1, length, file);
        Spectra s;
        std::fread(&s.kelvin, 4, 1, file);
        for (auto &layer : s.sensitivity) {
            layer.resize(81);
            std::fread(layer.data(), 4, 81, file);
        }
        for (auto &layer : s.dye) {
            layer.resize(81);
            std::fread(layer.data(), 4, 81, file);
        }
        if (has_minimum) {
            s.minimum_density.resize(81);
            std::fread(s.minimum_density.data(), 4, 81, file);
            // A stock with no measured base carries 81 zeros in this slot, not a missing block:
            // treat "no channel absorbs anything" as "the pack states no minimum density", which
            // is what `spectral.minimumDensity` being absent means on the Swift side.
            s.has_minimum_density = false;
            for (float v : s.minimum_density) {
                if (v > 0.0f) { s.has_minimum_density = true; break; }
            }
        }
        if (has_curves) {
            for (int layer = 0; layer < 3; ++layer) {
                curve::Curve &c = s.curves[size_t(layer)];
                float params[6];
                std::fread(params, 4, 6, file);
                c.d_min = params[0];
                c.gamma = params[1];
                c.toe = params[2];
                c.toe_width = params[3];
                c.shoulder = params[4];
                c.shoulder_width = params[5];
                if (has_secondary) {
                    float sec[6];
                    std::fread(sec, 4, 6, file);
                    c.has_secondary = sec[0] != 0.0f;
                    if (c.has_secondary) {
                        c.secondary.gamma = sec[1];
                        c.secondary.toe = sec[2];
                        c.secondary.toe_width = sec[3];
                        c.secondary.shoulder = sec[4];
                        c.secondary.shoulder_width = sec[5];
                    }
                }
                uint32_t points = 0;
                std::fread(&points, 4, 1, file);
                std::vector<float> le(static_cast<size_t>(points)), dn(static_cast<size_t>(points));
                for (uint32_t i = 0; i < points; ++i) {
                    float pair[2];
                    std::fread(pair, 4, 2, file);
                    le[size_t(i)] = pair[0];
                    dn[size_t(i)] = pair[1];
                }
                if (points >= 2) {
                    c.has_sampled = true;
                    c.sampled = curve::Sampled(std::move(le), std::move(dn));
                } else {
                    c.has_sampled = false;
                }
            }
        }
        if (has_donors) {
            uint32_t donor_count = 0;
            std::fread(&donor_count, 4, 1, file);
            for (uint32_t k = 0; k < donor_count; ++k) {
                Donor donor;
                uint32_t name_length = 0;
                std::fread(&name_length, 4, 1, file);
                donor.name.resize(name_length, '\0');
                if (name_length) std::fread(&donor.name[0], 1, name_length, file);
                donor.sensitivity.resize(81);
                std::fread(donor.sensitivity.data(), 4, 81, file);
                float params[6] = {0, 0, 0, 0, 0, 0};
                std::fread(params, 4, 6, file);
                donor.curve.d_min = params[0];
                donor.curve.gamma = params[1];
                donor.curve.toe = params[2];
                donor.curve.toe_width = params[3];
                donor.curve.shoulder = params[4];
                donor.curve.shoulder_width = params[5];
                uint32_t points = 0;
                std::fread(&points, 4, 1, file);
                std::vector<float> le(static_cast<size_t>(points)), dn(static_cast<size_t>(points));
                for (uint32_t i = 0; i < points; ++i) {
                    float pair[2];
                    std::fread(pair, 4, 2, file);
                    le[size_t(i)] = pair[0];
                    dn[size_t(i)] = pair[1];
                }
                if (points >= 2) {
                    donor.curve.has_sampled = true;
                    donor.curve.sampled = curve::Sampled(std::move(le), std::move(dn));
                }
                s.donors.push_back(std::move(donor));
            }
        }
        if (has_flags) {
            uint32_t f[3] = {0, 0, 0};
            std::fread(f, 4, 3, file);
            s.monochrome = f[0] != 0;
            s.reversal = f[1] != 0;
            s.reflection_print = f[2] != 0;
        }
        if (id == wanted) {
            out = s;
            std::fclose(file);
            // SpectralRuntime.dyes(family:) builds a measured monochrome stock's dye set as three
            // constant 1/3 layers: the record holds silver, not a dye, so every wavelength
            // attenuates alike. A pack that carries `kind: measured` publishes only its layer
            // sensitivities, and the packed file leaves the dye layers at zero; reading those
            // literally would transmit the whole spectrum and wash the frame out.
            // FilmSpectralProfile.init carries every published layer sensitivity past its
            // support: `layerSensitivity.map(SpectralGrid.continuedTails)`. A pack measured at
            // five-nanometre spacing stops at the ends of its support, and the records still
            // respond there -- skipping it leaves the outer bands blind. Samples-based packs
            // already fall to zero at both ends, which is why this only shows on `measured` ones.
            for (int c = 0; c < 3; ++c) {
                out.sensitivity[size_t(c)] = paper::continued_tails(out.sensitivity[size_t(c)]);
            }
            if (out.monochrome) {
                bool blank = true;
                for (int c = 0; c < 3 && blank; ++c) {
                    for (float v : out.dye[size_t(c)]) if (v != 0.0f) { blank = false; break; }
                }
                if (blank) {
                    for (int c = 0; c < 3; ++c) {
                        out.dye[size_t(c)].assign(out.dye[size_t(c)].size(), 1.0f / 3.0f);
                    }
                }
            }
            return true;
        }
    }
    std::fclose(file);
    return false;
}

/// FilmAbsorption.filmDensityOffset: the measured base less the three records' own minima
/// through their dye shapes, so the curve's `dMin` is not counted twice.
inline bool density_offset(const Spectra &stock, const std::array<curve::Curve, 3> &curves,
                           float scale, std::vector<float> &out) {
    if (!stock.has_minimum_density) return false;
    const float minimum[3] = {curves[0].d_min, curves[1].d_min, curves[2].d_min};
    out.resize(81);
    for (int i = 0; i < 81; ++i) {
        const float dye_sum = minimum[0] * stock.dye[0][size_t(i)]
            + minimum[1] * stock.dye[1][size_t(i)]
            + minimum[2] * stock.dye[2][size_t(i)];
        out[size_t(i)] = (stock.minimum_density[size_t(i)] - dye_sum) * scale;
    }
    return true;
}

/// SpectralModel.paperExposure: the lamp through the developed densities and the dyes, read
/// by the paper's three layers.
inline void paper_exposure(const std::array<float, 3> &density, const Spectra &stock,
                           const spectral::Spd &lamp,
                           const std::array<std::vector<float>, 3> &paper_sensitivity,
                           float neutral_density, const std::vector<float> *offset,
                           std::array<float, 3> &out) {
    out = {0.0f, 0.0f, 0.0f};
    for (int i = 0; i < 81; ++i) {
        float d = density[0] * stock.dye[0][size_t(i)]
                + density[1] * stock.dye[1][size_t(i)]
                + density[2] * stock.dye[2][size_t(i)]
                + neutral_density;
        if (offset) d += (*offset)[size_t(i)];
        const float transmitted = lamp[size_t(i)] * std::pow(10.0f, -d);
        out[0] += transmitted * paper_sensitivity[0][size_t(i)];
        out[1] += transmitted * paper_sensitivity[1][size_t(i)];
        out[2] += transmitted * paper_sensitivity[2][size_t(i)];
    }
}

}  // namespace film

#endif
