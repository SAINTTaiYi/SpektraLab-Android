package app.spektralab.ui;

import app.spektralab.ui.UiPalette;
import android.graphics.Bitmap;

public final class ColorWheel extends android.view.View {
        private Bitmap disc;
        private int discSize = 0;
        private int dragZone = -1;
        /** 1 = the rim (hue only), 2 = the handle (saturation only). */
        private int dragMode = 0;

        /** The Activity owns the four grading zones; the View owns gestures. */
        public interface Delegate {
            float hue(int zone);
            float sat(int zone);
            int zone();
            void setHue(int zone, float value);
            void setSat(int zone, float value);
            void onValueChanged();
            void onLive();
            void onCommit();
        }

        private final Delegate delegate;
        private final float density;

        public ColorWheel(android.content.Context c, Delegate delegate) {
            super(c);
            this.delegate = delegate;
            density = c.getResources().getDisplayMetrics().density;
        }

        private float radius() { return Math.min(getWidth(), getHeight()) / 2f - dip(6); }

        private void buildDisc(int size) {
            disc = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            final float r = size / 2f;
            final int[] px = new int[size * size];
            final float[] hsv = new float[3];
            for (int y = 0; y < size; ++y) {
                for (int x = 0; x < size; ++x) {
                    final float dx = x - r, dy = y - r;
                    final float d = (float) Math.hypot(dx, dy) / r;
                    if (d > 1f) { px[y * size + x] = 0; continue; }
                    float hue = (float) Math.toDegrees(Math.atan2(-dy, dx));
                    if (hue < 0) hue += 360f;
                    hsv[0] = hue;
                    hsv[1] = d;
                    hsv[2] = 1f;
                    final int c = android.graphics.Color.HSVToColor(hsv);
                    // Fade the rim out rather than leaving a hard circle edge.
                    final int a = (int) (255 * Math.min(1f, (1f - d) * 24f));
                    px[y * size + x] = (c & 0x00FFFFFF) | (a << 24);
                }
            }
            disc.setPixels(px, 0, size, 0, 0, size, size);
            discSize = size;
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            final int size = (int) (radius() * 2f);
            if (size <= 0) return;
            if (disc == null || discSize != size) buildDisc(size);
            final float cx = getWidth() / 2f, cy = getHeight() / 2f;
            canvas.drawBitmap(disc, cx - radius(), cy - radius(), null);
            final float hue = (float) Math.toRadians(delegate.hue(delegate.zone()));
            final float sat = Math.max(0f, Math.min(1f, delegate.sat(delegate.zone())));

            // **The radius is a track, so draw it.** A dashed line from the
            // centre out along the current hue is what makes "slide this dot
            // inward for less colour" legible; without it the handle looks
            // like it is floating.
            final android.graphics.Paint track = new android.graphics.Paint();
            track.setStyle(android.graphics.Paint.Style.STROKE);
            track.setStrokeWidth(dip(1));
            track.setColor(0x55FFFFFF);
            track.setPathEffect(new android.graphics.DashPathEffect(new float[]{dip(3), dip(4)}, 0f));
            canvas.drawLine(cx, cy,
                    cx + (float) Math.cos(hue) * radius(), cy - (float) Math.sin(hue) * radius(), track);

            // The rim handle: hue, at full saturation, so its own colour says
            // which hue it is.
            final float hx = cx + (float) Math.cos(hue) * radius();
            final float hy = cy - (float) Math.sin(hue) * radius();
            final android.graphics.Paint rimFill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            rimFill.setColor(android.graphics.Color.HSVToColor(new float[]{
                    (delegate.hue(delegate.zone()) + 360f) % 360f, 1f, 1f}));
            canvas.drawCircle(hx, hy, dip(8), rimFill);
            final android.graphics.Paint rimEdge = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            rimEdge.setStyle(android.graphics.Paint.Style.STROKE);
            rimEdge.setStrokeWidth(dip(1.6f));
            rimEdge.setColor(0xFFFFFFFF);
            canvas.drawCircle(hx, hy, dip(8), rimEdge);

            // The inner handle: saturation, hollow, wherever the radius says.
            final float px = cx + (float) Math.cos(hue) * radius() * sat;
            final float py = cy - (float) Math.sin(hue) * radius() * sat;
            final android.graphics.Paint hole = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            hole.setStyle(android.graphics.Paint.Style.FILL);
            hole.setColor(0x33000000);
            canvas.drawCircle(px, py, dip(9), hole);
            final android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(android.graphics.Paint.Style.STROKE);
            ring.setStrokeWidth(dip(2));
            ring.setColor(UiPalette.INK);
            canvas.drawCircle(px, py, dip(9), ring);
            final android.graphics.Paint ring2 = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            ring2.setStyle(android.graphics.Paint.Style.STROKE);
            ring2.setStrokeWidth(dip(1));
            ring2.setColor(0xAAFFFFFF);
            canvas.drawCircle(px, py, dip(9), ring2);

            final android.graphics.Paint centre = new android.graphics.Paint();
            centre.setStyle(android.graphics.Paint.Style.STROKE);
            centre.setStrokeWidth(dip(1));
            centre.setColor(UiPalette.LINE);
            canvas.drawCircle(cx, cy, dip(4), centre);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    // **A touch that starts outside the disc belongs to the
                    // list.** It used to grab the wheel anyway, so scrolling
                    // the panel threw the grading handle wherever the finger
                    // happened to pass.
                    final float cx = getWidth() / 2f, cy = getHeight() / 2f;
                    final float d = (float) Math.hypot(e.getX() - cx, e.getY() - cy);
                    if (d > radius() + dip(12)) return false;
                    // **Two rings, two jobs.** The outer band is the hue; the
                    // disc inside it is saturation along that hue's radius.
                    // One drag used to move both, so "change the hue" quietly
                    // changed the strength as well.
                    // **The panel scrolls, and a scroll takes the gesture away.**
                    // Without this, moving a finger past the touch slop made the
                    // enclosing `CappedScroll` intercept the stream: the wheel
                    // got ACTION_CANCEL instead of ACTION_MOVE, and its cancel
                    // branch is the one that renders -- so the wheel "rendered
                    // the moment it was touched" and could not be dragged at
                    // all. The bar already does this; the wheel never did.
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    dragZone = delegate.zone();
                    dragMode = d > radius() * 0.84f ? 1 : 2;
                    return applyWheelTouch(e, dragMode);
                }
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (dragZone != delegate.zone() || dragMode == 0) return false;
                    final boolean handled = applyWheelTouch(e, dragMode);
                    if (handled) delegate.onLive();
                    return handled;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    if (dragZone >= 0) {
                        dragZone = -1;
                        dragMode = 0;
                        delegate.onCommit();
                    }
                    return dragZone == -1;
                }
                default:
                    return super.onTouchEvent(e);
            }
        }

        private boolean applyWheelTouch(android.view.MotionEvent e, int mode) {
            final float cx = getWidth() / 2f, cy = getHeight() / 2f;
            final float dx = e.getX() - cx, dy = cy - e.getY();
            final float d = (float) Math.hypot(dx, dy);
            if (mode == 1) {
                float hue = (float) Math.toDegrees(Math.atan2(dy, dx));
                if (hue < 0) hue += 360f;
                delegate.setHue(delegate.zone(), hue > 180f ? hue - 360f : hue);
            } else {
                // Distance only: the hue stays where the rim put it, so a
                // slightly-off drag inward cannot swing the colour.
                delegate.setSat(delegate.zone(), Math.max(0f, Math.min(1f, d / Math.max(1f, radius()))));
            }
            delegate.onValueChanged();
            invalidate();
            return true;
        }
            private float dip(float v) { return v * density; }
    }
