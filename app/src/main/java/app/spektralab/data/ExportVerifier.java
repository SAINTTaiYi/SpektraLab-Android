package app.spektralab.data;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/** Cheap checks on a finished export: does it look like the canvas at all? */
public final class ExportVerifier {
    private ExportVerifier() {}

    /** Grayscale NCC against the canvas, or -1 when it cannot be measured. */
    public static double matchesCanvas(String path, Bitmap canvas) {
        if (canvas == null || canvas.getWidth() <= 0) return -1;
        try {
            final BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, probe);
            if (probe.outWidth <= 0 || probe.outHeight <= 0) return -1;
            int sample = 1;
            while (Math.max(probe.outWidth, probe.outHeight) / (sample * 2) >= 200) sample *= 2;
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            final Bitmap file = BitmapFactory.decodeFile(path, options);
            if (file == null) return -1;
            final int gw = 48;
            final int gh = Math.max(8, Math.round(gw * (float) file.getHeight() / file.getWidth()));
            final Bitmap a = Bitmap.createScaledBitmap(file, gw, gh, true);
            final Bitmap b = Bitmap.createScaledBitmap(canvas, gw, gh, true);
            final int[] pa = new int[gw * gh], pb = new int[gw * gh];
            a.getPixels(pa, 0, gw, 0, 0, gw, gh);
            b.getPixels(pb, 0, gw, 0, 0, gw, gh);
            double ma = 0, mb = 0;
            for (int i = 0; i < pa.length; ++i) {
                ma += gray(pa[i]); mb += gray(pb[i]);
            }
            ma /= pa.length * 3.0;
            mb /= pb.length * 3.0;
            double num = 0, da = 0, db = 0;
            for (int i = 0; i < pa.length; ++i) {
                final double va = gray(pa[i]) - ma;
                final double vb = gray(pb[i]) - mb;
                num += va * vb; da += va * va; db += vb * vb;
            }
            if (da <= 1e-9 || db <= 1e-9) return -1;
            return num / Math.sqrt(da * db);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Flags a stretched or near-empty file; null when it looks structurally sane. */
    public static String inspect(String path) {
        try {
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            final Bitmap bm = BitmapFactory.decodeFile(path, options);
            if (bm == null) return "decode failed";
            final int w = bm.getWidth(), h = bm.getHeight();
            if (w < 64 || h < 64) { bm.recycle(); return null; }
            final int[] px = new int[w * h];
            bm.getPixels(px, 0, w, 0, 0, w, h);
            bm.recycle();
            long sx = 0, sy = 0, nx = 0, ny = 0;
            for (int y = 0; y < h; ++y) {
                for (int x = 0; x < w - 1; ++x) {
                    sx += Math.abs(channel(px[y * w + x], 16) - channel(px[y * w + x + 1], 16))
                            + Math.abs(channel(px[y * w + x], 8) - channel(px[y * w + x + 1], 8))
                            + Math.abs(channel(px[y * w + x], 0) - channel(px[y * w + x + 1], 0));
                    ++nx;
                }
            }
            for (int y = 0; y < h - 1; ++y) {
                for (int x = 0; x < w; ++x) {
                    sy += Math.abs(channel(px[y * w + x], 16) - channel(px[(y + 1) * w + x], 16))
                            + Math.abs(channel(px[y * w + x], 8) - channel(px[(y + 1) * w + x], 8))
                            + Math.abs(channel(px[y * w + x], 0) - channel(px[(y + 1) * w + x], 0));
                    ++ny;
                }
            }
            if (nx == 0 || ny == 0 || sy <= 0) return null;
            final double ratio = ((double) sx / nx) / ((double) sy / ny);
            if (ratio < 0.35 || ratio > 3.0) {
                return String.format(java.util.Locale.US,
                        "X/Y contrast %.2f (X %.1f, Y %.1f) at %dx%d",
                        ratio, (double) sx / nx, (double) sy / ny, w, h);
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static double gray(int p) {
        return ((p & 255) + ((p >> 8) & 255) + ((p >> 16) & 255)) / 3.0;
    }

    private static int channel(int p, int shift) { return (p >> shift) & 255; }
}
