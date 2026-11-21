package app.spektralab.data;

import android.content.Context;
import android.net.Uri;

import app.spektralab.DebugSink;
import app.spektralab.domain.PhotoRepository;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/** The on-device implementation of {@link PhotoRepository}. */
public final class AndroidPhotoRepository implements PhotoRepository {
    private static final int MAX_COPIES = 40;
    private static final long MAX_BYTES = 2L << 30;

    private final Context context;

    public AndroidPhotoRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String displayName(String uri) {
        try {
            final String name = Uri.parse(uri).getLastPathSegment();
            return name == null ? "photo" : name;
        } catch (Throwable t) {
            return "photo";
        }
    }

    @Override
    public boolean canRead(String uri) {
        if (uri == null) return false;
        final Uri parsed = Uri.parse(uri);
        if ("file".equalsIgnoreCase(parsed.getScheme())) {
            return new File(parsed.getPath()).isFile();
        }
        try (InputStream in = context.getContentResolver().openInputStream(parsed)) {
            return in != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String persist(String source) {
        File dst = null;
        try {
            final Uri src = Uri.parse(source);
            final String name = displayName(source);
            final File dir = new File(context.getFilesDir(), "import");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            evictOldest(dir);
            dst = new File(dir, System.currentTimeMillis() + "_" + name);

            final InputStream in;
            if ("file".equalsIgnoreCase(src.getScheme())) {
                final File file = new File(src.getPath());
                if (!file.isFile()) return null;
                in = new FileInputStream(file);
            } else {
                in = context.getContentResolver().openInputStream(src);
            }
            try (InputStream input = in; FileOutputStream out = new FileOutputStream(dst)) {
                if (input == null) return null;
                final byte[] buf = new byte[1 << 16];
                int read;
                while ((read = input.read(buf)) > 0) out.write(buf, 0, read);
            }
            try { DebugSink.text("import", "copied " + name + " -> " + dst.getName()); }
            catch (Throwable e) { app.spektralab.Diagnostics.ignored("AndroidPhotoRepository:copied", e); }
            return Uri.fromFile(dst).toString();
        } catch (Throwable t) {
            if (dst != null) dst.delete();
            try { DebugSink.text("import", "copy failed: " + t); }
            catch (Throwable e) { app.spektralab.Diagnostics.ignored("AndroidPhotoRepository:failed", e); }
            return null;
        }
    }

    private static void evictOldest(File dir) {
        final File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long total = 0;
        int count = 0;
        for (File f : files) if (f.isFile()) { total += f.length(); ++count; }
        for (File f : files) {
            if (count <= MAX_COPIES && total <= MAX_BYTES) break;
            if (f.isFile() && f.delete()) { total -= f.length(); --count; }
        }
    }
}
