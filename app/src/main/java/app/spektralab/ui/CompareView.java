package app.spektralab.ui;

import app.spektralab.DebugSink;
import app.spektralab.Diagnostics;
import app.spektralab.ui.UiPalette;
import android.graphics.Bitmap;

public final class CompareView extends android.view.View {
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.RectF dst = new android.graphics.RectF();
        /** Scratch for the turned picture's own rectangle; `dst` stays the frame. */
        private final android.graphics.RectF frameRect = new android.graphics.RectF();
        private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        private final float slop;
        private final float density;
        private Bitmap before, after;
        private boolean holding, dragging;
        private float split, downX, downY;
        // **The canvas zooms.** A 20 MP frame is inspected at fit size only
        // down to the whole picture; pinch says "let me see that corner".
        // Everything stays in `dst`, so the crop frame and any other overlay
        // that asks `imageRect()` follows the zoom for free.
        private float zoom = 1f, panX = 0f, panY = 0f;
        private boolean panning;
        private long lastTapMs = 0;
        /** **The divider is opt-in.** Any single-finger drag used to become
         *  a comparison split, which is what "一直在跟分割线抢手势" was: the
         *  canvas could not pan without dragging the line instead. Now the
         *  pill in the corner turns it on. */
        private boolean splitMode = false;
        /** 0 off, 1 thirds + centre cross. Set by 构图 → 旋转 only. */
        private int gridMode = 0;

        /**
         * **Rotation is a drag on the picture, not a slider.**
         *
         * <p>The dial was better than a seekbar and still the wrong instrument:
         * levelling a horizon is done by looking at the horizon. So while the
         * rotate page is in front, one finger turns the picture under itself,
         * exactly as it does in the editor this is modelled on. The transform
         * is a `Canvas.rotate` on the view, not a render -- a real re-resample
         * costs a second on a normal frame and fails outright on a 128 MiB
         * device -- and the committed angle is applied once, on release.
         */
        private boolean rotateMode = false;
        /** How far the composition stands back while the rotate page is open. */
        private static final float ROTATE_SHRINK = 0.86f;
        private float previewRotation = 0f;
        private float rotLast = Float.NaN;
        private float rotTotal = 0f;
        private RotateSink rotateSink;

        public interface RotateSink {
            /** A finger went down: remember the committed angle to fall back to. */
            void onRotateStart();
            /** Live, this drag's total in degrees. Do not render from here. */
            void onRotateDelta(float totalDeg);
            /** Finger up: commit. */
            void onRotateCommit(float totalDeg);
            /** Double tap on the picture. */
            void onRotateReset();
        }

        public void setRotateSink(RotateSink sink) { rotateSink = sink; }

        public void setRotateMode(boolean on) {
            if (rotateMode == on) return;
            rotateMode = on;
            rotLast = Float.NaN;
            if (!on) {
                previewRotation = 0f;
                rotTotal = 0f;
            }
            invalidate();
        }

        /** Cleared once the rendered frame already carries the rotation. */
        public void setPreviewRotation(float deg) {
            if (Math.abs(previewRotation - deg) < 0.001f) return;
            previewRotation = deg;
            invalidate();
        }

        /** Screen angle of a point about the picture's centre. */
        private float angleAboutPicture(float x, float y) {
            final float cx = dst.centerX(), cy = dst.centerY();
            return (float) Math.toDegrees(Math.atan2(x - cx, cy - y));
        }

