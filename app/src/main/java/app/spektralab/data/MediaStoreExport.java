package app.spektralab.data;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import app.spektralab.Diagnostics;
import app.spektralab.SpkError;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Writes a finished export into MediaStore, with the album-write fallbacks. */
public final class MediaStoreExport {
    private final ContentResolver resolver;

    public MediaStoreExport(Context context) {
        resolver = context.getContentResolver();
    }

    public Uri write(File tmp, String baseName, String ext, String mime) throws IOException {
        Throwable lastError = null;
        for (int attempt = 0; attempt < 2; ++attempt) {
            final String name = attempt == 0 ? baseName
                    : "SpektraLab_" + System.currentTimeMillis() + "_r" + ext;
            final ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, mime);
            v.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/SpektraLab");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = null;
            try { uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v); }
            catch (Throwable t) { lastError = t; }
            if (uri == null) continue;
            try {
                copyInto(tmp, uri);
                v.clear();
                v.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, v, null, null);
                return uri;
            } catch (Throwable t) {
                lastError = t;
                try { resolver.delete(uri, null, null); }
                catch (Throwable e) { Diagnostics.ignored("MediaStoreExport:delete", e); }
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            Uri uri = null;
            try {
                final ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME,
                        "SpektraLab_" + System.currentTimeMillis() + ext);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/SpektraLab");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    copyInto(tmp, uri);
                    v.clear();
                    v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    resolver.update(uri, v, null, null);
                    return uri;
                }
            } catch (Throwable t) {
                lastError = t;
                if (uri != null) {
                    try { resolver.delete(uri, null, null); }
                    catch (Throwable e) { Diagnostics.ignored("MediaStoreExport:download-delete", e); }
                }
            }
        }
        throw new IOException(SpkError.ALBUM_WRITE_PREFIX, lastError);
    }

    private void copyInto(File tmp, Uri uri) throws IOException {
        try (InputStream in = new FileInputStream(tmp);
             OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IOException("openOutputStream returned null");
            final byte[] buf = new byte[262144];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }
}
