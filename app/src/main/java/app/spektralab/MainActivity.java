package app.spektralab;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import app.spektralab.engine.SpektraLabEngine;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MainActivity extends Activity {
    private SpektraLabEngine engine;
    /** This GPU's limits, read once per engine. Drives the import cap and the export tiers. */
    private DeviceProbe probe = DeviceProbe.parse("{}");
    /** The canary runs once per process, on its own thread. */
    private boolean canaryStarted = false;
    /** Set by the long-press on 导入: keep the decoder's own size and say it may fail. */
    private boolean importAtNativeSize = false;
    /** A cap the last E-1001 offered, good for exactly one import. */
    private int pendingCapPixels = 0;
    /** The user has already said yes to this frame's downscale. One import. */
    private boolean importBudgetAccepted = false;
    /** Set while a chip is re-opening a frame the user already agreed to
     *  shrink. The decision was made the first time; asking again on every tap
     *  turned the strip into a dialog cannon. */
    private boolean suppressBudgetDialog = false;
    /** A "按原尺寸重来" retry decodes the same file again. It must not add a
     *  second chip for a photograph the strip already carries. */
    private boolean retryingNativeSize = false;
    /** One retry offer per import; a dialog loop would be worse than the error. */
    private boolean e1001Offered = false;
    /** A render is in flight: one native result buffer, one renderer. */
    private volatile boolean rendering = false;
    private SpektraLabEngine.Session session;
    private boolean isRawFile = false;
    private String rawFilePath = null;
    // The last file that reached the gallery, so it can be handed straight to
    // another app. A picker that filters by MIME will not show a TIFF on a ROM
    // whose picture list does not know the type; an explicit ACTION_SEND does,
    // because the type travels in the intent instead of being guessed.
    private Uri lastExportUri = null;
    private String lastExportMime = null;
    /** Shown only after a TIFF export: the one format the picker hides. */
    private TextView sharePill = null;
    private Bitmap sourceBitmap;
    private Uri sourceUri;
    private Bitmap lastRenderedBitmap;
    private ByteBuffer sourcePixels;
    private int sourceWidth, sourceHeight;
    private CompareView compare;
    /** What the import put on the stage: the frame before any film. */
    private Bitmap beforeBitmap;
    private TextView statusText;
    private ProgressBar progressBar;
    private ProgressBar exportProgressBar;
    private final List<String> filmIds = new ArrayList<>(), filmLabels = new ArrayList<>();
    private final List<String> filmTypes = new ArrayList<>();
    /** Each film's declared paper (`target_print` in its profile, or the
     *  catalogue's alias for a stub profile). Selecting a film moves the paper
     *  here -- the macOS/iOS app's `selectFilmStock` rule. */
    private final java.util.Map<String, String> filmTargetPrints = new java.util.HashMap<>();
    private final List<String> printIds = new ArrayList<>(), printLabels = new ArrayList<>();
    /** null until the user picks a film: a fresh launch must not look like
     *  a Portra 400 session, and must not render one behind their back. */
    private String filmStock = null;
    private String printStock = "kodak_portra_endura";
    private float printExposure = 1.0f;
    private float grainAmount = 1.0f;      // 0..2, engine default 1
    private float halationAmount = 1.0f;   // 0..4
    // The paper's own veil is 1.0 on the wire (`GlareParams::amount`), and the
    // macOS/iOS app sends it at 1. The 3.0 this started at was a guess made
    // before that was readable: at 3 the veil is 0.09 % of the frame instead
    // of 0.03 %, which lifts the blacks a third of a stop and reads as haze.
    private float glareAmount = 1.0f;      // 0..30, area multiplier of the paper's own
    /**
     * RFC-023's highlight room: stops of scene highlight the print is given
     * back before it is laid onto the paper's shoulder. 0 is the stage off.
     *
     * <p>The complaint this answers was "the highlights blow out, the tonal
     * distribution up there is too short" -- the scene's top two or three
     * stops arriving at a paper that has half a stop of shoulder left. The
     * engine already models the shoulder; what it had no control over is how
     * much of the scene is delivered into it. Measured on a +2 EV synthetic
     * frame: off, the top 5% sat at 217/255 with saturation collapsing to
     * 17 %; at 2 stops of room it was 210/255 and 25 % -- the colour comes
     * back because the highlights stop climbing into the flat top.
     */
    private float highlightRoom = 0.0f;    // 0..3, stops
    private float shadowRoom = 0.0f;       // 0..3, stops (the toe, RFC-023)
    private float halationBoost = 0.0f;    // 0..8 EV, energy a clipped highlight lost
    private float halationScatter = 1.0f;  // 0..1, in-emulsion scatter mix
    private float lensBlur = 0.0f;         // 0..200 µm
    private float yFilter = 0.0f;          // -1..1, enlarger yellow
    private float mFilter = 0.0f;          // -1..1, enlarger magenta
    private float filterScale = 1.0f;      // 1..60 CC
    private float preflash = 0.0f;         // 0..1
    private float edgeFog = 1.0f;          // 0..4
    private float edgeLeaks = 0.0f;        // 0..4
    private float frameNumber = 0.0f;      // 0..99
    private float halationOn = 1f, grainOn = 1f, couplersOn = 1f, antihalationRemoved = 0f;
    private float scannerWhite = 0f, scannerBlack = 0f, edrOn = 0f;
    private float shadowRolloff = 2f, maxLift = 4f;
    private String cameraFilter = "", sceneNorm = "power";
    private String edgeMode = "strip", edgeGate = "auto", edgeHoles = "white", edgeCarrier = "none";
    private String datePlacement = "frame", dateCorner = "br", dateStyle = "lcd";
    private float dateInsetX = 3f, dateInsetY = 2.4f;
    private float edgeFNumber = 0f, dateSize = 1f, dateEv = 3.5f;
    private boolean edgeTextOn = true;
    private String dataText = "", dateTextB = "";
    private int cameraSeed = 1, frameSeed = 1;
    private float filmFormatMm = 36.0f;    // frame's long edge, 135 = 36 mm
    private boolean uiReady = false;
    private String lastImportError = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashLogger();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            android.util.Log.e("SpektraLab", "Uncaught exception in " + t.getName(), e);
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                DebugSink.text("crash", "thread=" + t.getName() + "\n" + sw);
            } catch (Throwable ignored) {}
        });
        // The native side, before anything reads it: every object the engine
        // is linked against is loaded by path, and a failure is kept (see
        // SpektraLabEngine.ensureLoaded) so that the import reports the real
        // reason instead of a NoClassDefFoundError on the class itself.
        String nativeDir = null;
        try { nativeDir = getApplicationInfo().nativeLibraryDir; } catch (Throwable ignored) {}
        try { SpektraLabEngine.prepare(nativeDir); } catch (Throwable e) { lastImportError = describe(e); }
        try { DebugSink.text("app-start", "buildInfo=" + SpektraLabEngine.buildInfo()); } catch (Throwable ignored) {}
        
        buildUi();
        uiReady = true;
        // The strip and the look come back on the next frame, not inside
        // onCreate: re-decoding a 50 MP RAW here would hold the first layout
        // for seconds and read as a hung launch. One empty frame first, then
        // the photograph arrives.
        new android.os.Handler(android.os.Looper.getMainLooper()).post(this::restoreSessionState);

        // Silent, and late enough not to compete with the first import. It only
        // speaks when there is a newer build than this one.
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> UpdateCheck.check(this, false), 3000);
        // Once per build, and late enough that the canvas has settled first.
        maybeShowFeedbackGroup();
    }

    // ---------------------------------------------------------------------
    // The interface.
    //
    // /sdcard/Download/SpektraLab/index.html is the reference. It is drawn at
    // a 390 px viewport, so its CSS px are this app's dp, and every number
    // below is quoted from it. Three deliberate departures:
    //   * the page's 12 px outer frame is dropped -- a phone screen is the app,
    //     not a sheet of paper floating on a page;
    //   * the preview *fits* the frame into itself (FIT_CENTER) where the
    //     page's last pass says `object-fit: cover`, which would crop away the
    //     edge of the photograph the user is about to render;
    //   * the ranges stay inside the engine's schema: grain stops at 2 (the
    //     page says 4; params.cpp refuses anything over 2) and exposure starts
    //     at 0.25 (below it the print is black and the schema's floor is 0.05).
    // ---------------------------------------------------------------------
    private static final int UI_BLUE       = 0xFF1C2AA5;   // --blue
    private static final int UI_BLUE_LIGHT = 0xFF3B4BC0;   // --blue-light
    private static final int UI_PAPER      = 0xFFF1F1EE;   // --paper
    private static final int UI_GROUND     = 0xFFE3E3E0;   // --ground
    private static final int UI_INK        = 0xFF171717;   // --ink
    private static final int UI_MUTED      = 0xFF777772;   // --muted
    private static final int UI_LINE       = 0xFFCECEC8;   // --line
    private static final int UI_STAGE      = 0xFF172174;   // .preview-image

    private Typeface tfDisplay;      // Inter 300 — numbers and the wordmark
    private Typeface tfBody;         // Inter 400 — Latin labels
    private Typeface tfHan;          // OPPO Sans — Chinese, and anything mixed
    private View emptyState, rawState;
    private TextView fileLabel, rawName, busyLabel, originalLabel, paperValue, edgeValue, formatFootValue;
    /**
     * One adjustable, as the panel shows it: a name, a glyph for the chip, and
     * a getter/setter pair that is the single source of truth. The panel shows
     * **one slider at a time** and a strip of chips to pick which -- thirteen
     * stacked sliders is a wall, and a wall is not a darkroom's control panel.
     */
    private static final class Knob {
        final String label, glyph;
        final float lo, hi;
        final java.util.function.Consumer<Float> set;
        final java.util.function.Supplier<Float> get;
        Knob(String label, String glyph, float lo, float hi,
             java.util.function.Consumer<Float> set, java.util.function.Supplier<Float> get) {
            this.label = label; this.glyph = glyph; this.lo = lo; this.hi = hi;
            this.set = set; this.get = get;
        }
    }
    private final java.util.List<Knob> knobs = new java.util.ArrayList<>();
    private int activeKnob = 0;
    private TextView knobTitle, knobValue, knobHintView;
    private SeekBar knobBar;
    private LinearLayout knobStrip;
    private SeekBar edgeFogBar, edgeLeaksBar, frameNoBar, edgeFNumBar, dateSizeBar, dateEvBar;
    private SeekBar dateInsetXBar, dateInsetYBar;
    private Square edgeTextMark;
    private LinearLayout edgeRoot, edgePage, datePage, edgeInner, dateInner;
    private int edgePageIndex = -1;
    private static final String[] MODE_IDS = {"strip", "filed"};
    private static final String[] MODE_LABELS = {"整条 strip", "窄边 filed"};
    private static final String[] GATE_IDS = {"auto", "square", "rounded", "eared", "shouldered", "kicked"};
    private static final String[] GATE_LABELS = {"自动", "方角", "圆角", "带耳", "带肩", "踢角"};
    private static final String[] HOLES_IDS = {"white", "black"};
    private static final String[] HOLES_LABELS = {"白齿孔（扫描）", "黑齿孔（暗房）"};
    private static final String[] CARRIER_IDS = {"none", "black", "open"};
    private static final String[] CARRIER_LABELS = {"无", "黑色片夹", "开放"};
    private static final String[] PLACE_IDS = {"frame", "rebate"};
    private static final String[] PLACE_LABELS = {"画面内", "片边"};
    private static final String[] CORNER_IDS = {"br", "bl", "tr", "tl"};
    private static final String[] STYLE_IDS = {"lcd", "dots", "data"};
    private static final String[] STYLE_LABELS = {"LCD 七段", "点阵 dots", "拍摄数据 data"};
    private static final String[] CORNER_LABELS = {"右下", "左下", "右上", "左上"};
    private LinearLayout opticsPanel;
    private boolean opticsUnlocked = false;

    // RFC-032/031, the two things the phone build never exposed: the rebate the
    // frame was cut from, and the date a point-and-shoot burned into it.
    private boolean overscanOn = false;
    private String overscanFormat = "135";
    private boolean dateOn = false;
    private String dateText = null;
    private Square overscanMark, dateMark;

    /** 片边: the ten carriers the engine's overscan knows. */
    private static final String[] EDGE_LABELS = {
            "135 全画幅", "135 半格", "135 XPan", "645",
            "6×6", "6×7", "6×8", "6×9", "6×12", "6×17"};
    private static final String[] EDGE_IDS = {
            "135", "135_half", "135_xpan", "120_645",
            "120_6x6", "120_6x7", "120_6x8", "120_6x9", "120_6x12", "120_6x17"};
    /** Each gate's long/short, from overscan.cpp's measured kFormats. The
     *  engine refuses a rebate cut for a different shape (5 %), so the list the
     *  user sees is filtered to the gates this frame actually is. */
    private static final double[] EDGE_RATIOS = {
            36.25 / 24.3, 24.0 / 18.0, 65.0 / 24.0, 56.0 / 41.5,
            1.0, 69.5 / 56.0, 76.0 / 56.0, 84.0 / 56.0, 112.0 / 56.0, 168.0 / 56.0};
    /** The frame each gate actually takes, in mm -- the same numbers the engine's
     *  kFormats carries as `along`.
     *
     *  <p>These have to travel with the gate. The rebate's canvas is the film's
     *  width (from the gate: 35 mm for 135, 61.5 mm for 120) plus the frame's
     *  own length (from the 尺度 page). Two independent controls means they can
     *  disagree, and "6x8 film around a 36 mm frame" is a camera that does not
     *  exist: the layout then stretches a 135-shaped rebate across a 120-wide
     *  film, and the edge print lands where the picture's corner should be. That
     *  is the "预览正常、导出乱飞" report: the preview had been drawn with the
     *  old gate, the export with the new one. Choosing a gate now chooses the
     *  frame that fits it. */
    private static final float[] EDGE_MM = {
            36f, 24f, 65f, 56f, 56f, 69.5f, 76f, 84f, 112f, 168f};

    /** The gate's frame length, by id (not by position -- `edgeIds` is a
     *  filtered subset of EDGE_IDS). */
    private static float gateMm(String id) {
        for (int i = 0; i < EDGE_IDS.length; ++i) if (EDGE_IDS[i].equals(id)) return EDGE_MM[i];
        return 36f;
    }

    /** The gate whose frame is closest to `mm`, for the scale page's cards. */
    private static String gateForMm(float mm) {
        String best = EDGE_IDS[0];
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < EDGE_IDS.length; ++i) {
            final float d = Math.abs(EDGE_MM[i] - mm);
            if (d < bestD) { bestD = d; best = EDGE_IDS[i]; }
        }
        return best;
    }

    private static String gateLabelsFallback(String id) {
        for (int i = 0; i < EDGE_IDS.length; ++i) if (EDGE_IDS[i].equals(id)) return EDGE_LABELS[i];
        return id;
    }

    /** Move the 尺度 page onto `mm`, highlight and all. */
    private void setScaleMm(float mm) {
        filmFormatMm = mm;
        int best = 0;
        for (int i = 0; i < FORMAT_MM.length; ++i) {
            if (Math.abs(FORMAT_MM[i] - mm) < Math.abs(FORMAT_MM[best] - mm)) best = i;
        }
        formatIndex = best;
        if (formatFootValue != null) {
            formatFootValue.setText(String.format(java.util.Locale.US, "%.0f mm", FORMAT_MM[best]));
        }
        rebuildFormatCards();
    }
    private final List<String> edgeLabels = new ArrayList<>(), edgeIds = new ArrayList<>();
    private LinearLayout controls, panelHolder, filmCardStrip, brandRow, formatCardStrip;
    private HorizontalScrollView filmScroll, formatScroll;
    /**
     * One photograph this session has opened, kept so the strip under the
     * canvas can bring it back without a trip through the picker.
     *
     * <p>`thumb` is its own small bitmap, not a reference to `beforeBitmap`:
     * the live source is replaced on every import, and a list holding those
     * would pin one 1024-px ARGB buffer per photograph.
     */
    /** Everything the film page and the optics page own.
     *
     *  <p>A look belongs to the photograph it was chosen on. It used to be one
     *  set of fields for the whole session, so re-opening the first frame after
     *  picking a film on the second one developed the *second* film onto the
     *  first: the shelf was a memory of photographs but only one memory of a
     *  look. */
    /**
     * A SeekBar you can grab anywhere.
     *
     * <p>The stock widget only starts a drag when the finger lands on the
     * thumb: the track is 2 px tall and the thumb 9 dp across, so most of a
     * fingertip misses and the row feels dead. This one maps the whole width
     * of the view to the value and starts on touch-down, which is also how a
     * camera app's slider behaves -- track to the value you want, not grab and
     * carry.
     *
     * <p>`onStopTrackingTouch` is fired by hand: the superclass only fires it
     * from its own gesture, and the app's rows send their delta from there
     * (one JNI call per gesture rather than one per hundredth of a stop).
     */
    private static final class ScrubBar extends SeekBar {
        private SeekBar.OnSeekBarChangeListener listener;
        private final int slop;
        private float downX, downY;
        private boolean tracking;      // the finger is allowed to move the value
        private boolean dragging;

        ScrubBar(android.content.Context c) {
            super(c);
            slop = (int) (8 * c.getResources().getDisplayMetrics().density);
        }

        @Override public void setOnSeekBarChangeListener(SeekBar.OnSeekBarChangeListener l) {
            listener = l;
            super.setOnSeekBarChangeListener(l);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent ev) {
            if (!isEnabled()) return false;
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    // Nothing moves yet. A finger that lands here on its way to
                    // scrolling the page must not change the value, and it must
                    // not steal the gesture from the ScrollView either.
                    downX = ev.getX();
                    downY = ev.getY();
                    tracking = true;
                    dragging = false;
                    setPressed(true);
                    return true;

                case android.view.MotionEvent.ACTION_MOVE: {
                    if (!tracking) return false;
                    final float dx = Math.abs(ev.getX() - downX);
                    final float dy = Math.abs(ev.getY() - downY);
                    if (!dragging) {
                        if (dy > slop && dy > dx) {
                            // A vertical swipe: hand it back so the list scrolls.
                            tracking = false;
                            setPressed(false);
                            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                            return false;
                        }
                        if (dx <= slop) return true;
                        dragging = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        if (listener != null) listener.onStartTrackingTouch(this);
                    }
                    return seekTo(ev.getX());
                }

                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    setPressed(false);
                    if (tracking && !dragging && ev.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                        // A tap: the whole width is the target.
                        if (listener != null) listener.onStartTrackingTouch(this);
                        seekTo(ev.getX());
                        if (listener != null) listener.onStopTrackingTouch(this);
                        performClick();
                    } else if (dragging) {
                        if (listener != null) listener.onStopTrackingTouch(this);
                        performClick();
                    }
                    tracking = false;
                    dragging = false;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    return true;

                default:
                    return true;
            }
        }

        private boolean seekTo(float x) {
            final int usable = getWidth() - getPaddingLeft() - getPaddingRight();
            if (usable <= 0) return false;
            float t = (x - getPaddingLeft()) / usable;
            t = Math.max(0f, Math.min(1f, t));
            setProgress(Math.round(t * getMax()));
            return true;
        }
    }

    private static final class Look {
        String filmStock;
        String printStock;
        float printExposure, grainAmount, halationAmount, glareAmount, filmFormatMm;
        /** RFC-023's highlight room, in stops; 0 leaves the stage off. */
        float highlightRoom;
        /** RFC-023's shadow side: lift the toe instead of the shoulder. */
        float shadowRoom;
        /** RFC-025: stops added to the highlights *before* the halo is drawn. */
        float halationBoost;
        /** RFC-025: the in-emulsion scatter, a mix weight (0..1). */
        float halationScatter;
        /** Lens blur in micrometres, before the film sees anything. */
        float lensBlur;
        /** Enlarger dichroic filters: -1 is 30 CC of the opposite colour. */
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
        /** RFC-023's remaining curve shape. */
        float shadowRolloff = 2f, maxLift = 4f;
        /** Choices: lens filter, the norm, and the rebate/date layout. */
        String cameraFilter = "", sceneNorm = "power";
        String edgeMode = "strip", edgeGate = "auto", edgeHoles = "white", edgeCarrier = "none";
        String datePlacement = "frame", dateCorner = "br", dateStyle = "lcd";
        /** mm from the gate's edge to the text, frame placement only. */
        float dateInsetX = 3f, dateInsetY = 2.4f;
        float edgeFNumber = 0f, dateSize = 1f, dateEv = 3.5f;
        /** The manufacturer's edge print: its own switch, because it is the
         *  one part of the rebate that has been landing inside the picture. */
        boolean edgeTextOn = true;
        /** Free text: the shooting data, and a pair's second frame's date. */
        String dataText = "", dateTextB = "";
        /** The rebate's own draws. */
        int cameraSeed = 1, frameSeed = 1;
        boolean overscanOn, dateOn;
        String overscanFormat;
    }
    private static final class Frame {
        final Uri uri;
        final boolean raw;
        final String name;
        Bitmap thumb;
        Look look = new Look();
        Frame(Uri uri, boolean raw, String name) {
            this.uri = uri; this.raw = raw; this.name = name;
        }
    }
    /**
     * The strip survives the process.
     *
     * <p>Every photograph the user opened, in the order they opened it, with
     * the look they gave it. The pixels are not kept -- a decoded RAW is 12
     * bytes per pixel and no phone will hold a shelf of those in a preference
     * file -- but the *recipe* is, and re-developing the frame on return is
     * what "I came back and my edit was still there" means.
     *
     * <p>Without this a return from another app started on an empty canvas:
     * the system reclaims a process holding a 200 MB frame plus a Vulkan
     * context long before it reclaims a chat app, and `onCreate` had nothing
     * to rebuild from.
     */
    private static final String PREFS_SESSION = "session";
    private static final String KEY_SESSION = "state";
    private static final String KEY_THUMBS = "thumbs";

    private static org.json.JSONObject lookToJson(Look l) {
        final org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("film", l.filmStock == null ? "" : l.filmStock);
            o.put("print", l.printStock == null ? "" : l.printStock);
            o.put("exp", (double) l.printExposure);
            o.put("grain", (double) l.grainAmount);
            o.put("halation", (double) l.halationAmount);
            o.put("glare", (double) l.glareAmount);
            o.put("highlight", (double) l.highlightRoom);
            o.put("shadow", (double) l.shadowRoom);
            o.put("boost", (double) l.halationBoost);
            o.put("scatter", (double) l.halationScatter);
            o.put("lens", (double) l.lensBlur);
            o.put("yf", (double) l.yFilter);
            o.put("mf", (double) l.mFilter);
            o.put("fscale", (double) l.filterScale);
            o.put("preflash", (double) l.preflash);
            o.put("fog", (double) l.edgeFog);
            o.put("leaks", (double) l.edgeLeaks);
            o.put("frameno", (double) l.frameNumber);
            o.put("halon", (double) l.halationOn);
            o.put("grainon", (double) l.grainOn);
            o.put("coupon", (double) l.couplersOn);
            o.put("ahrem", (double) l.antihalationRemoved);
            o.put("scw", (double) l.scannerWhite);
            o.put("scb", (double) l.scannerBlack);
            o.put("edr", (double) l.edrOn);
            o.put("rolloff", (double) l.shadowRolloff);
            o.put("lift", (double) l.maxLift);
            o.put("cfilter", l.cameraFilter == null ? "" : l.cameraFilter);
            o.put("snorm", l.sceneNorm == null ? "power" : l.sceneNorm);
            o.put("emode", l.edgeMode == null ? "strip" : l.edgeMode);
            o.put("egate", l.edgeGate == null ? "auto" : l.edgeGate);
            o.put("eholes", l.edgeHoles == null ? "white" : l.edgeHoles);
            o.put("ecarrier", l.edgeCarrier == null ? "none" : l.edgeCarrier);
            o.put("dplace", l.datePlacement == null ? "frame" : l.datePlacement);
            o.put("dcorn", l.dateCorner == null ? "br" : l.dateCorner);
            o.put("dstyle", l.dateStyle == null ? "lcd" : l.dateStyle);
            o.put("dinsx", (double) l.dateInsetX);
            o.put("dinsy", (double) l.dateInsetY);
            o.put("etext", l.edgeTextOn);
            o.put("efnum", (double) l.edgeFNumber);
            o.put("dsize", (double) l.dateSize);
            o.put("dev", (double) l.dateEv);
            o.put("dtext", l.dataText == null ? "" : l.dataText);
            o.put("dtextb", l.dateTextB == null ? "" : l.dateTextB);
            o.put("cseed", (double) l.cameraSeed);
            o.put("fseed", (double) l.frameSeed);
            o.put("mm", (double) l.filmFormatMm);
            o.put("overscan", l.overscanOn);
            o.put("format", l.overscanFormat == null ? "135" : l.overscanFormat);
            o.put("date", l.dateOn);
        } catch (Throwable ignored) { }
        return o;
    }

    private static Look lookFromJson(org.json.JSONObject o) {
        final Look l = new Look();
        if (o == null) return l;
        final String film = o.optString("film", "");
        l.filmStock = film.isEmpty() ? null : film;
        final String paper = o.optString("print", "");
        l.printStock = paper.isEmpty() ? "kodak_portra_endura" : paper;
        l.printExposure = (float) o.optDouble("exp", 1.0);
        l.grainAmount = (float) o.optDouble("grain", 1.0);
        l.halationAmount = (float) o.optDouble("halation", 1.0);
        l.glareAmount = (float) o.optDouble("glare", 1.0);
        l.highlightRoom = (float) o.optDouble("highlight", 0.0);
        l.shadowRoom = (float) o.optDouble("shadow", 0.0);
        l.halationBoost = (float) o.optDouble("boost", 0.0);
        l.halationScatter = (float) o.optDouble("scatter", 1.0);
        l.lensBlur = (float) o.optDouble("lens", 0.0);
        l.yFilter = (float) o.optDouble("yf", 0.0);
        l.mFilter = (float) o.optDouble("mf", 0.0);
        l.filterScale = (float) o.optDouble("fscale", 1.0);
        l.preflash = (float) o.optDouble("preflash", 0.0);
        l.edgeFog = (float) o.optDouble("fog", 1.0);
        l.edgeLeaks = (float) o.optDouble("leaks", 0.0);
        l.frameNumber = (float) o.optDouble("frameno", 0.0);
        l.halationOn = (float) o.optDouble("halon", 1.0);
        l.grainOn = (float) o.optDouble("grainon", 1.0);
        l.couplersOn = (float) o.optDouble("coupon", 1.0);
        l.antihalationRemoved = (float) o.optDouble("ahrem", 0.0);
        l.scannerWhite = (float) o.optDouble("scw", 0.0);
        l.scannerBlack = (float) o.optDouble("scb", 0.0);
        l.edrOn = (float) o.optDouble("edr", 0.0);
        l.shadowRolloff = (float) o.optDouble("rolloff", 2.0);
        l.maxLift = (float) o.optDouble("lift", 4.0);
        l.cameraFilter = o.optString("cfilter", "");
        l.sceneNorm = o.optString("snorm", "power");
        l.edgeMode = o.optString("emode", "strip");
        l.edgeGate = o.optString("egate", "auto");
        l.edgeHoles = o.optString("eholes", "white");
        l.edgeCarrier = o.optString("ecarrier", "none");
        l.datePlacement = o.optString("dplace", "frame");
        l.dateCorner = o.optString("dcorn", "br");
        l.dateStyle = o.optString("dstyle", "lcd");
        l.dateInsetX = (float) o.optDouble("dinsx", 3.0);
        l.dateInsetY = (float) o.optDouble("dinsy", 2.4);
        l.edgeTextOn = o.optBoolean("etext", true);
        l.edgeFNumber = (float) o.optDouble("efnum", 0.0);
        l.dateSize = (float) o.optDouble("dsize", 1.0);
        l.dateEv = (float) o.optDouble("dev", 3.5);
        l.dataText = o.optString("dtext", "");
        l.dateTextB = o.optString("dtextb", "");
        l.cameraSeed = o.optInt("cseed", 1);
        l.frameSeed = o.optInt("fseed", 1);
        l.filmFormatMm = (float) o.optDouble("mm", 36.0);
        l.overscanOn = o.optBoolean("overscan", false);
        final String format = o.optString("format", "135");
        l.overscanFormat = format.isEmpty() ? "135" : format;
        l.dateOn = o.optBoolean("date", false);
        return l;
    }

    private java.io.File thumbFile(Uri uri) {
        return new java.io.File(getCacheDir(),
                "thumb_" + Integer.toHexString(uri.toString().hashCode()) + ".png");
    }

    private void persistThumb(Frame frame) {
        if (frame == null || frame.thumb == null) return;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(thumbFile(frame.uri))) {
            frame.thumb.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } catch (Throwable ignored) { }
    }

    private Bitmap readThumb(Uri uri) {
        try {
            final java.io.File f = thumbFile(uri);
            if (!f.isFile()) return null;
            return android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    private void saveSessionState() {
        try {
            final org.json.JSONArray arr = new org.json.JSONArray();
            for (Frame f : frames) {
                final org.json.JSONObject o = new org.json.JSONObject();
                o.put("uri", f.uri.toString());
                o.put("raw", f.raw);
                o.put("name", f.name == null ? "" : f.name);
                o.put("look", lookToJson(f.look));
                arr.put(o);
            }
            final org.json.JSONObject root = new org.json.JSONObject();
            root.put("frames", arr);
            root.put("current", currentFrame);
            root.put("collapsed", shelfCollapsed);
            getSharedPreferences(PREFS_SESSION, MODE_PRIVATE).edit()
                    .putString(KEY_SESSION, root.toString()).apply();
        } catch (Throwable t) {
            try { DebugSink.text("session", "save failed: " + t); } catch (Throwable ignored) { }
        }
    }

    /**
     * Put the strip back. Returns true when a frame is on its way in.
     *
     * <p>The photograph itself is decoded again by the same entry point a chip
     * uses, so a restored RAW takes the RAW road and a restored JPEG the JPEG
     * one. `reopeningFrame` keeps it from appending a second copy.
     */
    private boolean restoreSessionState() {
        String raw = null;
        try {
            raw = getSharedPreferences(PREFS_SESSION, MODE_PRIVATE).getString(KEY_SESSION, null);
        } catch (Throwable ignored) { }
        if (raw == null || raw.isEmpty()) return false;
        try {
            final org.json.JSONObject root = new org.json.JSONObject(raw);
            final org.json.JSONArray arr = root.optJSONArray("frames");
            if (arr == null || arr.length() == 0) return false;
            frames.clear();
            for (int i = 0; i < arr.length(); ++i) {
                final org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                final String u = o.optString("uri", "");
                if (u.isEmpty()) continue;
                final Uri uri = Uri.parse(u);
                final Frame frame = new Frame(uri, o.optBoolean("raw", false), o.optString("name", ""));
                frame.look = lookFromJson(o.optJSONObject("look"));
                frame.thumb = readThumb(uri);
                frames.add(frame);
            }
            if (frames.isEmpty()) return false;
            final int saved = root.optInt("current", -1);
            currentFrame = (saved >= 0 && saved < frames.size()) ? saved : frames.size() - 1;
            shelfCollapsed = root.optBoolean("collapsed", false);
            rebuildFrameChips();
            setShelfCollapsed(shelfCollapsed);
            try {
                DebugSink.text("session", "restored " + frames.size() + " frames, current=" + currentFrame);
            } catch (Throwable ignored) { }
            // The photograph is decoded off the shelf by the same road a chip
            // takes. If the file is gone (moved, or a picker grant expired) the
            // import reports it and the strip keeps the rest.
            //
            // `suppressBudgetDialog`: the user answered that question on the
            // first import, and a restore is not a new decision. Without this
            // every return from another app re-asked "this is 50 MP, shrink it?"
            importBudgetAccepted = true;
            suppressBudgetDialog = true;
            reopeningFrame = true;
            loadImage(frames.get(currentFrame).uri);
            return true;
        } catch (Throwable t) {
            try { DebugSink.text("session", "restore failed: " + t); } catch (Throwable ignored) { }
            return false;
        }
    }

    private final java.util.List<Frame> frames = new java.util.ArrayList<>();
    private LinearLayout sourceBar, sourceRow, thumbStrip;
    /** The folded strip: a short stack of prints in the canvas's lower-left. */
    private View shelfHandle;
    private ImageView shelfHandleImage;
    private HorizontalScrollView thumbScroll;
    private boolean shelfCollapsed = false;
    /** Set while a chip is re-opening a frame, so the import does not append a
     *  second copy of a photograph the strip already has. */
    private boolean reopeningFrame = false;
    private int currentFrame = -1;
    private LinearLayout originalAction;
    private ToolGlyph originalGlyph;
    private int activeTabIndex = 0;
    private boolean panelCollapsed = false;
    /** The shelf opens on 原图, never on a stock the user did not pick. */
    private boolean showingOriginal = true;
    private String brandFilter = "all";
    private int formatIndex = 0;
    private final List<View> filmCards = new ArrayList<>();
    /** Real previews, per stock, from that stock's own render. A card never
     *  pretends to be a look the engine has not produced for this frame. */
    private final java.util.Map<String, Bitmap> filmThumbs = new java.util.HashMap<>();
    /** Each card's picture slot, so a finished thumbnail is swapped into one
     *  card instead of rebuilding the strip (a rebuild reset the scroll). */
    private final java.util.Map<String, android.widget.FrameLayout> filmCardImages = new java.util.HashMap<>();
    /** One background pass over the catalogue, low resolution, one stock at a
     *  time. The session has a single native result buffer, so this and a
     *  user render share RENDER_LOCK. */
    private volatile boolean prefetchRunning = false;
    private Thread prefetchThread = null;
    private int prefetchGeneration = 0;
    /** `film_stock` / `print_stock` the engine's session is actually holding,
     *  as a signature string. The catalogue queue borrows this one session to
     *  draw its cards and puts a film back when a card is done -- but "done"
     *  can land after the UI has already picked another stock, and a card that
     *  started while no film was chosen puts nothing back at all. So "what the
     *  UI selected" and "what the session carries" drift apart, and a render
     *  that trusts the session repaints the previous film while the edge text
     *  (a Java string) still follows the tap: exactly the reported "换了胶片
     *  颜色没变、相框名字却变了". Every render now re-asserts this signature
     *  under `renderMutex`. Guarded by that lock. */
    /** One render at a time, whole sequence -- the engine's lock only covers a
     *  single call, so a queued 512 px pass could otherwise swap parameters
     *  between the user's renderBegin and its copyArgbRange. */
    private final Object renderMutex = new Object();
    /** A stock change that arrived while a render held the session. Folded into
     *  one follow-up render when the current one lands. */
    private volatile boolean pendingRender = false;
    private static final int PREVIEW_LONG_EDGE = 2048;
    /** The engine refuses `preview_long_edge` below 800 (params.cpp), so the
     *  catalogue pass uses exactly that floor: the smallest legal frame. */
    private static final int PREFETCH_LONG_EDGE = 800;
    private final List<View> formatCards = new ArrayList<>();
    private static final int CONTROLS_OPEN_DP = 298;
    private static final int CONTROLS_CLOSED_DP = 58;
    /** 反馈群：the one place a user can reach a human. Shown once per build. */
    private static final String QQ_GROUP = "1134320449";
    private static final String PROMO_PREFS = "promo";
    private static final String PROMO_SEEN_CODE = "qq_group_seen_code";
    private static final String PROMO_MUTED = "qq_group_muted";

    private static final String[] BRAND_IDS = {"all", "kodak", "fujifilm", "ilford", "cinestill"};
    private static final String[] BRAND_NAMES = {"全部", "KODAK", "FUJIFILM", "ILFORD", "CINESTILL"};
    private static final String[] FORMAT_NAMES = {"135 全画幅", "135 半格", "135 XPan", "645", "6×6", "6×7", "6×9", "6×12", "6×17"};
    private static final float[] FORMAT_MM = {36f, 24f, 65f, 56f, 56f, 70f, 84f, 120f, 170f};
    private final View[] tabCells = new View[4];
    private final View[] tabBars = new View[4];
    private final TextView[] tabLabels = new TextView[4];
    private android.widget.FrameLayout uiRoot;
    private TextView inAppToast;
    private final Runnable hideUiToast = () -> {
        if (inAppToast == null) return;
        inAppToast.animate().cancel();
        inAppToast.animate().alpha(0f).translationY(dp(6)).setDuration(160)
                .withEndAction(() -> { if (inAppToast != null) inAppToast.setVisibility(View.GONE); }).start();
    };
    private final View[] panels = new View[4];

    private void loadUiFonts() {
        try {
            tfDisplay = Typeface.createFromAsset(getAssets(), "fonts/Inter-Light.ttf");
            tfBody = Typeface.createFromAsset(getAssets(), "fonts/Inter-Regular.ttf");
        } catch (Throwable t) {
            DebugSink.text("font", "inter: " + t);
            tfDisplay = Typeface.create("sans-serif-light", Typeface.NORMAL);
            tfBody = Typeface.create("sans-serif", Typeface.NORMAL);
        }
        try {
            tfHan = Typeface.createFromAsset(getAssets(), "fonts/OPPOSans.ttf");
        } catch (Throwable t) {
            DebugSink.text("font", "oppo: " + t);
            tfHan = Typeface.create("sans-serif", Typeface.NORMAL);
        }
    }

    /** The canvas owns the top edge now: white status icons on Rhine blue. */
    private void lightSystemBars() {
        try {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(UI_STAGE);
            getWindow().setNavigationBarColor(UI_PAPER);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    // The blue canvas is behind the status bar; only the paper
                    // navigation bar keeps dark glyphs.
                    int on = android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    int off = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                    c.setSystemBarsAppearance(on, on | off);
                }
            } else {
                int flags = 0;
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
                getWindow().getDecorView().setSystemUiVisibility(flags);
            }
        } catch (Throwable t) {
            DebugSink.text("bars", String.valueOf(t));
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView label(String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** A Mixed run keeps Latin on Inter and Chinese on OPPO Sans, so a title
     *  like "PHYSICAL SCALE / 光学尺度" never falls back to the platform font. */
    private static boolean isCjk(char c) {
        return (c >= 0x2E80 && c <= 0x9FFF)
                || (c >= 0x3000 && c <= 0x303F)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFF00 && c <= 0xFF65);
    }

    private static class TfSpan extends android.text.style.MetricAffectingSpan {
        private final Typeface tf;
        TfSpan(Typeface tf) { this.tf = tf; }
        @Override public void updateDrawState(android.text.TextPaint p) { p.setTypeface(tf); }
        @Override public void updateMeasureState(android.text.TextPaint p) { p.setTypeface(tf); }
    }

    private CharSequence mixedText(String text) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(text);
        int i = 0;
        while (i < text.length()) {
            final boolean cjk = isCjk(text.charAt(i));
            int j = i + 1;
            while (j < text.length() && isCjk(text.charAt(j)) == cjk) ++j;
            sb.setSpan(new TfSpan(cjk ? tfHan : tfBody), i, j,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i = j;
        }
        return sb;
    }

    private TextView mixedLabel(String text, float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setText(mixedText(text));
        t.setIncludeFontPadding(false);
        return t;
    }

    private View hairline(int color) {
        View v = new View(this);
        v.setBackgroundColor(color);
        return v;
    }

    /** clamp(30px, 8.6vw, 39px) */
    private float wordmarkSp() {
        float w = getResources().getConfiguration().screenWidthDp;
        return Math.max(30f, Math.min(39f, 0.086f * w));
    }

    /** clamp(35px, 10.5vw, 48px) */
    private float emptyTitleSp() {
        float w = getResources().getConfiguration().screenWidthDp;
        return Math.max(35f, Math.min(48f, 0.105f * w));
    }

    private void applySystemInsets(final View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            final int left, top, right, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                left = bars.left; top = bars.top; right = bars.right; bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left, top, right, bottom);
            // Android 15 paints edge-to-edge and ignores setStatusBarColor.
            // The inset is part of this view's background, so the blue has to
            // live there as well, while the navigation area stays paper.
            v.setBackground(new EdgeBackdrop(top));
            return insets;
        });
    }

    private static class EdgeBackdrop extends android.graphics.drawable.Drawable {
        private final int top;
        private final android.graphics.Paint paint = new android.graphics.Paint();
        EdgeBackdrop(int top) { this.top = top; }
        @Override public void draw(android.graphics.Canvas canvas) {
            final android.graphics.Rect b = getBounds();
            paint.setColor(UI_PAPER);
            canvas.drawRect(b, paint);
            if (top > 0) {
                paint.setColor(UI_STAGE);
                canvas.drawRect(b.left, b.top, b.right, b.top + top, paint);
            }
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

    private void buildUi() {
        loadUiFonts();
        lightSystemBars();

        final android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(UI_PAPER);
        applySystemInsets(root);
        setContentView(root);
        uiRoot = root;

        LinearLayout app = new LinearLayout(this);
        app.setOrientation(LinearLayout.VERTICAL);
        app.setBackgroundColor(UI_PAPER);
        root.addView(app, new android.widget.FrameLayout.LayoutParams(-1, -1));

        // The photograph is the page. The brand and Export float over it, the
        // source strip sits below it, and the tool panels sit below that.
        android.widget.FrameLayout stage = new android.widget.FrameLayout(this);
        stage.setBackgroundColor(UI_STAGE);
        stage.setClipChildren(false);

        compare = new CompareView(this);
        compare.setVisibility(View.GONE);
        stage.addView(compare, new android.widget.FrameLayout.LayoutParams(-1, -1));

        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(android.view.Gravity.CENTER);
        empty.setPadding(dp(16), dp(16), dp(16), dp(16));
        // One control, in the middle of the frame. The index line, the NO IMAGE
        // headline and the "select a RAW or JPEG" subtitle said the same thing
        // three times over, in three type sizes, and only the last of them
        // could be acted on.
        LinearLayout load = new LinearLayout(this);
        load.setOrientation(LinearLayout.HORIZONTAL);
        load.setGravity(android.view.Gravity.CENTER);
        load.setBackgroundColor(UI_PAPER);
        load.setPadding(dp(18), 0, dp(18), 0);
        load.setOnClickListener(v -> showImportMenu());
        load.addView(label("选择照片", 9, UI_BLUE, tfHan));
        ArrowView loadArrow = new ArrowView(this, UI_BLUE);
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        arrowLp.leftMargin = dp(8);
        load.addView(loadArrow, arrowLp);
        empty.addView(load, new LinearLayout.LayoutParams(-2, dp(38)));
        stage.addView(empty, new android.widget.FrameLayout.LayoutParams(-1, -1));
        emptyState = empty;

        LinearLayout raw = new LinearLayout(this);
        raw.setOrientation(LinearLayout.VERTICAL);
        raw.setGravity(android.view.Gravity.CENTER);
        raw.setPadding(dp(24), dp(16), dp(24), dp(16));
        raw.setVisibility(View.GONE);
        TextView rawTitle = label("RAW LOADED", 34, UI_PAPER, tfDisplay);
        rawTitle.setLetterSpacing(-0.055f);
        raw.addView(rawTitle);
        rawName = label("", 9, UI_PAPER, tfHan);
        rawName.setAlpha(0.7f);
        rawName.setLineSpacing(dp(4), 1f);
        rawName.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams rawNameLp = new LinearLayout.LayoutParams(-2, -2);
        rawNameLp.topMargin = dp(13);
        raw.addView(rawName, rawNameLp);
        stage.addView(raw, new android.widget.FrameLayout.LayoutParams(-1, -1));
        rawState = raw;

        busyLabel = label("RENDERING", 18, UI_PAPER, tfDisplay);
        busyLabel.setLetterSpacing(0.14f);
        busyLabel.setGravity(android.view.Gravity.CENTER);
        busyLabel.setBackgroundColor(0xE6172174);
        busyLabel.setVisibility(View.GONE);
        stage.addView(busyLabel, new android.widget.FrameLayout.LayoutParams(-1, -1));

        addCanvasChrome(stage);
        addPreviewFooter(stage);

        shelfHandle = buildShelfHandle();
        android.widget.FrameLayout.LayoutParams handleLp = new android.widget.FrameLayout.LayoutParams(
                dp(62), dp(62), android.view.Gravity.BOTTOM | android.view.Gravity.START);
        handleLp.leftMargin = dp(12);
        handleLp.bottomMargin = dp(34);
        stage.addView(shelfHandle, handleLp);

        android.widget.FrameLayout jobs = new android.widget.FrameLayout(this);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(UI_PAPER));
        progressBar.setVisibility(View.GONE);
        jobs.addView(progressBar, new android.widget.FrameLayout.LayoutParams(-1, -1));
        exportProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        exportProgressBar.setIndeterminate(true);
        exportProgressBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(UI_PAPER));
        exportProgressBar.setVisibility(View.GONE);
        jobs.addView(exportProgressBar, new android.widget.FrameLayout.LayoutParams(-1, -1));
        stage.addView(jobs, new android.widget.FrameLayout.LayoutParams(-1, dp(2), android.view.Gravity.BOTTOM));

        app.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));
        app.addView(buildSourceBar(), new LinearLayout.LayoutParams(-1, dp(62)));

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackgroundColor(UI_PAPER);

        panelHolder = new LinearLayout(this);
        panelHolder.setOrientation(LinearLayout.VERTICAL);
        panelHolder.setPadding(dp(16), 0, dp(16), 0);
        // Each panel states its own height. The optics page is 13 readouts
        // behind one slider, so it needs 182 dp; the film shelf needs the
        // full 240; the holder follows whichever is visible.
        // Every panel is exactly as tall as what it shows. A fixed height
        // left a blank sheet under the content on the short pages.
        panelHolder.addView(buildFilmPanel(), new LinearLayout.LayoutParams(-1, -2));
        panelHolder.addView(buildOpticsPanel(), new LinearLayout.LayoutParams(-1, -2));
        panelHolder.addView(buildFormatPanel(), new LinearLayout.LayoutParams(-1, -2));
        panelHolder.addView(buildEdgePanel(), new LinearLayout.LayoutParams(-1, -2));
        for (int i = 0; i < 4; ++i) panels[i] = panelHolder.getChildAt(i);

        controls.addView(panelHolder, new LinearLayout.LayoutParams(-1, -2));
        controls.addView(buildTabs(), new LinearLayout.LayoutParams(-1, dp(58)));
        app.addView(controls, new LinearLayout.LayoutParams(-1, -2));

        loadStockCatalog();
        refreshEdgeFormats();
        setTab(0);
        setPanelCollapsed(false);
        setOpticsUnlocked(false);
        rebuildFrameChips();
    }

    /** A Java-level crash never reaches `DebugSink`'s queue -- the process is
     *  gone first. This posts synchronously, from the dying thread, before the
     *  default handler takes over and the app disappears. */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                final java.io.StringWriter text = new java.io.StringWriter();
                error.printStackTrace(new java.io.PrintWriter(text));
                DebugSink.textNow("crash", thread.getName() + " | " + text);
            } catch (Throwable ignored) { }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** All in-app messages use this paper/ink toast, never Android's Toast. */
    void showInAppToast(final CharSequence message) {
        if (message == null || message.length() == 0) return;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            runOnUiThread(() -> showInAppToast(message));
            return;
        }
        if (uiRoot == null) return;
        if (inAppToast == null) {
            inAppToast = label(String.valueOf(message), 10, UI_PAPER, tfHan);
            inAppToast.setMaxWidth(Math.round(getResources().getConfiguration().screenWidthDp
                    * 0.82f * getResources().getDisplayMetrics().density));
            inAppToast.setGravity(android.view.Gravity.CENTER);
            inAppToast.setMaxLines(4);
            inAppToast.setEllipsize(android.text.TextUtils.TruncateAt.END);
            inAppToast.setPadding(dp(14), dp(10), dp(14), dp(10));
            inAppToast.setBackgroundColor(UI_INK);
            inAppToast.setElevation(dp(6));
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                    -2, -2, android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = dp(76);
            uiRoot.addView(inAppToast, lp);
        }
        inAppToast.animate().cancel();
        inAppToast.setText(message);
        inAppToast.setVisibility(View.VISIBLE);
        inAppToast.setAlpha(0f);
        inAppToast.setTranslationY(dp(8));
        inAppToast.animate().alpha(1f).translationY(0f).setDuration(150).start();
        inAppToast.removeCallbacks(hideUiToast);
        inAppToast.postDelayed(hideUiToast, 2200);
    }

    /** Brand and export are painted over the photograph, not stacked above it. */
    private void addCanvasChrome(android.widget.FrameLayout stage) {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.TOP);
        top.setPadding(dp(16), dp(12), dp(16), dp(8));
        top.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xC9070C23, 0x66070C23, 0x00070C23}));
        top.setClickable(false);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(0, dp(2), 0, 0);
        LinearLayout wordRow = new LinearLayout(this);
        wordRow.setOrientation(LinearLayout.HORIZONTAL);
        wordRow.setGravity(android.view.Gravity.TOP);
        TextView word = label("SpektraLab", 18, UI_PAPER, tfDisplay);
        word.setLetterSpacing(-0.045f);
        word.setSingleLine(true);
        word.setOnLongClickListener(v -> { showAbout(); return true; });
        wordRow.addView(word);
        TextView version = label("v" + UpdateCheck.versionName(this), 6.5f, UI_PAPER, tfBody);
        version.setAlpha(0.7f);
        LinearLayout.LayoutParams versionLp = new LinearLayout.LayoutParams(-2, -2);
        versionLp.leftMargin = dp(4);
        versionLp.topMargin = 0;
        wordRow.addView(version, versionLp);
        brand.addView(wordRow);
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));

        sharePill = label("分享", 10, UI_BLUE, tfHan);
        sharePill.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable shareBg = new android.graphics.drawable.GradientDrawable();
        shareBg.setColor(UI_PAPER);
        shareBg.setAlpha(220);
        sharePill.setBackground(shareBg);
        sharePill.setPadding(dp(13), 0, dp(13), 0);
        sharePill.setVisibility(View.GONE);
        sharePill.setOnClickListener(v -> shareLastExport());
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(-2, dp(32));
        shareLp.rightMargin = dp(6);
        top.addView(sharePill, shareLp);

        TextView export = label("导出", 10, UI_BLUE, tfHan);
        export.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable exportBg = new android.graphics.drawable.GradientDrawable();
        exportBg.setColor(UI_PAPER);
        export.setBackground(exportBg);
        export.setPadding(dp(15), 0, dp(15), 0);
        export.setOnClickListener(v -> showExportDialog());
        top.addView(export, new LinearLayout.LayoutParams(-2, dp(32)));

        stage.addView(top, new android.widget.FrameLayout.LayoutParams(-1, dp(64), android.view.Gravity.TOP));
    }

    /** File name and live state sit at the foot of the photograph. */
    private void addPreviewFooter(android.widget.FrameLayout stage) {
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(android.view.Gravity.CENTER_VERTICAL);
        foot.setPadding(dp(16), 0, dp(16), 0);
        foot.setClickable(false);

        fileLabel = label("未选择照片", 7, UI_PAPER, tfHan);
        fileLabel.setAlpha(0.76f);
        fileLabel.setSingleLine(true);
        fileLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        fileLabel.setShadowLayer(dp(2), 0, dp(1), 0x99000000);
        foot.addView(fileLabel, new LinearLayout.LayoutParams(0, -2, 1f));

        statusText = label("等待导入", 7, UI_PAPER, tfHan);
        statusText.setSingleLine(true);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusText.setGravity(android.view.Gravity.END);
        statusText.setAlpha(0.72f);
        statusText.setShadowLayer(dp(2), 0, dp(1), 0x99000000);
        statusText.setMaxWidth(Math.round(getResources().getConfiguration().screenWidthDp
                * 0.60f * getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-2, -2);
        statusLp.leftMargin = dp(12);
        foot.addView(statusText, statusLp);

        android.widget.FrameLayout.LayoutParams footLp = new android.widget.FrameLayout.LayoutParams(
                -1, dp(20), android.view.Gravity.BOTTOM);
        footLp.bottomMargin = dp(8);
        stage.addView(foot, footLp);
    }

    /** Solid paper shelf outside the canvas: ＋, one chip per photograph opened
     *  this session, and a way to fold the whole row away.
     *
     *  <p>There is no 原图 switch here. The film shelf already opens on 原图 and
     *  that card is the same toggle; two controls for one state, four dp apart,
     *  read as two different things.
     */
    private View buildSourceBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(UI_PAPER);
        sourceBar = bar;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(4), 0);
        sourceRow = row;

        TextView add = label("\uff0b", 17, UI_BLUE, tfDisplay);
        add.setGravity(android.view.Gravity.CENTER);
        add.setOnClickListener(v -> showImportMenu());
        row.addView(add, new LinearLayout.LayoutParams(dp(40), dp(48)));

        // The strip. Empty until a photograph is opened: no placeholder tile,
        // because an empty shelf is not a missing control.
        thumbScroll = new HorizontalScrollView(this);
        thumbScroll.setHorizontalScrollBarEnabled(false);
        thumbScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        thumbStrip = new LinearLayout(this);
        thumbStrip.setOrientation(LinearLayout.HORIZONTAL);
        thumbStrip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        thumbScroll.addView(thumbStrip, new android.widget.FrameLayout.LayoutParams(-2, -1));
        row.addView(thumbScroll, new LinearLayout.LayoutParams(0, dp(48), 1f));

        TextView fold = label("\u2039", 15, UI_MUTED, tfHan);
        fold.setGravity(android.view.Gravity.CENTER);
        fold.setOnClickListener(v -> setShelfCollapsed(true));
        row.addView(fold, new LinearLayout.LayoutParams(dp(28), dp(48)));

        bar.addView(row, new LinearLayout.LayoutParams(-1, dp(62)));
        return bar;
    }

    /** Folded: three cards stacked at the canvas's lower-left corner, the way a
     *  short stack of prints looks -- one picture, two edges behind it, and a
     *  chevron that says the rest of the strip is that way. */
    private View buildShelfHandle() {
        final int card = dp(52), step = dp(4);
        android.widget.FrameLayout handle = new android.widget.FrameLayout(this);
        handle.setVisibility(View.GONE);

        for (int i = 2; i >= 1; --i) {
            View edge = new View(this);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(i == 2 ? 0xFFD9D9D3 : 0xFFECECE7);
            bg.setCornerRadius(dp(7));
            bg.setStroke(Math.max(1, dp(1)), UI_LINE);
            edge.setBackground(bg);
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(card, card);
            lp.leftMargin = step * i;
            lp.topMargin = step * i;
            handle.addView(edge, lp);
        }

        shelfHandleImage = new ImageView(this);
        shelfHandleImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        shelfHandleImage.setClipToOutline(true);
        shelfHandleImage.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(7));
            }
        });
        handle.addView(shelfHandleImage, new android.widget.FrameLayout.LayoutParams(card, card));

        TextView chevron = label("\u203a", 14, UI_PAPER, tfDisplay);
        chevron.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        chevron.setPadding(0, 0, dp(5), 0);
        chevron.setShadowLayer(dp(2), 0, 0, 0xCC000000);
        handle.addView(chevron, new android.widget.FrameLayout.LayoutParams(card, card));

        handle.setOnClickListener(v -> setShelfCollapsed(false));
        return handle;
    }

    private Look captureLook() {
        final Look look = new Look();
        look.filmStock = filmStock;
        look.printStock = printStock;
        look.printExposure = printExposure;
        look.grainAmount = grainAmount;
        look.halationAmount = halationAmount;
        look.glareAmount = glareAmount;
        look.highlightRoom = highlightRoom;
        look.shadowRoom = shadowRoom;
        look.halationBoost = halationBoost;
        look.halationScatter = halationScatter;
        look.lensBlur = lensBlur;
        look.yFilter = yFilter;
        look.mFilter = mFilter;
        look.filterScale = filterScale;
        look.preflash = preflash;
        look.edgeFog = edgeFog;
        look.edgeLeaks = edgeLeaks;
        look.frameNumber = frameNumber;
        look.halationOn = halationOn;
        look.grainOn = grainOn;
        look.couplersOn = couplersOn;
        look.antihalationRemoved = antihalationRemoved;
        look.scannerWhite = scannerWhite;
        look.scannerBlack = scannerBlack;
        look.edrOn = edrOn;
        look.shadowRolloff = shadowRolloff;
        look.maxLift = maxLift;
        look.cameraFilter = cameraFilter;
        look.sceneNorm = sceneNorm;
        look.edgeMode = edgeMode;
        look.edgeGate = edgeGate;
        look.edgeHoles = edgeHoles;
        look.edgeCarrier = edgeCarrier;
        look.datePlacement = datePlacement;
        look.dateCorner = dateCorner;
        look.dateStyle = dateStyle;
        look.dateInsetX = dateInsetX;
        look.dateInsetY = dateInsetY;
        look.edgeTextOn = edgeTextOn;
        look.edgeFNumber = edgeFNumber;
        look.dateSize = dateSize;
        look.dateEv = dateEv;
        look.dataText = dataText;
        look.dateTextB = dateTextB;
        look.cameraSeed = cameraSeed;
        look.frameSeed = frameSeed;
        look.filmFormatMm = filmFormatMm;
        look.overscanOn = overscanOn;
        look.overscanFormat = overscanFormat;
        look.dateOn = dateOn;
        return look;
    }

    /** Put a frame's own look back, controls included: a slider that says 1.00
     *  while the render used 1.40 is worse than no slider. `setBarProgress`
     *  goes through `onProgressChanged`, so the number and the field move
     *  together. */
    private void applyLook(Look look) {
        if (look == null) return;
        filmStock = look.filmStock;
        printStock = look.printStock;
        printExposure = look.printExposure;
        grainAmount = look.grainAmount;
        halationAmount = look.halationAmount;
        glareAmount = look.glareAmount;
        highlightRoom = look.highlightRoom;
        shadowRoom = look.shadowRoom;
        halationBoost = look.halationBoost;
        halationScatter = look.halationScatter;
        lensBlur = look.lensBlur;
        yFilter = look.yFilter;
        mFilter = look.mFilter;
        filterScale = look.filterScale;
        preflash = look.preflash;
        edgeFog = look.edgeFog;
        edgeLeaks = look.edgeLeaks;
        frameNumber = look.frameNumber;
        halationOn = look.halationOn;
        grainOn = look.grainOn;
        couplersOn = look.couplersOn;
        antihalationRemoved = look.antihalationRemoved;
        scannerWhite = look.scannerWhite;
        scannerBlack = look.scannerBlack;
        edrOn = look.edrOn;
        shadowRolloff = look.shadowRolloff;
        maxLift = look.maxLift;
        cameraFilter = look.cameraFilter == null ? "" : look.cameraFilter;
        sceneNorm = look.sceneNorm == null ? "power" : look.sceneNorm;
        edgeMode = look.edgeMode == null ? "strip" : look.edgeMode;
        edgeGate = look.edgeGate == null ? "auto" : look.edgeGate;
        edgeHoles = look.edgeHoles == null ? "white" : look.edgeHoles;
        edgeCarrier = look.edgeCarrier == null ? "none" : look.edgeCarrier;
        datePlacement = look.datePlacement == null ? "frame" : look.datePlacement;
        dateCorner = look.dateCorner == null ? "br" : look.dateCorner;
        dateStyle = look.dateStyle == null ? "lcd" : look.dateStyle;
        dateInsetX = look.dateInsetX;
        dateInsetY = look.dateInsetY;
        edgeTextOn = look.edgeTextOn;
        edgeFNumber = look.edgeFNumber;
        dateSize = look.dateSize;
        dateEv = look.dateEv;
        dataText = look.dataText == null ? "" : look.dataText;
        dateTextB = look.dateTextB == null ? "" : look.dateTextB;
        cameraSeed = look.cameraSeed;
        frameSeed = look.frameSeed;
        filmFormatMm = look.filmFormatMm;
        overscanOn = look.overscanOn;
        overscanFormat = look.overscanFormat;
        dateOn = look.dateOn;
        setBarProgress(edgeFogBar, 0.0f, 4f, edgeFog);
        setBarProgress(edgeLeaksBar, 0.0f, 4f, edgeLeaks);
        setBarProgress(frameNoBar, 0.0f, 99f, frameNumber);
        setBarProgress(edgeFNumBar, 0.0f, 64f, edgeFNumber);
        setBarProgress(dateSizeBar, 0.4f, 3f, dateSize);
        setBarProgress(dateEvBar, -2f, 8f, dateEv);
        setBarProgress(dateInsetXBar, 0f, 30f, dateInsetX);
        setBarProgress(dateInsetYBar, 0f, 30f, dateInsetY);
        refreshKnob();
        refreshEdgeGroups();
        for (int i = 0; i < FORMAT_MM.length; ++i) {
            if (Math.abs(FORMAT_MM[i] - filmFormatMm) < 0.5f) { formatIndex = i; break; }
        }
        if (formatFootValue != null) {
            formatFootValue.setText(String.format(java.util.Locale.US, "%.0f mm", filmFormatMm));
        }
        refreshPaperLabel();
    }

    /** Park the live controls on the frame that owns them, before leaving it. */
    /** What a brand-new photograph arrives with. Not "whatever was on screen":
     *  a frame that has never been developed has no look, and inheriting the
     *  previous one made an untouched import come back with somebody else's
     *  grain and rebate. */
    private Look defaultLook() {
        final Look look = new Look();
        look.filmStock = null;
        look.printStock = "kodak_portra_endura";
        look.printExposure = 1.0f;
        look.grainAmount = 1.0f;
        look.halationAmount = 1.0f;
        look.glareAmount = 1.0f;
        look.highlightRoom = 0.0f;
        look.shadowRoom = 0.0f;
        look.halationBoost = 0.0f;
        look.halationScatter = 1.0f;
        look.lensBlur = 0.0f;
        look.yFilter = 0.0f;
        look.mFilter = 0.0f;
        look.filterScale = 1.0f;
        look.preflash = 0.0f;
        look.edgeFog = 1.0f;
        look.edgeLeaks = 0.0f;
        look.frameNumber = 0.0f;
        look.halationOn = 1f;
        look.grainOn = 1f;
        look.couplersOn = 1f;
        look.antihalationRemoved = 0f;
        look.scannerWhite = 0f;
        look.scannerBlack = 0f;
        look.edrOn = 0f;
        look.shadowRolloff = 2f;
        look.maxLift = 4f;
        look.cameraFilter = "";
        look.sceneNorm = "power";
        look.edgeMode = "strip";
        look.edgeGate = "auto";
        look.edgeHoles = "white";
        look.edgeCarrier = "none";
        look.datePlacement = "frame";
        look.dateCorner = "br";
        look.dateStyle = "lcd";
        look.dateInsetX = 3f;
        look.dateInsetY = 2.4f;
        look.edgeTextOn = true;
        look.edgeFNumber = 0f;
        look.dateSize = 1f;
        look.dateEv = 3.5f;
        look.dataText = "";
        look.dateTextB = "";
        look.cameraSeed = 1;
        look.frameSeed = 1;
        look.filmFormatMm = FORMAT_MM[0];
        look.overscanOn = false;
        look.overscanFormat = "135";
        look.dateOn = false;
        return look;
    }

    private void saveLookIntoFrames() {
        if (currentFrame < 0 || currentFrame >= frames.size()) return;
        frames.get(currentFrame).look = captureLook();
    }

    private void updateShelfHandle() {
        if (shelfHandleImage == null) return;
        Bitmap thumb = (currentFrame >= 0 && currentFrame < frames.size())
                ? frames.get(currentFrame).thumb : null;
        shelfHandleImage.setImageBitmap(thumb);
    }

    /** Fold or unfold the photograph strip. Unfolded it costs 62 dp above the
     *  panels; folded it costs 20, and the canvas takes the difference -- the
     *  stage is the weighted child of the column, so nothing else has to move. */
    private void setShelfCollapsed(boolean collapsed) {
        if (shelfCollapsed == collapsed) return;
        // Nothing to fold into: with no photographs the handle would not be
        // drawn, and ＋ would go with the row -- a one-way trip.
        if (collapsed && frames.isEmpty()) return;
        shelfCollapsed = collapsed;
        updateShelfVisibility();
        if (collapsed) updateShelfHandle();
    }

    /** The strip is furniture: it exists only when there is something on it.
     *  Before the first import it is not folded, it is absent -- the canvas's
     *  own 选择照片 button is the way in, and a 62 dp row of nothing above the
     *  panels is just a gap. */
    private void updateShelfVisibility() {
        if (sourceBar == null) return;
        final boolean show = !frames.isEmpty() && !shelfCollapsed;
        sourceBar.setVisibility(show ? View.VISIBLE : View.GONE);
        android.view.ViewGroup.LayoutParams lp = sourceBar.getLayoutParams();
        if (lp != null) {
            lp.height = dp(show ? 62 : 0);
            sourceBar.setLayoutParams(lp);
        }
        if (shelfHandle != null) {
            shelfHandle.setVisibility(shelfCollapsed && !frames.isEmpty()
                    ? View.VISIBLE : View.GONE);
        }
    }

    /** File the frame that just finished importing, once. */
    private void rememberFrame(Uri uri) {
        if (uri == null) return;
        String name = getFileName(uri);
        if (retryingNativeSize) {
            // Same photograph, decoded again at its own size. The chip is
            // already there; a second one would read as two files.
            retryingNativeSize = false;
            return;
        }
        if (reopeningFrame) {
            // A chip asked for this frame. It is already in the list; only the
            // highlight and the freshly decoded pixels changed.
            reopeningFrame = false;
            rebuildFrameChips();
            return;
        }
        final Frame frame = new Frame(uri, isRawFile, name);
        frame.look = captureLook();
        if (beforeBitmap != null) {
            try {
                final int w = Math.max(1, dp(42));
                final int h = Math.max(1, Math.round(
                        w * beforeBitmap.getHeight() / (float) beforeBitmap.getWidth()));
                frame.thumb = Bitmap.createScaledBitmap(beforeBitmap, w, h, true);
            } catch (Throwable ignored) { }
        }
        frames.add(frame);
        currentFrame = frames.size() - 1;
        persistThumb(frame);
        rebuildFrameChips();
        saveSessionState();
    }

    /** One chip per opened frame, newest at the right, the open one ringed. */
    private void rebuildFrameChips() {
        if (thumbStrip == null) return;
        thumbStrip.removeAllViews();
        for (int i = 0; i < frames.size(); ++i) {
            final int index = i;
            final Frame frame = frames.get(i);
            final boolean active = index == currentFrame;
            android.widget.FrameLayout box = new android.widget.FrameLayout(this);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(UI_GROUND);
            bg.setStroke(dp(active ? 2 : 1), active ? UI_BLUE : UI_LINE);
            box.setBackground(bg);
            box.setPadding(dp(2), dp(2), dp(2), dp(2));
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (frame.thumb != null) img.setImageBitmap(frame.thumb);
            box.addView(img, new android.widget.FrameLayout.LayoutParams(-1, -1));
            box.setOnClickListener(v -> reopenFrame(index));
            box.setOnLongClickListener(v -> {
                showActionSheet("删除这张照片？",
                        (frame.name == null ? "照片" : frame.name) + "\n\n只从这条栏里移除，文件本身不动。",
                        new String[]{"删除", "取消"}, which -> {
                            if (which == 0) removeFrame(index);
                        });
                return true;
            });
            box.setContentDescription(frame.name == null ? "照片" : frame.name);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42), dp(42));
            lp.rightMargin = dp(6);
            thumbStrip.addView(box, lp);
        }
        if (currentFrame >= 0 && currentFrame < thumbStrip.getChildCount()) {
            final View target = thumbStrip.getChildAt(currentFrame);
            thumbStrip.post(() -> {
                if (thumbScroll != null && target != null) {
                    thumbScroll.smoothScrollTo(Math.max(0, target.getLeft() - dp(36)), 0);
                }
            });
        }
        updateShelfHandle();
        updateShelfVisibility();
        try {
            DebugSink.text("frame-strip", "frames=" + frames.size() + " current=" + currentFrame);
        } catch (Throwable ignored) { }
    }

    /** Drop a chip. The file is untouched -- this is the strip's own list. If
     *  the frame was the one on the canvas, the canvas is emptied rather than
     *  silently showing a photograph that is no longer in the list. */
    private void removeFrame(int index) {
        if (index < 0 || index >= frames.size()) return;
        frames.remove(index);
        if (index == currentFrame) {
            currentFrame = -1;
            lastRenderedBitmap = null;
            if (compare != null) compare.clear();
            setStage(0, null);
            statusText.setText("等待导入");
        } else if (currentFrame > index) {
            currentFrame--;
        }
        rebuildFrameChips();
        saveSessionState();
        try { DebugSink.text("frame-strip", "removed " + index + ", now " + frames.size()); }
        catch (Throwable ignored) { }
    }

    /** Re-open a frame from the strip. Same entry point the picker uses, so a
     *  RAW chip and a JPEG chip take exactly the roads they took the first time. */
    private void reopenFrame(int index) {
        if (index < 0 || index >= frames.size()) return;
        if (index == currentFrame && session != null) return;
        final Frame frame = frames.get(index);
        saveLookIntoFrames();
        currentFrame = index;
        reopeningFrame = true;
        suppressBudgetDialog = true;
        rebuildFrameChips();
        loadImage(frame.uri);
    }

    private void setOriginalVisual(boolean on) {
        if (originalAction == null) return;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(on ? UI_BLUE : UI_PAPER);
        originalAction.setBackground(bg);
        if (originalGlyph != null) originalGlyph.setColor(on ? UI_PAPER : UI_BLUE);
        if (originalLabel != null) originalLabel.setTextColor(on ? UI_PAPER : UI_BLUE);
    }

    private View sectionHead(String title, Runnable reset) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView titleView = mixedLabel(title, 8.5f, UI_INK);
        titleView.setLetterSpacing(0.06f);
        head.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView resetView = label("重置当前页", 8.5f, UI_MUTED, tfHan);
        resetView.setPaintFlags(resetView.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        resetView.setPadding(dp(8), dp(10), 0, dp(10));
        resetView.setOnClickListener(v -> reset.run());
        head.addView(resetView);
        return head;
    }

    private View buildFilmPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(sectionHead("FILM & PAPER / 胶片与相纸", this::resetFilmPanel),
                new LinearLayout.LayoutParams(-1, dp(38)));

        HorizontalScrollView brandScroll = new HorizontalScrollView(this);
        brandScroll.setHorizontalScrollBarEnabled(false);
        brandScroll.setFillViewport(false);
        brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        brandScroll.addView(brandRow, new android.widget.FrameLayout.LayoutParams(-2, dp(38)));
        panel.addView(brandScroll, new LinearLayout.LayoutParams(-1, dp(38)));

        filmScroll = new HorizontalScrollView(this);
        filmScroll.setHorizontalScrollBarEnabled(false);
        filmScroll.setClipToPadding(false);
        filmScroll.setFillViewport(false);
        filmCardStrip = new LinearLayout(this);
        filmCardStrip.setOrientation(LinearLayout.HORIZONTAL);
        filmCardStrip.setGravity(android.view.Gravity.TOP);
        filmCardStrip.setPadding(0, dp(9), 0, dp(8));
        filmScroll.addView(filmCardStrip, new android.widget.FrameLayout.LayoutParams(-2, -1));
        panel.addView(filmScroll, new LinearLayout.LayoutParams(-1, dp(111)));

        LinearLayout paperRow = new LinearLayout(this);
        paperRow.setOrientation(LinearLayout.HORIZONTAL);
        paperRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        paperRow.addView(label("相纸 / 拷贝片", 9, UI_MUTED, tfHan));
        LinearLayout paperChoice = new LinearLayout(this);
        paperChoice.setOrientation(LinearLayout.HORIZONTAL);
        paperChoice.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        paperChoice.setOnClickListener(v -> showPaperPicker());
        paperValue = label("Kodak Portra Endura", 10, UI_INK, tfHan);
        paperValue.setSingleLine(true);
        paperValue.setEllipsize(android.text.TextUtils.TruncateAt.END);
        paperValue.setGravity(android.view.Gravity.END);
        paperChoice.addView(paperValue, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView paperArrow = label("↓", 10, UI_BLUE, tfBody);
        LinearLayout.LayoutParams paperArrowLp = new LinearLayout.LayoutParams(-2, -2);
        paperArrowLp.leftMargin = dp(7);
        paperChoice.addView(paperArrow, paperArrowLp);
        paperRow.addView(paperChoice, new LinearLayout.LayoutParams(0, dp(35), 1f));
        panel.addView(paperRow, new LinearLayout.LayoutParams(-1, dp(35)));
        return panel;
    }

    private String brandOf(String id) {
        if (id == null) return "other";
        if (id.startsWith("kodak")) return "kodak";
        if (id.startsWith("fujifilm")) return "fujifilm";
        if (id.startsWith("ilford")) return "ilford";
        if (id.startsWith("cinestill")) return "cinestill";
        return "other";
    }

    private int brandCount(String brand) {
        int n = 0;
        for (String id : filmIds) if ("all".equals(brand) || brand.equals(brandOf(id))) n++;
        return n;
    }

    private void rebuildBrandRow() {
        if (brandRow == null) return;
        brandRow.removeAllViews();
        for (int i = 0; i < BRAND_IDS.length; ++i) {
            final String id = BRAND_IDS[i];
            final int count = brandCount(id);
            if (count == 0) continue;
            TextView tab = label(BRAND_NAMES[i] + " " + count,
                    8.5f, id.equals(brandFilter) ? UI_BLUE : UI_MUTED,
                    "all".equals(id) ? tfHan : tfBody);
            tab.setGravity(android.view.Gravity.CENTER_VERTICAL);
            tab.setPadding(0, 0, dp(20), 0);
            if (id.equals(brandFilter)) {
                tab.setPaintFlags(tab.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            }
            tab.setOnClickListener(v -> {
                brandFilter = id;
                rebuildBrandRow();
                rebuildFilmCards();
                // A new shelf's cards are the ones now on screen: let the queue
                // take them first instead of finishing a brand nobody is looking at.
                if (!prefetchRunning) startPrefetch();
            });
            brandRow.addView(tab, new LinearLayout.LayoutParams(-2, dp(38)));
        }
    }

    private String shortStockName(String label) {
        if (label == null) return "";
        int cut = label.indexOf(" · ");
        return cut > 0 ? label.substring(0, cut) : label;
    }

    /** The card's own caption. `反转` is kept: a slide is in the shelf, and the
     *  tap has to explain why it cannot go on paper. */
    private String cardCaption(int index) {
        if (index < 0 || index >= filmLabels.size()) return "";
        // `shortStockName` drops the " · xxx" suffix the labels carry, so the
        // mark has to be put back here -- this is the only place a card's text
        // is decided, and it used to put back only the slide mark.
        final String base = shortStockName(filmLabels.get(index));
        if (index < filmTypes.size() && "positive".equals(filmTypes.get(index))) return base + " · 反转";
        if (index < filmIds.size() && isMonochromeStock(filmIds.get(index))) return base + " · 黑白";
        return base;
    }

    private android.graphics.drawable.GradientDrawable cardBorder(boolean active) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(UI_GROUND);
        bg.setStroke(dp(1), active ? UI_BLUE : UI_LINE);
        return bg;
    }

    private void rebuildFilmCards() {
        if (filmCardStrip == null) return;
        final int keepScroll = filmScroll == null ? 0 : filmScroll.getScrollX();
        filmCardStrip.removeAllViews();
        filmCards.clear();
        filmCardImages.clear();
        addFilmCard("original", "原图", -1);
        for (int i = 0; i < filmIds.size(); ++i) {
            final String id = filmIds.get(i);
            if (!"all".equals(brandFilter) && !brandFilter.equals(brandOf(id))) continue;
            addFilmCard(id, cardCaption(i), i);
        }
        updateFilmSelection();
        // Rebuilding is a deliberate act (import, brand switch): restore where
        // the user was looking rather than snapping back to the first card.
        if (filmScroll != null) filmScroll.post(() -> filmScroll.scrollTo(keepScroll, 0));
        try {
            DebugSink.text("film-shelf", "films=" + filmIds.size() + " cards=" + filmCards.size()
                    + " brand=" + brandFilter + " thumbs=" + filmThumbs.size());
        } catch (Throwable ignored) { }
    }

    private void addFilmCard(final String id, String text, final int index) {
        final boolean original = "original".equals(id);
        boolean active = original ? showingOriginal
                : (filmStock != null && !showingOriginal && id.equals(filmStock));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setTag(id);
        card.setBackground(cardBorder(active));
        card.setPadding(dp(1), dp(1), dp(1), dp(1));

        android.widget.FrameLayout imageBox = new android.widget.FrameLayout(this);
        imageBox.setBackgroundColor(original ? UI_PAPER : UI_GROUND);
        filmCardImages.put(id, imageBox);
        if (original || beforeBitmap == null) {
            TextView mark = label(original ? "—" : text, original ? 22 : 8,
                    UI_MUTED, original ? tfDisplay : tfHan);
            mark.setMaxLines(2);
            mark.setGravity(android.view.Gravity.CENTER);
            imageBox.addView(mark, new android.widget.FrameLayout.LayoutParams(-1, -1));
        } else {
            Bitmap preview = filmThumbs.get(id);
            if (preview == null && filmStock != null && !showingOriginal && id.equals(filmStock)) {
                preview = lastRenderedBitmap;
            }
            final boolean unrendered = preview == null;
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setImageBitmap(unrendered ? beforeBitmap : preview);
            if (unrendered) {
                // Not this film's picture yet: the source, held back so the
                // difference between "source" and "rendered" stays visible.
                thumb.setAlpha(0.55f);
            }
            imageBox.addView(thumb, new android.widget.FrameLayout.LayoutParams(-1, -1));
        }
        card.addView(imageBox, new LinearLayout.LayoutParams(-1, dp(67)));

        TextView name = label(text, 7.5f, active ? UI_BLUE : UI_INK, tfHan);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setBackgroundColor(UI_PAPER);
        name.setPadding(dp(6), dp(4), dp(5), dp(2));
        name.setGravity(android.view.Gravity.TOP);
        card.addView(name, new LinearLayout.LayoutParams(-1, dp(27)));

        card.setClickable(true);
        card.setOnClickListener(v -> pickFilmCard(id, index));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(dp(86), dp(94));
        cardLp.rightMargin = dp(9);
        filmCardStrip.addView(card, cardLp);
        filmCards.add(card);
    }

    /** A finished thumbnail goes into its own card. Deliberately *not* a
     *  rebuild: `removeAllViews` clamps the shelf's scroll back to zero, so a
     *  queue that filled a card every second yanked the strip out from under
     *  whoever was scrolling through it. */
    private void updateCardThumb(String id) {
        final android.widget.FrameLayout box = filmCardImages.get(id);
        final Bitmap thumb = filmThumbs.get(id);
        if (box == null || thumb == null) return;
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setImageBitmap(thumb);
        box.removeAllViews();
        box.addView(img, new android.widget.FrameLayout.LayoutParams(-1, -1));
    }

    private void cacheFilmThumb(String stock, Bitmap rendered) {
        if (stock == null || rendered == null) return;
        try {
            final int w = Math.max(1, dp(86));
            final int h = Math.max(1, Math.round(w * rendered.getHeight() / (float) rendered.getWidth()));
            filmThumbs.put(stock, Bitmap.createScaledBitmap(rendered, w, h, true));
        } catch (Throwable ignored) { }
    }

    private void updateFilmSelection() { updateFilmSelection(false); }

    private void updateFilmSelection(boolean scrollToCurrent) {
        View target = null;
        for (View card : filmCards) {
            Object tag = card.getTag();
            String id = tag == null ? "" : tag.toString();
            boolean active = "original".equals(id) ? showingOriginal
                    : (filmStock != null && !showingOriginal && id.equals(filmStock));
            card.setBackground(cardBorder(active));
            android.view.ViewGroup group = (android.view.ViewGroup) card;
            if (group.getChildCount() > 1) {
                ((TextView) group.getChildAt(1)).setTextColor(active ? UI_BLUE : UI_INK);
            }
            if (active) target = card;
        }
        if (scrollToCurrent && target != null && filmScroll != null) {
            final View view = target;
            filmScroll.post(() -> filmScroll.smoothScrollTo(Math.max(0, view.getLeft() - dp(8)), 0));
        }
    }

    private void pickFilmCard(String id, int index) {
        if ("original".equals(id)) {
            toggleOriginal();
            return;
        }
        if (index < 0 || index >= filmIds.size()) return;
        filmStock = filmIds.get(index);
        // The film's declared paper, exactly as `selectFilmStock` does on the
        // macOS side. Without this a black-and-white stock stayed on whatever
        // colour paper was there -- and a silver negative on Portra Endura is
        // a warm sepia print, not a black-and-white one. It reads as "the
        // black-and-white film is broken" rather than "the paper is wrong".
        final String target = filmTargetPrints.get(filmStock);
        if (target != null && printIds.contains(target)) printStock = target;
        showingOriginal = false;
        updateFilmSelection();
        // The paper row has nothing to say for a slide: refresh before the
        // render so the label and the picture cannot disagree.
        refreshPaperLabel();
        onStockChanged();
    }

    private void refreshPaperLabel() {
        if (paperValue == null) return;
        if (isPositiveStock(filmStock)) {
            paperValue.setText("无相纸 · 直接扫描");
            paperValue.setTextColor(UI_MUTED);
            return;
        }
        paperValue.setTextColor(UI_INK);
        int i = printIds.indexOf(printStock);
        paperValue.setText(i >= 0 && i < printLabels.size()
                ? shortStockName(printLabels.get(i)) : printStock);
    }

    private void showPaperPicker() {
        if (printIds.isEmpty()) return;
        if (isPositiveStock(filmStock)) {
            showInAppToast("反转片直接扫描，没有相纸这一步");
            return;
        }
        // The current paper, by its **label**: this sheet matched the id
        // against a list of names, so nothing was ever marked as selected.
        final int current = printIds.indexOf(printStock);
        final List<String> notes = new ArrayList<>();
        for (String id : printIds) notes.add(paperNote(id));
        final boolean filmKnown = filmStock != null;
        final boolean filmMono = isMonochromeStock(filmStock);
        showChoiceSheet("选择相纸 / 拷贝片", printLabels, notes,
                current >= 0 && current < printLabels.size() ? printLabels.get(current) : null,
                i -> !filmKnown || paperFitsFilm(printIds.get(i), filmMono),
                picked -> {
                    int i = printLabels.indexOf(picked);
                    if (i < 0 || printStock.equals(printIds.get(i))) return;
                    printStock = printIds.get(i);
                    refreshPaperLabel();
                    showingOriginal = false;
                    onStockChanged();
                });
    }

    /** A silver paper takes a silver film and nothing else; a colour paper
     *  takes a colour film. Anything within a kind is the user's to try -- the
     *  film's declared paper is already selected for them when they pick it. */
    private static boolean paperFitsFilm(String paper, boolean filmIsMonochrome) {
        final boolean paperIsMonochrome = "ilford_multigrade_iv_rc".equals(paper);
        return filmIsMonochrome == paperIsMonochrome;
    }

    /**
     * One line on what a paper does, taken from this engine rather than from a
     * datasheet: one frame (a sunlit street scene) through Portra 400, each
     * paper's black point (p05) and saturation measured against Portra
     * Endura's. Endura p05 22.7 / sat 23.1 %; Endura Premier 6.0 / 25.1;
     * Supra 12.0 / 22.8; Ultra 11.4 / 24.0; Ektacolor Edge 22.9 / 20.7;
     * Crystal Archive 8.7 / 22.0 · green-heavy; Ilford 16.0 / 0.0 %;
     * 2383 4.2 / 21.5 · warm; 2393 3.0 / 24.5.
     */
    private static String paperNote(String id) {
        if (id == null) return null;
        switch (id) {
            case "kodak_portra_endura":  return "中性 · 黑位偏亮 · 人像常用";
            case "kodak_endura_premier": return "更亮 · 深黑 · 对比高";
            case "kodak_supra_endura":   return "中性 · 对比适中";
            case "kodak_ultra_endura":   return "中性偏冷 · 对比适中";
            case "kodak_ektacolor_edge": return "低饱和 · 柔和";
            case "fujifilm_crystal_archive_typeii": return "偏青 · 富士色调";
            case "ilford_multigrade_iv_rc": return "黑白相纸 · 可变反差";
            case "kodak_2383":  return "偏暖 · 深黑 · 电影感";
            case "kodak_2393":  return "最深黑 · 高饱和";
            default: return null;
        }
    }

    private LinearLayout buildOpticsPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(sectionHead("OPTICS / 光学调整", this::resetOpticsPanel),
                new LinearLayout.LayoutParams(-1, dp(38)));

        // The readout: which slider this is, and what it is set to.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        knobTitle = label("", 9, UI_INK, tfHan);
        head.addView(knobTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        knobValue = label("", 9, UI_BLUE, tfBody);
        head.addView(knobValue);
        panel.addView(head, new LinearLayout.LayoutParams(-1, dp(20)));
        // What the knob does, in one line: twenty-two parameter names are a
        // vocabulary, not an explanation, and the engine's own names ("层间抑制")
        // are the least self-evident of them.
        knobHintView = label("", 7.5f, UI_MUTED, tfHan);
        knobHintView.setMaxLines(1);
        knobHintView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        panel.addView(knobHintView, new LinearLayout.LayoutParams(-1, dp(15)));

        knobBar = new ScrubBar(this);
        knobBar.setMax(1000);
        styleRange(knobBar);
        knobBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (knobs.isEmpty()) return;
                final Knob k = knobs.get(activeKnob);
                final float v = k.lo + (k.hi - k.lo) * progress / 1000f;
                k.set.accept(v);
                knobValue.setText(String.format(java.util.Locale.US, "%.2f", v));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                // One delta per gesture, sent from the same place every other
                // slider sends it.
                applyParams();
                if (uiReady && session != null) {
                    showingOriginal = false;
                    setOriginalVisual(false);
                    renderImage();
                }
            }
        });
        panel.addView(knobBar, new LinearLayout.LayoutParams(-1, dp(44)));

        // The strip: one chip per adjustable, the selection under the thumb.
        HorizontalScrollView stripScroll = new HorizontalScrollView(this);
        stripScroll.setHorizontalScrollBarEnabled(false);
        stripScroll.setClipToPadding(false);
        knobStrip = new LinearLayout(this);
        knobStrip.setOrientation(LinearLayout.HORIZONTAL);
        knobStrip.setPadding(0, dp(6), 0, 0);
        stripScroll.addView(knobStrip, new android.widget.FrameLayout.LayoutParams(-2, -1));
        // A fixed height, not `weight = 1`: the optics panel is the shortest
        // one, and letting its chip strip eat the leftover space is what left
        // a blank band under it while the canvas stayed small.
        panel.addView(stripScroll, new LinearLayout.LayoutParams(-1, dp(78)));

        knob("放大机曝光", "E",  0.25f, 4.0f,   v -> printExposure = v,   () -> printExposure);
        knob("高光保护",   "H",  0.0f,  3.0f,   v -> highlightRoom = v,   () -> highlightRoom);
        knob("暗部保护",   "S",  0.0f,  3.0f,   v -> shadowRoom = v,      () -> shadowRoom);
        knob("颗粒",       "G",  0.0f,  2.0f,   v -> grainAmount = v,     () -> grainAmount);
        knob("光晕",       "○",  0.0f,  4.0f,   v -> halationAmount = v,  () -> halationAmount);
        knob("光晕增强",   "+",  0.0f,  8.0f,   v -> halationBoost = v,   () -> halationBoost);
        knob("乳剂散射",   "~",  0.0f,  1.0f,   v -> halationScatter = v, () -> halationScatter);
        knob("眩光",       "✳",  0.0f,  12.0f,  v -> glareAmount = v,     () -> glareAmount);
        knob("镜头模糊",   "◌",  0.0f,  200.0f, v -> lensBlur = v,        () -> lensBlur);
        knob("黄滤镜",     "Y",  -1.0f, 1.0f,   v -> yFilter = v,         () -> yFilter);
        knob("品红滤镜",   "M",  -1.0f, 1.0f,   v -> mFilter = v,         () -> mFilter);
        knob("滤镜强度",   "C",  1.0f,  60.0f,  v -> filterScale = v,     () -> filterScale);
        knob("预闪",       "⚡", 0.0f,  1.0f,   v -> preflash = v,        () -> preflash);
        knob("曲线柔度",   "∿",  1.0f,  4.0f,   v -> shadowRolloff = v,   () -> shadowRolloff);
        knob("提升上限",   "L",  0.25f, 12.0f,  v -> maxLift = v,         () -> maxLift);
        // The switches, on a 0/1 slider: the panel has one slider by design,
        // and a second kind of control would be a second thing to explain.
        knob("光晕开关",   "◐",  0.0f,  1.0f,   v -> halationOn = v,       () -> halationOn);
        knob("去抗光晕",   "⊘",  0.0f,  1.0f,   v -> antihalationRemoved = v, () -> antihalationRemoved);
        knob("颗粒开关",   "g",  0.0f,  1.0f,   v -> grainOn = v,          () -> grainOn);
        knob("层间抑制",   "⧉",  0.0f,  1.0f,   v -> couplersOn = v,       () -> couplersOn);
        knob("扫描白",     "W",  0.0f,  1.0f,   v -> scannerWhite = v,     () -> scannerWhite);
        knob("扫描黑",     "B",  0.0f,  1.0f,   v -> scannerBlack = v,     () -> scannerBlack);
        knob("动态范围",   "HDR", 0.0f, 1.0f,   v -> edrOn = v,            () -> edrOn);

        // Two of the optics are choices, not numbers.
        addChoice(panel, "镜头滤镜", labelOf(FILTER_LABELS, FILTER_IDS, cameraFilter), tv ->
                showChoiceMapping("镜头滤镜（Wratten）", FILTER_LABELS, FILTER_IDS, cameraFilter, id -> {
                    cameraFilter = id;
                    tv.setText(labelOf(FILTER_LABELS, FILTER_IDS, id));
                    applyParams();
                    if (uiReady && session != null) { showingOriginal = false; setOriginalVisual(false); renderImage(); }
                }));
        addChoice(panel, "保护基准", labelOf(NORM_LABELS, NORM_IDS, sceneNorm), tv ->
                showChoiceMapping("高光/暗部保护按什么算", NORM_LABELS, NORM_IDS, sceneNorm, id -> {
                    sceneNorm = id;
                    tv.setText(labelOf(NORM_LABELS, NORM_IDS, id));
                    applyParams();
                    if (uiReady && session != null) { showingOriginal = false; setOriginalVisual(false); renderImage(); }
                }));

        selectKnob(0);
        return panel;
    }

    /** One chip in the strip, plus the entry that makes it live. */
    private void knob(String label, String glyph, float lo, float hi,
                      java.util.function.Consumer<Float> set, java.util.function.Supplier<Float> get) {
        final int index = knobs.size();
        knobs.add(new Knob(label, glyph, lo, hi, set, get));

        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);

        // A rectangle, not a ring: the glyphs are letters (E, H, S, G...), and
        // a letter reads better in a box than on a circle. It is also the
        // shape the rest of this app's controls use.
        TextView key = label(glyph, 12, UI_INK, tfBody);
        key.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable box = new android.graphics.drawable.GradientDrawable();
        box.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        box.setCornerRadius(0f);
        box.setColor(0x00000000);
        box.setStroke(dp(1), UI_LINE);
        key.setBackground(box);
        chip.addView(key, new LinearLayout.LayoutParams(dp(52), dp(34)));

        TextView name = label(label, 7.5f, UI_MUTED, tfHan);
        name.setGravity(android.view.Gravity.CENTER);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(dp(58), -2);
        nameLp.topMargin = dp(5);
        chip.addView(name, nameLp);

        chip.setOnClickListener(v -> selectKnob(index));
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(dp(60), -2);
        chipLp.rightMargin = dp(4);
        knobStrip.addView(chip, chipLp);
    }

    private void selectKnob(int index) {
        if (index < 0 || index >= knobs.size()) return;
        activeKnob = index;
        refreshKnob();
        refreshKnobChips();
    }

    /** Put the current value on the one slider, without changing which knob. */
    private void refreshKnob() {
        if (knobs.isEmpty() || knobBar == null) return;
        final Knob k = knobs.get(activeKnob);
        final float v = k.get.get();
        final int p = Math.round((v - k.lo) / (k.hi - k.lo) * 1000f);
        knobBar.setProgress(Math.max(0, Math.min(1000, p)));
        knobValue.setText(String.format(java.util.Locale.US, "%.2f", v));
        knobTitle.setText(k.label);
        if (knobHintView != null) knobHintView.setText(knobHint(k.label));
    }

    /** A label + current value + arrow, the same shape as the paper picker. */
    private TextView addChoice(LinearLayout parent, String title, String value,
                               java.util.function.Consumer<TextView> onTap) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(label(title, 9, UI_MUTED, tfHan));

        LinearLayout button = new LinearLayout(this);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        final TextView tv = label(value, 9.5f, UI_INK, tfHan);
        tv.setGravity(android.view.Gravity.END);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = label("↓", 9.5f, UI_BLUE, tfBody);
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(-2, -2);
        arrowLp.leftMargin = dp(7);
        button.addView(arrow, arrowLp);
        button.setOnClickListener(v -> onTap.accept(tv));
        row.addView(button, new LinearLayout.LayoutParams(0, -1, 1f));

        parent.addView(row, new LinearLayout.LayoutParams(-1, dp(34)));
        return tv;
    }

    /** A picker whose rows show labels but whose callback delivers ids. */
    private void showChoiceMapping(String title, String[] labels, String[] ids, String currentId,
                                   java.util.function.Consumer<String> onPick) {
        int at = -1;
        for (int i = 0; i < ids.length; ++i) if (ids[i].equals(currentId)) { at = i; break; }
        final java.util.List<String> rows = java.util.Arrays.asList(labels);
        showChoiceSheet(title, rows, at >= 0 ? labels[at] : null, picked -> {
            final int i = rows.indexOf(picked);
            if (i >= 0) onPick.accept(ids[i]);
        });
    }

    /** JSON escaping for the two free-text date fields. */
    private static String esc(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); ++i) {
            final char c = s.charAt(i);
            if (c == '"') out.append('\\').append('"');
            else if (c == '\\') out.append('\\').append('\\');
            else if (c < 0x20) out.append(' ');
            else out.append(c);
        }
        return out.toString();
    }

    /** One text field, in the same sheet shape as the pickers. */
    private void askText(String title, String initial, java.util.function.Consumer<String> onDone) {
        askText(title, initial, false, onDone);
    }

    private void askText(String title, String initial, boolean numeric,
                         java.util.function.Consumer<String> onDone) {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundColor(UI_PAPER);

        View cap = new View(this);
        cap.setBackgroundColor(UI_BLUE);
        sheet.addView(cap, new LinearLayout.LayoutParams(-1, dp(3)));

        TextView head = label(title, 10.5f, UI_INK, tfHan);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(-1, -2);
        headLp.setMargins(dp(16), dp(14), dp(16), dp(6));
        sheet.addView(head, headLp);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(initial == null ? "" : initial);
        input.setTextSize(13);
        input.setTextColor(UI_INK);
        input.setSingleLine(true);
        if (numeric) input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setBackground(null);
        input.setPadding(dp(16), dp(10), dp(16), dp(10));
        sheet.addView(input, new LinearLayout.LayoutParams(-1, -2));

        View line = new View(this);
        line.setBackgroundColor(UI_LINE);
        sheet.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = label("取消", 10, UI_MUTED, tfHan);
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setPadding(0, dp(14), 0, dp(14));
        cancel.setOnClickListener(v -> dialog.dismiss());
        actions.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView ok = label("确定", 10, UI_BLUE, tfHan);
        ok.setGravity(android.view.Gravity.CENTER);
        ok.setPadding(0, dp(14), 0, dp(14));
        ok.setOnClickListener(v -> {
            dialog.dismiss();
            onDone.accept(input.getText().toString().trim());
        });
        actions.addView(ok, new LinearLayout.LayoutParams(0, -2, 1f));
        sheet.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        dialog.setContentView(sheet);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0x00000000));
            dialog.getWindow().setLayout(-1, -2);
            dialog.getWindow().setGravity(android.view.Gravity.BOTTOM);
            dialog.getWindow().setDimAmount(0.35f);
        }
        input.requestFocus();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
    }

    private static String labelOf(String[] labels, String[] ids, String id) {
        for (int i = 0; i < ids.length; ++i) if (ids[i].equals(id)) return labels[i];
        return id == null ? "" : id;
    }

    // Wratten numbers, the eight the engine carries (`kCameraFilters`).
    private static final String[] FILTER_IDS = {"", "w8", "w11", "w12", "w15", "w21", "w25", "w47", "w58"};
    private static final String[] FILTER_LABELS = {"无", "W8 黄", "W11 黄绿", "W12 深黄", "W15 深黄",
            "W21 橙", "W25 红", "W47 蓝", "W58 绿"};
    private static final String[] NORM_IDS = {"power", "y", "max"};
    private static final String[] NORM_LABELS = {"幂律 power", "亮度 y", "峰值 max"};

    /** One line per knob, short enough to fit the row. */
    private static String knobHint(String label) {
        if (label == null) return "";
        switch (label) {
            case "放大机曝光": return "放大机的曝光量。数值越小，印出来越亮";
            case "高光保护": return "把高光折进相纸的肩部。拉得越高，压得越狠";
            case "暗部保护": return "把暗部从趾部提起来。拉得越高，暗部越亮";
            case "颗粒": return "银盐颗粒的强度。黑白片按各胶片自己的实测换算";
            case "光晕": return "强光穿过片基、在背面反射回来形成的红晕。1 是胶片本来的量";
            case "光晕增强": return "给被裁掉的高光补能量。只让光晕更亮，不改变它的范围";
            case "乳剂散射": return "光在乳剂内部散射形成的雾。发生在光晕之前";
            case "眩光": return "相纸表面的那层光晕。抬高黑位、降低对比";
            case "镜头模糊": return "镜头本身的光学模糊，单位微米。在胶片之前";
            case "黄滤镜": return "放大机的黄旋钮。往负走偏蓝，往正走偏黄";
            case "品红滤镜": return "放大机的品红旋钮。往负走偏绿，往正走偏品红";
            case "滤镜强度": return "上面两个旋钮的 CC 刻度。太小了看不出效果";
            case "预闪": return "印相前先给相纸一次均匀曝光，压在暗部的对比";
            case "曲线柔度": return "高光/暗部保护曲线的圆滑程度。越大越缓";
            case "提升上限": return "暗部最多能被提亮多少挡";
            case "光晕开关": return "关掉就是一支完全没有光晕的胶片";
            case "去抗光晕": return "把胶片当成撕掉抗光晕层。CineStill 800T 的物理差异";
            case "颗粒开关": return "关掉就是完全没有颗粒的画面";
            case "层间抑制": return "染料层之间的相互抑制。决定一支胶片的色彩分离";
            case "扫描白": return "扫描时按片基的白点做校正";
            case "扫描黑": return "扫描时按片基的黑点做校正";
            case "动态范围": return "扩展动态范围，把高光往回拉一点";
            default: return "";
        }
    }

    private void refreshKnobChips() {
        if (knobStrip == null) return;
        for (int i = 0; i < knobStrip.getChildCount(); ++i) {
            final LinearLayout chip = (LinearLayout) knobStrip.getChildAt(i);
            if (chip.getChildCount() < 2) continue;
            final boolean active = i == activeKnob;
            final TextView key = (TextView) chip.getChildAt(0);
            final TextView name = (TextView) chip.getChildAt(1);
            android.graphics.drawable.GradientDrawable box = new android.graphics.drawable.GradientDrawable();
            box.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            box.setCornerRadius(0f);
            box.setColor(active ? 0x141C2AA5 : 0x00000000);
            box.setStroke(dp(active ? 2 : 1), active ? UI_BLUE : UI_LINE);
            key.setBackground(box);
            key.setTextColor(active ? UI_BLUE : UI_INK);
            name.setTextColor(active ? UI_BLUE : UI_MUTED);
        }
    }

    private LinearLayout buildFormatPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(sectionHead("PHYSICAL SCALE / 光学尺度", this::resetFormatPanel),
                new LinearLayout.LayoutParams(-1, dp(38)));
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        formatCardStrip = new LinearLayout(this);
        formatCardStrip.setOrientation(LinearLayout.HORIZONTAL);
        formatCardStrip.setPadding(0, dp(10), 0, 0);
        formatScroll = scroll;
        scroll.addView(formatCardStrip, new android.widget.FrameLayout.LayoutParams(-2, dp(127)));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(127)));

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(android.view.Gravity.CENTER_VERTICAL);
        foot.addView(label("颗粒 / 光晕尺度 · 不裁切构图", 8.5f, UI_MUTED, tfHan),
                new LinearLayout.LayoutParams(0, -2, 1f));
        formatFootValue = label("36 mm", 9, UI_BLUE, tfBody);
        foot.addView(formatFootValue);
        panel.addView(foot, new LinearLayout.LayoutParams(-1, dp(28)));

        rebuildFormatCards();
        return panel;
    }

    private void rebuildFormatCards() {
        if (formatCardStrip == null) return;
        formatCardStrip.removeAllViews();
        formatCards.clear();
        for (int i = 0; i < FORMAT_NAMES.length; ++i) {
            final int index = i;
            boolean active = i == formatIndex;

            android.widget.FrameLayout card = new android.widget.FrameLayout(this);
            card.setBackground(cardBorder(active));
            card.setPadding(dp(1), dp(1), dp(1), dp(1));

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.VERTICAL);
            top.setPadding(dp(8), dp(16), dp(7), dp(2));

            LinearLayout valueRow = new LinearLayout(this);
            valueRow.setOrientation(LinearLayout.HORIZONTAL);
            valueRow.setGravity(android.view.Gravity.BOTTOM);
            TextView value = label(String.format(java.util.Locale.US, "%.0f", FORMAT_MM[i]),
                    23, active ? UI_BLUE : UI_MUTED, tfDisplay);
            value.setLetterSpacing(-0.05f);
            valueRow.addView(value);
            TextView unit = label("mm", 8, active ? UI_BLUE : UI_MUTED, tfBody);
            LinearLayout.LayoutParams unitLp = new LinearLayout.LayoutParams(-2, -2);
            unitLp.leftMargin = dp(2);
            unitLp.bottomMargin = dp(2);
            valueRow.addView(unit, unitLp);
            top.addView(valueRow);

            TextView kind = label(i == 2 || i >= 7 ? "宽幅" : "常规", 7, UI_MUTED, tfHan);
            LinearLayout.LayoutParams kindLp = new LinearLayout.LayoutParams(-2, -2);
            kindLp.topMargin = dp(4);
            top.addView(kind, kindLp);
            card.addView(top, new android.widget.FrameLayout.LayoutParams(-1, -1));

            TextView name = label(FORMAT_NAMES[i], 8.5f, active ? UI_BLUE : UI_INK, tfHan);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setGravity(android.view.Gravity.CENTER_VERTICAL);
            name.setBackgroundColor(UI_PAPER);
            name.setPadding(dp(8), 0, dp(7), 0);
            card.addView(name, new android.widget.FrameLayout.LayoutParams(
                    -1, dp(34), android.view.Gravity.BOTTOM));

            if (active) {
                View mark = new View(this);
                mark.setBackgroundColor(UI_BLUE);
                android.widget.FrameLayout.LayoutParams markLp = new android.widget.FrameLayout.LayoutParams(
                        dp(6), dp(6), android.view.Gravity.TOP | android.view.Gravity.END);
                markLp.topMargin = dp(4);
                markLp.rightMargin = dp(4);
                card.addView(mark, markLp);
            }

            card.setClickable(true);
            card.setOnClickListener(v -> pickFormat(index));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(dp(80), dp(105));
            cardLp.rightMargin = dp(9);
            formatCardStrip.addView(card, cardLp);
            formatCards.add(card);
        }
    }

    private void pickFormat(int index) {
        if (index < 0 || index >= FORMAT_MM.length) return;
        formatIndex = index;
        filmFormatMm = FORMAT_MM[index];
        // ... and the other way: a frame length picks the gate it fits.
        final String gate = gateForMm(filmFormatMm);
        if (!gate.equals(overscanFormat) && (edgeIds.isEmpty() || edgeIds.contains(gate))) {
            overscanFormat = gate;
            if (edgeValue != null) {
                final int gi = edgeIds.indexOf(gate);
                edgeValue.setText(gi >= 0 ? edgeLabels.get(gi) : gateLabelsFallback(gate));
            }
        }
        if (formatFootValue != null) {
            formatFootValue.setText(String.format(java.util.Locale.US, "%.0f mm", FORMAT_MM[index]));
        }
        rebuildFormatCards();
        if (index < formatCards.size() && formatScroll != null) {
            final View target = formatCards.get(index);
            formatScroll.post(() -> formatScroll.smoothScrollTo(Math.max(0, target.getLeft() - dp(8)), 0));
        }
        if (session != null && uiReady) {
            applyParams();
            showingOriginal = false;
            renderImage();
        }
    }

    private LinearLayout buildEdgePanel() {
        edgeRoot = new LinearLayout(this);
        edgeRoot.setOrientation(LinearLayout.VERTICAL);

        // Two big cards. A card is a switch, a one-line summary, and a door
        // into its own page -- not fifteen controls at once.
        LinearLayout home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.addView(sectionHead("FRAME & IMPRINT / 边框", this::resetEdgePanel),
                new LinearLayout.LayoutParams(-1, dp(38)));

        HorizontalScrollView cardScroll = new HorizontalScrollView(this);
        cardScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout cardStrip = new LinearLayout(this);
        cardStrip.setOrientation(LinearLayout.HORIZONTAL);
        cardStrip.addView(bigCard("片边", "REBATE", this::edgeSummary, () -> openEdgePage(0)),
                cardLp());
        cardStrip.addView(bigCard("日期水印", "DATE BACK", this::dateSummary, () -> openEdgePage(1)),
                cardLp());
        cardScroll.addView(cardStrip, new android.widget.FrameLayout.LayoutParams(-2, -2));
        home.addView(cardScroll, new LinearLayout.LayoutParams(-1, -2));

        edgeRoot.addView(home, new LinearLayout.LayoutParams(-1, -2));
        buildEdgePages();
        refreshEdgeGroups();
        return edgeRoot;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(176), -2);
        lp.rightMargin = dp(6);
        return lp;
    }

    /** An entry card: the scale page's row, a summary, and a chevron. */
    private View bigCard(String title, String subtitle,
                         java.util.function.Supplier<String> summary,
                         Runnable onOpen) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(9), dp(12), dp(7));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(UI_PAPER);
        bg.setCornerRadius(0f);
        bg.setStroke(dp(1), UI_LINE);
        card.setBackground(bg);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView name = mixedLabel(title + "  " + subtitle, 8.5f, UI_INK);
        name.setLetterSpacing(0.06f);
        titleRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        titleRow.addView(label("›", 12f, UI_MUTED, tfBody));
        card.addView(titleRow);

        final TextView lines = label("", 7.5f, UI_MUTED, tfHan);
        lines.setMaxLines(2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(4);
        card.addView(lines, lp);

        card.setOnClickListener(v -> onOpen.run());
        card.setTag(new Object[]{summary, lines});
        return card;
    }

    /** Repaint the two entry cards. Cheap, so it runs on every state change. */
    private void refreshEdgeCards() {
        if (edgeRoot == null || edgeRoot.getChildCount() == 0) return;
        View home = edgeRoot.getChildAt(0);
        if (!(home instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) home;
        for (int i = 0; i < group.getChildCount(); ++i) {
            View child = group.getChildAt(i);
            if (child instanceof HorizontalScrollView) {
                LinearLayout strip = (LinearLayout) ((HorizontalScrollView) child).getChildAt(0);
                for (int c = 0; c < strip.getChildCount(); ++c) {
                    View card = strip.getChildAt(c);
                    Object tag = card.getTag();
                    if (!(tag instanceof Object[])) continue;
                    Object[] parts = (Object[]) tag;
                    @SuppressWarnings("unchecked")
                    java.util.function.Supplier<String> summary = (java.util.function.Supplier<String>) parts[0];
                    ((TextView) parts[1]).setText(summary.get());
                }
            }
        }
    }

    private String edgeSummary() {
        if (!overscanOn) return "未开启 · 点进去设置";
        final int at = edgeIds.indexOf(overscanFormat);
        final String gate = at >= 0 && at < edgeLabels.size() ? edgeLabels.get(at) : overscanFormat;
        return gate + " · 雾 " + fmt1(edgeFog) + " · 漏光 " + fmt1(edgeLeaks)
                + " · 张数 " + (frameNumber < 0.5f ? "自动" : String.valueOf(Math.round(frameNumber)));
    }

    private String dateSummary() {
        if (!dateOn) return "未开启 · 点进去设置";
        return (dateText == null || dateText.isEmpty() ? imprintDate() : dateText)
                + " · " + labelOf(STYLE_LABELS, STYLE_IDS, dateStyle)
                + " " + labelOf(PLACE_LABELS, PLACE_IDS, datePlacement);
    }

    private static int jsonInt(String json, String key) {
        if (json == null) return -1;
        final String needle = "\"" + key + "\":";
        final int at = json.indexOf(needle);
        if (at < 0) return -1;
        int i = at + needle.length();
        final int start = i;
        while (i < json.length() && (json.charAt(i) == '-' || Character.isDigit(json.charAt(i)))) ++i;
        try { return Integer.parseInt(json.substring(start, i)); } catch (Throwable t) { return -1; }
    }

    private static String fmt1(float v) {
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    /** The two detail pages, built once, shown one at a time. */
    private void buildEdgePages() {
        final java.util.function.Consumer<Void> redraw = v -> {
            applyParams();
            if (uiReady && session != null) {
                showingOriginal = false;
                setOriginalVisual(false);
                renderImage();
            }
        };

        edgePage = new LinearLayout(this);
        edgePage.setOrientation(LinearLayout.VERTICAL);
        edgePage.addView(pageHead("片边 / REBATE"), new LinearLayout.LayoutParams(-1, dp(38)));
        overscanMark = new Square(this, UI_BLUE, 0x66171717);
        edgePage.addView(toggleRow("开启片边", overscanMark, overscanOn, on -> {
            if (on && edgeIds.isEmpty()) {
                overscanMark.setLit(false);
                showInAppToast("这张图的长宽比不对应任何片门");
                return;
            }
            overscanOn = on;
            refreshEdgeGroups();
            onEdgeChanged();
        }), new LinearLayout.LayoutParams(-1, dp(44)));
        LinearLayout edgeInner = new LinearLayout(this);
        edgeInner.setOrientation(LinearLayout.VERTICAL);

        {
            final int at = edgeIds.indexOf(overscanFormat);
            final String current = at >= 0 && at < edgeLabels.size() ? edgeLabels.get(at) : overscanFormat;
            addChoice(edgeInner, "片门格式", current, tv -> {
                edgeValue = tv;
                showEdgePicker();
            });
        }

        edgeTextMark = new Square(this, UI_BLUE, 0x66171717);
        edgeInner.addView(toggleRow("片边文字（厂标 / 张数）", edgeTextMark, edgeTextOn, on -> {
            edgeTextOn = on;
            redraw.accept(null);
        }), new LinearLayout.LayoutParams(-1, dp(40)));
        edgeFogBar = rangeRow(edgeInner, "片基雾", "片基本身的灰雾。0 是完全透明，越大越脏",
                0.0f, 4.0f, edgeFog, v -> edgeFog = v);
        edgeLeaksBar = rangeRow(edgeInner, "漏光", "卷轴漏进来的光，从片边渗向画面",
                0.0f, 4.0f, edgeLeaks, v -> edgeLeaks = v);
        frameNoBar = rangeRow(edgeInner, "片边张数", "片边上印的张数。0 = 按 seed 随机",
                0.0f, 99.0f, frameNumber, v -> frameNumber = v);
        edgeFNumBar = rangeRow(edgeInner, "光圈值", "相机记录的 f 值。决定片门边缘的虚化。0 = 未知（按 f/5.6）",
                0.0f, 64.0f, edgeFNumber, v -> edgeFNumber = v);

        addChoice(edgeInner, "片边模式", labelOf(MODE_LABELS, MODE_IDS, edgeMode), tv ->
                showChoiceMapping("片边模式", MODE_LABELS, MODE_IDS, edgeMode, id -> {
                    edgeMode = id;
                    tv.setText(labelOf(MODE_LABELS, MODE_IDS, id));
                    redraw.accept(null);
                }));
        addChoice(edgeInner, "片门形状", labelOf(GATE_LABELS, GATE_IDS, edgeGate), tv ->
                showChoiceMapping("片门形状", GATE_LABELS, GATE_IDS, edgeGate, id -> {
                    edgeGate = id;
                    tv.setText(labelOf(GATE_LABELS, GATE_IDS, id));
                    redraw.accept(null);
                }));
        addChoice(edgeInner, "齿孔", labelOf(HOLES_LABELS, HOLES_IDS, edgeHoles), tv ->
                showChoiceMapping("齿孔里看到什么", HOLES_LABELS, HOLES_IDS, edgeHoles, id -> {
                    edgeHoles = id;
                    tv.setText(labelOf(HOLES_LABELS, HOLES_IDS, id));
                    redraw.accept(null);
                }));
        addChoice(edgeInner, "片夹", labelOf(CARRIER_LABELS, CARRIER_IDS, edgeCarrier), tv ->
                showChoiceMapping("胶片两侧露出什么", CARRIER_LABELS, CARRIER_IDS, edgeCarrier, id -> {
                    edgeCarrier = id;
                    tv.setText(labelOf(CARRIER_LABELS, CARRIER_IDS, id));
                    redraw.accept(null);
                }));
        addChoice(edgeInner, "片边种子", String.valueOf(cameraSeed), tv ->
                askText("片边随机种子（0 – 2147483647）", String.valueOf(cameraSeed), true, v -> {
                    try { cameraSeed = Math.max(0, Integer.parseInt(v)); } catch (Exception ignored) { return; }
                    tv.setText(String.valueOf(cameraSeed));
                    redraw.accept(null);
                }));
        addChoice(edgeInner, "帧种子", String.valueOf(frameSeed), tv ->
                askText("帧随机种子（0 – 2147483647）", String.valueOf(frameSeed), true, v -> {
                    try { frameSeed = Math.max(0, Integer.parseInt(v)); } catch (Exception ignored) { return; }
                    tv.setText(String.valueOf(frameSeed));
                    redraw.accept(null);
                }));
        this.edgeInner = edgeInner;
        edgePage.addView(cappedScroll(edgeInner, 340), new LinearLayout.LayoutParams(-1, -2));
        edgeRoot.addView(edgePage, new LinearLayout.LayoutParams(-1, -2));

        datePage = new LinearLayout(this);
        datePage.setOrientation(LinearLayout.VERTICAL);
        datePage.addView(pageHead("日期水印 / DATE BACK"), new LinearLayout.LayoutParams(-1, dp(38)));
        dateMark = new Square(this, UI_BLUE, 0x66171717);
        datePage.addView(toggleRow("开启日期水印", dateMark, dateOn, on -> {
            dateOn = on;
            if (dateOn && dateText == null) dateText = imprintDate();
            refreshEdgeGroups();
            onEdgeChanged();
        }), new LinearLayout.LayoutParams(-1, dp(44)));
        LinearLayout dateInner = new LinearLayout(this);
        dateInner.setOrientation(LinearLayout.VERTICAL);
        // The one field the app used to decide for the user: it read the date
        // out of EXIF and gave no way to write a different one.
        addChoice(dateInner, "日期文字", dateText == null || dateText.isEmpty() ? imprintDate() : dateText,
                tv -> askText("印在画面上的日期（可以自己写）",
                        dateText == null || dateText.isEmpty() ? imprintDate() : dateText, v -> {
                            dateText = v.isEmpty() ? null : v;
                            tv.setText(dateText == null ? imprintDate() : dateText);
                            redraw.accept(null);
                        }));
        dateInsetXBar = rangeRow(dateInner, "左右内边距", "字到片门左右边的距离，毫米",
                0.0f, 30.0f, dateInsetX, v -> dateInsetX = v);
        dateInsetYBar = rangeRow(dateInner, "上下内边距", "字到片门上下边的距离，毫米",
                0.0f, 30.0f, dateInsetY, v -> dateInsetY = v);
        dateSizeBar = rangeRow(dateInner, "日期大小", "相对该样式的字高",
                0.4f, 3.0f, dateSize, v -> dateSize = v);
        dateEvBar = rangeRow(dateInner, "日期亮度", "红光在 18% 灰之上的曝光，单位挡",
                -2.0f, 8.0f, dateEv, v -> dateEv = v);
        addChoice(dateInner, "日期位置", labelOf(PLACE_LABELS, PLACE_IDS, datePlacement), tv ->
                showChoiceMapping("日期打在哪里", PLACE_LABELS, PLACE_IDS, datePlacement, id -> {
                    datePlacement = id;
                    tv.setText(labelOf(PLACE_LABELS, PLACE_IDS, id));
                    redraw.accept(null);
                }));
        addChoice(dateInner, "日期角位", labelOf(CORNER_LABELS, CORNER_IDS, dateCorner), tv ->
                showChoiceMapping("画面内的哪个角", CORNER_LABELS, CORNER_IDS, dateCorner, id -> {
                    dateCorner = id;
                    tv.setText(labelOf(CORNER_LABELS, CORNER_IDS, id));
                    redraw.accept(null);
                }));
        addChoice(dateInner, "日期样式", labelOf(STYLE_LABELS, STYLE_IDS, dateStyle), tv ->
                showChoiceMapping("日期用哪种字", STYLE_LABELS, STYLE_IDS, dateStyle, id -> {
                    dateStyle = id;
                    tv.setText(labelOf(STYLE_LABELS, STYLE_IDS, id));
                    redraw.accept(null);
                }));
        addChoice(dateInner, "拍摄数据", dataText.isEmpty() ? "（空）" : dataText, tv ->
                askText("拍摄数据（和日期一起印在片边）", dataText, v -> {
                    dataText = v;
                    tv.setText(v.isEmpty() ? "（空）" : v);
                    redraw.accept(null);
                }));
        addChoice(dateInner, "第二帧日期", dateTextB.isEmpty() ? "（空）" : dateTextB, tv ->
                askText("半格对的第二帧日期（用不到就留空）", dateTextB, v -> {
                    dateTextB = v;
                    tv.setText(v.isEmpty() ? "（空）" : v);
                    redraw.accept(null);
                }));
        this.dateInner = dateInner;
        datePage.addView(cappedScroll(dateInner, 340), new LinearLayout.LayoutParams(-1, -2));
        edgeRoot.addView(datePage, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Wrap-content up to `maxDp`, scrollable past it. A plain ScrollView
     *  either takes the whole panel or none of it. */
    private View cappedScroll(View content, int maxDp) {
        content.setPadding(0, dp(2), 0, dp(10));
        CappedScroll scroll = new CappedScroll(this, dp(maxDp));
        scroll.addView(content, new android.widget.FrameLayout.LayoutParams(-1, -2));
        return scroll;
    }

    private static final class CappedScroll extends ScrollView {
        private final int cap;

        CappedScroll(android.content.Context context, int cap) {
            super(context);
            this.cap = cap;
            setVerticalScrollBarEnabled(false);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            final int mode = MeasureSpec.getMode(heightSpec);
            final int size = MeasureSpec.getSize(heightSpec);
            final int limit = mode == MeasureSpec.EXACTLY ? Math.min(size, cap) : cap;
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
            // AT_MOST(cap) already hugs a shorter list; this only makes the
            // intent explicit if a future parent hands down EXACTLY.
            if (getMeasuredHeight() > cap) {
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY));
            }
        }
    }

    private ScrollView scrollOf(View content) {
        final ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new android.widget.FrameLayout.LayoutParams(-1, -2));
        return scroll;
    }

    private View pageHead(String title) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView back = label("‹ 返回", 10f, UI_BLUE, tfHan);
        back.setPadding(0, dp(8), dp(16), dp(8));
        back.setOnClickListener(v -> openEdgePage(-1));
        head.addView(back);
        TextView name = mixedLabel(title, 9f, UI_MUTED);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(8);
        head.addView(name, lp);
        return head;
    }

    /** -1 = the two cards, 0 = the rebate page, 1 = the date page. */
    private void openEdgePage(int which) {
        edgePageIndex = which;
        if (edgeRoot != null) {
            for (int i = 0; i < edgeRoot.getChildCount(); ++i) {
                edgeRoot.getChildAt(i).setVisibility(i == which + 1 ? View.VISIBLE : View.GONE);
            }
        }
        refreshEdgeCards();
    }

    private void refreshEdgeGroups() {
        if (edgeRoot == null) return;
        setEnabledDeep(edgeInner, overscanOn);
        setEnabledDeep(dateInner, dateOn);
        if (overscanMark != null) overscanMark.setLit(overscanOn);
        if (edgeTextMark != null) edgeTextMark.setLit(edgeTextOn);
        if (dateMark != null) dateMark.setLit(dateOn);
        refreshEdgeCards();
    }

    private static void setEnabledDeep(View view, boolean enabled) {
        if (view == null) return;
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.38f);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); ++i) setEnabledDeep(group.getChildAt(i), enabled);
        }
    }

    private void showEdgePicker() {
        if (edgeIds.isEmpty()) {
            showInAppToast("这张图没有可用片门");
            return;
        }
        int current = edgeIds.indexOf(overscanFormat);
        String currentLabel = current >= 0 && current < edgeLabels.size() ? edgeLabels.get(current) : edgeLabels.get(0);
        showChoiceSheet("选择片门格式", edgeLabels, currentLabel, picked -> {
            int i = edgeLabels.indexOf(picked);
            if (i < 0) return;
            overscanFormat = edgeIds.get(i);
            if (edgeValue != null) edgeValue.setText(edgeLabels.get(i));
            // The frame that goes in this gate, not whatever the scale page
            // happened to be left on.
            setScaleMm(gateMm(overscanFormat));
            onEdgeChanged();
        });
    }

    private View buildTabs() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(UI_PAPER);
        final String[] names = {"胶片", "光学", "尺度", "边框"};
        for (int i = 0; i < names.length; ++i) {
            final int index = i;
            android.widget.FrameLayout cell = new android.widget.FrameLayout(this);
            TextView name = label(names[i], 12, UI_MUTED, tfHan);
            name.setGravity(android.view.Gravity.CENTER);
            cell.addView(name, new android.widget.FrameLayout.LayoutParams(-1, -1));
            View bar = new View(this);
            bar.setBackgroundColor(UI_BLUE);
            bar.setVisibility(View.INVISIBLE);
            cell.addView(bar, new android.widget.FrameLayout.LayoutParams(
                    dp(26), dp(3), android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL));
            cell.setOnClickListener(v -> onTabClicked(index));
            tabs.addView(cell, new LinearLayout.LayoutParams(0, dp(58), 1f));
            tabCells[i] = cell;
            tabBars[i] = bar;
            tabLabels[i] = name;
        }
        return tabs;
    }

    private void onTabClicked(int index) {
        if (index == activeTabIndex) {
            setPanelCollapsed(!panelCollapsed);
            showInAppToast(panelCollapsed ? "参数面板已收起，再点当前页签展开" : "参数面板已展开");
        } else {
            activeTabIndex = index;
            setTab(index);
            setPanelCollapsed(false);
        }
    }

    private void setPanelCollapsed(boolean collapsed) {
        panelCollapsed = collapsed;
        if (panelHolder != null) panelHolder.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        if (controls != null) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) controls.getLayoutParams();
            // Open is "whatever the visible panel needs"; only the collapsed
            // row has a height of its own.
            lp.height = collapsed ? dp(CONTROLS_CLOSED_DP) : android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            controls.setLayoutParams(lp);
            // The holder's height follows the visible panel, so a tab switch
            // has to re-measure the whole column: without this the source
            // strip above kept its old position and the panel slid under it.
            controls.requestLayout();
            if (controls.getParent() instanceof android.view.View) {
                ((android.view.View) controls.getParent()).requestLayout();
            }
        }
    }

    /** Import and export use the same paper sheet as the style pickers. */
    void showActionSheet(String title, String note, String[] items,
                                 java.util.function.IntConsumer onPick) {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundColor(UI_PAPER);
        View cap = new View(this);
        cap.setBackgroundColor(UI_BLUE);
        sheet.addView(cap, new LinearLayout.LayoutParams(-1, dp(3)));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), 0, dp(16), 0);
        head.addView(label(title, 12, UI_INK, tfHan), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = label("关闭", 9, UI_MUTED, tfHan);
        close.setPaintFlags(close.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        close.setPadding(dp(12), dp(14), 0, dp(14));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        sheet.addView(head, new LinearLayout.LayoutParams(-1, dp(54)));

        if (note != null && !note.isEmpty()) {
            TextView noteView = label(note, 8.5f, UI_MUTED, tfHan);
            noteView.setPadding(dp(16), 0, dp(16), dp(12));
            sheet.addView(noteView);
        }
        for (int i = 0; i < items.length; ++i) {
            final int index = i;
            TextView row = label(items[i], 11, UI_INK, tfHan);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                onPick.accept(index);
            });
            sheet.addView(row, new LinearLayout.LayoutParams(-1, dp(50)));
        }
        dialog.setContentView(sheet);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setGravity(android.view.Gravity.BOTTOM);
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setDimAmount(0.35f);
        }
    }

    /** The feedback group, in the app's own card rather than a system dialog.
     *
     *  <p>Auto-shown once per build: a group nobody can find is useless, and a
     *  card on every launch is a card people learn to dismiss without reading.
     *  The About sheet carries a permanent entry, so the way back never depends
     *  on this having appeared. */
    private void maybeShowFeedbackGroup() {
        final android.content.SharedPreferences p = getSharedPreferences(PROMO_PREFS, MODE_PRIVATE);
        if (p.getBoolean(PROMO_MUTED, false)) return;
        if (p.getInt(PROMO_SEEN_CODE, 0) == UpdateCheck.versionCode(this)) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (isFinishing() || !uiReady) return;
            showFeedbackGroup(true);
        }, 1800);
    }

    void showFeedbackGroup(final boolean auto) {
        if (isFinishing()) return;
        final android.content.SharedPreferences p = getSharedPreferences(PROMO_PREFS, MODE_PRIVATE);
        final android.app.Dialog dialog = new android.app.Dialog(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(UI_PAPER);
        View cap = new View(this);
        cap.setBackgroundColor(UI_BLUE);
        card.addView(cap, new LinearLayout.LayoutParams(-1, dp(3)));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), 0, dp(16), 0);
        head.addView(mixedLabel("FEEDBACK / 反馈与交流", 8, UI_BLUE),
                new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = label("关闭", 9, UI_MUTED, tfHan);
        close.setPaintFlags(close.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        close.setPadding(dp(12), dp(14), 0, dp(14));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        card.addView(head, new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), 0, dp(16), 4);
        TextView title = label("遇到问题，或者想聊冲印？", 17, UI_INK, tfHan);
        body.addView(title);
        TextView copyText = label("渲染报错、导出异常、想要某支胶片，直接发在群里。"
                + "问题能复现的话，把「复制错误信息」的内容一起贴上。", 10, UI_MUTED, tfHan);
        copyText.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams copyTextLp = new LinearLayout.LayoutParams(-1, -2);
        copyTextLp.topMargin = dp(9);
        body.addView(copyText, copyTextLp);

        TextView number = label(QQ_GROUP, 27, UI_BLUE, tfDisplay);
        number.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams numberLp = new LinearLayout.LayoutParams(-1, -2);
        numberLp.topMargin = dp(16);
        body.addView(number, numberLp);
        TextView hint = label("QQ 群号，点下面按钮复制后搜索加入", 9, UI_MUTED, tfHan);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(5);
        body.addView(hint, hintLp);
        card.addView(body, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView open = label("打开 QQ", 10, UI_BLUE, tfHan);
        open.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable openBg = new android.graphics.drawable.GradientDrawable();
        openBg.setColor(UI_PAPER);
        openBg.setStroke(dp(1), UI_BLUE);
        open.setBackground(openBg);
        open.setOnClickListener(v -> openQqGroup());
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        openLp.rightMargin = dp(8);
        actions.addView(open, openLp);

        TextView copy = label("复制群号", 10, UI_PAPER, tfHan);
        copy.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable copyBg = new android.graphics.drawable.GradientDrawable();
        copyBg.setColor(UI_BLUE);
        copy.setBackground(copyBg);
        copy.setOnClickListener(v -> {
            if (copyToClipboard("SpektraLab 反馈群", QQ_GROUP)) {
                copy.setText("已复制");
            } else {
                copy.setText("复制失败 · 请长按");
            }
        });
        copy.setLongClickable(true);
        copy.setOnLongClickListener(v -> {
            copyToClipboard("SpektraLab 反馈群", QQ_GROUP);
            return true;
        });
        actions.addView(copy, new LinearLayout.LayoutParams(0, dp(42), 1.45f));
        card.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        if (auto) {
            TextView mute = label("不再自动弹出", 8.5f, UI_MUTED, tfHan);
            mute.setGravity(android.view.Gravity.CENTER);
            mute.setPaintFlags(mute.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            mute.setPadding(0, 0, 0, dp(14));
            mute.setOnClickListener(v -> {
                p.edit().putBoolean(PROMO_MUTED, true).apply();
                dialog.dismiss();
                showInAppToast("已关闭，仍可在「关于」里找到反馈群");
            });
            card.addView(mute, new LinearLayout.LayoutParams(-1, dp(30)));
        }

        dialog.setContentView(card);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            int width = Math.min(dp(358), getResources().getDisplayMetrics().widthPixels - dp(32));
            dialog.getWindow().setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setDimAmount(0.32f);
        }
        // Shown counts as seen: the point is that a new build mentions it once.
        p.edit().putInt(PROMO_SEEN_CODE, UpdateCheck.versionCode(this)).apply();
    }

    private boolean copyToClipboard(String labelText, String text) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(labelText, text));
            return true;
        } catch (Throwable t) {
            try { DebugSink.text("clipboard", String.valueOf(t)); } catch (Throwable ignored) { }
            return false;
        }
    }

    /** QQ's group card, then the search route. A ROM without QQ simply gets
     *  the number copied; that is a working answer, not a failure. */
    private void openQqGroup() {
        final String[] schemes = {
                "mqqwpa://im/chat?chat_type=group&uin=" + QQ_GROUP + "&version=1&src_type=web",
                "mqq://im/chat?chat_type=group&uin=" + QQ_GROUP + "&version=1&src_type=web",
        };
        for (String scheme : schemes) {
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(scheme)));
                return;
            } catch (Throwable ignored) { }
        }
        copyToClipboard("SpektraLab 反馈群", QQ_GROUP);
        showInAppToast("群号已复制，请在 QQ 里搜索加入");
    }

    /** A paper bottom sheet in the same visual language as the cards. */
    private void showChoiceSheet(String title, List<String> labels, String selected,
                                 java.util.function.Consumer<String> onPick) {
        showChoiceSheet(title, labels, null, selected, null, onPick);
    }

    /**
     * The same sheet with a second line under each name and rows that can be
     * refused.
     *
     * <p>The paper list needs both: a paper's character is what the choice is
     * actually about ("中性 · 人像常用" against "偏青 · 富士色调"), and a silver
     * paper cannot print a colour negative any more than a colour paper can
     * print a silver one. A row that cannot be picked says so by fading, not
     * by disappearing -- the user should see that the option exists and why it
     * is not for this film.
     */
    private void showChoiceSheet(String title, List<String> labels, List<String> notes,
                                 String selected,
                                 java.util.function.IntPredicate selectable,
                                 java.util.function.Consumer<String> onPick) {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundColor(UI_PAPER);
        View cap = new View(this);
        cap.setBackgroundColor(UI_BLUE);
        sheet.addView(cap, new LinearLayout.LayoutParams(-1, dp(3)));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), 0, dp(16), 0);
        head.addView(label(title, 12, UI_INK, tfHan), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = label("关闭", 9, UI_MUTED, tfHan);
        close.setPaintFlags(close.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        close.setPadding(dp(12), dp(14), 0, dp(14));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        sheet.addView(head, new LinearLayout.LayoutParams(-1, dp(54)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        boolean anyNote = false;
        for (int index = 0; index < labels.size(); ++index) {
            final String name = labels.get(index);
            final String note = (notes != null && index < notes.size()) ? notes.get(index) : null;
            final boolean enabled = selectable == null || selectable.test(index);
            final boolean active = name.equals(selected);
            if (note != null && !note.isEmpty()) anyNote = true;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            // A refused row is still readable: it is an option the user should
            // know exists, and only its tint says it is not for this film.
            row.setAlpha(enabled ? 1.0f : 0.38f);

            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            TextView text = label(name, 11, !enabled ? UI_MUTED : (active ? UI_BLUE : UI_INK), tfHan);
            text.setMaxLines(1);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            column.addView(text, new LinearLayout.LayoutParams(-1, -2));
            if (note != null && !note.isEmpty()) {
                TextView sub = label(note, 8.5f, UI_MUTED, tfHan);
                sub.setMaxLines(1);
                sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
                subLp.topMargin = dp(2);
                column.addView(sub, subLp);
            }
            row.addView(column, new LinearLayout.LayoutParams(0, -2, 1f));

            View dot = new View(this);
            android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
            dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dotBg.setColor(active ? UI_BLUE : 0x00000000);
            dotBg.setStroke(dp(1), active ? UI_BLUE : UI_MUTED);
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(13), dp(13));
            dotLp.leftMargin = dp(10);
            row.addView(dot, dotLp);

            if (enabled) {
                row.setClickable(true);
                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    onPick.accept(name);
                });
            }
            list.addView(row, new LinearLayout.LayoutParams(-1, dp(note != null && !note.isEmpty() ? 62 : 49)));
        }
        scroll.addView(list, new android.widget.FrameLayout.LayoutParams(-1, -2));
        int wanted = Math.min(dp(anyNote ? 62 : 49) * Math.max(1, labels.size()), dp(420));
        sheet.addView(scroll, new LinearLayout.LayoutParams(-1, wanted));

        dialog.setContentView(sheet);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setGravity(android.view.Gravity.BOTTOM);
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setDimAmount(0.35f);
        }
    }

    private void resetFilmPanel() {
        // 原图 is the reset: no stock, no render, the imported frame as it was.
        filmStock = null;
        printStock = "kodak_portra_endura";
        brandFilter = "all";
        showingOriginal = false;
        rebuildBrandRow();
        rebuildFilmCards();
        refreshPaperLabel();
        onStockChanged();
    }

    private void resetOpticsPanel() {
        printExposure = 1.0f;
        grainAmount = 1.0f;
        halationAmount = 1.0f;
        glareAmount = 1.0f;
        highlightRoom = 0.0f;
        shadowRoom = 0.0f;
        halationBoost = 0.0f;
        halationScatter = 1.0f;
        lensBlur = 0.0f;
        yFilter = 0.0f;
        mFilter = 0.0f;
        filterScale = 1.0f;
        preflash = 0.0f;
        refreshKnob();
        if (session != null) { applyParams(); renderImage(); }
    }

    private void setBarProgress(SeekBar bar, float lo, float hi, float value) {
        if (bar == null) return;
        bar.setProgress(Math.round((value - lo) / (hi - lo) * 1000f));
    }

    private void resetFormatPanel() {
        formatIndex = 0;
        filmFormatMm = FORMAT_MM[0];
        if (formatFootValue != null) formatFootValue.setText(String.format(java.util.Locale.US, "%.0f mm", FORMAT_MM[0]));
        rebuildFormatCards();
        if (session != null) { applyParams(); renderImage(); }
    }

    private void resetEdgePanel() {
        overscanOn = false;
        dateOn = false;
        overscanMark.setLit(false);
        dateMark.setLit(false);
        edgeFog = 1.0f;
        edgeLeaks = 0.0f;
        frameNumber = 0.0f;
        edgeFNumber = 0.0f;
        dateSize = 1.0f;
        dateEv = 3.5f;
        dateInsetX = 3.0f;
        dateInsetY = 2.4f;
        edgeMode = "strip";
        edgeGate = "auto";
        edgeTextOn = true;
        edgeHoles = "white";
        edgeCarrier = "none";
        datePlacement = "frame";
        dateCorner = "br";
        dateStyle = "lcd";
        cameraFilter = "";
        sceneNorm = "power";
        dataText = "";
        dateTextB = "";
        cameraSeed = 1;
        frameSeed = 1;
        setBarProgress(edgeFogBar, 0f, 4f, edgeFog);
        setBarProgress(edgeLeaksBar, 0f, 4f, edgeLeaks);
        setBarProgress(frameNoBar, 0f, 99f, frameNumber);
        setBarProgress(edgeFNumBar, 0f, 64f, edgeFNumber);
        setBarProgress(dateSizeBar, 0.4f, 3f, dateSize);
        setBarProgress(dateEvBar, -2f, 8f, dateEv);
        setBarProgress(dateInsetXBar, 0f, 30f, dateInsetX);
        setBarProgress(dateInsetYBar, 0f, 30f, dateInsetY);
        refreshKnob();
        if (session != null) onEdgeChanged();
    }

    private void toggleOriginal() {
        if (beforeBitmap == null) {
            showInAppToast("先导入一张照片");
            return;
        }
        // An unrendered import is already the original: pressing this must not
        // become the thing that starts a render.
        if (showingOriginal && lastRenderedBitmap == null) {
            showInAppToast("选一张胶片后才会渲染");
            return;
        }
        showingOriginal = !showingOriginal;
        if (showingOriginal) {
            compare.setAfter(null);
            emptyState.setVisibility(View.GONE);
            rawState.setVisibility(View.GONE);
            compare.setVisibility(View.VISIBLE);
            statusText.setText("ORIGINAL · 未渲染");
            setOriginalVisual(true);
            updateFilmSelection();
            return;
        }
        setOriginalVisual(false);
        if (lastRenderedBitmap != null) {
            compare.setAfter(lastRenderedBitmap);
            compare.setVisibility(View.VISIBLE);
            emptyState.setVisibility(View.GONE);
            rawState.setVisibility(View.GONE);
            statusText.setText("LIVE");
            updateFilmSelection();
        } else if (session != null && uiReady) {
            renderImage();
        }
    }

    /** A drawn tool glyph: no font fallback tofu at 19 dp. */
    private static class ToolGlyph extends View {
        static final int COMPARE = 0, CAMERA = 1;
        private final int kind;
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float stroke;

        ToolGlyph(android.content.Context context, int kind, int color) {
            super(context);
            this.kind = kind;
            stroke = Math.max(1f, getResources().getDisplayMetrics().density);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(color);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            final float w = getWidth(), h = getHeight(), p = stroke;
            if (kind == COMPARE) {
                canvas.drawRect(p, h * 0.20f, w - p, h * 0.82f, paint);
                canvas.drawLine(w * 0.5f, h * 0.20f, w * 0.5f, h * 0.82f, paint);
                canvas.drawLine(p, h * 0.68f, w * 0.42f, h * 0.38f, paint);
                canvas.drawLine(w - p, h * 0.68f, w * 0.58f, h * 0.38f, paint);
            } else {
                canvas.drawRect(p, h * 0.28f, w - p, h * 0.82f, paint);
                canvas.drawLine(w * 0.27f, h * 0.28f, w * 0.36f, h * 0.16f, paint);
                canvas.drawLine(w * 0.36f, h * 0.16f, w * 0.64f, h * 0.16f, paint);
                canvas.drawLine(w * 0.64f, h * 0.16f, w * 0.73f, h * 0.28f, paint);
                canvas.drawCircle(w * 0.5f, h * 0.55f, h * 0.16f, paint);
            }
        }
    }

    /** Only the gates whose shape this frame is: the engine's own 5 % rule. */
    private void refreshEdgeFormats() {
        edgeLabels.clear();
        edgeIds.clear();
        final double aspect = (sourceWidth > 0 && sourceHeight > 0)
                ? (double) Math.max(sourceWidth, sourceHeight) / Math.min(sourceWidth, sourceHeight)
                : 0.0;
        int best = 0;
        double bestError = Double.MAX_VALUE;
        for (int i = 0; i < EDGE_IDS.length; ++i) {
            final double error = aspect <= 0 ? 0.0 : Math.abs(aspect / EDGE_RATIOS[i] - 1.0);
            if (aspect > 0 && error > 0.045) continue;
            edgeLabels.add(EDGE_LABELS[i]);
            edgeIds.add(EDGE_IDS[i]);
            if (error < bestError) { bestError = error; best = edgeIds.size() - 1; }
        }
        if (!edgeIds.isEmpty() && !edgeIds.contains(overscanFormat)) {
            overscanFormat = edgeIds.get(Math.max(0, Math.min(best, edgeIds.size() - 1)));
        }
        if (edgeValue != null) {
            final int selected = edgeIds.indexOf(overscanFormat);
            edgeValue.setText(selected >= 0 ? edgeLabels.get(selected) : "无可用片门");
        }
        try {
            DebugSink.text("overscan", "aspect=" + aspect + " gates=" + edgeIds
                    + " chosen=" + overscanFormat);
        } catch (Throwable ignored) { }
    }

    private void onEdgeChanged() {
        applyParams();
        if (uiReady && session != null) {
            showingOriginal = false;
            setOriginalVisual(false);
            renderImage();
        }
    }

    /**
     * Render the catalogue once, small, in the background, so the shelf shows
     * what each film does to *this* frame instead of a grey placeholder.
     *
     * <p>800 px on the long edge, the engine's own floor for a preview: a card
     * is 86 by 67 dp and cannot show more detail than that anyway. The queue
     * yields the moment the user picks a film, because a real preview at full
     * preview size matters more than a complete shelf.
     */
    private void startPrefetch() {
        stopPrefetch();
        if (session == null || beforeBitmap == null || filmIds.isEmpty()) return;
        // Everything still missing. An empty list is the common case on the
        // second import of the same frame, and starting a thread for it would
        // only race the user's first render.
        // Visible brand first, then the rest: the user is looking at one shelf.
        // Slides are rendered like anything else now; the card's own delta
        // carries `scan_film`, so a card and the canvas agree.
        final java.util.List<String> pending = new java.util.ArrayList<>();
        final java.util.List<String> rest = new java.util.ArrayList<>();
        for (int i = 0; i < filmIds.size(); ++i) {
            final String id = filmIds.get(i);
            if (filmThumbs.containsKey(id)) continue;
            if ("all".equals(brandFilter) || brandFilter.equals(brandOf(id))) pending.add(id);
            else rest.add(id);
        }
        pending.addAll(rest);
        if (pending.isEmpty()) return;

        final int generation = ++prefetchGeneration;
        prefetchRunning = true;
        prefetchThread = new Thread(() -> {
            try {
                // The queue renders the `live` tier, whose long edge is the
                // session's `io.preview_long_edge`. `preview` is a fixed 3400 px
                // in the engine, which is why the cards used to crawl: each one
                // was a near-export-sized print. Set the edge once for the whole
                // pass -- changing it per card drops the tier's cached image and
                // costs more than the smaller render saves.
                setPrefetchEdge(PREFETCH_LONG_EDGE);
                for (String id : pending) {
                    if (generation != prefetchGeneration || !prefetchRunning) return;
                    if (filmThumbs.containsKey(id)) continue;
                    final long t0 = System.currentTimeMillis();
                    final Bitmap thumb = renderStockThumbnail(id);
                    final long ms = System.currentTimeMillis() - t0;
                    if (thumb == null) continue;
                    try { DebugSink.text("prefetch-timing", id + " " + ms + " ms"); } catch (Throwable ignored) { }
                    runOnUiThread(() -> {
                        if (generation != prefetchGeneration) return;
                        filmThumbs.put(id, thumb);
                        updateCardThumb(id);
                    });
                }
            } finally {
                // A queue from a previous frame can outlive the import that
                // replaced it. Knocking `prefetchRunning` down and resetting
                // the edge here would stop the *live* queue mid-shelf, and the
                // cards after that point would simply never fill.
                if (generation == prefetchGeneration) {
                    setPrefetchEdge(PREVIEW_LONG_EDGE);
                    prefetchRunning = false;
                }
                try {
                    DebugSink.text("prefetch", "done gen=" + generation + "/" + prefetchGeneration
                            + " thumbs=" + filmThumbs.size());
                } catch (Throwable ignored) { }
            }
        }, "film-prefetch");
        prefetchThread.setPriority(Thread.MIN_PRIORITY);
        prefetchThread.start();
    }

    private void setPrefetchEdge(int longEdge) {
        final SpektraLabEngine.Session s = session;
        if (s == null) return;
        synchronized (renderMutex) {
            try {
                s.setParams(String.format(java.util.Locale.US,
                        "{\"preview_long_edge\":%d}", longEdge));
            } catch (Throwable ignored) { }
        }
    }

    private void stopPrefetch() {
        prefetchGeneration++;
        prefetchRunning = false;
    }

    /** One stock, one small render at the shelf's own size.
     *
     *  The session is left sitting on whichever film this card asked for; the
     *  next render or export states its own film in its own delta, so there is
     *  no bookkeeping here to fall out of step. */
    private Bitmap renderStockThumbnail(String stock) {
        final SpektraLabEngine.Session s = session;
        if (s == null) return null;
        synchronized (renderMutex) {
            try {
                if (s != session) return null;          // the frame was replaced while we waited
                // **The whole parameter set, not just film+paper.** A delta
                // that carries `film_stock` is a stock change, and the engine
                // answers a stock change by rebuilding the parameter tree --
                // every field the delta leaves out falls back to its default.
                // This card used to send only the two names, so its exposure,
                // grain, halation, glare, format and rebate text were all the
                // defaults while a tapped render carried the user's. That is
                // the "some films look nothing like themselves" report: the
                // shelf and the canvas were developing two different recipes.
                // The card is "this film on its own paper": a black-and-white
                // stock gets Ilford whatever the canvas is printing on.
                final String cardPaper = filmTargetPrints.containsKey(stock)
                        ? filmTargetPrints.get(stock) : printStock;
                applyParams(stock, cardPaper, PREFETCH_LONG_EDGE);
                // `live` honours preview_long_edge (800 here); `preview` is a
                // fixed 3400 px and made every card an export-sized print.
                final int[] dims = s.renderBegin("live");
                final int w = dims[0], h = dims[1];
                if (w <= 0 || h <= 0) return null;
                final int[] pixels = new int[w * h];
                final int copied = s.copyArgbRange(pixels, 0, h);
                if (copied != w * h) return null;
                Bitmap full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                full.setPixels(pixels, 0, w, 0, 0, w, h);
                final int tw = Math.max(1, dp(86));
                final int th = Math.max(1, Math.round(tw * h / (float) w));
                Bitmap small = Bitmap.createScaledBitmap(full, tw, th, true);
                if (small != full) full.recycle();
                return small;
            } catch (Throwable t) {
                try { DebugSink.text("prefetch", "render " + stock + ": " + t); } catch (Throwable ignored) { }
                return null;
            } finally {
                // Nothing is put back. The card left its own film on the
                // session; the next render or export states the user's film in
                // its own delta, so there is nothing here to keep in step.
                try { s.renderEnd(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * A slide is developed to a positive and is never printed: the engine
     * scans it (`io.scan_film`) instead of running it through paper, and any
     * paper the session carries is ignored on that path. Everything else about
     * the frame -- grain, halation, the rebate -- is the film's own, which is
     * why the shelf can show one at all.
     */
    private boolean isPositiveStock(String stock) {
        final int i = stock == null ? -1 : filmIds.indexOf(stock);
        return i >= 0 && i < filmTypes.size() && "positive".equals(filmTypes.get(i));
    }

    /**
     * The four black-and-white stocks the catalogue carries.
     *
     * <p>Not a `type` in the catalogue: a monochrome film is a negative like
     * any other there, and the distinction only matters to two fields. The
     * engine's grain has a colour and a monochrome form, and the macOS/iOS app
     * sends the single-layer one for these -- three sub-layers are the colour
     * films' model, and nothing on a silver sheet supports the split.
     */
    private static boolean isMonochromeStock(String stock) {
        if (stock == null) return false;
        switch (stock) {
            case "kodak_tri_x_400":
            case "ilford_hp5_plus_400":
            case "kodak_tmax_100":
            case "fujifilm_neopan_acros_100_ii":
                return true;
            default:
                return false;
        }
    }

    /**
     * What the Grain slider's 1 means on a black-and-white stock: the
     * `grain_amount` at which the engine renders the diffuse RMS granularity
     * the maker publishes. Four approved 2026-10-09 on the macOS side; a
     * colour film's is 1, because nothing is measured into those.
     *
     * <p>Without this a black-and-white frame rendered at 1.0 whatever the
     * stock: Tri-X came out with 41 % less grain than the film has, Acros with
     * 56 % more.
     */
    private static double ownGrain(String stock) {
        if (stock == null) return 1.0;
        switch (stock) {
            case "kodak_tri_x_400": return 1.70;
            case "ilford_hp5_plus_400": return 1.49;
            case "kodak_tmax_100": return 0.74;
            case "fujifilm_neopan_acros_100_ii": return 0.64;
            default: return 1.0;
        }
    }

    /** The wire's `grain_amount`: the slider times the stock's own amount,
     *  clamped to the engine's 0..2. */
    private double wireGrain(String stock) {
        return Math.max(0.0, Math.min(2.0, grainAmount * ownGrain(stock)));
    }

    /**
     * The slider and the engine run **opposite ways**, and getting that wrong
     * is what made the first version blow highlights when it was pulled to the
     * top.
     *
     * <p>`scene_latitude_highlight_room` is not a strength: it is how many
     * stops of headroom the highlight is allowed to keep before the roll-off
     * pins it. Bigger room, less compression, brighter top. Measured on a
     * +2 EV synthetic frame at knee 1.0: room 0.5 gives p95 195 and
     * saturation 37.6 %, room 3.0 gives p95 213 and 21.1 %, no stage at all
     * gives 217 and 17.2 %.
     *
     * <p>The slider is "how much protection", so 0 is off and 3 is the
     * tightest the curvature floor permits (0.5 stop). In between it runs
     * linearly through 3.0 stops of room.
     */
    /**
     * The frame length as the rebate layout must see it.
     *
     * <p>With a rebate on, the layout and the frame length have to describe the
     * same film: the gate says how long the frame is (135 half is 18 x 24, so
     * 24 mm), and the scale page says how big a grain should look (36 mm by
     * default). Sending the scale page's number while the gate is a different
     * size lets the two disagree -- the canvas is laid out for one frame length
     * and the edge print placed for another, which is how the film edge ends up
     * printed across the picture. With a rebate the gate decides.
     */
    private float wireFormatMm() {
        if (!overscanOn) return filmFormatMm;
        final float gate = gateMm(overscanFormat);
        return gate > 0f ? gate : filmFormatMm;
    }

    private float wireHighlightRoom() {
        if (highlightRoom <= 0.01f) return 0f;
        final float s = Math.max(0f, Math.min(3f, highlightRoom));
        return 3.0f - (s / 3.0f) * 2.5f;
    }

    /** The shadow side of RFC-023, same shape. Here "more" and "more room" run
     *  the same way: the toe is held open by this many stops. */
    private float wireShadowRoom() {
        if (shadowRoom <= 0.01f) return 0f;
        return Math.max(0f, Math.min(3f, shadowRoom));
    }

    /** Either side of RFC-023 asking for the node at all. */
    private boolean sceneLatitudeActive() {
        return highlightRoom > 0.01f || shadowRoom > 0.01f;
    }

    /** The manufacturer's edge print: the stock's own catalogue name. */
    private String edgeTextFor(String stock) {
        final int i = filmIds.indexOf(stock);
        final String name = (i >= 0 && i < filmLabels.size()) ? filmLabels.get(i) : "KODAK";
        return name.toUpperCase(java.util.Locale.US);
    }

    /** "'26 10 31" — a date back's format, from the frame's own EXIF date when
     *  the file has one, from today when it does not. */
    private String imprintDate() {
        if (dateText != null) return dateText;
        String text = null;
        try {
            if (sourceUri != null) {
                String v = null;
                try (InputStream in = getContentResolver().openInputStream(sourceUri)) {
                    if (in != null) {
                        v = new android.media.ExifInterface(in)
                                .getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL);
                    }
                }
                if (v != null && v.length() >= 10) {
                    text = "'" + v.substring(2, 4) + " " + Integer.parseInt(v.substring(5, 7))
                            + " " + Integer.parseInt(v.substring(8, 10));
                }
            }
        } catch (Throwable ignored) { }
        if (text == null) {
            final java.util.Calendar c = java.util.Calendar.getInstance();
            text = "'" + (c.get(java.util.Calendar.YEAR) % 100) + " "
                    + (c.get(java.util.Calendar.MONTH) + 1) + " "
                    + c.get(java.util.Calendar.DAY_OF_MONTH);
        }
        dateText = text;
        return dateText;
    }

    /** A 12 px square that fills when on: the page has no switch, and a filled
     *  square is the same vocabulary the tabs use. */
    private static class Square extends View {
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int onColor, offColor;
        private final float stroke;
        private boolean lit;

        Square(android.content.Context context, int onColor, int offColor) {
            super(context);
            this.onColor = onColor;
            this.offColor = offColor;
            stroke = Math.max(1f, getResources().getDisplayMetrics().density);
        }

        void setLit(boolean on) {
            lit = on;
            invalidate();
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            final float w = getWidth(), h = getHeight(), inset = stroke / 2f;
            if (lit) {
                paint.setStyle(android.graphics.Paint.Style.FILL);
                paint.setColor(onColor);
                canvas.drawRect(0, 0, w, h, paint);
            } else {
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setColor(offColor);
                canvas.drawRect(inset, inset, w - inset, h - inset, paint);
            }
        }
    }

    private View toggleRow(String labelText, Square mark, boolean initial,
                           java.util.function.Consumer<Boolean> onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(label(labelText, 8, UI_INK, tfHan), new LinearLayout.LayoutParams(0, -2, 1f));
        mark.setLit(initial);
        row.addView(mark, new LinearLayout.LayoutParams(dp(12), dp(12)));
        final boolean[] state = {initial};
        row.setOnClickListener(v -> {
            state[0] = !state[0];
            mark.setLit(state[0]);
            onChange.accept(state[0]);
        });
        return row;
    }

    /** .range-field: an 8 px head with the value in blue, then the 10 px bar. */
    private SeekBar rangeRow(LinearLayout parent, String name, final float lo, final float hi,
                             float initial, final java.util.function.Consumer<Float> apply) {
        return rangeRow(parent, name, null, lo, hi, initial, apply);
    }

    private SeekBar rangeRow(LinearLayout parent, String name, String hint,
                             final float lo, final float hi,
                             float initial, final java.util.function.Consumer<Float> apply) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.addView(label(name, 8, UI_INK, tfHan), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView value = label(String.format(java.util.Locale.US, "%.2f", initial), 8, UI_BLUE, tfBody);
        head.addView(value);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(-1, -2);
        headLp.bottomMargin = dp(2);
        row.addView(head, headLp);
        if (hint != null && !hint.isEmpty()) {
            TextView note = label(hint, 7.5f, UI_MUTED, tfHan);
            note.setMaxLines(1);
            note.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
            noteLp.bottomMargin = dp(2);
            row.addView(note, noteLp);
        }

        SeekBar bar = new ScrubBar(this);
        bar.setMax(1000);
        bar.setProgress(Math.round((initial - lo) / (hi - lo) * 1000f));
        styleRange(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                float v = lo + (hi - lo) * progress / 1000f;
                value.setText(String.format(java.util.Locale.US, "%.2f", v));
                apply.accept(v);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                // The value only lived in a field until here: `apply` assigns
                // it, nothing sent it. That is why dragging did nothing and
                // re-picking the stock did everything (onStockChanged sends the
                // whole set). Send on release: one JNI call per gesture, not
                // one per hundredth of a stop.
                applyParams();
                if (uiReady && session != null) {
                    showingOriginal = false;
                    setOriginalVisual(false);
                    renderImage();
                }
            }
        });
        // **32 dp, not 10.** A 10 dp-tall SeekBar is a 10 dp-tall touch target:
        // the thumb is 9 dp across, so most of a finger lands outside the view
        // and the drag never starts. The track still draws 2 px -- that is the
        // drawable's inset, not the view's height.
        // 44 dp, the smallest a touch target should be. The track still draws
        // 2 px; the height is the finger's, and ScrubBar maps the whole width
        // to the value so the thumb does not have to be caught.
        row.addView(bar, new LinearLayout.LayoutParams(-1, dp(44)));
        parent.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
        return bar;
    }

    /** 2 px track, square 9 px thumb: input[type=range] in the page. */
    private void styleRange(SeekBar bar) {
        final int thickness = dp(2);
        final int inset = (dp(10) - thickness) / 2;
        android.graphics.drawable.ShapeDrawable back = new android.graphics.drawable.ShapeDrawable(
                new android.graphics.drawable.shapes.RectShape());
        back.getPaint().setColor(UI_LINE);
        android.graphics.drawable.ShapeDrawable fill = new android.graphics.drawable.ShapeDrawable(
                new android.graphics.drawable.shapes.RectShape());
        fill.getPaint().setColor(UI_BLUE);
        android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                fill, android.view.Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{back, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        // The insets are what make the track 2 px: a ShapeDrawable alone would
        // paint its whole bounds.
        layers.setLayerInset(0, 0, inset, 0, inset);
        layers.setLayerInset(1, 0, inset, 0, inset);
        bar.setProgressDrawable(layers);

        android.graphics.drawable.ShapeDrawable thumb = new android.graphics.drawable.ShapeDrawable(
                new android.graphics.drawable.shapes.RectShape());
        thumb.getPaint().setColor(UI_BLUE);
        thumb.setIntrinsicWidth(dp(9));
        thumb.setIntrinsicHeight(dp(9));
        bar.setThumb(thumb);
        bar.setSplitTrack(false);
        // 32 dp tall view, 10 dp of drawable between 11 dp of vertical padding:
        // the track keeps its old 2 px look while the touch target is a finger
        // wide. Without the padding the inset above would have to be 15 dp and
        // the track would vanish into it.
        bar.setPadding(dp(5), dp(11), dp(5), dp(11));
    }

    /** The old four-step ribbon is gone; the render state is in the canvas foot. */
    private void setStep(int active) { }

    private void setTab(int active) {
        activeTabIndex = active;
        // Arriving at the frame page always starts from the two entry cards.
        if (active == 3) openEdgePage(-1);
        else if (edgePageIndex >= 0) openEdgePage(-1);
        for (int i = 0; i < panels.length; ++i) {
            panels[i].setVisibility(i == active ? View.VISIBLE : View.GONE);
            tabBars[i].setVisibility(i == active ? View.VISIBLE : View.INVISIBLE);
            if (tabLabels[i] != null) tabLabels[i].setTextColor(i == active ? UI_BLUE : UI_MUTED);
        }
    }

    /**
     * Rows at the top and bottom of a frame that carry no picture at all -- the
     * "two big black bars" report, measured rather than guessed. A render that
     * comes back letterboxed says so in the status line.
     */
    private static int[] blackBands(Bitmap b) {
        final int w = b.getWidth(), h = b.getHeight();
        final int[] row = new int[w];
        int top = 0, bottom = 0;
        for (int y = 0; y < h; ++y) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            boolean black = true;
            for (int x = 0; x < w && black; ++x) black = (row[x] & 0xFFFFFF) <= 0x060606;
            if (!black) { top = y; break; }
        }
        for (int y = h - 1; y >= 0; --y) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            boolean black = true;
            for (int x = 0; x < w && black; ++x) black = (row[x] & 0xFFFFFF) <= 0x060606;
            if (!black) { bottom = h - 1 - y; break; }
        }
        return new int[]{top, bottom};
    }

    /** The optics sliders only mean something once there is a frame to change. */
    private void setOpticsUnlocked(boolean unlocked) {
        opticsUnlocked = unlocked;
        if (opticsPanel == null) return;
        opticsPanel.setAlpha(unlocked ? 1f : 0.38f);
        if (knobBar != null) knobBar.setEnabled(unlocked);
        if (knobStrip != null) knobStrip.setEnabled(unlocked);
        refreshEdgeGroups();
    }

    /** Which of the three things on the stage is showing. */
    private void setStage(int mode, String name) {
        if (mode == 0) compare.clear();
        emptyState.setVisibility(mode == 0 ? View.VISIBLE : View.GONE);
        compare.setVisibility(mode == 1 ? View.VISIBLE : View.GONE);
        rawState.setVisibility(mode == 2 ? View.VISIBLE : View.GONE);
        if (mode == 0) {
            showingOriginal = false;
            setOriginalVisual(false);
        }
        if (name != null && !name.isEmpty()) {
            fileLabel.setText(name);
            rawName.setText(name);
        }
    }

    /**
     * The two doors, asked on every tap of 01.
     *
     * They are not the same door: the gallery's picker only shows what the
     * media store knows as an image, and a Sony .ARW -- or a HEIC a download
     * left untyped -- is not one. Long-press used to hold this and nobody
     * found it; a tap asking a question costs one tap and answers it.
     */
    private void showImportMenu() {
        showActionSheet("导入照片", "从相册或文件管理器选择", new String[]{
                "从相册选择（照片）",
                "从文件管理器选择（RAW / HEIC / 任意文件）"
        }, which -> {
            // A deliberate pick is a *new* photograph, not a chip coming back.
            // Cleared here so a chip whose file disappeared cannot leave the
            // flag set and make the next import silently skip the strip.
            reopeningFrame = false;
            suppressBudgetDialog = false;
            saveLookIntoFrames();
            if (which == 0) pickImage(); else pickRawFile();
        });
    }

    /** Export tiers come from the session and the device, not a fixed sensor. */
    /**
     * Hands the file that was just written to another app, bypassing the file
     * picker. Lightroom's own picker asks Android for pictures; on some ROMs the
     * picture list has no entry for TIFF, so a perfectly good .tif never
     * appears in it -- while an ACTION_SEND carrying "image/tiff" reaches it
     * fine. Same class of problem Adobe documents for CR3 on Android.
     */
    private void shareLastExport() {
        if (lastExportUri == null) {
            showInAppToast("还没有导出过文件");
            return;
        }
        try {
            final Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(lastExportMime == null ? "image/*" : lastExportMime);
            send.putExtra(Intent.EXTRA_STREAM, lastExportUri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "发送到"));
        } catch (Throwable t) {
            showErrorDialog("无法发送", describe(t));
        }
    }

    private void showExportDialog() {
        if (session == null) {
            showInAppToast("先导入一张照片");
            return;
        }
        final String full = (sourceWidth > 0 && sourceHeight > 0)
                ? "全尺寸 " + sourceWidth + "×" + sourceHeight : "全尺寸";
        final String fullNote = probe.isLimited()
                ? "（本机上限约 " + String.format(java.util.Locale.US, "%.1f", maxImportPixels() / 1e6)
                  + " MP，可能导出失败）"
                : "（原图）";
        // What the driver says it can take, as opposed to what this build
        // currently lets through. The two were only ever visible as one number,
        // and it was always the smaller -- so a phone reporting 2 GiB of
        // maxStorageBufferRange looked like it was capped at 15 MP for no
        // stated reason. Name both, and say which one is temporary.
        final String driverLine;
        if (probe.maxStorageBufferRange > 0) {
            final double driverMp = probe.maxStorageBufferRange / 12.0 * 0.80 / 1e6;
            final double budgetMp = maxImportPixels() / 1e6;
            driverLine = String.format(java.util.Locale.US,
                    "驱动单缓冲 %.0f MiB，可达 %.0f MP；当前版本按 %.1f MP 预算（分块引擎优化中）",
                    probe.maxStorageBufferRange / 1048576.0, driverMp, budgetMp);
        } else {
            driverLine = probe.valid ? "驱动未报单缓冲上限" : "设备信息读取失败";
        };
        // One sheet, six rows. It was two -- format, then size -- and the first
        // thing that did was hide "全尺寸（原图）" behind a tap that nobody
        // expects from a menu that used to end in it.
        final boolean limited = probe.isLimited();
        // "the gallery says it is corrupt" is a question this sheet can answer
        // before the file is written, not after: Android's picture viewer does
        // not read 16-bit TIFF at all, and the phone's Lightroom does.
        // DNG is shipped as a file for a desktop, and says so on the tin. The
        // phone's Lightroom refuses every linear DNG this writer produces --
        // four containers, two photometrics, 600x400 through 3000x2000, a
        // correct XYZ(D50) colour matrix, a camera-shaped IFD0 preview -- with
        // "failed to load the development version", which Adobe lists as a
        // known Android bug. Rather than let that read as our defect, the row
        // and the note name the destination and close the question.
        final String note = (limited
                ? "TIFF 是 16 位；"
                  + "导出后点导出旁边的「分享」，分享到 Lightroom 即可使用（手机相册多半不显示 TIFF）。JPEG 哪儿都能看。"
                : "TIFF 是 16 位；"
                  + "导出后点导出旁边的「分享」，分享到 Lightroom 即可使用（手机相册多半不显示 TIFF）。JPEG 是 8 位、哪儿都能看")
                + "\n" + driverLine
                + "\nDNG 只给电脑上的 Lightroom / ACR 用：手机版 Lr 打不开是已知问题，"
                  + "遇到报错不必反馈。";
        showActionSheet("导出照片", note, new String[]{
                "JPEG · 实时画质",
                "JPEG · 预览画质 · 长边 3400",
                "JPEG · 全尺寸（原图）",
                "TIFF 16 位 · 实时画质",
                "TIFF 16 位 · 预览画质 · 长边 3400",
                "TIFF 16 位 · 全尺寸（原图）",
                "DNG · 全尺寸（电脑用 · 手机版打不开）"
        }, which -> {
            if (which == 6) { exportImage("dng", "full"); return; }
            exportImage(which >= 3 ? "tiff" : "jpeg",
                        which % 3 == 0 ? "live" : which % 3 == 1 ? "preview" : "full");
        });
    }

    /** Reset from the old single control strip: delegate to the film page. */
    private void resetLook() { resetFilmPanel(); }

    private static class ArrowView extends View {
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float stroke;

        ArrowView(android.content.Context context, int color) {
            super(context);
            paint.setColor(color);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            stroke = Math.max(1f, getResources().getDisplayMetrics().density * 1.1f);
            paint.setStrokeWidth(stroke);
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            final float w = getWidth(), h = getHeight();
            final float pad = stroke;
            canvas.drawLine(pad, h - pad, w - pad, pad, paint);
            canvas.drawLine(w - pad, pad, w - pad * 2f - w * 0.34f, pad, paint);
            canvas.drawLine(w - pad, pad, w - pad, pad * 2f + h * 0.34f, paint);
        }
    }

    /**
     * The stage's picture, and the two ways to see what the film did: hold it
     * to see the frame before, or drag the middle line to keep both in view.
     *
     * Each side is drawn fit-inside, in its own frame: a rebate makes the
     * "after" a different shape from the raw, and cropping one to match the
     * other would misrepresent what is being compared.
     */
    private static class CompareView extends View {
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.RectF dst = new android.graphics.RectF();
        private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        private final float slop;
        private final float density;
        private Bitmap before, after;
        private boolean holding, dragging;
        private float split, downX, downY;

        private final Runnable press = new Runnable() {
            @Override public void run() {
                if (dragging || after == null || before == null) return;
                holding = true;
                invalidate();
            }
        };

        CompareView(android.content.Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            slop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
        }

        void setBefore(Bitmap b) { before = b; invalidate(); }
        void setAfter(Bitmap b) { after = b; invalidate(); }

        void clear() {
            before = null;
            after = null;
            holding = false;
            dragging = false;
            split = 0f;
            invalidate();
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            final Bitmap base = after != null ? after : before;
            if (base == null) return;
            drawFit(canvas, base);
            if (before == null || after == null) return;
            if (holding) {
                drawFit(canvas, before);
            } else if (split > 0f) {
                final float x = split * getWidth();
                canvas.save();
                canvas.clipRect(0f, 0f, x, getHeight());
                drawFit(canvas, before);
                canvas.restore();
                drawDivider(canvas, x);
            }
        }

        private void drawFit(android.graphics.Canvas canvas, Bitmap bmp) {
            final float vw = getWidth(), vh = getHeight();
            if (vw <= 0 || vh <= 0 || bmp.getWidth() == 0) return;
            final float scale = Math.min(vw / bmp.getWidth(), vh / bmp.getHeight());
            final float w = bmp.getWidth() * scale, h = bmp.getHeight() * scale;
            final float left = (vw - w) / 2f, top = (vh - h) / 2f;
            dst.set(left, top, left + w, top + h);
            canvas.drawBitmap(bmp, null, dst, paint);
        }

        private void drawDivider(android.graphics.Canvas canvas, float x) {
            final float r = 6f * density, cy = getHeight() / 2f;
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(0xB3F1F1EE);
            canvas.drawRect(x - 0.5f, 0f, x + 0.5f, getHeight(), paint);
            paint.setColor(0xFFF1F1EE);
            canvas.drawRect(x - r, cy - r, x + r, cy + r, paint);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, density));
            paint.setColor(0xFF1C2AA5);
            canvas.drawRect(x - r, cy - r, x + r, cy + r, paint);
            paint.setStyle(android.graphics.Paint.Style.FILL);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            if (after == null || before == null) return false;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    dragging = false;
                    holding = false;
                    handler.postDelayed(press, android.view.ViewConfiguration.getLongPressTimeout());
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (holding) return true;
                    if (!dragging) {
                        if (Math.abs(e.getX() - downX) < slop && Math.abs(e.getY() - downY) < slop) return true;
                        dragging = true;
                        handler.removeCallbacks(press);
                    }
                    split = Math.max(0.02f, Math.min(0.98f, e.getX() / Math.max(1, getWidth())));
                    invalidate();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    handler.removeCallbacks(press);
                    if (holding) {
                        holding = false;
                        invalidate();
                    } else if (!dragging && split > 0f) {
                        // A tap closes it: the divider is a gesture, not a mode.
                        split = 0f;
                        invalidate();
                    }
                    dragging = false;
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(press);
                    holding = false;
                    dragging = false;
                    invalidate();
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }
    }

    /** .frame-guides: a 9 % inset rule, a centre cross, four corner marks. */
    private static class GuideView extends View {
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int mark;

        GuideView(android.content.Context context, int markLength) {
            super(context);
            mark = markLength;
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            final int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            final float dx = w * 0.09f, dy = h * 0.09f;
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            paint.setColor(0x26FFFFFF);
            canvas.drawRect(dx, dy, w - dx, h - dy, paint);
            paint.setColor(0x14FFFFFF);
            canvas.drawLine(w / 2f, dy, w / 2f, h - dy, paint);
            canvas.drawLine(dx, h / 2f, w - dx, h / 2f, paint);
            paint.setColor(0xA6FFFFFF);
            final float L = mark;
            canvas.drawLine(dx, dy, dx + L, dy, paint);
            canvas.drawLine(dx, dy, dx, dy + L, paint);
            canvas.drawLine(w - dx, dy, w - dx - L, dy, paint);
            canvas.drawLine(w - dx, dy, w - dx, dy + L, paint);
            canvas.drawLine(dx, h - dy, dx + L, h - dy, paint);
            canvas.drawLine(dx, h - dy, dx, h - dy - L, paint);
            canvas.drawLine(w - dx, h - dy, w - dx - L, h - dy, paint);
            canvas.drawLine(w - dx, h - dy, w - dx, h - dy - L, paint);
        }
    }

    /**
     * A frame this device cannot take at its own size: **asked about, not
     * quietly shrunk**.
     *
     * The import used to scale silently (a toast, on the RAW path only), which
     * reads as "the app ate my resolution" the next time the user exports a
     * 4700-pixel frame from a 45 MP negative. The two numbers are the whole
     * story: what the file is, and what this phone renders in one go.
     */
    private void showImportBudgetDialog(final Uri uri, final int w, final int h, final int capPixels) {
        final double fit = importScale(w, h, capPixels);
        final int sw = Math.max(1, (int) Math.round(w * fit));
        final int sh = Math.max(1, (int) Math.round(h * fit));
        final String budgetMessage = "原图 " + w + "×" + h + "（" + megapixels(w, h) + " MP），这台机器一次能渲染约 "
                + String.format(java.util.Locale.US, "%.1f", capPixels / 1e6) + " MP"
                + (overscanOn ? "（已按片边预留画布，片边会把画布再撑大约 1.5 倍）" : "") + "。"
                + "\n\n按 " + sw + "×" + sh + " 导入一定出得来；按原尺寸可能在本机渲染失败（E-1001）。";
        showActionSheet("这张图超过本机预算", budgetMessage, new String[]{
                "按 " + sw + "×" + sh + " 导入", "按原尺寸", "取消"
        }, which -> {
            if (which == 0) {
                importBudgetAccepted = true;
                loadImage(uri);
            } else if (which == 1) {
                importAtNativeSize = true;
                loadImage(uri);
            }
        });
    }

    /**
     * The scale the import will actually apply, so the dialog can name the size
     * it is about to deliver -- the same three ceilings, in the same order, as
     * `loadImage` below.
     */
    private double importScale(int w, int h, int capPixels) {
        final long area = (long) w * (long) h;
        double k = 1.0;
        if (area > capPixels) k = Math.sqrt((double) capPixels / (double) area);
        final int longEdge = Math.max(w, h);
        if (longEdge > IMPORT_LONG_EDGE) k = Math.min(k, (double) IMPORT_LONG_EDGE / longEdge);
        final long availBytes = availableMemoryBytes();
        if (availBytes > 0) {
            final long budget = Math.max(1L << 20, availBytes - (128L << 20));
            final double wantBytes = (double) area * 12.0;
            if (wantBytes > budget) k = Math.min(k, Math.sqrt((double) budget / wantBytes));
        }
        return Math.min(k, 1.0);
    }

    private static String megapixels(int w, int h) {
        return String.format(java.util.Locale.US, "%.1f", (double) w * (double) h / 1e6);
    }

    /** The file's own size, from the header, before anything is decoded. */
    private int[] sniffImageSize(Uri uri) {
        try {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream head = getContentResolver().openInputStream(uri)) {
                if (head != null) android.graphics.BitmapFactory.decodeStream(head, null, bounds);
            }
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                return new int[]{bounds.outWidth, bounds.outHeight};
            }
        } catch (Throwable t) {
            try { DebugSink.text("decode", "sniff " + t); } catch (Throwable ignored) { }
        }
        return null;
    }

    /** Long-press on the title: version, update check, and where the code lives. */
    /**
     * The probe, re-read if it never landed.
     *
     * `ensureEngine()` reads it exactly once, immediately after the engine is
     * created -- and `engine` is assigned first, so a throw in that one call
     * leaves `probe` at `parse("{}")` for the life of the process while every
     * render works perfectly. The device then reads as "设备信息读取失败" and,
     * worse, `maxImportPixels()` loses the one number it is derived from.
     */
    /**
     * Ask the driver whether it enforces the range it reports.
     *
     * The canary is the only way to tell a device that merely reports 128 MiB
     * from one that enforces it: past the limit the writes are dropped and no
     * error comes back, so a soft report and a hard wall are identical from
     * the API. It runs once, on its own thread, as soon as the engine exists --
     * before the first import asks for a budget.
     *
     * The import budget stays on the reported value until the answer lands,
     * which is the conservative side: someone who imports immediately is
     * never handed a larger frame than the measurement would have allowed.
     */
    private void startCanaryProbe() {
        if (canaryStarted || engine == null) return;
        canaryStarted = true;
        final SpektraLabEngine e = engine;
        new Thread(() -> {
            String json = null;
            try {
                synchronized (renderMutex) { json = e.probeCanary(DeviceProbe.CANARY_NEED_BYTES); }
            } catch (Throwable t) {
                try { DebugSink.text("canary", "threw: " + describe(t)); } catch (Throwable ignored) { }
            }
            final String result = json;
            final long budget = maxImportPixels();
            runOnUiThread(() -> {
                try {
                    if (result != null) probe.applyCanary(result);
                    DebugSink.text("canary", (result == null ? "(no answer)" : result)
                            + "\n" + probe.describe());
                } catch (Throwable ignored) { }
                try { DeviceReport.submit(this, probe, budget); } catch (Throwable ignored) { }
            });
        }, "canary-probe").start();
    }

    private DeviceProbe probeNow() {
        if (!probe.valid && engine == null) {
            // "关于" can be opened before anything is imported, which is the
            // usual way this reads as a failure: there is no engine yet, so
            // there is nothing to ask. Build it.
            try { ensureEngine(); }
            catch (Throwable t) {
                try { DebugSink.text("device-profile", "engine create failed: " + describe(t)); }
                catch (Throwable ignored) { }
            }
        }
        if (!probe.valid && engine != null) {
            try {
                final String json = engine.deviceProfile();
                probe = DeviceProbe.parse(json);
                try { DebugSink.text("device-profile", "re-read " + probe.describe() + "\n" + json); }
                catch (Throwable ignored) { }
            } catch (Throwable t) {
                try { DebugSink.text("device-profile", "re-read failed: " + describe(t)); }
                catch (Throwable ignored) { }
            }
        }
        return probe;
    }

    private void showAbout() {
        probeNow();
        final String version = UpdateCheck.versionName(this) + "（" + UpdateCheck.versionCode(this) + "）";
        final String details = probe.describe()
                + "\n\n引擎：SpektraLab / spektrafilm（GPL-3.0，Andrea Volpato 的光谱胶片模型）"
                + "\n移植：github.com/SAINTTaiYi/SpektraLab-Android";
        // The report switch lives here rather than in a settings screen: it is
        // one line of one dialog, and it is the only thing this app sends that
        // the user did not ask for. The label carries the state, because the
        // sheet's note cannot scroll and a longer paragraph would be clipped.
        final boolean reporting = DeviceReport.enabled(this);
        showActionSheet("SpektraLab Android " + version, details, new String[]{
                "检查更新",
                reporting ? "驱动报告：开（每周一次，只报机型和 GPU）" : "驱动报告：关",
                "反馈群（QQ 1134320449）", "打开 GitHub", "关闭"
        }, which -> {
            if (which == 0) {
                UpdateCheck.check(this, true);
            } else if (which == 1) {
                DeviceReport.setEnabled(this, !reporting);
                showAbout();
            } else if (which == 2) {
                showFeedbackGroup(false);
            } else if (which == 3) {
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/SAINTTaiYi/SpektraLab-Android/releases/latest")));
                } catch (Throwable ignored) { }
            }
        });
    }

    /** Reads assets/resources/stock_catalog.json (generated from the engine's profiles). */
    private void loadStockCatalog() {
        try (InputStream in = getAssets().open("resources/stock_catalog.json")) {
            byte[] buf = new byte[in.available()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            org.json.JSONObject root = new org.json.JSONObject(new String(buf, java.nio.charset.StandardCharsets.UTF_8));
            filmTargetPrints.clear();
            readStocks(root.optJSONArray("films"), filmIds, filmLabels, filmTypes, filmTargetPrints);
            readStocks(root.optJSONArray("prints"), printIds, printLabels, null, null);
        } catch (Throwable t) {
            android.util.Log.w("SpektraLab", "stock catalog: " + t);
        }
        if (filmIds.isEmpty()) { filmIds.add("kodak_portra_400"); filmLabels.add("Kodak Portra 400"); }
        if (printIds.isEmpty()) { printIds.add("kodak_portra_endura"); printLabels.add("Kodak Portra Endura"); }

        rebuildBrandRow();
        rebuildFilmCards();
        refreshPaperLabel();
    }

    private static void readStocks(org.json.JSONArray array, List<String> ids, List<String> labels,
                                   List<String> types, java.util.Map<String, String> targets) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            org.json.JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id");
            if (id.isEmpty()) continue;
            String name = o.optString("name", id);
            StringBuilder label = new StringBuilder(name);
            String type = o.optString("type", "");
            String use = o.optString("use", "");
            if ("positive".equals(type)) label.append(" · 反转");
            // A silver stock is a `negative` in this catalogue like any other,
            // so without this the four black-and-white films wear "· 负片"
            // and nothing says which ones they are. "Black and white" is the
            // only label a user looks for them by.
            else if (isMonochromeStock(id)) label.append(" · 黑白");
            else if ("negative".equals(type)) label.append(" · 负片");
            if ("cine".equals(use)) label.append(" · 电影");
            ids.add(id);
            labels.add(label.toString());
            if (types != null) types.add(type);
            if (targets != null) {
                final String target = o.optString("targetPrint", "");
                if (!target.isEmpty()) targets.put(id, target);
            }
        }
    }

    private int getStatusBarHeight() {
        int result = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) result = getResources().getDimensionPixelSize(resourceId);
        return result;
    }

    /**
     * Tap: the gallery, which is where phone photos and JPEGs are.
     *
     * Three intents, in the order the user expects them, and the first one this
     * ROM can actually resolve wins: the system Photo Picker (13+), then
     * `GET_CONTENT image/*` (every Android before 13), then the document
     * picker (every Android, period).
     *
     * The chain is not belt-and-braces. A launch that cannot be resolved throws
     * `ActivityNotFoundException`, and an uncaught throw inside a click handler
     * does not show an error -- it kills the process. That is exactly the
     * report "一选导入就自动退回桌面了" on a Honor/HarmonyOS phone: no message,
     * because there was nothing left alive to show one. Those ROMs ship without
     * the AOSP Photo Picker activity (it lives in the MediaProvider module, and
     * an HMS build does not have it) while the `ACTION_PICK_IMAGES` intent
     * still resolves to the chooser's fallback -- so the check has to be at the
     * launch, and it has to have somewhere to go next.
     */
    private void pickImage() {
        java.util.List<Intent> chain = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            chain.add(new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES));
        }
        chain.add(new Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"));
        chain.add(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"));
        if (!launchFirstResolvable(chain, 1)) {
            showInAppToast("本机没有可用的图片选择器；请点「导入」→「从文件管理器选择」");
        }
    }

    /**
     * Launch the first intent this ROM resolves, and never let the launch itself
     * kill the process. False when none of them is available.
     */
    private boolean launchFirstResolvable(java.util.List<Intent> chain, int requestCode) {
        for (Intent intent : chain) {
            try {
                if (intent.resolveActivity(getPackageManager()) == null) {
                    try { DebugSink.text("picker", "unresolvable " + intent.getAction()); }
                    catch (Throwable ignored) { }
                    continue;
                }
                // The grant has to be asked for as persistable *before* the
                // picker runs; `onActivityResult` then either keeps it or
                // copies the file. Without this flag there is nothing to take.
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, requestCode);
                try { DebugSink.text("picker", "launched " + intent.getAction()); }
                catch (Throwable ignored) { }
                return true;
            } catch (Throwable t) {
                // Resolvable and still refused: a provider that vanished
                // between the check and the launch, a SecurityException, or a
                // picker that throws from its own onCreate. Next one.
                try { DebugSink.text("picker", intent.getAction() + " failed: " + t); }
                catch (Throwable ignored) { }
            }
        }
        return false;
    }

    /**
     * Documents, for RAW. Sony's .ARW and several other RAW containers are
     * typed application/octet-stream by the storage layer, so a picker filtered
     * to image/* hides them completely -- which the user reads, correctly, as
     * "this app cannot import RAW". Asking for any file fixes that, but Android
     * then routes to Files rather than Photos, which is why it is its own
     * entry instead of replacing the gallery.
     *
     * **The same entry is the answer for a HEIC that the gallery will not
     * show.** The MIME list below names the HEIF types explicitly as well as
     * `image/*`, because several storage layers do not map `.heic` to an image
     * type at all -- a downloaded one then appears nowhere in a picker filtered
     * to images, which is what "下载后没法导入" describes.
     */
    private void pickRawFile() {
        final String[] types = {
                "image/*",
                "image/heic", "image/heif", "image/x-heic", "image/x-heif", "image/x-cfif",
                "application/octet-stream",
                "image/x-sony-arw", "image/x-canon-cr2", "image/x-canon-cr3",
                "image/x-nikon-nef", "image/x-adobe-dng", "image/x-panasonic-rw2",
                "image/x-panasonic-raw", "image/x-olympus-orf", "image/x-fuji-raf",
                "image/x-pentax-pef", "image/x-samsung-srw", "image/x-sigma-x3f"
        };
        java.util.List<Intent> chain = new ArrayList<>();
        Intent documents = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        documents.addCategory(Intent.CATEGORY_OPENABLE);
        documents.setType("*/*");
        documents.putExtra(Intent.EXTRA_MIME_TYPES, types);
        chain.add(documents);
        Intent content = new Intent(Intent.ACTION_GET_CONTENT);
        content.addCategory(Intent.CATEGORY_OPENABLE);
        content.setType("*/*");
        content.putExtra(Intent.EXTRA_MIME_TYPES, types);
        chain.add(content);
        if (!launchFirstResolvable(chain, 1)) {
            showInAppToast("本机没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 1) return;
        if (resultCode != RESULT_OK) return;          // the user cancelled
        // RESULT_OK with no URI happens on ROMs whose picker cannot hand back a
        // stream for the item (a cloud-only photo, a broken provider). Doing
        // nothing there is what "下载后没法导入" looks like from the outside: the
        // tap simply has no effect and nothing is said.
        Uri uri = data == null ? null : data.getData();
        if (uri == null) {
            try { DebugSink.text("picker", "RESULT_OK with no data"); } catch (Throwable ignored) { }
            showInAppToast("没有拿到图片（选择器返回空）。请点「导入」→「从文件管理器选择」再试一次");
            return;
        }
        importPicked(uri);
    }

    /**
     * Take the picker's grant, or make one of our own.
     *
     * <p>A `content://` URI from a picker is a **grant**, not a path, and the
     * grant is usually for this process only. ColorOS's file manager
     * (`com.android.fileexplorer.provider.FileExplorerFileProvider`) refuses
     * to make it persistable at all, so the restore-from-background path --
     * new in 1.4.0g -- reopened a URI the system no longer honours and
     * surfaced as `SecurityException` under [E-9000]. Copying the bytes into
     * `filesDir/import/` is the only thing that survives a restart no matter
     * which provider the user went through.
     */
    private void importPicked(Uri uri) {
        boolean persisted = false;
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            persisted = true;
        } catch (Throwable ignored) {
            // The provider declined. Expected for GET_CONTENT and for the
            // ColorOS file manager; not an error the user should see.
        }
        if (persisted) { loadImage(uri); return; }

        showInAppToast("正在把照片复制到应用目录…");
        new Thread(() -> {
            final Uri copy = privateCopy(uri);
            runOnUiThread(() -> {
                if (copy == null) {
                    showErrorDialog("导入失败", "读取或复制这个文件失败了。请换「从相册选择」再试一次。");
                    return;
                }
                loadImage(copy);
            });
        }, "import-copy").start();
    }

    /** A copy of the picked file inside the app's own storage. Null on any
     *  failure; the caller reports it rather than importing a URI that will
     *  not open next time. */
    private Uri privateCopy(Uri src) {
        try {
            final String name = getFileName(src);
            final java.io.File dir = new java.io.File(getFilesDir(), "import");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            final java.io.File dst = new java.io.File(dir,
                    System.currentTimeMillis() + "_" + (name == null ? "photo" : name));
            try (InputStream in = getContentResolver().openInputStream(src);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                if (in == null) return null;
                final byte[] buf = new byte[1 << 16];
                int read;
                while ((read = in.read(buf)) > 0) out.write(buf, 0, read);
            }
            try { DebugSink.text("import", "copied " + name + " -> " + dst.getName()
                    + " (" + dst.length() + " bytes)"); } catch (Throwable ignored) { }
            return Uri.fromFile(dst);
        } catch (Throwable t) {
            try { DebugSink.text("import", "copy failed: " + t); } catch (Throwable ignored) { }
            return null;
        }
    }

    /** Can this URI still be opened? A restored frame's grant may be gone. */
    private boolean canReadUri(Uri uri) {
        if (uri == null) return false;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            return in != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private void loadImage(Uri uri) {
        // A new photograph starts at the two entry cards, never on a page
        // whose switch belonged to the frame before it.
        if (edgePageIndex >= 0) openEdgePage(-1);
        // A frame the strip remembers but the system will not open any more:
        // say so in one sentence instead of letting a SecurityException be
        // classified as [E-9000] 未归类.
        if (!canReadUri(uri)) {
            showErrorDialog("照片已不可访问",
                    "这张照片的读取权限已经失效——系统收回了文件选择器给的临时授权。\n\n"
                    + "请点「导入」重新选择这张照片。新版本导入时会把照片复制到应用自己的目录，"
                    + "之后再切到别的软件回来就不会丢。");
            return;
        }
        sourceUri = uri;
        // The frame's own look, stated before any session is built: a chip
        // returns to a photograph *and* to what was done to it; a pick from ＋
        // starts with no film at all.
        if (reopeningFrame) {
            if (currentFrame >= 0 && currentFrame < frames.size())
                applyLook(frames.get(currentFrame).look);
        } else {
            applyLook(defaultLook());
        }
        try {
            // Check if this is a RAW file by extension
            String fileName = getFileName(uri);
            final String lower = fileName == null ? "" : fileName.toLowerCase();
            isRawFile = lower.endsWith(".rw2") || lower.endsWith(".raw")
                    || lower.endsWith(".dng") || lower.endsWith(".arw")
                    || lower.endsWith(".nef") || lower.endsWith(".cr2")
                    || lower.endsWith(".cr3") || lower.endsWith(".orf")
                    || lower.endsWith(".raf") || lower.endsWith(".pef")
                    || lower.endsWith(".srw") || lower.endsWith(".x3f")
                    || lower.endsWith(".rwl") || lower.endsWith(".3fr")
                    || lower.endsWith(".mrw") || lower.endsWith(".erf");
            // A RAW container we know of but cannot open still beats decoding
            // its bytes as a JPEG and reporting a mystery.
            final String[] knownButUnsupported = {".heic.raw", ".iiq", ".mos"};
            for (String ext : knownButUnsupported) {
                if (lower.endsWith(ext)) {
                    showInAppToast("[E-1107] 暂不支持 " + ext + " 格式");
                    return;
                }
            }
            
            if (isRawFile) {
                loadRawImage(uri);
                return;
            }

            // **Ask before shrinking.** Importing at the negative's own size is
            // the point of a 45 MP camera; a silent downscale is the app making
            // that decision on the user's behalf, and it shows up later as an
            // export that is smaller than the file. The header gives the size
            // without decoding anything, so the question is free.
            if (!importAtNativeSize && !importBudgetAccepted && !suppressBudgetDialog) {
                final int[] full = sniffImageSize(uri);
                if (full != null) {
                    final long area = (long) full[0] * (long) full[1];
                    final int cap = maxImportPixels();
                    if (area > cap || Math.max(full[0], full[1]) > IMPORT_LONG_EDGE) {
                        showImportBudgetDialog(uri, full[0], full[1], cap);
                        return;
                    }
                }
            }
            importBudgetAccepted = false;

            // Retire the shelf queue *before* the session it is holding goes
            // away. Its generation is the only thing that makes a card which
            // is already rendering drop its bitmap instead of filing the old
            // photograph's picture under this photograph's films: without this
            // bump the queue wrote last frame's thumbnails into `filmThumbs`,
            // every card then looked right in the map (`containsKey`), and the
            // new queue skipped them forever -- cards showing the previous
            // photo until each one was tapped.
            stopPrefetch();
            // Free the previous frame before decoding: a 20 MP pick wants a
            // ~200 MB direct buffer and the old session still holds its planes
            // (this OOM was 241 MB with 201 MB left in the heap).
            //
            // **Under the render lock.** `stopPrefetch` only bumps a generation
            // -- it does not wait for the thread, which may be inside
            // `renderBegin` on this very session. Closing it from under the GPU
            // is the SIGSEGV this device logs as `vkCmdCopyBuffer` against a
            // destroyed buffer, and it is what tapping an earlier thumbnail
            // while the shelf was still drawing looked like.
            synchronized (renderMutex) {
                if (session != null) { session.close(); session = null; }
            }
            lastRenderedBitmap = null;
            setOpticsUnlocked(false);
            dateText = null;
            setStage(0, null);
            sourceBitmap = null;
            sourcePixels = null;
            filmThumbs.clear();
            // The canvas is not a control. `setOriginalVisual` only paints the
            // 原图 button; what CompareView holds is `after` (the last render)
            // and it survives a new session. That was the whole "I changed the
            // photo and the canvas still shows the old one" report: the frame
            // was never dropped, so the next render was the first thing to
            // replace it.
            if (compare != null) compare.clear();
            System.gc();

            // Cap by area, the way the RAW path does. inSampleSize only halves,
            // so one exact scale follows if the sample still overshoots.
            final boolean nativeSize = importAtNativeSize;
            importAtNativeSize = false;                       // one import only
            // A cap the last render failure handed us (see offerSmallerImport):
            // one import's worth, then back to the device's own answer.
            final int forcedCap = pendingCapPixels;
            pendingCapPixels = 0;
            e1001Offered = false;
            final int capPixels = nativeSize ? Integer.MAX_VALUE
                    : (forcedCap > 0 ? forcedCap : maxImportPixels());
            final int edgeCap = nativeSize ? Integer.MAX_VALUE : IMPORT_LONG_EDGE;
            sourceBitmap = decodeImport(uri, capPixels, edgeCap);
            if (sourceBitmap == null) {
                // The old message here was "无法解码图片", which gave the user
                // nothing to act on. A HEIC/HEIF this phone's decoder refuses is
                // the common case, so the dialog carries the way out instead of
                // describing it.
                showActionSheet("[E-1107] 这张图本机解不开",
                        "多半是 HEIC/HEIF 或 10-bit 的图（三星、iPhone 默认存这种）。\n"
                                + "换「从文件选择」再试一次，读不到的话请先另存为 JPEG。",
                        new String[]{"从文件选择", "取消"}, which -> {
                            if (which == 0) pickRawFile();
                        });
                return;
            }
            sourceBitmap = applyExifRotation(uri, sourceBitmap);
            final long area = (long) sourceBitmap.getWidth() * sourceBitmap.getHeight();
            final int longEdge = Math.max(sourceBitmap.getWidth(), sourceBitmap.getHeight());
            // Three ceilings in three units, and the strictest wins: area is
            // quadratic in the scale, the long edge is linear, and the third is
            // what this phone can actually hold -- the import's float buffer is
            // 12 B/px, which is 240 MB at 20 MP and the largest single
            // allocation the app makes. Asking before taking it is what turns
            // an OutOfMemoryError (an Error, so the old `catch (Exception)`
            // never saw it and the process died) into a smaller import.
            double k = 1.0;
            if (area > capPixels) k = Math.min(k, Math.sqrt((double) capPixels / (double) area));
            if (longEdge > edgeCap) k = Math.min(k, (double) edgeCap / longEdge);
            final long availBytes = availableMemoryBytes();
            if (availBytes > 0) {
                final long budget = Math.max(1L << 20, availBytes - (128L << 20));
                final double wantBytes = (double) area * 12.0;
                if (wantBytes > budget) k = Math.min(k, Math.sqrt((double) budget / wantBytes));
            }
            if (k < 1.0) {
                final int nw = Math.max(1, (int) Math.round(sourceBitmap.getWidth() * k));
                final int nh = Math.max(1, (int) Math.round(sourceBitmap.getHeight() * k));
                Bitmap scaled = Bitmap.createScaledBitmap(sourceBitmap, nw, nh, true);
                if (scaled != sourceBitmap) sourceBitmap.recycle();
                sourceBitmap = scaled;
                try { DebugSink.text("import-scale", "k=" + k + " -> " + nw + "x" + nh
                        + " avail=" + availBytes); } catch (Throwable ignored) { }
            }
            sourceWidth = sourceBitmap.getWidth();
            sourceHeight = sourceBitmap.getHeight();
            refreshEdgeFormats();

            // Convert a row at a time: a full int[] of 20 MP would be another
            // 80 MB on the Java heap next to the direct buffer.
            rawFilePath = null;
            sourcePixels = ByteBuffer.allocateDirect(sourceWidth * sourceHeight * 3 * 4)
                .order(ByteOrder.nativeOrder());
            final int[] rowPixels = new int[sourceWidth];
            for (int y = 0; y < sourceHeight; y++) {
                sourceBitmap.getPixels(rowPixels, 0, sourceWidth, 0, y, sourceWidth, 1);
                for (int px : rowPixels) {
                    sourcePixels.putFloat(((px >> 16) & 0xFF) / 255.0f);
                    sourcePixels.putFloat(((px >> 8) & 0xFF) / 255.0f);
                    sourcePixels.putFloat((px & 0xFF) / 255.0f);
                }
            }
            sourcePixels.rewind();
            
            final int pw = Math.max(1, Math.round(sourceWidth * Math.min(1f, 1024f / Math.max(sourceWidth, sourceHeight))));
            final int ph = Math.max(1, Math.round(sourceHeight * Math.min(1f, 1024f / Math.max(sourceWidth, sourceHeight))));
            beforeBitmap = Bitmap.createScaledBitmap(sourceBitmap, pw, ph, true);
            compare.setBefore(beforeBitmap);
            sourceBitmap.recycle();
            sourceBitmap = null;
            statusText.setText("Local preview");
            setStage(1, getFileName(uri));
            rememberFrame(uri);
            rebuildFilmCards();
            setStep(1);
            
            // Create engine and session
            ensureEngine();
            synchronized (renderMutex) {
                if (session != null) { session.close(); session = null; }
            }
            session = engine.openSession(sourcePixels, sourceWidth, sourceHeight, 3);
            applySessionParams();
            lastImportError = null;
            // An import is not a look. The frame lands unrendered with no film
            // chosen; the first render happens when the user picks a stock.
            showingOriginal = true;
            lastRenderedBitmap = null;
            setOriginalVisual(true);
            rebuildFilmCards();
            if (filmStock != null) {
                // A chip came back with a look: develop it again rather than
                // making the user re-pick a film that was never unpicked.
                showingOriginal = false;
                updateFilmSelection();
                renderImage();
            } else {
                statusText.setText("未渲染 · 选择胶片开始");
                startPrefetch();
            }
        } catch (Throwable t) {
            lastImportError = describeImportFailure(t);
            showErrorDialog("导入失败", lastImportError);
            try { DebugSink.text("import-failed", lastImportError); } catch (Throwable ignored) { }
        }
    }

    /**
     * The long edge accepted without asking.
     *
     * It was 4700, from the days when a dispatch past 16,776,960 threads lost
     * its top rows: the long edge was the only number that kept a 3:2 frame
     * under that wall. The wall is gone (chunked dispatches, and every plane
     * is row-banded now), so the edge no longer has to answer for the area --
     * `maxImportPixels()` does, and it is derived from this device's binding
     * range and the memory the phone has free right now.
     *
     * 8000 is where a frame stops being plausible rather than where it stops
     * working: measured on the SM8735 here, 7500 x 5000 (37.5 MP) renders in
     * 32 s with a 2.7 GB pool, 8000 x 5333 (42.7 MP) in 57 s, and 8192 x 5464
     * (44.8 MP) loses the device inside `spk_iir_df_acc`. A 45 MP camera raw
     * is 8256 x 5504, so this lands it at 8000 x 5333 rather than at 4700.
     */
    private static final int IMPORT_LONG_EDGE = 8000;
    /** Used when the profile cannot be read at all. */
    private static final int IMPORT_PIXEL_FALLBACK = 16490040;

    private SpektraLabEngine ensureEngine() throws Exception {
        if (engine == null) {
            vkLogFile().delete();
            engine = SpektraLabEngine.create(getResourcesDir(), vkLogFile().getAbsolutePath());
            // **The probe comes first, and nothing may stop it.** It used to sit
            // after `uploadCapabilities()`, so a throw in any of the uploads left
            // `probe` at its `parse("{}")` default -- `? · 无单缓冲上限` -- and the
            // next call skipped this whole block because `engine` was already
            // set. That is a device reporting 128 MiB letting a 14.8 MP frame
            // in, and the render dying on a binding range.
            probe = DeviceProbe.parse(engine.deviceProfile());
            try { DebugSink.text("device-profile", probe.describe() + "\n" + engine.deviceProfile()); }
            catch (Throwable ignored) { }
            startCanaryProbe();
            try { uploadCapabilities(); } catch (Throwable ignored) { }
        }
        return engine;
    }

    /**
     * Pixels this device will actually render.
     *
     * Every kernel binds a whole plane, a plane is 12 bytes per pixel, and a
     * binding may not exceed the GPU's maxStorageBufferRange: 128 MiB holds
     * 11 MP, 256 MiB holds 22 MP. Importing past that does not degrade, it
     * fails -- "spk_sep_fir_acc: buffer exceeds maxStorageBufferRange" -- which
     * is what a 50 MP DNG did on a phone reporting 128 MiB. Ask the device
     * rather than guess a long edge, and keep the old workgroup cap.
     */
    private long availableMemoryBytes() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.availMem;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Pixels of **source** this device will render, from three ceilings.
     *
     * The binding ceiling comes from the engine itself (`spk_max_plane_bytes`,
     * a direct call) and only falls back to the parsed profile -- because the
     * profile is JSON and the JSON can fail to parse, and a failed parse used to
     * read as "no limit". A device whose driver reports the Vulkan minimum
     * (128 MiB = 11.2 MP) then accepted a 14.8 MP import and died at render.
     *
     * `withRebate` divides by the film canvas's growth: the ceiling applies to
     * the **canvas**, not to the source, and the canvas is wider than the frame
     * by the film's own width and by the margin along it. Measured from the
     * engine's gate table (`overscan.cpp`, kFormats) and confirmed against real
     * renders:
     *
     *     135        1.51   (4700x3133 -> 4914x4538)
     *     135 half   1.53
     *     120 645    1.18   (1952x2604 -> 2116x2833, measured)
     *     120 6x9    1.13
     *     135 XPan   1.50
     *
     * 1.6 covers the worst of them with margin. A frame that lands a little
     * small is a picture; one that lands over the limit is a dead end.
     */
    private int maxImportPixels(boolean withRebate) {
        try { ensureEngine(); } catch (Throwable ignored) { }
        probeNow();
        long byBinding = Long.MAX_VALUE;
        try {
            final int byNative = engine == null ? 0 : engine.maxFramePixels();
            if (byNative > 0) byBinding = byNative;
        } catch (Throwable ignored) { }
        if (byBinding == Long.MAX_VALUE) byBinding = probe.maxPixelsByBinding();
        final long byMemory = DeviceProbe.maxPixelsByMemory(availableMemoryBytes());
        // The hard cap is measured, not chosen: 42.7 MP renders here, 44.8 MP
        // takes the device down, and the pool's peak is 6.3 planes (76 B/px).
        long px = Math.min(Math.min(byBinding, byMemory), DeviceProbe.HARD_PIXEL_CAP);
        if (withRebate) px = (long) (px / 1.6);
        return (int) Math.min(Math.max(px, 1_000_000L), Integer.MAX_VALUE);
    }

    private int maxImportPixels() {
        return maxImportPixels(overscanOn);
    }

    private void loadRawImage(Uri uri) {
        try {
            String filePath = getFilePathFromUri(uri);
            rawFilePath = filePath;
            if (filePath == null) {
                showInAppToast("无法获取RAW文件路径");
                return;
            }
            
            statusText.setText("Decoding RAW…");
            // **The same three things the JPEG path does, for the same three
            // reasons** — and this path had none of them, which is why a RAW
            // import kept the previous photograph everywhere except the little
            // source thumbnail:
            //   · stopPrefetch() retires the shelf queue, whose generation is
            //     what stops last frame's cards being filed under this frame;
            //   · compare.clear() drops `CompareView.after`, the previous
            //     render, which nothing else ever clears;
            //   · filmThumbs.clear() is the shelf's own cache, keyed by film
            //     id with no frame identity in the key.
            stopPrefetch();
            if (compare != null) compare.clear();
            filmThumbs.clear();
            lastRenderedBitmap = null;
            setOpticsUnlocked(false);
            dateText = null;
            setStage(2, getFileName(uri));

            new Thread(() -> {
                try {
                    String resourcesDir = getResourcesDir();
                    if (engine == null) {
                        vkLogFile().delete();
                        engine = SpektraLabEngine.create(resourcesDir, vkLogFile().getAbsolutePath());
                        uploadCapabilities();
                    }
                    // Under the render lock: the shelf queue can still be
                    // inside `renderBegin` when this thread gets here, and
                    // closing a session out from under it is a native crash,
                    // not an exception.
                    synchronized (renderMutex) {
                        if (session != null) { session.close(); session = null; }
                    }
                    
                    // `film_stock` names a profile file: "" and "null" both make
                    // `load_profile` fail ("bad stock name"), so an unchosen film
                    // omits the key and the engine keeps its own default. The UI
                    // still shows 原图 and renders nothing until a card is tapped.
                    final String filmField = filmStock == null ? ""
                            : String.format("\"film_stock\":\"%s\",", filmStock);
                    String params = String.format(
                        "{%s\"print_stock\":\"%s\",\"input_color_space\":\"ProPhoto RGB\",\"input_cctf_decoding\":false,\"output_color_space\":\"sRGB\",\"print_exposure\":%.2f,\"preview_long_edge\":2048}",
                        filmField, printStock, printExposure
                    );
                    
                    try { DebugSink.text("open-params", "path=" + filePath + "\n" + params); } catch (Throwable ignored) {}
                    // The cap comes from the device, not from a constant: a
                    // plane is 12 B/px and a binding may not exceed the GPU's
                    // maxStorageBufferRange. Two limits apply, whichever is
                    // lower -- that range, and the 65 535-workgroup dispatch
                    // limit (16,776,960 px) this driver still loses blocks over.
                    final boolean nativeSize = importAtNativeSize;
                    importAtNativeSize = false;                   // one import only
                    if (nativeSize) {
                        try { DebugSink.text("import-mode", "native size, no cap"); } catch (Throwable ignored) { }
                    }
                    rawFilePath = filePath;
                    session = engine.openRawSession(filePath, params,
                            nativeSize ? 0 : maxImportPixels(),
                            nativeSize ? 0 : IMPORT_LONG_EDGE);
                    lastImportError = null;
                    // **The session's own parameters, which this path never sent.**
                    // `openRawSession` carries the film, the paper and the decode
                    // colour space; it does not carry grain, halation, glare, the
                    // format or the rebate, and those live in `applyParams()`. The
                    // bitmap path has always called this pair; the raw path did
                    // not, so a raw kept the engine's defaults for every optical
                    // control until the user moved a slider -- and a rebate or a
                    // date back chosen before the import never reached the render
                    // at all.
                    applySessionParams();

                    // **The raw itself**, which is the "before" side of every
                    // comparison and the only honest thing to show on import:
                    // the decoded frame in working space, packed with no
                    // transfer function, so a display reads it the way it reads
                    // a raw -- flat, and dark. The camera's own JPEG is **not**
                    // a stand-in for it: a preview is the camera's rendering,
                    // not this frame, and showing it as "原图" is the same lie
                    // as importing it would be. No frame, no picture.
                    Bitmap rawView = null;
                    try {
                        final int[] d = session.exportSource("live");
                        if (d != null && d[0] > 0 && d[1] > 0) {
                            final int rw = d[0], rh = d[1];
                            final int[] px = new int[rw * rh];
                            if (session.copyArgbRange(px, 0, rh) == rw * rh) {
                                rawView = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
                                rawView.setPixels(px, 0, rw, 0, 0, rw, rh);
                            }
                        }
                        try {
                            int probePixel = 0;
                            if (rawView != null) {
                                int[] one = new int[1];
                                rawView.getPixels(one, 0, 1, Math.max(0, rawView.getWidth() / 2),
                                                  Math.max(0, rawView.getHeight() / 2), 1, 1);
                                probePixel = one[0];
                            }
                            DebugSink.text("raw-view", rawView == null ? "export: empty"
                                    : "export " + rawView.getWidth() + "x" + rawView.getHeight()
                                      + " mid=" + Integer.toHexString(probePixel));
                        }
                        catch (Throwable ignored) { }
                    } catch (Throwable t) {
                        try { DebugSink.text("raw-view", "export failed: " + t); } catch (Throwable ignored) { }
                    }
                    final Bitmap preview = rawView;
                    final boolean hasPreview = preview != null;
                    if (!hasPreview) {
                        // The canvas is not allowed to be blank for no reason.
                        // A session that opened but produced no source frame is
                        // a bug, and it has to say so rather than look like a
                        // photo that failed to load.
                        try { DebugSink.text("raw-view", "no source frame for the canvas"); }
                        catch (Throwable ignored) { }
                        runOnUiThread(() -> statusText.setText("RAW 已载入，原图预览未生成"));
                    }
                    if (hasPreview) {
                        runOnUiThread(() -> {
                            beforeBitmap = preview;
                            compare.setBefore(preview);
                            setStage(1, getFileName(uri));
                            rememberFrame(uri);
                            rebuildFilmCards();
                            setStep(1);
                            statusText.setText("Raw preview");
                        });
                    }

                    // "Process" (配光): without a solved print exposure and
                    // filter pack the print runs on defaults and blows the
                    // highlights. The mac app makes the user press Process;
                    // this build does it once on open.
                    try {
                        String solved = session.solve("both");
                        DebugSink.text("solve", solved == null ? "(null)" : solved);
                    } catch (Throwable se) {
                        DebugSink.text("solve-failed", String.valueOf(se));
                    }
                    runOnUiThread(() -> {
                        if (session == null) return;
                        showingOriginal = true;
                        lastRenderedBitmap = null;
                        setOriginalVisual(true);
                        rebuildFilmCards();
                        if (filmStock != null) {
                            showingOriginal = false;
                            updateFilmSelection();
                            renderImage();
                        } else {
                            statusText.setText("未渲染 · 选择胶片开始");
                            startPrefetch();
                        }
                    });

                    // Get dimensions from session
                    int[] dims = session.getDimensions();
                    int w = dims[0];
                    int h = dims[1];
                    sourceWidth = w;
                    sourceHeight = h;
                    try { DebugSink.text("raw-dims", "w=" + w + " h=" + h); } catch (Throwable ignored) {}

                    // Say when the device, not the photo, set the size. Silent
                    // downscaling reads as a bug the next time the user exports.
                    final int[] src = SpektraLabEngine.rawSourceSize();
                    final boolean shrunk = src != null && src.length == 2
                            && (long) src[0] * src[1] > (long) w * h;
                    try {
                        DebugSink.text("raw-cap", shrunk
                                ? "source " + src[0] + "x" + src[1] + " -> session " + w + "x" + h
                                : "not capped " + w + "x" + h);
                    } catch (Throwable ignored) {}

                    runOnUiThread(() -> {
                        refreshEdgeFormats();
                        setStep(1);
                        if (shrunk && !suppressBudgetDialog) {
                            // The raw has to be decoded before its size is known,
                            // so this asks afterwards -- but it still asks, and
                            // it offers the escape hatch the long-press used to
                            // hide.
                            final int rawW = src[0], rawH = src[1];
                            final String budgetMessage = "原图 " + rawW + "×" + rawH + "（" + megapixels(rawW, rawH)
                                            + " MP），这台机器一次能渲染约 "
                                            + String.format(java.util.Locale.US, "%.1f", maxImportPixels() / 1e6)
                                            + " MP，已按 " + w + "×" + h + " 导入。"
                                            + "\n\n按原尺寸导入可能渲染失败（E-1001）。";
                                    showActionSheet("原图超过本机预算", budgetMessage,
                                            new String[]{"就这样", "按原尺寸重来"}, which -> {
                                                if (which == 1) {
                                                    importAtNativeSize = true;
                                                    retryingNativeSize = true;
                                                    loadRawImage(uri);
                                                }
                                            });
                        }
                        statusText.setText("RAW metadata loaded");
                    });
                } catch (Throwable t) {
                    final String said = describeImportFailure(t);
                    lastImportError = said;
                    // A High-Efficiency NEF is not a preview job. Say what it
                    // is (E-1108, a compression this build has no decoder for)
                    // and what to change, and leave the file alone.
                    runOnUiThread(() -> {
                        statusText.setText("RAW加载失败 " + SpkError.shortForm(said));
                        showErrorDialog("RAW 加载失败", said);
                    });
                }
            }).start();
        } catch (Throwable t) {
            lastImportError = describeImportFailure(t);
            showErrorDialog("RAW 加载失败", lastImportError);
        }
    }
    
    /**
     * A failure the user has to be able to read and copy.
     *
     * <p>A Toast is two lines on most phones and ends in an ellipsis, which is
     * exactly how the E-9000 detail disappeared for two releases: the message
     * carried the whole dlopen chain and the screen showed "……so …". This one
     * scrolls, can be selected, and copies itself in one tap -- an error the
     * user cannot forward is an error nobody can fix.
     */
    private void showErrorDialog(String title, String body) {
        final String shownTitle = (title == null || title.isEmpty()) ? "错误" : title;
        final String shownBody = (body == null || body.isEmpty()) ? "[E-0000] 未知错误" : body;
        runOnUiThread(() -> {
            final android.app.Dialog dialog = new android.app.Dialog(this);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(UI_PAPER);
            View cap = new View(this);
            cap.setBackgroundColor(UI_BLUE);
            card.addView(cap, new LinearLayout.LayoutParams(-1, dp(3)));

            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(android.view.Gravity.CENTER_VERTICAL);
            head.setPadding(dp(16), 0, dp(16), 0);
            TextView kicker = mixedLabel("ERROR / 错误详情", 8, UI_BLUE);
            kicker.setLetterSpacing(0.08f);
            head.addView(kicker, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView close = label("关闭", 9, UI_MUTED, tfHan);
            close.setPaintFlags(close.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            close.setPadding(dp(12), dp(14), 0, dp(14));
            close.setOnClickListener(v -> dialog.dismiss());
            head.addView(close);
            card.addView(head, new LinearLayout.LayoutParams(-1, dp(42)));

            ScrollView scroll = new ScrollView(this);
            LinearLayout bodyBox = new LinearLayout(this);
            bodyBox.setOrientation(LinearLayout.VERTICAL);
            bodyBox.setPadding(dp(16), 0, dp(16), 4);
            TextView titleView = label(shownTitle, 18, UI_INK, tfHan);
            titleView.setLetterSpacing(-0.02f);
            bodyBox.addView(titleView);
            TextView advice = label("请复制下面的完整信息，方便定位问题。", 10, UI_MUTED, tfHan);
            LinearLayout.LayoutParams adviceLp = new LinearLayout.LayoutParams(-1, -2);
            adviceLp.topMargin = dp(8);
            bodyBox.addView(advice, adviceLp);
            TextView detail = label(shownBody, 9.5f, 0xFF454640, tfHan);
            detail.setTextIsSelectable(true);
            detail.setLineSpacing(dp(4), 1f);
            detail.setPadding(dp(12), dp(11), dp(12), dp(11));
            android.graphics.drawable.GradientDrawable detailBg = new android.graphics.drawable.GradientDrawable();
            detailBg.setColor(0xFFE7E8E3);
            detail.setBackground(detailBg);
            LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
            detailLp.topMargin = dp(14);
            bodyBox.addView(detail, detailLp);
            scroll.addView(bodyBox, new android.widget.FrameLayout.LayoutParams(-1, -2));
            int bodyHeight = (int) Math.min(dp(340), getResources().getDisplayMetrics().heightPixels * 0.44f);
            card.addView(scroll, new LinearLayout.LayoutParams(-1, bodyHeight));

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(dp(16), dp(14), dp(16), dp(16));
            TextView closeAction = label("关闭", 10, UI_BLUE, tfHan);
            closeAction.setGravity(android.view.Gravity.CENTER);
            android.graphics.drawable.GradientDrawable closeBg = new android.graphics.drawable.GradientDrawable();
            closeBg.setColor(UI_PAPER);
            closeBg.setStroke(dp(1), UI_BLUE);
            closeAction.setBackground(closeBg);
            closeAction.setOnClickListener(v -> dialog.dismiss());
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            closeLp.rightMargin = dp(8);
            actions.addView(closeAction, closeLp);
            TextView copy = label("复制错误信息", 10, UI_PAPER, tfHan);
            copy.setGravity(android.view.Gravity.CENTER);
            android.graphics.drawable.GradientDrawable copyBg = new android.graphics.drawable.GradientDrawable();
            copyBg.setColor(UI_BLUE);
            copy.setBackground(copyBg);
            copy.setOnClickListener(v -> {
                try {
                    final String text = shownTitle + "\n\n" + shownBody
                            + "\n\nbuild=SpektraLab Android " + UpdateCheck.versionName(this)
                            + "\n" + probe.describe();
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("SpektraLab", text));
                    copy.setText("已复制");
                } catch (Throwable t) {
                    copy.setText("复制失败 · 请长按详情");
                }
            });
            actions.addView(copy, new LinearLayout.LayoutParams(0, dp(42), 1.45f));
            card.addView(actions, new LinearLayout.LayoutParams(-1, -2));

            dialog.setContentView(card);
            dialog.setCanceledOnTouchOutside(true);
            dialog.show();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
                int width = Math.min(dp(358),
                        getResources().getDisplayMetrics().widthPixels - dp(32));
                dialog.getWindow().setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                dialog.getWindow().setDimAmount(0.30f);
            }
        });
    }

    private int extractInt(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return 0;
        idx += key.length();
        int end = json.indexOf(",", idx);
        if (end < 0) end = json.indexOf("}", idx);
        if (end < 0) return 0;
        try {
            return Integer.parseInt(json.substring(idx, end).trim());
        } catch (Exception e) {
            return 0;
        }
    }
    
    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) result = result.substring(cut + 1);
        }
        return result;
    }
    
    private String getFilePathFromUri(Uri uri) {
        // For content:// URIs, we need to copy to a temp file
        if (uri.getScheme().equals("content")) {
            try {
                String fileName = getFileName(uri);
                java.io.File tempFile = new java.io.File(getCacheDir(), fileName);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     java.io.OutputStream out = new java.io.FileOutputStream(tempFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }
                return tempFile.getAbsolutePath();
            } catch (Exception e) {
                return null;
            }
        } else if (uri.getScheme().equals("file")) {
            return uri.getPath();
        }
        return null;
    }

    /**
     * The EXIF orientation, or -1 when the provider will not give it to us.
     *
     * Two attempts on purpose: a stream first, then the file descriptor. Some
     * pickers and cloud providers hand out a one-shot stream, and the second
     * openInputStream() then fails -- the old code swallowed that and returned
     * the unrotated bitmap, which is exactly how a portrait frame arrives
     * sideways "at random".
     */
    /** The TIFF/EXIF orientation of a file on disk (raw containers included). */
    private int orientationOfPath(String path) {
        int fromPlatform = -1;
        try {
            fromPlatform = new android.media.ExifInterface(path).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable t) {
            try { DebugSink.text("exif", "path: " + t); } catch (Throwable ignored) { }
        }
        if (fromPlatform > 0) return fromPlatform;
        // ExifInterface does not open every raw container: RW2 writes 0x55
        // where TIFF writes 42, and the platform reader refuses it outright on
        // some builds. IFD0's tag 0x0112 is four bytes away from a header we
        // can read ourselves.
        return tiffOrientation(path);
    }

    /** TAG_ORIENTATION out of a TIFF-style container, magic 42 or Panasonic's 0x55. */
    private int tiffOrientation(String path) {
        java.io.RandomAccessFile file = null;
        try {
            file = new java.io.RandomAccessFile(path, "r");
            if (file.length() < 16) return -1;
            final byte[] head = new byte[8];
            file.readFully(head);
            final boolean little = head[0] == 0x49 && head[1] == 0x49;
            final int magic = shortAt(head, 2, little);
            if (magic != 42 && magic != 0x55) return -1;
            final long ifd = longAt(head, 4, little);
            if (ifd <= 0 || ifd > file.length() - 2) return -1;
            file.seek(ifd);
            final byte[] count = new byte[2];
            file.readFully(count);
            final int entries = shortAt(count, 0, little);
            if (entries <= 0 || entries > 512) return -1;
            final byte[] table = new byte[entries * 12];
            file.readFully(table);
            for (int i = 0; i < entries; ++i) {
                final int base = i * 12;
                if (shortAt(table, base, little) != 0x0112) continue;      // Orientation
                if (shortAt(table, base + 2, little) != 3) continue;       // SHORT
                return shortAt(table, base + 8, little);
            }
        } catch (Throwable t) {
            return -1;
        } finally {
            if (file != null) try { file.close(); } catch (Throwable ignored) { }
        }
        return -1;
    }

    private static int shortAt(byte[] b, int at, boolean little) {
        final int a = b[at] & 0xFF, c = b[at + 1] & 0xFF;
        return little ? (c << 8 | a) : (a << 8 | c);
    }

    private static long longAt(byte[] b, int at, boolean little) {
        long v = 0;
        for (int i = 0; i < 4; ++i) {
            final int idx = little ? at + 3 - i : at + i;
            v = (v << 8) | (b[idx] & 0xFF);
        }
        return v;
    }

    private int readOrientation(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in != null) {
                return new android.media.ExifInterface(in).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Throwable ignored) { }
        try (android.os.ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r")) {
            if (fd != null) {
                return new android.media.ExifInterface(fd.getFileDescriptor()).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Throwable ignored) { }
        return -1;
    }

    /**
     * The image, decoded. `BitmapFactory` first -- the path every JPEG and PNG
     * has always taken -- and `ImageDecoder` when that comes back empty.
     *
     * The second attempt is for HEIC/HEIF. Samsung and iPhone save 10-bit HEIF,
     * `BitmapFactory.decodeStream` returns null on those (and reports no bounds
     * at all for the sniff), while the platform decoder behind `ImageDecoder`
     * (API 28+) hands back a software bitmap. The old code said "无法解码图片"
     * and stopped -- which a Samsung user read, correctly, as "this app cannot
     * import my photos".
     */
    private Bitmap decodeImport(Uri uri, int capPixels, int edgeCap) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream head = getContentResolver().openInputStream(uri)) {
                if (head != null) BitmapFactory.decodeStream(head, null, bounds);
            } catch (Throwable t) {
                try { DebugSink.text("decode", "sniff " + t); } catch (Throwable ignored) { }
            }
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                int sample = 1;
                while ((long) (bounds.outWidth / (sample * 2)) * (bounds.outHeight / (sample * 2)) >= capPixels
                        || Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edgeCap) sample *= 2;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                try (InputStream stream = getContentResolver().openInputStream(uri)) {
                    Bitmap decoded = BitmapFactory.decodeStream(stream, null, opts);
                    if (decoded != null) return decoded;
                    try { DebugSink.text("decode", "null at sample " + sample); }
                    catch (Throwable ignored) { }
                } catch (Throwable t) {
                    try { DebugSink.text("decode", "stream " + t); } catch (Throwable ignored) { }
                }
            } else {
                try { DebugSink.text("decode", "no bounds " + bounds.outWidth + "x" + bounds.outHeight); }
                catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            try { DebugSink.text("decode", "outer " + t); } catch (Throwable ignored) { }
        }
        return decodeViaImageDecoder(uri, capPixels, edgeCap);
    }

    /**
     * The platform decoder through its newer door, scaled in the decoder so the
     * full-size bitmap never exists. **Software allocator on purpose**: a
     * hardware bitmap cannot be read back with `getPixels`, and the import path
     * reads every pixel into a float buffer.
     */
    private Bitmap decodeViaImageDecoder(Uri uri, final int capPixels, final int edgeCap) {
        if (android.os.Build.VERSION.SDK_INT < 28) return null;
        try {
            final android.graphics.ImageDecoder.Source src =
                    android.graphics.ImageDecoder.createSource(getContentResolver(), uri);
            Bitmap decoded = android.graphics.ImageDecoder.decodeBitmap(src,
                    (decoder, info, source) -> {
                        final android.util.Size size = info.getSize();
                        final int w = size.getWidth(), h = size.getHeight();
                        double k = 1.0;
                        final long area = (long) w * (long) h;
                        if (area > capPixels) k = Math.min(k, Math.sqrt((double) capPixels / area));
                        final int longEdge = Math.max(w, h);
                        if (longEdge > edgeCap) k = Math.min(k, (double) edgeCap / longEdge);
                        if (k < 1.0) {
                            decoder.setTargetSize(Math.max(1, (int) Math.round(w * k)),
                                                  Math.max(1, (int) Math.round(h * k)));
                        }
                        decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
            try { DebugSink.text("decode", "imagedecoder " + decoded.getWidth() + "x"
                    + decoded.getHeight()); } catch (Throwable ignored) { }
            return decoded;
        } catch (Throwable t) {
            try { DebugSink.text("decode", "imagedecoder " + t); } catch (Throwable ignored) { }
            return null;
        }
    }

    /**
     * A failure the user can act on. `OutOfMemoryError` is an `Error`, not an
     * `Exception`, which is why the `catch (Exception)` this replaces let the
     * process die on the Honor report instead of saying 内存不足.
     */
    private String describeImportFailure(Throwable t) {
        try { DebugSink.text("import-error", String.valueOf(t)); } catch (Throwable ignored) { }
        if (t instanceof OutOfMemoryError) {
            return "[E-1003] 内存不足：这张图对本机太大。换一张小一点的，或先关掉其它应用再试";
        }
        return SpkError.format(describe(t));
    }

    private Bitmap applyExifRotation(Uri uri, Bitmap bitmap) {
        final int orientation = readOrientation(uri);
        try { DebugSink.text("exif", "orientation=" + orientation + " bitmap="
                + bitmap.getWidth() + "x" + bitmap.getHeight()); } catch (Throwable ignored) { }
        return rotateForOrientation(bitmap, orientation);
    }

    /** The orientation tag as a matrix. 0 or negative means "unreadable". */
    private Bitmap rotateForOrientation(Bitmap bitmap, int orientation) {
        if (orientation <= 0) return bitmap;      // unreadable: leave the pixels alone
        try {
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            switch (orientation) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90:
                    matrix.postRotate(90); break;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180:
                    matrix.postRotate(180); break;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270:
                    matrix.postRotate(270); break;
                case android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                    matrix.postScale(-1, 1); break;
                case android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    matrix.postScale(1, -1); break;
                // The two mirrored rotations. Rare, but a phone that writes 5
                // or 7 and lands in the default branch shows its photo sideways.
                case android.media.ExifInterface.ORIENTATION_TRANSPOSE:
                    matrix.postRotate(90); matrix.postScale(-1, 1); break;
                case android.media.ExifInterface.ORIENTATION_TRANSVERSE:
                    matrix.postRotate(-90); matrix.postScale(-1, 1); break;
                default:
                    return bitmap;
            }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        } catch (Exception e) {
            return bitmap;
        }
    }


    /**
     * A stock change only re-renders. Measured against fresh sessions on this
     * engine, the stored print solution already gives the same picture after a
     * film switch (mean 64.4/58.9/55.7 either way) and a paper switch only moves
     * the paper's own speed, which the enlarger-exposure slider is for. Calling
     * `spk_solve` here re-metered a cached full-size negative and never returned,
     * which is what left the status bar stuck on "配光中".
     */
    private void onStockChanged() {
        if (session == null || !uiReady) return;
        if (filmStock == null) {
            // No film yet: the frame stays the import's own, and the sheet's
            // choice is remembered for the first render.
            showInAppToast("选择一张胶片开始渲染");
            return;
        }
        // No `applySessionParams()` here any more: it wrote the engine from the
        // UI thread, which both raced the catalogue queue and could park the UI
        // behind a long render's lock. `renderImage` applies the stock under the
        // same lock it renders under -- one writer, one order.
        showingOriginal = false;
        setOriginalVisual(false);
        updateFilmSelection();
        renderImage();
    }


    /**
     * The cheap half: what a slider owns.
     *
     * No stock, no colour space, no preview size -- those describe the file and
     * live in `applySessionParams`. Sending them on every drag tick made the
     * engine rebuild the pipeline once per step (the presence of `film_stock`
     * in a delta *is* a stock change), which is what made the sliders feel
     * dead rather than merely slow.
     */
    private void applyParams() {
        if (session == null) return;
        // Every delta, with the frame it is being sent for: the engine treats a
        // stock change as a rebuild, and a render that follows one has to be
        // read next to the delta that caused it.
        try {
            DebugSink.text("params", "render=" + rendering + " film=" + filmStock + " print=" + printStock
                    + " overscan=" + overscanOn + " date=" + dateOn + " fmt=" + filmFormatMm);
        } catch (Throwable ignored) { }
        applyParams(PREVIEW_LONG_EDGE);
    }

    /**
     * The same delta **plus the film and the paper**, for every call that is
     * about to produce a picture.
     *
     * <p>This is the one thing that has to be true before `renderBegin`: the
     * session carries the stock the user is looking at. It used to be kept by
     * a Java-side `sessionSignature` -- a field written by the catalogue
     * queue and by the UI thread, compared against the engine's *assumed*
     * state. One write out of order and the signature claimed a stock the
     * session never got, so every film rendered the same picture while the
     * rebate text (which the app draws from its own field) kept changing.
     *
     * <p>Stating the stock in the delta costs ~10 ms on the host (26 ms
     * against 19 ms for the delta without it, measured at 900x600 with
     * `stock_cost`); a render is two orders of magnitude more. So there is no
     * bookkeeping left to get wrong: whoever draws, says what to draw.
     */
    private void applyParams(String stock, int longEdge) {
        applyParams(stock, printStock == null ? "kodak_portra_endura" : printStock, longEdge);
    }

    /**
     * The same delta with the paper named explicitly.
     *
     * <p>The shelf needs this: a card is "this film on **its** paper", so a
     * black-and-white card must render on Ilford even while the canvas is on
     * whatever paper the user picked. Before this the card took the global
     * `printStock`, so every black-and-white card came out sepia -- the shelf
     * showed the wrong recipe and read as "the black-and-white film is broken".
     */
    private void applyParams(String stock, String paper, int longEdge) {
        if (session == null || stock == null) return;
        // A null paper used to return here, which meant "sent nothing at all"
        // rather than "sent everything but the paper".
        if (paper == null) paper = "kodak_portra_endura";
        try {
            DebugSink.text("params", "draw film=" + stock + " print=" + paper
                    + " render=" + rendering + " long=" + longEdge);
        } catch (Throwable ignored) { }
        session.setParams(String.format(java.util.Locale.US,
            "{\"film_stock\":\"%s\",\"print_stock\":\"%s\","
            + "\"input_color_space\":\"%s\",\"input_cctf_decoding\":%s,\"auto_exposure\":%s,"
            + "\"auto_exposure_method\":\"balanced\","
            + "\"scan_film\":%s,"
            + "\"output_color_space\":\"sRGB\",\"preview_long_edge\":%d,"
            + "\"print_exposure\":%.2f,\"grain_amount\":%.3f,\"grain_sublayers_active\":%s,"
            + "\"halation_amount\":%.3f,"
            + "\"halation_boost_ev\":%.2f,\"halation_scatter_amount\":%.3f,"
            + "\"halation_active\":%s,\"antihalation_removed\":%s,"
            + "\"grain_active\":%s,\"dir_couplers_active\":%s,"
            + "\"scanner_white_correction\":%s,\"scanner_black_correction\":%s,"
            + "\"extended_dynamic_range\":%s,"
            + "\"camera_filter\":\"%s\","
            + "\"scene_latitude_active\":%s,\"scene_latitude_norm\":\"%s\","
            + "\"scene_latitude_highlight_knee\":1.0,"
            + "\"scene_latitude_highlight_room\":%.2f,\"scene_latitude_shadow_knee\":-2.0,"
            + "\"scene_latitude_shadow_room\":%.2f,"
            + "\"scene_latitude_rolloff\":%.2f,\"scene_latitude_max_lift\":%.2f,"
            + "\"glare_amount\":%.3f,\"film_format_mm\":%.1f,"
            + "\"lens_blur_um\":%.1f,"
            + "\"m_filter_shift\":%.3f,\"y_filter_shift\":%.3f,\"filter_shift_scale\":%.1f,"
            + "\"preflash_exposure\":%.4f,"
            + "\"overscan_active\":%s,\"overscan_format\":\"%s\",\"overscan_edge_text\":\"%s\","
            + "\"overscan_fog\":%.2f,\"overscan_leaks\":%.2f,\"overscan_frame_number\":%.0f,"
            + "\"overscan_mode\":\"%s\",\"overscan_gate\":\"%s\",\"overscan_holes\":\"%s\","
            + "\"overscan_carrier\":\"%s\",\"overscan_f_number\":%.0f,"
            + "\"overscan_camera_seed\":%d,\"overscan_frame_seed\":%d,"
            + "\"date_imprint_active\":%s,\"date_imprint_text\":\"%s\",\"date_imprint_style\":\"%s\","
            + "\"date_imprint_text_b\":\"%s\",\"date_imprint_data_text\":\"%s\","
            + "\"date_imprint_placement\":\"%s\",\"date_imprint_corner\":\"%s\","
            + "\"date_imprint_inset_x\":%.2f,\"date_imprint_inset_y\":%.2f,"
            + "\"date_imprint_size\":%.2f,\"date_imprint_ev\":%.1f}",
            stock, paper,
            isRawFile ? "ProPhoto RGB" : "sRGB",
            isRawFile ? "false" : "true",
            isRawFile ? "true" : "false",
            isPositiveStock(stock) ? "true" : "false",
            longEdge,
            printExposure, wireGrain(stock),
            isMonochromeStock(stock) ? "false" : "true",
            halationAmount,
            halationBoost, halationScatter,
            halationOn > 0.5f ? "true" : "false",
            antihalationRemoved > 0.5f ? "true" : "false",
            grainOn > 0.5f ? "true" : "false",
            couplersOn > 0.5f ? "true" : "false",
            scannerWhite > 0.5f ? "true" : "false",
            scannerBlack > 0.5f ? "true" : "false",
            edrOn > 0.5f ? "true" : "false",
            cameraFilter,
            sceneLatitudeActive() ? "true" : "false", sceneNorm, wireHighlightRoom(),
            wireShadowRoom(),
            shadowRolloff, maxLift,
            glareAmount, wireFormatMm(),
            lensBlur,
            mFilter, yFilter, filterScale,
            preflash,
            overscanOn ? "true" : "false", overscanFormat, edgeTextOn ? edgeTextFor(stock) : "",
            edgeFog, edgeLeaks, frameNumber,
            edgeMode, edgeGate, edgeHoles, edgeCarrier, edgeFNumber,
            cameraSeed, frameSeed,
            dateOn ? "true" : "false", dateOn ? imprintDate() : "", dateStyle,
            esc(dateTextB), esc(dataText),
            datePlacement, dateCorner, dateInsetX, dateInsetY, dateSize, dateEv
        ));
    }

    /** Same set, at whatever long edge the caller is rendering. The catalogue
     *  pass uses a small one so a whole shelf of films is affordable; a look
     *  the user is about to export still goes out at the preview size. */
    private void applyParams(int longEdge) {
        if (session == null) return;
        final String stock = filmStock;
        if (stock == null) return;
        // **With the stock, always.** `spk_set_params` decides to rebuild the
        // pipeline on `delta.has("film_stock") || delta.has("print_stock")` --
        // presence of the key, not a change in its value. The date back, the
        // rebate and every other SHOOT-layer field only reach the picture on
        // that rebuild, so a delta without the stock was written into the
        // parameter tree and then rendered with the old `pb_`: the date's size,
        // corner, style and exposure did nothing until the film was re-picked.
        // Sending it costs one profile read; not sending it costs the control.
        applyParams(stock, printStock == null ? "kodak_portra_endura" : printStock, longEdge);
    }

    /**
     * The half that describes the file: once per session, and after a stock
     * change.
     *
     * <p>`auto_exposure` is off for everything that is not a RAW. The engine's
     * default meter is `center_weighted`: an **arithmetic** mean of luminance
     * under a Gaussian centred on the frame, sigma 0.2 of the long edge (see
     * `exposure_evs_from` in the pipeline). An arithmetic mean is dominated by
     * whatever is brightest, so a frame whose middle is open sky meters about
     * three times mid-grey and the whole picture prints 1.7 stops down -- a
     * user sent exactly that screenshot: bright blue sky rendered as slate,
     * sunlit leaves rendered black.
     *
     * <p>A camera JPEG has already been metered once, by the camera, and that
     * decision is what the user sees in their gallery. Metering it a second
     * time is double-counting, and the two meters disagree by more than a stop
     * on exactly the pictures people take: sky, backlight, snow. With the
     * meter off the render starts from the file's own exposure, which is what
     * "import this photo" has to mean.
     *
     * <p>A RAW is scene-referred and has no exposure decision baked in, so it
     * keeps the meter. (`auto_exposure: false` makes the engine apply no EV at
     * all -- `pipeline.cpp` returns the input untouched.)
     */
    private void applySessionParams() {
        if (session == null) return;
        final String stock = filmStock;
        if (stock == null) return;
        applyParams(stock, PREVIEW_LONG_EDGE);
    }

    private void renderImage() {
        // Every branch says something. The foot is one thin line, too quiet to
        // carry "nothing happened"; failures need to be readable.
        if (session == null) {
            try { DebugSink.text("render", "no session, lastImportError=" + lastImportError); }
            catch (Throwable ignored) { }
            showErrorDialog("还没有可渲染的图像", lastImportError == null
                    ? "先导入一张照片。点画布中央的「选择照片」，或点下方「换图」；RAW / HEIC 请选「从文件管理器选择」。"
                    : "上次导入没有完成：\n\n" + lastImportError);
            return;
        }
        if (filmStock == null) {
            showInAppToast("先在下方选一张胶片");
            return;
        }
        stopPrefetch();
        if (rendering) {
            // One native result buffer for the whole process: two renders would
            // interleave on it and hand back another frame's dimensions.
            try { DebugSink.text("render", "already rendering"); } catch (Throwable ignored) { }
            statusText.setText("Rendering…");
            // Not dropped: a tap that lands mid-render is the *last* choice the
            // user made, so it becomes the next render instead of a toast.
            pendingRender = true;
            showInAppToast("正在渲染，完成后按新选择再画一次…");
            return;
        }
        final String renderedStock = filmStock;
        rendering = true;
        progressBar.setVisibility(View.VISIBLE);
        busyLabel.setVisibility(View.VISIBLE);
        statusText.setText("Rendering…");
        try { DebugSink.text("render", "begin " + sourceWidth + "x" + sourceHeight); }
        catch (Throwable ignored) { }

        new Thread(() -> {
            try {
                synchronized (renderMutex) {
                if (session == null) throw new RuntimeException("session closed before render");
                // The queue stops here, but a card it started before still
                // finishes and can put a stock back onto this session. Take the
                // film the user picked, under the same lock the render runs
                // under, so the picture and the edge text cannot disagree.
                applyParams(renderedStock, PREVIEW_LONG_EDGE);
                int[] dims = session.renderBegin("live");
                int w = dims[0], h = dims[1];
                if (w <= 0 || h <= 0) throw new RuntimeException("engine returned empty frame");

                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                int[] pixels = new int[w * h];
                int copied = session.copyArgbRange(pixels, 0, h);
                if (copied != w * h) {
                    throw new RuntimeException("pixel copy incomplete " + copied + "/" + (w * h));
                }
                bmp.setPixels(pixels, 0, w, 0, 0, w, h);
                session.renderEnd();
                // What the meter did to *this* frame. A render that looks dark
                // is either the film's curve or an exposure the engine decided
                // on, and the two are indistinguishable by eye -- one line of
                // numbers settles it.
                try {
                    final String pj = session.progressJson();
                    final int at = pj == null ? -1 : pj.indexOf("\"auto_exposure_ev\"");
                    DebugSink.text("exposure", "tier=live " + (at < 0 ? "(no progress json)"
                            : pj.substring(at, Math.min(pj.length(), at + 40))));
                } catch (Throwable ignored) { }
                // The rebate's own numbers, next to the buffer they belong to.
                // "the edge is wrong in the export but right in the preview" is
                // a question only these two lines can answer.
                try {
                    DebugSink.text("overscan-geom", "tier=live " + w + "x" + h + " "
                            + session.overscanGeometry());
                } catch (Throwable ignored) { }
                // The engine's log for this render. When a frame comes back as
                // one flat colour there is nothing else to read: it says which
                // tier was built, how big, and what each dispatch covered.
                uploadVkLog();

                final int fw = w, fh = h;
                try {
                    long hsh = 1469598103934665603L;
                    for (int i = 0; i < pixels.length; i += 7) { hsh ^= pixels[i]; hsh *= 1099511628211L; }
                    long t0 = System.currentTimeMillis();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, bo);
                    DebugSink.text("preview", "w=" + w + " h=" + h + " hash=" + Long.toHexString(hsh)
                            + " copy=" + copied + " first=" + Integer.toHexString(pixels[0])
                            + " mid=" + Integer.toHexString(pixels[(h / 2) * w + w / 2])
                            + " jpegBytes=" + bo.size());
                    DebugSink.bytes("preview", "p.jpg", bo.toByteArray(), "image/jpeg");
                    DebugSink.text("preview-timing", "jpeg_ms=" + (System.currentTimeMillis() - t0));
                } catch (Throwable ignored) {}
                runOnUiThread(() -> {
                    showingOriginal = false;
                    compare.setAfter(bmp);
                    setStage(1, null);
                    setStep(2);
                    lastRenderedBitmap = bmp;
                    cacheFilmThumb(renderedStock, bmp);
                    updateCardThumb(renderedStock);
                    updateFilmSelection();
                    // A user render stops the queue so it can own the GPU. Put
                    // it back afterwards, or the rest of the shelf never fills.
                    startPrefetch();
                    setOpticsUnlocked(true);
                    setOriginalVisual(false);
                    updateFilmSelection();
                    final int[] bands = blackBands(bmp);
                    statusText.setText("Preview ready " + fw + "×" + fh);
                    try {
                        DebugSink.text("bands", "render " + fw + "x" + fh
                                + " black rows top=" + bands[0] + " bottom=" + bands[1]
                                + " source=" + sourceWidth + "x" + sourceHeight);
                    } catch (Throwable ignored) { }
                    progressBar.setVisibility(View.GONE);
                    busyLabel.setVisibility(View.GONE);
                    rendering = false;
                    if (pendingRender) { pendingRender = false; renderImage(); }
                });
            }
            } catch (Throwable e) {
                try { session.renderEnd(); } catch (Throwable ignored) {}
                final String msg = describe(e);
                runOnUiThread(() -> {
                    statusText.setText("渲染失败 " + SpkError.shortForm(msg));
                    progressBar.setVisibility(View.GONE);
                    busyLabel.setVisibility(View.GONE);
                    rendering = false;
                    // The foot is one 7 sp line: a failure has to be readable,
                    // and it has to arrive with enough context to be reported.
                    showErrorDialog("渲染失败", SpkError.format(msg)
                            + "\n\n" + sourceWidth + "×" + sourceHeight
                            + "\n" + probe.describe());
                    if (pendingRender) { pendingRender = false; renderImage(); }
                });
                // E-1001 is "a plane is bigger than one binding may name", and
                // the only cure is a smaller session. The import cap already
                // answers for a normal import, so reaching here means the frame
                // came in past it -- a native-size import, or a build that had
                // no cap. Offering the retry beats printing a code and leaving
                // the user to find the long-press menu.
                // 1001 is the binding range by name; 1009 is a Vulkan call that
                // failed without naming one, which on a frame this size is the
                // same ceiling. Both have the same cure.
                final int code = SpkError.codeOf(msg);
                if (code == 1001 || code == 1009) offerSmallerImport();
            }
        }).start();
    }

    /**
     * Re-import at a size this device can actually bind, then render again.
     * One offer per import: a loop of dialogs is worse than the error.
     */
    private void offerSmallerImport() {
        if (sourceUri == null || e1001Offered) return;
        e1001Offered = true;
        // The same ceiling the import uses, at 75 % of it. When the probe had
        // failed this used to fall back to the dispatch cap -- 16.5 MP -- which
        // on a 128 MiB device is still three times too big, so the retry failed
        // the same way and the offer was a dead end.
        long target = maxImportPixels();
        target = (long) (target * 0.75);
        target = Math.max(1_000_000L, Math.min(target, 100_000_000L));
        final long picking = target;
        runOnUiThread(() -> showActionSheet("这张图超出本机的单缓冲上限",
                "按约 " + String.format(java.util.Locale.US, "%.1f", picking / 1e6)
                        + " MP 重新导入并渲染？画面会小一些，但能出图。",
                new String[]{"重试", "取消"}, which -> {
                    if (which == 0) {
                        pendingCapPixels = (int) picking;
                        loadImage(sourceUri);
                    }
                }));
    }

    private void exportImage() {
        exportImage("jpeg", "preview");
    }

    /**
     * A native crash kills the process before the export path can report, so
     * any *.prog the JNI left in the cache dir is picked up on the next start.
     */
    private void uploadStaleExportProgress() {
        try {
            java.io.File[] files = getCacheDir().listFiles();
            if (files == null) return;
            for (java.io.File f : files) {
                if (!f.getName().endsWith(".prog")) continue;
                byte[] buf = new byte[(int) f.length()];
                try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                    int off = 0, n;
                    while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
                }
                DebugSink.text("export-progress", "stale " + f.getName() + "\n"
                        + new String(buf, java.nio.charset.StandardCharsets.UTF_8));
                f.delete();
            }
        } catch (Throwable ignored) {}
    }

    private java.io.File vkLogFile() {
        return new java.io.File(getCacheDir(), "vk.log");
    }

    private void uploadVkLog() {
        try {
            java.io.File f = vkLogFile();
            if (!f.exists() || f.length() == 0) {
                DebugSink.text("vk-log", "(empty) exists=" + f.exists());
                return;
            }
            // The engine may still hold the file open; read the newest window
            // instead of truncating, so the failure is always in the tail.
            final int cap = 220 * 1024;
            long len = f.length();
            long start = Math.max(0, len - cap);
            byte[] buf = new byte[(int) (len - start)];
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
                raf.seek(start);
                raf.readFully(buf);
            }
            DebugSink.text("vk-log", "bytes=" + len + " from=" + start + "\n"
                    + new String(buf, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable t) {
            try { DebugSink.text("vk-log", "read failed: " + t); } catch (Throwable ignored) {}
        }
    }

    private void uploadCapabilities() {
        try {
            String caps = engine != null ? engine.capabilities() : null;
            if (caps != null) DebugSink.text("capabilities", caps);
            String mem = engine != null ? engine.memoryReport() : null;
            if (mem != null) DebugSink.text("memory-report", mem);
            uploadStaleExportProgress();
        } catch (Throwable ignored) {}
    }

    /** Upload the native export black box, if the JNI left one. */
    private void uploadExportProgress(java.io.File out) {
        try {
            java.io.File prog = new java.io.File(out.getAbsolutePath() + ".prog");
            if (!prog.exists()) return;
            byte[] buf = new byte[(int) prog.length()];
            try (java.io.InputStream in = new java.io.FileInputStream(prog)) {
                int off = 0, n;
                while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            }
            DebugSink.text("export-progress", new String(buf, java.nio.charset.StandardCharsets.UTF_8));
            prog.delete();
        } catch (Throwable ignored) {}
    }

    /** The rebate's canvas as `WxH`, or "" when this frame has no rebate.
     *  Printed next to the export's own size: when the two disagree the file
     *  carries a band that was never drawn, and that is the whole of the
     *  "the rebate flies in the export but not in the preview" report. */
    private String overscanCanvasLabel() {
        try {
            final String g = session == null ? null : session.overscanGeometry();
            if (g == null) return "";
            final int wAt = g.indexOf("\"canvas_w\":");
            final int hAt = g.indexOf("\"canvas_h\":");
            if (wAt < 0 || hAt < 0) return "";
            final int w0 = wAt + 11, w1 = g.indexOf(',', w0);
            final int h0 = hAt + 11, h1 = g.indexOf(',', h0);
            if (w1 < 0 || h1 < 0) return "";
            return g.substring(w0, w1) + "x" + g.substring(h0, h1);
        } catch (Throwable ignored) { return ""; }
    }

    private void exportImage(String format, String tier) {
        final boolean tiff = "tiff".equals(format);
        final boolean dng = "dng".equals(format);
        final String ext = dng ? ".dng" : tiff ? ".tif" : ".jpg";
        final String mime = dng ? "image/x-adobe-dng" : tiff ? "image/tiff" : "image/jpeg";
        if (sharePill != null) sharePill.setVisibility(View.GONE);
        if (engine == null || session == null) {
            showInAppToast("请先导入照片");
            return;
        }
        if (filmStock == null) {
            showInAppToast("先选一张胶片，再导出");
            return;
        }
        // The shelf queue prints 800 px cards through this same session, and a
        // card that lands between "start export" and the first strip swaps
        // film_stock under the full-size render. That -- not the params delta --
        // is what used to come back as a flat grey.
        stopPrefetch();
        new Thread(() -> {
            java.io.File tmp = new java.io.File(getCacheDir(), "export_" + System.currentTimeMillis() + ext);
            try {
                runOnUiThread(() -> {
                    statusText.setText("导出中：准备全尺寸渲染（分条）...");
                    exportProgressBar.setVisibility(View.VISIBLE);
                    setStep(3);
                });
                // A stock delta before an export is fine -- measured, not
                // assumed (`full_after_delta`: mean 104.2 std 11945 before,
                // 102.4 std 12481 after, and the two hashes differ). It is the
                // same delta the preview renders with, which is the point.
                try {
                    DebugSink.text("export-params", "film=" + filmStock + " print=" + printStock
                            + " pex=" + printExposure + " (no delta sent)");
                } catch (Throwable ignored) {}

                final long t0 = System.currentTimeMillis();
                final java.util.concurrent.atomic.AtomicBoolean spinning =
                        new java.util.concurrent.atomic.AtomicBoolean(true);
                final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
                Runnable tick = new Runnable() {
                    @Override public void run() {
                        if (!spinning.get()) return;
                        long el = (System.currentTimeMillis() - t0) / 1000;
                        statusText.setText("导出中（" + tier + "）：渲染中 " + el + " 秒...");
                        ticker.postDelayed(this, 1000);
                    }
                };
                runOnUiThread(tick);
                int[] info;
                try {
                    synchronized (renderMutex) {
                        // **A session of its own for the export.** The preview
                        // session has already rasterised the rebate's masks at
                        // the preview's millimetres per pixel. Rendering that
                        // same session at "full" rebuilds the *layout* -- the
                        // geometry it reports is right -- but the rasterised
                        // edge-print masks are still the preview's, so the film
                        // edge lands at the wrong scale: the top band prints
                        // twice and the second copy sits on the picture. That
                        // is "the preview is fine and the export is flying".
                        // A fresh session's first render *is* the export tier,
                        // so its masks can only be the export's size.
                        final SpektraLabEngine.Session keep = session;
                        SpektraLabEngine.Session fresh = null;
                        if (keep != null && sourcePixels != null && sourceWidth > 0 && sourceHeight > 0
                                && (long) sourceWidth * sourceHeight <= 30_000_000L) {
                            try { fresh = engine.openSession(sourcePixels, sourceWidth, sourceHeight, 3); }
                            catch (Throwable ignored) { fresh = null; }
                        } else if (keep != null && rawFilePath != null) {
                            try {
                                fresh = engine.openRawSession(rawFilePath,
                                        "{\"print_stock\":\"" + (printStock == null ? "kodak_portra_endura" : printStock)
                                                + "\",\"input_color_space\":\"ProPhoto RGB\","
                                                + "\"input_cctf_decoding\":false,\"output_color_space\":\"sRGB\","
                                                + "\"preview_long_edge\":2048}", 0, 0);
                            } catch (Throwable ignored) { fresh = null; }
                        }
                        if (fresh != null) {
                            session = fresh;
                            try { DebugSink.text("export", "fresh session for tier=" + tier); }
                            catch (Throwable ignored) { }
                        }
                        try {
                            // Everything the preview drew with, re-stated: a
                            // stock, a paper, a gate, an edge text or a date
                            // that moved since the last render would otherwise
                            // be exported as the *preview* had it, or not at all.
                            applyParams(filmStock, PREVIEW_LONG_EDGE);
                            try {
                                DebugSink.text("overscan-geom", "tier=" + tier + " before "
                                        + session.overscanGeometry());
                                DebugSink.text("export-params", "tier=" + tier + " " + session.getParams());
                            } catch (Throwable ignored) { }
                            info = dng
                                    ? session.exportDng(tmp.getAbsolutePath(), tier)
                                    : tiff
                                    ? session.exportTiff(tmp.getAbsolutePath(), tier)
                                    : session.exportJpeg(tmp.getAbsolutePath(), 95, 512, tier);
                            try {
                                DebugSink.text("overscan-geom", "tier=" + tier + " " + info[0] + "x" + info[1]
                                        + " " + session.overscanGeometry());
                            } catch (Throwable ignored) { }
                        } finally {
                            if (fresh != null) {
                                session = keep;
                                try { fresh.close(); } catch (Throwable ignored) { }
                            }
                        }
                    }
                } finally {
                    spinning.set(false);
                }
                uploadExportProgress(tmp);
                uploadVkLog();
                int w = info[0], h = info[1];
                long renderMs = info[2];
                if (w <= 0 || h <= 0 || !tmp.exists() || tmp.length() == 0) {
                    throw new RuntimeException("引擎没有输出图像");
                }

                final int fw = w, fh = h;
                runOnUiThread(() -> statusText.setText("导出中：写入相册 " + fw + "x" + fh + "..."));
                String fileName = "SpektraLab_" + System.currentTimeMillis() + ext;
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime);
                values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/SpektraLab");
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
                android.content.ContentResolver resolver = getContentResolver();
                Uri outUri = resolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (outUri == null) throw new RuntimeException("无法创建媒体文件");
                try (java.io.InputStream in = new java.io.FileInputStream(tmp);
                     java.io.OutputStream out = resolver.openOutputStream(outUri)) {
                    if (out == null) throw new RuntimeException("无法打开输出流");
                    byte[] buf = new byte[262144];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }
                values.clear();
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(outUri, values, null, null);

                try {
                    long len = tmp.length();
                    DebugSink.text("export", "format=" + format + " tier=" + tier + " w=" + w + " h=" + h + " renderMs=" + renderMs
                            + " bytes=" + len);
                    if (len > 0 && len < 30L * 1024 * 1024) {
                        byte[] buf = new byte[(int) len];
                        try (java.io.InputStream in = new java.io.FileInputStream(tmp)) {
                            int off = 0, n;
                            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
                        }
                        DebugSink.bytes("export", "out-" + tier + ext, buf, mime);
                    }
                } catch (Throwable ignored) {}
                final long secs = Math.max(1, (System.currentTimeMillis() - t0) / 1000);
                final long fRender = renderMs;
                final long bytes = tmp.exists() ? tmp.length() : 0;
                final String sizeLabel = bytes > 0
                        ? String.format("%.1f MB", bytes / 1048576.0) : "?";
                runOnUiThread(() -> {
                    lastExportUri = outUri;
                    lastExportMime = mime;
                    // A separate control, and only when it has something to do:
                    // every other format opens where it lands, and a permanent
                    // share button on a photograph app reads as "post to
                    // social", which is not what this is.
                    if (sharePill != null) sharePill.setVisibility((tiff || dng) ? View.VISIBLE : View.GONE);
                    // Dimensions and file size together: "3.3 MB" alone reads as
                    // a bug when the frame is 16 MP, and the reason is worth
                    // saying out loud rather than guessing at later.
                    final String canvas = overscanCanvasLabel();
                    final String canvasNote = canvas.isEmpty() ? "" : " · 画布 " + canvas;
                    final String tiffHint = tiff
                            ? "（TIFF：点导出旁边的「分享」发给手机版 Lightroom 即可使用）"
                            : dng ? "（DNG：线性 16 位，给电脑上的 Lightroom / ACR；手机版打不开，已知问题，不必反馈）" : "";
                    showInAppToast("已保存 " + fw + "x" + fh + canvasNote + " · " + sizeLabel + tiffHint);
                    statusText.setText("已导出 " + fw + "x" + fh + canvasNote + " · " + sizeLabel
                        + "（" + secs + " 秒，其中渲染 " + (fRender / 1000.0) + " 秒）" + tiffHint);
                    exportProgressBar.setVisibility(View.GONE);
                    setStep(2);
                });
            } catch (Throwable e) {
                final String msg = describe(e);
                try { DebugSink.text("export-error", "tier=" + tier + " " + msg); } catch (Throwable ignored) {}
                uploadExportProgress(tmp);
                uploadVkLog();
                runOnUiThread(() -> {
                    statusText.setText("导出失败 " + SpkError.shortForm(msg));
                    showErrorDialog("导出失败", SpkError.format(msg)
                            + "\n\n" + sourceWidth + "×" + sourceHeight
                            + "\n" + probe.describe());
                    exportProgressBar.setVisibility(View.GONE);
                    setStep(2);
                });
                final int code = SpkError.codeOf(msg);
                if (code == 1001 || code == 1009) offerSmallerImport();
            } finally {
                if (tmp.exists()) tmp.delete();
            }
        }).start();
    }


    /**
     * `Class: message`, then its cause chain. A native library that fails to
     * load says *why* in the cause (`dlopen failed: library "libz.so.1" not
     * found`, `cannot locate symbol ...`), and that sentence is the entire bug
     * report. The old form printed only the outer class name.
     */
    private static String describe(Throwable t) {
        final StringBuilder b = new StringBuilder();
        for (Throwable c = t; c != null && b.length() < 600; c = c.getCause()) {
            if (b.length() > 0) b.append(" <- ");
            b.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
            if (c.getCause() == c) break;
        }
        return b.toString();
    }

    private static void deleteTree(java.io.File f) {
        if (f == null) return;
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static final Object RESOURCE_LOCK = new Object();

    /**
     * The asset tree is ~30 MB; copying it on every import froze the picker for
     * seconds and was why a second photo only worked after a restart. The copy
     * now runs once per installed build, keyed by the version code, so an
     * upgrade still refreshes a stale .spv (which would otherwise show up as a
     * wrong-sized picture rather than an error).
     */
    private String getResourcesDir() throws java.io.IOException {
        java.io.File dir = new java.io.File(getFilesDir(), "resources");
        java.io.File marker = new java.io.File(dir, ".bundle");
        final String want = bundleVersion();
        if (marker.isFile() && want.equals(readSmallFile(marker))) return dir.getAbsolutePath();
        synchronized (RESOURCE_LOCK) {
            if (marker.isFile() && want.equals(readSmallFile(marker))) return dir.getAbsolutePath();
            final long t0 = System.currentTimeMillis();
            deleteTree(dir);
            copyAssetTree("resources", dir);
            try (java.io.FileWriter fw = new java.io.FileWriter(marker)) { fw.write(want); }
            try {
                DebugSink.text("resources", "copied in " + (System.currentTimeMillis() - t0)
                        + " ms (bundle " + want + ")");
            } catch (Throwable ignored) {}
        }
        return dir.getAbsolutePath();
    }

    private String bundleVersion() {
        try {
            return String.valueOf(getPackageManager().getPackageInfo(getPackageName(), 0).versionCode);
        } catch (Throwable t) {
            return "0";
        }
    }

    private static String readSmallFile(java.io.File f) {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 32)];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return new String(b, 0, off, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private void copyAssetTree(String assetPath, java.io.File outDir) throws java.io.IOException {
        String[] files = getAssets().list(assetPath);
        if (files == null || files.length == 0) {
            java.io.File parent = outDir.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream in = getAssets().open(assetPath);
                 java.io.OutputStream out = new java.io.FileOutputStream(outDir)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            }
            return;
        }
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new java.io.IOException("Cannot create directory: " + outDir);
        }
        for (String name : files) {
            copyAssetTree(assetPath + "/" + name, new java.io.File(outDir, name));
        }
    }

    @Override
    protected void onPause() {
        // The system can reclaim this process the moment another app needs the
        // memory, and a 20 MP frame plus a Vulkan context is exactly what it
        // reclaims first. Save before it does, not in onDestroy, which is not
        // called on a kill.
        try { saveLookIntoFrames(); } catch (Throwable ignored) { }
        saveSessionState();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopPrefetch();
        // Session *and* engine go down under the same lock the render runs
        // under: a shelf card that is mid-`renderBegin` is holding device
        // buffers owned by this engine, and tearing it down outside the lock
        // is the same destroyed-buffer SIGSEGV one layer up.
        synchronized (renderMutex) {
            if (session != null) { session.close(); session = null; }
            if (engine != null) { engine.close(); engine = null; }
        }
        super.onDestroy();
    }
}
