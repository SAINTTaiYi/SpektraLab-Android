package app.spektralab;

/**
 * One adjustable, as the panel shows it: a name, a glyph for the chip, and
 * a getter/setter pair that is the single source of truth. The panel shows
 * **one slider at a time** and a strip of chips to pick which -- thirteen
 * stacked sliders is a wall, and a wall is not a darkroom's control panel.
 */
final class Knob {
    final String label, glyph;
    final float lo, hi;
    final java.util.function.Consumer<Float> set;
    final java.util.function.Supplier<Float> get;
    /**
     * A shoot-layer field, so a drag re-develops the film. The delta names only
     * what moved, and the engine drops the cached negative for anything on the
     * shoot layer -- grain, halation, the latitude pair, the couplers, the lens.
     */
    final boolean heavy;
    /**
     * Logarithmic travel, so the default lands in the middle: 0.25, 1.0 and 4.0
     * are equal steps for an enlarger's exposure.
     */
    final boolean logScale;

    Knob(String label, String glyph, float lo, float hi,
         java.util.function.Consumer<Float> set, java.util.function.Supplier<Float> get,
         boolean heavy, boolean logScale) {
        this.label = label; this.glyph = glyph; this.lo = lo; this.hi = hi;
        this.set = set; this.get = get; this.heavy = heavy; this.logScale = logScale;
    }
}
