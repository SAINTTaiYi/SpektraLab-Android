package app.spektralab.domain;

/** Dispatches an export to the right native method; no Android UI dependency. */
public final class ExportController {
    public ExportController() {}

    /** Returns {width, height, renderMs, totalMs} from the native writer. */
    public int[] export(SessionApi session, String format, String tier,
                        String path, int quality) {
        if (session == null || session.isClosed()) {
            throw new IllegalStateException("session is null or closed");
        }
        final ExportPlan plan = ExportPlan.forFormat(format);
        if (".dng".equals(plan.extension)) return session.exportDng(path, tier);
        if (".tif".equals(plan.extension)) return session.exportTiff(path, tier);
        return session.exportJpeg(path, quality, 512, tier);
    }
}
