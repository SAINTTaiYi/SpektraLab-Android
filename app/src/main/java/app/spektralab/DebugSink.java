package app.spektralab;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Posts diagnostics to the Termux-side sink on 127.0.0.1:8770. Silent on failure. */
public final class DebugSink {
    private static final String BASE = "http://127.0.0.1:8770/upload";
    private static final ExecutorService EX = Executors.newSingleThreadExecutor();

    public static void text(String tag, String body) {
        post(tag, "text.txt", body.getBytes(), "text/plain");
    }

    public static void bytes(String tag, String name, byte[] data, String type) {
        post(tag, name, data, type);
    }

    /** Blocking POST, for the one caller that cannot afford a queue: the
     *  process is about to die and the executor below would never run. */
    public static void textNow(String tag, String body) {
        try {
            URL u = new URL(BASE + "?tag=" + URLEncoder.encode(tag, "UTF-8")
                    + "&name=" + URLEncoder.encode("text.txt", "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(1200);
            c.setReadTimeout(1200);
            final byte[] data = body.getBytes();
            c.setFixedLengthStreamingMode(data.length);
            c.setRequestProperty("Content-Type", "text/plain");
            OutputStream o = c.getOutputStream();
            o.write(data);
            o.close();
            c.getResponseCode();
            c.disconnect();
        } catch (Throwable ignored) { }
    }

    private static void post(final String tag, final String name, final byte[] data, final String type) {
        EX.execute(new Runnable() {
            @Override public void run() {
                try {
                    URL u = new URL(BASE + "?tag=" + URLEncoder.encode(tag, "UTF-8")
                            + "&name=" + URLEncoder.encode(name, "UTF-8"));
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setConnectTimeout(1500);
                    c.setReadTimeout(5000);
                    c.setFixedLengthStreamingMode(data.length);
                    c.setRequestProperty("Content-Type", type);
                    OutputStream o = c.getOutputStream();
                    o.write(data);
                    o.close();
                    c.getResponseCode();
                    c.disconnect();
                } catch (Throwable ignored) { }
            }
        });
    }
}
