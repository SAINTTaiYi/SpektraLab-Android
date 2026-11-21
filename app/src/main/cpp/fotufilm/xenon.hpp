#pragma once
// Illuminant.xenonProjection: the measured xenon arc through its two-term filtration, the lamp a
// release print is judged under. Its own spectrum on purpose -- a daylight or blackbody generator
// would erase the arc's narrow blue structure and the projector's IR-cut tail.
namespace spectral {

static const float kMeasuredXenonProjection[spectral_grid::kCount] = {
    0.136746f, 0.206570f, 0.281575f, 0.381492f, 0.477762f, 0.580290f,
    0.680332f, 0.752829f, 0.783793f, 0.796124f, 0.831295f, 0.891565f,
    0.966010f, 1.007482f, 1.079627f, 1.117596f, 1.188808f, 1.309845f,
    1.362901f, 1.240829f, 1.162528f, 1.170259f, 1.136912f, 1.146093f,
    1.091440f, 1.070819f, 1.058280f, 1.049140f, 1.045223f, 1.038052f,
    1.038052f, 1.031067f, 1.030798f, 1.024933f, 1.014342f, 1.006031f,
    1.000000f, 0.997845f, 1.009409f, 1.012829f, 1.009534f, 1.004477f,
    0.979938f, 0.945534f, 0.913741f, 0.902674f, 0.902922f, 0.915710f,
    0.920497f, 0.905554f, 0.870881f, 0.821181f, 0.767668f, 0.738798f,
    0.728746f, 0.710694f, 0.716394f, 0.716332f, 0.672787f, 0.560642f,
    0.420808f, 0.292705f, 0.180933f, 0.105948f, 0.061306f, 0.042653f,
    0.031200f, 0.026257f, 0.018528f, 0.017583f, 0.016041f, 0.014596f,
    0.014301f, 0.015295f, 0.014873f, 0.012305f, 0.010856f, 0.018298f,
    0.018653f, 0.021065f, 0.017534f,
};

inline Spd xenon_projection() {
    constexpr float a = 0.13620723f, b = -0.02311008f;
    Spd values{};
    for (int i = 0; i < spectral_grid::kCount; ++i) {
        const float q = (380.0f + 5.0f * float(i) - 560.0f) / 100.0f;
        values[size_t(i)] = kMeasuredXenonProjection[i] * std::exp(a * q + b * q * q);
    }
    const float anchor = values[spectral_grid::kAnchorIndex];
    if (anchor > 0.0f) {
        for (int i = 0; i < spectral_grid::kCount; ++i) values[size_t(i)] /= anchor;
    }
    return values;
}

}  // namespace spectral