        /**
         * **A grab near the middle turns the picture slowly, not wildly.**
         *
         * <p>`atan2` about the centre is the right *direction* and the wrong
         * *gain*: the angle swept by a finger is `arc / r`, so the same ten
         * pixels that mean one degree at the edge of the frame mean forty near
         * the middle. Every editor that lets you turn the picture by hand
         * either damps this or makes you grab the outside -- Cropper.js v2 gets
         * away with the raw ratio because it measures the angle *between two
         * fingers*, and a pair of fingers is never at r = 0.
         *
         * <p>So the swept angle is scaled by `r / rMin` below `rMin`, which is
         * exactly "treat the finger as if it were on a circle of radius rMin":
         * past that radius nothing changes at all, and inside it the picture
         * still moves, just at the same rate a hand on the rim would get. The
         * floor keeps a drag that starts dead centre from freezing.
         */
        private float rotationGain(float x, float y) {
            if (dst.width() <= 0f || dst.height() <= 0f) return 1f;
            final float dx = x - dst.centerX(), dy = y - dst.centerY();
            final float r = (float) Math.hypot(dx, dy);
            final float rMin = Math.max(density * 40f,
                    Math.min(dst.width(), dst.height()) * 0.18f);
            if (r >= rMin) return 1f;
            return Math.max(0.15f, r / rMin);
        }

        // **Pinch, by hand.** `ScaleGestureDetector` refuses to start until the
        // fingers are `getScaledMinimumScalingSpan()` apart -- about 27 mm on
        // this class of phone -- and that value is not configurable. Holding
        // two fingers closer than that (the natural way to pinch a phone) did
        // nothing at all: not the zoom, and not the fallback either, because
        // `isInProgress()` stayed false and the switch below has no
        // multi-pointer branch any more. So the span is measured here.
        private boolean pinching = false;
        private float pinchStartSpan = 0f, pinchStartZoom = 1f;
        private float pinchStartCx = 0f, pinchStartCy = 0f;
        private float pinchStartPanX = 0f, pinchStartPanY = 0f;
        // **The platform's detector, not a hand-rolled one.** Tracking
        // pointer ids by hand got the sequence wrong: the pinch never moved
        // the picture and the leftover finger became a comparison drag.
        // `ScaleGestureDetector` owns that state and reports a factor.

        /** Retired: the long press lives on the pill now. Kept so the
         *  `removeCallbacks` calls stay valid, never posted. */
        private final Runnable press = new Runnable() {
            @Override public void run() { }
        };

        /** **The pill owns "show me the original".** A press on the picture
         *  used to arm a 500 ms timer that fired in the middle of every pinch,
         *  which is the "一直跟长按对比功能冲突" report. */
        public void setHolding(boolean on) {
            final boolean wanted = on && before != null && after != null;
            if (holding == wanted) return;
            holding = wanted;
            invalidate();
        }

        public CompareView(android.content.Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            slop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
        }

        /** Two fingers down: remember the span, the centre and where we were. */
        private void beginPinch(android.view.MotionEvent e) {
            if (e.getPointerCount() < 2) return;
            pinchStartSpan = Math.max(1f, twoFingerSpan(e));
            pinchStartZoom = zoom;
            pinchStartCx = (e.getX(0) + e.getX(1)) / 2f;
            pinchStartCy = (e.getY(0) + e.getY(1)) / 2f;
            pinchStartPanX = panX;
            pinchStartPanY = panY;
            pinching = true;
            rotLast = Float.NaN;
            handler.removeCallbacks(press);
            holding = false;
            dragging = false;
            panning = false;
            split = 0f;
        }

        private void updatePinch(android.view.MotionEvent e) {
            if (e.getPointerCount() < 2 || pinchStartSpan <= 1f) return;
            final float span = Math.max(1f, twoFingerSpan(e));
            final float want = Math.max(0.2f, Math.min(8f, pinchStartZoom * span / pinchStartSpan));
            // Keep the point between the fingers under the fingers.
            final float cx = (e.getX(0) + e.getX(1)) / 2f;
            final float cy = (e.getY(0) + e.getY(1)) / 2f;
            final float k = want / Math.max(1e-4f, pinchStartZoom);
            panX = (pinchStartPanX - (pinchStartCx - getWidth() / 2f)) * k + (cx - getWidth() / 2f);
            panY = (pinchStartPanY - (pinchStartCy - getHeight() / 2f)) * k + (cy - getHeight() / 2f);
            zoom = want;
            if (zoom <= 1.005f) { panX = 0f; panY = 0f; }
            clampPan();
            invalidate();
        }

