package app.spektralab.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * The capture fields, read straight out of the container's IFD0.
 *
 * <p>The platform's `ExifInterface` decides by the first four bytes whether a
 * file is one it knows: it wants `II*\0` or `MM\0*`. That covers JPEG, DNG,
 * and the TIFF-based raws of Nikon, Canon and Sony -- and it refuses every
 * Panasonic `.RW2` (`IIU\0`), every Olympus `.ORF` (`IIRO`), so a raw from
 * those cameras reaches the export with no model, no date and no exposure,
 * which is precisely the file a photographer keeps.
 *
 * <p>None of them are a different format. They are TIFF with a different magic
 * number in the same two bytes, the same byte order flag, the same four-byte
 * IFD0 offset and the same twelve-byte entries -- measured on a DC-GH5 RW2: its
 * IFD0 sits at the offset the field names, and carries `Make` = "Panasonic",
 * `Model` = "DC-GH5" and the Exif sub-IFD pointer exactly like a NEF. So this
 * reads the tags itself and leaves `ExifInterface` to the containers that
 * really are something else (HEIF and its relatives).
 *
 * <p>Read-only, no Android types, so it can be checked on a desktop JVM against
 * the same files the phone would export from.
 */
public final class TiffExif {

    private TiffExif() { }

    /** A window big enough for IFD0, the Exif sub-IFD and their strings. */
    private static final int WINDOW = 1 << 20;

    /**
     * {make, model, datetime, shutter, aperture, iso, focal}, empty strings
     * where the container does not say, or null when it is not a TIFF at all.
     */
    public static String[] read(String path) {
        if (path == null) return null;
        byte[] d = null;
        try (InputStream in = new FileInputStream(new File(path))) {
            d = new byte[WINDOW];
            int off = 0, n;
            while (off < d.length) {
                n = in.read(d, off, d.length - off);
                if (n <= 0) break;
                off += n;
            }
            if (off < d.length) {
                final byte[] exact = new byte[off];
                System.arraycopy(d, 0, exact, 0, off);
                d = exact;
            }
        } catch (Throwable t) {
            return null;
        }
        if (d.length < 16) return null;

        final boolean little = d[0] == 'I' && d[1] == 'I';
        if (!little && !(d[0] == 'M' && d[1] == 'M')) return null;
        final int magic = u16(d, 2, little);
        // 0x2A TIFF, 0x55 Panasonic RW2 ("IIU\0"), 0x4F52/0x524F Olympus ORF.
        if (magic != 0x2A && magic != 0x55 && magic != 0x4F52 && magic != 0x524F) return null;

        final int ifd0 = (int) u32(d, 4, little);
        if (ifd0 < 8 || ifd0 + 2 > d.length) return null;

        final String[] out = {"", "", "", "", "", "", ""};
        final int[] seen = new int[1];

        final long make = tag(d, ifd0, little, 0x010F, seen);
        if (make >= 0) out[0] = text(d, make, seen[0], little);
        final long model = tag(d, ifd0, little, 0x0110, seen);
        if (model >= 0) out[1] = text(d, model, seen[0], little);
        final long dateTime = tag(d, ifd0, little, 0x0132, seen);
        if (dateTime >= 0) out[2] = text(d, dateTime, seen[0], little);

        final long exifIfd = tag(d, ifd0, little, 0x8769, seen);
        if (exifIfd >= 0) {
            final int at = (int) value32(d, exifIfd, seen[0], little);
            if (at > 0 && at + 2 <= d.length) {
                final long original = tag(d, at, little, 0x9003, seen);
                if (original >= 0) {
                    final String s = text(d, original, seen[0], little);
                    if (!s.isEmpty()) out[2] = s;
                }
                out[3] = rational(d, at, little, 0x829A);   // ExposureTime
                out[4] = rational(d, at, little, 0x829D);   // FNumber
                out[5] = integer(d, at, little, 0x8827);    // ISO
                out[6] = rational(d, at, little, 0x920A);   // FocalLength
            }
        }
        // **Panasonic keeps the ISO in IFD0, not in the Exif sub-IFD.** Tag
        // 0x0017 of the PanasonicRaw table, and the value is the ISO verbatim:
        // checked against LibRaw's own reading on a file shot at 250 (0x0017 =
        // 250) and one at 400 (0x0017 = 400) -- two different numbers, so it is
        // not a constant, and the neighbouring tags match the published table
        // (0x000B = 34316 is the RW2 compression code). Only for magic 0x55;
        // Olympus ORF numbers its private tags differently.
        if (out[5].isEmpty() && magic == 0x55) out[5] = integer(d, ifd0, little, 0x0017);
        return out;
    }

    // ------------------------------------------------------------- ifd access

