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
    // **Native by default.** The memory number is a suggestion, and a
    // suggestion that silently shrinks the file is a limit wearing a different
    // name. A frame above it still renders -- slower, and on a small device it
    // may fail -- but the user asked for their photograph, not for a downscale.
    // **Set by the import sheet, for that import only.** Every import larger
    // than the memory estimate asks again: the number is advice, and the answer
    // is the user's.
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
    // `volatile`: a batch export polls it from a worker while the UI thread
    // assigns it after a decode.
    private volatile SpektraLabEngine.Session session;
    private boolean isRawFile = false;
    /** Set in `onPause`, cleared in `onResume`. `startPrefetch` refuses to open
     *  a queue while this is set: the ring in the 1.5.0o report shows fifteen
     *  `prefetch:card` lines *after* `app:paused`, which is fifteen small
     *  renders holding the GPU while the system is deciding whether to reclaim
     *  the process. */
    private boolean appPaused = false;
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
    /** The pixel budget this import was given. Re-reading it after the decode
     *  reports a smaller number -- the decode itself has taken the memory --
     *  so the dialog ended up quoting a cap it never applied. */
    private int importCapPixels = 0;
    // The actual decoded frame, not the RAW sensor's dimensions. The latter can
    // be 51 MP while this session holds 12.8 MP, and export must open the same
    // frame -- never a second decode at a different size.
    private int importedWidth = 0, importedHeight = 0;
    // Composition is part of the frame's Look, and goes through the engine's
    // geometry node -- never a second bitmap transform at export time.
    private int geometryTurns = 0;
    /** 构图 page: which sub-page is showing (0 crop, 1 rotate). */
    private int composeSubPage = 0;
    /** The crop card that is lit: -1 original, otherwise an EDGE_* index. */
    private int cropRatioIndex = -1;
    // **A crop is a proposal until you press 应用.** Dragging the frame used
    // to write `geometry_crop_*` and render on release, which had two costs:
    // the picture changed under the frame you were still adjusting, and once
    // the canvas showed the *cropped* result the frame -- drawn in the same
    // normalised space as the engine's window -- was applied a second time and
    // sat off the image ("裁切后边框没有跟着图片"). So the frame edits these,
    // relative to whatever the canvas is showing right now, and 应用 folds
    // them into `geometryCrop*` once.
    private float pendingCropX = 0f, pendingCropY = 0f, pendingCropW = 1f, pendingCropH = 1f;
    private boolean geometryFlipH = false, geometryFlipV = false;
    private LinearLayout composeBody;
    private TextView composeCropTab, composeRotateTab;
    /** The ratio strip, kept so choosing a card does not scroll back to zero. */
    private HorizontalScrollView cropScroll;
    private int cropScrollX = 0;
    /** The before/after toggle, bottom-right of the canvas. */
    private TextView comparePill;
    /** The canvas itself, so a window-level pinch knows where the picture is. */
    private android.widget.FrameLayout canvasStage;

    // **The pinch lives at the window, not in a child view.** Two attempts to
    // put it inside `CompareView` produced no movement at all, which means the
    // second pointer never reached that view -- whatever sits above it, this
    // override is above that too.
    private boolean canvasPinching = false;
    private float canvasPinchStartSpan = 0f, canvasPinchStartZoom = 1f;
    private float canvasPinchStartCx = 0f, canvasPinchStartCy = 0f;
    private float canvasPinchStartPanX = 0f, canvasPinchStartPanY = 0f;
    /** The draggable frame over the canvas, alive only on 构图 → 裁切. */
    private CropOverlay cropOverlay;
    private float geometryRotation = 0.0f;
    private float geometryCropW = 1.0f, geometryCropH = 1.0f;
    private float geometryCropX = 0.0f, geometryCropY = 0.0f;
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
    /**
     * The engine's `camera.auto_exposure`, which is also the "As Shot" switch.
     *
     * <p>On (the default, and the original app's default): the meter picks the
     * film exposure, `balanced` at that. Off: the meter contributes nothing and
     * the frame is exposed as the camera recorded it -- `setFilmExposureAsShot`
     * upstream, and the only way back to the dim frame the file actually holds.
     */
    // **On by default, and only RAW reads it.** A decoded RAW frame is linear
    // and dark -- the meter is the only thing that lifts it to the exposure the
    // camera metered, and without it the shadows get pushed up in the grade and
    // the noise comes with them ("RAW looks dirty"). JPEG ignores this flag
    // entirely: it is already display-encoded, so metering it twice is the bug
    // 1.2.9 fixed.
    /** **Which meter, not whether to meter.** `""` is off; every other value is
     *  one of the seven names the engine implements (`balanced`, `center`,
     *  `protect_highlights`, `protect_shadows`, `center_weighted`, `average`,
     *  `median`). A switch could only offer two of those, and the two it offered
     *  were not the interesting pair: on the dark-frame reference,
     *  `balanced` lifts +5.4 EV and `protect_highlights` +2.4, so "on" meant
     *  either a grey wash or a kept window depending on a constant nobody could
     *  see. */
    private String meterMethod = "protect_highlights";
    private TextView meterChip;
    /** The EDR switch in the print panel; `edrOn` is still the value. */
    private Square edrMark;
    private Square hueOwnMark;

    /** Order is the sheet's order; `""` first so "off" is the first row. */
    private static final String[] METER_IDS = {
            "", "balanced", "protect_highlights", "protect_shadows",
            "center", "center_weighted", "average", "median"};
    private static final String[] METER_NAMES = {
            "关闭", "平衡", "保护高光", "保留暗部", "中心", "中心加权", "平均", "中位数"};
    private static final String[] METER_NOTES = {
            "不测光，用文件自己的曝光",
            "全幅中位数对准中灰；暗场景会被提亮好几挡",
            "平衡再压住高光两挡；有灯的画面不会糊成一片白",
            "平衡再抬起暗部；阴影留住，亮部跟着走",
            "只按画面中心测，忽略四周",
            "中心高斯加权后取平均——旧版默认，最容易被高光带跑",
            "整幅算术平均，完全不加权",
            "整幅中位亮度，最不容易被单个亮斑左右"};

    // ── undo ──────────────────────────────────────────────────────
    /** One JSON snapshot per settled edit, newest first. */
    private final java.util.ArrayDeque<String> undoStack = new java.util.ArrayDeque<>();
    private static final int UNDO_LIMIT = 400;
    /** The last snapshot pushed, so a re-render of the same state is free. */
    private String undoLast = null;
    private final android.os.Handler undoHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * **The look goes to disk after every finished render, not on the way out.**
     *
     * <p>Swiping the app out of recents kills the process without `onPause` on
     * this ROM, so the only place the edit was being written -- `onPause` --
     * could simply never run. The chip survived (it had been saved when the
     * sheet opened) but its Look did not: the next launch restored
     * `filmStock = null` and showed the original. Debounced here instead, so
     * "edit, then clear the background" keeps the edit.
     */
    private final android.os.Handler persistHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable persistSession = () -> {
        try { saveLookIntoFrames(); } catch (Throwable ignored) { }
        saveSessionState();
    };

    private void schedulePersist() {
        persistHandler.removeCallbacks(persistSession);
        persistHandler.postDelayed(persistSession, 1200);
    }
    private final Runnable undoPush = this::pushUndoNow;
    private android.widget.ImageView undoPill;
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
    /**
     * How far the in-emulsion scatter spreads, 0..1 -> 1x..12x the film's own
     * radius. The film's radius is sub-pixel, so at 0 the scatter slider has
     * nothing to show; this one is what makes it a visible diffusion. 0 is
     * the physical value and the default, so a frame that never touches it
     * renders exactly as before.
     */
    private float scatterRadius = 0.0f;
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
    // Five engine knobs that had no home in the app (see the schema diff):
    // the scanner's own lens, the halo's headroom, the density curve's gamma,
    // and the two switches that pick a different output path.
    private float glareActive = 1f;
    private float scannerLensBlur = 0f, halationHeadroom = 4f, densityGamma = 1f;
    private boolean digitalIntermediate = false, outputCctf = true;
    // 1 = undo the measured hue drift completely (greens land where the source
    // had them); 0 = leave each stock's own colour alone. The correction is what
    // made every stock converge on one palette -- the "no film character" report.
    private float hueCorrection = 1f;
    /**
     * Stocks whose green drift the spectral model gets wrong by more than 20
     * degrees on both test frames. Correcting them measures worse than leaving
     * them alone -- Vision3 500T moves 52 -> 49 against a source of 77 -- so the
     * correction is pinned off and the switch is read-only. The preference is
     * kept in {@link #hueCorrection}; switching to a supported stock restores it.
     */
    private static final java.util.Set<String> HUE_FIX_UNSUPPORTED =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "kodak_vision3_200t", "kodak_vision3_250d", "kodak_vision3_500t",
                    "kodak_vision3_50d", "cinestill_800t", "kodak_verita_200d",
                    "fujifilm_pro_400h"));
    private float shadowRolloff = 2f, maxLift = 4f;
    private String cameraFilter = "", sceneNorm = "power";
    private String edgeMode = "strip", edgeGate = "auto", edgeHoles = "white", edgeCarrier = "none";
    private String datePlacement = "frame", dateCorner = "br", dateStyle = "lcd";
    private float dateInsetX = 3f, dateInsetY = 2.4f;
    private float edgeFNumber = 0f, dateSize = 1f, dateEv = 3.5f;
    // A drag on the canvas: where the finger started, and where the imprint's
    // centre was at that moment (both normalised to the picture).
    private boolean dateDragOn = false;
    private float dateDragU0, dateDragV0, dateDragCX, dateDragCY;
    private boolean edgeTextOn = true;
    private String dataText = "", dateTextB = "";
    private int cameraSeed = 1, frameSeed = 1;
    private float filmFormatMm = 36.0f;    // frame's long edge, 135 = 36 mm
    private boolean uiReady = false;
    private String lastImportError = null;
    /// Where `reportPreviousExit` left the file, if there was one this launch.
    private String lastCrashFile = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // **Before anything logs, and before anything reports.** The ring lives
        // in memory; init only opens the one-slot retry file. A crash report
        // shipped from `uploadCrashFiles` needs that file when the network is
        // down, so init has to come first.
        DebugSink.init(this);
        installCrashLogger();
        // **A native death leaves nothing for the Java handler.** Install the
        // signal handler before the engine exists -- the first dispatch can
        // crash -- and ship whatever the last death wrote.
        installNativeCrashHandler();
        uploadCrashFiles();
        // Before anything else touches the engine: what the last launch was
        // doing, and how it ended. See `reportPreviousExit`.
        // **Read before writing.** This used to mark first and report second, so
        // the trail the report read was always the one this launch had just
        // written -- which made every single start look like a crash. Report the
        // previous launch, then record this one.
        reportPreviousExit();
        // **Before the first layout.** A palette applied afterwards would mean
        // rebuilding every widget, so the preference is read once here and the
        // switch asks for a restart.
        applyPalette(getSharedPreferences("ui", MODE_PRIVATE).getBoolean("dark", false));
        CrashTrail.mark(this, "app:onCreate");
        // The native side, before anything reads it: every object the engine
        // is linked against is loaded by path, and a failure is kept (see
        // SpektraLabEngine.ensureLoaded) so that the import reports the real
        // reason instead of a NoClassDefFoundError on the class itself.
        String nativeDir = null;
        try { nativeDir = getApplicationInfo().nativeLibraryDir; } catch (Throwable ignored) {}
        try { SpektraLabEngine.prepare(nativeDir); } catch (Throwable e) { lastImportError = describe(e); }
        // **Recorded, not uploaded.** `app-start` used to be a POST on every
        // launch, and it alone was enough to fill the free D1 quota over a few
        // hundred users. It now goes into the in-memory ring, which is what a
        // report carries when something actually goes wrong.
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
    // **The palette is a variable, not seven constants.** The app's whole look
    // is read off these seven -- panels, text, strokes, the canvas -- so making
    // them mutable and setting them from a preference is the entire theming
    // system: no per-widget branches, no second layout.
    private static int UI_BLUE       = 0xFF1C2AA5;   // accent, on paper
    private static int UI_BLUE_LIGHT = 0xFF3B4BC0;   // accent, lighter
    private static int UI_PAPER      = 0xFFF1F1EE;   // panel background
    private static int UI_GROUND     = 0xFFE3E3E0;   // card background
    private static int UI_INK        = 0xFF171717;   // text
    private static int UI_MUTED      = 0xFF777772;   // secondary text
    private static int UI_LINE       = 0xFFCECEC8;   // strokes
    private static int UI_STAGE      = 0xFF172174;   // the canvas
    /**
     * Ink for anything that sits on a white disc.
     *
     * <p>The compare pill is white in both themes, so its glyph cannot be
     * `UI_INK` -- the dark palette's ink is a light grey and would vanish into
     * the disc. This stays dark in both.
     */
    private static final int UI_ON_WHITE  = 0xFF171717;

    /**
     * The unchecked outline of a `Square`.
     *
     * <p>It was the literal `0x66171717` -- a 40% black hairline that is
     * invisible on the dark theme's panel, which is why "开启片边" and 时间水印
     * became un-clickable-looking holes on a black background.
     */
    /** The two switch rows, so the page's dimming can skip them. */
    private View overscanRow, dateRow;

    private static int uiToggleOff() { return darkTheme ? 0xFF7A736A : 0x66171717; }

    /**
     * Text that sits on the canvas.
     *
     * <p>`UI_PAPER` is the *panel* colour: white on the light theme, near-black
     * on the dark one. Every canvas label used it, which is why the wordmark,
     * "RAW LOADED", the file name and the status line went invisible the moment
     * the canvas turned black. On the canvas the contrast colour is what is
     * wanted, and it is white in both themes.
     */
    private static int uiOnCanvas() { return 0xFFF1F1EE; }
    /** True when the dark palette is in force; the canvas chrome asks. */
    private static boolean darkTheme = false;

    /**
     * Orange on black: the same seven roles, re-coloured. Orange is the accent
     * exactly where blue was -- selection outlines, the undo glyph, a link.
     * Panels are dark grey rather than pure black, because a pure-black panel
     * next to a pure-black canvas gives the frame no edge to sit against.
     */
    private static void applyPalette(boolean dark) {
        darkTheme = dark;
        if (dark) {
            UI_BLUE       = 0xFFE8862A;   // orange, readable on black
            UI_BLUE_LIGHT = 0xFFFFA54F;
            UI_PAPER      = 0xFF171717;
            UI_GROUND     = 0xFF242320;
            UI_INK        = 0xFFEDEAE4;
            UI_MUTED      = 0xFF9A948A;
            UI_LINE       = 0xFF3A352F;
            UI_STAGE      = 0xFF000000;   // **the canvas is black**
        } else {
            UI_BLUE       = 0xFF1C2AA5;
            UI_BLUE_LIGHT = 0xFF3B4BC0;
            UI_PAPER      = 0xFFF1F1EE;
            UI_GROUND     = 0xFFE3E3E0;
            UI_INK        = 0xFF171717;
            UI_MUTED      = 0xFF777772;
            UI_LINE       = 0xFFCECEC8;
            UI_STAGE      = 0xFF172174;
        }
    }

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
        /**
         * **A shoot-layer field, so a drag re-develops the film.**
         *
         * <p>The delta names only what moved, and the engine drops the cached
         * negative for anything on the shoot layer -- grain, halation, the
         * latitude pair, the couplers, the lens. Everything else reprints from
         * that cache in about a thirtieth of a second. Without this the user
         * has two sliders that behave differently and no way to know which is
         * which; the readout row now says it.
         */
        final boolean heavy;
        Knob(String label, String glyph, float lo, float hi,
             java.util.function.Consumer<Float> set, java.util.function.Supplier<Float> get,
             boolean heavy) {
            this.label = label; this.glyph = glyph; this.lo = lo; this.hi = hi;
            this.set = set; this.get = get; this.heavy = heavy;
        }
    }
    private final java.util.List<Knob> knobs = new java.util.ArrayList<>();
    private int activeKnob = 0;
    private TextView knobTitle, knobValue, knobHintView, knobCost;
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
    /**
     * Portrait crops, appended after the gates.
     *
     * <p>A gate is a landscape frame, so every card above it is landscape --
     * which left a portrait picture with no way to say "3:4" except by cropping
     * to a landscape ratio and then rotating. These are their own list because
     * they are <em>not</em> gates: nothing is printed on the film for them, so
     * they change the window only.
     */
    private static final String[] PORTRAIT_LABELS = {"2:3", "3:4", "4:5"};
    private static final float[] PORTRAIT_RATIOS = {2f / 3f, 3f / 4f, 4f / 5f};
    /**
     * The gate each portrait card is the portrait of.
     *
     * <p>A rebate is printed by a frame of a fixed physical shape, so a crop
     * that is not that shape cannot carry one -- which is why 9:16 (no film is
     * 16:9 portrait) was refused by the engine on every gate. These three are
     * the portrait forms of real gates, so choosing one can carry its gate
     * across and the render succeeds.
     */
    private static final String[] PORTRAIT_GATES = {"135", "135_half", "120_6x7"};
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
    /** Each gate's frame as a ratio caption. Read off `EDGE_RATIOS`, rounded to
     *  what the eye calls it -- 69.5/56 is 1.241, and everyone says 5:4. */
    private static final String[] EDGE_RATIO_LABELS = {
            "3:2", "4:3", "65:24", "4:3", "1:1", "5:4", "4:3", "3:2", "2:1", "3:1"};

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
        private final int thumbSlop;
        private float downX;
        private boolean tracking;
        private boolean dragging;
        private long lastUpMs;
        private int resetProgress = -1;
        private Runnable onReset;

        ScrubBar(android.content.Context c) {
            super(c);
            thumbSlop = (int) (26 * c.getResources().getDisplayMetrics().density);
        }

        @Override public void setOnSeekBarChangeListener(SeekBar.OnSeekBarChangeListener l) {
            listener = l;
            super.setOnSeekBarChangeListener(l);
        }

        /** Double-tap on the thumb returns the value here. */
        void setResetTarget(int progress, Runnable action) {
            resetProgress = progress;
            onReset = action;
        }

        private float thumbX() {
            final int span = Math.max(1, getWidth() - getPaddingLeft() - getPaddingRight());
            return getPaddingLeft() + span * (getProgress() / (float) Math.max(1, getMax()));
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent ev) {
            if (!isEnabled()) return false;
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    // **Only the thumb moves.** A tap on the track does nothing,
                    // and a gesture that starts away from the thumb belongs to
                    // the list underneath -- which is how Lightroom and
                    // LightCraft both behave. Dragging from anywhere (what this
                    // used to do) fights every scroll.
                    downX = ev.getX();
                    if (Math.abs(downX - thumbX()) > thumbSlop) return false;
                    final long now = android.os.SystemClock.uptimeMillis();
                    if (resetProgress >= 0 && now - lastUpMs < 300) {
                        setProgress(resetProgress);
                        if (onReset != null) onReset.run();
                        lastUpMs = 0;
                        return true;
                    }
                    tracking = true;
                    dragging = false;
                    setPressed(true);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (!tracking) return false;
                    if (!dragging) {
                        dragging = true;
                        if (listener != null) listener.onStartTrackingTouch(this);
                    }
                    return seekTo(ev.getX());
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    if (!tracking) return false;
                    tracking = false;
                    setPressed(false);
                    lastUpMs = android.os.SystemClock.uptimeMillis();
                    if (dragging) {
                        dragging = false;
                        if (listener != null) listener.onStopTrackingTouch(this);
                    }
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                }
                default:
                    return true;
            }
        }

        /** **`onProgressChanged`'s `fromUser` is always false here.** The bar
         *  drives itself through `setProgress`, which `AbsSeekBar` forwards with
         *  `fromUser = false`; the flag only means "the framework's own track
         *  touch ran", and this bar overrides that. Dragging the thumb is the
         *  real question a preview needs answered, so it is asked directly. */
        boolean isDragging() { return dragging; }

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
        /** The LibRaw gains that produced this frame's preview. White balance
         *  lives in the demosaic, not in `spk_set_params`, so a frame reopened
         *  from the strip has to carry them or it comes back as-shot while the
         *  sliders still say 4000 K. Null = as-shot. */
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
        /** The manufacturer's edge print: its own switch, because it is the
         *  one part of the rebate that has been landing inside the picture. */
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
         * The five curve channels (rgb, luma, r, g, b), each a flat
         * x,y,x,y,... list. A null entry means "never touched", which is what
         * `defaultLook` leaves behind and `applyLook` turns back into the
         * default two points.
         */
        final float[][] curves = new float[5][];
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
            o.put("mtr", l.meterMethod == null ? "" : l.meterMethod);
            o.put("grain", (double) l.grainAmount);
            o.put("halation", (double) l.halationAmount);
            o.put("glare", (double) l.glareAmount);
            o.put("highlight", (double) l.highlightRoom);
            o.put("shadow", (double) l.shadowRoom);
            o.put("boost", (double) l.halationBoost);
            o.put("scatter", (double) l.halationScatter);
            o.put("scatterr", (double) l.scatterRadius);
            o.put("lens", (double) l.lensBlur);
            o.put("vT", (double) l.viewTemperature);
            o.put("vTn", (double) l.viewTint);
            o.put("vE", (double) l.viewExposure);
            o.put("vC", (double) l.viewContrast);
            o.put("vB", (double) l.viewBrightness);
            o.put("vS", (double) l.viewSaturation);
            o.put("vHi", (double) l.viewHighlights);
            o.put("vSh", (double) l.viewShadows);
            o.put("vBk", (double) l.viewBlack);
            o.put("vWh", (double) l.viewWhite);
            o.put("vV", (double) l.viewVignette);
            o.put("cEv", (double) l.cameraEv);
            o.put("vVb", (double) l.viewVibrance);
            o.put("wb", l.wbPreset);
            o.put("wbK", (double) l.wbKelvin);
            o.put("wbT", (double) l.wbTint);
            if (l.wbMul != null && l.wbMul.length == 4) {
                final org.json.JSONArray wbArr = new org.json.JSONArray();
                for (float v : l.wbMul) wbArr.put((double) v);
                o.put("wbm", wbArr);
            }
            o.put("gturn", l.geometryTurns);
            o.put("grot", (double) l.geometryRotation);
            o.put("gcw", (double) l.geometryCropW);
            o.put("gch", (double) l.geometryCropH);
            o.put("gcx", (double) l.geometryCropX);
            o.put("gcy", (double) l.geometryCropY);
            o.put("gcri", l.cropRatioIndex);
            o.put("gfh", l.geometryFlipH);
            o.put("gfv", l.geometryFlipV);
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
            o.put("gla", (double) l.glareActive);
            o.put("slb", (double) l.scannerLensBlur);
            o.put("hhe", (double) l.halationHeadroom);
            o.put("dcg", (double) l.densityGamma);
            o.put("dint", l.digitalIntermediate);
            o.put("occtf", l.outputCctf);
            o.put("hcor", l.hueCorrection);
            o.put("rolloff", (double) l.shadowRolloff);
            for (int i = 0; i < 4; ++i) {
                o.put("cbh" + i, (double) l.cbHue[i]);
                o.put("cbs" + i, (double) l.cbSat[i]);
                o.put("cbl" + i, (double) l.cbLum[i]);
            }
            for (int i = 0; i < 5; ++i) {
                final float[] flat = l.curves[i];
                if (flat == null) continue;
                final org.json.JSONArray arr = new org.json.JSONArray();
                for (int k = 0; k < flat.length; ++k) arr.put((double) flat[k]);
                o.put("cv" + i, arr);
            }
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
        // `mm` is the name; an older state has only the boolean, and `true`
        // there meant exactly this default.
        // **Not `mm`** -- that key already belonged to `filmFormatMm`, and the
        // two writes landed in the same object: the format (24.0) overwrote the
        // meter name, and the next render sent `auto_exposure_method: "24"`,
        // which the engine refuses. `mtr` is this field's own key.
        l.meterMethod = sanitizeMeter(o.has("mtr") ? o.optString("mtr", "protect_highlights")
                : (o.optBoolean("meter", true) ? "protect_highlights" : ""));
        l.grainAmount = (float) o.optDouble("grain", 1.0);
        l.halationAmount = (float) o.optDouble("halation", 1.0);
        l.glareAmount = (float) o.optDouble("glare", 1.0);
        l.highlightRoom = (float) o.optDouble("highlight", 0.0);
        l.shadowRoom = (float) o.optDouble("shadow", 0.0);
        l.halationBoost = (float) o.optDouble("boost", 0.0);
        l.halationScatter = (float) o.optDouble("scatter", 1.0);
        l.scatterRadius = (float) o.optDouble("scatterr", 0.0);
        l.lensBlur = (float) o.optDouble("lens", 0.0);
        l.viewTemperature = (float) o.optDouble("vT", 0.0);
        l.viewTint = (float) o.optDouble("vTn", 0.0);
        l.viewExposure = (float) o.optDouble("vE", 0.0);
        l.viewContrast = (float) o.optDouble("vC", 0.0);
        l.viewBrightness = (float) o.optDouble("vB", 0.0);
        l.viewSaturation = (float) o.optDouble("vS", 0.0);
        l.viewHighlights = (float) o.optDouble("vHi", 0.0);
        l.viewShadows = (float) o.optDouble("vSh", 0.0);
        l.viewBlack = (float) o.optDouble("vBk", 0.0);
        l.viewWhite = (float) o.optDouble("vWh", 0.0);
        l.viewVignette = (float) o.optDouble("vV", 0.0);
        l.cameraEv = (float) o.optDouble("cEv", 0.0);
        l.viewVibrance = (float) o.optDouble("vVb", 0.0);
        l.wbPreset = o.optInt("wb", 0);
        l.wbKelvin = (float) o.optDouble("wbK", 5500.0);
        l.wbTint = (float) o.optDouble("wbT", 0.0);
        final org.json.JSONArray wbArr = o.optJSONArray("wbm");
        if (wbArr != null && wbArr.length() == 4) {
            l.wbMul = new float[4];
            for (int i = 0; i < 4; ++i) l.wbMul[i] = (float) wbArr.optDouble(i, 1.0);
        }
        l.geometryTurns = o.optInt("gturn", 0);
        l.geometryRotation = (float) o.optDouble("grot", 0.0);
        l.geometryCropW = (float) o.optDouble("gcw", 1.0);
        l.geometryCropH = (float) o.optDouble("gch", 1.0);
        l.geometryCropX = (float) o.optDouble("gcx", 0.0);
        l.geometryCropY = (float) o.optDouble("gcy", 0.0);
        l.cropRatioIndex = o.optInt("gcri", -1);
        l.geometryFlipH = o.optBoolean("gfh", false);
        l.geometryFlipV = o.optBoolean("gfv", false);
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
        l.glareActive = (float) o.optDouble("gla", 1.0);
        l.scannerLensBlur = (float) o.optDouble("slb", 0.0);
        l.halationHeadroom = (float) o.optDouble("hhe", 4.0);
        l.densityGamma = (float) o.optDouble("dcg", 1.0);
        l.digitalIntermediate = o.optBoolean("dint", false);
        l.outputCctf = o.optBoolean("occtf", true);
        l.hueCorrection = (float) o.optDouble("hcor", 1.0);
        // **1.5.4a shipped two wrong defaults for a few hours.** It sent
        // `halation_headroom_ev` 0 (engine default 4) and `output_cctf_encoding`
        // false (engine default true); either alone darkens every frame, and
        // together they made the print look broken. No UI could reach those
        // fields then, so a stored pair of exactly 0 + false can only be that
        // build's fingerprint -- lift it to what the engine actually uses.
        if (l.halationHeadroom <= 0f && !l.outputCctf) {
            l.halationHeadroom = 4f;
            l.outputCctf = true;
        }
        l.shadowRolloff = (float) o.optDouble("rolloff", 2.0);
        for (int i = 0; i < 4; ++i) {
            l.cbHue[i] = (float) o.optDouble("cbh" + i, 0.0);
            l.cbSat[i] = (float) o.optDouble("cbs" + i, 0.0);
            l.cbLum[i] = (float) o.optDouble("cbl" + i, 0.0);
        }
        for (int i = 0; i < 5; ++i) {
            final org.json.JSONArray arr = o.optJSONArray("cv" + i);
            if (arr == null) continue;
            final float[] flat = new float[arr.length()];
            for (int k = 0; k < arr.length(); ++k) flat[k] = (float) arr.optDouble(k, 0.0);
            l.curves[i] = flat;
        }
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
            // **A frame that had a film gets its print back.** The decode is
            // the whole of `loadImage`; without this an edit that was safely
            // on disk still came back as the original, because nothing ever
            // asked for a render. The strip already says "this is the
            // photograph you were working on" -- the canvas has to agree.
            if (filmStock != null) renderImage();
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
    /** Whether 原图 shows the decode as measured (off) or auto-levelled (on).
     *  Off by default because the caption promises 未调色: measured against
     *  Lightroom on a OnePlus 13 DNG, the old auto level sat 2.54 EV above it. */
    private boolean rawAutoBright = false;
    /** The DNG's own display profile ([baselineEV, x0, y0, ...]) for this
     *  frame, or null. Phones ship `ProfileToneCurve`; cameras do not. */
    private float[] sourceToneCurve = null;
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

    // ── live preview ───────────────────────────────────────────────
    /** A drag asked for a picture. `liveLoop` folds every request that lands
     *  while a frame is being drawn into the one that follows it. */
    private volatile boolean liveWanted = false;
    /** Bumped for every live frame and every cancel, so a frame that lost a
     *  race drops its bitmap instead of painting it over a newer one. */
    private final java.util.concurrent.atomic.AtomicInteger liveGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Bumped whenever a full-size render starts. A live frame that was already
     *  queued behind `renderMutex` when that happened must never paint: it would
     *  put 800 px back on top of the 2048 px result the user just waited for. */
    private final java.util.concurrent.atomic.AtomicInteger fullGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Which session `deltaSent` describes, and the last value sent for each
     *  key. Written only under `renderMutex`, by the thread that draws. A
     *  different session object clears both: a delta describes a session, not
     *  the app. */
    private SpektraLabEngine.Session deltaSession = null;
    private final java.util.HashMap<String, String> deltaSent = new java.util.HashMap<>();
    private final Object liveLock = new Object();
    private Thread liveThread = null;
    private long liveLastStart = 0;
    /** Reused scratch for `copyArgbRange`: a 0.43 MP frame is 1.7 MB a tick. */
    private int[] livePixels = null;
    /** How much of the meter's EV the decoded frame is shown at. */
    private static final double SOURCE_VIEW_EV_SCALE = 0.85;

    // **The view layer.** These run on the finished print -- screen and every
    // exported file -- and are skipped entirely while all of them are zero, so
    // the engine's own output is byte-for-byte what it was.
    private float viewTemperature = 0f, viewTint = 0f, viewExposure = 0f;
    private float viewContrast = 0f, viewBrightness = 0f, viewSaturation = 0f;
    private float viewHighlights = 0f, viewShadows = 0f;
    private float viewBlack = 0f, viewWhite = 0f, viewVignette = 0f;
    /** The camera's own exposure offset, in stops: applied by the engine. */
    private float cameraEv = 0f;
    /**
     * The exposure the engine's meter actually applied.
     *
     * <p>**It is not `exposure_ev_by_method.balanced`.** `node_exposure` uses
     * `camera.exposure_compensation_ev`, and `spk_solve` puts the metered value
     * for the *chosen* method there — `solved_params.exposure_compensation_ev`.
     * `balanced` is a different number (0.52 vs 0.0756 on the frame that
     * exposed this), and `spk_get_params` never sees either: the applied value
     * is runtime, not part of the tree.
     *
     * <p>**Not the EV to hand an export.** This is the *enlarger* solution
     * (`solved_params.exposure_compensation_ev`, 0.97 on the frame that
     * started this); the meter's own answer is `lastAppliedEv`, and that is
     * what {@link #appliedEvBelongsToCurrentFrame} guards. Kept for the log
     * line, where the two numbers side by side are what showed they differ.
     */
    private double meteredEv = 0.0;
    /**
     * The EV the last **successful render** actually applied, read back from
     * `progressJson()`'s `auto_exposure_ev`.
     *
     * <p>Three different numbers live around exposure and only one of them is
     * the picture: `camera.exposure_compensation_ev` in the parameter tree is
     * always 0 (the applied EV is runtime, not part of the tree),
     * `solve`'s answer is what the meter *would* apply, and this one is what
     * `node_auto_exposure` **did** apply to the frame on the canvas. An export
     * that has to reproduce the preview pins this, and turns the meter off so
     * the fresh session cannot decide a second time on a frame it sees
     * differently -- that second decision is what printed a 20.9 MP negative as
     * a white field with only the rebate left (ref SL-261107-503A: exported
     * mean 224/223/217 against a canvas of 103, 67 % of it past 250).
     */
    private double lastAppliedEv = 0.0;
    private boolean lastAppliedEvValid = false;
    /**
     * Which frame {@link #lastAppliedEv} belongs to, as that frame's URI.
     *
     * <p>**An EV without a frame is a number that will be applied to the wrong
     * picture.** A batch export walks the strip: `reopenFrame` swaps the frames
     * and closes the session, but nothing renders in between, so
     * `lastAppliedEv` is still the *previous* frame's. Pinning that would give
     * every file its neighbour's exposure. The URI makes the check exact, and
     * the fallback -- no pin -- is simply the meter, which is what the canvas
     * would have got anyway.
     */
    private String lastAppliedEvUri = null;
    /** The last value already written to the sink, so a live drag stays quiet. */
    private double loggedEv = Double.NaN;
    /** Vignette falloff start, as a percentage of the half-diagonal. */
    private static final float VIEW_VIGNETTE_MID = 50f;

    private static final int PREVIEW_LONG_EDGE = 2048;
    /** The engine refuses `preview_long_edge` below 800 (params.cpp), so the
     *  catalogue pass uses exactly that floor: the smallest legal frame. */
    private static final int PREFETCH_LONG_EDGE = 800;
    /** The live-preview size: 800 is the floor the engine accepts, and it is
     *  also the fastest this pipeline can draw. Measured with `live_probe` on
     *  a 10.7 MP frame (Adreno 825): 430 ms a frame at 800 with the rebate on,
     *  against 3.9 s at 2048 for the same frame. Releasing the thumb was the
     *  only thing that used to draw, so every gesture ended in a two-to-four
     *  second wait; a frame every 0.43 s is what makes the canvas track. */
    private static final int LIVE_LONG_EDGE = 800;
    /** Two live frames closer together than this are the same gesture. The
     *  render itself is ten times this, so the gap only stops a burst of
     *  requests from re-arming the loop right after a frame lands. */
    private static final long LIVE_MIN_GAP_MS = 90;
    private final List<View> formatCards = new ArrayList<>();
    private static final int CONTROLS_OPEN_DP = 298;
    private static final int CONTROLS_CLOSED_DP = 58;
    /** 反馈群：the one place a user can reach a human. Shown once per build. */
    private static final String QQ_GROUP = "1134320449";
    // **A hidden pixel ceiling, for one purpose: letting a tester name a size
    // the device's free-memory estimate would not pick.** `maxImportPixels`
    // no longer consults the driver's binding range at all -- the engine bands
    // every pass that would need a whole plane (docs/tiling.md, P0) -- so what
    // this overrides is the memory suggestion and nothing else. A frame above
    // it is still the user's call: the import sheet offers shrink or force.
    private static final String DEBUG_PREFS = "debug";
    private static final String DEBUG_CAP_MP = "pixel_cap_mp";
    private static final String DEBUG_SIM_MIB = "storage_limit_mib";
    private static final String PROMO_PREFS = "promo";
    private static final String PROMO_SEEN_CODE = "qq_group_seen_code";
    private static final String PROMO_MUTED = "qq_group_muted";

    private static final String[] BRAND_IDS = {"all", "kodak", "fujifilm", "ilford", "cinestill"};
    private static final String[] BRAND_NAMES = {"全部", "KODAK", "FUJIFILM", "ILFORD", "CINESTILL"};
    private static final String[] FORMAT_NAMES = {"135 全画幅", "135 半格", "135 XPan", "645", "6×6", "6×7", "6×9", "6×12", "6×17"};
    private static final float[] FORMAT_MM = {36f, 24f, 65f, 56f, 56f, 70f, 84f, 120f, 170f};
    private final View[] tabCells = new View[6];
    private final View[] tabBars = new View[6];
    private final TextView[] tabLabels = new TextView[6];
    private android.widget.FrameLayout uiRoot;
    private TextView inAppToast;
    private final Runnable hideUiToast = () -> {
        if (inAppToast == null) return;
        inAppToast.animate().cancel();
        inAppToast.animate().alpha(0f).translationY(dp(6)).setDuration(160)
                .withEndAction(() -> { if (inAppToast != null) inAppToast.setVisibility(View.GONE); }).start();
    };
    private final View[] panels = new View[6];

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

    /**
     * Android 15 re-applies edge-to-edge when the window gains focus, which
     * silently restores the light status-bar icons it inherited from the
     * theme. Setting the appearance once in `onCreate` is therefore a no-op by
     * the time the user sees the app; it has to be re-asserted here.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) lightSystemBars();
    }

    /** The canvas owns the top edge now: white status icons on Rhine blue. */
    private void lightSystemBars() {
        try {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(UI_STAGE);
            getWindow().setNavigationBarColor(UI_PAPER);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                // **`Window.getInsetsController()` dereferences `mDecor`.** On
                // a window that has not attached yet that is a null dereference
                // *inside* the framework, before our own null check can run --
                // the `bars` NPE in the 1.5.1c trace. Go through the decor we
                // can null-check.
                final android.view.View decor = getWindow() == null ? null : getWindow().getDecorView();
                final android.view.WindowInsetsController c =
                        decor == null ? null : decor.getWindowInsetsController();
                if (c != null) {
                    // The canvas is behind the status bar, so its glyphs are white in
                    // both themes; the navigation bar sits on the panel, so its
                    // glyphs follow the panel's lightness.
                    int on = 0;
                    if (!darkTheme) on |= android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    int off = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                    c.setSystemBarsAppearance(on, on | off);
                }
            } else {
                int flags = 0;
                if (!darkTheme && android.os.Build.VERSION.SDK_INT >= 26) {
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
        final android.widget.FrameLayout stage = new android.widget.FrameLayout(this);
        canvasStage = stage;
        stage.setBackgroundColor(UI_STAGE);
        stage.setClipChildren(false);

        compare = new CompareView(this);
        // **The date can be dragged onto the picture.** Its position is
        // `corner` plus an inset in millimetres from the gate's edge -- exact,
        // and impossible to aim with two sliders. While the date page is open a
        // touch moves it; everywhere else this returns false and the canvas
        // keeps its pinch, its pan and its compare handle.
        compare.setOnTouchListener(this::onCanvasTouch);
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
        TextView rawTitle = label("RAW LOADED", 34, uiOnCanvas(), tfDisplay);
        rawTitle.setLetterSpacing(-0.055f);
        raw.addView(rawTitle);
        rawName = label("", 9, uiOnCanvas(), tfHan);
        rawName.setAlpha(0.7f);
        rawName.setLineSpacing(dp(4), 1f);
        rawName.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams rawNameLp = new LinearLayout.LayoutParams(-2, -2);
        rawNameLp.topMargin = dp(13);
        raw.addView(rawName, rawNameLp);
        stage.addView(raw, new android.widget.FrameLayout.LayoutParams(-1, -1));
        rawState = raw;

        // On the canvas, so it is the canvas's contrast colour -- `UI_PAPER`
        // is the *panel* colour and went black on the dark theme.
        busyLabel = label("RENDERING", 18, darkTheme ? UI_BLUE : uiOnCanvas(), tfDisplay);
        busyLabel.setLetterSpacing(0.14f);
        busyLabel.setGravity(android.view.Gravity.CENTER);
        // **The rendering plate, and it was a literal of the blue canvas.**
        // At 90% opacity it covered the whole canvas, which is why the dark
        // theme still looked navy while everything else had gone black. It is
        // now the canvas colour itself, so it is invisible in both themes.
        busyLabel.setBackgroundColor(0xE6000000 | (UI_STAGE & 0x00FFFFFF));
        busyLabel.setVisibility(View.GONE);
        stage.addView(busyLabel, new android.widget.FrameLayout.LayoutParams(-1, -1));

        cropOverlay = new CropOverlay();
        cropOverlay.setVisibility(View.GONE);
        stage.addView(cropOverlay, new android.widget.FrameLayout.LayoutParams(-1, -1));

        addCanvasChrome(stage);
        addPreviewFooter(stage);

        // Ink on white in both themes, because the disc is white in both -- the
        // dark palette's ink is a light grey, which would disappear into it.
        comparePill = label("↔", 15, UI_ON_WHITE, tfBody);
        comparePill.setGravity(android.view.Gravity.CENTER);
        comparePill.setVisibility(View.GONE);
        comparePill.setOnClickListener(v -> {
            if (compare == null) return;
            final boolean on = !compare.isSplitMode();
            compare.setSplitMode(on);
            comparePill.setTextColor(on ? UI_BLUE : UI_ON_WHITE);
            comparePill.setBackground(comparePillBg(on));
        });
        // **Hold it to see the original, tap it to arm the divider.** Both are
        // "compare", so both live on the same button -- and neither is a
        // gesture on the picture, which is what used to fight the pinch.
        comparePill.setOnTouchListener(new View.OnTouchListener() {
            private boolean held = false;
            private final Runnable arm = () -> {
                held = true;
                if (compare != null) compare.setHolding(true);
            };
            @Override public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        held = false;
                        v.postDelayed(arm, 320);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        v.removeCallbacks(arm);
                        if (compare != null) compare.setHolding(false);
                        if (!held) v.performClick();
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        v.removeCallbacks(arm);
                        if (compare != null) compare.setHolding(false);
                        return true;
                    default:
                        return false;
                }
            }
        });
        final android.widget.FrameLayout.LayoutParams pillLp =
                new android.widget.FrameLayout.LayoutParams(dp(40), dp(40),
                        android.view.Gravity.BOTTOM | android.view.Gravity.END);
        pillLp.rightMargin = dp(12);
        pillLp.bottomMargin = dp(30);
        stage.addView(comparePill, pillLp);

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
        panelHolder.addView(buildViewPanel(), new LinearLayout.LayoutParams(-1, -2));
        panelHolder.addView(buildFormatPanel(), new LinearLayout.LayoutParams(-1, -2));
        panelHolder.addView(buildComposePanel(), new LinearLayout.LayoutParams(-1, -2));
        panelHolder.addView(buildEdgePanel(), new LinearLayout.LayoutParams(-1, -2));
        for (int i = 0; i < panels.length; ++i) panels[i] = panelHolder.getChildAt(i);

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
    /**
     * What the previous launch was doing when it died.
     *
     * Two sources, because they answer different questions: `CrashTrail` says
     * which *step*, and `ApplicationExitInfo` says *how the process ended* -- a
     * Java exception, a native crash (the Vulkan driver taking the process with
     * it), a signal, or the low-memory killer. A Java exception has already
     * been reported by {@link #installCrashLogger}; these cover everything that
     * never reaches a Java frame, which is every crash a driver causes.
     *
     * Written to `Android/data/<pkg>/files/crash/` so a user can send it without
     * any tooling, and to the sink when one is configured.
     */
    /** The last exit's `ApplicationExitInfo` reason, or -1 when it could not be
     *  read. The dialog needs it: a process the kernel reclaimed for memory is
     *  not a crash, and telling the user "异常退出" sends them looking for a bug
     *  that is really a 600 MB allocation. */
    private int lastExitReason = -1;

    private void reportPreviousExit() {
        final StringBuilder body = new StringBuilder();
        final String trail = CrashTrail.previous(this);
        // **The last line, read from the same string the report carries.**
        // `CrashTrail.last()` re-reads the preference and splits it itself; on
        // the 1.5.0m build it returned a step that was not the last one, so a
        // trail ending in `app:paused` still fell through to the exit-info
        // branch and asked about a crash that never happened. One source, one
        // answer, no second read.
        final String trimmed = trail.trim();
        final int nl = trimmed.lastIndexOf('\n');
        final String lastStep = nl < 0 ? trimmed : trimmed.substring(nl + 1);
        // **An empty ring is the one thing we can prove was not a crash.**
        // `onDestroy` is the only path that clears it, and a process the
        // system killed never reaches `onDestroy`. `ApplicationExitInfo` still
        // has a row for that ordinary exit (reason 1 or 10), so reading it
        // first turned every "swipe the app away, open it again" into
        // "上次启动异常退出" -- with nothing in the trail, because it had been
        // cleared on the way out.
        if (trail.isEmpty()) {
            Probe.log("exit", "skip=empty ring");
            CrashTrail.clear(this);
            return;
        }
        // A trail whose **last** line is `app:paused` means the user left; the
        // process was killed in the background afterwards, which is not a
        // crash. (It used to test `startsWith`, which was right when the trail
        // held one line and is wrong now that it holds sixty-four.)
        // **`app:onDestroy` counts too.** `onDestroy` marks it and then clears
        // the ring; a process the system kills between those two lines leaves
        // `app:onDestroy` as the last step. That is still a deliberate exit,
        // not a crash -- and on this ROM the kill lands exactly there, so the
        // trailing line is the only evidence left.
        // **Look for the pause, not at the last line.** The shelf keeps
        // painting cards after `onPause` -- a worker finishes the frame it
        // already started and marks every card it touches -- so the ring's
        // tail is `prefetch:card`, not `app:paused`. An `endsWith` test
        // therefore missed every ordinary exit. What matters is whether the
        // app came back to the foreground after the pause: a `render:begin`
        // after it means the user returned and the process died during real
        // work.
        final int pausedAt = Math.max(trimmed.lastIndexOf("app:paused"),
                                      trimmed.lastIndexOf("app:onDestroy"));
        // **An export that started and never finished is a crash, whatever the
        // pause says.** A 77 MP export takes long enough that people leave the
        // app while it runs; `onPause` then sits in the ring, and the test below
        // ("no `render:begin` after the pause") reads that as a deliberate exit
        // and stays quiet -- which is exactly why the last one came back with no
        // report at all. `export:written` is marked only after the file is on
        // disk, so its absence is proof the process did not get that far.
        final boolean exportUnfinished = trimmed.contains("export:begin")
                && !trimmed.contains("export:written");
        if (exportUnfinished) {
            Probe.log("exit", "export began and never wrote; reporting");
        } else if (pausedAt >= 0 && trimmed.indexOf("render:begin", pausedAt) < 0) {
            Probe.log("exit", "skip=paused at " + pausedAt + " last=" + lastStep);
            CrashTrail.clear(this);
            return;
        }
        body.append("trail:\n").append(trail).append('\n');
        // Reflected, not called: `ApplicationExitInfo` arrived in API 30 and this
        // project compiles against an older SDK, where the symbol does not exist
        // even though the phone has it. Same reason `DeviceReport` reflects its
        // way to the advertising-id-free device name.
        // **Not every exit is a crash.** 1 = exit_self, 10 = user requested,
        // 16 = package updated (an overwrite install); the rest of the numbers
        // outside 2..6 are ordinary lifecycle ends. `ApplicationExitInfo` has a
        // row for all of them, and reporting on every row is what turned
        // "覆盖安装" into "上次启动异常退出". 2..6 are the ones where the process
        // did not get to finish: SIGNALED, LOW_MEMORY, Java crash, native
        // crash, ANR. If the list cannot be read, the non-empty trail is
        // evidence enough to ask.
        boolean crashed = true;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                final android.app.ActivityManager am =
                        (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                if (am == null) {
                    DebugSink.text("exit", "activity manager unavailable");
                } else {
                    // **`getHistoricalProcessExitReasons`, not ...ExitInfos.**
                    // The latter has never existed on any Android release; it
                    // was a guess inside a reflection loop, so it threw
                    // NoSuchMethodException on every ROM and every report said
                    // "no exit-info API". That one wrong name is why three
                    // native deaths could not be told apart from the kernel
                    // reclaiming the process -- and the two need different
                    // fixes.
                    final java.util.List<android.app.ApplicationExitInfo> list =
                            am.getHistoricalProcessExitReasons(getPackageName(), 0, 8);
                    android.app.ApplicationExitInfo last = null;
                    if (list != null) {
                        for (android.app.ApplicationExitInfo info : list) {
                            if (info == null) continue;
                            if (info.getPid() == android.os.Process.myPid()) continue;
                            last = info;
                            break;
                        }
                    }
                    if (last == null) {
                        DebugSink.text("exit", "exit-info API present, no record for this package");
                    } else {
                        final int reason = last.getReason();
                        // **Every reason, not just the ones that say "crash".**
                        // 1 exit_self, 2 SIGNALED, 3 LOW_MEMORY, 4 Java crash,
                        // 5 native crash, 6 ANR, 9 excessive resources,
                        // 10 user requested, 13 other. Keeping only 4..6 made a
                        // kernel reclaim (3) or a SIGKILL (2) look like "the app
                        // vanished for no reason".
                        lastExitReason = reason;
                        crashed = reason >= 2 && reason <= 6;
                        if (crashed) {
                            body.append("exit: reason=").append(reason)
                                .append(" (").append(exitReasonName(reason)).append(')')
                                .append(" status=").append(last.getStatus())
                                .append(" pss=").append(last.getPss()).append("KB")
                                .append(" rss=").append(last.getRss()).append("KB")
                                .append(" desc=").append(last.getDescription()).append('\n');
                            try (java.io.InputStream stream = last.getTraceInputStream()) {
                                if (stream != null) {
                                    final java.io.ByteArrayOutputStream buf =
                                            new java.io.ByteArrayOutputStream();
                                    final byte[] chunk = new byte[8192];
                                    int n, total = 0;
                                    // **A tombstone is not 16 KB.** Truncating
                                    // kept the header and dropped the frame list.
                                    while ((n = stream.read(chunk)) > 0 && total < 262144) {
                                        buf.write(chunk, 0, n);
                                        total += n;
                                    }
                                    body.append(buf.toString("UTF-8")).append('\n');
                                }
                            } catch (Throwable ignored) { }
                        }
                    }
                }
            } catch (Throwable e) {
                // **Silence here is what made the first report unreadable.** An
                // exit row that cannot be read looks exactly like one that was
                // never written, and the two mean opposite things.
                body.append("exit: 读不到 ApplicationExitInfo（").append(e).append("）\n");
            }
        }
        // **A non-crashing exit reason leaves nothing to ask about.**
        if (!crashed) {
            Probe.log("exit", "skip=not-crash reason=" + lastExitReason);
            CrashTrail.clear(this);
            return;
        }
        if (body.length() == 0) { CrashTrail.clear(this); return; }
        try {
            final java.io.File dir = getExternalFilesDir("crash");
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs();
                final String stamp = new java.text.SimpleDateFormat("MMdd-HHmmss",
                        java.util.Locale.US).format(new java.util.Date());
                final java.io.File out = new java.io.File(dir, "exit-" + stamp + ".txt");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    fos.write(body.toString().getBytes("UTF-8"));
                }
                lastCrashFile = out.getAbsolutePath();
            }
        } catch (Throwable ignored) { }
        // **One row, and it is the whole report.** This used to be a
        // previous-exit row plus a probe row on every launch that had died; the
        // quota is counted in writes, and one is enough.
        final String exitHead = "previous-exit\n" + reportHead() + "--- exit ---\n";
        final String exitBody = body.toString();
        CrashTrail.clear(this);
        new Thread(() -> DebugSink.send("crash", exitHead + exitBody),
                "SpektraLab-report").start();
        // The report is only worth what reaches the developer. A crash on a
        // phone we do not own leaves this file and nothing else, so ask once,
        // late enough not to sit on top of the first layout, and make sending
        // it one tap.
        Probe.now("exit", "report trail=" + trail.length() + "B last=" + lastStep
                + " reason=" + lastExitReason);
        final String summary = lastStep.isEmpty() ? "(no step recorded)" : lastStep;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> offerCrashReport(summary), 1500);
    }

    /**
     * The previous launch died. Say where, and let the user send it.
     *
     * Text, not a file: the trail and the exit reason are the whole payload,
     * and `ACTION_SEND` with `text/plain` needs no FileProvider grant -- which
     * on a vendor ROM is one more thing that can fail silently.
     */
    /** `ApplicationExitInfo.REASON_*` in words. Kept here because the numeric
     *  constants are API 30 and this project compiles against an older SDK. */
    private static String exitReasonName(int reason) {
        switch (reason) {
            case 1:  return "exit_self";
            case 2:  return "SIGNALED 被信号杀死";
            case 3:  return "LOW_MEMORY 系统内存不足回收";
            case 4:  return "Java crash";
            case 5:  return "native crash";
            case 6:  return "ANR";
            case 7:  return "init failure";
            case 9:  return "excessive resource usage";
            case 10: return "user requested";
            case 11: return "user stopped";
            case 12: return "dependency died";
            case 13: return "other";
            default: return "reason " + reason;
        }
    }

    private void offerCrashReport(String where) {
        try {
            if (isFinishing() || isDestroyed()) return;
            final android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
            // **Two different events, two different sentences.** 3 is the
            // kernel reclaiming the process for memory and 2 is a signal kill;
            // neither is a defect in the code path the trail names, and calling
            // both "异常退出" is how a 600 MB import reads as a mystery crash.
            final boolean reclaimed = lastExitReason == 3 || lastExitReason == 2;
            b.setTitle(reclaimed ? "上次被系统回收了" : "上次启动异常退出");
            b.setMessage(reclaimed
                    ? "最后一步：" + where
                        + "\n\n系统当时内存不够，把应用回收了——多半是这张图太大，不是程序出错。"
                        + "\n\n把这份记录发我可以确认尺寸；也可以直接重开，导入时选「缩到建议尺寸」。"
                    : "最后一步：" + where
                        + "\n\n这台机器的崩溃日志对定位很关键。要不要发我一份？");
            b.setPositiveButton("发送日志", (d, w) -> shareCrashReport());
            b.setNegativeButton("关闭", null);
            b.show();
        } catch (Throwable ignored) { }
    }

    private void shareCrashReport() {
        try {
            final String body = lastCrashFile == null ? "" : readTextFile(lastCrashFile, 32768);
            final StringBuilder text = new StringBuilder("SpektraLab 崩溃报告\n");
            text.append("build=").append(SpektraLabEngine.buildInfo()).append('\n');
            // `probe` is filled by `ensureEngine()`, and this report is offered
            // on the launch *after* the exit -- before the user has imported
            // anything. "设备信息读取失败" there is the engine simply not having
            // been created yet, and it reads as a broken device probe.
            text.append("device=").append(probe.valid ? probe.describe()
                    : "（引擎本次启动尚未创建，无设备信息）").append("\n\n");
            text.append("ref=").append(DebugSink.REF).append('\n');
            text.append(body);
            final Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "SpektraLab 崩溃报告");
            send.putExtra(Intent.EXTRA_TEXT, text.toString());
            startActivity(Intent.createChooser(send, "发送崩溃报告"));
        } catch (Throwable t) {
            if (statusText != null) statusText.setText("没有可发送的内容");
        }
    }

    private static String readTextFile(String path, int cap) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(path)) {
            final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            final byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0 && buf.size() < cap) buf.write(chunk, 0, n);
            return buf.toString("UTF-8");
        } catch (Throwable e) {
            return "";
        }
    }


    /**
     * A cheap structural sanity check on a file we just wrote.
     *
     * Null when the picture looks like a picture, a one-line description when
     * it does not. `dX/dY` is the tell: this engine's worst reported failure was
     * a frame stretched along the horizontal axis, which leaves the rows varied
     * and the columns nearly identical.
     */
    private static String inspectExportedFile(String path) {
        try {
            final android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 2;
            final Bitmap bm = BitmapFactory.decodeFile(path, o);
            if (bm == null) return "decode failed";
            final int w = bm.getWidth(), h = bm.getHeight();
            if (w < 64 || h < 64) { bm.recycle(); return null; }
            final int[] px = new int[w * h];
            bm.getPixels(px, 0, w, 0, 0, w, h);
            bm.recycle();
            long sx = 0, sy = 0;
            long nx = 0, ny = 0;
            for (int y = 0; y < h; ++y) {
                final int row = y * w;
                for (int x = 0; x < w - 1; ++x) {
                    final int a = px[row + x], b = px[row + x + 1];
                    sx += Math.abs(((a >> 16) & 255) - ((b >> 16) & 255))
                        + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
                        + Math.abs((a & 255) - (b & 255));
                    ++nx;
                }
            }
            for (int y = 0; y < h - 1; ++y) {
                final int row = y * w;
                for (int x = 0; x < w; ++x) {
                    final int a = px[row + x], b = px[row + w + x];
                    sy += Math.abs(((a >> 16) & 255) - ((b >> 16) & 255))
                        + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
                        + Math.abs((a & 255) - (b & 255));
                    ++ny;
                }
            }
            if (nx == 0 || ny == 0) return null;
            final double dx = (double) sx / nx, dy = (double) sy / ny;
            if (dy <= 0.0) return null;
            final double ratio = dx / dy;
            if (ratio < 0.35 || ratio > 3.0) {
                return String.format(java.util.Locale.US,
                        "X/Y contrast %.2f (X %.1f, Y %.1f) at %dx%d", ratio, dx, dy, w, h);
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The engine's SIGSEGV handler writes a backtrace to this file. Installing
     * it is the only way a native crash produces evidence on a ROM without
     * `ApplicationExitInfo`: the Java handler never runs, and the sink queue is
     * lost with the process.
     */
    private void installNativeCrashHandler() {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "native-crash.txt");
            SpektraLabEngine.installCrashHandler(f.getAbsolutePath());
        } catch (Throwable ignored) { }
    }

    /**
     * Ship whatever the last death left on disk.
     *
     * <p>Two files, because two different deaths: the signal handler writes a
     * backtrace for a native crash, and {@link #installCrashLogger} writes the
     * Java stack for an exception. Both are shipped **only when they exist**
     * -- which is to say, only after a crash -- and one row each.
     */
    private void uploadCrashFiles() {
        shipCrashFile("native-crash.txt", "native-crash");
        shipCrashFile("java-crash.txt", "crash");
    }

    private void shipCrashFile(String name, String tag) {
        final java.io.File f = new java.io.File(getFilesDir(), name);
        if (!f.exists()) return;
        new Thread(() -> {
            try {
                final long len = Math.min(f.length(), 64L * 1024);
                if (len <= 0) { f.delete(); return; }
                final byte[] buf = new byte[(int) len];
                int off = 0, n;
                try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                    while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
                }
                final String body = "build=" + buildLine() + "\nversion=" + versionLine()
                        + new String(buf, 0, off, java.nio.charset.StandardCharsets.UTF_8);
                // `send` stashes the row when the network is down, so the file
                // is not needed as a second copy once it has been attempted.
                if (DebugSink.send(tag, body)) f.delete();
            } catch (Throwable ignored) { }
        }, "SpektraLab-crash-upload").start();
    }

    /**
     * A Java crash never reaches the sink: the process is gone before anything
     * asynchronous runs. So it writes a file, and the next launch ships that
     * file -- the same route the native backtrace takes.
     */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                final java.io.StringWriter text = new java.io.StringWriter();
                error.printStackTrace(new java.io.PrintWriter(text));
                final String body = "ref=" + DebugSink.REF + "\nbuild=" + buildLine()
                        + "\nversion=" + versionLine() + "thread=" + thread.getName()
                        + "\n\n" + text;
                DebugSink.text("crash", body);
                final java.io.File f = new java.io.File(getFilesDir(), "java-crash.txt");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f, false)) {
                    out.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) { }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private String buildLine() {
        try { return SpektraLabEngine.buildInfo(); } catch (Throwable t) { return "(native side not loaded)"; }
    }

    private String versionLine() {
        return UpdateCheck.versionName(this) + " (" + UpdateCheck.versionCode(this) + ")\n";
    }

    /** build + version + device: the first three lines of every report. */
    private String reportHead() {
        return "build=" + buildLine() + "\n"
                + "version=" + versionLine()
                + "device=" + (probe.valid ? probe.describe() : "(engine not created this launch)") + "\n";
    }

    // **The only automatic upload that is not a crash.** At most one a minute,
    // and never the same failure twice in a row: an error that repeats in a
    // loop (a bad parameter on every drag) must not become a write per frame.
    private long lastReportAt = 0L;
    private String lastReportKey = "";

    private void reportFailure(String title, String body) {
        final long now = System.currentTimeMillis();
        final String key = title + "|" + body;
        if (key.equals(lastReportKey) && now - lastReportAt < 60_000L) return;
        if (now - lastReportAt < 5_000L) return;
        lastReportKey = key;
        lastReportAt = now;
        final String head = "error\n" + reportHead() + "title=" + title + "\n";
        new Thread(() -> DebugSink.report("error", head, body), "SpektraLab-report").start();
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

    /**
     * The scrim behind the brand.
     *
     * <p>It used to be three literals of the blue canvas, which is why the dark
     * theme left a blue band across the top of a black screen. Now it is the
     * canvas colour at three opacities, so it is orange over the dark palette
     * and navy over the light one.
     */
    private int[] topWash() {
        final int rgb = UI_STAGE & 0x00FFFFFF;
        return new int[]{0xC9 << 24 | rgb, 0x66 << 24 | rgb, 0x00 << 24 | rgb};
    }

    /** Brand and export are painted over the photograph, not stacked above it. */
    private void addCanvasChrome(android.widget.FrameLayout stage) {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.TOP);
        top.setPadding(dp(16), dp(12), dp(16), dp(8));
        top.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                topWash()));
        top.setClickable(false);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(0, dp(2), 0, 0);
        LinearLayout wordRow = new LinearLayout(this);
        wordRow.setOrientation(LinearLayout.HORIZONTAL);
        wordRow.setGravity(android.view.Gravity.TOP);
        // `UI_PAPER` is the panel colour: white on the light theme (correct on
        // the navy canvas) and near-black on the dark one, where it vanished
        // into the black. The wordmark is always the canvas's *contrast* colour.
        TextView word = label("SpektraLab", 18, uiOnCanvas(), tfDisplay);
        word.setLetterSpacing(-0.045f);
        word.setSingleLine(true);
        wordRow.addView(word);
        // **The menu is a drawn glyph, not a text "≡".** Next to a real icon a
        // font character reads as a mistake; Material's `menu` path is the
        // designed 24 dp shape. It replaces the long-press on the wordmark,
        // which nothing on screen advertised.
        final android.widget.ImageView menuPill = new android.widget.ImageView(this);
        menuPill.setImageResource(getResources().getIdentifier(
                "ic_menu_white", "drawable", getPackageName()));
        menuPill.setAlpha(0.85f);
        menuPill.setPadding(dp(6), dp(6), dp(6), dp(6));
        menuPill.setContentDescription("设置");
        menuPill.setOnClickListener(v -> showAbout());
        // **Left of the wordmark, not between it and the version.** The menu
        // belongs at the edge of the bar, where every other app puts it;
        // between the name and its version number it reads as part of the name.
        final LinearLayout.LayoutParams menuLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        menuLp.rightMargin = dp(10);
        menuLp.topMargin = dp(1);
        wordRow.addView(menuPill, 0, menuLp);
        TextView version = label("v" + UpdateCheck.versionName(this), 6.5f, uiOnCanvas(), tfBody);
        version.setAlpha(0.7f);
        LinearLayout.LayoutParams versionLp = new LinearLayout.LayoutParams(-2, -2);
        versionLp.leftMargin = dp(4);
        versionLp.topMargin = 0;
        wordRow.addView(version, versionLp);
        brand.addView(wordRow);
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));

        // **A drawn glyph, not a text arrow.** "↶" is whatever the device's font
        // decides it is -- different weight, different curve, sometimes a tofu --
        // and next to a real icon it reads as a mistake. Material's `undo` path
        // is a designed 24 dp shape with predictable proportions.
        undoPill = new android.widget.ImageView(this);
        // No `R` class in this project -- resources are looked up by name.
        undoPill.setImageResource(getResources().getIdentifier(
                "ic_undo_white", "drawable", getPackageName()));
        undoPill.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        undoPill.setPadding(dp(7), dp(4), dp(7), dp(4));
        undoPill.setVisibility(View.GONE);
        undoPill.setOnClickListener(v -> undoStep());
        LinearLayout.LayoutParams undoLp = new LinearLayout.LayoutParams(-2, dp(32));
        undoLp.rightMargin = dp(6);
        top.addView(undoPill, undoLp);

        sharePill = label("分享", 11, uiOnCanvas(), tfHan);
        sharePill.setGravity(android.view.Gravity.CENTER);
        sharePill.setShadowLayer(dp(3), 0, dp(1), 0xCC000000);
        sharePill.setBackground(null);
        sharePill.setPadding(dp(8), 0, dp(8), 0);
        sharePill.setVisibility(View.GONE);
        sharePill.setOnClickListener(v -> shareLastExport());
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(-2, dp(32));
        shareLp.rightMargin = dp(6);
        top.addView(sharePill, shareLp);

        // Material "file upload", for the same reason as the undo glyph: the
        // tray-and-arrow I drew by hand had a thin stroke and a lopsided arrow.
        final android.widget.ImageView export = new android.widget.ImageView(this);
        export.setImageResource(getResources().getIdentifier(
                "ic_export_white", "drawable", getPackageName()));
        export.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        export.setOnClickListener(v -> showExportDialog());
        top.addView(export, new LinearLayout.LayoutParams(dp(26), dp(26)));

        stage.addView(top, new android.widget.FrameLayout.LayoutParams(-1, dp(64), android.view.Gravity.TOP));

        // **The histogram lives on the photograph, not in a panel.** It is the
        // one readout that is about *this* frame, so it sits where the frame is
        // and updates with it; behind it a 30% wash keeps a bright sky from
        // swallowing the traces.
        // **Whether the histogram shows is a preference now.** It is the one
        // readout that is about *this* frame, so it sits on the frame; but a
        // plate over the top-left corner of a photograph is not always
        // wanted, and the switch is in 光学 -> 亮度.
        histogramOn = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("histogram", true);
        rawAutoBright = getSharedPreferences("ui", MODE_PRIVATE)
                .getBoolean("raw_auto_bright", false);
        histogram = new HistogramView(this);
        final android.widget.FrameLayout.LayoutParams hlp =
                new android.widget.FrameLayout.LayoutParams(dp(148), dp(54),
                        android.view.Gravity.TOP | android.view.Gravity.START);
        hlp.leftMargin = dp(14);
        hlp.topMargin = dp(62);
        stage.addView(histogram, hlp);
        histogram.setVisibility(histogramOn ? View.VISIBLE : View.GONE);
        if (lastRenderedBitmap != null) histogram.setBitmap(lastRenderedBitmap);
    }

    /** File name and live state sit at the foot of the photograph. */
    private void addPreviewFooter(android.widget.FrameLayout stage) {
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(android.view.Gravity.CENTER_VERTICAL);
        foot.setPadding(dp(16), 0, dp(16), 0);
        foot.setClickable(false);

        fileLabel = label("未选择照片", 7, uiOnCanvas(), tfHan);
        fileLabel.setAlpha(0.76f);
        fileLabel.setSingleLine(true);
        fileLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        fileLabel.setShadowLayer(dp(2), 0, dp(1), 0x99000000);
        foot.addView(fileLabel, new LinearLayout.LayoutParams(0, -2, 1f));

        statusText = label("等待导入", 7, uiOnCanvas(), tfHan);
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

        TextView chevron = label("\u203a", 14, uiOnCanvas(), tfDisplay);
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
        look.meterMethod = meterMethod;
        look.grainAmount = grainAmount;
        look.halationAmount = halationAmount;
        look.glareAmount = glareAmount;
        look.highlightRoom = highlightRoom;
        look.shadowRoom = shadowRoom;
        look.halationBoost = halationBoost;
        look.halationScatter = halationScatter;
        look.scatterRadius = scatterRadius;
        look.lensBlur = lensBlur;
        look.viewTemperature = viewTemperature;
        look.viewTint = viewTint;
        look.viewExposure = viewExposure;
        look.viewContrast = viewContrast;
        look.viewBrightness = viewBrightness;
        look.viewSaturation = viewSaturation;
        look.viewHighlights = viewHighlights;
        look.viewShadows = viewShadows;
        look.viewBlack = viewBlack;
        look.viewWhite = viewWhite;
        look.viewVignette = viewVignette;
        look.cameraEv = cameraEv;
        look.viewVibrance = viewVibrance;
        look.wbPreset = wbPreset;
        look.wbKelvin = wbKelvin;
        look.wbTint = wbTint;
        look.wbMul = wbUserMul == null ? null : wbUserMul.clone();
        Probe.log("wb", "look-capture mul=" + Probe.mul(wbUserMul) + " preset=" + wbPreset);
        look.geometryTurns = geometryTurns;
        look.geometryRotation = geometryRotation;
        look.geometryCropW = geometryCropW;
        look.geometryCropH = geometryCropH;
        look.geometryCropX = geometryCropX;
        look.geometryCropY = geometryCropY;
        look.cropRatioIndex = cropRatioIndex;
        look.geometryFlipH = geometryFlipH;
        look.geometryFlipV = geometryFlipV;
        for (int i = 0; i < 4; ++i) {
            look.cbHue[i] = cbHue[i];
            look.cbSat[i] = cbSat[i];
            look.cbLum[i] = cbLum[i];
        }
        for (int i = 0; i < 5; ++i) {
            final java.util.List<float[]> ch = curvePoints[i];
            if (ch == null) continue;
            final float[] flat = new float[ch.size() * 2];
            for (int k = 0; k < ch.size(); ++k) {
                flat[k * 2] = ch.get(k)[0];
                flat[k * 2 + 1] = ch.get(k)[1];
            }
            look.curves[i] = flat;
        }
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
        look.glareActive = glareActive;
        look.scannerLensBlur = scannerLensBlur;
        look.halationHeadroom = halationHeadroom;
        look.densityGamma = densityGamma;
        look.digitalIntermediate = digitalIntermediate;
        look.outputCctf = outputCctf;
        look.hueCorrection = hueCorrection;
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
        meterMethod = sanitizeMeter(look.meterMethod);
        refreshMeterChip();
        grainAmount = look.grainAmount;
        halationAmount = look.halationAmount;
        glareAmount = look.glareAmount;
        highlightRoom = look.highlightRoom;
        shadowRoom = look.shadowRoom;
        halationBoost = look.halationBoost;
        halationScatter = look.halationScatter;
        scatterRadius = look.scatterRadius;
        lensBlur = look.lensBlur;
        viewTemperature = look.viewTemperature;
        viewTint = look.viewTint;
        viewExposure = look.viewExposure;
        viewContrast = look.viewContrast;
        viewBrightness = look.viewBrightness;
        viewSaturation = look.viewSaturation;
        viewHighlights = look.viewHighlights;
        viewShadows = look.viewShadows;
        viewBlack = look.viewBlack;
        viewWhite = look.viewWhite;
        viewVignette = look.viewVignette;
        cameraEv = look.cameraEv;
        viewVibrance = look.viewVibrance;
        wbPreset = look.wbPreset;
        wbKelvin = look.wbKelvin <= 0f ? 5500f : look.wbKelvin;
        wbTint = look.wbTint;
        wbUserMul = look.wbMul == null ? null : look.wbMul.clone();
        Probe.log("wb", "look-apply mul=" + Probe.mul(wbUserMul) + " preset=" + wbPreset);
        refreshViewPanel();
        pushViewAdjust();
        geometryTurns = look.geometryTurns;
        geometryRotation = look.geometryRotation;
        geometryCropW = look.geometryCropW;
        geometryCropH = look.geometryCropH;
        geometryCropX = look.geometryCropX;
        geometryCropY = look.geometryCropY;
        cropRatioIndex = look.cropRatioIndex;
        geometryFlipH = look.geometryFlipH;
        geometryFlipV = look.geometryFlipV;
        // **The grading wheels and the curves are per photograph.** They used to
        // live outside the Look, so undo could not reach them and switching
        // pictures carried them over -- "the curve does not reset when I change
        // photo" and "undo does nothing after grading" were the same omission.
        for (int i = 0; i < 4; ++i) {
            cbHue[i] = look.cbHue[i];
            cbSat[i] = look.cbSat[i];
            cbLum[i] = look.cbLum[i];
        }
        for (int i = 0; i < 5; ++i) {
            if (curvePoints[i] == null) curvePoints[i] = new java.util.ArrayList<>();
            curvePoints[i].clear();
            final float[] flat = look.curves[i];
            if (flat == null || flat.length < 4) {
                curvePoints[i].add(new float[]{0f, 0f});
                curvePoints[i].add(new float[]{1f, 1f});
            } else {
                for (int k = 0; k + 1 < flat.length; k += 2)
                    curvePoints[i].add(new float[]{flat[k], flat[k + 1]});
            }
        }
        // **The page has a picture of its own state.** Restoring a Look sets
        // the numbers; without this the crop cards and the rotation readout
        // still showed the previous photograph's values.
        // A restored Look describes the file's crop, not a proposal.
        pendingCropX = 0f; pendingCropY = 0f;
        pendingCropW = 1f; pendingCropH = 1f;
        if (composeBody != null) showComposePage(composeSubPage);
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
        glareActive = look.glareActive;
        scannerLensBlur = look.scannerLensBlur;
        halationHeadroom = look.halationHeadroom;
        densityGamma = look.densityGamma;
        digitalIntermediate = look.digitalIntermediate;
        outputCctf = look.outputCctf;
        hueCorrection = look.hueCorrection;
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
        setBarProgress(dateSizeBar, 0.4f, 8f, dateSize);
        setBarProgress(dateEvBar, -2f, 8f, dateEv);
        setBarProgress(dateInsetXBar, 0f, 200f, dateInsetX);
        setBarProgress(dateInsetYBar, 0f, 200f, dateInsetY);
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
        // A raw is scene-referred and has no exposure decision of its own; a
        // camera JPEG has already been metered once, by the camera, and
        // metering it again is how "import this photo" turned into a grey wash.
        look.meterMethod = isRawFile ? "protect_highlights" : "";
        look.grainAmount = 1.0f;
        look.halationAmount = 1.0f;
        look.glareAmount = 1.0f;
        look.highlightRoom = 0.0f;
        look.shadowRoom = 0.0f;
        look.halationBoost = 0.0f;
        look.halationScatter = 1.0f;
        look.scatterRadius = 0.0f;
        look.lensBlur = 0.0f;
        look.viewVignette = 0.0f;
        look.cameraEv = 0.0f;
        look.wbPreset = 0;
        look.wbKelvin = 5500f;
        look.wbTint = 0.0f;
        look.wbMul = null;
        look.geometryTurns = 0;
        look.geometryRotation = 0.0f;
        look.geometryCropW = 1.0f;
        look.geometryCropH = 1.0f;
        look.geometryCropX = 0.0f;
        look.geometryCropY = 0.0f;
        look.cropRatioIndex = -1;
        look.geometryFlipH = false;
        look.geometryFlipV = false;
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
        look.glareActive = 1f;
        look.scannerLensBlur = 0f;
        look.halationHeadroom = 4f;
        look.densityGamma = 1f;
        look.digitalIntermediate = false;
        look.outputCctf = true;
        look.hueCorrection = 1f;
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
            reopeningFrame = false;
            // **The chip may have been deleted while this decode ran.**
            // Deleting it (or every chip) during the load left `reopeningFrame`
            // set, this branch took the early return, and the new pixels were
            // filed under a frame that no longer exists -- the canvas stayed on
            // "Decoding RAW…" with nothing ever taking its place. If the index
            // is gone, fall through and add the frame for real.
            if (currentFrame >= 0 && currentFrame < frames.size()) {
                // **The chip is this frame's picture, so it has to be this
                // frame's pixels.** This branch returned without touching
                // `thumb`, which left every chip showing whatever that slot was
                // first imported with -- reported as "缩略图被同一张图片污染"
                // after switching between a JPEG and a RAW. Re-filing it here is
                // also what makes a re-decoded (tone-shifted) frame show its own
                // new thumbnail rather than the old one.
                final Frame reopened = frames.get(currentFrame);
                if (beforeBitmap != null) {
                    try {
                        final int w = Math.max(1, dp(42));
                        final int h = Math.max(1, Math.round(
                                w * beforeBitmap.getHeight() / (float) beforeBitmap.getWidth()));
                        reopened.thumb = Bitmap.createScaledBitmap(beforeBitmap, w, h, true);
                        persistThumb(reopened);
                    } catch (Throwable ignored) { }
                }
                rebuildFrameChips();
                return;
            }
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
        resetUndo();             // a new photograph starts its own history
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
                showActionSheet(frame.name == null ? "这张照片" : frame.name,
                        "参数是每张照片各自的：复制过去只改这条栏里的设置，文件不动。",
                        new String[]{"把这张的参数应用到其它照片…", "删除", "取消"}, which -> {
                            if (which == 0) showApplyLookSheet(index);
                            else if (which == 1) removeFrame(index);
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
        CrashTrail.markNow(this, "frame:remove " + index + " of " + frames.size());
        if (index < 0 || index >= frames.size()) return;
        frames.remove(index);
        if (frames.isEmpty()) stopPrefetch();
        if (index == currentFrame) {
            currentFrame = -1;
            lastRenderedBitmap = null;
        } else if (currentFrame > index) {
            currentFrame--;
        }
        // **"Nothing is loaded" is `frames.isEmpty()`, not `index == current`.**
        // The cleanup used to live inside the `index == currentFrame` branch, so
        // deleting the last chip while `currentFrame` was already -1 skipped it
        // entirely: the strip emptied but every film card kept showing the old
        // photograph, because they fall back to `beforeBitmap`.
        if (frames.isEmpty()) {
            currentFrame = -1;
            lastRenderedBitmap = null;
            filmThumbs.clear();
            if (compare != null) { compare.clear(); compare.resetView(); }
            // The session used to outlive its own frame: delete every chip and
            // "导出" still produced a picture from the closed-over state.
            synchronized (renderMutex) {
                if (session != null) { session.close(); session = null; }
            }
            beforeBitmap = null;
            showingOriginal = false;
            setStage(0, null);
            statusText.setText("等待导入");
            rebuildFilmCards();
        }
        rebuildFrameChips();
        saveSessionState();
        try { DebugSink.text("frame-strip", "removed " + index + ", now " + frames.size()); }
        catch (Throwable ignored) { }
    }

    /** Re-open a frame from the strip. Same entry point the picker uses, so a
     *  RAW chip and a JPEG chip take exactly the roads they took the first time. */
    private void reopenFrame(int index) {
        CrashTrail.markNow(this, "frame:reopen " + index + " of " + frames.size());
        if (index < 0 || index >= frames.size()) return;
        if (index == currentFrame && session != null) return;
        reopenFrameForced(index);
    }

    /**
     * Same as {@link #reopenFrame} but without the "already open" shortcut.
     *
     * <p>A batch export uses this for every frame, including the one already on
     * the canvas. The shortcut left the first export running on the session the
     * import opened -- a different open (its own parameters, its own preload) --
     * while the other five were re-decoded. Reported: the first export came out
     * about two stops darker than its own preview, and the other five matched.
     */
    private void reopenFrameForced(int index) {
        if (index < 0 || index >= frames.size()) return;
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

    private LinearLayout sectionHead(String title, Runnable reset) {
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


    /**
     * The pinch, caught at the window.
     *
     * <p>Two attempts to put it inside `CompareView` -- a hand-rolled pointer
     * sequence, then the platform's `ScaleGestureDetector` -- moved nothing at
     * all. That is not a maths problem: the second pointer never reached that
     * view. Catching it here is upstream of everything on the canvas, so there
     * is nothing left to eat it.
     */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        // **Two fingers is the whole condition, on every event.** The first
        // version only armed the pinch on ACTION_POINTER_DOWN and relied on
        // that one event having been seen; the status bar proved it was, but
        // nothing after it moved the picture. Re-arming on any two-finger
        // event removes the state machine from the path entirely.
        if (compare != null && ev.getPointerCount() >= 2) {
            final int action = ev.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_POINTER_UP
                    || action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                if (canvasPinching) {
                    canvasPinching = false;
                    try {
                        DebugSink.text("zoom", "pinch end at "
                                + String.format(java.util.Locale.US, "%.2f", compare.zoomLevel()));
                    } catch (Throwable ignored) { }
                }
                return true;
            }

            final float span = Math.max(1f, spanOf(ev));
            if (!canvasPinching || canvasPinchStartSpan <= 1f) {
                canvasPinching = true;
                canvasPinchStartSpan = span;
                canvasPinchStartZoom = compare.zoomLevel();
                canvasPinchStartCx = (ev.getX(0) + ev.getX(1)) / 2f;
                canvasPinchStartCy = (ev.getY(0) + ev.getY(1)) / 2f;
                canvasPinchStartPanX = compare.panXLevel();
                canvasPinchStartPanY = compare.panYLevel();
                try {
                    DebugSink.text("zoom", "pinch begin span=" + Math.round(span)
                            + " at=" + (int) ev.getRawX() + "," + (int) ev.getRawY());
                } catch (Throwable ignored) { }
                statusText.setText("ZOOM " + String.format(java.util.Locale.US, "%.2f", canvasPinchStartZoom)
                        + " · 0%");
                return true;
            }

            // **Incremental, so it shrinks as well as grows.** Anchored to the
            // span at the instant the pinch began, the ratio could never drop
            // below 1 if the session started with the fingers far apart, so
            // the picture could only get bigger. Each frame is now its own
            // baseline.
            final float ratio = span / Math.max(1f, canvasPinchStartSpan);
            final float want = Math.max(0.2f, Math.min(8f, canvasPinchStartZoom * ratio));
            final float cx = (ev.getX(0) + ev.getX(1)) / 2f;
            final float cy = (ev.getY(0) + ev.getY(1)) / 2f;
            final float k = want / Math.max(1e-4f, canvasPinchStartZoom);
            final float px = (canvasPinchStartPanX - (canvasPinchStartCx - compare.getWidth() / 2f)) * k
                    + (cx - compare.getWidth() / 2f);
            final float py = (canvasPinchStartPanY - (canvasPinchStartCy - compare.getHeight() / 2f)) * k
                    + (cy - compare.getHeight() / 2f);
            compare.setView(want, px, py);
            canvasPinchStartZoom = Math.max(0.2f, Math.min(8f, want));
            canvasPinchStartSpan = span;
            canvasPinchStartCx = cx;
            canvasPinchStartCy = cy;
            canvasPinchStartPanX = compare.panXLevel();
            canvasPinchStartPanY = compare.panYLevel();
            statusText.setText("ZOOM " + String.format(java.util.Locale.US, "%.2f", canvasPinchStartZoom));
            return true;
        }

        if (canvasPinching) {
            // The pinch ended without a clean up event on the two-finger path.
            canvasPinching = false;
        }
        return super.dispatchTouchEvent(ev);
    }

    /** Is this touch inside the canvas rectangle? */
    private boolean overCanvas(android.view.MotionEvent ev) {
        if (canvasStage == null) return false;
        final android.graphics.Rect r = new android.graphics.Rect();
        if (!canvasStage.getGlobalVisibleRect(r)) return false;
        final float x = ev.getRawX(), y = ev.getRawY();
        return x >= r.left && x <= r.right && y >= r.top && y <= r.bottom;
    }

    private static float spanOf(android.view.MotionEvent e) {
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    private View buildFilmPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout filmHead = sectionHead("FILM & PAPER / 胶片与相纸", this::resetFilmPanel);
        // **The "As Shot" switch, where the original app puts the same choice.**
        // On: the meter decides the film exposure. Off: the frame keeps the
        // exposure the camera recorded, which is the only way a lamp in a dark
        // room comes back dark instead of being lifted five stops onto Dmax.
        final TextView meterLabel = label("测光", 8.5f, UI_MUTED, tfHan);
        meterChip = label("", 8.5f, UI_INK, tfHan);
        meterChip.setGravity(android.view.Gravity.CENTER);
        // **48dp, not 40x20.** The old chip was smaller than the minimum touch
        // target, so a tap that landed two pixels off did nothing -- which is
        // what "点了没反应" was.
        meterChip.setPadding(dp(10), dp(6), dp(10), dp(6));
        meterChip.setMinWidth(dp(48));
        meterChip.setMinHeight(dp(30));
        meterChip.setOnClickListener(v -> showMeterPicker());
        final LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(-2, -2);
        labelLp.rightMargin = dp(6);
        filmHead.addView(meterLabel, 1, labelLp);
        final LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, -2);
        chipLp.rightMargin = dp(10);
        filmHead.addView(meterChip, 2, chipLp);
        refreshMeterChip();
        panel.addView(filmHead, new LinearLayout.LayoutParams(-1, dp(38)));

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

    /** Four colours and three lines: everything a box front is. */
    private static final class Cover {
        int body, band, ink, bandInk, accent;
        String wordmark = "KODAK", model = "", tech = "COLOR PRINT FILM", iso = "";
    }

    /**
     * The palette of a film's box.
     *
     * <p>Nothing here is artwork. The recognition lives in four things -- the
     * brand band, the stock's own colour, the wordmark, the name -- and all
     * four can be *stated*, which is what this does. Copying the printed boxes
     * would mean licensing 25 pieces of trade dress to fill a placeholder.
     */
    private static Cover coverFor(String id, String caption) {
        final Cover v = new Cover();
        v.body = 0xFFF2EDE3;
        v.band = 0xFFF5C518;
        v.ink = 0xFF171717;
        v.bandInk = 0xFFDA291C;
        v.accent = 0xFFDA291C;
        if (id == null) id = "";

        if (id.startsWith("fujifilm")) {
            v.wordmark = "FUJIFILM";
            v.body = 0xFFF4F2EC;
            v.band = 0xFF007A3D;
            v.bandInk = 0xFFF4F2EC;
            v.ink = 0xFF0B6B38;
            v.accent = 0xFF6D2C91;
        } else if (id.startsWith("ilford")) {
            v.wordmark = "ILFORD";
            v.body = 0xFF141414;
            v.band = 0xFF141414;
            v.bandInk = 0xFFF4F2EC;
            v.ink = 0xFFF4F2EC;
            v.accent = 0xFFF4F2EC;
            v.tech = "BLACK & WHITE FILM";
        } else if (id.startsWith("cinestill")) {
            v.wordmark = "CINESTILL";
            v.body = 0xFFC8102E;
            v.band = 0xFF101010;
            v.bandInk = 0xFFF7F4EF;
            v.ink = 0xFFF7F4EF;
            v.accent = 0xFF2AA3DC;
            v.tech = "CINE FILM · 36 EXP";
        }

        if (id.equals("kodak_gold_200")) {
            v.body = 0xFFE6007E; v.ink = 0xFFFFFFFF;
        } else if (id.equals("kodak_colorplus_200")) {
            v.body = 0xFFEE2E24; v.ink = 0xFFFFFFFF;
        } else if (id.equals("kodak_ultramax_400")) {
            v.body = 0xFF0B5FA5; v.ink = 0xFFFFFFFF;
        } else if (id.equals("kodak_ektar_100")) {
            v.body = 0xFFF2A900; v.ink = 0xFF171717;
        } else if (id.startsWith("kodak_portra")) {
            v.band = 0xFFE8B90C;
        } else if (id.equals("kodak_tri_x_400") || id.equals("kodak_tmax_100")) {
            v.body = 0xFFF2F0EA; v.band = 0xFF141414;
            v.bandInk = 0xFFF2F0EA; v.ink = 0xFF141414;
            v.tech = "BLACK & WHITE FILM";
        } else if (id.startsWith("kodak_vision3")) {
            v.body = 0xFF1B1B1B; v.ink = 0xFFF4F2EC;
            v.tech = "CINE FILM · 400 FT";
            if (id.endsWith("50d")) v.band = 0xFFF2C300;
            else if (id.endsWith("250d")) v.band = 0xFFE07A24;
            else if (id.endsWith("200t")) v.band = 0xFFD64545;
            else v.band = 0xFFC8202F;
        } else if (id.equals("kodak_verita_200d")) {
            v.body = 0xFF1F6E43; v.ink = 0xFFF4F2EC;
        } else if (id.equals("kodak_ektachrome_100")) {
            v.body = 0xFF101010; v.band = 0xFFF2C300;
            v.bandInk = 0xFF171717; v.ink = 0xFFF4F2EC;
            v.tech = "COLOUR REVERSAL";
        } else if (id.equals("kodak_kodachrome_64")) {
            v.body = 0xFFC8102E; v.ink = 0xFFF7F4EF;
            v.tech = "COLOUR REVERSAL";
        } else if (id.equals("fujifilm_c200")) {
            v.body = 0xFF009A44; v.band = 0xFF6D2C91;
            v.bandInk = 0xFFFFFFFF; v.ink = 0xFFFFFFFF;
        } else if (id.equals("fujifilm_xtra_400")) {
            v.body = 0xFF007A3D; v.ink = 0xFFFFFFFF; v.bandInk = 0xFFFFFFFF;
        } else if (id.equals("fujifilm_pro_400h")) {
            v.body = 0xFF007A3D; v.band = 0xFFF4F2EC;
            v.bandInk = 0xFF007A3D; v.ink = 0xFFFFFFFF;
        } else if (id.equals("fujifilm_neopan_acros_100_ii")) {
            v.body = 0xFFF4F2EC; v.band = 0xFF141414;
            v.bandInk = 0xFFF4F2EC; v.ink = 0xFF141414;
            v.tech = "BLACK & WHITE FILM";
        } else if (id.equals("fujifilm_velvia_100")) {
            v.body = 0xFF101010; v.band = 0xFFC8102E;
            v.bandInk = 0xFFF4F2EC; v.ink = 0xFFF4F2EC;
            v.tech = "COLOUR REVERSAL";
        } else if (id.equals("fujifilm_provia_100f")) {
            v.body = 0xFFF4F2EC; v.band = 0xFF007A3D;
            v.ink = 0xFF0B6B38; v.tech = "COLOUR REVERSAL";
        }

        // The name, minus the brand it already shows in the band.
        String m = caption == null ? "" : caption;
        final int dot = m.indexOf(" · ");
        if (dot > 0) m = m.substring(0, dot);
        for (String b : new String[]{"Kodak ", "Fujifilm ", "Ilford ", "CineStill "}) {
            if (m.startsWith(b)) { m = m.substring(b.length()); break; }
        }
        v.model = m;

        final java.util.regex.Matcher mt =
                java.util.regex.Pattern.compile("(\\d{2,4})\\s*(D|T)?").matcher(m);
        while (mt.find()) v.iso = mt.group(1) + (mt.group(2) == null ? "" : mt.group(2));
        return v;
    }

    /**
     * A film box, drawn from a palette.
     *
     * <p>Shown on the shelf only when there is no photograph yet -- once a
     * frame is loaded the card is the stock's own rendering of that frame,
     * which is more informative than any package. Before that, a grey
     * rectangle with the name typed into it is a placeholder; this reads as a
     * film at a glance, which is what the shelf is for.
     */
    private static class FilmCoverView extends View {
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path clip = new android.graphics.Path();
        private final android.graphics.RectF full = new android.graphics.RectF();
        private final int body, band, ink, bandInk, accent;
        private final String wordmark, model, tech, iso;
        private android.graphics.LinearGradient gloss;
        private int glossW, glossH;

        FilmCoverView(android.content.Context c, String id, String caption) {
            super(c);
            final Cover v = coverFor(id, caption);
            body = v.body;
            band = v.band;
            ink = v.ink;
            bandInk = v.bandInk;
            accent = v.accent;
            wordmark = v.wordmark;
            model = v.model;
            tech = v.tech;
            iso = v.iso;
        }

        private android.graphics.Typeface condensed() {
            return android.graphics.Typeface.create(
                    "sans-serif-condensed", android.graphics.Typeface.BOLD);
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            final int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            final float d = getResources().getDisplayMetrics().density;
            final float pad = Math.max(2f, w * 0.055f);

            full.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(full, 2f * d, 2f * d, android.graphics.Path.Direction.CW);
            c.save();
            c.clipPath(clip);

            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setShader(null);
            paint.setColor(body);
            c.drawRect(0, 0, w, h, paint);

            final float bandH = h * 0.30f;
            paint.setColor(band);
            c.drawRect(0, 0, w, bandH, paint);

            // --- the band: wordmark left, speed right -------------------
            paint.setTypeface(condensed());
            paint.setColor(bandInk);
            paint.setTextAlign(android.graphics.Paint.Align.LEFT);
            float ws = Math.max(5f * d, w * 0.125f);
            paint.setTextSize(ws);
            final float isoRoom = iso.isEmpty() ? 0f : w * 0.26f;
            float wmW = paint.measureText(wordmark);
            if (wmW > w - pad * 2 - isoRoom) {
                ws *= (w - pad * 2 - isoRoom) / wmW;
                paint.setTextSize(ws);
            }
            final float base = bandH * 0.5f + ws * 0.36f;
            c.drawText(wordmark, pad, base, paint);

            if (!iso.isEmpty()) {
                paint.setTypeface(android.graphics.Typeface.create(
                        "sans-serif", android.graphics.Typeface.NORMAL));
                paint.setTextSize(Math.max(4.5f * d, w * 0.072f));
                paint.setTextAlign(android.graphics.Paint.Align.RIGHT);
                c.drawText(iso, w - pad, base, paint);
                paint.setTextAlign(android.graphics.Paint.Align.LEFT);
            }

            // --- the name, one or two lines ------------------------------
            paint.setTypeface(condensed());
            paint.setColor(ink);
            final float room = w - pad * 2;
            // The foot's optical top, measured from its own baseline: the two
            // text blocks used to be laid out independently, and a long name
            // ("Neopan 100 Acros II") ended 0.2 dp above the fine print.
            final float footSize = Math.max(4f * d, w * 0.058f);
            final float footTop = h - 4.2f * d - footSize * 0.95f;
            final float avail = footTop - bandH - 2.5f * d;
            final String[] lines = twoLines(model, room, Math.max(5f * d, w * 0.15f));
            float size = Math.max(5f * d, w * 0.15f);
            for (;;) {
                paint.setTextSize(size);
                float widest = 0f;
                for (String ln : lines) widest = Math.max(widest, paint.measureText(ln));
                // Both constraints, not just the width: a name that fits
                // sideways can still be too tall for the body.
                final boolean fits = widest <= room && size * 1.06f * lines.length <= avail;
                if (fits || size <= 4.2f * d) break;
                size -= 0.4f * d;
            }
            final float lh = size * 1.06f;
            float y = bandH + 2.5f * d + (avail - lh * lines.length) * 0.5f + size * 0.82f;
            for (String ln : lines) {
                c.drawText(ln, pad, y, paint);
                y += lh;
            }

            // --- the foot: what kind of film this is ---------------------
            paint.setTypeface(android.graphics.Typeface.create(
                    "sans-serif", android.graphics.Typeface.NORMAL));
            paint.setTextSize(footSize);
            paint.setColor(ink);
            paint.setAlpha(0xB8);
            c.drawText(tech, pad, h - 4.2f * d, paint);
            paint.setAlpha(255);

            // The one graphic device every box has: a short rule under the band.
            paint.setColor(accent);
            c.drawRect(pad, bandH, pad + w * 0.24f, bandH + Math.max(1.5f, 1.1f * d), paint);

            // A coat of gloss, so the swatch reads as printed matter.
            if (gloss == null || glossW != w || glossH != h) {
                gloss = new android.graphics.LinearGradient(0, 0, w, h,
                        0x1EFFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP);
                glossW = w;
                glossH = h;
            }
            paint.setShader(gloss);
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            paint.setColor(0x33000000);
            c.drawRect(0, 0, w, h, paint);
            c.restore();
        }

        /** One line if it fits, else split at the space nearest the middle. */
        private String[] twoLines(String s, float room, float maxSize) {
            paint.setTextSize(maxSize);
            if (paint.measureText(s) <= room) return new String[]{s};
            int best = -1;
            float bestScore = Float.MAX_VALUE;
            for (int i = 0; i < s.length(); ++i) {
                if (s.charAt(i) != ' ') continue;
                final float a = paint.measureText(s.substring(0, i));
                final float b = paint.measureText(s.substring(i + 1));
                final float score = Math.max(a, b);
                if (score < bestScore) { bestScore = score; best = i; }
            }
            if (best < 0) return new String[]{s};
            return new String[]{s.substring(0, best), s.substring(best + 1)};
        }
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
        if (original) {
            TextView mark = label("—", 22, UI_MUTED, tfDisplay);
            mark.setGravity(android.view.Gravity.CENTER);
            imageBox.addView(mark, new android.widget.FrameLayout.LayoutParams(-1, -1));
        } else if (beforeBitmap == null) {
            // **No photograph yet, so there is nothing to preview.** A name
            // typed into a grey rectangle says "empty slot"; a drawn box front
            // says "this is a film". The shelf's job is choosing a stock, and
            // before the first import that is the only thing it can show.
            imageBox.addView(new FilmCoverView(this, id, text),
                    new android.widget.FrameLayout.LayoutParams(-1, -1));
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
        refreshHueOwnMark();
        onStockChanged();
    }

    /** True when the stock's hue table is not trustworthy enough to apply. */
    private static boolean hueFixUnsupported(String stock) {
        return stock != null && HUE_FIX_UNSUPPORTED.contains(stock);
    }

    /** What actually goes on the wire: the preference, clamped off where we
     *  know the table is wrong. Also used by the preview and by export. */
    private float wireHueCorrection(String stock) {
        return hueFixUnsupported(stock) ? 0f : hueCorrection;
    }

    private void refreshHueOwnMark() {
        if (hueOwnMark != null) hueOwnMark.setLit(hueFixUnsupported(filmStock) || hueCorrection < 0.5f);
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

    // ---------------------------------------------------------------------
    // The view layer, on Lightroom's shape: a row of categories, and the
    // sliders that belong to the one you picked.
    // ---------------------------------------------------------------------
    //
    // The arithmetic is the engine's own grade (`spk_view_grade_body.glsl`,
    // itself the Metal port), because the C API has no entry for that layer:
    // it lives behind `spk::view::render`, a desktop path. Running the same
    // numbers on the finished print keeps the film pipeline bit-exact -- with
    // every control at zero the pass is skipped.

    private float viewVibrance = 0f;

    // Decode-time white balance. The only control here that re-reads the file:
    // a raw's balance is part of the demosaic, not of the print.
    private int wbPreset = 0;                       // 0 as-shot, 1..5 preset, 6 custom
    /** The camera gains that produced the preview, kept so an export can be
     *  decoded with the same white balance. `redecodeWithWhiteBalance` applies
     *  them inside LibRaw (demosaic-time), not through `spk_set_params`, so a
     *  fresh export session that does not receive them decodes as-shot -- the
     *  preview is neutral, the file is yellow, and nothing in the parameter
     *  tree explains the difference. */
    private volatile float[] wbUserMul = null;
    /** True while the file is being re-read; renders stand down meanwhile. */
    private volatile boolean wbDecoding = false;
    private volatile boolean wbPending = false;
    private int wbGeneration = 0;
    private float wbKelvin = 5500f, wbTint = 0f;
    private static final int[] WB_KELVIN = {0, 5500, 6500, 7500, 3200, 4000, 0};

    /** Four-zone colour balance: hue in degrees, amount 0..1, lightness -1..1. */
    private final float[] cbHue = new float[4];
    private final float[] cbSat = new float[4];
    private final float[] cbLum = new float[4];

    /** Curve control points per channel: rgb, luma, red, green, blue. */
    private final java.util.List<float[]>[] curvePoints = new java.util.List[5];
    private int curveChannel = 0;
    private CurveView curveView;

    /** Every row knows how to re-read its own field, so a Look can move them. */
    private final java.util.List<Runnable> viewRefreshers = new java.util.ArrayList<>();
    private final LinearLayout[] viewPages = new LinearLayout[5];
    /** The rendered result's RGB distribution; null until the panel is built. */
    private HistogramView histogram = null;
    /** A display preference, not a look: it is stored beside the theme. */
    private boolean histogramOn = true;
    /** Which 光学 sub-page is showing; 重置 acts on this one only. */
    private int viewPage = 0;
    private TextView[] viewCatChips;


    /**
     * The rendered result's own RGB distribution.
     *
     * <p>Three channels over one 256-bin axis, composited with SCREEN so the
     * places where all three overlap read as white -- that is the shape a
     * useful histogram has, and plain alpha over alpha turns the overlap into
     * mud. Three things make it legible at 148 x 54 dp:
     *
     * <ul>
     *   <li>the vertical scale is a high percentile of the column heights, not
     *       the maximum. A night frame is half black, one bin at 0 then owns
     *       the peak and everything else is flattened into the floor;</li>
     *   <li>the bins get a 1-2-1 pass before they are drawn, so a single-bin
     *       spike reads as a peak rather than a one-pixel needle that the
     *       column downsampler may or may not land on;</li>
     *   <li>clipping is counted, not guessed: past a tenth of a percent at
     *       either end a marker appears in that corner.</li>
     * </ul>
     */
    private static class HistogramView extends View {
        /** A tenth of a percent of the samples: below this it is noise. */
        private static final double CLIP_SHARE = 0.001;

        private final int[] r = new int[256], g = new int[256], b = new int[256];
        private final int[] rs = new int[256], gs = new int[256], bs = new int[256];
        private int clipLo, clipHi, samples = 1;
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final android.graphics.Path plate = new android.graphics.Path();
        private final android.graphics.RectF box = new android.graphics.RectF();
        private final boolean screenBlend;

        HistogramView(android.content.Context c) {
            super(c);
            // Paint.setBlendMode arrived in API 29; before that the fills fall
            // back to straight alpha, which is duller but never wrong.
            screenBlend = android.os.Build.VERSION.SDK_INT >= 29;
        }

        void setBitmap(Bitmap bm) {
            java.util.Arrays.fill(r, 0);
            java.util.Arrays.fill(g, 0);
            java.util.Arrays.fill(b, 0);
            clipLo = 0;
            clipHi = 0;
            samples = 1;
            if (bm == null || bm.getWidth() < 8 || bm.getHeight() < 8) { invalidate(); return; }
            // A fixed sample budget. 40 000 samples resolve 256 bins as well
            // as 3 M do; walking a full 12 MP bitmap would cost 48 MB and
            // stall the UI thread the moment a render finishes.
            final double step = Math.max(1.0,
                    Math.sqrt((double) bm.getWidth() * bm.getHeight() / 40000.0));
            final int w = Math.max(8, (int) Math.round(bm.getWidth() / step));
            final int h = Math.max(8, (int) Math.round(bm.getHeight() / step));
            final Bitmap small = (w == bm.getWidth() && h == bm.getHeight())
                    ? bm : Bitmap.createScaledBitmap(bm, w, h, true);
            final int[] px = new int[w * h];
            small.getPixels(px, 0, w, 0, 0, w, h);
            if (small != bm) small.recycle();
            int lo = 0, hi = 0;
            for (int i = 0; i < px.length; ++i) {
                final int p = px[i];
                final int ri = (p >> 16) & 255, gi = (p >> 8) & 255, bi = p & 255;
                ++r[ri];
                ++g[gi];
                ++b[bi];
                if (ri == 0 || gi == 0 || bi == 0) ++lo;
                if (ri == 255 || gi == 255 || bi == 255) ++hi;
            }
            samples = px.length;
            clipLo = lo;
            clipHi = hi;
            smooth(r, rs);
            smooth(g, gs);
            smooth(b, bs);
            invalidate();
        }

        /** 1-2-1: a one-bin spike becomes a peak several columns wide. */
        private static void smooth(int[] in, int[] out) {
            out[0] = (in[0] * 3 + in[1]) / 4;
            for (int i = 1; i < 255; ++i) out[i] = (in[i - 1] + in[i] * 2 + in[i + 1]) / 4;
            out[255] = (in[255] * 3 + in[254]) / 4;
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            final int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            final float d = getResources().getDisplayMetrics().density;

            // A plate of its own, rather than a flat 30% wash: over a white
            // sky the traces used to disappear into the photograph.
            box.set(0, 0, w, h);
            plate.reset();
            plate.addRoundRect(box, 3f * d, 3f * d, android.graphics.Path.Direction.CW);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(0xB3000000);
            c.drawPath(plate, paint);

            c.save();
            c.clipPath(plate);

            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            paint.setColor(0x1FFFFFFF);
            for (int i = 1; i < 4; ++i) {
                final float x = w * i / 4f;
                c.drawLine(x, 0, x, h, paint);
            }
            paint.setColor(0x14FFFFFF);
            c.drawLine(0, h * 0.5f, w, h * 0.5f, paint);

            if (screenBlend) paint.setBlendMode(android.graphics.BlendMode.SCREEN);
            drawChannel(c, rs, 0xFFFF5A4E, w, h);
            drawChannel(c, gs, 0xFF47D97A, w, h);
            drawChannel(c, bs, 0xFF4E9BFF, w, h);
            if (screenBlend) paint.setBlendMode(android.graphics.BlendMode.SRC_OVER);

            // The corner markers both good editors have. Filled = at least one
            // channel is losing samples at that end.
            final float m = 3.4f * d;
            paint.setStyle(android.graphics.Paint.Style.FILL);
            if (clipLo > CLIP_SHARE * samples) {
                paint.setColor(0xF2FFFFFF);
                path.reset();
                path.moveTo(0, h);
                path.lineTo(m * 1.7f, h);
                path.lineTo(0, h - m);
                path.close();
                c.drawPath(path, paint);
            }
            if (clipHi > CLIP_SHARE * samples) {
                paint.setColor(0xF2FFFFFF);
                path.reset();
                path.moveTo(w, h);
                path.lineTo(w - m * 1.7f, h);
                path.lineTo(w, h - m);
                path.close();
                c.drawPath(path, paint);
            }
            c.restore();

            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, 0.6f * d));
            paint.setColor(0x38FFFFFF);
            c.drawPath(plate, paint);
        }

        private void drawChannel(android.graphics.Canvas c, int[] bins, int color, int w, int h) {
            final int[] heights = new int[w];
            int max = 1;
            for (int x = 0; x < w; ++x) {
                final int a = x * 256 / w, e = Math.max(a + 1, (x + 1) * 256 / w);
                int v = 0;
                for (int i = a; i < e && i < 256; ++i) v = Math.max(v, bins[i]);
                heights[x] = v;
                if (v > max) max = v;
            }
            // Normalise on the tall columns, with a ceiling of 1.5x the 97th
            // percentile: a lone spike can reach the top, but it cannot press
            // the other 255 bins into the bottom pixel row.
            final int[] sorted = heights.clone();
            java.util.Arrays.sort(sorted);
            final int cut = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.97f))];
            // Floor of max/8 as well: if the 97th percentile lands on an empty
            // column (a synthetic or almost-empty channel), normalising on it
            // would clamp every column to the top and draw a solid block.
            final int norm = Math.max(Math.max(1, max / 8),
                    Math.min(max, Math.max(1, cut) * 3 / 2));

            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(color);
            path.reset();
            path.moveTo(0, h);
            for (int x = 0; x < w; ++x) {
                final float t = Math.min(1f, heights[x] / (float) norm);
                path.lineTo(x, h - t * (h - 2f));
            }
            path.lineTo(w, h);
            path.close();
            c.drawPath(path, paint);

            // A crisp edge on the trace: without it three translucent fills
            // read as fog rather than as three curves.
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            paint.setColor(color);
            path.reset();
            for (int x = 0; x < w; ++x) {
                final float t = Math.min(1f, heights[x] / (float) norm);
                final float y = h - t * (h - 2f);
                if (x == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            c.drawPath(path, paint);
        }
    }

    private LinearLayout buildViewPanel() {
        final LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(sectionHead("OPTICS / 光学调整", this::resetViewPanel),
                new LinearLayout.LayoutParams(-1, dp(38)));

        final String[] cats = {"亮度", "颜色", "效果", "曲线", "分级"};
        final LinearLayout catRow = new LinearLayout(this);
        catRow.setOrientation(LinearLayout.HORIZONTAL);
        viewCatChips = new TextView[cats.length];
        for (int i = 0; i < cats.length; ++i) {
            final int page = i;
            final TextView chip = label(cats[i], 8, i == 0 ? UI_BLUE : UI_MUTED, tfBody);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setOnClickListener(v -> showViewPage(page));
            catRow.addView(chip, new LinearLayout.LayoutParams(0, dp(26), 1f));
            viewCatChips[i] = chip;
        }
        panel.addView(catRow, new LinearLayout.LayoutParams(-1, dp(28)));

        for (int i = 0; i < viewPages.length; ++i) {
            viewPages[i] = new LinearLayout(this);
            viewPages[i].setOrientation(LinearLayout.VERTICAL);
            viewPages[i].setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            final CappedScroll scroll = new CappedScroll(this, dp(280));
            scroll.addView(viewPages[i], new android.widget.FrameLayout.LayoutParams(-1, -2));
            panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
            // The scroll has to hide with its page, so the page list is the
            // one that gets toggled and the scroll is kept beside it.
            viewScrolls[i] = scroll;
        }
        buildTonePage(viewPages[0]);
        buildColourPage(viewPages[1]);
        buildEffectsPage(viewPages[2]);
        buildCurvesPage(viewPages[3]);
        buildGradingPage(viewPages[4]);

        for (int i = 0; i < curvePoints.length; ++i) {
            curvePoints[i] = new java.util.ArrayList<>();
            curvePoints[i].add(new float[]{0f, 0f});
            curvePoints[i].add(new float[]{1f, 1f});
        }
        return panel;
    }

    private final CappedScroll[] viewScrolls = new CappedScroll[5];

    private void showViewPage(int page) {
        viewPage = page;
        for (int i = 0; i < viewPages.length; ++i) {
            final boolean on = i == page;
            if (viewPages[i] != null) viewPages[i].setVisibility(on ? View.VISIBLE : View.GONE);
            if (viewScrolls[i] != null) viewScrolls[i].setVisibility(on ? View.VISIBLE : View.GONE);
            if (viewCatChips != null) viewCatChips[i].setTextColor(on ? UI_BLUE : UI_MUTED);
        }
    }

    /** One slider: name and value on a line, the track under it. */
    private void adjustRow(LinearLayout parent, String title, float lo, float hi,
                           java.util.function.Supplier<Float> get,
                           java.util.function.Consumer<Float> set,
                           boolean redecode) {
        adjustRow(parent, title, lo, hi, get, set, redecode, null);
    }

    private void adjustRow(LinearLayout parent, String title, float lo, float hi,
                           java.util.function.Supplier<Float> get,
                           java.util.function.Consumer<Float> set,
                           boolean redecode, String hint) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(android.view.Gravity.CENTER_VERTICAL);
        line.addView(label(title, 9, UI_INK, tfHan), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView value = label("", 9, UI_BLUE, tfBody);
        // Tap the number to type one, as Lightroom does.
        value.setOnClickListener(v -> editNumber(title, lo, hi, get.get(), set));
        line.addView(value);
        box.addView(line, new LinearLayout.LayoutParams(-1, dp(20)));

        final ScrubBar bar = new ScrubBar(this);
        bar.setMax(1000);
        styleRange(bar);
        final Runnable refresh = () -> {
            final float v = get.get();
            bar.setProgress(Math.max(0, Math.min(1000,
                    Math.round((v - lo) / (hi - lo) * 1000f))));
            value.setText(String.format(java.util.Locale.US, "%.2f", v));
        };
        viewRefreshers.add(refresh);
        refresh.run();
        // Double-tapping the thumb puts the row back where it started.
        final float def = lo <= 0f && hi >= 0f ? 0f : (lo >= 2000f && hi <= 12000f ? 5500f : lo);
        bar.setResetTarget(Math.round((def - lo) / (hi - lo) * 1000f), () -> {
            set.accept(def);
            refresh.run();
            // **The UI thread does not send the delta.** `spk_set_params` takes
            // `session->lock`, which `render_tier` holds for the whole render:
            // sent from here while a render is in flight it blocks the main
            // thread for that long, and then rebuilds the pipeline on the UI
            // thread right as the render finishes. `renderImage` sends the same
            // delta (with the stock) on the render thread, under the lock, once.
            if (uiReady && session != null) {
                showingOriginal = false;
                setOriginalVisual(false);
                renderImage();
            }
        });
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                final float v = lo + (hi - lo) * progress / 1000f;
                set.accept(v);
                value.setText(String.format(java.util.Locale.US, "%.2f", v));
                // **The thumb being down is the whole point of a slider.**
                // Drawing only on release made every row feel like a form
                // field with a delay; the value is already in the field by the
                // time this returns, so the preview is what the number says.
                // `redecode` rows are the decode white balance: they close and
                // re-open the file, which a live frame cannot ride along with.
                if (bar.isDragging() && !redecode) {
                    requestLivePreview();
                } else if (!redecode) {
                    // The value moved but the bar says it is not being dragged:
                    // either the framework drove `setProgress` (a refresh) or
                    // `dragging` never got set. Either way no preview is asked
                    // for, and this is the only line that can say which.
                    liveLog("progress without drag");
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                if (redecode) {
                    // The file is about to be read again; rendering the old one
                    // first would only queue behind the decode.
                    wbPreset = 6;
                    redecodeWithWhiteBalance();
                    return;
                }
                if (uiReady && session != null) {
                    showingOriginal = false;
                    setOriginalVisual(false);
                    renderImage();
                }
            }
        });
        box.addView(bar, new LinearLayout.LayoutParams(-1, dp(32)));
        if (hint != null) {
            final TextView note = label(hint, 7.5f, UI_MUTED, tfHan);
            note.setMaxLines(2);
            box.addView(note, new LinearLayout.LayoutParams(-1, dp(18)));
        }
        parent.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout wbDecodeBox;
    private TextView wbDecodeNote;

    private void buildWhiteBalancePage(LinearLayout page) {
        // **The decode half only exists for a RAW.** LibRaw applies the
        // balance while demosaicing, so a JPEG has no negative to re-develop:
        // the chips and both Kelvin sliders did nothing at all on `xxx.jpg`,
        // which reads as "白平衡不起效". The box is kept so the page can grey
        // it out and say why once the file is known.
        wbDecodeBox = new LinearLayout(this);
        wbDecodeBox.setOrientation(LinearLayout.VERTICAL);
        page.addView(wbDecodeBox, new LinearLayout.LayoutParams(-1, -2));
        wbDecodeBox.addView(label("白平衡（解码）/ WHITE BALANCE", 8, UI_INK, tfHan),
                new LinearLayout.LayoutParams(-1, dp(22)));
        final String[] names = {"原照", "日光", "阴天", "阴影", "钨丝", "荧光", "自定义"};
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        final TextView[] chips = new TextView[names.length];
        for (int i = 0; i < names.length; ++i) {
            final int preset = i;
            final TextView chip = label(names[i], 8, i == 0 ? UI_BLUE : UI_MUTED, tfBody);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setBackground(outlineBox(i == 0));
            chip.setOnClickListener(v -> {
                wbPreset = preset;
                for (int k = 0; k < chips.length; ++k) {
                    chips[k].setTextColor(k == preset ? UI_BLUE : UI_MUTED);
                    chips[k].setBackground(outlineBox(k == preset));
                }
                redecodeWithWhiteBalance();
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(58), dp(30));
            lp.rightMargin = dp(4);
            row.addView(chip, lp);
            chips[i] = chip;
        }
        scroll.addView(row, new android.widget.FrameLayout.LayoutParams(-2, -1));
        wbDecodeBox.addView(scroll, new LinearLayout.LayoutParams(-1, dp(36)));
        wbDecodeNote = label("这一页改的是「拍的时候用的是什么光」：白平衡写在去马赛克里，"
                + "所以每次都按当前尺寸重新解码一次。", 7.5f, UI_MUTED, tfHan);
        wbDecodeNote.setMaxLines(2);
        wbDecodeBox.addView(wbDecodeNote, new LinearLayout.LayoutParams(-1, dp(32)));
        adjustRow(wbDecodeBox, "解码色温", 2000f, 12000f, () -> wbKelvin, v -> wbKelvin = v, true,
                "光源的色温。3200K 钨丝、5500K 日光；数值越低画面越暖");
        adjustRow(wbDecodeBox, "解码色调", -150f, 150f, () -> wbTint, v -> wbTint = v, true,
                "绿-品红轴。日光灯发绿就往正值拖");
    }

    private void buildTonePage(LinearLayout page) {
        final LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        top.addView(label("", 9, UI_MUTED, tfHan), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView reset = label("复位", 8, UI_BLUE, tfHan);
        reset.setBackground(outlineBox(false));
        reset.setGravity(android.view.Gravity.CENTER);
        reset.setPadding(dp(10), 0, dp(10), 0);
        reset.setOnClickListener(v -> resetViewPanel());
        top.addView(reset, new LinearLayout.LayoutParams(-2, dp(28)));
        page.addView(top, new LinearLayout.LayoutParams(-1, dp(32)));

        // The histogram's own switch. It changes no pixels, so it must not go
        // through `applyParams` -- it shows or hides the plate on the canvas,
        // and the choice outlives the photograph.
        final Square histMark = new Square(this, UI_BLUE, uiToggleOff());
        page.addView(toggleRow("直方图", histMark, () -> histogramOn, on -> {
            histogramOn = on;
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("histogram", on).apply();
            if (histogram != null) histogram.setVisibility(on ? View.VISIBLE : View.GONE);
        }), new LinearLayout.LayoutParams(-1, -2));

        // **How the canvas reads the decode, not what the engine gets.**
        // Off: the numbers exactly as LibRaw handed them, through sRGB only.
        // On: the median goes to sRGB mid-grey (capped where p99.5 would clip)
        // and the encoded shadows are lifted. That is how every raw was shown
        // from 1.5.1 through 1.6.6a, after an import that came back at 2/255
        // looked like a black rectangle -- but it also hides the picture's own
        // exposure, and it ran 2.54 EV above Lightroom on the reference DNG.
        final Square levelMark = new Square(this, UI_BLUE, uiToggleOff());
        page.addView(toggleRow("原图自动提亮", levelMark, () -> rawAutoBright, on -> {
            rawAutoBright = on;
            getSharedPreferences("ui", MODE_PRIVATE).edit()
                    .putBoolean("raw_auto_bright", on).apply();
            refreshOriginalView();
        }), new LinearLayout.LayoutParams(-1, -2));

        adjustRow(page, "相机曝光", -4f, 4f, () -> cameraEv, v -> cameraEv = v, false);
        adjustRow(page, "曝光", -3f, 3f, () -> viewExposure, v -> viewExposure = v, false);
        adjustRow(page, "对比", -100f, 100f, () -> viewContrast, v -> viewContrast = v, false);
        adjustRow(page, "亮度", -100f, 100f, () -> viewBrightness, v -> viewBrightness = v, false);
        adjustRow(page, "高光", -100f, 100f, () -> viewHighlights, v -> viewHighlights = v, false);
        adjustRow(page, "阴影", -100f, 100f, () -> viewShadows, v -> viewShadows = v, false);
        adjustRow(page, "黑场", 0f, 50f, () -> viewBlack, v -> viewBlack = v, false);
        adjustRow(page, "白场", 0f, 50f, () -> viewWhite, v -> viewWhite = v, false);
    }

    private void buildColourPage(LinearLayout page) {
        // LightCraft's Color tab opens on the white balance: the presets, then
        // the two decode-time numbers, then the print-side ones.
        buildWhiteBalancePage(page);
        page.addView(label("调色 / TONE", 8, UI_INK, tfHan),
                new LinearLayout.LayoutParams(-1, dp(24)));
        final TextView wbNote = label(
                "上面两个 = 解码时改（等于换一种光拍，会重新解码）；下面两个 = 冲印后再偏色（立刻生效，"
                + "亮部会跟着平移）。", 7.5f, UI_MUTED, tfHan);
        wbNote.setMaxLines(2);
        page.addView(wbNote, new LinearLayout.LayoutParams(-1, dp(32)));
        adjustRow(page, "色温", -100f, 100f, () -> viewTemperature, v -> viewTemperature = v, false,
                "冲印完成后的整体偏暖偏冷。不动解码，改的是这张照片");
        adjustRow(page, "色调", -100f, 100f, () -> viewTint, v -> viewTint = v, false,
                "冲印完成后的绿-品红，同上，只影响已经出好的图");
        adjustRow(page, "饱和度", -100f, 100f, () -> viewSaturation, v -> viewSaturation = v, false);
        adjustRow(page, "自然饱和度", -100f, 100f, () -> viewVibrance, v -> viewVibrance = v, false,
                "只提暗中带色的部分，已经很艳的不动");

    }

    private void buildGradingPage(LinearLayout page) {
        // Four zones, one wheel -- Lightroom's shape: pick the tonal range,
        // then drag a dot. Hue is the angle, saturation the distance from grey.
        final String[] zones = {"主", "阴影", "中间调", "高光"};
        final LinearLayout zoneRow = new LinearLayout(this);
        zoneRow.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] zoneChips = new TextView[zones.length];
        for (int i = 0; i < zones.length; ++i) {
            final int zone = i;
            final TextView chip = label(zones[i], 8, i == 0 ? UI_BLUE : UI_MUTED, tfBody);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setOnClickListener(v -> {
                cbZone = zone;
                for (int k = 0; k < zoneChips.length; ++k)
                    zoneChips[k].setTextColor(k == zone ? UI_BLUE : UI_MUTED);
                if (colorWheel != null) colorWheel.invalidate();
                cbReadout = null;
                refreshViewPanel();
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(26), 1f);
            lp.rightMargin = dp(4);
            zoneRow.addView(chip, lp);
            zoneChips[i] = chip;
        }
        page.addView(zoneRow, new LinearLayout.LayoutParams(-1, dp(30)));

        colorWheel = new ColorWheel();
        page.addView(colorWheel, new LinearLayout.LayoutParams(-1, dp(196)));

        cbReadout = label("", 8, UI_MUTED, tfBody);
        cbReadout.setGravity(android.view.Gravity.CENTER);
        page.addView(cbReadout, new LinearLayout.LayoutParams(-1, dp(20)));
        refreshCbReadout();

        adjustRow(page, "明亮度", -1f, 1f, () -> cbLum[cbZone], v -> cbLum[cbZone] = v, false,
                "这个明暗区间整体提亮或压暗");
    }

    private int cbZone = 0;
    private ColorWheel colorWheel;
    private TextView cbReadout;

    private void refreshCbReadout() {
        if (cbReadout == null) return;
        final int hue = Math.round(cbHue[cbZone]);
        cbReadout.setText(String.format(java.util.Locale.US,
                "色相 %d°   饱和度 %.0f%%", hue, cbSat[cbZone] * 100f));
    }

    /**
     * The grading wheel: hue is the angle, saturation is how far the handle is
     * from the middle, and the middle is "no cast".
     */
    private final class ColorWheel extends View {
        private Bitmap disc;
        private int discSize = 0;
        private int dragZone = -1;
        /** 1 = the rim (hue only), 2 = the handle (saturation only). */
        private int dragMode = 0;

        ColorWheel() { super(MainActivity.this); }

        private float radius() { return Math.min(getWidth(), getHeight()) / 2f - dp(6); }

        private void buildDisc(int size) {
            disc = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            final float r = size / 2f;
            final int[] px = new int[size * size];
            final float[] hsv = new float[3];
            for (int y = 0; y < size; ++y) {
                for (int x = 0; x < size; ++x) {
                    final float dx = x - r, dy = y - r;
                    final float d = (float) Math.hypot(dx, dy) / r;
                    if (d > 1f) { px[y * size + x] = 0; continue; }
                    float hue = (float) Math.toDegrees(Math.atan2(-dy, dx));
                    if (hue < 0) hue += 360f;
                    hsv[0] = hue;
                    hsv[1] = d;
                    hsv[2] = 1f;
                    final int c = android.graphics.Color.HSVToColor(hsv);
                    // Fade the rim out rather than leaving a hard circle edge.
                    final int a = (int) (255 * Math.min(1f, (1f - d) * 24f));
                    px[y * size + x] = (c & 0x00FFFFFF) | (a << 24);
                }
            }
            disc.setPixels(px, 0, size, 0, 0, size, size);
            discSize = size;
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            final int size = (int) (radius() * 2f);
            if (size <= 0) return;
            if (disc == null || discSize != size) buildDisc(size);
            final float cx = getWidth() / 2f, cy = getHeight() / 2f;
            canvas.drawBitmap(disc, cx - radius(), cy - radius(), null);
            final float hue = (float) Math.toRadians(cbHue[cbZone]);
            final float sat = Math.max(0f, Math.min(1f, cbSat[cbZone]));

            // **The radius is a track, so draw it.** A dashed line from the
            // centre out along the current hue is what makes "slide this dot
            // inward for less colour" legible; without it the handle looks
            // like it is floating.
            final android.graphics.Paint track = new android.graphics.Paint();
            track.setStyle(android.graphics.Paint.Style.STROKE);
            track.setStrokeWidth(dp(1));
            track.setColor(0x55FFFFFF);
            track.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(3), dp(4)}, 0f));
            canvas.drawLine(cx, cy,
                    cx + (float) Math.cos(hue) * radius(), cy - (float) Math.sin(hue) * radius(), track);

            // The rim handle: hue, at full saturation, so its own colour says
            // which hue it is.
            final float hx = cx + (float) Math.cos(hue) * radius();
            final float hy = cy - (float) Math.sin(hue) * radius();
            final android.graphics.Paint rimFill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            rimFill.setColor(android.graphics.Color.HSVToColor(new float[]{
                    (cbHue[cbZone] + 360f) % 360f, 1f, 1f}));
            canvas.drawCircle(hx, hy, dp(8), rimFill);
            final android.graphics.Paint rimEdge = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            rimEdge.setStyle(android.graphics.Paint.Style.STROKE);
            rimEdge.setStrokeWidth(dp(1.6f));
            rimEdge.setColor(0xFFFFFFFF);
            canvas.drawCircle(hx, hy, dp(8), rimEdge);

            // The inner handle: saturation, hollow, wherever the radius says.
            final float px = cx + (float) Math.cos(hue) * radius() * sat;
            final float py = cy - (float) Math.sin(hue) * radius() * sat;
            final android.graphics.Paint hole = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            hole.setStyle(android.graphics.Paint.Style.FILL);
            hole.setColor(0x33000000);
            canvas.drawCircle(px, py, dp(9), hole);
            final android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(android.graphics.Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2));
            ring.setColor(UI_INK);
            canvas.drawCircle(px, py, dp(9), ring);
            final android.graphics.Paint ring2 = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            ring2.setStyle(android.graphics.Paint.Style.STROKE);
            ring2.setStrokeWidth(dp(1));
            ring2.setColor(0xAAFFFFFF);
            canvas.drawCircle(px, py, dp(9), ring2);

            final android.graphics.Paint centre = new android.graphics.Paint();
            centre.setStyle(android.graphics.Paint.Style.STROKE);
            centre.setStrokeWidth(dp(1));
            centre.setColor(UI_LINE);
            canvas.drawCircle(cx, cy, dp(4), centre);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    // **A touch that starts outside the disc belongs to the
                    // list.** It used to grab the wheel anyway, so scrolling
                    // the panel threw the grading handle wherever the finger
                    // happened to pass.
                    final float cx = getWidth() / 2f, cy = getHeight() / 2f;
                    final float d = (float) Math.hypot(e.getX() - cx, e.getY() - cy);
                    if (d > radius() + dp(12)) return false;
                    // **Two rings, two jobs.** The outer band is the hue; the
                    // disc inside it is saturation along that hue's radius.
                    // One drag used to move both, so "change the hue" quietly
                    // changed the strength as well.
                    // **The panel scrolls, and a scroll takes the gesture away.**
                    // Without this, moving a finger past the touch slop made the
                    // enclosing `CappedScroll` intercept the stream: the wheel
                    // got ACTION_CANCEL instead of ACTION_MOVE, and its cancel
                    // branch is the one that renders -- so the wheel "rendered
                    // the moment it was touched" and could not be dragged at
                    // all. The bar already does this; the wheel never did.
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    dragZone = cbZone;
                    dragMode = d > radius() * 0.84f ? 1 : 2;
                    return applyWheelTouch(e, dragMode);
                }
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (dragZone != cbZone || dragMode == 0) return false;
                    final boolean handled = applyWheelTouch(e, dragMode);
                    if (handled) requestLivePreview();
                    return handled;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    if (dragZone >= 0) {
                        dragZone = -1;
                        dragMode = 0;
                        pushViewAdjust();
                        applyParams();
                        if (uiReady && session != null) {
                            showingOriginal = false;
                            setOriginalVisual(false);
                            renderImage();
                        }
                    }
                    return dragZone == -1;
                }
                default:
                    return super.onTouchEvent(e);
            }
        }

        private boolean applyWheelTouch(android.view.MotionEvent e, int mode) {
            final float cx = getWidth() / 2f, cy = getHeight() / 2f;
            final float dx = e.getX() - cx, dy = cy - e.getY();
            final float d = (float) Math.hypot(dx, dy);
            if (mode == 1) {
                float hue = (float) Math.toDegrees(Math.atan2(dy, dx));
                if (hue < 0) hue += 360f;
                cbHue[cbZone] = hue > 180f ? hue - 360f : hue;
            } else {
                // Distance only: the hue stays where the rim put it, so a
                // slightly-off drag inward cannot swing the colour.
                cbSat[cbZone] = Math.max(0f, Math.min(1f, d / Math.max(1f, radius())));
            }
            refreshCbReadout();
            invalidate();
            return true;
        }
    }

    private void buildEffectsPage(LinearLayout page) {
        adjustRow(page, "暗角", -100f, 100f, () -> viewVignette, v -> viewVignette = v, false);
        page.addView(label("镜头模糊、光晕、眩光在「冲印」页：它们属于胶片与镜头的物理过程，"
                + "不是冲印之后的调色。", 7.5f, UI_MUTED, tfHan),
                new LinearLayout.LayoutParams(-1, dp(30)));
    }

    private void buildCurvesPage(LinearLayout page) {
        final String[] channels = {"RGB", "亮度", "R", "G", "B"};
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] chips = new TextView[channels.length];
        for (int i = 0; i < channels.length; ++i) {
            final int ch = i;
            final TextView chip = label(channels[i], 8, i == 0 ? UI_BLUE : UI_MUTED, tfBody);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setOnClickListener(v -> {
                curveChannel = ch;
                for (int k = 0; k < chips.length; ++k)
                    chips[k].setTextColor(k == ch ? UI_BLUE : UI_MUTED);
                if (curveView != null) curveView.invalidate();
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(26), 1f);
            lp.rightMargin = dp(4);
            row.addView(chip, lp);
            chips[i] = chip;
        }
        page.addView(row, new LinearLayout.LayoutParams(-1, dp(28)));
        curveView = new CurveView();
        page.addView(curveView, new LinearLayout.LayoutParams(-1, dp(150)));
        page.addView(label("拖动控制点；空白处点一下加点，最多 8 个", 7.5f, UI_MUTED, tfHan),
                new LinearLayout.LayoutParams(-1, dp(18)));
    }

    /** Type a value: Lightroom's "tap the number". */
    private void editNumber(String title, float lo, float hi, float current,
                            java.util.function.Consumer<Float> apply) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(String.format(java.util.Locale.US, "%.2f", current));
        input.setSelectAllOnFocus(true);
        input.setTextSize(14f);
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        final float v = Float.parseFloat(input.getText().toString().trim());
                        apply.accept(Math.max(lo, Math.min(hi, v)));
                        refreshViewPanel();
                        applyParams();
                        if (uiReady && session != null) {
                            showingOriginal = false;
                            setOriginalVisual(false);
                            renderImage();
                        }
                    } catch (Throwable ignored) { }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private android.graphics.drawable.GradientDrawable outlineBox(boolean selected) {
        android.graphics.drawable.GradientDrawable box = new android.graphics.drawable.GradientDrawable();
        box.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        box.setCornerRadius(0f);
        box.setColor(0x00000000);
        box.setStroke(dp(selected ? 2 : 1), selected ? UI_BLUE : UI_LINE);
        return box;
    }

    private void refreshViewPanel() {
        for (Runnable r : viewRefreshers) {
            try { r.run(); } catch (Throwable ignored) { }
        }
        if (curveView != null) curveView.invalidate();
        if (colorWheel != null) colorWheel.invalidate();
        // The switch is driven from `edrOn`, which `applyLook` and the reset
        // button also write.
        if (edrMark != null) edrMark.setLit(edrOn > 0.5f);
        refreshHueOwnMark();
        refreshCbReadout();
        if (wbDecodeBox != null) {
            final boolean raw = isRawFile;
            setEnabledDeepExcept(wbDecodeBox, raw, wbDecodeNote);
            if (wbDecodeNote != null) {
                wbDecodeNote.setText(raw
                        ? "这一页改的是「拍的时候用的是什么光」：白平衡写在去马赛克里，"
                          + "所以每次都按当前尺寸重新解码一次。"
                        : "JPEG 的白平衡已经烧进文件里，不能重解。要改色温请用下面的「色温」"
                          + "「色调」——那是冲印后的偏色，立刻生效。");
            }
        }
    }

    /**
     * 重置 on 光学 resets **the sub-page in front**: 亮度 leaves 颜色 alone,
     * 曲线 leaves 分级 alone. The chips are five different tools that happen
     * to share a page; one button that wipes all of them is a trap, because
     * the user is looking at one of them when they press it.
     */
    private void resetViewPanel() {
        switch (viewPage) {
            case 1:   // 颜色
                viewTemperature = viewTint = viewSaturation = viewVibrance = 0f;
                break;
            case 2:   // 效果
                viewVignette = 0f;
                break;
            case 3:   // 曲线
                for (int i = 0; i < curvePoints.length; ++i) {
                    curvePoints[i].clear();
                    curvePoints[i].add(new float[]{0f, 0f});
                    curvePoints[i].add(new float[]{1f, 1f});
                }
                break;
            case 4:   // 分级
                for (int z = 0; z < 4; ++z) { cbHue[z] = 0f; cbSat[z] = 0f; cbLum[z] = 0f; }
                break;
            default:  // 亮度
                cameraEv = 0f;
                viewExposure = viewContrast = viewBrightness = 0f;
                viewHighlights = viewShadows = viewBlack = viewWhite = 0f;
                break;
        }
        refreshViewPanel();
        pushViewAdjust();
        pushViewCurves();
        if (session != null) { applyParams(); renderImage(); }
    }

    /** The view layer's controls, as the JNI pass expects them. */
    private void pushViewAdjust() {
        try {
            SpektraLabEngine.setViewAdjust(new double[]{
                    viewTemperature, viewTint, viewExposure, viewContrast, viewBrightness,
                    viewSaturation, viewHighlights, viewShadows, viewBlack, viewWhite,
                    viewVignette, VIEW_VIGNETTE_MID,
                    cbHue[0], cbSat[0], cbLum[0], cbHue[1], cbSat[1], cbLum[1],
                    cbHue[2], cbSat[2], cbLum[2], cbHue[3], cbSat[3], cbLum[3],
                    viewVibrance});
        } catch (Throwable ignored) { }
    }

    /**
     * Re-reads the raw with the chosen white balance.
     *
     * <p>The only control on this page that touches the file: LibRaw applies the
     * balance while demosaicing, so it cannot be added afterwards. The camera's
     * XYZ -> camera matrix is read from the open session first -- it is what
     * turns a Kelvin temperature into channel gains for this sensor -- then the
     * session is closed and the file decoded again at the same size.
     */
    private void redecodeWithWhiteBalance() {
        if (!isRawFile || rawFilePath == null) {
            // A JPEG has no negative to re-develop. Saying so once is better
            // than a control that silently does nothing.
            showInAppToast("JPEG 的白平衡已经写在文件里，用下面的「色温」调整");
            return;
        }
        final SpektraLabEngine.Session current = session;
        // Mid-decode there is no session to read the matrix from. Remember that
        // another balance was asked for and run it the moment this one lands.
        if (current == null) { if (wbDecoding) wbPending = true; return; }
        final int generation = ++wbGeneration;
        final int kelvin = wbPreset == 6 ? Math.round(wbKelvin)
                : WB_KELVIN[Math.max(0, Math.min(6, wbPreset))];
        final float tint = wbPreset == 6 ? wbTint : 0f;
        final int cap = importCapPixels;
        final int edge = importCapPixels > 0 ? IMPORT_LONG_EDGE : 0;
        wbDecoding = true;
        runOnUiThread(() -> {
            if (statusText != null) statusText.setText("重新解码…");
        });
        new Thread(() -> {
            float[] mul = null;
            String params = null;
            try {
                // Under the lock only for what touches the session: read the
                // camera matrix and the parameter tree, then let it go. The
                // decode itself is seconds long and must not hold the lock.
                synchronized (renderMutex) {
                    if (wbPreset != 0 && kelvin > 0) {
                        try { mul = SpektraLabEngine.rawMulForKelvin(current, kelvin, tint); }
                        catch (Throwable ignored) { }
                    }
                    // **Carried to the export.** A fresh export session is a
                    // second decode of the same file; without these gains it
                    // starts from the camera's as-shot balance, so the preview
                    // and the file disagree by exactly the WB the user dialled.
                    wbUserMul = mul;
                    Probe.log("wb", "decode preset=" + wbPreset + " K=" + kelvin
                            + " tint=" + tint + " mul=" + Probe.mul(mul) + " cap=" + cap);
                    try { params = current.getParams(); } catch (Throwable ignored) { }
                    try { current.close(); } catch (Throwable ignored) { }
                    session = null;
                }
                SpektraLabEngine.Session fresh = mul != null
                        ? engine.openRawSessionTuned(rawFilePath, params, cap, edge, mul)
                        : engine.openRawSession(rawFilePath, params, cap, edge);
                if (generation != wbGeneration) {
                    // A newer choice arrived while this one decoded; its own
                    // thread will finish the job.
                    try { fresh.close(); } catch (Throwable ignored) { }
                    return;
                }
                synchronized (renderMutex) { session = fresh; }
                final Bitmap src = exportSourceBitmap(fresh, 0.0);
                wbDecoding = false;
                runOnUiThread(() -> {
                    if (src != null) {
                        beforeBitmap = src;
                        if (compare != null) compare.setBefore(src);
                    }
                    applyParams();
                    renderImage();
                    if (wbPending) { wbPending = false; redecodeWithWhiteBalance(); }
                });
            } catch (Throwable t) {
                wbDecoding = false;
                runOnUiThread(() -> {
                    if (session == null) session = current;
                    showErrorDialog("白平衡重解失败", describe(t));
                });
            }
        }).start();
    }

    /** Five channels x eight slots; x < 0 means "no point here". */
    private void pushViewCurves() {
        final double[] packed = new double[80];
        for (int ch = 0; ch < 5; ++ch) {
            for (int i = 0; i < 8; ++i) {
                final int at = (ch * 8 + i) * 2;
                final java.util.List<float[]> pts = curvePoints[ch];
                if (i < pts.size()) {
                    packed[at] = pts.get(i)[0];
                    packed[at + 1] = pts.get(i)[1];
                } else {
                    packed[at] = -1.0;
                    packed[at + 1] = 0.0;
                }
            }
        }
        try { SpektraLabEngine.setViewCurves(packed); } catch (Throwable ignored) { }
    }

    /** Fritsch-Carlson, for drawing the curve the engine will actually use. */
    private static double curveAt(java.util.List<float[]> pts, double x) {
        final int n = pts.size();
        if (n < 2) return x;
        if (x <= pts.get(0)[0]) return pts.get(0)[1];
        if (x >= pts.get(n - 1)[0]) return pts.get(n - 1)[1];
        final int segs = n - 1;
        final double[] d = new double[segs], m = new double[n];
        for (int i = 0; i < segs; ++i)
            d[i] = (pts.get(i + 1)[1] - pts.get(i)[1]) / Math.max(pts.get(i + 1)[0] - pts.get(i)[0], 1e-6);
        m[0] = d[0];
        m[segs] = d[segs - 1];
        for (int i = 1; i < segs; ++i)
            m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2;
        for (int i = 0; i < segs; ++i) {
            if (d[i] == 0) continue;
            final double a = m[i] / d[i], b = m[i + 1] / d[i], s2 = a * a + b * b;
            if (s2 > 9) { final double t = 3 / Math.sqrt(s2); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]; }
        }
        int i = 0;
        while (i < n - 2 && x > pts.get(i + 1)[0]) ++i;
        final double h = Math.max(pts.get(i + 1)[0] - pts.get(i)[0], 1e-6), t = (x - pts.get(i)[0]) / h;
        final double t2 = t * t, t3 = t2 * t;
        final double v = (2 * t3 - 3 * t2 + 1) * pts.get(i)[1] + (t3 - 2 * t2 + t) * h * m[i]
                + (-2 * t3 + 3 * t2) * pts.get(i + 1)[1] + (t3 - t2) * h * m[i + 1];
        return Math.max(0, Math.min(1, v));
    }

    /** A square you draw the curve in: drag a point, tap to add, hold to remove. */
    private final class CurveView extends View {
        private final int padPx = dp(10);
        private int dragging = -1;

        CurveView() { super(MainActivity.this); }

        private android.graphics.PointF toScreen(float x, float y) {
            final float w = getWidth() - 2f * padPx, h = getHeight() - 2f * padPx;
            return new android.graphics.PointF(padPx + x * w, padPx + (1f - y) * h);
        }

        private float[] fromScreen(float px, float py) {
            final float w = Math.max(1, getWidth() - 2f * padPx), h = Math.max(1, getHeight() - 2f * padPx);
            return new float[]{Math.max(0, Math.min(1, (px - padPx) / w)),
                               Math.max(0, Math.min(1, 1f - (py - padPx) / h))};
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            final android.graphics.Paint grid = new android.graphics.Paint();
            grid.setColor(UI_LINE);
            grid.setStrokeWidth(1f);
            grid.setStyle(android.graphics.Paint.Style.STROKE);
            for (int i = 1; i < 4; ++i) {
                final android.graphics.PointF a = toScreen(i / 4f, 0f), b = toScreen(i / 4f, 1f);
                canvas.drawLine(a.x, a.y, b.x, b.y, grid);
                final android.graphics.PointF c = toScreen(0f, i / 4f), d = toScreen(1f, i / 4f);
                canvas.drawLine(c.x, c.y, d.x, d.y, grid);
            }
            final java.util.List<float[]> pts = curvePoints[curveChannel];
            final android.graphics.Paint line = new android.graphics.Paint();
            line.setColor(UI_BLUE);
            line.setStrokeWidth(dp(2));
            line.setStyle(android.graphics.Paint.Style.STROKE);
            final android.graphics.Path path = new android.graphics.Path();
            for (int i = 0; i <= 64; ++i) {
                final float x = i / 64f;
                final android.graphics.PointF p = toScreen(x, (float) curveAt(pts, x));
                if (i == 0) path.moveTo(p.x, p.y); else path.lineTo(p.x, p.y);
            }
            canvas.drawPath(path, line);
            final android.graphics.Paint dot = new android.graphics.Paint();
            dot.setColor(UI_INK);
            for (float[] q : pts) {
                final android.graphics.PointF p = toScreen(q[0], q[1]);
                canvas.drawRect(p.x - dp(4), p.y - dp(4), p.x + dp(4), p.y + dp(4), dot);
            }
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            final java.util.List<float[]> pts = curvePoints[curveChannel];
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    dragging = -1;
                    float best = dp(26);
                    for (int i = 0; i < pts.size(); ++i) {
                        final android.graphics.PointF p = toScreen(pts.get(i)[0], pts.get(i)[1]);
                        final float d = (float) Math.hypot(p.x - e.getX(), p.y - e.getY());
                        if (d < best) { best = d; dragging = i; }
                    }
                    if (dragging < 0 && pts.size() < 8) {
                        final float[] q = fromScreen(e.getX(), e.getY());
                        int at = 0;
                        while (at < pts.size() && pts.get(at)[0] < q[0]) ++at;
                        pts.add(at, q);
                        dragging = at;
                        invalidate();
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (dragging < 0 || dragging >= pts.size()) return true;
                    final float[] q = fromScreen(e.getX(), e.getY());
                    final float lo = dragging > 0 ? pts.get(dragging - 1)[0] + 0.005f : 0f;
                    final float hi = dragging < pts.size() - 1 ? pts.get(dragging + 1)[0] - 0.005f : 1f;
                    pts.get(dragging)[0] = Math.max(lo, Math.min(hi, q[0]));
                    pts.get(dragging)[1] = q[1];
                    invalidate();
                    // `advance`-free: the curve is already in `pts`, and
                    // `applyParams` pushes it from there.
                    requestLivePreview();
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    if (dragging >= 0) {
                        pushViewCurves();
                        if (uiReady && session != null) {
                            showingOriginal = false;
                            setOriginalVisual(false);
                            renderImage();
                        }
                    }
                    dragging = -1;
                    return true;
                }
                default:
                    return super.onTouchEvent(e);
            }
        }
    }

    private LinearLayout buildOpticsPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(sectionHead("PRINT / 胶片冲印参数", this::resetOpticsPanel),
                new LinearLayout.LayoutParams(-1, dp(38)));

        // **配光.** `spk_solve` is the enlarger's own analysis: it measures the
        // frame and returns the exposure and the three dichroic filters that
        // print it neutral. The import already runs it once (that is where the
        // source view's EV comes from), but until now there was no way to ask
        // again -- change the film, change the light, and the old numbers just
        // stayed. This is also the only place the c/m/y *neutral* values are
        // visible at all; the engine derives them from
        // `neutral_print_filters.json` per stock and illuminant.
        addChoice(panel, "配光（测光 + 三色滤镜）", "点击重新配光", tv -> resolvePrinting());

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
        knobCost = label("", 7.5f, UI_MUTED, tfHan);
        knobCost.setGravity(android.view.Gravity.END);
        panel.addView(knobCost, new LinearLayout.LayoutParams(-1, dp(14)));

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
                if (knobBar instanceof ScrubBar && ((ScrubBar) knobBar).isDragging()) {
                    requestLivePreview();
                } else {
                    liveLog("knob progress without drag");
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                // One delta per gesture, sent from the render thread under the
                // engine lock -- never from here (see `adjustRow`).
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
        // **A switch, not a 0..1 knob.** `edr_enabled` is a boolean at both ends
        // of the wire: the knob could only mean "on" by being past half, so the
        // same field had two controls and the slider's position silently decided
        // the checkbox. It also read as one more thing to tune rather than the
        // one bit it is.
        edrMark = new Square(this, UI_BLUE, uiToggleOff());
        panel.addView(toggleRow("扩展动态范围 (EDR)", edrMark,
                () -> edrOn > 0.5f,
                on -> {
                    edrOn = on ? 1f : 0f;
                    if (uiReady && session != null) {
                        showingOriginal = false;
                        setOriginalVisual(false);
                        renderImage();
                    }
                }), new LinearLayout.LayoutParams(-1, -2));

        // **A switch, not a 0..1 knob**, for the same reason as EDR above: the
        // field has two meaningful values and a slider would invent 98 others
        // that all render the same. On = the stock's own hues (the 1.6.7 look);
        // off = the green-window correction.
        hueOwnMark = new Square(this, UI_BLUE, uiToggleOff());
        panel.addView(toggleRow("保留胶片原色（不校色相）", hueOwnMark,
                () -> hueFixUnsupported(filmStock) || hueCorrection < 0.5f,
                on -> {
                    if (hueFixUnsupported(filmStock)) {
                        // Pinned on for this stock: say why instead of silently
                        // flipping back, or the switch reads as broken.
                        refreshHueOwnMark();
                        showActionSheet("这支胶片不支持色相校正",
                                "它的光谱反演标定还不准：实测绿色偏差 20 到 30 度，"
                                + "强行校正会比保留原色偏得更多。所以这支胶片的色相校正是固定的。"
                                + "换一支胶片就能改。",
                                new String[]{"知道了"}, which -> { });
                        return;
                    }
                    hueCorrection = on ? 0f : 1f;
                    if (uiReady && session != null) {
                        showingOriginal = false;
                        setOriginalVisual(false);
                        renderImage();
                    }
                }), new LinearLayout.LayoutParams(-1, -2));

        knob("放大曝光 越右越暗", "E",  0.25f, 4.0f,   v -> printExposure = v,   () -> printExposure);
        knob("高光保护",   "H",  0.0f,  3.0f,   v -> highlightRoom = v,   () -> highlightRoom, true);
        knob("暗部保护",   "S",  0.0f,  3.0f,   v -> shadowRoom = v,      () -> shadowRoom, true);
        knob("颗粒",       "G",  0.0f,  2.0f,   v -> grainAmount = v,     () -> grainAmount, true);
        knob("光晕",       "○",  0.0f,  4.0f,   v -> halationAmount = v,  () -> halationAmount, true);
        knob("光晕增强",   "+",  0.0f,  8.0f,   v -> halationBoost = v,   () -> halationBoost, true);
        knob("乳剂散射",   "~",  0.0f,  1.0f,   v -> halationScatter = v, () -> halationScatter, true);
        knob("散射半径",   "◍",  0.0f,  1.0f,   v -> scatterRadius = v,  () -> scatterRadius, true);
        knob("眩光",       "✳",  0.0f,  12.0f,  v -> glareAmount = v,     () -> glareAmount);
        knob("镜头模糊",   "◌",  0.0f,  200.0f, v -> lensBlur = v,        () -> lensBlur, true);
        knob("黄滤镜",     "Y",  -1.0f, 1.0f,   v -> yFilter = v,         () -> yFilter);
        knob("品红滤镜",   "M",  -1.0f, 1.0f,   v -> mFilter = v,         () -> mFilter);
        knob("滤镜强度",   "C",  1.0f,  60.0f,  v -> filterScale = v,     () -> filterScale);
        panel.addView(label("光晕 1.0 = 胶片本身的量，往右才是加强：高光先挑出来再加光晕，那圈红光是真发光，"
                + "不是把画面整体糊亮。\n散射半径 0 = 胶片本身的扩散（不到一个像素，看不出）；拉起来才有雾感。",
                7.5f, UI_MUTED, tfHan),
                new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT));
        knob("预闪",       "⚡", 0.0f,  1.0f,   v -> preflash = v,        () -> preflash);
        knob("曲线柔度",   "∿",  1.0f,  4.0f,   v -> shadowRolloff = v,   () -> shadowRolloff, true);
        knob("提升上限",   "L",  0.25f, 12.0f,  v -> maxLift = v,         () -> maxLift, true);
        // The switches, on a 0/1 slider: the panel has one slider by design,
        // and a second kind of control would be a second thing to explain.
        knob("光晕开关",   "◐",  0.0f,  1.0f,   v -> halationOn = v,       () -> halationOn, true);
        knob("去抗光晕",   "⊘",  0.0f,  1.0f,   v -> antihalationRemoved = v, () -> antihalationRemoved, true);
        knob("颗粒开关",   "g",  0.0f,  1.0f,   v -> grainOn = v,          () -> grainOn, true);
        knob("层间抑制",   "⧉",  0.0f,  1.0f,   v -> couplersOn = v,       () -> couplersOn, true);
        knob("扫描白",     "W",  0.0f,  1.0f,   v -> scannerWhite = v,     () -> scannerWhite);
        knob("扫描黑",     "B",  0.0f,  1.0f,   v -> scannerBlack = v,     () -> scannerBlack);
        // Six more that the schema has had all along and the panel never
        // showed. Booleans ride a 0..1 knob because the knob is the panel's
        // one control; the readout says "0.00" / "1.00" and the hint says
        // which end is which.
        knob("眩光开关",   "☀",  0.0f,  1.0f,   v -> glareActive = v,      () -> glareActive);
        knob("扫描仪模糊", "S",  0.0f, 20.0f,   v -> scannerLensBlur = v,  () -> scannerLensBlur);
        knob("光晕余量",   "R",  0.0f,  8.0f,   v -> halationHeadroom = v, () -> halationHeadroom, true);
        knob("浓度曲线",   "γ",  0.2f,  4.0f,   v -> densityGamma = v,     () -> densityGamma, true);
        knob("数字中间片", "I",  0.0f,  1.0f,   v -> digitalIntermediate = v > 0.5f,
                                                                    () -> digitalIntermediate ? 1f : 0f);
        knob("输出编码",   "E",  0.0f,  1.0f,   v -> outputCctf = v > 0.5f,
                                                                    () -> outputCctf ? 1f : 0f);


        // Two of the optics are choices, not numbers.
        addChoice(panel, "镜头滤镜", labelOf(FILTER_LABELS, FILTER_IDS, cameraFilter), tv ->
                showChoiceMapping("镜头滤镜（Wratten）", FILTER_LABELS, FILTER_IDS, cameraFilter, id -> {
                    cameraFilter = id;
                    tv.setText(labelOf(FILTER_LABELS, FILTER_IDS, id));
                    applyParams();
                    if (uiReady && session != null) { showingOriginal = false; setOriginalVisual(false); renderImage(); }
                }));
        // **What the canvas is drawn in.** Not a look parameter: it never
        // reaches a file, and two exports of the same frame in different
        // canvas spaces are the same bytes.
        addChoice(panel, "预览色域", previewSpace, tv -> {
            final java.util.List<String> labels = java.util.Arrays.asList(SPACES);
            showChoiceSheet("预览色域（只影响屏幕；导出始终是 sRGB）", labels, previewSpace, picked -> {
                final int index = labels.indexOf(picked);
                if (index >= 0) previewSpace = SPACES[index];
                tv.setText(previewSpace);
                if (uiReady && session != null) { applyParams(); renderImage(); }
            });
        });

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
        knob(label, glyph, lo, hi, set, get, false);
    }

    /** `heavy` marks a shoot-layer field: dragging it re-develops the film. */
    private void knob(String label, String glyph, float lo, float hi,
                      java.util.function.Consumer<Float> set, java.util.function.Supplier<Float> get,
                      boolean heavy) {
        final int index = knobs.size();
        knobs.add(new Knob(label, glyph, lo, hi, set, get, heavy));

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
        if (knobCost != null) {
            knobCost.setText(k.heavy ? "这一项要重算胶片 · 约 0.1 秒一帧"
                                     : "只重印 · 约 0.03 秒一帧");
            knobCost.setTextColor(k.heavy ? 0xFFC8741E : UI_MUTED);
        }
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
    /**
     * The space the canvas is drawn in.
     *
     * <p>**Two spaces, not four, and the short list is the honest one.** The
     * engine renders into ProPhoto and converts on the way out, and it knows
     * ProPhoto, Adobe RGB, Display P3 and sRGB. The screen is the other half:
     * `Bitmap#setColorSpace` takes a platform `ColorSpace`, and Android only
     * names two of them (`ColorSpace.Named`). Adobe RGB and ProPhoto would each
     * need an ICC profile built by hand before the system would colour-manage
     * them, and an **untagged** wide-gamut buffer is displayed as if it were
     * sRGB -- which desaturates it by exactly the amount the wider gamut was
     * supposed to add. Two choices that work beat four where two lie.
     *
     * <p>What this changes is the screen, not a file: exports stay sRGB,
     * because a P3 JPEG would need a P3 ICC the writer does not carry yet.
     */
    private static final String[] SPACES = {"sRGB", "Display P3"};
    private String previewSpace = "sRGB";

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
            case "乳剂散射": return "光在乳剂内部散射形成的雾。实测几乎无变化（<0.1%）";
            case "眩光": return "相纸表面的那层光晕。抬高黑位、降低对比";
            case "镜头模糊": return "镜头本身的光学模糊，单位微米。在胶片之前";
            case "黄滤镜": return "放大机的黄旋钮。往负走偏黄，往正走偏蓝（冷）";
            case "品红滤镜": return "放大机的品红旋钮。往负走偏绿，往正走偏品红";
            case "滤镜强度": return "上面两个旋钮的 CC 刻度。太小了看不出效果";
            case "预闪": return "印相前先给相纸一次均匀曝光，压一点暗部对比。满值只走引擎上限的 4%";
            case "曲线柔度": return "高光/暗部保护曲线的圆滑程度。越大越缓";
            case "提升上限": return "暗部最多能被提亮多少挡。实测 4→12 几乎无差别";
            case "光晕开关": return "关掉就是一支完全没有光晕的胶片";
            case "去抗光晕": return "把胶片当成撕掉抗光晕层。实测画面变化 <0.2%";
            case "颗粒开关": return "关掉就是完全没有颗粒的画面";
            case "层间抑制": return "染料层之间的相互抑制。往左颜色变淡，往右变浓";
            case "眩光开关":   return "0 关掉相纸表面的那层光晕，1 保留";
            case "扫描仪模糊": return "扫描仪镜头自己的模糊，单位微米。在整条管线最后";
            case "光晕余量":   return "给光晕留的高光余量，单位挡。越大越不容易糊成一片";
            case "浓度曲线":   return "胶片密度曲线的伽马。0.6 发灰发亮，1.35 偏红，默认 1";
            case "数字中间片": return "跳过光学印相，走数码中间片那条路。0 关，1 开";
            case "输出编码":   return "输出的传递函数。0 是相纸的，1 是显示器编码";
            case "胶片原色":   return "往右保留胶片本来的偏色，往左把色相校回原图（绿色更准）";
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


    // ──────────────────────────────────────────────────────────────────
    // 构图: crop and rotate, out of the 尺度 page and into their own.
    // ──────────────────────────────────────────────────────────────────

    /**
     * The 构图 page.
     *
     * <p>Crop and rotate used to be three dropdowns at the bottom of 尺度,
     * under a row of film-format cards, and the page read as "physical size,
     * and also these other things". They are not a size -- they are what the
     * frame *is*, and they change the picture's shape, not its scale. So they
     * get their own page, laid out the way a picture editor does it: ratios as
     * shapes you can see, rotation as a dial you can feel.
     */
    private LinearLayout buildComposePanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(sectionHead("COMPOSITION / 构图", this::resetComposePanel),
                new LinearLayout.LayoutParams(-1, dp(38)));

        LinearLayout sub = new LinearLayout(this);
        sub.setOrientation(LinearLayout.HORIZONTAL);
        composeCropTab = composeTabView("裁切", 0);
        composeRotateTab = composeTabView("旋转", 1);
        sub.addView(composeCropTab, new LinearLayout.LayoutParams(0, dp(32), 1f));
        sub.addView(composeRotateTab, new LinearLayout.LayoutParams(0, dp(32), 1f));
        panel.addView(sub, new LinearLayout.LayoutParams(-1, dp(32)));

        composeBody = new LinearLayout(this);
        composeBody.setOrientation(LinearLayout.VERTICAL);
        panel.addView(composeBody, new LinearLayout.LayoutParams(-1, -2));
        showComposePage(0);      // the page is built hidden; it must still have content
        return panel;
    }

    private TextView composeTabView(String text, int page) {
        final TextView tv = label(text, 12, UI_MUTED, tfHan);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setOnClickListener(v -> showComposePage(page));
        return tv;
    }

    private static void underline(TextView tv, boolean on) {
        final int flags = tv.getPaintFlags();
        tv.setPaintFlags(on ? (flags | android.graphics.Paint.UNDERLINE_TEXT_FLAG)
                : (flags & ~android.graphics.Paint.UNDERLINE_TEXT_FLAG));
    }

    private void showComposePage(int page) {
        composeSubPage = page;
        if (composeBody == null) return;
        composeCropTab.setTextColor(page == 0 ? UI_INK : UI_MUTED);
        composeRotateTab.setTextColor(page == 1 ? UI_INK : UI_MUTED);
        underline(composeCropTab, page == 0);
        underline(composeRotateTab, page == 1);
        if (cropScroll != null) cropScrollX = cropScroll.getScrollX();
        composeBody.removeAllViews();
        if (page == 0) buildCropPage(); else buildRotatePage();
        if (cropOverlay != null) {
            // **Only while 构图 → 裁切 is the page in front.** The page is
            // built during `onCreate`, long before `setTab` runs, so keying on
            // `page == 0` alone left the frame drawn over every other page --
            // which is exactly what "裁切框一直在画布上" was.
            final boolean show = activeTabIndex == 4 && page == 0;
            cropOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                cropOverlay.sync(pendingCropX, pendingCropY, pendingCropW, pendingCropH,
                        cropAspectForSelection());
            }
        }
    }

    /** The frame's own aspect, which is what "原始" means. */
    private float originalAspect() {
        final int w = importedWidth > 0 ? importedWidth : sourceWidth;
        final int h = importedHeight > 0 ? importedHeight : sourceHeight;
        return (w > 0 && h > 0) ? (float) w / h : 1.5f;
    }

    private void buildCropPage() {
        final HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        final LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        // **Room for the border on the bottom edge.** The cards are 94 dp and
        // the strip used to be 94 dp tall with a 10 dp top pad, so the bottom
        // of every card -- and with it the selected card's outline -- was
        // clipped away. The strip is as tall as its children plus both pads.
        strip.setPadding(0, dp(8), 0, dp(6));

        for (int i = -1; i < EDGE_LABELS.length; ++i) {
            final int index = i;
            final boolean original = i < 0;
            final boolean active = cropRatioIndex == index;
            final float ratio = original ? originalAspect() : (float) EDGE_RATIOS[i];

            final LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            card.setBackground(cardBorder(active));
            card.setPadding(dp(3), dp(6), dp(3), dp(4));

            card.addView(new RatioMark(this, ratio, active ? UI_BLUE : UI_INK),
                    new LinearLayout.LayoutParams(-1, dp(44)));

            final TextView caption = label(original ? "原始" : EDGE_RATIO_LABELS[i],
                    8.5f, active ? UI_INK : UI_MUTED, tfBody);
            caption.setGravity(android.view.Gravity.CENTER);
            card.addView(caption, new LinearLayout.LayoutParams(-1, -2));

            final TextView gate = label(original ? "整幅" : EDGE_LABELS[i], 6.5f, UI_MUTED, tfHan);
            gate.setGravity(android.view.Gravity.CENTER);
            card.addView(gate, new LinearLayout.LayoutParams(-1, -2));

            card.setOnClickListener(v -> applyCropRatio(index));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(76), dp(94));
            lp.rightMargin = dp(6);
            strip.addView(card, lp);
        }
        for (int i = 0; i < PORTRAIT_RATIOS.length; ++i) {
            final int index = EDGE_LABELS.length + i;
            final boolean active = cropRatioIndex == index;
            final float ratio = PORTRAIT_RATIOS[i];

            final LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            card.setBackground(cardBorder(active));
            card.setPadding(dp(3), dp(6), dp(3), dp(4));
            card.addView(new RatioMark(this, ratio, active ? UI_BLUE : UI_INK),
                    new LinearLayout.LayoutParams(-1, dp(44)));
            final TextView caption = label(PORTRAIT_LABELS[i], 8.5f,
                    active ? UI_INK : UI_MUTED, tfBody);
            caption.setGravity(android.view.Gravity.CENTER);
            card.addView(caption, new LinearLayout.LayoutParams(-1, -2));
            final TextView gate = label("竖幅", 6.5f, UI_MUTED, tfHan);
            gate.setGravity(android.view.Gravity.CENTER);
            card.addView(gate, new LinearLayout.LayoutParams(-1, -2));

            card.setOnClickListener(v -> applyCropRatio(index));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(76), dp(94));
            lp.rightMargin = dp(6);
            strip.addView(card, lp);
        }
        scroll.addView(strip, new android.widget.FrameLayout.LayoutParams(-2, dp(108)));
        cropScroll = scroll;
        composeBody.addView(scroll, new LinearLayout.LayoutParams(-1, dp(108)));
        // **Restore the scroll after layout, not before it.** `post` can run
        // while the new strip is still unmeasured; `scrollTo` then clamps
        // against a zero content width and the strip lands back on the first
        // card. Picking one of the portrait ratios at the end of the strip
        // therefore snapped to the start on every tap -- the "抽搐" on the last
        // few cards. `OnPreDraw` runs after measure/layout and before the
        // frame is drawn, which is the first moment `scrollTo` means anything.
        scroll.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                        scroll.scrollTo(cropScrollX, 0);
                        Probe.log("crop", "scroll " + cropScrollX + " -> " + scroll.getScrollX());
                        return true;
                    }
                });

        final LinearLayout footRow = new LinearLayout(this);
        footRow.setOrientation(LinearLayout.HORIZONTAL);
        footRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final TextView foot = label(cropSummary(), 8.5f, UI_MUTED, tfHan);
        footRow.addView(foot, new LinearLayout.LayoutParams(0, -2, 1f));

        // **Nothing happens to the picture until 应用.** 取消 throws the
        // proposal away; the frame goes back to the whole canvas.
        final TextView cancel = label("取消", 9, UI_MUTED, tfHan);
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setBackground(cardBorder(false));
        cancel.setOnClickListener(v -> {
            pendingCropX = 0f; pendingCropY = 0f;
            pendingCropW = 1f; pendingCropH = 1f;
            cropRatioIndex = -1;
            showComposePage(0);
        });
        final LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(dp(58), dp(30));
        cancelLp.rightMargin = dp(6);
        footRow.addView(cancel, cancelLp);

        final TextView apply = label("应用裁切", 9, UI_BLUE, tfHan);
        apply.setGravity(android.view.Gravity.CENTER);
        apply.setBackground(cardBorder(true));
        apply.setOnClickListener(v -> commitPendingCrop());
        footRow.addView(apply, new LinearLayout.LayoutParams(dp(84), dp(30)));

        composeBody.addView(footRow, new LinearLayout.LayoutParams(-1, dp(38)));
    }

    private android.graphics.drawable.GradientDrawable comparePillBg(boolean on) {
        final android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        // Nearly opaque white in both themes: it floats over the photograph,
        // and a translucent disc picked up whatever was behind it.
        bg.setColor(on ? 0xFFFFFFFF : 0xF2FFFFFF);
        bg.setStroke(dp(on ? 2 : 1), on ? UI_BLUE : UI_LINE);
        return bg;
    }

    /** Shown only when there is something to compare against. */
    private void refreshComparePill() {
        if (comparePill == null) return;
        final boolean can = compare != null && lastRenderedBitmap != null;
        comparePill.setVisibility(can ? View.VISIBLE : View.GONE);
        if (!can && compare != null && compare.isSplitMode()) compare.setSplitMode(false);
        final boolean on = compare != null && compare.isSplitMode();
        comparePill.setTextColor(on ? UI_BLUE : UI_ON_WHITE);
        comparePill.setBackground(comparePillBg(on));
    }

    /** The aspect of what the canvas is showing: the file, after any crop
     *  already applied. The pending frame is measured in that space. */
    private float currentAspect() {
        return originalAspect() * (geometryCropW / Math.max(1e-4f, geometryCropH));
    }

    /** The picture-pixel ratio the drag is held to; 0 means free. */
    private float cropAspectForSelection() {
        if (cropRatioIndex >= EDGE_RATIOS.length
                && cropRatioIndex < EDGE_RATIOS.length + PORTRAIT_RATIOS.length) {
            return PORTRAIT_RATIOS[cropRatioIndex - EDGE_RATIOS.length];
        }
        return cropRatioIndex >= 0 && cropRatioIndex < EDGE_RATIOS.length
                ? (float) EDGE_RATIOS[cropRatioIndex] : 0f;
    }

    private String cropSummary() {
        if (pendingCropW >= 0.999f && pendingCropH >= 0.999f
                && pendingCropX <= 0.001f && pendingCropY <= 0.001f) return "整幅 · 未裁切";
        return String.format(java.util.Locale.US, "保留 %.0f%% × %.0f%%（按对角缩放）",
                pendingCropW * 100, pendingCropH * 100);
    }

    /**
     * Cut the frame to a gate's ratio, centred.
     *
     * <p>The engine takes a normalised window, so the job here is only to find
     * the largest window of the wanted shape inside the picture: wider than the
     * frame means full width and a shorter window; narrower means the reverse.
     * There is no free-drag box yet -- the four numbers can carry one, but the
     * gesture does not exist, and a card that promised one would be lying.
     */
    private void applyCropRatio(int index) {
        cropRatioIndex = index;
        if (index < 0) {
            pendingCropW = 1f; pendingCropH = 1f;
            pendingCropX = 0f; pendingCropY = 0f;
        } else {
            // Portrait cards are appended after the gates, so an index past the
            // gate table is a portrait ratio rather than a film format.
            final float want = index < EDGE_RATIOS.length
                    ? (float) EDGE_RATIOS[index]
                    : PORTRAIT_RATIOS[index - EDGE_RATIOS.length];
            final float have = currentAspect();
            if (want >= have) { pendingCropW = 1f; pendingCropH = have / want; }
            else { pendingCropW = want / have; pendingCropH = 1f; }
            pendingCropX = (1f - pendingCropW) * 0.5f;
            pendingCropY = (1f - pendingCropH) * 0.5f;
        }
        Probe.log("crop", "ratio=" + index + " W=" + pendingCropW + " H=" + pendingCropH
                + " X=" + pendingCropX + " Y=" + pendingCropY);
        showComposePage(0);
    }

    /**
     * Fold the pending frame into the engine's window and render.
     *
     * <p>Composed, not replaced: the pending frame is measured against what
     * the canvas currently shows, which may itself already be a crop. Two
     * nested windows are one window in the original file's coordinates.
     */
    private void commitPendingCrop() {
        if (pendingCropW >= 0.999f && pendingCropH >= 0.999f
                && pendingCropX <= 0.001f && pendingCropY <= 0.001f) {
            showInAppToast("还没有裁切");
            return;
        }
        final float nw = geometryCropW * pendingCropW;
        final float nh = geometryCropH * pendingCropH;
        final float nx = geometryCropX + pendingCropX * geometryCropW;
        final float ny = geometryCropY + pendingCropY * geometryCropH;
        geometryCropW = Math.min(1f, nw);
        geometryCropH = Math.min(1f, nh);
        geometryCropX = Math.max(0f, Math.min(1f - geometryCropW, nx));
        geometryCropY = Math.max(0f, Math.min(1f - geometryCropH, ny));
        pendingCropX = 0f; pendingCropY = 0f;
        pendingCropW = 1f; pendingCropH = 1f;
        // **The card *is* the gate.** Each crop card is labelled with a gate
        // ("4:3 135 半格") and the rebate prints the gate's model, so when the
        // two disagree the engine refuses the render -- "a 1365 x 1012 frame is
        // not the 135 gate's shape" -- after the user has done everything right.
        // Carrying the card's gate across on apply keeps the shape under the
        // card and the shape printed on the film the same shape.
        // **Not gated on `overscanOn`.** The usual order is crop first, then
        // turn the rebate on -- and at that moment the gate had never been told
        // which card the crop came from, so it kept 135 for a 4:3 picture and
        // the render failed. Carrying the card's gate across whether or not the
        // rebate is currently on costs nothing and covers both orders.
        if (cropRatioIndex >= 0 && cropRatioIndex < EDGE_IDS.length + PORTRAIT_GATES.length) {
            // Landscape cards carry their own gate; portrait cards carry the
            // gate they are the portrait of. Either way the rebate and the
            // window end up the same shape, which is the only combination the
            // engine can print.
            final String want = cropRatioIndex < EDGE_IDS.length
                    ? EDGE_IDS[cropRatioIndex]
                    : PORTRAIT_GATES[cropRatioIndex - EDGE_IDS.length];
            if (!want.equals(overscanFormat)) {
                overscanFormat = want;
                final double mm = gateMm(want);
                if (mm > 0) filmFormatMm = (float) mm;
            }
        }
        cropRatioIndex = -1;
        commitCompose();
        showComposePage(0);
    }

    private void buildRotatePage() {
        final TextView readout = label(String.format(java.util.Locale.US, "%.1f°", geometryRotation),
                16, UI_INK, tfBody);
        readout.setGravity(android.view.Gravity.CENTER);
        composeBody.addView(readout, new LinearLayout.LayoutParams(-1, dp(32)));

        final ScrubBar bar = new ScrubBar(this);
        bar.setMax(900);
        bar.setProgress(Math.round((geometryRotation + 45f) * 10f));
        bar.setPadding(dp(5), dp(11), dp(5), dp(11));
        bar.setResetTarget(450, () -> {
            geometryRotation = 0f;
            bar.setProgress(450);
            readout.setText("0.0°");
            commitCompose();
        });
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int value, boolean fromUser) {
                geometryRotation = (value - 450) / 10f;
                readout.setText(String.format(java.util.Locale.US, "%.1f°", geometryRotation));
                if (bar.isDragging()) requestLivePreview();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) { commitCompose(); }
        });
        composeBody.addView(bar, new LinearLayout.LayoutParams(-1, dp(32)));

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);

        final LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1f);
        row.addView(circleAction("↺", "向左 90°", () -> turnBy(-1)), cell);
        row.addView(circleAction("↻", "向右 90°", () -> turnBy(1)),
                new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(circleAction("⇆", "水平翻转", () -> {
            geometryFlipH = !geometryFlipH;
            showComposePage(1);
            commitCompose();
        }), new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(circleAction("⇅", "垂直翻转", () -> {
            geometryFlipV = !geometryFlipV;
            showComposePage(1);
            commitCompose();
        }), new LinearLayout.LayoutParams(0, -2, 1f));
        composeBody.addView(row, new LinearLayout.LayoutParams(-1, -2));

        final TextView foot = label("角度 ±45°；双击滑杆上的圆点归零。翻转和 90° 是整幅换向，画面不变形。",
                8f, UI_MUTED, tfHan);
        foot.setPadding(0, dp(4), 0, 0);
        composeBody.addView(foot, new LinearLayout.LayoutParams(-1, dp(30)));
    }

    private View circleAction(String glyph, String caption, Runnable tap) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        final TextView circle = label(glyph, 15, UI_INK, tfBody);
        circle.setGravity(android.view.Gravity.CENTER);
        final android.graphics.drawable.GradientDrawable ring =
                new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setColor(0x00000000);
        ring.setStroke(dp(1), UI_LINE);
        circle.setBackground(ring);
        circle.setOnClickListener(v -> tap.run());
        box.addView(circle, new LinearLayout.LayoutParams(dp(46), dp(46)));

        final TextView name = label(caption, 8, UI_MUTED, tfHan);
        name.setGravity(android.view.Gravity.CENTER);
        name.setOnClickListener(v -> tap.run());
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private void turnBy(int direction) {
        geometryTurns = ((geometryTurns + direction) % 4 + 4) % 4;
        commitCompose();
    }

    /** One write, one render: every control on this page ends here. */
    private void commitCompose() {
        applyParams();
        if (uiReady && session != null) {
            showingOriginal = false;
            setOriginalVisual(false);
            renderImage();
        }
    }

    /**
     * Reset **the sub-page you are looking at**, not the whole page.
     *
     * <p>One 重置 at the top of 构图 used to wipe the crop and the rotation
     * together, so nudging the angle back to zero also threw away a crop the
     * user had just framed. Each sub-page owns its own numbers now.
     */
    private void resetComposePanel() {
        if (composeSubPage == 0) {
            geometryCropW = 1f; geometryCropH = 1f;
            geometryCropX = 0f; geometryCropY = 0f;
            pendingCropX = 0f; pendingCropY = 0f;
            pendingCropW = 1f; pendingCropH = 1f;
            cropRatioIndex = -1;
        } else {
            geometryTurns = 0;
            geometryRotation = 0f;
            geometryFlipH = false;
            geometryFlipV = false;
        }
        showComposePage(composeSubPage);
        commitCompose();
    }

    /**
     * The crop frame on the picture, dragged by hand.
     *
     * <p>The ratio cards are a starting point; this is the rest of it. Once a
     * card has fixed a shape, the corners keep that shape while they scale
     * (the opposite corner is the anchor, the pointer picks the size, and the
     * missing dimension follows from the ratio). With 原始 or a hand-dragged
     * frame the ratio is free and the corners move independently.
     *
     * <p>Everything is normalised to the picture, so it holds at any canvas
     * size -- the same numbers `geometry_crop_*` takes.
     */
    private final class CropOverlay extends View {
        private final android.graphics.RectF image = new android.graphics.RectF();
        private float nx = 0f, ny = 0f, nw = 1f, nh = 1f;
        private float aspect = 0f;                 // picture w/h; 0 = free
        private int drag = 0;                      // 0 none, 1 move, 2 TL, 3 TR, 4 BL, 5 BR
        private float downX, downY, sx, sy, sw, sh;
        /** True while the last drag actually held the card's ratio. */
        private boolean ratioHeld;

        CropOverlay() { super(MainActivity.this); }

        void sync(float x, float y, float w, float h, float a) {
            nx = x; ny = y; nw = w; nh = h; aspect = a;
            invalidate();
        }

        private void pullImage() {
            if (compare != null) image.set(compare.imageRect());
        }

        private float px(float n) { return image.left + n * image.width(); }
        private float py(float n) { return image.top + n * image.height(); }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            pullImage();
            if (image.isEmpty() || image.width() <= 0f) return;

            final float l = px(nx), t = py(ny), r = px(nx + nw), b = py(ny + nh);

            final android.graphics.Paint dim = new android.graphics.Paint();
            dim.setColor(0x99000000);
            canvas.drawRect(0, 0, getWidth(), t, dim);
            canvas.drawRect(0, b, getWidth(), getHeight(), dim);
            canvas.drawRect(0, t, l, b, dim);
            canvas.drawRect(r, t, getWidth(), b, dim);

            final android.graphics.Paint edge =
                    new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(android.graphics.Paint.Style.STROKE);
            edge.setStrokeWidth(dp(1.4f));
            edge.setColor(0xFFFFFFFF);
            canvas.drawRect(l, t, r, b, edge);

            final android.graphics.Paint thirds =
                    new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            thirds.setStyle(android.graphics.Paint.Style.STROKE);
            thirds.setStrokeWidth(dp(0.8f));
            thirds.setColor(0x66FFFFFF);
            for (int i = 1; i < 3; ++i) {
                final float x = l + (r - l) * i / 3f;
                final float y = t + (b - t) * i / 3f;
                canvas.drawLine(x, t, x, b, thirds);
                canvas.drawLine(l, y, r, y, thirds);
            }

            final android.graphics.Paint grip =
                    new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            grip.setStyle(android.graphics.Paint.Style.STROKE);
            grip.setStrokeWidth(dp(3f));
            grip.setColor(0xFFFFFFFF);
            final float arm = Math.min(dp(22), Math.min(r - l, b - t) * 0.28f);
            canvas.drawLine(l, t, l + arm, t, grip);
            canvas.drawLine(l, t, l, t + arm, grip);
            canvas.drawLine(r, t, r - arm, t, grip);
            canvas.drawLine(r, t, r, t + arm, grip);
            canvas.drawLine(l, b, l + arm, b, grip);
            canvas.drawLine(l, b, l, b - arm, grip);
            canvas.drawLine(r, b, r - arm, b, grip);
            canvas.drawLine(r, b, r, b - arm, grip);
        }

        private int cornerAt(float x, float y) {
            final float tolerance = dp(30);
            final float l = px(nx), t = py(ny), r = px(nx + nw), b = py(ny + nh);
            if (Math.hypot(x - l, y - t) < tolerance) return 2;
            if (Math.hypot(x - r, y - t) < tolerance) return 3;
            if (Math.hypot(x - l, y - b) < tolerance) return 4;
            if (Math.hypot(x - r, y - b) < tolerance) return 5;
            if (x > l && x < r && y > t && y < b) return 1;
            return 0;
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            pullImage();
            if (image.width() <= 0f) return false;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    final int hit = cornerAt(e.getX(), e.getY());
                    if (hit == 0) return false;
                    drag = hit;
                    downX = e.getX(); downY = e.getY();
                    sx = nx; sy = ny; sw = nw; sh = nh;
                    // A box that already has a card's shape starts out held;
                    // a free / "整幅" box starts out not.
                    ratioHeld = aspect > 0.0001f;
                    final android.view.ViewParent parent = getParent();
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                case android.view.MotionEvent.ACTION_POINTER_DOWN:
                    // **A second finger is a pinch, and returning false here
                    // would not hand it over** -- this view already owns the
                    // gesture, and Android does not re-dispatch to a sibling.
                    // So the pinch is forwarded explicitly.
                    drag = 0;
                    return compare != null && compare.onTouchEvent(e);
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (e.getPointerCount() > 1 && compare != null) return compare.onTouchEvent(e);
                    if (drag == 0) return false;
                    applyDrag(e.getX(), e.getY());
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    if (e.getPointerCount() > 1 && compare != null) return compare.onTouchEvent(e);
                    if (drag != 0) {
                        // **commit first, clear afterwards.** `commitCrop`
                        // needs to know whether this was a move or a corner
                        // drag to decide if the ratio card still owns the box.
                        commitCrop();
                    } else if (compare != null) {
                        compare.onTouchEvent(e);        // close a pinch cleanly
                    }
                    return true;
                }
            }
            return false;
        }

        private void applyDrag(float x, float y) {
            final float dxN = (x - downX) / Math.max(1f, image.width());
            final float dyN = (y - downY) / Math.max(1f, image.height());
            final float min = 0.04f;

            if (drag == 1) {
                nx = Math.max(0f, Math.min(1f - sw, sx + dxN));
                ny = Math.max(0f, Math.min(1f - sh, sy + dyN));
            } else {
                // The opposite corner is the anchor.
                final boolean left = (drag == 2 || drag == 4);
                final boolean up = (drag == 2 || drag == 3);
                final float ax = left ? sx + sw : sx;
                final float ay = up ? sy + sh : sy;
                float mx = left ? sx + dxN : sx + sw + dxN;
                float my = up ? sy + dyN : sy + sh + dyN;
                mx = Math.max(0f, Math.min(1f, mx));
                my = Math.max(0f, Math.min(1f, my));

                float w = Math.abs(mx - ax);
                float h = Math.abs(my - ay);
                if (aspect > 0.0001f && image.width() > 0f) {
                    // The ratio is in picture pixels; normalised space is
                    // stretched by the picture's own aspect.
                    final float aspN = aspect * (image.height() / image.width());
                    if (w / Math.max(1e-5f, h) > aspN) h = w / aspN; else w = h * aspN;
                    // **Shrink both sides together at the picture edge.**
                    // Clamping w and h independently here (what the old code
                    // did) changes the ratio the moment a drag reaches an
                    // edge -- which is how a fixed-ratio card silently turned
                    // into a custom box without any other card being tapped.
                    final float roomW = left ? ax : 1f - ax;
                    final float roomH = up ? ay : 1f - ay;
                    if (roomW > 1e-3f && roomH > 1e-3f) {
                        if (w > roomW && w > 1e-5f) { h *= roomW / w; w = roomW; }
                        if (h > roomH && h > 1e-5f) { w *= roomH / h; h = roomH; }
                        if (w < min || h < min) {
                            final float lift = Math.max(min / Math.max(1e-5f, w),
                                    min / Math.max(1e-5f, h));
                            if (lift > 1f) { w *= lift; h *= lift; }
                        }
                        ratioHeld = true;
                    } else {
                        // The anchor sits against the edge: there is no room
                        // to hold the shape, so this box is honestly custom.
                        w = Math.max(min, Math.min(1f, w));
                        h = Math.max(min, Math.min(1f, h));
                        ratioHeld = false;
                    }
                } else {
                    w = Math.max(min, Math.min(1f, w));
                    h = Math.max(min, Math.min(1f, h));
                    ratioHeld = false;
                }
                w = Math.max(1e-5f, Math.min(1f, w));
                h = Math.max(1e-5f, Math.min(1f, h));

                nx = Math.max(0f, Math.min(1f - w, left ? ax - w : ax));
                ny = Math.max(0f, Math.min(1f - h, up ? ay - h : ay));
                nw = w; nh = h;
            }
            invalidate();
        }

        private void commitCrop() {
            // **A proposal, not a render.** The picture only changes when 应用
            // is pressed; dragging keeps the frame and the photograph in the
            // same space, which is what makes them line up.
            pendingCropX = nx; pendingCropY = ny;
            pendingCropW = nw; pendingCropH = nh;
            keepRatioClaim();
            Probe.log("crop", "drag=" + drag + " held=" + ratioHeld
                    + " ratio=" + cropRatioIndex + " W=" + nw + " H=" + nh
                    + " aspect=" + aspect);
            drag = 0;
            showComposePage(0);
        }

        /**
         * Keep the card that owns this shape, if it still owns it.
         *
         * <p>A move only changes the origin, so the claim survives untouched.
         * A corner drag is held to the ratio by {@link #applyDrag}, so it
         * survives too. The one honest exception is a box that had to give up
         * its shape because it would have left the picture.
         *
         * <p>This used to be an unconditional {@code cropRatioIndex = -2} on
         * every touch-up, so a single slide inside a fixed ratio permanently
         * unlocked the ratio: the cards lost their selection, the summary
         * read "custom", and the next corner drag was free.
         */
        private void keepRatioClaim() {
            if (cropRatioIndex == -2) return;       // already custom
            if (cropRatioIndex == -1) {
                // "原始 / 整幅" is a claim as well: the whole frame.
                final boolean whole = nw >= 0.999f && nh >= 0.999f
                        && nx <= 0.001f && ny <= 0.001f;
                if (!whole) cropRatioIndex = -2;
                return;
            }
            if (drag == 1) return;                  // a move keeps the shape
            // **No geometry compare here on purpose.** `applyDrag` already
            // holds the shape to the card's ratio and sets `ratioHeld` only
            // when it could; comparing `nw/nh` to the ratio would re-derive
            // the same thing from the display rect, which lags the canvas by
            // a render after 应用裁切 and would knock the card off for no
            // visible reason.
            if (!ratioHeld) cropRatioIndex = -2;
        }
    }

    /** A crop ratio drawn at that shape: 3:2 is a wide box, 1:1 a square,
     *  6:17 a slash. The card's caption says which gate it came from. */
    private static final class RatioMark extends View {
        private final float aspect;
        private final int colour;
        private final float density;

        RatioMark(android.content.Context c, float aspect, int colour) {
            super(c);
            this.aspect = aspect > 0.01f ? aspect : 1f;
            this.colour = colour;
            this.density = c.getResources().getDisplayMetrics().density;
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            final float pad = density * 7f;
            final float boxW = Math.max(0f, getWidth() - pad * 2f);
            final float boxH = Math.max(0f, getHeight() - pad * 2f);
            float w = boxW;
            float h = boxW / aspect;
            if (h > boxH) { h = boxH; w = boxH * aspect; }
            final float cx = getWidth() / 2f, cy = getHeight() / 2f;
            final android.graphics.Paint paint =
                    new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(density * 1.1f);
            paint.setColor(colour);
            canvas.drawRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, paint);
        }
    }

    private LinearLayout buildFormatPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(sectionHead("FRAME & SCALE / 构图尺度", this::resetFormatPanel),
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
        foot.addView(label("物理尺幅 · 裁切与旋转在「构图」页", 8.5f, UI_MUTED, tfHan),
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
        overscanMark = new Square(this, UI_BLUE, uiToggleOff());
        overscanRow = toggleRow("开启片边", overscanMark, () -> overscanOn, on -> {
            if (on && edgeIds.isEmpty()) {
                overscanMark.setLit(false);
                showInAppToast("这张图的长宽比不对应任何片门");
                return;
            }
            overscanOn = on;
            refreshEdgeGroups();
            onEdgeChanged();
        });
        edgePage.addView(overscanRow, new LinearLayout.LayoutParams(-1, dp(52)));
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

        edgeTextMark = new Square(this, UI_BLUE, uiToggleOff());
        edgeInner.addView(toggleRow("片边文字（厂标 / 张数）", edgeTextMark, () -> edgeTextOn, on -> {
            edgeTextOn = on;
            redraw.accept(null);
        }), new LinearLayout.LayoutParams(-1, dp(52)));
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
        dateMark = new Square(this, UI_BLUE, uiToggleOff());
        dateRow = toggleRow("开启日期水印", dateMark, () -> dateOn, on -> {
            dateOn = on;
            if (dateOn && dateText == null) dateText = imprintDate();
            refreshEdgeGroups();
            onEdgeChanged();
        });
        datePage.addView(dateRow, new LinearLayout.LayoutParams(-1, dp(52)));
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
        // 0..200 mm: 30 was the schema's old cap and it stopped the date a
        // third of the way along a 6x12 / 6x17 rebate.
        dateInsetXBar = rangeRow(dateInner, "左右内边距", "字到片门左右边的距离，毫米（可直接在画布上拖）",
                0.0f, 200.0f, dateInsetX, v -> dateInsetX = v);
        dateInsetYBar = rangeRow(dateInner, "上下内边距", "字到片门上下边的距离，毫米（可直接在画布上拖）",
                0.0f, 200.0f, dateInsetY, v -> dateInsetY = v);
        // 8.0: the engine clamps at 8 now, because "make the date bigger" was
        // the one adjustment the old 3.0 ceiling refused.
        dateSizeBar = rangeRow(dateInner, "日期大小", "相对该样式的字高（1.0 是标准，可放大到 8 倍）",
                0.4f, 8.0f, dateSize, v -> dateSize = v);
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
        TextView dateTip = label(
                "日期位置：画面内时可选中角位，也可以直接在画布上拖；片边模式的位置由片边条决定，用上面两个内边距微调",
                7.5f, UI_MUTED, tfHan);
        dateTip.setLineSpacing(dp(1), 1f);
        dateInner.addView(dateTip, new LinearLayout.LayoutParams(-1, -2));
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
        // **Everything except the switch itself.** `setEnabledDeep` recursed
        // into the whole page, which disabled the very toggle that turns the
        // feature on -- the page dimmed and the switch stopped responding, so
        // the only way in was to guess where it was. The switch row is kept
        // live; the controls it governs are not.
        setEnabledDeepExcept(edgeInner, overscanOn, overscanRow);
        setEnabledDeepExcept(dateInner, dateOn, dateRow);
        if (overscanMark != null) overscanMark.setLit(overscanOn);
        if (edgeTextMark != null) edgeTextMark.setLit(edgeTextOn);
        if (dateMark != null) dateMark.setLit(dateOn);
        Probe.log("toggle", "sync overscan=" + overscanOn + " date=" + dateOn
                + " edgeText=" + edgeTextOn);
        refreshEdgeCards();
    }

    /** `setEnabledDeep`, but never touching `keep` or anything inside it. */
    private static void setEnabledDeepExcept(View view, boolean enabled, View keep) {
        if (view == null) return;
        if (keep != null && (view == keep || isInside(view, keep))) return;
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.38f);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); ++i)
                setEnabledDeepExcept(group.getChildAt(i), enabled, keep);
        }
    }

    private static boolean isInside(View view, View ancestor) {
        android.view.ViewParent p = view.getParent();
        while (p != null) {
            if (p == ancestor) return true;
            if (!(p instanceof View)) return false;
            p = ((View) p).getParent();
        }
        return false;
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
        // **Every gate, always.** The sheet used to be filtered to the shapes
        // this frame matches, so a 9:16 crop showed an empty list and read as
        // "the app only has three gates". Showing all ten -- with the ones that
        // cannot be printed greyed and saying why -- turns a dead end into an
        // instruction.
        final float ca = currentAspect();
        final double aspect = ca > 0 ? (double) Math.max(ca, 1f / ca) : 0.0;
        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<String> notes = new java.util.ArrayList<>();
        String selectedLabel = null;
        for (int i = 0; i < EDGE_IDS.length; ++i) {
            final boolean fits = aspect > 0
                    && Math.abs(aspect / EDGE_RATIOS[i] - 1.0) <= 0.045;
            labels.add(EDGE_LABELS[i]);
            notes.add(fits ? null : "与当前画面比例不符（" + EDGE_RATIO_LABELS[i] + "）");
            if (EDGE_IDS[i].equals(overscanFormat)) selectedLabel = EDGE_LABELS[i];
        }
        showChoiceSheet("选择片门格式", labels, notes, selectedLabel,
                i -> aspect > 0 && Math.abs(aspect / EDGE_RATIOS[i] - 1.0) <= 0.045,
                picked -> {
                    final int i = labels.indexOf(picked);
                    if (i < 0) return;
                    overscanFormat = EDGE_IDS[i];
                    if (edgeValue != null) edgeValue.setText(EDGE_LABELS[i]);
                    // The frame that goes in this gate, not whatever the scale
                    // page happened to be left on.
                    setScaleMm(gateMm(overscanFormat));
                    onEdgeChanged();
                });
    }

    private View buildTabs() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(UI_PAPER);
        final String[] names = {"胶片", "冲印", "光学", "尺度", "构图", "边框"};
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

    // ---------------------------------------------------------------- presets
    // A preset is one Look, the same JSON the session already stores per frame.
    // Sharing it is text on purpose: no FileProvider, no MIME the other app has
    // to understand, and the receiver can paste it back or read it.

    private org.json.JSONArray presetList() {
        final String raw = getSharedPreferences("presets", MODE_PRIVATE).getString("list", "[]");
        try {
            return new org.json.JSONArray(raw);
        } catch (Throwable t) {
            return new org.json.JSONArray();
        }
    }

    private void storePresetList(org.json.JSONArray list) {
        getSharedPreferences("presets", MODE_PRIVATE).edit().putString("list", list.toString()).apply();
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
    private static org.json.JSONObject lookCompact(Look l) {
        final org.json.JSONObject full = lookToJson(l);
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
            try { out.put(k, full.opt(k)); } catch (Throwable ignored) { }
        }
        return out;
    }

    /** "fujifilm_c200" reads badly in a chat; its label reads fine. */
    private String stockDisplay(String id) {
        if (id == null || id.isEmpty()) return "";
        int i = filmIds.indexOf(id);
        if (i >= 0 && i < filmLabels.size()) return shortStockName(filmLabels.get(i));
        i = printIds.indexOf(id);
        if (i >= 0 && i < printLabels.size()) return shortStockName(printLabels.get(i));
        return id;
    }

    private Look copyLook(Look src) {
        if (src == null) return null;
        try {
            return lookFromJson(lookToJson(src));
        } catch (Throwable t) {
            return null;
        }
    }

    private void showPresetsPanel() {
        final org.json.JSONArray list = presetList();
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
        head.addView(label("预设 / PRESETS", 12, UI_INK, tfHan),
                new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = label("关闭", 9, UI_MUTED, tfHan);
        close.setPadding(dp(12), dp(14), 0, dp(14));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        sheet.addView(head, new LinearLayout.LayoutParams(-1, dp(54)));

        TextView note = label("一套参数（胶片、曝光、光学、构图、片边、日期）。分享出去是一段文本，对方粘贴导入即可。",
                8.5f, UI_MUTED, tfHan);
        note.setPadding(dp(16), 0, dp(16), dp(10));
        sheet.addView(note);

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        if (list.length() == 0) {
            TextView empty = label("还没有预设。调好一张照片，点下面的「保存当前参数」。", 9, UI_MUTED, tfHan);
            empty.setPadding(dp(16), dp(12), dp(16), dp(12));
            rows.addView(empty);
        }
        for (int i = 0; i < list.length(); ++i) {
            final org.json.JSONObject p = list.optJSONObject(i);
            final int removeAt = i;
            if (p == null) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(10), 0);
            TextView name = label(p.optString("name", "预设"), 10, UI_INK, tfHan);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView apply = label("应用", 8.5f, UI_BLUE, tfHan);
            apply.setPadding(dp(8), dp(12), dp(8), dp(12));
            apply.setOnClickListener(v -> {
                dialog.dismiss();
                applyPreset(p);
            });
            row.addView(apply);

            TextView share = label("分享", 8.5f, UI_MUTED, tfHan);
            share.setPadding(dp(8), dp(12), dp(8), dp(12));
            share.setOnClickListener(v -> sharePreset(p));
            row.addView(share);

            TextView del = label("删除", 8.5f, UI_MUTED, tfHan);
            del.setPadding(dp(8), dp(12), dp(4), dp(12));
            del.setOnClickListener(v -> {
                final org.json.JSONArray next = new org.json.JSONArray();
                for (int k = 0; k < list.length(); ++k) {
                    if (k == removeAt) continue;
                    final org.json.JSONObject q = list.optJSONObject(k);
                    if (q != null) next.put(q);
                }
                storePresetList(next);
                dialog.dismiss();
                showPresetsPanel();
            });
            row.addView(del);
            rows.addView(row, new LinearLayout.LayoutParams(-1, dp(46)));
        }
        sheet.addView(cappedScroll(rows, 300), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setPadding(dp(10), dp(6), dp(10), dp(10));
        TextView save = label("保存当前参数", 8.5f, UI_BLUE, tfHan);
        save.setGravity(android.view.Gravity.CENTER);
        save.setOnClickListener(v -> {
            dialog.dismiss();
            saveCurrentPreset();
        });
        foot.addView(save, new LinearLayout.LayoutParams(0, dp(34), 1f));
        TextView pull = label("从剪贴板导入", 8.5f, UI_INK, tfHan);
        pull.setGravity(android.view.Gravity.CENTER);
        pull.setOnClickListener(v -> {
            dialog.dismiss();
            importPresetFromClipboard();
        });
        foot.addView(pull, new LinearLayout.LayoutParams(0, dp(34), 1f));
        TextView push = label("分享当前", 8.5f, UI_INK, tfHan);
        push.setGravity(android.view.Gravity.CENTER);
        push.setOnClickListener(v -> {
            dialog.dismiss();
            shareCurrentLook();
        });
        foot.addView(push, new LinearLayout.LayoutParams(0, dp(34), 1f));
        sheet.addView(foot, new LinearLayout.LayoutParams(-1, dp(50)));

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

    private void saveCurrentPreset() {
        if (frames.isEmpty() || filmStock == null) {
            showInAppToast("先导入一张照片、选一支胶片");
            return;
        }
        saveLookIntoFrames();
        final String fallback = stockDisplay(filmStock)
                + (printStock == null || printStock.isEmpty() ? "" : " · " + stockDisplay(printStock));
        askText("预设名字", fallback, name -> {
            final String n = (name == null || name.trim().isEmpty()) ? "未命名预设" : name.trim();
            try {
                final org.json.JSONArray list = presetList();
                final org.json.JSONObject p = new org.json.JSONObject();
                p.put("name", n);
                p.put("at", System.currentTimeMillis());
                p.put("look", lookToJson(captureLook()));
                list.put(p);
                storePresetList(list);
                showInAppToast("已保存预设「" + n + "」");
            } catch (Throwable t) {
                showInAppToast("保存失败：" + t.getMessage());
            }
        });
    }

    private void applyPreset(org.json.JSONObject p) {
        final org.json.JSONObject look = p == null ? null : p.optJSONObject("look");
        if (look == null) {
            showInAppToast("这个预设是空的");
            return;
        }
        final Look l = lookFromJson(look);
        if (l == null) {
            showInAppToast("预设格式不认识");
            return;
        }
        applyLook(l);
        saveLookIntoFrames();
        if (session != null) applyParams();
        if (uiReady && session != null) {
            showingOriginal = false;
            setOriginalVisual(false);
            renderImage();
        }
        showInAppToast("已应用预设「" + p.optString("name", "") + "」");
    }

    private void sharePreset(org.json.JSONObject p) {
        if (p == null) return;
        try {
            final org.json.JSONObject out = new org.json.JSONObject();
            out.put("app", "SpektraLab Android");
            out.put("version", UpdateCheck.versionCode(this));
            out.put("name", p.optString("name", "SpektraLab 参数"));
            final org.json.JSONObject raw = p.optJSONObject("look");
            out.put("look", raw == null ? null : lookCompact(lookFromJson(raw)));
            shareLookJson(out);
        } catch (Throwable t) {
            showInAppToast("分享失败：" + t.getMessage());
        }
    }

    private void shareCurrentLook() {
        if (frames.isEmpty() || filmStock == null) {
            showInAppToast("先导入一张照片、选一支胶片");
            return;
        }
        saveLookIntoFrames();
        try {
            final org.json.JSONObject out = new org.json.JSONObject();
            out.put("app", "SpektraLab Android");
            out.put("version", UpdateCheck.versionCode(this));
            out.put("name", stockDisplay(filmStock) + " · " + stockDisplay(printStock));
            out.put("look", lookCompact(captureLook()));
            shareLookJson(out);
        } catch (Throwable t) {
            showInAppToast("分享失败：" + t.getMessage());
        }
    }

    private void shareLookJson(org.json.JSONObject payload) {
        try {
            final android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(android.content.Intent.EXTRA_SUBJECT, payload.optString("name", "SpektraLab 参数"));
            send.putExtra(android.content.Intent.EXTRA_TEXT,
                    "SpektraLab 参数（复制整段，在应用里「从剪贴板导入」）\n" + payload.toString());
            startActivity(android.content.Intent.createChooser(send, "分享参数"));
        } catch (Throwable t) {
            showInAppToast("分享失败：" + (t.getMessage() == null ? "参数序列化失败" : t.getMessage()));
        }
    }

    private void importPresetFromClipboard() {
        try {
            final android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) {
                showInAppToast("剪贴板里没有文本");
                return;
            }
            final android.content.ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
            final CharSequence cs = item == null ? null : item.coerceToText(this);
            final String text = cs == null ? "" : cs.toString();
            final int a = text.indexOf('{'), b = text.lastIndexOf('}');
            if (a < 0 || b <= a) {
                showInAppToast("剪贴板里没有参数 JSON");
                return;
            }
            final org.json.JSONObject o = new org.json.JSONObject(text.substring(a, b + 1));
            final org.json.JSONObject look = o.optJSONObject("look") != null ? o.optJSONObject("look") : o;
            final Look l = lookFromJson(look);
            if (l == null) {
                showInAppToast("这段 JSON 不是参数");
                return;
            }
            applyLook(l);
            saveLookIntoFrames();
            if (session != null) applyParams();
            if (uiReady && session != null) {
                showingOriginal = false;
                setOriginalVisual(false);
                renderImage();
            }
            showInAppToast("已导入并应用");
        } catch (Throwable t) {
            showInAppToast("导入失败：" + (t.getMessage() == null ? "格式不对" : t.getMessage()));
        }
    }

    // ------------------------------------------------------- look on many frames

    /**
     * Copy this frame's Look onto other frames.
     *
     * <p>Nothing is re-decoded here: a frame carries its Look and renders with
     * it when it is opened, which is what makes this instant on twenty RAW
     * files. The files themselves are never touched.
     */
    private void showApplyLookSheet(final int sourceIndex) {
        if (sourceIndex < 0 || sourceIndex >= frames.size()) return;
        final java.util.ArrayList<Integer> targets = new java.util.ArrayList<>();
        for (int i = 0; i < frames.size(); ++i) {
            if (i != sourceIndex) targets.add(i);
        }
        if (targets.isEmpty()) {
            showInAppToast("只有这一张照片，没有可应用的对象");
            return;
        }
        final String[] labels = new String[targets.size()];
        for (int i = 0; i < targets.size(); ++i) {
            final int index = targets.get(i);
            final Frame f = frames.get(index);
            labels[i] = (f.name == null || f.name.isEmpty() ? "照片 " + (index + 1) : f.name);
        }
        final String srcName = frames.get(sourceIndex).name == null
                ? "这张" : frames.get(sourceIndex).name;
        showMultiChoiceSheet("把这张的参数应用到", 
                "选中的照片会换成这张的胶片、曝光、光学、构图、片边和日期。"
                        + "只改这条栏里的设置，文件不动；点开时才按新参数渲染。",
                labels, picked -> {
                    saveLookIntoFrames();
                    final Look src = copyLook(frames.get(sourceIndex).look);
                    if (src == null) {
                        showInAppToast("这张还没有可复制的参数");
                        return;
                    }
                    int n = 0;
                    for (int k : picked) {
                        final int index = targets.get(k);
                        frames.get(index).look = copyLook(src);
                        ++n;
                    }
                    saveSessionState();
                    rebuildFrameChips();
                    showInAppToast("已应用到 " + n + " 张照片（点开时生效）");
                });
    }

    /** A bottom sheet with checkboxes; `onDone` gets the picked indices. */
    private void showMultiChoiceSheet(String title, String note, String[] labels,
                                      final java.util.function.Consumer<java.util.List<Integer>> onDone) {
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
        close.setPadding(dp(12), dp(14), 0, dp(14));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        sheet.addView(head, new LinearLayout.LayoutParams(-1, dp(54)));
        if (note != null && !note.isEmpty()) {
            TextView noteView = label(note, 8.5f, UI_MUTED, tfHan);
            noteView.setPadding(dp(16), 0, dp(16), dp(10));
            sheet.addView(noteView);
        }

        final boolean[] picked = new boolean[labels.length];
        final Square[] marks = new Square[labels.length];
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < labels.length; ++i) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            final Square sq = new Square(this, UI_BLUE, uiToggleOff());
            marks[i] = sq;
            LinearLayout.LayoutParams sqLp = new LinearLayout.LayoutParams(dp(20), dp(20));
            sqLp.rightMargin = dp(12);
            row.addView(sq, sqLp);
            TextView name = label(labels[i], 10, UI_INK, tfHan);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            row.setOnClickListener(v -> {
                picked[index] = !picked[index];
                sq.setLit(picked[index]);
            });
            rows.addView(row, new LinearLayout.LayoutParams(-1, dp(46)));
        }
        sheet.addView(cappedScroll(rows, 320), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setPadding(dp(10), dp(6), dp(10), dp(10));
        final TextView all = label("全选", 9, UI_MUTED, tfHan);
        all.setGravity(android.view.Gravity.CENTER);
        all.setOnClickListener(v -> {
            final boolean want = !all.isSelected();
            all.setSelected(want);
            all.setText(want ? "全不选" : "全选");
            for (int i = 0; i < picked.length; ++i) {
                picked[i] = want;
                if (marks[i] != null) marks[i].setLit(want);
            }
        });
        foot.addView(all, new LinearLayout.LayoutParams(0, dp(34), 1f));
        TextView apply = label("应用", 9, UI_BLUE, tfHan);
        apply.setGravity(android.view.Gravity.CENTER);
        apply.setOnClickListener(v -> {
            final java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
            for (int i = 0; i < picked.length; ++i) if (picked[i]) out.add(i);
            if (out.isEmpty()) {
                showInAppToast("先勾选要应用的照片");
                return;
            }
            dialog.dismiss();
            onDone.accept(out);
        });
        foot.addView(apply, new LinearLayout.LayoutParams(0, dp(34), 1f));
        sheet.addView(foot, new LinearLayout.LayoutParams(-1, dp(50)));

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

    /** Import and export use the same paper sheet as the style pickers. */
    void showActionSheet(String title, String note, String[] items,
                                 java.util.function.IntConsumer onPick) {
        showActionSheet(title, note, items, onPick, null);
    }

    /**
     * The same sheet, with a gesture on its title.
     *
     * <p>There is no EditText anywhere in this UI and no settings screen, so a
     * knob that must exist without being discoverable rides on a long press:
     * the title is the only view every sheet has that a normal tap does not
     * already claim.
     */
    void showActionSheet(String title, String note, String[] items,
                         java.util.function.IntConsumer onPick, final Runnable onTitleLongPress) {
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
        final TextView titleView = label(title, 12, UI_INK, tfHan);
        if (onTitleLongPress != null) {
            titleView.setOnLongClickListener(v -> {
                dialog.dismiss();
                onTitleLongPress.run();
                return true;
            });
        }
        head.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = label("关闭", 9, UI_MUTED, tfHan);
        close.setPaintFlags(close.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        close.setPadding(dp(12), dp(14), 0, dp(14));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        sheet.addView(head, new LinearLayout.LayoutParams(-1, dp(54)));

        if (note != null && !note.isEmpty()) {
            TextView noteView = label(note, 8.5f, UI_MUTED, tfHan);
            noteView.setPadding(dp(16), 0, dp(16), dp(12));
            // **A long note has to scroll.** The sheet is `WRAP_CONTENT`, so a
            // full changelog (seventy lines) pushed the title and the action
            // rows off the screen -- and the update dialog's first action is
            // 「去更新」, so the announcement became a dead end. Capped at 46% of
            // the window, and only for notes long enough to need it, so every
            // short sheet looks exactly as it did.
            final int lines = note.split("\n", -1).length;
            // **The action rows keep their space.** A fixed cap was still a
            // guess: on a 640 dp screen the note took what was left and pushed
            // 「去更新」 off the window, so the announcement had no way out.
            // The room is what the screen gives after the title and the items,
            // and the note scrolls inside exactly that.
            final int screenH = getResources().getDisplayMetrics().heightPixels;
            final int reserved = dp(54) + items.length * dp(50) + dp(28);
            final int room = Math.max(dp(96), screenH - reserved);
            if (lines > 8 && room < lines * dp(16)) {
                ScrollView noteScroll = new ScrollView(this);
                noteScroll.setVerticalScrollBarEnabled(false);
                noteScroll.addView(noteView, new android.widget.FrameLayout.LayoutParams(-1, -2));
                sheet.addView(noteScroll, new LinearLayout.LayoutParams(-1, Math.min(room, dp(420))));
            } else {
                sheet.addView(noteView);
            }
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

    /** Anything that is not one of the seven names -- or off -- is not a meter.
     *  A state file written by another build, or a key collision, must not reach
     *  the engine as a value the user cannot act on: the render fails with a
     *  message about a string they never typed. */
    private static String sanitizeMeter(String m) {
        if (m == null || m.isEmpty()) return "";
        for (String id : METER_IDS) if (id.equals(m)) return m;
        return "balanced";
    }

    private static String meterName(String id) {
        if (id == null || id.isEmpty()) return "关闭";
        for (int i = 0; i < METER_IDS.length; ++i) if (METER_IDS[i].equals(id)) return METER_NAMES[i];
        return id;
    }

    /** The seven meters, one row each, with what each one actually does. */
    private void showMeterPicker() {
        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<String> notes = new java.util.ArrayList<>();
        int at = 0;
        for (int i = 0; i < METER_IDS.length; ++i) {
            labels.add(METER_NAMES[i]);
            notes.add(METER_NOTES[i]);
            if (METER_IDS[i].equals(meterMethod == null ? "" : meterMethod)) at = i;
        }
        showChoiceSheet("测光方式（决定整幅的曝光）", labels, notes, labels.get(at), null,
                picked -> {
                    final int index = labels.indexOf(picked);
                    if (index >= 0) setMeterMethod(METER_IDS[index]);
                });
    }

    private void setMeterMethod(String method) {
        meterMethod = method == null ? "" : method;
        refreshMeterChip();
        // **No delta from here.** `renderImage` sends the change on its own
        // thread, under the engine lock, immediately before `renderBegin` --
        // and that is now the only thread allowed to touch the delta
        // bookkeeping (`deltaSent`), because it is a map shared between
        // renders rather than a string built and thrown away.
        if (uiReady && session != null) renderImage();
    }

    private void refreshMeterChip() {
        if (meterChip == null) return;
        final boolean on = meterMethod != null && !meterMethod.isEmpty();
        meterChip.setText(meterName(meterMethod));
        meterChip.setTextColor(on ? UI_BLUE : UI_MUTED);
        meterChip.setBackground(outlineBox(on));
    }

    private void resetFilmPanel() {
        // 原图 is the reset: no stock, no render, the imported frame as it was.
        filmStock = null;
        printStock = "kodak_portra_endura";
        meterMethod = isRawFile ? "protect_highlights" : "";
        refreshMeterChip();
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
        scatterRadius = 0.0f;
        lensBlur = 0.0f;
        yFilter = 0.0f;
        mFilter = 0.0f;
        filterScale = 1.0f;
        preflash = 0.0f;
        hueCorrection = 1f;
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

    /**
     * 重置 on 边框 resets **the sub-page in front of you**: the 片边 page
     * leaves the date alone, and the 日期 page leaves the rebate alone. From
     * the two entry cards it resets both, because there is no narrower thing
     * to mean.
     */
    private void resetEdgePanel() {
        if (edgePageIndex == 0) { resetOverscanPage(); return; }
        if (edgePageIndex == 1) { resetDatePage(); return; }
        resetOverscanPage();
        resetDatePage();
    }

    private void resetOverscanPage() {
        overscanOn = false;
        overscanMark.setLit(false);
        edgeFog = 1.0f;
        edgeLeaks = 0.0f;
        frameNumber = 0.0f;
        edgeFNumber = 0.0f;
        edgeMode = "strip";
        edgeGate = "auto";
        edgeTextOn = true;
        edgeHoles = "white";
        edgeCarrier = "none";
        cameraFilter = "";
        sceneNorm = "power";
        cameraSeed = 1;
        frameSeed = 1;
        setBarProgress(edgeFogBar, 0f, 4f, edgeFog);
        setBarProgress(edgeLeaksBar, 0f, 4f, edgeLeaks);
        setBarProgress(frameNoBar, 0f, 99f, frameNumber);
        setBarProgress(edgeFNumBar, 0f, 64f, edgeFNumber);
        refreshKnob();
        if (session != null) onEdgeChanged();
    }

    private void resetDatePage() {
        dateOn = false;
        dateMark.setLit(false);
        dateSize = 1.0f;
        dateEv = 3.5f;
        dateInsetX = 3.0f;
        dateInsetY = 2.4f;
        datePlacement = "frame";
        dateCorner = "br";
        dateStyle = "lcd";
        dataText = "";
        dateTextB = "";
        setBarProgress(dateSizeBar, 0.4f, 8f, dateSize);
        setBarProgress(dateEvBar, -2f, 8f, dateEv);
        setBarProgress(dateInsetXBar, 0f, 200f, dateInsetX);
        setBarProgress(dateInsetYBar, 0f, 200f, dateInsetY);
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
            // **Not a negative, and not a developed picture either.** A raw is
            // the sensor's own reading: linear, no white balance, no colour
            // matrix, no tone curve -- which is why "刚导入时的原图" reads as
            // "the image is broken". Lightroom has already done all of that by
            // the time it shows you the frame; here the film and the paper are
            // what develop it. (Calling it a negative would be wrong twice
            // over: it is positive, and it is not film.)
            statusText.setText("ORIGINAL · RAW 原始数据（未调色，选胶片后才成片）");
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
        static final int COMPARE = 0, CAMERA = 1, EXPORT = 2;
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
            } else if (kind == EXPORT) {
                // A tray, and an arrow leaving it.
                canvas.drawLine(p, h * 0.58f, p, h * 0.86f, paint);
                canvas.drawLine(p, h * 0.86f, w - p, h * 0.86f, paint);
                canvas.drawLine(w - p, h * 0.86f, w - p, h * 0.58f, paint);
                canvas.drawLine(w * 0.5f, h * 0.70f, w * 0.5f, h * 0.16f, paint);
                canvas.drawLine(w * 0.5f, h * 0.16f, w * 0.28f, h * 0.38f, paint);
                canvas.drawLine(w * 0.5f, h * 0.16f, w * 0.72f, h * 0.38f, paint);
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
        // **The cropped frame, not the source.** The engine checks the shape of
        // the frame it is handed, so judging the source meant a 9:16 crop still
        // listed the gates the *file* matched -- choosing one then failed with
        // "not the gate's shape" on a list the app itself had said was fine.
        final float ca = currentAspect();
        final double aspect = ca > 0 ? (double) Math.max(ca, 1f / ca) : 0.0;
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
        if (appPaused) return;
        // **A batch export is not a browsing session.** Reopening each frame
        // restarts this queue, and twenty-five card renders between two
        // exports compete for the same GPU and the same session -- the batch
        // got slower with every frame and the canvas flickered with thumbnails
        // that were not the export's.
        if (batchExportRunning) return;
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
                // **A card is a pipeline rebuild, and a rebuild leaves planes.**
                // Twenty-five of them, back to back, is the biggest allocation
                // storm the app performs: measured on the bench, each stock
                // leaves about 5.5 MB live beside seven pinned tables, so the
                // shelf alone walks away with 300 MB that the next photograph
                // then has to find room beside. Giving the idle planes back
                // every six cards costs one re-allocation per six and keeps the
                // shelf's footprint flat.
                final SpektraLabEngine prefetchEngine = engine;
                int sinceTrim = 0;
                for (String id : pending) {
                    if (generation != prefetchGeneration || !prefetchRunning) return;
                    if (filmThumbs.containsKey(id)) continue;
                    final long t0 = System.currentTimeMillis();
                    final Bitmap thumb = renderStockThumbnail(id);
                    final long ms = System.currentTimeMillis() - t0;
                    if (++sinceTrim >= 6) {
                        sinceTrim = 0;
                        if (prefetchEngine != null) {
                            try { prefetchEngine.trimPool(); } catch (Throwable ignored) { }
                        }
                    }
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
                // **Tell the diff, or it lies.** `applyParamsTo` remembers the
                // last value it *sent*; a direct `setParams` moves the engine
                // without moving that memory, so the next `renderImage` sees
                // "2048, unchanged" and sends nothing -- and the canvas renders
                // at the shelf's 800. That is the `Thread-9 render:returned
                // 629x1167` in the field report: a blurry picture, not a crash.
                deltaSent.clear();
                deltaSession = null;
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
        // **Twenty-five renders nobody asked for.** The shelf develops every
        // stock in the background; when one of them takes the process down the
        // user was tapping a chip, not the card, so the report has to name the
        // card by id or it blames the wrong thing.
        CrashTrail.mark(this, "prefetch:card " + stock);
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
                // **Monochrome keeps its own paper; everything else follows the
                // canvas.** A black-and-white stock on Portra Endura comes out
                // sepia, so it gets Ilford. The rest used to fall back to the
                // catalogue's `target_print`, and the difference read as the
                // film being wrong: C200 on Crystal Archive is visibly cyan
                // beside the same frame on Endura, and the paper was the only
                // variable. A card is "this film, on the paper you are using".
                final String targetPaper = filmTargetPrints.get(stock);
                final String cardPaper = "ilford_multigrade_iv_rc".equals(targetPaper)
                        ? targetPaper
                        : (printStock == null ? "kodak_portra_endura" : printStock);
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

    /** The knee moves with the slider: at zero the engine's default is sent,
     *  so an untouched frame is bit-for-bit what it was.
     *
     *  <p>Measured on a real 20 MP frame, brightest decile: knee 1.0 with the
     *  room at its tightest darkens those pixels by 13.3 %, but at a mid
     *  slider value the room is 2.2 stops and the same knee leaves nothing to
     *  work on. Pulling the knee down to 0.5 by the top of the travel makes
     *  the middle of the slider do something too. */
    private float wireHighlightKnee() {
        if (highlightRoom <= 0.01f) return 1.0f;
        final float s = Math.max(0f, Math.min(3f, highlightRoom));
        return 1.0f - 0.5f * (s / 3.0f);
    }

    /** The toe's knee, which is the whole reason 阴影保护 looked dead.
     *
     *  <p>At the engine's default of -2 stops, pixels in the darkest tenth of
     *  a real frame were lifted by 0.23 % -- under one 8-bit level, i.e.
     *  nothing. Moving the knee to -0.3 takes the same setting to 2.9 %, and
     *  the knee now travels with the slider. */
    private float wireShadowKnee() {
        if (shadowRoom <= 0.01f) return -2.0f;
        final float s = Math.max(0f, Math.min(3f, shadowRoom));
        return -2.0f + 1.7f * (s / 3.0f);
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
        // **The rebate is printed in a Latin typeface.** The shelf's labels
        // carry a Chinese suffix ("CineStill 800T · 反转"), and stb_truetype has
        // no glyph for 反转 in Liberation Sans or DejaVu Mono -- every one of
        // them came out as a box on the film. The mark is for the card, not for
        // the negative; a real rebate says the stock's name in Latin and
        // nothing else.
        String latin = name;
        final int dot = latin.indexOf(" · ");
        if (dot > 0) latin = latin.substring(0, dot);
        final StringBuilder out = new StringBuilder();
        for (int k = 0; k < latin.length(); ++k) {
            final char c = latin.charAt(k);
            // Drop anything outside the printable Latin-1 range: a stray CJK
            // character is one box, and one box is worse than one missing word.
            if (c >= 0x20 && c < 0x7F) out.append(c);
        }
        final String trimmed = out.toString().trim();
        return (trimmed.isEmpty() ? "KODAK" : trimmed).toUpperCase(java.util.Locale.US);
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

    private View toggleRow(String labelText, Square mark,
                           java.util.function.BooleanSupplier isOn,
                           java.util.function.Consumer<Boolean> onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // **The whole row is the target.** The 18 dp square is a readout, not
        // the hit area; a 44 dp row with a 12 dp square is what "开关比较难点"
        // describes.
        row.setMinimumHeight(dp(48));
        row.setPadding(0, dp(6), 0, dp(6));
        row.addView(label(labelText, 8, UI_INK, tfHan), new LinearLayout.LayoutParams(0, -2, 1f));
        mark.setLit(isOn.getAsBoolean());
        row.addView(mark, new LinearLayout.LayoutParams(dp(18), dp(18)));
        row.setOnClickListener(v -> {
            // **Read the field, not a copy of it.** `applyLook`, the two reset
            // buttons and `resetOverscanPage` write `overscanOn` / `dateOn` /
            // `edgeTextOn` directly. The old `boolean[] state` kept the value
            // from construction time, so after restoring a look whose rebate
            // was already on, the row lit up true but the next tap toggled
            // *off a value that was already true* -- i.e. the picture kept the
            // rebate while the switch read off. That is "没点开也会进行渲染".
            final boolean next = !isOn.getAsBoolean();
            Probe.log("toggle", labelText + " " + isOn.getAsBoolean() + " -> " + next);
            mark.setLit(next);
            // **Say that a render is coming.** Every one of these switches is a
            // shoot-layer field, so it re-develops the film: 1-2 s at the 2048
            // preview. Flipping one and seeing nothing for two seconds reads as
            // "the switch does not work", and the natural response is to flip it
            // again -- which drops the negative and starts the whole develop
            // over, each time allocating a fresh set of planes. Ten taps of that
            // is what put the launcher out of memory.
            if (rendering) {
                statusText.setText("渲染中…（这是胶片层参数，约 1–2 秒）");
            }
            onChange.accept(next);
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
                if (bar instanceof ScrubBar && ((ScrubBar) bar).isDragging()) requestLivePreview();
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
        if (active == 5) openEdgePage(-1);
        else if (edgePageIndex >= 0) openEdgePage(-1);
        if (cropOverlay != null) {
            cropOverlay.setVisibility(active == 4 && composeSubPage == 0 ? View.VISIBLE : View.GONE);
        }
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
    /**
     * Where a touch on the canvas puts the date.
     *
     * <p>The quarter that was touched becomes the corner; the distance from
     * that quarter's two edges becomes the inset. Millimetres, not pixels: the
     * engine lays the imprint out against the gate, so the same inset has to
     * mean the same thing at every preview size.
     */
    private boolean onCanvasTouch(android.view.View v, android.view.MotionEvent e) {
        if (edgePageIndex != 1 || !dateOn || !"frame".equals(datePlacement)) return false;
        if (!uiReady || session == null || compare == null) return false;
        // **A pinch belongs to the canvas.** This listener sits in front of
        // `compare`, so a second finger has to be handed over explicitly or
        // zooming stops working the moment the date page is open.
        if (e.getPointerCount() > 1) {
            dateDragOn = false;
            return compare.onTouchEvent(e);
        }
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN: {
                final android.graphics.RectF r = compare.imageRect();
                if (r.width() <= 0f || r.height() <= 0f) return false;
                dateDragOn = true;
                dateDragU0 = clamp01((e.getX() - r.left) / r.width());
                dateDragV0 = clamp01((e.getY() - r.top) / r.height());
                final float[] c = dateCentre(r);
                dateDragCX = c[0]; dateDragCY = c[1];
                return true;
            }
            case android.view.MotionEvent.ACTION_MOVE: {
                if (!dateDragOn) return false;
                final android.graphics.RectF r = compare.imageRect();
                if (r.width() <= 0f || r.height() <= 0f) return false;
                final float u = clamp01((e.getX() - r.left) / r.width());
                final float fv = clamp01((e.getY() - r.top) / r.height());
                // **Relative, not absolute.** Putting the centre under the
                // finger on touch-down made a nudge jump half the picture; the
                // box keeps the offset it had and follows from there.
                applyDateCentre(clamp01(dateDragCX + (u - dateDragU0)),
                        clamp01(dateDragCY + (fv - dateDragV0)), r);
                return true;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                if (!dateDragOn) return false;
                dateDragOn = false;
                if (session != null) { applyParams(); renderImage(); }
                return true;
            default:
                return false;
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** The frame's own size in millimetres, the same numbers the engine uses. */
    private float[] frameMm(android.graphics.RectF r) {
        final float longMm = filmFormatMm > 0f ? filmFormatMm : 36.0f;
        final float aspect = r.width() / Math.max(1f, r.height());
        return aspect >= 1f
                ? new float[]{longMm, longMm / aspect}
                : new float[]{longMm * aspect, longMm};
    }

    /** The imprint's height in millimetres: the factor the engine uses. */
    private float dateTextHeightMm() {
        final float k = Math.max(0.4f, Math.min(8f, dateSize));
        return ("dots".equals(dateStyle) ? 0.95f : 1.3f) * k;
    }

    /** Its width, near enough to place its centre. LCD and dots advance ~0.70 em. */
    private float dateTextWidthMm() {
        final String t = (dateText != null && !dateText.isEmpty()) ? dateText : "'00 00 00";
        // Measured on the engine's LCD face: "26 11 07" is 7.2 mm wide at
        // 1.3 mm tall, i.e. 0.615 em per glyph. 0.62 keeps the centre within
        // ~0.5 mm of the finger instead of a word width.
        final float per = "data".equals(dateStyle) ? 0.55f : 0.62f;
        return dateTextHeightMm() * per * Math.max(1, t.length());
    }

    /** Where the imprint's centre sits on the picture, 0..1 each way. */
    private float[] dateCentre(android.graphics.RectF r) {
        final float[] mm = frameMm(r);
        final float w = dateTextWidthMm(), h = dateTextHeightMm();
        final boolean left = dateCorner.equals("bl") || dateCorner.equals("tl");
        final boolean top = dateCorner.equals("tl") || dateCorner.equals("tr");
        final float fw = Math.max(1f, mm[0]), fh = Math.max(1f, mm[1]);
        final float cx = left ? (dateInsetX + 0.5f * w) / fw
                : 1f - (dateInsetX + 0.5f * w) / fw;
        final float cy = top ? (dateInsetY + 0.5f * h) / fh
                : 1f - (dateInsetY + 0.5f * h) / fh;
        return new float[]{clamp01(cx), clamp01(cy)};
    }

    /**
     * Put the imprint's centre at (cx, cy).
     *
     * <p>The engine measures `inset_x` from the frame's side to the text's
     * *edge*, and `inset_y` from its top/bottom to the text's *baseline*. A
     * centre is what a finger knows, so the two are converted here -- ignoring
     * this is why a drag used to land a whole word-width away from the finger,
     * and why the far side of a big frame could not be reached (inset was
     * capped at 30 mm while 6x12 is 112 mm wide).
     */
    private void applyDateCentre(float cx, float cy, android.graphics.RectF r) {
        final float[] mm = frameMm(r);
        final float w = dateTextWidthMm(), h = dateTextHeightMm();
        // 2 % of the frame: the corner does not flicker while the finger
        // crosses the middle.
        final float hyst = 0.02f;
        final boolean wasLeft = dateCorner.equals("bl") || dateCorner.equals("tl");
        final boolean wasTop = dateCorner.equals("tl") || dateCorner.equals("tr");
        final boolean left = wasLeft ? cx < 0.5f + hyst : cx < 0.5f - hyst;
        final boolean top = wasTop ? cy < 0.5f + hyst : cy < 0.5f - hyst;
        dateCorner = top ? (left ? "tl" : "tr") : (left ? "bl" : "br");

        final float ix = left ? (cx * mm[0] - 0.5f * w) : ((1f - cx) * mm[0] - 0.5f * w);
        final float iy = top ? (cy * mm[1] - 0.5f * h) : ((1f - cy) * mm[1] - 0.5f * h);
        dateInsetX = Math.max(0f, Math.min(mm[0], ix));
        dateInsetY = Math.max(0f, Math.min(mm[1], iy));

        // The sliders must cover the same range or `setBarProgress` writes a
        // clamped value straight back into the field and the imprint jumps home.
        setBarProgress(dateInsetXBar, 0.0f, 200f, dateInsetX);
        setBarProgress(dateInsetYBar, 0.0f, 200f, dateInsetY);
        // The date lives in the SHOOT layer, so this is a full film pass
        // (~120 ms at 800 px); `requestLivePreview` merges the frames.
        requestLivePreview();
    }

    private void setStage(int mode, String name) {
        if (mode == 0) compare.clear();
        refreshComparePill();
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
        if (batchExportRunning) {
            showInAppToast("批量导出中，等它跑完再导入");
            return;
        }
        // **Two pickers, and only one of them returns the file on disk.**
        // Measured on this ROM: `ACTION_OPEN_DOCUMENT` with type `image/*` is
        // served by the gallery ("安全访问"), and the copy it hands over has had
        // its EXIF cut down to the capture date and ISO -- 6,415,345 bytes
        // against the original's 6,416,266, different header hash, and both our
        // parser and the platform's agree on the two surviving fields. The same
        // action with `*/*` plus an EXTRA_MIME_TYPES list goes to the document
        // browser, which returns the original bytes. So the file entry is the
        // one that keeps the capture fields, and it has to say so.
        showActionSheet("导入照片",
                "要保留拍摄参数（EXIF），请选第二项：文件管理器读的是磁盘上的原文件。"
                        + "系统相册选择器会先复制一份，部分机型的副本会丢掉这些信息。", new String[]{
                "从相册选择（快 · 可能丢拍摄参数）",
                "RAW / JPEG / HEIC · 任意文件（保留 EXIF）"
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
        if (batchExportRunning) {
            // **The only cancel.** A batch can run for ten minutes; without a
            // way out the user's choice is to kill the app.
            showActionSheet("批量导出中", "正在一张张导出。停止会保留已经写好的那些。",
                    new String[]{"停止批量导出", "继续"}, which -> {
                        if (which == 0) batchExportAbort = true;
                    });
            return;
        }
        if (session == null) {
            showInAppToast("先导入一张照片");
            return;
        }
        final String full = (sourceWidth > 0 && sourceHeight > 0)
                ? "全尺寸 " + sourceWidth + "×" + sourceHeight : "全尺寸";
        // No ceiling to warn about: the export renders the frame that was
        // imported, and that may be the file's own size. What is worth saying
        // is that it takes time, not that it will be refused.
        final String fullNote = "（原图 · 大尺寸会慢）";
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
                    "驱动单缓冲 %.0f MiB（分块后不再限制单帧）；本机按可用内存建议 %.1f MP",
                    probe.maxStorageBufferRange / 1048576.0, budgetMp);
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
        final boolean batchable = frames.size() > 1;
        final java.util.ArrayList<String> rows = new java.util.ArrayList<>();
        if (batchable) rows.add("批量 · 全部 " + frames.size() + " 张（JPEG · 全尺寸）");
        rows.add("JPEG · 实时画质");
        rows.add("JPEG · 预览画质 · 长边 3400");
        rows.add("JPEG · 全尺寸（原图）");
        rows.add("TIFF 16 位 · 实时画质");
        rows.add("TIFF 16 位 · 预览画质 · 长边 3400");
        rows.add("TIFF 16 位 · 全尺寸（原图）");
        rows.add("DNG · 全尺寸（电脑用 · 手机版打不开）");
        showActionSheet("导出照片", note, rows.toArray(new String[0]), which0 -> {
            int which = which0;
            if (batchable) {
                if (which == 0) {
                    startBatchExport("jpeg", "full");
                    return;
                }
                --which;
            }
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
        // **The canvas zooms.** A 20 MP frame is inspected at fit size only
        // down to the whole picture; pinch says "let me see that corner".
        // Everything stays in `dst`, so the crop frame and any other overlay
        // that asks `imageRect()` follows the zoom for free.
        private float zoom = 1f, panX = 0f, panY = 0f;
        private boolean panning;
        private long lastTapMs = 0;
        /** **The divider is opt-in.** Any single-finger drag used to become
         *  a comparison split, which is what "一直在跟分割线抢手势" was: the
         *  canvas could not pan without dragging the line instead. Now the
         *  pill in the corner turns it on. */
        private boolean splitMode = false;

        // **Pinch, by hand.** `ScaleGestureDetector` refuses to start until the
        // fingers are `getScaledMinimumScalingSpan()` apart -- about 27 mm on
        // this class of phone -- and that value is not configurable. Holding
        // two fingers closer than that (the natural way to pinch a phone) did
        // nothing at all: not the zoom, and not the fallback either, because
        // `isInProgress()` stayed false and the switch below has no
        // multi-pointer branch any more. So the span is measured here.
        private boolean pinching = false;
        private float pinchStartSpan = 0f, pinchStartZoom = 1f;
        private float pinchStartCx = 0f, pinchStartCy = 0f;
        private float pinchStartPanX = 0f, pinchStartPanY = 0f;
        // **The platform's detector, not a hand-rolled one.** Tracking
        // pointer ids by hand got the sequence wrong: the pinch never moved
        // the picture and the leftover finger became a comparison drag.
        // `ScaleGestureDetector` owns that state and reports a factor.


        /** Retired: the long press lives on the pill now. Kept so the
         *  `removeCallbacks` calls stay valid, never posted. */
        private final Runnable press = new Runnable() {
            @Override public void run() { }
        };

        /** **The pill owns "show me the original".** A press on the picture
         *  used to arm a 500 ms timer that fired in the middle of every pinch,
         *  which is the "一直跟长按对比功能冲突" report. */
        void setHolding(boolean on) {
            final boolean wanted = on && before != null && after != null;
            if (holding == wanted) return;
            holding = wanted;
            invalidate();
        }

        CompareView(android.content.Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            slop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
        }

        /** Two fingers down: remember the span, the centre and where we were. */
        private void beginPinch(android.view.MotionEvent e) {
            if (e.getPointerCount() < 2) return;
            pinchStartSpan = Math.max(1f, twoFingerSpan(e));
            pinchStartZoom = zoom;
            pinchStartCx = (e.getX(0) + e.getX(1)) / 2f;
            pinchStartCy = (e.getY(0) + e.getY(1)) / 2f;
            pinchStartPanX = panX;
            pinchStartPanY = panY;
            pinching = true;
            handler.removeCallbacks(press);
            holding = false;
            dragging = false;
            panning = false;
            split = 0f;
        }

        private void updatePinch(android.view.MotionEvent e) {
            if (e.getPointerCount() < 2 || pinchStartSpan <= 1f) return;
            final float span = Math.max(1f, twoFingerSpan(e));
            final float want = Math.max(0.2f, Math.min(8f, pinchStartZoom * span / pinchStartSpan));
            // Keep the point between the fingers under the fingers.
            final float cx = (e.getX(0) + e.getX(1)) / 2f;
            final float cy = (e.getY(0) + e.getY(1)) / 2f;
            final float k = want / Math.max(1e-4f, pinchStartZoom);
            panX = (pinchStartPanX - (pinchStartCx - getWidth() / 2f)) * k + (cx - getWidth() / 2f);
            panY = (pinchStartPanY - (pinchStartCy - getHeight() / 2f)) * k + (cy - getHeight() / 2f);
            zoom = want;
            if (zoom <= 1.005f) { panX = 0f; panY = 0f; }
            clampPan();
            invalidate();
        }

        private void endPinch() {
            if (!pinching) return;
            pinching = false;
            pinchStartSpan = 0f;
        }

        private float twoFingerSpan(android.view.MotionEvent e) {
            return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
        }

        void setBefore(Bitmap b) { before = b; invalidate(); }
        void setAfter(Bitmap b) { after = b; invalidate(); }

        boolean isSplitMode() { return splitMode; }

        float zoomLevel() { return zoom; }
        float panXLevel() { return panX; }
        float panYLevel() { return panY; }

        /** Set the view outright; the caller has already worked out the pan. */
        void setView(float z, float px, float py) {
            zoom = Math.max(0.2f, Math.min(8f, z));
            panX = px;
            panY = py;
            if (zoom <= 1.005f) { panX = 0f; panY = 0f; }
            clampPan();
            invalidate();
        }

        void setSplitMode(boolean on) {
            splitMode = on;
            if (!on) split = 0f;
            invalidate();
        }

        void clear() {
            before = null;
            after = null;
            holding = false;
            dragging = false;
            split = 0f;
            splitMode = false;
            zoom = 1f;
            panX = 0f;
            panY = 0f;
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

        /** The rectangle the picture occupies on screen, for overlays. */
        android.graphics.RectF imageRect() { return new android.graphics.RectF(dst); }

        private void drawFit(android.graphics.Canvas canvas, Bitmap bmp) {
            final float vw = getWidth(), vh = getHeight();
            if (vw <= 0 || vh <= 0 || bmp.getWidth() == 0) return;
            final float scale = Math.min(vw / bmp.getWidth(), vh / bmp.getHeight()) * zoom;
            final float w = bmp.getWidth() * scale, h = bmp.getHeight() * scale;
            final float left = (vw - w) / 2f + panX, top = (vh - h) / 2f + panY;
            dst.set(left, top, left + w, top + h);
            canvas.drawBitmap(bmp, null, dst, paint);
        }

        /** Keep the picture from being dragged off its own frame. */
        private void clampPan() {
            final Bitmap base = after != null ? after : before;
            if (base == null || base.getWidth() == 0) return;
            final float vw = getWidth(), vh = getHeight();
            if (vw <= 0 || vh <= 0) return;
            final float scale = Math.min(vw / base.getWidth(), vh / base.getHeight()) * zoom;
            final float w = base.getWidth() * scale, h = base.getHeight() * scale;
            final float maxX = Math.max(0f, (w - vw) / 2f);
            final float maxY = Math.max(0f, (h - vh) / 2f);
            panX = Math.max(-maxX, Math.min(maxX, panX));
            panY = Math.max(-maxY, Math.min(maxY, panY));
        }

        private float span(android.view.MotionEvent e) {
            return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
        }

        /** Back to fit. Double-tap, or call it when a frame is replaced. */
        void resetView() {
            zoom = 1f; panX = 0f; panY = 0f;
            invalidate();
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
            paint.setColor(darkTheme ? 0xFFFFFFFF : 0xFF1C2AA5);
            canvas.drawRect(x - r, cy - r, x + r, cy + r, paint);
            paint.setStyle(android.graphics.Paint.Style.FILL);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            // Zooming only needs *a* picture; the before/after divider needs
            // both, and says so where it uses them.
            if (after == null && before == null) return false;

            final int action = e.getActionMasked();

            // **The second finger starts the pinch immediately.** No slop, no
            // minimum span: two fingers on the glass is the whole condition.
            if (action == android.view.MotionEvent.ACTION_POINTER_DOWN
                    && e.getPointerCount() >= 2) {
                beginPinch(e);
                try { DebugSink.text("zoom", "pinch begin span=" + Math.round(pinchStartSpan)); }
                catch (Throwable ignored) { }
                return true;
            }
            if (pinching) {
                if (action == android.view.MotionEvent.ACTION_MOVE) {
                    updatePinch(e);
                    return true;
                }
                if (action == android.view.MotionEvent.ACTION_POINTER_UP) {
                    endPinch();
                    try { DebugSink.text("zoom", "pinch end at " + String.format(java.util.Locale.US, "%.2f", zoom)); }
                    catch (Throwable ignored) { }
                    return true;
                }
                if (action == android.view.MotionEvent.ACTION_UP
                        || action == android.view.MotionEvent.ACTION_CANCEL) {
                    endPinch();
                    lastTapMs = 0;
                    return true;
                }
                return true;
            }

            switch (action) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    dragging = false;
                    holding = false;
                    panning = false;
                    // no long press on the picture: the pill owns it
                    return true;
                case android.view.MotionEvent.ACTION_POINTER_DOWN:
                case android.view.MotionEvent.ACTION_POINTER_UP:
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    // **The divider only moves while its pill is lit.**
                    if (splitMode) {
                        if (before == null || after == null) return true;
                        if (!dragging) {
                            if (Math.abs(e.getX() - downX) < slop && Math.abs(e.getY() - downY) < slop) return true;
                            dragging = true;
                            handler.removeCallbacks(press);
                        }
                        split = Math.max(0.02f, Math.min(0.98f, e.getX() / Math.max(1, getWidth())));
                        invalidate();
                        return true;
                    }
                    // **One finger pans only once there is something to pan.**
                    if (zoom > 1.01f) {
                        if (!panning) {
                            if (Math.abs(e.getX() - downX) < slop && Math.abs(e.getY() - downY) < slop) return true;
                            panning = true;
                            handler.removeCallbacks(press);
                        }
                        panX += e.getX() - downX;
                        panY += e.getY() - downY;
                        downX = e.getX();
                        downY = e.getY();
                        clampPan();
                        invalidate();
                        return true;
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP: {
                    handler.removeCallbacks(press);
                    final long now = android.os.SystemClock.uptimeMillis();
                    if (!splitMode && !dragging && !panning && !holding && split == 0f && now - lastTapMs < 320) {
                        resetView();
                        lastTapMs = 0;
                    } else {
                        lastTapMs = (dragging || panning || holding) ? 0 : now;
                    }
                    if (holding) {
                        holding = false;
                        invalidate();
                    } else if (!dragging && split > 0f) {
                        // A tap closes it: the divider is a gesture, not a mode.
                        split = 0f;
                        invalidate();
                    }
                    dragging = false;
                    panning = false;
                    return true;
                }
                case android.view.MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(press);
                    holding = false;
                    dragging = false;
                    panning = false;
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
        final long nativeMb = (long) w * h * 12L >> 20;
        final long availMb = availableMemoryBytes() >> 20;
        // **The number that makes "original" an informed choice.** The sheet
        // used to say "memory may run out and the app will disappear" without
        // ever saying how much; on a 50 MP file that is 600 MB of float and the
        // answer decides whether the tap is a coin flip.
        final String budgetMessage = "原图 " + w + "×" + h + "（" + megapixels(w, h) + " MP）。"
                + "\n\n按原图：渲染约 " + Math.max(5, (int) Math.round((double) w * h / 1e6 * 1.4))
                + " 秒；交给 GPU 需要约 " + nativeMb + " MB 连续内存，本机现在可用 "
                + availMb + " MB。"
                + (nativeMb * 10L > availMb * 8L
                        ? "\n按原图很可能被系统回收（应用直接消失，不报错）。" : "")
                + "\n\n缩到 " + sw + "×" + sh + "：更快更稳，细节会少一些。"
                + "\n\n本机按可用内存建议约 "
                + String.format(java.util.Locale.US, "%.1f", capPixels / 1e6) + " MP"
                + (overscanOn ? "（已按片边预留画布）" : "") + "。";
        showActionSheet("按多大的尺寸导入", budgetMessage, new String[]{
                "按原图 " + w + "×" + h, "缩到 " + sw + "×" + sh, "取消"
        }, which -> {
            if (which == 0) {
                importAtNativeSize = true;
                loadImage(uri);
            } else if (which == 1) {
                importBudgetAccepted = true;
                loadImage(uri);
            }
        });
    }

    /**
     * The RAW sheet, shown *before* the decode.
     *
     * The size comes from the container header (`nativeRawDimensions`), so the
     * question is asked while the memory is still unspent -- which is the whole
     * difference between this and the old post-decode notice.
     */
    private void showRawBudgetDialog(final Uri uri, final int w, final int h, final int capPixels) {
        final double fit = importScale(w, h, capPixels);
        final int sw = Math.max(1, (int) Math.round(w * fit));
        final int sh = Math.max(1, (int) Math.round(h * fit));
        final int seconds = Math.max(5, (int) Math.round((double) w * h / 1e6 * 1.4));
        // **The numbers, before the choice.** "内存不够时会闪退" is true but
        // tells nobody whether this frame is close or hopeless; the two figures
        // are what the decision actually turns on.
        final long needBytes = (long) w * (long) h * 100L;
        final long availBytes = availableMemoryBytes();
        final String message = "这张 RAW 是 " + w + "×" + h + "（" + megapixels(w, h) + " MP）。"
                + "\n\n按原图：需要约 " + String.format(java.util.Locale.US, "%.1f", needBytes / 1e9)
                + " GB 内存（解码 + 显存），本机当前可用 "
                + String.format(java.util.Locale.US, "%.1f", availBytes / 1e9) + " GB；"
                + "不够时会直接闪退，渲染约 " + seconds + " 秒。"
                + "\n缩到 " + sw + "×" + sh + "：更快更稳，细节会少一些。"
                + "\n\n本机按可用内存建议约 "
                + String.format(java.util.Locale.US, "%.1f", capPixels / 1e6) + " MP"
                + (overscanOn ? "（已按片边预留画布）" : "") + "。";
        showActionSheet("按多大的尺寸导入", message, new String[]{
                "按原图 " + w + "×" + h, "缩到 " + sw + "×" + sh, "取消"
        }, which -> {
            if (which == 0) {
                importAtNativeSize = true;
                loadRawImage(uri);
            } else if (which == 1) {
                pendingCapPixels = capPixels;
                loadRawImage(uri);
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
        // **The same budget the import uses.** This used to allow `avail - 128
        // MB` at 12 B/px, which only covers the decode -- the GPU's 76 B/px of
        // planes was not in it, so the "缩到" size could still die inside
        // `spk_open`. `maxPixelsByMemory` is the one number that already
        // accounts for the reserve and all three copies.
        final long availBytes = availableMemoryBytes();
        if (availBytes > 0) {
            final long capByMemory = DeviceProbe.maxPixelsByMemory(availBytes);
            if (capByMemory > 0 && (long) w * (long) h > capByMemory) {
                k = Math.min(k, Math.sqrt((double) capByMemory / (double) area));
            }
        }
        return Math.min(k, 1.0);
    }

    /**
     * The line that replaces a refusal: how big this frame is, and what the
     * memory estimate would have picked. It never changes the import.
     */
    private String importSizeNote(int w, int h) {
        final long area = (long) w * (long) h;
        final long suggest = maxImportPixels();
        if (area <= suggest) return "";
        return " · " + megapixels(w, h) + " MP（建议 "
                + String.format(java.util.Locale.US, "%.1f", suggest / 1e6) + " MP，渲染会慢）";
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

    private int debugPixelCapMp() {
        try {
            return getSharedPreferences(DEBUG_PREFS, MODE_PRIVATE).getInt(DEBUG_CAP_MP, 0);
        } catch (Throwable ignored) { return 0; }
    }

    /** The range the *running* engine was created with; 0 when not simulated. */
    private int appliedSimMib = 0;

    private int debugStorageLimitMib() {
        try {
            return getSharedPreferences(DEBUG_PREFS, MODE_PRIVATE).getInt(DEBUG_SIM_MIB, 0);
        } catch (Throwable ignored) { return 0; }
    }

    private void setDebugStorageLimitMib(int mib) {
        try {
            getSharedPreferences(DEBUG_PREFS, MODE_PRIVATE).edit().putInt(DEBUG_SIM_MIB, mib).apply();
        } catch (Throwable ignored) { }
        try { DebugSink.text("debug-cap", "simulated storage range = " + mib + " MiB (0 = off)"); }
        catch (Throwable ignored) { }
    }

    private void setDebugPixelCapMp(int mp) {
        try {
            getSharedPreferences(DEBUG_PREFS, MODE_PRIVATE).edit().putInt(DEBUG_CAP_MP, mp).apply();
        } catch (Throwable ignored) { }
        try { DebugSink.text("debug-cap", "pixel ceiling override = " + mp + " MP (0 = off)"); }
        catch (Throwable ignored) { }
    }

    /**
     * The hidden sheet: long-press the version line in 关于.
     *
     * <p>Presets rather than a text field, because the number has to be
     * comparable between two testers and a typo in a pixel cap reads as a
     * crash. 关于 shows the effective value afterwards, after the memory
     * ceiling and the hard cap have had their say -- picking 50 MP on a phone
     * with 4.5 GB free legitimately lands at half that.
     */
    private void showPixelCapSheet() {
        final int[] caps = {0, 20, 30, 40, 50};
        final int[] sims = {0, 128, 256};
        final int currentCap = debugPixelCapMp();
        final int currentSim = debugStorageLimitMib();
        final java.util.List<String> labels = new java.util.ArrayList<>();
        for (int v : caps)
            labels.add((v == currentCap ? "· " : "") + (v == 0 ? "像素上限：按设备自动" : "像素上限：" + v + " MP"));
        for (int v : sims)
            labels.add((v == currentSim ? "· " : "") + (v == 0 ? "模拟驱动绑定：关闭" : "模拟驱动绑定：" + v + " MiB"));
        final long memoryCap = DeviceProbe.maxPixelsByMemory(availableMemoryBytes());
        final String note = "像素上限只改「按绑定范围算」的那一道；内存上限（这台机器约 "
                + String.format(java.util.Locale.US, "%.0f", memoryCap / 1e6)
                + " MP）和硬上限 42 MP 仍然有效。模拟驱动绑定让引擎按小绑定分带渲染，"
                + "改完要重启应用才生效。两者一起开 = 假装自己是 128 MiB 的机型，但允许导入更大的帧。";
        showActionSheet("调试：像素上限 / 驱动绑定", note, labels.toArray(new String[0]), which -> {
            if (which < caps.length) setDebugPixelCapMp(caps[which]);
            else setDebugStorageLimitMib(sims[which - caps.length]);
            showAbout();
        });
    }

    private void showAbout() {
        probeNow();
        final String version = UpdateCheck.versionName(this) + "（" + UpdateCheck.versionCode(this) + "）";
        final int debugMp = debugPixelCapMp();
        final int debugSim = debugStorageLimitMib();
        final String debugLine = (debugMp == 0 && debugSim == 0 && appliedSimMib == 0) ? ""
                : "\n调试：像素上限 " + (debugMp > 0 ? debugMp + " MP" : "按设备")
                  + "，实际生效 " + String.format(java.util.Locale.US, "%.1f", maxImportPixels(false) / 1e6) + " MP"
                  + (debugSim > 0 ? "；模拟绑定 " + debugSim + " MiB" : "")
                  + (debugSim != appliedSimMib ? "（重启后生效）" : "");
        final String details = probe.describe() + debugLine
                + "\n\n引擎：SpektraLab / spektrafilm（GPL-3.0，Andrea Volpato 的光谱胶片模型）"
                + "\n移植：github.com/SAINTTaiYi/SpektraLab-Android";
        // The report switch lives here rather than in a settings screen: it is
        // one line of one dialog, and it is the only thing this app sends that
        // the user did not ask for. The label carries the state, because the
        // sheet's note cannot scroll and a longer paragraph would be clipped.
        final boolean reporting = DeviceReport.enabled(this);
        final boolean dark = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("dark", false);
        showActionSheet("SpektraLab Android " + version, details, new String[]{
                "检查更新",
                "预设（保存 / 分享参数）",
                // **Askable, not only post-mortem.** A hang, a wrong picture or a
                // render that "took forever" leaves nothing on disk while the
                // process is alive -- and those are the reports that arrive as a
                // screenshot. One tap copies the same ring a crash would have
                // left behind.
                "导出最近的步骤（上传给开发者）",
                reporting ? "驱动报告：开（每周一次，只报机型和 GPU）" : "驱动报告：关",
                dark ? "界面：暗黑 · 黑橙（切换后重启生效）" : "界面：蓝白（切换后重启生效）",
                "反馈群（QQ 1134320449）", "打开 GitHub", "关闭"
        }, which -> {
            if (which == 0) {
                UpdateCheck.check(this, true);
            } else if (which == 1) {
                showPresetsPanel();
            } else if (which == 2) {
                // **The only upload the user starts by hand**, and the only way
                // the ring leaves the phone when nothing has gone wrong yet.
                final String ring = CrashTrail.snapshot(this);
                final String head = "user-report\n" + reportHead()
                        + "steps=" + DebugSink.ringSize() + "\n";
                new Thread(() -> {
                    final boolean ok = DebugSink.report("steps", head,
                            ring.isEmpty() ? "(这次还没有记录)" : ring);
                    runOnUiThread(() -> showTextDialog(
                            ok ? "已上传最近的步骤" : "上传失败",
                            "记录号 " + DebugSink.REF + "\n\n"
                            + (ring.isEmpty() ? "(这次还没有记录)" : ring)
                            + (ok ? "\n已经上传，不用再发给我了。"
                                  : "\n网络没通，请把这段复制发给我。"),
                            false));
                }, "SpektraLab-report").start();
            } else if (which == 3) {
                DeviceReport.setEnabled(this, !reporting);
                showAbout();
            } else if (which == 4) {
                // The palette is read once in onCreate; a restart is the whole
                // rebuild, and the current session's state is already saved on
                // pause, so nothing the user did is lost by asking for it.
                getSharedPreferences("ui", MODE_PRIVATE).edit()
                        .putBoolean("dark", !dark).apply();
                showActionSheet("界面已切换", "退出应用再打开，新的界面就会生效。",
                        new String[]{"好"}, null);
            } else if (which == 5) {
                showFeedbackGroup(false);
            } else if (which == 6) {
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/SAINTTaiYi/SpektraLab-Android/releases/latest")));
                } catch (Throwable ignored) { }
            }
        }, this::showPixelCapSheet);
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
            final Intent multi = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
            multi.putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, 50);
            chain.add(multi);
        }
        chain.add(new Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"));
        if (!launchFirstResolvable(chain, 1)) {
            showInAppToast("本机没有相册选择器；请改用「RAW / JPEG / HEIC」那一项");
        }
    }

    /**
     * Launch the first intent this ROM resolves, and never let the launch itself
     * kill the process. False when none of them is available.
     */
    private boolean launchFirstResolvable(java.util.List<Intent> chain, int requestCode) {
        for (Intent intent : chain) {
            try {
                // **Do not gate on `resolveActivity`.** Android 11's package
                // visibility rules hide every storage picker from an app that
                // has not declared it in `<queries>`, and `resolveActivity`
                // then answers null for the system picker that
                // `startActivityForResult` would have opened. That is
                // "本机没有相册选择器" / "本机没有可用的文件选择器" on a phone whose
                // gallery and file manager are both fine. Launch it; an
                // ActivityNotFoundException falls through to the next
                // candidate, and everything else is caught below.
                //
                // The grant has to be asked for as persistable *before* the
                // picker runs; `onActivityResult` then either keeps it or
                // copies the file. Without this flag there is nothing to take.
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                if (requestCode == 1) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                Probe.log("picker", "try " + intent.getAction());
                startActivityForResult(intent, requestCode);
                Probe.log("picker", "ok " + intent.getAction());
                try { DebugSink.text("picker", "launched " + intent.getAction()); }
                catch (Throwable ignored) { }
                return true;
            } catch (android.content.ActivityNotFoundException missing) {
                // This ROM has no handler for this action; try the next one.
                Probe.log("picker", "missing " + intent.getAction());
                try { DebugSink.text("picker", "unresolvable " + intent.getAction()); }
                catch (Throwable ignored) { }
            } catch (Throwable t) {
                // A provider that vanished between the check and the launch, a
                // SecurityException, or a picker that throws from its own
                // onCreate. Next one.
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
        // **A multi-selection arrives as a ClipData**, a single one as `data`.
        // Both are collected here; one URI keeps the old path exactly (and its
        // "copy to our directory" fallback), several go through the queue.
        final java.util.ArrayList<Uri> picked = new java.util.ArrayList<>();
        if (data != null) {
            final android.content.ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                for (int i = 0; i < clip.getItemCount(); ++i) {
                    final Uri u = clip.getItemAt(i).getUri();
                    if (u != null) picked.add(u);
                }
            } else if (data.getData() != null) {
                picked.add(data.getData());
            }
        }
        if (picked.isEmpty()) {
            try { DebugSink.text("picker", "RESULT_OK with no data"); } catch (Throwable ignored) { }
            showInAppToast("没有拿到图片（选择器返回空）。请点「导入」→「从文件管理器选择」再试一次");
            return;
        }
        if (picked.size() == 1) {
            importPicked(picked.get(0));
            return;
        }
        startBatchImport(picked);
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
        CrashTrail.mark(this, "import:picked");
        // A pick from ＋ is a new photograph, never a chip coming back.
        reopeningFrame = false;
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
        CrashTrail.mark(this, "import:load " + uri);
        // **The previous photograph's dimensions are not this one's.**
        // `originalAspect()` prefers `importedWidth` when it is set, and only
        // the RAW path ever set it. So a RAW followed by a JPEG kept the RAW's
        // shape: on a 6912x4530 raw then a 1280x1708 jpeg, `refreshEdgeFormats`
        // logged `aspect=1.5258` for the *portrait* frame it had just imported,
        // picked the 135 gate, and the render then refused it with
        // "a 1280 x 1708 frame is not the 135 gate's shape" -- E-1305.
        importedWidth = 0;
        importedHeight = 0;
        // A profile belongs to one file. The RAW branch below attaches this
        // frame's; a JPEG has none, and the canvas falls back to plain sRGB.
        sourceToneCurve = null;
        // **The previous frame's gate is not this frame's shape.** A 6x17 chosen
        // for a panorama stayed selected for the next picture, and the render
        // then refused a 1365x1024 frame with "is not the 120_6x17 gate's
        // shape". `refreshEdgeFormats` re-picks from the new aspect a few lines
        // later, so this only clears the stale choice.
        overscanFormat = "135";
        // **The one number that separates the two hypotheses.** A death inside
        // the decode leaves nothing but `import:load` behind, and at that point
        // "this file is corrupt" and "this file is 50 MP and the kernel
        // reclaimed us" look identical. The header costs a few milliseconds and
        // answers it before anything large is allocated.
        try {
            final int[] sz = sniffImageSize(uri);
            final long want = sz == null ? 0L : (long) sz[0] * sz[1] * 12L;
            CrashTrail.mark(this, "import:size "
                    + (sz == null ? "unknown" : sz[0] + "x" + sz[1])
                    + " need=" + (want >> 20) + "MB"
                    + " avail=" + (availableMemoryBytes() >> 20) + "MB");
            try { DebugSink.text("import-size", (sz == null ? "unknown" : sz[0] + "x" + sz[1])
                    + " need=" + (want >> 20) + "MB avail=" + (availableMemoryBytes() >> 20) + "MB"
                    + " native=" + importAtNativeSize); } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
        // A new photograph starts at the two entry cards, never on a page
        // whose switch belonged to the frame before it.
        CrashTrail.mark(this, "import:page");
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
        CrashTrail.mark(this, "import:canread");
        sourceUri = uri;
        // A different file means a different sensor balance; the previous
        // frame's gains must not be handed to it.
        wbUserMul = null;
        // The frame's own look, stated before any session is built: a chip
        // returns to a photograph *and* to what was done to it; a pick from ＋
        // starts with no film at all.
        if (reopeningFrame && currentFrame >= 0 && currentFrame < frames.size()) {
            applyLook(frames.get(currentFrame).look);
        } else {
            // **A reopen with no frame to reopen is a new photograph.** This
            // used to leave the global fields untouched when the index was
            // gone (every chip deleted, then ＋), so the new frame inherited
            // the previous one's print exposure and meter switch -- reported
            // as "重新导入一张还是黑灰", gone after a restart for the obvious
            // reason.
            reopeningFrame = false;
            applyLook(defaultLook());
        }
        CrashTrail.mark(this, "import:look");
        try {
            // Check if this is a RAW file by extension
            String fileName = getFileName(uri);
            CrashTrail.markNow(this, "import:name " + fileName);
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
            // **Ask before shrinking.** The header gives the size without
            // decoding anything, so the question is free -- and the answer
            // belongs to the user, not to a memory estimate.
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
            // **The old frame's planes go before the new one asks for its own.**
            // `spk_open` allocates the decoded source while the previous
            // session's pool is still resident. On a 16 GB phone a 32 MP pool
            // plus a 50 MP frame is more than the app's cgroup will hand out,
            // and it surfaces as `cannot allocate Vulkan result pixels` ->
            // E-1003 even though the frame alone would have fit. The export
            // path already trims for exactly this reason.
            try { if (engine != null) engine.trimPool(); } catch (Throwable ignored) { }
            lastRenderedBitmap = null;
            setOpticsUnlocked(false);
            dateText = null;
            setStage(0, null);
            // The white-balance half is RAW-only; tell it which file this is.
            refreshViewPanel();
            sourceBitmap = null;
            // `allocDirect` has no cleaner: dropping the field frees nothing.
            if (sourcePixels != null) { SpektraLabEngine.freeDirect(sourcePixels); sourcePixels = null; }
            // The previous file's unpacked plane is not this frame's problem.
            try { SpektraLabEngine.clearRawCache(); } catch (Throwable ignored) { }
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
            // An explicit "render smaller" from the failure sheet outranks the
            // native default: the user asked for that one.
            final int capPixels = forcedCap > 0 ? forcedCap
                    : (nativeSize ? Integer.MAX_VALUE : maxImportPixels());
            final int edgeCap = nativeSize ? Integer.MAX_VALUE : IMPORT_LONG_EDGE;
            // The cleanup above (shelf queue, session close, gc) is its own
            // stretch; without this mark a death there would still be reported
            // as `import:look` and blamed on the decoder.
            CrashTrail.mark(this, "import:decoding");
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
            // **Where the import actually is.** `import:load` covers everything
            // from the pick to the session, which is a fifty-line stretch with
            // a 200 MB bitmap and a 600 MB direct buffer in it; when the system
            // reclaims the process mid-way the trail cannot tell a bad file
            // from a decoder that handed back 50 MP it could not then convert.
            CrashTrail.mark(this, "import:decoded " + sourceBitmap.getWidth() + "x"
                    + sourceBitmap.getHeight());
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
            // **Nothing is scaled when the user asked for the original.** The
            // memory test below used to run even with `nativeSize`, so "force
            // original" still came back smaller -- the one place where the
            // suggestion was a ceiling in disguise.
            final long availBytes = availableMemoryBytes();
            if (!nativeSize) {
                if (area > capPixels) k = Math.min(k, Math.sqrt((double) capPixels / (double) area));
                if (longEdge > edgeCap) k = Math.min(k, (double) edgeCap / longEdge);
                // **No third ceiling from free RAM.** `capPixels` already came
                // from the pixel budget, and that budget is now derived from
                // total RAM rather than whatever happened to be free. Applying
                // `availMem - 128 MB` *again*, here, is what made two imports of
                // one file land on different sizes. The float buffer is malloc'd
                // and its failure has an honest message (`导入这张图需要 N MB`),
                // so the guess is not needed.
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
            // The JPEG path sets these too, so nothing has to infer a shape from
            // whatever the last raw happened to be.
            importedWidth = sourceWidth;
            importedHeight = sourceHeight;
            refreshEdgeFormats();

            // Convert a row at a time: a full int[] of 20 MP would be another
            // 80 MB on the Java heap next to the direct buffer.
            rawFilePath = null;
            // 12 bytes a pixel, outside the Java heap, and the largest single
            // allocation this app makes: 240 MB at 20 MP, 600 MB at 50. The
            // engine takes float32 only (the Metal zero-copy door is refused by
            // the Vulkan backend), so it cannot be made smaller -- but it can be
            // made *legible*: an OutOfMemoryError here is caught and explained,
            // and only a kernel reclaim leaves the trail without this mark.
            final long pixelBytes = (long) sourceWidth * sourceHeight * 3L * 4L;
            CrashTrail.mark(this, "import:pixels " + (pixelBytes >> 20) + "MB avail="
                    + (availableMemoryBytes() >> 20) + "MB");
            try { DebugSink.text("import-pixels", sourceWidth + "x" + sourceHeight
                    + " need=" + (pixelBytes >> 20) + "MB avail="
                    + (availableMemoryBytes() >> 20) + "MB"); } catch (Throwable ignored) { }
            // **malloc, not allocateDirect.** The runtime caps direct-buffer
            // memory at the heap growth limit (512 MB on the phone that
            // reported this), and a 50 MP frame is 576 MB of float32, so the
            // old call was refused with 4.3 GB free -- and the message blamed
            // the device. This one asks for memory that exists.
            try {
                sourcePixels = SpektraLabEngine.allocDirect(pixelBytes)
                        .order(ByteOrder.nativeOrder());
            } catch (Throwable oom) {
                throw new IllegalStateException("导入这张图需要 " + (pixelBytes >> 20)
                        + " MB 内存，本机现在给不出来（可用 "
                        + (availableMemoryBytes() >> 20) + " MB）。"
                        + "\n\n点「导入」再选一次，这次选「缩到建议尺寸」。", oom);
            }
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
            CrashTrail.mark(this, "import:engine");
            ensureEngine();
            CrashTrail.mark(this, "import:engine-ok");
            synchronized (renderMutex) {
                if (session != null) { session.close(); session = null; }
            }
            session = engine.openSession(sourcePixels, sourceWidth, sourceHeight, 3);
            CrashTrail.mark(this, "import:session");
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
                // **Not while a batch is exporting.** Each frame is opened only
                // to hand it to `exportImage`, which builds its own full-size
                // session; a preview pass here is a second render of the same
                // frame, and it holds `rendering` up long enough that the next
                // parameter touch answers "渲染中". The batch's order is
                // load -> export, with nothing in between.
                if (!batchExportRunning) renderImage();
            } else {
                statusText.setText("未渲染 · 选择胶片开始" + importSizeNote(sourceWidth, sourceHeight));
                if (!batchExportRunning) startPrefetch();
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
    // 8736 is a 5DS R at full size, 8660 the 50 MP phone class. The old 8000
    // cut both. This is not a memory limit -- the pixel budget is -- it is the
    // point where a decode stops being a photograph and starts being a pano.
    private static final int IMPORT_LONG_EDGE = 12000;
    /** Used when the profile cannot be read at all. */
    private static final int IMPORT_PIXEL_FALLBACK = 16490040;

    private SpektraLabEngine ensureEngine() throws Exception {
        if (engine == null) {
            vkLogFile().delete();
            // **Before the device exists** -- `vulkan_gpu.cpp` reads this once,
            // when it creates the VkDevice. "0" means off (the engine ignores a
            // zero), and writing it every time keeps a previous run's value from
            // outliving the switch that set it.
            final int simMib = debugStorageLimitMib();
            SpektraLabEngine.setEnv("SPEKTRAFILM_STORAGE_LIMIT",
                    simMib > 0 ? String.valueOf((long) simMib * 1024L * 1024L) : "0");
            // The canvas ceiling is the engine's own measured number (42 MP).
            // A tester who has lifted the frame's ceiling needs it lifted too,
            // or the rebate refuses a 51 MP frame for a reason that no longer
            // applies: the canvas bands now. Twice the frame cap, because a
            // rebate canvas is at most ~1.6x the frame.
            // **Striped film chain, on by default.** Measured on a 16.7 MP
            // frame: peak device memory 1354.5 -> 1163.8 MiB (6.8 -> 5.8 whole
            // planes) and 13088 -> 12378 ms, with the output bit-identical
            // (`6a19bb88851e5304` both ways). It is the only lever that has
            // moved the peak at all; the pool trim and its keep budget did not
            // (1144 MiB at best, 1.7%).
            SpektraLabEngine.setEnv("SPK_BAND_ROWS", "512");
            // **Where a suspended session goes.** `tmpfile()` has no writable
            // home in an Android app process, so without this every suspend is
            // refused and the preview's source -- a whole frame's plane -- stays
            // resident through an export that is already at the ceiling.
            SpektraLabEngine.setEnv("SPK_SCRATCH_DIR", getCacheDir().getAbsolutePath());
            SpektraLabEngine.setEnv("SPK_MAX_CANVAS_PIXELS",
                    debugPixelCapMp() > 0 ? String.valueOf((long) debugPixelCapMp() * 3_000_000L) : "0");
            appliedSimMib = simMib;
            // **The one call that can take the process down before any UI.**
            // `spk_engine_create` builds a VkDevice and uploads every LUT; a
            // driver that refuses it returns null (we report that), but a driver
            // that *faults* leaves nothing at all. Bracketing it is what makes
            // `engine:create` without `engine:created` a diagnosis instead of a
            // mystery.
            CrashTrail.markNow(this, "engine:create sim=" + simMib);
            engine = SpektraLabEngine.create(getResourcesDir(), vkLogFile().getAbsolutePath());
            CrashTrail.markNow(this, "engine:created " + (engine != null));
            // **The probe comes first, and nothing may stop it.** It used to sit
            // after `uploadCapabilities()`, so a throw in any of the uploads left
            // `probe` at its `parse("{}")` default -- `? · 无单缓冲上限` -- and the
            // next call skipped this whole block because `engine` was already
            // set. That is a device reporting 128 MiB letting a 14.8 MP frame
            // in, and the render dying on a binding range.
            // **The second call that has no Java frame in it.** `deviceProfile`
            // is a JNI round trip into `vkGetPhysicalDeviceProperties2`; a
            // driver that faults there leaves a trail that ends at
            // `engine:profile-in` with no `profile-out`, which is a diagnosis
            // rather than the silence that `engine:created true` followed by
            // nothing used to be.
            CrashTrail.markNow(this, "engine:profile-in");
            final String profileJson = engine.deviceProfile();
            CrashTrail.markNow(this, "engine:profile-out " + (profileJson == null ? 0 : profileJson.length()));
            probe = DeviceProbe.parse(profileJson);
            CrashTrail.markNow(this, "engine:probe " + (probe != null && probe.valid));
            try { DebugSink.text("device-profile", probe.describe() + "\n" + engine.deviceProfile()); }
            catch (Throwable ignored) { }
            // A simulated limit is a claim about a device this one is not, so
            // the canary -- which measures the *real* driver and would raise the
            // range back to 2 GiB -- is not run against it.
            if (simMib == 0) startCanaryProbe();
            try { uploadCapabilities(); } catch (Throwable ignored) { }
            CrashTrail.markNow(this, "engine:ready");
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
    /** Total physical RAM. Stable across imports; `availMem` is not. */
    private long totalMemoryBytes() {
        try {
            final android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            final android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem;
        } catch (Throwable t) {
            return 0;
        }
    }

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
        // **No fallback to `probe.maxPixelsByBinding()`.** `maxFramePixels()`
        // answers 0 on purpose -- the renderer bands every pass that would
        // otherwise bind a whole plane -- and falling back here silently put
        // the 8.95 MP ceiling back on every 128 MiB device, which is exactly
        // the number that release claimed to have removed.
        // **Total, not free.** `availMem` moves by gigabytes as other apps come
        // and go, so the same JPEG imported at 42 MP one minute and 36 MP the
        // next -- and the export followed. Total RAM is a property of the
        // phone; the reserve below is what keeps the system off the low-memory
        // killer. If a frame still does not fit, it fails with a message that
        // names the allocation instead of quietly shrinking the import.
        final long byMemory = DeviceProbe.maxPixelsByMemory(
                Math.max(availableMemoryBytes(), (long) (totalMemoryBytes() * 0.45)));
        // **No hard 42 MP ceiling.** It was measured on one 12 GB phone while a
        // canvas plane still had to fit a binding; the banded renderer and a
        // larger device both outgrew it. What is left is memory, and the user
        // gets asked before a frame is shrunk -- see `showImportBudgetDialog`.
        long px = Math.min(byBinding, byMemory);
        // The hidden override (long-press the version in 关于) replaces this
        // suggestion outright: it exists so a tester can name a size neither the
        // driver nor the free-memory estimate would pick.
        final int debugMp = debugPixelCapMp();
        if (debugMp > 0) px = (long) debugMp * 1_000_000L;
        // **A rebate does not shrink the import.** This divided the budget by
        // 1.6 whenever the edge was on, on the theory that the canvas is 1.6x
        // the frame. The canvas has been banded since 1.4.1 (memory peaks at
        // 3.70 GB for a 77 MP canvas, measured three times), and what has to
        // fit in a single binding is the *frame*, not the canvas. The only
        // thing this line did was make the same photograph import smaller
        // because a border was switched on.
        // (kept as a no-op so the call sites stay unchanged)
        return (int) Math.min(Math.max(px, 1_000_000L), Integer.MAX_VALUE);
    }

    private int maxImportPixels() {
        return maxImportPixels(overscanOn);
    }

    /**
     * The decoded frame as a bitmap for the screen.
     *
     * <p>Two things are done to it here and nowhere else: the sRGB encoding a
     * display expects (the frame itself is linear working space -- correct for
     * a file, black on a screen), and `ev` stops of lift, which is the exposure
     * the meter is about to give the film. Without the second the "原图" side of
     * every comparison sits three stops under the developed side and reads as a
     * failure rather than as a starting point.
     */
    /**
     * A frame bitmap in {@link #previewSpace}.
     *
     * <p>Assigning a `ColorSpace` is what makes the system colour-manage the
     * bitmap instead of assuming sRGB: on a P3 phone an untagged P3 buffer is
     * shown as if its numbers were sRGB, which desaturates it by exactly the
     * amount the wider gamut was supposed to add.
     */
    private Bitmap newFrameBitmap(int w, int h) {
        final Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final android.graphics.ColorSpace cs = colorSpaceFor(previewSpace);
        if (cs != null && android.os.Build.VERSION.SDK_INT >= 26) bmp.setColorSpace(cs);
        return bmp;
    }

    private static android.graphics.ColorSpace colorSpaceFor(String name) {
        if (android.os.Build.VERSION.SDK_INT < 26) return null;
        if ("Display P3".equals(name))
            return android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3);
        return null;          // sRGB is what an untagged ARGB_8888 already is
    }

    private Bitmap exportSourceBitmap(SpektraLabEngine.Session source, double ev) {
        Bitmap bitmap = null;
        try {
            // **The gain is computed from *this* frame, so the frame has to
            // exist first.**
            //
            // `nativeSetSourceDisplayEv` does not just scale by 2^ev: it samples
            // the current source into p50 and p99.5, puts the median on 18 % and
            // stops before the 99.5th clips. That sampling reads `g_result`,
            // which `exportSource` is what fills. Setting the EV *before* the
            // export therefore measured the previous frame -- or nothing -- and
            // fell back to the raw `2^ev`, which at the +10.2 EV the meter had
            // produced turned the picture into a white sheet: logged as
            // `raw-view export 2048x1363 ev=10.2 mean=254/254/253`, and reported
            // as "导入照片后整张照片爆掉".
            final int[] d = source.exportSource("live");
            if (d != null && d[0] > 0 && d[1] > 0) {
                // The phone's curve wins over the auto-level switch: it is the
                // vendor's own statement, and it already carries the contrast
                // the auto-level was imitating.
                SpektraLabEngine.setSourceToneCurve(sourceToneCurve);
                SpektraLabEngine.setSourceDisplayEv(ev, rawAutoBright);
                final int rw = d[0], rh = d[1];
                final int[] px = new int[rw * rh];
                if (source.copyArgbRange(px, 0, rh) == rw * rh) {
                    bitmap = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
                    bitmap.setPixels(px, 0, rw, 0, 0, rw, rh);
                }
            }
            try {
                // **A number for "the colour looks wrong".** Every such report
                // so far has been resolved by comparing the three channel means
                // of this exact bitmap: the 1.5.0e path showed 28/30/42 where a
                // display-encoded frame is 81/86/97, and "dark and violet" is
                // what those numbers look like. Sampling every 97th pixel keeps
                // it off the frame budget.
                String means = "";
                if (bitmap != null) {
                    final int[] sample = new int[bitmap.getWidth() * bitmap.getHeight()];
                    bitmap.getPixels(sample, 0, bitmap.getWidth(), 0, 0,
                            bitmap.getWidth(), bitmap.getHeight());
                    long sr = 0, sg = 0, sb = 0;
                    int n = 0;
                    for (int i = 0; i < sample.length; i += 97) {
                        sr += (sample[i] >> 16) & 0xFF;
                        sg += (sample[i] >> 8) & 0xFF;
                        sb += sample[i] & 0xFF;
                        ++n;
                    }
                    if (n > 0) means = " mean=" + (sr / n) + "/" + (sg / n) + "/" + (sb / n);
                }
                DebugSink.text("raw-view", bitmap == null
                        ? "export: empty ev=" + ev
                        : "export " + bitmap.getWidth() + "x" + bitmap.getHeight()
                          + " ev=" + ev + means);
            } catch (Throwable ignored) { }
        } catch (Throwable t) {
            try { DebugSink.text("raw-view", "export failed: " + t); } catch (Throwable ignored) { }
        } finally {
            try { SpektraLabEngine.setSourceDisplayEv(0, false); } catch (Throwable ignored) { }
            try { SpektraLabEngine.setSourceToneCurve(null); } catch (Throwable ignored) { }
        }
        return bitmap;
    }

    /**
     * Re-reads the original with the current display rule.
     *
     * The canvas is the only thing this touches: `exportSource` hands back the
     * engine's working numbers, and `nativeCopyArgbRange` is what applies the
     * gain and the transfer function. No render, no parameter, no export.
     */
    private void refreshOriginalView() {
        if (!uiReady || session == null) return;
        new Thread(() -> {
            Bitmap b = null;
            synchronized (renderMutex) {
                final SpektraLabEngine.Session s = session;
                if (s != null) b = exportSourceBitmap(s, 0.0);
            }
            if (b == null) return;
            final Bitmap shown = b;
            runOnUiThread(() -> {
                if (!uiReady || compare == null) return;
                // The frame has to be kept even while the developed side is on
                // screen: the switch is a property of the canvas, and flipping
                // it must not leave a stale "before" behind the film.
                beforeBitmap = shown;
                compare.setBefore(shown);
                if (showingOriginal) setStage(1, null);
            });
        }, "raw-view-rule").start();
    }

    private void loadRawImage(Uri uri) {
        // **A different import is a different frame.** The cache keys on path +
        // mtime, and every import is copied to `files/import/<same name>`; two
        // copies inside one second share an mtime, so the second import could
        // be handed the first one's decoded pixels. Clearing here costs one
        // unpack and buys the guarantee that the pixels belong to this file.
        try { SpektraLabEngine.clearRawCache(); } catch (Throwable ignored) { }
        CrashTrail.mark(this, "import:raw " + uri);
        try {
            String filePath = getFilePathFromUri(uri);
            rawFilePath = filePath;
            // **The phone's own idea of how this raw should look.** OnePlus and
            // OPPO ship a 128/140-point `ProfileToneCurve` plus a
            // `BaselineExposure`; LibRaw parses neither, which is the whole of
            // "our canvas is flat and Lightroom's is not". Read once here, a
            // 6 KB seek, and handed to the canvas only.
            sourceToneCurve = SpektraLabEngine.sourceDisplayProfile(filePath);
            final int curvePts = sourceToneCurve == null ? 0 : (sourceToneCurve.length - 1) / 2;
            DebugSink.text("raw-view", "display profile: "
                    + (sourceToneCurve == null ? "none"
                       : curvePts + " points, baseline "
                         + String.format(java.util.Locale.US, "%.2f", sourceToneCurve[0]) + " EV"));
            // **Which half of the import we got to.** Until now the RAW path
            // filed one mark (`import:raw`) and then nothing until the decode
            // had already finished, so a report of the form "crashed importing
            // a RAW" could not be narrowed past "somewhere in the next four
            // seconds" -- the file copy, `open_file`, the decode, `spk_open`,
            // the meter or the source view. The JPEG path has had these marks
            // for a while; this one had not.
            CrashTrail.markNow(this, "import:path " + (filePath == null ? "(null)" : "ok"));
            if (filePath == null) {
                showInAppToast("无法获取RAW文件路径");
                return;
            }

            // **Ask before the decode.** `open_file` reads the container header
            // in milliseconds; the decode that follows commits 12 bytes per
            // pixel before anyone is consulted, and at 50 MP that is 600 MB. A
            // sheet that appears after that memory is spent is a post-mortem,
            // not a choice.
            if (!suppressBudgetDialog && !importAtNativeSize && pendingCapPixels <= 0) {
                final int[] dims = SpektraLabEngine.rawDimensions(filePath);
                if (dims != null && dims[0] > 0 && dims[1] > 0) {
                    final long area = (long) dims[0] * (long) dims[1];
                    final int cap = maxImportPixels();
                    if (area > cap || Math.max(dims[0], dims[1]) > IMPORT_LONG_EDGE) {
                        statusText.setText("选择导入尺寸");
                        showRawBudgetDialog(uri, dims[0], dims[1], cap);
                        return;
                    }
                }
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
                    Probe.log("import", "pre avail=" + Probe.mb(availableMemoryBytes())
                            + " cap=" + maxImportPixels() + " native=" + importAtNativeSize
                            + " pool=" + Probe.pool(engine));
                    Probe.step(this, "import:pre");
                    // **Same trim as the JPEG path.** A 50 MP frame needs
                    // ~76 B/px of planes (3.8 GB); the previous session's pool
                    // is freed here rather than left for the allocator to run
                    // out against.
                    try { if (engine != null) engine.trimPool(); } catch (Throwable ignored) { }
                    Probe.log("import", "trimmed pool=" + Probe.pool(engine));
                    
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
                    importCapPixels = nativeSize ? 0 : maxImportPixels();
                    // **The frame's own balance, if it had one.** A frame
                    // reopened from the strip restores `wbUserMul` through
                    // `applyLook`; without passing it here the reopen decodes
                    // as-shot while the sliders still show the saved Kelvin.
                    final float[] wbMul = wbUserMul;
                    CrashTrail.markNow(this, "import:opening cap=" + importCapPixels);
                    session = wbMul != null
                            ? engine.openRawSessionTuned(filePath, params,
                                    importCapPixels, nativeSize ? 0 : IMPORT_LONG_EDGE, wbMul)
                            : engine.openRawSession(filePath, params,
                                    importCapPixels, nativeSize ? 0 : IMPORT_LONG_EDGE);
                    Probe.log("import", "opened=" + (session != null)
                            + " cap=" + importCapPixels
                            + " edge=" + (nativeSize ? 0 : IMPORT_LONG_EDGE)
                            + " mul=" + Probe.mul(wbMul)
                            + " pool=" + Probe.pool(engine));
                    lastImportError = null;
                    {
                        // The size the engine actually opened, which is not
                        // always the one `rawDimensions` promised: `half_size`
                        // and the budget can both change it.
                        final int[] sd = session == null ? null : session.getDimensions();
                        CrashTrail.markNow(this, "import:opened "
                                + (sd == null || sd.length < 2 ? "none" : sd[0] + "x" + sd[1]));
                    }
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

                    // **Meter before the source view.** `spk_export_source`
                    // materialises the live tier, and a meter taken after it
                    // has come back -11 EV on a frame whose own export read a
                    // perfectly normal mean -- the second RAW opened from a
                    // batch metered black, rendered black, and ignored every
                    // metering mode. Measuring first makes the `solve` below a
                    // cache hit (`meter_key` does not change in between).
                    try { session.solve("exposure"); } catch (Throwable ignored) { }
                    // **The raw itself**, which is the "before" side of every
                    // comparison and the only honest thing to show on import:
                    // the decoded frame in working space, packed with no
                    // transfer function, so a display reads it the way it reads
                    // a raw -- flat, and dark. The camera's own JPEG is **not**
                    // a stand-in for it: a preview is the camera's rendering,
                    // not this frame, and showing it as "原图" is the same lie
                    // as importing it would be. No frame, no picture.
                    Bitmap rawView = exportSourceBitmap(session, 0.0);
                    final Bitmap preview = rawView;
                    CrashTrail.markNow(this, "import:source " + (preview == null ? "none"
                            : preview.getWidth() + "x" + preview.getHeight()));
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
                    String solvedJson = null;
                    try {
                        solvedJson = session.solve("both");
                        DebugSink.text("solve", solvedJson == null ? "(null)" : solvedJson);
                        CrashTrail.markNow(this, "import:print " + (solvedJson == null ? "none" : "ok"));
                    } catch (Throwable se) {
                        DebugSink.text("solve-failed", String.valueOf(se));
                    }
                    // **The meter's EV, applied to what the screen shows.** The
                    // developed side gets it; leaving the decoded side at 0 EV
                    // is what made a night frame look like a black rectangle
                    // next to a correctly exposed print.
                    if (solvedJson != null) {
                        // The applied value, for the export. See `meteredEv`.
                        try {
                            final org.json.JSONObject root = new org.json.JSONObject(solvedJson);
                            final org.json.JSONObject sp = root.optJSONObject("solved_params");
                            meteredEv = sp == null ? 0.0 : sp.optDouble("exposure_compensation_ev", 0.0);
                            Probe.log("meter", "applied ev=" + meteredEv);
                        } catch (Throwable ignored) { }
                    }
                    if (hasPreview && solvedJson != null) {
                        double solvedEv = 0.0;
                        try {
                            solvedEv = new org.json.JSONObject(solvedJson)
                                    .getJSONObject("exposure_ev_by_method")
                                    .optDouble("balanced", 0.0);
                        } catch (Throwable ignored) { }
                        // **Shown a little under the meter, on purpose.** At the
                        // full metered EV the decoded side reads brighter than
                        // the developed one, and the film's own contrast stops
                        // showing. 85 % is the same exposure a "before" frame is
                        // expected to have.
                        solvedEv *= SOURCE_VIEW_EV_SCALE;
                        if (Math.abs(solvedEv) > 0.02) {
                            final Bitmap lifted = exportSourceBitmap(session, solvedEv);
                            if (lifted != null) {
                                final double shownEv = solvedEv;
                                runOnUiThread(() -> {
                                    beforeBitmap = lifted;
                                    if (compare != null) compare.setBefore(lifted);
                                    if (currentFrame >= 0 && currentFrame < frames.size()) {
                                        final Frame frame = frames.get(currentFrame);
                                        try {
                                            final int tw = Math.max(1, dp(42));
                                            final int th = Math.max(1, Math.round(
                                                    tw * lifted.getHeight() / (float) lifted.getWidth()));
                                            frame.thumb = Bitmap.createScaledBitmap(lifted, tw, th, true);
                                            persistThumb(frame);
                                        } catch (Throwable ignored) { }
                                        rebuildFrameChips();
                                    }
                                    try {
                                        DebugSink.text("raw-view", "display ev=" + shownEv);
                                    } catch (Throwable ignored) { }
                                });
                            }
                        }
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
                            // Same as the JPEG path: a batch opens this frame
                            // for the export, not for a preview pass.
                            if (!batchExportRunning) renderImage();
                        } else {
                            statusText.setText("未渲染 · 选择胶片开始");
                            if (!batchExportRunning) startPrefetch();
                        }
                    });

                    // Get dimensions from session
                    int[] dims = session == null ? null : session.getDimensions();
                    if (dims == null || dims.length < 2 || dims[0] <= 0 || dims[1] <= 0) {
                        // **A session that will not say how big it is failed
                        // quietly.** Seen while switching back and forth between
                        // two photographs: the previous import's close raced
                        // this one's open, and the handle that came back had no
                        // size. One clean reopen is worth trying before asking
                        // the user to start again -- and if it fails the same
                        // way, that is a decode problem and the message below is
                        // the honest one.
                        try { if (session != null) session.close(); } catch (Throwable ignored) { }
                        session = wbMul != null
                                ? engine.openRawSessionTuned(filePath, params,
                                        importCapPixels, nativeSize ? 0 : IMPORT_LONG_EDGE, wbMul)
                                : engine.openRawSession(filePath, params,
                                        importCapPixels, nativeSize ? 0 : IMPORT_LONG_EDGE);
                        dims = session == null ? null : session.getDimensions();
                    }
                    if (dims == null || dims.length < 2 || dims[0] <= 0 || dims[1] <= 0) {
                        // A zero here used to travel silently: the canvas laid
                        // itself out for nothing, every later size was wrong,
                        // and the reason was never on screen.
                        throw new IllegalStateException(
                                "session reported no size: " + java.util.Arrays.toString(dims));
                    }
                    importedWidth = dims[0];
                    importedHeight = dims[1];
                    int w = dims[0];
                    int h = dims[1];
                    sourceWidth = w;
                    sourceHeight = h;
                    try { DebugSink.text("raw-dims", "w=" + w + " h=" + h); } catch (Throwable ignored) {}

                    // **The session's own size, not `rawSourceSize()`.** That
                    // legacy query answers {0,0} on purpose -- its comment says
                    // callers must ask the session -- and reading it put
                    // "RAW 0×0 · 0.0 MP" on the status bar.
                    final boolean capped = importCapPixels > 0;
                    try {
                        DebugSink.text("raw-cap", (capped ? "capped at " + importCapPixels : "native")
                                + " -> session " + w + "x" + h);
                    } catch (Throwable ignored) {}

                    runOnUiThread(() -> {
                        refreshEdgeFormats();
                        setStep(1);
                        // **No sheet.** The raw arrives at the size it was
                        // imported at; the suggestion is a line in the status
                        // bar, not a gate.
                        statusText.setText("RAW " + w + "×" + h
                                + " · " + megapixels(w, h) + " MP"
                                + importSizeNote(w, h));
                        refreshViewPanel();
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
        showTextDialog(title, body, true);
    }

    /**
     * Shared body for the error dialog and for the answer to "导出最近的步骤".
     *
     * <p>{@code report} decides whether this dialog is itself a diagnostic
     * event: a failure is (once), a confirmation of a report the user just
     * asked for is not.
     */
    private void showTextDialog(String title, String body, boolean report) {
        // **A batch must not stop at the first bad frame.** One dialog per
        // failure would hide the queue behind a stack of sheets; the line is
        // recorded, the queue moves on, and the summary at the end counts it.
        if (batchExportRunning) {
            try { DebugSink.text("batch-error", title + " | " + body); }
            catch (Throwable ignored) { }
            return;
        }
        final String shownTitle = (title == null || title.isEmpty()) ? "错误" : title;
        final String shownBody = (body == null || body.isEmpty()) ? "[E-0000] 未知错误" : body;
        // **One row per failure.** The ring is already in memory; sending it
        // here is what makes an error report readable without the user having
        // to copy anything, and it costs one write instead of sixty-four.
        if (report) reportFailure(shownTitle, shownBody);
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
            TextView advice = label("请复制下面的完整信息，方便定位问题。记录号 " + DebugSink.REF, 10, UI_MUTED, tfHan);
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
                    // **The ref goes first.** It is the only line that leads
                    // back to the D1 rows; a screenshot of the dialog is not
                    // always possible, and a pasted body without it is a
                    // report with no address.
                    final String text = "记录号 " + DebugSink.REF
                            + "\n\n" + shownTitle + "\n\n" + shownBody
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
            // A provider can answer a `content://` URI whose path is null; the
            // crash then lands in `lastIndexOf` and reads as an import failure
            // with no file at all.
            if (result == null) return null;
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
                // **`filesDir`, not `cacheDir`.** The cache directory is the one
                // place the system is free to delete from under us, and the path
                // this returns is kept in `rawFilePath` and re-read by every full
                // export. A copy cut short reports `RAW unpack: Input/output
                // error (-100009)`, which reads like a corrupt file -- and the
                // file is fine. `filesDir/import` is the directory `privateCopy`
                // already uses for the same reason.
                java.io.File dir = new java.io.File(getFilesDir(), "import");
                if (!dir.exists() && !dir.mkdirs()) dir = getFilesDir();
                java.io.File tempFile = new java.io.File(dir, fileName);
                // What the provider says the size is, so a short copy is caught
                // here rather than in libraw's unpack().
                long expected = -1;
                try (android.database.Cursor c = getContentResolver().query(uri,
                        new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
                    if (c != null && c.moveToFirst()) expected = c.getLong(0);
                } catch (Throwable ignored) { }
                try (InputStream in = getContentResolver().openInputStream(uri);
                     java.io.OutputStream out = new java.io.FileOutputStream(tempFile)) {
                    byte[] buf = new byte[64 * 1024];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }
                if (expected > 0 && tempFile.length() != expected) {
                    try { DebugSink.text("import", "copy short: " + tempFile.length()
                            + " of " + expected + " for " + fileName); } catch (Throwable ignored) { }
                    tempFile.delete();
                    return null;
                }
                try { DebugSink.text("import", "copied " + tempFile.length() + " -> "
                        + tempFile.getAbsolutePath()); } catch (Throwable ignored) { }
                return tempFile.getAbsolutePath();
            } catch (Exception e) {
                try { DebugSink.text("import", "copy failed: " + e); } catch (Throwable ignored) { }
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
            // **Never tell the user to bring a smaller picture.** That turns our
            // own allocation into their composition problem, and the frame that
            // failed here is usually one the engine can render perfectly -- the
            // 576 MB import buffer was refused by a ledger, not by the device.
            // What is worth saying is which way out exists and that the file
            // itself is wanted if it keeps happening.
            return "[E-1003] 这张图解码时内存不够了。先关掉其它应用再试一次；"
                    + "如果反复出现，把这张图的原始文件发给我，我看能不能在解码这一步省下内存";
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
        // **A delta is never sent from the UI thread.**
        //
        // `spk_set_params` takes `session->lock`, and `render_tier` holds that
        // lock for the entire render. Releasing a slider mid-render therefore
        // blocked the main thread for the rest of the render, then rebuilt the
        // whole pipeline on the UI thread at the exact instant the render
        // finished -- and `renderImage` queued a second rebuild right after it
        // (`pendingRender`), so two full pipeline rebuilds landed back to back.
        // That is "渲染期间不能进行光学调整，否则容易出现闪退" on Adreno 730.
        //
        // Every caller below is followed by `renderImage()`, and its render
        // thread sends this same delta -- with the film and the paper, under
        // the lock -- once, immediately before `renderBegin`. So this method is
        // now a log line: the value belongs to the next render, and the next
        // render is the only thing allowed to say so.
        try {
            DebugSink.text("params", "deferred render=" + rendering + " film=" + filmStock
                    + " print=" + printStock + " overscan=" + overscanOn + " date=" + dateOn
                    + " fmt=" + filmFormatMm);
        } catch (Throwable ignored) { }
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
        applyParamsTo(session, stock, paper, longEdge, previewSpace);
    }

    // ── undo ──────────────────────────────────────────────────────

    /**
     * Take a snapshot once the edit settles.
     *
     * <p>Debounced, because a slider fires `renderImage` on every release and
     * a burst of them would fill the stack with near-identical states. The
     * dedupe on the serialised Look means a render that changed nothing costs
     * nothing, which is what makes "one entry per real edit" hold.
     */
    private void scheduleUndo() {
        undoHandler.removeCallbacks(undoPush);
        undoHandler.postDelayed(undoPush, 600);
    }

    /** A render that failed must not enter the undo stack: replaying it would
     *  fail again, which is "undo brings the error back". */
    private void cancelUndo() {
        undoHandler.removeCallbacks(undoPush);
    }

    private void pushUndoNow() {
        if (session == null || filmStock == null) return;
        final String now;
        try { now = lookToJson(captureLook()).toString(); }
        catch (Throwable t) { return; }
        if (now.equals(undoLast)) return;
        undoLast = now;
        undoStack.push(now);
        while (undoStack.size() > UNDO_LIMIT) undoStack.removeLast();
        refreshUndoPill();
    }

    /**
     * Back one edit.
     *
     * <p>The stack holds the state *after* each edit, so undoing is: drop the
     * top (where we are), restore the new top. The first entry is the import,
     * which is why the button hides once `size() <= 1` -- there is nothing
     * before the import to go back to.
     */
    private void undoStep() {
        undoHandler.removeCallbacks(undoPush);
        if (undoStack.size() <= 1) {
            showInAppToast("已经回到最初的编辑");
            return;
        }
        undoStack.pop();
        final String previous = undoStack.peek();
        if (previous == null) return;
        try {
            applyLook(lookFromJson(new org.json.JSONObject(previous)));
        } catch (Throwable ignored) { }
        undoLast = previous;
        refreshUndoPill();
        applyParams();
        if (uiReady && session != null) {
            showingOriginal = false;
            setOriginalVisual(false);
            renderImage();
        }
    }

    /** A new photograph starts its own history. */
    private void resetUndo() {
        undoHandler.removeCallbacks(undoPush);
        undoStack.clear();
        undoLast = null;
        refreshUndoPill();
    }

    private void refreshUndoPill() {
        if (undoPill == null) return;
        undoPill.setVisibility(undoStack.size() > 1 ? View.VISIBLE : View.GONE);
    }

    /** Build the one Look delta and send it to the named session. Export uses
     * this directly; it never borrows the global preview-session field. */
    // ── the delta ──────────────────────────────────────────────────

    /** A JSON string literal. `q(null)` is `""`, not `"null"`. */
    private static String q(String v) {
        if (v == null) return "\"\"";
        final StringBuilder o = new StringBuilder(v.length() + 2).append('"');
        for (int i = 0; i < v.length(); ++i) {
            final char c = v.charAt(i);
            if (c == '"' || c == '\\') o.append('\\').append(c);
            else if (c < 0x20) o.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
            else o.append(c);
        }
        return o.append('"').toString();
    }

    private static String b(boolean v) { return v ? "true" : "false"; }

    private static String f(String fmt, double v) {
        return String.format(java.util.Locale.US, fmt, v);
    }

    /**
     * Only the keys whose value differs from what this session already carries.
     *
     * <p>**Why this exists.** The delta used to name all seventy-odd fields
     * every time. `spk_set_params` treats the *presence* of `film_stock` as a
     * stock change, so every slider tick rebuilt the whole parameter tree
     * (30–110 ms), and -- the part that mattered more -- a shoot-layer field in
     * the delta makes the engine drop the cached negative. With the whole set
     * going out on every tick, `spk_reprint` could never skip the film side: it
     * always found an empty cache and spent 200 ms redeveloping a frame the
     * user was only nudging the print exposure of.
     *
     * <p>The engine carries every field a delta leaves out (a stock change reads
     * the session's own tree back and replays it), so sending less says exactly
     * the same thing. What is *not* allowed to drift is this map: it is written
     * only by the thread that draws, under `renderMutex`, and a different
     * session object clears it.
     */
    private String deltaFor(SpektraLabEngine.Session target,
                            java.util.LinkedHashMap<String, String> kv) {
        if (target != deltaSession) {
            deltaSent.clear();
            deltaSession = target;
        }
        final StringBuilder out = new StringBuilder(768);
        int n = 0;
        for (java.util.Map.Entry<String, String> e : kv.entrySet()) {
            if (e.getValue().equals(deltaSent.get(e.getKey()))) continue;
            out.append(n++ == 0 ? "{" : ",").append('"').append(e.getKey()).append("\":")
               .append(e.getValue());
            deltaSent.put(e.getKey(), e.getValue());
        }
        if (n == 0) return null;
        return out.append('}').toString();
    }

    /** Frame `i`'s URI, or null. The identity behind "whose EV is this". */
    private String frameUri(int i) {
        try {
            if (i < 0 || i >= frames.size()) return null;
            final Frame f = frames.get(i);
            return f == null || f.uri == null ? null : f.uri.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when {@link #lastAppliedEv} was measured on the frame in front of us. */
    private boolean appliedEvBelongsToCurrentFrame() {
        if (!lastAppliedEvValid) return false;
        final String here = frameUri(currentFrame);
        return here != null && here.equals(lastAppliedEvUri);
    }

    /** `"auto_exposure_ev":0.0755` out of a progress document, for a log line. */
    private static String appliedEvText(String progressJson) {
        if (progressJson == null) return "(no progress json)";
        final int at = progressJson.indexOf("\"auto_exposure_ev\"");
        if (at < 0) return "(none)";
        return progressJson.substring(at, Math.min(progressJson.length(), at + 40));
    }

    /**
     * Records the EV a render applied, from the progress document read **right
     * after** that render.
     */
    private void rememberAppliedEv(String progressJson, String tier) {
        String text = appliedEvText(progressJson);
        if (progressJson != null) {
            final int at = progressJson.indexOf("\"auto_exposure_ev\":");
            if (at >= 0) {
                int i = at + "\"auto_exposure_ev\":".length();
                while (i < progressJson.length() && progressJson.charAt(i) == ' ') ++i;
                final int start = i;
                while (i < progressJson.length()) {
                    final char c = progressJson.charAt(i);
                    if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.'
                            || c == 'e' || c == 'E') ++i;
                    else break;
                }
                if (i > start) {
                    try {
                        final double v = Double.parseDouble(progressJson.substring(start, i));
                        if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                            lastAppliedEv = v;
                            lastAppliedEvValid = true;
                            lastAppliedEvUri = frameUri(currentFrame);
                        }
                    } catch (Throwable ignored) { }
                }
            }
        }
        // One line per *change*, not per frame: the live loop draws at 11 Hz and
        // a steady EV is the normal case.
        if (lastAppliedEv != loggedEv) {
            loggedEv = lastAppliedEv;
            try {
                DebugSink.text("exposure", "tier=" + tier + " " + text
                        + " -> pinned " + lastAppliedEv + (lastAppliedEvValid ? "" : " (none)"));
            } catch (Throwable ignored) { }
        }
    }

    private void applyParamsTo(SpektraLabEngine.Session target, String stock, String paper,
                               int longEdge, String space) {
        applyParamsTo(target, stock, paper, longEdge, space, false);
    }

    /**
     * @param force send every key, ignoring what this session was already told.
     *
     * <p>**An export must force.** The delta is diffed against `deltaSent`, and
     * when the export runs on the session the canvas already used, every value
     * is "unchanged" -- so nothing was sent at all (`export-params:
     * ... pex=0.41125 (no delta sent)`), and the engine kept whatever that
     * session was opened with. The owner's report: the enlarger exposure was
     * pulled to the end of its travel and every exported file came back as if
     * it had never been touched. The engine is fine -- `print_exposure`
     * 0.25/1.00/4.00 renders 188.65/78.29/15.29 on the host.
     */
    private void applyParamsTo(SpektraLabEngine.Session target, String stock, String paper,
                               int longEdge, String space, boolean force) {
        if (force) {
            // Cold bookkeeping, not a behaviour change: with an empty ledger
            // every key differs and the loop below sends all of them.
            deltaSession = null;
            deltaSent.clear();
        }
        if (target == null || stock == null) return;
        // A null paper used to return here, which meant "sent nothing at all"
        // rather than "sent everything but the paper".
        if (paper == null) paper = "kodak_portra_endura";
        pushViewAdjust();
        pushViewCurves();
        final java.util.LinkedHashMap<String, String> kv = new java.util.LinkedHashMap<>();
        kv.put("film_stock", q(stock));
        kv.put("print_stock", q(paper));
        kv.put("input_color_space", q(isRawFile ? "ProPhoto RGB" : "sRGB"));
        kv.put("input_cctf_decoding", b(!isRawFile));
        // **The honour system is the user's.** A JPEG used to be forced off
        // because the engine's default meter is an arithmetic mean and metering
        // a camera JPEG twice washed it grey. That is a reason not to *default*
        // it on, not a reason to refuse: a frame someone deliberately asks to
        // meter is their call, and `protect_highlights` on a JPEG is a
        // reasonable request. The default for a JPEG is still off.
        final String meter = sanitizeMeter(meterMethod);
        // **An export keeps the canvas' own meter, and that is the whole
        // point.** `auto_exposure` is applied by `node_auto_exposure` in
        // `film_prefix` -- *before* `scene_latitude` rolls the highlights off --
        // while `exposure_compensation_ev` is applied in `filming.expose`,
        // after it. The same number of stops therefore produces two different
        // pictures (host, 20 MP frame: 21410 vs 10061 mean16, p05 7271 vs 552).
        // Turning the meter off for the export and moving its EV into
        // `exposure_compensation_ev` is what made every file look as if the
        // enlarger exposure had never been touched. The export is the picture
        // on screen, so it is given the same flag *and* the same EV.
        kv.put("auto_exposure", b(!meter.isEmpty()));
        // **`protect_highlights`, not `balanced`, while the meter is on.**
        // `balanced` aimed a lamp-in-a-dark-room at mid-grey and put 19 % of the
        // frame past the paper's white; `protect_highlights` is the same meter
        // with the highlight end held, two stops lower on that scene (+2.41 vs
        // +5.41). The default (meter off) keeps `balanced` so the value is
        // identical to what the reference sends.
        kv.put("auto_exposure_method", q(meter.isEmpty() ? "balanced" : meter));
        kv.put("scan_film", b(isPositiveStock(stock)));
        kv.put("output_color_space", q(space == null ? "sRGB" : space));
        kv.put("preview_long_edge", Integer.toString(longEdge));
        kv.put("print_exposure", f("%.2f", printExposure));
        kv.put("grain_amount", f("%.3f", wireGrain(stock)));
        kv.put("grain_sublayers_active", b(!isMonochromeStock(stock)));
        kv.put("halation_amount", f("%.3f", halationAmount));
        kv.put("halation_boost_ev", f("%.2f", halationBoost));
        kv.put("halation_scatter_amount", f("%.3f", halationScatter));
        kv.put("halation_scatter_scale", f("%.3f", 1.0f + 11.0f * scatterRadius));
        kv.put("halation_additive", b(halationAmount > 1.01f));
        kv.put("halation_active", b(halationOn > 0.5f));
        kv.put("antihalation_removed", b(antihalationRemoved > 0.5f));
        kv.put("grain_active", b(grainOn > 0.5f));
        kv.put("dir_couplers_active", b(true));
        kv.put("dir_couplers_amount", f("%.3f", couplersOn));
        kv.put("scanner_white_correction", b(scannerWhite > 0.5f));
        // The five that used to have no sender. Ranges are the schema's:
        // scanner.lens_blur 0..20, halation.headroom_ev 0..8,
        // film_render.density_curve_gamma 0.2..4.
        kv.put("glare_active", b(glareActive > 0.5f));
        kv.put("scanner_lens_blur", f("%.2f", scannerLensBlur));
        kv.put("halation_headroom_ev", f("%.2f", halationHeadroom));
        kv.put("density_curve_gamma", f("%.4f", densityGamma));
        kv.put("digital_intermediate", b(digitalIntermediate));
        kv.put("output_cctf_encoding", b(outputCctf));
        kv.put("hue_correction", f("%.3f", wireHueCorrection(stock)));
        kv.put("scanner_black_correction", b(scannerBlack > 0.5f));
        kv.put("extended_dynamic_range", b(edrOn > 0.5f));
        kv.put("camera_filter", q(cameraFilter));
        kv.put("scene_latitude_active", b(sceneLatitudeActive()));
        kv.put("scene_latitude_norm", q(sceneNorm));
        kv.put("scene_latitude_highlight_knee", f("%.2f", wireHighlightKnee()));
        kv.put("scene_latitude_highlight_room", f("%.2f", wireHighlightRoom()));
        kv.put("scene_latitude_shadow_knee", f("%.2f", wireShadowKnee()));
        kv.put("scene_latitude_shadow_room", f("%.2f", wireShadowRoom()));
        kv.put("scene_latitude_rolloff", f("%.2f", shadowRolloff));
        kv.put("scene_latitude_max_lift", f("%.2f", maxLift));
        kv.put("glare_amount", f("%.3f", glareAmount));
        kv.put("film_format_mm", f("%.1f", wireFormatMm()));
        kv.put("lens_blur_um", f("%.1f", lensBlur));
        // **A zero-width crop is not a small crop, it is a blank frame.** The
        // renderer honours the rectangle it is given, so `crop_w == 0` (or a
        // rectangle pushed outside the frame) comes back as one flat colour --
        // every film card grey, the canvas grey, and no error anywhere. Clamp
        // to something that can actually contain pixels.
        final float cropW = Math.max(0.01f, Math.min(1f, geometryCropW));
        final float cropH = Math.max(0.01f, Math.min(1f, geometryCropH));
        final float cropX = Math.max(0f, Math.min(1f - cropW, geometryCropX));
        final float cropY = Math.max(0f, Math.min(1f - cropH, geometryCropY));
        kv.put("geometry_crop_x", f("%.6f", cropX));
        kv.put("geometry_crop_y", f("%.6f", geometryCropY));
        kv.put("geometry_crop_w", f("%.6f", cropW));
        kv.put("geometry_crop_h", f("%.6f", cropH));
        kv.put("geometry_rotation_deg", f("%.3f", geometryRotation));
        kv.put("geometry_quarter_turns", Integer.toString(geometryTurns));
        kv.put("geometry_flip_h", b(geometryFlipH));
        kv.put("geometry_flip_v", b(geometryFlipV));
        kv.put("m_filter_shift", f("%.3f", mFilter));
        kv.put("y_filter_shift", f("%.3f", yFilter));
        kv.put("filter_shift_scale", f("%.1f", filterScale));
        // **0..1 on the slider, 0..0.04 in the engine.** Measured on a real
        // frame: 0.05 already takes the top 5 % down by 24 %, and 0.5 by 65 %
        // -- the physics is not linear in the way the number suggested, so the
        // slider keeps only the usable end.
        kv.put("preflash_exposure", f("%.4f", preflash * 0.04f));
        kv.put("overscan_active", b(overscanOn));
        kv.put("overscan_format", q(overscanFormat));
        kv.put("overscan_edge_text", q(edgeTextOn ? edgeTextFor(stock) : ""));
        kv.put("overscan_fog", f("%.2f", edgeFog));
        kv.put("overscan_leaks", f("%.2f", edgeLeaks));
        kv.put("overscan_frame_number", f("%.0f", frameNumber));
        kv.put("overscan_mode", q(edgeMode));
        kv.put("overscan_gate", q(edgeGate));
        kv.put("overscan_holes", q(edgeHoles));
        kv.put("overscan_carrier", q(edgeCarrier));
        kv.put("overscan_f_number", f("%.0f", edgeFNumber));
        kv.put("overscan_camera_seed", Integer.toString(cameraSeed));
        kv.put("overscan_frame_seed", Integer.toString(frameSeed));
        kv.put("date_imprint_active", b(dateOn));
        kv.put("date_imprint_text", q(dateOn ? imprintDate() : ""));
        kv.put("date_imprint_style", q(dateStyle));
        kv.put("date_imprint_text_b", q(dateTextB));
        kv.put("date_imprint_data_text", q(dataText));
        kv.put("date_imprint_placement", q(datePlacement));
        kv.put("date_imprint_corner", q(dateCorner));
        kv.put("date_imprint_inset_x", f("%.2f", dateInsetX));
        kv.put("date_imprint_inset_y", f("%.2f", dateInsetY));
        kv.put("date_imprint_size", f("%.2f", dateSize));
        kv.put("date_imprint_ev", f("%.1f", dateEv));
        // The user's own offset only. The meter's stops travel through the pin
        // (`pinAutoExposureEv`), which lands in the same node the canvas used.
        kv.put("exposure_compensation_ev", f("%.2f", cameraEv));

        final String delta = deltaFor(target, kv);
        if (delta == null) return;
        try {
            DebugSink.text("params", "draw film=" + stock + " print=" + paper
                    + " render=" + rendering + " long=" + longEdge
                    + " keys=" + delta.split(",").length + " [" + keyNames(delta) + "]");
        } catch (Throwable ignored) { }
        // **A JSON that does not parse is a build error, not a user error.**
        try {
            new org.json.JSONObject(delta);
        } catch (Throwable bad) {
            try { DebugSink.textNow("params-bad", bad + "\n" + delta); }
            catch (Throwable ignored) { }
        }
        try {
            target.setParams(delta);
        } catch (Throwable t) {
            // The engine may have applied none, some or all of it. Start over
            // from a full delta next time rather than assume.
            deltaSent.clear();
            deltaSession = null;
            throw t;
        }
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
        // **Retired.** It wrote the engine from whichever thread imported the
        // file, racing the render thread; and the delta bookkeeping is now a
        // map shared between renders, so "who may write it" has exactly one
        // answer. Every field it used to send (colour space, cctf decoding,
        // meter) is part of the full delta `renderImage` builds, and a new
        // session starts with an empty bookkeeping map -- so the first render
        // states all of it anyway.
    }

    /**
     * A mark every three seconds, from now until the thread is interrupted.
     *
     * A blocking native call is a black box: `session.renderBegin` can take
     * forty seconds on a 50 MP rebate canvas, and a process that dies inside it
     * leaves exactly one line -- `render:call` -- whether it died at second one
     * or second thirty-nine. The ticker turns "somewhere in there" into "after
     * 27 seconds", which is a different investigation.
     *
     * It writes through {@link CrashTrail#mark}, so a tick costs a commit only
     * when the throttle has expired; the thread is a daemon and is interrupted
     * in a `finally`, so it can never outlive the call it is watching.
     */
    private Thread startTick(final String tag, final SpektraLabEngine.Session s) {
        final Thread t = new Thread(() -> {
            int n = 0;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(3000);
                    if (Thread.currentThread().isInterrupted()) return;
                    n += 3;
                    // **Which node, not just how long.** The engine publishes a
                    // 96-byte label from the render thread through a seqlock;
                    // this reads it without taking the render lock, so a render
                    // that has been inside `filming.expose.halation` for twenty
                    // seconds says so in the trail instead of just "20s".
                    String stage = null;
                    try { stage = s == null ? null : s.stageSnapshot(); } catch (Throwable ignored) { }
                    CrashTrail.mark(this, tag + ":tick " + n + "s"
                            + (stage == null || stage.isEmpty() ? "" : " @ " + stage));
                }
            } catch (InterruptedException ignored) {
            } catch (Throwable ignored) { }
        }, "tick-" + tag);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void stopTick(Thread t) {
        if (t != null) t.interrupt();
    }

    // ── live preview ───────────────────────────────────────────────

    /**
     * A slider moved. Ask for a picture.
     *
     * <p>Called from `onProgressChanged`, so it runs a hundred times a second
     * while a thumb is down. Everything here is a flag: the drawing happens on
     * `liveLoop`, one frame at a time, and requests that arrive mid-frame are
     * folded into the next one. Nothing is queued, so a fast drag cannot build
     * a backlog it will spend the rest of the gesture draining.
     *
     * <p>It deliberately does not draw while `rendering`: a full-size pass
     * holds `renderMutex` for two to four seconds, and a live frame started
     * now would simply wait behind it and then paint 800 px over the 2048 px
     * result. The next move asks again.
     */
    /** The field names inside a delta, for one readable log line. */
    private static final java.util.regex.Pattern KEY_IN_DELTA =
            java.util.regex.Pattern.compile("\"([A-Za-z0-9_]+)\":");
    private static String keyNames(String delta) {
        final java.util.regex.Matcher m = KEY_IN_DELTA.matcher(delta);
        final StringBuilder out = new StringBuilder();
        while (m.find()) {
            if (out.length() > 0) out.append(',');
            out.append(m.group(1));
        }
        return out.toString();
    }

    /** FNV-1a over the pixels, the same shape the preview log uses. */
    private static long pixelHash(int[] pixels, int size) {
        long h = 1469598103934665603L;
        for (int i = 0; i < size; i += 7) { h ^= pixels[i]; h *= 1099511628211L; }
        return h;
    }

    /** One line a second is enough to tell "blocked" from "never called". */
    private long lastLiveLog = 0;
    private void liveLog(String what) {
        final long now = System.currentTimeMillis();
        if (now - lastLiveLog < 1000) return;
        lastLiveLog = now;
        try { DebugSink.text("live", what); } catch (Throwable ignored) { }
    }

    private void requestLivePreview() {
        if (!uiReady || appPaused || session == null || filmStock == null) {
            liveLog("blocked state ui=" + uiReady + " paused=" + appPaused
                    + " session=" + (session != null) + " film=" + filmStock);
            return;
        }
        if (wbDecoding) {
            liveLog("blocked wb");
            return;
        }
        // **A full-size render in flight is not a reason to drop the frame.**
        // It used to `return` here, and since a 2048 render takes ~1.4 s while a
        // drag lasts about as long, every request of the whole gesture landed
        // inside one and was thrown away -- "the number moves and the picture
        // does not", on exactly the print-layer sliders that were supposed to be
        // the fast ones. `drawLivePreview` already takes `renderMutex` and
        // re-checks `fullGeneration` inside it, so queueing is safe: the live
        // frame waits for the full one to finish and paints immediately after.
        // Dragging a slider while the canvas shows the source means "show me
        // what this does", so the canvas has to be showing the print.
        if (showingOriginal) {
            showingOriginal = false;
            setOriginalVisual(false);
        }
        // The shelf's queue renders other stocks into the same session; a live
        // frame would land between two cards and be overwritten by the next
        // one. A gesture is the user editing, which outranks the catalogue.
        liveLog("request ok");
        stopPrefetch();
        if (liveThread == null) {
            liveThread = new Thread(this::liveLoop, "live-preview");
            liveThread.setDaemon(true);
            liveThread.start();
        }
        liveWanted = true;
        synchronized (liveLock) { liveLock.notifyAll(); }
    }

    /**
     * Drop whatever a live frame is about to paint.
     *
     * <p>A full-size render is starting, or the photograph is changing. The
     * frame already inside `renderBegin` cannot be interrupted -- it will
     * finish and then throw its bitmap away, which is exactly what the
     * generation check is for.
     */
    private void cancelLivePreview() {
        liveGeneration.incrementAndGet();
        liveWanted = false;
    }

    private void liveLoop() {
        while (true) {
            synchronized (liveLock) {
                while (!liveWanted) {
                    try { liveLock.wait(); } catch (InterruptedException e) { return; }
                }
            }
            // Let the gesture settle for a moment: every request that arrives
            // during this sleep folds into the frame that follows.
            final long since = System.currentTimeMillis() - liveLastStart;
            if (since < LIVE_MIN_GAP_MS) {
                try { Thread.sleep(LIVE_MIN_GAP_MS - since); } catch (InterruptedException e) { return; }
            }
            liveWanted = false;
            if (session == null || filmStock == null || wbDecoding) continue;
            // The release that ended the drag has already started the full-size
            // pass. It sets `rendering` before it takes the lock, so this is the
            // cheap chance to stay out of its way instead of spending 0.4 s of
            // its time on a frame it is about to replace.
            if (rendering) continue;
            liveLastStart = System.currentTimeMillis();
            drawLivePreview();
        }
    }

    /**
     * One frame at `LIVE_LONG_EDGE`, on the same lock, with the same delta a
     * full render sends -- stock and paper included.
     *
     * <p>**The delta is sent here, from this thread, inside `renderMutex`,
     * immediately before `renderBegin`.** That is the one rule the 1.5.0l fix
     * rests on (`applyParams()` on the UI thread is a log line, not a writer):
     * `spk_set_params` takes the session lock, `render_tier` holds it for the
     * whole render, and a delta sent from the UI thread parks the main thread
     * behind a live render -- the exact stack that used to take Adreno 730
     * down mid-drag.
     *
     * <p>Nothing else here is the full path's job. No JPEG to the sink, no
     * progress json, no undo entry, no persist: this bitmap lives until the
     * next frame replaces it.
     */
    private void drawLivePreview() {
        final SpektraLabEngine.Session s = session;
        final String stock = filmStock;
        if (s == null || stock == null) return;
        // **Read the generation, never advance it here.**
        //
        // `runOnUiThread` only posts; this method returns immediately and the
        // loop starts the next frame, whose `++` lands microseconds later. The
        // UI thread then compares against a counter that has already moved past
        // the frame it was handed and drops it -- measured at 18 of 20 frames
        // thrown away, which is exactly "the number changes and the picture does
        // not". Advancing on cancel alone keeps the invalidation the caller
        // actually means: stop showing frames from before the user let go.
        final int gen = liveGeneration.get();
        final int fullGen = fullGeneration.get();
        final int frame = currentFrame;
        final String paper = printStock == null ? "kodak_portra_endura" : printStock;
        Bitmap bmp = null;
        long liveHash = 0;
        synchronized (renderMutex) {
            // `renderImage` may have taken the session while this frame was
            // waiting for the lock.
            if (s != session || fullGen != fullGeneration.get()) return;
            try {
                applyParams(stock, paper, LIVE_LONG_EDGE);
                // **Reprint, not render.** The delta above only names what
                // moved, so a print- or view-layer change never touches the
                // cached negative and this skips the whole film side: 32 ms
                // against 200 ms at this size. A shoot-layer change drops that
                // cache inside `spk_set_params` itself, so the fallback is a
                // full develop -- correct, just slow, and only on the sliders
                // that genuinely need it (grain, halation, geometry, rebate).
                final long liveStart = System.currentTimeMillis();
                final int[] dims = s.renderBegin("live", true);
                final int w = dims[0], h = dims[1];
                if (w <= 0 || h <= 0) throw new RuntimeException("empty live frame");
                if (livePixels == null || livePixels.length < w * h) livePixels = new int[w * h];
                final int copied = s.copyArgbRange(livePixels, 0, h);
                if (copied != w * h) throw new RuntimeException("live copy " + copied + "/" + (w * h));
                bmp = newFrameBitmap(w, h);
                bmp.setPixels(livePixels, 0, w, 0, 0, w, h);
                s.renderEnd();
                try { rememberAppliedEv(s.progressJson(), "live"); } catch (Throwable ignored) { }
                liveHash = pixelHash(livePixels, w * h);
                try { DebugSink.text("live", "frame " + w + "x" + h + " "
                        + (System.currentTimeMillis() - liveStart) + " ms "
                        + Long.toHexString(liveHash)); }
                catch (Throwable ignored) { }
            } catch (Throwable t) {
                try { s.renderEnd(); } catch (Throwable ignored) { }
                try { DebugSink.text("live", "fail " + describe(t)); } catch (Throwable ignored) { }
                return;
            }
        }
        final Bitmap out = bmp;
        final long hash = liveHash;
        runOnUiThread(() -> {
            if (!uiReady || gen != liveGeneration.get()) {
                liveLog("drop: superseded");
                return;
            }
            if (fullGen != fullGeneration.get()) {
                liveLog("drop: a full render started");
                return;
            }
            if (frame != currentFrame) {
                liveLog("drop: frame changed");
                return;
            }
            if (session == null || compare == null) {
                liveLog("drop: no session/canvas");
                return;
            }
            lastRenderedBitmap = out;
            compare.setAfter(out);
            setStage(1, null);
            // The other half of the comparison: `frame` says what was produced,
            // this says what the canvas was handed. If they disagree the loss is
            // between the two, not in the render.
            try { DebugSink.text("live", "canvas " + out.getWidth() + "x" + out.getHeight()
                    + " " + Long.toHexString(hash)); } catch (Throwable ignored) { }
        });
    }

    /**
     * Hand the GPU pool back when a render left it much larger than the frame.
     *
     * <p>**Shoot-layer edits are the expensive ones.** Each one drops the cached
     * negative, so `run_film` allocates a new set of planes and the pool keeps
     * them for the next render. One edit is fine; ten in a row (which is what a
     * switch that "does nothing" invites) leaves gigabytes parked, and Android
     * answers by killing the launcher. Trimming costs one re-allocation on the
     * next render, which is far cheaper than that.
     */
    private void trimPoolIfBloated() {
        if (engine == null) return;
        try {
            final String report = engine.memoryReport();
            // `"total":N` is the pool's own byte count.
            final int at = report.indexOf("\"total\":");
            if (at < 0) return;
            int i = at + 8;
            final StringBuilder digits = new StringBuilder();
            while (i < report.length() && Character.isDigit(report.charAt(i))) digits.append(report.charAt(i++));
            if (digits.length() == 0) return;
            final long total = Long.parseLong(digits.toString());
            if (total > (1536L << 20)) {
                // **Under the render lock.** Trimming destroys device buffers;
                // a render that starts between the measurement and the trim
                // would hand the GPU memory that is being freed.
                long freed;
                synchronized (renderMutex) { freed = engine.trimPool(); }
                try { DebugSink.text("pool-trim",
                        "after render: total was " + (total >> 20) + " MB, freed "
                                + (freed >> 20) + " MB"); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    private void renderImage() { renderImage(PREVIEW_LONG_EDGE); }

    private void renderImage(int longEdge) {
        // The full-size pass owns the canvas from here. A live frame still in
        // flight would otherwise land a moment later and put 800 px on top of
        // the result the user waited for.
        cancelLivePreview();
        fullGeneration.incrementAndGet();
        CrashTrail.mark(this, "render:begin");
        // **A decode in flight owns the session.** It is closed for a few
        // seconds while LibRaw re-demosaics; a render that walked in then would
        // either complain about a null session or queue behind the decode for
        // the whole of it, which is what "白平衡卡死" was.
        if (wbDecoding) { pendingRender = true; return; }
        // Every branch says something. The foot is one thin line, too quiet to
        // carry "nothing happened"; failures need to be readable.
        if (session == null) {
            try { DebugSink.text("render", "no session, lastImportError=" + lastImportError); }
            catch (Throwable ignored) { }
            // **A frame in the strip means an import is on its way.** Coming
            // back from the background walks through here while `loadImage` is
            // still decoding -- there is a picture loading, so "还没有可渲染的
            // 图像" is the wrong sentence and the dialog is pure noise. Ask for
            // the render again once the import finishes; if the import really
            // failed, its own message is the one to show.
            if (!frames.isEmpty() && lastImportError == null) {
                pendingRender = true;
                return;
            }
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
            Probe.log("render", "queued film=" + filmStock + " pending=" + pendingRender);
            statusText.setText("Rendering…");
            // Not dropped: a tap that lands mid-render is the *last* choice the
            // user made, so it becomes the next render instead of a toast.
            pendingRender = true;
            showInAppToast("正在渲染，完成后按新选择再画一次…");
            return;
        }
        scheduleUndo();          // one entry per settled edit
        final String renderedStock = filmStock;
        // **The frame this render belongs to.** Deleting the chips (or opening
        // another photo) while a render is in flight used to let its callback
        // call setStage(1) on a canvas that no longer had a frame: the empty
        // state -- and with it the 选择照片 button -- vanished, which is the
        // "删掉全部缩略图后导入按钮有时候会消失" report.
        final int renderedFrame = currentFrame;
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
                CrashTrail.markNow(MainActivity.this, "render:lock");
                applyParams(renderedStock, longEdge);
                Probe.log("render", "apply film=" + renderedStock + " long=" + longEdge
                        + " src=" + sourceWidth + "x" + sourceHeight);
                CrashTrail.markNow(MainActivity.this, "render:params");
                // **The black box is `renderBegin`.** Everything before it is
                // Java and leaves a stack; everything after it is arithmetic on
                // a frame that already exists. So the ticker wraps exactly that
                // call, and `render:returned` is the first proof it came back.
                Thread ticker = startTick("render", session);
                CrashTrail.markNow(MainActivity.this, "render:call");
                int[] dims;
                try {
                    dims = session.renderBegin("live");
                } finally {
                    stopTick(ticker);
                }
                int w = dims[0], h = dims[1];
                CrashTrail.markNow(MainActivity.this, "render:returned " + w + "x" + h);
                if (w <= 0 || h <= 0) throw new RuntimeException("engine returned empty frame");

                CrashTrail.markNow(MainActivity.this, "render:alloc " + w + "x" + h);
                Bitmap bmp = newFrameBitmap(w, h);
                int[] pixels = new int[w * h];
                CrashTrail.markNow(MainActivity.this, "render:copy");
                int copied = session.copyArgbRange(pixels, 0, h);
                if (copied != w * h) {
                    throw new RuntimeException("pixel copy incomplete " + copied + "/" + (w * h));
                }
                bmp.setPixels(pixels, 0, w, 0, 0, w, h);
                CrashTrail.markNow(MainActivity.this, "render:setPixels");
                session.renderEnd();
                CrashTrail.markNow(MainActivity.this, "render:end");
                // What the meter did to *this* frame. A render that looks dark
                // is either the film's curve or an exposure the engine decided
                // on, and the two are indistinguishable by eye -- one line of
                // numbers settles it.
                try {
                    rememberAppliedEv(session.progressJson(), "full");
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
                    if (currentFrame != renderedFrame || session == null) {
                        try { DebugSink.text("render", "frame changed mid-render; dropped"); }
                        catch (Throwable ignored) { }
                        return;
                    }
                    showingOriginal = false;
                    compare.setAfter(bmp);
                    setStage(1, null);
                    // Give the pool back if this render left it far larger than
                    // the frame -- ten shoot-layer edits in a row is what parks
                    // gigabytes and gets the launcher killed. Off the UI thread:
                    // trimming destroys buffers and waits on the device.
                    new Thread(MainActivity.this::trimPoolIfBloated, "pool-trim").start();
                    setStep(2);
                    lastRenderedBitmap = bmp;
                    // The histogram describes what the canvas shows, so it moves
                    // with the render, not with the tab that displays it.
                    if (histogram != null) histogram.setBitmap(bmp);
                    refreshComparePill();
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
                    // The canvas now shows this look; it is also the version
                    // that has to survive a kill from recents.
                    schedulePersist();
                    if (pendingRender) { pendingRender = false; renderImage(); }
                });
            }
            } catch (Throwable e) {
                try { session.renderEnd(); } catch (Throwable ignored) {}
                final String msg = describe(e);
                runOnUiThread(() -> {
                    // **A failed render is not an edit.** `scheduleUndo` runs
                    // before the render does, so a failure would still push the
                    // state that caused it -- and undo then replayed the error
                    // instead of the picture before it.
                    cancelUndo();
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

    /**
     * Put the finished file into a collection the user can find.
     *
     * <p>**Three doors, because the other end of `insert` is somebody else's
     * code.** A Mali device reported `FileNotFoundException: No item at
     * content://media/external/images/media/1000068740`: the row `insert` had
     * just handed back was not readable through `openOutputStream`. The pixels
     * are already in `tmp` at that point -- two minutes of rendering -- so
     * failing the whole export over a provider that will not open its own new
     * row is the wrong trade. Retry once under a fresh name (the usual cause is
     * a name collision the provider resolved in a way it cannot then open),
     * then try Downloads, which is a different collection and is served by a
     * different code path on every ROM met so far.
     */
    private Uri writeExportFile(java.io.File tmp, String baseName, String ext, String mime)
            throws java.io.IOException {
        final android.content.ContentResolver resolver = getContentResolver();
        Throwable lastError = null;
        for (int attempt = 0; attempt < 2; ++attempt) {
            final String name = attempt == 0 ? baseName
                    : "SpektraLab_" + System.currentTimeMillis() + "_r" + ext;
            final android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, mime);
            v.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/SpektraLab");
            v.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = null;
            try { uri = resolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v); }
            catch (Throwable t) { lastError = t; }
            if (uri == null) continue;
            try {
                copyInto(resolver, tmp, uri);
                v.clear();
                v.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, v, null, null);
                return uri;
            } catch (Throwable t) {
                lastError = t;
                try { resolver.delete(uri, null, null); } catch (Throwable ignored) { }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            Uri uri = null;
            try {
                final android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                        "SpektraLab_" + System.currentTimeMillis() + ext);
                v.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/SpektraLab");
                v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);
                uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    copyInto(resolver, tmp, uri);
                    v.clear();
                    v.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
                    resolver.update(uri, v, null, null);
                    return uri;
                }
            } catch (Throwable t) {
                lastError = t;
                if (uri != null) { try { resolver.delete(uri, null, null); } catch (Throwable ignored) { } }
            }
        }
        throw new java.io.IOException("相册写入失败（" + lastError + "）。照片已经渲染好，"
                + "是系统相册没有接受这个文件——重启手机再导一次；"
                + "如果每次都是这样，把这条发给我");
    }

    /** Stream `tmp` into an already-inserted MediaStore row. */
    private static void copyInto(android.content.ContentResolver resolver,
                                 java.io.File tmp, Uri uri) throws java.io.IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(tmp);
             java.io.OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new java.io.IOException("openOutputStream returned null");
            final byte[] buf = new byte[262144];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }

    /**
     * Ask the engine to analyse this frame again: exposure and the three
     * neutral filters, exactly what macOS calls 「配光」.
     *
     * <p>Nothing here is new state -- the engine already owns these numbers and
     * derives them per stock. What the button buys is the ability to *re-run*
     * the analysis after the stock, paper or light changed, and to see what it
     * came back with. 「恢复」 then puts the enlarger back on those numbers by
     * zeroing the offsets the user may have dialled in (brightness, yellow,
     * magenta, pre-flash) -- the neutral filters themselves are the engine's and
     * are not offsets.
     */
    private void resolvePrinting() {
        final SpektraLabEngine.Session s = session;
        if (s == null || filmStock == null) {
            showActionSheet("还没有可配光的照片", "先导入一张照片并选一支胶片。",
                    new String[]{"知道了"}, which -> { });
            return;
        }
        statusText.setText("配光中…");
        new Thread(() -> {
            String json = null;
            String failure = null;
            try {
                synchronized (renderMutex) {
                    if (s != session) return;
                    json = s.solve("both");
                }
            } catch (Throwable e) {
                failure = describeImportFailure(e);
            }
            final String out = json, err = failure;
            runOnUiThread(() -> {
                if (err != null) {
                    statusText.setText("配光失败");
                    showErrorDialog("配光失败", err);
                    return;
                }
                String balanced = "?", ch = "?", mg = "?", ye = "?";
                try {
                    final org.json.JSONObject o = new org.json.JSONObject(out);
                    final org.json.JSONObject ev = o.optJSONObject("exposure_ev_by_method");
                    if (ev != null) balanced = String.format(java.util.Locale.US, "%+.2f EV",
                            ev.optDouble("balanced", 0.0));
                    final org.json.JSONObject sp = o.optJSONObject("solved_params");
                    if (sp != null) {
                        ch = String.format(java.util.Locale.US, "%.1f", sp.optDouble("c_filter_neutral", 0.0));
                        mg = String.format(java.util.Locale.US, "%.1f", sp.optDouble("m_filter_neutral", 0.0));
                        ye = String.format(java.util.Locale.US, "%.1f", sp.optDouble("y_filter_neutral", 0.0));
                    }
                } catch (Throwable ignored) { }
                statusText.setText("配光完成 " + balanced);
                showActionSheet("配光结果",
                        "测光 " + balanced + "\n\n中性滤镜（引擎按胶片与光源查表）\n"
                                + "青 " + ch + " · 品红 " + mg + " · 黄 " + ye
                                + "\n\n「恢复放大机」会把亮度、黄、品红、预闪归零，"
                                + "让画面回到配光算出的中性。",
                        new String[]{"恢复放大机", "关闭"}, which -> {
                            if (which != 0) return;
                            printExposure = 1.0f;
                            mFilter = 0f;
                            yFilter = 0f;
                            preflash = 0f;
                            // The panel has one shared knob, so the readout
                            // follows whatever is selected; refreshing it is
                            // enough, and it keeps working whichever row the
                            // user happens to be on.
                            refreshKnob();
                            applyParams();
                            renderImage();
                        });
            });
        }, "solve").start();
    }

    // ---------------------------------------------------------------- batch
    // **Import adds chips without decoding them.** A frame renders when it is
    // opened, and decoding twenty RAW files up front would take two minutes and
    // hold a hundred megabytes per file. Export is the opposite -- every frame
    // has to be built at full size -- so it runs one at a time through the same
    // path a single export uses.

    private volatile boolean batchExportRunning = false;
    private volatile boolean batchExportAbort = false;

    /** Take the grant, or keep a copy that survives a restart. Null on failure. */
    private Uri persistOrCopy(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return uri;
        } catch (Throwable ignored) { }
        return privateCopy(uri);
    }

    private static boolean isRawName(String name) {
        final String lower = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".rw2") || lower.endsWith(".raw")
                || lower.endsWith(".dng") || lower.endsWith(".arw")
                || lower.endsWith(".nef") || lower.endsWith(".cr2")
                || lower.endsWith(".cr3") || lower.endsWith(".orf")
                || lower.endsWith(".raf") || lower.endsWith(".pef")
                || lower.endsWith(".srw") || lower.endsWith(".x3f")
                || lower.endsWith(".rwl") || lower.endsWith(".3fr")
                || lower.endsWith(".mrw") || lower.endsWith(".erf");
    }

    private Bitmap systemThumb(Uri uri) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                return getContentResolver().loadThumbnail(uri,
                        new android.util.Size(Math.max(1, dp(42)), Math.max(1, dp(42))), null);
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Add a chip without decoding anything. False when it is already there. */
    private boolean addFrameOnly(Uri uri) {
        if (uri == null) return false;
        for (Frame f : frames) {
            if (f.uri != null && f.uri.toString().equals(uri.toString())) return false;
        }
        String name = getFileName(uri);
        if (name == null || name.isEmpty()) name = "photo";
        final Frame frame = new Frame(uri, isRawName(name), name);
        frame.look = defaultLook();
        frame.thumb = systemThumb(uri);
        frames.add(frame);
        persistThumb(frame);
        return true;
    }

    private void startBatchImport(final java.util.List<Uri> uris) {
        final int total = uris.size();
        runOnUiThread(() -> {
            if (statusText != null) statusText.setText("正在加入 " + total + " 张照片…");
        });
        new Thread(() -> {
            int added = 0, failed = 0;
            for (Uri u : uris) {
                final Uri target = persistOrCopy(u);
                if (target == null) { ++failed; continue; }
                if (addFrameOnly(target)) ++added; else ++failed;
            }
            final int a = added, f = failed;
            runOnUiThread(() -> {
                if (a > 0) {
                    saveSessionState();
                    rebuildFrameChips();
                }
                final String msg = "已加入 " + a + " 张" + (f > 0 ? "，跳过 " + f + " 张" : "");
                if (statusText != null) statusText.setText(msg);
                showInAppToast(msg);
                if (a > 0 && currentFrame < 0 && !frames.isEmpty()) {
                    reopenFrame(frames.size() - a);
                }
            });
        }, "SpektraLab-batch-import").start();
    }

    private void uiWait(Runnable r, long ms) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        runOnUiThread(() -> {
            try { r.run(); } catch (Throwable ignored) { } finally { latch.countDown(); }
        });
        try { latch.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (InterruptedException ignored) { }
    }

    private boolean pollUntil(java.util.function.BooleanSupplier cond, long ms) {
        final long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return true;
            try { Thread.sleep(250); } catch (InterruptedException ie) { return false; }
        }
        return false;
    }

    /**
     * Export every frame, one at a time, each with its own Look.
     *
     * <p>It goes through `reopenFrame` + `exportImage` rather than a second
     * implementation: opening a frame is how the app learns its size, its white
     * balance and its meter, and a parallel path would drift from it. The cost
     * is that the canvas shows each frame as it is processed.
     */
    private void startBatchExport(String format, String tier) {
        if (batchExportRunning) {
            showInAppToast("批量导出已经在跑");
            return;
        }
        if (frames.size() < 2) {
            showInAppToast("只有一张照片，不需要批量");
            return;
        }
        saveLookIntoFrames();
        final int total = frames.size();
        final int home = currentFrame;
        batchExportRunning = true;
        batchExportAbort = false;
        // **Stop the shelf that is already running.** `startPrefetch` refuses
        // to start a new one while a batch is going, but the queue the last
        // import kicked off keeps painting twenty-five cards -- and the trail
        // of the crash at `export:write full jpeg` shows it doing exactly that
        // inside the export: twenty-five pipeline rebuilds competing for the
        // same pool with a 12.6 MP render that is about to allocate.
        stopPrefetch();
        if (sharePill != null) sharePill.setVisibility(View.GONE);
        new Thread(() -> {
            int ok = 0, failed = 0;
            try {
            for (int i = 0; i < total; ++i) {
                if (batchExportAbort) break;
                final int index = i;
                final Frame frame = frames.get(index);
                final String name = frame.name == null ? ("第 " + (i + 1) + " 张") : frame.name;
                if (frame.look == null || frame.look.filmStock == null) {
                    ++failed;               // nothing to print: no film chosen
                    continue;
                }
                runOnUiThread(() -> {
                    if (statusText != null) {
                        statusText.setText("批量导出 " + (index + 1) + "/" + total + "：" + name);
                    }
                    if (exportProgressBar != null) exportProgressBar.setVisibility(View.VISIBLE);
                });
                // **State the Look before the decode, every frame.** `loadImage`
                // also applies it, but only while `reopeningFrame` survives the
                // trip; stating it here makes the export independent of that
                // flag. Reported: six frames came back with the film but not
                // the print exposure or the optics, which is what a half-applied
                // Look looks like.
                try {
                    DebugSink.text("batch-look", (index + 1) + "/" + total
                            + " film=" + frame.look.filmStock
                            + " print=" + frame.look.printStock
                            + " exp=" + frame.look.printExposure
                            + " grain=" + frame.look.grainAmount
                            + " hal=" + frame.look.halationAmount
                            + " glare=" + frame.look.glareAmount
                            + " edr=" + frame.look.edrOn
                            + " viewC=" + frame.look.viewContrast);
                } catch (Throwable ignored) { }
                uiWait(() -> {
                    applyLook(frame.look);
                    // **Drop the previous frame's session before asking for
                    // this one.** `loadImage` closes it on its own worker, so
                    // for a moment `session` still held the *old* object -- the
                    // poll below was satisfied by it, the export started, and
                    // the worker closed that session underneath (NPE in
                    // `exportJpeg`, E-9000). Closing it here, under the lock,
                    // makes `session != null` mean this frame and nothing else.
                    synchronized (renderMutex) {
                        if (session != null) { session.close(); session = null; }
                    }
                    // **Forced, even for the frame already on screen.** The
                    // shortcut `reopenFrame` takes (`index == currentFrame &&
                    // session != null`) leaves the first export on the session
                    // the import opened, while every later frame is re-decoded.
                    reopenFrameForced(index);
                }, 5000);
                if (!pollUntil(() -> session != null, 180000)) {
                    ++failed;
                    try { DebugSink.text("batch-export", "open failed " + index + " " + name); }
                    catch (Throwable ignored) { }
                    continue;
                }
                final java.util.concurrent.CountDownLatch done =
                        new java.util.concurrent.CountDownLatch(1);
                uiWait(() -> exportImage(format, tier, done::countDown), 5000);
                boolean finished = false;
                try { finished = done.await(20, java.util.concurrent.TimeUnit.MINUTES); }
                catch (InterruptedException ie) { break; }
                if (finished) ++ok; else ++failed;
                // **Before the next frame asks for its own planes.**
                uiWait(() -> {
                    synchronized (renderMutex) {
                        if (session != null) { session.close(); session = null; }
                    }
                }, 5000);
                try { if (engine != null) engine.trimPool(); } catch (Throwable ignored) { }
                // **The next frame starts from zero.** The decoded raw cache
                // holds one frame's unpacked plane, and the heap still carries
                // the last frame's bitmaps; six frames of that is what made the
                // fifth one die.
                try { SpektraLabEngine.clearRawCache(); } catch (Throwable ignored) { }
                System.gc();
            }
            } finally {
                // **The flag has to come down even when the loop throws**, or
                // every later batch is refused and every error dialog is eaten.
                final int fok = ok, ffail = failed;
                runOnUiThread(() -> {
                    batchExportRunning = false;
                    if (exportProgressBar != null) exportProgressBar.setVisibility(View.GONE);
                    final String msg = "批量导出完成：" + fok + " 张成功"
                            + (ffail > 0 ? "，" + ffail + " 张失败（多半是没选胶片或打不开）" : "");
                    if (statusText != null) statusText.setText(msg);
                    showInAppToast(msg);
                    if (home >= 0 && home < frames.size()) {
                        final int back = home;
                        reopenFrame(back);
                        // **The restore has to render.** `loadImage` deliberately
                        // stops at the original, so without this the canvas stayed
                        // on "RENDERING / Decoding RAW…" after a batch that had
                        // already finished.
                        new Thread(() -> {
                            if (!pollUntil(() -> session != null, 120000)) return;
                            runOnUiThread(() -> {
                                // **800, not 2048.** Six full-size exports leave
                                // the pool carrying a gigabyte of lookup tables
                                // that nothing reclaims; the redisplay then asked
                                // for another 20 MP chain and the system killed
                                // the process right there -- two reports, both
                                // "it dies when it goes back to the first frame".
                                // The frame is a preview at this point anyway;
                                // tapping a film re-renders at full size.
                                if (back < frames.size() && filmStock != null) renderImage(LIVE_LONG_EDGE);
                            });
                        }, "SpektraLab-batch-restore").start();
                    }
                });
            }
        }, "SpektraLab-batch-export").start();
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

    private static void putExif(android.media.ExifInterface out, String tag, String value) {
        if (value != null && !value.trim().isEmpty() && !"null".equals(value)) out.setAttribute(tag, value);
    }

    /** {make, model, datetime, shutter, aperture, iso, focal} for a RAW frame. */
    private String[] sourceExifForExport() {
        final String[] none = {"", "", "", "", "", "", ""};
        final java.util.Locale us = java.util.Locale.US;
        try {
            if (rawFilePath != null && session != null) {
                final org.json.JSONObject src = new org.json.JSONObject(session.sourceExifJson());
                return new String[]{
                        nz(src.optString("make", "")), nz(src.optString("model", "")),
                        nz(src.optString("datetime", "")),
                        src.has("shutter") ? exifShutter(src.getDouble("shutter")) : "",
                        src.has("aperture") ? String.format(us, "%.1f", src.getDouble("aperture")) : "",
                        src.has("iso") ? String.valueOf(Math.round(src.getDouble("iso"))) : "",
                        src.has("focal") ? String.format(us, "%.1f", src.getDouble("focal")) : ""};
            }
        } catch (Throwable ignored) { }
        return none;
    }

    /**
     * The imported JPEG's capture fields: our own parse of its APP1 first, the
     * platform reader only for what that parse could not find. Both halves are
     * logged, so a missing field names the reader that dropped it.
     */
    private String[] jpegSourceExif() {
        final String[] nativeFields = {"", "", "", "", "", "", ""};
        final String[] platformFields = {"", "", "", "", "", "", ""};
        int headBytes = 0;
        String headHash = "";
        String declared = "?";
        try {
            final byte[] head = readSourceHead(262144);
            if (head != null) {
                headBytes = head.length;
                final String[] parsed = SpektraLabEngine.readJpegExif(head);
                for (int i = 0; i < nativeFields.length && i < parsed.length; ++i)
                    nativeFields[i] = parsed[i] == null ? "" : parsed[i];
                if (parsed != null && parsed.length > 0 && (parsed[0] == null || parsed[0].isEmpty())) {
                    try {
                        final java.io.File dump = new java.io.File(getExternalFilesDir(null), "srchead.bin");
                        try (java.io.FileOutputStream out = new java.io.FileOutputStream(dump)) {
                            out.write(head);
                        }
                    } catch (Throwable ignored) { }
                }
                try {
                    final byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(head);
                    final StringBuilder hex = new StringBuilder();
                    for (int i = 0; i < 8; ++i) hex.append(String.format("%02x", digest[i]));
                    headHash = hex.toString();
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        try (android.database.Cursor c = getContentResolver().query(sourceUri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                final int idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) declared = String.valueOf(c.getLong(idx));
            }
        } catch (Throwable ignored) { }
        try (java.io.InputStream in = getContentResolver().openInputStream(sourceUri)) {
            if (in != null) {
                final android.media.ExifInterface src = new android.media.ExifInterface(in);
                platformFields[0] = nz(src.getAttribute(android.media.ExifInterface.TAG_MAKE));
                platformFields[1] = nz(src.getAttribute(android.media.ExifInterface.TAG_MODEL));
                platformFields[2] = nz(src.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL));
                platformFields[3] = nz(src.getAttribute(android.media.ExifInterface.TAG_EXPOSURE_TIME));
                platformFields[4] = nz(src.getAttribute(android.media.ExifInterface.TAG_F_NUMBER));
                platformFields[5] = nz(src.getAttribute(android.media.ExifInterface.TAG_ISO_SPEED_RATINGS));
                platformFields[6] = nz(src.getAttribute(android.media.ExifInterface.TAG_FOCAL_LENGTH));
            }
        } catch (Throwable ignored) { }
        final String[] merged = new String[7];
        for (int i = 0; i < 7; ++i) {
            // A number field that came back as a bare zero is "not known": the
            // other reader may still have it, and a zero would only be written
            // as 0/1 in the output.
            final boolean worthIt = !nativeFields[i].isEmpty()
                    && !(i >= 3 && isZeroNumber(nativeFields[i]));
            merged[i] = worthIt ? nativeFields[i] : platformFields[i];
        }
        try {
            String sourceName = "";
            try { sourceName = getFileName(sourceUri); } catch (Throwable ignored) { }
            DebugSink.text("exif", "jpeg source name=" + sourceName + " size=" + declared
                    + " head=" + headBytes + " B sha=" + headHash + " uri=" + sourceUri
                    + " native=" + java.util.Arrays.toString(nativeFields)
                    + " platform=" + java.util.Arrays.toString(platformFields)
                    + " -> " + java.util.Arrays.toString(merged));
        } catch (Throwable ignored) { }
        return merged;
    }

    private static boolean isZeroNumber(String value) {
        try {
            return Double.parseDouble(value.trim()) == 0.0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The first `max` bytes of the imported file -- an EXIF header lives there. */
    private byte[] readSourceHead(int max) {
        if (sourceUri == null) return null;
        try (java.io.InputStream in = getContentResolver().openInputStream(sourceUri)) {
            if (in == null) return null;
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final byte[] buf = new byte[32768];
            int total = 0, n;
            while (total < max && (n = in.read(buf, 0, Math.min(buf.length, max - total))) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String nz(String value) {
        return value == null || "null".equals(value) ? "" : value;
    }

    /** A shutter in the form a camera writes: `1/204`, or `2` for a long one. */
    private static String exifShutter(double seconds) {
        if (!(seconds > 0.0) || !Double.isFinite(seconds)) return "";
        if (seconds >= 1.0) {
            return seconds == Math.rint(seconds)
                    ? String.valueOf((long) seconds)
                    : String.format(java.util.Locale.US, "%.1f", seconds);
        }
        final long denominator = Math.round(1.0 / seconds);
        return denominator > 0 ? "1/" + denominator : "";
    }

    private void exportImage(String format, String tier) {
        exportImage(format, tier, null);
    }

    private void exportImage(String format, String tier, final Runnable onDone) {
        CrashTrail.mark(this, "export:begin");
        // **The export's exposure, decided once, before any branch.** The open
        // params, the forced delta and the pin all have to agree: the canvas'
        // meter flag, the canvas' EV, and only the *user's* offset in
        // `exposure_compensation_ev` (see `applyParamsTo`).
        final String exportMeter = sanitizeMeter(meterMethod);
        final boolean exportAutoExposure = !exportMeter.isEmpty();
        // Only the frame the canvas last measured may hand its EV over; a batch
        // walks the strip without rendering, so the previous frame's EV would
        // otherwise be pinned onto this one.
        final boolean pinEv = exportAutoExposure && appliedEvBelongsToCurrentFrame();
        // A live frame is 800 px and this is about to build a 4175 px one;
        // letting the two queue behind the same lock buys nothing.
        cancelLivePreview();
        final boolean tiff = "tiff".equals(format);
        final boolean dng = "dng".equals(format);
        final String ext = dng ? ".dng" : tiff ? ".tif" : ".jpg";
        final String mime = dng ? "image/x-adobe-dng" : tiff ? "image/tiff" : "image/jpeg";
        if (sharePill != null) sharePill.setVisibility(View.GONE);
        if (engine == null || session == null) {
            showInAppToast("请先导入照片");
            if (onDone != null) onDone.run();
            return;
        }
        if (filmStock == null) {
            showInAppToast("先选一张胶片，再导出");
            if (onDone != null) onDone.run();
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

                // **The numbers the export is about to spend, before it spends
                // them.** Which frame, which cap, whether the rebate is on, and
                // what the phone says is free -- the four things that decide
                // whether the next two minutes fit.
                try {
                    // The crop travels with the plan: a "garbled once" report is
                    // almost always geometry, and the one number that explains it
                    // is the window the render was asked for.
                    DebugSink.textNow("export-crop", String.format(java.util.Locale.US,
                            "x=%.4f y=%.4f w=%.4f h=%.4f turns=%d rot=%.2f",
                            geometryCropX, geometryCropY, geometryCropW, geometryCropH,
                            geometryTurns, geometryRotation));
                    DebugSink.textNow("export-plan", "src=" + sourceWidth + "x" + sourceHeight
                            + " importCap=" + importCapPixels + " frameCap=" + maxImportPixels()
                            + " overscan=" + overscanOn + " tier=" + tier
                            + " avail=" + (availableMemoryBytes() >> 20) + "MiB");
                } catch (Throwable ignored) { }
                final long t0 = System.currentTimeMillis();
                final java.util.concurrent.atomic.AtomicBoolean spinning =
                        new java.util.concurrent.atomic.AtomicBoolean(true);
                final java.util.concurrent.atomic.AtomicLong lastMem =
                        new java.util.concurrent.atomic.AtomicLong(-5);
                final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
                Runnable tick = new Runnable() {
                    @Override public void run() {
                        if (!spinning.get()) return;
                        long el = (System.currentTimeMillis() - t0) / 1000;
                        statusText.setText("导出中（" + tier + "）：渲染中 " + el + " 秒...");
                        // **What the export is holding, every five seconds.**
                        // A black screen leaves no stack and no exception -- the
                        // last line before the process dies is the only evidence
                        // there is. `textNow` posts synchronously because the
                        // queue does not survive the crash this exists to
                        // explain, and the line carries the pool's peak together
                        // with the system's own free-memory figure so "the
                        // engine asked for too much" and "the phone had too
                        // little" stay distinguishable.
                        if (el % 5 == 0 && lastMem.getAndSet(el) != el) {
                            try {
                                final long avail = availableMemoryBytes();
                                DebugSink.textNow("export-mem", el + "s avail=" + (avail >> 20)
                                        + "MiB " + (engine == null ? "no engine" : engine.memoryReport()));
                            } catch (Throwable ignored) { }
                        }
                        ticker.postDelayed(this, 1000);
                    }
                };
                // The memory probe must not live on the UI thread: the export
                // blocks it, which is why the 12-second crash left no samples.
                Thread memTicker = new Thread(() -> {
                    while (spinning.get()) {
                        try {
                            final long el = (System.currentTimeMillis() - t0) / 1000;
                            final String line = el + "s avail="
                                    + (availableMemoryBytes() >> 20) + "MiB "
                                    + (engine == null ? "no engine" : engine.memoryReport());
                            // **On disk first.** `textNow` needs the network; a
                            // phone in flight mode -- or a process killed at the
                            // moment it mattered -- loses the sample entirely,
                            // which is why the 18-second export crash came back
                            // with none. The ring is a SharedPreferences write
                            // and survives the kill; the report picks it up.
                            CrashTrail.markNow(MainActivity.this, "export-mem " + line);
                            DebugSink.textNow("export-mem", line);
                        } catch (Throwable ignored) { }
                        try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
                    }
                }, "SpektraLab-export-memory");
                memTicker.setDaemon(true);
                memTicker.start();
                runOnUiThread(tick);
                // A full-size export does not need the preview bitmaps resident.
                // Keep the frame and its Look; these two are only the display.
                final Bitmap savedBefore = beforeBitmap;
                final Bitmap savedAfter = lastRenderedBitmap;
                runOnUiThread(() -> {
                    beforeBitmap = null;
                    lastRenderedBitmap = null;
                    if (compare != null) compare.clear();
                });
                int[] info;
                try {
                    synchronized (renderMutex) {
                        CrashTrail.markNow(MainActivity.this, "export:lock");
                        // **Give the pool back before the export asks for more.**
                        // The preview that has just been rendered leaves its
                        // working set pooled, and a 40 MP export then wants the
                        // whole thing again. Measured on the owner's phone: the
                        // export dies at 153 s with the launcher behind it.
                        try {
                            final long freed = engine.trimPool();
                            DebugSink.text("export", "pre-export trim freed " + (freed >> 20)
                                    + " MiB; report " + engine.memoryReport());
                        } catch (Throwable ignored) { }
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
                        // **Give the preview's planes back for the duration.**
                        // At 41 MP the preview session holds 373 MiB of source
                        // that the export session is about to want, and closing
                        // it would lose the look being exported. Suspending
                        // writes those planes to the cache directory and drops
                        // the device copies; `resume` puts them back. Refused
                        // when there is no scratch directory, which is a no-op
                        // rather than a failure.
                        final SpektraLabEngine.Session keep = session;
                        boolean keepSuspended = false;
                        // **A batch closes this session a moment later anyway.**
                        // Suspending writes a frame's worth of planes to the
                        // cache directory and resuming allocates them again --
                        // once per frame, for a session that is about to die.
                        if (keep != null && !batchExportRunning) {
                            try {
                                keepSuspended = keep.suspend();
                                DebugSink.text("export", "preview suspended=" + keepSuspended
                                        + "; report " + engine.memoryReport());
                            } catch (Throwable ignored) { }
                        }
                        CrashTrail.markNow(MainActivity.this, "export:suspend " + keepSuspended);
                        SpektraLabEngine.Session fresh = null;
                        // **No area cap here.** This read `<= 30 MP`, a leftover
                        // from when `sourcePixels` was a `ByteBuffer.allocateDirect`
                        // capped by the heap growth limit. A 44 MP JPEG fell through
                        // it, had no `rawFilePath` either, and so exported on the
                        // *preview* session: `full` on a 2048 px source, which is
                        // both the wrong size and the shape that failed on the
                        // Mali report. The buffer is malloc'd now and the cap is
                        // thirty times smaller than what the phones can hold.
                        if (keep != null && sourcePixels != null && sourceWidth > 0 && sourceHeight > 0) {
                            // **The export goes back to the file for its size.**
                            // `sourcePixels` carries whatever the import's
                            // memory estimate allowed, and that estimate moves
                            // with free RAM: the same JPEG landed at 42 MP one
                            // minute and 36 MP the next, so two exports of one
                            // photograph came out 7523x5642 and 6990x5242.
                            // Re-decoding at the file's own size is the only
                            // answer that does not depend on what the phone was
                            // doing at import time. If it does not fit, the
                            // engine says which plane it wanted -- an honest
                            // refusal beats a silently smaller file.
                            Bitmap fullBmp = null;
                            ByteBuffer fullBuf = null;
                            try {
                                final String path = sourceUri != null ? getFilePathFromUri(sourceUri) : null;
                                if (path != null) {
                                    final android.graphics.BitmapFactory.Options o =
                                            new android.graphics.BitmapFactory.Options();
                                    o.inSampleSize = 1;
                                    o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                                    fullBmp = android.graphics.BitmapFactory.decodeFile(path, o);
                                }
                            } catch (Throwable ignored) { fullBmp = null; }
                            final boolean bigger = fullBmp != null
                                    && (long) fullBmp.getWidth() * fullBmp.getHeight()
                                       > (long) sourceWidth * sourceHeight;
                            if (bigger) {
                                final int fw = fullBmp.getWidth(), fh = fullBmp.getHeight();
                                try {
                                    fullBuf = SpektraLabEngine.allocDirect((long) fw * fh * 12L)
                                            .order(ByteOrder.nativeOrder());
                                    final int[] row = new int[fw];
                                    for (int y = 0; y < fh; y++) {
                                        fullBmp.getPixels(row, 0, fw, 0, y, fw, 1);
                                        for (int px : row) {
                                            fullBuf.putFloat(((px >> 16) & 0xFF) / 255.0f);
                                            fullBuf.putFloat(((px >> 8) & 0xFF) / 255.0f);
                                            fullBuf.putFloat((px & 0xFF) / 255.0f);
                                        }
                                    }
                                    fullBuf.rewind();
                                    fresh = engine.openSession(fullBuf, fw, fh, 3);
                                    CrashTrail.markNow(MainActivity.this,
                                            "export:full-size " + fw + "x" + fh
                                                    + " (import was " + sourceWidth + "x" + sourceHeight + ")");
                                } catch (Throwable e) {
                                    fresh = null;
                                    try { DebugSink.textNow("export-full",
                                            "could not re-decode at " + fw + "x" + fh + ": " + e); }
                                    catch (Throwable ignored) { }
                                } finally {
                                    if (fullBmp != null) fullBmp.recycle();
                                    if (fullBuf != null) SpektraLabEngine.freeDirect(fullBuf);
                                }
                            }
                            if (fresh == null) {
                                try { fresh = engine.openSession(sourcePixels, sourceWidth, sourceHeight, 3); }
                                catch (Throwable ignored) { fresh = null; }
                            }
                            CrashTrail.markNow(MainActivity.this,
                                    "export:session " + (fresh != null ? "fresh" : "none"));
                        } else if (keep != null && rawFilePath != null) {
                            // **No budget gate here.** One was added in 1.4.1t and it
                            // refused frames the engine goes on to render: it compared
                            // the canvas against `maxPixelsByMemory(available)` before
                            // the export had trimmed the preview's pool, and 100 B/px
                            // is the whole-frame figure, not the banded one. Refusing
                            // is not the fix for a large frame -- rendering it is.
                            // A frame that genuinely does not fit fails in the engine
                            // and says which plane it wanted; that message is true,
                            // this one was a guess.
                            final int budgetPixels = importedWidth > 0 && importedHeight > 0
                                    ? importedWidth * importedHeight
                                    : (importCapPixels > 0 ? importCapPixels
                                       : sourceWidth * sourceHeight);
                            try {
                                // **The export renders the frame that was imported,
                                // never the file's own size.** This passed 0, 0 --
                                // "no cap" -- so a 51 MP negative was decoded a second
                                // time at native size even when the import had landed
                                // at 7 MP, and a rebate canvas is 1.51x that: 77 MP,
                                // 924 MB per plane, several planes. Measured on the
                                // owner's phone, 2026-11-03: a full export after a
                                // preview export of the same frame took the screen
                                // black and the launcher with it.
                                // **The budget the import actually used**, not the
                                // session's dimensions: `getDimensions()` answers the
                                // *source* size for a raw, so the export re-decoded at
                                // 51 MP what the import had settled at 12.8 MP -- and a
                                // rebate canvas is 1.51x that. Measured twice: black
                                // screen, launcher restart, on a 12 GB phone.
                                // The budget is checked above, before the `try`, so a
                                // refusal reaches the user instead of becoming a null
                                // session. Here we only size the decode by it.
                                // **0, 0 -- the file's own size, not the import's.**
                                // This used to pass the budget the import settled
                                // on, which is derived from free RAM and therefore
                                // moves: the same frame exported 42 MP and then
                                // 36 MP without anything changing. An export that
                                // changes size between two presses of the same
                                // button is not a memory strategy, it is a bug.
                                // If the frame genuinely does not fit, the engine
                                // refuses and names the plane -- that message is
                                // true, and it is the one to show.
                                final int exportPixels = 0;
                                final int exportEdge = 0;
                                final float[] wbMul = wbUserMul;
                                Probe.log("wb", "export tier=" + tier + " mul=" + Probe.mul(wbMul)
                                        + " px=" + exportPixels + " edge=" + exportEdge);
                                // **What the fresh session is opened with.** The
                                // enlarger exposure has now been sent three ways
                                // (delta, forced delta, open params) and the owner
                                // still sees no change in the file, so the next
                                // report has to say which value actually left the
                                // app. `export-params` reads the session's copy;
                                // this line is the string the engine parsed.
                                try {
                                    DebugSink.text("export-open", "pex=" + f("%.6f", printExposure)
                                            + " auto=" + exportAutoExposure
                                            + " pin=" + (pinEv ? f("%.6f", lastAppliedEv) : "none")
                                            + " offset=" + f("%.4f", cameraEv)
                                            + " paper=" + printStock + " raw=" + rawFilePath);
                                } catch (Throwable ignored) { }
                                fresh = wbMul != null
                                        ? engine.openRawSessionTuned(rawFilePath,
                                                "{\"print_stock\":\"" + (printStock == null ? "kodak_portra_endura" : printStock)
                                                        + "\",\"input_color_space\":\"ProPhoto RGB\","
                                                        + "\"input_cctf_decoding\":false,\"output_color_space\":\"sRGB\","
                                                        // **The meter travels with the export.** This
                                                        // string used to name five fields, so the
                                                        // fresh session fell back to the engine's
                                                        // own defaults: `auto_exposure` true. On a
                                                        // frame whose meter clamps (it read +12 EV
                                                        // here) that put the whole picture at white
                                                        // -- and the rebate, painted afterwards,
                                                        // survived it. The export is the picture on
                                                        // screen, so it has to be told the same
                                                        // thing the preview was told.
                                                        // **Never meter again on export.** The
                                                        // preview's own value, measured once at
                                                        // import; letting the fresh session run
                                                        // the meter a second time is what turned
                                                        // 0.0756 into a clamped +12 and pushed
                                                        // the picture to white.
                                                        + "\"auto_exposure\":" + b(exportAutoExposure) + ","
                                                        + "\"exposure_compensation_ev\":" + f("%.6f", cameraEv) + ","
                                                        // **Open with the enlarger exposure too.** It is
                                                        // a live PRINT field: sent as a delta the engine
                                                        // updates the session's copy while the render
                                                        // keeps the pipeline's, so the file came back as
                                                        // if the slider had never moved (6/6 exports at
                                                        // 0.41, `export-params` in D1). Opening with it
                                                        // puts it on the pipeline from the first frame.
                                                        + "\"print_exposure\":" + f("%.6f", printExposure) + ","
                                                        + "\"preview_long_edge\":2048}",
                                                exportPixels, exportEdge, wbMul)
                                        : engine.openRawSession(rawFilePath,
                                                "{\"print_stock\":\"" + (printStock == null ? "kodak_portra_endura" : printStock)
                                                        + "\",\"input_color_space\":\"ProPhoto RGB\","
                                                        + "\"input_cctf_decoding\":false,\"output_color_space\":\"sRGB\","
                                                        // **The meter travels with the export.** This
                                                        // string used to name five fields, so the
                                                        // fresh session fell back to the engine's
                                                        // own defaults: `auto_exposure` true. On a
                                                        // frame whose meter clamps (it read +12 EV
                                                        // here) that put the whole picture at white
                                                        // -- and the rebate, painted afterwards,
                                                        // survived it. The export is the picture on
                                                        // screen, so it has to be told the same
                                                        // thing the preview was told.
                                                        // **Never meter again on export.** The
                                                        // preview's own value, measured once at
                                                        // import; letting the fresh session run
                                                        // the meter a second time is what turned
                                                        // 0.0756 into a clamped +12 and pushed
                                                        // the picture to white.
                                                        + "\"auto_exposure\":" + b(exportAutoExposure) + ","
                                                        + "\"exposure_compensation_ev\":" + f("%.6f", cameraEv) + ","
                                                        // **Open with the enlarger exposure too.** It is
                                                        // a live PRINT field: sent as a delta the engine
                                                        // updates the session's copy while the render
                                                        // keeps the pipeline's, so the file came back as
                                                        // if the slider had never moved (6/6 exports at
                                                        // 0.41, `export-params` in D1). Opening with it
                                                        // puts it on the pipeline from the first frame.
                                                        + "\"print_exposure\":" + f("%.6f", printExposure) + ","
                                                        + "\"preview_long_edge\":2048}",
                                                exportPixels, exportEdge);
                            } catch (Throwable ignored) { fresh = null; }
                            CrashTrail.markNow(MainActivity.this,
                                    "export:raw-session " + (fresh != null ? "fresh" : "none"));
                        }
                        // **Never swap the global session for an export.**
                        // The export runs on its own local object; assigning it
                        // to `session` let a concurrent UI call write the next
                        // preview's parameters into the export's frame.
                        final SpektraLabEngine.Session exportSession = fresh != null ? fresh : keep;
                        if (exportSession == null) {
                            // The frame's session went away between `export:lock`
                            // and here. Saying so is the honest end of this
                            // frame; dereferencing it was a null pointer.
                            try {
                                DebugSink.text("export-error", "tier=" + tier + " no session for the export");
                            } catch (Throwable ignored) { }
                            runOnUiThread(() -> {
                                showInAppToast("这张照片还没准备好，跳过");
                                if (onDone != null) onDone.run();
                            });
                            return;
                        }
                        if (fresh != null) {
                            try { DebugSink.text("export", "fresh session for tier=" + tier); }
                            catch (Throwable ignored) { }
                        } else if (keepSuspended && keep != null) {
                            // **The export is about to run on the session we just
                            // suspended.** Every branch that builds a fresh session is
                            // wrapped in `catch (Throwable) { fresh = null; }`, so a
                            // refused budget, a raw path the picker never gave us, or a
                            // decode failure all land here -- and `suspend()` has already
                            // dropped the preview's `source` plane while leaving its
                            // dimensions. `tier_image` then hands the pipeline an Image
                            // with a shape and no buffer: `affine: input plane has no
                            // buffer (7387x4952x3)`, on a frame that previews perfectly.
                            // Give the planes back before the export asks for them.
                            try { keep.resume(); } catch (Throwable ignored) { }
                            keepSuspended = false;
                            try { DebugSink.text("export", "no fresh session; preview resumed"); }
                            catch (Throwable ignored) { }
                        }
                        try {
                            // Everything the preview drew with, re-stated: a
                            // stock, a paper, a gate, an edge text or a date
                            // that moved since the last render would otherwise
                            // be exported as the *preview* had it, or not at all.
                            // Re-state the complete Look on the export object,
                            // without routing it through the global `session`.
                            // **Exports are sRGB, whatever the canvas is set to.**
                            // The JPEG and TIFF writers carry an sRGB ICC; a P3
                            // image with an sRGB profile would be read as the
                            // wrong colours by everything that believes it.
                            // **Force.** Diffed against the canvas session this
                            // was a no-op, so every export kept the parameters
                            // its session was opened with -- the enlarger
                            // exposure among them.
                            applyParamsTo(exportSession, filmStock,
                                    printStock == null ? "kodak_portra_endura" : printStock,
                                    PREVIEW_LONG_EDGE, "sRGB", true);
                            // **Same frame, same EV, same node.** Pinning puts
                            // the meter's gain back in `film_prefix`, where the
                            // canvas applied it -- not after the highlight
                            // rolloff, where an export used to put it. When the
                            // EV belongs to another frame (a batch walking the
                            // strip) clearing is the honest state: the meter is
                            // on and its own whole-frame measurement is exactly
                            // what the canvas would have got.
                            try {
                                if (pinEv) exportSession.pinAutoExposureEv(lastAppliedEv);
                                else exportSession.clearPinAutoExposureEv();
                            } catch (Throwable ignored) { }
                            // **Which object got the parameters.** The owner's
                            // files come out at print_exposure 1.0 while this app
                            // sends 0.41 -- on the same frame the host answers
                            // 21410 vs 11996, so the engine is fine and the
                            // parameter is fine. Either this session is not the
                            // one that renders, or something replaces its value
                            // between here and the write. These two lines answer
                            // that with identity, not inference.
                            try {
                                final String gp = exportSession.getParams();
                                int j = gp.indexOf("\"print_exposure\"");
                                DebugSink.text("export-ident", "sent hash=" + System.identityHashCode(exportSession)
                                        + " fresh=" + (fresh != null) + " keep=" + (keep != null)
                                        + " " + (j >= 0 ? gp.substring(j, Math.min(gp.length(), j + 34)) : "no-pex"));
                            } catch (Throwable ignored) { }
                            try {
                                DebugSink.text("export-exposure", "tier=" + tier
                                        + " auto=" + exportAutoExposure
                                        + " method=" + (exportMeter.isEmpty() ? "balanced" : exportMeter)
                                        + " pin=" + (pinEv ? f("%.4f", lastAppliedEv) + " same-frame" : "none")
                                        + " seenEv=" + f("%.4f", lastAppliedEv)
                                        + (lastAppliedEvValid ? "" : " (none)")
                                        + " solveEv=" + f("%.4f", meteredEv)
                                        + " offset=" + f("%.2f", cameraEv));
                            } catch (Throwable ignored) { }
                            try {
                                DebugSink.text("overscan-geom", "tier=" + tier + " before "
                                        + exportSession.overscanGeometry());
                                DebugSink.text("export-params", "tier=" + tier + " " + exportSession.getParams());
                            } catch (Throwable ignored) { }
                            // **Metadata goes in with the pixels.** The writer
                            // appends one APP1 segment and then the ICC profile;
                            // nothing reopens the finished file afterwards.
                            try {
                                if (rawFilePath != null && session != null) {
                                    final String[] meta = sourceExifForExport();
                                    SpektraLabEngine.setExportExif(meta[0], meta[1], meta[2],
                                            meta[3], meta[4], meta[5], meta[6]);
                                    DebugSink.text("exif", "raw source metadata: "
                                            + meta[0] + " / " + meta[1] + " / " + meta[2]
                                            + " / " + meta[3] + "s / f" + meta[4]
                                            + " / ISO" + meta[5] + " / " + meta[6] + "mm");
                                } else if (sourceUri != null) {
                                    final String[] meta = jpegSourceExif();
                                    SpektraLabEngine.setExportExif(meta[0], meta[1], meta[2],
                                            meta[3], meta[4], meta[5], meta[6]);
                                }
                            } catch (Throwable metaError) {
                                DebugSink.text("exif", "export metadata skipped: " + metaError);
                            }
                            // **The export's own black box.** This call runs the
                            // whole film and print chain at `full` and then
                            // encodes it; on a 51 MP frame it is a minute and a
                            // half of native code with no Java frame anywhere in
                            // it. The ticker makes "it died somewhere" into "it
                            // died after 74 seconds".
                            try {
                                final String gp = exportSession.getParams();
                                int j = gp.indexOf("\"print_exposure\"");
                                DebugSink.text("export-ident", "call hash=" + System.identityHashCode(exportSession)
                                        + " " + (j >= 0 ? gp.substring(j, Math.min(gp.length(), j + 34)) : "no-pex"));
                            } catch (Throwable ignored) { }
                            CrashTrail.markNow(this, "export:write " + tier + " " + format);
                            Thread wtick = startTick("export", exportSession);
                            try {
                                info = dng
                                        ? exportSession.exportDng(tmp.getAbsolutePath(), tier)
                                        : tiff
                                        ? exportSession.exportTiff(tmp.getAbsolutePath(), tier)
                                        : exportSession.exportJpeg(tmp.getAbsolutePath(), 95, 512, tier);
                            } finally {
                                stopTick(wtick);
                            }
                            CrashTrail.markNow(this, "export:written " + info[0] + "x" + info[1]);
                            try {
                                DebugSink.text("overscan-geom", "tier=" + tier + " " + info[0] + "x" + info[1]
                                        + " " + exportSession.overscanGeometry());
                            } catch (Throwable ignored) { }
                        } finally {
                            if (fresh != null) {
                                try { fresh.close(); } catch (Throwable ignored) { }
                                // **The export's pool goes back before the
                                // preview's planes come back.** `resume`
                                // allocates persistent buffers, and they cannot
                                // come out of an arena the export is still
                                // sitting in -- the whole reason the preview was
                                // suspended is that the two do not fit at once.
                                try { engine.trimPool(); } catch (Throwable ignored) { }
                            }
                            if (keepSuspended && keep != null) {
                                try { keep.resume(); } catch (Throwable ignored) { }
                            }
                        }
                        // **Hand the pool back before anything else runs.** A
                        // 51 MP full render leaves its whole working set
                        // pooled, and the pool only returns it when the *last*
                        // session closes or a different frame is opened --
                        // neither of which a reused preview session ever does.
                        // Measured: without this the launcher and SystemUI were
                        // evicted and the phone froze for about a minute after
                        // an export that had itself succeeded.
                        try {
                            final long freed = engine.trimPool();
                            DebugSink.text("export", "pool trim freed "
                                    + (freed >> 20) + " MiB; report " + engine.memoryReport());
                        } catch (Throwable ignored) { }
                    }
                } finally {
                    spinning.set(false);
                    try { memTicker.interrupt(); } catch (Throwable ignored) { }
                    runOnUiThread(() -> {
                        if (beforeBitmap == null) beforeBitmap = savedBefore;
                        if (lastRenderedBitmap == null) lastRenderedBitmap = savedAfter;
                        if (compare != null) {
                            compare.setBefore(beforeBitmap);
                            compare.setAfter(lastRenderedBitmap);
                        }
                    });
                }
                uploadExportProgress(tmp);
                uploadVkLog();
                int w = info[0], h = info[1];
                long renderMs = info[2];
                if (w <= 0 || h <= 0 || !tmp.exists() || tmp.length() == 0) {
                    throw new RuntimeException("引擎没有输出图像");
                }

                // (The capture fields are written by the JPEG encoder itself,
                // handed over just above. This used to reopen the finished file
                // and save attributes through the platform class; its guard read
                // `"jpg".equals(ext)` against an `ext` of ".jpg", so for a whole
                // release no export carried any of it -- silently.)
                final int fw = w, fh = h;
                runOnUiThread(() -> statusText.setText("导出中：写入相册 " + fw + "x" + fh + "..."));
                String fileName = "SpektraLab_" + System.currentTimeMillis() + ext;
                final Uri outUri = writeExportFile(tmp, fileName, ext, mime);
                // **Does the file look like the picture we think we wrote?**
                // Two measurements of the smoothed result: the mean difference
                // between neighbouring rows and between neighbouring columns.
                // A photograph has both (their ratio sits near 1); a frame
                // stretched along one axis does not. Cheap enough for every
                // export, and the only evidence a "garbled, once, never again"
                // report can leave behind.
                {
                    final String verdict = inspectExportedFile(tmp.getAbsolutePath());
                    if (verdict != null) {
                        try { DebugSink.textNow("export-selfcheck", verdict); } catch (Throwable ignored) { }
                        runOnUiThread(() -> {
                            if (statusText != null) statusText.setText("导出结构异常：" + verdict);
                        });
                    }
                }
                try {
                    long len = tmp.length();
                    DebugSink.text("export", "format=" + format + " tier=" + tier + " w=" + w + " h=" + h + " renderMs=" + renderMs
                            + (info.length > 4 ? " mean16=" + info[4] : "")
                            + (info.length > 6 ? " pipelinePe=" + (info[5] / 1000.0)
                                    + " pipelineGain=" + (info[6] / 1000.0) : "")
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
                if (onDone != null) runOnUiThread(onDone);
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
    protected void onResume() {
        super.onResume();
        // Coming back is the only place this clears; `startPrefetch` refuses to
        // open a queue while it is set.
        appPaused = false;
    }

    @Override
    protected void onPause() {
        // **Leaving is not dying.** Swiping the app out of recents kills the
        // process without `onDestroy`, which made every ordinary exit look like
        // a crash on the next launch. `onPause` is the last callback that always
        // runs, so it is where "this was deliberate" gets recorded.
        // **Forced, not throttled.** A throttled write inside the same 100 ms
        // window as the last edit would be the one that got dropped, and then
        // the next launch reports an ordinary exit as a crash.
        CrashTrail.markNow(this, "app:paused");
        // **The shelf keeps painting after the user leaves.** The 1.5.0o trail
        // shows fifteen `prefetch:card` lines *after* `app:paused`: a
        // background thread still holding the GPU while the process is a
        // candidate for reclaim. That is wasted work at the one moment it
        // costs the most, and it also overwrote the pause as the ring's last
        // step -- which is why the exit looked like a crash.
        appPaused = true;
        stopPrefetch();
        cancelLivePreview();
        // The system can reclaim this process the moment another app needs the
        // memory, and a 20 MP frame plus a Vulkan context is exactly what it
        // reclaims first. Save before it does, not in onDestroy, which is not
        // called on a kill.
        persistHandler.removeCallbacks(persistSession);
        try { saveLookIntoFrames(); } catch (Throwable ignored) { }
        saveSessionState();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // **Cleared last, not first.** Tearing the engine down is itself a
        // place this can die (device buffers freed while a shelf card still
        // holds them), and clearing on entry would erase the evidence for that
        // one before it happened.
        CrashTrail.markNow(this, "app:onDestroy");
        stopPrefetch();
        cancelLivePreview();
        // Session *and* engine go down under the same lock the render runs
        // under: a shelf card that is mid-`renderBegin` is holding device
        // buffers owned by this engine, and tearing it down outside the lock
        // is the same destroyed-buffer SIGSEGV one layer up.
        synchronized (renderMutex) {
            if (session != null) { session.close(); session = null; }
            if (engine != null) { engine.close(); engine = null; }
            // The source block is the app's largest allocation and has no
            // cleaner behind it; the engine took its own copy at `spk_open`.
            if (sourcePixels != null) { SpektraLabEngine.freeDirect(sourcePixels); sourcePixels = null; }
        }
        // A clean exit erases the trail: next launch reads non-empty only when
        // this one did not get here.
        CrashTrail.clear(this);
        // Nothing may paint after this point: the canvas is going away with the
        // window, and a live frame's callback holds a reference to it.
        uiReady = false;
        super.onDestroy();
    }
}
