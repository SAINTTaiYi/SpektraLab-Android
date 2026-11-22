package app.spektralab.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The Film & Paper panel, built from injected strings, fonts and callbacks. */
public final class FilmPanel {
    public interface Host {
        String text(String key);
        TextView label(String text, float sp, int color, Typeface typeface);
        LinearLayout sectionHead(String title, Runnable reset);
        void onMeterClick();
        void onPaperClick();
        void onReset();
    }

    public final View root;
    public final TextView meterChip;
    public final LinearLayout brandRow;
    public final HorizontalScrollView filmScroll;
    public final LinearLayout filmCardStrip;
    public final TextView paperValue;

    FilmPanel(View root, TextView meterChip, LinearLayout brandRow,
              HorizontalScrollView filmScroll, LinearLayout filmCardStrip,
              TextView paperValue) {
        this.root = root; this.meterChip = meterChip; this.brandRow = brandRow;
        this.filmScroll = filmScroll; this.filmCardStrip = filmCardStrip;
        this.paperValue = paperValue;
    }

    public static FilmPanel build(Context c, Host host, Typeface han, Typeface body, float density) {
        final LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout filmHead = host.sectionHead(host.text("film.film_paper"), host::onReset);
        final TextView meterLabel = host.label(host.text("film.metering"), 8.5f,
                UiPalette.MUTED, han);
        final TextView meterChip = host.label("", 8.5f, UiPalette.INK, han);
        meterChip.setGravity(android.view.Gravity.CENTER);
        meterChip.setPadding(dip(density, 10), dip(density, 6), dip(density, 10), dip(density, 6));
        meterChip.setMinWidth(dip(density, 48));
        meterChip.setMinHeight(dip(density, 30));
        meterChip.setOnClickListener(v -> host.onMeterClick());
        final LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-2, -2);
        labelLp.rightMargin = dip(density, 6);
        filmHead.addView(meterLabel, 1, labelLp);
        final LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, -2);
        chipLp.rightMargin = dip(density, 10);
        filmHead.addView(meterChip, 2, chipLp);
        panel.addView(filmHead, new LinearLayout.LayoutParams(-1, dip(density, 38)));

        final HorizontalScrollView brandScroll = new HorizontalScrollView(c);
        brandScroll.setHorizontalScrollBarEnabled(false);
        brandScroll.setFillViewport(false);
        final LinearLayout brandRow = new LinearLayout(c);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        brandScroll.addView(brandRow, new android.widget.FrameLayout.LayoutParams(-2, dip(density, 38)));
        panel.addView(brandScroll, new LinearLayout.LayoutParams(-1, dip(density, 38)));

        final HorizontalScrollView filmScroll = new HorizontalScrollView(c);
        filmScroll.setHorizontalScrollBarEnabled(false);
        filmScroll.setClipToPadding(false);
        filmScroll.setFillViewport(false);
        final LinearLayout filmCardStrip = new LinearLayout(c);
        filmCardStrip.setOrientation(LinearLayout.HORIZONTAL);
        filmCardStrip.setGravity(android.view.Gravity.TOP);
        filmCardStrip.setPadding(0, dip(density, 9), 0, dip(density, 8));
        filmScroll.addView(filmCardStrip, new android.widget.FrameLayout.LayoutParams(-2, -1));
        panel.addView(filmScroll, new LinearLayout.LayoutParams(-1, dip(density, 111)));

        final LinearLayout paperRow = new LinearLayout(c);
        paperRow.setOrientation(LinearLayout.HORIZONTAL);
        paperRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        paperRow.addView(host.label(host.text("film.paper_print_film"), 9,
                UiPalette.MUTED, han));
        final LinearLayout paperChoice = new LinearLayout(c);
        paperChoice.setOrientation(LinearLayout.HORIZONTAL);
        paperChoice.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        paperChoice.setOnClickListener(v -> host.onPaperClick());
        final TextView paperValue = host.label("Kodak Portra Endura", 10, UiPalette.INK, han);
        paperValue.setSingleLine(true);
        paperValue.setEllipsize(android.text.TextUtils.TruncateAt.END);
        paperValue.setGravity(android.view.Gravity.END);
        paperChoice.addView(paperValue, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView paperArrow = host.label("↓", 10, UiPalette.BLUE, body);
        final LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(-2, -2);
        arrowLp.leftMargin = dip(density, 7);
        paperChoice.addView(paperArrow, arrowLp);
        paperRow.addView(paperChoice, new LinearLayout.LayoutParams(0, dip(density, 35), 1f));
        panel.addView(paperRow, new LinearLayout.LayoutParams(-1, dip(density, 35)));
        return new FilmPanel(panel, meterChip, brandRow, filmScroll, filmCardStrip, paperValue);
    }

    private static int dip(float density, float v) { return Math.round(v * density); }
}
