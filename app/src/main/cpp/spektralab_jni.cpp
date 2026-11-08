/*
 * SpektraLab for Android — JNI bridge.
 * GPLv3. Bridges app.spektralab.engine.SpektraLabEngine (Kotlin) to spk_engine.h C ABI.
 */
#include <jni.h>
#include <unwind.h>
#include <dlfcn.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstdio>
#include <cstring>
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
#include <mutex>
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
// **The EV the canvas applied, handed to a session that has to reproduce that
// frame.** `auto_exposure` and `exposure_compensation_ev` are not
// interchangeable -- the first multiplies the frame in `film_prefix`, before
// `scene_latitude` rolls the highlights off; the second does it after. Same
// stops, two different pictures (21410 vs 10061 mean16 at 20 MP on the host).
JNI(void, nativePinAutoExposureEv)(JNIEnv*, jclass, jlong sessionHandle, jdouble ev) {
    spk_session* s = reinterpret_cast<spk_session*>(sessionHandle);
    if (!s) return;
    spk_pin_auto_exposure_ev(s, double(ev));
}

JNI(void, nativeClearPinAutoExposureEv)(JNIEnv*, jclass, jlong sessionHandle) {
    spk_session* s = reinterpret_cast<spk_session*>(sessionHandle);
    if (!s) return;
    spk_clear_pin_auto_exposure_ev(s);
}

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

// ---------------------------------------------------------------------------
// **What the process was doing when it died.** The engine's failures are Java
// exceptions with codes; a crash inside a dispatch is not, and this ROM does
// not hand out `ApplicationExitInfo`, so a SIGSEGV leaves nothing at all. A
// backtrace captured in the signal handler is the difference between "it
// crashed again" and the function that did it.
//
// Everything here is async-signal-safe: no malloc, no printf, one pre-opened
// fd, and `_Unwind_Backtrace` (which libgcc documents as usable from a
// handler when the stack is intact).
// ---------------------------------------------------------------------------
namespace {

constexpr int kMaxCrashFrames = 48;
struct CrashFrame { uintptr_t pc; uintptr_t base; const char* lib; };
CrashFrame g_crash_frames[kMaxCrashFrames];
int g_crash_frame_count = 0;
int g_crash_fd = -1;

_Unwind_Reason_Code crash_unwind(_Unwind_Context* ctx, void*) {
    if (g_crash_frame_count >= kMaxCrashFrames) return _URC_END_OF_STACK;
    const uintptr_t pc = uintptr_t(_Unwind_GetIP(ctx));
    if (!pc) return _URC_END_OF_STACK;
    Dl_info info{};
    CrashFrame& f = g_crash_frames[g_crash_frame_count];
    f.pc = pc;
    f.base = 0;
    f.lib = "?";
    if (dladdr(reinterpret_cast<void*>(pc), &info) && info.dli_fname) {
        f.lib = info.dli_fname;
        f.base = uintptr_t(info.dli_fbase);
    }
    ++g_crash_frame_count;
    return _URC_NO_REASON;
}

void crash_write(const char* text, int len) {
    if (g_crash_fd >= 0 && len > 0) {
        ssize_t ignored = write(g_crash_fd, text, size_t(len));
        (void)ignored;
    }
}

void crash_handler(int sig, siginfo_t* info, void*) {
    char line[512];
    int n = std::snprintf(line, sizeof line,
                          "\n=== native crash: signal %d (%s) addr=%p tid=%d\n",
                          sig,
                          sig == SIGSEGV ? "SIGSEGV" : sig == SIGABRT ? "SIGABRT"
                          : sig == SIGBUS ? "SIGBUS" : sig == SIGFPE ? "SIGFPE"
                          : sig == SIGILL ? "SIGILL" : "?", 
                          info ? info->si_addr : nullptr,
                          int(gettid()));
    crash_write(line, n);
    g_crash_frame_count = 0;
    _Unwind_Backtrace(crash_unwind, nullptr);
    for (int i = 0; i < g_crash_frame_count; ++i) {
        const CrashFrame& f = g_crash_frames[i];
        const char* lib = f.lib ? f.lib : "?";
        // Only the file name: the full path is noise in a log line.
        const char* slash = std::strrchr(lib, '/');
        if (slash) lib = slash + 1;
        n = std::snprintf(line, sizeof line, "  #%02d pc=%016llx %s+0x%llx\n", i,
                          (unsigned long long)f.pc, lib,
                          (unsigned long long)(f.base ? f.pc - f.base : 0));
        crash_write(line, n);
    }
    crash_write("=== end\n", 8);
    if (g_crash_fd >= 0) fsync(g_crash_fd);
    struct sigaction dfl{};
    dfl.sa_handler = SIG_DFL;
    sigemptyset(&dfl.sa_mask);
    sigaction(sig, &dfl, nullptr);
    raise(sig);
}

}  // namespace

