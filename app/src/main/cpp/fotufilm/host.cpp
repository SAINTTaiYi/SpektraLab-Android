// A C++ host for the fotufilm kernels: decode, develop, print, encode.
//
// No Swift, no Halide compiler, no Flutter. The decoder is the engine's own
// (CFotufilmCodecs, through lcms2 and its D50 PCS), so the scene handed to the
// kernels is the engine's scene rather than a re-derivation that lands 3e-5 away.
//
//   host <photo> <config.f32> <exposure.f32> <film.f32> <paper.f32> <out.ppm> [maxEdge]
//
// With a bake dump beside the parameters the same frame can be compared byte for
// byte: pass the dump's own blocks and the output must equal its output-*.f32.

#include "fotufilm_codecs.h"
#include "FotufilmConfigLayout.h"
#include "FotufilmHalide.h"

#include <cstring>
#include "view_adjust.hpp"
#include "camera_profile.hpp"
#include "physics.hpp"
#include "tables.hpp"
#include "film_screen.hpp"
#include "tone.hpp"
#include "negative_viewing.hpp"
#include "cineon_log.hpp"
#include "telecine_bands.hpp"
#include "film_direct.hpp"
#include "film_grain.hpp"
#include "print_detail.hpp"
#include "print_correction.hpp"

#include <cmath>
#include <cstdint>
#include <atomic>
#include <dirent.h>
#include <sys/stat.h>
#include <chrono>
#include <cstdio>
#include <exception>
#include <new>
#include <algorithm>
#include <cstring>
#include <map>
#include <fstream>
#include <string>
#include <thread>
#include <vector>

#include "rebate/rebate.hpp"
#include "print_frame.hpp"

namespace {

/// Set by `fh_cancel` from the UI thread. The Halide tile call is one blocking C call and cannot
/// be interrupted, so the flag is read before and after: a cancelled render hands back an error
/// instead of writing the file the caller no longer wants.
std::atomic<bool> g_cancelled{false};

/**
 * Runs `body(row)` for each of `rows`, on up to eight threads.
 *
 * <p>Every loop this is used on is element-wise across rows, so each output
 * pixel keeps exactly the sequence of floating-point operations it had
 * single-threaded: the split is over work, never over arithmetic. Nothing here
 * reorders a sum.
 */
template <typename Body>
void parallel_rows(int rows, Body body) {
    if (rows <= 0) return;
    unsigned hw = std::thread::hardware_concurrency();
    if (hw == 0) hw = 1;
    int n = int(std::min<unsigned>(hw, 8u));
    if (n > rows) n = rows;
    if (n <= 1 || rows < 16) {
        for (int i = 0; i < rows; ++i) body(i);
        return;
    }
    const int per = (rows + n - 1) / n;
    std::vector<std::thread> pool;
    pool.reserve(size_t(n - 1));
    for (int t = 1; t < n; ++t) {
        const int lo = t * per;
        const int hi = std::min(rows, lo + per);
        if (lo >= hi) break;
        pool.emplace_back([&body, lo, hi]() {
            for (int i = lo; i < hi; ++i) body(i);
        });
    }
    for (int i = 0; i < std::min(rows, per); ++i) body(i);
    for (std::thread &t : pool) t.join();
}

/// `options.grainMottleShare`: -1 keeps the stock's own share, which is what the bake
/// used. Anything else re-splits the published variance between the sharp grain and the
/// coarse field, exactly as `FilmEngine` does.
static float g_mottle_share = -1.0f;
/// `options.gradeSpaceEncoded`: the grade applied to the encoded signal instead of linear light.
static int g_grade_space = -1;
/// `options.printViewingKelvin`: the lamp a finished print is judged under.
static std::string g_print_light;
/// `options.digitalReferenceIndex`: which receiver Digital Reference uses.
static std::string g_digital_reference;
/// `options.enlargerIndex`: 1 for a condenser head, whose Callier coefficient hardens the print.
static int g_enlarger = 0;
/// `options.localTone`: 0 keys the tone shift to each pixel instead of to its region.
static int g_local_tone = -1;
/// `PrinterProfile`: the simulated printer's lamp and filters. Off unless `--printer` says on.
static int g_printer = 0;
static float g_printer_lamp = 3200.0f, g_printer_m = 0.40f, g_printer_y = 0.50f;
static float g_printer_ev = 0.0f;
/// `PrinterLights`: the release printer's three lights, in printer points (25 = the stock's aim).
static float g_printer_rgb[3] = {25.0f, 25.0f, 25.0f};

std::vector<float> read_f32(const std::string &path, size_t count) {
    std::vector<float> values(count);
    std::FILE *file = std::fopen(path.c_str(), "rb");
    if (!file) {
        std::fprintf(stderr, "missing %s\n", path.c_str());
        return {};
    }
    const size_t read = std::fread(values.data(), sizeof(float), count, file);
    std::fclose(file);
    if (read != count) {
        std::fprintf(stderr, "%s holds %zu floats, wanted %zu\n", path.c_str(), read, count);
        values.clear();
    }
    return values;
}

bool write_ppm(const std::string &path, const std::vector<uint8_t> &rgb, int width, int height) {
    std::FILE *file = std::fopen(path.c_str(), "wb");
    if (!file) return false;
    std::fprintf(file, "P6\n%d %d\n255\n", width, height);
    std::fwrite(rgb.data(), 1, rgb.size(), file);
    std::fclose(file);
    return true;
}

/// The engine's display encoding, as HostEngine does it: linear Rec. 2020 to linear
/// Display P3, then the sRGB transfer. Matrices from ColorScience.
// Linear Rec.2020 -> linear Display P3, D65 to D65 with Bradford folded into the matrix.
void to_display_p3_linear(const float *linear, int count, std::vector<float> &out) {
    static const float m[9] = {
         1.343578253f, -0.282179671f, -0.061398582f,
        -0.065297453f,  1.075787916f, -0.010490463f,
         0.002821787f, -0.019598495f,  1.016776707f,
    };
    out.resize(size_t(count) * 3);
    for (int i = 0; i < count; ++i) {
        const float r = linear[i * 3 + 0], g = linear[i * 3 + 1], b = linear[i * 3 + 2];
        out[size_t(i) * 3 + 0] = m[0] * r + m[1] * g + m[2] * b;
        out[size_t(i) * 3 + 1] = m[3] * r + m[4] * g + m[5] * b;
        out[size_t(i) * 3 + 2] = m[6] * r + m[7] * g + m[8] * b;
    }
}

/// `.cineon-log` delivers code values, not light: the table already wrote
/// `(445 - read/0.002)/1023` and the engine's output transform for that medium is the identity
/// (`FilmOutputConversion.cineonLog`: identity matrix, linear transfer, no shoulder). Encoding
/// them as if they were display light would put a 445 grey at 177 instead of 113.
bool g_code_values = false;

/// The sRGB transfer's eight-bit map, answered without a `pow` a channel.
///
/// <p>Rounding to a byte is monotone in the input, so the whole curve is 256
/// thresholds: a value's byte is how many of them it reaches. The thresholds are
/// built once, by bisecting the *same* expression the slow path evaluates -- this
/// is another way to compute one function, not a different function, and it is
/// checked against it over the whole range. It replaces three `pow` calls a pixel,
/// which measured 137 ms on a 2240x2068 canvas (and it is what a drag frame at
/// 420 px spends 5 ms on).
struct SrgbByteTable {
    float threshold[257];
    uint8_t coarse[4097];
};

static uint8_t srgb_byte_slow(float v) {
    const float e = v <= 0.0031308f ? v * 12.92f
                                    : 1.055f * std::pow(v, 1.0f / 2.4f) - 0.055f;
    const float c = e <= 0 ? 0 : e >= 1 ? 1 : e;
    return uint8_t(c * 255.0f + 0.5f);
}

static const SrgbByteTable &srgb_byte_table() {
    static const SrgbByteTable table = []() {
        SrgbByteTable t{};
        for (int b = 0; b < 257; ++b) t.threshold[b] = 2.0f;   // never reached
        t.threshold[0] = -1.0f;
        for (int b = 1; b < 256; ++b) {
            // **The bit pattern, not the value.** Positive floats order the same
            // way as their integers, so the smallest float that rounds to `b` is a
            // bisection over the representation -- 32 steps, no tolerance.
            uint32_t lo = 0, hi = 0x3F800000u;                 // 0 .. 1
            while (lo < hi) {
                const uint32_t mid = lo + (hi - lo) / 2;
                float x;
                std::memcpy(&x, &mid, sizeof x);
                if (srgb_byte_slow(x) >= b) hi = mid; else lo = mid + 1;
            }
            float x;
            std::memcpy(&x, &lo, sizeof x);
            t.threshold[b] = x;
        }
        for (int i = 0; i <= 4096; ++i) t.coarse[i] = srgb_byte_slow(float(i) / 4096.0f);
        return t;
    }();
    return table;
}

/// The byte `srgb_byte_slow` gives, one index and at most two comparisons.
inline uint8_t srgb_byte_fast(const SrgbByteTable &t, float v) {
    if (!(v > 0.0f)) return 0;                                 // negative, zero, NaN
    if (v >= 1.0f) return 255;
    int b = int(t.coarse[int(v * 4096.0f)]);
    if (b > 0 && v < t.threshold[b]) --b;
    else if (b < 255 && v >= t.threshold[b + 1]) ++b;
    return uint8_t(b);
}

void display_p3(const float *linear, int count, std::vector<uint8_t> &out) {
    // The develop kernels already deliver Display P3 (their output matches a bake's
    // output-*.f32 planes to 1e-6), so this is the transfer alone -- no matrix.
    out.resize(size_t(count) * 3);
    if (!g_code_values) {
        const SrgbByteTable &table = srgb_byte_table();
        // A byte is independent of every other byte here, so the range splits
        // anywhere: 137 ms of `pow` became 44 ms of table lookups, and eight
        // chunks take that 44 to 12.
        const int chunks = std::min(64, std::max(1, count / 16384));
        parallel_rows(chunks, [&](int chunk) {
            const int lo = int(std::int64_t(chunk) * count / chunks);
            const int hi = int(std::int64_t(chunk + 1) * count / chunks);
            for (int i = lo; i < hi; ++i) {
                const float *c = &linear[size_t(i) * 3];
                out[size_t(i) * 3 + 0] = srgb_byte_fast(table, c[0]);
                out[size_t(i) * 3 + 1] = srgb_byte_fast(table, c[1]);
                out[size_t(i) * 3 + 2] = srgb_byte_fast(table, c[2]);
            }
        });
        return;
    }
    // `.cineon-log` is already a code value; its output transform is the identity.
    for (int i = 0; i < count; ++i) {
        const float *c = &linear[size_t(i) * 3];
        for (int k = 0; k < 3; ++k) {
            const float clamped = c[k] <= 0 ? 0 : c[k] >= 1 ? 1 : c[k];
            out[size_t(i) * 3 + k] = uint8_t(clamped * 255.0f + 0.5f);
        }
    }
}

// --- the box reduction the host applies before developing -------------------
//
// AreaResample.swift: every output pixel is the exact area mean of the source it
// covers, so a preview holds the frame's light and its grain statistics start from
// the same scene the full develop does. Separable, and the weights are in Float,
// accumulated in source order -- the same order the Swift does, so the scene the
// kernels see is the scene the engine hands them.

struct Tap {
    int first = 0;
    std::vector<float> weights;
};

std::vector<Tap> taps(int source, int target) {
    std::vector<Tap> out(static_cast<std::size_t>(target));
    const double step = double(source) / double(target);
    for (int index = 0; index < target; ++index) {
        const double start = double(index) * step;
        const double end = start + step;
        const int first = int(start);
        const int last = std::min(source - 1, int(std::floor(end - 1e-9)));
        out[size_t(index)].first = first;
        for (int column = first; column <= last; ++column) {
            const double covered = std::min(end, double(column + 1)) - std::max(start, double(column));
            out[size_t(index)].weights.push_back(float(covered / step));
        }
    }
    return out;
}

std::vector<float> reduce_rgba(const std::vector<float> &rgba, int width, int height,
                               int target_width, int target_height) {
    if (target_width == width && target_height == height) return rgba;
    const std::vector<Tap> columns = taps(width, target_width);
    const std::vector<Tap> rows = taps(height, target_height);
    std::vector<float> horizontal(size_t(target_width) * size_t(height) * 4, 0.0f);
    // **Rows, not pixels.** A six-megapixel file reduced to a 420 px drag frame
    // costs the same as reducing it to the canvas, and it happens on every
    // frame of a drag: 59-120 ms single-threaded, measured. Each row's taps are
    // summed in the same order as before.
    parallel_rows(height, [&](int y) {
        for (int x = 0; x < target_width; ++x) {
            float sum[4] = {0, 0, 0, 0};
            const Tap &tap = columns[size_t(x)];
            for (size_t k = 0; k < tap.weights.size(); ++k) {
                const size_t i = (size_t(y) * size_t(width) + size_t(tap.first + int(k))) * 4;
                const float weight = tap.weights[k];
                for (int c = 0; c < 4; ++c) sum[c] += rgba[i + c] * weight;
            }
            const size_t o = (size_t(y) * size_t(target_width) + size_t(x)) * 4;
            for (int c = 0; c < 4; ++c) horizontal[o + c] = sum[c];
        }
    });
    std::vector<float> out(size_t(target_width) * size_t(target_height) * 4, 0.0f);
    parallel_rows(target_height, [&](int y) {
        const Tap &tap = rows[size_t(y)];
        for (int x = 0; x < target_width; ++x) {
            float sum[4] = {0, 0, 0, 0};
            for (size_t k = 0; k < tap.weights.size(); ++k) {
                const size_t i = (size_t(tap.first + int(k)) * size_t(target_width) + size_t(x)) * 4;
                const float weight = tap.weights[k];
                for (int c = 0; c < 4; ++c) sum[c] += horizontal[i + c] * weight;
            }
            const size_t o = (size_t(y) * size_t(target_width) + size_t(x)) * 4;
            for (int c = 0; c < 4; ++c) out[o + c] = sum[c];
        }
    });
    return out;
}

/// AreaResample.size: the longest edge lands on `max_edge`, the other keeps the aspect.
void resampled_size(int width, int height, int max_edge, int &out_width, int &out_height) {
    const int longest = std::max(width, height);
    if (max_edge <= 0 || max_edge >= longest) { out_width = width; out_height = height; return; }
    const double scale = double(max_edge) / double(longest);
    out_width = std::max(1, int(std::round(double(width) * scale)));
    out_height = std::max(1, int(std::round(double(height) * scale)));
}

// --- the frame's own shape ------------------------------------------------
//
// The second engine has no geometry node: its chain goes from the photograph
// straight into the film. Cropping and turning therefore happen here, on the
// scene the decoder handed over, **through the first engine's own map** -- the
// constants below are `Pipeline::resolve_geometry_frame`'s, and the sampling is
// `spk_geometry_resample_df.comp`'s, down to the half-pixel offset and the
// clamped edge. A frame cropped to the same numbers must come out the same
// whichever engine developed it, or switching engines would move the picture.
//
// Format, one slot: `geo:cx|cy|cw|ch|rotation|turns|flips`, the crop normalised
// to the photograph. Identity is the absence of the slot, so a render nobody
// cropped takes the untouched path and stays bit-exact.
struct GeometrySlot {
    bool active = false;
    double x = 0.0, y = 0.0, w = 1.0, h = 1.0;
    double rotation = 0.0;
    int turns = 0, flips = 0;
};
static GeometrySlot g_geometry;

// The view layer: the finished print's tone and colour controls, from the header
// the engine's own viewer compiles. All zero => the pass is skipped and the bytes
// are the ones the develop produced.
static spk_adjust::ViewAdjust g_view;
// The frame gauge, in millimetres of short side. Zero means "read the sensor".
static float g_frame_mm = 0.0f;

// --- the film outside the frame (rebate/rebate.hpp) ---------------------------
//
// The first engine's overscan, as a CPU module: gate geometry from the seeds,
// the edge print and the date as film-space drawing, and the four composites
// that turn them into exposure. Off by default, and off means the whole path is
// skipped -- `rebate:0` has to leave a picture byte-identical.
static int g_rebate = 0;
static std::string g_rebate_gate = "135";
static std::string g_rebate_text;
static double g_rebate_fog = 1.0, g_rebate_leaks = 0.0;
static int g_rebate_num = 0, g_rebate_cam = 1, g_rebate_fr = 1;
static std::string g_rebate_date, g_rebate_place = "rebate";
static std::string g_rebate_style = "lcd", g_rebate_corner = "br";
static double g_rebate_size = 1.0;
static double g_rebate_fnum = 0.0;       // the camera's f-number: the gate's penumbra
static double g_rebate_inset_x = 3.0, g_rebate_inset_y = 2.4;   // mm from the gate's edge
static std::string g_rebate_carrier = "none";   // past the film's long edges
static std::string g_rebate_holes = "white";    // the scan's light, or a black backing

// --- the print frame's materials (print_frame.hpp) ---------------------------
static std::string g_pf_kind;
static std::string g_pf_gauge = "135";
static bool g_pf_etext = false;   // draw the stock's own edge print
static double g_pf_margin = 3.0, g_pf_fx = 0.0, g_pf_fy = 0.0;
static double g_pf_paper[3] = {0.930, 0.923, 0.900};
static double g_pf_film[3]  = {0.965, 0.700, 0.520};
// The light box's view of the same rebate, and the ink printed on it.
static double g_pf_film_light[3] = {0.975, 0.960, 0.940};
static double g_pf_film_ink[3]   = {0.055, 0.050, 0.046};

// D50 (CIE daylight 5000 K) from the S0/S1/S2 basis in `spectral_data.hpp`.
static void pf_d50(double out[spectral_grid::kCount]) {
    const double x = 0.34567, y = 0.35850;
    const double M = 0.0241 + 0.2562 * x - 0.7341 * y;
    const double M1 = (-1.3515 - 1.7703 * x + 5.9114 * y) / M;
    const double M2 = (0.0300 - 31.4424 * x + 30.0717 * y) / M;
    for (int i = 0; i < spectral_grid::kCount; ++i)
        out[i] = std::max(0.0, double(spectral_grid::kS0[i]) +
                                   M1 * double(spectral_grid::kS1[i]) +
                                   M2 * double(spectral_grid::kS2[i]));
}

// A density spectrum seen through D50, as display sRGB. Normalised by the
// illuminant's own Y so a density of zero is white.
// A density spectrum -> linear sRGB (D50, Bradford). Common to the print-side
// and the light-box colour below.
static bool pf_linear_display(const std::vector<float> &density, double lin[3]) {
    if (density.size() < size_t(spectral_grid::kCount)) return false;
    double spd[spectral_grid::kCount];
    pf_d50(spd);
    double X = 0, Y = 0, Z = 0, Yn = 0;
    for (int i = 0; i < spectral_grid::kCount; ++i) {
        const double t = std::pow(10.0, -double(std::max(0.0f, density[size_t(i)])));
        const double I = spd[i];
        X += I * t * double(spectral_grid::kXBar[i]);
        Y += I * t * double(spectral_grid::kYBar[i]);
        Z += I * t * double(spectral_grid::kZBar[i]);
        Yn += I * double(spectral_grid::kYBar[i]);
    }
    if (!(Yn > 0.0)) return false;
    const double x = X / Yn, y = Y / Yn, z = Z / Yn;
    // XYZ(D50) -> linear sRGB, Bradford adapted.
    lin[0] =  3.1338561 * x - 1.6168667 * y - 0.4906146 * z;
    lin[1] = -0.9787684 * x + 1.9161415 * y + 0.0334540 * z;
    lin[2] =  0.0719453 * x - 0.2289914 * y + 1.4052427 * z;
    return true;
}

static double pf_encode(double v) {
    v = v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    return v <= 0.0031308 ? 12.92 * v : 1.055 * std::pow(v, 1.0 / 2.4) - 0.055;
}

static void pf_encode3(const double lin[3], double out[3]) {
    out[0] = pf_encode(lin[0]); out[1] = pf_encode(lin[1]); out[2] = pf_encode(lin[2]);
}

static void pf_density_to_display(const std::vector<float> &density, double out[3]) {
    double lin[3];
    if (!pf_linear_display(density, lin)) return;
    pf_encode3(lin, out);
}

// **The light box, and one gain for everything on the strip.** fotufilm's
// `PrintFrame` normalises the rebate's clear base so its brightest channel
// reaches `lightBoxBaseLevel` (0.9), and multiplies **every mark on the strip by
// that same number** (`0.9 / max(clear)`), which is why the letters stay dark
// against a pale base. Normalising each colour by its own maximum instead made
// the inscription come out as a lighter peach -- measured before this: the text
// was (220, 150, 110)-ish on a (248, 193, 155) base.
static void pf_density_pair_to_lightbox(const std::vector<float> &base_density,
                                        const std::vector<float> &ink_density, double level,
                                        double out_base[3], double out_ink[3]) {
    double lb[3], li[3];
    if (!pf_linear_display(base_density, lb) || !pf_linear_display(ink_density, li)) return;
    const double m = std::max(lb[0], std::max(lb[1], lb[2]));
    const double k = (level > 0.0 && m > 1e-9) ? level / m : 1.0;
    double ob[3] = {lb[0] * k, lb[1] * k, lb[2] * k};
    double oi[3] = {li[0] * k, li[1] * k, li[2] * k};
    pf_encode3(ob, out_base);
    pf_encode3(oi, out_ink);
}

static void pf_paper_from(const paper::Stock &sheet) {
    std::vector<float> density(size_t(spectral_grid::kCount), 0.0f);
    for (int c = 0; c < 3; ++c) {
        const std::vector<float> &dye = sheet.spectra.dyes[size_t(c)];
        for (int i = 0; i < spectral_grid::kCount && i < int(dye.size()); ++i)
            density[size_t(i)] += float(sheet.curves[size_t(c)].d_min) * dye[size_t(i)];
    }
    pf_density_to_display(density, g_pf_paper);
}

static void pf_film_from(const film::Spectra &stock) {
    const int n = int(spectral_grid::kCount);
    // **两个颜色，都是这支胶片自己的密度。** 负片的片边是 dMin（片基 + 灰雾），
    // 反转片是 dMax（未曝光的片边显影到最大密度）；印字取 0.72 档（官方
    // `edgeDensities`）。印出来的那张（Carrier / Emulsion）要不归一化的值，
    // 光箱下的片边（Film Border）要归一化到 0.9。
    std::vector<float> base_density(size_t(n), 0.0f), ink_density(size_t(n), 0.0f);
    bool built = true;
    for (int c = 0; c < 3; ++c) {
        const std::vector<float> &dye = stock.dye[size_t(c)];
        if (int(dye.size()) < n) { built = false; break; }
        const double dmin = double(stock.curves[size_t(c)].d_min);
        const double dmax = double(stock.curves[size_t(c)].d_max());
        const double dbase = stock.reversal ? dmax : dmin;
        // `edgeDensities`: a negative's letters are 0.72 of the range denser
        // than its rebate, a transparency's 0.08 -- on the dark dMax edge those
        // read as faint light marks, which is what a real reversal strip has.
        const double dink = dmin + (stock.reversal ? 0.08 : 0.72) * (dmax - dmin);
        for (int i = 0; i < n; ++i) {
            base_density[size_t(i)] += float(dbase * double(dye[size_t(i)]));
            ink_density[size_t(i)] += float(dink * double(dye[size_t(i)]));
        }
    }
    if (!built) {
        // A measured stock with no dye curves: fall back to its own dMin.
        if (!stock.has_minimum_density) return;
        base_density = stock.minimum_density;
        ink_density = stock.minimum_density;
    }
    pf_density_to_display(base_density, g_pf_film);
    if (stock.reversal) {
        pf_density_to_display(base_density, g_pf_film_light);
        pf_density_to_display(ink_density, g_pf_film_ink);
    } else {
        pf_density_pair_to_lightbox(base_density, ink_density, 0.9, g_pf_film_light, g_pf_film_ink);
    }
}

// Film Border / Film Border Positive: the rebate draws 135 and the 120 frames,
// `print_frame` draws the rest (Super 35, 16 mm, Super 8, 4 x 5, Instax).
static bool pf_draws_film() {
    if (!print_frame::is_film_kind(print_frame::kind_from_id(g_pf_kind))) return false;
    return !print_frame::film_gauge_uses_rebate(g_pf_gauge);
}
static void parse_curves(const std::string &text, spk_adjust::ViewAdjust &out);
/** The 25 numbers `nativeSetViewAdjust` takes, in the same order and units. */
static void parse_view(const std::string &text, spk_adjust::ViewAdjust &out) {
    // `view:` and `curves:` are separate verbs and may arrive in either order;
    // resetting the whole struct here would throw the other one away.
    float kept[5][256];
    std::memcpy(kept, out.curve, sizeof kept);
    const bool hadCurves = out.curves;
    out = spk_adjust::ViewAdjust{};
    if (hadCurves) {
        out.curves = true;
        std::memcpy(out.curve, kept, sizeof kept);
    }
    double v[66] = {0};
    int n = 0;
    std::size_t at = 0;
    while (at <= text.size() && n < 66) {
        const std::size_t comma = text.find(',', at);
        const std::string one = text.substr(at, comma == std::string::npos
                                                   ? std::string::npos : comma - at);
        v[n++] = one.empty() ? 0.0 : std::atof(one.c_str());
        if (comma == std::string::npos) break;
        at = comma + 1;
    }
    out.vibrance = n >= 25 ? v[24] : 0.0;
    out.temperature = v[0];
    out.tint = v[1];
    out.exposure = v[2];
    out.contrast = v[3];
    out.brightness = v[4];
    out.saturation = v[5];
    out.highlights = v[6];
    out.shadows = v[7];
    out.black_point = v[8];
    out.white_point = v[9];
    out.vignette = v[10];
    out.vignette_mid = v[11] > 0.0 ? v[11] : 50.0;
    // 25..52: the eight-band HSL and the detail controls, appended after the
    // original 25 so an older caller's `view:` still means what it meant.
    if (n >= 49) {
        for (int i = 0; i < 8; ++i) {
            out.hsl_hue[i] = v[25 + i];
            out.hsl_sat[i] = v[33 + i];
            out.hsl_lum[i] = v[41 + i];
        }
    }
    if (n >= 53) {
        out.sharpen = v[49];
        out.sharpen_radius = v[50] > 0.0 ? v[50] : 1.0;
        out.sharpen_mask = v[51];
        out.texture = v[52];
    }
    // 53..65: the dodge and burn spots, appended exactly as the engine reads them.
    if (n >= 66) {
        const int count = int(v[53] < 0.0 ? 0.0 : (v[53] > 3.0 ? 3.0 : v[53]));
        out.spot_count = count;
        for (int i = 0; i < count; ++i) {
            out.spot[i][0] = v[54 + i];
            out.spot[i][1] = v[57 + i];
            out.spot[i][2] = v[60 + i];
            out.spot[i][3] = v[63 + i];
        }
    }
    if (n >= 24) {
        constexpr double kPi = 3.14159265358979323846;
        for (int z = 0; z < 4; ++z) {
            const double hue = v[12 + z * 3] * kPi / 180.0;
            const double sat = v[13 + z * 3] * 0.25;
            for (int c = 0; c < 3; ++c)
                out.cb[z][c] = std::cos(hue - 2.0 * kPi * c / 3.0) * sat;
            out.cb_lum[z] = v[14 + z * 3] * 0.25;
            if (std::fabs(out.cb[z][0]) + std::fabs(out.cb[z][1]) +
                std::fabs(out.cb[z][2]) + std::fabs(out.cb_lum[z]) > 1e-9)
                out.balance = true;
        }
    }
    out.normalise();
}

/**
 * Five channels x eight control points, x < 0 meaning "unused", built with the
 * engine's own monotone cubic -- the same block `nativeSetViewCurves` runs, so a
 * curve drawn in the app is the curve the kernel would have drawn.
 */
static void parse_curves(const std::string &text, spk_adjust::ViewAdjust &out) {
    double v[80] = {0};
    int n = 0;
    std::size_t at = 0;
    while (at <= text.size() && n < 80) {
        const std::size_t comma = text.find(',', at);
        const std::string one = text.substr(at, comma == std::string::npos
                                                   ? std::string::npos : comma - at);
        v[n++] = one.empty() ? 0.0 : std::atof(one.c_str());
        if (comma == std::string::npos) break;
        at = comma + 1;
    }
    for (int ch = 0; ch < 5; ++ch) {
        double xs[8], ys[8];
        int m = 0;
        for (int k = 0; k < 8; ++k) {
            const double x = v[(ch * 8 + k) * 2], y = v[(ch * 8 + k) * 2 + 1];
            if (x < 0.0) continue;
            xs[m] = x;
            ys[m] = y;
            ++m;
        }
        if (m < 2) {
            for (int i = 0; i < 256; ++i) out.curve[ch][i] = float(i / 255.0);
            continue;
        }
        spk_adjust::detail::build_curve(out.curve[ch], xs, ys, m);
    }
    bool any = false;
    for (int ch = 0; ch < 5; ++ch)
        for (int i = 0; i < 256; ++i)
            if (std::fabs(double(out.curve[ch][i]) - i / 255.0) > 1e-3) any = true;
    out.curves = any;
    out.normalise();
}



static bool parse_geometry(const std::string &text, GeometrySlot &out) {
    double v[5] = {0, 0, 1, 1, 0};
    int t = 0, f = 0;
    const int got = std::sscanf(text.c_str(), "%lf|%lf|%lf|%lf|%lf|%d|%d",
                                &v[0], &v[1], &v[2], &v[3], &v[4], &t, &f);
    if (got < 5) return false;
    out.x = std::min(std::max(v[0], 0.0), 1.0);
    out.y = std::min(std::max(v[1], 0.0), 1.0);
    out.w = std::min(std::max(v[2], 0.001), 1.0 - out.x);
    out.h = std::min(std::max(v[3], 0.001), 1.0 - out.y);
    out.rotation = std::min(std::max(v[4], -45.0), 45.0);
    out.turns = ((t % 4) + 4) % 4;
    out.flips = f & 3;
    out.active = !(out.turns == 0 && out.flips == 0 && std::abs(out.rotation) < 1e-4
                   && out.w >= 0.9999 && out.h >= 0.9999 && out.x <= 1e-4 && out.y <= 1e-4);
    return true;
}

/// The cropped frame, before any preview reduction: `output_size` in the engine.
void geometry_frame_size(const GeometrySlot &g, uint32_t in_w, uint32_t in_h,
                         uint32_t &out_w, uint32_t &out_h, uint32_t &crop_w, uint32_t &crop_h) {
    uint32_t oh = uint32_t(std::lround(g.h * double(in_h)));
    uint32_t ow = uint32_t(std::lround(g.w * double(in_w)));
    if (g.turns % 2 == 1) std::swap(oh, ow);
    oh = std::max(oh, 1u);
    ow = std::max(ow, 1u);
    out_w = ow;
    out_h = oh;
    crop_w = g.turns % 2 == 0 ? ow : oh;      // the crop's own frame
    crop_h = g.turns % 2 == 0 ? oh : ow;
}

/// The map's six constants plus the rotation's cosine and sine.
void geometry_consts(const GeometrySlot &g, uint32_t in_w, uint32_t in_h,
                     uint32_t crop_w, uint32_t crop_h, uint32_t out_w, uint32_t out_h,
                     float c[8]) {
    const double a = g.rotation * 3.14159265358979323846 / 180.0;
    const double cos_a = std::cos(a), sin_a = std::sin(a);
    // A turned frame is zoomed until the picture covers it, or the kernel
    // clamps at the edges and four triangular smears show where the frame used
    // to end. Same factor as the engine's and the app's canvas preview.
    double cover = 1.0;
    if (std::abs(sin_a) > 1e-9) {
        const double W = double(std::max(out_w, 1u)), H = double(std::max(out_h, 1u));
        const double c = std::abs(cos_a), s = std::abs(sin_a);
        cover = std::max(c + s * H / W, c + s * W / H);
    }
    c[0] = float(g.w * double(in_w) / double(std::max(crop_w, 1u)) / cover);
    c[1] = float(g.h * double(in_h) / double(std::max(crop_h, 1u)) / cover);
    c[2] = float(g.w * double(in_w) / 2.0 / cover);
    c[3] = float(g.h * double(in_h) / 2.0 / cover);
    c[4] = float((g.x + g.w / 2.0) * double(in_w) - 0.5);
    c[5] = float((g.y + g.h / 2.0) * double(in_h) - 0.5);
    c[6] = float(cos_a);
    c[7] = float(sin_a);
}

/// One pass from the source scene to the cropped, turned frame.
void geometry_resample(const float *src, uint32_t sw, uint32_t sh,
                       uint32_t out_w, uint32_t out_h, const float c[8],
                       uint32_t turns, uint32_t flips, std::vector<float> &dst) {
    dst.assign(size_t(out_w) * size_t(out_h) * 4, 0.0f);
    const float w1 = float(sw) - 1.0f, h1 = float(sh) - 1.0f;
    for (uint32_t y = 0; y < out_h; ++y) {
        for (uint32_t x = 0; x < out_w; ++x) {
            float u = float(x) + 0.5f, v = float(y) + 0.5f;
            if ((flips & 1u) != 0u) u = float(out_w) - u;
            if ((flips & 2u) != 0u) v = float(out_h) - v;
            float t;
            switch (turns) {
                case 1u: t = u; u = v; v = float(out_w) - t; break;
                case 2u: u = float(out_w) - u; v = float(out_h) - v; break;
                case 3u: t = u; u = float(out_h) - v; v = t; break;
                default: break;
            }
            const float px = c[0] * u - c[2];
            const float py = c[1] * v - c[3];
            const float fx = c[4] + (px * c[6] - py * c[7]);
            const float fy = c[5] + (px * c[7] + py * c[6]);
            float x0f, y0f, tx, ty;
            if (fx <= 0.0f) { x0f = 0.0f; tx = 0.0f; }
            else if (fx >= w1) { x0f = w1; tx = 0.0f; }
            else { x0f = std::floor(fx); tx = std::min(std::max(fx - x0f, 0.0f), 1.0f); }
            if (fy <= 0.0f) { y0f = 0.0f; ty = 0.0f; }
            else if (fy >= h1) { y0f = h1; ty = 0.0f; }
            else { y0f = std::floor(fy); ty = std::min(std::max(fy - y0f, 0.0f), 1.0f); }
            const uint32_t x0 = uint32_t(x0f), y0 = uint32_t(y0f);
            const uint32_t x1 = std::min(x0 + 1u, sw - 1u), y1 = std::min(y0 + 1u, sh - 1u);
            const size_t o = (size_t(y) * size_t(out_w) + size_t(x)) * 4;
            for (int ch = 0; ch < 3; ++ch) {
                const float a = src[(size_t(y0) * sw + x0) * 4 + size_t(ch)];
                const float b = src[(size_t(y0) * sw + x1) * 4 + size_t(ch)];
                const float d = src[(size_t(y1) * sw + x0) * 4 + size_t(ch)];
                const float e = src[(size_t(y1) * sw + x1) * 4 + size_t(ch)];
                const float top = a * (1.0f - tx) + b * tx;
                const float bot = d * (1.0f - tx) + e * tx;
                dst[o + size_t(ch)] = top * (1.0f - ty) + bot * ty;
            }
            dst[o + 3] = 1.0f;
        }
    }
}

// --- re-deriving the pixel-spaced slots for another frame size --------------
//
// A packed configuration holds radii in pixels, folded from the film's millimetre figures
// at the size it was baked for. Developing the same film at another size means folding them
// again -- with the same functions, in the same order, so the slots land on the same bytes
// the engine's own initializer would have produced.

float sensor_short_side_mm(const ffc_capture &c) {
    const double width = c.stored_width, height = c.stored_height;
    if (width > 0 && height > 0 && c.focal_plane_x_resolution > 0 && c.focal_plane_y_resolution > 0) {
        double mm_per_unit = 0.0;
        switch (c.focal_plane_unit) {
            case 2: mm_per_unit = 25.4; break;
            case 3: mm_per_unit = 10.0; break;
            case 4: mm_per_unit = 1.0; break;
            case 5: mm_per_unit = 0.001; break;
            default: break;
        }
        if (mm_per_unit > 0) {
            const double w = width / c.focal_plane_x_resolution * mm_per_unit;
            const double h = height / c.focal_plane_y_resolution * mm_per_unit;
            const float s = float(std::min(w, h));
            if (s > 1.0f && s < 80.0f) return s;
        }
    }
    if (width > 0 && height > 0 && c.focal_length > 0 && c.focal_length_35mm > 0) {
        const double crop = double(c.focal_length_35mm) / double(c.focal_length);
        if (crop > 0) {
            const double diagonal = 43.266615 / crop;
            const double aspect = std::max(width, height) / std::min(width, height);
            const double s = diagonal / std::sqrt(aspect * aspect + 1.0);
            if (s > 1.0f && s < 80.0f) return float(s);
        }
    }
    return 0.0f;
}

bool resize_config(std::vector<float> &config, const physics::FramePhysics &ph,
                   int baked_width, int baked_height, int target_width, int target_height,
                   float sensor_short_mm) {
    if (!ph.valid) return false;
    const float baked_short = float(std::min(baked_width, baked_height));
    if (!(baked_short > 0.0f) || !(ph.px_per_mm > 0.0f)) return false;
    // **The gauge belongs to the photograph, not to the skeleton.**
    //
    // `pxPerMM` is the frame's short side over the *film* it is developed on, and every
    // millimetre-sized structure -- the emulsion's blur, halation, the couplers, the grain --
    // is multiplied by it. The skeletons were baked from a photograph the engine could not
    // measure, so they carry a 16 mm gauge (7.4 mm short side). Developing a 4/3 capture
    // against that scales all of those radii by 24 / 7.4 = 3.24: the picture comes back soft
    // and the grain reads as noise. Measured on one Panasonic RW2 against fotufilm's own web
    // demo -- high-frequency detail 0.75/255 against its 2.58; with the frame and the meter
    // both right, 2.58 against 2.58.
    float millimetres = baked_short / ph.px_per_mm;
    if (sensor_short_mm > 0.0f) millimetres = sensor_short_mm;
    const float px_per_mm = float(std::min(target_width, target_height)) / millimetres;

    for (int i = 0; i < 3; ++i) {
        const float sigma = ph.emulsion_diffusion_mm[i] * px_per_mm;
        config[size_t(FOTUFILM_CONFIG_MTF_SIGMA + i)] = sigma;
        config[size_t(FOTUFILM_CONFIG_MTF_RADIUS + i)] = float(physics::gaussian_radius(sigma));
        const float secondary = ph.emulsion_diffusion_secondary_mm[i] * px_per_mm;
        config[size_t(FOTUFILM_CONFIG_MTF_SECONDARY_SIGMA + i)] = secondary;
        config[size_t(FOTUFILM_CONFIG_MTF_SECONDARY_RADIUS + i)] =
            float(physics::gaussian_radius(secondary));
    }
    const float luma = ph.luma_diffusion_mm * px_per_mm;
    config[size_t(FOTUFILM_CONFIG_MTF_LUMA_SIGMA)] = luma;
    config[size_t(FOTUFILM_CONFIG_MTF_LUMA_RADIUS)] = float(physics::gaussian_radius(luma));

    for (int i = 0; i < 3; ++i) {
        config[size_t(FOTUFILM_CONFIG_HALATION_RADIUS + i)] =
            float(physics::halation_box_radius(ph.halation_sigma_mm[i] * px_per_mm));
        config[size_t(FOTUFILM_CONFIG_HALATION_RING_RADIUS + i)] =
            std::max(ph.halation_ring_mm[i] * px_per_mm, 0.0f);
    }

    const float coupler = ph.coupler_diffusion_mm * px_per_mm;
    config[size_t(FOTUFILM_CONFIG_COUPLER_SIGMA)] = coupler;
    config[size_t(FOTUFILM_CONFIG_COUPLER_RADIUS)] = float(physics::gaussian_radius(coupler));

    const float reference = ph.adjacency_radius_mm * px_per_mm;
    const float adjacency = reference * (ph.adjacency_screened
        ? physics::kScreenedPrimarySigma : 1.0f);
    const float adjacency_secondary = ph.adjacency_screened
        ? reference * physics::kScreenedSecondarySigma : 0.0f;
    config[size_t(FOTUFILM_CONFIG_ADJACENCY_SIGMA)] = adjacency;
    config[size_t(FOTUFILM_CONFIG_ADJACENCY_RADIUS)] = float(physics::gaussian_radius(adjacency));
    config[size_t(FOTUFILM_CONFIG_ADJACENCY_SECONDARY_SIGMA)] = adjacency_secondary;
    config[size_t(FOTUFILM_CONFIG_ADJACENCY_SECONDARY_RADIUS)] =
        float(physics::gaussian_radius(adjacency_secondary));

    // The enlarger's own blur lands on the print, not on the film (FilmEngine.swift:1122). With no
    // enlarger blur the sigma still rests on the floor, but the radius stays at zero because the
    // stage never runs -- so the pair is not simply "sigma -> radius".
    {
        const float print_sigma = std::max(ph.enlarger_blur_mm * px_per_mm,
                                           physics::kGrainSigmaFloorPixels);
        config[8710] = print_sigma;
        config[8711] = ph.enlarger_blur_mm > 0.0f
            ? float(physics::gaussian_radius(print_sigma)) : 0.0f;
        // The print radius feeds the sharpening amount too; the engine keeps them in step.
        if (ph.enlarger_blur_mm > 0.0f) config[8729] = config[8729];
    }

    // The enlarger's sampled stage cannot carry its own variance at this pixel spacing; the
    // shortfall rides the grain's spread arm, as it does in the initializer.
    float fold = 0.0f, keep = 1.0f;
    if (ph.enlarger_blur_mm > 0.0f) {
        const float enlarger_sigma = ph.enlarger_blur_mm * px_per_mm;
        if (enlarger_sigma > 0.0f) {
            const float shortfall = enlarger_sigma * enlarger_sigma
                - physics::sampled_gaussian_variance(enlarger_sigma);
            fold = std::sqrt(std::max(shortfall, 0.0f));
        }
        keep = fold > 0.0f
            ? std::min(std::max(ph.enlarger_scan_sharpening, 0.0f), 1.0f) : 1.0f;
    }

    // A silver emulsion's clump is the Gaussian that stands in for its Boolean field, and the
    // stand-in is solved for on this lattice: the field's own covariance is read at the pixel
    // spacing, so it moves when the frame does. A dye cloud is a millimetre figure and does not.
    float clump_mm[3], mottle_mm[3];
    for (int i = 0; i < 3; ++i) {
        if (ph.is_silver) {
            const float clump = ph.grain_size_mm / 2.0f * ph.grain_layer_size_ratio[i];
            const float disc = 2.0f * clump * px_per_mm;
            clump_mm[i] = physics::silver_equivalent_clump_pixels(
                disc, ph.grain_anchor_coverage, fold, keep) / px_per_mm;
            const float mottle = clump * ph.mottle_size_ratio;
            const float mottle_disc = 2.0f * mottle * px_per_mm;
            mottle_mm[i] = physics::silver_equivalent_clump_pixels(
                mottle_disc, ph.grain_anchor_coverage, fold, keep) / px_per_mm;
        } else {
            clump_mm[i] = ph.rendered_clump_mm[i];
            mottle_mm[i] = ph.rendered_mottle_mm[i];
        }
    }
    const float share = g_mottle_share >= 0.0f
        ? std::min(std::max(g_mottle_share, 0.0f), 0.9f) : ph.mottle_share;
    float grain_sigma = 0.0f, mottle_sigma = 0.0f;
    for (int i = 0; i < 3; ++i) {
        const float grain = physics::discrete_grain_sigma(clump_mm[i] * px_per_mm, fold, keep);
        config[size_t(FOTUFILM_CONFIG_GRAIN_SIGMA_LAYER + i)] = grain;
        if (grain > grain_sigma) grain_sigma = grain;
        const float mottle = physics::discrete_grain_sigma(mottle_mm[i] * px_per_mm, fold, keep);
        config[size_t(FOTUFILM_CONFIG_MOTTLE_SIGMA_LAYER + i)] = mottle;
        if (mottle > mottle_sigma) mottle_sigma = mottle;
    }
    if (!(grain_sigma > 0.0f)) grain_sigma = physics::kGrainSigmaFloorPixels;
    if (!(mottle_sigma > 0.0f)) mottle_sigma = physics::kGrainSigmaFloorPixels;
    config[size_t(FOTUFILM_CONFIG_GRAIN_SIGMA)] = grain_sigma;
    config[size_t(FOTUFILM_CONFIG_GRAIN_RADIUS)] = float(physics::gaussian_radius(grain_sigma));
    config[size_t(FOTUFILM_CONFIG_MOTTLE_SIGMA)] = mottle_sigma;
    config[size_t(FOTUFILM_CONFIG_MOTTLE_RADIUS)] = float(physics::gaussian_radius(mottle_sigma));

    // The published granularity is read through a fixed 48 um aperture. Its amplitude does not
    // move with the frame size, nor does the response the aperture keeps of a millimetre clump,
    // so the slot is the two of them and the pixel spacing between them -- in the initializer's
    // own order. A silver stand-in moves with the lattice, so its response is read again.
    for (int i = 0; i < 3; ++i) {
        const float response = ph.is_silver
            ? physics::granularity_aperture_response(clump_mm[i])
            : ph.grain_aperture_response[i];
        const float aperture = std::sqrt(3.14159265f) * 0.024f * px_per_mm / response;
        // A dump from before the split stored the amplitude with the baked aperture already
        // folded in; take it back out there, so both dumps mean the same thing.
        float amplitude = ph.grain_amplitude[i];
        if (!ph.grain_amplitude_is_raw) {
            const float baked = std::sqrt(3.14159265f) * 0.024f * ph.px_per_mm
                / ph.grain_aperture_response[i];
            amplitude = baked > 0.0f ? amplitude / baked : 0.0f;
        }
        config[size_t(FOTUFILM_CONFIG_GRAIN + i)] = amplitude * aperture
            * std::sqrt(1.0f - share);
        // The coarse field is the same published amplitude read through its own clump's
        // aperture: the split moves variance between the two, it does not add any.
        const float coarse_aperture = std::sqrt(3.14159265f) * 0.024f * px_per_mm
            / physics::granularity_aperture_response(mottle_mm[i]);
        config[size_t(FOTUFILM_CONFIG_MOTTLE + i)] = amplitude * coarse_aperture
            * std::sqrt(share);
    }

    const float clumps_per_pixel = ph.grain_clumps_per_mm2 / (px_per_mm * px_per_mm);
    config[size_t(FOTUFILM_CONFIG_GRAIN_LAMBDA)] = clumps_per_pixel;
    config[size_t(FOTUFILM_CONFIG_MOTTLE_LAMBDA)] =
        clumps_per_pixel / (ph.mottle_size_ratio * ph.mottle_size_ratio);

    if (g_grade_space >= 0) config[size_t(FOTUFILM_CONFIG_GRADE_SPACE)] = g_grade_space ? 1.0f : 0.0f;
    config[size_t(FOTUFILM_CONFIG_FRAME_WIDTH)] = float(target_width);
    config[size_t(FOTUFILM_CONFIG_FRAME_HEIGHT)] = float(target_height);
    return true;
}

}  // namespace

