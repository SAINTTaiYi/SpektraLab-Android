package app.spektralab.ui;

import app.spektralab.ui.UiPalette;
import app.spektralab.ui.HistogramView;

public final class CurveView extends android.view.View {
        // **Room for the numbers.** The plot used to sit 10 dp from every edge,
        // which left nowhere to print an axis: the curve was a shape with no
        // scale, and "is this point at 128 or at 140" could only be guessed.
        // The left gutter carries the output axis, the bottom one the input.
        private int dragging = -1;

        /** The Activity owns the curve points; the View owns the gesture. */
        public interface Delegate {
            java.util.List<float[]> points(int channel);
            int channel();
            HistogramView histogram();
            android.graphics.Typeface typeface();
            void onLive();
            void onCommit();
        }

        private final Delegate delegate;
        private final int padL, padR, padT, padB;

        public CurveView(android.content.Context c, Delegate delegate) {
            super(c);
            this.delegate = delegate;
            final float d = c.getResources().getDisplayMetrics().density;
            padL = Math.round(26 * d); padR = Math.round(8 * d);
            padT = Math.round(8 * d); padB = Math.round(16 * d);
        }

        private android.graphics.PointF toScreen(float x, float y) {
            final float w = getWidth() - padL - padR, h = getHeight() - padT - padB;
            return new android.graphics.PointF(padL + x * w, padT + (1f - y) * h);
        }

        private float[] fromScreen(float px, float py) {
            final float w = Math.max(1, getWidth() - padL - padR), h = Math.max(1, getHeight() - padT - padB);
            return new float[]{Math.max(0, Math.min(1, (px - padL) / w)),
                               Math.max(0, Math.min(1, 1f - (py - padT) / h))};
        }

        /**
         * The photograph's own distribution, behind the curve.
         *
         * <p>Every real curve editor puts it there for one reason: the curve is
         * a decision about <em>this</em> picture's tones, and a bare grid says
         * nothing about where those tones are. Only the channel being edited is
         * drawn -- three traces for RGB, one for 亮度 and for R/G/B -- and all
         * of it stays low and translucent so the curve and its handles remain
         * the brightest thing in the box.
         */
        private void drawHistogramBackdrop(android.graphics.Canvas canvas, float plotW, float plotH) {
            if (delegate.histogram() == null || delegate.histogram().samples <= 1) return;
            switch (delegate.channel()) {
                case 1:
                    drawHistogramTrace(canvas, delegate.histogram().ys, 0xFFFFFFFF, 0x34, plotW, plotH);
                    return;
                case 2:
                    drawHistogramTrace(canvas, delegate.histogram().rs, HistogramView.RED, 0x58, plotW, plotH);
                    return;
                case 3:
                    drawHistogramTrace(canvas, delegate.histogram().gs, HistogramView.GREEN, 0x58, plotW, plotH);
                    return;
                case 4:
                    drawHistogramTrace(canvas, delegate.histogram().bs, HistogramView.BLUE, 0x58, plotW, plotH);
                    return;
                default:
                    // RGB: three traces at a third of the weight, so their
                    // overlap whitens where all three are strong -- the same
                    // read as the floating histogram, minus its plate.
                    drawHistogramTrace(canvas, delegate.histogram().rs, HistogramView.RED, 0x40, plotW, plotH);
                    drawHistogramTrace(canvas, delegate.histogram().gs, HistogramView.GREEN, 0x40, plotW, plotH);
                    drawHistogramTrace(canvas, delegate.histogram().bs, HistogramView.BLUE, 0x40, plotW, plotH);
            }
        }

