package app.spektralab.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * Where a photograph was taken, read out of its own GPS IFD.
 *
 * <p>**A phone's raw file usually has none.** The camera writes the position into
 * the JPEG it develops beside the raw -- same folder, same base name -- and the
 * DNG that comes out of the same shutter press carries no GPS at all. So
 * "keep the location" means reading the sibling JPEG, which is why this class
 * lives next to the parser that reads capture fields rather than inside the
 * export writer.
 *
 * <p>Plain {@code java.io}, no Android types: the same bytes are read on a
 * desktop JVM, against a file whose tags exiftool has already confirmed, and
 * that is the only thing that makes a hand-written IFD walk trustworthy.
 *
 * <p>Read-only. Writing is {@link JpegExif}'s job -- it already owns the APP1
 * segment the exports carry, and GPS is one more IFD in it.
 */
public final class GpsExif {

    private GpsExif() { }

    /**
     * One fix, in the units EXIF keeps: degrees, metres, and the two strings the
     * standard stores beside them.
     *
     * <p>Signed degrees rather than the ref letters, because every consumer of
     * this -- a map, a gallery, a place name -- wants a number; `belowSea` and
     * the two strings are what the writer has to put back.
     */
    public static final class Fix {
        public double lat;
        public double lon;
        public double alt;
        public boolean belowSea;
        /** EXIF's own spellings: "10:30:00" and "2026:02:05". */
        public String time = "";
        public String date = "";

        /**
         * Is there a position in here?
         *
         * <p>0,0 is the Gulf of Guinea and also the default of every field that
         * was never filled, and a fix on the equator at Greenwich is not
         * something a phone camera produces. Both are treated as "no fix" --
         * writing 0,0 would put a photograph in the Atlantic.
         */
        public boolean usable() {
            if (Math.abs(lat) > 90.0 || Math.abs(lon) > 180.0) return false;
            return Math.abs(lat) > 1e-6 || Math.abs(lon) > 1e-6;
        }

        /** One line, for a preference file or a JNI call. */
        public String encode() {
            return String.format(Locale.US, "%.6f,%.6f,%.3f,%d,%s,%s",
                    lat, lon, alt, belowSea ? 1 : 0, time, date);
        }

        /** The other end of {@link #encode}; null when the line is not one. */
        public static Fix decode(String line) {
            if (line == null) return null;
            final String[] f = line.split(",", 6);
            if (f.length < 3) return null;
            final Fix fix = new Fix();
            try {
                fix.lat = Double.parseDouble(f[0]);
                fix.lon = Double.parseDouble(f[1]);
                fix.alt = Double.parseDouble(f[2]);
                if (f.length > 3) fix.belowSea = "1".equals(f[3].trim());
                if (f.length > 4) fix.time = f[4].trim();
                if (f.length > 5) fix.date = f[5].trim();
            } catch (Throwable ignored) {
                return null;
            }
            return fix.usable() ? fix : null;
        }

        /** For a log line: what was found, without a paragraph of prose. */
        public String describe() {
            return String.format(Locale.US, "%.6f,%.6f alt=%.1f%s %s %s",
                    lat, lon, alt, belowSea ? " below sea" : "", date, time);
        }
    }

    /**
     * How much of a file to read before the walk.
     *
     * <p>A JPEG keeps its APP1 in the first few kilobytes, but a **DNG is a TIFF
     * and a TIFF writer may put the Exif IFD anywhere** -- the OPPO files this
     * was measured against carry it at 728 KB, past any JPEG-sized window, and
     * the symptom was a raw that looked like it had no position while exiftool
     * read one. A megabyte covers every layout met so far; a file that keeps it
     * further out simply reports no fix, which is the honest answer.
     */
    public static final int WINDOW = 1 << 20;

