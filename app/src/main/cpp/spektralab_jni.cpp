/*
 * SpektraLab for Android — JNI bridge.
 * GPLv3. Bridges app.spektralab.engine.SpektraLabEngine (Kotlin) to spk_engine.h C ABI.
 */
#include <jni.h>
#include <cstdint>
#include <cstdio>
#include <cstdarg>
#include <cmath>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <chrono>
#include <jpeglib.h>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#endif

#include "spektrafilm/spk_engine.h"
#include "io/image_writer.hpp"
#include "export_metadata.hpp"
#include "view_adjust.hpp"

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL \
    Java_app_spektralab_engine_SpektraLabEngine_##name

// Where the engine's resources live. Export needs it for the sRGB profile, and
// `nativeCreateEngine` is the only place that knows.
static std::string g_resources_dir;

#define LOG_TAG "SpektraLabJNI"
#ifdef __ANDROID__
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) fprintf(stderr, __VA_ARGS__)
#define LOGE(...) fprintf(stderr, __VA_ARGS__)
#endif

static void throw_runtime(JNIEnv* env, const char* format, ...) {
    if (env->ExceptionCheck()) return;
    char buffer[1024];
    va_list args;
    va_start(args, format);
    std::vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) { env->ThrowNew(cls, buffer); env->DeleteLocalRef(cls); }
}

static void throw_status(JNIEnv* env, spk_status st) {
    if (st == SPK_OK) return;
    char msg[512];
    const char* detail = spk_last_error(nullptr);
    snprintf(msg, sizeof(msg), "spektralab: %s", detail ? detail : "unknown error");
    throw_runtime(env, msg);
}

JNI(void, nativeSetEnv)(JNIEnv* env, jclass, jstring name, jstring value) {
    if (!name || !value) return;
    const char* n = env->GetStringUTFChars(name, nullptr);
    const char* v = env->GetStringUTFChars(value, nullptr);
    if (n && v) setenv(n, v, 1);
    if (n) env->ReleaseStringUTFChars(name, n);
    if (v) env->ReleaseStringUTFChars(value, v);
}

// The device's per-binding storage range, in bytes (0 = no limit). The app
// asks before importing so it can cap the frame itself and say what it did,
// instead of failing at render time inside a kernel.
JNI(jlong, nativeMaxPlaneBytes)(JNIEnv*, jclass, jlong engineHandle) {
    spk_engine* engine = reinterpret_cast<spk_engine*>(engineHandle);
    if (!engine) return 0;
    return jlong(spk_max_plane_bytes(engine));
}

// The device as JSON (binding range, heaps, workgroup limits). The app's probe
// reads this once per engine and derives the export tiers from it.
JNI(jstring, nativeDeviceProfile)(JNIEnv* env, jclass, jlong engineHandle) {
    spk_engine* engine = reinterpret_cast<spk_engine*>(engineHandle);
    if (!engine) return env->NewStringUTF("{}");
    return env->NewStringUTF(spk_device_profile(engine));
}

// Does this driver enforce the range it reports? The app asks once, before its
// first import, with the plane size it wants; the engine walks 2x/4x/8x the
// reported value and answers with what actually landed. See `spk_probe_canary`.
JNI(jstring, nativeProbeCanary)(JNIEnv* env, jclass, jlong engineHandle, jlong needBytes) {
    spk_engine* engine = reinterpret_cast<spk_engine*>(engineHandle);
    if (!engine) return env->NewStringUTF("{}");
    return env->NewStringUTF(spk_probe_canary(engine, uint64_t(needBytes)));
}

// A stable code for an engine message, so the UI can show [E-1001] plus text.
JNI(jint, nativeErrorCode)(JNIEnv* env, jclass, jstring message) {
    if (!message) return 0;
    const char* text = env->GetStringUTFChars(message, nullptr);
    const int code = spk_error_code(text);
    env->ReleaseStringUTFChars(message, text);
    return code;
}

JNI(jlong, nativeTrimPool)(JNIEnv*, jclass, jlong engineHandle) {
    spk_engine* e = reinterpret_cast<spk_engine*>(engineHandle);
    if (!e) return 0;
    return jlong(spk_trim_pool(e));
}

// Both return `spk_status` rather than throwing: a session with nowhere to
// write its scratch file is not an error the caller can act on, and an export
// that ignores the return is no worse off than before it asked.
JNI(jint, nativeSuspendSession)(JNIEnv*, jclass, jlong sessionHandle) {
    spk_session* s = reinterpret_cast<spk_session*>(sessionHandle);
    if (!s) return SPK_ERR_INVALID_ARG;
    return jint(spk_suspend_session(s));
}

JNI(jint, nativeResumeSession)(JNIEnv*, jclass, jlong sessionHandle) {
    spk_session* s = reinterpret_cast<spk_session*>(sessionHandle);
    if (!s) return SPK_ERR_INVALID_ARG;
    return jint(spk_resume_session(s));
}

JNI(jlong, nativeCreateEngine)(JNIEnv* env, jclass, jstring resourcesDir) {
    const char* dir = env->GetStringUTFChars(resourcesDir, nullptr);
    if (!dir) { throw_runtime(env, "resourcesDir is null"); return 0; }
    LOGI("creating engine, resources=%s", dir);
    spk_engine* engine = spk_engine_create(dir, nullptr);
    // Kept for export: the sRGB profile and the film's own edge data live in
    // this tree, and an exported file has to carry the profile it was written
    // in rather than let the reader guess.
    g_resources_dir = dir;
    env->ReleaseStringUTFChars(resourcesDir, dir);
    if (!engine) {
        // `spk_last_error` ignores its handle: the slot is per-thread, and the
        // engine is exactly what does not exist here. Without this the app has
        // nothing but "failed" to show, which is how every driver problem
        // became an E-9000.
        const char* why = spk_last_error(nullptr);
        throw_runtime(env, "spk_engine_create failed: %s",
                      (why && *why) ? why : "no reason reported");
        return 0;
    }
    return reinterpret_cast<jlong>(engine);
}

JNI(void, nativeDestroyEngine)(JNIEnv*, jclass, jlong handle) {
    if (handle) spk_engine_destroy(reinterpret_cast<spk_engine*>(handle));
}

