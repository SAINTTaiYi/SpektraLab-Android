#pragma once
#include <algorithm>
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

// Source capture metadata, carried separately from the pixels. This is an
// allow-list, not a copy of the source's EXIF block: no GPS, serial numbers,
// owner fields, thumbnails or maker notes cross into the export.

struct SourceExif {
    std::string make, model, datetime;
    double shutter = 0.0, aperture = 0.0, iso = 0.0, focal_mm = 0.0;
    bool present = false;
};
// **Reading the source's own EXIF, here rather than through the platform.**
//
// The Java side asked `android.media.ExifInterface` for seven attributes and
// got one back -- the capture date -- on a file whose IFD0 clearly holds Make
// and Model. Rather than guess which reader disagrees, the export reads the
// bytes it is about to embed: one SOI walk, one IFD walk, both endiannesses,
// every offset bounded. It runs on the source file's first kilobyte, so it is
// also cheap.
inline uint16_t exif_u16(const uint8_t* p, bool little) {
    return little ? uint16_t(p[0] | (p[1] << 8)) : uint16_t((p[0] << 8) | p[1]);
}
inline uint32_t exif_u32(const uint8_t* p, bool little) {
    return little ? (uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24))
                  : ((uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | uint32_t(p[3]));
}

struct ExifView {
    const uint8_t* base = nullptr;   // TIFF header ("II"/"MM")
    size_t size = 0;
    bool little = true;

    bool has(size_t off, size_t n) const { return off <= size && n <= size - off; }
    uint16_t u16(size_t off) const { return exif_u16(base + off, little); }
    uint32_t u32(size_t off) const { return exif_u32(base + off, little); }

    // A tag's value bytes: inline when they fit in four, at an offset otherwise.
    bool value(uint16_t type, uint32_t count, uint32_t raw, size_t unit,
               size_t& at, size_t& bytes) const {
        bytes = size_t(count) * unit;
        if (bytes <= 4) { at = 0; return true; }        // caller reads `raw` as bytes
        at = raw;
        (void)type;
        return has(at, bytes);
    }
    std::string ascii(uint32_t count, uint32_t raw) const {
        std::string out;
        const size_t n = size_t(count);
        if (n == 0) return out;
        const uint8_t* p = n <= 4 ? reinterpret_cast<const uint8_t*>(&raw) : (has(raw, n) ? base + raw : nullptr);
        if (!p) return out;
        for (size_t i = 0; i < n && p[i]; ++i) out.push_back(char(p[i]));
        while (!out.empty() && (out.back() == ' ' || out.back() == '\0')) out.pop_back();
        return out;
    }
    double rational(uint32_t raw) const {
        if (!has(raw, 8)) return 0.0;
        const double num = double(u32(raw));
        const double den = double(u32(raw + 4));
        return den != 0.0 ? num / den : 0.0;
    }
    double integer(uint16_t type, uint32_t raw) const {
        // An inline SHORT lives in the *first two bytes* of the value field,
        // which is the low half in a little-endian file and the high half in a
        // big-endian one. Reading low-always turned this camera's ISO 64 into 0.
        if (type == 3) return double(little ? (raw & 0xFFFFu) : (raw >> 16));
        if (type == 4) return double(raw);
        return 0.0;
    }
};

// Fills `out` from a JPEG's APP1(Exif). Returns false when there is none.
inline bool read_jpeg_source_exif(const uint8_t* data, size_t size, SourceExif& out) {
    if (!data || size < 4 || data[0] != 0xFF || data[1] != 0xD8) return false;
    size_t p = 2;
    while (p + 4 <= size) {
        if (data[p] != 0xFF) { ++p; continue; }
        const uint8_t marker = data[p + 1];
        if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { p += 2; continue; }
        if (marker == 0xDA || marker == 0xD9) break;      // image data starts
        const size_t length = size_t(data[p + 2] << 8) | data[p + 3];
        if (length < 2 || p + 2 + length > size) break;
        const uint8_t* payload = data + p + 4;
        const size_t payload_size = length - 2;
        if (marker == 0xE1 && payload_size > 6 &&
            std::memcmp(payload, "Exif\0\0", 6) == 0) {
            ExifView v;
            v.base = payload + 6;
            v.size = payload_size - 6;
            if (v.size < 8) return false;
            if (!(v.base[0] == 'I' && v.base[1] == 'I') && !(v.base[0] == 'M' && v.base[1] == 'M')) return false;
            v.little = v.base[0] == 'I';
            if (v.u16(2) != 42) return false;
            const uint32_t ifd0 = v.u32(4);
            if (!v.has(ifd0, 2)) return false;
            const uint16_t n0 = v.u16(ifd0);
            uint32_t exif_ifd = 0;
            for (uint16_t i = 0; i < n0; ++i) {
                const size_t e = ifd0 + 2 + size_t(i) * 12;
                if (!v.has(e, 12)) break;
                const uint16_t tag = v.u16(e);
                const uint16_t type = v.u16(e + 2);
                const uint32_t count = v.u32(e + 4);
                const uint32_t raw = v.u32(e + 8);
                if (tag == 0x010F && type == 2) out.make = v.ascii(count, raw);
                else if (tag == 0x0110 && type == 2) out.model = v.ascii(count, raw);
                else if (tag == 0x8769) exif_ifd = raw;
            }
            if (exif_ifd && v.has(exif_ifd, 2)) {
                const uint16_t n1 = v.u16(exif_ifd);
                for (uint16_t i = 0; i < n1; ++i) {
                    const size_t e = exif_ifd + 2 + size_t(i) * 12;
                    if (!v.has(e, 12)) break;
                    const uint16_t tag = v.u16(e);
                    const uint16_t type = v.u16(e + 2);
                    const uint32_t count = v.u32(e + 4);
                    const uint32_t raw = v.u32(e + 8);
                    if (tag == 0x9003 && type == 2) out.datetime = v.ascii(count, raw);
                    else if (tag == 0x829A && type == 5) out.shutter = v.rational(raw);
                    else if (tag == 0x829D && type == 5) out.aperture = v.rational(raw);
                    // 0x8827 is ISOSpeedRatings; phones that write the newer
                    // 0x8833 (ISOSpeed) instead left ISO at zero. Take either.
                    // 0x8827 (ISOSpeedRatings) and 0x8833 (ISOSpeed) both appear
                    // in the wild; whichever comes first with a value wins, and a
                    // later zero does not erase it.
                    else if ((tag == 0x8827 || tag == 0x8833) && out.iso == 0.0)
                        out.iso = v.integer(type, raw);
                    else if (tag == 0x920A && type == 5) out.focal_mm = v.rational(raw);
                }
            }
            // A camera writes "2026:11:03 19:51:25"; only accept that shape.
            if (out.datetime.size() > 19) out.datetime.resize(19);
            out.present = !(out.make.empty() && out.model.empty() && out.datetime.empty());
            return out.present;
        }
        p += 2 + length;
    }
    return false;
}



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

