package app.spektralab.ui;

import android.graphics.Bitmap;

public final class HistogramView extends android.view.View {
        /** A tenth of a percent of the samples: below this it is noise. */
        private static final double CLIP_SHARE = 0.001;
        /** The three traces' colours, shared with the curve page's backdrop. */
        public static final int RED = 0xFFFF5A4E, GREEN = 0xFF47D97A, BLUE = 0xFF4E9BFF;

        public final int[] r = new int[256], g = new int[256], b = new int[256];
        public final int[] rs = new int[256], gs = new int[256], bs = new int[256];
        /** Rec.709 luma: the 亮度 curve edits this, not the three channels. */
        public final int[] y = new int[256], ys = new int[256];
        public int clipLo, clipHi, samples = 1;
        /** Told when the bins moved, so the curve page's backdrop repaints too. */
        private Runnable onChanged;
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final android.graphics.Path plate = new android.graphics.Path();
        private final android.graphics.RectF box = new android.graphics.RectF();
        private final boolean screenBlend;

        public HistogramView(android.content.Context c) {
            super(c);
            // Paint.setBlendMode arrived in API 29; before that the fills fall
            // back to straight alpha, which is duller but never wrong.
            screenBlend = android.os.Build.VERSION.SDK_INT >= 29;
        }

        public void setOnChanged(Runnable r) { onChanged = r; }

        private void changed() {
            invalidate();
            if (onChanged != null) onChanged.run();
        }

        public void setBitmap(Bitmap bm) {
            if (bm == null || bm.getWidth() < 8 || bm.getHeight() < 8) { applyBins(null); return; }
            // A fixed sample budget. 40 000 samples resolve 256 bins as well
            // as 3 M do; walking a full 12 MP bitmap would cost 48 MB and
            // stall the UI thread the moment a render finishes.
            final double step = Math.max(1.0,
                    Math.sqrt((double) bm.getWidth() * bm.getHeight() / 40000.0));
            final int w = Math.max(8, (int) Math.round(bm.getWidth() / step));
            final int h = Math.max(8, (int) Math.round(bm.getHeight() / step));
            final Bitmap small = (w == bm.getWidth() && h == bm.getHeight())
                    ? bm : Bitmap.createScaledBitmap(bm, w, h, true);
            final int[] px = new int[w * h];
            small.getPixels(px, 0, w, 0, 0, w, h);
            if (small != bm) small.recycle();
            applyBins(computeBins(px, w, h));
        }

        /**
         * The same bins from a frame's ARGB array, with a strided sample and no
         * Bitmap in between.
         *
         * <p>The floating histogram only moved when a full 2048 render finished,
         * so dragging a slider left both it and the curve page's backdrop frozen
         * on the previous picture -- "两个直方图都不能实时预览". A live frame
         * already holds its pixels (`copyArgbRange` wrote them), so counting them
         * directly is a few hundred microseconds instead of a scaled bitmap.
         *
         * <p>Returns `{samples, clipLo, clipHi, r[256], g[256], b[256], y[256]}`;
         * null when there is nothing to count. Static and allocation-light so the
         * render thread can call it without touching a View.
         */
        public static int[] computeBins(int[] px, int w, int h) {
            if (px == null || w < 8 || h < 8 || px.length < w * h) return null;
            final int[] out = new int[3 + 4 * 256];
            final int stride = Math.max(1,
                    (int) Math.round(Math.sqrt((double) w * h / 40000.0)));
            int n = 0, lo = 0, hi = 0;
            for (int yy = 0; yy < h; yy += stride) {
                final int row = yy * w;
                for (int xx = 0; xx < w; xx += stride) {
                    final int p = px[row + xx];
                    final int ri = (p >> 16) & 255, gi = (p >> 8) & 255, bi = p & 255;
                    ++out[3 + ri];
                    ++out[3 + 256 + gi];
                    ++out[3 + 512 + bi];
                    ++out[3 + 768 + Math.max(0, Math.min(255,
                            (ri * 2126 + gi * 7152 + bi * 722) / 10000))];
                    if (ri == 0 || gi == 0 || bi == 0) ++lo;
                    if (ri == 255 || gi == 255 || bi == 255) ++hi;
                    ++n;
                }
            }
            out[0] = Math.max(1, n);
            out[1] = lo;
            out[2] = hi;
            return out;
        }

