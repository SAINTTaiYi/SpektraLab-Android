package app.spektralab;

import android.os.Handler;
import android.os.Looper;

/** One place that posts work to the UI thread, independent of Activity state. */
final class UiDispatcher {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UiDispatcher() {}

    static boolean isMain() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    static void post(Runnable r) {
        // Always post, even from the main thread: the old Activity.runOnUiThread
        // semantics are what every call site was written against. uiWait() has
        // its own explicit inline fast path where that is wanted.
        MAIN.post(r);
    }
}
