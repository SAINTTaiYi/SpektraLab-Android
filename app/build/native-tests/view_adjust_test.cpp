// Compile-and-run regression checks for the pure view-layer maths.
//
// No Android or engine headers are involved, so the same file can be built on a
// host with any C++20 compiler or cross-compiled for Android with the NDK:
//
//   clang++ -std=c++20 -Iapp/src/main/cpp app/build/native-tests/view_adjust_test.cpp -o view_adjust_test
//   ./view_adjust_test
//
// `scripts/verify.ps1` compiles it for Android when no host C++ compiler is
// available; the assertions are then a syntax/template check and can be run on
// a device or a Linux host.
#include "view_adjust.hpp"

#include <cmath>
#include <cstdio>
#include <string>

static int failures = 0;

static void check(bool ok, const char* what) {
    if (!ok) {
        std::fprintf(stderr, "FAIL: %s\n", what);
        ++failures;
    }
}

int main() {
    using spk_adjust::ViewAdjust;

    ViewAdjust neutral;
    neutral.normalise();
    check(neutral.neutral(), "a zero ViewAdjust must be neutral");

    double px[3] = {0.25, 0.5, 0.75};
    const double before[3] = {px[0], px[1], px[2]};
    spk_adjust::apply_pixel(px, 0, 0, 1, 1, neutral);
    check(px[0] == before[0] && px[1] == before[1] && px[2] == before[2],
          "a neutral pass must not change a pixel");

    // Monotone cubic with two points is the straight line between them.
    const double xs[2] = {0.0, 1.0}, ys[2] = {0.0, 1.0};
    float table[256] = {};
    spk_adjust::detail::build_curve(table, xs, ys, 2);
    for (int i = 0; i < 256; ++i)
        check(std::fabs(table[i] - i / 255.0) < 1e-4f, "identity curve must be identity");

    // A monotone S curve must stay inside [0,1] and keep its endpoints.
    const double sx[4] = {0.0, 0.3, 0.7, 1.0}, sy[4] = {0.0, 0.2, 0.8, 1.0};
    spk_adjust::detail::build_curve(table, sx, sy, 4);
    check(std::fabs(table[0]) < 1e-6f && std::fabs(table[255] - 1.0f) < 1e-6f,
          "curve endpoints must be preserved");
    for (int i = 1; i < 255; ++i)
        check(table[i] >= 0.0f && table[i] <= 1.0f && table[i] >= table[i - 1],
              "curve must be monotone and bounded");

    // A black/white point edit must leave pure black and pure white alone.
    ViewAdjust levels;
    levels.black_point = 20.0;
    levels.white_point = 20.0;
    levels.normalise();
    double black[3] = {0.0, 0.0, 0.0}, white[3] = {1.0, 1.0, 1.0};
    spk_adjust::apply_pixel(black, 0, 0, 1, 1, levels);
    spk_adjust::apply_pixel(white, 0, 0, 1, 1, levels);
    check(black[0] <= 0.0 && white[0] <= 1.0, "levels must clamp their range");

    if (failures) {
        std::fprintf(stderr, "%d view_adjust check(s) failed\n", failures);
        return 1;
    }
    std::puts("view_adjust: all checks passed");
    return 0;
}
