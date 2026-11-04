package app.spektralab;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The last thing the app was doing, on disk, before it died.
 *
 * `installCrashLogger` catches Java exceptions, but the crashes that matter on
 * a phone are the ones that never reach a Java frame: a Vulkan driver that
 * takes the process down on an allocation or a dispatch leaves no stack, no
 * tombstone the user can copy, and -- since 1.4.x ships without a sink -- no
 * record at all. A crash that leaves nothing behind is a crash that cannot be
 * fixed on someone else's device.
 *
 * So: one line, committed synchronously, at each step that can plausibly be the
 * last. A commit is a few milliseconds and these are five or six points on a
 * path that already spends seconds decoding, so the cost is invisible. On the
 * next launch the trail is non-empty exactly when the previous launch did not
 * reach `onDestroy`, and it is reported once and cleared.
 */
final class CrashTrail {
    private static final String FILE = "trail";
    private static final String KEY = "last";

    private CrashTrail() {}

    /** Record the step. Synchronous on purpose: `apply` can lose the write. */
    static void mark(Context context, String step) {
        if (context == null) return;
        try {
            final long now = System.currentTimeMillis();
            final SharedPreferences sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            sp.edit().putString(KEY, step + " @" + now).commit();
        } catch (Throwable ignored) { }
    }

    /** What the previous launch was doing, or "" when it ended cleanly. */
    static String previous(Context context) {
        if (context == null) return "";
        try {
            return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "");
        } catch (Throwable e) {
            return "";
        }
    }

    /** Called once the trail has been reported, and again on a clean exit. */
    static void clear(Context context) {
        if (context == null) return;
        try {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).commit();
        } catch (Throwable ignored) { }
    }
}
