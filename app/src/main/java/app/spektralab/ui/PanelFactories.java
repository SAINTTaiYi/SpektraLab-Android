package app.spektralab.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Stateless factories for the two shelf-related panels. */
public final class PanelFactories {
    public interface Host {
        TextView label(String text, float sp, int color, Typeface typeface);
    }

    public static final class SourceBar {
        public final LinearLayout root;
        public final LinearLayout row;
        public final HorizontalScrollView scroll;
        public final LinearLayout strip;
        public final TextView add;

        SourceBar(LinearLayout root, LinearLayout row, HorizontalScrollView scroll,
                  LinearLayout strip, TextView add) {
            this.root = root; this.row = row; this.scroll = scroll;
            this.strip = strip; this.add = add;
        }
    }

    public static final class ShelfHandle {
        public final View root;
        public final ImageView image;

        ShelfHandle(View root, ImageView image) { this.root = root; this.image = image; }
    }

    private PanelFactories() {}

    public static SourceBar sourceBar(Context c, Host host, Typeface display, Typeface han,
                                      float density, Runnable onAdd, Runnable onFold) {
        final LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(UiPalette.PAPER);
        final LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dip(density, 10), 0, dip(density, 4), 0);

        final TextView add = host.label("＋", 17, UiPalette.BLUE, display);
        add.setGravity(android.view.Gravity.CENTER);
        add.setOnClickListener(v -> onAdd.run());
        row.addView(add, new LinearLayout.LayoutParams(dip(density, 40), dip(density, 48)));

        final HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final LinearLayout strip = new LinearLayout(c);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        scroll.addView(strip, new FrameLayout.LayoutParams(-2, -1));
        row.addView(scroll, new LinearLayout.LayoutParams(0, dip(density, 48), 1f));

        final TextView fold = host.label("‹", 15, UiPalette.MUTED, han);
        fold.setGravity(android.view.Gravity.CENTER);
        fold.setOnClickListener(v -> onFold.run());
        row.addView(fold, new LinearLayout.LayoutParams(dip(density, 28), dip(density, 48)));

        bar.addView(row, new LinearLayout.LayoutParams(-1, dip(density, 62)));
        return new SourceBar(bar, row, scroll, strip, add);
    }

    public static ShelfHandle shelfHandle(Context c, Host host, Typeface display,
                                          float density, Runnable onClick) {
        final int card = dip(density, 52), step = dip(density, 4);
        final FrameLayout handle = new FrameLayout(c);
        handle.setVisibility(View.GONE);
        for (int i = 2; i >= 1; --i) {
            final View edge = new View(c);
            final android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setColor(i == 2 ? 0xFFD9D9D3 : 0xFFECECE7);
            bg.setCornerRadius(dip(density, 7));
            bg.setStroke(Math.max(1, dip(density, 1)), UiPalette.LINE);
            edge.setBackground(bg);
            final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(card, card);
            lp.leftMargin = step * i;
            lp.topMargin = step * i;
            handle.addView(edge, lp);
        }
        final ImageView image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setClipToOutline(true);
        image.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dip(density, 7));
            }
        });
        handle.addView(image, new FrameLayout.LayoutParams(card, card));

        final TextView chevron = host.label("›", 14, UiPalette.onCanvas(), display);
        chevron.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        chevron.setPadding(0, 0, dip(density, 5), 0);
        chevron.setShadowLayer(dip(density, 2), 0, 0, 0xCC000000);
        handle.addView(chevron, new FrameLayout.LayoutParams(card, card));
        handle.setOnClickListener(v -> onClick.run());
        return new ShelfHandle(handle, image);
    }

    private static int dip(float density, float v) { return Math.round(v * density); }
}
