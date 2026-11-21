package app.spektralab;

/** Pure memory-budget maths, kept free of Android so it can be tested on a JVM. */
final class PixelBudget {
    /** The old measured ceiling; now only the fallback when RAM cannot be read. */
    static final long HARD_PIXEL_CAP = 42_000_000L;
    /** 1.5 GiB reserve for the app's host side and the rest of the phone. */
    private static final long RESERVE = 1536L << 20;
    /** 12 B/px decode + 4 B/px ARGB + 76 B/px of live GPU planes. */
    private static final long BYTES_PER_PIXEL = 12L * 7 + 16;

    private PixelBudget() {}

    static long maxPixelsByMemory(long availableBytes) {
        if (availableBytes <= 0) return HARD_PIXEL_CAP;
        return Math.max(0, availableBytes - RESERVE) / BYTES_PER_PIXEL;
    }
}
