/*
 * SpektraLab for Android — RAW decode JNI bridge.
 * GPLv3. Decodes RAW files via LibRaw and opens engine sessions directly.
 */
#include <jni.h>
#include <cstdint>
#include <cstdio>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>
#include <mutex>
#include <unordered_map>

#ifdef __ANDROID__
#include <android/log.h>
#endif

#include "spektrafilm/spk_engine.h"
#include <libraw/libraw.h>

#include "io/raw_decoder.hpp"
#include "export_metadata.hpp"

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL \
    Java_app_spektralab_engine_SpektraLabEngine_##name

#define LOG_TAG "SpektraLabRAW"
#ifdef __ANDROID__
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) fprintf(stderr, __VA_ARGS__)
#define LOGE(...) fprintf(stderr, __VA_ARGS__)
#endif

static void throw_status(JNIEnv* env, spk_status st) {
    if (st == SPK_OK) return;
    char msg[512];
    const char* detail = spk_last_error(nullptr);
    snprintf(msg, sizeof(msg), "spektralab: %s", detail ? detail : "unknown error");
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) { env->ThrowNew(cls, msg); env->DeleteLocalRef(cls); }
}

// **Dimensions belong to a session, not to the process.** A single pair of
// globals made an unrelated RAW open change what an existing session reported;
// that is a data race in the export/thumbnail paths and reads as an
// intermittently rotated or differently sized photograph. Keep both the
// decoder size and the frame actually handed to the engine per handle.
struct RawSessionInfo {
    uint32_t width = 0, height = 0;
    uint32_t source_width = 0, source_height = 0;
    int raw_flip = 0;
    spk_export::SourceExif exif;
    // XYZ -> camera, from LibRaw's own calibration. A decode-time white
    // balance needs it: the multipliers are what turn an illuminant into
    // per-channel gains for *this* sensor.
    bool has_matrix = false;
    float camera_from_xyz[9] = {0};
};
static std::mutex g_raw_info_mutex;
static std::unordered_map<jlong, RawSessionInfo> g_raw_info;


static void throw_runtime(JNIEnv* env, const char* msg) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) { env->ThrowNew(cls, msg); env->DeleteLocalRef(cls); }
}