JNI(jlong, nativeOpenSession)(JNIEnv* env, jclass, jlong engineHandle,
    jobject pixels, jint width, jint height, jint channels) {
    spk_engine* engine = reinterpret_cast<spk_engine*>(engineHandle);
    if (!engine) { throw_runtime(env, "engine is null"); return 0; }
    if (!pixels) { throw_runtime(env, "pixels buffer is null"); return 0; }
    void* data = env->GetDirectBufferAddress(pixels);
    if (!data) { throw_runtime(env, "pixels must be a direct ByteBuffer"); return 0; }
    jlong capacity = env->GetDirectBufferCapacity(pixels);
    jlong needed = jlong(width) * height * channels * sizeof(float);
    if (capacity < needed) {
        char msg[128]; snprintf(msg, sizeof(msg), "buffer too small: %lld < %lld",
            (long long)capacity, (long long)needed);
        throw_runtime(env, msg); return 0;
    }
    spk_image img;
    img.data = static_cast<const float*>(data);
    img.width = static_cast<uint32_t>(width);
    img.height = static_cast<uint32_t>(height);
    img.channels = static_cast<uint32_t>(channels);
    char* open_json = nullptr;
    spk_session* session = spk_open(engine, &img, nullptr, &open_json);
    if (open_json) spk_string_free(open_json);
    if (!session) {
        // **Carry the engine's own words.** Throwing a bare "spk_open failed"
        // is what turned every open failure into [E-9000] 未归类: the app has a
        // code table keyed on the message, and the one string it was given
        // matched nothing. The engine always has a reason here.
        char msg[512];
        std::snprintf(msg, sizeof msg, "spk_open failed: %s", spk_last_error(engine));
        throw_runtime(env, msg);
        return 0;
    }
    return reinterpret_cast<jlong>(session);
}

/**
 * The last render's progress JSON. Carries `auto_exposure_ev`, which is the only
 * place the number the meter actually applied can be read: `spk_get_params`
 * returns what was *asked for*, not what the frame got. Without this, "the
 * picture looks darker on that phone" has no measurable answer.
 */
JNI(jstring, nativeProgressJson)(JNIEnv* env, jclass, jlong sessionHandle) {
    spk_session* session = reinterpret_cast<spk_session*>(sessionHandle);
    if (!session) return nullptr;
    char* json = nullptr;
    spk_status st = spk_progress(session, nullptr, &json);
    if (st != SPK_OK || !json) return nullptr;
    jstring out = env->NewStringUTF(json);
    spk_string_free(json);
    return out;
}

extern "C" JNIEXPORT void JNICALL
Java_app_spektralab_engine_SpektraLabEngine_nativeForgetRawSession(JNIEnv*, jclass, jlong);
extern "C" JNIEXPORT jstring JNICALL
Java_app_spektralab_engine_SpektraLabEngine_nativeRawExifJson(JNIEnv*, jclass, jlong);

JNI(void, nativeCloseSession)(JNIEnv* env, jclass, jlong sessionHandle) {
    if (sessionHandle) {
        Java_app_spektralab_engine_SpektraLabEngine_nativeForgetRawSession(env, nullptr, sessionHandle);
        spk_session_release(reinterpret_cast<spk_session*>(sessionHandle));
    }
}

JNI(void, nativeSetParams)(JNIEnv* env, jclass, jlong sessionHandle, jstring paramsJson) {
    spk_session* session = reinterpret_cast<spk_session*>(sessionHandle);
    if (!session) { throw_runtime(env, "session is null"); return; }
    const char* json = env->GetStringUTFChars(paramsJson, nullptr);
    if (!json) { throw_runtime(env, "paramsJson is null"); return; }
    spk_status st = spk_set_params(session, json, nullptr);
    env->ReleaseStringUTFChars(paramsJson, json);
    if (st != SPK_OK) throw_status(env, st);
}

JNI(jstring, nativeGetParams)(JNIEnv* env, jclass, jlong sessionHandle) {
    spk_session* session = reinterpret_cast<spk_session*>(sessionHandle);
    if (!session) { throw_runtime(env, "session is null"); return nullptr; }
    char* json = nullptr;
    spk_status st = spk_get_params(session, &json);
    if (st != SPK_OK || !json) { throw_status(env, st); return nullptr; }
    jstring result = env->NewStringUTF(json);
    spk_string_free(json);
    return result;
}

// Render into a pre-allocated direct ByteBuffer (uint16 RGBA).
// Returns elapsed_ms. Dimensions and row_stride are available via nativeGetLastRenderInfo.
static thread_local uint32_t g_last_row_stride = 0;
static thread_local uint32_t g_last_width = 0;
static thread_local uint32_t g_last_height = 0;

JNI(jintArray, nativeGetLastRenderInfo)(JNIEnv* env, jclass) {
    jintArray result = env->NewIntArray(3);
    if (!result) return nullptr;
    jint values[3] = {(jint)g_last_width, (jint)g_last_height, (jint)g_last_row_stride};
    env->SetIntArrayRegion(result, 0, 3, values);
    return result;
}
JNI(jdouble, nativeRenderToBuffer)(JNIEnv* env, jclass, jlong sessionHandle,
    jstring tier, jobject outBuffer, jint outWidth, jint outHeight) {
    spk_session* session = reinterpret_cast<spk_session*>(sessionHandle);
    if (!session) { throw_runtime(env, "session is null"); return -1; }
    const char* tierStr = env->GetStringUTFChars(tier, nullptr);
    if (!tierStr) { throw_runtime(env, "tier is null"); return -1; }
    void* outData = env->GetDirectBufferAddress(outBuffer);
    if (!outData) {
        env->ReleaseStringUTFChars(tier, tierStr);
        throw_runtime(env, "outBuffer must be direct"); return -1;
    }
    jlong capacity = env->GetDirectBufferCapacity(outBuffer);
    // Engine returns row_stride_px which may be > width; we copy row by row.
    // Our output buffer uses tightly packed width*4 channels.
    jlong needed = jlong(outWidth) * outHeight * 4 * sizeof(uint16_t);
    if (capacity < needed) {
        char msg[128]; snprintf(msg, sizeof(msg), "output buffer too small: %lld < %lld",
            (long long)capacity, (long long)needed);
        env->ReleaseStringUTFChars(tier, tierStr);
        throw_runtime(env, msg); return -1;
    }

    spk_result result{};
    spk_status st = spk_render(session, tierStr, &result);
    env->ReleaseStringUTFChars(tier, tierStr);
    if (st != SPK_OK) { throw_status(env, st); return -1; }

    // Store actual output dimensions from the engine
    g_last_width = result.width;
    g_last_height = result.height;
    g_last_row_stride = result.row_stride_px;

    const uint16_t* src = result.rgba16;
    uint16_t* dst = static_cast<uint16_t*>(outData);
    // Copy actual engine dimensions, not requested dimensions
    uint32_t w = result.width;
    uint32_t h = result.height;
    for (uint32_t y = 0; y < h; ++y) {
        std::memcpy(dst + size_t(y) * w * 4,
                    src + size_t(y) * result.row_stride_px * 4,
                    size_t(w) * 4 * sizeof(uint16_t));
    }
    double elapsed = result.elapsed_ms;
    spk_result_free(&result);
    return elapsed;
}


