package app.spektralab;

/** The edit recipe attached to one photograph. Persisted as JSON in session state. */
final class Look {
    String filmStock;
    String printStock;
    float printExposure, grainAmount, halationAmount, glareAmount, filmFormatMm;
    String meterMethod;
    /** RFC-023's highlight room, in stops; 0 leaves the stage off. */
    float highlightRoom;
    /** RFC-023's shadow side: lift the toe instead of the shoulder. */
    float shadowRoom;
    /** RFC-025: stops added to the highlights *before* the halo is drawn. */
    float halationBoost;
    /** RFC-025: the in-emulsion scatter, a mix weight (0..1). */
    float halationScatter;
    /** How far that scatter spreads, 0..1 -> 1x..12x the film's radius. */
    float scatterRadius;
    /** Lens blur in micrometres, before the film sees anything. */
    float lensBlur;
    /** The view layer: tone and colour on the finished print. */
    float viewTemperature, viewTint, viewExposure, viewContrast, viewBrightness;
    float viewSaturation, viewHighlights, viewShadows, viewBlack, viewWhite, viewVignette;
    /** Camera exposure compensation, in stops (engine side). */
    float cameraEv;
    float viewVibrance;
    int wbPreset;
    float wbKelvin, wbTint;
    /** The LibRaw gains that produced this frame's preview. Null = as-shot. */
    float[] wbMul;
    /** Enlarger dichroic filters: -1 is 30 CC of the opposite colour. */
    int geometryTurns = 0;
    float geometryRotation = 0.0f;
    float geometryCropW = 1.0f, geometryCropH = 1.0f;
    float geometryCropX = 0.0f, geometryCropY = 0.0f;
    int cropRatioIndex = -1;
    boolean geometryFlipH, geometryFlipV;
    float yFilter, mFilter;
    /** CC value those two shifts are scaled to. */
    float filterScale;
    /** Pre-flash, as a fraction of the clear-base exposure (0..1). */
    float preflash;
    /** Rebate: base fog and light leaks. */
    float edgeFog, edgeLeaks, frameNumber;
    /** Switches: 1 is on. Kept as floats so one knob can drive them. */
    float halationOn = 1f, grainOn = 1f, couplersOn = 1f, antihalationRemoved = 0f;
    float scannerWhite = 0f, scannerBlack = 0f, edrOn = 0f;
    float glareActive = 1f;
    float scannerLensBlur = 0f, halationHeadroom = 4f, densityGamma = 1f;
    boolean digitalIntermediate = false, outputCctf = true;
    float hueCorrection = 1f;
    /** RFC-023's remaining curve shape. */
    float shadowRolloff = 2f, maxLift = 4f;
    /** Choices: lens filter, the norm, and the rebate/date layout. */
    String cameraFilter = "", sceneNorm = "power";
    String edgeMode = "strip", edgeGate = "auto", edgeHoles = "white", edgeCarrier = "none";
    String datePlacement = "frame", dateCorner = "br", dateStyle = "lcd";
    /** mm from the gate's edge to the text, frame placement only. */
    float dateInsetX = 3f, dateInsetY = 2.4f;
    float edgeFNumber = 0f, dateSize = 1f, dateEv = 3.5f;
    /** The manufacturer's edge print. */
    boolean edgeTextOn = true;
    /** Free text: the shooting data, and a pair's second frame's date. */
    String dataText = "", dateTextB = "";
    /** The rebate's own draws. */
    int cameraSeed = 1, frameSeed = 1;
    boolean overscanOn, dateOn;
    String overscanFormat;
    /** The four grading wheels, in 主/阴影/中间调/高光 order. */
    final float[] cbHue = new float[4];
    final float[] cbSat = new float[4];
    final float[] cbLum = new float[4];
    /**
     * The five curve channels (rgb, luma, r, g, b), each a flat x,y,... list.
     * A null entry means "never touched".
     */
    final float[][] curves = new float[5][];
}