JNI(void, nativeInstallCrashHandler)(JNIEnv* env, jclass, jstring path) {
    if (!path || g_crash_fd >= 0) return;
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return;
    g_crash_fd = ::open(p, O_WRONLY | O_CREAT | O_APPEND, 0600);
    env->ReleaseStringUTFChars(path, p);
    if (g_crash_fd < 0) return;
    // **The handler needs a stack of its own.** A stack overflow is one of the
    // ways this process dies, and a handler that runs on the stack it just
    // exhausted cannot unwind anything -- it faults again and the frames are
    // lost. 256 KB is well past what 48 frames need.
    static char crash_stack[256 * 1024];
    stack_t ss{};
    ss.ss_sp = crash_stack;
    ss.ss_size = sizeof crash_stack;
    ss.ss_flags = 0;
    sigaltstack(&ss, nullptr);
    struct sigaction sa{};
    sa.sa_sigaction = crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    const int signals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL};
    for (int sig : signals) sigaction(sig, &sa, nullptr);
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

// **A direct buffer ART's native-allocation ledger does not count.**
//
// `ByteBuffer.allocateDirect` registers its memory with the runtime, and that
// ledger is capped by the heap's growth limit -- 512 MB on the phone that
// reported this. A 50 MP frame needs 12 bytes a pixel for the engine's float32
// door, which is 600 MB: the allocation is refused before the device's real
// free memory is ever consulted, and the message reads like the phone is out of
// RAM while it has 4 GB spare. malloc has no such ceiling. `spk_open` copies
// what it is given, so all this really needs is memory that exists.
JNI(jobject, nativeAllocDirect)(JNIEnv* env, jclass, jlong bytes) {
    if (bytes <= 0 || bytes > (jlong(1) << 40)) {
        throw_runtime(env, "direct buffer size is invalid"); return nullptr;
    }
    void* p = std::malloc(size_t(bytes));
    if (!p) {
        throw_runtime(env, "out of native memory for a %lld MB buffer",
                      (long long)(bytes >> 20));
        return nullptr;
    }
    jobject buf = env->NewDirectByteBuffer(p, bytes);
    if (!buf) { std::free(p); throw_runtime(env, "cannot wrap the buffer"); return nullptr; }
    return buf;
}

