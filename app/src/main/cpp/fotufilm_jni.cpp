// The only thing Java needs from the second engine: run the whole chain on one
// photograph.
//
// Everything below `fh_process` is the same code the validation shell and the
// CLI exercise, so a picture that matches in the terminal matches on the phone:
// the tables, the configuration's pixel slots and the kernel are not re-derived
// for Android. This file exists so the two engines stay two libraries -- the
// Vulkan engine's JNI is a different translation unit and a different .so.

#include <jni.h>

#include <cmath>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

#include <fcntl.h>
#include <unistd.h>

#include "fotufilm_codecs.h"

extern "C" void fh_cancel(void);

extern "C" int fh_process(const char *resources, const char *config_prefix, const char *photo,
                          const char *out, const char *stock, const char *paper,
                          int max_edge, int screen_medium, float ev, float highlights,
                          float shadows, float saturation, float vibrance, float quality,
                          float warmth, float tint, float flare, float grade_lift, float grade_gain,
                          float expired_years, float bleach, const char *table_cache,
                          const char *meter_prefix, float push_stops, const char *slots);

/// The development conditions a stock lists, so the UI never offers a step the
/// pack lacks.
extern "C" int fh_push_stops(const char *resources, const char *stock, float *out, int capacity);


/// sRGB's transfer, both ways. The codecs hand back Display P3 *with the sRGB transfer*, so the
/// primaries are what change here; the encoding only has to be removed and put back.
inline float srgb_linear(float v) {
    if (!(v > 0.0f)) return 0.0f;
    if (v > 1.0f) v = 1.0f;
    return v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f);
}

inline int srgb_byte(float v) {
    if (!(v > 0.0f)) return 0;
    if (v > 1.0f) v = 1.0f;
    const float s = v <= 0.0031308f ? 12.92f * v : 1.055f * std::pow(v, 1.0f / 2.4f) - 0.055f;
    const int i = int(s * 255.0f + 0.5f);
    return i < 0 ? 0 : (i > 255 ? 255 : i);
}