// Open a session from a RAW file path.
// Returns session handle, or 0 on failure.
static jlong open_raw_impl(JNIEnv* env, jlong engineHandle, jstring filePath,
                          jstring paramsJson, jint maxPixels, jint maxLongEdge,
                          const std::array<float, 4>* user_mul) {
    spk_engine* engine = reinterpret_cast<spk_engine*>(engineHandle);
    if (!engine) { throw_runtime(env, "engine is null"); return 0; }
    
    const char* path = env->GetStringUTFChars(filePath, nullptr);
    if (!path) { throw_runtime(env, "filePath is null"); return 0; }
    
    LOGI("decoding RAW: %s", path);
    
    spk::io::DecodedRaw raw;
    std::string error;
    spk::io::RawDecodeThreadScope scope;
    
    // maxPixels is a decode-time hint as well: a frame more than four times
    // the budget is decoded at half size, which is a quarter of the memory
    // and still more pixels than the session will keep.
    bool ok = spk::io::decode_raw_compatible(path, user_mul, raw, error,
                                            size_t(maxPixels > 0 ? maxPixels : 0));
    env->ReleaseStringUTFChars(filePath, path);
    
    if (!ok) {
        std::string msg = "RAW decode failed: " + error;
        throw_runtime(env, msg.c_str());
        return 0;
    }
    
    LOGI("RAW decoded: %ux%u, %zu floats", raw.width, raw.height, raw.rgb.size());
    RawSessionInfo info;
    info.source_width = raw.width;
    info.source_height = raw.height;
    info.raw_flip = raw.metadata.raw_flip;
    // LibRaw's capture fields, not the camera JPEG's metadata. Nothing here
    // includes location, a serial number or an owner string.
    info.exif.make = raw.metadata.make;
    info.exif.model = raw.metadata.model;
    info.exif.datetime = raw.metadata.capture_datetime;
    info.exif.shutter = raw.metadata.shutter;
    info.exif.aperture = raw.metadata.aperture;
    info.exif.iso = raw.metadata.iso;
    info.exif.focal_mm = raw.metadata.focal_mm;
    info.exif.present = true;
    LOGI("RAW orientation: flip=%d, decoded=%ux%u", info.raw_flip, raw.width, raw.height);
    // Optional bound on the source the engine is given, in pixels. The app
    // derives it from spk_max_plane_bytes(): a plane is 12 B/px, and a device
    // whose maxStorageBufferRange is 128 MiB refuses anything past ~11 MP.
    const uint64_t src_pixels = uint64_t(raw.width) * uint64_t(raw.height);
    const uint32_t src_edge = std::max(raw.width, raw.height);
    const bool over_area = maxPixels > 0 && src_pixels > uint64_t(maxPixels);
    const bool over_edge = maxLongEdge > 0 && src_edge > uint32_t(maxLongEdge);
    if (over_area || over_edge) {
        // Two ceilings, two units. Area is what the GPU enforces -- a plane is
        // 12 B/px and a binding may not exceed maxStorageBufferRange, so a
        // 3:4 frame holds half again the pixels of 3:2 at the same long edge.
        // The long edge is the size this build was actually validated at.
        double sc = 1.0;
        if (over_area) sc = std::min(sc, std::sqrt(double(maxPixels) / double(src_pixels)));
        if (over_edge) sc = std::min(sc, double(maxLongEdge) / double(src_edge));
        const uint32_t nw = uint32_t(std::lround(raw.width * sc));
        const uint32_t nh = uint32_t(std::lround(raw.height * sc));
        std::vector<float> small(size_t(nw) * nh * 3);
        for (uint32_t y = 0; y < nh; ++y) {
            const uint32_t y0 = uint32_t(double(y) * raw.height / nh);
            const uint32_t y1 = std::max(y0 + 1u, uint32_t(double(y + 1) * raw.height / nh));
            for (uint32_t x = 0; x < nw; ++x) {
                const uint32_t x0 = uint32_t(double(x) * raw.width / nw);
                const uint32_t x1 = std::max(x0 + 1u, uint32_t(double(x + 1) * raw.width / nw));
                for (int c = 0; c < 3; ++c) {
                    double sum = 0; uint32_t n = 0;
                    for (uint32_t yy = y0; yy < y1; ++yy)
                        for (uint32_t xx = x0; xx < x1; ++xx) { sum += raw.rgb[(size_t(yy) * raw.width + xx) * 3 + c]; ++n; }
                    small[(size_t(y) * nw + x) * 3 + c] = float(sum / n);
                }
            }
        }
        raw.rgb.swap(small); raw.width = nw; raw.height = nh;
        LOGI("RAW downscaled to %ux%u for the session (cap %d px, %d long edge)",
             raw.width, raw.height, int(maxPixels), int(maxLongEdge));
    }
    info.width = raw.width;
    info.height = raw.height;
    info.has_matrix = true;
    std::copy(raw.metadata.camera_from_xyz.begin(), raw.metadata.camera_from_xyz.end(),
              info.camera_from_xyz);
    
    // Create spk_image from decoded RAW
    spk_image img;
    img.data = raw.rgb.data();
    img.width = raw.width;
    img.height = raw.height;
    img.channels = 3;
    
    const char* params = nullptr;
    if (paramsJson) {
        params = env->GetStringUTFChars(paramsJson, nullptr);
    }
    
    char* open_json = nullptr;
    spk_session* session = spk_open(engine, &img, params, &open_json);
    
    if (paramsJson) env->ReleaseStringUTFChars(paramsJson, params);
    if (open_json) spk_string_free(open_json);
    
    if (!session) {
        // Same rule as the plain open: the engine has a reason, and a message
        // without it classifies as [E-9000] 未归类 -- the one answer that
        // tells neither the user nor the log anything.
        char msg[512];
        std::snprintf(msg, sizeof msg, "spk_open failed after RAW decode: %s",
                      spk_last_error(engine));
        throw_runtime(env, msg);
        return 0;
    }
    
    const jlong handle = reinterpret_cast<jlong>(session);
    {
        std::lock_guard<std::mutex> lock(g_raw_info_mutex);
        g_raw_info[handle] = info;
    }
    return handle;
}

