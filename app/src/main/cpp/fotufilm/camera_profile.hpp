#ifndef FOTUFILM_HOST_CAMERA_PROFILE_HPP
#define FOTUFILM_HOST_CAMERA_PROFILE_HPP

// Camera spectral profiles: the correction a decoded RAW still needs.
//
// `CameraProfileCorrection.resolve` mirrors itself: an explicit profile id first, then a
// case-insensitive make/model match; the anchor matrices come from solving the profile's own
// sensitivity against a training set, the correction blends them in mired and divides out the
// daylight anchor. The solve lives in the engine, so the two matrices arrive precomputed in
// `camera_profiles.bin` — this file only interpolates, inverts and applies, which keeps the
// port lossless without re-deriving a constrained spectral reconstruction.

#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <unordered_map>
#include <vector>

namespace camera_profile {

constexpr float kTungstenKelvin = 2856.0f;
constexpr float kDaylightKelvin = 6504.0f;
/// Below this a correction does not move a pixel enough to be worth a pass.
constexpr float kMinimumDeviation = 1e-12f;

using Mat3 = std::array<float, 9>;

struct Profile {
    std::string id;
    Mat3 tungsten{};
    Mat3 daylight{};
};

inline bool load(const std::string &path, std::vector<Profile> &out) {
    std::FILE *file = std::fopen(path.c_str(), "rb");
    if (!file) return false;
    char magic[4];
    if (std::fread(magic, 1, 4, file) != 4 || std::memcmp(magic, "CPR1", 4) != 0) {
        std::fclose(file);
        return false;
    }
    uint32_t count = 0;
    std::fread(&count, 4, 1, file);
    out.clear();
    out.reserve(count);
    for (uint32_t i = 0; i < count; ++i) {
        uint32_t idlen = 0;
        std::fread(&idlen, 4, 1, file);
        Profile p;
        p.id.resize(idlen);
        std::fread(&p.id[0], 1, idlen, file);
        uint32_t bits[18];
        std::fread(bits, 4, 18, file);
        for (int k = 0; k < 9; ++k) p.tungsten[size_t(k)] = [&] {
            float v; std::memcpy(&v, &bits[k], 4); return v; }();
        for (int k = 0; k < 9; ++k) p.daylight[size_t(k)] = [&] {
            float v; std::memcpy(&v, &bits[9 + k], 4); return v; }();
        // The sensitivity the matrices were solved from, kept only so the file is a record.
        uint32_t bands = 0;
        std::fread(&bands, 4, 1, file);
        std::vector<uint64_t> skip(size_t(bands) * 3);
        if (!skip.empty()) std::fread(skip.data(), 8, skip.size(), file);
        out.push_back(std::move(p));
    }
    std::fclose(file);
    return true;
}

/// `CameraSpectralProfileStore.identityKey`: the first make token, then the model with a repeated
/// make prefix removed, both folded and joined by 0x1F.
inline std::string identity_key(const std::string &make, const std::string &model) {
    auto fold = [](const std::string &s) {
        std::string out;
        size_t b = s.find_first_not_of(" \t\r\n");
        size_t e = s.find_last_not_of(" \t\r\n");
        if (b == std::string::npos) return out;
        for (size_t i = b; i <= e; ++i) {
            unsigned char c = static_cast<unsigned char>(s[i]);
            out.push_back(char(std::tolower(c)));
        }
        return out;
    };
    auto tokens = [](const std::string &s) {
        std::vector<std::string> out;
        size_t i = 0;
        while (i < s.size()) {
            while (i < s.size() && std::isspace(static_cast<unsigned char>(s[i]))) ++i;
            size_t b = i;
            while (i < s.size() && !std::isspace(static_cast<unsigned char>(s[i]))) ++i;
            if (i > b) out.push_back(s.substr(b, i - b));
        }
        return out;
    };
    const std::string made = fold(make);
    const std::vector<std::string> made_tokens = tokens(made);
    if (made_tokens.empty()) return std::string();
    const std::string brand = made_tokens.front();
    std::vector<std::string> parts = tokens(fold(model));
    if (parts.size() > 1 && parts.front() == brand) parts.erase(parts.begin());
    if (parts.empty()) return std::string();
    std::string key = brand;
    key.push_back('\x1f');
    for (size_t i = 0; i < parts.size(); ++i) {
        if (i) key.push_back(' ');
        key += parts[i];
    }
    return key;
}

/// `DualIlluminantMatrices.matrix(cct:)`: the anchors blended in mired, re-pinned so a neutral
/// stays neutral at every temperature.
inline Mat3 matrix_at(const Profile &p, float cct) {
    const float cool_mired = 1.0f / kDaylightKelvin;
    const float warm_mired = 1.0f / kTungstenKelvin;
    const float mired = 1.0f / std::max(cct, 1.0f);
    float weight = (mired - cool_mired) / (warm_mired - cool_mired);
    weight = std::min(std::max(weight, 0.0f), 1.0f);
    if (weight == 1.0f) return p.tungsten;
    if (weight == 0.0f) return p.daylight;
    Mat3 out{};
    for (int row = 0; row < 3; ++row) {
        float v[3];
        for (int c = 0; c < 3; ++c) {
            v[c] = weight * p.tungsten[size_t(row * 3 + c)]
                 + (1.0f - weight) * p.daylight[size_t(row * 3 + c)];
        }
        const float sum = v[0] + v[1] + v[2];
        if (std::fabs(sum) > 1e-4f) { v[0] /= sum; v[1] /= sum; v[2] /= sum; }
        for (int c = 0; c < 3; ++c) out[size_t(row * 3 + c)] = v[c];
    }
    return out;
}

inline bool invert3(const Mat3 &m, Mat3 &out) {
    const double a = m[0], b = m[1], c = m[2];
    const double d = m[3], e = m[4], f = m[5];
    const double g = m[6], h = m[7], i = m[8];
    const double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
    const double det = a * A + b * B + c * C;
    if (std::fabs(det) < 1e-12) return false;
    out[0] = float(A / det);
    out[1] = float(-(b * i - c * h) / det);
    out[2] = float((b * f - c * e) / det);
    out[3] = float(B / det);
    out[4] = float((a * i - c * g) / det);
    out[5] = float(-(a * f - c * d) / det);
    out[6] = float(C / det);
    out[7] = float(-(a * h - b * g) / det);
    out[8] = float((a * e - b * d) / det);
    return true;
}

/// `correction(cct) = M(cct) · M(6504)⁻¹`, rows renormalized to preserve white. Identity (and
/// therefore skipped) at the daylight anchor.
inline bool correction(const Profile &p, float cct, Mat3 &out) {
    Mat3 inverse{};
    if (!invert3(p.daylight, inverse)) return false;
    const Mat3 blended = matrix_at(p, cct);
    for (int row = 0; row < 3; ++row) {
        double v[3] = {0, 0, 0};
        for (int k = 0; k < 3; ++k) {
            const double w = blended[size_t(row * 3 + k)];
            v[0] += w * inverse[size_t(k * 3 + 0)];
            v[1] += w * inverse[size_t(k * 3 + 1)];
            v[2] += w * inverse[size_t(k * 3 + 2)];
        }
        const double sum = v[0] + v[1] + v[2];
        if (std::fabs(sum) > 1e-4) { v[0] /= sum; v[1] /= sum; v[2] /= sum; }
        for (int c = 0; c < 3; ++c) out[size_t(row * 3 + c)] = float(v[c]);
    }
    return true;
}

inline float max_deviation_from_identity(const Mat3 &m) {
    float worst = 0.0f;
    for (int row = 0; row < 3; ++row) {
        for (int col = 0; col < 3; ++col) {
            const float expected = row == col ? 1.0f : 0.0f;
            worst = std::max(worst, std::fabs(m[size_t(row * 3 + col)] - expected));
        }
    }
    return worst;
}

/// One 3×3 pass over interleaved scene-linear RGBA. Alpha is transport, not light.
inline void apply(const Mat3 &m, std::vector<float> &rgba) {
    const float r0 = m[0], r1 = m[1], r2 = m[2];
    const float r3 = m[3], r4 = m[4], r5 = m[5];
    const float r6 = m[6], r7 = m[7], r8 = m[8];
    const size_t n = rgba.size() / 4 * 4;
    for (size_t i = 0; i < n; i += 4) {
        const float x = rgba[i], y = rgba[i + 1], z = rgba[i + 2];
        rgba[i]     = r0 * x + r1 * y + r2 * z;
        rgba[i + 1] = r3 * x + r4 * y + r5 * z;
        rgba[i + 2] = r6 * x + r7 * y + r8 * z;
    }
}

/// The same pass over a caller-owned raster.
inline void apply(const Mat3 &m, float *rgba, size_t count) {
    const float r0 = m[0], r1 = m[1], r2 = m[2];
    const float r3 = m[3], r4 = m[4], r5 = m[5];
    const float r6 = m[6], r7 = m[7], r8 = m[8];
    const size_t n = count / 4 * 4;
    for (size_t i = 0; i < n; i += 4) {
        const float x = rgba[i], y = rgba[i + 1], z = rgba[i + 2];
        rgba[i]     = r0 * x + r1 * y + r2 * z;
        rgba[i + 1] = r3 * x + r4 * y + r5 * z;
        rgba[i + 2] = r6 * x + r7 * y + r8 * z;
    }
}

struct Store {
    std::vector<Profile> profiles;
    std::unordered_map<std::string, size_t> by_id;
    std::unordered_map<std::string, std::string> by_identity;

