package app.spektralab.engine;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The second engine: fotufilm, ported to C++ and driven from one call.
 *
 * <p>It is a different machine from {@link SpektraLabEngine} and deliberately
 * so. Its film responses come from measured spectral data, its print chain is a
 * Halide kernel, and it renders on the CPU. What it does <em>not</em> have is
 * everything the first engine's app layer is built around: no rebate, no date
 * imprint, no crop or rotation, no live preview tier, no TIF/DNG writer. So the
 * two coexist rather than one replacing the other: a stock id carrying the
 * {@code fotu:} prefix is handed to this class, everything else stays with the
 * Vulkan engine.
 *
 * <p>The stock list is {@code assets/fotufilm/catalog.txt}, generated from the
 * engine's own stock definitions (id, name, subtitle, and the three flags that
 * decide which media list a stock may be printed on). The 47 skeletons under
 * {@code assets/fotufilm/cfg/<stock>/<medium>/} were baked by the Swift engine
 * and are byte-identical to what it renders from -- that is why this port can
 * leave the models and the tables alone and still match it.
 */
public final class FotuFilm {

    /**
     * Loaded on first use, not with the class.
     *
     * <p>The shelf reads {@code catalog.txt} at launch to know that this engine
     * has 47 stocks. That is all the launch needs; the two native libraries are
     * six megabytes and only the first render wants them. Loading them in a
     * static block put that cost -- and, on a device that refuses them, an
     * error -- on the path every existing user walks before seeing anything.
     */
    private static boolean loaded;

    private static void load() {
        if (loaded) return;
        synchronized (FotuFilm.class) {
            if (loaded) return;
            // The host library names the codec library and libc++ in DT_NEEDED;
            // loading the codecs first makes a missing one a clean
            // UnsatisfiedLinkError here rather than a relocation failure inside
            // the first render.
            System.loadLibrary("fotufilmcodecs");
            System.loadLibrary("fotufilmhost");
            System.loadLibrary("fotufilm_jni");
            loaded = true;
        }
    }

    private FotuFilm() { }

    /** A stock id belongs to this engine when it carries this prefix. */
    public static final String PREFIX = "fotu:";

    /** The digital reference: no sheet at all. */
    public static final String SCREEN = "screen";

    /**
     * The two media with no print material.
     *
     * <p>Declared before the tables below: a static initialiser may not forward-reference a
     * field, and these are entries in all three of them.
     */
    public static final String NEGATIVE = "negative";
    public static final String CINEON_LOG = "cineon-log";

    /**
     * The sheets the port ships, in viewing order. The directory names and the
     * table file names do not share a spelling (`paper_ilfochrome_cps1k.bin`
     * sits under `ilfochrome-cps-1k`), so the mapping is spelled out.
     */
    public static final String[] MEDIUMS = {
            "crystal-archive", "ektacolor-edge", "endura-premier",
            "vision-2383", "vision-2393",
            "eterna-cp", "eterna-cp-3521xd",
            "exr-5386", "agfa-cp30",
            "ilfochrome-cps-1k", "ilfochrome-clm-1k",
            SCREEN,
            // **Appended, never inserted.** The engine persists a medium's *index* in some hosts,
            // so a new medium goes on the end. These two have no print material: `.negative`
            // shows the developed dyes on a light box, `.cineon-log` writes their printing
            // density as 10-bit Cineon code values for a grading suite.
            NEGATIVE, CINEON_LOG,
    };

    public static final String[] MEDIUM_LABELS = {
            "Crystal Archive", "Ektacolor Edge", "Endura Premier",
            "Vision 2383", "Vision 2393",
            "Eterna CP", "Eterna CP 3521XD",
            "EXR 5386", "Agfa CP30",
            "Ilfochrome CPS-1K", "Ilfochrome CLM-1K",
            "数字参考 · 无相纸",
            "Negative · 负片", "Cineon Log · 调色",
    };

