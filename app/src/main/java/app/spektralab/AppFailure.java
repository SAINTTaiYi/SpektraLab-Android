package app.spektralab;

/** Classification for the three failures a user can act on. */
final class AppFailure extends RuntimeException {
    enum Stage { IMPORT, RENDER, EXPORT, OTHER }

    final Stage stage;

    private AppFailure(Stage stage, Throwable cause) {
        super(cause == null ? null : cause.getMessage(), cause);
        this.stage = stage;
    }

    static AppFailure of(Stage stage, Throwable cause) {
        return new AppFailure(stage, cause);
    }

    /** Derive a coarse stage from the dialog title, for the diagnostic row. */
    static Stage fromTitle(String title) {
        if (title == null) return Stage.OTHER;
        if (title.contains("导入") || title.contains("RAW")) return Stage.IMPORT;
        if (title.contains("渲染")) return Stage.RENDER;
        if (title.contains("导出")) return Stage.EXPORT;
        return Stage.OTHER;
    }
}