// `FilmStock.flare` is the share of the light the emulsion's own veil hands back. The pack keeps
// it in the JSON, not in stocks.bin, so it travels beside the binaries in this little table.
static float flare_of(const std::string &id, const std::string &resources = std::string()) {
    static std::map<std::string, float> table;
    static bool loaded = false;
    if (!loaded) {
        loaded = true;
        const std::string dirs[] = {resources, resources + "/assets/fotufilm", "", "./", "assets/fotufilm/",
                                    "/data/data/com.termux/files/home/spk-debug/cpphost/"};
        for (const std::string &dir : dirs) {
            if (dir.empty() && &dir != &dirs[2]) continue;
            std::FILE *f = std::fopen((dir + "/film_flare.txt").c_str(), "r");
            if (!f) continue;
            char line[256];
            while (std::fgets(line, sizeof line, f)) {
                char name[128]; float v = 0.008f;
                if (std::sscanf(line, "%127s %f", name, &v) == 2) table[name] = v;
            }
            std::fclose(f);
            break;
        }
    }
    const auto it = table.find(id);
    return it == table.end() ? 0.008f : it->second;
}

// --- the laid tiles, on disk -------------------------------------------------
//
// A stock's bank is 13.5 MB and takes fifteen seconds to lay with eight threads. A session that
// switches between a few stocks pays that again every time it comes back, so the last few are
// kept beside the table cache. The format is the bank verbatim, with the levels' mean light in
// front: the kernel reads the summed-area tables, but the configuration block needs the means,
// and they cannot be recovered from the tables (their last corner is zero by construction).
namespace tilecache {

constexpr uint32_t kMagic = 0x31544C46u;   // "FLT1"
constexpr size_t kEntries = size_t(grain::kTileSide + 1) * size_t(grain::kTileSide + 1);
constexpr size_t kBank = kEntries * size_t(grain::kTileLevels) * 3;

inline std::string path_for(const std::string &dir, const std::string &tag, long stamp) {
    return dir + "/film_tiles_" + tag + "_" + std::to_string(stamp) + ".f32";
}

inline bool load(const std::string &path, std::vector<float> &bank,
                 std::vector<grain::TileLevel> *levels) {
    std::FILE *f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    uint32_t head[3] = {0u, 0u, 0u};
    bool ok = std::fread(head, sizeof(uint32_t), 3, f) == 3 && head[0] == kMagic
              && head[1] == 3u && head[2] == uint32_t(grain::kTileLevels);
    std::vector<float> means;
    if (ok) {
        means.resize(size_t(3) * size_t(grain::kTileLevels));
        ok = std::fread(means.data(), sizeof(float), means.size(), f) == means.size();
    }
    if (ok) {
        bank.resize(kBank);
        ok = std::fread(bank.data(), sizeof(float), bank.size(), f) == bank.size();
    }
    std::fclose(f);
    if (!ok) return false;
    for (int r = 0; r < 3; ++r) {
        levels[r].clear();
        bool any = false;
        for (int k = 0; k < grain::kTileLevels; ++k)
            if (means[size_t(r) * size_t(grain::kTileLevels) + size_t(k)] > 0.0f) any = true;
        if (!any) continue;
        levels[r].assign(size_t(grain::kTileLevels), grain::TileLevel{});
        for (int k = 0; k < grain::kTileLevels; ++k) {
            const size_t at = (size_t(r) * size_t(grain::kTileLevels) + size_t(k)) * kEntries;
            levels[r][size_t(k)].sums.assign(bank.begin() + at, bank.begin() + at + kEntries);
            levels[r][size_t(k)].mean_light = means[size_t(r) * size_t(grain::kTileLevels) + size_t(k)];
        }
    }
    return true;
}

inline bool save(const std::string &path, const std::vector<float> &bank,
                 const std::vector<grain::TileLevel> *levels) {
    if (bank.size() != kBank) return false;
    std::FILE *f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    const uint32_t head[3] = {kMagic, 3u, uint32_t(grain::kTileLevels)};
    std::fwrite(head, sizeof(uint32_t), 3, f);
    std::vector<float> means(size_t(3) * size_t(grain::kTileLevels), 0.0f);
    for (int r = 0; r < 3; ++r)
        if (!levels[r].empty())
            for (int k = 0; k < grain::kTileLevels; ++k)
                means[size_t(r) * size_t(grain::kTileLevels) + size_t(k)] =
                    levels[r][size_t(k)].mean_light;
    std::fwrite(means.data(), sizeof(float), means.size(), f);
    std::fwrite(bank.data(), sizeof(float), bank.size(), f);
    const bool ok = std::fflush(f) == 0;
    std::fclose(f);
    return ok;
}

/// Keeps `keep` banks, oldest out.
inline void trim(const std::string &dir, int keep) {
    DIR *d = opendir(dir.c_str());
    if (!d) return;
    std::vector<std::pair<long, std::string>> found;
    while (dirent *e = readdir(d)) {
        const std::string name = e->d_name;
        if (name.rfind("film_tiles_", 0) != 0) continue;
        struct stat st {};
        if (stat((dir + "/" + name).c_str(), &st) != 0) continue;
        found.emplace_back(long(st.st_mtime), name);
    }
    closedir(d);
    if (int(found.size()) <= keep) return;
    std::sort(found.begin(), found.end());
    for (size_t i = 0; i + size_t(keep) < found.size(); ++i)
        std::remove((dir + "/" + found[i].second).c_str());
}

}  // namespace tilecache

/**
 * The strip, kept between calls.
 *
 * <p>Building it is a second full pass over the canvas -- `canvas_pass` measured
 * 215-256 ms at 2240x2068, the largest single cost in a fotufilm render after the
 * develop itself. It is a pure function of the frame, the canvas size and the
 * strip's own settings, and a slider release changes none of the three, so the
 * second render of a photograph should pay nothing for it. One entry: the app
 * renders one size at a time, in bursts.
 */
struct Strip {
    uint64_t key = 0;
    int width = 0, height = 0;
    std::vector<float> canvas;
    std::vector<uint8_t> gate, film, ink;
    long long used = 0;
};
struct StripCache {
    std::vector<Strip> strips;
    long long clock = 0;
};
static StripCache g_strip;

/** The strip rasterised for this key, or null. */
static Strip *strip_find(uint64_t key) {
    for (Strip &one : g_strip.strips) {
        if (one.key != key || one.canvas.empty()) continue;
        one.used = ++g_strip.clock;
        return &one;
    }
    return nullptr;
}

/**
 * Keeps a strip, evicting by age.
 *
 * <p>Three entries is the app's whole cycle -- 420 while the finger moves, 900
 * when it stops, 2048 as the canvas -- and 128 MB holds all three at a 2240x2068
 * canvas. Without them every slider release rebuilt the 2048 strip (218 ms) and
 * the 900 one (66 ms).
 */