// **The sensor's own size, before anything is unpacked.**
//
// `open_file` reads the container header and nothing else -- a few milliseconds,
// against 600 MB of float for a 50 MP decode. The import sheet needs the number
// *before* the decode so it can say what the frame is and what it will cost; a
// sheet that appears after the memory is already spent is a post-mortem, not a
// choice.
JNI(jintArray, nativeRawDimensions)(JNIEnv* env, jclass, jstring filePath) {
    if (!filePath) return nullptr;
    const char* path = env->GetStringUTFChars(filePath, nullptr);
    if (!path) return nullptr;
    int w = 0, h = 0;
    {
        LibRaw probe;
        if (probe.open_file(path) == LIBRAW_SUCCESS) {
            w = int(probe.imgdata.sizes.width);
            h = int(probe.imgdata.sizes.height);
            if (w <= 0 || h <= 0) {
                w = int(probe.imgdata.sizes.raw_width);
                h = int(probe.imgdata.sizes.raw_height);
            }
        }
    }
    env->ReleaseStringUTFChars(filePath, path);
    if (w <= 0 || h <= 0) return nullptr;
    const jint dims[2] = {jint(w), jint(h)};
    jintArray out = env->NewIntArray(2);
    if (out) env->SetIntArrayRegion(out, 0, 2, dims);
    return out;
}

JNI(jlong, nativeOpenRawSession)(JNIEnv* env, jclass, jlong engineHandle, jstring filePath,
                                 jstring paramsJson, jint maxPixels, jint maxLongEdge) {
    return open_raw_impl(env, engineHandle, filePath, paramsJson, maxPixels, maxLongEdge, nullptr);
}

// The same open, with the white balance chosen at decode time instead of taken
// from the camera's as-shot record. `mul` is R, G, B, G2.
JNI(jlong, nativeOpenRawSessionTuned)(JNIEnv* env, jclass, jlong engineHandle, jstring filePath,
                                      jstring paramsJson, jint maxPixels, jint maxLongEdge,
                                      jfloatArray mul) {
    if (!mul || env->GetArrayLength(mul) < 4)
        return open_raw_impl(env, engineHandle, filePath, paramsJson, maxPixels, maxLongEdge, nullptr);
    std::array<float, 4> channels{};
    env->GetFloatArrayRegion(mul, 0, 4, channels.data());
    return open_raw_impl(env, engineHandle, filePath, paramsJson, maxPixels, maxLongEdge, &channels);
}

// **A blackbody at `kelvin`, expressed as channel gains for this sensor.**
//
// The chromaticity comes from the CIE approximations (Planckian below 4000 K,
// daylight locus above), the sensor's own XYZ -> camera matrix turns it into
// camera RGB, and the multipliers are the reciprocal of that response,
// normalised so green stays at 1 -- the same convention as LibRaw's as-shot
// multipliers. `tint` is the green/magenta axis, +-150 as upstream.
JNI(jfloatArray, nativeRawMulForKelvin)(JNIEnv* env, jclass, jlong sessionHandle,
                                        jdouble kelvin, jdouble tint) {
    float matrix[9];
    {
        std::lock_guard<std::mutex> lock(g_raw_info_mutex);
        const auto it = g_raw_info.find(sessionHandle);
        if (it == g_raw_info.end() || !it->second.has_matrix) return nullptr;
        std::copy(std::begin(it->second.camera_from_xyz), std::end(it->second.camera_from_xyz), matrix);
    }
    const double t = std::clamp(kelvin, 2000.0, 12000.0);
    double x, y;
    if (t < 4000.0) {
        x = -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910;
        y = -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
    } else {
        x = -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390;
        y = 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483;
    }
    const double xyz[3] = {x / std::max(y, 1e-6), 1.0, (1.0 - x - y) / std::max(y, 1e-6)};
    double cam[3];
    for (int c = 0; c < 3; ++c)
        cam[c] = matrix[c * 3 + 0] * xyz[0] + matrix[c * 3 + 1] * xyz[1] + matrix[c * 3 + 2] * xyz[2];
    double mul[3];
    for (int c = 0; c < 3; ++c) mul[c] = 1.0 / std::max(std::fabs(cam[c]), 1e-6);
    // A negative response means the illuminant is outside this sensor's gamut;
    // fall back to the as-shot balance rather than inventing a colour cast.
    if (cam[0] <= 0.0 || cam[1] <= 0.0 || cam[2] <= 0.0) return nullptr;
    const double g = mul[1];
    for (int c = 0; c < 3; ++c) mul[c] /= g;
    mul[1] *= std::pow(2.0, -std::clamp(tint, -150.0, 150.0) / 300.0);

    jfloatArray out = env->NewFloatArray(4);
    if (!out) return nullptr;
    const float values[4] = {float(mul[0]), float(mul[1]), float(mul[2]), float(mul[1])};
    env->SetFloatArrayRegion(out, 0, 4, values);
    return out;
}