// Free one from `nativeAllocDirect`. `NewDirectByteBuffer` installs no cleaner,
// so the Java reference going away frees nothing at all.
JNI(void, nativeFreeDirect)(JNIEnv* env, jclass, jobject buffer) {
    if (!buffer) return;
    void* p = env->GetDirectBufferAddress(buffer);
    if (p) std::free(p);
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
/**
 * The stage label the render is inside, for the watchdog.
 *
 * Reads the seqlock snapshot, never the pipeline's `std::string`, so it is safe
 * to call while a render is running -- which is the only time it is useful.
 * Returns null when no consistent read was possible in three tries: that means
 * the render is crossing nodes quickly, not that it is stuck.
 */
JNI(jstring, nativeStageSnapshot)(JNIEnv* env, jclass, jlong sessionHandle) {
    spk_session* session = reinterpret_cast<spk_session*>(sessionHandle);
    if (!session) return nullptr;
    char buf[96];
    if (!spk_stage_snapshot(session, buf, sizeof buf)) return nullptr;
    return env->NewStringUTF(buf);
}

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
// Encoded-domain lift for the canvas: 0.85 when the canvas auto-levels, 1.0
// (identity) when it shows the decode as measured. See `lift8`.
static double g_source_display_lift = 1.0;

// **The vendor's display profile, when the source ships one.**
//
// `profile_tone_curve` is the (x, y) list from DNG tag 50940 -- the phone's own
// statement of how the linear scene should be read -- and
// `g_source_baseline_ev` is tag 50730, applied first. Empty means the file has
// no profile (every camera DNG, every NEF/RAF tested), and the canvas falls
// back to the plain sRGB rendering below.
static std::vector<float> g_source_curve;
static double g_source_baseline_ev = 0.0;
static std::mutex g_source_profile_mutex;

JNI(void, nativeSetSourceDisplayEv)(JNIEnv*, jclass, jdouble ev, jboolean auto_level) {
    // **The canvas defaults to "as measured".**
    //
    // `auto_level` is the opt-in that used to be the only behaviour: put the
    // frame's median on 18 % of linear (sRGB mid-grey), capped where p99.5
    // would clip, plus a `pow(v, 0.85)` lift in the encoded domain. It reads
    // well next to a phone's own preview -- which is where the 18 % came from,
    // after an imported raw came back at 2/255 and looked like a black
    // rectangle -- but it is a rendering, not the file: two frames get
    // different gains, and the picture's own exposure disappears into it.
    //
    // Measured against Lightroom on one OnePlus 13 DNG: the auto canvas sat
    // 2.54 EV above Lightroom's (linear median 0.0421 against 0.00725). With
    // the gain at 1.0 and no lift the same frame lands at 0.0108 against
    // 0.00725 -- 0.4 EV, which is Lightroom's own tone curve. The default is
    // therefore the measurement; the switch in 光学 -> 亮度 turns the old
    // behaviour back on for anyone who prefers it.
    if (!auto_level) {
        g_source_display_gain = 1.0;
        g_source_display_lift = 1.0;
        return;
    }
    g_source_display_lift = 0.85;
    const double clamped = ev < -4.0 ? -4.0 : (ev > 6.0 ? 6.0 : ev);
    double gain = std::pow(2.0, clamped);
    // **Cap the display gain so the frame's own highlights survive it.**
    //
    // The metered EV is computed for a *print*: `balanced` lifts a scene that is
    // five stops under the meter into the paper's straight line, and the paper's
    // shoulder absorbs what is left. Applied as a display exposure it has no
    // shoulder -- it just clips. Measured on a lamp against a dark room: linear
    // p50 0.23%, p99 65%, balanced +5.41 EV, and 19.07% of the frame past 1.0.
    //
    // p99.5 is sampled (40k pixels, a 200x200 grid) and the gain is stopped
    // where that percentile reaches 0.995. A night frame -- small p99.5 -- still
    // lifts all the way; a scene that is already bright is limited to what its
    // own highlights allow. The cap never darkens: a frame whose p99.5 is
    // already 1.0 keeps gain 1.0.
    if (g_result_valid && g_result_is_source && g_result.rgba16 &&
        g_result.width && g_result.height) {
        const uint32_t w = g_result.width, h = g_result.height;
        const uint32_t stride = g_result.row_stride_px ? g_result.row_stride_px : w;
        const uint32_t ystep = std::max<uint32_t>(1, h / 200u);
        const uint32_t xstep = std::max<uint32_t>(1, w / 200u);
        std::vector<uint16_t> luma;
        luma.reserve(size_t(w / xstep + 1) * size_t(h / ystep + 1));
        for (uint32_t y = 0; y < h; y += ystep) {
            const uint16_t* row = g_result.rgba16 + size_t(y) * stride * 4;
            for (uint32_t x = 0; x < w; x += xstep) {
                const uint16_t* q = row + size_t(x) * 4;
                luma.push_back(uint16_t((uint32_t(q[0]) + uint32_t(q[1]) + uint32_t(q[2])) / 3u));
            }
        }
        if (!luma.empty()) {
            std::sort(luma.begin(), luma.end());
            const double p50 = double(luma[luma.size() / 2]) / 65535.0;
            const size_t k = std::min(luma.size() - 1, (luma.size() * 995) / 1000);
            const double p995 = double(luma[k]) / 65535.0;
            // **A display exposure, not the film's.** The reference's canvas
            // reads a `.display` decode (CIRAWFilter's own auto), while its
            // engine reads `.linear`; ours was feeding the *film* EV into the
            // canvas, and a night frame metered +0.24 stops came back at 2/255.
            // Put the median on 18 % of linear -- sRGB mid-grey -- and let the
            // 99.5th percentile stop the gain before it clips, which is the
            // same two rules the import sheet quotes.
            if (p50 > 1e-5) {
                double want = 0.18 / p50;
                if (p995 > 1e-6) {
                    const double cap = 0.995 / p995;
                    if (want > cap) want = cap;
                }
                want = std::clamp(want, 0.25, 16.0);
                gain = want;
            }
        }
    }
    g_source_display_gain = gain;
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

// `reprint` runs the print side over the session's cached negative instead of
// developing the film again. The two are byte-identical when the negative is
// current (`live_probe` H: grain and glare off, reprint == render on every
// parameter step); what it saves is the whole film side -- 200 ms of an
// 800-pixel drag frame against 32 ms. The engine itself drops the cache when a
// shoot-layer field changes, so this flag can never reprint a stale negative.
JNI(jintArray, nativeRenderBegin)(JNIEnv* env, jclass, jlong sessionHandle, jstring tier,
                                  jboolean reprint) {
    spk_session* session = reinterpret_cast<spk_session*>(sessionHandle);
    if (!session) { throw_runtime(env, "session is null"); return nullptr; }
    const char* t = env->GetStringUTFChars(tier, nullptr);
    if (!t) { throw_runtime(env, "tier is null"); return nullptr; }

    if (g_result_valid) { spk_result_free(&g_result); g_result_valid = false; }
    std::memset(&g_result, 0, sizeof g_result);

    spk_status st = reprint ? spk_reprint(session, t, &g_result)
                            : spk_render(session, t, &g_result);
    env->ReleaseStringUTFChars(tier, t);
    if (st != SPK_OK) {
        char msg[512];
        std::snprintf(msg, sizeof msg, "%s failed (%d): %s",
                      reprint ? "spk_reprint" : "spk_render", (int)st, spk_last_error(nullptr));
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


// Encoded-domain lift: 0 stays 0, 255 stays 255, the shadows come up.
// Identity unless the canvas is auto-levelling -- see `g_source_display_lift`.
static inline uint8_t lift8(uint8_t v) {
    if (g_source_display_lift >= 0.9999) return v;
    static uint8_t table[256];
    static bool ready = false;
    if (!ready) {
        for (int i = 0; i < 256; ++i)
            table[i] = uint8_t(std::lround(255.0 * std::pow(i / 255.0, 0.85)));
        ready = true;
    }
    return table[v];
}

// Piecewise-linear read of a DNG tone curve. Values outside the ends clamp to
// the end points, which is what a DNG reader does with scene values past 1.0.
static double curve_lookup(const std::vector<float>& c, double x) {
    const int n = int(c.size() / 2);
    if (n < 2) return x;
    if (x <= double(c[0])) return double(c[1]);
    const int last = 2 * (n - 1);
    if (x >= double(c[last])) return double(c[last + 1]);
    int lo = 0, hi = n - 1;
    while (hi - lo > 1) {
        const int mid = (lo + hi) / 2;
        if (double(c[2 * mid]) <= x) lo = mid; else hi = mid;
    }
    const double x0 = c[2 * lo], y0 = c[2 * lo + 1];
    const double x1 = c[2 * hi], y1 = c[2 * hi + 1];
    return (x1 > x0) ? y0 + (y1 - y0) * (x - x0) / (x1 - x0) : y0;
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

    // The profile is copied once per call: it can be replaced from the UI
    // thread between frames, and reading it per pixel would need the lock.
    std::vector<float> curve;
    double curve_base = 1.0;
    {
        std::lock_guard<std::mutex> lk(g_source_profile_mutex);
        curve = g_source_curve;
        curve_base = std::pow(2.0, std::clamp(g_source_baseline_ev, -6.0, 6.0));
    }

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
        } else if (g_result_is_source && !curve.empty()) {
            // **The vendor's own rendering.**
            //
            // `ProfileToneCurve` is defined on the scene-linear PCS values,
            // which is exactly what `spk_export_source` hands back (LibRaw's
            // ProPhoto linear). Apply `BaselineExposure`, then the curve per
            // channel, then encode -- no auto-level, no shadow lift, no extra
            // saturation: the phone already said what it wants. Measured
            // against Lightroom on the same OnePlus 13 DNG: p95 188.4 against
            // 187.7, median 23.6 against 20.5, linear median 0.00889 against
            // 0.00725.
            const uint8_t* lut = srgb_display_lut();
            static const double p2s[9] = {
                 2.0340758, -0.7273341, -0.3067418,
                -0.2288131,  1.2317301, -0.0029169,
                -0.0085698, -0.1532866,  1.1618564,
            };
            for (uint32_t x = 0; x < w; ++x) {
                const double pr = curve_lookup(curve, double(src[x * 4 + 0]) / 65535.0 * curve_base);
                const double pg = curve_lookup(curve, double(src[x * 4 + 1]) / 65535.0 * curve_base);
                const double pb = curve_lookup(curve, double(src[x * 4 + 2]) / 65535.0 * curve_base);
                const uint32_t ir = uint32_t(std::clamp(p2s[0] * pr + p2s[1] * pg + p2s[2] * pb, 0.0, 1.0) * 65535.0 + 0.5);
                const uint32_t ig = uint32_t(std::clamp(p2s[3] * pr + p2s[4] * pg + p2s[5] * pb, 0.0, 1.0) * 65535.0 + 0.5);
                const uint32_t ib = uint32_t(std::clamp(p2s[6] * pr + p2s[7] * pg + p2s[8] * pb, 0.0, 1.0) * 65535.0 + 0.5);
                line[x] = jint(0xFF000000u)
                        | (jint(lut[ir]) << 16)
                        | (jint(lut[ig]) << 8)
                        | jint(lut[ib]);
            }
        } else if (g_result_is_source) {
            const uint8_t* lut = srgb_display_lut();
            // `curve_base` carries `BaselineExposure` even when there is no
            // tone curve -- most cameras and most older phones ship only the
            // exposure, and the DNG spec applies it either way.
            const double gain = g_source_display_gain * curve_base;
            // **The reference has two decodes, not one.** Its engine reads a
            // `.linear` decode with every tone rendering switched off
            // (`boostAmount = 0`, `localToneMapAmount = 0`, gamut mapping off);
            // its canvas reads a separate `.display` decode with CIRAWFilter's
            // defaults on -- boost, local tone map, gamut mapping. Ours had only
            // the linear one plus an sRGB OETF, which is exactly why an
            // imported frame came back flatter than the same file in Photos.
            //
            // A gentle S-curve plus a little saturation is not Apple's boost,
            // but it is the same *kind* of thing: a display rendering, not a
            // measurement. The engine still gets the untouched numbers.
            // **The working space is ProPhoto, not sRGB.** `raw_decoder` asks
            // LibRaw for `output_color = 4`, and `spk_export_source` hands the
            // engine's working numbers straight back. Encoding those as sRGB
            // leaves the luminance alone and collapses the colour -- ProPhoto's
            // primaries are far wider, so the same numbers mean a much duller
            // picture once a display reads them. That is the whole of "导入进来
            // 就是很寡淡", and it is why the highlight lift did not help.
            //
            // ProPhoto (D50) -> linear sRGB (D65), Bradford adapted.
            //
            // **Every row must sum to 1.** That is the transform's answer for a
            // neutral: (1,1,1) in has to come out (1,1,1), or every grey in the
            // frame picks up a cast. The matrix that shipped through 1.6.6
            // summed to 1.039 / 0.984 / **1.212** -- blue 21 % high, so a white
            // wall read as cool grey and the whole picture looked washed out
            // next to the same file in Photos.
            //
            // Measured on one OnePlus 13 DNG against LibRaw's own sRGB
            // development of the same file: mean 49.2/49.2/53.0 (B/G 1.077,
            // hue centroid 235 deg) before; 47.8/49.4/47.6 (B/G 0.964, hue 100
            // deg) after, against LibRaw's 47.5/49.0/47.3 and B/G 0.966. The
            // per-pixel hue difference is then mean 3.1 deg, p50 2.3, p90 6.8,
            // 0.2 % of samples past 20 deg. The old matrix put that at 235 deg
            // against 92 -- a blue-violet cast over the whole canvas.
            static const double p2s[9] = {
                 2.0340758, -0.7273341, -0.3067418,
                -0.2288131,  1.2317301, -0.0029169,
                -0.0085698, -0.1532866,  1.1618564,
            };
            for (uint32_t x = 0; x < w; ++x) {
                const double pr = double(src[x * 4 + 0]) / 65535.0;
                const double pg = double(src[x * 4 + 1]) / 65535.0;
                const double pb = double(src[x * 4 + 2]) / 65535.0;
                double c[3] = {
                    std::clamp((p2s[0] * pr + p2s[1] * pg + p2s[2] * pb) * gain, 0.0, 1.0),
                    std::clamp((p2s[3] * pr + p2s[4] * pg + p2s[5] * pb) * gain, 0.0, 1.0),
                    std::clamp((p2s[6] * pr + p2s[7] * pg + p2s[8] * pb) * gain, 0.0, 1.0),
                };
                for (int i = 0; i < 3; ++i) {
                    const double t = c[i];
                    const double smooth = t * t * (3.0 - 2.0 * t);
                    c[i] = 0.72 * t + 0.28 * smooth;
                }
                const double l = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
                for (int i = 0; i < 3; ++i) {
                    c[i] = std::clamp(l + (c[i] - l) * 1.12, 0.0, 1.0);
                }
                const uint32_t ir = uint32_t(c[0] * 65535.0 + 0.5);
                const uint32_t ig = uint32_t(c[1] * 65535.0 + 0.5);
                const uint32_t ib = uint32_t(c[2] * 65535.0 + 0.5);
                // **A display lift on top of the OETF.** A night frame's median
                // is 2 % linear; the sRGB curve leaves that at 30/255, which
                // reads as a black rectangle next to what Photos shows. Gamma
                // 0.85 in the encoded domain lifts the shadows and leaves 255
                // where it is -- a display decision, exactly like the S-curve
                // above, and the engine never sees it.
                line[x] = jint(0xFF000000u)
                        | (jint(lift8(lut[ir])) << 16)
                        | (jint(lift8(lut[ig])) << 8)
                        | jint(lift8(lut[ib]));
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

// [baselineEV, x0, y0, x1, y1, ...]; null or shorter than two points clears it.
JNI(void, nativeSetSourceToneCurve)(JNIEnv* env, jclass, jfloatArray curve) {
    std::lock_guard<std::mutex> lk(g_source_profile_mutex);
    g_source_curve.clear();
    g_source_baseline_ev = 0.0;
    if (!curve) return;
    const jsize n = env->GetArrayLength(curve);
    if (n < 1) return;
    // [baseline] alone is legal: no curve, but the exposure still applies.
    std::vector<float> buf(static_cast<size_t>(n));
    env->GetFloatArrayRegion(curve, 0, jsize(n), buf.data());
    g_source_baseline_ev = double(buf[0]);
    if (n >= 5) g_source_curve.assign(buf.begin() + 1, buf.end());
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
// **Where a "ruler" would show, and on which side.**
//
// The report is "one export has a column of little bars along the left edge,
// another does not" -- and it is true for some frames and not others, which
// rules out the encoder and points at something the frame's own size decides.
// Guessing has not found it, so this records the evidence instead: each side's
// thin strip is reduced to a per-line mean, and a line counts as *hot* when it
// stands out from both neighbours by more than 24/255. A real picture's edge is
// smooth; a seam, a resample fold or a half-written band is not.
//
// Four counts and where the first hot line sits, in one log line. Read-only.
static void log_edge_profile(const spk_result& r, const std::string& progPath) {
    if (!r.rgba16 || !r.width || !r.height) return;
    const uint32_t stride = r.row_stride_px ? r.row_stride_px : r.width;
    const uint32_t w = r.width, h = r.height;
    const uint32_t bw = std::max<uint32_t>(3, w / 64);
    const uint32_t bh = std::max<uint32_t>(3, h / 64);
    auto strip_mean = [&](uint32_t line, bool vertical, int which) -> int {
        const uint32_t span = vertical ? bw : bh;
        long sum = 0;
        for (uint32_t j = 0; j < span; ++j) {
            uint32_t x = 0, y = 0;
            if (vertical) { y = line; x = (which == 0) ? j : (w - 1u - j); }
            else          { x = line; y = (which == 0) ? j : (h - 1u - j); }
            const uint16_t* p = r.rgba16 + (size_t(y) * stride + x) * 4;
            sum += (uint32_t(p[0]) + p[1] + p[2]) / 3u;
        }
        return int((sum / long(span)) / 257L);
    };
    // `period` is the whole point. Picture content makes an edge rough in
    // whatever rhythm it likes; a ruler is evenly spaced, so the gaps between
    // hot lines agree with each other. 0 means "not a ruler".
    auto scan = [&](bool vertical, int which, int& hot, int& first, int& period) {
        const uint32_t lines = vertical ? h : w;
        std::vector<int> m(lines);
        for (uint32_t i = 0; i < lines; ++i) m[i] = strip_mean(i, vertical, which);
        hot = 0; first = -1; period = 0;
        std::vector<int> hots;
        for (uint32_t i = 1; i + 1 < lines; ++i) {
            const int d = std::abs(m[i - 1] - 2 * m[i] + m[i + 1]);
            if (d > 24) { if (first < 0) first = int(i); ++hot; hots.push_back(int(i)); }
        }
        if (hots.size() < 4) return;
        std::vector<int> gaps;
        for (size_t i = 1; i < hots.size(); ++i) gaps.push_back(hots[i] - hots[i - 1]);
        std::sort(gaps.begin(), gaps.end());
        const int med = gaps[gaps.size() / 2];
        if (med <= 0) return;
        int near = 0;
        for (int g : gaps) if (std::abs(g - med) <= std::max(1, med / 5)) ++near;
        if (near * 5 >= int(gaps.size()) * 4) period = med;
    };
    int lh, lf, lp, rh, rf, rp, th, tf, tp, bhot, bf, bp;
    scan(true, 0, lh, lf, lp);
    scan(true, 1, rh, rf, rp);
    scan(false, 0, th, tf, tp);
    scan(false, 1, bhot, bf, bp);
    char line[224];
    std::snprintf(line, sizeof line,
                  "edge %ux%u stride=%u  L=%d@%d p%d R=%d@%d p%d T=%d@%d p%d B=%d@%d p%d  (strip %ux%u)",
                  w, h, stride, lh, lf, lp, rh, rf, rp, th, tf, tp, bhot, bf, bp, bw, bh);
    prog_line(progPath, "%s", line);
    std::fprintf(stderr, "[edge] %s\n", line);
}

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
    log_edge_profile(result, progPath);
    log_content_profile(result, progPath);
    prog_line(progPath, "rendered %ux%u render_ms=%.0f", result.width, result.height,
              std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count());
    const double render_ms =
        std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    const int w = int(result.width), h = int(result.height);
    // **The buffer the encoder is about to see.** The app logs
    // `print_exposure: 0.41` at both ends of this call, and the file comes back
    // at 1.0 on the owner's device while the same sequence renders 21410 vs
    // 11996 on the host. One number settles which half is wrong: 21410 here
    // and 11700 in the file means the encoder path; 11700 here means the
    // engine rendered with 1.0.
    double mean16 = 0.0;
    {
        const uint16_t* px = reinterpret_cast<const uint16_t*>(result.rgba16);
        const size_t npx = size_t(result.width) * result.height;
        if (px && npx) {
            // Row by row: `row_stride_px` is the padded pitch the engine hands
            // back, and on a backend whose alignment is above 1 a flat walk
            // reads the padding as pixels and reports a mean nobody rendered.
            const uint32_t st = result.row_stride_px ? result.row_stride_px : result.width;
            double sum = 0.0;
            for (uint32_t y = 0; y < result.height; ++y) {
                const uint16_t* row = px + size_t(y) * st * 4;
                for (uint32_t x = 0; x < result.width; ++x)
                    sum += row[x * 4] + row[x * 4 + 1] + row[x * 4 + 2];
            }
            mean16 = sum / (double(npx) * 3.0);
        }
    }
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

    // **What the pipeline itself read and multiplied by.** `getParams` is the
    // session's copy; these two come from the object that ran `print_output`.
    // pe 1.0 means the delta never reached `params_.enlarger`; pe 0.41 with
    // gain 1.0 means the paper factor cancelled it; both right means the node
    // ran and something downstream lifts the picture back.
    const double dbg_pe = spk_debug_print_exposure(session);
    const double dbg_gain = spk_debug_print_gain(session);
    prog_line(progPath, "pipeline pe=%.6f gain=%.6f", dbg_pe, dbg_gain);

    jintArray arr = env->NewIntArray(7);
    if (!arr) return nullptr;
    jint v[7] = { w, h, jint(render_ms), jint(total_ms), jint(mean16),
                  jint(dbg_pe * 1000.0), jint(dbg_gain * 1000.0) };
    env->SetIntArrayRegion(arr, 0, 7, v);
    LOGI("export %dx%d render=%.1fms total=%.1fms mean16=%.0f pe=%.4f gain=%.4f",
         w, h, render_ms, total_ms, mean16, dbg_pe, dbg_gain);
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
    log_edge_profile(result, progPath);
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
    log_edge_profile(result, progPath);
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
