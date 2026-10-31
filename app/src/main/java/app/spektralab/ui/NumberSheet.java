package app.spektralab.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * 数字输入弹层：点滑杆右侧的数值进来。
 *
 * 三种落值方式：直接键入、−/+ 微调（0.01）、确定按钮；超出范围自动夹紧。
 * 确定后只回调一次，和拖动松手是同一套提交语义。
 */
public final class NumberSheet {

    private static final float STEP = 0.01f;

    private NumberSheet() { }

    public static void show(Context c, String title, float value, float lo, float hi,
                            final Consumer<Float> onOk) {
        Tokens.loadFonts(c);

        final Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(Tokens.RAISED);
        final float r = Tokens.dp(c, Tokens.RADIUS_SHEET);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(bg);
        root.setPadding(0, 0, 0, Tokens.dp(c, 8));

        // 手柄
        final LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                Tokens.dp(c, 40), Tokens.dp(c, 2));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = Tokens.dp(c, 10);
        root.addView(Chrome.sheetHandle(c), hlp);

        // 标题 + 范围
        final LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Tokens.dp(c, Tokens.GUTTER), 0, Tokens.dp(c, Tokens.GUTTER), 0);
        head.setMinimumHeight(Tokens.dp(c, 48));
        final TextView t = Tokens.text(c, title, Tokens.SP_SHEET_TITLE, Tokens.TEXT_1,
                Tokens.display(), 0f, false);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(Tokens.text(c, String.format(Locale.US, "%.2f – %.2f", lo, hi),
                Tokens.SP_HINT, Tokens.TEXT_3, Tokens.body(), 0f, false));
        root.addView(head);

        // − 输入框 +
        final LinearLayout editor = new LinearLayout(c);
        editor.setOrientation(LinearLayout.HORIZONTAL);
        editor.setGravity(Gravity.CENTER_VERTICAL);
        editor.setPadding(Tokens.dp(c, Tokens.GUTTER), 0, Tokens.dp(c, Tokens.GUTTER), 0);

        final EditText input = new EditText(c);
        input.setText(String.format(Locale.US, "%.2f", value));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        input.setTextColor(Tokens.ACCENT);
        input.setGravity(Gravity.CENTER);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setSingleLine(true);
        input.setBackground(null);
        input.setSelectAllOnFocus(true);
        input.setPadding(0, Tokens.dp(c, 10), 0, Tokens.dp(c, 10));
        Tokens.tnum(input);

        editor.addView(stepButton(c, "−", () -> nudge(input, -STEP, lo, hi)),
                new LinearLayout.LayoutParams(Tokens.dp(c, 56), Tokens.dp(c, 56)));
        editor.addView(input, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        editor.addView(stepButton(c, "+", () -> nudge(input, STEP, lo, hi)),
                new LinearLayout.LayoutParams(Tokens.dp(c, 56), Tokens.dp(c, 56)));
        root.addView(editor);

        // 细线 + 说明
        final LinearLayout.LayoutParams hairLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Tokens.dp(c, 1)));
        hairLp.leftMargin = Tokens.dp(c, Tokens.GUTTER);
        hairLp.rightMargin = Tokens.dp(c, Tokens.GUTTER);
        hairLp.topMargin = Tokens.dp(c, 6);
        root.addView(Chrome.hairline(c), hairLp);

        final TextView hint = Tokens.text(c, "可直接输入，± 号按 0.01 微调",
                Tokens.SP_HINT, Tokens.TEXT_3, Tokens.han(), 0f, false);
        hint.setPadding(Tokens.dp(c, Tokens.GUTTER), Tokens.dp(c, 10),
                Tokens.dp(c, Tokens.GUTTER), 0);
        root.addView(hint);

        // 取消 / 确定
        final LinearLayout foot = new LinearLayout(c);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        foot.setMinimumHeight(Tokens.dp(c, 56));
        foot.setPadding(0, 0, Tokens.dp(c, Tokens.GUTTER), 0);

        final TextView cancel = Tokens.text(c, "取消", Tokens.SP_BODY, Tokens.TEXT_2,
                Tokens.han(), 0f, false);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(Tokens.dp(c, 16), 0, Tokens.dp(c, 16), 0);
        cancel.setOnClickListener(v -> d.dismiss());
        foot.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tokens.dp(c, Tokens.TOUCH_MIN)));

        final TextView ok = Tokens.text(c, "确定", Tokens.SP_BODY, Tokens.ACCENT,
                Tokens.han(), 0f, false);
        ok.setGravity(Gravity.CENTER);
        ok.setPadding(Tokens.dp(c, 16), 0, Tokens.dp(c, 4), 0);
        ok.setOnClickListener(v -> {
            final float parsed = parse(input.getText().toString(), value, lo, hi);
            d.dismiss();
            if (onOk != null) onOk.accept(parsed);
        });
        foot.addView(ok, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Tokens.dp(c, Tokens.TOUCH_MIN)));
        root.addView(foot);

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                ok.performClick();
                return true;
            }
            return false;
        });

        d.setContentView(root);
        final Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.6f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        d.show();
        input.requestFocus();
        input.setSelection(input.getText().length());
    }

    // ------------------------------------------------------------ 内部

    private static TextView stepButton(Context c, String label, final Runnable action) {
        final TextView b = Tokens.text(c, label, 20f, Tokens.TEXT_1, Tokens.body(), 0f, false);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setContentDescription("−".equals(label) ? "减少 0.01" : "增加 0.01");
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private static void nudge(EditText input, float delta, float lo, float hi) {
        final float cur = parse(input.getText().toString(), lo, lo, hi);
        final float next = clamp(cur + delta, lo, hi);
        input.setText(String.format(Locale.US, "%.2f", next));
        input.setSelection(input.getText().length());
    }

    private static float parse(String s, float fallback, float lo, float hi) {
        try {
            return clamp(Float.parseFloat(s.trim()), lo, hi);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
