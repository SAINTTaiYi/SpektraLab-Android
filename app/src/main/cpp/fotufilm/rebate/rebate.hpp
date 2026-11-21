// The film outside the frame, as a standalone CPU module.
//
// Everything here is lifted from the first engine's `overscan.cpp` -- the gate
// tables, the layout drawn from the seeds, the imprint (edge print, frame
// number, DX, date) as film-space drawing operations, and the coverage
// rasteriser that turns those into masks. What is *not* here: the four compute
// kernels that composite the masks onto the exposure. Those are transcribed
// separately (`rebate_composite.cpp`), because a shader has no C++ twin.
//
// The point of the split: 85% of the engine's overscan is CPU code that never
// needed a GPU, and the second engine (fotufilm) is a CPU chain that wants the
// same rebate. This file is that 85%, with no Vulkan, no Metal and no Apple
// frameworks -- the bundled Liberation/DejaVu outlines and stb do the text.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace rebate {

// --- the layout, verbatim from the engine's `pipeline.hpp` --------------------
struct OverscanLayout {
    bool valid = false;
    std::string format;
    double px = 0.0;                      // mm per pixel (the frame's pitch)
    uint32_t frame_w = 0, frame_h = 0;
    uint32_t canvas_w = 0, canvas_h = 0;
    bool vertical = false;                // the film runs along the image's y axis
    double gate_s0 = 0, gate_t0 = 0, gate_along = 0, gate_across = 0;
    double pair_adv = 0;                  // > 0: a half-frame pair, the same gate again this far along
    double corner = 0, penumbra = 0;
    double wob_a[4][3] = {{0}}, wob_ph[4][3] = {{0}};
    double film_w = 0;
    double A[4] = {1, 0, 0, 1};           // canvas mm (about its centre) -> film mm
    double cu = 0, cv = 0, cs = 0, ct = 0;
    double scan_rot = 0;
    bool perforated = false;
    double perf_pitch = 0, perf_w = 0, perf_h = 0, perf_edge = 0, perf_r = 0, perf_phase = 0;
    double fog_amp = 0, fog_width = 0.5, fog_period = 8.0;
    uint32_t fog_seed = 0;
    struct Leak { double s = 0; int side = 0; double amp = 0, sig_s = 1, sig_t = 1; };
    std::vector<Leak> leaks;
    double fog_rgb[3] = {1, 1, 1}, date_rgb[3] = {1, 1, 0}, edge_rgb[3] = {1, 1, 0};
    double hole_cmy[3] = {0, 0, 0};
    double flare_amp = 0, flare_width = 0.05;
    // The gate's own shape: corner radii (s-,t-), (s+,t-), (s+,t+), (s-,t+);
    // convex quads added to (+1) or cut from (-1) the opening, film mm; the
    // machined edge's roughness.
    std::string gate_family;
    double gate_r[4] = {0, 0, 0, 0};
    struct Quad { double pts[8] = {0}; double round = 0; double sign = 1; };
    std::vector<Quad> quads;
    double rough_amp = 0, rough_period = 0.15;
    uint32_t rough_seed = 0;
    double top_recess = 0;                // how far the t- side's middle sits back from its ears (shouldered)
    bool holes_light = false;
    bool carrier_open = false;            // past the film's long edges: the scan's light, not a black carrier
    bool carrier_none = false;            // no carrier: the canvas ends at the film's edge
    double light_rgb[3] = {1, 1, 1};      // the backlight through the scan, linear output RGB
    double light_fall = 0, light_dir = 0; // the light panel's falloff across the canvas
    uint32_t perf_seed = 0;               // per-hole punch tolerances, burrs, wall slant
    double wall_t = 0.13;                 // base thickness, mm
    double wall_tilt[2] = {0, 0};         // the scan's viewing tilt, tan(angle) along (s, t)
    double wall_parallax = 0;             // 1 / the scan's viewing distance, 1/mm
    double base_rgb[3] = {1, 1, 1};       // the base's transmittance colour, linear output RGB
    double wall_glow = 0;                 // black holes: the wall's rim brightness
    double wall_scatter = 0.02;           // light scattered in the base widens the wall, mm
};

// --- the two parameter blocks (engine `params.hpp`, verbatim) ----------------
struct OverscanParams {
    bool active = false;
    std::string format = "135";
    std::string mode = "strip";
    int camera_seed = 1;
    int frame_seed = 1;
    double fog = 1.0;
    double leaks = 0.0;
    std::string edge_text;
    double f_number = 0.0;
    std::string gate = "auto";
    std::string holes = "white";
    int frame_number = 0;
    std::string carrier = "none";
    bool pair = false;
};