JNI(jstring, nativeMemoryReport)(JNIEnv* env, jclass, jlong engineHandle) {
    spk_engine* engine = reinterpret_cast<spk_engine*>(engineHandle);
    if (!engine) return nullptr;
    const char* json = spk_memory_report(engine);
    return json ? env->NewStringUTF(json) : nullptr;
}

JNI(jstring, nativeCapabilities)(JNIEnv* env, jclass, jlong engineHandle) {
    spk_engine* engine = reinterpret_cast<spk_engine*>(engineHandle);
    if (!engine) return nullptr;
    const char* json = spk_capabilities(engine);
    return json ? env->NewStringUTF(json) : nullptr;
}

// ---------------------------------------------------------------------------
// Overscan geometry, as the pipeline currently holds it.
//
// The rebate is laid out against the frame the render is about to draw, once
// per tier. When an export disagrees with the preview the question is always
// the same: what canvas did this tier think it was drawing? `spk_overscan_geometry`
// answers it with the layout's own numbers, so a report can carry them.
// ---------------------------------------------------------------------------
JNI(jstring, nativeOverscanGeometry)(JNIEnv* env, jclass, jlong sessionHandle) {
    spk_session* session = reinterpret_cast<spk_session*>(sessionHandle);
    if (!session) return env->NewStringUTF("{\"valid\":false}");
    char* json = nullptr;
    if (spk_overscan_geometry(session, &json) != SPK_OK || !json) {
        return env->NewStringUTF("{\"valid\":false}");
    }
    jstring out = env->NewStringUTF(json);
    spk_string_free(json);
    return out;
}

JNI(jstring, nativeGetBuildInfo)(JNIEnv* env, jclass) {
    const char* info = spk_build_info();
    return env->NewStringUTF(info ? info : "unknown");
}


// ---------------------------------------------------------------------------
// Direct ARGB path: no ByteBuffer, no endianness, no stride arithmetic in Java.
//
// nativeRenderBegin renders and keeps the result alive; returns {w, h}.
// nativeCopyArgbRange converts whole rows to opaque ARGB into `out`.
// ---------------------------------------------------------------------------
static spk_result g_result{};
static bool g_result_valid = false;

// **The source frame is linear; a screen is not.**
//
// `spk_export_source` returns the decoded working-space numbers with no
// transfer function, on purpose: that is what an undeveloped frame *is*, and
// it is the right thing to hand a file. On a phone screen it reads as a black
// rectangle, which is what "RAW 看不见底片，贼黑" was. So the display copy
// gets the sRGB encoding a display expects -- and only the display copy: the
// film pipeline and every export still see the linear values untouched.
static bool g_result_is_source = false;

// The view layer's tone controls. They run on the finished print -- screen and
// every exported file -- and do nothing at all until one of them leaves zero.
static spk_adjust::ViewAdjust g_adjust;

JNI(void, nativeSetViewAdjust)(JNIEnv* env, jclass, jdoubleArray values) {
    g_adjust = spk_adjust::ViewAdjust{};
    if (!values) return;
    const jsize n = env->GetArrayLength(values);
    double v[25] = {0};
    env->GetDoubleArrayRegion(values, 0, std::min<jsize>(n, 25), v);
    g_adjust.vibrance = n >= 25 ? v[24] : 0.0;
    g_adjust.temperature = v[0];
    g_adjust.tint = v[1];
    g_adjust.exposure = v[2];
    g_adjust.contrast = v[3];
    g_adjust.brightness = v[4];
    g_adjust.saturation = v[5];
    g_adjust.highlights = v[6];
    g_adjust.shadows = v[7];
    g_adjust.black_point = v[8];
    g_adjust.white_point = v[9];
    g_adjust.vignette = v[10];
    g_adjust.vignette_mid = v[11] > 0.0 ? v[11] : 50.0;
    // Four zones: master, shadows, midtones, highlights. Each is hue in
    // degrees, saturation 0..1 and luminance -1..1 -- the same units the
    // upstream grade layer takes.
    if (n >= 24) {
        constexpr double kPi = 3.14159265358979323846;
        for (int z = 0; z < 4; ++z) {
            const double hue = v[12 + z * 3] * kPi / 180.0;
            const double sat = v[13 + z * 3] * 0.25;
            for (int c = 0; c < 3; ++c)
                g_adjust.cb[z][c] = std::cos(hue - 2.0 * kPi * c / 3.0) * sat;
            g_adjust.cb_lum[z] = v[14 + z * 3] * 0.25;
            if (std::fabs(g_adjust.cb[z][0]) + std::fabs(g_adjust.cb[z][1]) +
                std::fabs(g_adjust.cb[z][2]) + std::fabs(g_adjust.cb_lum[z]) > 1e-9)
                g_adjust.balance = true;
        }
    }
    g_adjust.normalise();
}

