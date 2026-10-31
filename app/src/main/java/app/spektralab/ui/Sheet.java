package app.spektralab.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 底部弹层：列表（选值）/ 说明 / 确认 三种形态，全部暗色。
 *
 * 替换掉原来的平台 AlertDialog 与 Spinner 下拉——那两样一出现就弹白底，
 * 是这个界面里唯一击穿设计系统的地方。
 *
 * 规格：05-minimal-spec.md §3 ④⑤。
 */
public final class Sheet {

    /** 选中回调（列表形态）。 */
    public interface OnPick { void onPick(int index); }

    /** 无参动作（按钮）。 */
    public interface OnAction { void run(); }

    private Sheet() { }

    // ------------------------------------------------------------ 列表

    public static void list(Context c, String title, List<String> items, int selected, OnPick onPick) {
        list(c, title, items, null, selected, onPick);
    }

    /** 列表，可带右侧短标签（如「负片 / 反转 / 电影」）。 */
    public static void list(Context c, String title, List<String> items, List<String> subs,
                            int selected, OnPick onPick) {
        final Dialog d = base(c);
        LinearLayout root = panel(c);
        root.addView(handle(c), lp(Tokens.dp(c, 40), Tokens.dp(c, 2), Gravity.CENTER_HORIZONTAL, 10));
        root.addView(titleRow(c, title, null, null));

        LinearLayout rows = new LinearLayout(c);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            final String sub = (subs != null && i < subs.size()) ? subs.get(i) : null;
            rows.addView(listRow(c, items.get(i), sub, i == selected, () -> {
                d.dismiss();
                if (onPick != null) onPick.onPick(index);
            }));
        }

