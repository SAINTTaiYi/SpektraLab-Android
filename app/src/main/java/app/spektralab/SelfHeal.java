package app.spektralab;

import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the engine says when it refuses, read back as an instruction.
 *
 * <p>Every numeric range in this engine is a schema field with a {@code lo} and
 * an {@code hi}, and a refusal names all three:
 *
 * <pre>'preview_long_edge'=400.000000 is outside [800.000000, 8192.000000]</pre>
 *
 * <p>That sentence has been in front of us six times and a person has had to
 * write the answer out by hand: {@code 560} for the drag preview, {@code 400}
 * for the live-clip preview, {@code __scan__} for a paper the engine has no
 * profile for, and a highlight room four times its ceiling. Each was found by
 * a user, reported, and patched in the next build. The sentence is
 * machine-readable; there is no reason for the seventh one to reach a user.
 *
 * <h3>What this is allowed to touch</h3>
 *
 * <p><b>Only the value the engine itself named.</b> No size, no budget, no
 * sampling ratio: a clamp is not permission to make the picture smaller, and a
 * number the engine just rejected is by definition a number that was doing
 * nothing. When the payload does not contain that key, nothing is changed --
 * guessing at a neighbour would be a different repair than the one asked for.
 *
 * <p>Every repair is logged, because a silent one is a bug nobody will ever
 * find again. See {@link #note}.
 *
 * <h3>What this is not</h3>
 *
 * <p>It does not fix logic. A negative rendered as {@code 1 - RGB}, a light
 * leak that bounces off the wrong layer, an unmix that picks a different
 * active set near zero -- all of those are wrong answers that no amount of
 * retrying turns into right ones. This class only covers the family where the
 * engine states the correct answer in the same breath as the refusal.
 */
public final class SelfHeal {

    private SelfHeal() {}

    // ---------------------------------------------------------------- ranges

    /** A range the engine drew, read back out of the sentence it drew it in. */
    public static final class Range {
        public final String key;
        public final double value;
        public final double lo;
        public final double hi;
        /** True when the value was below the range, i.e. the cure is {@link #lo}. */
        public final boolean below;

        Range(String key, double value, double lo, double hi) {
            this.key = key;
            this.value = value;
            this.lo = lo;
            this.hi = hi;
            this.below = value < lo;
        }

        /**
         * The value to send instead: the named bound, nudged inward by one part
         * per million.
         *
         * <p>The nudge is not superstition. The engine prints its bounds with
         * {@code std::to_string}, six decimals, so a bound of {@code 0.3333333}
         * reaches us as {@code 0.333333} and sending that back is still short.
         * One part per million is far below anything a parameter here means and
         * far above that rounding.
         */
        public double repair() {
            // **Both bounds integral: send the bound itself.** The field is
            // either an `int` (the engine rounds it anyway -- `as_int` is
            // `num + 0.5` truncated) or a `float` whose range was chosen in
            // whole numbers. Either way the bound is a valid answer, and
            // `240.00024` in the log for a `[240, 8192]` field only looks like
            // a bug someone should chase.
            if (lo == Math.rint(lo) && hi == Math.rint(hi)) return below ? lo : hi;
            // One part per million of the bound, but never more than half the
            // range. A range can be narrower than the rounding it was printed
            // with -- `[0.333333, 0.333333]` is impossible, but
            // `[0.333333, 0.3333332]` is not, and stepping a whole 1e-6 into
            // that one lands outside it.
            final double span = hi - lo;
            final double want = Math.max(1e-12, Math.abs(below ? lo : hi) * 1e-6);
            final double eps = Math.min(want, span * 0.5);
            return below ? lo + eps : hi - eps;
        }

        /** True when the repaired value is inside the range it came from. */
        public boolean repairIsInside() {
            final double v = repair();
            return v >= lo && v <= hi && Double.isFinite(v);
        }

        @Override public String toString() {
            return key + " " + num(value) + " -> " + num(repair())
                    + " [" + num(lo) + ", " + num(hi) + "]";
        }
    }

    private static final String NUMBER = "(-?[0-9]+(?:\\.[0-9]*)?(?:[eE][-+]?[0-9]+)?)";

    /** {@code 'preview_long_edge'=400.000000 is outside [800.000000, 8192.000000]} */
    private static final Pattern RANGE = Pattern.compile(
            "'([A-Za-z0-9_]+)'\\s*=\\s*" + NUMBER
                    + "\\s+is outside\\s*\\[\\s*" + NUMBER + "\\s*,\\s*" + NUMBER + "\\s*\\]");

    /** {@code no profile '__scan__' (cannot open .../profiles/__scan__.json)} */
    private static final Pattern NO_PROFILE = Pattern.compile("no profile '([^']*)'");

    /** The paper every spare path in this app already falls back to. */
    private static final String FALLBACK_PAPER = "kodak_portra_endura";

    /**
     * @return the range the message names, or null when it names none (or
     *         names one the value is already inside, which is not a refusal
     *         worth acting on).
     */
    public static Range parseRange(String message) {
        if (message == null || message.isEmpty()) return null;
        final Matcher m = RANGE.matcher(message);
        if (!m.find()) return null;
        try {
            final double v = Double.parseDouble(m.group(2));
            final double lo = Double.parseDouble(m.group(3));
            final double hi = Double.parseDouble(m.group(4));
            if (!(hi > lo)) return null;
            if (v >= lo && v <= hi) return null;
            return new Range(m.group(1), v, lo, hi);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Rewrite one number in a parameter payload.
     *
     * <p>String surgery rather than {@code JSONObject}: re-serialising would
     * reorder the keys and re-format every other number, and this payload is
     * compared against the last one it sent. Exactly one token changes.
     *
     * @return the repaired payload, or null when the key is not present (or is
     *         present as a quoted string, which is a different field shape and
     *         not something this may silently rewrite).
     */
    public static String clampJson(String json, Range range) {
        if (json == null || range == null) return null;
        final Pattern p = Pattern.compile(
                "(\"" + Pattern.quote(range.key) + "\"\\s*:\\s*)" + NUMBER);
        final Matcher m = p.matcher(json);
        if (!m.find()) return null;
        // **Checked on the text, not on the double.** `num` is a formatter, and
        // the thing the engine validates is the string it parses back, so a
        // repair that is exact as a double and rounded out of range as six
        // decimals is still a failed repair. Range widths as small as 1e-6
        // come out of this engine (`advance` steps are printed at 6 places).
        final String text = num(range.repair());
        final double wire;
        try { wire = Double.parseDouble(text); }
        catch (Throwable bad) { return null; }
        if (!(wire >= range.lo && wire <= range.hi)) return null;
        final String out = json.substring(0, m.start(2)) + text
                + json.substring(m.end(2));
        return out.equals(json) ? null : out;
    }

    // ------------------------------------------------------------- the retry

    /**
     * The one retry an engine refusal earns.
     *
     * @return the payload to send again, or null when the refusal is not one
     *         this can repair -- and then the caller must throw the original
     *         exception untouched, because a second identical failure is worse
     *         than the first one (it hides which attempt was the real one).
     */
    public static String retry(String json, String message) {
        if (json == null || json.isEmpty()) return null;

        final Range range = parseRange(message);
        if (range != null) {
            if (!range.repairIsInside()) {
                note("range-impossible", range.toString() + " -- the range is narrower than its own rounding");
                return null;
            }
            final String fixed = clampJson(json, range);
            if (fixed == null) {
                note("range-miss", range.key + " is not a number in this payload");
                return null;
            }
            note("range", range.toString());
            return fixed;
        }

        final String missing = paperThatDoesNotExist(message);
        if (missing != null) {
            final String fixed = replaceString(json, "print_stock", FALLBACK_PAPER);
            if (fixed == null) {
                note("paper-miss", "'" + missing + "' but print_stock is not in this payload");
                return null;
            }
            note("paper", "'" + missing + "' -> '" + FALLBACK_PAPER + "'");
            return fixed;
        }
        return null;
    }

    private static String paperThatDoesNotExist(String message) {
        if (message == null) return null;
        final Matcher m = NO_PROFILE.matcher(message);
        return m.find() ? m.group(1) : null;
    }

    private static String replaceString(String json, String key, String value) {
        final Pattern p = Pattern.compile(
                "(\"" + Pattern.quote(key) + "\"\\s*:\\s*)\"([^\"]*)\"");
        final Matcher m = p.matcher(json);
        if (!m.find()) return null;
        if (value.equals(m.group(2))) return null;
        return json.substring(0, m.start(2)) + value + json.substring(m.end(2));
    }

    // ---------------------------------------------------------------- device

    /**
     * A repair that belongs to a device rather than to a refusal.
     *
     * <p>Here the engine is not complaining -- it is doing something that is
     * wrong on this GPU, this ROM or this build, and the cure is known from a
     * log rather than from a message. Every rule names the record that earned
     * it, and every rule has a ceiling on the build it applies to, so that
     * fixing the root cause retired the rule by construction rather than by
     * somebody remembering to.
     */
    public static final class Rule {
        /** Stable id; appears verbatim in the {@code heal:} line. */
        public final String id;
        /** Substring of the Vulkan renderer string. Empty = any device. */
        public final String gpu;
        /** Highest versionCode this applies to. 0 = any. */
        public final int maxBuild;
        /** Parameter name. */
        public final String key;
        /** What to send instead. */
        public final String value;
        /** The report or measurement that earned this rule. */
        public final String why;

        public Rule(String id, String gpu, int maxBuild, String key, String value, String why) {
            this.id = id;
            this.gpu = gpu == null ? "" : gpu;
            this.maxBuild = maxBuild;
            this.key = key;
            this.value = value;
            this.why = why == null ? "" : why;
        }
    }

    /**
     * <b>Empty on purpose.</b>
     *
     * <p>A rule is a claim that some devices need a different value, and a
     * claim has to be earned by a log before it ships. Shipping the machinery
     * empty is what lets us prove it changes nothing: with no rules this class
     * is inert, and every existing test still produces the same bytes.
     *
     * <p>The first candidates are already written down (the Mali-G720 export
     * that comes back flat from a fresh {@code full} session, the 128 MiB
     * Adreno rows whose real ceiling the canary measures at 2x what the driver
     * advertises), but each of those is currently handled in code where it can
     * be tested directly. Moving one here is a separate change with its own
     * before/after.
     */
    private static final Rule[] RULES = {};

    /**
     * Apply the device rules to a parameter map, in place.
     *
     * @param renderer the Vulkan renderer string, or null when unknown (a rule
     *                 that names a GPU never fires against null -- not knowing
     *                 the device is not evidence about it).
     * @return how many rules fired.
     */
    public static int applyRules(Map<String, String> kv, String renderer, int build) {
        if (RULES.length == 0 || kv == null) return 0;
        int fired = 0;
        for (Rule r : RULES) {
            if (r.key == null || r.value == null) continue;
            if (r.maxBuild > 0 && build > r.maxBuild) continue;
            if (!r.gpu.isEmpty()
                    && (renderer == null || !renderer.contains(r.gpu))) continue;
            final String was = kv.get(r.key);
            if (was == null || r.value.equals(was)) continue;
            kv.put(r.key, r.value);
            note("rule:" + r.id, r.key + " " + was + " -> " + r.value
                    + (r.why.isEmpty() ? "" : " (" + r.why + ")"));
            ++fired;
        }
        return fired;
    }

    /** How many rules could fire on this device, for the settings page. */
    public static int ruleCount() { return RULES.length; }

    private static volatile String RENDERER = "";
    private static volatile int BUILD = 0;

    /**
     * Who this process is, for the device rules.
     *
     * @param renderer the Vulkan renderer string, or null when it has not been
     *                 probed. A rule that names a GPU never fires against null:
     *                 not knowing the device is not evidence about it.
     * @param build    the versionCode this APK was installed as; a rule stops
     *                 applying once the build it was written for is behind us.
     */
    public static void identify(String renderer, int build) {
        if (renderer != null && !renderer.isEmpty()) RENDERER = renderer;
        if (build > 0) BUILD = build;
    }

    /** {@link #applyRules(Map, String, int)} against this process' identity. */
    public static int applyRules(Map<String, String> kv) {
        return applyRules(kv, RENDERER.isEmpty() ? null : RENDERER, BUILD);
    }

    // ------------------------------------------------------------- utilities

    /**
     * The shortest decimal that means this number.
     *
     * <p>Integers stay integers: the payload is diffed against the previous
     * one, and {@code 800} turning into {@code 800.000000} for no reason would
     * be a change in the log that has no meaning.
     */
    static String num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        // Twelve places, not six: the engine prints its bounds at six, and a
        // repair has to be representable *between* two values that differ in
        // the sixth. Nine was not enough for the tightest range in the schema.
        String s = String.format(Locale.US, "%.12f", v);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') --end;
        if (end > 0 && s.charAt(end - 1) == '.') --end;
        return end > 0 ? s.substring(0, end) : "0";
    }

    /**
     * One line, in the ring, for the first occurrence and then every twentieth.
     *
     * <p>Not uploaded on its own -- this app sends three things and a repair is
     * not one of them (see {@code DebugSink}). It shows up in the steps the
     * user chooses to send and in the trail a crash carries, which is where it
     * is actually useful: "this session repaired something" is the first
     * question every report about a strange picture asks.
     */
    private static final HashMap<String, Integer> SEEN = new HashMap<>();

    private static void note(String kind, String detail) {
        final String key = kind + "|" + detail;
        int n;
        synchronized (SEEN) {
            final Integer was = SEEN.get(key);
            n = was == null ? 1 : was + 1;
            SEEN.put(key, n);
        }
        if (n == 1 || n % 20 == 0) {
            try {
                DebugSink.text("heal", kind + " x" + n + ": " + detail);
            } catch (Throwable ignored) { }
        }
    }

    // -------------------------------------------------------------- ledger

    private static final HashMap<String, Integer> SPENT = new HashMap<>();

    /**
     * Spend one unit of a named repair's budget.
     *
     * <p>Any repair that can be attempted again needs a ceiling, because the
     * alternative is a loop that reads as a hang and reports nothing. The count
     * is per process: a repair already spent is a repair whose cause is still
     * there, and trying it once more per frame turns one bug into a thousand
     * log lines.
     *
     * @return true when the caller may run this repair now.
     */
    public static boolean budget(String id, int max) {
        if (id == null || max <= 0) return false;
        synchronized (SPENT) {
            final Integer was = SPENT.get(id);
            final int n = was == null ? 0 : was;
            if (n >= max) return false;
            SPENT.put(id, n + 1);
            return true;
        }
    }

    /** How much of a repair's budget has been spent, for the diagnostics page. */
    public static int spent(String id) {
        synchronized (SPENT) {
            final Integer n = SPENT.get(id);
            return n == null ? 0 : n;
        }
    }

    /**
     * Record a repair that already happened somewhere else.
     *
     * <p>Two of this app's repairs predate this class -- the gate re-pick and
     * the export session switch -- and neither is a parameter clamp, so neither
     * can go through {@link #retry}. They still belong in the same ledger: the
     * first question every report about a strange picture asks is "did this
     * session repair anything", and that answer is worth nothing if half the
     * repairs do not write it down.
     */
    public static void repaired(String kind, String detail) {
        note(kind, detail);
    }

    /**
     * For the diagnostics page: what this session has repaired, one phrase.
     *
     * <p>Grouped by kind, because the detail is already in the {@code heal:}
     * line and a settings row that listed every frame size would be a
     * paragraph. The shape is "range x1; gate x2" -- enough for a report to
     * say "it says it repaired something", which is the question being asked.
     */
    public static String summary() {
        final java.util.LinkedHashMap<String, Integer> byKind = new java.util.LinkedHashMap<>();
        synchronized (SEEN) {
            for (Map.Entry<String, Integer> e : SEEN.entrySet()) {
                final String key = e.getKey();
                final int bar = key.indexOf('|');
                final String kind = bar > 0 ? key.substring(0, bar) : key;
                final Integer was = byKind.get(kind);
                byKind.put(kind, (was == null ? 0 : was) + e.getValue());
            }
        }
        // Budgets only show when they were touched: a counter that is zero is
        // not a repair.
        synchronized (SPENT) {
            for (Map.Entry<String, Integer> e : SPENT.entrySet()) {
                if (!byKind.containsKey(e.getKey())) byKind.put(e.getKey(), e.getValue());
            }
        }
        if (byKind.isEmpty()) return "nothing repaired";
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : byKind.entrySet()) {
            if (sb.length() > 0) sb.append(" \u00b7 ");
            sb.append(e.getKey()).append(" \u00d7").append(e.getValue());
        }
        return sb.toString();
    }
}
