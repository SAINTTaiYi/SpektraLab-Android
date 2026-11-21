package app.spektralab;

/** One place for failures that used to be swallowed without a trace. */
public final class Diagnostics {
    private Diagnostics() {}

    public static void ignored(String where, Throwable t) {
        try { DebugSink.text("ignored", where + ": " + brief(t)); }
        catch (Throwable ignored) { }
    }

    static String brief(Throwable t) {
        if (t == null) return "null";
        final StringBuilder b = new StringBuilder();
        for (Throwable c = t; c != null && b.length() < 300; c = c.getCause()) {
            if (b.length() > 0) b.append(" <- ");
            b.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
            if (c.getCause() == c) break;
        }
        return b.toString();
    }
}
