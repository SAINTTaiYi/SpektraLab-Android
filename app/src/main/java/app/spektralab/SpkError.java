package app.spektralab;

import static app.spektralab.AppLanguage.tr;

import app.spektralab.engine.SpektraLabEngine;

/**
 * Every failure the user can see carries a stable code.
 *
 * The engine classifies (see spk_error_code); this table says what to do about
 * it in words. A code without a message would be a riddle, and a message
 * without a code cannot be reported — so the UI shows both, always, and a
 * screenshot is enough to file the bug.
 */
public final class SpkError {

    private SpkError() {}

    /**
     * The stable code for a raw engine message, or 0 when the engine has no
     * opinion. Callers that want to *act* on a failure branch on this: E-1001
     * is the one with a cure (a smaller session), and a string compare on the
     * wording would break the moment the wording improves.
     */
    public static int codeOf(String raw) {
        if (raw == null || raw.isEmpty()) return 0;
        try { return SpektraLabEngine.errorCode(raw); } catch (Throwable ignored) { return 0; }
    }

    /**
     * The picker's grant, gone. Not an engine failure at all: a `content://`
     * URI is valid only while the process that received the grant lives, and
     * the ColorOS file manager will not make it persistable.
     */
    private static String importPermission() {
        return tr("error.android_revoked_temporary_access_to");
    }

    /**
     * The pixels are finished and on disk; the provider refused its own new
     * row. That distinction matters: "render failed" sends the user to their
     * photo library, "the album would not take the file" sends them to a
     * restart, and the two need different words.
     */
    private static String albumWrite() {
        return tr("error.the_photo_rendered_successfully_but");
    }

    public static final String ALBUM_WRITE_PREFIX = "Could not save to the photo library";

    static boolean isAlbumWriteFailure(String raw) {
        if (raw == null) return false;
        return raw.contains(ALBUM_WRITE_PREFIX) || raw.contains("相册写入失败")
                || raw.contains("No item at content://media/");
    }

    static boolean isPermissionFailure(String raw) {
        if (raw == null) return false;
        return raw.contains("SecurityException") || raw.contains("Permission Denial")
                || raw.contains("that is not exported") || raw.contains("not exported from UID");
    }

    public static String format(String raw) {
        if (raw == null || raw.isEmpty()) return tr("import.e_0000_unknown_error");
        if (isPermissionFailure(raw)) return "[E-1304] " + importPermission() + "\n\n" + raw;
        if (isAlbumWriteFailure(raw)) return "[E-1306] " + albumWrite() + "\n\n" + raw;
        int code = codeOf(raw);
        if (code == 0) code = 9000;
        if (code == 9000 && isNativeLoadFailure(raw)) {
            return "[E-9000] " + loadFailure(raw) + "\n\n" + raw;
        }
        return "[E-" + code + "] " + explain(code) + "\n\n" + raw;
    }

    /**
     * The most common E-9000 by far: the engine's own library did not load.
     * It used to arrive as a bare NoClassDefFoundError with no cause, which
     * reads like a bug in the app rather than a broken install, and sent people
     * looking in the wrong place. The chain now travels with the message (see
     * SpektraLabEngine.ensureLoaded), so this only has to name it.
     */
    private static String loadFailure(String raw) {
        if (raw.contains("libvulkan.so")) {
            return tr("error.spektralab_renders_on_the_gpu");
        }
        return tr("error.the_native_engine_library_libspektrafilm");
    }

    static boolean isNativeLoadFailure(String raw) {
        if (raw == null) return false;
        return raw.contains("libspektrafilm_engine") || raw.contains("引擎库没有加载成功")
                || raw.contains("dlopen failed") || raw.contains("UnsatisfiedLink");
    }

