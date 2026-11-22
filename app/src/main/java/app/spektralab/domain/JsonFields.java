package app.spektralab.domain;

/** Bounds-checked field reads for the small engine JSON documents. */
public final class JsonFields {
    private JsonFields() {}

    public static int firstInt(String json, String key) {
        if (json == null) return -1;
        final String needle = "\"" + key + "\":";
        final int at = json.indexOf(needle);
        if (at < 0) return -1;
        int i = skipSpace(json, at + needle.length());
        final int start = i;
        while (i < json.length() && (json.charAt(i) == '-' || Character.isDigit(json.charAt(i)))) ++i;
        try { return Integer.parseInt(json.substring(start, i)); }
        catch (Throwable t) { return -1; }
    }

    public static double firstNumber(String json, String key) {
        if (json == null) return Double.NaN;
        final String needle = "\"" + key + "\":";
        final int at = json.indexOf(needle);
        if (at < 0) return Double.NaN;
        int i = skipSpace(json, at + needle.length());
        final int start = i;
        while (i < json.length()) {
            final char c = json.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') ++i;
            else break;
        }
        if (i == start) return Double.NaN;
        try {
            final double value = Double.parseDouble(json.substring(start, i));
            return Double.isNaN(value) || Double.isInfinite(value) ? Double.NaN : value;
        } catch (Throwable t) { return Double.NaN; }
    }

    public static String snippet(String json, String key, int maxLength) {
        if (json == null) return "(no progress json)";
        final int at = json.indexOf("\"" + key + "\"");
        if (at < 0) return "(none)";
        return json.substring(at, Math.min(json.length(), at + maxLength));
    }

    private static int skipSpace(String s, int at) {
        while (at < s.length() && s.charAt(at) == ' ') ++at;
        return at;
    }
}