    /// `camera_identities.txt`: one `id<TAB>make<TAB>model` line per bundled profile. The
    /// identities come from the same registry the engine folds, so the lookup matches.
    void load_identities(const std::string &path) {
        std::FILE *file = std::fopen(path.c_str(), "r");
        if (!file) return;
        char line[512];
        while (std::fgets(line, sizeof line, file)) {
            std::string s(line);
            while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
            const size_t a = s.find('\t');
            if (a == std::string::npos) continue;
            const size_t b = s.find('\t', a + 1);
            if (b == std::string::npos) continue;
            const std::string id = s.substr(0, a);
            const std::string make = s.substr(a + 1, b - a - 1);
            const std::string model = s.substr(b + 1);
            const std::string key = identity_key(make, model);
            if (!key.empty()) by_identity[key] = id;
        }
        std::fclose(file);
    }

    bool load_all(const std::string &bin, const std::string &identities) {
        if (!load(bin, profiles)) return false;
        for (size_t i = 0; i < profiles.size(); ++i) by_id[profiles[i].id] = i;
        load_identities(identities);
        return true;
    }

    /// Resolve by explicit id, then by make/model.
    const Profile *resolve(const std::string &profile_id, const std::string &make,
                           const std::string &model) const {
        if (!profile_id.empty()) {
            auto it = by_id.find(profile_id);
            if (it != by_id.end()) return &profiles[it->second];
        }
        if (make.empty() || model.empty()) return nullptr;
        auto it = by_identity.find(identity_key(make, model));
        if (it == by_identity.end()) return nullptr;
        auto found = by_id.find(it->second);
        return found == by_id.end() ? nullptr : &profiles[found->second];
    }
};

/// A resolved correction, or nothing when there is no profile, no scene temperature, or an
/// exactly identity matrix — the three cases the engine's `resolve` returns nil for.
struct Resolved {
    std::string profile_id;
    float cct = 0.0f;
    Mat3 matrix{};
    float max_deviation = 0.0f;
    explicit operator bool() const { return max_deviation > kMinimumDeviation; }
};

inline Resolved resolve_correction(const Store &store, const std::string &profile_id,
                                   const std::string &make, const std::string &model,
                                   bool have_kelvin, float kelvin) {
    Resolved out;
    if (!have_kelvin || !(kelvin > 0.0f)) return out;
    const Profile *p = store.resolve(profile_id, make, model);
    if (!p) return out;
    if (!correction(*p, kelvin, out.matrix)) return out;
    out.profile_id = p->id;
    out.cct = kelvin;
    out.max_deviation = max_deviation_from_identity(out.matrix);
    return out;
}

}  // namespace camera_profile

#endif