// Five channels x eight control points, x < 0 meaning "unused". The tables are
// built here with the engine's own monotone cubic, so a curve drawn in the app
// is the curve the kernel would have drawn.
JNI(void, nativeSetViewCurves)(JNIEnv* env, jclass, jdoubleArray packed) {
    g_adjust.curves = false;
    if (!packed || env->GetArrayLength(packed) < 80) { g_adjust.normalise(); return; }
    double v[80] = {0};
    env->GetDoubleArrayRegion(packed, 0, 80, v);
    for (int ch = 0; ch < 5; ++ch) {
        double xs[8], ys[8];
        int n = 0;
        for (int k = 0; k < 8; ++k) {
            const double x = v[(ch * 8 + k) * 2], y = v[(ch * 8 + k) * 2 + 1];
            if (x < 0.0) continue;
            xs[n] = x;
            ys[n] = y;
            ++n;
        }
        if (n < 2) { for (int i = 0; i < 256; ++i) g_adjust.curve[ch][i] = float(i / 255.0); continue; }
        spk_adjust::detail::build_curve(g_adjust.curve[ch], xs, ys, n);
        for (int i = 0; i < 256; ++i)
            if (std::fabs(double(g_adjust.curve[ch][i]) - i / 255.0) > 1e-3) { g_adjust.curves = true; }
    }
    g_adjust.normalise();
}

// One place for "grade this result before it leaves the library".
static void grade_result(spk_result& r) {
    if (g_adjust.neutral() || !r.rgba16 || !r.width || !r.height) return;
    spk_adjust::apply(const_cast<uint16_t*>(r.rgba16), r.width, r.height,
                      r.row_stride_px ? r.row_stride_px : r.width, g_adjust);
}

// **The same exposure the render will use.** The meter applies an EV to the
// film, which is why the developed side of the comparison looks right while the
// decoded side looks three stops under. The source copy is shown at that same
// exposure, so "before" and "after" are a comparison of the film, not of two
// different exposures.
static double g_source_display_gain = 1.0;

JNI(void, nativeSetSourceDisplayEv)(JNIEnv*, jclass, jdouble ev) {
    const double clamped = ev < -4.0 ? -4.0 : (ev > 6.0 ? 6.0 : ev);
    g_source_display_gain = std::pow(2.0, clamped);
}

static const uint8_t* srgb_display_lut() {
    static uint8_t table[65536];
    static bool ready = false;
    if (!ready) {
        for (int i = 0; i < 65536; ++i) {
            const double x = double(i) / 65535.0;
            const double s = x <= 0.0031308 ? 12.92 * x : 1.055 * std::pow(x, 1.0 / 2.4) - 0.055;
            const int q = int(s * 255.0 + 0.5);
            table[i] = uint8_t(q < 0 ? 0 : (q > 255 ? 255 : q));
        }
        ready = true;
    }
    return table;
}

JNI(jintArray, nativeRenderBegin)(JNIEnv* env, jclass, jlong sessionHandle, jstring tier) {
    spk_session* session = reinterpret_cast<spk_session*>(sessionHandle);
    if (!session) { throw_runtime(env, "session is null"); return nullptr; }
    const char* t = env->GetStringUTFChars(tier, nullptr);
    if (!t) { throw_runtime(env, "tier is null"); return nullptr; }

    if (g_result_valid) { spk_result_free(&g_result); g_result_valid = false; }
    std::memset(&g_result, 0, sizeof g_result);

    spk_status st = spk_render(session, t, &g_result);
    env->ReleaseStringUTFChars(tier, t);
    if (st != SPK_OK) {
        char msg[512];
        std::snprintf(msg, sizeof msg, "spk_render failed (%d): %s", (int)st, spk_last_error(session ? nullptr : nullptr));
        throw_runtime(env, msg);
        return nullptr;
    }
    if (!g_result.rgba16 || g_result.width == 0 || g_result.height == 0) {
        spk_result_free(&g_result);
        throw_runtime(env, "render produced no pixels");
        return nullptr;
    }
    g_result_valid = true;
    g_result_is_source = false;

    jintArray out = env->NewIntArray(2);
    if (!out) return nullptr;
    jint dims[2] = {(jint)g_result.width, (jint)g_result.height};
    env->SetIntArrayRegion(out, 0, 2, dims);
    return out;
}

// nativeExportSource: the decoded frame itself at `tier`, before any film.
//
// This is what "原图" shows, and it had no implementation at all: Java declared
// `nativeExportSource` and called it after every RAW import, the call threw
// UnsatisfiedLinkError, and the catch around it logged "export failed" and
// moved on. Nobody saw a blank frame because a camera-JPEG extractor stood in
// for it -- so removing that extractor is what finally made the missing half
// visible. Same result slot as a render, so `copyArgbRange` reads it either way.
JNI(jintArray, nativeExportSource)(JNIEnv* env, jclass, jlong sessionHandle, jstring tier) {
    spk_session* session = reinterpret_cast<spk_session*>(sessionHandle);
    if (!session) { throw_runtime(env, "session is null"); return nullptr; }
    const char* t = tier ? env->GetStringUTFChars(tier, nullptr) : nullptr;
    if (!t) { throw_runtime(env, "tier is null"); return nullptr; }

    if (g_result_valid) { spk_result_free(&g_result); g_result_valid = false; }
    std::memset(&g_result, 0, sizeof g_result);

    spk_status st = spk_export_source(session, t, &g_result);
    env->ReleaseStringUTFChars(tier, t);
    if (st != SPK_OK) {
        char msg[512];
        std::snprintf(msg, sizeof msg, "spk_export_source failed (%d): %s",
                      (int)st, spk_last_error(nullptr));
        throw_runtime(env, msg);
        return nullptr;
    }
    if (!g_result.rgba16 || g_result.width == 0 || g_result.height == 0) {
        spk_result_free(&g_result);
        throw_runtime(env, "source export produced no pixels");
        return nullptr;
    }
    g_result_valid = true;
    g_result_is_source = true;

    jintArray out = env->NewIntArray(2);
    if (!out) return nullptr;
    jint dims[2] = {(jint)g_result.width, (jint)g_result.height};
    env->SetIntArrayRegion(out, 0, 2, dims);
    return out;
}

