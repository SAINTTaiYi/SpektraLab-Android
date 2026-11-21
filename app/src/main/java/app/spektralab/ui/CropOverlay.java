package app.spektralab.ui;

import app.spektralab.ui.UiPalette;

public final class CropOverlay extends android.view.View {
        private final android.graphics.RectF image = new android.graphics.RectF();
        private float nx = 0f, ny = 0f, nw = 1f, nh = 1f;
        private float aspect = 0f;                 // picture w/h; 0 = free
        private int drag = 0;                      // 0 none, 1 move, 2 TL, 3 TR, 4 BL, 5 BR
        private float downX, downY, sx, sy, sw, sh;
        /** True while the last drag actually held the card's ratio. */
        private boolean ratioHeld;

        /** The Activity owns crop state; the View owns the gesture maths. */
        public interface Delegate {
            android.graphics.RectF imageRect();
            boolean onTouchCompare(android.view.MotionEvent e);
            void onCommit(float x, float y, float w, float h, boolean ratioHeld, int drag);
        }

        private final Delegate delegate;
        private final float density;

        public CropOverlay(android.content.Context c, Delegate delegate) {
            super(c);
            this.delegate = delegate;
            density = c.getResources().getDisplayMetrics().density;
        }

        public void sync(float x, float y, float w, float h, float a) {
            nx = x; ny = y; nw = w; nh = h; aspect = a;
            invalidate();
        }

        private void pullImage() {
            final android.graphics.RectF rect = delegate.imageRect();
            if (rect != null) image.set(rect);
        }

        private float px(float n) { return image.left + n * image.width(); }
        private float py(float n) { return image.top + n * image.height(); }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            pullImage();
            if (image.isEmpty() || image.width() <= 0f) return;

            final float l = px(nx), t = py(ny), r = px(nx + nw), b = py(ny + nh);

            final android.graphics.Paint dim = new android.graphics.Paint();
            dim.setColor(0x99000000);
            canvas.drawRect(0, 0, getWidth(), t, dim);
            canvas.drawRect(0, b, getWidth(), getHeight(), dim);
            canvas.drawRect(0, t, l, b, dim);
            canvas.drawRect(r, t, getWidth(), b, dim);

            final android.graphics.Paint edge =
                    new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(android.graphics.Paint.Style.STROKE);
            edge.setStrokeWidth(dip(1.4f));
            edge.setColor(0xFFFFFFFF);
            canvas.drawRect(l, t, r, b, edge);

            final android.graphics.Paint thirds =
                    new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            thirds.setStyle(android.graphics.Paint.Style.STROKE);
            thirds.setStrokeWidth(dip(0.8f));
            thirds.setColor(0x66FFFFFF);
            for (int i = 1; i < 3; ++i) {
                final float x = l + (r - l) * i / 3f;
                final float y = t + (b - t) * i / 3f;
                canvas.drawLine(x, t, x, b, thirds);
                canvas.drawLine(l, y, r, y, thirds);
            }

