package app.spektralab.engine;

import java.nio.ByteBuffer;

/**
 * Java facade over the SpektraLab C ABI (spk_engine.h).
 */
public class SpektraLabEngine implements AutoCloseable {
    // **Not a `static {}` block on purpose.** A class initialiser that throws
    // leaves the class *erroneous*: the first access reports the real reason
    // (an UnsatisfiedLinkError, usually swallowed by a caller), and every later
    // one reports a bare `NoClassDefFoundError: app.spektralab.engine.
    // SpektraLabEngine` with nothing in it. That is exactly what E-9000 was
    // showing on the phones where the native library did not load: the cause was
    // reported once, to nobody, and the visible failure had lost it. Keeping the
    // failure in a field means every caller sees the cause, every time.
    private static volatile boolean nativeLoaded;
    private static volatile String nativeLoadError;
    private static volatile String nativeLibDir;

    /**
     * The app's own native-library directory, before anything native is used.
     *
     * Optional: without it the plain loader is tried, which is what a desktop
     * build of these classes would want. With it, every bundled object is
     * loaded by absolute path first.
     */
    public static void prepare(String libraryDir) {
        nativeLibDir = libraryDir;
        ensureLoaded();
    }

    /**
     * Loads the engine, and the objects it is linked against, once.
     *
     * <p>`libraw.so` names `libjpeg_8.so` and `libz_1.so` as DT_NEEDED. Those
     * two are jpeg-turbo and zlib, whose real sonames are `libjpeg.so.8` and
     * `libz.so.1` -- names an APK is not allowed to carry. The package
     * installer only extracts entries under `lib/` whose file name ends in
     * `.so` (NativeLibraryHelper checks exactly that), so shipped under their
     * upstream names they are silently left out of the install. Every library
     * that needs them then dies with `dlopen failed: library "libjpeg.so.8"
     * not found`, and the engine never loads. That was E-9000 on every device
     * whose installer is strict about it -- the reference device's is not,
     * which is why it took a user's screenshot to see it.
     *
     * <p>They are renamed at package time and the DT_NEEDED of everything that
     * names them is rewritten to match (app/build/build-libs.sh), so every
     * installed name is a plain `lib*.so`. Loading each one by absolute path
     * first still earns its keep: it puts the object in the namespace under its
     * soname before the engine asks for it, and it names the one that failed.
     */
    private static synchronized void ensureLoaded() {
        if (nativeLoaded) return;
        if (nativeLoadError != null) throw new RuntimeException(nativeLoadError);
        final String[] bundled = {
                "libc++_shared.so", "libjpeg_8.so", "libz_1.so",
                "liblcms2.so", "libraw.so", "libraw_r.so",
        };
        final StringBuilder tried = new StringBuilder();
        final String dir = nativeLibDir;
        if (dir != null && !dir.isEmpty()) {
            for (String name : bundled) {
                final java.io.File f = new java.io.File(dir, name);
                if (!f.isFile()) { tried.append(name).append("=missing "); continue; }
                try { System.load(f.getAbsolutePath()); }
                catch (Throwable e) { tried.append(name).append('=').append(brief(e)).append(' '); }
            }
            try {
                System.load(new java.io.File(dir, "libspektrafilm_engine.so").getAbsolutePath());
                nativeLoaded = true;
                return;
            } catch (Throwable e) {
                tried.append("engine=").append(brief(e)).append(' ');
            }
        }
        try {
            System.loadLibrary("spektrafilm_engine");
            nativeLoaded = true;
            return;
        } catch (Throwable e) {
            tried.append("loadLibrary=").append(brief(e));
        }
        nativeLoadError = "引擎库没有加载成功（libspektrafilm_engine.so）。" + tried;
        throw new RuntimeException(nativeLoadError);
    }