struct DateImprintParams {
    bool active = false;
    std::string text;
    std::string text_b;
    std::string data_text;
    std::string placement = "frame";
    double exposure_ev = 3.5;
    std::string style = "lcd";
    double size = 1.0;
    std::string corner = "br";
    double inset_x = 3.0;
    double inset_y = 2.4;
};

// --- what the engine keeps on `Pipeline` for this -----------------------------
struct Ctx {
    std::string stock;
    std::string reference_illuminant = "D65";
    double px_um = 0.0;                   // the scan's pixel pitch, um
    double frame_w_mm = 0.0, frame_h_mm = 0.0;
    double film_format_mm = 36.0;         // fallback when the format name is unknown
    OverscanParams os;
    DateImprintParams di;
    // The edge print's three colours. The engine derives them from spectra
    // (`light_weights`); here they are inputs, baked per stock in stage 2.
    double fog_rgb[3] = {1.0, 0.92, 0.72};
    double date_rgb[3] = {1.0, 0.42, 0.10};
    double edge_rgb[3] = {1.0, 0.97, 0.86};
    double base_rgb[3] = {1.0, 0.66, 0.34};   // the film base seen through the scan's light
    // The canvas ceiling the engine takes from the device. 0 = no limit; the
    // host passes its own budget in stage 3.
    uint64_t max_binding_bytes = 0, heap_bytes = 0;
    // The stock's own spectra, for the hole's CMY (the dye that cancels the
    // base). Empty = the hole carries no dye, which is what a stock with no
    // data gets; the host fills these from `stocks.bin` in stage 3.
    std::vector<double> channel_density;   // 3 x wavelengths
    std::vector<double> base_density;      // wavelengths
    struct Marks { unsigned ops = 0, text = 0; int box[4] = {0, 0, 0, 0}; } marks;
};

// One imprint group's coverage, already placed in canvas pixels.
struct Mask {
    int x0 = 0, y0 = 0, w = 0, h = 0;
    std::vector<float> cov;               // w * h, one channel
    double exposure[3] = {0, 0, 0};       // the group's own exposure
};

double gate_long_mm(const Ctx& ctx);

bool layout(Ctx& ctx, uint32_t frame_w, uint32_t frame_h, OverscanLayout& out,
            std::string& error);

void params_block(const Ctx& ctx, const OverscanLayout& L, std::vector<float>& P);

std::vector<Mask> masks(Ctx& ctx, const OverscanLayout& L, std::string& error);

std::string geometry_json(const Ctx& ctx, const OverscanLayout& L);

// --- the composites (`rebate_composite.cpp`, from `spk_overscan_body.glsl`) ----
//
// The engine runs these as four compute kernels over the same parameter block.
// Here they run over a float plane, in the same order and with the same
// arithmetic; a difference between the two is a difference in the picture.
struct Canvas {
    int w = 0, h = 0;
    std::vector<float> rgb;               // w * h * 3, raw exposure
};

// (1) the frame on the canvas: penumbra, gate flare, edge fog, the leaks.
// `stride` floats per frame pixel: 3 for a planar dump, 4 for the RGBA the
// decoder hands the host (its alpha is not exposure).
// `gate` and `film` (optional) receive 8-bit coverage planes of the same size as
// the canvas: the gate's (1 inside the frame, 0 in the rebate, soft in the
// penumbra) and the film's (0 in a perforation or past the strip's edge). They
// are what the print-domain pass needs to know, and they are free here --
// `canvas_pass` computes both anyway.
bool canvas_pass(const std::vector<float>& P, const float* frame, int fw, int fh,
                 int stride, Canvas& out,
                 std::vector<uint8_t>* gate = nullptr,
                 std::vector<uint8_t>* film = nullptr);
// (2) an imprint's coverage, added as exposure.
void add_mask(const Mask& m, Canvas& c);
// (3) where there is no film, the density becomes the cmy that cancels the base.
void film_present(const std::vector<float>& P, Canvas& c);
// (4) the scan's view: the cut wall, its shoulder, and the carrier past the strip.
void light_pass(const std::vector<float>& P, Canvas& c);
// (5) the same thing, after the print, where the chain has no density plane to
// write: holes become the scan's light, the unexposed rebate becomes the film
// base the reference medium prints it as, and an imprint's ink is left alone.
// `gate`/`film`/`ink` are 8-bit planes (see `canvas_pass`), any may be null.
void print_after(const std::vector<float>& P, uint8_t* rgb, int w, int h,
                 const uint8_t* gate, const uint8_t* film, const uint8_t* ink);

}  // namespace rebate