/// The same APP1, with the source's *capture* fields. The output orientation
/// stays top-left because the geometry node has already baked any rotation.
inline std::vector<uint8_t> build_exif_app1(const std::string& software,
                                            const std::string& datetime,
                                            uint32_t width, uint32_t height,
                                            const SourceExif& src) {
    struct E { uint16_t tag, type; uint32_t count, value; };
    std::vector<E> ifd0 = {
        {0x0112,3,1,1}, {0x0131,2,uint32_t(software.size()+1),0},
        {0x0132,2,20,0}, {0x8769,4,1,0}
    };
    if (!src.make.empty()) ifd0.push_back({0x010F,2,uint32_t(src.make.size()+1),0});
    if (!src.model.empty()) ifd0.push_back({0x0110,2,uint32_t(src.model.size()+1),0});
    std::vector<E> exif = {
        {0x9000,7,4,0x32333230}, {0xA001,3,1,1}, {0xA002,4,1,width}, {0xA003,4,1,height}
    };
    if (!src.datetime.empty()) exif.push_back({0x9003,2,20,0});
    if (src.shutter > 0) exif.push_back({0x829A,5,1,0});
    if (src.aperture > 0) exif.push_back({0x829D,5,1,0});
    if (src.iso > 0) exif.push_back({0x8827,3,1,uint32_t(src.iso+0.5)});
    if (src.focal_mm > 0) exif.push_back({0x920A,5,1,0});
    auto bytag=[](std::vector<E>& v){std::sort(v.begin(),v.end(),[](const E&a,const E&b){return a.tag<b.tag;});};
    bytag(ifd0); bytag(exif);
    const size_t ifd0_at=8, ifd0_size=2+12*ifd0.size()+4;
    const size_t exif_at=ifd0_at+ifd0_size, exif_size=2+12*exif.size()+4;
    std::vector<uint8_t> t(ifd0_at+ifd0_size+exif_size,0);
    t[0]='I';t[1]='I';put16(t,2,42);put32(t,4,uint32_t(ifd0_at));
    auto add=[&](const void* p,size_t n)->uint32_t{
        while(t.size()&1)t.push_back(0);uint32_t off=uint32_t(t.size());
        const auto* b=static_cast<const uint8_t*>(p);t.insert(t.end(),b,b+n);return off;
    };
    auto ascii=[&](E& e,const std::string& v){std::string z=v;z.resize(e.count-1,' ');z.push_back('\0');e.value=add(z.data(),e.count);};
    auto rational=[&](E& e,double v){uint32_t den=1000000,n=uint32_t(v*den+0.5);
        uint32_t p=add(&n,4);add(&den,4);e.value=p;};
    for(E& e:ifd0){
        if(e.tag==0x0131)ascii(e,software);
        else if(e.tag==0x0132){std::string d=datetime;d.resize(19,' ');ascii(e,d);}
        else if(e.tag==0x010F)ascii(e,src.make);
        else if(e.tag==0x0110)ascii(e,src.model);
        else if(e.tag==0x8769)e.value=uint32_t(exif_at);
    }
    for(E& e:exif){
        if(e.tag==0x9003){std::string d=src.datetime;d.resize(19,' ');ascii(e,d);}
        else if(e.tag==0x829A)rational(e,src.shutter);
        else if(e.tag==0x829D)rational(e,src.aperture);
        else if(e.tag==0x920A)rational(e,src.focal_mm);
    }
    auto write=[&](size_t at,const std::vector<E>& entries){
        put16(t,at,uint16_t(entries.size()));size_t q=at+2;
        for(const E& e:entries){put16(t,q,e.tag);put16(t,q+2,e.type);put32(t,q+4,e.count);put32(t,q+8,e.value);q+=12;}
        put32(t,q,0);
    };
    write(ifd0_at,ifd0);write(exif_at,exif);
    std::vector<uint8_t> app1={'E','x','i','f',0,0};app1.insert(app1.end(),t.begin(),t.end());return app1;
}


}  // namespace spk_export
