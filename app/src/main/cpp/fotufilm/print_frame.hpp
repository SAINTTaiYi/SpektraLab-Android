// Fotufilm's print-frame model, as a standalone CPU compositor.
//
// Transcribed from `Sources/FotufilmCore/PrintFrame.swift` and
// `docs/print-frame-model.md`. The finishing compositor runs *after* image
// formation: it never resamples the photograph and never touches its colour
// profile -- it only places the formed picture on a material and draws that
// material around it.
//
// This file carries the paper family (Paper Border at four sheet sizes, Carrier
// Border, Emulsion Border), the mounts and the posting canvases, plus the slide
// mount. The film-border family (Film Border / Film Border Positive) stays on
// the existing `rebate` module, which already draws the developed strip.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace print_frame {

enum class Kind {
    None = 0,
    Film,            // the developed strip            (drawn by `rebate`)
    FilmPositive,    // the same, scanned and inverted (drawn by `rebate`)
    SlideMount,      // a transparency in a 2 x 2 card mount
    Paper4x6, Paper5x7, Paper8x10, Paper5x5,
    Carrier,         // a filed-out carrier's black rebate inside the paper margin
    Emulsion,        // the unexposed film just outside the gate, printed with the photo
    WhiteMount, BlackMount,
    SocialSquare, SocialPortrait, SocialStory
};

const char* kind_id(Kind k);
Kind kind_from_id(const std::string& id);
const char* kind_label(Kind k);
bool is_film_kind(Kind k);

// The nine gauges of `FilmFormat.swift`. `135` and the 120 frames keep the
// `rebate` module (it already draws a developed strip); the rest are drawn here.
bool film_gauge_known(const std::string& id);
bool film_gauge_uses_rebate(const std::string& id);

// The stock's own factory edge print, from `docs/print-frame-model.md`'s
// verified table. Empty when the stock has no documented inscription, or when
// the gauge carries none (4 x 5 sheets, Super 8, Instax).
const char* edge_print_for(const std::string& stock, const std::string& gauge);

struct Request {
    Kind kind = Kind::None;
    // Film Border
    std::string gauge = "135";
    int frames = 1;
    bool medium_format_back = false;
    std::string inscription;
    std::string frame_no = "12";
    // The stock's id (`fp4plus125`, `hp5plus400`, ...). Sheet notch codes are
    // per stock: without one there is nothing to draw.
    std::string stock;
    // Shared
    double framing_x = 0.0, framing_y = 0.0;   // -1..1 of the crop's slack
    double margin_mm = 3.0;
    // Materials, display-referred 0..1, supplied by the host from the spectra.
    double paper_rgb[3] = {0.930, 0.923, 0.900};
    // 印出来的片基（Carrier / Emulsion）：胶片自己的密度，不归一化。
    double film_rgb[3]  = {0.965, 0.700, 0.520};
    // **光箱下的片边**（Film Border）：负片 = 片基透射色归一化到 0.9（浅），
    // 反转片 = 最大密度（深）。官方 `PrintFrame` 的 `.film` 分支就是这个。
    double film_light[3] = {0.975, 0.960, 0.940};
    // 片边上的印字：同一套密度取 0.72 档（官方的 `edgeDensities`）。
    double film_ink[3]   = {0.055, 0.050, 0.046};
    // The DX number, on the same strip and through the same light box: a
    // negative's bars are 0.5 denser than its rebate (official `edgeDensities`).
    double film_bar[3]   = {0.55, 0.40, 0.32};
    /// Negatives carry a DX code; a transparency's edge has none
    /// (`edgeBars` is empty when `stock.isReversal`).
    bool draws_bars = true;
    /// `printFrameEdgePrinting`: off leaves the strip bare.
    bool shows_print = true;
    // 印出来的相纸最大密度（Carrier 的片夹边）：官方 `rebateRGB`。
    double paper_dense[3] = {0.060, 0.055, 0.050};
    double card_rgb[3]  = {0.940, 0.940, 0.940};   // slide mount card (0.86 linear)
    double black_rgb[3] = {0.155, 0.155, 0.155};   // Black Mount (0.02 linear)
    bool stipple = true;                       // paper micro-texture
};

struct Layout {
    bool valid = false;
    int canvas_w = 0, canvas_h = 0;
    int photo_x = 0, photo_y = 0, photo_w = 0, photo_h = 0;   // destination, 1:1
    int crop_x = 0, crop_y = 0, crop_w = 0, crop_h = 0;       // source crop
    double px_per_mm = 0.0;
    double sheet_w_mm = 0.0, sheet_h_mm = 0.0;
    double margin_mm = 0.0;
    double rebate_mm = 0.0;
    bool square = false;
    /// The film was turned a quarter turn to match a portrait picture.
    bool rotated = false;
    /// How many frames' length of the roll the picture was exposed across
    /// (`FilmBorderGeometry.spanning`); 1 unless the gauge spans frames.
    int frames = 1;
    /// The aperture ran across the whole film (a medium-format back).
    bool exposes_edges = false;
};

bool layout(const Request& req, int src_w, int src_h, Layout& out, std::string& error);

// `out` is canvas_w * canvas_h * 3, sRGB 8-bit, the same domain the writers take.
void compose(const Request& req, const Layout& L, const uint8_t* src, int src_w, int src_h,
             std::vector<uint8_t>& out);

}  // namespace print_frame