    /**
     * `Class: message` plus the cause chain. A dlopen failure names the missing
     * file or symbol in its *cause*, not in the top-level message, so the chain
     * is the part worth keeping.
     */
    private static String brief(Throwable t) {
        final StringBuilder b = new StringBuilder();
        for (Throwable c = t; c != null && b.length() < 400; c = c.getCause()) {
            if (b.length() > 0) b.append(" <- ");
            b.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
            if (c.getCause() == c) break;
        }
        return b.toString();
    }

    private final long handle;

    private SpektraLabEngine(long handle) {
        this.handle = handle;
    }

    /**
     * Install the native signal handler that writes a backtrace to `path`.
     *
     * <p>A Java exception arrives with a stack; a SIGSEGV inside a dispatch
     * arrives as "the process is gone", and this ROM does not report
     * `ApplicationExitInfo` either. Idempotent: the first call wins.
     */
    public static void installCrashHandler(String path) {
        try { nativeInstallCrashHandler(path); } catch (Throwable ignored) { }
    }

    public static SpektraLabEngine create(String resourcesDir) {
        return create(resourcesDir, null);
    }

    /**
     * @param logPath when non-null, the Vulkan backend writes its per-submit and
     *                per-allocation diagnostics to this file (the app cannot
     *                read stderr).
     */
    public static SpektraLabEngine create(String resourcesDir, String logPath) {
        ensureLoaded();
        if (logPath != null) {
            nativeSetEnv("SPEKTRAFILM_TRANSFER_TIMINGS", "1");
            nativeSetEnv("SPK_VK_LOG", logPath);
        }
        long handle = nativeCreateEngine(resourcesDir);
        if (handle == 0) throw new RuntimeException("Failed to create SpektraLab engine");
        return new SpektraLabEngine(handle);
    }

    /**
     * Debug only: an environment variable the native side reads when it creates
     * the Vulkan device.
     *
     * <p>`SPEKTRAFILM_STORAGE_LIMIT` is how a phone whose driver reports a 2 GiB
     * binding range is made to behave like one that reports 128 MiB -- which is
     * the device class the banded renderer exists for (docs/tiling.md, P0) and
     * which is not in this drawer. It can only lower the limit; the engine
     * refuses a value above what the driver advertises.
     */
    public static void setEnv(String name, String value) {
        ensureLoaded();
        nativeSetEnv(name, value);
    }

    public static String buildInfo() {
        ensureLoaded();
        return nativeGetBuildInfo();
    }

    /** The engine's `capabilities` JSON: backend, device, pool and memory types. */
    public String capabilities() {
        return nativeCapabilities(handle);
    }

    /** Buffer ledger: what the engine holds and where it was allocated. */
    /**
     * Drop the pooled render buffers that nothing is using, and say how many
     * bytes that gave back. Called after an export: the pool keeps a full
     * render's working set by design, and on a big frame that is more device
     * memory than the rest of the phone can afford to lose.
     */
    public long trimPool() {
        if (handle == 0) return 0;
        return nativeTrimPool(handle);
    }

    public String memoryReport() {
        return nativeMemoryReport(handle);
    }

    /**
     * A direct buffer outside the runtime's native-allocation ledger.
     *
     * <p>{@code ByteBuffer.allocateDirect} is capped by the heap's growth limit
     * -- 512 MB on most phones -- and a 50 MP frame's float32 is 600 MB, so the
     * allocation is refused while the device still has gigabytes free. Free it
     * with {@link #freeDirect}; there is no cleaner behind this one.
     */
    public static ByteBuffer allocDirect(long bytes) { return nativeAllocDirect(bytes); }

    /** Release a buffer from {@link #allocDirect}. The caller must drop it too. */
    public static void freeDirect(ByteBuffer buffer) { if (buffer != null) nativeFreeDirect(buffer); }

    /**
     * Drop the open RAW file's unpacked data.
     *
     * <p>Decoding keeps the LibRaw object alive so a white-balance change only
     * re-runs `dcraw_process` (2.4 s instead of 3.0 s at 20 MP). That object
     * holds the sensor plane and a demosaiced copy, so it is dropped when the
     * picture changes rather than kept for a frame nobody is looking at.
     */
    public static void clearRawCache() { nativeClearRawCache(); }

