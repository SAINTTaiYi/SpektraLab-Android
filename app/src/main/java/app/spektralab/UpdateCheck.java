package app.spektralab;

import static app.spektralab.AppLanguage.tr;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Looks for a newer build and, if there is one, says so.
 *
 * The announcement lives in two places on purpose. Cloudflare answers fastest
 * where it is reachable at all — workers.dev is refused outright on mainland
 * China networks (TCP to 443 never opens), so the same shape of JSON is also
 * kept in the repository, where jsDelivr and raw.githubusercontent.com serve
 * it. The first endpoint that answers wins; if none does, the check is silent
 * and the app carries on. Nothing about the device is sent with the request:
 * it is a GET of a public file, and the reply is a version number.
 */
final class UpdateCheck {

    private static final String[] ENDPOINTS = {
            // Cloudflare, on a name that resolves and serves in mainland China.
            // The workers.dev hostname does NOT: TCP to 443 never opens there,
            // which is why this is a custom domain rather than the default one.
            "https://update.workshop.skin/latest.json",
            "https://cdn.jsdelivr.net/gh/SAINTTaiYi/SpektraLab-Android@master/update.json",
            "https://raw.githubusercontent.com/SAINTTaiYi/SpektraLab-Android/master/update.json",
    };

    private static final String PREFS = "update";
    private static final String KEY_SEEN_CODE = "seen_code";
    private static final String KEY_CHECKED_AT = "checked_at";
    // 30 minutes, not 6 hours: an announcement published while someone has the
    // app open should land on their next launch, not tomorrow.
    private static final long MIN_INTERVAL_MS = 30L * 60 * 1000;

    private UpdateCheck() {}

    /** Silent unless there is something to say, or `manual` was asked for. */
    static void check(final Activity activity, final boolean manual) {
        final SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!manual) {
            final long last = prefs.getLong(KEY_CHECKED_AT, 0);
            if (System.currentTimeMillis() - last < MIN_INTERVAL_MS) return;
        }
        new Thread(() -> {
            String body = null;
            String lastError = null;
            for (String endpoint : ENDPOINTS) {
                try {
                    body = fetch(endpoint);
                    if (body != null && !body.isEmpty()) break;
                } catch (Throwable t) {
                    lastError = endpoint + ": " + t;
                }
            }
            prefs.edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply();

            if (body == null) {
                if (manual) {
                    final String detail = lastError == null ? tr("update.no_update_server_responded") : lastError;
                    post(activity, () -> toast(activity,
                        "[E-2001] " + SpkError.explain(2001) + "\n" + detail));
                }
                return;
            }

            final Announcement a;
            try {
                a = Announcement.parse(body);
            } catch (Throwable t) {
                if (manual) post(activity, () -> toast(activity,
                        "[E-2002] " + SpkError.explain(2002) + "\n" + t.getMessage()));
                return;
            }
            if (a == null) return;

            final int current = versionCode(activity);
            if (a.versionCode <= current) {
                if (manual) post(activity, () -> toast(activity,
                        tr("update.you_re_up_to_date") + versionName(activity) + " (" + current + ")"));
                return;
            }
            if (!manual && prefs.getInt(KEY_SEEN_CODE, 0) == a.versionCode) return;

            post(activity, () -> show(activity, a, prefs));
        }, "update-check").start();
    }

    /** The app owns its messages: never show the Android system Toast. */
    private static void toast(Activity activity, String message) {
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).showInAppToast(message);
        }
    }

    private static void show(Activity activity, Announcement a, SharedPreferences prefs) {
        final StringBuilder message = new StringBuilder();
        for (String note : a.notes) {
            // **The address is pinned, not scrolled.** It is passed to the sheet
            // separately so it stays on screen while the changelog scrolls; a
            // server-side copy of the same line is dropped here instead of
            // being printed twice.
            if (note.trim().isEmpty() || note.contains("spektralab.workshop.skin/download")) continue;
            message.append("· ").append(note).append('\n');
        }
        if (message.length() > 0) message.append('\n');
        message.append(tr("update.tap_download_to_open_the"));

        if (!(activity instanceof MainActivity)) return;
        final String[] actions = {tr("update.download"), tr("update.copy_link"), tr("update.later")};
        ((MainActivity) activity).showActionSheet(tr("update.update_available") + a.versionName,
                message.toString().trim(), actions, which -> {
                    if (which == 0) {
                        prefs.edit().putInt(KEY_SEEN_CODE, a.versionCode).apply();
                        try {
                            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(a.url)));
                        } catch (ActivityNotFoundException e) {
                            toast(activity, tr("update.no_browser_available") + a.url);
                        }
                    } else if (which == 1) {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("SpektraLab", a.url));
                        toast(activity, tr("update.link_copied"));
                        prefs.edit().putInt(KEY_SEEN_CODE, a.versionCode).apply();
                    } else {
                        prefs.edit().putInt(KEY_SEEN_CODE, a.versionCode).apply();
                    }
                }, null, addressLine());
    }

    /**
     * The one line the reader must be able to act on, pinned above the list.
     *
     * <p>Short on purpose: the full `url` is a hundred-character GitHub asset
     * link, and it is what Copy link copies. This is what a person types.
     */
    private static String addressLine() {
        return tr("update.download_2") + "spektralab.workshop.skin/download";
    }

    private static String fetch(String endpoint) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        // 3 s to connect: the fallbacks are a CDN and a raw file, and a
        // blocked host should not hold the check up for longer than that.
        c.setConnectTimeout(3000);
        c.setReadTimeout(5000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "SpektraLab-Android");
        try {
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream();
                 BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            }
        } finally {
            c.disconnect();
        }
    }

    private static void post(Activity activity, Runnable r) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) r.run();
        });
    }

    static int versionCode(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return android.os.Build.VERSION.SDK_INT >= 28 ? (int) p.getLongVersionCode() : p.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** One announcement: what it is, where to get it, what changed. */
    static final class Announcement {
        final int versionCode;
        final String versionName;
        final String url;
        final String[] notes;

        private Announcement(int code, String name, String url, String[] notes) {
            this.versionCode = code;
            this.versionName = name;
            this.url = url;
            this.notes = notes;
        }

        static Announcement parse(String json) throws Exception {
            JSONObject o = new JSONObject(json);
            int code = o.optInt("latest_version_code", 0);
            if (code <= 0) return null;
            String name = o.optString("latest_version_name", String.valueOf(code));
            String url = o.optString("url", "https://github.com/SAINTTaiYi/SpektraLab-Android/releases/latest");
            final AppLanguage.Language language = AppLanguage.current();
            final String primary = language.locale.getLanguage();
            JSONArray arr = o.optJSONArray("notes_" + language.tag.replace('-', '_'));
            if (arr == null) arr = o.optJSONArray("notes_" + primary);
            // The upstream feed's original, unqualified notes are Chinese.
            if (arr == null && "zh".equals(primary)) arr = o.optJSONArray("notes");
            if (arr == null) arr = o.optJSONArray("notes_en");
            String[] notes = new String[arr == null ? 0 : arr.length()];
            for (int i = 0; i < notes.length; ++i) notes[i] = arr.optString(i, "");
            return new Announcement(code, name, url, notes);
        }
    }
}