static void strip_keep(Strip entry) {
    entry.used = ++g_strip.clock;
    g_strip.strips.push_back(std::move(entry));
    constexpr std::uint64_t kBudget = 128ull << 20;
    constexpr std::size_t kMost = 3;
    const auto bytes = [](const Strip &one) {
        return one.canvas.size() * sizeof(float) + one.gate.size() + one.film.size()
             + one.ink.size();
    };
    for (;;) {
        std::uint64_t total = 0;
        for (const Strip &one : g_strip.strips) total += bytes(one);
        if (g_strip.strips.size() <= kMost && total <= kBudget) return;
        std::size_t oldest = 0;
        for (std::size_t i = 1; i < g_strip.strips.size(); ++i)
            if (g_strip.strips[i].used < g_strip.strips[oldest].used) oldest = i;
        g_strip.strips.erase(g_strip.strips.begin() + std::ptrdiff_t(oldest));
        if (g_strip.strips.empty()) return;
    }
}

static double g_fh_t0 = 0.0;
static bool fh_time_on() {
    static const bool on = std::getenv("FH_TIME") != nullptr;
    return on;
}
/** One line per phase when FH_TIME is set; silent otherwise. */
static void fh_mark(const char *what) {
    if (!fh_time_on()) return;
    using namespace std::chrono;
    const double t = duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
    std::fprintf(stderr, "[fh] %-10s %8.1f ms\n", what, g_fh_t0 == 0.0 ? 0.0 : t - g_fh_t0);
    g_fh_t0 = t;
}

/** Modification time in seconds, for the scene cache's key. */
static long long file_mtime_seconds(const std::string &path) {
    struct stat st {};
    if (::stat(path.c_str(), &st) != 0) return 0;
    return (long long)st.st_mtime;
}

/**
 * The decoded photograph, kept between calls.
 *
 * <p>A shelf pass renders forty-seven stocks from one file, and every canvas
 * render opens it again: two seconds a time, for pixels that have not changed.
 * One entry is enough -- the app works on one photograph at a time -- and the
 * key is path + modification time + working edge, so a re-import or a different
 * tier decodes again. `fh_process` is called from one thread at a time (the app
 * serialises its renders), which is also what the grain-tile cache assumes.
 */
struct SceneCache {
    std::string key;
    uint32_t width = 0, height = 0;
    bool is_raw = false;
    float headroom = 1.0f;
    std::vector<float> rgba;
    // What produced this frame, apart from the tier. A JPEG is decoded at its
    // stored size whatever edge the caller asked for, so a second render that
    // only differs in `working_edge` need not open the file again -- `edge_free`
    // records that at decode time and this pair is how it is recognised.
    std::string path;
    long long mtime = 0;
    bool edge_free = false;
    // **More than one size.** The canvas renders at 2048, the settle frame at 900
    // and the drag frame at 420, and one slot meant each of them threw the other
    // two away: a six-megapixel file came back through the two-pass resample
    // (59-120 ms, measured) on the first frame of every size in the cycle.
    struct Reduced {
        uint32_t width = 0, height = 0;
        std::vector<float> rgba;
        long long used = 0;
    };
    std::vector<Reduced> reduced;
    long long reduced_clock = 0;
    // The file's own Exif record, beside the pixels it describes. The encoder
    // wants it at write time, and the only copy the decoder hands out is freed
    // with the image -- long before an export encodes anything. A second render
    // does not decode the file again, so it comes from here.
    std::vector<uint8_t> exif;
    // The photograph's own gauge, for the same reason: the capture it comes from
    // is only alive during the decode, and every millimetre-sized structure in
    // the configuration is scaled by it. A cache hit that started from 0 fell
    // back to 24 mm, so a medium-format frame would be developed at the wrong
    // gauge on its *second* render and at the right one on its first.
    float sensor_short_mm = 0.0f;
};
static SceneCache g_scene;


// --- a scene the phone can hold --------------------------------------------
//
// `ffc_decode` develops a JPEG at its stored size. A hundred megapixels is a
// 1.6 GB float frame before a single kernel runs, and the three planes the
// kernels take are another 1.2 GB on top -- more than an 8 GB phone has once
// the app, the preview session and the canvas bitmaps are in the same heap.
// The codecs' thumbnail entry point asks libjpeg for a reduced scale instead,
// so the frame never exists at full size.

/** Free memory as the kernel reports it; zero when unreadable. */
static uint64_t mem_available_bytes() {
    FILE *f = std::fopen("/proc/meminfo", "r");
    if (!f) return 0;
    char key[64] = {};
    unsigned long long value = 0;
    uint64_t out = 0;
    while (std::fscanf(f, "%63s %llu", key, &value) == 2) {
        if (std::strcmp(key, "MemAvailable:") == 0) { out = uint64_t(value) << 10; break; }
    }
    std::fclose(f);
    return out;
}

/**
 * What one pixel of the exact path costs, in bytes.
 *
 * <p>Sixteen for the decoded RGBA float frame and twelve for the three scene
 * planes the kernel reads, alive at the same time. Named because the log line
 * that reports the budget used to hard-code the old twenty, so a support call
 * that quoted it was off by forty percent -- the number the code enforces and
 * the number it prints have to be the same number.
 */
static constexpr uint64_t kSceneBytesPerPixel = 28;

/** How many pixels this chain may hold: sixteen bytes a pixel decoded, twelve more as planes. */
static uint64_t scene_budget_pixels() {
    // A diagnostic knob: the probes need to exercise the reduced path on a machine with
    // plenty of memory, and a support call may want to pin it.
    if (const char *forced = std::getenv("FH_BUDGET_MP")) {
        const double mp = std::atof(forced);
        if (mp > 0.0) return uint64_t(mp * 1e6);
    }
    const uint64_t avail = mem_available_bytes();
    const uint64_t bytes = avail ? avail * 45 / 100 : (2ull << 30);
    // At 20 a 101 MP JPEG was judged affordable on a 4.8 GB phone, which is 1.6 GB of float plus
    // 1.2 GB of planes.
    return std::max<uint64_t>(4000000ull, bytes / kSceneBytesPerPixel);
}

/** A JPEG's stored size from its start-of-frame header; false when the file is not one. */
static bool jpeg_dimensions(const char *path, uint32_t &w, uint32_t &h) {
    FILE *f = std::fopen(path, "rb");
    if (!f) return false;
    bool ok = false;
    if (std::fgetc(f) == 0xFF && std::fgetc(f) == 0xD8) {
        for (;;) {
            int c = std::fgetc(f);
            if (c != 0xFF) break;
            int marker = std::fgetc(f);
            while (marker == 0xFF) marker = std::fgetc(f);
            if (marker < 0 || marker == 0xD9 || marker == 0xDA) break;
            const int hi = std::fgetc(f), lo = std::fgetc(f);
            if (hi < 0 || lo < 0) break;
            const int len = (hi << 8) | lo;
            if (len < 2) break;
            // SOF0..SOF15, except the four that are not frame headers.
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8
                && marker != 0xCC && len >= 7) {
                std::fgetc(f);                       // sample precision
                const int hh = std::fgetc(f), hl = std::fgetc(f);
                const int wh = std::fgetc(f), wl = std::fgetc(f);
                if (hh < 0 || hl < 0 || wh < 0 || wl < 0) break;
                h = uint32_t((hh << 8) | hl);
                w = uint32_t((wh << 8) | wl);
                ok = w > 0 && h > 0;
                break;
            }
            if (std::fseek(f, len - 2, SEEK_CUR) != 0) break;
        }
    }
    std::fclose(f);
    return ok;
}

// Display P3 (D65) -> linear Rec.2020 (D65): the inverse of `to_display_p3_linear`, from the
// same ColorScience matrices. Row sums are 1, so a neutral stays neutral.
static const float kP3ToRec2020[9] = {
     0.753833034f,  0.198597369f,  0.047569597f,
     0.045743849f,  0.941777220f,  0.012478931f,
    -0.001210340f,  0.017601717f,  0.983608623f,
};

static inline float srgb_eotf(float v) {
    return v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f);
}

/** The file, developed at a size this phone can hold, into `image`. */
static bool thumbnail_scene(const char *path, uint32_t jw, uint32_t jh, int max_edge,
                            uint64_t budget_px, ffc_image &image) {
    const double k = std::sqrt(double(budget_px) / (double(jw) * double(jh)));
    uint32_t edge = uint32_t(std::max(1024.0, std::round(double(std::max(jw, jh)) * k)));
    if (max_edge > 0 && edge > uint32_t(max_edge)) edge = uint32_t(max_edge);
    ffc_picture pic{};
    char err[512] = {};
    if (ffc_thumbnail(path, edge, &pic, err, sizeof err) != 0 || !pic.rgba || !pic.width) {
        std::fprintf(stderr, "[fh] thumbnail failed: %s\n", err);
        return false;
    }
    const size_t px = size_t(pic.width) * size_t(pic.height);
    float *buf = static_cast<float *>(std::malloc(px * 4 * sizeof(float)));
    if (!buf) { ffc_picture_free(&pic); return false; }
    for (size_t i = 0; i < px; ++i) {
        const uint8_t *c = &pic.rgba[i * 4];
        const float r = srgb_eotf(c[0] / 255.0f);
        const float g = srgb_eotf(c[1] / 255.0f);
        const float b = srgb_eotf(c[2] / 255.0f);
        buf[i * 4 + 0] = kP3ToRec2020[0] * r + kP3ToRec2020[1] * g + kP3ToRec2020[2] * b;
        buf[i * 4 + 1] = kP3ToRec2020[3] * r + kP3ToRec2020[4] * g + kP3ToRec2020[5] * b;
        buf[i * 4 + 2] = kP3ToRec2020[6] * r + kP3ToRec2020[7] * g + kP3ToRec2020[8] * b;
        buf[i * 4 + 3] = 1.0f;
    }
    std::printf("big jpeg %ux%u (%.1f MP) -> %ux%u (%.1f MP) at %.2f GB budget (approximate)\n",
                jw, jh, double(jw) * double(jh) / 1e6,
                pic.width, pic.height, double(pic.width) * pic.height / 1e6,
                double(budget_px) * double(kSceneBytesPerPixel) / 1e9);
    std::memset(&image.capture, 0, sizeof image.capture);
    image.capture.stored_width = jw;
    image.capture.stored_height = jh;
    image.rgba = buf;
    image.width = pic.width;
    image.height = pic.height;
    image.is_raw = 0;
    image.content_headroom = 1.0f;
    ffc_picture_free(&pic);
    return true;
}


// --- a 16-bit linear TIFF --------------------------------------------------
//
// **The file to grade from.** A JPEG is eight bits of display codes: the whole
// top stop of the print lives in about ten of them, which is why "pull the
// highlights back" has nothing to pull. A linear 16-bit TIFF spends 32768 codes
// on the same stop, and the linear transfer means a tone curve in Lightroom
// moves detail rather than posterising it. The primaries stay Display P3 --
// the kernels deliver linear P3, and converting to sRGB would clip the
// saturated reds a film stock lives on.
//
// Uncompressed, one strip, little-endian: every reader accepts it.

static void tiff16(std::vector<uint8_t> &out, uint32_t at, uint16_t n) {
    out[at] = uint8_t(n); out[at + 1] = uint8_t(n >> 8);
}
static void tiff32(std::vector<uint8_t> &out, uint32_t at, uint32_t n) {
    for (int i = 0; i < 4; ++i) out[at + i] = uint8_t(n >> (8 * i));
}

// "make|model|datetime|shutter|aperture|iso|focal" -- the capture's own record,
// set by `--exif`. A TIFF has no APP1, so these become ordinary IFD0 tags.
static std::string g_exif_fields;

struct TiffEntry { uint16_t tag, type; uint32_t count, value; };

static bool write_tiff16_linear(const std::string &path, const float *p3, int w, int h,
                                const std::string &icc_path, std::string &error) {
    std::vector<uint8_t> icc;
    if (!icc_path.empty()) {
        if (std::FILE *f = std::fopen(icc_path.c_str(), "rb")) {
            std::fseek(f, 0, SEEK_END);
            long n = std::ftell(f);
            std::fseek(f, 0, SEEK_SET);
            if (n > 0) {
                icc.resize(size_t(n));
                if (std::fread(icc.data(), 1, size_t(n), f) != size_t(n)) icc.clear();
            }
            std::fclose(f);
        }
    }
    const size_t pixels = size_t(w) * size_t(h);
    std::vector<uint8_t> data(pixels * 6);
    for (size_t i = 0; i < pixels; ++i) {
        for (int c = 0; c < 3; ++c) {
            float v = p3[i * 3 + size_t(c)];
            v = v <= 0.f ? 0.f : (v >= 1.f ? 1.f : v);
            const uint32_t u = uint32_t(v * 65535.0f + 0.5f);
            data[i * 6 + size_t(c) * 2 + 0] = uint8_t(u & 0xFF);
            data[i * 6 + size_t(c) * 2 + 1] = uint8_t((u >> 8) & 0xFF);
        }
    }

    // --- the capture's own record -----------------------------------------
    //
    // A grading file that says nothing about where it came from is a scan with
    // the invoice torn off: Lightroom shows no camera, no date and no exposure,
    // so the frame cannot be filed beside the rest of the shoot. TIFF carries
    // no APP1, but these are the very tags EXIF uses, in IFD0 where every
    // reader looks.
    struct Meta { uint16_t tag, type; uint32_t count; std::vector<uint8_t> v; };
    std::vector<Meta> meta;
    if (!g_exif_fields.empty()) {
        std::vector<std::string> f;
        size_t cut = 0;
        while (cut <= g_exif_fields.size()) {
            const size_t end = g_exif_fields.find('|', cut);
            f.push_back(g_exif_fields.substr(cut, end == std::string::npos
                                                       ? std::string::npos : end - cut));
            if (end == std::string::npos) break;
            cut = end + 1;
        }
        while (f.size() < 7) f.push_back(std::string());
        auto put32 = [](std::vector<uint8_t> &v, size_t at, uint32_t x) {
            for (int i = 0; i < 4; ++i) v[at + size_t(i)] = uint8_t(x >> (8 * i));
        };
        auto text = [&](uint16_t tag, const std::string &v) {
            if (v.empty()) return;
            Meta m{tag, 2 /* ASCII */, uint32_t(v.size() + 1), std::vector<uint8_t>(v.begin(), v.end())};
            m.v.push_back(0);
            meta.push_back(m);
        };
        auto rational = [&](uint16_t tag, const std::string &v, long scale) {
            if (v.empty()) return;
            double x = 0.0;
            const size_t slash = v.find('/');
            if (slash != std::string::npos) {
                const double den = std::atof(v.substr(slash + 1).c_str());
                if (!(den > 0.0)) return;
                x = std::atof(v.substr(0, slash).c_str()) / den;
            } else {
                x = std::atof(v.c_str());
            }
            if (!(x > 0.0)) return;
            uint32_t num = uint32_t(x * double(scale) + 0.5), den = uint32_t(scale);
            uint32_t a = num, b = den;
            while (b) { const uint32_t t = a % b; a = b; b = t; }
            if (a > 1) { num /= a; den /= a; }
            Meta m{tag, 5 /* RATIONAL */, 1, std::vector<uint8_t>(8, 0)};
            put32(m.v, 0, num);
            put32(m.v, 4, den);
            meta.push_back(m);
        };
        text(271, f[0]);                    // Make
        text(272, f[1]);                    // Model
        text(306, f[2]);                    // DateTime
        rational(33434, f[3], 1000000L);    // ExposureTime
        rational(33437, f[4], 10L);         // FNumber
        if (!f[5].empty()) {
            const long iso = std::lround(std::atof(f[5].c_str()));
            if (iso > 0 && iso <= 65535L) {
                Meta m{34855, 3 /* SHORT */, 1, std::vector<uint8_t>(2, 0)};
                m.v[0] = uint8_t(uint16_t(iso) & 0xFF);
                m.v[1] = uint8_t((uint16_t(iso) >> 8) & 0xFF);
                meta.push_back(m);
            } else if (iso > 0) {
                Meta m{34855, 4 /* LONG */, 1, std::vector<uint8_t>(4, 0)};
                put32(m.v, 0, uint32_t(iso));
                meta.push_back(m);
            }
        }
        rational(37386, f[6], 10L);         // FocalLength
    }

    std::vector<TiffEntry> e;
    e.push_back({256, 4, 1, uint32_t(w)});                  // ImageWidth
    e.push_back({257, 4, 1, uint32_t(h)});                  // ImageLength
    e.push_back({258, 3, 3, 0});                            // BitsPerSample, patched
    e.push_back({259, 3, 1, 1});                            // Compression = none
    e.push_back({262, 3, 1, 2});                            // Photometric = RGB
    e.push_back({273, 4, 1, 0});                            // StripOffsets, patched
    e.push_back({277, 3, 1, 3});                            // SamplesPerPixel
    e.push_back({278, 4, 1, uint32_t(h)});                  // RowsPerStrip
    e.push_back({279, 4, 1, uint32_t(data.size())});        // StripByteCounts
    e.push_back({282, 5, 1, 0});                            // XResolution, patched
    e.push_back({283, 5, 1, 0});                            // YResolution, patched
    e.push_back({296, 3, 1, 2});                            // ResolutionUnit = inch
    const size_t icc_entry = icc.empty() ? size_t(-1) : e.size();
    if (icc_entry != size_t(-1)) e.push_back({34675, 7, uint32_t(icc.size()), 0});
    const size_t meta_first = e.size();
    for (const Meta &m : meta) e.push_back({m.tag, m.type, m.count, 0});

    const uint32_t ifd_at = 8;
    const uint32_t ifd_bytes = uint32_t(2 + 12 * e.size() + 4);
    uint32_t cursor = ifd_at + ifd_bytes;
    const uint32_t bps_at = cursor; cursor += 6;            // three SHORTs
    if (cursor & 1u) ++cursor;
    const uint32_t xres_at = cursor; cursor += 8;
    const uint32_t yres_at = cursor; cursor += 8;
    uint32_t icc_at = 0;
    if (icc_entry != size_t(-1)) { icc_at = cursor; cursor += uint32_t(icc.size()); }
    if (cursor & 1u) ++cursor;
    std::vector<uint32_t> meta_at(meta.size(), 0);
    for (size_t i = 0; i < meta.size(); ++i) {
        if (meta[i].v.size() <= 4) continue;          // fits in the entry itself
        meta_at[i] = cursor;
        cursor += uint32_t((meta[i].v.size() + 1) & ~size_t(1));
    }
    if (cursor & 1u) ++cursor;
    const uint32_t data_at = cursor;
    // An entry's value field is 4 bytes: the bytes themselves when they fit,
    // an offset into the value area when they do not.
    for (size_t i = 0; i < meta.size(); ++i) {
        uint32_t packed = 0;
        for (size_t b = 0; b < meta[i].v.size() && b < 4; ++b)
            packed |= uint32_t(meta[i].v[b]) << (8 * b);
        e[meta_first + i].value = meta[i].v.size() > 4 ? meta_at[i] : packed;
    }

    // The header and the pixels are written separately: at fifty megapixels the
    // strip is six hundred megabytes, and a second copy of it is the difference
    // between an export and an abort.
    std::vector<uint8_t> out(size_t(data_at), 0);
    out[0] = 'I'; out[1] = 'I'; tiff16(out, 2, 42); tiff32(out, 4, ifd_at);
    tiff16(out, ifd_at, uint16_t(e.size()));
    for (size_t i = 0; i < e.size(); ++i) {
        const uint32_t at = ifd_at + 2 + uint32_t(i) * 12;
        TiffEntry t = e[i];
        if (t.tag == 258) t.value = bps_at;
        if (t.tag == 273) t.value = data_at;
        if (t.tag == 282) t.value = xres_at;
        if (t.tag == 283) t.value = yres_at;
        if (icc_entry == i) t.value = icc_at;
        tiff16(out, at + 0, t.tag);
        tiff16(out, at + 2, t.type);
        tiff32(out, at + 4, t.count);
        tiff32(out, at + 8, t.value);
    }
    tiff32(out, ifd_at + 2 + uint32_t(e.size()) * 12, 0);
    tiff16(out, bps_at + 0, 16); tiff16(out, bps_at + 2, 16); tiff16(out, bps_at + 4, 16);
    // 72 dpi, as a rational: 72/1
    tiff32(out, xres_at + 0, 72); tiff32(out, xres_at + 4, 1);
    tiff32(out, yres_at + 0, 72); tiff32(out, yres_at + 4, 1);
    if (icc_entry != size_t(-1)) std::memcpy(&out[icc_at], icc.data(), icc.size());
    for (size_t i = 0; i < meta.size(); ++i)
        if (meta[i].v.size() > 4) std::memcpy(&out[meta_at[i]], meta[i].v.data(), meta[i].v.size());

    std::FILE *f = std::fopen(path.c_str(), "wb");
    if (!f) { error = "cannot create " + path; return false; }
    bool ok = std::fwrite(out.data(), 1, out.size(), f) == out.size();
    if (ok) ok = std::fwrite(data.data(), 1, data.size(), f) == data.size();
    std::fclose(f);
    if (!ok) { error = "short write to " + path; return false; }
    return true;
}


// --- the coupler matrix, its warp table, the fringe, the output levels ---------
//
// Four controls that all start from the *baked* configuration rather than from a
// stock field: the inhibition matrix is nine floats already in the layout, and
// the reach/self controls are operations on it. Recovering its factors beats
// carrying `couplerGeometry` beside `stocks.bin`.

struct CouplerFactors {
    float transmit[2] = {1.f, 1.f};   // one per gap, R–G then G–B
    float release[3] = {0.f, 0.f, 0.f};
    float self_retention = 0.f;
    bool ok = false;
};

static CouplerFactors coupler_factors(const float *m) {
    CouplerFactors f;
    if (!(m[3] > 0.f) || !(m[5] > 0.f)) return f;
    f.transmit[1] = m[6] / m[3];              // M[2][0] / M[1][0]
    f.transmit[0] = m[2] / m[5];              // M[0][2] / M[1][2]
    if (!(f.transmit[0] > 0.f) || !(f.transmit[1] > 0.f)) return f;
    // Each release is read off a crossing that spans only that donor's own gap:
    // M[1][0] = release[0] * t0, M[0][1] = release[1] * t0, M[1][2] = release[2] * t1.
    f.release[0] = m[3] / f.transmit[0];
    f.release[1] = m[1] / f.transmit[0];
    f.release[2] = m[5] / f.transmit[1];
    if (!(f.release[0] > 0.f)) return f;
    f.self_retention = m[0] / f.release[0];
    f.ok = true;
    return f;
}

/// `CouplerGeometry.matrix(gapReachScales:selfScale:)`: the survival through a gap
/// is `exp(-w/range)`, so a reach multiplier is an exponent on it.
static void coupler_matrix(const CouplerFactors &f, const float reach[2], float self_scale,
                           float *m) {
    float t[2];
    for (int g = 0; g < 2; ++g) {
        const float scale = reach[g];
        t[g] = scale <= 0.f ? 0.f
             : (scale == 1.f ? f.transmit[g] : std::pow(f.transmit[g], 1.f / scale));
    }
    for (int r = 0; r < 3; ++r) {
        for (int d = 0; d < 3; ++d) {
            if (r == d) { m[r * 3 + d] = f.release[r] * f.self_retention * self_scale; continue; }
            const int lo = std::min(r, d), hi = std::max(r, d);
            float crossing = 1.f;
            for (int g = lo; g < hi; ++g) crossing *= t[g];
            m[r * 3 + d] = f.release[d] * crossing;
        }
    }
}

/// `FilmEngine.inhibitorRelease`: the normalised Hill form. Gamma 1 is the exact
/// linear mode, and every stock that ships a gamma gets a different shoulder.
static float inhibitor_release(float activation, float gamma) {
    const float a = std::min(std::max(activation, 0.f), 1.f);
    if (gamma == 1.f) return a;
    const float released = std::pow(a, gamma);
    const float retained = std::pow(1.f - a, gamma);
    return released / std::max(released + retained, 1.17549435e-38f);
}

/// `FilmStock.couplerReleaseGamma`, which `stocks.bin` does not carry: twenty-one
/// of the forty-seven packs have a gamma below one, so the side table is not
/// optional -- without it every coupler render comes out a different picture.
static const float *coupler_gamma_of(const std::string &id, const std::string &resources) {
    static std::map<std::string, std::array<float, 3>> table;
    static std::array<float, 3> fallback = {1.f, 1.f, 1.f};
    static bool loaded = false;
    if (!loaded) {
        loaded = true;
        const std::string dirs[] = {resources, resources + "/assets/fotufilm", "", "./",
                                    "assets/fotufilm/",
                                    "/data/data/com.termux/files/home/spk-debug/cpphost/"};
        for (const std::string &dir : dirs) {
            std::FILE *f = std::fopen((dir + "/coupler_gamma.txt").c_str(), "r");
            if (!f) continue;
            char line[256];
            while (std::fgets(line, sizeof line, f)) {
                char name[128];
                std::array<float, 3> g = {1.f, 1.f, 1.f};
                if (std::sscanf(line, "%127s %f %f %f", name, &g[0], &g[1], &g[2]) == 4)
                    table[name] = g;
            }
            std::fclose(f);
            break;
        }
    }
    const auto it = table.find(id);
    return it == table.end() ? fallback.data() : it->second.data();
}

/// The donor capture layer's inhibition, which `stocks.bin` does not carry either.
struct DonorSide {
    float inhibition[3] = {0.f, 0.f, 0.f};
    float gamma = 1.f;
    bool any() const { return inhibition[0] != 0.f || inhibition[1] != 0.f || inhibition[2] != 0.f; }
};

static DonorSide donor_of(const std::string &id, const std::string &resources) {
    static std::map<std::string, DonorSide> table;
    static bool loaded = false;
    if (!loaded) {
        loaded = true;
        const std::string dirs[] = {resources, resources + "/assets/fotufilm", "", "./",
                                    "assets/fotufilm/",
                                    "/data/data/com.termux/files/home/spk-debug/cpphost/"};
        for (const std::string &dir : dirs) {
            std::FILE *f = std::fopen((dir + "/donor.txt").c_str(), "r");
            if (!f) continue;
            char line[256];
            while (std::fgets(line, sizeof line, f)) {
                char name[128];
                DonorSide d;
                if (std::sscanf(line, "%127s %f %f %f %f", name, &d.inhibition[0],
                                &d.inhibition[1], &d.inhibition[2], &d.gamma) == 5)
                    table[name] = d;
            }
            std::fclose(f);
            break;
        }
    }
    const auto it = table.find(id);
    return it == table.end() ? DonorSide{} : it->second;
}