    private static native void nativeClearRawCache();
    private static native ByteBuffer nativeAllocDirect(long bytes);
    private static native void nativeFreeDirect(ByteBuffer buffer);

    public Session openSession(ByteBuffer pixels, int width, int height, int channels) {
        if (!pixels.isDirect()) throw new IllegalArgumentException("pixels must be direct");
        long sessionHandle = nativeOpenSession(handle, pixels, width, height, channels);
        if (sessionHandle == 0) throw new RuntimeException("Failed to open session");
        return new Session(sessionHandle, width, height);
    }

    /**
     * Open a session from a RAW file path. Decodes via LibRaw internally.
     * The paramsJson is applied at spk_open time.
     */
    public Session openRawSession(String filePath, String paramsJson) {
        return openRawSession(filePath, paramsJson, 0, 0);
    }

    /** Opens a RAW session, optionally bounding the source by area and long edge. */
    public Session openRawSession(String filePath, String paramsJson, int maxPixels, int maxLongEdge) {
        long sessionHandle = nativeOpenRawSession(handle, filePath, paramsJson, maxPixels, maxLongEdge);
        if (sessionHandle == 0) throw new RuntimeException("Failed to open RAW session");
        return new Session(sessionHandle, 0, 0); // width/height unknown until render
    }

    /**
     * The same open, with the decode white balance chosen instead of taken from
     * the camera's as-shot record. {@code mul} is R, G, B, G2 with green at 1.
     */
    public Session openRawSessionTuned(String filePath, String paramsJson, int maxPixels, int maxLongEdge,
                                       float[] mul) {
        long sessionHandle = nativeOpenRawSessionTuned(handle, filePath, paramsJson, maxPixels, maxLongEdge, mul);
        if (sessionHandle == 0) throw new RuntimeException("Failed to open RAW session");
        return new Session(sessionHandle, 0, 0);
    }

    /**
     * Largest frame this device can render, in pixels, from the GPU's
     * maxStorageBufferRange (0 when it has none). Every kernel binds a whole
     * plane and a plane is 12 bytes per pixel, so a frame past this fails at
     * render time with "buffer exceeds maxStorageBufferRange".
     */
    public int maxFramePixels() {
        // **0: no binding ceiling.** `nativeMaxPlaneBytes` still reports what the
        // driver advertises, and `describe()` still shows it, but a frame is no
        // longer bounded by it -- the renderer bands every pass that would
        // otherwise need the whole plane (docs/tiling.md, P0). The budget is the
        // memory one; returning the old range/12 figure here would quietly
        // reinstate the 8.95 MP ceiling on every 128 MiB device.
        return 0;
    }

    @Override
    public void close() {
        if (handle != 0) nativeDestroyEngine(handle);
    }

    public static class Session implements AutoCloseable {
        /** Guards the single native result buffer against a second thread. */
        private static final Object RENDER_LOCK = new Object();
        private final long handle;
        public final int width;
        public final int height;

        Session(long handle, int width, int height) {
            this.handle = handle;
            this.width = width;
            this.height = height;
        }

        public void setParams(String paramsJson) {
            nativeSetParams(handle, paramsJson);
        }

        /** The rebate's own layout: canvas_w/h, mm_per_px, vertical, gate quads.
         *  `{"valid":false}` when the frame has no rebate. */
        public String overscanGeometry() {
            return nativeOverscanGeometry(handle);
        }

        public String getParams() {
            return nativeGetParams(handle);
        }

        public String sourceExifJson() { return nativeRawExifJson(handle); }

