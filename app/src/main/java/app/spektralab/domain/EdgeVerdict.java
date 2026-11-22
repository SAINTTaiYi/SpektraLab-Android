package app.spektralab.domain;

/** Structured interpretation of the engine's `edge ...` diagnostic line. */
public final class EdgeVerdict {
    public final String kind;
    public final String first;
    public final String second;
    public final String third;
    public final String fourth;
    public final String fifth;

    private EdgeVerdict(String kind, String first, String second, String third,
                        String fourth, String fifth) {
        this.kind = kind;
        this.first = first;
        this.second = second;
        this.third = third;
        this.fourth = fourth;
        this.fifth = fifth;
    }

    /** Returns null for a clean frame or an unrecognised line. */
    public static EdgeVerdict fromLine(String line) {
        if (line == null || !line.startsWith("edge ")) return null;
        final java.util.regex.Matcher r = java.util.regex.Pattern
                .compile("Lrow=(\\d+)@(-?\\d+) n(\\d+) g(\\d+) Y=(\\S+)").matcher(line);
        if (r.find() && Integer.parseInt(r.group(1)) >= 30 && Integer.parseInt(r.group(3)) >= 10) {
            return new EdgeVerdict("left_short_marks", r.group(1), r.group(2),
                    r.group(3), r.group(4), r.group(5));
        }
        final java.util.regex.Matcher w = java.util.regex.Pattern
                .compile("Wrow=(\\d+)@(-?\\d+) n(\\d+)").matcher(line);
        if (w.find() && Integer.parseInt(w.group(1)) >= 20 && Integer.parseInt(w.group(3)) >= 10) {
            return new EdgeVerdict("left_wide_band", w.group(1), w.group(2),
                    w.group(3), "", "");
        }
        final java.util.regex.Matcher v = java.util.regex.Pattern
                .compile("Vmax=(\\d+)@(\\d+)").matcher(line);
        if (v.find() && Integer.parseInt(v.group(1)) >= 8) {
            return new EdgeVerdict("vertical_line", v.group(2), v.group(1), "", "", "");
        }
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(" ([LRTB])=(\\d+)@(-?\\d+) p(\\d+)").matcher(line);
        while (m.find()) {
            if (Integer.parseInt(m.group(4)) >= 8) {
                return new EdgeVerdict("side_period", m.group(1), m.group(4), "", "", "");
            }
        }
        return null;
    }
}
