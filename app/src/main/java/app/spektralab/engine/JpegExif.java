package app.spektralab.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The APP1 segment the second engine's exports were missing.
 *
 * <p>fotufilm writes its JPEG through its own codec library, which emits the
 * frame and an ICC profile and nothing else: every export came back without the
 * model, the capture time, the shutter, the aperture, the ISO or the focal
 * length, so the album showed a file with no history. The first engine's writer
 * appends an APP1 itself; this is the same idea for the file that arrives from
 * the other side.
 *
 * <p>Deliberately plain {@code java.io}. No Android types, no
 * {@code ExifInterface}: its {@code saveAttributes} re-opens and rewrites the
 * whole file and the segment set it keeps is a per-release detail of the
 * platform. Here the compressed data is copied through untouched -- only the
 * marker chain in front of it is rebuilt -- so a bug in this class can lose
 * metadata but cannot touch one pixel. That also makes it testable on a desktop
 * JVM, which is how it was verified.
 *
 * <p>The orientation is written as 1 on purpose: the pixels handed over are
 * already upright (the decoder applied the source's orientation), so carrying
 * the source's 6 or 8 across would rotate the print a second time.
 */
public final class JpegExif {

    private JpegExif() { }

    /** What goes into the segment. Empty strings are omitted, not written blank. */
    public static final class Meta {
        public String make = "";
        public String model = "";
        /** EXIF's own spelling: "2026:02:05 16:57:48". */
        public String datetime = "";
        /** Seconds: "1/125", "0.008" or "2.5". */
        public String exposure = "";
        /** f-number as a decimal: "2.8". */
        public String aperture = "";
        public String iso = "";
        /** Millimetres as a decimal: "35.0". */
        public String focal = "";
        public String software = "SpektraLab";
        /** Free text, EXIF UserComment -- the film and the sheet this print came off. */
        public String note = "";
        public int width;
        public int height;
    }

    // ------------------------------------------------------------------ build

    private static final int T_SHORT = 3;
    private static final int T_LONG = 4;
    private static final int T_RATIONAL = 5;
    private static final int T_ASCII = 2;
    private static final int T_UNDEFINED = 7;

    /** A complete APP1 segment: marker, length, "Exif\0\0", TIFF block. */
    public static byte[] buildApp1(Meta m) {
        if (m == null) return null;
        final List<Entry> ifd0 = new ArrayList<>();
        final List<Entry> exif = new ArrayList<>();

        if (filled(m.make)) ifd0.add(ascii(0x010F, m.make));
        if (filled(m.model)) ifd0.add(ascii(0x0110, m.model));
        ifd0.add(shorts(0x0112, 1));                    // Orientation: already upright
        ifd0.add(rational(0x011A, 72, 1));              // XResolution
        ifd0.add(rational(0x011B, 72, 1));              // YResolution
        ifd0.add(shorts(0x0128, 2));                    // ResolutionUnit: inch
        if (filled(m.software)) ifd0.add(ascii(0x0131, m.software));
        if (filled(m.datetime)) ifd0.add(ascii(0x0132, m.datetime));
        if (m.width > 0 && m.height > 0) {
            ifd0.add(longs(0xA002, m.width));           // PixelXDimension
            ifd0.add(longs(0xA003, m.height));          // PixelYDimension
        }

        final long[] exposure = rationalOf(m.exposure, 1000000L);
        if (exposure != null) exif.add(rational(0x829A, exposure[0], exposure[1]));
        final long[] aperture = rationalOf(m.aperture, 10L);
        if (aperture != null) exif.add(rational(0x829D, aperture[0], aperture[1]));
        final long iso = longOf(m.iso);
        if (iso > 0) {
            if (iso <= 0xFFFFL) exif.add(shorts(0x8827, (int) iso));
            else exif.add(longs(0x8827, iso));
        }
        if (filled(m.datetime)) exif.add(ascii(0x9003, m.datetime));
        final long[] focal = rationalOf(m.focal, 10L);
        if (focal != null) exif.add(rational(0x920A, focal[0], focal[1]));
        if (filled(m.note)) exif.add(userComment(m.note));

        // A segment carrying only "orientation, 72 dpi" is not worth writing; one
        // that carries the camera or the date is. A frame with no size at all is
        // a caller mistake, not a file to enhance.
        final boolean worth = filled(m.make) || filled(m.model) || filled(m.datetime)
                || (m.width > 0 && m.height > 0);
        if (!worth) return null;

        if (!exif.isEmpty())
            ifd0.add(new Entry(0x8769, T_LONG, new byte[]{0, 0, 0, 0}));   // ExifIFDPointer
        final byte[] tiff = assemble(ifd0, exif);
        if (tiff == null) return null;

        final int total = 2 + 6 + tiff.length;
        if (total > 0xFFFF) return null;
        final byte[] out = new byte[2 + total];
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xE1;
        out[2] = (byte) ((total >> 8) & 0xFF);
        out[3] = (byte) (total & 0xFF);
        out[4] = 'E'; out[5] = 'x'; out[6] = 'i'; out[7] = 'f'; out[8] = 0; out[9] = 0;
        System.arraycopy(tiff, 0, out, 10, tiff.length);
        return out;
    }

    private static final class Entry {
        final int tag, type;
        final byte[] data;
        Entry(int tag, int type, byte[] data) { this.tag = tag; this.type = type; this.data = data; }
        int count() { return data.length / sizeOf(type); }
    }

    private static int sizeOf(int type) {
        switch (type) {
            case 1: case 2: case 6: case 7: return 1;
            case 3: case 8: return 2;
            case 4: case 9: case 11: return 4;
            case 5: case 10: case 12: return 8;
            default: return 1;
        }
    }

    /** Two IFDs, little-endian, values after them. Null when it does not fit one segment. */
    private static byte[] assemble(List<Entry> ifd0, List<Entry> exif) {
        final Comparator<Entry> byTag = (a, b) -> Integer.compare(a.tag, b.tag);
        ifd0.sort(byTag);
        exif.sort(byTag);

        final int ifd0At = 8;
        final int ifd0Size = 2 + ifd0.size() * 12 + 4;
        int ifd0Values = 0;
        for (Entry e : ifd0) if (e.data.length > 4) ifd0Values += align(e.data.length);
        final int exifAt = ifd0At + ifd0Size + ifd0Values;
        final int exifSize = 2 + exif.size() * 12 + 4;
        int exifValues = 0;
        for (Entry e : exif) if (e.data.length > 4) exifValues += align(e.data.length);
        final int total = exifAt + exifSize + exifValues;
        if (total > 65000) return null;

        final byte[] tiff = new byte[total];
        tiff[0] = 'I'; tiff[1] = 'I';                    // little endian
        put16(tiff, 2, 42);
        put32(tiff, 4, ifd0At);

        // The pointer only exists because the Exif IFD does; write its offset now.
        for (Entry e : ifd0) {
            if (e.tag == 0x8769) {
                e.data[0] = (byte) (exifAt & 0xFF);
                e.data[1] = (byte) ((exifAt >> 8) & 0xFF);
                e.data[2] = (byte) ((exifAt >> 16) & 0xFF);
                e.data[3] = (byte) ((exifAt >> 24) & 0xFF);
            }
        }

        int at = ifd0At;
        put16(tiff, at, ifd0.size()); at += 2;
        int valueAt = ifd0At + ifd0Size;
        for (Entry e : ifd0) {
            put16(tiff, at, e.tag); put16(tiff, at + 2, e.type); put32(tiff, at + 4, e.count());
            if (e.data.length > 4) {
                put32(tiff, at + 8, valueAt);
                System.arraycopy(e.data, 0, tiff, valueAt, e.data.length);
                valueAt += align(e.data.length);
            } else {
                System.arraycopy(e.data, 0, tiff, at + 8, e.data.length);
            }
            at += 12;
        }
        put32(tiff, at, 0);                              // no IFD1: a stale thumbnail
                                                         // would show the ungraded file
        at = exifAt;
        put16(tiff, at, exif.size()); at += 2;
        valueAt = exifAt + exifSize;
        for (Entry e : exif) {
            put16(tiff, at, e.tag); put16(tiff, at + 2, e.type); put32(tiff, at + 4, e.count());
            if (e.data.length > 4) {
                put32(tiff, at + 8, valueAt);
                System.arraycopy(e.data, 0, tiff, valueAt, e.data.length);
                valueAt += align(e.data.length);
            } else {
                System.arraycopy(e.data, 0, tiff, at + 8, e.data.length);
            }
            at += 12;
        }
        put32(tiff, at, 0);
        return tiff;
    }

    private static int align(int n) { return (n + 1) & ~1; }

    private static Entry ascii(int tag, String v) {
        final byte[] b = v.getBytes(StandardCharsets.US_ASCII);
        final byte[] out = new byte[b.length + 1];        // the spec's trailing NUL
        System.arraycopy(b, 0, out, 0, b.length);
        return new Entry(tag, T_ASCII, out);
    }

    private static Entry userComment(String v) {
        final byte[] body = v.getBytes(StandardCharsets.US_ASCII);
        final byte[] out = new byte[8 + body.length];
        final byte[] id = {'A', 'S', 'C', 'I', 'I', 0, 0, 0};
        System.arraycopy(id, 0, out, 0, 8);
        System.arraycopy(body, 0, out, 8, body.length);
        return new Entry(0x9286, T_UNDEFINED, out);
    }

    private static Entry shorts(int tag, int v) {
        return new Entry(tag, T_SHORT, new byte[]{(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF)});
    }

    private static Entry longs(int tag, long v) {
        final byte[] b = new byte[4];
        put32(b, 0, v);
        return new Entry(tag, T_LONG, b);
    }

    private static Entry rational(int tag, long num, long den) {
        final byte[] b = new byte[8];
        put32(b, 0, num);
        put32(b, 4, den);
        return new Entry(tag, T_RATIONAL, b);
    }

    private static void put16(byte[] b, int at, int v) {
        b[at] = (byte) (v & 0xFF);
        b[at + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static void put32(byte[] b, int at, long v) {
        b[at] = (byte) (v & 0xFF);
        b[at + 1] = (byte) ((v >> 8) & 0xFF);
        b[at + 2] = (byte) ((v >> 16) & 0xFF);
        b[at + 3] = (byte) ((v >> 24) & 0xFF);
    }

    // ------------------------------------------------------------------ parse

    /**
     * "1/125" / "0.008" / "2.5" to a rational, or null when it is not a number.
     *
     * <p>A decimal is scaled by {@code scale} so a half-stop of aperture keeps
     * its digit; the pair is then reduced so 2.8 does not store as 2800000/1000000.
     */
    private static long[] rationalOf(String text, long scale) {
        if (!filled(text)) return null;
        final String s = text.trim();
        try {
            long num, den;
            final int slash = s.indexOf('/');
            if (slash > 0) {
                num = Math.round(Double.parseDouble(s.substring(0, slash)));
                den = Math.round(Double.parseDouble(s.substring(slash + 1)));
            } else {
                final double v = Double.parseDouble(s);
                num = Math.round(v * scale);
                den = scale;
            }
            if (den == 0 || num <= 0) return null;
            final long g = gcd(Math.abs(num), Math.abs(den));
            return new long[]{num / g, den / g};
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long gcd(long a, long b) { while (b != 0) { final long t = a % b; a = b; b = t; } return a == 0 ? 1 : a; }

    private static long longOf(String text) {
        if (!filled(text)) return 0;
        try { return Math.round(Double.parseDouble(text.trim())); } catch (Throwable ignored) { return 0; }
    }

    private static boolean filled(String s) { return s != null && !s.trim().isEmpty() && !"null".equals(s); }

    // ----------------------------------------------------------------- splice

    /**
     * Puts {@code app1} in front of the image data and leaves everything else
     * exactly as it was.
     *
     * <p>Rewrites to a temporary beside the file and renames over it, so a
     * failure halfway cannot leave a half-written export. The compressed data is
     * streamed, never loaded: a hundred-megapixel export is forty megabytes.
     *
     * @return true when the segment landed; false means the file was left alone
     */
    public static boolean inject(File file, byte[] app1) {
        if (file == null || app1 == null || app1.length < 10) return false;
        File tmp = null;
        try {
            final byte[] head = new byte[65536];
            final int have;
            try (InputStream in = new FileInputStream(file)) {
                have = readFully(in, head);
            }
            if (have < 4 || (head[0] & 0xFF) != 0xFF || (head[1] & 0xFF) != 0xD8) return false;

            // The JFIF APP0 stays first, Exif goes next, and everything else --
            // the ICC in APP2, the tables -- follows untouched. An APP1 already in
            // the chain is dropped rather than duplicated, so running this twice
            // leaves the same file.
            final ByteArrayOutputStream prefix = new ByteArrayOutputStream();
            int pos = 2;
            boolean complete = false;
            while (pos + 4 <= have) {
                if ((head[pos] & 0xFF) != 0xFF) break;
                final int marker = head[pos + 1] & 0xFF;
                if (marker == 0xE1) {
                    final int len = ((head[pos + 2] & 0xFF) << 8) | (head[pos + 3] & 0xFF);
                    if (len < 2 || pos + 2 + len > have) break;
                    pos += 2 + len;                   // an old segment: replaced
                    continue;
                }
                if (marker != 0xE0) { complete = true; break; }
                final int len = ((head[pos + 2] & 0xFF) << 8) | (head[pos + 3] & 0xFF);
                if (len < 2 || pos + 2 + len > have) break;
                prefix.write(head, pos, 2 + len);
                pos += 2 + len;
            }
            if (!complete) {
                // Either the chain is longer than the buffer -- a sizeable ICC,
                // which is legitimate and puts APP1 straight after SOI, where
                // readers accept it -- or the file ended in the middle of it,
                // which means the export is truncated and there is nothing here
                // worth enhancing.
                if (have < head.length) return false;
                pos = 2;
                prefix.reset();
            }

            tmp = new File(file.getParentFile(), file.getName() + ".exif.tmp");
            try (OutputStream out = new FileOutputStream(tmp);
                 InputStream in = new FileInputStream(file)) {
                out.write(0xFF); out.write(0xD8);
                prefix.writeTo(out);
                out.write(app1);
                long skip = pos;
                while (skip > 0) {
                    final long n = in.skip(skip);
                    if (n <= 0) break;
                    skip -= n;
                }
                final byte[] buf = new byte[262144];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (!tmp.renameTo(file)) {
                // Same directory, so this only fails on a locked file; put it back.
                try { file.delete(); } catch (Throwable ignored) { }
                if (!tmp.renameTo(file)) return false;
            }
            tmp = null;
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (tmp != null) { try { tmp.delete(); } catch (Throwable ignored) { } }
        }
    }

    /**
     * Whether the file already carries an APP1 segment.
     *
     * <p>The encoder writes one itself when it has the source's record --
     * `ffc_encode` takes the Exif the decoder captured, which is a fuller copy
     * than anything can be rebuilt from the outside. This is how a caller knows
     * not to write a second, poorer one on top of it.
     */
    public static boolean hasExif(File file) {
        try (InputStream in = new FileInputStream(file)) {
            final byte[] head = new byte[65536];
            final int have = readFully(in, head);
            if (have < 4 || (head[0] & 0xFF) != 0xFF || (head[1] & 0xFF) != 0xD8) return false;
            int pos = 2;
            while (pos + 4 <= have) {
                if ((head[pos] & 0xFF) != 0xFF) return false;
                final int marker = head[pos + 1] & 0xFF;
                if (marker < 0xE0 || marker > 0xEF) return false;
                if (marker == 0xE1) return true;
                final int len = ((head[pos + 2] & 0xFF) << 8) | (head[pos + 3] & 0xFF);
                if (len < 2 || pos + 2 + len > have) return false;
                pos += 2 + len;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The frame's pixel size from its SOF, or null. */
    public static int[] sizeOf(File file) {
        try (InputStream in = new FileInputStream(file)) {
            final byte[] soi = new byte[2];
            if (readFully(in, soi) < 2) return null;
            if ((soi[0] & 0xFF) != 0xFF || (soi[1] & 0xFF) != 0xD8) return null;
            final byte[] b = new byte[2];        // the marker, nothing else
            while (true) {
                if (readFully(in, b) < 2) return null;
                if ((b[0] & 0xFF) != 0xFF) return null;
                final int marker = b[1] & 0xFF;
                if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) continue;
                if (marker == 0xD9 || marker == 0xDA) return null;
                final byte[] len = new byte[2];
                if (readFully(in, len) < 2) return null;
                final int size = ((len[0] & 0xFF) << 8) | (len[1] & 0xFF);
                if (size < 2) return null;
                if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    final byte[] sof = new byte[5];
                    if (readFully(in, sof) < 5) return null;
                    final int h = ((sof[1] & 0xFF) << 8) | (sof[2] & 0xFF);
                    final int w = ((sof[3] & 0xFF) << 8) | (sof[4] & 0xFF);
                    return new int[]{w, h};
                }
                long skip = size - 2;
                while (skip > 0) {
                    final long n = in.skip(skip);
                    if (n <= 0) return null;
                    skip -= n;
                }
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            final int n = in.read(b, off, b.length - off);
            if (n <= 0) break;
            off += n;
        }
        return off;
    }
}
