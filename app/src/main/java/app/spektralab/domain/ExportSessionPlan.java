package app.spektralab.domain;

/** Pure decision about the preview session while a full-size export runs. */
public final class ExportSessionPlan {
    private ExportSessionPlan() {}

    /** 5.1 planes of 12 B/px plus encoder tables, measured at 20.9 MP. */
    public static long workingSetBytes(int width, int height) {
        return (long) width * height * 12L * 51L / 10L + (160L << 20);
    }

    /**
     * Close the preview session only when a fresh export session exists, the
     * batch loop is not about to reopen it, and suspension failed or the
     * working set will not fit in what is left.
     */
    public static boolean closePreview(boolean freshOpened, boolean batchExport,
                                       boolean suspended, long needBytes, long available) {
        if (!freshOpened || batchExport) return false;
        if (!suspended) return true;
        return available > 0 && needBytes > available * 55L / 100L;
    }
}