        private void endPinch() {
            if (!pinching) return;
            pinching = false;
            pinchStartSpan = 0f;
        }

        private float twoFingerSpan(android.view.MotionEvent e) {
            return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
        }

        public void setBefore(Bitmap b) { before = b; invalidate(); }
        public void setAfter(Bitmap b) { after = b; invalidate(); }

        public boolean isSplitMode() { return splitMode; }

        public float zoomLevel() { return zoom; }
        public float panXLevel() { return panX; }
        public float panYLevel() { return panY; }

        /** Set the view outright; the caller has already worked out the pan. */
        public void setView(float z, float px, float py) {
            zoom = Math.max(0.2f, Math.min(8f, z));
            panX = px;
            panY = py;
            if (zoom <= 1.005f) { panX = 0f; panY = 0f; }
            clampPan();
            invalidate();
        }

        public void setSplitMode(boolean on) {
            splitMode = on;
            if (!on) split = 0f;
            invalidate();
        }

        public void clear() {
            before = null;
            after = null;
            holding = false;
            dragging = false;
            split = 0f;
            splitMode = false;
            zoom = 1f;
            panX = 0f;
            panY = 0f;
            invalidate();
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            final Bitmap base = after != null ? after : before;
            if (base == null) return;
            drawFit(canvas, base);
            drawGrid(canvas);
            if (before == null || after == null) return;
            if (holding) {
                drawFit(canvas, before);
            } else if (split > 0f) {
                final float x = split * getWidth();
                canvas.save();
                canvas.clipRect(0f, 0f, x, getHeight());
                drawFit(canvas, before);
                canvas.restore();
                drawDivider(canvas, x);
            }
            if (rotateMode) drawCropFrame(canvas);
        }

