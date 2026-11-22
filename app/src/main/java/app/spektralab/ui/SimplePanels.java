package app.spektralab.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Stateless scaffolds for the Format, Border and Compose panels. */
public final class SimplePanels {
    public interface Host {
        String text(String key);
        TextView label(String text, float sp, int color, Typeface typeface);
        LinearLayout sectionHead(String title, Runnable reset);
        void onReset();
    }

    public static final class Format {
        public final LinearLayout root, cardStrip;
        public final HorizontalScrollView scroll;
        public final TextView footLabel, footValue;
        Format(LinearLayout root, LinearLayout strip, HorizontalScrollView scroll,
               TextView footLabel, TextView footValue) {
            this.root = root; this.cardStrip = strip; this.scroll = scroll;
            this.footLabel = footLabel; this.footValue = footValue;
        }
    }

    public static final class Edge {
        public final LinearLayout root;
        Edge(LinearLayout root) { this.root = root; }
    }

    public static final class Compose {
        public final LinearLayout root, body;
        public final TextView cropTab, rotateTab;
        Compose(LinearLayout root, LinearLayout body, TextView cropTab, TextView rotateTab) {
            this.root = root; this.body = body; this.cropTab = cropTab; this.rotateTab = rotateTab;
        }
    }

    private SimplePanels() {}

    public static Format format(Context c, Host host, float density,
                                Typeface han, Typeface body, Runnable ready, Runnable reset) {
        final LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(host.sectionHead(host.text("border.frame_scale"), reset),
                new LinearLayout.LayoutParams(-1, dip(density, 38)));
        final HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        final LinearLayout strip = new LinearLayout(c);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setPadding(0, dip(density, 10), 0, 0);
        scroll.addView(strip, new android.widget.FrameLayout.LayoutParams(-2, dip(density, 127)));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dip(density, 127)));
        final LinearLayout foot = new LinearLayout(c);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final TextView footLabel = host.label(host.text("border.physical_film_size_crop_and"),
                8.5f, UiPalette.MUTED, han);
        foot.addView(footLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView footValue = host.label("36 mm", 9, UiPalette.BLUE, body);
        foot.addView(footValue);
        panel.addView(foot, new LinearLayout.LayoutParams(-1, dip(density, 28)));
        return new Format(panel, strip, scroll, footLabel, footValue);
    }

    public interface EdgeHost extends Host {
        android.view.View bigCard(String title, String subtitle,
                                  java.util.function.Supplier<String> summary, Runnable onClick);
        LinearLayout.LayoutParams cardParams();
        String rebateSummary();
        String dateSummary();
        void onRebate();
        void onDate();
    }

    public static Edge edge(Context c, EdgeHost host, float density, Runnable ready) {
        final LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout home = new LinearLayout(c);
        home.setOrientation(LinearLayout.VERTICAL);
        home.addView(host.sectionHead(host.text("border.frame_imprint"), host::onReset),
                new LinearLayout.LayoutParams(-1, dip(density, 38)));
        final HorizontalScrollView cardScroll = new HorizontalScrollView(c);
        cardScroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout strip = new LinearLayout(c);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.addView(host.bigCard(host.text("border.film_border"), "REBATE",
                host::rebateSummary, host::onRebate), host.cardParams());
        strip.addView(host.bigCard(host.text("border.date_stamp"), "DATE BACK",
                host::dateSummary, host::onDate), host.cardParams());
        cardScroll.addView(strip, new android.widget.FrameLayout.LayoutParams(-2, -2));
        home.addView(cardScroll, new LinearLayout.LayoutParams(-1, -2));
        root.addView(home, new LinearLayout.LayoutParams(-1, -2));
        return new Edge(root);
    }

    public interface ComposeHost extends Host {
        TextView tab(String text, int page);
        void onReady();
    }

    public static Compose compose(Context c, ComposeHost host, float density) {
        final LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(host.sectionHead(host.text("compose.composition"), host::onReset),
                new LinearLayout.LayoutParams(-1, dip(density, 38)));
        final LinearLayout sub = new LinearLayout(c);
        sub.setOrientation(LinearLayout.HORIZONTAL);
        final TextView cropTab = host.tab(host.text("compose.crop"), 0);
        final TextView rotateTab = host.tab(host.text("compose.rotate"), 1);
        sub.addView(cropTab, new LinearLayout.LayoutParams(0, dip(density, 32), 1f));
        sub.addView(rotateTab, new LinearLayout.LayoutParams(0, dip(density, 32), 1f));
        panel.addView(sub, new LinearLayout.LayoutParams(-1, dip(density, 32)));
        final LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        panel.addView(body, new LinearLayout.LayoutParams(-1, -2));
        return new Compose(panel, body, cropTab, rotateTab);
    }

    private static int dip(float density, float v) { return Math.round(v * density); }
}