/// `FilmEngine.couplerWarp`: the neutral anchor the DIR stage reads, 128 samples
/// per channel over the warp window. Release gamma is 1 for every shipped stock,
/// where the Hill form collapses to a clamp.
static void coupler_warp(const film::Spectra &stock, const float *m,
                         float scale, const float gamma[3], const DonorSide &donor,
                         float *out) {
    const std::array<curve::Curve, 3> &curves = stock.curves;
    constexpr int kSamples = 128;
    constexpr float kWarpMin = -4.f, kWarpMax = 4.f;
    for (int i = 0; i < 3 * kSamples; ++i) out[i] = 0.f;
    bool any = false;
    for (int i = 0; i < 9; ++i) if (m[i] != 0.f) any = true;
    if (!(scale > 0.f) || !any) return;

    constexpr int kSolve = 4 * kSamples;
    constexpr float kSolveLow = kWarpMin - 1.f;
    const float solve_step = ((kWarpMax + 1.f) - kSolveLow) / float(kSolve - 1);
    std::vector<float> activation(size_t(3) * kSolve, 0.f);
    for (int layer = 0; layer < 3; ++layer) {
        const curve::Curve &c = curves[size_t(layer)];
        const float range = c.d_max() - c.d_min;
        if (!(range > 0.f)) continue;
        for (int i = 0; i < kSolve; ++i) {
            const float l = kSolveLow + float(i) * solve_step;
            const float a = (c.density(l) - c.d_min) / range;
            activation[size_t(layer) * kSolve + size_t(i)] = inhibitor_release(a, gamma[layer]);
        }
    }
    // `size_t(kSolve)` here is a parameter declaration, not a constructor call.
    // The donor rides the same release sum its per-pixel path does; outside it, a
    // neutral would develop the fourth layer's inhibition twice on the grey axis.
    std::vector<float> donor_activation(size_t(kSolve), 0.f);
    if (donor.any() && !stock.donors.empty()) {
        const curve::Curve &c = stock.donors.front().curve;
        const float range = c.d_max() - c.d_min;
        if (range > 0.f) {
            for (int i = 0; i < kSolve; ++i) {
                const float l = kSolveLow + float(i) * solve_step;
                donor_activation[size_t(i)] =
                    inhibitor_release((c.density(l) - c.d_min) / range, donor.gamma);
            }
        }
    }
    std::vector<float> u(static_cast<std::size_t>(kSolve)), off(static_cast<std::size_t>(kSolve));
    for (int ch = 0; ch < 3; ++ch) {
        for (int i = 0; i < kSolve; ++i) {
            float released = 0.f;
            for (int d = 0; d < 3; ++d)
                released += m[ch * 3 + d] * activation[size_t(d) * kSolve + size_t(i)];
            released += donor.inhibition[ch] * donor_activation[size_t(i)];
            off[size_t(i)] = scale * released;
            u[size_t(i)] = kSolveLow + float(i) * solve_step - off[size_t(i)];
        }
        for (int i = 1; i < kSolve; ++i)
            if (u[size_t(i)] <= u[size_t(i - 1)]) u[size_t(i)] = u[size_t(i - 1)] + 1e-6f;
        int cursor = 0;
        for (int k = 0; k < kSamples; ++k) {
            const float target = kWarpMin + (kWarpMax - kWarpMin) * float(k) / float(kSamples - 1);
            while (cursor + 2 < kSolve && u[size_t(cursor) + 1] < target) ++cursor;
            const float span = u[size_t(cursor) + 1] - u[size_t(cursor)];
            const float t = span > 0.f
                ? std::min(std::max((target - u[size_t(cursor)]) / span, 0.f), 1.f) : 0.f;
            out[ch * kSamples + k] = off[size_t(cursor)]
                + t * (off[size_t(cursor) + 1] - off[size_t(cursor)]);
        }
    }
}

/// The sRGB EOTF, for the two output levels: they arrive as encoded values and
/// the grade works in display-linear light.
static float srgb_eotf_f(float v) {
    return v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f);
}

// Which maker's edge print a fotufilm id gets. The engine keys the whole
// rebate print on the catalogue id (`fujifilm_c200`, `kodak_portra_400`), and
// the second engine's ids are the short names -- so the brand goes back on.
static std::string rebate_stock_name(const std::string &id) {
    struct Rule { const char *token; const char *name; };
    // Longest first: `portra` before `p`, `cinestill` before `still`.
    static const Rule rules[] = {
        // The 135 strip's DX code differs per Portra speed (400 = 79/13,
        // 160 = 95/14, 800 = 79/14); a bare `portra` token sent every one of
        // them to 400's. The layout family is Kodak either way, so only the
        // barcode and the printed stock name change.
        {"portra160", "kodak_portra_160"}, {"portra800", "kodak_portra_800"},
        {"portra", "kodak_portra_400"}, {"ektor", "kodak_ektar_100"},
        {"ektar", "kodak_ektar_100"},   {"ektachrome", "kodak_ektachrome_100"},
        {"kodachrome", "kodak_kodachrome_64"}, {"gold", "kodak_gold_200"},
        {"ultramax", "kodak_ultramax_400"}, {"trix", "kodak_tri_x_400"},
        {"tmax", "kodak_tmax_100"},     {"verita", "kodak_verita_200d"},
        {"vision", "kodak_vision3_250d"}, {"aerochrome", "kodak_aerochrome_1443"},
        {"c200", "fujifilm_c200"},      {"superia", "fujifilm_xtra_400"},
        {"pro400h", "fujifilm_pro_400h"}, {"provia", "fujifilm_provia_100f"},
        {"velvia", "fujifilm_velvia_100"}, {"astia", "fujifilm_astia_100f"},
        {"acros", "fujifilm_neopan_acros_100_ii"}, {"neopan", "fujifilm_neopan_acros_100_ii"},
        {"instax", "fujifilm_instax_mini"}, {"cinestill", "cinestill_800t"},
        {"hp5", "ilford_hp5_plus_400"}, {"fp4", "ilford_fp4_plus_125"},
        {"delta", "ilford_delta_3200"}, {"panf", "ilford_pan_f_plus_50"},
        {"agfa", "agfa_apx_100"},       {"apx", "agfa_apx_100"},
        {"rollei", "rollei_retro_80s"},
    };
    std::string low;
    low.reserve(id.size());
    for (char c : id) low.push_back(char(std::tolower(static_cast<unsigned char>(c))));
    for (const Rule &r : rules)
        if (low.find(r.token) != std::string::npos) return r.name;
    return id;
}