    /** The fix in {@code head} (a JPEG's first bytes), or null when it has none. */
    public static Fix read(byte[] head) {
        if (head == null || head.length < 32) return null;
        int tiffAt = -1, tiffLen = 0;
        if ((head[0] & 0xFF) != 0xFF || (head[1] & 0xFF) != 0xD8) {
            // **A DNG is a TIFF, and some cameras do write the fix into it.**
            // Then no sibling is needed at all -- the raw being developed has
            // its own position, and reading it here is the whole answer.
            if (isTiff(head, 0)) {
                tiffAt = 0;
                tiffLen = head.length;
            } else {
                return null;
            }
        }

        // **One SOI walk.** Segment lengths are bounded by the buffer, and the
        // scan stops at the first SOS: everything after it is entropy-coded and
        // cannot contain a marker that means anything here.
        int pos = 2;
        while (tiffAt < 0 && pos + 4 <= head.length) {
            final int marker = head[pos + 1] & 0xFF;
            if ((head[pos] & 0xFF) != 0xFF) return null;
            if (marker == 0xD8) { pos += 2; continue; }
            if (marker == 0xD9 || marker == 0xDA) break;
            final int len = ((head[pos + 2] & 0xFF) << 8) | (head[pos + 3] & 0xFF);
            if (len < 2 || pos + 2 + len > head.length) return null;
            if (marker == 0xE1 && len >= 8
                    && head[pos + 4] == 'E' && head[pos + 5] == 'x' && head[pos + 6] == 'i'
                    && head[pos + 7] == 'f' && head[pos + 8] == 0 && head[pos + 9] == 0) {
                tiffAt = pos + 10;
                tiffLen = len - 8;
                break;
            }
            pos += 2 + len;
        }
        if (tiffAt < 0 || tiffAt + 8 > head.length) return null;

        final boolean le;
        if (head[tiffAt] == 'I' && head[tiffAt + 1] == 'I') le = true;
        else if (head[tiffAt] == 'M' && head[tiffAt + 1] == 'M') le = false;
        else return null;
        final Tiff t = new Tiff(head, tiffAt, Math.min(head.length, tiffAt + tiffLen), le);
        if (t.u16(2) != 42) return null;

        final int ifd0 = (int) t.u32(4);
        // **IFD0 first, then the Exif sub-IFD.** The standard puts the pointer in
        // IFD0 and nearly every writer obliges; a few camera raws carry it one
        // level down, and looking there costs four bytes.
        int gpsPointer = t.find(ifd0, 0x8825);
        if (gpsPointer < 0) {
            final int exifIfd = t.find(ifd0, 0x8769);
            if (exifIfd >= 0) gpsPointer = t.find((int) t.u32(exifIfd + 8), 0x8825);
        }
        if (gpsPointer < 0) return null;
        final int gps = (int) t.u32(gpsPointer + 8);
        if (!t.has(gps, 2)) return null;

        final Fix fix = new Fix();
        final double[] d = new double[3];
        // Latitude and longitude are three rationals each: degrees, minutes,
        // seconds. The reference letter carries the sign, not the number.
        if (t.rationals(gps, 0x0002, 3, d)) {
            fix.lat = d[0] + d[1] / 60.0 + d[2] / 3600.0;
            if ("S".equalsIgnoreCase(t.ascii(gps, 0x0001, 2))) fix.lat = -fix.lat;
        }
        if (t.rationals(gps, 0x0004, 3, d)) {
            fix.lon = d[0] + d[1] / 60.0 + d[2] / 3600.0;
            if ("W".equalsIgnoreCase(t.ascii(gps, 0x0003, 2))) fix.lon = -fix.lon;
        }
        if (t.rationals(gps, 0x0006, 1, d)) fix.alt = d[0];
        fix.belowSea = t.bytes(gps, 0x0005, 1) == 1;
        if (t.rationals(gps, 0x0007, 3, d)) {
            fix.time = String.format(Locale.US, "%02d:%02d:%02d",
                    Math.round(d[0]), Math.round(d[1]), Math.round(d[2]));
        }
        fix.date = t.ascii(gps, 0x001D, 11);
        return fix.usable() ? fix : null;
    }

    /** `II*\0` or `MM\0*`, the two ways a TIFF header spells forty-two. */
    private static boolean isTiff(byte[] b, int at) {
        if (b == null || at + 4 > b.length) return false;
        if (b[at] == 'I' && b[at + 1] == 'I' && b[at + 2] == 42 && b[at + 3] == 0) return true;
        return b[at] == 'M' && b[at + 1] == 'M' && b[at + 2] == 0 && b[at + 3] == 42;
    }

