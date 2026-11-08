package app.spektralab;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Diagnostics for someone else's phone.
 *
 * <p><b>Nothing leaves the phone unless somebody asked for it.</b> The old
 * build uploaded every line -- {@code app-start} on every launch, a
 * {@code probe-*} row per gesture, an {@code export-*} row per export -- and
 * filled the free D1 quota in a day. A full quota does not fail loudly: the
 * Worker's insert starts returning errors and the rows that would have
 * explained the next crash are exactly the ones missing.
 *
 * <p>So the shape changed. Every line still gets written, but into a small
 * in-memory ring ({@link #text}); that ring is what a report carries. Three
 * things, and only three, put it on the wire:
 *
 * <ul>
 *   <li>the user taps the {@code 导出最近的步骤} item in 关于 -- one row;</li>
 *   <li>an error dialog is shown -- one row, throttled ({@code reportFailure}
 *       in {@code MainActivity});</li>
 *   <li>the previous launch died -- one row at the next start, plus the
 *       native backtrace file when the signal handler left one.</li>
 * </ul>
 *
 * <p>{@link #report} bundles head + ring + details into a single row on
 * purpose: a report is one write, not sixty-four. If the send fails the bundle
 * is kept in {@code sink-pending.txt} and retried once on the next launch --
 * one file, one row, no growing queue. The 8 MB write-ahead queue is gone; it
 * existed to survive a broken network, and it became the thing that had to be
 * diagnosed (1.5.1l: a 9.4 MB backlog refused every later enqueue).
 */
public final class DebugSink {
    /** Where reports go. */
    private static final String CLOUD = "https://sink.workshop.skin/upload";
    /** Keep a local reader during development; off in builds users run. */
    private static final boolean LOCAL_ALSO = false;
    private static final String LOCAL = "http://127.0.0.1:8770/upload";

    /** One row; the Worker rejects above this too. */
    private static final int MAX_BYTES = 262144;
    /** Per line kept in the ring. A params dump is ~2 KB and that is plenty. */
    private static final int MAX_LINE = 4096;
    /** Ring size in characters. 400 lines of 80 characters is the usual case. */
    private static final int RING_BYTES = 96 * 1024;
    /** `vk-log` is a firehose and never once named the node that died. */
    private static final boolean VK_LOG = false;

    /**
     * One id per process launch. Printed in the error dialog and in the crash
     * report, sent with every row ({@code x-ref}), so a screenshot that says
     * "记录号 SL-261106-1A2B" can be answered by
     * {@code GET /admin/logs?ref=SL-261106-1A2B}.
     */
    public static final String REF = makeRef();

    private static final Object LOCK = new Object();
    private static final java.util.ArrayDeque<String> RING = new java.util.ArrayDeque<>();
    private static int ringChars = 0;
    private static File pendingFile;

    private DebugSink() { }

    /** Call once, first thing in {@code onCreate}, before anything logs. */
    public static void init(Context context) {
        if (context == null) return;
        final File retry;
        synchronized (LOCK) {
            if (pendingFile == null) pendingFile = new File(context.getFilesDir(), "sink-pending.txt");
            // **An inherited backlog must not be replayed.** 1.5.1l shipped
            // 9.4 MB of `vk-log` rows; reporting is on demand now, so a queue
            // from an older build is deleted rather than sent.
            final File legacy = new File(context.getFilesDir(), "sink-queue.log");
            if (legacy.exists()) legacy.delete();
            retry = pendingFile;
        }
        text("ds-init", "ring=" + RING_BYTES + "B pending=" + (retry == null ? -1 : retry.length()));
        // One retry per launch, and only when a report already failed to send.
        if (retry != null && retry.exists()) {
            new Thread(() -> retryPending(retry), "SpektraLab-sink-retry").start();
        }
    }

    /** A line for the ring. **No network.** */
    public static void text(String tag, String body) {
        remember(tag, body);
    }

    /**
     * Same as {@link #text}. Kept because call sites read better with it
     * ("this one has to survive a cold launch"), and because the ring lives in
     * memory: a line is either recorded or it is not, and either way it is
     * never sent on its own.
     */
    public static void textNow(String tag, String body) {
        remember(tag, body);
    }

    private static void remember(String tag, String body) {
        if (body == null) return;
        if (!VK_LOG && "vk-log".equals(tag)) return;
        final String line = stamp() + " " + tag + " | " + clamp(body, MAX_LINE);
        synchronized (LOCK) {
            RING.addLast(line);
            ringChars += line.length();
            // Keep at least a handful of lines even when they are all huge.
            while (RING.size() > 8 && ringChars > RING_BYTES) {
                ringChars -= RING.removeFirst().length();
            }
        }
    }

    /** The ring as plain text, oldest first. What a report carries. */
    public static String ring() {
        synchronized (LOCK) {
            final StringBuilder b = new StringBuilder(ringChars + 16);
            for (String s : RING) b.append(s).append('\n');
            return b.toString();
        }
    }

    /** How many lines the ring holds right now, for the 关于 label. */
    public static int ringSize() {
        synchronized (LOCK) { return RING.size(); }
    }

    /**
     * Put one row on the wire. The only network path in this class.
     *
     * <p>A failed send is stashed, not queued: the next launch tries that one
     * bundle once.
     */
    public static boolean send(String tag, String body) {
        if (body == null) return false;
        final String row = clampBytes(body, MAX_BYTES);
        final boolean ok = post(CLOUD, tag, "text.txt", row, "text/plain", REF);
        if (ok && LOCAL_ALSO) post(LOCAL, tag, "text.txt", row, "text/plain", REF);
        if (!ok) stash(tag, row);
        return ok;
    }

    /**
     * Head + ring + details as a single row.
     *
     * <p>One row is the point: the D1 quota is counted in writes, and the old
     * path spent sixty-four of them on the same information.
     */
    public static boolean report(String tag, String head, String extra) {
        final StringBuilder b = new StringBuilder();
        if (head != null) b.append(head);
        b.append("ref=").append(REF).append('\n');
        b.append("--- recent steps ---\n").append(ring());
        if (extra != null && !extra.isEmpty()) {
            if (!extra.endsWith("\n")) b.append('\n');
            b.append("--- details ---\n").append(extra);
        }
        return send(tag, b.toString());
    }

    /** Binary best-effort: never sent, never queued. */
    public static void bytes(String tag, String name, byte[] data, String type) {
        // Binary is not the diagnostic; base64 would triple the row for a
        // picture nobody needs.
    }

    // -- one-slot retry ------------------------------------------------------

    private static void stash(String tag, String body) {
        synchronized (LOCK) {
            if (pendingFile == null) return;
            try {
                final org.json.JSONObject o = new org.json.JSONObject();
                o.put("t", System.currentTimeMillis());
                o.put("g", tag);
                o.put("r", REF);
                o.put("b", body);
                try (FileOutputStream out = new FileOutputStream(pendingFile, false)) {
                    out.write((o.toString() + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) { }
        }
    }

    private static void retryPending(File f) {
        try {
            String line = null;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String s;
                // Only the newest bundle: an older failure is stale by now.
                while ((s = r.readLine()) != null) if (!s.isEmpty()) line = s;
            }
            if (line == null) { f.delete(); return; }
            final org.json.JSONObject o = new org.json.JSONObject(line);
            if (post(CLOUD, o.optString("g", "retry"), "text.txt",
                    o.optString("b", ""), "text/plain", o.optString("r", REF))) {
                f.delete();
            }
        } catch (Throwable ignored) { }
    }

    // -- transport -----------------------------------------------------------

    private static boolean post(String base, String tag, String name, String body,
                                String type, String ref) {
        try {
            final URL u = new URL(base + "?tag=" + URLEncoder.encode(tag, "UTF-8")
                    + "&name=" + URLEncoder.encode(name, "UTF-8"));
            final HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(2000);
            c.setReadTimeout(8000);
            final byte[] data = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(data.length);
            c.setRequestProperty("Content-Type", type);
            c.setRequestProperty("x-ref", ref);
            try (OutputStream o = c.getOutputStream()) {
                o.write(data);
            }
            final int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 300;
        } catch (Throwable t) {
            return false;
        }
    }

    // -- small helpers -------------------------------------------------------

    private static String stamp() {
        final long ms = System.currentTimeMillis();
        return String.format(java.util.Locale.US, "%02d:%02d:%02d.%03d",
                (ms / 3600000L) % 24L, (ms / 60000L) % 60L, (ms / 1000L) % 60L, ms % 1000L);
    }

    private static String clamp(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\u2026";
    }

    /**
     * Truncate to a UTF-8 byte budget (CJK is three bytes a character).
     *
     * <p>The marker is part of the budget, not extra: the Worker refuses a row
     * above {@link #MAX_BYTES}, and a clamp that lands eleven bytes over is a
     * clamp that loses the whole report.
     */
    private static String clampBytes(String s, int max) {
        if (s.getBytes(StandardCharsets.UTF_8).length <= max) return s;
        final String tail = "\n[truncated]";
        final int budget = max - tail.getBytes(StandardCharsets.UTF_8).length;
        int n = budget / 3;
        while (n > 0 && s.substring(0, Math.min(n, s.length()))
                .getBytes(StandardCharsets.UTF_8).length > budget) {
            n /= 2;
        }
        return s.substring(0, Math.min(n, s.length())) + tail;
    }

    private static String makeRef() {
        final java.util.Date now = new java.util.Date();
        return String.format(java.util.Locale.US, "SL-%02d%02d%02d-%04X",
                now.getYear() % 100, now.getMonth() + 1, now.getDate(),
                new java.util.Random().nextInt(0x10000));
    }
}
