package app.spektralab.domain;

/** FNV-1a over a sampled int[] — the hash used by live/preview diagnostic logs. */
public final class PixelHash {
    private PixelHash() {}

    public static long fnv1a(int[] pixels, int size, int step) {
        long h = 1469598103934665603L;
        if (pixels == null) return h;
        final int n = Math.min(size, pixels.length);
        for (int i = 0; i < n; i += step) { h ^= pixels[i]; h *= 1099511628211L; }
        return h;
    }
}