JNI(jint, nativeCopyArgbRange)(JNIEnv* env, jclass, jintArray dst, jint rowOffset, jint rowCount) {
    if (!g_result_valid) { throw_runtime(env, "no rendered result"); return -1; }
    const uint32_t w = g_result.width;
    const uint32_t h = g_result.height;
    if (rowOffset < 0 || rowCount <= 0 || uint32_t(rowOffset) >= h) return 0;
    uint32_t rows = uint32_t(rowCount);
    if (uint32_t(rowOffset) + rows > h) rows = h - uint32_t(rowOffset);

    const jsize cap = env->GetArrayLength(dst);
    if (jsize(rows) * jsize(w) > cap) { throw_runtime(env, "argb array too small"); return -1; }

    std::vector<jint> tmp(size_t(rows) * w);
    const uint16_t* px = g_result.rgba16;
    for (uint32_t y = 0; y < rows; ++y) {
        const uint16_t* src = px + size_t(rowOffset + y) * g_result.row_stride_px * 4;
        jint* line = tmp.data() + size_t(y) * w;
        if (!g_result_is_source && !g_adjust.neutral()) {
            for (uint32_t x = 0; x < w; ++x) {
                double c[3] = {src[x * 4 + 0] / 65535.0, src[x * 4 + 1] / 65535.0,
                               src[x * 4 + 2] / 65535.0};
                spk_adjust::apply_pixel(c, int(x), int(rowOffset + y), int(w), int(h), g_adjust);
                const int r = int(std::clamp(c[0], 0.0, 1.0) * 255.0 + 0.5);
                const int g = int(std::clamp(c[1], 0.0, 1.0) * 255.0 + 0.5);
                const int b = int(std::clamp(c[2], 0.0, 1.0) * 255.0 + 0.5);
                line[x] = jint(0xFF000000u) | (jint(r) << 16) | (jint(g) << 8) | jint(b);
            }
        } else if (g_result_is_source) {
            const uint8_t* lut = srgb_display_lut();
            const double gain = g_source_display_gain;
            for (uint32_t x = 0; x < w; ++x) {
                const uint32_t ir = uint32_t(std::min(65535.0, double(src[x * 4 + 0]) * gain));
                const uint32_t ig = uint32_t(std::min(65535.0, double(src[x * 4 + 1]) * gain));
                const uint32_t ib = uint32_t(std::min(65535.0, double(src[x * 4 + 2]) * gain));
                line[x] = jint(0xFF000000u)
                        | (jint(lut[ir]) << 16)
                        | (jint(lut[ig]) << 8)
                        | jint(lut[ib]);
            }
        } else {
            for (uint32_t x = 0; x < w; ++x) {
                const uint16_t r = src[x * 4 + 0];
                const uint16_t g = src[x * 4 + 1];
                const uint16_t b = src[x * 4 + 2];
                line[x] = jint(0xFF000000u)
                        | (jint((r & 0xFF00u) << 8))
                        | (jint(g & 0xFF00u))
                        | (jint(b >> 8));
            }
        }
    }
    env->SetIntArrayRegion(dst, 0, jsize(tmp.size()), tmp.data());
    return jint(rows * w);
}

JNI(void, nativeRenderEnd)(JNIEnv*, jclass) {
    if (g_result_valid) { spk_result_free(&g_result); g_result_valid = false; }
}

// ---------------------------------------------------------------------------
// Full-resolution export: render the "full" tier in strips and stream it
// straight into a JPEG. No Bitmap, no int[]: a 20 MP frame is ~81 MB in each
// of those, and the copy is what used to push the app over.
// ---------------------------------------------------------------------------

// Export black box: a line per stage, flushed immediately, so a native crash
// still leaves a trace of how far the render got.
static void prog_line(const std::string& path, const char* fmt, ...) {
    if (path.empty()) return;
    FILE* f = std::fopen(path.c_str(), "a");
    if (!f) return;
    va_list ap;
    va_start(ap, fmt);
    std::vfprintf(f, fmt, ap);
    va_end(ap);
    std::fputc('\n', f);
    std::fflush(f);
    std::fclose(f);
}

// The rebate makes the canvas a different size from the tier's source: the
// film's width around the frame is not the frame. `spk_render` is supposed to
// hand back the canvas, and on a phone it has been seen to hand back the
// *source* size with the canvas drawn into its top-left -- 4175x5450 for a
// 4175x4235 canvas, the last 1215 rows an unwritten buffer. That reads as "the
// rebate is flying": the frame and its edge are squeezed into the top of the
// file with a dead band under them.
//
// So ask the layout what it actually drew. Rows are trimmed only when the
// widths already agree, because the row stride is the buffer's, not ours.
static void trim_to_canvas(spk_session* session, spk_result& r, const std::string& progPath) {
    char* reply = nullptr;
    if (spk_overscan_geometry(session, &reply) != SPK_OK || !reply) return;
    const std::string g = reply;
    spk_string_free(reply);
    auto field = [&](const char* key) -> long {
        const std::string needle = std::string("\"") + key + "\":";
        const size_t at = g.find(needle);
        if (at == std::string::npos) return -1;
        return std::atol(g.c_str() + at + needle.size());
    };
    const long cw = field("canvas_w"), ch = field("canvas_h");
    if (cw <= 0 || ch <= 0) return;
    if (long(r.width) == cw && long(r.height) == ch) return;
    prog_line(progPath, "geometry: result %ux%u, canvas %ldx%ld", r.width, r.height, cw, ch);
    // The canvas is drawn into the buffer's **top-left** (that is the shape of
    // this engine bug, and the reason this function exists). Cropping to the
    // canvas is therefore right whenever the buffer is the bigger one -- the
    // old version only did it when the widths already agreed, so a full export
    // whose width differed kept the whole buffer and showed the bottom rebate
    // in the middle of the file: "preview is fine, export is flying".
    if (cw <= long(r.width) && ch <= long(r.height)) {
        r.width = uint32_t(cw);
        r.height = uint32_t(ch);
        prog_line(progPath, "geometry: cropped to the canvas, %ux%u", r.width, r.height);
    }
}