namespace {

/**
 * What the host said about the last call.
 *
 * <p>Everything in the chain reports with `printf`, and in an Android app that
 * goes to /dev/null: a render that returned 1 arrived at Java as the number 1
 * and nothing else. The call is run with file descriptors 1 and 2 pointed at a
 * pipe, so the host's own sentences come back with the code. The host prints a
 * handful of lines per render, well inside a pipe's buffer.
 */
std::mutex g_log_mutex;
std::string g_last_log;

/// Runs `body` with stdout/stderr captured. Restores the descriptors whatever
/// happens, and leaves the text where `fh_last_log` can hand it over.
template <typename Body>
int captured(Body body) {
    int fds[2] = {-1, -1};
    int saved_out = -1, saved_err = -1;
    std::fflush(stdout);
    std::fflush(stderr);
    if (::pipe(fds) == 0) {
        saved_out = ::dup(1);
        saved_err = ::dup(2);
        if (saved_out >= 0 && saved_err >= 0) {
            ::dup2(fds[1], 1);
            ::dup2(fds[1], 2);
            ::close(fds[1]);
            fds[1] = -1;
            // Unbuffered, so the two streams land in the order they happened:
            // stdout is block-buffered when it is not a terminal, and every
            // stderr line would otherwise arrive before all of its stdout ones.
            ::setvbuf(stdout, nullptr, _IONBF, 0);
        } else {
            if (saved_out >= 0) { ::close(saved_out); saved_out = -1; }
            if (saved_err >= 0) { ::close(saved_err); saved_err = -1; }
            ::close(fds[0]);
            ::close(fds[1]);
            fds[0] = fds[1] = -1;
        }
    }
    const int rc = body();
    std::fflush(stdout);
    std::fflush(stderr);
    if (saved_out >= 0) {
        ::dup2(saved_out, 1);
        ::dup2(saved_err, 2);
        ::close(saved_out);
        ::close(saved_err);
        std::string text;
        char buffer[4096];
        ssize_t got;
        // Both write ends are closed now: read returns EOF at the end of what
        // the host wrote, and the loop never blocks.
        while ((got = ::read(fds[0], buffer, sizeof buffer)) > 0) {
            if (text.size() < 32768) text.append(buffer, size_t(got));
            if (text.size() >= 32768) { ::close(fds[0]); fds[0] = -1; break; }
        }
        if (fds[0] >= 0) ::close(fds[0]);
        std::lock_guard<std::mutex> lock(g_log_mutex);
        g_last_log = std::move(text);
    }
    return rc;
}

/// JNI's `NewStringUTF` takes *modified* UTF-8 and ART makes no promise about
/// anything else in it.
///
/// <p>The text this file hands back is the host's log, and the host prints file
/// paths: a name the picker copied out of a zip, a QQ download, a file off an SD
/// card. None of those are guaranteed to be valid UTF-8, and a lone 0x80..0xFF
/// byte is not something to feed a JNI string constructor. Decoding it here
/// turns anything that is not a sequence into U+FFFD, which is a replacement
/// character in a diagnostic line instead of undefined behaviour in the runtime.
jstring to_jstring(JNIEnv *env, const std::string &text) {
    std::vector<jchar> utf16;
    utf16.reserve(text.size());
    size_t i = 0;
    while (i < text.size()) {
        const unsigned char first = static_cast<unsigned char>(text[i]);
        unsigned cp = 0;
        size_t need = 0;
        if (first < 0x80) { cp = first; }
        else if ((first & 0xE0) == 0xC0) { cp = first & 0x1Fu; need = 1; }
        else if ((first & 0xF0) == 0xE0) { cp = first & 0x0Fu; need = 2; }
        else if ((first & 0xF8) == 0xF0) { cp = first & 0x07u; need = 3; }
        else { utf16.push_back(0xFFFD); ++i; continue; }
        if (i + need >= text.size()) { utf16.push_back(0xFFFD); ++i; continue; }
        bool good = true;
        for (size_t k = 1; k <= need; ++k) {
            const unsigned char next = static_cast<unsigned char>(text[i + k]);
            if ((next & 0xC0) != 0x80) { good = false; break; }
            cp = (cp << 6) | (next & 0x3Fu);
        }
        if (!good) { utf16.push_back(0xFFFD); ++i; continue; }
        i += need + 1;
        // A surrogate code point is not a character, nothing above the last
        // plane exists, and a sequence longer than the value needs is a different
        // spelling of a character that was already writable -- `C0 AF` is an
        // overlong slash, and decoding it as one is how a decoder differs from
        // its neighbours.
        if ((need == 1 && cp < 0x80u) || (need == 2 && cp < 0x800u) || (need == 3 && cp < 0x10000u)
                || cp > 0x10FFFFu || (cp >= 0xD800u && cp <= 0xDFFFu)) cp = 0xFFFDu;
        if (cp >= 0x10000u) {
            cp -= 0x10000u;
            utf16.push_back(jchar(0xD800u + (cp >> 10)));
            utf16.push_back(jchar(0xDC00u + (cp & 0x3FFu)));
        } else {
            utf16.push_back(jchar(cp));
        }
    }
    jstring result = env->NewString(utf16.empty() ? nullptr : utf16.data(),
                                    jsize(utf16.size()));
    if (!result) {
        env->ExceptionClear();
        result = env->NewString(nullptr, 0);
    }
    return result;
}

/// A message for `ThrowNew`, which has no length argument and so cannot carry a
/// replacement character: anything outside ASCII becomes '?'.
std::string ascii_only(const std::string &text) {
    std::string out;
    out.reserve(text.size());
    for (char c : text) out.push_back(static_cast<unsigned char>(c) < 0x80 ? c : '?');
    return out;
}

std::string take(JNIEnv *env, jstring value) {
    if (!value) return std::string();
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string copy = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return copy;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_app_spektralab_engine_FotuFilm_nativeProcess(JNIEnv *env, jclass, jstring resources,
                                            jstring config_prefix, jstring photo, jstring out,
                                            jstring stock, jstring paper, jint max_edge,
                                            jboolean screen_medium, jfloat ev, jfloat highlights,
                                            jfloat shadows, jfloat saturation, jfloat vibrance,
                                            jfloat quality, jfloat warmth, jfloat tint,
                                            jfloat flare, jfloat grade_lift, jfloat grade_gain,
                                            jfloat expired_years, jfloat bleach, jstring table_cache,
                                            jstring meter_prefix, jfloat push_stops,
                                            jstring slots) {
    // Each string has to outlive the call, so they are named rather than inlined.
    const std::string resources_s = take(env, resources);
    const std::string config_s = take(env, config_prefix);
    const std::string photo_s = take(env, photo);
    const std::string out_s = take(env, out);
    const std::string stock_s = take(env, stock);
    const std::string paper_s = take(env, paper);
    const std::string cache_s = take(env, table_cache);
    const std::string meter_s = take(env, meter_prefix);
    // "30=0.5;mul:31=2;add:456=0.3" -- the panel's direct configuration writes.
    const std::string slots_s = take(env, slots);
    return captured([&]() {
        return fh_process(resources_s.c_str(), config_s.c_str(), photo_s.c_str(), out_s.c_str(),
                          stock_s.c_str(), paper_s.c_str(), int(max_edge),
                          screen_medium ? 1 : 0, ev, highlights, shadows, saturation, vibrance,
                          quality, warmth, tint, flare, grade_lift, grade_gain,
                          expired_years, bleach, cache_s.c_str(), meter_s.c_str(), push_stops,
                          slots_s.c_str());
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_spektralab_engine_FotuFilm_nativeLastLog(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    return to_jstring(env, g_last_log);
}

extern "C" JNIEXPORT void JNICALL
Java_app_spektralab_engine_FotuFilm_cancel(JNIEnv *, jclass) {
    fh_cancel();
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_app_spektralab_engine_FotuFilm_nativePushStops(JNIEnv *env, jclass, jstring resources, jstring stock) {
    const std::string resources_s = take(env, resources);
    const std::string stock_s = take(env, stock);
    float buffer[16];
    const int n = fh_push_stops(resources_s.c_str(), stock_s.c_str(), buffer, 16);
    if (n <= 0) return nullptr;
    jfloatArray out = env->NewFloatArray(n);
    if (out) env->SetFloatArrayRegion(out, 0, n, buffer);
    return out;
}

// **A TIFF is not something Android can open.** `BitmapFactory` and `ImageDecoder` both refuse it,
// so importing anything Photoshop, Lightroom or a film scanner wrote ended on "无法解码图片".
// The second engine's codecs already carry libtiff, so the pixels are one call away: the
// thumbnail entry point decodes, uprights and reduces to `max_edge`, and hands back Display P3
// with the sRGB transfer at eight bits -- the same encoding a JPEG import arrives in.
extern "C" JNIEXPORT jintArray JNICALL
Java_app_spektralab_engine_FotuFilm_nativeDecodeImageToArgb(
        JNIEnv *env, jclass, jstring path, jint max_edge) {
    const std::string file = take(env, path);
    if (file.empty()) return nullptr;
    ffc_picture picture{};
    char error[512] = {};
    const int32_t status = ffc_thumbnail(file.c_str(),
                                         max_edge > 0 ? uint32_t(max_edge) : 0u,
                                         &picture, error, sizeof error);
    if (status != 0 || !picture.rgba || picture.width == 0 || picture.height == 0) {
        jclass cls = env->FindClass("java/lang/RuntimeException");
        if (cls) {
            env->ThrowNew(cls, ascii_only(error[0] ? error : "this image could not be decoded").c_str());
            env->DeleteLocalRef(cls);
        }
        ffc_picture_free(&picture);
        return nullptr;
    }
    const int w = int(picture.width), h = int(picture.height);
    std::vector<jint> out(size_t(2) + size_t(w) * size_t(h));
    out[0] = w;
    out[1] = h;
    // Display P3 to sRGB, both D65, row sums exactly one: a neutral in has to be a neutral out.
    static const float m[9] = {
         1.2249401f, -0.2249404f,  0.0000000f,
        -0.0420569f,  1.0420571f,  0.0000000f,
        -0.0196376f, -0.0786361f,  1.0982735f,
    };
    const uint8_t *src = picture.rgba;
    for (size_t i = 0, n = size_t(w) * size_t(h); i < n; ++i) {
        const float r = srgb_linear(src[i * 4 + 0] / 255.0f);
        const float g = srgb_linear(src[i * 4 + 1] / 255.0f);
        const float b = srgb_linear(src[i * 4 + 2] / 255.0f);
        out[2 + i] = jint(0xFF000000u)
                | (jint(srgb_byte(m[0] * r + m[1] * g + m[2] * b)) << 16)
                | (jint(srgb_byte(m[3] * r + m[4] * g + m[5] * b)) << 8)
                | jint(srgb_byte(m[6] * r + m[7] * g + m[8] * b));
    }
    ffc_picture_free(&picture);
    jintArray result = env->NewIntArray(jsize(out.size()));
    if (!result) return nullptr;
    env->SetIntArrayRegion(result, 0, jsize(out.size()), out.data());
    return result;
}
