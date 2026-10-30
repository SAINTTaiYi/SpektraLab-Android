package app.spektralab.engine;

import java.nio.ByteBuffer;

/**
 * Java facade over the SpektraLab C ABI (spk_engine.h).
 */
public class SpektraLabEngine implements AutoCloseable {
    static {
        System.loadLibrary("spektrafilm_engine");
    }

    private final long handle;

    private SpektraLabEngine(long handle) {
        this.handle = handle;
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
        if (logPath != null) {
            nativeSetEnv("SPEKTRAFILM_TRANSFER_TIMINGS", "1");
            nativeSetEnv("SPK_VK_LOG", logPath);
        }
        long handle = nativeCreateEngine(resourcesDir);
        if (handle == 0) throw new RuntimeException("Failed to create SpektraLab engine");
        return new SpektraLabEngine(handle);
    }

    public static String buildInfo() {
        return nativeGetBuildInfo();
    }

    /** The engine's `capabilities` JSON: backend, device, pool and memory types. */
    public String capabilities() {
        return nativeCapabilities(handle);
    }

    /** Buffer ledger: what the engine holds and where it was allocated. */
    public String memoryReport() {
        return nativeMemoryReport(handle);
    }

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
     * Largest frame this device can render, in pixels, from the GPU's
     * maxStorageBufferRange (0 when it has none). Every kernel binds a whole
     * plane and a plane is 12 bytes per pixel, so a frame past this fails at
     * render time with "buffer exceeds maxStorageBufferRange".
     */
    public int maxFramePixels() {
        long limit = nativeMaxPlaneBytes(handle);
        if (limit <= 0) return 0;                 // no limit: caller decides
        double px = limit / 12.0 * 0.80;          // 20% headroom for wider planes
        return (int) Math.min(px, Integer.MAX_VALUE);
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

        public String getParams() {
            return nativeGetParams(handle);
        }

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
            synchronized (RENDER_LOCK) { return nativeRenderBegin(handle, tier); }
        }

        /** Converts `rowCount` rows starting at `rowOffset` into opaque ARGB. */
        public int copyArgbRange(int[] dst, int rowOffset, int rowCount) {
            synchronized (RENDER_LOCK) { return nativeCopyArgbRange(dst, rowOffset, rowCount); }
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
    private static native String nativeGetBuildInfo();
    private static native String nativeCapabilities(long handle);
    private static native String nativeMemoryReport(long handle);
    private static native long nativeOpenSession(long engineHandle, ByteBuffer pixels, int width, int height, int channels);
    /** The GPU as JSON: binding range, heaps, workgroup limits. */
    public String deviceProfile() { return nativeDeviceProfile(handle); }

    /** Stable code for an engine message, 0 for empty, 9000 for unrecognised. */
    public static int errorCode(String message) { return nativeErrorCode(message); }

    /** The RAW decoder's own frame size, before the device cap shrank it. */
    public static int[] rawSourceSize() { return nativeRawSourceSize(); }

    private native String nativeDeviceProfile(long engineHandle);
    private static native int nativeErrorCode(String message);
    private static native int[] nativeRawSourceSize();
    private static native long nativeMaxPlaneBytes(long engineHandle);
    private static native long nativeOpenRawSession(long engineHandle, String filePath, String paramsJson,
                                                    int maxPixels, int maxLongEdge);
    private static native void nativeCloseSession(long sessionHandle);
    private static native void nativeSetParams(long sessionHandle, String paramsJson);
    private static native String nativeGetParams(long sessionHandle);
    private static native int[] nativeGetSessionDimensions(long sessionHandle);
    private static native double nativeRenderToBuffer(long sessionHandle, String tier, ByteBuffer outBuffer, int outWidth, int outHeight);
    private static native int nativeGetLastRowStride();
    private static native int[] nativeGetLastRenderInfo();
    private static native int[] nativeRenderBegin(long sessionHandle, String tier);
    private static native int nativeCopyArgbRange(int[] dst, int rowOffset, int rowCount);
    private static native void nativeRenderEnd();
    private static native int[] nativeExportJpeg(long handle, String path, int quality, int stripRows, String tier);
    private static native String nativeSolve(long handle, String target);
}