    /** The same, from a file (its first {@value #WINDOW} bytes are enough). */
    public static Fix read(File file) {
        if (file == null) return null;
        try (InputStream in = new FileInputStream(file)) {
            final byte[] head = new byte[WINDOW];
            int off = 0, n;
            while (off < head.length && (n = in.read(head, off, head.length - off)) > 0) off += n;
            if (off < 32) return null;
            if (off == head.length) return read(head);
            final byte[] exact = new byte[off];
            System.arraycopy(head, 0, exact, 0, off);
            return read(exact);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ IFD walk

    /** A bounded view of one TIFF block: every offset checked before it is read. */
    private static final class Tiff {
        private final byte[] b;
        private final int base, end;
        private final boolean le;

        Tiff(byte[] b, int base, int end, boolean le) {
            this.b = b; this.base = base; this.end = end; this.le = le;
        }

        boolean has(int off, int n) {
            return off >= 0 && n >= 0 && base + off + n <= end;
        }

        int u16(int off) {
            if (!has(off, 2)) return 0;
            final int a = b[base + off] & 0xFF, c = b[base + off + 1] & 0xFF;
            return le ? (a | (c << 8)) : ((a << 8) | c);
        }

        long u32(int off) {
            if (!has(off, 4)) return 0;
            final long a = b[base + off] & 0xFF, c = b[base + off + 1] & 0xFF;
            final long d = b[base + off + 2] & 0xFF, e = b[base + off + 3] & 0xFF;
            return le ? (a | (c << 8) | (d << 16) | (e << 24))
                      : ((a << 24) | (c << 16) | (d << 8) | e);
        }

        /** The entry for {@code tag} inside the IFD at {@code ifd}, or -1. */
        int find(int ifd, int tag) {
            if (!has(ifd, 2)) return -1;
            final int n = u16(ifd);
            for (int i = 0; i < n; ++i) {
                final int e = ifd + 2 + i * 12;
                if (!has(e, 12)) return -1;
                if (u16(e) == tag) return e;
            }
            return -1;
        }

        /** Where a tag's value bytes live, and how many there are. */
        private int valueAt(int entry, int unit, int count, int[] lenOut) {
            final int bytes = unit * count;
            lenOut[0] = bytes;
            return bytes <= 4 ? entry + 8 : (int) u32(entry + 8);
        }

        /** `count` rationals out of a tag: {num, den} pairs, 8 bytes each. */
        boolean rationals(int ifd, int tag, int count, double[] out) {
            final int e = find(ifd, tag);
            if (e < 0) return false;
            final int[] len = new int[1];
            final int at = valueAt(e, 8, count, len);
            if (!has(at, 8 * count)) return false;
            for (int i = 0; i < count; ++i) {
                final long num = u32(at + i * 8);
                final long den = u32(at + i * 8 + 4);
                out[i] = den == 0 ? 0.0 : (double) num / (double) den;
            }
            return true;
        }

        /** A one-byte tag (GPSAltitudeRef), 0 when absent. */
        int bytes(int ifd, int tag, int count) {
            final int e = find(ifd, tag);
            if (e < 0) return 0;
            final int[] len = new int[1];
            final int at = valueAt(e, 1, count, len);
            return has(at, 1) ? (b[base + at] & 0xFF) : 0;
        }

        /** An ASCII tag, NULs and trailing spaces dropped. */
        String ascii(int ifd, int tag, int count) {
            final int e = find(ifd, tag);
            if (e < 0) return "";
            final int[] len = new int[1];
            final int at = valueAt(e, 1, count, len);
            if (!has(at, 1)) return "";
            final StringBuilder s = new StringBuilder();
            for (int i = 0; i < count && has(at + i, 1); ++i) {
                final int c = b[base + at + i] & 0xFF;
                if (c == 0) break;
                s.append((char) c);
            }
            return s.toString().trim();
        }
    }
}
