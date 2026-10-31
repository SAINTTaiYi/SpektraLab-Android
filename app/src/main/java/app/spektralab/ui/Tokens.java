package app.spektralab.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

/**
 * 暗房设计系统 · 唯一 token 来源。
 *
 * 约束：本项目无 AndroidX、无 Kotlin、res/ 只有 values/，
 * 所以颜色/字号/间距全部是编译期常量 + 静态方法；
 * 静态内部类（CompareView / GuideView / Glyph）也能直接引用，不必持有 Activity。
 *
 * 规格见 docs/redesign/05-minimal-spec.md 与 02-design-system.md。
 */
public final class Tokens {

    private Tokens() { }

    // ---------------------------------------------------------------- 颜色
    public static final int ROOT      = 0xFF0A0A0B; // 根底 / 系统栏
    public static final int STAGE     = 0xFF000000; // 预览舞台（纯黑）
    public static final int SURFACE   = 0xFF111112; // 面板 / 列表底
    public static final int RAISED    = 0xFF191A1C; // 弹层

    public static final int HAIR        = 0x14FFFFFF; // 8%  分区分割线
    public static final int HAIR_STRONG = 0x24FFFFFF; // 14% 描边 / ghost 边
    public static final int TRACK       = 0x1AFFFFFF; // 10% 滑杆轨道

    public static final int TEXT_1 = 0xFFEDECE8; // 16.5:1
    public static final int TEXT_2 = 0xFFA0A099; // 6.9:1
    public static final int TEXT_3 = 0xFF82827A; // 5.1:1（仅 ≥10sp 的微标）
    public static final int TEXT_ON_ACCENT = 0xFF0A0A0B;

    public static final int ACCENT       = 0xFFD9A55B; // 安全灯琥珀 9.0:1
    public static final int ACCENT_PRESS = 0xFFB98A45;
    public static final int ACCENT_TINT  = 0x1FD9A55B; // 12%
    public static final int DANGER       = 0xFFE08A76;

    /** 词标 SpektraLab 的颜色：跟 accent 走（备选 TEXT_1 / 0xFF6E7BD8 / TEXT_3）。 */
    public static final int WORDMARK = ACCENT;

    public static final float DISABLED_ALPHA = 0.38f;
    public static final float BUSY_DIM       = 0.35f;

    // ---------------------------------------------------------------- 字号（sp）：极简版只留 4 档
    public static final float SP_TITLE  = 24f;
    public static final float SP_ACTION = 13f;
    public static final float SP_BODY   = 12f;
    public static final float SP_HINT   = 10f;

    /** 弹层标题与列表值用的两个字号（不参与首屏密度）。 */
    public static final float SP_SHEET_TITLE = 20f;
    public static final float SP_FIELD_VALUE = 14f;

    // ---------------------------------------------------------------- 间距 / 行高（dp）
    public static final int GUTTER         = 20;
    public static final int GUTTER_COMPACT = 16;
    public static final int PAGE_SIDE      = 16; // 照片左右留白
    public static final int ROW_FIELD      = 56;
    public static final int ROW_SLIDER     = 56;
    public static final int ROW_ACTION     = 44;
    public static final int ROW_TAB        = 40;
    public static final int SHEET_HEAD     = 52;
    public static final int SHEET_FOOT     = 56;
    public static final int TOUCH_MIN      = 44;
    public static final int BTN_HEIGHT     = 48;
    public static final int BTN_HEIGHT_SM  = 44;
    public static final int HAIRLINE       = 1;

    // ---------------------------------------------------------------- 圆角
    public static final int RADIUS_NONE  = 0;
    public static final int RADIUS_SM    = 2;
    public static final int RADIUS_SHEET = 14;

    // ---------------------------------------------------------------- 字体
    private static Typeface display, body, han;

    public static synchronized void loadFonts(Context c) {
        if (display != null) return;
        try {
            display = Typeface.createFromAsset(c.getAssets(), "fonts/Inter-Light.ttf");
            body = Typeface.createFromAsset(c.getAssets(), "fonts/Inter-Regular.ttf");
        } catch (Throwable t) {
            display = Typeface.create("sans-serif-light", Typeface.NORMAL);
            body = Typeface.create("sans-serif", Typeface.NORMAL);
        }
        try {
            han = Typeface.createFromAsset(c.getAssets(), "fonts/OPPOSans.ttf");
        } catch (Throwable t) {
            han = Typeface.create("sans-serif", Typeface.NORMAL);
        }
    }

    public static Typeface display() { return display; }
    public static Typeface body() { return body; }
    public static Typeface han() { return han; }

    // ---------------------------------------------------------------- 度量
    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static float spToPx(Context c, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                c.getResources().getDisplayMetrics());
    }

    /** 数字等宽：滑杆值、尺寸、百分比不跳动。 */
    public static void tnum(TextView t) {
        t.setFontFeatureSettings("'tnum' 1");
    }

    /** 统一文本构造。tracking 为字距（em），caps 只用于英文。 */
    public static TextView text(Context c, CharSequence s, float sp, int color,
                                Typeface tf, float tracking, boolean caps) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        t.setLetterSpacing(tracking);
        if (caps) t.setAllCaps(true);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static View hairline(Context c, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        return v;
    }

    /** 命中区下限 44dp。 */
    public static void touchMin(View v, int widthDp, int heightDp) {
        Context c = v.getContext();
        v.setMinimumWidth(dp(c, widthDp));
        v.setMinimumHeight(dp(c, Math.max(heightDp, TOUCH_MIN)));
    }
}
