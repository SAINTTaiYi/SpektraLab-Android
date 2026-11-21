package app.spektralab;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Serialization of the photo strip; no UI state or Activity methods. */
final class SessionState {
    static final class Snapshot {
        final List<Frame> frames;
        final int current;
        final String currentUri;
        final boolean collapsed;

        Snapshot(List<Frame> frames, int current, String currentUri, boolean collapsed) {
            this.frames = frames;
            this.current = current;
            this.currentUri = currentUri;
            this.collapsed = collapsed;
        }
    }

    private SessionState() {}

    static JSONObject toJson(List<Frame> frames, int current, boolean collapsed) {
        try {
            return encode(frames, current, collapsed);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static JSONObject encode(List<Frame> frames, int current, boolean collapsed)
            throws org.json.JSONException {
        final JSONArray array = new JSONArray();
        for (Frame f : frames) {
            final JSONObject o = new JSONObject();
            o.put("uri", f.uri.toString());
            o.put("raw", f.raw);
            o.put("name", f.name == null ? "" : f.name);
            o.put("look", LookCodec.toJson(f.look));
            o.put("orig", f.original);
            array.put(o);
        }
        final JSONObject root = new JSONObject();
        root.put("frames", array);
        root.put("current", current);
        root.put("collapsed", collapsed);
        return root;
    }

    /** Returns null when the document is empty or malformed. */
    static Snapshot fromJson(String raw, Context context) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            final JSONObject root = new JSONObject(raw);
            final JSONArray array = root.optJSONArray("frames");
            if (array == null || array.length() == 0) return null;
            final List<Frame> frames = new ArrayList<>();
            for (int i = 0; i < array.length(); ++i) {
                final JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                final String uri = o.optString("uri", "");
                if (uri.isEmpty()) continue;
                final Frame frame = new Frame(Uri.parse(uri),
                        o.optBoolean("raw", false), o.optString("name", ""));
                frame.look = LookCodec.fromJson(o.optJSONObject("look"));
                // A frame saved before this flag existed was looking at its print.
                frame.original = o.optBoolean("orig", false);
                frame.thumb = ThumbCache.read(context, frame.uri);
                frames.add(frame);
            }
            if (frames.isEmpty()) return null;
            final int current = root.optInt("current", -1);
            final String currentUri = current >= 0 && current < frames.size()
                    ? frames.get(current).uri.toString() : "";
            return new Snapshot(frames, current, currentUri,
                    root.optBoolean("collapsed", false));
        } catch (Throwable t) {
            return null;
        }
    }
}
