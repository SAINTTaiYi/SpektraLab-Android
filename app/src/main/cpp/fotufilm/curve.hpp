#ifndef FOTUFILM_HOST_CURVE_HPP
#define FOTUFILM_HOST_CURVE_HPP

// CharacteristicCurve and SampledCharacteristicCurve: dye density against log exposure.
//
// A colour record is usually measured, so the analytic six-parameter fit is a fallback; the
// sampled record is the real one and carries C1 Hermite interpolation through every point.
// Its tangents are Fritsch-Carlson monotone cubic, which is what keeps the interpolation from
// overshooting the local extrema a measured curve can have.

#include <algorithm>
#include <cmath>
#include <vector>

namespace curve {

inline float softplus(float x) {
    if (x > 20.0f) return x;
    if (x < -20.0f) return std::exp(x);
    return std::log1p(std::exp(x));
}

inline float hermite(float x, float x0, float x1, float y0, float y1, float m0, float m1) {
    const float h = x1 - x0;
    const float t = std::min(std::max((x - x0) / h, 0.0f), 1.0f);
    const float delta = y1 - y0;
    const float a = h * m0, b = h * m1;
    return y0 + t * (a + t * (3.0f * delta - 2.0f * a - b + t * (-2.0f * delta + a + b)));
}

struct Sampled {
    std::vector<float> log_exposure;
    std::vector<float> density;
    std::vector<float> slopes;

    Sampled() = default;
    Sampled(std::vector<float> le, std::vector<float> d)
        : log_exposure(std::move(le)), density(std::move(d)), slopes(density.size(), 0.0f) {
        for (size_t i = 1; i + 1 < density.size(); ++i) {
            const double h0 = double(log_exposure[i]) - double(log_exposure[i - 1]);
            const double h1 = double(log_exposure[i + 1]) - double(log_exposure[i]);
            const double d0 = (double(density[i]) - double(density[i - 1])) / h0;
            const double d1 = (double(density[i + 1]) - double(density[i])) / h1;
            if (d0 * d1 > 0.0) {
                const double w0 = 2.0 * h1 + h0, w1 = h1 + 2.0 * h0;
                slopes[i] = float((w0 + w1) / (w0 / d0 + w1 / d1));
            }
        }
    }

    /// FilmStock.expired's curve move: a speed loss is a pure translation of the curve.
    Sampled shifted(float dx, float dy) const {
        Sampled out = *this;
        for (float &v : out.log_exposure) v += dx;
        for (float &v : out.density) v += dy;
        return out;    // the slopes are unchanged by a translation
    }

    float value_at(float x) const {
        if (x <= log_exposure.front()) return density.front();
        if (x >= log_exposure.back()) return density.back();
        size_t low = 0, high = log_exposure.size() - 1;
        while (high - low > 1) {
            const size_t mid = (low + high) / 2;
            if (log_exposure[mid] <= x) low = mid; else high = mid;
        }
        return hermite(x, log_exposure[low], log_exposure[high], density[low], density[high],
                       slopes[low], slopes[high]);
    }

    size_t size() const { return density.size(); }
};

struct Component {
    float gamma, toe, toe_width, shoulder, shoulder_width;

    float density(float x) const {
        const float t = toe_width * softplus((x - toe) / toe_width);
        const float s = shoulder_width * softplus((x - shoulder) / shoulder_width);
        return gamma * std::min(std::max(t - s, 0.0f), shoulder - toe);
    }
    float density_range() const { return gamma * (shoulder - toe); }
};

struct Curve {
    float d_min, gamma, toe, toe_width, shoulder, shoulder_width;
    bool has_secondary = false;
    Component secondary{};
    bool has_sampled = false;
    Sampled sampled;

    float d_max() const {
        if (has_sampled) {
            float m = sampled.density.front();
            for (float d : sampled.density) m = std::max(m, d);
            return m;
        }
        return d_min + gamma * (shoulder - toe) + (has_secondary ? secondary.density_range() : 0.0f);
    }

    float density(float x) const {
        if (has_sampled) return sampled.value_at(x);
        const float t = toe_width * softplus((x - toe) / toe_width);
        const float s = shoulder_width * softplus((x - shoulder) / shoulder_width);
        const float base = d_min + gamma * std::min(std::max(t - s, 0.0f), shoulder - toe);
        return base + (has_secondary ? secondary.density(x) : 0.0f);
    }
};

/// CharacteristicCurve.logExposure: the log exposure that develops to `target` density.
///
/// A sampled record brackets on its own table, and a target past its last sample is not a
/// bisection at all: a dense shoulder can bend back, so the answer is the first rising crossing
/// if there is one and the peak otherwise.
inline float log_exposure(const Curve &curve, float target) {
    float lo = curve.has_sampled ? curve.sampled.log_exposure.front() : curve.toe - 6.0f;
    float hi = curve.has_sampled ? curve.sampled.log_exposure.back() : curve.shoulder + 6.0f;
    if (curve.has_sampled && !curve.sampled.density.empty()
        && target > curve.sampled.density.back()) {
        size_t upper = curve.sampled.density.size();
        for (size_t i = 0; i < curve.sampled.density.size(); ++i) {
            if (curve.sampled.density[i] >= target) { upper = i; break; }
        }
        if (upper == curve.sampled.density.size()) {
            size_t peak = 0;
            for (size_t i = 1; i < curve.sampled.density.size(); ++i) {
                if (curve.sampled.density[i] > curve.sampled.density[peak]) peak = i;
            }
            return curve.sampled.log_exposure[peak];
        }
        if (upper == 0) return curve.sampled.log_exposure.front();
        lo = curve.sampled.log_exposure[upper - 1];
        hi = curve.sampled.log_exposure[upper];
    }
    for (int i = 0; i < 60; ++i) {
        const float mid = (lo + hi) * 0.5f;
        if (curve.density(mid) < target) lo = mid; else hi = mid;
    }
    return (lo + hi) * 0.5f;
}

}  // namespace curve

#endif