        /**
         * Writes this session's persistent planes -- its source and every tier
         * it has rendered -- to a scratch file and releases the device copies,
         * so a job that needs the memory more can have it. {@link #resume}
         * reads them back and the session is exactly as it was.
         *
         * <p>Returns false when no scratch file could be created; the session
         * is untouched in that case, and the caller should carry on. This is
         * not a cache and not an error path: it is the difference between an
         * export holding one frame's planes and two.
         */
        public boolean suspend() {
            if (suspended) return true;
            suspended = nativeSuspendSession(handle) == 0;
            return suspended;
        }

        /** Undo {@link #suspend}. A no-op when nothing was suspended. */
        public boolean resume() {
            if (!suspended) return true;
            suspended = nativeResumeSession(handle) == 0;
            return suspended;
        }

        public boolean isSuspended() { return suspended; }

        /**
         * Apply `ev` stops of auto-exposure on this session instead of letting
         * the meter decide.
         *
         * <p>Not the same thing as `exposure_compensation_ev`, even though both
         * are a factor of 2^ev. That field is applied after the highlight
         * rolloff; this one before it, in `film_prefix`, where the canvas'
         * meter does its work. Moving an export from one to the other is what
         * made a file look "as if the enlarger exposure never moved": on a
         * 20 MP frame the two differ by 2.1x in mean and 13x in the shadows.
         *
         * <p>Pair it with `auto_exposure = true` in the params: the engine
         * still gates the node on that flag.
         */
        public void pinAutoExposureEv(double ev) { nativePinAutoExposureEv(handle, ev); }

        /** Undo {@link #pinAutoExposureEv}; the meter decides again. */
        public void clearPinAutoExposureEv() { nativeClearPinAutoExposureEv(handle); }

        private boolean suspended;

        public int[] getDimensions() {
            return nativeGetSessionDimensions(handle);
        }

        public int[] getLastRenderInfo() {
            return nativeGetLastRenderInfo();
        }

        /** Renders `tier` and keeps the result alive. Returns {width, height}. */
        // The native render result is one buffer shared by every session, so a
        // second thread entering between renderBegin and copyArgbRange would
        // hand back another frame's dimensions. Rare, and it looks like a
        // randomly rotated or shifted picture rather than a crash.
        public int[] renderBegin(String tier) {
            synchronized (RENDER_LOCK) { return nativeRenderBegin(handle, tier, false); }
        }

        /**
         * Render `tier` over the session's cached negative: the whole film side
         * is skipped and only the print runs (`spk_reprint`).
         *
         * <p>Byte-identical to {@link #renderBegin(String)} when the negative is
         * current -- verified with `live_probe` group H, grain and glare off,
         * reprint == render on every parameter step. The engine drops that
         * cache itself whenever a shoot-layer field changes, so this cannot
         * bring back a stale negative; it just costs a full render that frame.
         *
         * <p>32 ms against 200 ms at an 800-pixel preview, which is the
         * difference between a slider that tracks and a slider that waits.
         */
        public int[] renderBegin(String tier, boolean reprint) {
            synchronized (RENDER_LOCK) { return nativeRenderBegin(handle, tier, reprint); }
        }

        /** Converts `rowCount` rows starting at `rowOffset` into opaque ARGB. */
        public int copyArgbRange(int[] dst, int rowOffset, int rowCount) {
            synchronized (RENDER_LOCK) { return nativeCopyArgbRange(dst, rowOffset, rowCount); }
        }

        /** The decoded frame at `tier`, before the film. Keeps it alive for copyArgbRange. */
        public int[] exportSource(String tier) {
            synchronized (RENDER_LOCK) { return nativeExportSource(handle, tier); }
        }

        /**
         * The last render's progress JSON. `auto_exposure_ev` in it is the EV
         * the meter applied to this frame -- null when the meter was off. It is
         * the only way to tell "the render is dark" from "the render is dark
         * *because* the meter took 1.7 stops off it".
         */
        public String progressJson() {
            synchronized (RENDER_LOCK) { return nativeProgressJson(handle); }
        }