static int run(int argc, char **argv) {
    bool medium_negative = false;   // `.negative`: a viewing mode, timed by nothing
    bool medium_cineon = false;     // `.cineon-log`: code values, no sheet either
    if (argc < 4) {
        std::fprintf(stderr,
            "usage: %s <photo> <bake-prefix> <out.ppm> [--max-edge N] [--mask N] [--seed N]"
            " [--input <prefix>] [--compare <out-prefix>] [--self-tables <resources> --stock <id> --paper <bin>]\n",
            argv[0]);
        return 2;
    }
    const std::string photo = argv[1], prefix = argv[2], out_path = argv[3];
    if (fh_time_on()) {
        using namespace std::chrono;
        g_fh_t0 = duration<double, std::milli>(steady_clock::now().time_since_epoch()).count();
    }
    int max_edge = 0;
    float quality = 0.95f;
    std::string display_path;
    std::string scene_path;
    std::string meter_prefix;
    bool meter_explicit = false;
    std::string dump_tables_prefix;
    // The running-time slots, as the catalogue names them. `EXPOSURE_GAIN` is the one the bake
    // already solved for, so the flag scales it rather than replacing it: --ev 0 is the bake's own
    // picture, byte for byte. The rest are absolute and start at their neutral values.
    float ev = 0.0f, highlights = 0.0f, shadows = 0.0f;
    float saturation = 1.0f, vibrance = 0.0f;
    float push_stops_wanted = 0.0f;
    std::string camera_profile_dir;
    std::string camera_profile_id;
    std::string push_path;
    bool have_tone = false;
    float wb[3] = {1.0f, 1.0f, 1.0f};
    float warmth = 0, tint = 0, flare = 0, grade_lift = 0, grade_gain = 0;
    int32_t mask = 0;
    bool have_mask = false;
    uint32_t seed = 0;
    bool have_seed = false;
    std::string compare_prefix;
    std::string dump_config_path;
    std::string resources_dir, stock_id, paper_bin, input_prefix, table_cache_dir;
    float expired_years = 0.0f, bleach = 0.0f;
    // Direct configuration writes, applied last: `--slot 30=0.0136 --slot 453=0.2`.
    // Three ways to move a slot, because a control is one of three kinds: an absolute value
    // (`--slot`), a multiplier on the film's own calibration (`--mul`, grain and saturation), or
    // an offset in the encode (`--add`, vibrance).
    std::vector<std::pair<int, float>> slot_overrides, slot_muls, slot_adds;
    float print_correction_value = 0.0f;
    float halation_colour = 0.0f;
    // Shadow and Highlight Detail: the print's hold, applied as printing light.
    float shadow_detail = 0.0f, highlight_detail = 0.0f;
    // The couplers, the fringe, the two output levels and the printer's preflash.
    float coupler_reach[2] = {1.f, 1.f};
    float coupler_self = 1.f;
    bool coupler_touched = false;
    float fringe_amount = -1.f, fringe_radius_um = 0.f;
    float black_point = -1.f, white_point = -1.f;
    float printer_preflash = 0.f;
    // The film grain's tiles: the population the Swift engine laid them from, and how the
    // frame lays them (size, colour mix, per-record amounts, scan softness).
    std::string grain_records_path;
    float grain_size = 1.0f, grain_colour = 1.0f, grain_softness = 1.0f;
    // The blurred-clump grain's own size knob (grain mode 0, which is what runs when the tile
    // model is off). `grain_size` above only moves FILM_TILE's pitch.
    float grain_scale = 1.0f;
    float grain_layers[3] = {1.0f, 1.0f, 1.0f};
    // Multipliers on the metre-scale physics, applied before `resize_config` folds them into
    // pixels. Halation and the film-grain sliders live here rather than in the configuration.
    std::vector<std::pair<std::string, float>> phys_muls;
    // A block multiplier: `--mulrange 66-449=1.375`. The coupler warp is 384 floats and every one
    // of them scales with the coupler strength.
    std::vector<std::tuple<int, int, float>> mul_ranges;
    bool screen_medium = false;
    for (int i = 4; i < argc; ++i) {
        const std::string flag = argv[i];
        auto next = [&](const char *name) -> const char * {
            if (i + 1 >= argc) {
                std::fprintf(stderr, "%s needs a value\n", name);
                std::exit(2);
            }
            return argv[++i];
        };
        if (flag == "--max-edge") max_edge = std::atoi(next("--max-edge"));
        else if (flag == "--quality") quality = float(std::atof(next("--quality")));
        else if (flag == "--ev") ev = float(std::atof(next("--ev")));
        else if (flag == "--highlights") { highlights = float(std::atof(next("--highlights"))); have_tone = true; }
        else if (flag == "--shadows") { shadows = float(std::atof(next("--shadows"))); have_tone = true; }
        else if (flag == "--saturation") { saturation = float(std::atof(next("--saturation"))); have_tone = true; }
        else if (flag == "--vibrance") { vibrance = float(std::atof(next("--vibrance"))); have_tone = true; }
        else if (flag == "--push") push_stops_wanted = float(std::atof(next("--push")));
        else if (flag == "--camera-profile") camera_profile_dir = next("--camera-profile");
        else if (flag == "--camera-profile-id") camera_profile_id = next("--camera-profile-id");
        else if (flag == "--push-file") push_path = next("--push-file");
        else if (flag == "--temperature") {
            const float t = float(std::atof(next("--temperature")));
            wb[0] = wb[1] = wb[2] = t;
        }
        else if (flag == "--mulrange") {
            const char *v = next("--mulrange");
            const char *dash = std::strchr(v, '-');
            const char *eq = dash ? std::strchr(dash, '=') : nullptr;
            if (!dash || !eq) { std::fprintf(stderr, "--mulrange wants A-B=value\n"); return 2; }
            mul_ranges.emplace_back(int(std::atoi(v)), int(std::atoi(dash + 1)),
                                    float(std::atof(eq + 1)));
        }
        else if (flag == "--physmul") {
            const char *v = next("--physmul");
            const char *eq = std::strchr(v, '=');
            if (!eq) { std::fprintf(stderr, "--physmul wants NAME=value\n"); return 2; }
            phys_muls.emplace_back(std::string(v, size_t(eq - v)), float(std::atof(eq + 1)));
        }
        else if (flag == "--slot" || flag == "--mul" || flag == "--add") {
            const char *v = next(flag.c_str());
            char *end = nullptr;
            const long at = std::strtol(v, &end, 10);
            if (!end || *end != '=') { std::fprintf(stderr, "%s wants N=value\n", flag.c_str()); return 2; }
            const float amount = float(std::atof(end + 1));
            if (flag == "--slot") slot_overrides.emplace_back(int(at), amount);
            else if (flag == "--mul") slot_muls.emplace_back(int(at), amount);
            else slot_adds.emplace_back(int(at), amount);
        }
        else if (flag == "--expired") expired_years = float(std::atof(next("--expired")));
        else if (flag == "--bleach") bleach = float(std::atof(next("--bleach")));
        else if (flag == "--table-cache") table_cache_dir = next("--table-cache");
        else if (flag == "--warmth") warmth = float(std::atof(next("--warmth")));
        else if (flag == "--tint") tint = float(std::atof(next("--tint")));
        else if (flag == "--flare") flare = float(std::atof(next("--flare")));
        else if (flag == "--grade-lift") grade_lift = float(std::atof(next("--grade-lift")));
        else if (flag == "--grade-gain") grade_gain = float(std::atof(next("--grade-gain")));
        else if (flag == "--wb")
            for (int c = 0; c < 3; ++c) wb[c] = float(std::atof(next("--wb")));
        else if (flag == "--mask") { mask = int32_t(std::strtol(next("--mask"), nullptr, 0)); have_mask = true; }
        else if (flag == "--shadow-detail") shadow_detail = std::strtof(next("--shadow-detail"), nullptr);
        else if (flag == "--print-correction") print_correction_value = std::strtof(next("--print-correction"), nullptr);
        else if (flag == "--halation-colour") halation_colour = std::strtof(next("--halation-colour"), nullptr);
        else if (flag == "--highlight-detail") highlight_detail = std::strtof(next("--highlight-detail"), nullptr);
        else if (flag == "--coupler-reach") {
            const float v = float(std::atof(next("--coupler-reach")));
            coupler_reach[0] = coupler_reach[1] = v; coupler_touched = true;
        }
        else if (flag == "--coupler-rg") {
            // The catalogue's Red–Green Reach: the first gap alone.
            coupler_reach[0] = float(std::atof(next("--coupler-rg"))); coupler_touched = true;
        }
        else if (flag == "--coupler-gb") {
            coupler_reach[1] = float(std::atof(next("--coupler-gb"))); coupler_touched = true;
        }
        else if (flag == "--coupler-self") {
            coupler_self = float(std::atof(next("--coupler-self"))); coupler_touched = true;
        }
        else if (flag == "--fringe") fringe_amount = float(std::atof(next("--fringe")));
        else if (flag == "--fringe-radius") fringe_radius_um = float(std::atof(next("--fringe-radius")));
        else if (flag == "--black-point") black_point = float(std::atof(next("--black-point")));
        else if (flag == "--white-point") white_point = float(std::atof(next("--white-point")));
        else if (flag == "--printer-preflash") printer_preflash = float(std::atof(next("--printer-preflash")));
        else if (flag == "--seed") { seed = uint32_t(std::strtoul(next("--seed"), nullptr, 0)); have_seed = true; }
        else if (flag == "--compare") compare_prefix = next("--compare");
        else if (flag == "--dump-display") display_path = next("--dump-display");
        else if (flag == "--dump-scene") scene_path = next("--dump-scene");
        else if (flag == "--meter") { meter_prefix = next("--meter"); meter_explicit = true; }
        else if (flag == "--dump-tables") dump_tables_prefix = next("--dump-tables");
        else if (flag == "--grain") grain_records_path = next("--grain");
        else if (flag == "--grain-size") grain_size = float(std::atof(next("--grain-size")));
        else if (flag == "--grain-scale") grain_scale = float(std::atof(next("--grain-scale")));
        else if (flag == "--mottle") g_mottle_share = float(std::atof(next("--mottle")));
        else if (flag == "--grade-space") g_grade_space = int(std::atof(next("--grade-space")));
        else if (flag == "--print-light") g_print_light = next("--print-light");
        else if (flag == "--digital-reference") g_digital_reference = next("--digital-reference");
        else if (flag == "--enlarger") g_enlarger = std::strcmp(next("--enlarger"), "condenser") == 0 ? 1 : 0;
        else if (flag == "--local-tone") g_local_tone = int(std::atof(next("--local-tone")));
        else if (flag == "--printer") g_printer = int(std::atof(next("--printer")));
        else if (flag == "--printer-lamp") g_printer_lamp = float(std::atof(next("--printer-lamp")));
        else if (flag == "--printer-magenta") g_printer_m = float(std::atof(next("--printer-magenta")));
        else if (flag == "--printer-yellow") g_printer_y = float(std::atof(next("--printer-yellow")));
        else if (flag == "--printer-exposure") g_printer_ev = float(std::atof(next("--printer-exposure")));
        else if (flag == "--printer-red") g_printer_rgb[0] = float(std::atof(next("--printer-red")));
        else if (flag == "--printer-green") g_printer_rgb[1] = float(std::atof(next("--printer-green")));
        else if (flag == "--printer-blue") g_printer_rgb[2] = float(std::atof(next("--printer-blue")));
        else if (flag == "--grain-colour") grain_colour = float(std::atof(next("--grain-colour")));
        else if (flag == "--grain-softness") grain_softness = float(std::atof(next("--grain-softness")));
        else if (flag == "--grain-layers") {
            const char *v = next("--grain-layers");
            std::sscanf(v, "%f,%f,%f", &grain_layers[0], &grain_layers[1], &grain_layers[2]);
        }
        else if (flag == "--dump-config") dump_config_path = next("--dump-config");
        else if (flag == "--exif") g_exif_fields = next("--exif");
        else if (flag == "--geometry") parse_geometry(next("--geometry"), g_geometry);
        else if (flag == "--view") parse_view(next("--view"), g_view);
        else if (flag == "--frame-mm") g_frame_mm = float(std::atof(next("--frame-mm")));
        else if (flag == "--rebate") g_rebate = int(std::atof(next("--rebate")));
        else if (flag == "--rgate") g_rebate_gate = next("--rgate");
        else if (flag == "--rtext") g_rebate_text = next("--rtext");
        else if (flag == "--rfog") g_rebate_fog = std::atof(next("--rfog"));
        else if (flag == "--rleaks") g_rebate_leaks = std::atof(next("--rleaks"));
        else if (flag == "--rnum") g_rebate_num = int(std::atof(next("--rnum")));
        else if (flag == "--rcam") g_rebate_cam = int(std::atof(next("--rcam")));
        else if (flag == "--rfr") g_rebate_fr = int(std::atof(next("--rfr")));
        else if (flag == "--rdate") g_rebate_date = next("--rdate");
        else if (flag == "--rplace") g_rebate_place = next("--rplace");
        else if (flag == "--rstyle") g_rebate_style = next("--rstyle");
        else if (flag == "--rcorner") g_rebate_corner = next("--rcorner");
        else if (flag == "--rsize") g_rebate_size = std::atof(next("--rsize"));
        else if (flag == "--rinsx") g_rebate_inset_x = std::atof(next("--rinsx"));
        else if (flag == "--rinsy") g_rebate_inset_y = std::atof(next("--rinsy"));
        else if (flag == "--rfn") g_rebate_fnum = std::atof(next("--rfn"));
        else if (flag == "--rcar") g_rebate_carrier = next("--rcar");
        else if (flag == "--rholes") g_rebate_holes = next("--rholes");
        else if (flag == "--pf") {
            g_pf_kind = next("--pf");
            if (print_frame::is_film_kind(print_frame::kind_from_id(g_pf_kind))) g_rebate = 1;
        }
        else if (flag == "--pfgauge") g_pf_gauge = next("--pfgauge");
        else if (flag == "--etext") g_pf_etext = std::atoi(next("--etext")) != 0;
        else if (flag == "--pfmargin") g_pf_margin = std::atof(next("--pfmargin"));
        else if (flag == "--pffx") g_pf_fx = std::atof(next("--pffx"));
        else if (flag == "--pffy") g_pf_fy = std::atof(next("--pffy"));
        else if (flag == "--curves") parse_curves(next("--curves"), g_view);
        else if (flag == "--self-tables") resources_dir = next("--self-tables");
        else if (flag == "--stock") stock_id = next("--stock");
        else if (flag == "--paper") paper_bin = next("--paper");
        else if (flag == "--input") input_prefix = next("--input");
        else if (flag == "--screen") screen_medium = true;
        else { std::fprintf(stderr, "unknown flag %s\n", flag.c_str()); return 2; }
    }

    // --- 1. decode through the engine's own codecs ---------------------------
    ffc_image image{};
    char error[512] = {};
    // A RAW is demosaiced no larger than the editor's working edge before it is reduced
    // further: PortableImageDecoder.workingLongEdge. And the reduction to that edge only
    // happens on the RAW entry point -- ffc_decode leaves the developed frame at its own
    // size -- so a host that calls the general one gets a different scene.
    constexpr uint32_t kRawWorkingLongEdge = 2560;
    ffc_raw_limits limits{};
    limits.max_file_bytes = 1ull << 30;
    limits.max_sensor_pixels = 300000000ull;
    limits.max_working_bytes = 8ull << 30;
    uint32_t source_width = 0, source_height = 0;
    // The editor works at 2560 because it renders interactively. A still that asks for the frame's
    // own size must not inherit that ceiling, or "original" comes out smaller than the 4096 tier.
    // Zero means "leave it at the sensor's size"; a larger tier raises the working edge to match.
    const uint32_t working_edge =
        max_edge > 0 ? std::max<uint32_t>(kRawWorkingLongEdge, uint32_t(max_edge)) : 0u;
    // **One decode per photograph.** See `SceneCache`: the shelf and the canvas
    // both re-render the same file many times, and the decode is two thirds of
    // a card's cost.
    const bool use_input = !input_prefix.empty();
    const long long scene_mtime = use_input ? 0 : file_mtime_seconds(photo);
    const std::string scene_key = use_input ? std::string()
        : photo + "|" + std::to_string(scene_mtime) + "|"
          + std::to_string(working_edge);
    // **A file the decoder reads whole is the same frame at every tier.** The
    // working edge only reaches `ffc_decode_raw`, which answers "unsupported" for a
    // JPEG and then decodes it at its stored size: the tier changed the cache key
    // and nothing else, so an export asked for the frame's own size (`max_edge`
    // zero, its own key) opened and decoded the file a second time -- 0.5 s on a
    // twelve-megapixel JPEG, measured. `edge_free` is written at decode time, so
    // only a frame whose pixels provably do not depend on the edge is reused.
    const bool scene_from_cache = !use_input && !scene_key.empty()
        && !g_scene.rgba.empty()
        && (g_scene.key == scene_key
            || (g_scene.edge_free && g_scene.path == photo && g_scene.mtime == scene_mtime));
    int32_t status = 0;
    bool used_thumbnail = false;
    std::vector<uint8_t> source_exif;
    float source_sensor_mm = 0.0f;
    if (!use_input && !scene_from_cache) {
        status = ffc_decode_raw(photo.c_str(), FFC_DECODE_SCENE, working_edge,
                                &limits, &image, &source_width, &source_height,
                                error, sizeof error);
        if (status == FFC_RAW_UNSUPPORTED) {
            // **A hundred megapixels does not have to be a hundred-megapixel float frame.**
            // Every render that fits the budget keeps the exact `ffc_decode` path, byte for
            // byte; only a file genuinely beyond it takes the reduced-scale decode.
            const uint64_t budget_px = scene_budget_pixels();
            uint32_t jw = 0, jh = 0;
            const bool is_jpeg = jpeg_dimensions(photo.c_str(), jw, jh);
            const double native_px = double(jw) * double(jh);
            // **One reason to decode small: memory.** The frame would not fit, so the file is
            // developed at a size the phone can hold and the rest is averaged away.
            //
            // It used to trigger on arithmetic too -- a file more than ten times the target was
            // decoded through the thumbnail route to save the develop time. That is an
            // approximation and it costs accuracy: 3072x4096 taken to 150x200 that way differs
            // from the engine's own scene planes by mean 0.0145 / max 0.37 in linear Rec.2020,
            // while decoding the file whole and reducing it by area is bit-identical (measured
            // on `tiny150b.jpg`, n = 0 of 30000 pixels). Previews are the path that matters, so
            // the shortcut is gone and only the memory case keeps it.
            const bool over_budget = native_px > double(budget_px);
            if (is_jpeg && over_budget
                    && thumbnail_scene(photo.c_str(), jw, jh, max_edge, budget_px, image)) {
                used_thumbnail = true;
                status = 0;
            } else {
                status = ffc_decode(photo.c_str(), FFC_DECODE_SCENE, 0, &image, error, sizeof error);
            }
        }
        if (status != 0) {
            std::printf("decode failed (%d): %s\n", status, error);
            return 1;
        }
        std::printf("decoded %ux%u  raw=%d  headroom=%g  capture-exif=%zu B\n",
                    image.width, image.height, image.is_raw, image.content_headroom,
                    size_t(image.capture.exif_length));
        // **The record an export carries.** `ffc_encode` writes this back, and the
        // engine's own host passes exactly this: the decoder's copy, with the
        // encoder stripping the orientation, the pixel size, the thumbnail and the
        // maker notes -- each of which would be a lie once the print is a
        // different size and already upright. GPS goes with it unless the caller
        // asks to keep it; `HostMetadataPolicy.default` is preserve-without-location.
        if (image.capture.exif && image.capture.exif_length > 0)
            source_exif.assign(image.capture.exif, image.capture.exif + image.capture.exif_length);
        source_sensor_mm = sensor_short_side_mm(image.capture);
    } else if (scene_from_cache) {
        source_exif = g_scene.exif;
        source_sensor_mm = g_scene.sensor_short_mm;
        image.width = g_scene.width;
        image.height = g_scene.height;
        image.is_raw = g_scene.is_raw;
        image.content_headroom = g_scene.headroom;
        std::printf("scene from the cache: %ux%u\n", image.width, image.height);
    }
    // **The byte table has to be the same function, not a close one.** Run this
    // with FH_LUT_CHECK=1 after touching `srgb_byte_fast`: it walks a four-million
    // step sweep of [0,1], every threshold's sixteen-neighbourhood and a million
    // random bit patterns, and compares each against the `pow` it replaces.
    if (std::getenv("FH_LUT_CHECK")) {
        const SrgbByteTable &t = srgb_byte_table();
        long long bad = 0, tried = 0;
        for (int i = 0; i <= 4000000; ++i) {
            const float v = float(i) / 4000000.0f;
            ++tried;
            if (srgb_byte_fast(t, v) != srgb_byte_slow(v)) ++bad;
        }
        for (int b = 1; b < 256; ++b) {
            for (int k = -8; k <= 8; ++k) {
                uint32_t bits;
                std::memcpy(&bits, &t.threshold[b], sizeof bits);
                bits = uint32_t(int64_t(bits) + k);
                if (bits > 0x3F800000u) continue;
                float v;
                std::memcpy(&v, &bits, sizeof v);
                ++tried;
                if (srgb_byte_fast(t, v) != srgb_byte_slow(v)) ++bad;
            }
        }
        uint32_t state = 12345u;
        for (int i = 0; i < 1000000; ++i) {
            state = state * 1664525u + 1013904223u;
            const uint32_t bits = state & 0x3F7FFFFFu;   // a float in [0, 1)
            float v;
            std::memcpy(&v, &bits, sizeof v);
            ++tried;
            if (srgb_byte_fast(t, v) != srgb_byte_slow(v)) ++bad;
        }
        float edge[5] = {0.0f, -0.0f, 1.0f, -1.0f, 0.0031308f};
        for (float v : edge) {
            ++tried;
            if (srgb_byte_fast(t, v) != srgb_byte_slow(v)) ++bad;
        }
        std::printf("srgb lut: %lld mismatches of %lld\n", bad, tried);
    }
    fh_mark("decode");
    // The camera's own spectral correction, where the engine applies it: as soon as the RAW is in
    // linear working space, before anything reads the scene.
    if (!camera_profile_dir.empty() && image.is_raw && !scene_from_cache) {
        static camera_profile::Store store;
        static bool loaded = false;
        if (!loaded) {
            loaded = store.load_all(camera_profile_dir + "/camera_profiles.bin",
                                    camera_profile_dir + "/camera_identities.txt");
            if (!loaded) std::printf("camera profiles: could not load from %s\n", camera_profile_dir.c_str());
        }
        if (loaded) {
            const camera_profile::Resolved resolved = camera_profile::resolve_correction(
                store, camera_profile_id, image.capture.make, image.capture.model,
                image.as_shot_kelvin > 0.0f, image.as_shot_kelvin);
            if (resolved) {
                camera_profile::apply(resolved.matrix, image.rgba,
                                      size_t(image.width) * size_t(image.height) * 4);
                std::printf("camera profile %s at %.0f K (max deviation %.4f)\n",
                            resolved.profile_id.c_str(), double(resolved.cct),
                            double(resolved.max_deviation));
            } else {
                std::printf("camera profile: none (%s %s at %.0f K)\n",
                            image.capture.make, image.capture.model, double(image.as_shot_kelvin));
            }
        }
    }
    // Keep the decoded scene for the next render of this file. The ceiling used to be eight
    // megapixels, which left every full-size phone JPEG out -- and once the preview path stopped
    // going through the reduced-scale decode (see above) that meant paying 0.8 s of decode for
    // every one of the forty-seven shelf cards. Sixteen megapixels holds a 12.6 MP JPEG (200 MB
    // of float RGBA) once, which is what the engine's own host keeps for the same file.
    if (!use_input && !scene_from_cache) {
        const size_t px = size_t(image.width) * size_t(image.height);
        if (px > 0 && px <= 16000000) {
            if (g_scene.key != scene_key) {
                // A different frame: its reductions are about pixels that no longer
                // exist. The decoded frame is simply overwritten below.
                g_scene.reduced.clear();
            }
            g_scene.key = scene_key;
            g_scene.path = photo;
            g_scene.mtime = scene_mtime;
            g_scene.edge_free = !image.is_raw && !used_thumbnail;
            g_scene.width = image.width;
            g_scene.height = image.height;
            g_scene.is_raw = image.is_raw;
            g_scene.headroom = image.content_headroom;
            g_scene.rgba.assign(image.rgba, image.rgba + px * 4);
            g_scene.exif = source_exif;
            g_scene.sensor_short_mm = source_sensor_mm;
        }
    }
    fh_mark("profile");
    const float *decoded = scene_from_cache ? g_scene.rgba.data() : image.rgba;

    // The reductions, by size. Four entries cover every size the app renders
    // (420 drag, 900 settle, 2048 canvas, 320 card) and stay under 64 MB at a
    // 2240x2068 canvas.
    const auto take_reduced = [](uint32_t w, uint32_t h, std::vector<float> &out) -> bool {
        for (SceneCache::Reduced &entry : g_scene.reduced) {
            if (entry.width != w || entry.height != h || entry.rgba.empty()) continue;
            out = entry.rgba;
            entry.used = ++g_scene.reduced_clock;
            return true;
        }
        return false;
    };
    const auto save_reduced = [](uint32_t w, uint32_t h, const std::vector<float> &v) {
        // A cap, because this vector outlives the render: a caller that asked for a
        // size this chain never asks for would otherwise park hundreds of megabytes.
        if (v.empty() || v.size() * sizeof(float) > (96ull << 20)) return;
        for (SceneCache::Reduced &entry : g_scene.reduced) {
            if (entry.width == w && entry.height == h) {
                entry.rgba = v;
                entry.used = ++g_scene.reduced_clock;
                return;
            }
        }
        SceneCache::Reduced entry;
        entry.width = w;
        entry.height = h;
        entry.rgba = v;
        entry.used = ++g_scene.reduced_clock;
        g_scene.reduced.push_back(std::move(entry));
        constexpr std::size_t kKeep = 4;
        while (g_scene.reduced.size() > kKeep) {
            std::size_t oldest = 0;
            for (std::size_t i = 1; i < g_scene.reduced.size(); ++i)
                if (g_scene.reduced[i].used < g_scene.reduced[oldest].used) oldest = i;
            g_scene.reduced.erase(g_scene.reduced.begin() + std::ptrdiff_t(oldest));
        }
    };

    // The scene at the size the render asks for, reduced exactly as the engine reduces it.
    int target_width = 0, target_height = 0;
    std::vector<float> source_rgba;
    if (g_geometry.active) {
        // **The frame's shape comes first, then the preview edge.** The engine
        // crops and turns at full resolution and reduces afterwards; doing both
        // in one pass here lands on the same picture for less memory.
        uint32_t frame_w = 0, frame_h = 0, crop_w = 0, crop_h = 0;
        geometry_frame_size(g_geometry, image.width, image.height, frame_w, frame_h,
                            crop_w, crop_h);
        double scale = 1.0;
        if (max_edge > 0) {
            const uint32_t longest = std::max(frame_w, frame_h);
            if (longest > uint32_t(max_edge)) scale = double(max_edge) / double(longest);
        }
        const uint32_t out_w = std::max(1u, uint32_t(std::lround(double(frame_w) * scale)));
        const uint32_t out_h = std::max(1u, uint32_t(std::lround(double(frame_h) * scale)));
        const uint32_t map_w = std::max(1u, uint32_t(std::lround(double(crop_w) * scale)));
        const uint32_t map_h = std::max(1u, uint32_t(std::lround(double(crop_h) * scale)));
        float c[8];
        geometry_consts(g_geometry, image.width, image.height, map_w, map_h, out_w, out_h, c);
        geometry_resample(decoded, image.width, image.height, out_w, out_h, c,
                          uint32_t(g_geometry.turns), uint32_t(g_geometry.flips), source_rgba);
        target_width = int(out_w);
        target_height = int(out_h);
        std::printf("geometry %.4f,%.4f %.4fx%.4f rot %.2f turns %d flips %d"
                    "  %ux%u -> %ux%u\n",
                    g_geometry.x, g_geometry.y, g_geometry.w, g_geometry.h, g_geometry.rotation,
                    g_geometry.turns, g_geometry.flips, image.width, image.height, out_w, out_h);
    } else {
    resampled_size(int(image.width), int(image.height), max_edge, target_width, target_height);
    // **A rebate is not free.** The canvas is the frame plus its margins, and
    // `max_edge` is the canvas's long edge -- so with the rebate on the picture
    // itself would be laid out smaller than asked. Worse, the engine drops the
    // edge print entirely on canvases under 900 px, which a 6x6 at a preview
    // edge falls under. So the budget is scaled by the ratio the layout will
    // add, computed once at this size (the ratio is scale-invariant).
    //
    // Only when a long edge was asked for. At `max_edge <= 0` the target *is*
    // the file's own size, which is the frame's: the canvas is then simply
    // larger than the photograph, as a strip is, and scaling this budget there
    // would resample the picture up by the rebate's share.
    // **The stock's own inscription, not the catalogue's name.** The edge print
    // is what the factory exposed on the rebate (`200 / FUJI / CA24`), not
    // "FUJIFILM C200"; docs/print-frame-model.md lists the verified ones. It has
    // to be resolved before the rebate's context is built, below.
    if (g_pf_etext && g_rebate_text.empty())
        g_rebate_text = print_frame::edge_print_for(stock_id, g_pf_gauge);

    if (g_rebate && !pf_draws_film() && max_edge > 0 && target_width > 0 && target_height > 0) {
        rebate::Ctx probe;
        probe.stock = rebate_stock_name(stock_id);
        probe.os.active = true;
        probe.os.format = g_rebate_gate;
        probe.px_um = 1000.0 * rebate::gate_long_mm(probe) /
                      double(std::max(target_width, target_height));
        probe.frame_w_mm = float(double(target_width) * probe.px_um / 1000.0);
        probe.frame_h_mm = float(double(target_height) * probe.px_um / 1000.0);
        rebate::OverscanLayout probeL;
        std::string perr;
        if (rebate::layout(probe, uint32_t(target_width), uint32_t(target_height), probeL, perr)) {
            const double ratio = double(std::max(probeL.canvas_w, probeL.canvas_h)) /
                                 double(std::max(target_width, target_height));
            if (ratio > 1.001) {
                target_width = int(std::lround(target_width * ratio));
                target_height = int(std::lround(target_height * ratio));
                std::printf("rebate: the canvas takes %.3fx the frame; budgeting %dx%d\n",
                            ratio, target_width, target_height);
            }
        }
    }
    if (target_width != int(image.width) || target_height != int(image.height)) {
        // First render included: the frame is the same pixels whether or not it
        // came out of the decoder this time, and the second render of a size is
        // what a slider release is.
        if (!take_reduced(uint32_t(target_width), uint32_t(target_height), source_rgba)) {
            source_rgba.assign(decoded, decoded + size_t(image.width) * size_t(image.height) * 4);
            source_rgba = reduce_rgba(source_rgba, int(image.width), int(image.height),
                                      target_width, target_height);
            if (!use_input) save_reduced(uint32_t(target_width), uint32_t(target_height),
                                         source_rgba);
            std::printf("resampled %ux%u -> %dx%d\n", image.width, image.height,
                        target_width, target_height);
        }
    }
    }

    fh_mark("resample");
    // --- the film outside the frame ------------------------------------------
    //
    // **The canvas is the frame from here on.** The first engine builds the
    // rebate on the exposure and lets the whole chemical chain run over it, so
    // the strip develops with the picture -- same grain, same MTF, same paper.
    // The same thing happens here: the composite hands back a plane that is the
    // frame plus its margins *in the same units as the frame's own pixels*, and
    // every stage after this one sees one bigger photograph.
    //
    // What that buys over a drawn border: the perforations are where the punch
    // left them (each hole its own microns off pitch and size), the frame's edge
    // is the gate's shadow rather than a cut, the fog and the leaks are exposure
    // that develops, and the date and the edge print develop as density.
    std::vector<float> rebate_canvas, rebate_P;
    // Two coverage planes and the imprint's own, for the print-domain pass: the
    // gate's (0 in the rebate), the film's (0 in a perforation) and where a mark
    // was drawn. 8 bits each, canvas-sized -- 2.4 MB at 940x869.
    std::vector<uint8_t> rebate_gate, rebate_film, rebate_ink;
    double rebate_short_scale = 1.0;
    // The gate's own size. The meter runs on *this*, not on the canvas: the
    // engine meters the frame before it lays it on film, and a rebate whose
    // clear margins were averaged into the exposure would print ~3 % dark.
    int rebate_frame_w = 0, rebate_frame_h = 0;
    if (g_rebate && !pf_draws_film() && target_width > 0 && target_height > 0) {
        if (!input_prefix.empty()) {
            std::printf("rebate: --input is the frame, not the canvas; refusing\n");
            return 2;
        }
        rebate::Ctx ctx;
        // The engine's edge print is keyed on the catalogue's own id
        // (`fujifilm_c200`), so a fotufilm id has to be folded back into one
        // that carries its maker: the DX code, the number style and the marker
        // glyphs all come from that lookup.
        ctx.stock = rebate_stock_name(stock_id);
        ctx.os.active = true;
        ctx.os.format = g_rebate_gate;
        ctx.os.fog = g_rebate_fog;
        ctx.os.leaks = g_rebate_leaks;
        ctx.os.edge_text = g_rebate_text;
        ctx.os.frame_number = g_rebate_num;
        ctx.os.camera_seed = g_rebate_cam;
        ctx.os.frame_seed = g_rebate_fr;
        ctx.di.active = !g_rebate_date.empty();
        ctx.di.text = g_rebate_date;
        ctx.di.placement = g_rebate_place;
        ctx.di.style = g_rebate_style;
        ctx.di.corner = g_rebate_corner;
        ctx.di.size = g_rebate_size;
        ctx.di.inset_x = g_rebate_inset_x;
        ctx.di.inset_y = g_rebate_inset_y;
        ctx.os.f_number = g_rebate_fnum;
        ctx.os.carrier = g_rebate_carrier;
        ctx.os.holes = g_rebate_holes;
        // The pitch comes from the gate's long edge against the frame's, exactly
        // as the engine's `set_source_shape` derives it.
        ctx.px_um = 1000.0 * rebate::gate_long_mm(ctx) /
                    double(std::max(target_width, target_height));
        ctx.frame_w_mm = float(double(target_width) * ctx.px_um / 1000.0);
        ctx.frame_h_mm = float(double(target_height) * ctx.px_um / 1000.0);
        rebate::OverscanLayout L;
        std::string err;
        if (!rebate::layout(ctx, uint32_t(target_width), uint32_t(target_height), L, err)) {
            std::printf("rebate: %s\n", err.c_str());
            return 2;
        }
        std::vector<float> P;
        rebate::params_block(ctx, L, P);
        // **The strip is a rasterisation, not a develop.** Everything below is a
        // pure function of the frame, the canvas size and this parameter block --
        // and a slider release changes none of the three. The key is those three,
        // hashed; the byte cap keeps a full-size export from parking 300 MB.
        uint64_t strip_key = 1469598103934665603ull;
        const auto mix_strip = [&strip_key](const void *bytes, std::size_t n) {
            const unsigned char *b = static_cast<const unsigned char *>(bytes);
            for (std::size_t i = 0; i < n; ++i) {
                strip_key = (strip_key ^ b[i]) * 1099511628211ull;
            }
        };
        mix_strip(&target_width, sizeof target_width);
        mix_strip(&target_height, sizeof target_height);
        mix_strip(scene_key.data(), scene_key.size());
        mix_strip(P.data(), P.size() * sizeof(float));
        // **Everything the imprint reads, not just the parameter block.** The edge
        // print and the date are drawn on the CPU from `ctx`, and a key that only
        // hashed `P` would keep a strip whose frame number had changed. The crop
        // and the turn are here for the same reason: they change the frame the
        // strip is built around without changing the picture's file.
        const auto mix_text = [&mix_strip](const std::string &text) {
            mix_strip(text.data(), text.size());
            const char nul = 0;
            mix_strip(&nul, 1);
        };
        mix_text(ctx.stock);
        mix_text(ctx.os.format);
        mix_text(ctx.os.edge_text);
        mix_text(ctx.os.carrier);
        mix_text(ctx.os.holes);
        mix_text(ctx.di.text);
        mix_text(ctx.di.placement);
        mix_text(ctx.di.style);
        mix_text(ctx.di.corner);
        const auto mix_num = [&mix_strip](double v) { mix_strip(&v, sizeof v); };
        mix_num(ctx.os.fog);
        mix_num(ctx.os.leaks);
        mix_num(ctx.os.f_number);
        mix_num(ctx.os.frame_number);
        mix_num(ctx.os.camera_seed);
        mix_num(ctx.os.frame_seed);
        mix_num(ctx.di.size);
        mix_num(ctx.di.inset_x);
        mix_num(ctx.di.inset_y);
        mix_num(ctx.px_um);
        mix_num(ctx.frame_w_mm);
        mix_num(ctx.frame_h_mm);
        mix_num(g_geometry.x);
        mix_num(g_geometry.y);
        mix_num(g_geometry.w);
        mix_num(g_geometry.h);
        mix_num(g_geometry.rotation);
        const int geo_bits = (g_geometry.active ? 1 : 0) | (g_geometry.turns << 1)
                           | (g_geometry.flips << 4);
        mix_strip(&geo_bits, sizeof geo_bits);
        const std::uint64_t strip_bytes =
            std::uint64_t(L.canvas_w) * std::uint64_t(L.canvas_h);
        // Twelve bytes of canvas and three of coverage a pixel: 96 MB is a
        // 2240x2068 strip, and nothing from a full-size export.
        const bool keep_strip = strip_bytes * 15ull <= (96ull << 20);
        int canvas_w = target_width, canvas_h = target_height;
        Strip *kept = keep_strip ? strip_find(strip_key) : nullptr;
        if (kept != nullptr) {
            canvas_w = kept->width;
            canvas_h = kept->height;
            rebate_canvas = kept->canvas;
            rebate_gate = kept->gate;
            rebate_film = kept->film;
            rebate_ink = kept->ink;
            std::printf("rebate: %s %dx%d -> canvas %dx%d  (kept)\n", L.format.c_str(),
                        target_width, target_height, canvas_w, canvas_h);
        } else {
            const float *scene_src = source_rgba.empty() ? decoded : source_rgba.data();
            rebate::Canvas canvas;
            if (!rebate::canvas_pass(P, scene_src, target_width, target_height, 4, canvas,
                                     &rebate_gate, &rebate_film)) {
                std::printf("rebate: the canvas refused to build\n");
                return 2;
            }
            fh_mark("rb-canvas");
            const std::vector<rebate::Mask> ms = rebate::masks(ctx, L, err);
            fh_mark("rb-masks");
            for (const rebate::Mask &m : ms) rebate::add_mask(m, canvas);
            // Where the edge print or the date was drawn, so the print-domain pass
            // leaves its ink alone.
            if (!ms.empty()) {
                rebate_ink.assign(size_t(canvas.w) * size_t(canvas.h), 0);
                for (const rebate::Mask &m : ms) {
                    for (int my = 0; my < m.h; ++my) {
                        const int y = m.y0 + my;
                        if (y < 0 || y >= canvas.h) continue;
                        for (int mx = 0; mx < m.w; ++mx) {
                            const int x = m.x0 + mx;
                            if (x < 0 || x >= canvas.w) continue;
                            if (m.cov[size_t(my) * size_t(m.w) + size_t(mx)] < 0.5f) continue;
                            rebate_ink[size_t(y) * size_t(canvas.w) + size_t(x)] = 255;
                        }
                    }
                }
            }
            fh_mark("rb-ink");
            std::printf("rebate: %s %dx%d -> canvas %dx%d  masks %zu  ops %u  text %u\n",
                        L.format.c_str(), target_width, target_height, canvas.w, canvas.h,
                        ms.size(), ctx.marks.ops, ctx.marks.text);
            // The engine keeps its print on canvases of 900 px and up; under that it
            // is a third of a pixel and it draws nothing. Say so -- an empty rebate
            // otherwise reads as a broken toggle.
            if (std::max(canvas.w, canvas.h) < 900 && ctx.marks.text > 0)
                std::printf("rebate: the print is skipped below 900 px (canvas %d); raise the "
                            "long edge\n", std::max(canvas.w, canvas.h));
            canvas_w = canvas.w;
            canvas_h = canvas.h;
            rebate_canvas.swap(canvas.rgb);
            if (keep_strip) {
                Strip entry;
                entry.key = strip_key;
                entry.width = canvas_w;
                entry.height = canvas_h;
                entry.canvas = rebate_canvas;
                entry.gate = rebate_gate;
                entry.film = rebate_film;
                entry.ink = rebate_ink;
                strip_keep(std::move(entry));
            }
        }
        if (const char *inkp = std::getenv("DUMP_INK")) {
            if (std::FILE *f = std::fopen(inkp, "wb")) {
                std::fprintf(f, "P5\n%d %d\n255\n", canvas_w, canvas_h);
                std::fwrite(rebate_ink.data(), 1, rebate_ink.size(), f);
                std::fclose(f);
                std::printf("wrote ink mask %s (%dx%d)\n", inkp, canvas_w, canvas_h);
            }
        }
        // The canvas is the same film at the same pitch: the gauge grows with it,
        // so the grain keeps its millimetres.
        rebate_frame_w = target_width;
        rebate_frame_h = target_height;
        const double was_short = double(std::min(target_width, target_height));
        rebate_P = P;
        target_width = canvas_w;
        target_height = canvas_h;
        rebate_short_scale = double(std::min(target_width, target_height)) / was_short;
    }

    fh_mark("canvas");
    int32_t width = int32_t(target_width), height = int32_t(target_height);
    size_t frame = size_t(width) * size_t(height);

    // --- 2. the configuration, with the frame's own size ---------------------
    std::vector<float> config = read_f32(prefix + "config.f32", FOTUFILM_FRAME_CONFIGURATION_COUNT);
    if (config.empty()) return 2;
    const int baked_width = int(config[FOTUFILM_CONFIG_FRAME_WIDTH]);
    const int baked_height = int(config[FOTUFILM_CONFIG_FRAME_HEIGHT]);
    std::printf("config %dx%d (baked %dx%d)  grain mode %g\n", width, height,
                baked_width, baked_height, config[FOTUFILM_CONFIG_GRAIN_MODE]);
    physics::FramePhysics frame_physics;
    // The physics sits beside the frame's files, not behind its own prefix.
    const size_t slash = prefix.find_last_of('/');
    const std::string directory = slash == std::string::npos ? std::string() : prefix.substr(0, slash + 1);
    const std::string physics_path = directory + "physio-" + std::to_string(baked_width) + "x"
        + std::to_string(baked_height) + ".txt";
    const bool have_physics = physics::read_physics(physics_path, frame_physics);
    fh_mark("cfg-read");
    // **A medium with no sheet still has an aperture.** `.cineon-log` is the deliverable of a
    // 4K film scanner: 6.1 um per photosite across the Super 35 aperture, so the sample's own
    // footprint is a 0.003 mm Gaussian (PrintPaper.enlargerBlurMM's `.cineonLog` arm). The
    // skeletons carry 0 there -- they were derived from a `.screen` bake -- and the shortfall
    // rides the grain's spread arm: 0.4407 against the engine's 0.5177, and no print MTF at all.
    if (paper_bin == "cineon-log") {
        frame_physics.enlarger_blur_mm = 0.003f;
        frame_physics.enlarger_scan_sharpening = 0.0f;
    }
    // **Before the fold.** `resize_config` turns each millimetre into pixels and a blur radius;
    // scaling the millimetres afterwards would change nothing, which is why halation had no
    // entry point until now.
    for (const auto &kv : phys_muls) {
        const std::string &n = kv.first;
        const float v = kv.second;
        auto scale3 = [&](float (&field)[3]) {
            for (int i = 0; i < 3; ++i) {
                if (n == "halationSigmaMM_" + std::to_string(i) ||
                    n == "emulsionDiffusionMM_" + std::to_string(i) ||
                    n == "renderedClumpMM_" + std::to_string(i) ||
                    n == "renderedMottleMM_" + std::to_string(i) ||
                    n == "grainAmplitude_" + std::to_string(i) ||
                    n == "halationRingRadiusMM_" + std::to_string(i)) field[i] *= v;
            }
        };
        scale3(frame_physics.halation_sigma_mm);
        scale3(frame_physics.emulsion_diffusion_mm);
        scale3(frame_physics.rendered_clump_mm);
        scale3(frame_physics.rendered_mottle_mm);
        scale3(frame_physics.grain_amplitude);
        scale3(frame_physics.halation_ring_mm);
        if (n == "grainClumpsPerMM2") frame_physics.grain_clumps_per_mm2 *= v;
        if (n == "lumaDiffusionMM") frame_physics.luma_diffusion_mm *= v;
        if (n == "couplerDiffusionMM") frame_physics.coupler_diffusion_mm *= v;
        if (n == "adjacencyRadiusMM") frame_physics.adjacency_radius_mm *= v;
        if (n == "enlargerBlurMM") frame_physics.enlarger_blur_mm *= v;
    }
    // The photograph's own gauge; 135 when the file does not say. Every skeleton was baked on
    // 16 mm, which is the wrong piece of film for anything this app develops.
    float frame_short_side_mm = source_sensor_mm;
    if (!(frame_short_side_mm > 0.0f)) frame_short_side_mm = 24.0f;
    // **A photograph is developed as 135 film unless it came off something larger.**
    //
    // The gauge is the width of the piece of film the picture is developed on, and every
    // millimetre-sized structure -- the emulsion's diffusion, halation, the couplers, the grain's
    // clump size and its amplitude -- is multiplied by the pixels-per-millimetre it implies. Read
    // straight off a phone's EXIF, a 1/1.4" sensor measures 4.9 mm, so all of those radii come
    // out 24/4.9 = 4.9x too large: measured on one phone JPEG the 100 % Laplacian falls from
    // 10.39 to 2.36 and the grain, whose amplitude also rides px/mm, reads as noise. A capture
    // smaller than a 135 frame is a 135 frame as far as the print is concerned; only medium and
    // large format move the gauge. `FOTU_MM` still overrides this for probes.
    if (frame_short_side_mm < 24.0f) frame_short_side_mm = 24.0f;
    if (const char *mm = std::getenv("FOTU_MM")) frame_short_side_mm = float(std::atof(mm));
    // **The app's own gauge wins.** A phone sensor that reports 5 mm is not a
    // 5 mm frame, and the whole physical chain -- grain size, halation radius,
    // MTF -- is scaled from this one number, which is why a wrong one reads as
    // "mush". The user picks the format; this is where it lands.
    if (g_frame_mm > 0.0f) frame_short_side_mm = g_frame_mm;
    // With a rebate the frame the kernels see is the whole strip, so its short
    // side is the film's, not the gate's. Same pixels per millimetre either way.
    if (rebate_short_scale != 1.0) frame_short_side_mm *= float(rebate_short_scale);
    // **A crop does not coarsen the grain.** The engine records the *source's*
    // long edge and measures its millimetre figures against that, so the same
    // piece of film keeps the same grain whatever the user kept of it. Here the
    // gauge is what `resize_config` divides the frame's short side by, so the
    // frame's short side is the one the photograph would have had uncropped at
    // this output size -- and the cover zoom is not compensated either, for the
    // same reason: the engine does not compensate it.
    if (g_geometry.active && target_width > 0 && target_height > 0
        && image.width > 0 && image.height > 0) {
        const double long_out = double(std::max(target_width, target_height));
        const double short_uncropped = long_out
            * double(std::min(image.width, image.height))
            / double(std::max(image.width, image.height));
        if (short_uncropped > 1.0)
            frame_short_side_mm *= float(double(std::min(target_width, target_height)) / short_uncropped);
    }
    // **Not only when the size changed.** A `--physmul` moves a millimetre, and the only thing
    // that turns a millimetre into the pixel radius the kernel reads is this fold; skipping it at
    // the baked size left the multiplier with nothing to act on. At the baked size the fold is
    // the identity, so nothing else moves.
    if (have_physics) {
        if (!resize_config(config, frame_physics, baked_width, baked_height, width, height,
                           frame_short_side_mm)) {
            std::printf("cannot re-derive the pixel slots from %s\n", physics_path.c_str());
            return 2;
        }
        std::printf("re-derived the pixel slots at %.3f px/mm (frame %.2f mm, baked gauge %.3f)\n",
                    float(std::min(width, height)) / frame_short_side_mm, frame_short_side_mm,
                    float(std::min(baked_width, baked_height)) / frame_physics.px_per_mm);
    } else {
        if (!have_physics && (baked_width != width || baked_height != height)) {
            std::printf("no %s: the slots are still the baked size's; "
                        "the frame will not match\n", physics_path.c_str());
        }
        config[size_t(FOTUFILM_CONFIG_FRAME_WIDTH)] = float(width);
        config[size_t(FOTUFILM_CONFIG_FRAME_HEIGHT)] = float(height);
    }


    // --- 2b. the film grain's tiles ------------------------------------------
    // Grain mode 1 reads a bank of laid tiles: this stock's coating, rendered once and
    // sampled many times. The Android engine has no Halide tile builder, so it lays them in
    // Swift from the population beside the skeleton; the same population lays the same tiles
    // here, and the configuration carries what the kernel blends them by.
    fh_mark("resize");
    if (!grain_records_path.empty()) {
        // The bank is 13.5 MB a stock and takes seconds to lay; a process that renders the
        // same film twice must not pay twice.
        static std::string held_path;
        static std::vector<float> held_bank;
        static std::vector<grain::TileLevel> held_levels[3];
        static bool held_monochrome = false;
        static std::vector<grain::Record> held_records;
        if (held_path != grain_records_path) {
            int mark_shape = 0, samples = 0;
            std::string tag;
            if (!grain::read_records_text(grain_records_path.c_str(), held_records, held_monochrome,
                                          mark_shape, samples, tag)) return 2;
            if (mark_shape != grain::kMarkShape || samples != grain::kTableSamples) {
                std::printf("grain: %s was laid with markShape %d / %d samples, not this port's %d / %d\n",
                            tag.c_str(), mark_shape, samples, grain::kMarkShape, grain::kTableSamples);
                return 2;
            }
            std::printf("grain: %s monochrome=%d sublayers %zu/%zu/%zu\n", tag.c_str(),
                        held_monochrome ? 1 : 0, held_records[0].sublayers.size(),
                        held_records[1].sublayers.size(), held_records[2].sublayers.size());
            // The cache key carries the population's own size, so a stock whose crystals were
            // re-laid never reads a bank from the older ones.
            struct stat records_stat {};
            const long stamp = stat(grain_records_path.c_str(), &records_stat) == 0
                             ? long(records_stat.st_size) : 0L;
            // **Its own directory.** The table cache prunes by file count -- eighteen of the
            // 574 KB tables -- and a 13.5 MB bank in the same listing would be thrown out by a
            // rule written for tables.
            const std::string tile_dir = table_cache_dir.empty()
                ? std::string() : (table_cache_dir + "/tiles");
            if (!tile_dir.empty()) ::mkdir(tile_dir.c_str(), 0700);
            const std::string cached = tile_dir.empty()
                ? std::string() : tilecache::path_for(tile_dir, tag, stamp);
            if (!cached.empty() && tilecache::load(cached, held_bank, held_levels)) {
                std::printf("grain: tiles from %s\n", cached.c_str());
                held_path = grain_records_path;
            } else {
                std::fprintf(stderr, "[grain] laying %zu records, record1 %zu sublayers\n",
                             held_records.size(), held_records[1].sublayers.size());
                held_bank = grain::build_tiles(held_records, held_monochrome, held_levels);
                std::fprintf(stderr, "[grain] laid %zu floats\n", held_bank.size());
                if (!cached.empty() && tilecache::save(cached, held_bank, held_levels)) {
                    std::printf("grain: tiles cached to %s\n", cached.c_str());
                    tilecache::trim(tile_dir, 3);
                }
                held_path = grain_records_path;
            }
        }
        const int32_t tile_id = 1;
        if (fotufilm_halide_set_film_tiles(tile_id, held_bank.data(), int64_t(held_bank.size())) != 0) {
            std::printf("grain: the backend refused the tiles\n");
            return 2;
        }
        const float short_side = frame_short_side_mm > 0.0f ? frame_short_side_mm : 24.0f;
        const float px_per_mm = float(std::min(width, height)) / short_side;
        grain::Look look;
        for (int i = 0; i < 3; ++i) look.layers[i] = grain_layers[i];
        look.colour = grain_colour;
        look.size = grain_size;
        look.softness = grain_softness;
        const std::vector<float> block = grain::configuration_block(
            held_records, held_monochrome, held_levels, px_per_mm, 1.0f, look, float(tile_id));
        for (size_t i = 0; i < block.size() && size_t(FOTUFILM_CONFIG_FILM_TILE) + i < config.size(); ++i)
            config[size_t(FOTUFILM_CONFIG_FILM_TILE) + i] = block[i];
        config[size_t(FOTUFILM_CONFIG_GRAIN_MODE)] = 1.0f;
        std::printf("grain: tiles id=%d pitch=%.4f footprint=%.4f px/mm=%.3f size=%.3f softness=%.3f\n",
                    tile_id, block[0], block[1], px_per_mm, look.size, look.softness);
    }

    fh_mark("config");
    // --- 3. scene planes, out of the associated RGBA the decoder hands over --
    if (!scene_path.empty()) {
        if (std::FILE *f = std::fopen(scene_path.c_str(), "wb")) {
            const float *src = source_rgba.empty() ? image.rgba : source_rgba.data();
            for (size_t i = 0; i < frame; ++i)
                std::fwrite(&src[i * 4], sizeof(float), 3, f);
            std::fclose(f);
            std::printf("wrote %s  %dx%d  scene\n", scene_path.c_str(), width, height);
        }
    }
    const float *scene = source_rgba.empty() ? decoded : source_rgba.data();
    // **Two scenes, on purpose.** `red/green/blue` is the photograph -- what the
    // meter reads and what the tone grid is solved on, exactly as before the
    // rebate existed. `canvas_*` is the same photograph on a strip of film,
    // which is what the kernels develop. With the rebate off the two are the
    // same vector and nothing about this duplicates work.
    const int meter_w = rebate_frame_w > 0 ? rebate_frame_w : width;
    const int meter_h = rebate_frame_h > 0 ? rebate_frame_h : height;
    const size_t meter_px = size_t(meter_w) * size_t(meter_h);
    std::vector<float> red(meter_px), green(meter_px), blue(meter_px);
    // Element-wise: 36 ms of strided reads on a 2240x2068 canvas, and the meter is
    // the only reader, so a row split changes nothing it can see.
    parallel_rows(meter_h, [&](int y) {
        const size_t row = size_t(y) * size_t(meter_w);
        const float *src = scene + row * 4;
        for (int x = 0; x < meter_w; ++x) {
            red[row + size_t(x)] = src[size_t(x) * 4 + 0];
            green[row + size_t(x)] = src[size_t(x) * 4 + 1];
            blue[row + size_t(x)] = src[size_t(x) * 4 + 2];
        }
    });
    std::vector<float> canvas_r, canvas_g, canvas_b;
    if (!rebate_canvas.empty()) {
        if (rebate_canvas.size() != frame * 3) {
            std::printf("rebate: canvas is %zu floats, the frame needs %zu\n",
                        rebate_canvas.size(), frame * 3);
            return 2;
        }
        canvas_r.resize(frame);
        canvas_g.resize(frame);
        canvas_b.resize(frame);
        parallel_rows(height, [&](int y) {
            const size_t row = size_t(y) * size_t(width);
            const float *src = rebate_canvas.data() + row * 3;
            for (int x = 0; x < width; ++x) {
                canvas_r[row + size_t(x)] = src[size_t(x) * 3 + 0];
                canvas_g[row + size_t(x)] = src[size_t(x) * 3 + 1];
                canvas_b[row + size_t(x)] = src[size_t(x) * 3 + 2];
            }
        });
    }
    const float *dev_r = canvas_r.empty() ? red.data() : canvas_r.data();
    const float *dev_g = canvas_g.empty() ? green.data() : canvas_g.data();
    const float *dev_b = canvas_b.empty() ? blue.data() : canvas_b.data();
    if (!scene_from_cache) ffc_image_free(&image);

    // The scene planes can also come straight from a dump, which is how a table change gets
    // measured without the decoder standing between the two runs.
    if (!input_prefix.empty()) {
        red = read_f32(input_prefix + "input-r.f32", frame);
        green = read_f32(input_prefix + "input-g.f32", frame);
        blue = read_f32(input_prefix + "input-b.f32", frame);
        if (red.empty() || green.empty() || blue.empty()) return 2;
        std::printf("scene planes from %s\n", input_prefix.c_str());
    }

    fh_mark("planes");
    // --- 4. the spectral tables ---------------------------------------------
    constexpr int32_t kLutDimension = 33;
    constexpr size_t kLutCount = size_t(kLutDimension) * kLutDimension * kLutDimension * 4;
    std::vector<float> exposure, film, paper;
    film::Spectra stock;
    bool stock_loaded = false;
    if (resources_dir.empty()) {
        exposure = read_f32(prefix + "exposure.f32", kLutCount);
        film = read_f32(prefix + "film.f32", kLutCount);
        paper = read_f32(prefix + "paper.f32", kLutCount);
    } else {
        // Built here instead of read: the whole point of the port. Nothing on this path
        // touches the dump's three tables.
        if (!film::read_stocks(resources_dir + "/stocks.bin", stock_id, stock)) {
            std::printf("no stock %s in %s/stocks.bin\n", stock_id.c_str(), resources_dir.c_str());
            return 2;
        }
        stock_loaded = true;
        pf_film_from(stock);
        // Room-temperature ageing moves the curves before anything reads them, exactly as
        // `FilmStock.expired(years:)` does; the grain it adds rides the configuration instead.
        if (expired_years > 0.0f) stock = film::aged(stock, expired_years);
        paper::Stock sheet;
        std::string paper_error;
        const std::string bin = paper_bin.empty() ? "paper_crystal.bin" : paper_bin;
        // **A medium with no print material still needs a sheet object.** `.negative` and
        // `.cineon-log` are outputs, not papers, so the medium name arrives in the paper slot;
        // they have no bin. The film stage below reads a sheet's spectra for its lamp and
        // receiver, so it gets the measured RA-4 sheet the other media share -- exactly what the
        // engine's own default receiver is -- and their tables never read the sheet's curves.
        const bool medium_without_sheet = bin == "negative" || bin == "cineon-log";
        const std::string sheet_name = medium_without_sheet ? "paper_ektacolor_edge.bin" : bin;
        // The JNI hands the sheet over by bare name, and an Android process runs with `/` as its
        // working directory, not the assets folder. Anything without a directory is resolved
        // against the resources, the same way the stocks and the reflectance prior already are.
        const std::string paper_path =
            sheet_name.find('/') == std::string::npos ? resources_dir + "/" + sheet_name : sheet_name;
        if (!paper::load(paper_path, sheet, paper_error)) {
            std::printf("%s\n", paper_error.c_str());
            return 2;
        }
        pf_paper_from(sheet);
        spectral::ReflectanceTable model;
        if (!model.load(resources_dir + "/rec2020-reflectance-prior.coeff")) {
            std::printf("cannot load the reflectance prior\n");
            return 2;
        }
        // --- the table cache -------------------------------------------------
        //
        // The three tables depend on the stock, the sheet and the medium only: no slider, no
        // frame size, no measurement. So the first render of a pair pays for them (about a
        // third of a second) and every later one reads 574 KB apiece instead.
        std::string medium = screen_medium ? "screen" : bin;
        if (expired_years > 0.0f) medium += "_x" + std::to_string(expired_years);
        if (bleach > 0.0f) medium += "_bl" + std::to_string(bleach);
        // The paper table is the sheet read under a lamp, and the screen table is one receiver:
        // both belong in the key, or the first choice made would be cached for every later one.
        if (!g_print_light.empty()) medium += "_pl" + g_print_light;
        if (!g_digital_reference.empty()) medium += "_dr" + g_digital_reference;
        if (g_enlarger) medium += "_en1";
        if (g_printer) {
            medium += "_pr" + std::to_string(g_printer_lamp) + "_"
                    + std::to_string(g_printer_m) + "_" + std::to_string(g_printer_y)
                    + "_" + std::to_string(g_printer_rgb[0]) + "_"
                    + std::to_string(g_printer_rgb[1]) + "_"
                    + std::to_string(g_printer_rgb[2]);
        }
        // Callier: only a condenser head has one, and only where an enlarger actually images the
        // film onto a reflection sheet. FilmStock.isMonochrome picks the silver figure.
        float enlarger_callier = 1.0f;
        if (g_enlarger && !screen_medium && !stock.reversal && !stock.reflection_print) {
            enlarger_callier = stock.monochrome ? 1.4f : 1.05f;
        }
        const auto cache_path = [&](const char *kind) -> std::string {
            if (table_cache_dir.empty()) return std::string();
            if (std::strcmp(kind, "exposure") == 0)
                return table_cache_dir + "/exposure_" + stock_id + ".f32";
            return table_cache_dir + "/" + stock_id + "__" + medium + "__" + kind + ".f32";
        };
        const auto read_table = [&](const char *kind, std::vector<float> &out) {
            const std::string path = cache_path(kind);
            if (path.empty()) return false;
            out = read_f32(path, kLutCount);
            return out.size() == kLutCount;
        };
        const auto write_table = [&](const char *kind, const std::vector<float> &v) {
            const std::string path = cache_path(kind);
            if (path.empty() || v.size() != kLutCount) return;
            if (std::FILE *f = std::fopen(path.c_str(), "wb")) {
                std::fwrite(v.data(), sizeof(float), v.size(), f);
                std::fclose(f);
            }
        };
        const bool from_cache = read_table("exposure", exposure) & read_table("film", film)
                              & read_table("paper", paper);
        if (from_cache) std::printf("tables from the cache: %s / %s\n", stock_id.c_str(), medium.c_str());

        const char *table_error = "";
        // **Two outputs have no print material.** `.negative` shows the developed dyes themselves;
        // `.cineon-log` writes their printing density as Cineon code values. Neither has a sheet,
        // so neither has a bin: the medium name arrives in the paper slot and the paper stage is
        // taken out of the picture here rather than by shipping a bin that lies.
        // **Set before it is read.** The assignment used to sit below this line, so the flag was
        // still false here and `.negative` never entered this branch at all: it fell through to
        // the print path and produced a plain film-table render (measured 47/47/57 against the
        // engine's 128/81/58).
        medium_negative = paper_bin == "negative";
        medium_cineon = paper_bin == "cineon-log";
        g_code_values = medium_cineon;
        if (medium_negative) screen_medium = true;     // the same plumbing as Digital Reference
        if (!from_cache) std::printf("building the tables for %s on %s\n", stock_id.c_str(), bin.c_str());
        // Set here, not inside the table block: the tone stage below needs to know that this
    // medium times nothing, and the block's own scope ends before it.
    if (!from_cache) {
        exposure::build(stock, model, exposure);
        // A transparency cannot be printed onto a negative-positive sheet -- the film is already a
    // positive. The app's rule is "no paper, scan it directly", and the same rule belongs here:
    // otherwise a reversal stock paired with ektacolor would take the negative printing path and
    // come out wrong (max 1.0 against the engine).
    if (!screen_medium && stock.reversal && sheet.process != paper::Process::PositivePaper) {
        screen_medium = true;
        std::printf("transparency on a negative sheet: reading the film directly\n");
    }

    if (screen_medium) {
            // Digital Reference: equal-energy lamp, the receiver's own sensitivity, and the
            // graded output table. No sheet takes part in the film stage.
            // A stock whose minimum densities are all zero (monochrome, transparency) has no
            // base to subtract; the film stage then takes no offset at all, as the engine does.
            std::vector<float> offset;
            if (!film::density_offset(stock, stock.curves, 1.0f, offset)) offset.clear();
            paper::Stock receiver;
            // The receiver's bands are the measured RA-4 paper's own; the bin is the same
            // array the reflection path uses, so nothing new has to be shipped.
            std::string receiver_error;
            // The receiver's bands are a measured RA-4 sheet's own. A caller that passed a real
            // path keeps it; a bare name is the screen's placeholder and is resolved against the
            // resources instead, because the working directory is not the assets folder.
            const std::string receiver_path =
                (!bin.empty() && bin.find('/') != std::string::npos)
                    ? bin : resources_dir + "/paper_ektacolor_edge.bin";
            if (!paper::load(receiver_path, receiver, receiver_error)) {
                std::printf("%s\n", receiver_error.c_str());
                return 2;
            }
            spectral::Spd flat{};
            for (int i = 0; i < spectral::kCount; ++i) flat[size_t(i)] = 1.0f;
            if (medium_negative) {
                // `.negative`: the light box's view of the developed film. The kernel hands the
                // table the negative's own activation -- nothing was inverted upstream -- and the
                // cube is display RGB, the base's brightest channel on `lightBoxBaseLevel`.
                negative_viewing::build(stock, film, table_error);
            } else if (stock.reflection_print) {
                // An integral instant sheet never reaches a second emulsion: its table is the
                // direct view's display-RGB cube, base divided out and the fitted cubic basis
                // standing in for the Status A unmix.
                film_direct::build(stock, film, table_error);
            } else if (stock.monochrome) {
                film_screen::build_monochrome(stock, film, table_error);
            } else if (stock.reversal) {
                film_screen::build_reversal(stock, film, table_error);
            } else {
                const int dr_style = g_digital_reference == "reference-exposure" ? 0
                    : (g_digital_reference == "graded-print" ? 1 : 2);
                film_screen::build(stock, receiver.spectra.sensitivity, offset, flat, film,
                                   table_error, dr_style);
            }
            screen_ref::Style screen_style = screen_ref::Style::AutoLevels;
            if (g_digital_reference == "reference-exposure")
                screen_style = screen_ref::Style::ReferenceExposure;
            else if (g_digital_reference == "graded-print")
                screen_style = screen_ref::Style::GradedPrint;
            tables::build_screen(stock, screen_style, 2.0f, paper, table_error);
            if (medium_negative) {
                // **`.negative` has no paper table at all.** Swift returns `paperOutput: nil` for
                // it; a receiver table here only adds light that the medium never had. Zeroed
                // unless a caller asks otherwise, so the A/B is one environment variable.
                const bool keep = std::getenv("FOTU_NEG_PAPER") != nullptr;
                if (!keep) std::fill(paper.begin(), paper.end(), 0.0f);
            }
        } else {
            spectral::Spd printer_filtered;
            const spectral::Spd *printer_lamp = nullptr;
            if (g_printer) {
                printer_filtered = spectral::printer_lamp(g_printer_lamp, g_printer_m, g_printer_y);
                printer_lamp = &printer_filtered;
            }
            // The release printer's three lights: one printer point is 0.025 log exposure and
            // 25 is the aim, so the gain is 10^((points - 25) * 0.025) per beam.
            std::array<float, 3> beam_gain = {1.0f, 1.0f, 1.0f};
            const bool lights_moved = g_printer
                && (g_printer_rgb[0] != 25.0f || g_printer_rgb[1] != 25.0f
                    || g_printer_rgb[2] != 25.0f);
            if (lights_moved) {
                for (int b = 0; b < 3; ++b)
                    beam_gain[size_t(b)] = std::pow(10.0f, (g_printer_rgb[b] - 25.0f) * 0.025f);
            }
            // `.cineon-log` reads the film through the telecine receiver's own bands, not through
            // a sheet's sensitivity: SMPTE RP 180's three passbands, which is what the engine's
            // `sensitivity(bands:)` answers for a medium with no print material. A substituted
            // RA-4 sheet is close -- 0.065 log on c200 -- and visibly wrong.
            if (medium_cineon) {
                const auto &bands = telecine_bands::sensitivity();
                for (int c = 0; c < 3; ++c) {
                    sheet.spectra.sensitivity[size_t(c)].assign(bands[size_t(c)].begin(),
                                                                bands[size_t(c)].end());
                }
            }
            // The lamp is a code-value medium's too: nothing here prints, and the exposure model
            // is the scanner's. Selected by experiment against the engine's own table.
            spectral::Spd scan_lamp;
            const spectral::Spd *scan_lamp_ptr = nullptr;
            if (medium_cineon) {
                // **Equal energy**, the same lamp Digital Reference uses: a medium that writes code
                // values has no enlarger and no viewer. Measured against the engine's own table the
                // alternatives are 6.6e-2 (3200 K), 5.3e-2 (D50) and 3.0e-2 (D65) away; this one is
                // 2.0e-6.
                const char *want = std::getenv("FOTU_CINEON_LAMP");
                const std::string mode = want ? want : "flat";
                if (mode == "d50") scan_lamp = spectral::daylight(5003.0f);
                else if (mode == "d65") scan_lamp = spectral::daylight(6504.0f);
                else if (mode == "flat") { for (int i = 0; i < spectral::kCount; ++i) scan_lamp[size_t(i)] = 1.0f; }
                else scan_lamp = spectral::planckian(3200.0f);
                scan_lamp_ptr = &scan_lamp;
            }
            tables::build_film(stock, sheet, film, table_error, scan_lamp_ptr, bleach,
                               enlarger_callier, printer_lamp,
                               lights_moved ? &beam_gain : nullptr);
            spectral::Spd light;
            const spectral::Spd *override_light = nullptr;
            if (g_print_light == "d65") { light = spectral::daylight(6504.0f); override_light = &light; }
            else if (g_print_light == "d50") { light = spectral::daylight(5003.0f); override_light = &light; }
            else if (g_print_light == "tungsten") { light = spectral::planckian(2856.0f); override_light = &light; }
            if (medium_cineon) {
                // The sheet took no part in the film stage above, and its table is not a sheet's:
                // the medium delivers code values, so nothing is inverted, viewed or finished.
                cineon_log::build(paper, table_error);
            } else {
                tables::build_paper(sheet, true, paper, table_error, override_light,
                                    stock.monochrome);
            }
        }
        std::printf("tables built: %zu + %zu + %zu floats\n",
                    exposure.size(), film.size(), paper.size());
        write_table("exposure", exposure);
        write_table("film", film);
        write_table("paper", paper);
        }
    }
    if (exposure.empty() || film.empty() || paper.empty()) return 2;
    fh_mark("tables");
    // The dump's own mask and seed, unless the caller overrides them: the feature bits decide
    // which stages the kernel runs, so a re-derivation that guesses them develops a different
    // picture whatever the configuration says.
    {
        std::ifstream meta(prefix + "meta.txt");
        std::string line;
        while (std::getline(meta, line)) {
            if (!have_mask && line.rfind("feature_mask=", 0) == 0) {
                mask = int32_t(std::strtol(line.c_str() + 13, nullptr, 10));
            } else if (!have_seed && line.rfind("seed=", 0) == 0) {
                seed = uint32_t(std::strtoul(line.c_str() + 5, nullptr, 10));
            }
        }
        std::printf("meta %s -> mask 0x%x seed %u\n",
                    (prefix + "meta.txt").c_str(), mask, seed);
    }
    // The meta's own value stands; the default is only for a dump without one.
    if (!have_mask && mask == 0) mask = 1 << 8;    // float IO: what the kernels always take here
    if (!have_seed && seed == 0) seed = 1179208781u;

    // --- 4b. Auto Levels: the slots a develop rewrites from its own frame ------
    //
    // The baked skeleton carries the measurement of the frame it was baked from. Recompute that
    // frame and this one, and write only the difference -- what
    // `FilmEngineInvocation.setToneBase` does on its own CPU. The scale is scene-independent
    // (ratio 1), so only the mid-points, the exposure and the two tone controls move.
    // The tone grid the highlights/shadows controls feed is built from this same measurement.
    tone::Measurement frame_meter;
    bool frame_meter_valid = false;

    if (!meter_prefix.empty()) {
        if (!stock_loaded) { std::printf("--meter needs --self-tables and --stock\n"); return 2; }
        // The baseline: what this stock metered on the scene it was baked from, eight floats
        // beside its skeleton. Re-measuring that scene would need its planes in the package.
        const std::vector<float> baked = read_f32(meter_prefix + "meter.bin", 8);
        if (baked.size() != 8) {
            std::printf("no meter.bin beside %s\n", meter_prefix.c_str());
            return 2;
        }
        tone::Metered old_levels;
        old_levels.scale[0] = old_levels.scale[1] = old_levels.scale[2] = baked[0];
        old_levels.shift[0] = baked[1];
        old_levels.shift[1] = baked[2];
        old_levels.shift[2] = baked[3];
        old_levels.film_boost = baked[4];
        old_levels.shadow_lift = baked[5];
        old_levels.highlight_hold = baked[6];
        old_levels.tone_key = baked[7];
        old_levels.has_tone_key = true;
        old_levels.valid = true;

        const float balance[3] = {1.0f, 1.0f, 1.0f};
        tone::Measurement &now_meter = frame_meter;
        now_meter.init(meter_w, meter_h, balance, 1.0f, true);
        now_meter.add(red.data(), green.data(), blue.data(), meter_w, meter_h, true);
        const tone::Metered new_levels = tone::meter(now_meter, stock, 0.0f);
        if (old_levels.valid && new_levels.valid) {
            // **Absolute, not a delta.** The metered midpoints were applied as
            // `config += new - old`, which is only right when `config` and `meter.bin` came from
            // the same bake. The per-medium skeletons and the 47 stock-level meter.bin dumps were
            // baked from different photographs: on one Agfachrome frame the two disagreed by
            // 0.058, so the delta came out zero and the print kept the *other* scene's mid-grey --
            // 3.5 levels of uniform brightness against the Swift engine on the same file.
            config[8695] = new_levels.shift[0];
            config[62] = new_levels.shift[1];
            config[8696] = new_levels.shift[2];
            config[61] *= std::exp2(new_levels.film_boost - old_levels.film_boost);
            config[453] = -std::min(new_levels.highlight_hold, 1.0f);
            config[454] = std::min(new_levels.shadow_lift, 1.0f);
            frame_meter_valid = true;
            auto dbg = now_meter.region_stops();
            tone::SceneStops sc{0, 0, 0};
            tone::scene_stops(dbg, sc);
            std::printf("scene: median %+.4f bright %+.4f dark %+.4f  (cells %d)\n",
                        sc.median, sc.bright, sc.dark, int(dbg.size()));
            if (const char *dmp = std::getenv("SPK_METER_DUMP")) {
                if (std::FILE *f = std::fopen(dmp, "wb")) {
                    std::fwrite(config.data(), sizeof(float), config.size(), f);
                    std::fclose(f);
                    std::printf("metered config -> %s\n", dmp);
                }
            }
            std::printf("meter: boost %+.4f -> %+.4f | r/b %+.4f %+.4f | lift %.4f hold %.4f\n",
                        old_levels.film_boost, new_levels.film_boost,
                        new_levels.shift[0] - old_levels.shift[0],
                        new_levels.shift[2] - old_levels.shift[2],
                        new_levels.shadow_lift, new_levels.highlight_hold);
        } else {
            std::printf("meter: not measured (valid old=%d new=%d)\n", old_levels.valid, new_levels.valid);
        }
    }

    // --- 4c. the film's exposure boost, from this frame's own highlight --------
    //
    // `FilmEngineInvocation.build` writes EXPOSURE_GAIN = exp2(exposureEV + filmBoost), and
    // `filmBoost` is `exposureEV - toneKey(white)` on the frame's *retimed* highlight. A
    // skeleton baked from another photograph therefore carries that photograph's boost -- on
    // one C200 frame a whole stop out, which is a 32-level shift in the print.
    //
    // Nothing else in the tone base moves with the scene: the levels are keyed on
    // `white + boost`, which is the print's own white, so `levels.shift` is constant and the
    // baked one stands. Only this slot is rewritten. (The measured shift and lift belong to
    // the *bake's* scene and are left where the skeleton put them, because the engine only
    // applies them through `setToneBase`, which the screen path does not reach.)
    // **Only on the Digital Reference medium.** A real paper's skeleton already carries the
    // Auto Levels its own bake measured, in the paper's density coordinates; rewriting the gain
    // here (the shift lives on another axis entirely) moves the print by half a stop.
    // `.negative` shows the film, it does not time a print: there is no sheet to re-expose, so
    // the frame's own measurement must not move the gain or the tone grid. (Swift's light-box
    // table is indexed by the negative's density and takes no part in Auto Levels at all.)
    if (stock_loaded && !meter_explicit && screen_medium && !medium_negative) {
        const float balance[3] = {wb[0], wb[1], wb[2]};
        tone::Measurement meter;
        meter.init(meter_w, meter_h, balance, 1.0f, true);
        meter.global_read = (g_local_tone == 0);
        meter.add(red.data(), green.data(), blue.data(), meter_w, meter_h, true);
        std::vector<float> stops = meter.region_stops();
        tone::SceneStops scene{0.0f, 0.0f, 0.0f};
        if (tone::scene_stops(stops, scene)) {
            const tone::Metered metered = tone::meter(meter, stock, ev);
            // The two tone controls are this frame's own on every medium that times a print.
            // A skeleton baked from another photograph carries that frame's lift -- 0.733 on
            // C200 where this scene wants 0.665, which is 2.4 levels of shadow -- and the
            // kernel reads these slots directly, so they have to be rewritten here.
            if (metered.valid) {
                config[size_t(453)] = -std::min(metered.highlight_hold, 1.0f);
                config[size_t(454)] = std::min(metered.shadow_lift, 1.0f);
            }
            const float white = tone::retime_highlight(scene, ev);
            if (tone::keys_tone(stock)) {
                // A negative's levels are keyed on `white + boost`, and `boost` is
                // `ev - white + 3`: the sum is the print's own white, three stops over grey,
                // whatever the scene. So the re-time shift and the tone compression come out
                // the same and the skeleton's stand -- only the gain follows this frame.
                const float boost = tone::film_boost(stock, white, ev);
                config[size_t(FOTUFILM_CONFIG_EXPOSURE_GAIN)] = std::exp2(boost);
                // The red and blue mid-points are the green one plus this scene's own cast, so
                // they follow the frame the same way the gain does. Left baked they carry the
                // bake's scene: 0.0036 apart on C200, which is a sixth of a level -- invisible,
                // but it is also the only thing keeping the Digital Reference medium off 1:1.
                if (metered.valid) {
                    config[size_t(8695)] = config[size_t(FOTUFILM_CONFIG_PAPER_MIDPOINT)]
                        + (metered.shift[0] - metered.shift[1]);
                    config[size_t(8696)] = config[size_t(FOTUFILM_CONFIG_PAPER_MIDPOINT)]
                        + (metered.shift[2] - metered.shift[1]);
                }
                std::printf("boost: scene median %+.4f bright %+.4f -> white %+.4f boost %+.4f (gain %.5f)\n",
                            scene.median, scene.bright, white, boost, std::exp2(boost));
            } else if (stock.reversal) {
                // A transparency has no such cancellation: `filmBoost` is zero and the levels
                // read `white` itself, so the shift moves with the frame. Left as baked it is a
                // whole stop out on another scene -- twelve levels on Provia.
                // **Written, not added.** For a transparency `levels.shift` is the mid-point
                // outright -- there is no `meterLevels` baseline under it, the way a negative's
                // levels cancel out -- so the skeleton's own value has to go.
                const tone::Levels levels = tone::levels_for(stock, true, white, ev);
                config[size_t(8695)] = levels.shift;
                config[size_t(FOTUFILM_CONFIG_PAPER_MIDPOINT)] = levels.shift;
                config[size_t(8696)] = levels.shift;
                std::printf("boost: reversal white %+.4f -> shift %+.5f\n", white, levels.shift);
            } else if (medium_negative && std::getenv("FOTU_NEG_MID") != nullptr) {
                // A viewed negative is a directly-viewed medium like a transparency: the mid is
                // `levels.shift` outright, not an offset under a baked baseline. Measured through
                // the engine on c200 it is the difference between a washed frame (G/R 0.75) and
                // the engine's 128/81/58 (G/R 0.63).
                const tone::Levels levels = tone::levels_for(stock, false, white, ev);
                config[size_t(8695)] = levels.shift;
                config[size_t(FOTUFILM_CONFIG_PAPER_MIDPOINT)] = levels.shift;
                config[size_t(8696)] = levels.shift;
                std::printf("boost: negative white %+.4f -> mid %+.5f\n", white, levels.shift);
            }
        }
    }

    // --- 4d. Shadow and Highlight Detail ---------------------------------------
    //
    // A print's deepest tones sit on the paper's shoulder and its brightest on its toe, where a
    // stop of the negative moves the density hardly at all. Lifting them on the finished picture
    // only greys the black and the white; the hold moves the printing light instead, one log
    // exposure the same on every record, so the ends print back on the steeper part.
    if (screen_medium && (shadow_detail > 0.0f || highlight_detail > 0.0f)) {
        curve::Curve curves[3] = {screen_ref::straight_curve(), screen_ref::straight_curve(),
                                  screen_ref::straight_curve()};
        float midpoints[3];
        for (int i = 0; i < 3; ++i) {
            midpoints[i] = screen_ref::log_exposure_of(
                curves[i], curves[i].d_min + screen_ref::kAnchorDensity);
        }
        const print_detail::Shown shown =
            print_detail::detail_densities(stock, screen_ref::Style::AutoLevels, 2.0f);
        float slots[print_detail::kCount];
        print_detail::packed(shadow_detail, highlight_detail, curves, midpoints, shown, slots);
        // Only a print that takes the hold reads the film's corners.
        print_detail::corners(film, 33, &slots[4]);
        for (int i = 0; i < print_detail::kCount; ++i) {
            config[size_t(FOTUFILM_CONFIG_PRINT_DETAIL) + size_t(i)] = slots[i];
        }
        mask |= 1 << 15;    // FOTUFILM_FRAME_PRINT_DETAIL
        std::printf("detail: shadows %.3f highlights %.3f knee %.5f white %.5f corners %.4f %.4f %.4f / %.4f %.4f %.4f\n",
                    slots[0], slots[1], slots[2], slots[3], slots[4], slots[5], slots[6],
                    slots[7], slots[8], slots[9]);
    }

    // --- 4e. the print correction --------------------------------------------
    // `printingContrastScale(correction:paper:)`: the sheet's neutral printing balance
    // interpolated from one. Zero leaves the slots at the bake's own [1,1,1].
    if (print_correction_value > 0.0f && stock_loaded && !medium_negative && !medium_cineon) {
        // The sheet is loaded inside the table branch; a correction only ever runs with the
        // tables on, but the load is cheap and keeps this block self-contained.
        paper::Stock pc_sheet;
        std::string pc_error;
        const std::string pc_bin = paper_bin.empty() ? "paper_crystal.bin" : paper_bin;
        const std::string pc_path =
            pc_bin.find('/') == std::string::npos ? resources_dir + "/" + pc_bin : pc_bin;
        if (!paper::load(pc_path, pc_sheet, pc_error)) {
            std::printf("print correction: %s\n", pc_error.c_str());
        } else {
        float balance[3];
        print_correction::neutral_printing_balance(stock, pc_sheet, balance);
        for (int c = 0; c < 3; ++c) {
            config[size_t(FOTUFILM_CONFIG_MASKING + c)] =
                1.0f + print_correction_value * (balance[size_t(c)] - 1.0f);
        }
        std::printf("print correction %.3f -> masking %.4f %.4f %.4f (balance %.4f %.4f %.4f)\n",
                    print_correction_value, config[size_t(FOTUFILM_CONFIG_MASKING)],
                    config[size_t(FOTUFILM_CONFIG_MASKING + 1)],
                    config[size_t(FOTUFILM_CONFIG_MASKING + 2)],
                    balance[0], balance[1], balance[2]);
        }
    }

    // --- 4f. the halation's colour -------------------------------------------
    // `Halation.kernel`'s mix is `s / (1 + s)` of the returned fraction, and the matrix's
    // rows split that share by the stock's return spectrum. `halationSourceColour` pulls the
    // three fractions toward the brightest before either is formed, so the matrix keeps its
    // row shapes and only the shares move; the blur weights do not see the colour at all.
    if (halation_colour > 0.0f) {
        float strength[3], mix_old[3], mix_new[3];
        for (int c = 0; c < 3; ++c) {
            mix_old[c] = config[size_t(FOTUFILM_CONFIG_HALATION + c)];
            strength[c] = mix_old[c] / std::max(1.0f - mix_old[c], 1e-6f);
        }
        const float top = std::max(strength[0], std::max(strength[1], strength[2]));
        const float t = std::min(halation_colour, 1.0f);
        for (int c = 0; c < 3; ++c) {
            const float v = strength[c] + t * (top - strength[c]);
            mix_new[c] = v / (1.0f + v);
            config[size_t(FOTUFILM_CONFIG_HALATION + c)] = mix_new[c];
        }
        for (int r = 0; r < 3; ++r) {
            const float old = mix_old[r];
            for (int c = 0; c < 3; ++c) {
                const size_t at = size_t(FOTUFILM_CONFIG_HALATION_MATRIX + r * 3 + c);
                const float shape = old > 1e-9f ? config[at] / old : 0.0f;
                config[at] = mix_new[r] * shape;
            }
        }
        std::printf("halation colour %.3f -> mix %.6f %.6f %.6f\n", halation_colour,
                    mix_new[0], mix_new[1], mix_new[2]);
    }

    // --- 5. the running-time slots, then develop and print --------------------
    // Offsets from config-layout.json, evaluated: FLARE 57, EXPOSURE_GAIN 61, PAPER_MIDPOINT 62,
    // WHITE_BALANCE 450..452, HIGHLIGHTS 453, SHADOWS 454, SATURATION 455, VIBRANCE 456.
    {
        // A pushed or pulled development swaps the layer curves before anything is derived from
    // them: the tables, the mid density, the grain all follow the condition's own curves.
    if (push_stops_wanted != 0.0f) {
        const std::string path = push_path.empty()
            ? resources_dir + "/push.bin" : push_path;
        film::PushBlocks pushed;
        if (film::read_push(path, stock_id, push_stops_wanted, pushed)) {
            film::apply_push_curves(pushed, stock.curves);
            // The configuration carries the same three records the kernel actually reads, so the
            // swap has to land there too: six parameters per layer, the packed sampled curves and
            // the coupler warp the engine solved for these curves.
            for (int c = 0; c < 3; ++c) {
                for (int f = 0; f < 6; ++f) config[size_t(c * 6 + f)] = pushed.curves[size_t(c)][size_t(f)];
            }
            if (!pushed.warp.empty() && config.size() >= 66 + pushed.warp.size()) {
                std::copy(pushed.warp.begin(), pushed.warp.end(), config.begin() + 66);
            }
            for (int c = 0; c < 3; ++c) {
                const std::vector<float> &rec = pushed.sampled[size_t(c)];
                const size_t base = 8804 + size_t(c) * 3073;
                if (!rec.empty() && base + rec.size() <= config.size()) {
                    std::copy(rec.begin(), rec.end(), config.begin() + base);
                }
            }
            for (int f = 0; f < 6; ++f) {
                if (8743 + f < int(config.size())) config[size_t(8743 + f)] = pushed.donor[size_t(f)];
            }
            std::printf("push %+.4f stops from %s (curves + sampled + warp + donor)\n",
                        push_stops_wanted, path.c_str());
        } else {
            const std::vector<float> listed = film::push_stops(path, stock_id);
            std::printf("no %+.4f-stop condition for %s", push_stops_wanted, stock_id.c_str());
            if (!listed.empty()) {
                std::printf(" (has");
                for (float v : listed) std::printf(" %+g", v);
                std::printf(")");
            }
            std::printf("\n");
            return 2;
        }
    }
    const float film_flare = flare_of(stock_id, resources_dir);
    const bool touched = ev != 0.0f || have_tone || wb[0] != 1.0f || wb[1] != 1.0f
            || wb[2] != 1.0f || warmth != 0.0f || tint != 0.0f || flare != 0.0f
            || grade_lift != 0.0f || grade_gain != 0.0f;
        if (touched) {
            // Ageing adds granularity in proportion to the mean fog it laid down. The curve half
            // already moved in `film::aged`; this is the configuration half.
            if (expired_years > 0.0f) {
                const float decades = expired_years / 10.0f;
                const float mean_fog = 0.08f * decades * (0.7f + 1.0f + 1.3f) / 3.0f;
                for (int c = 30; c <= 32; ++c) config[size_t(c)] *= 1.0f + mean_fog;
                for (int c = 125; c <= 127; ++c) config[size_t(c)] += mean_fog;
            }
            // Warmth and tint are multiplicative camera gains, so a stop of either is exp2.
            if (warmth != 0.0f) { wb[0] *= std::exp2(0.5f * warmth); wb[2] *= std::exp2(-0.5f * warmth); }
            if (tint != 0.0f) wb[1] *= std::exp2(-0.5f * tint);
            // Flare and the grade are offsets on the baked table, so zero means "as baked".
            // FilmEngine.swift:1226 -- veilingGlare = stock.flare * flareScale, so the scale is
            // not the slot value itself. 0.992 * 0.5 = 0.496 is the engine's own answer.
            if (flare != 0.0f) config[57] += film_flare * flare;
            // ColorGrade: lift = 0.04 * level + 0.02 * tilt, gain = exp2(0.6 * level + 0.3 * tilt).
            // The bands' pads are zero here, so only the level terms are live.
            if (grade_lift != 0.0f) {
                const float l = std::max(-1.0f, std::min(1.0f, grade_lift));
                for (int c = 470; c <= 472; ++c) config[size_t(c)] += 0.04f * l;
            }
            if (grade_gain != 0.0f) {
                const float g = std::max(-1.0f, std::min(1.0f, grade_gain));
                for (int c = 473; c <= 475; ++c) config[size_t(c)] *= std::exp2(0.6f * g);
            }
            if (ev != 0.0f) config[61] *= std::exp2(ev);
            // Halide applies the printer's exposure before the paper response: it is a shift of
            // the print's mid-grey, not of the negative's. Measured against the engine: +1 EV is
            // exactly +log10(2) on all three midpoints.
            // ToneBase.swift:419/424 -- the user's control rides on top of what Auto Levels put
            // there, it does not replace it.
            config[453] += highlights;
            config[454] += shadows;
            // ...and a non-zero control needs the keyed tone grid, 48x64 at this frame size.
            // Offsets are the layout's: TONE_GRID_WIDTH 481, TONE_GRID_A 483 (4096), B 4579.
            if (highlights != 0.0f || shadows != 0.0f) {
                // Local Tone off reads the whole frame as one cell, so the shift is global
                // instead of keyed to the region a pixel sits in. Measured separately because
                // the grid dimensions are fixed when the sums are accumulated.
                if (g_local_tone == 0) {
                    // Local Tone off keys the shift to each pixel's own luminance, so the
                    // regional grid is not consulted at all: it is written as the identity.
                    // Verified against the engine's own dump, which is exactly 1 x 1 at A = 1.
                    config[481] = 1.0f;
                    config[482] = 1.0f;
                    config[483] = 1.0f;
                    for (int i = 1; i < 4096; ++i) config[size_t(483 + i)] = 0.0f;
                    for (int i = 0; i < 4096; ++i) config[size_t(4579 + i)] = 0.0f;
                    config[455] = saturation;
                    config[456] = vibrance;
                    config[450] = wb[0]; config[451] = wb[1]; config[452] = wb[2];
                } else if (!frame_meter_valid) {
                    // Without --meter the frame still has to be measured, because the grid is a
                    // function of the scene: the engine measures it at bake time.
                    const float bal[3] = {wb[0], wb[1], wb[2]};
                    frame_meter.init(meter_w, meter_h, bal, config[61], true);
                    frame_meter.global_read = (g_local_tone == 0);
                    frame_meter.add(red.data(), green.data(), blue.data(), meter_w, meter_h);
                    frame_meter_valid = true;
                }
                std::vector<float> ga, gb;
                tone::solved_coefficients(frame_meter, ga, gb);
                const size_t n = ga.size();
                if (n > 0 && n <= 4096) {
                    config[481] = float(frame_meter.grid_w);
                    config[482] = float(frame_meter.grid_h);
                    for (size_t i = 0; i < n; ++i) config[483 + i] = ga[i];
                    for (size_t i = 0; i < n; ++i) config[4579 + i] = gb[i];
                }
            }
            config[455] = saturation;
            config[456] = vibrance;
            config[450] = wb[0];
            config[451] = wb[1];
            config[452] = wb[2];
            std::printf("slots: ev=%+.2f hi=%+.2f sh=%+.2f sat=%.2f vib=%+.2f wb=%.3f/%.3f/%.3f\n",
                        ev, highlights, shadows, saturation, vibrance, wb[0], wb[1], wb[2]);
        }
    }

    // The dump has to follow the slot writes: it is the configuration the kernel will see, and
    // dumping it before the edits was why every parameter looked like it did nothing.
    // **Last, so a direct write is not undone.** `resize_config` re-derives every physical slot
    // (grain amplitude, the blur radii) from the frame's own millimetres, so a caller that wants
    // to move one of those has to write it after that pass -- writing the baked config before it
    // is silently overwritten, which is what made `GRAIN = 0` look like a no-op.
    for (const auto &kv : slot_overrides) {
        if (kv.first < 0 || size_t(kv.first) >= config.size()) {
            std::fprintf(stderr, "--slot %d is outside the configuration (%zu floats)\n",
                         kv.first, config.size());
            return 2;
        }
        config[size_t(kv.first)] = kv.second;
    }
    for (const auto &kv : slot_muls) {
        if (kv.first < 0 || size_t(kv.first) >= config.size()) return 2;
        config[size_t(kv.first)] *= kv.second;
    }
    for (const auto &rg : mul_ranges) {
        const int lo = std::max(0, std::get<0>(rg));
        const int hi = std::min(int(config.size()) - 1, std::get<1>(rg));
        for (int at = lo; at <= hi; ++at) config[size_t(at)] *= std::get<2>(rg);
    }
    for (const auto &kv : slot_adds) {
        if (kv.first < 0 || size_t(kv.first) >= config.size()) return 2;
        config[size_t(kv.first)] += kv.second;
    }

    // --- the couplers, the fringe, the two output levels, the printer preflash ---
    //
    // These four all edit the configuration the bake produced rather than a stock
    // field, so they run last: `resize_config` re-derives the physical slots from
    // the frame's millimetres, and anything it owns has to be written after it.
    const float px_per_mm = frame_short_side_mm > 0.f
        ? float(std::min(width, height)) / frame_short_side_mm : 0.f;

    if (coupler_touched && stock_loaded) {
        const CouplerFactors f = coupler_factors(&config[21]);
        if (!f.ok) {
            std::printf("couplers: the baked matrix has no recoverable factors; left alone\n");
        } else {
            coupler_matrix(f, coupler_reach, coupler_self, &config[21]);
            coupler_warp(stock, &config[21], config[58],
                         coupler_gamma_of(stock_id, resources_dir),
                         donor_of(stock_id, resources_dir), &config[66]);
            std::printf("couplers: reach %.3f/%.3f self %.3f -> %.4f %.4f %.4f\n",
                        coupler_reach[0], coupler_reach[1], coupler_self,
                        config[21], config[22], config[23]);
        }
    }

    if (fringe_amount >= 0.f && stock_loaded) {
        bool any = false, cross = false;
        for (int r = 0; r < 3; ++r)
            for (int d = 0; d < 3; ++d) {
                if (config[21 + r * 3 + d] != 0.f) any = true;
                if (r != d && config[21 + r * 3 + d] != 0.f) cross = true;
            }
        const float coupler_mm = px_per_mm > 0.f ? config[51] / px_per_mm : 0.f;
        const float mm = fringe_radius_um / 1000.f;
        // `FilmEngine`'s own gate: a fringe is the coupler diffusion reaching
        // further than the coating's, and it needs a colour stock whose layers do
        // inhibit each other before it means anything.
        const bool active = fringe_amount > 0.f && config[58] > 0.f && any && cross
            && !stock.monochrome && mm > coupler_mm;
        const float sigma = mm * px_per_mm;
        const int radius = active ? physics::gaussian_radius(sigma) : 0;
        if (active && radius > 0) {
            config[18026] = std::min(std::max(fringe_amount, 0.f), 1.f);
            config[18027] = sigma;
            config[18028] = float(radius);
            std::printf("fringe: %.3f at %.0f um -> sigma %.3f px, radius %d\n",
                        config[18026], fringe_radius_um, sigma, radius);
        } else {
            std::printf("fringe: not active (couplers %d, cross %d, mono %d, %.3f vs coupler %.3f mm)\n",
                        any && config[58] > 0.f, cross, stock.monochrome ? 1 : 0, mm, coupler_mm);
        }
    }

    if (black_point >= 0.f || white_point >= 0.f) {
        // `ColorGrade.levelled(in:)`: the levels arrive as encoded values, the
        // grade works in display-linear light, and the lift/gain are then placed
        // between the two. At rest this is the identity, bit for bit.
        const float lo = black_point >= 0.f
            ? srgb_eotf_f(std::min(std::max(black_point, 0.f), 0.5f)) : 0.f;
        const float hi = white_point >= 0.f
            ? srgb_eotf_f(std::min(std::max(white_point, 0.5f), 1.f)) : 1.f;
        for (int c = 0; c < 3; ++c) {
            config[size_t(470 + c)] = lo + (hi - lo) * config[size_t(470 + c)];
            config[size_t(473 + c)] = lo + (hi - lo) * config[size_t(473 + c)];
        }
        std::printf("levels: black %.3f -> %.5f, white %.3f -> %.5f\n",
                    black_point < 0.f ? 0.f : black_point, lo,
                    white_point < 0.f ? 1.f : white_point, hi);
    }

    if (printer_preflash != 0.f) {
        config[18033] = printer_preflash;
        std::printf("printer preflash %.5f\n", printer_preflash);
    }
    // **A bigger grain is a bigger clump, not a louder one.**
    //
    // Grain mode 0 -- the default, and the only path when the tile model is off -- draws a
    // Poisson field and blurs it by `GRAIN_SIGMA_LAYER` at `GRAIN_RADIUS`. So the clump's size
    // lives in those four slots, and the tile model's `--grain-size` never touches them: that is
    // why the panel's grain-size slider did nothing unless the 15-second tile model was on.
    // Scaling sigma and the radius together keeps the clump's shape, and the density falls with
    // the square of the scale, so the field gets coarser instead of blotchier.
    if (grain_scale > 0.0f && std::abs(grain_scale - 1.0f) > 1e-4f) {
        for (int i = 0; i < FOTUFILM_CONFIG_GRAIN_SIGMA_LAYER_COUNT; ++i)
            config[size_t(FOTUFILM_CONFIG_GRAIN_SIGMA_LAYER + i)] *= grain_scale;
        config[size_t(FOTUFILM_CONFIG_GRAIN_SIGMA)] *= grain_scale;
        config[size_t(FOTUFILM_CONFIG_GRAIN_RADIUS)] =
            float(physics::gaussian_radius(config[size_t(FOTUFILM_CONFIG_GRAIN_SIGMA)]));
        config[size_t(FOTUFILM_CONFIG_GRAIN_LAMBDA)] /= (grain_scale * grain_scale);
    }

    // Halide applies the printer's exposure before the paper response: it is a shift of the
    // print's mid-grey, not of the negative's. Measured against the engine: +1 EV is exactly
    // +log10(2) on all three midpoints. Written here, outside the slot block, because a render
    // that asks for no direct slot edits never enters it.
    if (g_printer && g_printer_ev != 0.0f) {
        const float d = g_printer_ev * std::log10(2.0f);
        config[size_t(FOTUFILM_CONFIG_PAPER_MIDPOINT)] += d;
        config[8695] += d;
        config[8696] += d;
        std::printf("printer exposure %+.3f EV -> midpoints %+.5f\n", g_printer_ev, d);
    }

    if (!dump_config_path.empty()) {
        if (std::FILE *out = std::fopen(dump_config_path.c_str(), "wb")) {
            std::fwrite(config.data(), sizeof(float), config.size(), out);
            std::fclose(out);
        }
    }

    if (!dump_tables_prefix.empty()) {
        const auto write = [&](const char *suffix, const std::vector<float> &v) {
            std::FILE *f = std::fopen((dump_tables_prefix + suffix).c_str(), "wb");
            if (f) { std::fwrite(v.data(), sizeof(float), v.size(), f); std::fclose(f); }
        };
        write("exposure.f32", exposure);
        write("film.f32", film);
        write("paper.f32", paper);
        std::printf("tables -> %s{exposure,film,paper}.f32\n", dump_tables_prefix.c_str());
    }

    if (g_cancelled.load()) return 3;
    fh_mark("slots");
    std::vector<float> got_r(frame), got_g(frame), got_b(frame);

    const int rc = fotufilm_halide_process_tile(
        dev_r, dev_g, dev_b,
        got_r.data(), got_g.data(), got_b.data(),
        width, height, width, height,
        0, 0, 0, 0, width, height,
        config.data(), exposure.data(), film.data(), paper.data(),
        kLutDimension, mask, seed);
    std::printf("process_tile -> %d  (mask 0x%x, seed %u)\n", rc, mask, seed);
    fh_mark("kernel");
    if (rc != 0) return 1;
    if (g_cancelled.load()) return 3;

    // --- 6. what it says, against a bake dump's own output -------------------
    if (!compare_prefix.empty()) {
        std::vector<float> want_r = read_f32(compare_prefix + "output-r.f32", frame);
        std::vector<float> want_g = read_f32(compare_prefix + "output-g.f32", frame);
        std::vector<float> want_b = read_f32(compare_prefix + "output-b.f32", frame);
        if (want_r.empty() || want_g.empty() || want_b.empty()) return 2;
        const std::vector<float> *mine[3] = {&got_r, &got_g, &got_b};
        const std::vector<float> *theirs[3] = {&want_r, &want_g, &want_b};
        const char *names[3] = {"r", "g", "b"};
        int bad = 0;
        for (int c = 0; c < 3; ++c) {
            size_t different = 0;
            double worst = 0, sum = 0;
            for (size_t i = 0; i < frame; ++i) {
                const double d = std::fabs(double((*mine[c])[i]) - double((*theirs[c])[i]));
                if (d != 0) ++different;
                sum += d;
                if (d > worst) worst = d;
            }
            std::printf("%s: differing %zu/%zu  max %.9g  mean %.9g\n",
                        names[c], different, frame, worst, sum / double(frame));
            if (different != 0) ++bad;
        }
        std::printf(bad == 0 ? "IDENTICAL\n" : "DIFFERENT\n");
        if (bad != 0) return 1;
    }

    // --- 7. the picture ------------------------------------------------------
    std::vector<float> interleaved(frame * 3);
    for (size_t i = 0; i < frame; ++i) {
        interleaved[i * 3 + 0] = got_r[i];
        interleaved[i * 3 + 1] = got_g[i];
        interleaved[i * 3 + 2] = got_b[i];
    }
    if (!display_path.empty()) {
        const std::vector<float> &p3 = interleaved;
        if (std::FILE *f = std::fopen(display_path.c_str(), "wb")) {
            std::fwrite(p3.data(), sizeof(float), p3.size(), f);
            std::fclose(f);
            std::printf("wrote %s  %dx%d  display-linear P3\n", display_path.c_str(), width, height);
        }
    }
    fh_mark("tones");

    // The view layer runs on what a display would show, which is what the JNI
    // hands `apply_pixel`: encode, adjust, decode. Zero on every control is the
    // untouched develop, byte for byte.
    if (!g_view.neutral() && g_view.spatial) {
        // Sharpening and texture need whole rows; they are their own pass.
        spk_adjust::apply_float_spatial(interleaved.data(), uint32_t(width),
                                        uint32_t(height), g_view);
    } else if (!g_view.neutral()) {
        for (std::size_t i = 0; i < frame; ++i) {
            double c[3];
            for (int k = 0; k < 3; ++k) {
                const float v = interleaved[i * 3 + k];
                double e = v <= 0.0031308f ? double(v) * 12.92
                                           : 1.055 * std::pow(double(v), 1.0 / 2.4) - 0.055;
                c[k] = e < 0.0 ? 0.0 : (e > 1.0 ? 1.0 : e);
            }
            spk_adjust::apply_pixel(c, int(i % std::size_t(width)),
                                    int(i / std::size_t(width)),
                                    int(width), int(height), g_view);
            for (int k = 0; k < 3; ++k) {
                const double e = c[k] < 0.0 ? 0.0 : (c[k] > 1.0 ? 1.0 : c[k]);
                interleaved[i * 3 + k] = float(e <= 0.04045 ? e / 12.92
                                                            : std::pow((e + 0.055) / 1.055, 2.4));
            }
        }
    }

    std::vector<uint8_t> rgb;
    display_p3(interleaved.data(), int(frame), rgb);
    fh_mark("display");
    // Where there is no film the print shows the scan's light: the perforations,
    // which this chain cannot see otherwise -- a hole and clear base both reach
    // the kernel as "no exposure" and both print the same grey.
    if (!rebate_P.empty())
        rebate::print_after(rebate_P, rgb.data(), width, height,
                            rebate_gate.empty() ? nullptr : rebate_gate.data(),
                            rebate_film.empty() ? nullptr : rebate_film.data(),
                            rebate_ink.empty() ? nullptr : rebate_ink.data());

    // Film Border Positive is the same developed strip, scanned and inverted.
    // The rebate and its lettering already sit in the print domain here, so what
    // the inversion changes is the perforations: a hole is clear film, and a
    // clear hole scans white but prints black ("black perforations").
    if (g_pf_kind == "film-positive" && !rebate_film.empty()) {
        const size_t px = size_t(width) * size_t(height);
        for (size_t i = 0; i < px && i < rebate_film.size(); ++i) {
            if (rebate_film[i] != 0) continue;
            uint8_t *p = rgb.data() + i * 3;
            p[0] = uint8_t(p[0] < 24 ? p[0] : 24);
            p[1] = uint8_t(p[1] < 24 ? p[1] : 24);
            p[2] = uint8_t(p[2] < 24 ? p[2] : 24);
        }
    }

    fh_mark("rebate-print");
    // The print frame: the picture is formed first, then placed on its material.
    // Film Border / Film Border Positive belong to the rebate above.
    if (!g_pf_kind.empty() && g_pf_kind != "none") {
        const print_frame::Kind pk = print_frame::kind_from_id(g_pf_kind);
        // A film border is drawn here when the gauge is one of print_frame's;
        // 135 and the 120 frames stay on the rebate above.
        const bool film_here = print_frame::is_film_kind(pk) && pf_draws_film();
        if (pk != print_frame::Kind::None && (!print_frame::is_film_kind(pk) || film_here)) {
            print_frame::Request pf;
            pf.kind = pk;
            pf.gauge = g_pf_gauge;
            pf.frame_no = g_rebate_num > 0 ? std::to_string(g_rebate_num) : std::string();
            pf.inscription = g_rebate_text;
            pf.stock = stock_id;
            pf.margin_mm = g_pf_margin;
            pf.framing_x = g_pf_fx;
            pf.framing_y = g_pf_fy;
            std::memcpy(pf.paper_rgb, g_pf_paper, sizeof pf.paper_rgb);
            std::memcpy(pf.film_rgb, g_pf_film, sizeof pf.film_rgb);
            std::memcpy(pf.film_light, g_pf_film_light, sizeof pf.film_light);
            std::memcpy(pf.film_ink, g_pf_film_ink, sizeof pf.film_ink);
            print_frame::Layout pl;
            std::string pf_error;
            if (print_frame::layout(pf, int(width), int(height), pl, pf_error)) {
                std::vector<uint8_t> framed;
                print_frame::compose(pf, pl, rgb.data(), int(width), int(height), framed);
                rgb.swap(framed);
                std::printf("print-frame | %s  canvas %dx%d (%.1f x %.1f mm)  picture %dx%d at %d,%d  %.3f px/mm\n",
                            print_frame::kind_id(pk), pl.canvas_w, pl.canvas_h,
                            pl.sheet_w_mm, pl.sheet_h_mm, pl.photo_w, pl.photo_h,
                            pl.photo_x, pl.photo_y, pl.px_per_mm);
                width = uint32_t(pl.canvas_w);
                height = uint32_t(pl.canvas_h);
                frame = size_t(width) * size_t(height);
            } else {
                std::printf("print-frame | %s\n", pf_error.c_str());
                return 2;
            }
        }
    }

    // The codecs library writes the P3 profile with the pixels, so a JPEG out of here carries the
    // same colour the PPM's numbers mean. Alpha is ignored by the encoder.
    const bool jpeg = out_path.size() > 4
        && (out_path.compare(out_path.size() - 4, 4, ".jpg") == 0
            || out_path.compare(out_path.size() - 5, 5, ".jpeg") == 0);
    if (jpeg) {
        if (!ffc_can_encode("image/jpeg")) {
            std::printf("this build cannot write JPEG\n");
            return 1;
        }
        std::vector<uint8_t> rgba(size_t(frame) * 4);
        for (size_t i = 0; i < frame; ++i) {
            rgba[i * 4 + 0] = rgb[i * 3 + 0];
            rgba[i * 4 + 1] = rgb[i * 3 + 1];
            rgba[i * 4 + 2] = rgb[i * 3 + 2];
            rgba[i * 4 + 3] = 255;
        }
        char error[512] = {0};
        // The metadata policy is the Mac app's default: the source's camera,
        // exposure and lens records travel with the print, its location does not.
        const int32_t status = ffc_encode(out_path.c_str(), "image/jpeg", rgba.data(), 8,
                                          uint32_t(width), uint32_t(height), quality,
                                          source_exif.empty() ? nullptr : source_exif.data(),
                                          source_exif.size(),
                                          0 /* keep_location */, error, sizeof error);
        if (status != 0) {
            std::printf("ffc_encode failed (%d): %s\n", status, error);
            return 1;
        }
        std::printf("wrote %s  %dx%d  q%.2f\n", out_path.c_str(), width, height, quality);
        fh_mark("encode");
        return 0;
    }
    // **The grading file.** Chosen by the caller's extension so the C entry point
    // keeps one signature: `.tif`/`.tiff` asks for linear 16-bit Display P3.
    const bool tiff = out_path.size() > 4
        && (out_path.compare(out_path.size() - 4, 4, ".tif") == 0
            || out_path.compare(out_path.size() - 5, 5, ".tiff") == 0);
    if (tiff) {
        std::string error;
        const std::string icc = resources_dir.empty() ? std::string()
                                                      : resources_dir + "/io/linear-P3.icc";
        if (!write_tiff16_linear(out_path, interleaved.data(), int(width), int(height), icc, error)) {
            std::printf("%s\n", error.c_str());
            return 1;
        }
        std::printf("wrote %s  %dx%d  16-bit linear Display P3%s\n", out_path.c_str(),
                    width, height, icc.empty() ? " (no ICC)" : "");
        return 0;
    }
    // **One extension, one encoder, and nothing else.**
    //
    // `.png` used to fall through to here and come out as a PPM with a .png
    // name -- silently, from a code path that looked like it had succeeded. The
    // app then could not decode its own intermediate, deleted it, and reported
    // a MediaStore failure that had nothing to do with MediaStore. A name this
    // build does not encode is now an error, so the caller reads what happened.
    const bool ppm = out_path.size() > 4
        && (out_path.compare(out_path.size() - 4, 4, ".ppm") == 0
            || out_path.compare(out_path.size() - 4, 4, ".pnm") == 0);
    if (!ppm) {
        std::printf("cannot write %s: this build encodes .jpg, .tif and .ppm only\n",
                    out_path.c_str());
        return 2;
    }
    if (!write_ppm(out_path, rgb, width, height)) {
        std::printf("cannot write %s\n", out_path.c_str());
        return 1;
    }
    std::printf("wrote %s  %dx%d\n", out_path.c_str(), width, height);
    return 0;
}

