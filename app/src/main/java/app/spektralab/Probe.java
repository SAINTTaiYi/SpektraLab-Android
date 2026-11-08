package app.spektralab;

import android.content.Context;

import app.spektralab.engine.SpektraLabEngine;

/**
 * Targeted probes for the defects fixed between 1.5.0h and 1.5.0x.
 *
 * <p>A screenshot says "it failed"; these say *what the numbers were*. Each
 * area answers one question that has already cost a round trip:
 *
 * <ul>
 *   <li>{@code import} — free memory and pool contents before/after the trim,
 *       and the cap the decode was given (E-1003, the 50 MP import).</li>
 *   <li>{@code wb} — the LibRaw gains a preview decode computed, whether they
 *       reached the Look, and whether an export received them (white balance
 *       lost on export / on reopening a frame).</li>
 *   <li>{@code render} — a render that was queued behind another one, and the
 *       delta the render thread actually sent (params while rendering).</li>
 *   <li>{@code exit} — why the previous launch was or was not reported: trail
 *       length, last step, pause position, reason (the false "异常退出").</li>
 *   <li>{@code picker} — every candidate action and whether it launched
 *       (a ROM that reports no picker).</li>
 *   <li>{@code toggle} — the rebate/date switch value before and after a tap,
 *       and what the marks were synced to (a switch that did not match).</li>
 *   <li>{@code crop} — the ratio tapped and the scroll position restored
 *       (the jitter on the portrait cards).</li>
 * </ul>
 *
 * <p>Everything goes to the sink as {@code probe-<area>}; the steps that
 * bracket a possible death also go into the crash ring as {@code probe:<area>}
 * so a report carries them even when the sink never ran. {@link DebugSink}
 * already swallows a missing sink, and none of these is on a per-pixel path:
 * the highest frequency is one line per gesture.
 */
final class Probe {
    private Probe() {}

    static void log(String area, String body) {
        try { DebugSink.text("probe-" + area, body); } catch (Throwable ignored) { }
    }

    /** Blocking; only for the line just before something that can kill us. */
    static void now(String area, String body) {
        try { DebugSink.textNow("probe-" + area, body); } catch (Throwable ignored) { }
    }

    /** A forced mark, so the ring carries the step even if the write is next. */
    static void step(Context context, String step) {
        try { CrashTrail.markNow(context, "probe:" + step); } catch (Throwable ignored) { }
    }

    static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1fMB", bytes / 1048576.0);
    }

    static String mul(float[] m) {
        if (m == null) return "as-shot";
        if (m.length < 4) return java.util.Arrays.toString(m);
        return String.format(java.util.Locale.US, "%.4f/%.4f/%.4f/%.4f", m[0], m[1], m[2], m[3]);
    }

    static String pool(SpektraLabEngine engine) {
        try { return engine == null ? "no-engine" : engine.memoryReport(); }
        catch (Throwable t) { return "report-failed:" + t; }
    }
}