        /**
         * Where the render is, asked from another thread.
         *
         * **Deliberately not under `RENDER_LOCK`.** That lock is held for the
         * whole of a render, so taking it here would make the watchdog wait for
         * the very call it is watching -- it would return after the crash, not
         * before it. The engine side is a lock-free seqlock for exactly this
         * reason: 96 bytes, published with a release store, read under a
         * sequence check.
         *
         * Null when no consistent read was possible in three tries, which means
         * the render is crossing nodes quickly rather than sitting in one.
         */
        public String stageSnapshot() {
            if (handle == 0) return null;
            try { return nativeStageSnapshot(handle); } catch (Throwable t) { return null; }
        }

        public void renderEnd() {
            synchronized (RENDER_LOCK) { nativeRenderEnd(); }
        }

        public SimResult render(String tier, int outWidth, int outHeight) {
            // If width/height are 0 (RAW session), we need to query the actual size
            if (outWidth <= 0 || outHeight <= 0) {
                // For RAW sessions, get params to find actual dimensions
                String params = getParams();
                // Parse width/height from params JSON
                // This is a fallback; normally caller should provide dimensions
                throw new IllegalArgumentException("outWidth/outHeight must be positive for RAW sessions; use getParams() to query dimensions first");
            }
            int bufSize = outWidth * outHeight * 4 * 2; // uint16 RGBA
            ByteBuffer buffer = ByteBuffer.allocateDirect(bufSize);
            double elapsedMs = nativeRenderToBuffer(handle, tier, buffer, outWidth, outHeight);
            if (elapsedMs < 0) throw new RuntimeException("Render failed");
            int[] info = nativeGetLastRenderInfo();
            int actualW = info[0];
            int actualH = info[1];
            int rowStride = info[2];
            return new SimResult(buffer, actualW, actualH, rowStride, elapsedMs);
        }

        /** Solve the print exposure / enlarger filter pack ("Process"). */
        public String solve(String target) {
            return nativeSolve(handle, target);
        }

        /** Full-resolution render + JPEG write, all native. Returns {w, h, renderMs, totalMs}. */
        public int[] exportJpeg(String path, int quality, int stripRows, String tier) {
            return nativeExportJpeg(handle, path, quality, stripRows, tier);
        }

        /**
         * The same render as 16-bit sRGB TIFF: no 8-bit quantisation, and the
         * profile is in the file rather than assumed by the reader. Returns
         * {width, height, renderMs, totalMs}.
         */
        public int[] exportTiff(String path, String tier) {
            synchronized (RENDER_LOCK) { return nativeExportTiff(handle, path, tier); }
        }

        /**
         * The same render as a linear 16-bit DNG. Linear on purpose: a DNG
         * holds light, so Lightroom gets the whole ramp to develop instead of
         * a curve it would have to guess and invert.
         */
        public int[] exportDng(String path, String tier) {
            synchronized (RENDER_LOCK) { return nativeExportDng(handle, path, tier); }
        }

        @Override
        public void close() {
            if (handle != 0) nativeCloseSession(handle);
        }
    }

    public static class SimResult {
        public final ByteBuffer pixels; // little-endian uint16 RGBA
        public final int width;
        public final int height;
        public final int rowStridePx;
        public final double elapsedMs;

        SimResult(ByteBuffer pixels, int width, int height, int rowStridePx, double elapsedMs) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.rowStridePx = rowStridePx;
            this.elapsedMs = elapsedMs;
        }