// ---------------------------------------------------------------------------
// The one entry point a host needs. A phone has no argv, so this assembles one and calls the same
// path the CLI does; the picture, the tables and the slots are therefore identical by construction.
// ---------------------------------------------------------------------------
/// Raises the cancel flag. The UI calls this while a render is in flight; the render answers 3
/// and writes nothing.
/// The development conditions a stock lists, so the UI can offer only steps the pack carries.
extern "C" int fh_push_stops(const char *resources, const char *stock, float *out, int capacity) {
    if (!resources || !stock || !out || capacity <= 0) return 0;
    const std::vector<float> listed =
        film::push_stops(std::string(resources) + "/push.bin", stock);
    const int n = int(std::min<size_t>(listed.size(), size_t(capacity)));
    for (int i = 0; i < n; ++i) out[i] = listed[size_t(i)];
    return n;
}

extern "C" void fh_cancel(void) { g_cancelled.store(true); }

/**
 * Every option back to the engine's own default, before one call.
 *
 * <p>`fh_process` is the library entry point and the app calls it dozens of
 * times from one loaded library -- a canvas render every time a control moves,
 * a live frame per drag tick, a card per shelf slot, an export. Each call reads
 * its options from `argv`, and an option the caller did not send used to mean
 * "what the last call left in the static". The panel only sends a verb when the
 * control is away from its default, so the consequence was one-way: turning
 * `prn:1` (optical printer) on worked, turning it off sent nothing and the
 * printer stayed on; `lct:0` is sent whenever target tone is *off*, which is the
 * default, so it poisoned every later render and the toggle could never come
 * back. That is the whole of "the buttons in the print panel do nothing".
 *
 * <p>The caches below this are deliberately *not* reset: the decoded scene, the
 * built tables and the laid grain tiles are pure functions of their inputs and
 * are what makes a card forty milliseconds.
 */
