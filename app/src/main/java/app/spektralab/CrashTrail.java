package app.spektralab;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The last sixty-four things the app did, on disk, before it died.
 *
 * `installCrashLogger` catches Java exceptions, but the crashes that matter on
 * a phone are the ones that never reach a Java frame: a Vulkan driver that
 * takes the process down on an allocation or a dispatch leaves no stack, no
 * tombstone the user can copy, and -- since 1.4.x ships without a sink -- no
 * record at all.
 *
 * **One line was not enough.** A single "last step" narrows a crash to the
 * fifty lines between two marks, and when the answer is "the decoder died" or
 * "the kernel reclaimed us at the 600 MB allocation", fifty lines is still a
 * guess. So this keeps a ring: the last 64 steps, each with the seconds since
 * boot and the thread that wrote it. On the next launch the whole ring is
 * reported, and its last line is the step the process never came back from.
 *
 * Writes are throttled: a `commit` is a few milliseconds and a render can log
 * twenty steps a second, so consecutive marks inside the same window share one
 * commit. Steps that bracket something expensive (`markNow`) force it through,
 * because those are the ones worth losing nothing for.
 */
final class CrashTrail {
    private static final String FILE = "trail";
    private static final String KEY = "ring";
    private static final int CAP = 64;
    /** Marks closer together than this share one commit. */
    private static final long FLUSH_MS = 100L;

    private static final Object LOCK = new Object();
    private static final java.util.ArrayDeque<String> RING = new java.util.ArrayDeque<>();
    private static long lastFlush = 0L;

    private CrashTrail() {}

    /** Record a step. Cheap; may defer the write by up to {@link #FLUSH_MS}. */
    static void mark(Context context, String step) {
        record(context, step, false);
    }

    /**
     * Record a step and force it to disk.
     *
     * Use at the boundaries of anything that can kill the process -- a decode,
     * a 600 MB allocation, a Vulkan call -- where the step just before is the
     * whole point and a 100 ms window is 100 ms too much.
     */
    static void markNow(Context context, String step) {
        record(context, step, true);
    }

    private static void record(Context context, String step, boolean force) {
        if (context == null || step == null) return;
        final Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        final String line = android.os.SystemClock.elapsedRealtime() / 1000L + "s "
                + Thread.currentThread().getName() + " " + step;
        synchronized (LOCK) {
            RING.addLast(line);
            while (RING.size() > CAP) RING.removeFirst();
            final long now = android.os.SystemClock.uptimeMillis();
            if (!force && now - lastFlush < FLUSH_MS) return;
            lastFlush = now;
            flush(app);
        }
    }

    /** Caller holds {@link #LOCK}. */
    private static void flush(Context app) {
        try {
            final StringBuilder b = new StringBuilder(RING.size() * 56);
            for (String s : RING) b.append(s).append('\n');
            app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                    .edit().putString(KEY, b.toString()).commit();
        } catch (Throwable ignored) { }
    }

    /** The whole ring from the previous launch, or "" when it ended cleanly. */
    static String previous(Context context) {
        if (context == null) return "";
        try {
            return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "");
        } catch (Throwable e) {
            return "";
        }
    }

    /** The last step of the previous launch, or "" -- what the old trail was. */
    static String last(Context context) {
        final String all = previous(context);
        if (all.isEmpty()) return "";
        int end = all.length();
        while (end > 0 && all.charAt(end - 1) == '\n') end--;
        final int cut = all.lastIndexOf('\n', end - 1);
        return all.substring(cut + 1, end);
    }

    /**
     * The ring as it stands right now.
     *
     * For a user who wants to send it without waiting to crash -- "it hung for
     * forty seconds and then came back", "the export looked stripy" -- those
     * leave nothing on disk, because the process is still alive to write over
     * it. Falls back to the previous launch's ring when this one is empty.
     */
    static String snapshot(Context context) {
        synchronized (LOCK) {
            if (!RING.isEmpty()) {
                final StringBuilder b = new StringBuilder(RING.size() * 56);
                for (String s : RING) b.append(s).append('\n');
                return b.toString();
            }
        }
        return previous(context);
    }

    /** Called once the trail has been reported, and again on a clean exit. */
    static void clear(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            RING.clear();
            lastFlush = 0L;
        }
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).commit();
        } catch (Throwable ignored) { }
    }
}