    /** The table each medium is printed through; the screen ignores its sheet. */
    private static final String[] MEDIUM_BINS = {
            "paper_crystal_archive.bin", "paper_ektacolor_edge.bin", "paper_endura_premier.bin",
            "paper_vision_2383.bin", "paper_vision_2393.bin",
            "paper_eterna_cp.bin", "paper_eterna_cp_3521xd.bin",
            "paper_exr_5386.bin", "paper_agfa_cp30.bin",
            "paper_ilfochrome_cps1k.bin", "paper_ilfochrome_clm1k.bin",
            "paper_ektacolor_edge.bin",
            // A medium, not a bin: the host recognises these two by name and takes the paper
            // stage out of the picture (`host.cpp`, `medium_negative` / `medium_cineon`).
            NEGATIVE, CINEON_LOG,
    };

    /** Whether this medium prints onto a sheet at all. */
    public static boolean hasSheet(String medium) {
        return medium != null && !NEGATIVE.equals(medium) && !CINEON_LOG.equals(medium);
    }

    /**
     * Transparencies and integral instant sheets carry no negative -- the film
     * is already a positive -- so there is no printing step and the media list
     * is the screen alone. `PrintPaper.choices(for:)` in the engine says the
     * same thing; offering a negative sheet to one of these takes the printing
     * path and comes out wrong.
     */
    public static final Set<String> DIRECT_READ = new HashSet<>(Arrays.asList(
            "aerochrome1443", "agfachrome100", "agfachrome200", "agfachrome50", "astia100f",
            "ektachromee100", "infraredcolor", "instaxmini", "instaxwide", "kodachrome200",
            "kodachrome25", "kodachrome64", "provia100f", "velvia50"));

    /** One stock, as the shelf and the paper picker need it. */
    public static final class Stock {
        public final String id, name, subtitle, brand;
        public final boolean monochrome, reversal, reflection;

        Stock(String id, String name, String subtitle, boolean monochrome,
              boolean reversal, boolean reflection) {
            this.id = id;
            this.name = name;
            this.subtitle = subtitle;
            this.monochrome = monochrome;
            this.reversal = reversal;
            this.reflection = reflection;
            int cut = subtitle.indexOf('·');
            this.brand = (cut > 0 ? subtitle.substring(0, cut) : subtitle).trim();
        }

        /** The word the card carries after the name: 反转 / 黑白 / 一次成像. */
        public String kindMark() {
            if (reflection) return "一次成像";
            if (reversal) return "反转";
            if (monochrome) return "黑白";
            return "";
        }
    }

    /** Reads the catalogue that ships beside the skeletons. */
    public static List<Stock> readCatalog(Context context) {
        final List<Stock> out = new ArrayList<>();
        try (InputStream in = context.getAssets().open("fotufilm/catalog.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                final String[] f = line.split("\\|", -1);
                if (f.length < 6) continue;
                out.add(new Stock(f[0], f[1], f[2],
                        "1".equals(f[3]), "1".equals(f[4]), "1".equals(f[5])));
            }
        } catch (Throwable ignored) {
            // An APK without the assets can still report why: the shelf simply
            // stays empty and the first tap says the resources are missing.
        }
        return out;
    }

    /**
     * Which media a stock may be printed on, in {@link #MEDIUMS} order.
     *
     * <p>Three lists, exactly as the engine's own availability table: a
     * negative or a silver film takes the nine projected colour sheets and the
     * screen; a transparency that is not instant takes the screen and the two
     * Ilfochrome sheets; an integral instant film takes the screen alone.
     */
    public static List<String> mediaFor(Stock stock) {
        final List<String> out = new ArrayList<>();
        if (stock == null) return out;
        if (DIRECT_READ.contains(stock.id)) {
            out.add(SCREEN);
            if (!stock.reflection) {
                out.add("ilfochrome-cps-1k");
                out.add("ilfochrome-clm-1k");
            }
            return out;
        }
        for (String medium : MEDIUMS) {
            if (!SCREEN.equals(medium) && medium.startsWith("ilfochrome")) continue;
            out.add(medium);
        }
        return out;
    }