static void reset_options() {
    g_mottle_share = -1.0f;
    g_grade_space = -1;
    g_print_light.clear();
    g_digital_reference.clear();
    g_enlarger = 0;
    g_local_tone = -1;
    g_printer = 0;
    g_printer_lamp = 3200.0f;
    g_printer_m = 0.40f;
    g_printer_y = 0.50f;
    g_printer_ev = 0.0f;
    g_printer_rgb[0] = 25.0f;
    g_printer_rgb[1] = 25.0f;
    g_printer_rgb[2] = 25.0f;
    g_exif_fields.clear();
    g_geometry = GeometrySlot();
    g_view = spk_adjust::ViewAdjust{};
    g_frame_mm = 0.0f;
    g_rebate = 0;
    g_rebate_gate = "135";
    g_rebate_text.clear();
    g_rebate_fog = 1.0;
    g_rebate_leaks = 0.0;
    g_rebate_num = 0;
    g_rebate_cam = 1;
    g_rebate_fr = 1;
    g_rebate_date.clear();
    g_rebate_place = "rebate";
    g_rebate_style = "lcd";
    g_rebate_corner = "br";
    g_rebate_size = 1.0;
    g_rebate_fnum = 0.0;
    g_rebate_inset_x = 3.0;
    g_rebate_inset_y = 2.4;
    g_rebate_carrier = "none";
    g_rebate_holes = "white";
}

