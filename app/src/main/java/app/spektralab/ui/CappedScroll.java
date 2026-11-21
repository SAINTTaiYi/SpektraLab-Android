package app.spektralab.ui;

import app.spektralab.ui.UiPalette;
import android.view.View;
import android.widget.ScrollView;

public final class CappedScroll extends android.widget.ScrollView {
        private final int cap;

        public CappedScroll(android.content.Context context, int cap) {
            super(context);
            this.cap = cap;
            setVerticalScrollBarEnabled(false);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            final int mode = MeasureSpec.getMode(heightSpec);
            final int size = MeasureSpec.getSize(heightSpec);
            final int limit = mode == MeasureSpec.EXACTLY ? Math.min(size, cap) : cap;
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
            // AT_MOST(cap) already hugs a shorter list; this only makes the
            // intent explicit if a future parent hands down EXACTLY.
            if (getMeasuredHeight() > cap) {
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY));
            }
        }
    }
