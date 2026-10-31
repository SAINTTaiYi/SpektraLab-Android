package app.spektralab.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「调节」底部弹层：一个头（调节 / 完成）、四个标签、一次一组的内容、一条恢复默认。
 *
 * 参数不常驻屏幕——这是极简版与旧版最大的区别。内容由 MainActivity 组装后塞进来，
 * 这个类只负责壳与标签切换。
 */
public final class ParamSheet {

    private static final String[] TABS = {"胶片", "光学", "画幅", "更多"};

    private final Dialog dialog;
    private final LinearLayout root;
    private final FrameLayout content;
    private final TextView[] tabLabels = new TextView[TABS.length];
    private final View[] tabBars = new View[TABS.length];
    private final View[] groups = new View[TABS.length];

    private Runnable onReset;
    private Runnable onAbout;
    private int active = 1;

    public ParamSheet(Context c) {
        Tokens.loadFonts(c);

        dialog = new Dialog(c);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Tokens.RAISED);
        float r = Tokens.dp(c, Tokens.RADIUS_SHEET);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(bg);

        final LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                Tokens.dp(c, 40), Tokens.dp(c, 2));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.topMargin = Tokens.dp(c, 10);
        root.addView(handle(c), handleLp);
        root.addView(header(c));

        LinearLayout tabs = new LinearLayout(c);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setMinimumHeight(Tokens.dp(c, Tokens.ROW_TAB));
        for (int i = 0; i < TABS.length; i++) {
            tabs.addView(tab(c, i), new LinearLayout.LayoutParams(0, Tokens.dp(c, Tokens.ROW_TAB), 1f));
        }
        root.addView(tabs);

        content = new FrameLayout(c);
        content.setPadding(0, 0, 0, 0);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(footer(c));

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.6f);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
    }

    // ------------------------------------------------------------ 组装

    /** 放进某一组（0 胶片 / 1 光学 / 2 画幅 / 3 更多）。 */
    public void setGroup(int index, View v) {
        if (index < 0 || index >= groups.length) return;
        groups[index] = v;
        content.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        v.setVisibility(index == active ? View.VISIBLE : View.GONE);
    }

    public void setOnReset(Runnable r) { this.onReset = r; }

    public void setOnAbout(Runnable r) { this.onAbout = r; }

    public void setTab(int index) {
        if (index < 0 || index >= TABS.length) return;
        active = index;
        for (int i = 0; i < TABS.length; i++) {
            final boolean on = i == index;
            if (tabLabels[i] != null) tabLabels[i].setTextColor(on ? Tokens.TEXT_1 : Tokens.TEXT_3);
            if (tabBars[i] != null) tabBars[i].setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            if (groups[i] != null) groups[i].setVisibility(on ? View.VISIBLE : View.GONE);
        }
    }

    public void show() {
        if (!dialog.isShowing()) dialog.show();
    }

    public void dismiss() {
        if (dialog.isShowing()) dialog.dismiss();
    }

    public boolean isShowing() { return dialog.isShowing(); }

    public void setOnDismiss(Runnable r) { dialog.setOnDismissListener(d -> r.run()); }


    /** 组内说明行（10sp，只用于解释交互，不抢内容）。 */
    public static TextView hint(Context c, String text) {
        TextView t = Tokens.text(c, text, Tokens.SP_HINT, Tokens.TEXT_3, Tokens.han(), 0f, false);
        t.setPadding(Tokens.dp(c, Tokens.GUTTER), Tokens.dp(c, 6),
                Tokens.dp(c, Tokens.GUTTER), Tokens.dp(c, 10));
        return t;
    }

    // ------------------------------------------------------------ 零件

    private View handle(Context c) { return Chrome.sheetHandle(c); }

    private View header(final Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Tokens.dp(c, Tokens.GUTTER), 0, Tokens.dp(c, Tokens.GUTTER), 0);
        row.setMinimumHeight(Tokens.dp(c, Tokens.SHEET_HEAD));

        TextView t = Tokens.text(c, "调节", Tokens.SP_SHEET_TITLE, Tokens.TEXT_1,
                Tokens.display(), 0f, false);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView done = Tokens.text(c, "完成", 10f, Tokens.TEXT_3, Tokens.han(), 0.06f, false);
        done.setGravity(Gravity.CENTER);
        done.setPadding(Tokens.dp(c, 10), 0, 0, 0);
        done.setOnClickListener(v -> dismiss());
        row.addView(done, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tokens.dp(c, Tokens.TOUCH_MIN)));
        return row;
    }

    private View tab(final Context c, final int index) {
        FrameLayout cell = new FrameLayout(c);

        TextView t = Tokens.text(c, TABS[index], Tokens.SP_BODY, Tokens.TEXT_3,
                Tokens.han(), 0f, false);
        t.setGravity(Gravity.CENTER);
        cell.addView(t, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View bar = new View(c);
        bar.setBackgroundColor(Tokens.ACCENT);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                Tokens.dp(c, 24), Math.max(2, Tokens.dp(c, 2)), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cell.addView(bar, blp);

        tabLabels[index] = t;
        tabBars[index] = bar;
        bar.setVisibility(index == active ? View.VISIBLE : View.INVISIBLE);
        t.setTextColor(index == active ? Tokens.TEXT_1 : Tokens.TEXT_3);

        cell.setOnClickListener(v -> setTab(index));
        return cell;
    }

    private View footer(final Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Tokens.dp(c, Tokens.SHEET_FOOT));
        row.setPadding(Tokens.dp(c, Tokens.GUTTER), 0, Tokens.dp(c, Tokens.GUTTER), 0);

        TextView reset = Tokens.text(c, "恢复默认", 10f, Tokens.TEXT_3, Tokens.han(), 0.06f, false);
        reset.setPadding(0, 0, Tokens.dp(c, 16), 0);
        reset.setOnClickListener(v -> {
            if (onReset != null) {
                onReset.run();
                setTab(active);
            }
        });
        row.addView(reset, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tokens.dp(c, Tokens.TOUCH_MIN)));

        row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));

        final TextView about = Tokens.text(c, "关于", 10f, Tokens.TEXT_3, Tokens.han(), 0.06f, false);
        about.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        about.setPadding(Tokens.dp(c, 16), 0, 0, 0);
        about.setOnClickListener(v -> {
            dismiss();
            if (onAbout != null) onAbout.run();
        });
        row.addView(about, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tokens.dp(c, Tokens.TOUCH_MIN)));

        row.setBackgroundColor(Tokens.SURFACE);
        return row;
    }
}