extern "C" int fh_process(const char *resources, const char *config_prefix, const char *photo,
                          const char *out, const char *stock, const char *paper,
                          int max_edge, int screen_medium, float ev, float highlights,
                          float shadows, float saturation, float vibrance, float quality,
                          float warmth, float tint, float flare, float grade_lift, float grade_gain,
                          float expired_years, float bleach, const char *table_cache,
                          const char *meter_prefix, float push_stops, const char *slots) {
    bool medium_negative = false;    // `.negative`: no sheet, so no timing either
    reset_options();
    g_cancelled.store(false);
    std::vector<std::string> args = {"fh", photo, config_prefix, out,
                                     "--max-edge", std::to_string(max_edge),
                                     "--self-tables", resources,
                                     "--stock", stock, "--paper", paper,
                                     "--quality", std::to_string(quality)};
    if (screen_medium) args.push_back("--screen");
    // The camera's spectral correction ships beside the skeletons; a package without it simply
    // decodes uncalibrated, which is what the engine does when no profile resolves.
    if (resources && *resources) {
        args.push_back("--camera-profile");
        args.push_back(resources);
    }
    if (push_stops != 0.0f) {
        args.push_back("--push");
        args.push_back(std::to_string(push_stops));
    }
    // Auto Levels: the stock's baseline reading sits beside its skeleton, so a frame the
    // package never saw is re-metered rather than printed on the reference scene's levels.
    // The configuration already carries the metered levels: it was dumped after Auto Levels ran
    // on it. Re-metering here would apply a second, differently-derived set, so `--meter` is only
    // passed when a caller explicitly asks for it (the probes do; the app does not).
    // **The skeleton's own meter, unless the caller names another.** Each per-medium skeleton
    // ships a `meter.bin` beside it -- the levels Auto Levels read on the scene the skeleton was
    // baked from. Skipping it leaves the metered slots at that scene's values and the picture
    // comes back 0.4 of a stop dark with the contrast flattened, which is what "our render and
    // the web demo are different pictures" was.
    // **Not implicitly.** The per-medium skeletons and the stock-level `meter.bin` were baked
    // from different photographs: re-metering against that baseline moves the mid-points and the
    // shadow lift by another scene's worth, and the print comes back a sixth of a stop dark on a
    // frame the skeleton never saw. The one slot that genuinely follows the frame is the film
    // boost, and `run()` re-derives that itself. Set `FH_METER=<dir>` to meter explicitly.
    if (const char *explicit_meter = std::getenv("FH_METER")) {
        if (*explicit_meter) { args.push_back("--meter"); args.push_back(explicit_meter); }
    }
    if (slots && *slots) {
        // "30=0.0136;453=0.2" -- one string keeps fh_process's signature stable as the panel grows.
        // A prefix changes the verb: "mul:30=1.6" multiplies, "add:454=0.3" offsets,
        // "physmul:halationSigmaMM_0=2" scales the metre-scale physics before it is folded.
        std::string text(slots);
        size_t at = 0;
        while (at <= text.size()) {
            const size_t end = text.find(';', at);
            const std::string one = text.substr(at, end == std::string::npos ? std::string::npos : end - at);
            if (!one.empty()) {
                if (one.rfind("grain:", 0) == 0) { args.push_back("--grain"); args.push_back(one.substr(6)); }
                else if (one.rfind("hd:", 0) == 0) { args.push_back("--highlight-detail"); args.push_back(one.substr(3)); }
                else if (one.rfind("pc:", 0) == 0) { args.push_back("--print-correction"); args.push_back(one.substr(3)); }
                else if (one.rfind("hc:", 0) == 0) { args.push_back("--halation-colour"); args.push_back(one.substr(3)); }
                // The couplers, the fringe, the two output levels, the printer preflash.
                else if (one.rfind("crg:", 0) == 0) { args.push_back("--coupler-rg"); args.push_back(one.substr(4)); }
                else if (one.rfind("cgb:", 0) == 0) { args.push_back("--coupler-gb"); args.push_back(one.substr(4)); }
                else if (one.rfind("cr:", 0) == 0) { args.push_back("--coupler-reach"); args.push_back(one.substr(3)); }
                else if (one.rfind("cs:", 0) == 0) { args.push_back("--coupler-self"); args.push_back(one.substr(3)); }
                else if (one.rfind("frr:", 0) == 0) { args.push_back("--fringe-radius"); args.push_back(one.substr(4)); }
                else if (one.rfind("fr:", 0) == 0) { args.push_back("--fringe"); args.push_back(one.substr(3)); }
                else if (one.rfind("bp:", 0) == 0) { args.push_back("--black-point"); args.push_back(one.substr(3)); }
                else if (one.rfind("wp:", 0) == 0) { args.push_back("--white-point"); args.push_back(one.substr(3)); }
                else if (one.rfind("ppf:", 0) == 0) { args.push_back("--printer-preflash"); args.push_back(one.substr(4)); }
                else if (one.rfind("sd:", 0) == 0) { args.push_back("--shadow-detail"); args.push_back(one.substr(3)); }
                else if (one.rfind("grainsize:", 0) == 0) { args.push_back("--grain-size"); args.push_back(one.substr(10)); }
                else if (one.rfind("grainscale:", 0) == 0) { args.push_back("--grain-scale"); args.push_back(one.substr(11)); }
                else if (one.rfind("gm:", 0) == 0) { args.push_back("--mottle"); args.push_back(one.substr(3)); }
                else if (one.rfind("gsc:", 0) == 0) { args.push_back("--grade-space"); args.push_back(one.substr(4)); }
                else if (one.rfind("plt:", 0) == 0) { args.push_back("--print-light"); args.push_back(one.substr(4)); }
                else if (one.rfind("dsr:", 0) == 0) { args.push_back("--digital-reference"); args.push_back(one.substr(4)); }
                else if (one.rfind("enl:", 0) == 0) { args.push_back("--enlarger"); args.push_back(one.substr(4)); }
                else if (one.rfind("lct:", 0) == 0) { args.push_back("--local-tone"); args.push_back(one.substr(4)); }
                else if (one.rfind("prn:", 0) == 0) { args.push_back("--printer"); args.push_back(one.substr(4)); }
                else if (one.rfind("prl:", 0) == 0) { args.push_back("--printer-lamp"); args.push_back(one.substr(4)); }
                else if (one.rfind("prm:", 0) == 0) { args.push_back("--printer-magenta"); args.push_back(one.substr(4)); }
                else if (one.rfind("pry:", 0) == 0) { args.push_back("--printer-yellow"); args.push_back(one.substr(4)); }
                else if (one.rfind("pre:", 0) == 0) { args.push_back("--printer-exposure"); args.push_back(one.substr(4)); }
                else if (one.rfind("prr:", 0) == 0) { args.push_back("--printer-red"); args.push_back(one.substr(4)); }
                else if (one.rfind("prg:", 0) == 0) { args.push_back("--printer-green"); args.push_back(one.substr(4)); }
                else if (one.rfind("prb:", 0) == 0) { args.push_back("--printer-blue"); args.push_back(one.substr(4)); }
                else if (one.rfind("graincolour:", 0) == 0) { args.push_back("--grain-colour"); args.push_back(one.substr(12)); }
                else if (one.rfind("grainsoft:", 0) == 0) { args.push_back("--grain-softness"); args.push_back(one.substr(10)); }
                else if (one.rfind("grainlayers:", 0) == 0) { args.push_back("--grain-layers"); args.push_back(one.substr(12)); }
                else if (one.rfind("exif:", 0) == 0) { args.push_back("--exif"); args.push_back(one.substr(5)); }
                else if (one.rfind("geo:", 0) == 0) { args.push_back("--geometry"); args.push_back(one.substr(4)); }
                else if (one.rfind("view:", 0) == 0) { args.push_back("--view"); args.push_back(one.substr(5)); }
                else if (one.rfind("mm:", 0) == 0) { args.push_back("--frame-mm"); args.push_back(one.substr(3)); }
                else if (one.rfind("rebate:", 0) == 0) { args.push_back("--rebate"); args.push_back(one.substr(7)); }
                else if (one.rfind("rgate:", 0) == 0) { args.push_back("--rgate"); args.push_back(one.substr(6)); }
                else if (one.rfind("rtext:", 0) == 0) { args.push_back("--rtext"); args.push_back(one.substr(6)); }
                else if (one.rfind("rfog:", 0) == 0) { args.push_back("--rfog"); args.push_back(one.substr(5)); }
                else if (one.rfind("rleaks:", 0) == 0) { args.push_back("--rleaks"); args.push_back(one.substr(7)); }
                else if (one.rfind("rnum:", 0) == 0) { args.push_back("--rnum"); args.push_back(one.substr(5)); }
                else if (one.rfind("rcam:", 0) == 0) { args.push_back("--rcam"); args.push_back(one.substr(5)); }
                else if (one.rfind("rfr:", 0) == 0) { args.push_back("--rfr"); args.push_back(one.substr(4)); }
                else if (one.rfind("rdate:", 0) == 0) { args.push_back("--rdate"); args.push_back(one.substr(6)); }
                else if (one.rfind("rplace:", 0) == 0) { args.push_back("--rplace"); args.push_back(one.substr(7)); }
                else if (one.rfind("rstyle:", 0) == 0) { args.push_back("--rstyle"); args.push_back(one.substr(7)); }
                else if (one.rfind("rcorner:", 0) == 0) { args.push_back("--rcorner"); args.push_back(one.substr(8)); }
                else if (one.rfind("rsize:", 0) == 0) { args.push_back("--rsize"); args.push_back(one.substr(6)); }
                else if (one.rfind("rinsx:", 0) == 0) { args.push_back("--rinsx"); args.push_back(one.substr(6)); }
                else if (one.rfind("rinsy:", 0) == 0) { args.push_back("--rinsy"); args.push_back(one.substr(6)); }
                else if (one.rfind("rfn:", 0) == 0) { args.push_back("--rfn"); args.push_back(one.substr(4)); }
                else if (one.rfind("rcar:", 0) == 0) { args.push_back("--rcar"); args.push_back(one.substr(5)); }
                else if (one.rfind("rholes:", 0) == 0) { args.push_back("--rholes"); args.push_back(one.substr(7)); }
                else if (one.rfind("pf:", 0) == 0) { args.push_back("--pf"); args.push_back(one.substr(3)); }
                else if (one.rfind("pfg:", 0) == 0) { args.push_back("--pfgauge"); args.push_back(one.substr(4)); }
                else if (one.rfind("etext:", 0) == 0) { args.push_back("--etext"); args.push_back(one.substr(6)); }
                else if (one.rfind("pfm:", 0) == 0) { args.push_back("--pfmargin"); args.push_back(one.substr(4)); }
                else if (one.rfind("pffx:", 0) == 0) { args.push_back("--pffx"); args.push_back(one.substr(5)); }
                else if (one.rfind("pffy:", 0) == 0) { args.push_back("--pffy"); args.push_back(one.substr(5)); }
                else if (one.rfind("curves:", 0) == 0) { args.push_back("--curves"); args.push_back(one.substr(7)); }
                else if (one.rfind("mulrange:", 0) == 0) { args.push_back("--mulrange"); args.push_back(one.substr(9)); }
                else if (one.rfind("physmul:", 0) == 0) { args.push_back("--physmul"); args.push_back(one.substr(8)); }
                else if (one.rfind("mul:", 0) == 0) { args.push_back("--mul"); args.push_back(one.substr(4)); }
                else if (one.rfind("add:", 0) == 0) { args.push_back("--add"); args.push_back(one.substr(4)); }
                else { args.push_back("--slot"); args.push_back(one); }
            }
            if (end == std::string::npos) break;
            at = end + 1;
        }
    }
    if (expired_years > 0.0f) { args.push_back("--expired"); args.push_back(std::to_string(expired_years)); }
    if (bleach > 0.0f) { args.push_back("--bleach"); args.push_back(std::to_string(bleach)); }
    if (table_cache && *table_cache) {
        args.push_back("--table-cache");
        args.push_back(table_cache);
    }
    if (ev != 0.0f) { args.push_back("--ev"); args.push_back(std::to_string(ev)); }
    if (warmth != 0.0f) { args.push_back("--warmth"); args.push_back(std::to_string(warmth)); }
    if (tint != 0.0f) { args.push_back("--tint"); args.push_back(std::to_string(tint)); }
    if (flare != 0.0f) { args.push_back("--flare"); args.push_back(std::to_string(flare)); }
    if (grade_lift != 0.0f) { args.push_back("--grade-lift"); args.push_back(std::to_string(grade_lift)); }
    if (grade_gain != 0.0f) { args.push_back("--grade-gain"); args.push_back(std::to_string(grade_gain)); }
    if (highlights != 0.0f || shadows != 0.0f || saturation != 1.0f || vibrance != 0.0f) {
        args.push_back("--highlights"); args.push_back(std::to_string(highlights));
        args.push_back("--shadows"); args.push_back(std::to_string(shadows));
        args.push_back("--saturation"); args.push_back(std::to_string(saturation));
        args.push_back("--vibrance"); args.push_back(std::to_string(vibrance));
    }
    std::vector<char *> argv;
    argv.reserve(args.size());
    for (auto &a : args) argv.push_back(const_cast<char *>(a.c_str()));
    // **A failed allocation must not take the app with it.** A 100 MP JPEG decodes to a
    // float RGBA frame (16 bytes a pixel); `std::vector` throws when that does not fit, and the
    // exception used to cross the C ABI boundary and abort the process -- mid-export, with no
    // message and nothing the Java side could catch. Report it as a code instead.
    try {
        return run(int(argv.size()), argv.data());
    } catch (const std::bad_alloc &) {
        std::fprintf(stderr, "[fh] out of memory\n");
        return 4;
    } catch (const std::exception &e) {
        std::fprintf(stderr, "[fh] threw: %s\n", e.what());
        return 5;
    } catch (...) {
        std::fprintf(stderr, "[fh] threw: unknown\n");
        return 5;
    }
}

#ifndef FH_NO_MAIN
int main(int argc, char **argv) { return run(argc, argv); }
#endif
