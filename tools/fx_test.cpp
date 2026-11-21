// Does each new UI knob actually move the picture?
#include "spektrafilm/spk_engine.h"
#include "io/raw_decoder.hpp"
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <cmath>
#include <cstdlib>

static uint64_t hash_and_mean(const spk_result& r, double mean[3]) {
    uint64_t h = 1469598103934665603ull; double n = 0;
    mean[0] = mean[1] = mean[2] = 0;
    const unsigned short* q = r.rgba16;
    unsigned st = r.row_stride_px ? r.row_stride_px : r.width;
    for (unsigned y = 0; y < r.height; ++y) {
        const unsigned short* row = q + size_t(y) * st * 4;
        for (unsigned x = 0; x < r.width; ++x) {
            for (int c = 0; c < 3; ++c) mean[c] += row[size_t(x) * 4 + c];
            n += 1;
        }
        for (unsigned x = 0; x < r.width * 4; x += 97) { h ^= row[x]; h *= 1099511628211ull; }
    }
    for (int c = 0; c < 3; ++c) mean[c] /= n * 257.0;
    return h;
}


static void dump_ppm(const spk_result& r, const char* path) {
    FILE* f = std::fopen(path, "wb");
    if (!f) return;
    std::fprintf(f, "P6\n%u %u\n255\n", r.width, r.height);
    unsigned st = r.row_stride_px ? r.row_stride_px : r.width;
    std::vector<unsigned char> row(size_t(r.width) * 3);
    for (unsigned y = 0; y < r.height; ++y) {
        const unsigned short* q = r.rgba16 + size_t(y) * st * 4;
        for (unsigned x = 0; x < r.width; ++x)
            for (int c = 0; c < 3; ++c) {
                unsigned v = q[size_t(x) * 4 + c] >> 8;
                row[size_t(x) * 3 + c] = (unsigned char)(v > 255 ? 255 : v);
            }
        std::fwrite(row.data(), 1, row.size(), f);
    }
    std::fclose(f);
}

int main(int argc, char** argv) {
    spk::io::DecodedRaw raw; std::string err;
    if (!spk::io::decode_raw_compatible(argv[2], raw, err)) { std::printf("decode %s\n", err.c_str()); return 1; }
    unsigned le = 1200, nw = unsigned(raw.width * (double(le)/std::max(raw.width, raw.height)));
    unsigned nh = unsigned(raw.height * (double(le)/std::max(raw.width, raw.height)));
    std::vector<float> small(size_t(nw)*nh*3);
    for (unsigned y=0;y<nh;++y) for (unsigned x=0;x<nw;++x) {
        unsigned sx=unsigned(double(x)*raw.width/nw), sy=unsigned(double(y)*raw.height/nh);
        for (int c=0;c<3;++c) small[(size_t(y)*nw+x)*3+c]=raw.rgb[(size_t(sy)*raw.width+sx)*3+c];
    }
    spk_engine* e = spk_engine_create(argv[1], nullptr);
    spk_image img{small.data(), nw, nh, 3};
    for (int i = 3; i < argc; ++i) {
        std::string extra = argv[i];
        std::string p = "{\"film_stock\":\"kodak_portra_400\",\"print_stock\":\"kodak_portra_endura\","
            "\"input_color_space\":\"ProPhoto RGB\",\"input_cctf_decoding\":false,"
            "\"output_color_space\":\"sRGB\",\"print_exposure\":1.00,\"preview_long_edge\":2048";
        if (extra != "-") p += "," + extra;
        p += "}";
        char* j = nullptr; spk_session* s = spk_open(e, &img, p.c_str(), &j); if (j) spk_string_free(j);
        if (!s) { std::printf("%-46s OPEN FAIL %s\n", extra.c_str(), spk_last_error(e)); continue; }
        char* sj=nullptr; spk_solve(s,"both",&sj); if (sj) spk_string_free(sj);
        std::fprintf(stderr, "[fx] render start\n"); spk_result r{}; spk_status st = spk_render(s, "live", &r); std::fprintf(stderr, "[fx] render done st=%d rgba16=%p\n", int(st), (void*)r.rgba16);
        double m[3]; uint64_t h = hash_and_mean(r, m);
        if (const char* d = std::getenv("FX_DUMP")) dump_ppm(r, d);
        if (st != 0) std::printf("  err: %s\n", spk_last_error(e));
        std::printf("%-46s st=%d mean %6.2f/%6.2f/%6.2f  %016llx\n", extra.c_str(), int(st), m[0], m[1], m[2],
                    (unsigned long long)h);
        std::fprintf(stderr, "[fx] before free\n"); spk_result_free(&r); std::fprintf(stderr, "[fx] after free\n"); spk_session_release(s);
    }
    return 0;
}
