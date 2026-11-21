package app.spektralab;

/** JSON string quoting for the hand-built parameter deltas. Pure Java. */
final class JsonQuote {
    private JsonQuote() {}

    static String quote(String v) {
        if (v == null) return "\"\"";
        final StringBuilder out = new StringBuilder(v.length() + 2).append('"');
        for (int i = 0; i < v.length(); ++i) {
            final char c = v.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
