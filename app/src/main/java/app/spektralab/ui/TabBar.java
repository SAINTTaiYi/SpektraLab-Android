package app.spektralab.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The six top-level tabs; visual state is a function of the active index. */
public final class TabBar {
    public interface Host {
        String label(int index);
        String compositionDescription();
        void onClick(int index);
        TextView createLabel(String text, int color);
    }

    public final View root;
    private final View[] bars;
    private final TextView[] labels;

    public TabBar(Context c, Host host, int count, float density) {
        final LinearLayout tabs = new LinearLayout(c);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(UiPalette.PAPER);
        bars = new View[count];
        labels = new TextView[count];
        for (int i = 0; i < count; ++i) {
            final int index = i;
            final FrameLayout cell = new FrameLayout(c);
            final TextView name = host.createLabel(host.label(i), UiPalette.MUTED);
            if (i == 4) name.setContentDescription(host.compositionDescription());
            name.setGravity(android.view.Gravity.CENTER);
            cell.addView(name, new FrameLayout.LayoutParams(-1, -1));
            final View bar = new View(c);
            bar.setBackgroundColor(UiPalette.BLUE);
            bar.setVisibility(View.INVISIBLE);
            cell.addView(bar, new FrameLayout.LayoutParams(
                    Math.round(26 * density), Math.round(3 * density),
                    android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL));
            cell.setOnClickListener(v -> host.onClick(index));
            tabs.addView(cell, new LinearLayout.LayoutParams(0, Math.round(58 * density), 1f));
            bars[i] = bar;
            labels[i] = name;
        }
        root = tabs;
    }

    public void select(int active) {
        for (int i = 0; i < bars.length; ++i) {
            bars[i].setVisibility(i == active ? View.VISIBLE : View.INVISIBLE);
            if (labels[i] != null) {
                labels[i].setTextColor(i == active ? UiPalette.BLUE : UiPalette.MUTED);
            }
        }
    }
}