// How much real picture each band of the result carries, 32 bands top to
// bottom. A band of film has structure; a band of an unwritten buffer, or of
// film past the frame, is a flat wash. When an export disagrees with the
// preview, this line says whether the canvas was short or the placement was.
static void log_content_profile(const spk_result& r, const std::string& progPath) {
    if (!r.rgba16 || !r.width || !r.height) return;
    const uint32_t stride = r.row_stride_px ? r.row_stride_px : r.width;
    const int kBands = 32;
    std::string line = "content:";
    char cell[16];
    for (int b = 0; b < kBands; ++b) {
        const uint32_t y0 = uint32_t(uint64_t(b) * r.height / kBands);
        const uint32_t y1 = uint32_t(uint64_t(b + 1) * r.height / kBands);
        double sum = 0, sq = 0;
        long n = 0;
        for (uint32_t y = y0; y < y1; y += 4) {
            const uint16_t* row = r.rgba16 + size_t(y) * stride * 4;
            for (uint32_t x = 0; x < r.width; x += 16) {
                const double v = row[size_t(x) * 4 + 1] / 65535.0;
                sum += v; sq += v * v; ++n;
            }
        }
        const double mean = n ? sum / n : 0.0;
        const double var = n ? std::max(0.0, sq / n - mean * mean) : 0.0;
        std::snprintf(cell, sizeof cell, " %.3f", std::sqrt(var));
        line += cell;
    }
    prog_line(progPath, "%s", line.c_str());
}


// ---------------------------------------------------------------- export ---
//
// Two things every exported file has been missing since the first release:
// the sRGB profile it was actually written in, and any EXIF at all. A JPEG
// with neither is read as sRGB by luck and as "no metadata" by every tool
// that groups by camera or date; a 16-bit TIFF without a profile is worse,
// because nothing down the chain can know what those numbers mean.

// The profile path is the only thing the JNI adds to the shared helpers.
static bool load_srgb_profile(std::vector<uint8_t>& out) {
    if (g_resources_dir.empty()) return false;
    return spk_export::load_srgb_profile_file(g_resources_dir + "/io/sRGB.icc", out);
}

static std::string export_software() {
    const char* build = spk_build_info();
    std::string name = "SpektraLab Android";
    // `spk_build_info` carries version and build number; keep it to one line
    // and well under the 64-byte ASCII budget a Software tag deserves.
    if (build && *build) {
        std::string extra(build);
        const size_t nl = extra.find('\n');
        if (nl != std::string::npos) extra.resize(nl);
        if (!extra.empty() && extra.size() < 40) name += " " + extra;
    }
    return name;
}

static std::string now_exif_datetime() {
    const std::time_t t = std::time(nullptr);
    std::tm tm{};
#if defined(_WIN32)
    localtime_s(&tm, &t);
#else
    localtime_r(&t, &tm);
#endif
    char buf[24];
    std::strftime(buf, sizeof buf, "%Y:%m:%d %H:%M:%S", &tm);
    return buf;
}

static bool write_jpeg_file(const char* path, const spk_result& r, int quality,
                            const spk_export::SourceExif& source = {}) {
    const uint32_t w = r.width, h = r.height;
    const uint32_t stride = r.row_stride_px ? r.row_stride_px : r.width;
    if (!r.rgba16 || !w || !h) return false;
    FILE* f = std::fopen(path, "wb");
    if (!f) return false;
    jpeg_compress_struct cinfo{};
    jpeg_error_mgr jerr{};
    cinfo.err = jpeg_std_error(&jerr);
    jpeg_create_compress(&cinfo);
    jpeg_stdio_dest(&cinfo, f);
    cinfo.image_width = w;
    cinfo.image_height = h;
    cinfo.input_components = 3;
    cinfo.in_color_space = JCS_RGB;
    jpeg_set_defaults(&cinfo);
    jpeg_set_quality(&cinfo, quality, TRUE);
    // **4:4:4 chroma.** libjpeg's default is 4:2:0, which throws away three
    // quarters of the colour resolution -- exactly where film grain, dye clouds
    // and halation edges live. A 16.5 MP print measured 3.3 MB at q95 with the
    // default; the same frame at 4:4:4 is ~3x that and keeps its texture.
    for (int c = 0; c < cinfo.num_components; ++c) {
        cinfo.comp_info[c].h_samp_factor = 1;
        cinfo.comp_info[c].v_samp_factor = 1;
    }
    jpeg_start_compress(&cinfo, TRUE);
    // **What the numbers mean, and who wrote them.** Without these two markers
    // a JPEG is sRGB only because every reader guesses so, and it has no date,
    // no software and no dimensions in its metadata at all.
    {
        const std::vector<uint8_t> exif = spk_export::build_exif_app1(export_software(),
                                                          now_exif_datetime(), w, h, source);
        if (!exif.empty())
            jpeg_write_marker(&cinfo, JPEG_APP0 + 1, exif.data(), unsigned(exif.size()));
        std::vector<uint8_t> icc;
        if (load_srgb_profile(icc)) spk_export::write_icc_marker(&cinfo, icc);
    }
    std::vector<uint8_t> row(size_t(w) * 3);
    while (cinfo.next_scanline < h) {
        // row_stride_px counts *pixels*; each pixel is 4 uint16 (RGBA).
        const uint16_t* src = r.rgba16 + size_t(cinfo.next_scanline) * stride * 4;
        for (uint32_t x = 0; x < w; ++x) {
            for (int c = 0; c < 3; ++c) {
                row[size_t(x) * 3 + c] = uint8_t((uint32_t(src[size_t(x) * 4 + c]) * 255u + 32767u) / 65535u);
            }
        }
        JSAMPROW rp = row.data();
        jpeg_write_scanlines(&cinfo, &rp, 1);
    }
    jpeg_finish_compress(&cinfo);
    jpeg_destroy_compress(&cinfo);
    std::fclose(f);
    return true;
}

// **Source capture metadata, handed over just before an export.**
//
// The writer below appends one APP1 segment with these fields and then the ICC
// profile, in the same pass that encodes the pixels. The alternative -- letting
// Java reopen the finished file and save attributes through the platform's
// ExifInterface -- rewrites a ten-megabyte JPEG from another process boundary,
// and nothing in that path is ours: not the segment order, not the colour
// profile that has to survive it. Metadata belongs with the encoder that knows
// what it wrote.
static spk_export::SourceExif g_export_exif;

static std::string jni_string(JNIEnv* env, jstring value) {
    if (!value) return std::string();
    const char* text = env->GetStringUTFChars(value, nullptr);
    std::string out = text ? text : "";
    if (text) env->ReleaseStringUTFChars(value, text);
    return out;
}

