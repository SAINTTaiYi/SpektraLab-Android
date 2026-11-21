package app.spektralab.domain;

/** Pure downscale decision for an import; no Android or engine dependency. */
public final class ImportPlanner {
    public static final class Plan {
        public final double scale;
        public final int width;
        public final int height;

        public Plan(double scale, int width, int height) {
            this.scale = scale;
            this.width = width;
            this.height = height;
        }
    }

    private ImportPlanner() {}

    public static Plan plan(int sourceWidth, int sourceHeight, int capPixels, int edgeCap) {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException("source dimensions must be positive");
        }
        final long area = (long) sourceWidth * sourceHeight;
        final int longEdge = Math.max(sourceWidth, sourceHeight);
        double scale = 1.0;
        if (capPixels > 0 && area > capPixels) {
            scale = Math.min(scale, Math.sqrt((double) capPixels / area));
        }
        if (edgeCap > 0 && longEdge > edgeCap) {
            scale = Math.min(scale, (double) edgeCap / longEdge);
        }
        final int width = Math.max(1, (int) Math.round(sourceWidth * scale));
        final int height = Math.max(1, (int) Math.round(sourceHeight * scale));
        return new Plan(scale, width, height);
    }
}
