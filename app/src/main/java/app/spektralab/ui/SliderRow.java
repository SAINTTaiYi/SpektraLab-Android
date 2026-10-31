package app.spektralab.ui;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * 一条滑杆行（56dp）：上行是标签与琥珀数值，下行是轨道。
 *
 * 轨道是**自己画的**（白轨 + 琥珀填充两条 View），不依赖 SeekBar 的
 * progressDrawable inset —— 之前的写法在不同 ROM 上会被裁成 0 高，
 * 界面上只剩一个圆点。
 *
 * 两种输入方式：
 *   1. 拖动 —— 松手才提交（一次手势一次 JNI）；
 *   2. 点右侧数值 —— 数字输入（可键入、± 0.01 微调），确定后同样只提交一次。
 */
public final class SliderRow extends LinearLayout {

    public interface OnCommit { void onCommit(float value); }

    private final String labelText;
    private final float lo, hi;
    private final TextView valueView;
    private final SeekBar bar;
    private FrameLayout trackBox;
    private View fillView;
    private final int trackInset;
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
        this.trackInset = Tokens.dp(c, 7);
        Tokens.loadFonts(c);

        setOrientation(VERTICAL);
        setMinimumHeight(Tokens.dp(c, Tokens.ROW_SLIDER));
        setPadding(Tokens.dp(c, Tokens.GUTTER), Tokens.dp(c, 4),
                Tokens.dp(c, Tokens.GUTTER), Tokens.dp(c, 4));

        // ---- 上行：标签 + 数值（可点，带 1px 琥珀描边暗示可输入）----
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = Tokens.text(c, name, Tokens.SP_BODY, Tokens.TEXT_2, Tokens.han(), 0f, false);
        label.setSingleLine(true);
        head.addView(label, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        valueView = Tokens.text(c, fmt(initial), Tokens.SP_ACTION, Tokens.ACCENT,
                Tokens.body(), 0.02f, false);
        valueView.setGravity(Gravity.CENTER);
        valueView.setSingleLine(true);
        valueView.setMinWidth(Tokens.dp(c, 64));
        valueView.setPadding(Tokens.dp(c, 8), Tokens.dp(c, 2), Tokens.dp(c, 8), Tokens.dp(c, 2));

        GradientDrawable box = new GradientDrawable();
        box.setShape(GradientDrawable.RECTANGLE);
        box.setColor(0x00000000);
        box.setStroke(Math.max(1, Tokens.dp(c, 1)), 0x66D9A55B);
        valueView.setBackground(box);

        Tokens.tnum(valueView);
        valueView.setClickable(true);
        valueView.setFocusable(true);
        valueView.setContentDescription(name + "，当前 " + fmt(initial) + "，点按输入数值");
        valueView.setOnClickListener(v -> promptValue());
        head.addView(valueView, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        addView(head, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Tokens.dp(c, 18)));

        // ---- 下行：轨道（自己画）----
        trackBox = new FrameLayout(c);
        final int trackH = Math.max(2, Tokens.dp(c, 3));

        final android.view.View rail = new android.view.View(c);
        rail.setBackgroundColor(0xE6FFFFFF);
        trackBox.addView(rail, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, trackH, Gravity.CENTER_VERTICAL));

        fillView = new android.view.View(c);
        fillView.setBackgroundColor(Tokens.ACCENT);
        trackBox.addView(fillView, new FrameLayout.LayoutParams(0, trackH,
                Gravity.START | Gravity.CENTER_VERTICAL));

        bar = new SeekBar(c);
        bar.setMax(1000);
        bar.setProgress(progressOf(initial));
        bar.setBackground(null);
        bar.setProgressDrawable(new ColorDrawable(0x00000000));
        bar.setThumb(thumb(c));
        bar.setSplitTrack(false);
        bar.setPadding(trackInset, 0, trackInset, 0);
        bar.setContentDescription(name);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                final float v = valueOf(progress);
                valueView.setText(fmt(v));
                syncFill();
                if (SliderRow.this.onChange != null) SliderRow.this.onChange.accept(v);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                if (SliderRow.this.onCommit != null) {
                    SliderRow.this.onCommit.onCommit(valueOf(sb.getProgress()));
                }
            }
        });
        trackBox.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Tokens.dp(c, 30)));

        trackBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or_, ob) -> syncFill());
        addView(trackBox, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Tokens.dp(c, 30)));

        post(this::syncFill);
    }

    /** 外部改值（恢复默认 / 数字输入）：只同步显示，不提交。 */
    public void setValue(float v) {
        final float clamped = Math.max(lo, Math.min(hi, v));
        bar.setProgress(progressOf(clamped));
        valueView.setText(fmt(clamped));
        syncFill();
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

    /** 让琥珀填充段的宽度跟进度走（轨道宽度 - 两端 thumb 内缩）。 */
    private void syncFill() {
        if (trackBox == null || fillView == null) return;
        final int usable = trackBox.getWidth() - trackInset * 2;
        if (usable <= 0) return;
        final int w = Math.max(Tokens.dp(getContext(), 3),
                Math.round(usable * bar.getProgress() / 1000f));
        ViewGroup.LayoutParams lp = fillView.getLayoutParams();
        if (lp.width != w) {
            lp.width = w;
            fillView.setLayoutParams(lp);
        }
    }

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

    /** 拇指 14dp：奶白 + 1px 琥珀环，压在白轨与琥珀填充上都分得清。 */
    private GradientDrawable thumb(Context c) {
        GradientDrawable t = new GradientDrawable();
        t.setShape(GradientDrawable.OVAL);
        t.setColor(0xFFEDECE8);
        t.setStroke(Math.max(1, Tokens.dp(c, 1)), Tokens.ACCENT);
        t.setSize(Tokens.dp(c, 14), Tokens.dp(c, 14));
        return t;
    }
}