    public static String mediumLabel(String medium) {
        final int i = indexOfMedium(medium);
        return i >= 0 ? MEDIUM_LABELS[i] : medium;
    }

    public static int indexOfMedium(String medium) {
        for (int i = 0; i < MEDIUMS.length; ++i) if (MEDIUMS[i].equals(medium)) return i;
        return -1;
    }

    public static String mediumBin(String medium) {
        final int i = indexOfMedium(medium);
        return i >= 0 ? MEDIUM_BINS[i] : MEDIUM_BINS[MEDIUM_BINS.length - 1];
    }

    /** The medium a stock starts on: the screen for a direct-read stock. */
    public static String defaultMedium(String stockId) {
        return DIRECT_READ.contains(stockId) ? SCREEN : "crystal-archive";
    }

    public static boolean isFotu(String stock) {
        return stock != null && stock.startsWith(PREFIX);
    }

    public static String idOf(String stock) {
        return isFotu(stock) ? stock.substring(PREFIX.length()) : stock;
    }

    // ------------------------------------------------------------------ assets

    /**
     * Copies `assets/fotufilm` into `filesDir` once per installed build.
     *
     * <p>The native side opens these by path -- a zip inside the APK is not a
     * file system -- and it is 32 MB, so the copy is keyed by the version code
     * and skipped on every launch after the first.
     */
    public static File unpack(Context context, File root, String version) throws IOException {
        final File stamp = new File(root, ".unpacked-" + version);
        if (stamp.isFile() && intact(root)) return root;
        copyTree(context, "fotufilm", root);
        // Not fatal if the marker cannot be written: the tree is complete, the
        // marker only saves the copy on the next launch.
        stamp.createNewFile();
        return root;
    }

    /**
     * The few files the native side opens by name, all of them.
     *
     * <p>The marker alone says "this build unpacked once", not "the tree is
     * still here": a cleaner that trims `filesDir`, a copy interrupted by a full
     * disk, or a build that shipped a new sheet without a new marker all leave
     * it in place while a file it names is gone. The failure then surfaces as
     * `fotufilm 渲染失败（返回 2）` -- the host could not read a table -- with
     * nothing pointing at the cause. Fifteen `stat` calls once per launch turn
     * that into a re-copy.
     */
    private static boolean intact(File root) {
        if (!new File(root, "cfg").isDirectory()) return false;
        if (!present(new File(root, "stocks.bin"))) return false;
        if (!present(new File(root, "catalog.txt"))) return false;
        // Without this one the exposure table cannot be built at all and every
        // render returns 2; it is also the smallest file in the tree.
        if (!present(new File(root, "rec2020-reflectance-prior.coeff"))) return false;
        for (String bin : MEDIUM_BINS) {
            if (!present(new File(root, bin))) return false;
        }
        return true;
    }

    private static boolean present(File f) {
        try { return f.isFile() && f.length() > 0; } catch (Throwable t) { return false; }
    }

