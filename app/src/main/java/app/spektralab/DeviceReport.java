package app.spektralab;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * One device's GPU capabilities, sent once a week so the ceilings this app
 * works to can be checked against hardware nobody here owns.
 *
 * <p>What this exists for: {@code maxStorageBufferRange} is the number every
 * import decision hangs on, and the drivers that report it are not
 * measurable from one phone. Adreno 7xx reports 128 MiB and Adreno 8xx reports
 * 2 GiB on the same API version; Mali reports 2 GiB on one part and 256 MiB on
 * another. The 42 MP frame cap and the 100 B/px memory budget were both set
 * from a single device. A table built from real installs is the only way those
 * numbers get better.
 *
 * <p>What it sends: GPU name, Vulkan API and driver versions, the three
 * binding/allocation/image ceilings, heap size, workgroup limits, the phone's
 * make and model, the Android release, the canary result, and the frame budget
 * this build derived. <b>Nothing else.</b> No photograph, no file name, no
 * account, no advertising id, no location, no installed-app list, no
 * identifier that survives an uninstall.
 *
 * <p>The switch lives in 关于 and defaults on. A failure is silent: this is a
 * diagnostic, and a diagnostic that complains is a bug.
 */
final class DeviceReport {

    /** Same Worker as the update channel; `/report` is its own route. */
    private static final String ENDPOINT = "https://report.workshop.skin/report";
    private static final String PREFS = "spektralab";
    private static final String KEY_ENABLED = "driver_report_enabled";
    private static final String KEY_LAST = "driver_report_last";
    private static final String KEY_TRY = "driver_report_try";
    private static final String KEY_SEEN = "driver_report_seen";
    /** Once a week is enough: a driver changes when the phone updates, not daily. */
    private static final long INTERVAL_MS = 7L * 24 * 3600 * 1000;
    /** A failed attempt retries in an hour: the first launch is often offline. */
    private static final long RETRY_MS = 3600L * 1000;
    private static final int TIMEOUT_MS = 8000;

    private DeviceReport() {}

    static boolean enabled(Context c) {
        final SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, on).apply();
    }

    /** The last answer we got back, or "" -- shown in 关于 so this is not a mystery. */
    static String lastAnswer(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SEEN, "");
    }

    /**
     * Send one report, if it is due and allowed.
     *
     * Called after the canary finishes, so the document carries a measured
     * range rather than only a reported one. Runs on its own thread; a caller
     * on the UI thread is not blocked and never learns whether it worked,
     * which is deliberate.
     */
    static void submit(Context c, DeviceProbe probe, long budgetPixels) {
        if (probe == null || !probe.valid) return;
        final Context app = c.getApplicationContext();
        if (!enabled(app)) return;
        final SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final long now = System.currentTimeMillis();
        // Throttled on the last *success*, so a phone that installs offline
        // still gets its row in the table once the network comes back.
        if (now - p.getLong(KEY_LAST, 0L) < INTERVAL_MS) return;
        if (now - p.getLong(KEY_TRY, 0L) < RETRY_MS) return;
        p.edit().putLong(KEY_TRY, now).apply();

        final String version = UpdateCheck.versionName(app);
        final int code = UpdateCheck.versionCode(app);
        final JSONObject body = probe.reportJson(
                version, code,
                Build.VERSION.RELEASE == null ? "?" : Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT,
                Build.MANUFACTURER == null ? "?" : Build.MANUFACTURER,
                Build.MODEL == null ? "?" : Build.MODEL,
                Build.DEVICE == null ? "?" : Build.DEVICE,
                budgetPixels);

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                final byte[] bytes = body.toString().getBytes("UTF-8");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(bytes);
                }
                final int status = conn.getResponseCode();
                if (status == 200) p.edit().putLong(KEY_LAST, now).apply();
                p.edit().putString(KEY_SEEN, status + " · " + (status == 200 ? "已上报" : "未上报")).apply();
                try { DebugSink.text("driver-report", status + "\n" + body); }
                catch (Throwable ignored) { }
            } catch (Throwable t) {
                p.edit().putString(KEY_SEEN, "失败 · " + t.getClass().getSimpleName()).apply();
                try { DebugSink.text("driver-report", "failed: " + t + "\n" + body); }
                catch (Throwable ignored) { }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "driver-report").start();
    }
}
