package app.spektralab.ui;

/** The app's theme tokens. Mutated once at startup, read by every panel. */
public final class UiPalette {
    public static int BLUE = 0xFF1C2AA5;
    public static int BLUE_LIGHT = 0xFF3B4BC0;
    public static int PAPER = 0xFFF1F1EE;
    public static int GROUND = 0xFFE3E3E0;
    public static int INK = 0xFF171717;
    public static int MUTED = 0xFF777772;
    public static int LINE = 0xFFCECEC8;
    public static int STAGE = 0xFF172174;
    public static final int ON_WHITE = 0xFF171717;
    private static boolean dark = false;

    private UiPalette() {}

    public static boolean dark() { return dark; }

    public static int toggleOff() { return dark ? 0xFF7A736A : 0x66171717; }

    public static int onCanvas() { return 0xFFF1F1EE; }

    public static void apply(boolean isDark) {
        dark = isDark;
        if (isDark) {
            BLUE = 0xFFE8862A; BLUE_LIGHT = 0xFFFFA54F;
            PAPER = 0xFF171717; GROUND = 0xFF242320;
            INK = 0xFFEDEAE4; MUTED = 0xFF9A948A; LINE = 0xFF3A352F;
            STAGE = 0xFF000000;
        } else {
            BLUE = 0xFF1C2AA5; BLUE_LIGHT = 0xFF3B4BC0;
            PAPER = 0xFFF1F1EE; GROUND = 0xFFE3E3E0;
            INK = 0xFF171717; MUTED = 0xFF777772; LINE = 0xFFCECEC8;
            STAGE = 0xFF172174;
        }
    }
}
