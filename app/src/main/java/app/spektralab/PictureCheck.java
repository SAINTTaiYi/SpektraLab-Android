package app.spektralab;

import java.util.Locale;

/**
 * Is this a photograph, or is it a colour?
 *
 * <p>The engine has two ways to fail and only one of them throws. A refused
 * parameter comes back as a sentence with a range in it, which {@link SelfHeal}
 * reads. A render that <em>succeeds</em> and hands back a frame of one flat
 * value comes back as success -- no exception, no error code, a bitmap with
 * plausible dimensions -- and reaches the canvas, the film card and the export
 * as if it were the picture. Two of those are in the records: SL-261118-7167
 * (`mean16=14040`, 22 of 32 bands exactly 0.000) and the 26 MP frame that
 * rendered byte-identically four times.
 *
 * <p>So the check is on the answer, not on the call. A 32x32 grid is sampled
 * and three numbers are read off it: the spread of the whole grid, of each row,
 * and of each column. A photograph fails all three by an order of magnitude.
 *
 * <h3>Measured</h3>
 * <pre>
 *                                  overall  rowStd  colStd
 *   real photographs (n=6)         44–61   25.8–49.5  25.9–54.8
 *   one colour                      0.00    0.00       0.00
 *   one colour per row            55.40    0.00      55.40   <- the 458 shape
 *   one colour per column         55.40   55.40       0.00
 * </pre>
 *
 * <p>The thresholds sit at 3.0 and 2.0 -- an order of magnitude below the
 * smallest real value and above the largest broken one. This is a detector for
 * "the engine produced a constant", not an aesthetic judgement: a picture of a
 * white wall still has grain, and grain is several levels.
 */
public final class PictureCheck {

    private PictureCheck() {}

    /** Sampling grid. 32x32 = 1024 points, which is 2–4 ms on a phone. */
    public static final int GRID = 32;

    /** Below this, the whole grid is one value. Real minimum measured: 26.2. */
    public static final double FLAT_OVERALL = 1.5;
    /**
     * Below this, every row (or every column) is one value. Real minimum
     * measured: 25.8 -- so the margin is a factor of 50. The broken shape is
     * exactly 0.00, and the only pictures that come close are horizontal
     * gradients, which still carry grain.
     */
    public static final double FLAT_LINE = 0.5;

    public static final class Result {
        public final double overall;
        public final double rowStd;
        public final double colStd;
        /** null when this looks like a picture; otherwise what it looks like. */
        public final String verdict;

        Result(double overall, double rowStd, double colStd, String verdict) {
            this.overall = overall;
            this.rowStd = rowStd;
            this.colStd = colStd;
            this.verdict = verdict;
        }

        public boolean ok() { return verdict == null; }

        public String describe() {
            return String.format(Locale.US, "%s (spread %.2f, rows %.2f, cols %.2f)",
                    verdict, overall, rowStd, colStd);
        }

        /** For the log: the numbers alone, whether or not it passed. */
        public String numbers() {
            return String.format(Locale.US, "spread %.2f rows %.2f cols %.2f",
                    overall, rowStd, colStd);
        }
    }

    /**
     * @param grid {@link #GRID}×{@link #GRID} ARGB samples, row-major.
     */
    public static Result of(int[] grid) {
        final int n = GRID;
        if (grid == null || grid.length < n * n) {
            return new Result(0, 0, 0, null);   // nothing to judge; do not convict
        }
        final double[] v = new double[n * n];
        for (int i = 0; i < v.length; ++i) {
            final int p = grid[i];
            // Luma only. Every broken shape here is a constant in all three
            // channels at once, and luma is the cheapest thing that sees it.
            v[i] = 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF);
        }

        double sum = 0, sum2 = 0;
        for (double x : v) { sum += x; sum2 += x * x; }
        final double mean = sum / v.length;
        final double overall = Math.sqrt(Math.max(0, sum2 / v.length - mean * mean));

        double rowAcc = 0;
        for (int gy = 0; gy < n; ++gy) {
            double s = 0, s2 = 0;
            for (int gx = 0; gx < n; ++gx) { final double x = v[gy * n + gx]; s += x; s2 += x * x; }
            final double m = s / n;
            rowAcc += Math.sqrt(Math.max(0, s2 / n - m * m));
        }
        final double rowStd = rowAcc / n;

        double colAcc = 0;
        for (int gx = 0; gx < n; ++gx) {
            double s = 0, s2 = 0;
            for (int gy = 0; gy < n; ++gy) { final double x = v[gy * n + gx]; s += x; s2 += x * x; }
            final double m = s / n;
            colAcc += Math.sqrt(Math.max(0, s2 / n - m * m));
        }
        final double colStd = colAcc / n;

        String verdict = null;
        if (overall < FLAT_OVERALL) verdict = "one colour";
        else if (rowStd < FLAT_LINE) verdict = "one colour per row";
        else if (colStd < FLAT_LINE) verdict = "one colour per column";
        return new Result(overall, rowStd, colStd, verdict);
    }
}
