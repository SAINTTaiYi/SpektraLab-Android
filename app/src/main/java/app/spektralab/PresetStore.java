package app.spektralab;

import org.json.JSONArray;
import org.json.JSONObject;

/** Preset persistence and share compaction; no UI or Activity state. */
public final class PresetStore {
    private PresetStore() {}

static org.json.JSONArray load(android.content.Context c) {
        final String raw = c.getSharedPreferences("presets", android.content.Context.MODE_PRIVATE).getString("list", "[]");
        try {
            return new org.json.JSONArray(raw);
        } catch (Throwable t) {
            return new org.json.JSONArray();
        }
    }

    static void save(android.content.Context c, org.json.JSONArray list) {
        c.getSharedPreferences("presets", android.content.Context.MODE_PRIVATE).edit().putString("list", list.toString()).apply();
    }

/**
     * The keys a share may leave out when they hold the engine's default.
     *
     * <p>82 keys is a screenful of JSON in a chat window, and nine tenths of it
     * is zeros nobody set. Each value here is exactly the fallback
     * `lookFromJson` uses, so omitting one and reading it back is lossless.
     */
    private static final java.util.Map<String, Double> SHARE_DEFAULTS =
            new java.util.HashMap<String, Double>();
    static {
        for (String k : new String[]{
                "highlight", "shadow", "boost", "scatterr", "lens",
                "vT", "vTn", "vE", "vC", "vB", "vS", "vHi", "vSh", "vBk",
                "vWh", "vV", "cEv", "vVb", "wb", "wbT",
                "yf", "mf", "preflash", "leaks", "frameno",
                "ahrem", "scw", "scb", "edr", "slb",
                "cbh0", "cbh1", "cbh2", "cbh3",
                "cbs0", "cbs1", "cbs2", "cbs3",
                "cbl0", "cbl1", "cbl2", "cbl3",
                "gturn", "grot", "gcx", "gcy"}) {
            SHARE_DEFAULTS.put(k, 0.0);
        }
        SHARE_DEFAULTS.put("exp", 1.0);
        SHARE_DEFAULTS.put("grain", 1.0);
        SHARE_DEFAULTS.put("halation", 1.0);
        SHARE_DEFAULTS.put("glare", 1.0);
        SHARE_DEFAULTS.put("scatter", 1.0);
        SHARE_DEFAULTS.put("fog", 1.0);
        SHARE_DEFAULTS.put("halon", 1.0);
        SHARE_DEFAULTS.put("grainon", 1.0);
        SHARE_DEFAULTS.put("coupon", 1.0);
        SHARE_DEFAULTS.put("gla", 1.0);
        SHARE_DEFAULTS.put("fscale", 1.0);
        SHARE_DEFAULTS.put("gcw", 1.0);
        SHARE_DEFAULTS.put("gch", 1.0);
        SHARE_DEFAULTS.put("wbK", 5500.0);
        SHARE_DEFAULTS.put("hhe", 4.0);
        SHARE_DEFAULTS.put("dcg", 1.0);
        SHARE_DEFAULTS.put("rolloff", 2.0);
        SHARE_DEFAULTS.put("lift", 4.0);
        SHARE_DEFAULTS.put("gcri", -1.0);
        SHARE_DEFAULTS.put("dinsx", 3.0);
        SHARE_DEFAULTS.put("dinsy", 2.4);
        SHARE_DEFAULTS.put("dsize", 1.0);
        SHARE_DEFAULTS.put("dev", 3.5);
        SHARE_DEFAULTS.put("mm", 36.0);
        SHARE_DEFAULTS.put("cseed", 1.0);
        SHARE_DEFAULTS.put("fseed", 1.0);
    }

    /** Drop the keys still holding their default, and the flat curves. */
    public static org.json.JSONObject compact(Look l) {
        final org.json.JSONObject full = LookCodec.toJson(l);
        final org.json.JSONObject out = new org.json.JSONObject();
        final java.util.Iterator<String> it = full.keys();
        while (it.hasNext()) {
            final String k = it.next();
            final Double def = SHARE_DEFAULTS.get(k);
            if (def != null) {
                final double v = full.optDouble(k, Double.NaN);
                if (!Double.isNaN(v) && Math.abs(v - def) < 1e-6) continue;
            }
            if (k.startsWith("cv")) {
                final org.json.JSONArray arr = full.optJSONArray(k);
                if (arr == null || arr.length() == 0) continue;
                boolean flat = true;
                for (int i = 0; i < arr.length(); ++i) {
                    if (Math.abs(arr.optDouble(i, 0.0)) > 1e-6) { flat = false; break; }
                }
                if (flat) continue;
            }
            try { out.put(k, full.opt(k)); } catch (Throwable spkIgnored) { Diagnostics.ignored("optJSONArray:94", spkIgnored); }
        }
        return out;
    }
}
