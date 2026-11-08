package app.spektralab;

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

    /** "[E-1001] 中文说明" plus the engine's own text, which carries the numbers. */
    /**
     * The picker's grant, gone. Not an engine failure at all: a `content://`
     * URI is valid only while the process that received the grant lives, and
     * the ColorOS file manager will not make it persistable.
     */
    private static final String IMPORT_PERMISSION =
            "照片的读取权限已失效（系统收回了文件选择器给的临时授权）。请点「导入」重新选择这张照片";

    /**
     * The pixels are finished and on disk; the provider refused its own new
     * row. That distinction matters: "render failed" sends the user to their
     * photo library, "the album would not take the file" sends them to a
     * restart, and the two need different words.
     */
    private static final String ALBUM_WRITE =
            "照片已经渲染完成，是系统相册拒绝接收这个文件（不是渲染失败）。"
            + "重启手机再导出一次；如果每次都这样，把这条发给我";

    static boolean isAlbumWriteFailure(String raw) {
        if (raw == null) return false;
        return raw.contains("相册写入失败") || raw.contains("No item at content://media/");
    }

    static boolean isPermissionFailure(String raw) {
        if (raw == null) return false;
        return raw.contains("SecurityException") || raw.contains("Permission Denial")
                || raw.contains("that is not exported") || raw.contains("not exported from UID");
    }

    public static String format(String raw) {
        if (raw == null || raw.isEmpty()) return "[E-0000] 未知错误";
        if (isPermissionFailure(raw)) return "[E-1304] " + IMPORT_PERMISSION + "（" + raw + "）";
        if (isAlbumWriteFailure(raw)) return "[E-1306] " + ALBUM_WRITE + "（" + raw + "）";
        int code = codeOf(raw);
        if (code == 0) code = 9000;
        if (code == 9000 && isNativeLoadFailure(raw)) {
            if (raw.contains("libvulkan.so")) return "[E-9000] " + NO_VULKAN + "（" + raw + "）";
            return "[E-9000] " + NATIVE_LOAD + "（" + raw + "）";
        }
        return "[E-" + code + "] " + explain(code) + "（" + raw + "）";
    }

    /**
     * The most common E-9000 by far: the engine's own library did not load.
     * It used to arrive as a bare NoClassDefFoundError with no cause, which
     * reads like a bug in the app rather than a broken install, and sent people
     * looking in the wrong place. The chain now travels with the message (see
     * SpektraLabEngine.ensureLoaded), so this only has to name it.
     */
    private static final String NATIVE_LOAD =
            "引擎库没有加载成功（libspektrafilm_engine.so）。这是安装包里的原生库没有就位，"
            + "不是你操作的问题——重装没用；请点「复制」把下面这段完整发给我，我按名字就能定位";

    private static final String NO_VULKAN =
            "这台手机没有可用的 Vulkan 驱动，SpektraLab 的渲染全在 GPU 上，没有它跑不起来；"
            + "如果你的机型确实支持 Vulkan，请把这条截图发给我";

    static boolean isNativeLoadFailure(String raw) {
        if (raw == null) return false;
        return raw.contains("libspektrafilm_engine") || raw.contains("引擎库没有加载成功")
                || raw.contains("dlopen failed") || raw.contains("UnsatisfiedLink");
    }

    /** Short form for a status line, without the engine text. */
    public static String shortForm(String raw) {
        if (isPermissionFailure(raw)) return "[E-1304] 照片权限已失效，请重新导入";
        int code = codeOf(raw);
        if (code == 0) code = 9000;
        if (code == 9000 && isNativeLoadFailure(raw)) return "[E-9000] 引擎库没有加载成功";
        if (code == 1010) return "[E-1010] 画布超过单帧上限（约 42 MP）";
        if (code == 1108) return "[E-1108] 解码未授权：请把相机改成「无损压缩」";
        if (code == 1109) return "[E-1109] 缺少解码器：请先转成普通 DNG";
        if (code == 1110) return "[E-1110] 机型未收录：请把原始文件发我";
        return "[E-" + code + "] " + explain(code);
    }

    public static String explain(int code) {
        switch (code) {
            // 1xxx — the GPU, or the frame it was asked to take
            // **Never "use a smaller size".** The banded renderer exists so a
            // frame is not refused for the driver's binding range; if a kernel
            // still binds a whole plane, that is our bug, not the picture's.
            case 1001: return "渲染器有一个阶段仍按整块绑定这张图，超过了本机 GPU 的单缓冲上限。"
                             + "这是引擎里还没分带的路径，不是你图片的问题——请把这条和图片尺寸发给我";
            case 1002: return "渲染调度超出本机上限（图像太大）";
            // Byte-for-byte the honest sentence: `maxPixelsByMemory` reserves
            // 1.5 GiB and budgets 100 B/px (12 B/px decode + 4 B/px ARGB +
            // 76 B/px of GPU planes). A 50 MP frame wants ~5 GB by that
            // measure, so an 8 GB phone's ~4.5 GB of free memory cannot hold
            // it at native size. Nothing here is a cap in the code -- it is
            // the picture outgrowing the machine.
            case 1003: return "导入时内存不够：这张图按原尺寸解码（12 字节/像素）再加渲染要用的显存"
                             + "（约 76 字节/像素），超过了本机当前可用的内存。"
                             + "关掉其它应用或重启手机再试；如果还不够，就是这张图的尺寸超过了"
                             + "这台机器的物理内存，不是程序设了上限";
            case 1004: return "GPU 驱动被重置（过热或单次运算过久），请等机器凉下来再试";
            case 1005: return "本机没有可用的 Vulkan 驱动，或驱动版本低于引擎要求"
                             + "（需要 Vulkan 1.1 以上的计算管线）。"
                             + "换个系统版本更新的机器试试；同一台机器升级系统也可能解决";
            case 1006: return "本机驱动能力低于本程序的最低要求";
            case 1007: return "该效果在当前图形后端上尚未实现";
            case 1008: return "图形资源创建失败";
            // 1009 is what the engine reports when a Vulkan call itself failed
            // and the message did not name a limit. On a big frame that is
            // nearly always the device refusing to allocate the planes, so the
            // advice is the same as 1001's rather than "internal error", which
            // tells the user nothing and the developer less.
            case 1009: return "图形后端调用失败，大图时多为显存不足。关掉其它应用或重启手机再试一次；"
                             + "把这条发给我可以定位是哪一次分配失败";
            // The rebate's canvas, which is a frame of its own: a source that
            // fits can still lay out a canvas that cannot be rendered.
            case 1011: return "渲染引擎没能在这台设备上启动：Vulkan 设备建立失败，"
                             + "多半是驱动不完整或显存被别的应用占着。"
                             + "重启手机再试一次；仍然不行就是这台机器的驱动跑不了这个引擎";
            case 1010: return "开了片边之后，画布超过了安卓端冲洗引擎的单帧上限（约 42 MP）。"
                             + "这是画布分配的上限，不是引擎的渲染上限——请把尺寸发给我";
            // 11xx — the file
            case 1101: return "RAW 打开失败：格式或压缩方式不受支持";
            case 1102: return "RAW 解包失败：文件确实损坏或没有下载完整（换一份重传试试）";
            case 1103: return "RAW 显影失败";
            case 1104: return "RAW 输出异常";
            case 1105: return "RAW 高光扩展失败";
            case 1106: return "RAW 解码失败";
            // Not damage, and not something a smaller frame or a re-download
            // fixes: Nikon's high-efficiency NEF is JPEG-XS, and the decoder
            // for it is Nikon's to license. The cure is one camera setting, so
            // the sentence is the setting.
            case 1108: return "解码未授权：这是尼康高效率压缩（HE/HE★）RAW，"
                             + "解 JPEG-XS 的授权不在本程序里。请改相机设置——"
                             + "照片拍摄菜单 → RAW 记录 → 无损压缩；"
                             + "已经拍好的这张，请用 NX Studio 或 Adobe DNG Converter 转成 DNG 再导入";
            // No decoder in this build, and no way to add one without the
            // codec's own library. A different converter for each, so two
            // sentences; one code, because the cure is always "convert".
            case 1109: return "这个文件用的是本机没有解码器的压缩（DNG 的 JPEG XL，"
                             + "或 GoPro GPR 的 VC-5）。请先用 Adobe DNG Converter 转成普通 DNG"
                             + "（选无损压缩），GoPro 的文件请先用 GoPro Player 导出 DNG 或 TIFF";
            // The decoder has tables for specific bodies. A new one is not a
            // broken file, and saying "corrupt" would send the user to their
            // card reader for nothing.
            case 1110: return "这台相机还没有收录进解码器的机型表，这一档压缩暂时解不了。"
                             + "请把这张原始文件（不要压缩、不要截图）发我，我加上机型支持";
            // 12xx — the render graph
            case 1201: return "该效果在当前图形后端上尚未实现";
            case 1202: return "渲染没有输出像素";
            // 13xx — the request
            case 1301: return "胶片或相纸名称无效";
            case 1302: return "参数超出允许范围";
            case 1303: return "会话状态异常，请重新导入照片";
            case 1304: return IMPORT_PERMISSION;
            // The gate and the picture disagree about their shape. A crop is
            // the cure, and there are two ways to make one, so name both.
            case 1305: return "片门与照片比例不符：到「边框」页换一个同比例的片门，" +
                              "或到「构图」页裁成片门的比例";
            // 2xxx — the update channel
            case 2001: return "检查更新失败：网络不可用，或公告地址被当前网络拦截";
            case 2002: return "更新公告格式错误";
            default:   return "未归类的错误，请把这条截图发给开发者";
        }
    }
}