// "1/204" or "0.0049" -- a camera writes the first, ExifInterface returns it,
// and the writer wants seconds.
static double jni_seconds(const std::string& text) {
    if (text.empty()) return 0.0;
    const size_t slash = text.find('/');
    if (slash != std::string::npos) {
        const double numerator = std::atof(text.substr(0, slash).c_str());
        const double denominator = std::atof(text.substr(slash + 1).c_str());
        return denominator > 0.0 ? numerator / denominator : 0.0;
    }
    return std::atof(text.c_str());
}

JNI(void, nativeSetExportExif)(JNIEnv* env, jclass, jstring make, jstring model, jstring datetime,
                               jstring shutter, jstring aperture, jstring iso, jstring focal) {
    g_export_exif = spk_export::SourceExif{};
    g_export_exif.make = jni_string(env, make);
    g_export_exif.model = jni_string(env, model);
    g_export_exif.datetime = jni_string(env, datetime);
    g_export_exif.shutter = jni_seconds(jni_string(env, shutter));
    g_export_exif.aperture = jni_seconds(jni_string(env, aperture));
    g_export_exif.iso = jni_seconds(jni_string(env, iso));
    g_export_exif.focal_mm = jni_seconds(jni_string(env, focal));
    g_export_exif.present = !(g_export_exif.make.empty() && g_export_exif.model.empty() &&
                              g_export_exif.datetime.empty());
}

// **What the source JPEG's own APP1 says, as seven fields.**
//
// Returned rather than stashed: the caller merges it with whatever the platform
// reader managed to produce and logs both, so a field that goes missing can be
// traced to the reader that dropped it instead of to "EXIF is broken".
// Fields: make, model, datetime, shutter(s), aperture, iso, focal(mm).
JNI(jstring, nativeReadJpegExif)(JNIEnv* env, jclass, jbyteArray head) {
    if (!head) return env->NewStringUTF("");
    const jsize n = env->GetArrayLength(head);
    if (n <= 0) return env->NewStringUTF("");
    std::vector<uint8_t> bytes(static_cast<size_t>(n), 0);
    env->GetByteArrayRegion(head, 0, n, reinterpret_cast<jbyte*>(bytes.data()));
    spk_export::SourceExif parsed;
    if (!spk_export::read_jpeg_source_exif(bytes.data(), bytes.size(), parsed))
        return env->NewStringUTF("");
    char numbers[256];
    std::snprintf(numbers, sizeof numbers, "%.6f\x01%.2f\x01%.0f\x01%.2f",
                  parsed.shutter, parsed.aperture, parsed.iso, parsed.focal_mm);
    std::string joined = parsed.make + '\x01' + parsed.model + '\x01' + parsed.datetime
                       + '\x01' + numbers;
    return env->NewStringUTF(joined.c_str());
}