        if (items.size() > 8) {
            ScrollView sv = new ScrollView(c);
            sv.setVerticalScrollBarEnabled(false);
            sv.addView(rows);
            root.addView(sv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (c.getResources().getDisplayMetrics().heightPixels * 0.60f)));
        } else {
            root.addView(rows);
        }

        show(d, root);
    }

    /** 说明 + 列表（关于弹层：上面一段设备能力，下面两行动作）。 */
    public static void listWithBody(Context c, String title, String body, List<String> items, OnPick onPick) {
        final Dialog d = base(c);
        LinearLayout root = panel(c);
        root.addView(handle(c), lp(Tokens.dp(c, 40), Tokens.dp(c, 2), Gravity.CENTER_HORIZONTAL, 10));
        root.addView(titleRow(c, title, null, null));
        if (body != null && body.length() > 0) root.addView(bodyView(c, body));

        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            root.addView(listRow(c, items.get(i), null, false, () -> {
                d.dismiss();
                if (onPick != null) onPick.onPick(index);
            }));
        }
        show(d, root);
    }

    // ------------------------------------------------------------ 说明 / 确认

    public static void message(Context c, String title, String body, String action, OnAction onAction) {
        final Dialog d = base(c);
        LinearLayout root = panel(c);
        root.addView(handle(c), lp(Tokens.dp(c, 40), Tokens.dp(c, 2), Gravity.CENTER_HORIZONTAL, 10));
        root.addView(titleRow(c, title, null, null));
        root.addView(bodyView(c, body));
        root.addView(buttonRow(c, d, null, action, onAction));
        show(d, root);
    }

    public static void confirm(Context c, String title, String body, String positive, OnAction onOk) {
        final Dialog d = base(c);
        LinearLayout root = panel(c);
        root.addView(handle(c), lp(Tokens.dp(c, 40), Tokens.dp(c, 2), Gravity.CENTER_HORIZONTAL, 10));
        root.addView(titleRow(c, title, null, null));
        root.addView(bodyView(c, body));
        root.addView(buttonRow(c, d, "取消", positive, onOk));
        show(d, root);
    }

    // ------------------------------------------------------------ 零件

    private static Dialog base(Context c) {
        Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return d;
    }

    private static LinearLayout panel(Context c) {
        Tokens.loadFonts(c);
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Tokens.RAISED);
        float r = Tokens.dp(c, Tokens.RADIUS_SHEET);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(bg);
        root.setPadding(0, 0, 0, Tokens.dp(c, 12));
        return root;
    }

    private static void show(final Dialog d, View content) {
        d.setContentView(content);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.6f);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        d.show();
    }

    private static View handle(Context c) {
        return Chrome.sheetHandle(c);
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int gravity, int topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.gravity = gravity;
        p.topMargin = topMargin;
        return p;
    }

    /** 弹层头：20sp Light 标题 + 可选右上文字动作。 */
    static LinearLayout titleRow(Context c, String title, String action, Runnable onAction) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Tokens.dp(c, Tokens.GUTTER), 0, Tokens.dp(c, Tokens.GUTTER), 0);
        row.setMinimumHeight(Tokens.dp(c, Tokens.SHEET_HEAD));

        TextView t = Tokens.text(c, title == null ? "" : title, Tokens.SP_SHEET_TITLE,
                Tokens.TEXT_1, Tokens.display(), 0f, false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (action != null) {
            TextView a = Tokens.text(c, action, 10f, Tokens.TEXT_3, Tokens.han(), 0.06f, false);
            a.setGravity(Gravity.CENTER);
            a.setPadding(Tokens.dp(c, 8), 0, Tokens.dp(c, 4), 0);
            if (onAction != null) a.setOnClickListener(v -> onAction.run());
            row.addView(a, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Tokens.dp(c, Tokens.TOUCH_MIN)));
        }
        return row;
    }

    /** 56dp 列表行：文字 15sp，选中转 accent 并带勾；行底一条发丝线。 */
    private static View listRow(Context c, String text, String sub, boolean selected,
                               Runnable onPick) {
        FrameLayout frame = new FrameLayout(c);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Tokens.dp(c, Tokens.GUTTER), 0, Tokens.dp(c, Tokens.GUTTER), 0);

        TextView t = Tokens.text(c, text, 15f, selected ? Tokens.ACCENT : Tokens.TEXT_1,
                Tokens.han(), 0f, false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (sub != null && sub.length() > 0) {
            TextView s = Tokens.text(c, sub, Tokens.SP_HINT, Tokens.TEXT_3, Tokens.han(), 0f, false);
            s.setSingleLine(true);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.leftMargin = Tokens.dp(c, 12);
            row.addView(s, slp);
        }

        if (selected) {
            View check = new Check(c, Tokens.ACCENT);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    Tokens.dp(c, 11), Tokens.dp(c, 11));
            clp.leftMargin = Tokens.dp(c, 12);
            row.addView(check, clp);
        }

        frame.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tokens.dp(c, Tokens.ROW_FIELD)));
        FrameLayout.LayoutParams hairLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Tokens.dp(c, 1)),
                Gravity.BOTTOM);
        frame.addView(Chrome.hairline(c), hairLp);

        frame.setMinimumHeight(Tokens.dp(c, Tokens.ROW_FIELD));
        frame.setClickable(true);
        frame.setFocusable(true);
        frame.setOnClickListener(v -> onPick.run());
        frame.setContentDescription(selected ? text + "，已选中" : text);
        return frame;
    }

    private static View bodyView(Context c, String body) {
        TextView t = Tokens.text(c, body == null ? "" : body, 12f, Tokens.TEXT_2,
                Tokens.han(), 0f, false);
        t.setLineSpacing(Tokens.dp(c, 5), 1f);
        t.setPadding(Tokens.dp(c, Tokens.GUTTER), 0, Tokens.dp(c, Tokens.GUTTER),
                Tokens.dp(c, 16));
        return t;
    }

    private static View buttonRow(Context c, final Dialog dialog, String cancel,
                                  String positive, final OnAction onPositive) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        row.setMinimumHeight(Tokens.dp(c, Tokens.SHEET_FOOT));
        row.setPadding(0, 0, Tokens.dp(c, Tokens.GUTTER), 0);

        if (cancel != null) {
            TextView n = Tokens.text(c, cancel, 12f, Tokens.TEXT_2, Tokens.han(), 0f, false);
            n.setPadding(Tokens.dp(c, 12), 0, Tokens.dp(c, 12), 0);
            n.setGravity(Gravity.CENTER);
            n.setOnClickListener(v -> {
                if (dialog != null) dialog.dismiss();
            });
            row.addView(n, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Tokens.dp(c, Tokens.TOUCH_MIN)));
        }

        TextView p = Tokens.text(c, positive == null ? "" : positive, 12f, Tokens.ACCENT,
                Tokens.han(), 0f, false);
        p.setPadding(Tokens.dp(c, 12), 0, Tokens.dp(c, 4), 0);
        p.setGravity(Gravity.CENTER);
        p.setOnClickListener(v -> {
            if (onPositive != null) onPositive.run();
        });
        row.addView(p, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tokens.dp(c, Tokens.TOUCH_MIN)));
        return row;
    }

    // ------------------------------------------------------------ 勾（手绘，避免 tofu）

    static final class Check extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Check(Context c, int color) {
            super(c);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f, c.getResources().getDisplayMetrics().density * 1.5f));
            p.setStrokeCap(Paint.Cap.SQUARE);
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            canvas.drawLine(w * 0.12f, h * 0.52f, w * 0.42f, h * 0.82f, p);
            canvas.drawLine(w * 0.42f, h * 0.82f, w * 0.92f, h * 0.20f, p);
        }
    }
}
