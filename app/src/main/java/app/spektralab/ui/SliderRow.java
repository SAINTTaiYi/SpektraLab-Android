package app.spektralab.ui;

import android.content.Context;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * 一条滑杆行（56dp）：上行是标签与琥珀数值，下行是轨道。
 *
 * 两种输入方式：
 *   1. 拖动 —— 松手才提交（一次手势一次 JNI，不是每一千分之一都调）；
 *   2. 点右侧数值 —— 弹出数字输入（可键入、可 ± 微调 0.01），确定后同样只提交一次。
 */
public final class SliderRow extends LinearLayout {

    public interface OnCommit { void onCommit(float value); }

    private final String labelText;
    private final float lo, hi;
    private final TextView valueView;
    private final SeekBar bar;
    private final Consumer<Float> onChange;
    private final OnCommit onCommit;

    public SliderRow(Context c, String name, float lo, float hi, float initial,
                     Consumer<Float> onChange, OnCommit onCommit) {
        super(c);
        this.labelText = name;
        this.lo = lo;
        this.hi = hi;
        this.onChange = onChange;
        this.onCommit = onCommit;
        Tokens.loadFonts(c);

        setOrientation(VERTICAL);
        setMinimumHeight(Tokens.dp(c, Tokens.ROW_SLIDER));
        setPadding(Tokens.dp(c, Tokens.GUTTER), Tokens.dp(c, 4),
                Tokens.dp(c, Tokens.GUTTER), Tokens.dp(c, 4));

        // ---- 上行：标签 + 数值（可点，带下划线暗示可输入）----
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = Tokens.text(c, name, Tokens.SP_BODY, Tokens.TEXT_2, Tokens.han(), 0f, false);
        label.setSingleLine(true);
        head.addView(label, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        valueView = Tokens.text(c, fmt(initial), Tokens.SP_ACTION, Tokens.ACCENT,
                Tokens.body(), 0.02f, false);
        valueView.setGravity(Gravity.END);
        valueView.setSingleLine(true);
        valueView.setMinWidth(Tokens.dp(c, 64));
        valueView.setPadding(Tokens.dp(c, 8), Tokens.dp(c, 2), Tokens.dp(c, 2), Tokens.dp(c, 2));

        GradientDrawable underline = new GradientDrawable();
        underline.setShape(GradientDrawable.RECTANGLE);
        underline.setColor(0x00000000);
        underline.setStroke(Math.max(1, Tokens.dp(c, 1)), 0x66D9A55B);
        valueView.setBackground(underline);

        Tokens.tnum(valueView);
        valueView.setClickable(true);
        valueView.setFocusable(true);
        valueView.setContentDescription(name + "，当前 " + fmt(initial) + "，点按输入数值");
        valueView.setOnClickListener(v -> promptValue());
        head.addView(valueView, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        addView(head, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Tokens.dp(c, 18)));

        // ---- 下行：30dp 触控区，轨道 2dp 居中，拇指 14dp ----
        // 之前 36dp + insets 17/17 会被行高挤压后裁掉，轨道就"消失"了。
        bar = new SeekBar(c);
        bar.setMax(1000);
        bar.setProgress(progressOf(initial));
        style(c, bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                final float v = valueOf(progress);
                valueView.setText(fmt(v));
                if (SliderRow.this.onChange != null) SliderRow.this.onChange.accept(v);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                if (SliderRow.this.onCommit != null) {
                    SliderRow.this.onCommit.onCommit(valueOf(sb.getProgress()));
                }
            }
        });
        addView(bar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Tokens.dp(c, 30)));
    }

    /** 外部改值（恢复默认 / 数字输入）：只同步显示，不提交。 */
    public void setValue(float v) {
        final float clamped = Math.max(lo, Math.min(hi, v));
        bar.setProgress(progressOf(clamped));
        valueView.setText(fmt(clamped));
    }

    /** 外部改值并提交一次（数字输入走这条）。 */
    public void setValueAndCommit(float v) {
        setValue(v);
        final float clamped = Math.max(lo, Math.min(hi, v));
        if (onChange != null) onChange.accept(clamped);
        if (onCommit != null) onCommit.onCommit(clamped);
    }

    public void setRowEnabled(boolean enabled) {
        bar.setEnabled(enabled);
        valueView.setEnabled(enabled);
        setAlpha(enabled ? 1f : Tokens.DISABLED_ALPHA);
    }

    // ------------------------------------------------------------ 内部

    private void promptValue() {
        if (!isEnabled()) return;
        NumberSheet.show(getContext(), labelText, valueOf(bar.getProgress()), lo, hi,
                v -> setValueAndCommit(v));
    }

    private int progressOf(float v) {
        final float k = (v - lo) / (hi - lo);
        return Math.max(0, Math.min(1000, Math.round(k * 1000f)));
    }

    private float valueOf(int progress) {
        return lo + (hi - lo) * progress / 1000f;
    }

    private static String fmt(float v) {
        return String.format(Locale.US, "%.2f", v);
    }

    /** 轨道 3dp、未选段纯白（90%），已选段琥珀，拇指 14dp 奶白+金环——白轨上也能看清。 */
    private void style(Context c, SeekBar bar) {
        final int trackH = Math.max(2, Tokens.dp(c, 3));
        final int inset = Math.max(0, (Tokens.dp(c, 30) - trackH) / 2);

        GradientDrawable back = new GradientDrawable();
        back.setShape(GradientDrawable.RECTANGLE);
        back.setColor(0xE6FFFFFF);

        GradientDrawable fill = new GradientDrawable();
        fill.setShape(GradientDrawable.RECTANGLE);
        fill.setColor(Tokens.ACCENT);

        ClipDrawable clip = new ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new android.graphics.drawable.Drawable[]{back, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        layers.setLayerInset(0, 0, inset, 0, inset);
        layers.setLayerInset(1, 0, inset, 0, inset);
        bar.setProgressDrawable(layers);

        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(0xFFEDECE8);
        thumb.setStroke(Math.max(1, Tokens.dp(c, 1)), Tokens.ACCENT);
        thumb.setSize(Tokens.dp(c, 14), Tokens.dp(c, 14));
        bar.setThumb(thumb);
        bar.setSplitTrack(false);
        bar.setPadding(Tokens.dp(c, 7), 0, Tokens.dp(c, 7), 0);
    }
}