// The decoder's own size, before the device cap shrank it. Equal to the
// session size whenever nothing was cut, which is what the UI compares.
JNI(jintArray, nativeRawSourceSize)(JNIEnv* env, jclass) {
    jintArray result = env->NewIntArray(2);
    if (!result) return nullptr;
    // This legacy query has no session argument. It is deliberately no longer
    // a guess from the newest RAW; callers that need a frame's size must ask
    // the session itself.
    jint values[2] = {0, 0};
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

// Get the dimensions of *this* session's image. A JPEG session is not in the
// RAW map; its Java wrapper already carries the Bitmap's dimensions, so the
// fallback keeps that legacy path working without claiming another RAW's size.
JNI(jintArray, nativeGetSessionDimensions)(JNIEnv* env, jclass, jlong sessionHandle) {
    jintArray result = env->NewIntArray(2);
    if (!result) return nullptr;
    jint values[2] = {0, 0};
    {
        std::lock_guard<std::mutex> lock(g_raw_info_mutex);
        const auto it = g_raw_info.find(sessionHandle);
        if (it != g_raw_info.end()) {
            values[0] = jint(it->second.width);
            values[1] = jint(it->second.height);
        }
    }
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

JNI(jstring, nativeRawExifJson)(JNIEnv* env, jclass, jlong sessionHandle) {
    std::lock_guard<std::mutex> lock(g_raw_info_mutex);
    const auto it = g_raw_info.find(sessionHandle);
    if (it == g_raw_info.end()) return env->NewStringUTF("{}");
    const auto& e = it->second.exif;
    auto esc=[](const std::string& v) {
        std::string o;
        for (char c : v) {
            if (c == '\\' || c == '"') o += '\\';
            if (static_cast<unsigned char>(c) >= 32) o += c;
        }
        return o;
    };
    char num[256];
    std::snprintf(num, sizeof num,
                  "\"shutter\":%.10g,\"aperture\":%.10g,\"iso\":%.10g,\"focal\":%.10g",
                  e.shutter, e.aperture, e.iso, e.focal_mm);
    std::string json = "{\"make\":\"" + esc(e.make) + "\",\"model\":\"" + esc(e.model)
        + "\",\"datetime\":\"" + esc(e.datetime) + "\"," + num + "}";
    return env->NewStringUTF(json.c_str());
}

// Close-time removal for the per-session record. The real session is released
// by spektralab_jni.cpp; this only forgets the RAW decoder's sidecar.
JNI(void, nativeForgetRawSession)(JNIEnv*, jclass, jlong sessionHandle) {
    std::lock_guard<std::mutex> lock(g_raw_info_mutex);
    g_raw_info.erase(sessionHandle);
}

// Get RAW metadata as JSON string (for debugging/info)
JNI(jstring, nativeGetRawMetadata)(JNIEnv* env, jclass, jstring filePath) {
    const char* path = env->GetStringUTFChars(filePath, nullptr);
    if (!path) { throw_runtime(env, "filePath is null"); return nullptr; }
    
    spk::io::DecodedRaw raw;
    std::string error;
    spk::io::RawDecodeThreadScope scope;
    
    bool ok = spk::io::decode_raw_compatible(path, raw, error);
    env->ReleaseStringUTFChars(filePath, path);
    
    if (!ok) {
        std::string msg = "RAW decode failed: " + error;
        throw_runtime(env, msg.c_str());
        return nullptr;
    }
    
    // Build simple JSON with key metadata
    std::string json = "{";
    json += "\"width\":" + std::to_string(raw.width) + ",";
    json += "\"height\":" + std::to_string(raw.height) + ",";
    json += "\"make\":\"" + raw.metadata.make + "\",";
    json += "\"model\":\"" + raw.metadata.model + "\",";
    json += "\"decoder\":\"" + raw.metadata.decoder_name + "\",";
    json += "\"libraw_version\":\"" + raw.metadata.libraw_version + "\"";
    json += "}";
    
    return env->NewStringUTF(json.c_str());
}
