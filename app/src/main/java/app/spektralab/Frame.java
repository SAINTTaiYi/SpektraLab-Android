package app.spektralab;

import android.graphics.Bitmap;
import android.net.Uri;

/** One photograph in the strip: its URI, a small thumb, and its own edit recipe. */
final class Frame {
    final Uri uri;
    final boolean raw;
    final String name;
    Bitmap thumb;
    Look look = new Look();
    /**
     * Whether this frame was left on 原图. A frame can carry a film and still be
     * sitting on the source; remembering only `look.filmStock` used to re-render
     * it when a chip was reopened.
     */
    boolean original = true;

    Frame(Uri uri, boolean raw, String name) {
        this.uri = uri; this.raw = raw; this.name = name;
    }
}