    /**
     * Where an entry's value lives, or -1 when the tag is absent.
     *
     * <p>`seen[0]` comes back as the entry's count and type, which the caller
     * needs to know whether the four value bytes hold the value or an offset.
     * The count is packed into the high half of the returned `long` and the
     * inline bytes into the low half, so one call answers both questions.
     */
    private static long tag(byte[] d, int ifd, boolean little, int want, int[] seen) {
        if (ifd + 2 > d.length) return -1;
        int count = u16(d, ifd, little);
        if (count < 0 || count > 512) return -1;
        for (int i = 0; i < count; ++i) {
            final int at = ifd + 2 + i * 12;
            if (at + 12 > d.length) return -1;
            if (u16(d, at, little) != want) continue;
            final int type = u16(d, at + 2, little);
            final int n = (int) u32(d, at + 4, little);
            seen[0] = n;
            seen[0] = (type << 24) | (n & 0xFFFFFF);
            // The four bytes are either the value or its offset; hand back both.
            return ((long) (at + 8) << 32) | (u32(d, at + 8, little) & 0xFFFFFFFFL);
        }
        return -1;
    }

    private static int typeOf(int seen) { return (seen >>> 24) & 0xFF; }

    private static int countOf(int seen) { return seen & 0xFFFFFF; }

    /** The offset the entry points at, or the value itself when it is inline. */
    private static long value32(byte[] d, long entry, int seen, boolean little) {
        final int at = (int) (entry >>> 32);
        if (at == 0 || at + 4 > d.length) return 0;
        final long inline = u32(d, at, little);
        final int type = typeOf(seen);
        final int count = countOf(seen);
        final int size = count * typeSize(type);
        if (size <= 4) return inline;
        return inline;
    }

    private static int typeSize(int type) {
        switch (type) {
            case 1: case 2: case 6: case 7: return 1;
            case 3: case 8: return 2;
            case 4: case 9: case 11: return 4;
            case 5: case 10: case 12: return 8;
            default: return 1;
        }
    }

    /** An ASCII value, wherever its bytes are. */
    private static String text(byte[] d, long entry, int seen, boolean little) {
        final int at = (int) (entry >>> 32);
        final int type = typeOf(seen);
        final int count = countOf(seen);
        if (type != 2 || at == 0 || count <= 0 || count > 4096) return "";
        int from;
        if (count <= 4) from = at;
        else {
            final long off = u32(d, at, little);
            if (off < 0 || off + count > d.length) return "";
            from = (int) off;
        }
        if (from + count > d.length) return "";
        int end = from;
        while (end < from + count && d[end] != 0) ++end;
        try {
            return new String(d, from, end - from, "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** A RATIONAL as a decimal: "0.005", "2.8". Empty when absent or zero. */
    private static String rational(byte[] d, int ifd, boolean little, int want) {
        final int[] seen = new int[1];
        final long e = tag(d, ifd, little, want, seen);
        if (e < 0) return "";
        final int at = (int) (e >>> 32);
        final int type = typeOf(seen[0]);
        final int count = countOf(seen[0]);
        double v = 0;
        long num = 0, den = 0;
        if (type == 5 && count >= 1) {
            final long off = u32(d, at, little);        // a rational is always 8 bytes
            if (off < 0 || off + 8 > d.length) return "";
            num = u32(d, (int) off, little);
            den = u32(d, (int) off + 4, little);
            if (den == 0) return "";
            v = (double) num / (double) den;
        } else if (type == 3) {
            v = u16(d, at, little);
        } else if (type == 4) {
            v = u32(d, at, little);
        }
        if (!(v > 0)) return "";
        // A shutter is written the way a shutter is spoken about: 1/15, not
        // 0.066667. Everything else -- f-numbers, focal lengths -- is a decimal.
        if (num > 0 && den > 0 && num < den) {
            long a = num, b = den;
            while (b != 0) { final long t = a % b; a = b; b = t; }
            final long g = a == 0 ? 1 : a;
            return (num / g) + "/" + (den / g);
        }
        final String s = String.format(java.util.Locale.US, "%.6f", v);
        int cut = s.length();
        while (cut > 1 && s.charAt(cut - 1) == '0') --cut;
        if (cut > 1 && s.charAt(cut - 1) == '.') --cut;
        return s.substring(0, cut);
    }

    /** A SHORT or LONG as an integer. */
    private static String integer(byte[] d, int ifd, boolean little, int want) {
        final int[] seen = new int[1];
        final long e = tag(d, ifd, little, want, seen);
        if (e < 0) return "";
        final int at = (int) (e >>> 32);
        final int type = typeOf(seen[0]);
        long v = -1;
        if (type == 3 && at + 2 <= d.length) v = u16(d, at, little);
        else if (type == 4 && at + 4 <= d.length) v = u32(d, at, little);
        return v > 0 ? String.valueOf(v) : "";
    }

    private static int u16(byte[] d, int at, boolean little) {
        if (at + 2 > d.length) return 0;
        final int a = d[at] & 0xFF, b = d[at + 1] & 0xFF;
        return little ? (a | (b << 8)) : ((a << 8) | b);
    }

    private static long u32(byte[] d, int at, boolean little) {
        if (at + 4 > d.length) return 0;
        long v = 0;
        for (int i = 0; i < 4; ++i) {
            final int shift = little ? 8 * i : 8 * (3 - i);
            v |= ((long) (d[at + i] & 0xFF)) << shift;
        }
        return v;
    }
}
