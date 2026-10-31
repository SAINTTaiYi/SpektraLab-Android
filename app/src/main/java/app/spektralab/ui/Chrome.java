package app.spektralab.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 极简版的"外壳"零件：系统栏、insets、词标、主按钮、文字动作、手绘图标。
 *
 * 全部是静态工厂，不持有 Activity 状态；颜色只有一个来源（Tokens）。
 * 规格：docs/redesign/05-minimal-spec.md §3。
 */
public final class Chrome {

    private Chrome() { }

    // ------------------------------------------------------------ 系统栏

    /**
     * 暗色系统栏：清除亮色图标标志，栏色与根底一致。
     * 原来是 lightSystemBars()（把图标压成深色），暗色主题下必须反向。
     */
    public static void darkSystemBars(Activity a) {
        try {
            android.view.Window w = a.getWindow();
            w.setStatusBarColor(Tokens.ROOT);
            w.setNavigationBarColor(Tokens.ROOT);
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    c.setSystemBarsAppearance(0, mask);
                }
            } else {
                View d = w.getDecorView();
                int flags = d.getSystemUiVisibility();
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                d.setSystemUiVisibility(flags);
            }
        } catch (Throwable ignored) {
            // 系统栏不是功能，失败不影响界面
        }
    }

    /** 把系统栏 / 刘海占位让给根布局（与旧 applySystemInsets 同语义）。 */
    public static void applyInsets(final View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }

    // ------------------------------------------------------------ 文字

    /** 词标：12sp、字距 .08em、琥珀。空状态左上角，也可长按进"关于"。 */
    public static TextView wordmark(Context c, String text) {
        Tokens.loadFonts(c);
        TextView t = Tokens.text(c, text, Tokens.SP_ACTION, Tokens.WORDMARK,
                Tokens.body(), 0.06f, false);
        t.setSingleLine(true);
        return t;
    }

    /** 空状态大标题：24sp Light。 */
    public static TextView title(Context c, CharSequence s) {
        Tokens.loadFonts(c);
        TextView t = Tokens.text(c, s, Tokens.SP_TITLE, Tokens.TEXT_1,
                Tokens.display(), 0.02f, false);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** 文字动作（换图 / 调色 / 完成 / 恢复默认）：不带框，命中 44dp。 */
    public static TextView textAction(Context c, CharSequence s) {
        Tokens.loadFonts(c);
        TextView t = Tokens.text(c, s, Tokens.SP_BODY, Tokens.TEXT_2,
                Tokens.han(), 0f, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Tokens.dp(c, 4), 0, Tokens.dp(c, 4), 0);
        Tokens.touchMin(t, 0, Tokens.ROW_ACTION);
        return t;
    }

    /** 单行提示（10sp）：只在需要时出现——提示语、进度、错误。 */
    public static TextView hint(Context c, CharSequence s, boolean danger) {
        Tokens.loadFonts(c);
        TextView t = Tokens.text(c, s, Tokens.SP_HINT, danger ? Tokens.DANGER : Tokens.TEXT_3,
                Tokens.han(), 0.02f, false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setMinHeight(Tokens.dp(c, 20));
        return t;
    }

    // ------------------------------------------------------------ 按钮

    /** 主按钮：唯一描边元素，就是"下一步"。enabled=false 只换描边与文字色。 */
    public static TextView primaryButton(Context c, CharSequence label, boolean enabled) {
        Tokens.loadFonts(c);
        TextView t = Tokens.text(c, label, Tokens.SP_ACTION,
                enabled ? Tokens.ACCENT : Tokens.TEXT_3, Tokens.han(), 0f, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Tokens.dp(c, 20), 0, Tokens.dp(c, 20), 0);
        t.setMinHeight(Tokens.dp(c, Tokens.BTN_HEIGHT_SM));
        t.setBackground(border(c, enabled ? Tokens.ACCENT : Tokens.HAIR_STRONG));
        t.setEnabled(enabled);
        t.setClickable(enabled);
        t.setFocusable(enabled);
        return t;
    }

    /** 全宽主按钮（空状态）：48dp 高，左右 20 边距由父布局给。 */
    public static TextView wideButton(Context c, CharSequence label) {
        Tokens.loadFonts(c);
        TextView t = Tokens.text(c, label, Tokens.SP_ACTION, Tokens.ACCENT,
                Tokens.han(), 0f, false);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(Tokens.dp(c, Tokens.BTN_HEIGHT));
        t.setBackground(border(c, Tokens.ACCENT));
        return t;
    }

    /** 改主按钮的文字与可用态：同一个按钮从头用到尾，所以只改属性不重建。 */
    public static void setPrimary(TextView t, CharSequence label, boolean enabled) {
        if (t == null) return;
        t.setText(label);
        t.setTextColor(enabled ? Tokens.ACCENT : Tokens.TEXT_3);
        t.setBackground(border(t.getContext(), enabled ? Tokens.ACCENT : Tokens.HAIR_STRONG));
        t.setEnabled(enabled);
        t.setClickable(enabled);
        t.setFocusable(enabled);
    }

    private static GradientDrawable border(Context c, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(Tokens.dp(c, Tokens.RADIUS_SM));
        d.setColor(android.graphics.Color.TRANSPARENT);
        d.setStroke(Math.max(1, Tokens.dp(c, 1)), color);
        return d;
    }

    // ------------------------------------------------------------ 手绘图标

    /** 12dp 的两笔图形。字符表里没有的符号一律自己画，避免 tofu。 */
    public static final class Glyph extends View {
        public static final int CHEVRON_DOWN = 0;
        public static final int ARROW_NE     = 1;

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int kind;

        public Glyph(Context c, int color, int kind) {
            super(c);
            this.kind = kind;
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, c.getResources().getDisplayMetrics().density));
        }

        @Override protected void onDraw(Canvas canvas) {
            final float w = getWidth(), h = getHeight();
            final float pad = Math.min(w, h) * 0.22f;
            if (kind == ARROW_NE) {
                canvas.drawLine(pad, h - pad, w - pad, pad, p);
                canvas.drawLine(w - pad, pad, w - pad * 2f - w * 0.30f, pad, p);
                canvas.drawLine(w - pad, pad, w - pad, pad * 2f + h * 0.30f, p);
            } else {
                canvas.drawLine(pad, h * 0.38f, w / 2f, h * 0.66f, p);
                canvas.drawLine(w / 2f, h * 0.66f, w - pad, h * 0.38f, p);
            }
        }
    }

    /** 40×2dp 的弹层手柄。 */
    public static View sheetHandle(Context c) {
        View v = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(Tokens.dp(c, 1));
        d.setColor(0x33FFFFFF);
        v.setBackground(d);
        return v;
    }

    /** 1px 发丝线（水平）。 */
    public static View hairline(Context c) {
        View v = Tokens.hairline(c, Tokens.HAIR);
        v.setMinimumHeight(Math.max(1, Tokens.dp(c, 1)));
        return v;
    }

    /** 竖分隔 1×16dp，用于动作栏。 */
    public static View vline(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Tokens.HAIR);
        v.setLayoutParams(new ViewGroup.LayoutParams(Math.max(1, Tokens.dp(c, 1)),
                Tokens.dp(c, 16)));
        return v;
    }

    /** 居中容器：把一条内容放在根布局里，左右留白统一。 */
    public static LinearLayout row(Context c, int gravity) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(gravity);
        l.setPadding(Tokens.dp(c, Tokens.GUTTER), 0, Tokens.dp(c, Tokens.GUTTER), 0);
        return l;
    }

    /** 供弹层复用：把 dp 转成像素的短别名。 */
    public static int px(Context c, float dp) {
        return Tokens.dp(c, dp);
    }
}
