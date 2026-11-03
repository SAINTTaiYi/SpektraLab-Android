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
 *
 * <p>But a reported number is a claim, and the canary is the check on it. On
 * Adreno, bounding a descriptor past {@code maxStorageBufferRange} is not an
 * error the API reports: the update succeeds, the dispatch runs in about a
 * millisecond, and the writes past the limit are simply dropped. A driver that
 * over-reports its range and one that does not are indistinguishable from the
 * outside — {@link #applyCanary} is what tells them apart, by writing.
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

    /**
     * Where a frame stops rendering on this class of hardware, measured.
     *
     * The dispatch wall is fixed, so what is left is memory: the pool peaks at
     * 6.3 live planes, 76 bytes per pixel. On the SM8735 here that is 37.5 MP
     * in 2.7 GB (32 s), 42.7 MP in 57 s, and 44.8 MP loses the device inside
     * `spk_iir_df_acc`.
     *
     * <p>**No longer an import ceiling.** It survives as the canary's target
     * ({@link #CANARY_NEED_BYTES}) and as the answer when free memory cannot be
     * read at all. A frame above it is not refused: the user is asked whether
     * to shrink or to force the original size.
     */
    public static final int HARD_PIXEL_CAP = 42_000_000;

    /**
     * The plane size one frame at {@link #HARD_PIXEL_CAP} needs, in bytes.
     *
     * This is what the canary is asked to reach: if the reported binding range
     * already covers it, there is nothing to learn and the probe returns
     * without allocating. 504 MB -- below every Adreno 6xx/7xx/8xx report, and
     * above the 128 MiB that 7xx devices claim.
     */
    public static final long CANARY_NEED_BYTES = HARD_PIXEL_CAP * 12L;

    public final String backend;
    public final String device;
    public final long maxStorageBufferRange;   // 0 = no per-binding limit
    public final long heapDeviceLocal;
    public final int maxWorkgroupCount;
    /** The other two ceilings a frame can hit, carried for the report. */
    public final long maxMemoryAllocation;
    public final int maxImage2d;
    public final int maxWorkgroupInvocations;
    public final String api;
    public final String driver;
    /**
     * False when the profile could not be read at all.
     *
     * **This is the flag that matters.** A probe that failed used to be
     * indistinguishable from a device with no per-binding limit: both said
     * `maxStorageBufferRange = 0`, and `maxPixelsByBinding()` answered
     * `Long.MAX_VALUE` for both. On a phone whose driver really does report the
     * Vulkan minimum (128 MiB, i.e. 11.2 MP) that let a 14.8 MP import through,
     * and the render then died on `spk_sep_fir_acc: buffer argument 0 needs
     * 168.9 MiB, this device allows 128.0 MiB`. The user's screen said
     * `? · 无单缓冲上限`, which is this flag missing.
     */
    public final boolean valid;

    /** Did the canary run this session, and what did it find? */
    public boolean canaryRan = false;
    public boolean canaryPassed = false;
    public long canaryEffectiveRange = 0;
    public String canarySkipped = "";
    public String canaryTiers = "";
    /** Milliseconds the engine spent walking the tiers, 0 when it skipped. */
    public long canaryMillis = 0;

    private DeviceProbe(String backend, String device, long range, long heap, int workgroups,
                        boolean valid, long maxAllocation, int image2d, int invocations,
                        String api, String driver) {
        this.backend = backend;
        this.device = device;
        this.maxStorageBufferRange = range;
        this.heapDeviceLocal = heap;
        this.maxWorkgroupCount = workgroups;
        this.valid = valid;
        this.maxMemoryAllocation = maxAllocation;
        this.maxImage2d = image2d;
        this.maxWorkgroupInvocations = invocations;
        this.api = api == null ? "?" : api;
        this.driver = driver == null ? "?" : driver;
    }

    /** Never throws: an unreadable profile is a device that claims no limits. */
    public static DeviceProbe parse(String json) {
        try {
            if (json == null || json.length() < 3) return missing();
            JSONObject o = new JSONObject(json);
            if (!o.has("max_storage_buffer_range")) return missing();
            return new DeviceProbe(
                    o.optString("backend", "?"),
                    o.optString("device", "?"),
                    o.optLong("max_storage_buffer_range", 0),
                    o.optLong("heap_device_local", 0),
                    o.optInt("max_workgroup_count", 0),
                    true,
                    o.optLong("max_memory_allocation", 0),
                    o.optInt("max_image_2d", 0),
                    o.optInt("max_workgroup_invocations", 0),
                    o.optString("api", "?"),
                    o.optString("driver", "?"));
        } catch (Throwable t) {
            return missing();
        }
    }

    private static DeviceProbe missing() {
        return new DeviceProbe("?", "?", 0, 0, 0, false, 0, 0, 0, "?", "?");
    }

    /**
     * Adopt a canary result produced by {@code SpektraLabEngine.probeCanary}.
     *
     * The engine's answer is authoritative about what it measured and says
     * nothing it did not: {@code probe_ok} is whether writes past the reported
     * range landed, {@code effective_range} is the largest tier that did. A
     * skipped run means the reported range already covers what we want, which
     * is a pass -- there is nothing above it we need.
     *
     * A malformed or absent document leaves the probe exactly as it was: the
     * reported number is a valid fallback, and a failed measurement must never
     * be read as a permissive one.
     */
    public void applyCanary(String json) {
        if (json == null || json.length() < 3) return;
        try {
            JSONObject o = new JSONObject(json);
            if (!o.has("probe_ok")) return;
            canaryRan = true;
            canaryPassed = o.optBoolean("probe_ok", false);
            canaryEffectiveRange = o.optLong("effective_range", maxStorageBufferRange);
            canarySkipped = o.optString("skipped", "");
            if (canaryEffectiveRange <= 0) canaryEffectiveRange = maxStorageBufferRange;
            org.json.JSONArray tiers = o.optJSONArray("tiers");
            canaryTiers = tiers == null ? "[]" : tiers.toString();
        } catch (Throwable ignored) {
            // Leave the probe alone.
        }
    }

    /** True when the canary actually allocated and wrote something. */
    public boolean canaryMeasured() {
        return canaryRan && canaryEffectiveRange > 0 && canaryEffectiveRange != maxStorageBufferRange;
    }

    /**
     * The range this driver will actually honour.
     *
     * The canary only ever raises it. A device that reports 128 MiB and takes
     * 1 GiB is the case worth having: it is the difference between importing
     * 11 MP and importing 42 MP on a 2023 flagship. A canary that failed or
     * never ran leaves the reported number in place, because an unmeasured
     * device is not a permissive one.
     */
    public long effectiveStorageRange() {
        if (canaryRan && canaryPassed && canaryEffectiveRange > maxStorageBufferRange)
            return canaryEffectiveRange;
        return maxStorageBufferRange;
    }

    /** Largest frame the binding range allows, with 20% headroom. */
    public long maxPixelsByBinding() {
        final long range = effectiveStorageRange();
        return range > 0 ? (long) (range / 12.0 * 0.80) : Long.MAX_VALUE;
    }

    /**
     * What the *living* system can take, which is the limit that actually
     * bites once the binding cap is lifted: a whole-frame render holds seven
     * live planes of 12 B/px (docs/tiling.md measures 7.0 at 20.3 and 50 MP
     * alike). The 1.5 GiB reserve is this app's own host side plus whatever
     * else the phone is running -- at 4.5 GB available that lands on 38 MP,
     * which is where the measured ceiling sat.
     */
    public static long maxPixelsByMemory(long availableBytes) {
        if (availableBytes <= 0) return HARD_PIXEL_CAP;
        final long reserve = 1536L << 20;   // 1.5 GiB: a frame that pushes the system into its low-memory killer costs more than it gives
        // 76 B/px is the GPU pool's measured peak (6.3 live planes), and the
        // import itself holds the frame twice more before the GPU sees it: an
        // ARGB_8888 bitmap (4 B/px) and the 12 B/px float buffer handed to
        // spk_open. That is what made a 45 MP import die before the render did.
        return Math.max(0, availableBytes - reserve) / (12L * 7 + 16);
    }

    /**
     * Largest frame the *driver's range* allows. Not an import ceiling any
     * more: memory is (`maxPixelsByMemory`), and above that the user is asked.
     */
    public long maxPixels() {
        return maxPixelsByBinding();
    }

    /**
     * True when this device cannot take the frame size this build was tuned for
     * -- or when the profile could not be read, which must warn rather than
     * reassure. "Unknown" used to land on the reassuring side.
     */
    public boolean isLimited() {
        if (!valid) return true;
        // **A binding range is not a frame limit any more.** Every pass that
        // would once have needed a whole plane is banded (docs/tiling.md, P0),
        // and the canary lifts the reported range on devices that enforce more
        // than they advertise. What is left is memory, and `maxPixelsByMemory`
        // reports that on its own. Keeping the old test here told 34.5% of
        // devices they were capped at 8.95 MP while the renderer would have
        // taken three times that.
        return false;
    }

    /** Megapixels the device can take, for a label. */
    public double maxMegapixels() {
        return maxPixels() / 1e6;
    }

    /** One line for the status bar. */
    public String describe() {
        if (!valid) return "设备信息读取失败";
        StringBuilder sb = new StringBuilder(device == null || device.isEmpty() ? "GPU" : device);
        if (maxStorageBufferRange > 0) {
            // "上限 X MP" used to be printed here from the binding range and
            // read as a hard ceiling. There is none: the renderer bands a frame
            // that does not fit one binding, and the import asks before it
            // shrinks anything.
            sb.append(" · 单缓冲 ").append(Math.round(maxStorageBufferRange / 1048576.0)).append(" MiB（分块）");
            if (canaryMeasured()) {
                sb.append(" · 实测可到 ").append(Math.round(effectiveStorageRange() / 1048576.0)).append(" MiB");
            } else if (canaryRan && canaryPassed) {
                sb.append(" · 实测确认");
            } else if (canaryRan) {
                sb.append(" · 实测未能突破");
            }
        } else {
            sb.append(" · 无单缓冲上限");
        }
        return sb.toString();
    }

    /**
     * This device, as the one document the report endpoint accepts.
     *
     * Capability only. No photograph, no file name, no account, no identifier
     * that outlives the install -- the fields are the ones a GPU database
     * would hold, plus the frame budget this build derived from them.
     */
    public JSONObject reportJson(String appVersion, int appCode, String androidRelease,
                                 int sdk, String brand, String model, String deviceCode,
                                 long budgetPixels) {
        JSONObject o = new JSONObject();
        try {
            o.put("v", 1);
            o.put("app", appVersion);
            o.put("app_code", appCode);
            o.put("android", androidRelease);
            o.put("sdk", sdk);
            o.put("brand", brand);
            o.put("model", model);
            o.put("device", deviceCode);
            o.put("gpu", this.device == null ? "?" : this.device);
            o.put("api", this.api);
            o.put("driver", this.driver);
            o.put("range", this.maxStorageBufferRange);
            o.put("alloc", this.maxMemoryAllocation);
            o.put("img2d", this.maxImage2d);
            o.put("wg_inv", this.maxWorkgroupInvocations);
            o.put("wg_count", this.maxWorkgroupCount);
            o.put("heap0", this.heapDeviceLocal);
            o.put("valid", this.valid);
            o.put("canary_ran", this.canaryRan);
            o.put("canary_ok", this.canaryPassed);
            o.put("canary_effective", this.canaryEffectiveRange);
            o.put("canary_skipped", this.canarySkipped);
            o.put("canary_tiers", this.canaryTiers);
            o.put("budget_px", budgetPixels);
            o.put("limited", isLimited());
            o.put("ts", System.currentTimeMillis() / 1000L);
        } catch (Throwable ignored) {
        }
        return o;
    }
}
