package app.spektralab;

import org.json.JSONObject;

/**
 * What this GPU actually is, and therefore what this app is allowed to ask of
 * it.
 *
 * The one number that decides everything is {@code maxStorageBufferRange}: the
 * largest range a single storage-buffer binding may name. Every kernel binds a
 * whole plane and a plane is 12 bytes per pixel, so the device's binding range
 * is a hard ceiling on the frame — 128 MiB is 11.2 MP, 256 MiB is 22.4 MP, and
 * a phone that reports 128 MiB fails at 4700 pixels on the long edge with
 * "buffer exceeds maxStorageBufferRange". Measured, not guessed.
 */
public final class DeviceProbe {

    /**
     * The frame ceiling this build will import on its own.
     *
     * It used to be a driver limit: past 16,776,960 pixels (65 535 workgroups
     * x 256 threads) a dispatch folded into workgroup rows and the top rows
     * came back unwritten. That is fixed -- the engine now issues one 1D
     * dispatch per 65 535 workgroups and every shader offsets by the chunk's
     * first element, verified byte-identical at 2 and 4 chunks on a 20.3 MP
     * frame. The number stays as a **memory** policy until a frame's live
     * planes are costed properly: three 16.5 MP float planes are ~200 MB each,
     * and a phone that swaps under that is worse than one that renders smaller.
     */
    public static final int DISPATCH_PIXEL_CAP = 16_490_040;

    public final String backend;
    public final String device;
    public final long maxStorageBufferRange;   // 0 = no per-binding limit
    public final long heapDeviceLocal;
    public final int maxWorkgroupCount;

    private DeviceProbe(String backend, String device, long range, long heap, int workgroups) {
        this.backend = backend;
        this.device = device;
        this.maxStorageBufferRange = range;
        this.heapDeviceLocal = heap;
        this.maxWorkgroupCount = workgroups;
    }

    /** Never throws: an unreadable profile is a device that claims no limits. */
    public static DeviceProbe parse(String json) {
        try {
            JSONObject o = new JSONObject(json);
            return new DeviceProbe(
                    o.optString("backend", "?"),
                    o.optString("device", "?"),
                    o.optLong("max_storage_buffer_range", 0),
                    o.optLong("heap_device_local", 0),
                    o.optInt("max_workgroup_count", 0));
        } catch (Throwable t) {
            return new DeviceProbe("?", "?", 0, 0, 0);
        }
    }

    /** Largest frame the binding range allows, with 20% headroom. */
    public long maxPixelsByBinding() {
        return maxStorageBufferRange > 0
                ? (long) (maxStorageBufferRange / 12.0 * 0.80)
                : Long.MAX_VALUE;
    }

    /** Largest frame imported without asking. */
    public long maxPixels() {
        return Math.min(maxPixelsByBinding(), DISPATCH_PIXEL_CAP);
    }

    /** True when this device cannot take the frame size this build was tuned for. */
    public boolean isLimited() {
        return maxStorageBufferRange > 0 && maxPixelsByBinding() < DISPATCH_PIXEL_CAP;
    }

    /** Megapixels the device can take, for a label. */
    public double maxMegapixels() {
        return maxPixels() / 1e6;
    }

    /** One line for the status bar. */
    public String describe() {
        StringBuilder sb = new StringBuilder(device == null || device.isEmpty() ? "GPU" : device);
        if (maxStorageBufferRange > 0) {
            sb.append(" · 单缓冲 ").append(Math.round(maxStorageBufferRange / 1048576.0)).append(" MiB");
            sb.append(" · 上限 ").append(String.format("%.1f", maxMegapixels())).append(" MP");
        } else {
            sb.append(" · 无单缓冲上限");
        }
        return sb.toString();
    }
}