        private void drawHistogramTrace(android.graphics.Canvas canvas, int[] bins, int color,
                                        int alpha, float plotW, float plotH) {
            final int w = Math.max(8, Math.round(plotW));
            final int[] heights = new int[w];
            int max = 1;
            for (int i = 0; i < w; ++i) {
                final int a = i * 256 / w, e = Math.max(a + 1, (i + 1) * 256 / w);
                int v = 0;
                for (int k = a; k < e && k < 256; ++k) v = Math.max(v, bins[k]);
                heights[i] = v;
                if (v > max) max = v;
            }
            // Same normalisation as the floating histogram: 1.5x the 97th
            // percentile, floored at max/8. A lone spike may reach the top; it
            // may not flatten the other 255 bins into the bottom row.
            final int[] sorted = heights.clone();
            java.util.Arrays.sort(sorted);
            final int cut = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.97f))];
            final int norm = Math.max(Math.max(1, max / 8),
                    Math.min(max, Math.max(1, cut) * 3 / 2));
            final float bottom = padT + plotH;
            final float top = padT + dip(1);
            final android.graphics.Path p = new android.graphics.Path();
            p.moveTo(padL, bottom);
            for (int i = 0; i < w; ++i) {
                final float t = Math.min(1f, heights[i] / (float) norm);
                p.lineTo(padL + i, bottom - t * (bottom - top));
            }
            p.lineTo(padL + plotW, bottom);
            p.close();
            // **A vertical fade, not a flat wash.** Top-weighted is how a
            // histogram reads as a shape rather than as a coloured block, and
            // the fade keeps the bottom -- where the curve's shadow and the
            // 0..64 tones live -- quiet enough that the orange curve stays the
            // brightest line in the box.
            final int rgb = color & 0x00FFFFFF;
            final android.graphics.Paint fill =
                    new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            fill.setStyle(android.graphics.Paint.Style.FILL);
            fill.setShader(new android.graphics.LinearGradient(0, top, 0, bottom,
                    rgb | (alpha << 24), rgb | ((alpha / 3) << 24),
                    android.graphics.Shader.TileMode.CLAMP));
            canvas.drawPath(p, fill);
            // A crisp top edge. A translucent fill alone reads as a smudge,
            // and the curve needs a clear silhouette underneath it.
            final android.graphics.Paint edge =
                    new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(android.graphics.Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, dip(0.7f)));
            edge.setColor(color);
            edge.setAlpha(Math.min(255, alpha * 2));
            p.reset();
            for (int i = 0; i < w; ++i) {
                final float t = Math.min(1f, heights[i] / (float) norm);
                final float x = padL + i, y = bottom - t * (bottom - top);
                if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            canvas.drawPath(p, edge);
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            // **The tones first, the decision on top of them.** The backdrop
            // shares this view's x axis, so a control point sits directly over
            // the tones it is about to move.
            drawHistogramBackdrop(canvas, Math.max(1f, getWidth() - padL - padR),
                    Math.max(1f, getHeight() - padT - padB));
            final android.graphics.Paint grid = new android.graphics.Paint();
            grid.setColor(UiPalette.LINE);
            grid.setStrokeWidth(1f);
            grid.setStyle(android.graphics.Paint.Style.STROKE);
            for (int i = 1; i < 4; ++i) {
                final android.graphics.PointF a = toScreen(i / 4f, 0f), b = toScreen(i / 4f, 1f);
                canvas.drawLine(a.x, a.y, b.x, b.y, grid);
                final android.graphics.PointF c = toScreen(0f, i / 4f), d = toScreen(1f, i / 4f);
                canvas.drawLine(c.x, c.y, d.x, d.y, grid);
            }
            // The plot's own frame: without it the ticks float next to nothing.
            final android.graphics.Paint frame = new android.graphics.Paint();
            frame.setColor(UiPalette.LINE);
            frame.setStrokeWidth(1f);
            frame.setStyle(android.graphics.Paint.Style.STROKE);
            canvas.drawRect(padL, padT, padL + (getWidth() - padL - padR),
                    padT + (getHeight() - padT - padB), frame);

            // **The axis values, in the units the user reads everywhere else.**
            // The curve is applied to 8-bit levels, so 0/64/128/192/255 is the
            // scale a photographer expects; a bare grid is not.
            final android.graphics.Paint tick = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            tick.setColor(UiPalette.MUTED);
            tick.setTextSize(dip(7));
            tick.setTypeface(delegate.typeface());
            final int[] levels = {0, 64, 128, 192, 255};
            for (int i = 0; i < levels.length; ++i) {
                final float t = i / 4f;
                final android.graphics.PointF y = toScreen(0f, t);
                tick.setTextAlign(android.graphics.Paint.Align.RIGHT);
                canvas.drawText(String.valueOf(levels[i]), padL - dip(3), y.y + dip(2.5f), tick);
                final android.graphics.PointF x = toScreen(t, 0f);
                // The two end labels align to the plot's own edges; centring
                // them would push half a glyph outside the view.
                if (i == 0) {
                    tick.setTextAlign(android.graphics.Paint.Align.LEFT);
                    canvas.drawText(String.valueOf(levels[i]), dip(1), getHeight() - dip(4), tick);
                } else if (i == levels.length - 1) {
                    tick.setTextAlign(android.graphics.Paint.Align.RIGHT);
                    canvas.drawText(String.valueOf(levels[i]), getWidth() - dip(1), getHeight() - dip(4), tick);
                } else {
                    tick.setTextAlign(android.graphics.Paint.Align.CENTER);
                    canvas.drawText(String.valueOf(levels[i]), x.x, getHeight() - dip(4), tick);
                }
            }

            final java.util.List<float[]> pts = delegate.points(delegate.channel());
            final android.graphics.Paint line = new android.graphics.Paint();
            line.setColor(UiPalette.BLUE);
            line.setStrokeWidth(dip(2));
            line.setStyle(android.graphics.Paint.Style.STROKE);
            final android.graphics.Path path = new android.graphics.Path();
            for (int i = 0; i <= 64; ++i) {
                final float x = i / 64f;
                final android.graphics.PointF p = toScreen(x, (float) curveAt(pts, x));
                if (i == 0) path.moveTo(p.x, p.y); else path.lineTo(p.x, p.y);
            }
            canvas.drawPath(path, line);
            final android.graphics.Paint dot = new android.graphics.Paint();
            dot.setColor(UiPalette.INK);
            for (float[] q : pts) {
                final android.graphics.PointF p = toScreen(q[0], q[1]);
                canvas.drawRect(p.x - dip(4), p.y - dip(4), p.x + dip(4), p.y + dip(4), dot);
            }
            // **The point under the finger, in numbers.** Dragging a dot moved a
            // shape; the readout is what makes it a curve editor rather than a
            // drawing. Shown only while dragging, top right, so it never sits
            // on top of the curve the user is aiming at.
            if (dragging >= 0 && dragging < pts.size()) {
                final int in = Math.round(pts.get(dragging)[0] * 255f);
                final int out = Math.round(pts.get(dragging)[1] * 255f);
                final android.graphics.Paint read = new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
                read.setColor(UiPalette.BLUE);
                read.setTextSize(dip(8));
                read.setTypeface(delegate.typeface());
                read.setTextAlign(android.graphics.Paint.Align.RIGHT);
                canvas.drawText(in + " → " + out, getWidth() - padR, padT + dip(9), read);
            }
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            final java.util.List<float[]> pts = delegate.points(delegate.channel());
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    dragging = -1;
                    float best = dip(26);
                    for (int i = 0; i < pts.size(); ++i) {
                        final android.graphics.PointF p = toScreen(pts.get(i)[0], pts.get(i)[1]);
                        final float d = (float) Math.hypot(p.x - e.getX(), p.y - e.getY());
                        if (d < best) { best = d; dragging = i; }
                    }
                    if (dragging < 0 && pts.size() < 8) {
                        final float[] q = fromScreen(e.getX(), e.getY());
                        int at = 0;
                        while (at < pts.size() && pts.get(at)[0] < q[0]) ++at;
                        pts.add(at, q);
                        dragging = at;
                        invalidate();
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (dragging < 0 || dragging >= pts.size()) return true;
                    final float[] q = fromScreen(e.getX(), e.getY());
                    final float lo = dragging > 0 ? pts.get(dragging - 1)[0] + 0.005f : 0f;
                    final float hi = dragging < pts.size() - 1 ? pts.get(dragging + 1)[0] - 0.005f : 1f;
                    pts.get(dragging)[0] = Math.max(lo, Math.min(hi, q[0]));
                    pts.get(dragging)[1] = q[1];
                    invalidate();
                    // `advance`-free: the curve is already in `pts`, and
                    // `applyParams` pushes it from there.
                    delegate.onLive();
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    if (dragging >= 0) {
                        delegate.onCommit();
                    }
                    dragging = -1;
                    return true;
                }
                default:
                    return super.onTouchEvent(e);
            }
        }
            private float dip(float v) { return v * getResources().getDisplayMetrics().density; }
        private static double curveAt(java.util.List<float[]> pts, double x) {
        final int n = pts.size();
        if (n < 2) return x;
        if (x <= pts.get(0)[0]) return pts.get(0)[1];
        if (x >= pts.get(n - 1)[0]) return pts.get(n - 1)[1];
        final int segs = n - 1;
        final double[] d = new double[segs], m = new double[n];
        for (int i = 0; i < segs; ++i)
            d[i] = (pts.get(i + 1)[1] - pts.get(i)[1]) / Math.max(pts.get(i + 1)[0] - pts.get(i)[0], 1e-6);
        m[0] = d[0];
        m[segs] = d[segs - 1];
        for (int i = 1; i < segs; ++i)
            m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2;
        for (int i = 0; i < segs; ++i) {
            if (d[i] == 0) continue;
            final double a = m[i] / d[i], b = m[i + 1] / d[i], s2 = a * a + b * b;
            if (s2 > 9) { final double t = 3 / Math.sqrt(s2); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]; }
        }
        int i = 0;
        while (i < n - 2 && x > pts.get(i + 1)[0]) ++i;
        final double h = Math.max(pts.get(i + 1)[0] - pts.get(i)[0], 1e-6), t = (x - pts.get(i)[0]) / h;
        final double t2 = t * t, t3 = t2 * t;
        final double v = (2 * t3 - 3 * t2 + 1) * pts.get(i)[1] + (t3 - 2 * t2 + t) * h * m[i]
                + (-2 * t3 + 3 * t2) * pts.get(i + 1)[1] + (t3 - t2) * h * m[i + 1];
        return Math.max(0, Math.min(1, v));
    }
}
