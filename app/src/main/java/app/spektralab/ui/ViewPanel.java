package app.spektralab.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** The Optics panel: a state-free scaffold whose pages are supplied by the host. */
public final class ViewPanel {
    public interface Host {
        String text(String key);
        TextView label(String text, float sp, int color, Typeface typeface);
        LinearLayout sectionHead(String title, Runnable reset);
        void onCategory(int page);
        void buildPage(int page, LinearLayout container);
        void onReset();
        void onReady();
    }

    public final LinearLayout root;
    public final TextView lockNote;
    public final TextView[] categoryChips;

    private ViewPanel(LinearLayout root, TextView lockNote, TextView[] chips) {
        this.root = root; this.lockNote = lockNote; this.categoryChips = chips;
    }

    public static ViewPanel build(Context c, Host host, Typeface han, Typeface body, float density,
                                  LinearLayout[] pages, CappedScroll[] scrolls,
                                  List<float[]>[] curvePoints) {
        final LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(host.sectionHead(host.text("optics.optics"), host::onReset),
                new LinearLayout.LayoutParams(-1, dip(density, 38)));
        final TextView lockNote = host.label(host.text("optics.choose_a_film_first_these"),
                8.5f, UiPalette.MUTED, han);
        lockNote.setPadding(dip(density, 2), dip(density, 4), dip(density, 2), dip(density, 8));
        panel.addView(lockNote, new LinearLayout.LayoutParams(-1, -2));

        final String[] cats = {
                host.text("optics.light"), host.text("optics.color"),
                host.text("optics.effects"), host.text("optics.curves"),
                host.text("optics.grading")};
        final LinearLayout catRow = new LinearLayout(c);
        catRow.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] chips = new TextView[cats.length];
        for (int i = 0; i < cats.length; ++i) {
            final int page = i;
            final TextView chip = host.label(cats[i], 8,
                    i == 0 ? UiPalette.BLUE : UiPalette.MUTED, body);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setOnClickListener(v -> host.onCategory(page));
            catRow.addView(chip, new LinearLayout.LayoutParams(0, dip(density, 26), 1f));
            chips[i] = chip;
        }
        panel.addView(catRow, new LinearLayout.LayoutParams(-1, dip(density, 28)));

        for (int i = 0; i < pages.length; ++i) {
            pages[i] = new LinearLayout(c);
            pages[i].setOrientation(LinearLayout.VERTICAL);
            pages[i].setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            final CappedScroll scroll = new CappedScroll(c, dip(density, 280));
            scroll.addView(pages[i], new android.widget.FrameLayout.LayoutParams(-1, -2));
            panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
            scrolls[i] = scroll;
            host.buildPage(i, pages[i]);
        }
        for (int i = 0; i < curvePoints.length; ++i) {
            curvePoints[i] = new java.util.ArrayList<>();
            curvePoints[i].add(new float[]{0f, 0f});
            curvePoints[i].add(new float[]{1f, 1f});
        }
        host.onReady();
        return new ViewPanel(panel, lockNote, chips);
    }

    private static int dip(float density, float v) { return Math.round(v * density); }
}