            final android.graphics.Paint grip =
                    new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            grip.setStyle(android.graphics.Paint.Style.STROKE);
            grip.setStrokeWidth(dip(3f));
            grip.setColor(0xFFFFFFFF);
            final float arm = Math.min(dip(22), Math.min(r - l, b - t) * 0.28f);
            canvas.drawLine(l, t, l + arm, t, grip);
            canvas.drawLine(l, t, l, t + arm, grip);
            canvas.drawLine(r, t, r - arm, t, grip);
            canvas.drawLine(r, t, r, t + arm, grip);
            canvas.drawLine(l, b, l + arm, b, grip);
            canvas.drawLine(l, b, l, b - arm, grip);
            canvas.drawLine(r, b, r - arm, b, grip);
            canvas.drawLine(r, b, r, b - arm, grip);
        }

        private int cornerAt(float x, float y) {
            final float tolerance = dip(30);
            final float l = px(nx), t = py(ny), r = px(nx + nw), b = py(ny + nh);
            if (Math.hypot(x - l, y - t) < tolerance) return 2;
            if (Math.hypot(x - r, y - t) < tolerance) return 3;
            if (Math.hypot(x - l, y - b) < tolerance) return 4;
            if (Math.hypot(x - r, y - b) < tolerance) return 5;
            if (x > l && x < r && y > t && y < b) return 1;
            return 0;
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            pullImage();
            if (image.width() <= 0f) return false;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    final int hit = cornerAt(e.getX(), e.getY());
                    if (hit == 0) return false;
                    drag = hit;
                    downX = e.getX(); downY = e.getY();
                    sx = nx; sy = ny; sw = nw; sh = nh;
                    // A box that already has a card's shape starts out held;
                    // a free / "整幅" box starts out not.
                    ratioHeld = aspect > 0.0001f;
                    final android.view.ViewParent parent = getParent();
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                case android.view.MotionEvent.ACTION_POINTER_DOWN:
                    // **A second finger is a pinch, and returning false here
                    // would not hand it over** -- this view already owns the
                    // gesture, and Android does not re-dispatch to a sibling.
                    // So the pinch is forwarded explicitly.
                    drag = 0;
                    return delegate.onTouchCompare(e);
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (e.getPointerCount() > 1) return delegate.onTouchCompare(e);
                    if (drag == 0) return false;
                    applyDrag(e.getX(), e.getY());
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    if (e.getPointerCount() > 1) return delegate.onTouchCompare(e);
                    if (drag != 0) {
                        // **commit first, clear afterwards.** `commitCrop`
                        // needs to know whether this was a move or a corner
                        // drag to decide if the ratio card still owns the box.
                        commitCrop();
                    } else {
                        delegate.onTouchCompare(e);     // close a pinch cleanly
                    }
                    return true;
                }
            }
            return false;
        }

        private void applyDrag(float x, float y) {
            final float dxN = (x - downX) / Math.max(1f, image.width());
            final float dyN = (y - downY) / Math.max(1f, image.height());
            final float min = 0.04f;

            if (drag == 1) {
                nx = Math.max(0f, Math.min(1f - sw, sx + dxN));
                ny = Math.max(0f, Math.min(1f - sh, sy + dyN));
            } else {
                // The opposite corner is the anchor.
                final boolean left = (drag == 2 || drag == 4);
                final boolean up = (drag == 2 || drag == 3);
                final float ax = left ? sx + sw : sx;
                final float ay = up ? sy + sh : sy;
                float mx = left ? sx + dxN : sx + sw + dxN;
                float my = up ? sy + dyN : sy + sh + dyN;
                mx = Math.max(0f, Math.min(1f, mx));
                my = Math.max(0f, Math.min(1f, my));

                float w = Math.abs(mx - ax);
                float h = Math.abs(my - ay);
                if (aspect > 0.0001f && image.width() > 0f) {
                    // The ratio is in picture pixels; normalised space is
                    // stretched by the picture's own aspect.
                    final float aspN = aspect * (image.height() / image.width());
                    if (w / Math.max(1e-5f, h) > aspN) h = w / aspN; else w = h * aspN;
                    // **Shrink both sides together at the picture edge.**
                    // Clamping w and h independently here (what the old code
                    // did) changes the ratio the moment a drag reaches an
                    // edge -- which is how a fixed-ratio card silently turned
                    // into a custom box without any other card being tapped.
                    final float roomW = left ? ax : 1f - ax;
                    final float roomH = up ? ay : 1f - ay;
                    if (roomW > 1e-3f && roomH > 1e-3f) {
                        if (w > roomW && w > 1e-5f) { h *= roomW / w; w = roomW; }
                        if (h > roomH && h > 1e-5f) { w *= roomH / h; h = roomH; }
                        if (w < min || h < min) {
                            final float lift = Math.max(min / Math.max(1e-5f, w),
                                    min / Math.max(1e-5f, h));
                            if (lift > 1f) { w *= lift; h *= lift; }
                        }
                        ratioHeld = true;
                    } else {
                        // The anchor sits against the edge: there is no room
                        // to hold the shape, so this box is honestly custom.
                        w = Math.max(min, Math.min(1f, w));
                        h = Math.max(min, Math.min(1f, h));
                        ratioHeld = false;
                    }
                } else {
                    w = Math.max(min, Math.min(1f, w));
                    h = Math.max(min, Math.min(1f, h));
                    ratioHeld = false;
                }
                w = Math.max(1e-5f, Math.min(1f, w));
                h = Math.max(1e-5f, Math.min(1f, h));

                nx = Math.max(0f, Math.min(1f - w, left ? ax - w : ax));
                ny = Math.max(0f, Math.min(1f - h, up ? ay - h : ay));
                nw = w; nh = h;
            }
            invalidate();
        }

        private void commitCrop() {
            delegate.onCommit(nx, ny, nw, nh, ratioHeld, drag);
            drag = 0;
        }

        public float dip(float v) { return v * density; }
    }
