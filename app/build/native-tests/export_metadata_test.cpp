// Round-trip checks for the hand-written EXIF/ICC writer. Compiled by
// scripts/verify.ps1; runnable on any host with libjpeg headers available.
#include "export_metadata.hpp"

#include <cstdio>

static int failures = 0;

static void check(bool ok, const char* what) {
    if (!ok) { std::fprintf(stderr, "FAIL: %s\n", what); ++failures; }
}

int main() {
    spk_export::SourceExif src;
    src.make = "CAMERA";
    src.model = "MODEL X";
    src.datetime = "2026:11:21 12:34:56";
    src.shutter = 1.0 / 250.0;
    src.aperture = 2.8;
    src.iso = 200;
    src.focal_mm = 35.0;
    src.present = true;

    std::vector<uint8_t> app1 = spk_export::build_exif_app1(
            "SpektraLab Android", "2026:11:21 12:34:56", 4000, 3000, src);
    check(app1.size() > 6 && app1[0] == 'E' && app1[5] == 0, "APP1 header");

    // Wrap the segment in a minimal JPEG so the reader walks the same bytes.
    std::vector<uint8_t> jpeg = {0xFF, 0xD8};
    const uint16_t len = uint16_t(app1.size() + 2);
    jpeg.push_back(uint8_t(0xFF)); jpeg.push_back(0xE1);
    jpeg.push_back(uint8_t(len >> 8)); jpeg.push_back(uint8_t(len & 0xFF));
    jpeg.insert(jpeg.end(), app1.begin(), app1.end());
    jpeg.push_back(0xFF); jpeg.push_back(0xD9);

    spk_export::SourceExif parsed;
    check(spk_export::read_jpeg_source_exif(jpeg.data(), jpeg.size(), parsed),
          "EXIF segment must be readable");
    check(parsed.make == src.make, "Make survives the round trip");
    check(parsed.model == src.model, "Model survives the round trip");
    check(parsed.datetime == src.datetime, "DateTimeOriginal survives the round trip");
    check(parsed.iso == src.iso, "ISO survives the round trip");
    check(parsed.shutter > 0.0039 && parsed.shutter < 0.0041, "shutter is rational");
    check(parsed.aperture > 2.79 && parsed.aperture < 2.81, "aperture is rational");

    if (failures) {
        std::fprintf(stderr, "%d export_metadata check(s) failed\n", failures);
        return 1;
    }
    std::puts("export_metadata: all checks passed");
    return 0;
}
