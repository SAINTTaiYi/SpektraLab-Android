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

#ifdef __ANDROID__
#include <android/log.h>
#endif

#include "spektrafilm/spk_engine.h"
#include "io/raw_decoder.hpp"

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

static uint32_t g_last_raw_width = 0;
static uint32_t g_last_raw_height = 0;

static void throw_runtime(JNIEnv* env, const char* msg) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) { env->ThrowNew(cls, msg); env->DeleteLocalRef(cls); }
}

// Open a session from a RAW file path.
// Returns session handle, or 0 on failure.
JNI(jlong, nativeOpenRawSession)(JNIEnv* env, jclass, jlong engineHandle,
    jstring filePath, jstring paramsJson, jint maxLongEdge) {
    spk_engine* engine = reinterpret_cast<spk_engine*>(engineHandle);
    if (!engine) { throw_runtime(env, "engine is null"); return 0; }
    
    const char* path = env->GetStringUTFChars(filePath, nullptr);
    if (!path) { throw_runtime(env, "filePath is null"); return 0; }
    
    LOGI("decoding RAW: %s", path);
    
    spk::io::DecodedRaw raw;
    std::string error;
    spk::io::RawDecodeThreadScope scope;
    
    bool ok = spk::io::decode_raw_compatible(path, raw, error);
    env->ReleaseStringUTFChars(filePath, path);
    
    if (!ok) {
        std::string msg = "RAW decode failed: " + error;
        throw_runtime(env, msg.c_str());
        return 0;
    }
    
    LOGI("RAW decoded: %ux%u, %zu floats", raw.width, raw.height, raw.rgb.size());
    // Optional bound on the source the engine is given. A 20 MP frame is a
    // 244 MB plane; staying under that keeps every stage inside what the phone
    // GPU will actually complete.
    if (maxLongEdge > 0 && int(std::max(raw.width, raw.height)) > int(maxLongEdge)) {
        const double sc = double(maxLongEdge) / double(std::max(raw.width, raw.height));
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
        LOGI("RAW downscaled to %ux%u for the session", raw.width, raw.height);
    }
    g_last_raw_width = raw.width;
    g_last_raw_height = raw.height;
    
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
        throw_runtime(env, "spk_open failed after RAW decode");
        return 0;
    }
    
    return reinterpret_cast<jlong>(session);
}

// Get the actual dimensions of the current session's image.
JNI(jintArray, nativeGetSessionDimensions)(JNIEnv* env, jclass, jlong sessionHandle) {
    (void)sessionHandle; // Not needed, we use stored RAW dimensions
    jintArray result = env->NewIntArray(2);
    if (!result) return nullptr;
    jint values[2] = {(jint)g_last_raw_width, (jint)g_last_raw_height};
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
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
