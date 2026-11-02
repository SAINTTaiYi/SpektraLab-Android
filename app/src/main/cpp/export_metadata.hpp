#pragma once
// What an exported file says about itself: the sRGB profile it was written in,
// and an Exif block that names the software, the time and the colour space.
//
// Header-only and free of JNI on purpose: the app calls these through the
// bridge, and `jpeg_meta_test` calls the same code on the host, so the markers
// are checked by a real JPEG decoder rather than by reading the source.

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <string>
#include <vector>

#include <jpeglib.h>

namespace spk_export {

inline bool load_srgb_profile_file(const std::string& path, std::vector<uint8_t>& out) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    std::fseek(f, 0, SEEK_END);
    const long size = std::ftell(f);
    std::fseek(f, 0, SEEK_SET);
    bool ok = size > 132 && size < (4 << 20);
    if (ok) {
        out.resize(size_t(size));
        ok = std::fread(out.data(), 1, out.size(), f) == out.size();
    }
    std::fclose(f);
    if (!ok) return false;
    // "acsp" at 36 and an RGB/XYZ data class at 16/20: this is a profile, not
    // a file that happens to live at that path.
    ok = std::memcmp(out.data() + 36, "acsp", 4) == 0 &&
         std::memcmp(out.data() + 16, "RGB ", 4) == 0;
    if (!ok) out.clear();
    return ok;
}

/// APP2, in the chunked form ICC specifies: a 14-byte header per marker, at
/// most 255 markers. sRGB is 588 bytes, but a display profile would not be.
inline void write_icc_marker(j_compress_ptr cinfo, const std::vector<uint8_t>& icc) {
    if (icc.empty()) return;
    constexpr size_t kMax = 65519;
    const size_t chunks = (icc.size() + kMax - 1) / kMax;
    for (size_t i = 0; i < chunks; ++i) {
        const size_t off = i * kMax;
        const size_t len = std::min(kMax, icc.size() - off);
        std::vector<uint8_t> seg(14 + len);
        std::memcpy(seg.data(), "ICC_PROFILE\0", 12);
        seg[12] = uint8_t(i + 1);
        seg[13] = uint8_t(chunks);
        std::memcpy(seg.data() + 14, icc.data() + off, len);
        jpeg_write_marker(cinfo, JPEG_APP0 + 2, seg.data(), unsigned(seg.size()));
    }
}

inline void put16(std::vector<uint8_t>& v, size_t at, uint16_t x) {
    v[at] = uint8_t(x); v[at + 1] = uint8_t(x >> 8);
}
inline void put32(std::vector<uint8_t>& v, size_t at, uint32_t x) {
    for (int i = 0; i < 4; ++i) v[at + i] = uint8_t(x >> (8 * i));
}

/// A complete APP1 Exif payload: "Exif\0\0", a little-endian TIFF header, IFD0
/// and one Exif sub-IFD.
///
/// It says only what is true of this file: who wrote it, when, that its pixels
/// are sRGB, and how many there are. It does **not** copy the source camera's
/// make, model or capture time — this picture is a print, not that exposure,
/// and claiming a body shot it would be a nicer lie than the app should tell.
/// (Carrying the original EXIF through, marked as such, is a separate decision.)
inline std::vector<uint8_t> build_exif_app1(const std::string& software,
                                            const std::string& datetime,
                                            uint32_t width, uint32_t height) {
    struct Entry { uint16_t tag, type; uint32_t count, value; };
    std::vector<Entry> ifd0 = {
        {0x0112, 3, 1, 1},                              // Orientation: top-left
        {0x0131, 2, uint32_t(software.size() + 1), 0},  // Software
        {0x0132, 2, 20, 0},                             // DateTime
        {0x8769, 4, 1, 0},                              // ExifIFDPointer
    };
    std::vector<Entry> exif = {
        // ExifVersion, four ASCII bytes "0232" packed little-endian: what a
        // 2.3-era reader expects, and what every writer since has emitted.
        {0x9000, 7, 4, 0x32333230},
        {0xA001, 3, 1, 1},                              // ColorSpace: sRGB
        {0xA002, 4, 1, width},
        {0xA003, 4, 1, height},
    };
    const size_t ifd0_at = 8;
    const size_t ifd0_size = 2 + 12 * ifd0.size() + 4;
    const size_t exif_at = ifd0_at + ifd0_size;
    const size_t exif_size = 2 + 12 * exif.size() + 4;

    std::vector<uint8_t> t(ifd0_at + ifd0_size + exif_size, 0);
    t[0] = 'I'; t[1] = 'I';
    put16(t, 2, 42);
    put32(t, 4, uint32_t(ifd0_at));
    auto add = [&](const void* p, size_t n) -> uint32_t {
        while (t.size() & 1) t.push_back(0);
        const uint32_t off = uint32_t(t.size());
        const uint8_t* b = static_cast<const uint8_t*>(p);
        t.insert(t.end(), b, b + n);
        return off;
    };
    std::string stamp = datetime;
    stamp.resize(19, ' ');
    stamp.push_back('\0');                              // 20 bytes, NUL included
    ifd0[1].value = add(software.c_str(), software.size() + 1);
    ifd0[2].value = add(stamp.data(), 20);
    ifd0[3].value = uint32_t(exif_at);

    auto write_ifd = [&](size_t at, const std::vector<Entry>& entries) {
        put16(t, at, uint16_t(entries.size()));
        size_t q = at + 2;
        for (const Entry& e : entries) {
            put16(t, q, e.tag); put16(t, q + 2, e.type);
            put32(t, q + 4, e.count); put32(t, q + 8, e.value);
            q += 12;
        }
        put32(t, q, 0);                                 // no next IFD
    };
    write_ifd(ifd0_at, ifd0);
    write_ifd(exif_at, exif);

    std::vector<uint8_t> app1({'E', 'x', 'i', 'f', 0, 0});
    app1.insert(app1.end(), t.begin(), t.end());
    return app1;
}


}  // namespace spk_export