JNI(jintArray, nativeExportJpeg)(JNIEnv* env, jclass, jlong sessionHandle,
                                 jstring path, jint quality, jint stripRows, jstring tier) {
    spk_session* session = reinterpret_cast<spk_session*>(sessionHandle);
    if (!session) { throw_runtime(env, "session is null"); return nullptr; }
    const char* p = env->GetStringUTFChars(path, nullptr);
    std::string outPath = p ? p : "";
    if (p) env->ReleaseStringUTFChars(path, p);
    if (outPath.empty()) { throw_runtime(env, "empty export path"); return nullptr; }

    // NOTE: the session is opened with `striped` already on. Sending striped
    // again in a delta on a 20 MP session is a trap: spk_set_params accepts it
    // and the next full render never returns. Only plain parameters travel
    // through deltas; the strip settings are fixed at open.
    (void)stripRows;
    const std::string progPath = outPath + ".prog";
    std::remove(progPath.c_str());
    prog_line(progPath, "begin");
    const auto t0 = std::chrono::steady_clock::now();
    std::string tier_cstr = "full";
    if (tier) {
        const char* tt = env->GetStringUTFChars(tier, nullptr);
        if (tt) { tier_cstr = tt; env->ReleaseStringUTFChars(tier, tt); }
    }
    spk_result result{};
    spk_status st = spk_render(session, tier_cstr.c_str(), &result);
    if (st != SPK_OK) {
        prog_line(progPath, "render-failed status=%d err=%s", int(st),
                  spk_last_error(nullptr) ? spk_last_error(nullptr) : "unknown");
        spk_result_free(&result);
        char msg[256];
        std::snprintf(msg, sizeof msg, "full render failed (%d): %s", int(st),
                      spk_last_error(nullptr) ? spk_last_error(nullptr) : "unknown");
        throw_runtime(env, msg);
        return nullptr;
    }
    trim_to_canvas(session, result, progPath);
    grade_result(result);
    log_content_profile(result, progPath);
    prog_line(progPath, "rendered %ux%u render_ms=%.0f", result.width, result.height,
              std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
    const double render_ms =
        std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    const int w = int(result.width), h = int(result.height);
    const bool ok = write_jpeg_file(outPath.c_str(), result, int(quality), g_export_exif);
    const double total_ms =
        std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    spk_result_free(&result);
    if (!ok) {
        prog_line(progPath, "jpeg-failed");
        throw_runtime(env, "JPEG encode failed");
        return nullptr;
    }
    prog_line(progPath, "done total_ms=%.0f", total_ms);

    jintArray arr = env->NewIntArray(4);
    if (!arr) return nullptr;
    jint v[4] = { w, h, jint(render_ms), jint(total_ms) };
    env->SetIntArrayRegion(arr, 0, 4, v);
    LOGI("export %dx%d render=%.1fms total=%.1fms", w, h, render_ms, total_ms);
    return arr;
}

// The same render, as 16-bit sRGB TIFF: for grading the print somewhere else
// without 8-bit quantisation, and the only export path that carries a real
// profile by construction rather than by convention.
JNI(jintArray, nativeExportTiff)(JNIEnv* env, jclass, jlong sessionHandle, jstring path, jstring tier) {
    spk_session* session = reinterpret_cast<spk_session*>(sessionHandle);
    if (!session) { throw_runtime(env, "session is null"); return nullptr; }
    const char* p = env->GetStringUTFChars(path, nullptr);
    std::string outPath = p ? p : "";
    if (p) env->ReleaseStringUTFChars(path, p);
    if (outPath.empty()) { throw_runtime(env, "empty export path"); return nullptr; }

    const std::string progPath = outPath + ".prog";
    std::remove(progPath.c_str());
    prog_line(progPath, "begin");
    const auto t0 = std::chrono::steady_clock::now();
    std::string tier_cstr = "full";
    if (tier) {
        const char* tt = env->GetStringUTFChars(tier, nullptr);
        if (tt) { tier_cstr = tt; env->ReleaseStringUTFChars(tier, tt); }
    }
    spk_result result{};
    const spk_status st = spk_render(session, tier_cstr.c_str(), &result);
    if (st != SPK_OK) {
        prog_line(progPath, "render-failed status=%d err=%s", int(st),
                  spk_last_error(nullptr) ? spk_last_error(nullptr) : "unknown");
        spk_result_free(&result);
        char msg[256];
        std::snprintf(msg, sizeof msg, "full render failed (%d): %s", int(st),
                      spk_last_error(nullptr) ? spk_last_error(nullptr) : "unknown");
        throw_runtime(env, msg);
        return nullptr;
    }
    trim_to_canvas(session, result, progPath);
    grade_result(result);
    log_content_profile(result, progPath);
    const double render_ms =
        std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    const int w = int(result.width), h = int(result.height);

    std::string error;
    const std::string iccPath = g_resources_dir + "/io/sRGB.icc";
    const bool ok = spk::io::write_srgb_tiff(outPath, result.rgba16, result.width, result.height,
                                             result.row_stride_px ? result.row_stride_px : result.width,
                                             iccPath, error);
    const double total_ms =
        std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    spk_result_free(&result);
    if (!ok) {
        prog_line(progPath, "tiff-failed %s", error.c_str());
        throw_runtime(env, error.c_str());
        return nullptr;
    }
    prog_line(progPath, "done total_ms=%.0f", total_ms);

    jintArray arr = env->NewIntArray(4);
    if (!arr) return nullptr;
    jint v[4] = { w, h, jint(render_ms), jint(total_ms) };
    env->SetIntArrayRegion(arr, 0, 4, v);
    LOGI("export tiff %dx%d render=%.1fms total=%.1fms", w, h, render_ms, total_ms);
    return arr;
}

// A DNG is the same render with the display transfer function switched off:
// the file holds light, and Lightroom develops it from there. Everything else
// -- banding, the canvas trim, the deflate -- is shared with the TIFF path.
JNI(jintArray, nativeExportDng)(JNIEnv* env, jclass, jlong sessionHandle, jstring path, jstring tier) {
    spk_session* session = reinterpret_cast<spk_session*>(sessionHandle);
    if (!session) { throw_runtime(env, "session is null"); return nullptr; }
    const char* p = env->GetStringUTFChars(path, nullptr);
    std::string outPath = p ? p : "";
    if (p) env->ReleaseStringUTFChars(path, p);
    if (outPath.empty()) { throw_runtime(env, "empty export path"); return nullptr; }

    const std::string progPath = outPath + ".prog";
    std::remove(progPath.c_str());
    prog_line(progPath, "begin");
    const auto t0 = std::chrono::steady_clock::now();
    std::string tier_cstr = "full";
    if (tier) {
        const char* tt = env->GetStringUTFChars(tier, nullptr);
        if (tt) { tier_cstr = tt; env->ReleaseStringUTFChars(tier, tt); }
    }
    spk_result result{};
    spk_set_output_linear(session, 1);
    const spk_status st = spk_render(session, tier_cstr.c_str(), &result);
    spk_set_output_linear(session, 0);   // the previews that follow are display-encoded
    if (st != SPK_OK) {
        prog_line(progPath, "render-failed status=%d err=%s", int(st),
                  spk_last_error(nullptr) ? spk_last_error(nullptr) : "unknown");
        spk_result_free(&result);
        char msg[256];
        std::snprintf(msg, sizeof msg, "full render failed (%d): %s", int(st),
                      spk_last_error(nullptr) ? spk_last_error(nullptr) : "unknown");
        throw_runtime(env, msg);
        return nullptr;
    }
    trim_to_canvas(session, result, progPath);
    grade_result(result);
    log_content_profile(result, progPath);
    const double render_ms =
        std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    const int w = int(result.width), h = int(result.height);

    std::string error;
    const bool ok = spk::io::write_dng(outPath, result.rgba16, result.width, result.height,
                                       result.row_stride_px ? result.row_stride_px : result.width,
                                       error);
    const double total_ms =
        std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    spk_result_free(&result);
    if (!ok) {
        prog_line(progPath, "dng-failed %s", error.c_str());
        throw_runtime(env, error.c_str());
        return nullptr;
    }
    prog_line(progPath, "done total_ms=%.0f", total_ms);

    jintArray arr = env->NewIntArray(4);
    if (!arr) return nullptr;
    jint v[4] = { w, h, jint(render_ms), jint(total_ms) };
    env->SetIntArrayRegion(arr, 0, 4, v);
    LOGI("export dng %dx%d render=%.1fms total=%.1fms", w, h, render_ms, total_ms);
    return arr;
}

// ---------------------------------------------------------------------------
// "Process" (配光): solve the print exposure and the enlarger filter pack for
// the frame. Without it the print runs on defaults and a bright subject blows
// out -- which reads as a rendering bug but is an unsolved print.
// ---------------------------------------------------------------------------
JNI(jstring, nativeSolve)(JNIEnv* env, jclass, jlong sessionHandle, jstring target) {
    spk_session* session = reinterpret_cast<spk_session*>(sessionHandle);
    if (!session) { throw_runtime(env, "session is null"); return nullptr; }
    const char* t = target ? env->GetStringUTFChars(target, nullptr) : nullptr;
    char* reply = nullptr;
    spk_status st = spk_solve(session, t ? t : "both", &reply);
    if (t) env->ReleaseStringUTFChars(target, t);
    if (st != SPK_OK) { if (reply) spk_string_free(reply); throw_status(env, st); return nullptr; }
    jstring out = reply ? env->NewStringUTF(reply) : nullptr;
    if (reply) spk_string_free(reply);
    return out;
}