        SimResult(ByteBuffer pixels, int width, int height, double elapsedMs) {
            this(pixels, width, height, width, elapsedMs);
        }
    }

    // JNI methods
    private static native void nativeSetEnv(String name, String value);
    private static native long nativeCreateEngine(String resourcesDir);
    private static native void nativeDestroyEngine(long handle);
    /** The rebate's layout for whatever was rendered last: canvas, mm/px, gate quads. */
    private static native String nativeOverscanGeometry(long handle);
    private static native String nativeGetBuildInfo();
    private static native String nativeCapabilities(long handle);
    private static native String nativeMemoryReport(long handle);
    private static native long nativeTrimPool(long handle);
    private static native long nativeOpenSession(long engineHandle, ByteBuffer pixels, int width, int height, int channels);
    /** The GPU as JSON: binding range, heaps, workgroup limits. */
    public String deviceProfile() { return nativeDeviceProfile(handle); }

    /**
     * Does this driver enforce the range it reports, or only say it?
     *
     * Nothing in the Vulkan API distinguishes the two: a descriptor whose
     * range exceeds {@code maxStorageBufferRange} updates without complaint
     * and the writes past the limit are dropped with no error at all. This
     * asks the engine to find out -- it allocates a buffer larger than the
     * reported range, binds the whole thing, and writes probes through it.
     *
     * @param needBytes the plane size we want; when the reported range
     *                  already covers it this returns immediately without
     *                  allocating anything.
     * @return JSON: reported_range, cap, probe_ok, effective_range, skipped,
     *         tiers[]. Never null; {@code "{}"} when the engine is gone.
     */
    public String probeCanary(long needBytes) { return nativeProbeCanary(handle, needBytes); }

    /** Stable code for an engine message, 0 for empty, 9000 for unrecognised. */
    public static int errorCode(String message) { ensureLoaded(); return nativeErrorCode(message); }

    /** Read this session's source capture fields, if it came from a RAW. */
    public static String rawExifJson(Session session) {
        return session == null ? "{}" : session.sourceExifJson();
    }

    /** The RAW decoder's own frame size, before the device cap shrank it. */
    public static int[] rawSourceSize() { ensureLoaded(); return nativeRawSourceSize(); }

    private native String nativeDeviceProfile(long engineHandle);
    private static native int nativeErrorCode(String message);
    private static native int[] nativeRawSourceSize();
    private static native String nativeRawExifJson(long sessionHandle);
    private static native long nativeMaxPlaneBytes(long engineHandle);
    private static native String nativeProbeCanary(long engineHandle, long needBytes);

    private static native void nativeInstallCrashHandler(String path);
    /** The RAW's own dimensions from its header. Does not decode. */
    private static native int[] nativeRawDimensions(String path);

    /**
     * How big the file is, asked before it is unpacked so the import can tell
     * the user what it will cost. Null when the container cannot be read.
     */
    public static int[] rawDimensions(String path) {
        try { return nativeRawDimensions(path); } catch (Throwable t) { return null; }
    }

    private static native long nativeOpenRawSession(long engineHandle, String filePath, String paramsJson,
                                                    int maxPixels, int maxLongEdge);
    private static native long nativeOpenRawSessionTuned(long engineHandle, String filePath, String paramsJson,
                                                         int maxPixels, int maxLongEdge, float[] mul);
    private static native void nativeSetViewAdjust(double[] values);
    private static native void nativeSetViewCurves(double[] packed);
    private static native void nativeSetSourceDisplayEv(double ev, boolean autoLevel);
    private static native void nativeSetExportExif(String make, String model, String datetime,
                                                   String shutter, String aperture, String iso, String focal);
    private static native String nativeReadJpegExif(byte[] head);
    private static native float[] nativeRawMulForKelvin(long sessionHandle, double kelvin, double tint);

    /**
     * The DNG display profile, as [baselineEV, x0, y0, x1, y1, ...].
     *
     * Null when the file ships no {@code ProfileToneCurve} -- which is the case
     * for camera DNGs, NEF and RAF. The canvas uses it to show a phone's raw
     * the way the phone asked for it; the engine never sees it.
     */
    public static float[] sourceDisplayProfile(String path) {
        try { return nativeSourceDisplayProfile(path); } catch (Throwable t) { return null; }
    }
    private static native float[] nativeSourceDisplayProfile(String path);

    /** Canvas-only: apply a vendor tone curve, or null to clear it. */
    public static void setSourceToneCurve(float[] profile) {
        try { nativeSetSourceToneCurve(profile); } catch (Throwable ignored) { }
    }
    private static native void nativeSetSourceToneCurve(float[] profile);

    /** The optical page's continuous controls, in the kernel's own order. */
    public static void setViewAdjust(double[] values) {
        try { nativeSetViewAdjust(values); } catch (Throwable ignored) { }
    }

    /** Five tone curves, eight control points each, as (x, y) pairs. */
    public static void setViewCurves(double[] packed) {
        try { nativeSetViewCurves(packed); } catch (Throwable ignored) { }
    }

    /**
     * Display-only exposure for the "original" side; never touches an export.
     *
     * {@code autoLevel} is the old behaviour: put this frame's median on sRGB
     * mid-grey and lift the encoded shadows. False shows the decode as
     * measured -- which is what Lightroom's canvas does, and what the label
     * "RAW 原始数据（未调色）" promises.
     */
    public static void setSourceDisplayEv(double ev, boolean autoLevel) {
        try { nativeSetSourceDisplayEv(ev, autoLevel); } catch (Throwable ignored) { }
    }

    /** Source EXIF for the next export; empty strings mean "do not write". */
    public static void setExportExif(String make, String model, String datetime,
                                     String shutter, String aperture, String iso, String focal) {
        try { nativeSetExportExif(make, model, datetime, shutter, aperture, iso, focal); }
        catch (Throwable ignored) { }
    }

    /** The source JPEG's own EXIF, seven fields; empty strings when unreadable. */
    public static String[] readJpegExif(byte[] head) {
        final String[] out = {"", "", "", "", "", "", ""};
        try {
            final String joined = nativeReadJpegExif(head);
            if (joined == null || joined.isEmpty()) return out;
            final String[] parts = joined.split("\u0001", -1);
            for (int i = 0; i < 7 && i < parts.length; ++i) out[i] = parts[i] == null ? "" : parts[i];
        } catch (Throwable ignored) { }
        return out;
    }

    /** Channel gains for a blackbody at {@code kelvin}; null when the sensor has no matrix. */
    public static float[] rawMulForKelvin(Session session, double kelvin, double tint) {
        if (session == null) return null;
        try { return nativeRawMulForKelvin(session.handle, kelvin, tint); }
        catch (Throwable t) { return null; }
    }
    private static native void nativeCloseSession(long sessionHandle);
    private static native void nativeSetParams(long sessionHandle, String paramsJson);
    private static native String nativeGetParams(long sessionHandle);
    private static native String nativeProgressJson(long sessionHandle);

    private static native String nativeStageSnapshot(long sessionHandle);
    private static native int[] nativeGetSessionDimensions(long sessionHandle);
    private static native double nativeRenderToBuffer(long sessionHandle, String tier, ByteBuffer outBuffer, int outWidth, int outHeight);
    private static native int nativeGetLastRowStride();
    private static native int[] nativeGetLastRenderInfo();
    private static native int[] nativeRenderBegin(long sessionHandle, String tier, boolean reprint);
    private static native int[] nativeExportSource(long sessionHandle, String tier);
    private static native int nativeCopyArgbRange(int[] dst, int rowOffset, int rowCount);
    private static native void nativeRenderEnd();
    private static native int[] nativeExportJpeg(long handle, String path, int quality, int stripRows, String tier);
    private static native int[] nativeExportTiff(long handle, String path, String tier);
    private static native int[] nativeExportDng(long handle, String path, String tier);
    private static native String nativeSolve(long handle, String target);

    // Residency, for the export path: a whole frame's plane written to a
    // scratch file and given back. Refused (non-zero) when the host named no
    // writable scratch directory, which leaves the session as it was.
    // The EV a second session must reproduce without metering again.
    private static native void nativePinAutoExposureEv(long sessionHandle, double ev);
    private static native void nativeClearPinAutoExposureEv(long sessionHandle);

    private static native int nativeSuspendSession(long sessionHandle);
    private static native int nativeResumeSession(long sessionHandle);
}
