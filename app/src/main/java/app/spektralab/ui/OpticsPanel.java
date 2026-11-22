package app.spektralab.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Stateless scaffold for the Print/Optics panel; knob bindings stay in the host. */
public final class OpticsPanel {
    public interface Host {
        String text(String key);
        TextView label(String text, float sp, int color, Typeface typeface);
        LinearLayout sectionHead(String title, Runnable reset);
        void styleBar(ScrubBar bar);
        void onKnobProgress(int progress);
        void onKnobCommit();
        void onReset();
    }

    public static final class Result {
        public final LinearLayout root;
        public final TextView title, value, hint, cost;
        public final ScrubBar bar;
        public final LinearLayout strip;

        Result(LinearLayout root, TextView title, TextView value, TextView hint,
               TextView cost, ScrubBar bar, LinearLayout strip) {
            this.root = root; this.title = title; this.value = value; this.hint = hint;
            this.cost = cost; this.bar = bar; this.strip = strip;
        }
    }

    private OpticsPanel() {}

    public static Result build(Context c, Host host, Typeface han, Typeface body,
                               float density) {
        final LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(host.sectionHead(host.text("print.print_settings"), host::onReset),
                new LinearLayout.LayoutParams(-1, dip(density, 38)));

        final LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final TextView title = host.label("", 9, UiPalette.INK, han);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView value = host.label("", 9, UiPalette.BLUE, body);
        head.addView(value);
        panel.addView(head, new LinearLayout.LayoutParams(-1, dip(density, 20)));

        final TextView hint = host.label("", 7.5f, UiPalette.MUTED, han);
        hint.setMaxLines(1);
        hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        panel.addView(hint, new LinearLayout.LayoutParams(-1, dip(density, 15)));
        final TextView cost = host.label("", 7.5f, UiPalette.MUTED, han);
        cost.setGravity(android.view.Gravity.END);
        panel.addView(cost, new LinearLayout.LayoutParams(-1, dip(density, 14)));

        final ScrubBar bar = new ScrubBar(c);
        bar.setMax(1000);
        host.styleBar(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                host.onKnobProgress(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { host.onKnobCommit(); }
        });
        panel.addView(bar, new LinearLayout.LayoutParams(-1, dip(density, 32)));

        final android.widget.HorizontalScrollView stripScroll =
                new android.widget.HorizontalScrollView(c);
        stripScroll.setHorizontalScrollBarEnabled(false);
        stripScroll.setClipToPadding(false);
        final LinearLayout strip = new LinearLayout(c);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(0, dip(density, 6), 0, 0);
        stripScroll.addView(strip, new android.widget.FrameLayout.LayoutParams(-2, -1));
        panel.addView(stripScroll, new LinearLayout.LayoutParams(-1, dip(density, 78)));

        return new Result(panel, title, value, hint, cost, bar, strip);
    }

    /** Called after the host has stored the returned view references. */
    public static void populate(LinearLayout panel, java.util.function.Consumer<LinearLayout> bits) {
        bits.accept(panel);
    }

    private static int dip(float density, float v) { return Math.round(v * density); }
}
