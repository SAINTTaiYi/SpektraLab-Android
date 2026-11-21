package app.spektralab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Disk cache for the strip's small thumbnails and export scratch files. */
final class ThumbCache {
    private ThumbCache() {}

    static File file(Context context, Uri uri) {
        return new File(context.getCacheDir(),
                "thumb_" + Integer.toHexString(uri.toString().hashCode()) + ".png");
    }

    static void persist(Context context, Frame frame) {
        if (frame == null || frame.thumb == null) return;
        try (FileOutputStream out = new FileOutputStream(file(context, frame.uri))) {
            frame.thumb.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Throwable spkIgnored) { Diagnostics.ignored("persist:27", spkIgnored); }
    }

    static Bitmap read(Context context, Uri uri) {
        try {
            final File f = file(context, uri);
            if (!f.isFile()) return null;
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    static void prune(Context context, List<Frame> frames) {
        final File[] files = context.getCacheDir().listFiles(
                (dir, name) -> name.startsWith("thumb_") && name.endsWith(".png"));
        if (files == null) return;
        final Set<String> keep = new HashSet<>();
        for (Frame f : frames) {
            if (f.uri != null) keep.add("thumb_" + Integer.toHexString(f.uri.toString().hashCode()) + ".png");
        }
        for (File f : files) if (!keep.contains(f.getName())) f.delete();
    }

    static void pruneDiagnostics(Context context) {
        final long cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        final File[] files = context.getCacheDir().listFiles((dir, name) ->
                name.endsWith(".prog") || name.startsWith("export_") || name.startsWith("vk"));
        if (files == null) return;
        for (File f : files) if (f.isFile() && f.lastModified() < cutoff) f.delete();
    }
}