        /** Install a bin set produced by `computeBins` (UI thread). */
        public void applyBins(int[] packed) {
            java.util.Arrays.fill(r, 0);
            java.util.Arrays.fill(g, 0);
            java.util.Arrays.fill(b, 0);
            java.util.Arrays.fill(y, 0);
            clipLo = 0;
            clipHi = 0;
            samples = 1;
            if (packed == null || packed.length < 3 + 4 * 256) { changed(); return; }
            samples = Math.max(1, packed[0]);
            clipLo = packed[1];
            clipHi = packed[2];
            System.arraycopy(packed, 3, r, 0, 256);
            System.arraycopy(packed, 259, g, 0, 256);
            System.arraycopy(packed, 515, b, 0, 256);
            System.arraycopy(packed, 771, y, 0, 256);
            smooth(r, rs);
            smooth(g, gs);
            smooth(b, bs);
            smooth(y, ys);
            changed();
        }

        /** 1-2-1: a one-bin spike becomes a peak several columns wide. */
        private static void smooth(int[] in, int[] out) {
            out[0] = (in[0] * 3 + in[1]) / 4;
            for (int i = 1; i < 255; ++i) out[i] = (in[i - 1] + in[i] * 2 + in[i + 1]) / 4;
            out[255] = (in[255] * 3 + in[254]) / 4;
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            final int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            final float d = getResources().getDisplayMetrics().density;

            // A plate of its own, rather than a flat 30% wash: over a white
            // sky the traces used to disappear into the photograph.
            box.set(0, 0, w, h);
            plate.reset();
            plate.addRoundRect(box, 3f * d, 3f * d, android.graphics.Path.Direction.CW);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(0xB3000000);
            c.drawPath(plate, paint);

            c.save();
            c.clipPath(plate);

            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            paint.setColor(0x1FFFFFFF);
            for (int i = 1; i < 4; ++i) {
                final float x = w * i / 4f;
                c.drawLine(x, 0, x, h, paint);
            }
            paint.setColor(0x14FFFFFF);
            c.drawLine(0, h * 0.5f, w, h * 0.5f, paint);

            if (screenBlend) paint.setBlendMode(android.graphics.BlendMode.SCREEN);
            drawChannel(c, rs, 0xFFFF5A4E, w, h);
            drawChannel(c, gs, 0xFF47D97A, w, h);
            drawChannel(c, bs, 0xFF4E9BFF, w, h);
            if (screenBlend) paint.setBlendMode(android.graphics.BlendMode.SRC_OVER);

            // The corner markers both good editors have. Filled = at least one
            // channel is losing samples at that end.
            final float m = 3.4f * d;
            paint.setStyle(android.graphics.Paint.Style.FILL);
            if (clipLo > CLIP_SHARE * samples) {
                paint.setColor(0xF2FFFFFF);
                path.reset();
                path.moveTo(0, h);
                path.lineTo(m * 1.7f, h);
                path.lineTo(0, h - m);
                path.close();
                c.drawPath(path, paint);
            }
            if (clipHi > CLIP_SHARE * samples) {
                paint.setColor(0xF2FFFFFF);
                path.reset();
                path.moveTo(w, h);
                path.lineTo(w - m * 1.7f, h);
                path.lineTo(w, h - m);
                path.close();
                c.drawPath(path, paint);
            }
            c.restore();

            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, 0.6f * d));
            paint.setColor(0x38FFFFFF);
            c.drawPath(plate, paint);
        }

        private void drawChannel(android.graphics.Canvas c, int[] bins, int color, int w, int h) {
            final int[] heights = new int[w];
            int max = 1;
            for (int x = 0; x < w; ++x) {
                final int a = x * 256 / w, e = Math.max(a + 1, (x + 1) * 256 / w);
                int v = 0;
                for (int i = a; i < e && i < 256; ++i) v = Math.max(v, bins[i]);
                heights[x] = v;
                if (v > max) max = v;
            }
            // Normalise on the tall columns, with a ceiling of 1.5x the 97th
            // percentile: a lone spike can reach the top, but it cannot press
            // the other 255 bins into the bottom pixel row.
            final int[] sorted = heights.clone();
            java.util.Arrays.sort(sorted);
            final int cut = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.97f))];
            // Floor of max/8 as well: if the 97th percentile lands on an empty
            // column (a synthetic or almost-empty channel), normalising on it
            // would clamp every column to the top and draw a solid block.
            final int norm = Math.max(Math.max(1, max / 8),
                    Math.min(max, Math.max(1, cut) * 3 / 2));

            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(color);
            path.reset();
            path.moveTo(0, h);
            for (int x = 0; x < w; ++x) {
                final float t = Math.min(1f, heights[x] / (float) norm);
                path.lineTo(x, h - t * (h - 2f));
            }
            path.lineTo(w, h);
            path.close();
            c.drawPath(path, paint);

            // A crisp edge on the trace: without it three translucent fills
            // read as fog rather than as three curves.
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            paint.setColor(color);
            path.reset();
            for (int x = 0; x < w; ++x) {
                final float t = Math.min(1f, heights[x] / (float) norm);
                final float y = h - t * (h - 2f);
                if (x == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            c.drawPath(path, paint);
        }
    }