    private static void copyTree(Context context, String assetPath, File destination) throws IOException {
        final String[] children = context.getAssets().list(assetPath);
        if (children == null || children.length == 0) {
            final File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create " + parent);
            }
            try (InputStream in = context.getAssets().open(assetPath);
                 OutputStream out = new FileOutputStream(destination)) {
                final byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            }
            return;
        }
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("cannot create " + destination);
        }
        for (String child : children) {
            copyTree(context, assetPath + "/" + child, new File(destination, child));
        }
    }

    // ------------------------------------------------------------------ render

    /**
     * Develops one photograph.
     *
     * @param resources    the unpacked `fotufilm` directory (stocks.bin, sheets)
     * @param configPrefix one frame's skeleton, `.../cfg/&lt;stock&gt;/&lt;medium&gt;/`
     * @param photo        a camera raw, JPEG, PNG or TIFF, by path
     * @param out          where the JPEG lands
     * @param maxEdge      long edge of the output; 0 keeps the photograph's own
     * @param slots        direct configuration writes, "30=0.5;mul:31=2;add:456=0.3"
     * @return 0 on success, 3 when cancelled, anything else is a failure the
     *         caller reports as such
     */
    private static final Object RENDER_LOCK = new Object();

    public static int process(String resources, String configPrefix, String photo,
                              String out, String stock, String paper,
                              int maxEdge, boolean screenMedium,
                              float ev, float highlights, float shadows,
                              float saturation, float vibrance, float quality,
                              float warmth, float tint, float flare,
                              float gradeLift, float gradeGain,
                              float expiredYears, float bleach,
                              String tableCache, String meterPrefix, float pushStops,
                              String slots) {
        load();
        // **One render at a time.** The host keeps the decoded scene and the
        // spectral tables in process-wide statics between calls -- that is what
        // makes a card forty milliseconds -- so two calls in flight would have
        // one thread reading a scene the other is replacing. The app already
        // serialises its user-visible renders; this covers the shelf queue and
        // the live frame, which do not wait for each other.
        synchronized (RENDER_LOCK) {
            return nativeProcess(resources, configPrefix, photo, out, stock, paper,
                    maxEdge, screenMedium, ev, highlights, shadows, saturation, vibrance,
                    quality, warmth, tint, flare, gradeLift, gradeGain,
                    expiredYears, bleach, tableCache, meterPrefix, pushStops, slots);
        }
    }

    /**
     * Pixels for an image the platform will not open -- a TIFF.
     *
     * <p>`BitmapFactory` and `ImageDecoder` both refuse TIFF, so a file from Lightroom, Photoshop
     * or a film scanner reached the import path with no bounds, no bitmap, and an error dialog
     * that said the app could not read photos. The codecs this engine already carries have
     * libtiff; this asks them, uprighted and reduced to `maxEdge`.
     *
     * @return `{width, height, ARGB...}`, or null when the file is not decodable either way.
     */
    public static int[] decodeImage(String path, int maxEdge) {
        load();
        return nativeDecodeImageToArgb(path, maxEdge);
    }

    private static native int[] nativeDecodeImageToArgb(String path, int maxEdge);

    private static native int nativeProcess(String resources, String configPrefix, String photo,
                                            String out, String stock, String paper,
                                            int maxEdge, boolean screenMedium,
                                            float ev, float highlights, float shadows,
                                            float saturation, float vibrance, float quality,
                                            float warmth, float tint, float flare,
                                            float gradeLift, float gradeGain,
                                            float expiredYears, float bleach,
                                            String tableCache, String meterPrefix, float pushStops,
                                            String slots);

    /** The development steps this stock lists, ascending; empty when none. */
    public static float[] pushStops(String resources, String stock) {
        load();
        return nativePushStops(resources, stock);
    }

    private static native float[] nativePushStops(String resources, String stock);

    /**
     * What the host printed during the last render, verbatim.
     *
     * <p>`printf` in an Android app goes nowhere, so a failure arrived as a
     * bare integer -- "fotufilm 渲染失败（返回 1）" with no reason was all the
     * app could ever say. The JNI points the two descriptors at a pipe for the
     * duration of the call and hands the text back here.
     */
    /**
     * The About page's line for this engine: its build stamp, then what shipped
     * beside it.
     *
     * <p>The count comes from `catalog.txt` in the assets (a few hundred bytes,
     * no unpack), so it answers even on a device that refuses the libraries --
     * in which case the line says so instead of pretending.
     */
    public static String buildInfo(Context context) {
        String line;
        try {
            load();
            line = nativeBuildInfo();
        } catch (Throwable t) {
            line = "fotufilm-native 未加载（" + t.getClass().getSimpleName() + "）";
        }
        final int stocks = readCatalog(context).size();
        if (stocks > 0) line += " · " + stocks + " 支胶片 · " + MEDIUMS.length + " 种媒介";
        return line;
    }

    private static native String nativeBuildInfo();

    public static String lastLog() {
        try {
            return nativeLastLog();
        } catch (Throwable t) {
            return null;
        }
    }

    private static native String nativeLastLog();

    /** Asks the render in flight to stop; the kernel call still finishes. */
    public static native void cancel();
}
