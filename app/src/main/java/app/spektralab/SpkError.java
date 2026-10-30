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

    /** "[E-1001] 中文说明" plus the engine's own text, which carries the numbers. */
    public static String format(String raw) {
        if (raw == null || raw.isEmpty()) return "[E-0000] 未知错误";
        int code = 9000;
        try { code = SpektraLabEngine.errorCode(raw); } catch (Throwable ignored) { }
        if (code == 0) code = 9000;
        return "[E-" + code + "] " + explain(code) + (raw.isEmpty() ? "" : "（" + raw + "）");
    }

    /** Short form for a status line, without the engine text. */
    public static String shortForm(String raw) {
        int code = 9000;
        try { code = SpektraLabEngine.errorCode(raw); } catch (Throwable ignored) { }
        if (code == 0) code = 9000;
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
            case 1009: return "图形后端内部错误";
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
