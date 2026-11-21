package app.spektralab.ui;

import app.spektralab.ui.UiPalette;
import android.graphics.Typeface;

public final class RotateScale extends android.view.View {
        /** Degrees of value the arc spans across its own width. */
        private static final float SPAN_DEG = 44f;

        private final android.graphics.Paint ink =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint labelPaint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private float angle = 0f;
        private float downX = 0f;
        private boolean dragging = false;

        /** The Activity keeps the geometry state; the View only reports deltas. */
        public interface Delegate {
            void onDragStart();
            void onPreview(float deltaDegrees);
            void onCommit(float deltaDegrees);
        }

        private final Delegate delegate;
        private final float density;

        public RotateScale(android.content.Context c, android.graphics.Typeface typeface,
                           Delegate delegate) {
            super(c);
            this.delegate = delegate;
            density = c.getResources().getDisplayMetrics().density;
            ink.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            labelPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
            labelPaint.setTypeface(typeface);
        }

        public void setAngle(float deg) {
            if (Math.abs(angle - deg) < 0.005f) return;
            angle = deg;
            invalidate();
        }

        /** One pixel is a fortieth of a degree at the usual width. */
        private float pxPerDeg() {
            return Math.max(1f, getWidth() / SPAN_DEG);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    delegate.onDragStart();
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (!dragging) return true;
                    // **Relative to where the finger landed**, not to the last
                    // event: a scrubber that integrates its own deltas drifts
                    // against the refresh rate, and this one is calibrated in
                    // pixels.
                    delegate.onPreview((e.getX() - downX) / pxPerDeg());
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    if (dragging) {
                        dragging = false;
                        delegate.onCommit((e.getX() - downX) / pxPerDeg());
                    }
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            final float w = getWidth(), h = getHeight();
            if (w <= 0f || h <= 0f) return;
            final float cx = w / 2f;
            final float baseY = h - dip(10);
            final float halfW = w / 2f - dip(3);
            final double halfRad = Math.toRadians(SPAN_DEG / 2.0);
            final float r = (float) (halfW / Math.sin(halfRad));
            final float cy = baseY - r;

            // Ticks live on the value, not on the screen: tick `k` is the mark
            // for `k` degrees, so it slides as `angle` moves and the ones every
            // five stay the ones every five.
            // **The ticks that are in view are the ones near the value, not the
            // ones near zero.** Iterating -22..22 emptied the scale the moment
            // the angle passed the width of the arc: at 45 degrees every tick
            // worth drawing was in the 20s and 30s, outside the loop.
            final int from = (int) Math.ceil(angle - SPAN_DEG / 2f) - 1;
            final int to = (int) Math.floor(angle + SPAN_DEG / 2f) + 1;
            for (int k = from; k <= to; ++k) {
                final float rel = k - angle;
                if (Math.abs(rel) > SPAN_DEG / 2f) continue;
                final double phi = Math.toRadians(rel);
                final float x = cx + (float) (r * Math.sin(phi));
                final float y = cy + (float) (r * Math.cos(phi));
                if (k % 5 == 0) {
                    ink.setColor(UiPalette.INK);
                    ink.setAlpha(190);
                    ink.setStrokeWidth(dip(1.6f));
                    final float len = dip(7);
                    canvas.drawLine(x, y,
                            x + (float) (len * Math.sin(phi)),
                            y + (float) (len * Math.cos(phi)), ink);
                } else {
                    ink.setColor(UiPalette.INK);
                    ink.setAlpha(120);
                    canvas.drawCircle(x, y, dip(1.1f), ink);
                }
            }

            // The pointer: fixed at the middle, longer than any tick, pointing
            // away from the centre the way the ruler does.
            ink.setColor(UiPalette.INK);
            ink.setAlpha(235);
            ink.setStrokeWidth(dip(1.8f));
            canvas.drawLine(cx, baseY, cx, baseY + dip(7), ink);

            labelPaint.setColor(UiPalette.INK);
            labelPaint.setTextSize(dip(13));
            canvas.drawText(String.format(java.util.Locale.US, "%.2f°", angle),
                    cx, baseY - dip(20), labelPaint);
        }
            private float dip(float v) { return v * density; }
    }