    /** Short form for a status line, without the engine text. */
    public static String shortForm(String raw) {
        if (isPermissionFailure(raw)) return tr("error.e_1304_photo_access_expired");
        if (isAlbumWriteFailure(raw)) return "[E-1306] " + tr("error.album_write_short");
        int code = codeOf(raw);
        if (code == 0) code = 9000;
        if (code == 9000 && isNativeLoadFailure(raw)) return tr("error.e_9000_could_not_load");
        if (code == 1010) return tr("error.e_1010_canvas_exceeds_the");
        if (code == 1108) return tr("error.e_1108_unsupported_nikon_raw");
        if (code == 1109) return tr("error.e_1109_decoder_unavailable_convert");
        if (code == 1110) return tr("error.e_1110_camera_model_not");
        return "[E-" + code + "] " + explain(code);
    }

    public static String explain(int code) {
        switch (code) {
            // 1xxx — the GPU, or the frame it was asked to take
            // **Never "use a smaller size".** The banded renderer exists so a
            // frame is not refused for the driver's binding range; if a kernel
            // still binds a whole plane, that is our bug, not the picture's.
            case 1001: return tr("error.detail.1001");
            case 1002: return tr("error.detail.1002");
            // Byte-for-byte the honest sentence: `maxPixelsByMemory` reserves
            // 1.5 GiB and budgets 100 B/px (12 B/px decode + 4 B/px ARGB +
            // 76 B/px of GPU planes). A 50 MP frame wants ~5 GB by that
            // measure, so an 8 GB phone's ~4.5 GB of free memory cannot hold
            // it at native size. Nothing here is a cap in the code -- it is
            // the picture outgrowing the machine.
            case 1003: return tr("error.detail.1003");
            case 1004: return tr("error.detail.1004");
            case 1005: return tr("error.detail.1005");
            case 1006: return tr("error.detail.1006");
            case 1007: return tr("error.detail.1007");
            case 1008: return tr("error.detail.1008");
            // 1009 is what the engine reports when a Vulkan call itself failed
            // and the message did not name a limit. On a big frame that is
            // nearly always the device refusing to allocate the planes, so the
            // advice is the same as 1001's rather than "internal error", which
            // tells the user nothing and the developer less.
            case 1009: return tr("error.detail.1009");
            // The rebate's canvas, which is a frame of its own: a source that
            // fits can still lay out a canvas that cannot be rendered.
            case 1011: return tr("error.detail.1011");
            case 1010: return tr("error.detail.1010");
            // 11xx — the file
            case 1101: return tr("error.detail.1101");
            case 1102: return tr("error.detail.1102");
            case 1103: return tr("error.detail.1103");
            case 1104: return tr("error.detail.1104");
            case 1105: return tr("error.detail.1105");
            case 1106: return tr("error.detail.1106");
            // Not damage, and not something a smaller frame or a re-download
            // fixes: Nikon's high-efficiency NEF is JPEG-XS, and the decoder
            // for it is Nikon's to license. The cure is one camera setting, so
            // the sentence is the setting.
            case 1108: return tr("error.detail.1108");
            // No decoder in this build, and no way to add one without the
            // codec's own library. A different converter for each, so two
            // sentences; one code, because the cure is always "convert".
            case 1109: return tr("error.detail.1109");
            // The decoder has tables for specific bodies. A new one is not a
            // broken file, and saying "corrupt" would send the user to their
            // card reader for nothing.
            case 1110: return tr("error.detail.1110");
            // 12xx — the render graph
            case 1201: return tr("error.detail.1201");
            case 1202: return tr("error.detail.1202");
            // 13xx — the request
            case 1301: return tr("error.detail.1301");
            case 1302: return tr("error.detail.1302");
            case 1303: return tr("error.detail.1303");
            case 1304: return importPermission();
            // The gate and the picture disagree about their shape. A crop is
            // the cure, and there are two ways to make one, so name both.
            case 1305: return tr("error.detail.1305");
            // 2xxx — the update channel
            case 2001: return tr("error.detail.2001");
            case 2002: return tr("error.detail.2002");
            default:   return tr("error.detail.default");
        }
    }
}