        /**
         * **The crop frame, drawn over the picture that overflows it.**
         *
         * <p>Two passes, like the grid: a dark halo then a white hairline, so
         * the frame survives a white dress and a black suit without a shadow
         * layer. The corner brackets are the part that reads at a glance --
         * they say "this rectangle, not the picture's own edge".
         */
        private void drawCropFrame(android.graphics.Canvas canvas) {
            if (dst.width() <= 0f || dst.height() <= 0f) return;
            final float r = density * 3f;
            final android.graphics.Paint p = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.SQUARE);
            for (int pass = 0; pass < 2; ++pass) {
                p.setColor(pass == 0 ? 0x4D000000 : 0xE6FFFFFF);
                p.setStrokeWidth(pass == 0 ? density * 3.4f : density * 1.4f);
                canvas.drawRoundRect(dst, r, r, p);
            }
            // Corner brackets: a quarter-frame's worth at each corner, drawn
            // over the hairline so they read as handles rather than a border.
            final float arm = Math.min(dpC(26f), Math.min(dst.width(), dst.height()) * 0.18f);
            p.setColor(0xFFFFFFFF);
            p.setStrokeWidth(density * 3f);
            final float l = dst.left, t = dst.top, rr = dst.right, b = dst.bottom;
            canvas.drawLine(l, t + r, l + arm, t + r, p);          // ↖ horizontal
            canvas.drawLine(l + r, t, l + r, t + arm, p);          // ↖ vertical
            canvas.drawLine(rr - arm, t + r, rr, t + r, p);        // ↗
            canvas.drawLine(rr - r, t, rr - r, t + arm, p);
            canvas.drawLine(l, b - r, l + arm, b - r, p);          // ↙
            canvas.drawLine(l + r, b - arm, l + r, b, p);
            canvas.drawLine(rr - arm, b - r, rr, b - r, p);        // ↘
            canvas.drawLine(rr - r, b - arm, rr - r, b, p);
        }

        /** dp inside the view, where the outer `dp()` is not in scope. */
        private float dpC(float v) { return v * density; }

        /** The rectangle the picture occupies on screen, for overlays. */
        public android.graphics.RectF imageRect() { return new android.graphics.RectF(dst); }

        /** **A grid you can level a horizon against.** Drawn inside the picture
         *  rectangle, so it follows zoom and pan for free. */
        public void setGrid(int mode) {
            if (gridMode == mode) return;
            gridMode = mode;
            invalidate();
        }

        private void drawGrid(android.graphics.Canvas canvas) {
            if (gridMode == 0 || dst.width() <= 0f || dst.height() <= 0f) return;
            final float l = dst.left, t = dst.top, r = dst.right, b = dst.bottom;
            final float hair = Math.max(1f, density);
            // Two passes: a dark halo first, then a light hairline. On a white
            // sky a pure-white line disappears; on a black shadow a pure-black
            // one does. The pair reads on both.
            for (int pass = 0; pass < 2; ++pass) {
                final android.graphics.Paint p = new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setColor(pass == 0 ? 0x38000000 : 0x88FFFFFF);
                p.setStrokeWidth(pass == 0 ? hair * 3f : hair);
                for (int i = 1; i <= 2; ++i) {
                    final float x = l + (r - l) * i / 3f;
                    final float y = t + (b - t) * i / 3f;
                    canvas.drawLine(x, t, x, b, p);
                    canvas.drawLine(l, y, r, y, p);
                }
                // Centre cross, a touch stronger than the thirds.
                p.setColor(pass == 0 ? 0x44000000 : 0xA6FFFFFF);
                final float cx = (l + r) / 2f, cy = (t + b) / 2f;
                canvas.drawLine(cx, t, cx, b, p);
                canvas.drawLine(l, cy, r, cy, p);
            }
        }

        private void drawFit(android.graphics.Canvas canvas, Bitmap bmp) {
            final float vw = getWidth(), vh = getHeight();
            if (vw <= 0 || vh <= 0 || bmp.getWidth() == 0) return;
            // **The rotate page stands back a little.** A turned picture is
            // bigger than the frame it has to cover, and the whole point of the
            // frame is to show how much of the picture is being thrown away:
            // that only reads if the picture's own edges are on screen. So the
            // composition shrinks here, and returns to full size on the next
            // page -- which is also what the editor in the reference does.
            final float scale = Math.min(vw / bmp.getWidth(), vh / bmp.getHeight()) * zoom
                    * (rotateMode ? ROTATE_SHRINK : 1f);
            final float w = bmp.getWidth() * scale, h = bmp.getHeight() * scale;
            final float left = (vw - w) / 2f + panX, top = (vh - h) / 2f + panY;
            dst.set(left, top, left + w, top + h);
            if (previewRotation == 0f) {
                canvas.drawBitmap(bmp, null, dst, paint);
                return;
            }
            // **The picture grows as it turns, and is clipped to the frame.**
            // Rotating a rectangle inside its own rectangle leaves four empty
            // triangles; every editor answers by pushing the frame in until
            // the picture covers it. The engine does the same arithmetic on
            // the same numbers (`resolve_geometry_frame`, the `cover` term),
            // which is what makes the last frame of the drag and the first
            // frame of the render the same picture.
            final float grow = scale * coverScale(bmp.getWidth(), bmp.getHeight(), previewRotation);
            final float gw = bmp.getWidth() * grow, gh = bmp.getHeight() * grow;
            final float cx = dst.centerX(), cy = dst.centerY();
            frameRect.set(cx - gw / 2f, cy - gh / 2f, cx + gw / 2f, cy + gh / 2f);
            // **No clip.** The overflow *is* the information: the corners of a
            // turned picture are what the crop frame is cutting away, and
            // hiding them makes the frame look like a decoration instead of a
            // decision. The view's own bounds are the only limit.
            final int save = canvas.save();
            canvas.rotate(previewRotation, cx, cy);
            canvas.drawBitmap(bmp, null, frameRect, paint);
            canvas.restoreToCount(save);
        }

        /**
         * **How much the picture has to grow so a turned frame is still full.**
         *
         * <p>The bbox of the turned rectangle over the rectangle itself, taken
         * on the axis that needs more: 1.65x at 45 degrees on a 3:4 frame, and
         * exactly 1 at rest, where the whole path is skipped.
         */
        private float coverScale(float w, float h, float deg) {
            if (w <= 0f || h <= 0f) return 1f;
            final double a = Math.toRadians(deg);
            final double c = Math.abs(Math.cos(a)), s = Math.abs(Math.sin(a));
            return (float) Math.max(c + s * (h / w), c + s * (w / h));
        }

        /** Keep the picture from being dragged off its own frame. */
        private void clampPan() {
            final Bitmap base = after != null ? after : before;
            if (base == null || base.getWidth() == 0) return;
            final float vw = getWidth(), vh = getHeight();
            if (vw <= 0 || vh <= 0) return;
            final float scale = Math.min(vw / base.getWidth(), vh / base.getHeight()) * zoom;
            final float w = base.getWidth() * scale, h = base.getHeight() * scale;
            final float maxX = Math.max(0f, (w - vw) / 2f);
            final float maxY = Math.max(0f, (h - vh) / 2f);
            panX = Math.max(-maxX, Math.min(maxX, panX));
            panY = Math.max(-maxY, Math.min(maxY, panY));
        }

        private float span(android.view.MotionEvent e) {
            return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
        }

        /** Back to fit. Double-tap, or call it when a frame is replaced. */
        public void resetView() {
            zoom = 1f; panX = 0f; panY = 0f;
            invalidate();
        }

        private void drawDivider(android.graphics.Canvas canvas, float x) {
            final float r = 6f * density, cy = getHeight() / 2f;
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(0xB3F1F1EE);
            canvas.drawRect(x - 0.5f, 0f, x + 0.5f, getHeight(), paint);
            paint.setColor(0xFFF1F1EE);
            canvas.drawRect(x - r, cy - r, x + r, cy + r, paint);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, density));
            paint.setColor(UiPalette.dark() ? 0xFFFFFFFF : 0xFF1C2AA5);
            canvas.drawRect(x - r, cy - r, x + r, cy + r, paint);
            paint.setStyle(android.graphics.Paint.Style.FILL);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            // Zooming only needs *a* picture; the before/after divider needs
            // both, and says so where it uses them.
            if (after == null && before == null) return false;

            final int action = e.getActionMasked();

            // **The second finger starts the pinch immediately.** No slop, no
            // minimum span: two fingers on the glass is the whole condition.
            if (action == android.view.MotionEvent.ACTION_POINTER_DOWN
                    && e.getPointerCount() >= 2) {
                beginPinch(e);
                try { DebugSink.text("zoom", "pinch begin span=" + Math.round(pinchStartSpan)); }
                catch (Throwable spkIgnored) { Diagnostics.ignored("getActionMasked:10217", spkIgnored); }
                return true;
            }
            if (pinching) {
                if (action == android.view.MotionEvent.ACTION_MOVE) {
                    updatePinch(e);
                    return true;
                }
                if (action == android.view.MotionEvent.ACTION_POINTER_UP) {
                    endPinch();
                    try { DebugSink.text("zoom", "pinch end at " + String.format(java.util.Locale.US, "%.2f", zoom)); }
                    catch (Throwable spkIgnored) { Diagnostics.ignored("getActionMasked:10228", spkIgnored); }
                    return true;
                }
                if (action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL) {
                    endPinch();
                    lastTapMs = 0;
                    return true;
                }
                return true;
            }

            // **One finger turns the picture while the rotate page is open.**
            // Two fingers still zoom: `beginPinch` ran above and set
            // `pinching`, which this branch never sees.
            if (rotateMode && e.getPointerCount() == 1) {
                switch (action) {
                    case android.view.MotionEvent.ACTION_DOWN: {
                        final long now = android.os.SystemClock.uptimeMillis();
                        if (now - lastTapMs < 320L) {
                            rotLast = Float.NaN;
                            previewRotation = 0f;
                            if (rotateSink != null) rotateSink.onRotateReset();
                            lastTapMs = 0L;
                            invalidate();
                            return true;
                        }
                        lastTapMs = now;
                        rotTotal = 0f;
                        rotLast = angleAboutPicture(e.getX(), e.getY());
                        if (rotateSink != null) rotateSink.onRotateStart();
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_MOVE: {
                        if (Float.isNaN(rotLast)) return true;
                        final float cur = angleAboutPicture(e.getX(), e.getY());
                        float d = cur - rotLast;
                        while (d > 180f) d -= 360f;
                        while (d < -180f) d += 360f;
                        rotLast = cur;
                        // Damped: see `rotationGain`. The accumulator still
                        // integrates the finger's own sweep, so a drag that
                        // wanders in and out of the middle has no seam.
                        rotTotal += d * rotationGain(e.getX(), e.getY());
                        if (rotateSink != null) {
                            // The host owns the limit and the preview angle: the
                            // committed angle may already be 10°, so ±45 has to
                            // be applied against *that*, not against this drag.
                            rotateSink.onRotateDelta(rotTotal);
                        } else {
                            previewRotation = rotTotal;
                            invalidate();
                        }
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL: {
                        // **A released drag is not half a double tap.** This
                        // flag was only ever set on DOWN and cleared inside the
                        // pinch branch, so letting go and touching the picture
                        // again within 320 ms -- exactly what someone does when
                        // the first try looks wrong -- counted as a double tap
                        // and silently zeroed the angle.
                        lastTapMs = 0L;
                        if (Float.isNaN(rotLast)) return true;
                        rotLast = Float.NaN;
                        if (rotateSink != null) rotateSink.onRotateCommit(rotTotal);
                        return true;
                    }
                    default:
                        break;
                }
            }

            switch (action) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    dragging = false;
                    holding = false;
                    panning = false;
                    // no long press on the picture: the pill owns it
                    return true;
                case android.view.MotionEvent.ACTION_POINTER_DOWN:
                case android.view.MotionEvent.ACTION_POINTER_UP:
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    // **The divider only moves while its pill is lit.**
                    if (splitMode) {
                        if (before == null || after == null) return true;
                        if (!dragging) {
                            if (Math.abs(e.getX() - downX) < slop && Math.abs(e.getY() - downY) < slop) return true;
                            dragging = true;
                            handler.removeCallbacks(press);
                        }
                        split = Math.max(0.02f, Math.min(0.98f, e.getX() / Math.max(1, getWidth())));
                        invalidate();
                        return true;
                    }
                    // **One finger pans only once there is something to pan.**
                    if (zoom > 1.01f) {
                        if (!panning) {
                            if (Math.abs(e.getX() - downX) < slop && Math.abs(e.getY() - downY) < slop) return true;
                            panning = true;
                            handler.removeCallbacks(press);
                        }
                        panX += e.getX() - downX;
                        panY += e.getY() - downY;
                        downX = e.getX();
                        downY = e.getY();
                        clampPan();
                        invalidate();
                        return true;
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP: {
                    handler.removeCallbacks(press);
                    final long now = android.os.SystemClock.uptimeMillis();
                    if (!splitMode && !dragging && !panning && !holding && split == 0f && now - lastTapMs < 320) {
                        resetView();
                        lastTapMs = 0;
                    } else {
                        lastTapMs = (dragging || panning || holding) ? 0 : now;
                    }
                    if (holding) {
                        holding = false;
                        invalidate();
                    } else if (!dragging && split > 0f) {
                        // A tap closes it: the divider is a gesture, not a mode.
                        split = 0f;
                        invalidate();
                    }
                    dragging = false;
                    panning = false;
                    return true;
                }
                case android.view.MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(press);
                    holding = false;
                    dragging = false;
                    panning = false;
                    invalidate();
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }
    }
