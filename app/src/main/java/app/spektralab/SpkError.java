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
    public static String format(String raw) {
        if (raw == null || raw.isEmpty()) return "[E-0000] 未知错误";
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
            "引擎库没有加载成功（libspektrafilm_engine.so）——多数是安装时 so 没被解出来，"
            + "从官网重新下载覆盖安装一次通常就好；仍然不行请把这条截图发给我";

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
        int code = codeOf(raw);
        if (code == 0) code = 9000;
        if (code == 9000 && isNativeLoadFailure(raw)) return "[E-9000] 引擎库没有加载成功";
        if (code == 1010) return "[E-1010] 画布超过单帧上限（约 42 MP）";
        return "[E-" + code + "] " + explain(code);
    }

    public static String explain(int code) {
        switch (code) {
            // 1xxx — the GPU, or the frame it was asked to take
            case 1001: return "这张图超过了本机 GPU 的单缓冲上限，请用较小的尺寸导入或导出";
            case 1002: return "渲染调度超出本机上限（图像太大）";
            case 1003: return "显存不足";
            case 1004: return "GPU 驱动被重置（过热或单次运算过久），请等机器凉下来再试";
            case 1005: return "本机没有可用的 Vulkan 驱动";
            case 1006: return "本机驱动能力低于本程序的最低要求";
            case 1007: return "该效果在当前图形后端上尚未实现";
            case 1008: return "图形资源创建失败";
            // 1009 is what the engine reports when a Vulkan call itself failed
            // and the message did not name a limit. On a big frame that is
            // nearly always the device refusing to allocate the planes, so the
            // advice is the same as 1001's rather than "internal error", which
            // tells the user nothing and the developer less.
            case 1009: return "图形后端调用失败（大图多为显存不足）：可关掉片边、或按较小尺寸重新导入后重试";
            // The rebate's canvas, which is a frame of its own: a source that
            // fits can still lay out a canvas that cannot be rendered.
            case 1010: return "开了片边之后，画布超过了安卓端冲洗引擎的单帧上限（约 42 MP）："
                             + "请关掉片边，或按更小的尺寸重新导入";
            // 11xx — the file
            case 1101: return "RAW 打开失败：格式或压缩方式不受支持";
            case 1102: return "RAW 解包失败：文件可能损坏或未下载完整";
            case 1103: return "RAW 显影失败";
            case 1104: return "RAW 输出异常";
            case 1105: return "RAW 高光扩展失败";
            case 1106: return "RAW 解码失败";
            // 12xx — the render graph
            case 1201: return "该效果在当前图形后端上尚未实现";
            case 1202: return "渲染没有输出像素";
            // 13xx — the request
            case 1301: return "胶片或相纸名称无效";
            case 1302: return "参数超出允许范围";
            case 1303: return "会话状态异常，请重新导入照片";
            // 2xxx — the update channel
            case 2001: return "检查更新失败：网络不可用，或公告地址被当前网络拦截";
            case 2002: return "更新公告格式错误";
            default:   return "未归类的错误，请把这条截图发给开发者";
        }
    }
}
