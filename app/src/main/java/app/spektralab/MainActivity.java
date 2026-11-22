package app.spektralab;

import static app.spektralab.AppLanguage.tr;

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
import app.spektralab.ui.UiPalette;
import app.spektralab.domain.JsonFields;
import app.spektralab.domain.JsonQuote;
import app.spektralab.domain.ExportPlan;
import app.spektralab.domain.PixelHash;
import app.spektralab.domain.EdgeVerdict;
import app.spektralab.ui.CompareView;
import app.spektralab.ui.CurveView;
import app.spektralab.ui.FilmCoverView;
import app.spektralab.ui.ColorWheel;
import app.spektralab.ui.CropOverlay;
import app.spektralab.ui.RotateScale;
import app.spektralab.ui.HistogramView;
import app.spektralab.ui.RatioMark;
import app.spektralab.ui.GuideView;
import app.spektralab.ui.EdgeBackdrop;
import app.spektralab.ui.Square;
import app.spektralab.ui.ArrowView;
import app.spektralab.ui.ToolGlyph;
import app.spektralab.ui.CappedScroll;
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
    /**
     * Which canvas a render belongs to.
     *
     * <p>Bumped whenever the frame underneath is replaced -- a new import, a
     * reset, deleting the strip. A render that comes back for an epoch that no
     * longer exists must not paint, and (just as important) it must hand the
     * RENDERING plate back: the old code dropped the bitmap and returned, which
     * took the only `rendering = false` with it.
     */
    private int renderEpoch = 0;
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
    // Device-golden test hooks (default 0/false; never set by normal UI use).
    private static int testBandRows = 0;
    private static boolean testDeterministic = false;
    private static boolean testAutoFilm = false;

    /** Data-layer boundaries; implementations are in app.spektralab.data. */
    private app.spektralab.domain.PhotoRepository photoRepository;
    private app.spektralab.data.PresetRepository presetRepository;
    private app.spektralab.data.StockCatalogRepository stockCatalogRepository;
    private app.spektralab.domain.AppPreferences appPreferences;
    private app.spektralab.domain.ImportController importController;
    private app.spektralab.data.MediaStoreExport exportStore;
    private final app.spektralab.domain.ExportController exportController =
            new app.spektralab.domain.ExportController();
    private final app.spektralab.domain.RenderController renderController =
            new app.spektralab.domain.RenderController();

    private Bitmap sourceBitmap;
    private Uri sourceUri;
    /** An ACTION_VIEW URI that arrived before the canvas was ready. */
    private Uri pendingIncomingUri = null;
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
    /** **A way out that is on screen for the whole batch.** `showExportDialog`
     *  already offers 停止, but it is two taps deep and the sheet that holds it
     *  is the same one that *starts* an export. A batch can run ten minutes;
     *  the stop control belongs next to the progress, not behind a menu. */
    private TextView abortBatchPill;
    /** The 构图 → 旋转 reference grid. On by default: the whole point of the
     *  dial is levelling a horizon, and without lines there is nothing to
     *  level against. */
    private boolean rotateGridOn = true;
    private RotateScale rotateArc;
    /** The committed angle when the finger went down: a failed render has to
     *  put the picture back where it was, not leave it turned in the numbers
     *  and straight on the screen. */
    private float rotationBeforeDrag = 0f;
    private boolean rotationPending = false;
    /**
     * **The geometry as it was when the user committed it.**
     *
     * <p>The render thread sends this, not the live fields. It used to read
     * them ~90 ms after the commit -- `spk_set_params` spends that long in a
     * rebuild -- and on another thread. `SL-261111-FAA1` shows what that
     * window costs: the full delta read `geometry_rotation_deg = -8.643` at
     * 59.971 and `sendGeometry` read `-0.000` at 00.059, on the same render
     * thread. Something on the UI thread zeroed the field in between, the
     * engine got 0, and the picture came back upright with the scale still
     * reading 8.64. A value that is decided on one thread and delivered on
     * another has to be captured where it is decided.
     */
    private float geoSnapRot, geoSnapX, geoSnapY, geoSnapW = 1f, geoSnapH = 1f;
    private int geoSnapTurns;
    private boolean geoSnapFlipH, geoSnapFlipV, geoSnapValid;
    /**
     * **A geometry edit is owed to the engine.** Set by every crop / rotate
     * commit and consumed by the render thread, which sends it on its own
     * before the ordinary delta. See `sendGeometry`.
     */
    private boolean pendingGeometry = false;
    /** Bumped by every geometry commit; a render from before it must not clear
     *  the preview turn (it does not carry it). */
    private int geometrySeq = 0;
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
    private String[] METER_NAMES;
    private String[] METER_NOTES;

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
        try { saveLookIntoFrames(); } catch (Throwable spkIgnored) { Diagnostics.ignored("parse:257", spkIgnored); }
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

    private void loadLocalizedLabels() {
        METER_NAMES = new String[] {
                tr("meter.off"), tr("meter.balanced"), tr("meter.protect_highlights"), tr("meter.protect_shadows"), tr("meter.center"), tr("meter.center_weighted"), tr("meter.average"), tr("meter.median")};
        METER_NOTES = new String[] {
                tr("meter.skip_metering_and_use_the"),
                tr("meter.align_the_full_frame_median"),
                tr("meter.start_with_balanced_and_hold"),
                tr("meter.start_with_balanced_and_lift"),
                tr("meter.meter_only_the_center_of"),
                tr("meter.average_with_gaussian_center_weighting"),
                tr("meter.take_the_unweighted_average_across"),
                tr("meter.take_the_median_luminance_across")};
        MODE_LABELS = new String[] {tr("meter.full_strip"), tr("meter.filed_edge")};
        GATE_LABELS = new String[] {tr("meter.auto"), tr("meter.square"), tr("meter.rounded"), tr("meter.tabbed"), tr("meter.shouldered"), tr("meter.notched")};
        HOLES_LABELS = new String[] {tr("meter.white_sprocket_holes_scan"), tr("meter.black_sprocket_holes_darkroom")};
        CARRIER_LABELS = new String[] {tr("meter.none"), tr("meter.black_holder"), tr("meter.open")};
        PLACE_LABELS = new String[] {tr("meter.in_image"), tr("meter.on_film_border")};
        STYLE_LABELS = new String[] {tr("meter.seven_segment_lcd"), tr("meter.dot_matrix"), tr("meter.shooting_data")};
        CORNER_LABELS = new String[] {tr("meter.bottom_right"), tr("meter.bottom_left"), tr("meter.top_right"), tr("meter.top_left")};
        EDGE_LABELS = new String[] {
                tr("meter.35_mm_full_frame"), tr("meter.35_mm_half_frame"), "135 XPan", "645",
                "6×6", "6×7", "6×8", "6×9", "6×12", "6×17"};
        BRAND_NAMES = new String[] {tr("meter.all"), "KODAK", "FUJIFILM", "ILFORD", "CINESTILL"};
        FORMAT_NAMES = EDGE_LABELS;
        FILTER_LABELS = new String[] {tr("meter.none"), tr("meter.w8_yellow"), tr("meter.w11_yellow_green"), tr("meter.w12_deep_yellow"), tr("meter.w15_deep_yellow"),
                tr("meter.w21_orange"), tr("meter.w25_red"), tr("meter.w47_blue"), tr("meter.w58_green")};
        NORM_LABELS = new String[] {tr("meter.power"), tr("meter.luminance_y"), tr("meter.peak_max")};
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        photoRepository = new app.spektralab.data.AndroidPhotoRepository(this);
        importController = new app.spektralab.domain.ImportController(photoRepository);
        exportStore = new app.spektralab.data.MediaStoreExport(this);
        presetRepository = new app.spektralab.data.SharedPreferencesPresetRepository(this);
        appPreferences = new app.spektralab.data.SharedPreferencesAppPreferences(this, "ui");
        stockCatalogRepository = new app.spektralab.data.AssetStockCatalogRepository();
        readTestExtras(getIntent());
        AppLanguage.load(this);
        loadLocalizedLabels();
        // **Before anything logs, and before anything reports.** The ring lives
        // in memory; init only opens the one-slot retry file. A crash report
        // shipped from `uploadCrashFiles` needs that file when the network is
        // down, so init has to come first.
        DebugSink.init(this);
        installCrashLogger();
        // **The handler is installed further down, not here.** It lives in the
        // engine library, and a call made before `prepare` loads that library
        // threw `UnsatisfiedLinkError` straight into a `catch (Throwable)` --
        // so every native crash for three months wrote nothing at all and the
        // reports said only "reason=5". Ship what the last death wrote now;
        // arm the handler the moment the library exists.
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
        UiPalette.apply(appPreferences.getBoolean("dark", false));
        CrashTrail.mark(this, "app:onCreate");
        // The native side, before anything reads it: every object the engine
        // is linked against is loaded by path, and a failure is kept (see
        // SpektraLabEngine.ensureLoaded) so that the import reports the real
        // reason instead of a NoClassDefFoundError on the class itself.
        String nativeDir = null;
        try { nativeDir = getApplicationInfo().nativeLibraryDir; } catch (Throwable spkIgnored) { Diagnostics.ignored("onCreate:414", spkIgnored); }
        try { SpektraLabEngine.prepare(nativeDir); } catch (Throwable e) { lastImportError = describe(e); }
        // **Now, and only now.** `prepare` is what loads the library the
        // handler lives in; the first dispatch can crash, so this is as early
        // as it can be armed and as late as it must be.
        installNativeCrashHandler();
        // **Recorded, not uploaded.** `app-start` used to be a POST on every
        // launch, and it alone was enough to fill the free D1 quota over a few
        // hundred users. It now goes into the in-memory ring, which is what a
        // report carries when something actually goes wrong.
        try { DebugSink.text("app-start", "buildInfo=" + SpektraLabEngine.buildInfo()); } catch (Throwable spkIgnored) { Diagnostics.ignored("onCreate:424", spkIgnored); }
        
        buildUi();
        uiReady = true;
        // The strip and the look come back on the next frame, not inside
        // onCreate: re-decoding a 50 MP RAW here would hold the first layout
        // for seconds and read as a hung launch. One empty frame first, then
        // the photograph arrives.
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            restoreSessionState();
            pruneThumbFiles();
            pruneDiagnosticFiles();
            // The manifest advertises "open with" for image/* content and file
            // URIs. Consume the launch intent only after the saved strip has
            // come back, so an external photograph is not overwritten by the
            // restore of the previous session.
            handleIncomingIntent(getIntent());
        });

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
    // Theme tokens live in UiPalette.
    private View overscanRow, dateRow;

    private Typeface tfDisplay;      // Inter 300 — numbers and the wordmark
    private Typeface tfBody;         // Inter 400 — Latin labels
    private Typeface tfHan;          // OPPO Sans — Chinese, and anything mixed
    private View emptyState, rawState;
    private TextView fileLabel, rawName, busyLabel, originalLabel, paperValue, edgeValue, formatFootValue;
    /** The 尺度 page's foot caption -- it says who owns the frame's length. */
    private TextView formatFootLabel;
    /** True while the millimetre cards are locked because a rebate is on. */
    private boolean formatLocked = false;
    // The editable value, the frame in the strip and the panel's slider model
    // are plain data holders now (Look.java, Frame.java, Knob.java).
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
    private String[] MODE_LABELS;
    private static final String[] GATE_IDS = {"auto", "square", "rounded", "eared", "shouldered", "kicked"};
    private String[] GATE_LABELS;
    private static final String[] HOLES_IDS = {"white", "black"};
    private String[] HOLES_LABELS;
    private static final String[] CARRIER_IDS = {"none", "black", "open"};
    private String[] CARRIER_LABELS;
    private static final String[] PLACE_IDS = {"frame", "rebate"};
    private String[] PLACE_LABELS;
    private static final String[] CORNER_IDS = {"br", "bl", "tr", "tl"};
    private static final String[] STYLE_IDS = {"lcd", "dots", "data"};
    private String[] STYLE_LABELS;
    private String[] CORNER_LABELS;
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
    private String[] EDGE_LABELS;
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

    /** The gate's position in EDGE_IDS (an array, not a list). */
    private static int gateIndex(String id) {
        for (int i = 0; i < EDGE_IDS.length; ++i) if (EDGE_IDS[i].equals(id)) return i;
        return -1;
    }

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

    private String gateLabelsFallback(String id) {
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
        refreshFormatFoot();
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

    // The Look codec is a pure data mapping; see LookCodec.java.
    private static org.json.JSONObject lookToJson(Look l) { return LookCodec.toJson(l); }
    private static Look lookFromJson(org.json.JSONObject o) { return LookCodec.fromJson(o); }

    // Thumbnail and scratch-file handling is file I/O, not UI; see ThumbCache.
    private void pruneDiagnosticFiles() { ThumbCache.pruneDiagnostics(this); }
    private void pruneThumbFiles() { ThumbCache.prune(this, frames); }
    private void persistThumb(Frame frame) { ThumbCache.persist(this, frame); }

    private void saveSessionState() {
        try {
            final String json = SessionState.toJson(frames, currentFrame, shelfCollapsed).toString();
            // Never overwrite a good state file with an empty document.
            if (!"{}".equals(json)) {
                getSharedPreferences(PREFS_SESSION, MODE_PRIVATE).edit()
                        .putString(KEY_SESSION, json).apply();
            }
        } catch (Throwable t) {
            try { DebugSink.text("session", "save failed: " + t); } catch (Throwable spkIgnored) { Diagnostics.ignored("toString:844", spkIgnored); }
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
        } catch (Throwable spkIgnored) { Diagnostics.ignored("restoreSessionState:859", spkIgnored); }
        if (raw == null || raw.isEmpty()) return false;
        try {
            final SessionState.Snapshot snapshot = SessionState.fromJson(raw, this);
            if (snapshot == null) return false;
            frames.clear();
            frames.addAll(snapshot.frames);
            final String savedUri = snapshot.currentUri;
            // **A frame whose grant expired can never be opened again.** Left in
            // the strip it re-ran `loadImage`, hit the same dialog, and kept the
            // dead chip for the next launch; dropped here it costs one toast and
            // the start is clean from then on.
            int dropped = 0;
            for (int i = frames.size() - 1; i >= 0; --i) {
                if (!canReadUri(frames.get(i).uri)) {
                    frames.remove(i);
                    ++dropped;
                }
            }
            if (dropped > 0) {
                try {
                    DebugSink.text("session", "dropped " + dropped + " unreadable frames");
                } catch (Throwable spkIgnored) { Diagnostics.ignored("fromJson:881", spkIgnored); }
                showInAppToast(dropped + tr("frame.photos_were_removed_from_the"));
            }
            if (frames.isEmpty()) return false;
            // The saved index was an index into the old list. The frame itself
            // is what has to come back, so it is looked up again by URI.
            int pick = -1;
            for (int i = 0; i < frames.size(); ++i) {
                if (frames.get(i).uri.toString().equals(savedUri)) { pick = i; break; }
            }
            currentFrame = pick >= 0 ? pick : frames.size() - 1;
            shelfCollapsed = snapshot.collapsed;
            rebuildFrameChips();
            setShelfCollapsed(shelfCollapsed);
            try {
                DebugSink.text("session", "restored " + frames.size() + " frames, current=" + currentFrame);
            } catch (Throwable spkIgnored) { Diagnostics.ignored("fromJson:897", spkIgnored); }
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
            // **Bring the frame back the way it was left.** A frame that was
            // parked on 原图 must not come back developed just because it still
            // carries a look -- that is the same jump the chips had.
            final boolean restoredOriginal = frames.get(currentFrame).original;
            reopenToOriginal = restoredOriginal;
            loadImage(frames.get(currentFrame).uri);
            // **A frame that had a film gets its print back.** The decode is
            // the whole of `loadImage`; without this an edit that was safely
            // on disk still came back as the original, because nothing ever
            // asked for a render. The strip already says "this is the
            // photograph you were working on" -- the canvas has to agree.
            if (filmStock != null && !restoredOriginal) renderImage();
            return true;
        } catch (Throwable t) {
            try { DebugSink.text("session", "restore failed: " + t); } catch (Throwable spkIgnored) { Diagnostics.ignored("get:922", spkIgnored); }
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
    /** Set by `reopenFrameForced`: this frame was left on 原图, so do not render it. */
    private boolean reopenToOriginal = false;
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
    /** `SPK_BAND_CAP`: how many elements one band may cover. 0 = the engine's
     *  own 2^21. Raising it is the only way to ask "is this a band seam?" on a
     *  device that shows the symptom and not on one that does not. */
    private static final String DEBUG_BAND_CAP = "band_cap";
    private static final String PROMO_PREFS = "promo";
    private static final String PROMO_SEEN_CODE = "qq_group_seen_code";
    private static final String PROMO_MUTED = "qq_group_muted";

    private static final String[] BRAND_IDS = {"all", "kodak", "fujifilm", "ilford", "cinestill"};
    private String[] BRAND_NAMES;
    // **One card per gate, in the gates' own order.** This used to be a
    // separate ladder (36/24/65/56/56/70/84/120/170) that only resembled the
    // gates: 6x8 had no card, 6x12 and 6x17 were 120/170, and 56 appeared twice
    // with nothing to say which was which -- so "645" and "135 半格" could not be
    // told apart from "6x6", and two of the ten gates had no card at all. Now
    // index i is EDGE_IDS[i], which is what `applyGate` needs.
    private String[] FORMAT_NAMES;
    private static final float[] FORMAT_MM = EDGE_MM;
    private app.spektralab.ui.TabBar tabBar;
    private android.widget.FrameLayout uiRoot;
    private TextView inAppToast;
    private final Runnable hideUiToast = () -> {
        if (inAppToast == null) return;
        inAppToast.animate().cancel();
        inAppToast.animate().alpha(0f).translationY(dp(6)).setDuration(160)
                .withEndAction(() -> { if (inAppToast != null) inAppToast.setVisibility(View.GONE); }).start();
    };
    private final View[] panels = new View[6];
    /** Every dialog this Activity owns, so onDestroy can release its window. */
    private final java.util.List<android.app.Dialog> liveDialogs = new java.util.ArrayList<>();

    private android.app.Dialog newDialog() {
        final android.app.Dialog dialog = new android.app.Dialog(this);
        liveDialogs.add(dialog);
        return dialog;
    }

    private void dismissDialogs() {
        for (android.app.Dialog d : liveDialogs) {
            try { if (d.isShowing()) d.dismiss(); } catch (Throwable spkIgnored) { Diagnostics.ignored("dismissDialogs:1143", spkIgnored); }
        }
        liveDialogs.clear();
    }

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
            getWindow().setStatusBarColor(UiPalette.STAGE);
            getWindow().setNavigationBarColor(UiPalette.PAPER);
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
                    if (!UiPalette.dark()) on |= android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    int off = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                    c.setSystemBarsAppearance(on, on | off);
                }
            } else {
                int flags = 0;
                if (!UiPalette.dark() && android.os.Build.VERSION.SDK_INT >= 26) {
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

    

    private void buildUi() {
        loadUiFonts();
        lightSystemBars();

        final android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(UiPalette.PAPER);
        applySystemInsets(root);
        setContentView(root);
        uiRoot = root;

        LinearLayout app = new LinearLayout(this);
        app.setOrientation(LinearLayout.VERTICAL);
        app.setBackgroundColor(UiPalette.PAPER);
        root.addView(app, new android.widget.FrameLayout.LayoutParams(-1, -1));

        // The photograph is the page. The brand and Export float over it, the
        // source strip sits below it, and the tool panels sit below that.
        final android.widget.FrameLayout stage = new android.widget.FrameLayout(this);
        canvasStage = stage;
        stage.setBackgroundColor(UiPalette.STAGE);
        stage.setClipChildren(false);

        compare = new CompareView(this);
        // **The date can be dragged onto the picture.** Its position is
        // `corner` plus an inset in millimetres from the gate's edge -- exact,
        // and impossible to aim with two sliders. While the date page is open a
        // touch moves it; everywhere else this returns false and the canvas
        // keeps its pinch, its pan and its compare handle.
        compare.setOnTouchListener(this::onCanvasTouch);
        // **Turning the picture turns only the view.** A real re-resample is a
        // second of GPU work and, on a 128 MiB driver, a refusal; the commit
        // happens once, on release, and the preview angle is dropped when the
        // rendered frame already carries it.
        compare.setRotateSink(new CompareView.RotateSink() {
            @Override public void onRotateStart() {
                rotationBeforeDrag = geometryRotation;
            }
            @Override public void onRotateDelta(float total) { previewRotate(total); }
            @Override public void onRotateCommit(float total) { commitRotate(total); }
            @Override public void onRotateReset() {
                try { DebugSink.text("rotate", "reset by double tap (was " + geometryRotation + ")"); }
                catch (Throwable spkIgnored) { Diagnostics.ignored("onRotateReset:1364", spkIgnored); }
                rotationBeforeDrag = geometryRotation;
                geometryRotation = 0f;
                rotationPending = true;
                if (rotateArc != null) rotateArc.setAngle(0f);
                if (compare != null) compare.setPreviewRotation(0f);
                commitCompose();
            }
        });
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
        load.setBackgroundColor(UiPalette.PAPER);
        load.setPadding(dp(18), 0, dp(18), 0);
        load.setOnClickListener(v -> showImportMenu());
        load.addView(label(tr("frame.choose_photos"), 9, UiPalette.BLUE, tfHan));
        ArrowView loadArrow = new ArrowView(this, UiPalette.BLUE);
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
        TextView rawTitle = label(tr("frame.raw_loaded"), 34, UiPalette.onCanvas(), tfDisplay);
        rawTitle.setLetterSpacing(-0.055f);
        raw.addView(rawTitle);
        rawName = label("", 9, UiPalette.onCanvas(), tfHan);
        rawName.setAlpha(0.7f);
        rawName.setLineSpacing(dp(4), 1f);
        rawName.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams rawNameLp = new LinearLayout.LayoutParams(-2, -2);
        rawNameLp.topMargin = dp(13);
        raw.addView(rawName, rawNameLp);
        stage.addView(raw, new android.widget.FrameLayout.LayoutParams(-1, -1));
        rawState = raw;

        // On the canvas, so it is the canvas's contrast colour -- `UiPalette.PAPER`
        // is the *panel* colour and went black on the dark theme.
        busyLabel = label(tr("frame.rendering"), 18, UiPalette.dark() ? UiPalette.BLUE : UiPalette.onCanvas(), tfDisplay);
        busyLabel.setLetterSpacing(0.14f);
        busyLabel.setGravity(android.view.Gravity.CENTER);
        // **The rendering plate, and it was a literal of the blue canvas.**
        // At 90% opacity it covered the whole canvas, which is why the dark
        // theme still looked navy while everything else had gone black. It is
        // now the canvas colour itself, so it is invisible in both themes.
        busyLabel.setBackgroundColor(0xE6000000 | (UiPalette.STAGE & 0x00FFFFFF));
        busyLabel.setVisibility(View.GONE);
        stage.addView(busyLabel, new android.widget.FrameLayout.LayoutParams(-1, -1));

        cropOverlay = new CropOverlay(this, new CropOverlay.Delegate() {
                @Override public android.graphics.RectF imageRect() {
                    return compare == null ? null : compare.imageRect();
                }
                @Override public boolean onTouchCompare(android.view.MotionEvent e) {
                    return compare != null && compare.onTouchEvent(e);
                }
                @Override public void onCommit(float x, float y, float w, float h,
                                               boolean held, int drag) {
                    pendingCropX = x; pendingCropY = y;
                    pendingCropW = w; pendingCropH = h;
                    if (cropRatioIndex == -2) {
                        // already custom
                    } else if (cropRatioIndex == -1) {
                        final boolean whole = w >= 0.999f && h >= 0.999f
                                && x <= 0.001f && y <= 0.001f;
                        if (!whole) cropRatioIndex = -2;
                    } else if (drag != 1 && !held) {
                        cropRatioIndex = -2;
                    }
                    Probe.log("crop", "drag=" + drag + " held=" + held
                            + " ratio=" + cropRatioIndex + " W=" + w + " H=" + h);
                    showComposePage(0);
                }
            });
        cropOverlay.setVisibility(View.GONE);
        stage.addView(cropOverlay, new android.widget.FrameLayout.LayoutParams(-1, -1));

        addCanvasChrome(stage);
        addPreviewFooter(stage);

        // Ink on white in both themes, because the disc is white in both -- the
        // dark palette's ink is a light grey, which would disappear into it.
        comparePill = label("↔", 15, UiPalette.ON_WHITE, tfBody);
        comparePill.setGravity(android.view.Gravity.CENTER);
        comparePill.setVisibility(View.GONE);
        comparePill.setOnClickListener(v -> {
            if (compare == null) return;
            final boolean on = !compare.isSplitMode();
            compare.setSplitMode(on);
            comparePill.setTextColor(on ? UiPalette.BLUE : UiPalette.ON_WHITE);
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
        progressBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(UiPalette.PAPER));
        progressBar.setVisibility(View.GONE);
        jobs.addView(progressBar, new android.widget.FrameLayout.LayoutParams(-1, -1));
        exportProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        exportProgressBar.setIndeterminate(true);
        exportProgressBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(UiPalette.PAPER));
        exportProgressBar.setVisibility(View.GONE);
        jobs.addView(exportProgressBar, new android.widget.FrameLayout.LayoutParams(-1, -1));
        stage.addView(jobs, new android.widget.FrameLayout.LayoutParams(-1, dp(2), android.view.Gravity.BOTTOM));

        app.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));
        app.addView(buildSourceBar(), new LinearLayout.LayoutParams(-1, dp(62)));

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackgroundColor(UiPalette.PAPER);

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
        refreshPanelLocks();
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
                            } catch (Throwable spkIgnored) { Diagnostics.ignored("getReason:1694", spkIgnored); }
                        }
                    }
                }
            } catch (Throwable e) {
                // **Silence here is what made the first report unreadable.** An
                // exit row that cannot be read looks exactly like one that was
                // never written, and the two mean opposite things.
                body.append(tr("crash.exit_could_not_read_applicationexitinfo")).append(e).append(")\n");
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
        } catch (Throwable spkIgnored) { Diagnostics.ignored("File:1724", spkIgnored); }
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
            case 2:  return tr("crash.signaled_terminated_by_a_signal");
            case 3:  return tr("crash.low_memory_reclaimed_by_android");
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
            b.setTitle(reclaimed ? tr("crash.android_closed_the_app_last") : tr("crash.the_app_closed_unexpectedly_last"));
            b.setMessage(reclaimed
                    ? tr("crash.last_step") + where
                        + tr("crash.android_ran_low_on_memory")
                        + tr("crash.you_can_send_this_report")
                    : tr("crash.last_step") + where
                        + tr("crash.would_you_like_to_send"));
            b.setPositiveButton(tr("crash.send_log"), (d, w) -> shareCrashReport());
            b.setNegativeButton(tr("crash.close"), null);
            b.show();
        } catch (Throwable spkIgnored) { Diagnostics.ignored("Builder:1790", spkIgnored); }
    }

    private void shareCrashReport() {
        try {
            final String body = lastCrashFile == null ? "" : readTextFile(lastCrashFile, 32768);
            final StringBuilder text = new StringBuilder(tr("crash.spektralab_crash_report"));
            text.append("build=").append(SpektraLabEngine.buildInfo()).append('\n');
            // `probe` is filled by `ensureEngine()`, and this report is offered
            // on the launch *after* the exit -- before the user has imported
            // anything. "设备信息读取失败" there is the engine simply not having
            // been created yet, and it reads as a broken device probe.
            text.append("device=").append(deviceLine()).append("\n\n");
            text.append("ref=").append(DebugSink.REF).append('\n');
            text.append(body);
            final Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, tr("crash.spektralab_crash_report_2"));
            send.putExtra(Intent.EXTRA_TEXT, text.toString());
            startActivity(Intent.createChooser(send, tr("crash.send_crash_report")));
        } catch (Throwable t) {
            if (statusText != null) statusText.setText(tr("crash.nothing_to_send"));
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
    /**
     * **Does the file look like the picture that was on the canvas?**
     *
     * <p>The stretch check below compares a file with itself, so a frame that
     * came back as uninitialised memory passes it: `SL-261111-252F` exported a
     * 5973x3222 JPEG whose gate held fine row-banded noise -- row-internal std
     * 11.5, row-to-row 8.7, ratio 1.0 -- with the rebate, the date and the
     * sprockets all drawn perfectly on top. `X/Y contrast` cannot see that.
     *
     * <p>Correlating against the canvas can: the canvas is the same picture
     * with the same look at a smaller size, so a normal export lands around
     * 0.95 and noise lands near 0. Coarse (48 columns) so resampling and JPEG
     * cannot move it.
     *
     * @return the correlation, or -1 when it cannot be measured.
     */


    /**
     * The engine's SIGSEGV handler writes a backtrace to this file. Installing
     * it is the only way a native crash produces evidence on a ROM without
     * `ApplicationExitInfo`: the Java handler never runs, and the sink queue is
     * lost with the process.
     */
    private void installNativeCrashHandler() {
        int code = -1;
        try {
            java.io.File f = new java.io.File(getFilesDir(), "native-crash.txt");
            code = SpektraLabEngine.installCrashHandler(f.getAbsolutePath());
        } catch (Throwable spkIgnored) { Diagnostics.ignored("installNativeCrashHandler:1952", spkIgnored); }
        // 0 armed, 1 already armed, 2 no path, 3 open failed, 4 sigaction
        // failed, 5 the library was not loaded. In the trail of every report,
        // so the next "reason=5 with no native-crash row" answers itself.
        CrashTrail.mark(this, "crash-handler " + code);
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
            } catch (Throwable spkIgnored) { Diagnostics.ignored("versionLine:1989", spkIgnored); }
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
            } catch (Throwable spkIgnored) { Diagnostics.ignored("getFilesDir:2012", spkIgnored); }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /**
     * The device, or the last one this install reported.
     *
     * <p>Plenty of deaths happen before `ensureEngine`, and the report used to
     * say "(engine not created this launch)" -- which is true, and useless:
     * 181 of 291 crash reports in a week carried no GPU at all, including every
     * native crash. `rememberDevice` keeps the last description this install
     * managed to read.
     */
    private String deviceLine() {
        if (probe != null && probe.valid) return probe.describe();
        final String last = getSharedPreferences("ui", MODE_PRIVATE).getString("last_device", null);
        return last == null ? "(engine not created this launch)" : last + tr("editor.previous_launch");
    }

    private void rememberDevice() {
        try {
            if (probe == null || !probe.valid) return;
            getSharedPreferences("ui", MODE_PRIVATE).edit()
                    .putString("last_device", probe.describe()).apply();
        } catch (Throwable spkIgnored) { Diagnostics.ignored("rememberDevice:2037", spkIgnored); }
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
                + "device=" + deviceLine() + "\n";
    }

    // **The only automatic upload that is not a crash.** At most one a minute,
    // and never the same failure twice in a row: an error that repeats in a
    // loop (a bad parameter on every drag) must not become a write per frame.
    private long lastReportAt = 0L;
    private String lastReportKey = "";

    private void reportFailure(String title, String body) {
        // Automatic error uploads are diagnostics, not part of the app's job.
        // The 关于 switch is the single consent for every automatic upload.
        if (!DeviceReport.enabled(this)) return;
        final long now = System.currentTimeMillis();
        final String key = title + "|" + body;
        if (key.equals(lastReportKey) && now - lastReportAt < 60_000L) return;
        if (now - lastReportAt < 5_000L) return;
        lastReportKey = key;
        lastReportAt = now;
        final String head = "error\n" + reportHead()
                + "stage=" + AppFailure.fromTitle(title) + "\ntitle=" + title + "\n";
        new Thread(() -> DebugSink.report("error", head, body), "SpektraLab-report").start();
    }

    /** All in-app messages use this paper/ink toast, never Android's Toast. */
    void showInAppToast(final CharSequence message) {
        if (message == null || message.length() == 0) return;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            UiDispatcher.post(() -> showInAppToast(message));
            return;
        }
        if (uiRoot == null) return;
        if (inAppToast == null) {
            inAppToast = label(String.valueOf(message), 10, UiPalette.PAPER, tfHan);
            inAppToast.setMaxWidth(Math.round(getResources().getConfiguration().screenWidthDp
                    * 0.82f * getResources().getDisplayMetrics().density));
            inAppToast.setGravity(android.view.Gravity.CENTER);
            inAppToast.setMaxLines(4);
            inAppToast.setEllipsize(android.text.TextUtils.TruncateAt.END);
            inAppToast.setPadding(dp(14), dp(10), dp(14), dp(10));
            inAppToast.setBackgroundColor(UiPalette.INK);
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
        final int rgb = UiPalette.STAGE & 0x00FFFFFF;
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
        // `UiPalette.PAPER` is the panel colour: white on the light theme (correct on
        // the navy canvas) and near-black on the dark one, where it vanished
        // into the black. The wordmark is always the canvas's *contrast* colour.
        TextView word = label("SpektraLab", 18, UiPalette.onCanvas(), tfDisplay);
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
        menuPill.setContentDescription(tr("editor.settings"));
        menuPill.setOnClickListener(v -> showAbout());
        // **Left of the wordmark, not between it and the version.** The menu
        // belongs at the edge of the bar, where every other app puts it;
        // between the name and its version number it reads as part of the name.
        final LinearLayout.LayoutParams menuLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        menuLp.rightMargin = dp(10);
        menuLp.topMargin = dp(1);
        wordRow.addView(menuPill, 0, menuLp);
        TextView version = label("v" + UpdateCheck.versionName(this), 6.5f, UiPalette.onCanvas(), tfBody);
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
        undoPill.setContentDescription(tr("editor.undo"));
        undoPill.setOnClickListener(v -> undoStep());
        LinearLayout.LayoutParams undoLp = new LinearLayout.LayoutParams(-2, dp(32));
        undoLp.rightMargin = dp(6);
        top.addView(undoPill, undoLp);

        sharePill = label(tr("editor.share"), 11, UiPalette.onCanvas(), tfHan);
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
        export.setContentDescription(tr("editor.export"));
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
        rotateGridOn = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("rotate_grid", true);
        rawAutoBright = getSharedPreferences("ui", MODE_PRIVATE)
                .getBoolean("raw_auto_bright", false);
        histogram = new HistogramView(this);
        // The curve page draws the same bins as a backdrop; without this it
        // would sit on the distribution from the last time *it* was laid out.
        histogram.setOnChanged(() -> { if (curveView != null) curveView.invalidate(); });
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

        fileLabel = label(tr("editor.no_photo_selected"), 7, UiPalette.onCanvas(), tfHan);
        fileLabel.setAlpha(0.76f);
        fileLabel.setSingleLine(true);
        fileLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        fileLabel.setShadowLayer(dp(2), 0, dp(1), 0x99000000);
        foot.addView(fileLabel, new LinearLayout.LayoutParams(0, -2, 1f));

        statusText = label(tr("editor.waiting_for_import"), 7, UiPalette.onCanvas(), tfHan);
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

        abortBatchPill = label(tr("editor.stop"), 7, 0xFFFFFFFF, tfHan);
        abortBatchPill.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable abortBg =
                new android.graphics.drawable.GradientDrawable();
        abortBg.setCornerRadius(dp(3));
        abortBg.setColor(0xFFC8741E);
        abortBatchPill.setBackground(abortBg);
        abortBatchPill.setPadding(dp(9), dp(2), dp(9), dp(2));
        abortBatchPill.setVisibility(View.GONE);
        abortBatchPill.setOnClickListener(v -> {
            if (!batchExportRunning) return;
            batchExportAbort = true;
            statusText.setText(tr("editor.stopping_after_the_current_photo"));
        });
        LinearLayout.LayoutParams abortLp = new LinearLayout.LayoutParams(-2, -2);
        abortLp.leftMargin = dp(8);
        foot.addView(abortBatchPill, abortLp);

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
        final app.spektralab.ui.PanelFactories.SourceBar built =
                app.spektralab.ui.PanelFactories.sourceBar(this,
                        (text, sp, color, tf) -> label(text, sp, color, tf),
                        tfDisplay, tfHan, getResources().getDisplayMetrics().density,
                        this::showImportMenu, () -> setShelfCollapsed(true));
        sourceBar = built.root;
        sourceRow = built.row;
        thumbScroll = built.scroll;
        thumbStrip = built.strip;
        return built.root;
    }

    /** Folded: three cards stacked at the canvas's lower-left corner, the way a
     *  short stack of prints looks -- one picture, two edges behind it, and a
     *  chevron that says the rest of the strip is that way. */
    private View buildShelfHandle() {
        final app.spektralab.ui.PanelFactories.ShelfHandle built =
                app.spektralab.ui.PanelFactories.shelfHandle(this,
                        (text, sp, color, tf) -> label(text, sp, color, tf),
                        tfDisplay, getResources().getDisplayMetrics().density,
                        () -> setShelfCollapsed(false));
        shelfHandleImage = built.image;
        return built.root;
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
        rebuildFormatCards();
        refreshFormatFoot();
        refreshPaperLabel();
        refreshViewLock();
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
        final Frame f = frames.get(currentFrame);
        f.look = captureLook();
        f.original = showingOriginal;
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
                    } catch (Throwable spkIgnored) { Diagnostics.ignored("round:2791", spkIgnored); }
                }
                rebuildFrameChips();
                return;
            }
        }
        final Frame frame = new Frame(uri, isRawFile, name);
        frame.look = captureLook();
        frame.original = true;            // an import lands on 原图
        if (beforeBitmap != null) {
            try {
                final int w = Math.max(1, dp(42));
                final int h = Math.max(1, Math.round(
                        w * beforeBitmap.getHeight() / (float) beforeBitmap.getWidth()));
                frame.thumb = Bitmap.createScaledBitmap(beforeBitmap, w, h, true);
            } catch (Throwable spkIgnored) { Diagnostics.ignored("round:2806", spkIgnored); }
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
            bg.setColor(UiPalette.GROUND);
            bg.setStroke(dp(active ? 2 : 1), active ? UiPalette.BLUE : UiPalette.LINE);
            box.setBackground(bg);
            box.setPadding(dp(2), dp(2), dp(2), dp(2));
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (frame.thumb != null) img.setImageBitmap(frame.thumb);
            box.addView(img, new android.widget.FrameLayout.LayoutParams(-1, -1));
            // **Not while a batch is exporting.** `batchExportRunning` already
            // gates `startPrefetch`, `showImportMenu`, `showExportDialog` and
            // the preview render inside `loadImage`, but not this -- and this
            // is the one that closes the session the batch is in the middle of
            // exporting. A field report (Mali-G925, 1.6.1w) shows the order:
            // `frame:reopen 6 of 7` lands between `export:write` and
            // `export:written`, three more imports start behind it, one of them
            // comes back `import:source none`, and the process dies with
            // SIGSEGV at 7.6 GB RSS. One toast is cheaper than that.
            box.setOnClickListener(v -> {
                if (batchExportRunning) {
                    showInAppToast(tr("editor.wait_for_batch_export_to"));
                    return;
                }
                reopenFrame(index);
            });
            box.setOnLongClickListener(v -> {
                // Deleting a frame mid-batch is the same race from the other
                // side: the loop reads `frames.get(index)` after every await.
                if (batchExportRunning) {
                    showInAppToast(tr("editor.wait_for_batch_export_to_2"));
                    return true;
                }
                showActionSheet(frame.name == null ? tr("editor.this_photo") : frame.name,
                        tr("editor.settings_are_per_photo_applying"),
                        new String[]{tr("editor.apply_edits_to_other_photos"), tr("editor.delete_selected_photos"),
                                tr("editor.delete_this_photo"), tr("editor.cancel")}, which -> {
                            if (which == 0) showApplyLookSheet(index);
                            else if (which == 1) showBatchDeleteSheet();
                            else if (which == 2) removeFrame(index);
                        });
                return true;
            });
            box.setContentDescription(frame.name == null ? tr("editor.photo") : frame.name);
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
        } catch (Throwable spkIgnored) { Diagnostics.ignored("getChildAt:2884", spkIgnored); }
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
            statusText.setText(tr("editor.waiting_for_import"));
            // No frame, no chain: the same state a reset lands in.
            filmStock = null;
            renderEpoch++;
            refreshPanelLocks();
            rebuildFilmCards();
        }
        rebuildFrameChips();
        saveSessionState();
        try { DebugSink.text("frame-strip", "removed " + index + ", now " + frames.size()); }
        catch (Throwable spkIgnored) { Diagnostics.ignored("removeFrame:2929", spkIgnored); }
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
        reopenToOriginal = frame.original;
        suppressBudgetDialog = true;
        rebuildFrameChips();
        loadImage(frame.uri);
    }

    private void setOriginalVisual(boolean on) {
        if (originalAction == null) return;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(on ? UiPalette.BLUE : UiPalette.PAPER);
        originalAction.setBackground(bg);
        if (originalGlyph != null) originalGlyph.setColor(on ? UiPalette.PAPER : UiPalette.BLUE);
        if (originalLabel != null) originalLabel.setTextColor(on ? UiPalette.PAPER : UiPalette.BLUE);
    }

    private LinearLayout sectionHead(String title, Runnable reset) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView titleView = mixedLabel(title, 8.5f, UiPalette.INK);
        titleView.setLetterSpacing(0.06f);
        head.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView resetView = label(tr("editor.reset_this_page"), 8.5f, UiPalette.MUTED, tfHan);
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
                    } catch (Throwable spkIgnored) { Diagnostics.ignored("getActionMasked:3013", spkIgnored); }
                }
                endTwist();
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
                } catch (Throwable spkIgnored) { Diagnostics.ignored("spanOf:3031", spkIgnored); }
                statusText.setText("ZOOM " + String.format(java.util.Locale.US, "%.2f", canvasPinchStartZoom)
                        + " · 0%");
                beginTwist(ev);
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
            // The same two fingers also carry an angle; on the rotate page it
            // turns the picture (see `updateTwist`). Off that page it is a
            // no-op, so pinch-to-zoom keeps exactly the code it had.
            updateTwist(ev);
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
        final app.spektralab.ui.FilmPanel built = app.spektralab.ui.FilmPanel.build(
                this, new app.spektralab.ui.FilmPanel.Host() {
                    @Override public String text(String key) { return tr(key); }
                    @Override public TextView label(String text, float sp, int color,
                                                     android.graphics.Typeface tf) {
                        return MainActivity.this.label(text, sp, color, tf);
                    }
                    @Override public LinearLayout sectionHead(String title, Runnable reset) {
                        return MainActivity.this.sectionHead(title, reset);
                    }
                    @Override public void onMeterClick() { showMeterPicker(); }
                    @Override public void onPaperClick() { showPaperPicker(); }
                    @Override public void onReset() { resetFilmPanel(); }
                }, tfHan, tfBody, getResources().getDisplayMetrics().density);
        meterChip = built.meterChip;
        brandRow = built.brandRow;
        filmScroll = built.filmScroll;
        filmCardStrip = built.filmCardStrip;
        paperValue = built.paperValue;
        refreshMeterChip();
        return built.root;
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
                    8.5f, id.equals(brandFilter) ? UiPalette.BLUE : UiPalette.MUTED,
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
        if (index < filmTypes.size() && "positive".equals(filmTypes.get(index))) return base + tr("film.slide");
        if (index < filmIds.size() && isMonochromeStock(filmIds.get(index))) return base + tr("film.b_w");
        return base;
    }

    /** A hairline box with no fill. The grid's cells are dividers, not panels:
     *  a grey block behind each one read as dirt on the paper. */
    private android.graphics.drawable.GradientDrawable cellBorder() {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(android.graphics.Color.TRANSPARENT);
        bg.setStroke(dp(1), UiPalette.LINE);
        return bg;
    }

    private android.graphics.drawable.GradientDrawable cardBorder(boolean active) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(UiPalette.GROUND);
        bg.setStroke(dp(1), active ? UiPalette.BLUE : UiPalette.LINE);
        return bg;
    }

    /** Four colours and three lines: everything a box front is. */


    /**
     * The palette of a film's box.
     *
     * <p>Nothing here is artwork. The recognition lives in four things -- the
     * brand band, the stock's own colour, the wordmark, the name -- and all
     * four can be *stated*, which is what this does. Copying the printed boxes
     * would mean licensing 25 pieces of trade dress to fill a placeholder.
     */


    /**
     * A film box, drawn from a palette.
     *
     * <p>Shown on the shelf only when there is no photograph yet -- once a
     * frame is loaded the card is the stock's own rendering of that frame,
     * which is more informative than any package. Before that, a grey
     * rectangle with the name typed into it is a placeholder; this reads as a
     * film at a glance, which is what the shelf is for.
     */


    private void rebuildFilmCards() {
        if (filmCardStrip == null) return;
        final int keepScroll = filmScroll == null ? 0 : filmScroll.getScrollX();
        filmCardStrip.removeAllViews();
        filmCards.clear();
        filmCardImages.clear();
        addFilmCard("original", tr("film.original"), -1);
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
        } catch (Throwable spkIgnored) { Diagnostics.ignored("get:3527", spkIgnored); }
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
        imageBox.setBackgroundColor(original ? UiPalette.PAPER : UiPalette.GROUND);
        filmCardImages.put(id, imageBox);
        if (original) {
            TextView mark = label("—", 22, UiPalette.MUTED, tfDisplay);
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

        TextView name = label(text, 7.5f, active ? UiPalette.BLUE : UiPalette.INK, tfHan);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setBackgroundColor(UiPalette.PAPER);
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
        } catch (Throwable spkIgnored) { Diagnostics.ignored("getWidth:3609", spkIgnored); }
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
                ((TextView) group.getChildAt(1)).setTextColor(active ? UiPalette.BLUE : UiPalette.INK);
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
        refreshViewLock();
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
            paperValue.setText(tr("film.no_paper_direct_scan"));
            paperValue.setTextColor(UiPalette.MUTED);
            return;
        }
        paperValue.setTextColor(UiPalette.INK);
        int i = printIds.indexOf(printStock);
        paperValue.setText(i >= 0 && i < printLabels.size()
                ? shortStockName(printLabels.get(i)) : printStock);
    }

    private void showPaperPicker() {
        if (printIds.isEmpty()) return;
        if (isPositiveStock(filmStock)) {
            showInAppToast(tr("film.slide_film_is_scanned_directly"));
            return;
        }
        // The current paper, by its **label**: this sheet matched the id
        // against a list of names, so nothing was ever marked as selected.
        final int current = printIds.indexOf(printStock);
        final List<String> notes = new ArrayList<>();
        for (String id : printIds) notes.add(paperNote(id));
        final boolean filmKnown = filmStock != null;
        final boolean filmMono = isMonochromeStock(filmStock);
        showChoiceSheet(tr("film.choose_paper_or_print_film"), printLabels, notes,
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
            case "print.knob.kodak_portra_endura":  return tr("film.neutral_lifted_blacks_good_for");
            case "print.knob.kodak_endura_premier": return tr("film.brighter_deep_blacks_high_contrast");
            case "print.knob.kodak_supra_endura":   return tr("film.neutral_moderate_contrast");
            case "print.knob.kodak_ultra_endura":   return tr("film.cool_neutral_moderate_contrast");
            case "print.knob.kodak_ektacolor_edge": return tr("film.low_saturation_soft");
            case "print.knob.fujifilm_crystal_archive_typeii": return tr("film.cyan_bias_fuji_tones");
            case "print.knob.ilford_multigrade_iv_rc": return tr("film.b_w_paper_variable_contrast");
            case "print.knob.kodak_2383":  return tr("film.warm_deep_blacks_cinematic");
            case "print.knob.kodak_2393":  return tr("film.deepest_blacks_high_saturation");
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
    /** Live frames between histogram refreshes (see `drawLivePreview`). */
    private int liveHistCounter;
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


    private LinearLayout buildViewPanel() {
        final LinearLayout panel = new LinearLayout(this);
        viewRoot = panel;
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(sectionHead(tr("optics.optics"), this::resetViewPanel),
                new LinearLayout.LayoutParams(-1, dp(38)));
        // **Say why, instead of letting a live page do nothing.**
        //
        // The optics layer is applied to the engine's *output* -- `grade_result`
        // on export, `copyArgbRange` on the canvas -- so with no film there is
        // nothing for it to sit on: the sliders moved and the picture did not.
        // A dead page with no explanation is what made a user ask for a "no
        // film mode"; he read the silence as a missing feature. This line is the
        // cheap half of that answer.
        viewLockNote = label(tr("optics.choose_a_film_first_these"),
                8.5f, UiPalette.MUTED, tfHan);
        viewLockNote.setPadding(dp(2), dp(4), dp(2), dp(8));
        panel.addView(viewLockNote, new LinearLayout.LayoutParams(-1, -2));

        final String[] cats = {tr("optics.light"), tr("optics.color"), tr("optics.effects"), tr("optics.curves"), tr("optics.grading")};
        final LinearLayout catRow = new LinearLayout(this);
        catRow.setOrientation(LinearLayout.HORIZONTAL);
        viewCatChips = new TextView[cats.length];
        for (int i = 0; i < cats.length; ++i) {
            final int page = i;
            final TextView chip = label(cats[i], 8, i == 0 ? UiPalette.BLUE : UiPalette.MUTED, tfBody);
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
        // **Locked from the first frame.** An import lands with no film, so the
        // page has nothing to act on; leaving it bright until the first render
        // would teach the opposite of what the note says.
        refreshViewLock();
        return panel;
    }

    private final CappedScroll[] viewScrolls = new CappedScroll[5];

    private void showViewPage(int page) {
        viewPage = page;
        for (int i = 0; i < viewPages.length; ++i) {
            final boolean on = i == page;
            if (viewPages[i] != null) viewPages[i].setVisibility(on ? View.VISIBLE : View.GONE);
            if (viewScrolls[i] != null) viewScrolls[i].setVisibility(on ? View.VISIBLE : View.GONE);
            if (viewCatChips != null) viewCatChips[i].setTextColor(on ? UiPalette.BLUE : UiPalette.MUTED);
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
        line.addView(label(title, 9, UiPalette.INK, tfHan), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView value = label("", 9, UiPalette.BLUE, tfBody);
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
            final TextView note = label(hint, 7.5f, UiPalette.MUTED, tfHan);
            note.setMaxLines(2);
            box.addView(note, new LinearLayout.LayoutParams(-1, dp(18)));
        }
        parent.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    /** The 光学 page's root and its "what this page is" line. */
    private LinearLayout viewRoot;
    private TextView viewLockNote;
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
        wbDecodeBox.addView(label(tr("optics.raw_white_balance"), 8, UiPalette.INK, tfHan),
                new LinearLayout.LayoutParams(-1, dp(22)));
        final String[] names = {tr("optics.as_shot"), tr("optics.daylight"), tr("optics.cloudy"), tr("optics.shade"), tr("optics.tungsten"), tr("optics.fluorescent"), tr("optics.custom")};
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        final TextView[] chips = new TextView[names.length];
        for (int i = 0; i < names.length; ++i) {
            final int preset = i;
            final TextView chip = label(names[i], 8, i == 0 ? UiPalette.BLUE : UiPalette.MUTED, tfBody);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setBackground(outlineBox(i == 0));
            chip.setOnClickListener(v -> {
                wbPreset = preset;
                for (int k = 0; k < chips.length; ++k) {
                    chips[k].setTextColor(k == preset ? UiPalette.BLUE : UiPalette.MUTED);
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
        wbDecodeNote = label(tr("optics.these_controls_set_the_light")
                + tr("optics.changing_them_decodes_the_image"), 7.5f, UiPalette.MUTED, tfHan);
        wbDecodeNote.setMaxLines(2);
        wbDecodeBox.addView(wbDecodeNote, new LinearLayout.LayoutParams(-1, dp(32)));
        adjustRow(wbDecodeBox, tr("optics.raw_temperature"), 2000f, 12000f, () -> wbKelvin, v -> wbKelvin = v, true,
                tr("optics.light_source_temperature_3200_k"));
        adjustRow(wbDecodeBox, tr("optics.raw_tint"), -150f, 150f, () -> wbTint, v -> wbTint = v, true,
                tr("optics.green_magenta_balance_increase_to"));
    }

    private void buildTonePage(LinearLayout page) {
        // **Three controls, one row.** Two full-width switch rows plus a lone
        // 「复位」 on a line of its own was the tallest thing on the shortest
        // page; the same treatment as the print page is 46 dp instead of 128.
        LinearLayout toneGrid = new LinearLayout(this);
        toneGrid.setOrientation(LinearLayout.HORIZONTAL);
        // The histogram's own switch. It changes no pixels, so it must not go
        // through `applyParams` -- it shows or hides the plate on the canvas,
        // and the choice outlives the photograph.
        final Square histMark = new Square(this, UiPalette.BLUE, UiPalette.toggleOff());
        toggleCell(toneGrid, tr("optics.histogram"), tr("optics.show"), histMark, () -> histogramOn, on -> {
            histogramOn = on;
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("histogram", on).apply();
            if (histogram != null) histogram.setVisibility(on ? View.VISIBLE : View.GONE);
        });
        choiceCell(toneGrid, tr("optics.reset"), tr("optics.reset_all"), tv -> resetViewPanel());

        // **How the canvas reads the decode, not what the engine gets.**
        // Off: the numbers exactly as LibRaw handed them, through sRGB only.
        // On: the median goes to sRGB mid-grey (capped where p99.5 would clip)
        // and the encoded shadows are lifted. That is how every raw was shown
        // from 1.5.1 through 1.6.6a, after an import that came back at 2/255
        // looked like a black rectangle -- but it also hides the picture's own
        // exposure, and it ran 2.54 EV above Lightroom on the reference DNG.
        final Square levelMark = new Square(this, UiPalette.BLUE, UiPalette.toggleOff());
        toggleCell(toneGrid, tr("optics.original_brightness"), tr("meter.auto"), levelMark, () -> rawAutoBright, on -> {
            rawAutoBright = on;
            getSharedPreferences("ui", MODE_PRIVATE).edit()
                    .putBoolean("raw_auto_bright", on).apply();
            refreshOriginalView();
        });
        page.addView(toneGrid, new LinearLayout.LayoutParams(-1, dp(46)));

        adjustRow(page, tr("optics.camera_exposure"), -4f, 4f, () -> cameraEv, v -> cameraEv = v, false);
        adjustRow(page, tr("optics.exposure"), -3f, 3f, () -> viewExposure, v -> viewExposure = v, false);
        adjustRow(page, tr("optics.contrast"), -100f, 100f, () -> viewContrast, v -> viewContrast = v, false);
        adjustRow(page, tr("optics.brightness"), -100f, 100f, () -> viewBrightness, v -> viewBrightness = v, false);
        adjustRow(page, tr("optics.highlights"), -100f, 100f, () -> viewHighlights, v -> viewHighlights = v, false);
        adjustRow(page, tr("optics.shadows"), -100f, 100f, () -> viewShadows, v -> viewShadows = v, false);
        adjustRow(page, tr("optics.blacks"), 0f, 50f, () -> viewBlack, v -> viewBlack = v, false);
        adjustRow(page, tr("optics.whites"), 0f, 50f, () -> viewWhite, v -> viewWhite = v, false);
    }

    private void buildColourPage(LinearLayout page) {
        // LightCraft's Color tab opens on the white balance: the presets, then
        // the two decode-time numbers, then the print-side ones.
        buildWhiteBalancePage(page);
        page.addView(label(tr("optics.color_2"), 8, UiPalette.INK, tfHan),
                new LinearLayout.LayoutParams(-1, dp(24)));
        final TextView wbNote = label(
                tr("optics.the_controls_above_re_decode")
                + tr("optics.including_its_highlights"), 7.5f, UiPalette.MUTED, tfHan);
        wbNote.setMaxLines(2);
        page.addView(wbNote, new LinearLayout.LayoutParams(-1, dp(32)));
        adjustRow(page, tr("optics.temperature"), -100f, 100f, () -> viewTemperature, v -> viewTemperature = v, false,
                tr("optics.warm_or_cool_the_finished"));
        adjustRow(page, tr("optics.tint"), -100f, 100f, () -> viewTint, v -> viewTint = v, false,
                tr("optics.adjust_the_finished_print_s"));
        adjustRow(page, tr("optics.saturation"), -100f, 100f, () -> viewSaturation, v -> viewSaturation = v, false);
        adjustRow(page, tr("optics.vibrance"), -100f, 100f, () -> viewVibrance, v -> viewVibrance = v, false,
                tr("optics.boost_muted_colors_in_darker"));

    }

    private void buildGradingPage(LinearLayout page) {
        // Four zones, one wheel -- Lightroom's shape: pick the tonal range,
        // then drag a dot. Hue is the angle, saturation the distance from grey.
        final String[] zones = {tr("optics.global"), tr("optics.shadows"), tr("optics.midtones"), tr("optics.highlights")};
        final LinearLayout zoneRow = new LinearLayout(this);
        zoneRow.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] zoneChips = new TextView[zones.length];
        for (int i = 0; i < zones.length; ++i) {
            final int zone = i;
            final TextView chip = label(zones[i], 8, i == 0 ? UiPalette.BLUE : UiPalette.MUTED, tfBody);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setOnClickListener(v -> {
                cbZone = zone;
                for (int k = 0; k < zoneChips.length; ++k)
                    zoneChips[k].setTextColor(k == zone ? UiPalette.BLUE : UiPalette.MUTED);
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

        colorWheel = new ColorWheel(this, new ColorWheel.Delegate() {
                @Override public float hue(int zone) { return cbHue[zone]; }
                @Override public float sat(int zone) { return cbSat[zone]; }
                @Override public int zone() { return cbZone; }
                @Override public void setHue(int zone, float value) { cbHue[zone] = value; }
                @Override public void setSat(int zone, float value) { cbSat[zone] = value; }
                @Override public void onValueChanged() { refreshCbReadout(); }
                @Override public void onLive() { requestLivePreview(); }
                @Override public void onCommit() {
                    pushViewAdjust();
                    applyParams();
                    if (uiReady && session != null) {
                        showingOriginal = false;
                        setOriginalVisual(false);
                        renderImage();
                    }
                }
            });
        page.addView(colorWheel, new LinearLayout.LayoutParams(-1, dp(196)));

        cbReadout = label("", 8, UiPalette.MUTED, tfBody);
        cbReadout.setGravity(android.view.Gravity.CENTER);
        page.addView(cbReadout, new LinearLayout.LayoutParams(-1, dp(20)));
        refreshCbReadout();

        adjustRow(page, tr("optics.luminance"), -1f, 1f, () -> cbLum[cbZone], v -> cbLum[cbZone] = v, false,
                tr("optics.lighten_or_darken_this_tonal"));
    }

    private int cbZone = 0;
    private ColorWheel colorWheel;
    private TextView cbReadout;

    private void refreshCbReadout() {
        if (cbReadout == null) return;
        final int hue = Math.round(cbHue[cbZone]);
        cbReadout.setText(AppLanguage.format("optics.hue_d_saturation_0f", hue, cbSat[cbZone] * 100f));
    }

    /**
     * The grading wheel: hue is the angle, saturation is how far the handle is
     * from the middle, and the middle is "no cast".
     */
    

    private void buildEffectsPage(LinearLayout page) {
        adjustRow(page, tr("optics.vignette"), -100f, 100f, () -> viewVignette, v -> viewVignette = v, false);
        page.addView(label(tr("optics.lens_blur_halation_and_glare")
                + tr("optics.rather_than_grading_the_finished"), 7.5f, UiPalette.MUTED, tfHan),
                new LinearLayout.LayoutParams(-1, dp(30)));
    }

    private void buildCurvesPage(LinearLayout page) {
        final String[] channels = {"RGB", tr("optics.luminance_2"), "R", "G", "B"};
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] chips = new TextView[channels.length];
        for (int i = 0; i < channels.length; ++i) {
            final int ch = i;
            final TextView chip = label(channels[i], 8, i == 0 ? UiPalette.BLUE : UiPalette.MUTED, tfBody);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setOnClickListener(v -> {
                curveChannel = ch;
                for (int k = 0; k < chips.length; ++k)
                    chips[k].setTextColor(k == ch ? UiPalette.BLUE : UiPalette.MUTED);
                if (curveView != null) curveView.invalidate();
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(26), 1f);
            lp.rightMargin = dp(4);
            row.addView(chip, lp);
            chips[i] = chip;
        }
        page.addView(row, new LinearLayout.LayoutParams(-1, dp(28)));
        curveView = new CurveView(this, new CurveView.Delegate() {
                @Override public java.util.List<float[]> points(int channel) { return curvePoints[channel]; }
                @Override public int channel() { return curveChannel; }
                @Override public HistogramView histogram() { return histogram; }
                @Override public android.graphics.Typeface typeface() { return tfBody; }
                @Override public void onLive() { requestLivePreview(); }
                @Override public void onCommit() {
                    pushViewCurves();
                    if (uiReady && session != null) {
                        showingOriginal = false;
                        setOriginalVisual(false);
                        renderImage();
                    }
                }
            });
        page.addView(curveView, new LinearLayout.LayoutParams(-1, dp(150)));
        page.addView(label(tr("optics.drag_the_control_points_or"), 7.5f, UiPalette.MUTED, tfHan),
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
                .setPositiveButton(tr("optics.ok"), (d, w) -> {
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
                    } catch (Throwable spkIgnored) { Diagnostics.ignored("trim:4620", spkIgnored); }
                })
                .setNegativeButton(tr("editor.cancel"), null)
                .show();
    }

    private android.graphics.drawable.GradientDrawable outlineBox(boolean selected) {
        android.graphics.drawable.GradientDrawable box = new android.graphics.drawable.GradientDrawable();
        box.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        box.setCornerRadius(0f);
        box.setColor(0x00000000);
        box.setStroke(dp(selected ? 2 : 1), selected ? UiPalette.BLUE : UiPalette.LINE);
        return box;
    }

    private void refreshViewPanel() {
        for (Runnable r : viewRefreshers) {
            try { r.run(); } catch (Throwable spkIgnored) { Diagnostics.ignored("refreshViewPanel:4637", spkIgnored); }
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
                        ? tr("optics.these_controls_set_the_light")
                          + tr("optics.changing_them_decodes_the_image")
                        : tr("optics.jpeg_white_balance_is_baked")
                          + tr("optics.to_grade_the_finished_print"));
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
        } catch (Throwable spkIgnored) { Diagnostics.ignored("pushViewAdjust:4705", spkIgnored); }
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
            showInAppToast(tr("optics.jpeg_white_balance_is_baked_2"));
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
        UiDispatcher.post(() -> {
            if (statusText != null) statusText.setText(tr("optics.decoding_again"));
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
                        catch (Throwable spkIgnored) { Diagnostics.ignored("round:4748", spkIgnored); }
                    }
                    // **Carried to the export.** A fresh export session is a
                    // second decode of the same file; without these gains it
                    // starts from the camera's as-shot balance, so the preview
                    // and the file disagree by exactly the WB the user dialled.
                    wbUserMul = mul;
                    Probe.log("wb", "decode preset=" + wbPreset + " K=" + kelvin
                            + " tint=" + tint + " mul=" + Probe.mul(mul) + " cap=" + cap);
                    try { params = current.getParams(); } catch (Throwable spkIgnored) { Diagnostics.ignored("round:4757", spkIgnored); }
                    try { current.close(); } catch (Throwable spkIgnored) { Diagnostics.ignored("round:4758", spkIgnored); }
                    session = null;
                }
                SpektraLabEngine.Session fresh = mul != null
                        ? engine.openRawSessionTuned(rawFilePath, params, cap, edge, mul)
                        : engine.openRawSession(rawFilePath, params, cap, edge);
                if (generation != wbGeneration) {
                    // A newer choice arrived while this one decoded; its own
                    // thread will finish the job.
                    try { fresh.close(); } catch (Throwable spkIgnored) { Diagnostics.ignored("round:4767", spkIgnored); }
                    return;
                }
                synchronized (renderMutex) { session = fresh; }
                final Bitmap src = exportSourceBitmap(fresh, 0.0);
                wbDecoding = false;
                UiDispatcher.post(() -> {
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
                UiDispatcher.post(() -> {
                    if (session == null) session = current;
                    showErrorDialog(tr("optics.could_not_re_decode_with"), describe(t));
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
        try { SpektraLabEngine.setViewCurves(packed); } catch (Throwable spkIgnored) { Diagnostics.ignored("pushViewCurves:4808", spkIgnored); }
    }

    /** Fritsch-Carlson, for drawing the curve the engine will actually use. */


    /** A square you draw the curve in: drag a point, tap to add, hold to remove. */
    

    private LinearLayout buildOpticsPanel() {
        LinearLayout panel = new LinearLayout(this);
        // **The field the lock reads.** Without this line `setOpticsUnlocked`
        // hit `if (opticsPanel == null) return;` every single time and the page
        // was never dimmed -- which is why "原图状态下冲印还是亮的" survived a
        // version that was supposed to fix it.
        opticsPanel = panel;
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(sectionHead(tr("print.print_settings"), this::resetOpticsPanel),
                new LinearLayout.LayoutParams(-1, dp(38)));

        // The readout: which slider this is, and what it is set to.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        knobTitle = label("", 9, UiPalette.INK, tfHan);
        head.addView(knobTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        knobValue = label("", 9, UiPalette.BLUE, tfBody);
        head.addView(knobValue);
        panel.addView(head, new LinearLayout.LayoutParams(-1, dp(20)));
        // What the knob does, in one line: twenty-two parameter names are a
        // vocabulary, not an explanation, and the engine's own names ("层间抑制")
        // are the least self-evident of them.
        knobHintView = label("", 7.5f, UiPalette.MUTED, tfHan);
        knobHintView.setMaxLines(1);
        knobHintView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        panel.addView(knobHintView, new LinearLayout.LayoutParams(-1, dp(15)));
        knobCost = label("", 7.5f, UiPalette.MUTED, tfHan);
        knobCost.setGravity(android.view.Gravity.END);
        panel.addView(knobCost, new LinearLayout.LayoutParams(-1, dp(14)));

        knobBar = new ScrubBar(this);
        knobBar.setMax(1000);
        styleRange(knobBar);
        knobBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (knobs.isEmpty()) return;
                final Knob k = knobs.get(activeKnob);
                final float v = knobValue(k, progress);
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
        // **32 dp, the same as every other slider in the app.** It was 44 here
        // and 44 in `rangeRow`, against 32 in `adjustRow` -- and because the
        // track's thickness came from the row height, those two drew a track
        // four times thicker than the optics page.
        panel.addView(knobBar, new LinearLayout.LayoutParams(-1, dp(32)));

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

        // **A switch, not a 0..1 knob**, for the same reason as EDR above: the
        // field has two meaningful values and a slider would invent 98 others
        // that all render the same. On = the stock's own hues (the 1.6.7 look);
        // off = the green-window correction.

        // **Log travel.** 0.25 / 1.0 / 4.0 are equal stops, and 1.0 is the
        // neutral: on a linear bar it sat at 20 %, which is the "越右越暗" half
        // only. With a ratio scale the default is the middle of the track.
        knob("print.knob.enlarger_exposure_right_is_darker", "E",  0.25f, 4.0f,   v -> printExposure = v,   () -> printExposure, false, true);
        knob("print.knob.highlight_protection",   "H",  0.0f,  3.0f,   v -> highlightRoom = v,   () -> highlightRoom, true);
        knob("print.knob.shadow_protection",   "S",  0.0f,  3.0f,   v -> shadowRoom = v,      () -> shadowRoom, true);
        knob("print.knob.grain",       "G",  0.0f,  2.0f,   v -> grainAmount = v,     () -> grainAmount, true);
        knob("print.knob.halation",       "○",  0.0f,  4.0f,   v -> halationAmount = v,  () -> halationAmount, true);
        knob("print.knob.halation_boost",   "+",  0.0f,  8.0f,   v -> halationBoost = v,   () -> halationBoost, true);
        knob("print.knob.emulsion_scatter",   "~",  0.0f,  1.0f,   v -> halationScatter = v, () -> halationScatter, true);
        knob("print.knob.scatter_radius",   "◍",  0.0f,  1.0f,   v -> scatterRadius = v,  () -> scatterRadius, true);
        knob("print.knob.glare",       "✳",  0.0f,  12.0f,  v -> glareAmount = v,     () -> glareAmount);
        knob("print.knob.lens_blur",   "◌",  0.0f,  200.0f, v -> lensBlur = v,        () -> lensBlur, true);
        knob("print.knob.yellow_filter",     "Y",  -1.0f, 1.0f,   v -> yFilter = v,         () -> yFilter);
        knob("print.knob.magenta_filter",   "M",  -1.0f, 1.0f,   v -> mFilter = v,         () -> mFilter);
        knob("print.knob.filter_strength",   "C",  1.0f,  60.0f,  v -> filterScale = v,     () -> filterScale);

        knob("print.knob.pre_flash",       "⚡", 0.0f,  1.0f,   v -> preflash = v,        () -> preflash);
        knob("print.knob.curve_softness",   "∿",  1.0f,  4.0f,   v -> shadowRolloff = v,   () -> shadowRolloff, true);
        knob("print.knob.maximum_shadow_lift",   "L",  0.25f, 12.0f,  v -> maxLift = v,         () -> maxLift, true);
        // The switches, on a 0/1 slider: the panel has one slider by design,
        // and a second kind of control would be a second thing to explain.
        knob("print.knob.enable_halation",   "◐",  0.0f,  1.0f,   v -> halationOn = v,       () -> halationOn, true);
        knob("print.knob.remove_anti_halation",   "⊘",  0.0f,  1.0f,   v -> antihalationRemoved = v, () -> antihalationRemoved, true);
        knob("print.knob.enable_grain",   "g",  0.0f,  1.0f,   v -> grainOn = v,          () -> grainOn, true);
        knob("print.knob.interlayer_inhibition",   "⧉",  0.0f,  1.0f,   v -> couplersOn = v,       () -> couplersOn, true);
        knob("print.knob.scan_whites",     "W",  0.0f,  1.0f,   v -> scannerWhite = v,     () -> scannerWhite);
        knob("print.knob.scan_blacks",     "B",  0.0f,  1.0f,   v -> scannerBlack = v,     () -> scannerBlack);
        // Six more that the schema has had all along and the panel never
        // showed. Booleans ride a 0..1 knob because the knob is the panel's
        // one control; the readout says "0.00" / "1.00" and the hint says
        // which end is which.
        knob("print.knob.enable_glare",   "☀",  0.0f,  1.0f,   v -> glareActive = v,      () -> glareActive);
        knob("print.knob.scanner_blur", "S",  0.0f, 20.0f,   v -> scannerLensBlur = v,  () -> scannerLensBlur);
        knob("print.knob.halation_headroom",   "R",  0.0f,  8.0f,   v -> halationHeadroom = v, () -> halationHeadroom, true);
        knob("print.knob.density_curve",   "γ",  0.2f,  4.0f,   v -> densityGamma = v,     () -> densityGamma, true);
        knob("print.knob.digital_intermediate", "I",  0.0f,  1.0f,   v -> digitalIntermediate = v > 0.5f,
                                                                    () -> digitalIntermediate ? 1f : 0f);
        knob("print.knob.output_encoding",   "E",  0.0f,  1.0f,   v -> outputCctf = v > 0.5f,
                                                                    () -> outputCctf ? 1f : 0f);

        // **Six controls, two rows.** This page is the tallest one in the app;
        // on a 640 dp screen six full-width rows at the bottom pushed the tab
        // bar off it. Three to a row is the same six controls in 92 dp instead
        // of 204 -- and the two switches read better beside each other than
        // alone above twenty-two knobs.
        LinearLayout gridTop = new LinearLayout(this);
        gridTop.setOrientation(LinearLayout.HORIZONTAL);
        choiceCell(gridTop, tr("print.color_timing"), tr("print.rebalance_print"), tv -> resolvePrinting());
        edrMark = new Square(this, UiPalette.BLUE, UiPalette.toggleOff());
        toggleCell(gridTop, tr("print.dynamic_range"), "EDR", edrMark,
                () -> edrOn > 0.5f,
                on -> {
                    edrOn = on ? 1f : 0f;
                    if (uiReady && session != null) {
                        showingOriginal = false;
                        setOriginalVisual(false);
                        renderImage();
                    }
                });
        hueOwnMark = new Square(this, UiPalette.BLUE, UiPalette.toggleOff());
        toggleCell(gridTop, tr("print.film_hue"), tr("print.keep_film_colors"), hueOwnMark,
                () -> hueFixUnsupported(filmStock) || hueCorrection < 0.5f,
                on -> {
                    if (hueFixUnsupported(filmStock)) {
                        // Pinned on for this stock: say why instead of silently
                        // flipping back, or the switch reads as broken.
                        refreshHueOwnMark();
                        showActionSheet(tr("print.hue_correction_unavailable_for_this"),
                                tr("print.this_stock_s_spectral_calibration")
                            + tr("print.correction_would_shift_the_hues")
                                + tr("print.choose_a_different_film_to"),
                                new String[]{tr("print.ok")}, which -> { });
                        return;
                    }
                    hueCorrection = on ? 0f : 1f;
                    if (uiReady && session != null) {
                        showingOriginal = false;
                        setOriginalVisual(false);
                        renderImage();
                    }
                });
        panel.addView(gridTop, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout gridBottom = new LinearLayout(this);
        gridBottom.setOrientation(LinearLayout.HORIZONTAL);
        choiceCell(gridBottom, tr("print.lens_filter"), labelOf(FILTER_LABELS, FILTER_IDS, cameraFilter), tv ->
                showChoiceMapping(tr("print.lens_filter_wratten"), FILTER_LABELS, FILTER_IDS, cameraFilter, id -> {
                    cameraFilter = id;
                    tv.setText(labelOf(FILTER_LABELS, FILTER_IDS, id));
                    applyParams();
                    if (uiReady && session != null) { showingOriginal = false; setOriginalVisual(false); renderImage(); }
                }));
        // **What the canvas is drawn in.** Not a look parameter: it never
        // reaches a file, and two exports of the same frame in different
        // canvas spaces are the same bytes.
        choiceCell(gridBottom, tr("print.preview_gamut"), previewSpace, tv -> {
            final java.util.List<String> labels = java.util.Arrays.asList(SPACES);
            showChoiceSheet(tr("print.preview_gamut_display_only_exports"), labels, previewSpace, picked -> {
                final int index = labels.indexOf(picked);
                if (index >= 0) previewSpace = SPACES[index];
                tv.setText(previewSpace);
                if (uiReady && session != null) { applyParams(); renderImage(); }
            });
        });
        choiceCell(gridBottom, tr("print.protection_basis"), labelOf(NORM_LABELS, NORM_IDS, sceneNorm), tv ->
                showChoiceMapping(tr("print.basis_for_highlight_and_shadow"), NORM_LABELS, NORM_IDS, sceneNorm, id -> {
                    sceneNorm = id;
                    tv.setText(labelOf(NORM_LABELS, NORM_IDS, id));
                    applyParams();
                    if (uiReady && session != null) { showingOriginal = false; setOriginalVisual(false); renderImage(); }
                }));
        // **A gap, not two borders flush together.** Each cell carries its own
        // 1 dp outline; stacked, the two rows met in a 2 dp line that read as
        // heavier than every other rule on the page.
        LinearLayout.LayoutParams gridBottomLp = new LinearLayout.LayoutParams(-1, dp(46));
        gridBottomLp.topMargin = dp(6);
        panel.addView(gridBottom, gridBottomLp);

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
        knob(label, glyph, lo, hi, set, get, heavy, false);
    }

    private void knob(String label, String glyph, float lo, float hi,
                      java.util.function.Consumer<Float> set, java.util.function.Supplier<Float> get,
                      boolean heavy, boolean logScale) {
        final int index = knobs.size();
        knobs.add(new Knob(label, glyph, lo, hi, set, get, heavy, logScale));

        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);
        chip.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);

        // A rectangle, not a ring: the glyphs are letters (E, H, S, G...), and
        // a letter reads better in a box than on a circle. It is also the
        // shape the rest of this app's controls use.
        TextView key = label(glyph, 12, UiPalette.INK, tfBody);
        key.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable box = new android.graphics.drawable.GradientDrawable();
        box.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        box.setCornerRadius(0f);
        box.setColor(0x00000000);
        box.setStroke(dp(1), UiPalette.LINE);
        key.setBackground(box);
        chip.addView(key, new LinearLayout.LayoutParams(dp(52), dp(34)));

        TextView name = label(tr(label), 7.5f, UiPalette.MUTED, tfHan);
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
    /** Progress 0..1000 for a knob, honouring its scale. */
    private static int knobProgress(Knob k, float v) {
        if (k.logScale && k.lo > 0f && k.hi > k.lo) {
            final double t = Math.log(Math.max(k.lo, Math.min(k.hi, v)) / (double) k.lo)
                    / Math.log((double) k.hi / k.lo);
            return (int) Math.round(Math.max(0.0, Math.min(1.0, t)) * 1000.0);
        }
        return Math.round((v - k.lo) / (k.hi - k.lo) * 1000f);
    }

    private static float knobValue(Knob k, int progress) {
        final double t = Math.max(0, Math.min(1000, progress)) / 1000.0;
        if (k.logScale && k.lo > 0f) return (float) (k.lo * Math.pow((double) k.hi / k.lo, t));
        return (float) (k.lo + (k.hi - k.lo) * t);
    }

    private void refreshKnob() {
        if (knobs.isEmpty() || knobBar == null) return;
        final Knob k = knobs.get(activeKnob);
        final float v = k.get.get();
        knobBar.setProgress(Math.max(0, Math.min(1000, knobProgress(k, v))));
        knobValue.setText(String.format(java.util.Locale.US, "%.2f", v));
        knobTitle.setText(tr(k.label));
        if (knobHintView != null) knobHintView.setText(knobHint(k.label));
        if (knobCost != null) {
            knobCost.setText(k.heavy ? tr("print.recalculates_the_film_about_0")
                                     : tr("print.reprints_only_about_0_03"));
            knobCost.setTextColor(k.heavy ? 0xFFC8741E : UiPalette.MUTED);
        }
    }

    /** A label + current value + arrow, the same shape as the paper picker. */
    private TextView addChoice(LinearLayout parent, String title, String value,
                               java.util.function.Consumer<TextView> onTap) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(label(title, 9, UiPalette.MUTED, tfHan));

        LinearLayout button = new LinearLayout(this);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        final TextView tv = label(value, 9.5f, UiPalette.INK, tfHan);
        tv.setGravity(android.view.Gravity.END);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = label("↓", 9.5f, UiPalette.BLUE, tfBody);
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(-2, -2);
        arrowLp.leftMargin = dp(7);
        button.addView(arrow, arrowLp);
        button.setOnClickListener(v -> onTap.accept(tv));
        row.addView(button, new LinearLayout.LayoutParams(0, -1, 1f));

        parent.addView(row, new LinearLayout.LayoutParams(-1, dp(34)));
        return tv;
    }

    /** One cell of the six-control grid: a name and its value, a third of the
     *  width. Six of these used to be six full-width rows. */
    private TextView choiceCell(LinearLayout row, String title, String value,
                                java.util.function.Consumer<TextView> onTap) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(android.view.Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(9), dp(4), dp(6), dp(4));
        cell.setBackground(cellBorder());
        cell.addView(label(title, 8f, UiPalette.MUTED, tfHan), new LinearLayout.LayoutParams(-1, -2));
        final TextView tv = label(value, 9f, UiPalette.INK, tfHan);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(-1, -2);
        tvLp.topMargin = dp(1);
        cell.addView(tv, tvLp);
        cell.setOnClickListener(v -> onTap.accept(tv));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.rightMargin = dp(6);
        row.addView(cell, lp);
        return tv;
    }

    /** The same cell for a two-state switch: its name, then the word and the square. */
    private void toggleCell(LinearLayout row, String title, String valueText, Square mark,
                            java.util.function.BooleanSupplier isOn,
                            java.util.function.Consumer<Boolean> onChange) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(android.view.Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(9), dp(4), dp(6), dp(4));
        cell.setBackground(cellBorder());
        cell.addView(label(title, 8f, UiPalette.MUTED, tfHan), new LinearLayout.LayoutParams(-1, -2));
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView tv = label(valueText, 9f, UiPalette.INK, tfHan);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
        mark.setLit(isOn.getAsBoolean());
        line.addView(mark, new LinearLayout.LayoutParams(dp(14), dp(14)));
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-1, -2);
        lineLp.topMargin = dp(1);
        cell.addView(line, lineLp);
        // **The whole cell is the target**, same as `toggleRow`: a 14 dp square
        // with a 46 dp cell around it is what a thumb expects.
        cell.setOnClickListener(v -> {
            final boolean next = !isOn.getAsBoolean();
            Probe.log("toggle", title + " " + isOn.getAsBoolean() + " -> " + next);
            mark.setLit(next);
            if (rendering) statusText.setText(tr("print.rendering_film_settings_about_1"));
            onChange.accept(next);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.rightMargin = dp(6);
        row.addView(cell, lp);
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
        final android.app.Dialog dialog = newDialog();
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundColor(UiPalette.PAPER);

        View cap = new View(this);
        cap.setBackgroundColor(UiPalette.BLUE);
        sheet.addView(cap, new LinearLayout.LayoutParams(-1, dp(3)));

        TextView head = label(title, 10.5f, UiPalette.INK, tfHan);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(-1, -2);
        headLp.setMargins(dp(16), dp(14), dp(16), dp(6));
        sheet.addView(head, headLp);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setText(initial == null ? "" : initial);
        input.setTextSize(13);
        input.setTextColor(UiPalette.INK);
        input.setSingleLine(true);
        if (numeric) input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setBackground(null);
        input.setPadding(dp(16), dp(10), dp(16), dp(10));
        sheet.addView(input, new LinearLayout.LayoutParams(-1, -2));

        View line = new View(this);
        line.setBackgroundColor(UiPalette.LINE);
        sheet.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = label(tr("editor.cancel"), 10, UiPalette.MUTED, tfHan);
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setPadding(0, dp(14), 0, dp(14));
        cancel.setOnClickListener(v -> dialog.dismiss());
        actions.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView ok = label(tr("optics.ok"), 10, UiPalette.BLUE, tfHan);
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
    private String[] FILTER_LABELS;
    private static final String[] NORM_IDS = {"power", "y", "max"};
    private String[] NORM_LABELS;

    /** One line per knob, short enough to fit the row. */
    private static String knobHint(String label) {
        if (label == null) return "";
        switch (label) {
            case "print.knob.enlarger_exposure_right_is_darker": return tr("print.exposure_from_the_enlarger_smaller");
            case "print.knob.highlight_protection": return tr("print.rolls_highlights_into_the_paper");
            case "print.knob.shadow_protection": return tr("print.lifts_shadows_out_of_the");
            case "print.knob.grain": return tr("print.strength_of_the_silver_halide");
            case "print.knob.halation": return tr("print.red_glow_from_light_reflecting");
            case "print.knob.halation_boost": return tr("print.restores_energy_to_clipped_highlights");
            case "print.knob.emulsion_scatter": return tr("print.haze_caused_by_light_scattering");
            case "print.knob.glare": return tr("print.light_scattered_by_the_paper");
            case "print.knob.lens_blur": return tr("print.optical_lens_blur_in_micrometers");
            case "print.knob.yellow_filter": return tr("print.enlarger_yellow_control_negative_is");
            case "print.knob.magenta_filter": return tr("print.enlarger_magenta_control_negative_is");
            case "print.knob.filter_strength": return tr("print.scales_the_two_filter_controls");
            case "print.knob.pre_flash": return tr("print.gives_the_paper_a_uniform");
            case "print.knob.curve_softness": return tr("print.smooths_the_highlight_and_shadow");
            case "print.knob.maximum_shadow_lift": return tr("print.how_many_stops_shadows_can");
            case "print.knob.enable_halation": return tr("print.turn_off_to_simulate_a");
            case "print.knob.remove_anti_halation": return tr("print.simulates_removing_the_anti_halation");
            case "print.knob.enable_grain": return tr("print.turn_off_for_a_grain");
            case "print.knob.interlayer_inhibition": return tr("print.controls_how_dye_layers_inhibit");
            case "print.knob.enable_glare":   return tr("print.set_to_0_to_remove");
            case "print.knob.scanner_blur": return tr("print.scanner_lens_blur_in_micrometers");
            case "print.knob.halation_headroom":   return tr("print.highlight_headroom_for_halation_in");
            case "print.knob.density_curve":   return tr("print.film_density_curve_gamma_0");
            case "print.knob.digital_intermediate": return tr("print.bypass_optical_printing_and_use");
            case "print.knob.output_encoding":   return tr("print.output_transfer_function_0_uses");
            case "print.knob.keep_film_colors":   return tr("print.move_right_to_keep_the");
            case "print.knob.scan_whites": return tr("print.correct_the_scan_using_the");
            case "print.knob.scan_blacks": return tr("print.correct_the_scan_using_the_2");
            case "print.dynamic_range": return tr("print.extend_dynamic_range_to_recover");
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
            box.setStroke(dp(active ? 2 : 1), active ? UiPalette.BLUE : UiPalette.LINE);
            key.setBackground(box);
            key.setTextColor(active ? UiPalette.BLUE : UiPalette.INK);
            name.setTextColor(active ? UiPalette.BLUE : UiPalette.MUTED);
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
        panel.addView(sectionHead(tr("compose.composition"), this::resetComposePanel),
                new LinearLayout.LayoutParams(-1, dp(38)));

        LinearLayout sub = new LinearLayout(this);
        sub.setOrientation(LinearLayout.HORIZONTAL);
        composeCropTab = composeTabView(tr("compose.crop"), 0);
        composeRotateTab = composeTabView(tr("compose.rotate"), 1);
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
        final TextView tv = label(text, 12, UiPalette.MUTED, tfHan);
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
        composeCropTab.setTextColor(page == 0 ? UiPalette.INK : UiPalette.MUTED);
        composeRotateTab.setTextColor(page == 1 ? UiPalette.INK : UiPalette.MUTED);
        underline(composeCropTab, page == 0);
        underline(composeRotateTab, page == 1);
        if (cropScroll != null) cropScrollX = cropScroll.getScrollX();
        composeBody.removeAllViews();
        if (page == 0) buildCropPage(); else buildRotatePage();
        syncRotateGrid();
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

            card.addView(new RatioMark(this, ratio, active ? UiPalette.BLUE : UiPalette.INK),
                    new LinearLayout.LayoutParams(-1, dp(44)));

            final TextView caption = label(original ? tr("compose.original") : EDGE_RATIO_LABELS[i],
                    8.5f, active ? UiPalette.INK : UiPalette.MUTED, tfBody);
            caption.setGravity(android.view.Gravity.CENTER);
            card.addView(caption, new LinearLayout.LayoutParams(-1, -2));

            final TextView gate = label(original ? tr("compose.full_frame") : EDGE_LABELS[i], 6.5f, UiPalette.MUTED, tfHan);
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
            card.addView(new RatioMark(this, ratio, active ? UiPalette.BLUE : UiPalette.INK),
                    new LinearLayout.LayoutParams(-1, dp(44)));
            final TextView caption = label(PORTRAIT_LABELS[i], 8.5f,
                    active ? UiPalette.INK : UiPalette.MUTED, tfBody);
            caption.setGravity(android.view.Gravity.CENTER);
            card.addView(caption, new LinearLayout.LayoutParams(-1, -2));
            final TextView gate = label(tr("compose.portrait"), 6.5f, UiPalette.MUTED, tfHan);
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
        final TextView foot = label(cropSummary(), 8.5f, UiPalette.MUTED, tfHan);
        footRow.addView(foot, new LinearLayout.LayoutParams(0, -2, 1f));

        // **Nothing happens to the picture until 应用.** 取消 throws the
        // proposal away; the frame goes back to the whole canvas.
        final TextView cancel = label(tr("editor.cancel"), 9, UiPalette.MUTED, tfHan);
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

        final TextView apply = label(tr("compose.apply_crop"), 9, UiPalette.BLUE, tfHan);
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
        bg.setStroke(dp(on ? 2 : 1), on ? UiPalette.BLUE : UiPalette.LINE);
        return bg;
    }

    /** Shown only when there is something to compare against. */
    private void refreshComparePill() {
        if (comparePill == null) return;
        final boolean can = compare != null && lastRenderedBitmap != null;
        comparePill.setVisibility(can ? View.VISIBLE : View.GONE);
        if (!can && compare != null && compare.isSplitMode()) compare.setSplitMode(false);
        final boolean on = compare != null && compare.isSplitMode();
        comparePill.setTextColor(on ? UiPalette.BLUE : UiPalette.ON_WHITE);
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
                && pendingCropX <= 0.001f && pendingCropY <= 0.001f) return tr("compose.full_frame_uncropped");
        return AppLanguage.format("compose.keep_0f_0f_scaled_by",
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
            showInAppToast(tr("compose.no_crop_applied"));
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
            // **State the frame length whether or not the id changed.** The
            // default `filmFormatMm` is 24, and a 3:2 crop that keeps
            // `overscanFormat = "135"` used to leave it there: the rebate
            // rendered at 36 (wireFormatMm takes it from the gate) while the
            // 尺度 page showed 24, and the two numbers never met again.
            overscanFormat = want;
            final double mm = gateMm(want);
            if (mm > 0) filmFormatMm = (float) mm;
        }
        cropRatioIndex = -1;
        commitCompose();
        showComposePage(0);
    }

    private void buildRotatePage() {
        // **The gesture is on the picture; the scale is the readout.** This
        // page used to make the finger turn a dial while the picture sat still,
        // which is the wrong way round: 修偏 is done by watching the horizon.
        final RotateScale arc = new RotateScale(this, tfBody, new RotateScale.Delegate() {
                @Override public void onDragStart() { rotationBeforeDrag = geometryRotation; }
                @Override public void onPreview(float delta) { previewRotate(delta); }
                @Override public void onCommit(float delta) { commitRotate(delta); }
            });
        arc.setAngle(geometryRotation);
        rotateArc = arc;
        android.widget.FrameLayout arcBox = new android.widget.FrameLayout(this);
        arcBox.addView(arc, new android.widget.FrameLayout.LayoutParams(-1, -1));
        final TextView gridChip = label("", 8f, UiPalette.BLUE, tfHan);
        final Runnable paintChip = () -> gridChip.setText(rotateGridOn ? tr("compose.guides_on") : tr("compose.guides_off"));
        paintChip.run();
        gridChip.setPadding(dp(8), dp(4), dp(8), dp(4));
        gridChip.setOnClickListener(v -> {
            rotateGridOn = !rotateGridOn;
            getSharedPreferences("ui", MODE_PRIVATE).edit()
                    .putBoolean("rotate_grid", rotateGridOn).apply();
            paintChip.run();
            syncRotateGrid();
        });
        android.widget.FrameLayout.LayoutParams chipLp = new android.widget.FrameLayout.LayoutParams(
                -2, -2, android.view.Gravity.TOP | android.view.Gravity.END);
        chipLp.topMargin = 0;
        chipLp.rightMargin = dp(6);
        arcBox.addView(gridChip, chipLp);
        composeBody.addView(arcBox, new LinearLayout.LayoutParams(-1, dp(70)));
        final TextView tip = label(tr("compose.drag_the_dial_or_image"),
                7.5f, UiPalette.MUTED, tfHan);
        tip.setGravity(android.view.Gravity.CENTER);
        composeBody.addView(tip, new LinearLayout.LayoutParams(-1, dp(14)));

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);

        final LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1f);
        row.addView(circleAction("↺", tr("compose.left_90"), () -> turnBy(-1)), cell);
        row.addView(circleAction("↻", tr("compose.right_90"), () -> turnBy(1)),
                new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(circleAction("⇆", tr("compose.flip_horizontally"), () -> {
            geometryFlipH = !geometryFlipH;
            showComposePage(1);
            commitCompose();
        }), new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(circleAction("⇅", tr("compose.flip_vertically"), () -> {
            geometryFlipV = !geometryFlipV;
            showComposePage(1);
            commitCompose();
        }), new LinearLayout.LayoutParams(0, -2, 1f));
        composeBody.addView(row, new LinearLayout.LayoutParams(-1, -2));

        // No footnote under the buttons: the tip above the scale already says
        // what the gesture does, and this page is height-sensitive. (The old
        // one here still said "转表盘" three pages after the dial was removed.)
    }

    /** The grid belongs to exactly one square inch of this app. */
    private void syncRotateGrid() {
        if (compare == null) return;
        final boolean rotatePage = rotatePageLive();
        compare.setRotateMode(rotatePage);
        compare.setGrid(rotateGridOn && rotatePage ? 1 : 0);
    }

    private static float clampRotation(float deg) {
        return Math.max(-45f, Math.min(45f, deg));
    }

    /** The one square inch that owns the rotate gesture. */
    private boolean rotatePageLive() {
        return activeTabIndex == 4 && composeSubPage == 1;
    }

    /** Live: the picture turns under the finger, nothing renders. */
    private void previewRotate(float total) {
        final float want = clampRotation(rotationBeforeDrag + total);
        if (rotateArc != null) rotateArc.setAngle(want);
        if (compare != null) compare.setPreviewRotation(want - rotationBeforeDrag);
    }

    /** Release: level snaps, the pipeline hears about it once. */
    private void commitRotate(float total) {
        float want = clampRotation(rotationBeforeDrag + total);
        try {
            DebugSink.text("rotate", "commit want=" + want + " before=" + rotationBeforeDrag
                    + " total=" + total);
        } catch (Throwable spkIgnored) { Diagnostics.ignored("commitRotate:6082", spkIgnored); }
        // Level is a place, not a number: 0.6 degrees is about two pixels of
        // travel at the frame's corner.
        if (Math.abs(want) < 0.6f) want = 0f;
        geometryRotation = want;
        rotationPending = true;
        geometrySeq++;
        if (rotateArc != null) rotateArc.setAngle(want);
        if (compare != null) compare.setPreviewRotation(want - rotationBeforeDrag);
        commitCompose();
    }

    // **Turning with two fingers, after Cropper.js v2's `ACTION_TRANSFORM`.**
    // One finger already rotates on this page, but once the picture is zoomed
    // in, that finger is also the only way to look around -- so the pair of
    // fingers gets to level the frame without letting go of the zoom. The angle
    // between the two pointers is the whole measurement; no minimum span and no
    // second detector, because the span is already being tracked for the zoom
    // and two fingers are never at the centre of the picture.
    //
    // The dead zone is not cosmetic: a pinch wobbles by a degree or two no
    // matter how carefully it is done, and a photograph that quietly rotates
    // 2 degrees every time the user zooms in is worse than one that cannot be
    // turned with two fingers at all.
    private static final float TWIST_DEAD_ZONE_DEG = 2.5f;
    private float twistLast = Float.NaN;
    private float twistTotal = 0f;
    private boolean twistActive = false;

    private static float angleOfPair(android.view.MotionEvent e) {
        return (float) Math.toDegrees(Math.atan2(
                e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)));
    }

    private void beginTwist(android.view.MotionEvent e) {
        twistTotal = 0f;
        twistActive = false;
        twistLast = rotatePageLive() ? angleOfPair(e) : Float.NaN;
    }

    private void updateTwist(android.view.MotionEvent e) {
        if (!rotatePageLive()) { twistLast = Float.NaN; return; }
        final float cur = angleOfPair(e);
        if (Float.isNaN(twistLast)) { twistLast = cur; return; }
        float d = cur - twistLast;
        while (d > 180f) d -= 360f;
        while (d < -180f) d += 360f;
        twistLast = cur;
        twistTotal += d;
        if (Math.abs(twistTotal) <= TWIST_DEAD_ZONE_DEG) return;
        if (!twistActive) {
            twistActive = true;
            rotationBeforeDrag = geometryRotation;
        }
        previewRotate(twistTotal - Math.signum(twistTotal) * TWIST_DEAD_ZONE_DEG);
    }

    private void endTwist() {
        final boolean active = twistActive;
        final float total = twistTotal;
        twistLast = Float.NaN;
        twistTotal = 0f;
        twistActive = false;
        if (!active) return;
        commitRotate(total - Math.signum(total) * TWIST_DEAD_ZONE_DEG);
    }

    /**
     * **The scale under the picture, after the editor this is modelled on.**
     *
     * <p>A short arc whose centre is the picture's own centre, so a degree of
     * finger travel on the canvas and a tick on this scale are the same degree.
     * The readings sit in a gap in the middle: the number *is* the pointer.
     */
    /**
     * **The rotation scale, after the editor in the reference recording.**
     *
     * <p>A shallow arc of dots under the picture: one dot per degree, a longer
     * tick every five, and the whole ruler sliding under a fixed pointer as the
     * angle moves -- so the mark under the pointer is the angle you are on, and
     * the number above it is that angle written out. Dragging anywhere on the
     * scale turns the picture, which is the part a bare seekbar cannot do:
     * there is no thumb to aim at, the gesture starts where the eye already is.
     *
     * <p>The reading carries two decimals because the scale is fine enough to
     * resolve a hundredth: at 400 dp wide the ruler spans 44 degrees, so one
     * pixel is a fortieth of a degree. Level is a place, and the commit still
     * snaps to it (see `commitRotate`).
     */
    

    private View circleAction(String glyph, String caption, Runnable tap) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        final TextView circle = label(glyph, 15, UiPalette.INK, tfBody);
        circle.setGravity(android.view.Gravity.CENTER);
        final android.graphics.drawable.GradientDrawable ring =
                new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setColor(0x00000000);
        ring.setStroke(dp(1), UiPalette.LINE);
        circle.setBackground(ring);
        circle.setOnClickListener(v -> tap.run());
        box.addView(circle, new LinearLayout.LayoutParams(dp(40), dp(40)));

        final TextView name = label(caption, 7.5f, UiPalette.MUTED, tfHan);
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
        // **The gate list is a function of the shape being rendered.** A crop
        // or a quarter turn changes that shape, and until this call the list
        // (and therefore `overscanFormat`) still described the picture before
        // the edit -- so the very next render was refused with E-1305 on a
        // gate the app itself had offered.
        refreshEdgeFormats();
        // Captured here, on the thread that owns the edit, and read by
        // `sendGeometry` from the render thread. See `geoSnapRot`.
        geoSnapRot = geometryRotation;
        geoSnapTurns = geometryTurns;
        geoSnapFlipH = geometryFlipH;
        geoSnapFlipV = geometryFlipV;
        geoSnapW = Math.max(0.01f, Math.min(1f, geometryCropW));
        geoSnapH = Math.max(0.01f, Math.min(1f, geometryCropH));
        geoSnapX = Math.max(0f, Math.min(1f - geoSnapW, geometryCropX));
        geoSnapY = Math.max(0f, Math.min(1f - geoSnapH, geometryCropY));
        geoSnapValid = true;
        pendingGeometry = true;
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
    

    /** A crop ratio drawn at that shape: 3:2 is a wide box, 1:1 a square,
     *  6:17 a slash. The card's caption says which gate it came from. */
    

    private LinearLayout buildFormatPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(sectionHead(tr("border.frame_scale"), this::resetFormatPanel),
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
        formatFootLabel = label(tr("border.physical_film_size_crop_and"), 8.5f, UiPalette.MUTED, tfHan);
        foot.addView(formatFootLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        formatFootValue = label("36 mm", 9, UiPalette.BLUE, tfBody);
        foot.addView(formatFootValue);
        panel.addView(foot, new LinearLayout.LayoutParams(-1, dp(28)));

        rebuildFormatCards();
        return panel;
    }

    /** 76, not 76.0 -- but 69.5 has to stay 69.5. */
    private static String mmText(float mm) {
        return Math.abs(mm - Math.round(mm)) < 0.05f
                ? String.format(java.util.Locale.US, "%.0f", mm)
                : String.format(java.util.Locale.US, "%.1f", mm);
    }

    /**
     * The 尺度 page's foot line.
     *
     * <p>**A rebate owns the frame's length.** `wireFormatMm` forwards the
     * gate's own `along` the moment a rebate is on, so the millimetre cards
     * decide nothing then -- and they used to keep looking live anyway: 24, 36,
     * 56 and 65 mm rendered byte-identical pictures (measured, hash
     * `be003cc212e7e8be` all four). A control that looks live and is not is
     * worse than no control, so the caption says who is holding the pen and the
     * cards below it grey out.
     */
    private void refreshFormatFoot() {
        if (formatFootLabel != null) {
            formatFootLabel.setText(overscanOn ? tr("border.physical_film_size_tap_to")
                                               : tr("border.physical_film_size_crop_and"));
        }
        if (formatFootValue == null) return;
        formatFootValue.setText(String.format(java.util.Locale.US, "%s mm",
                mmText(overscanOn ? gateMm(overscanFormat) : filmFormatMm)));
    }

    /** Rebuild the cards only when the rebate took the length over, or gave it
     *  back -- every other edge toggle leaves them (and their scroll) alone. */
    private void syncFormatLock() {
        refreshFormatFoot();
        if (formatLocked != overscanOn) rebuildFormatCards();
    }

    private void rebuildFormatCards() {
        if (formatCardStrip == null) return;
        formatCardStrip.removeAllViews();
        formatCards.clear();
        // **A rebate owns the length**, so the ladder becomes a readout: it
        // still shows every size (so the user can see where the gate sits) but
        // only the one the gate picked is lit, and none of them take a tap.
        final boolean byGate = overscanOn;
        formatLocked = byGate;
        // The lit card is the gate's own card; with the tables aligned that is
        // just its index (56 mm is 645 *and* 6x6, 76 mm is 6x8).
        final int byGateCard = byGate ? gateIndex(overscanFormat) : -1;
        for (int i = 0; i < FORMAT_NAMES.length; ++i) {
            final int index = i;
            boolean active = byGate ? i == byGateCard : i == formatIndex;

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
                    23, active ? UiPalette.BLUE : UiPalette.MUTED, tfDisplay);
            value.setLetterSpacing(-0.05f);
            valueRow.addView(value);
            TextView unit = label("mm", 8, active ? UiPalette.BLUE : UiPalette.MUTED, tfBody);
            LinearLayout.LayoutParams unitLp = new LinearLayout.LayoutParams(-2, -2);
            unitLp.leftMargin = dp(2);
            unitLp.bottomMargin = dp(2);
            valueRow.addView(unit, unitLp);
            top.addView(valueRow);

            TextView kind = label(i == 2 || i >= 8 ? tr("border.wide") : tr("border.standard"), 7, UiPalette.MUTED, tfHan);
            LinearLayout.LayoutParams kindLp = new LinearLayout.LayoutParams(-2, -2);
            kindLp.topMargin = dp(4);
            top.addView(kind, kindLp);
            card.addView(top, new android.widget.FrameLayout.LayoutParams(-1, -1));

            TextView name = label(FORMAT_NAMES[i], 8.5f, active ? UiPalette.BLUE : UiPalette.INK, tfHan);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setGravity(android.view.Gravity.CENTER_VERTICAL);
            name.setBackgroundColor(UiPalette.PAPER);
            name.setPadding(dp(8), 0, dp(7), 0);
            card.addView(name, new android.widget.FrameLayout.LayoutParams(
                    -1, dp(34), android.view.Gravity.BOTTOM));

            if (active) {
                View mark = new View(this);
                mark.setBackgroundColor(UiPalette.BLUE);
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
        if (overscanOn) {
            // **A card is a gate.** With a rebate on the engine takes the
            // frame's length from the gate, so a card cannot mean "the frame is
            // this long" -- but with the tables aligned it means exactly "this
            // gate", index for index. Locking them (1.6.0n) made them feel dead;
            // mapping by nearest millimetre (1.6.0o) could not tell 645 from 6x6
            // and left 6x8 unreachable.
            applyGate(EDGE_IDS[index]);
            return;
        }
        formatIndex = index;
        filmFormatMm = FORMAT_MM[index];
        // ... and the other way: a card *is* a gate now, so the two pages agree
        // by construction (nearest millimetre could not tell 645 from 6x6).
        final String gate = EDGE_IDS[index];
        if (!gate.equals(overscanFormat) && (edgeIds.isEmpty() || edgeIds.contains(gate))) {
            overscanFormat = gate;
            if (edgeValue != null) {
                final int gi = edgeIds.indexOf(gate);
                edgeValue.setText(gi >= 0 ? edgeLabels.get(gi) : gateLabelsFallback(gate));
            }
        }
        refreshFormatFoot();
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
        home.addView(sectionHead(tr("border.frame_imprint"), this::resetEdgePanel),
                new LinearLayout.LayoutParams(-1, dp(38)));

        HorizontalScrollView cardScroll = new HorizontalScrollView(this);
        cardScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout cardStrip = new LinearLayout(this);
        cardStrip.setOrientation(LinearLayout.HORIZONTAL);
        cardStrip.addView(bigCard(tr("border.film_border"), "REBATE", this::edgeSummary, () -> openEdgePage(0)),
                cardLp());
        cardStrip.addView(bigCard(tr("border.date_stamp"), "DATE BACK", this::dateSummary, () -> openEdgePage(1)),
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
        bg.setColor(UiPalette.PAPER);
        bg.setCornerRadius(0f);
        bg.setStroke(dp(1), UiPalette.LINE);
        card.setBackground(bg);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView name = mixedLabel(title + "  " + subtitle, 8.5f, UiPalette.INK);
        name.setLetterSpacing(0.06f);
        titleRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        titleRow.addView(label("›", 12f, UiPalette.MUTED, tfBody));
        card.addView(titleRow);

        final TextView lines = label("", 7.5f, UiPalette.MUTED, tfHan);
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
        if (!overscanOn) return tr("border.off_tap_to_configure");
        final int at = edgeIds.indexOf(overscanFormat);
        final String gate = at >= 0 && at < edgeLabels.size() ? edgeLabels.get(at) : overscanFormat;
        return gate + tr("border.fog") + fmt1(edgeFog) + tr("border.leak") + fmt1(edgeLeaks)
                + tr("border.frame") + (frameNumber < 0.5f ? tr("meter.auto") : String.valueOf(Math.round(frameNumber)));
    }

    private String dateSummary() {
        if (!dateOn) return tr("border.off_tap_to_configure");
        return (dateText == null || dateText.isEmpty() ? imprintDate() : dateText)
                + " · " + labelOf(STYLE_LABELS, STYLE_IDS, dateStyle)
                + " " + labelOf(PLACE_LABELS, PLACE_IDS, datePlacement);
    }

    private static int jsonInt(String json, String key) { return JsonFields.firstInt(json, key); }

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
        edgePage.addView(pageHead(tr("border.film_border_2")), new LinearLayout.LayoutParams(-1, dp(38)));
        overscanMark = new Square(this, UiPalette.BLUE, UiPalette.toggleOff());
        overscanRow = toggleRow(tr("border.enable_film_border"), overscanMark, () -> overscanOn, on -> {
            if (on && edgeIds.isEmpty()) {
                overscanMark.setLit(false);
                // **Point at the way out.** A 16:9 frame has no gate at all, and
                // this switch used to answer "does not correspond to any gate"
                // while the picker that could fix it was dimmed a few rows down.
                showInAppToast(tr("border.choose_a_film_gate_first"));
                return;
            }
            overscanOn = on;
            refreshEdgeGroups();
            onEdgeChanged();
        });
        edgePage.addView(overscanRow, new LinearLayout.LayoutParams(-1, dp(52)));
        // **The field, not a local that shadows it.** `refreshEdgeGroups` dims
        // `edgeInner`, and a local here left the field null -- so every switch
        // it was meant to grey stayed lit and enabled, which is the opposite of
        // what the page says it does.
        edgeInner = new LinearLayout(this);
        edgeInner.setOrientation(LinearLayout.VERTICAL);

        // **The two rows that *decide* the shape live outside the dimmed block.**
        // `refreshEdgeGroups` disables `edgeInner` while the rebate is off, and
        // these two used to be inside it -- so a 1.777:1 frame could not be
        // given a gate at all: the 开启片边 switch refused ("no gate fits"),
        // and the picker one row below was greyed out and untappable. The only
        // way through was to crop in 构图 first. Now the entries stay live and
        // choosing a gate crops the picture and turns the rebate on.
        LinearLayout gateRow = new LinearLayout(this);
        gateRow.setOrientation(LinearLayout.HORIZONTAL);
        {
            final int at = edgeIds.indexOf(overscanFormat);
            final String current = at >= 0 && at < edgeLabels.size() ? edgeLabels.get(at) : overscanFormat;
            choiceCell(gateRow, tr("border.film_gate_format"), current, tv -> {
                edgeValue = tv;
                showEdgePicker();
            });
        }
        // **方向, where the user looks for it.** A rebate is cut by a gate and a
        // gate's shape is fixed -- the film runs along the picture's short edge
        // -- so "turn the rebate" can only mean "turn the picture". That is one
        // quarter turn, and `commitCompose` then re-picks the gates for the new
        // shape. The rebate follows; the canvas shows both.
        choiceCell(gateRow, tr("border.border_orientation"), tr("border.rotate_90"), tv -> {
            turnBy(1);
            showInAppToast(tr("border.rotated_90_the_border_follows"));
        });
        edgePage.addView(gateRow, new LinearLayout.LayoutParams(-1, dp(46)));

        edgeTextMark = new Square(this, UiPalette.BLUE, UiPalette.toggleOff());
        edgeInner.addView(toggleRow(tr("border.edge_markings_brand_frame_number"), edgeTextMark, () -> edgeTextOn, on -> {
            edgeTextOn = on;
            redraw.accept(null);
        }), new LinearLayout.LayoutParams(-1, dp(52)));
        edgeFogBar = rangeRow(edgeInner, tr("border.base_fog"), tr("border.haze_in_the_film_base"),
                0.0f, 4.0f, edgeFog, v -> edgeFog = v);
        edgeLeaksBar = rangeRow(edgeInner, tr("border.light_leak"), tr("border.light_leaking_into_the_roll"),
                0.0f, 4.0f, edgeLeaks, v -> edgeLeaks = v);
        frameNoBar = rangeRow(edgeInner, tr("border.frame_number"), tr("border.number_printed_on_the_film"),
                0.0f, 99.0f, frameNumber, v -> frameNumber = v);
        edgeFNumBar = rangeRow(edgeInner, tr("border.aperture"), tr("border.recorded_f_number_affects_the"),
                0.0f, 64.0f, edgeFNumber, v -> edgeFNumber = v);

        LinearLayout edgeShapeRow = new LinearLayout(this);
        edgeShapeRow.setOrientation(LinearLayout.HORIZONTAL);
        choiceCell(edgeShapeRow, tr("border.border_style"), labelOf(MODE_LABELS, MODE_IDS, edgeMode), tv ->
                showChoiceMapping(tr("border.border_style"), MODE_LABELS, MODE_IDS, edgeMode, id -> {
                    edgeMode = id;
                    tv.setText(labelOf(MODE_LABELS, MODE_IDS, id));
                    redraw.accept(null);
                }));
        choiceCell(edgeShapeRow, tr("border.gate_shape"), labelOf(GATE_LABELS, GATE_IDS, edgeGate), tv ->
                showChoiceMapping(tr("border.gate_shape"), GATE_LABELS, GATE_IDS, edgeGate, id -> {
                    edgeGate = id;
                    tv.setText(labelOf(GATE_LABELS, GATE_IDS, id));
                    redraw.accept(null);
                }));
        choiceCell(edgeShapeRow, tr("border.sprocket_holes"), labelOf(HOLES_LABELS, HOLES_IDS, edgeHoles), tv ->
                showChoiceMapping(tr("border.what_appears_through_the_sprocket"), HOLES_LABELS, HOLES_IDS, edgeHoles, id -> {
                    edgeHoles = id;
                    tv.setText(labelOf(HOLES_LABELS, HOLES_IDS, id));
                    redraw.accept(null);
                }));
        edgeInner.addView(edgeShapeRow, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout edgeSeedRow = new LinearLayout(this);
        edgeSeedRow.setOrientation(LinearLayout.HORIZONTAL);
        choiceCell(edgeSeedRow, tr("border.film_holder"), labelOf(CARRIER_LABELS, CARRIER_IDS, edgeCarrier), tv ->
                showChoiceMapping(tr("border.what_appears_alongside_the_film"), CARRIER_LABELS, CARRIER_IDS, edgeCarrier, id -> {
                    edgeCarrier = id;
                    tv.setText(labelOf(CARRIER_LABELS, CARRIER_IDS, id));
                    redraw.accept(null);
                }));
        choiceCell(edgeSeedRow, tr("border.border_seed"), String.valueOf(cameraSeed), tv ->
                askText(tr("border.random_seed_for_the_film"), String.valueOf(cameraSeed), true, v -> {
                    try { cameraSeed = Math.max(0, Integer.parseInt(v)); } catch (Exception ignored) { return; }
                    tv.setText(String.valueOf(cameraSeed));
                    redraw.accept(null);
                }));
        choiceCell(edgeSeedRow, tr("border.frame_seed"), String.valueOf(frameSeed), tv ->
                askText(tr("border.random_seed_for_the_frame"), String.valueOf(frameSeed), true, v -> {
                    try { frameSeed = Math.max(0, Integer.parseInt(v)); } catch (Exception ignored) { return; }
                    tv.setText(String.valueOf(frameSeed));
                    redraw.accept(null);
                }));
        LinearLayout.LayoutParams edgeSeedLp = new LinearLayout.LayoutParams(-1, dp(46));
        edgeSeedLp.topMargin = dp(6);
        edgeInner.addView(edgeSeedRow, edgeSeedLp);
        this.edgeInner = edgeInner;
        // **240, not 340.** 片门格式 and 片边方向 moved out of the scroll and
        // into the page, so the block keeps its old total height instead of
        // pushing the tab bar off the bottom of a 640 dp screen.
        edgePage.addView(cappedScroll(edgeInner, 240), new LinearLayout.LayoutParams(-1, -2));
        edgeRoot.addView(edgePage, new LinearLayout.LayoutParams(-1, -2));

        datePage = new LinearLayout(this);
        datePage.setOrientation(LinearLayout.VERTICAL);
        datePage.addView(pageHead(tr("border.date_stamp_2")), new LinearLayout.LayoutParams(-1, dp(38)));
        dateMark = new Square(this, UiPalette.BLUE, UiPalette.toggleOff());
        dateRow = toggleRow(tr("border.enable_date_stamp"), dateMark, () -> dateOn, on -> {
            dateOn = on;
            if (dateOn && dateText == null) dateText = imprintDate();
            refreshEdgeGroups();
            onEdgeChanged();
        });
        datePage.addView(dateRow, new LinearLayout.LayoutParams(-1, dp(52)));
        dateInner = new LinearLayout(this);
        dateInner.setOrientation(LinearLayout.VERTICAL);
        // 0..200 mm: 30 was the schema's old cap and it stopped the date a
        // third of the way along a 6x12 / 6x17 rebate.
        dateInsetXBar = rangeRow(dateInner, tr("border.horizontal_inset"), tr("border.distance_from_the_sides_of"),
                0.0f, 200.0f, dateInsetX, v -> dateInsetX = v);
        dateInsetYBar = rangeRow(dateInner, tr("border.vertical_inset"), tr("border.distance_from_the_top_or"),
                0.0f, 200.0f, dateInsetY, v -> dateInsetY = v);
        // 8.0: the engine clamps at 8 now, because "make the date bigger" was
        // the one adjustment the old 3.0 ceiling refused.
        dateSizeBar = rangeRow(dateInner, tr("border.date_size"), tr("border.relative_character_height_1_0"),
                0.4f, 8.0f, dateSize, v -> dateSize = v);
        dateEvBar = rangeRow(dateInner, tr("border.date_brightness"), tr("border.red_light_exposure_above_18"),
                -2.0f, 8.0f, dateEv, v -> dateEv = v);
        LinearLayout dateGrid1 = new LinearLayout(this);
        dateGrid1.setOrientation(LinearLayout.HORIZONTAL);
        // The one field the app used to decide for the user: it read the date
        // out of EXIF and gave no way to write a different one.
        choiceCell(dateGrid1, tr("border.date_text"), dateText == null || dateText.isEmpty() ? imprintDate() : dateText,
                tv -> askText(tr("border.text_stamped_on_the_image"),
                        dateText == null || dateText.isEmpty() ? imprintDate() : dateText, v -> {
                            dateText = v.isEmpty() ? null : v;
                            tv.setText(dateText == null ? imprintDate() : dateText);
                            redraw.accept(null);
                        }));
        choiceCell(dateGrid1, tr("border.date_placement"), labelOf(PLACE_LABELS, PLACE_IDS, datePlacement), tv ->
                showChoiceMapping(tr("border.where_to_place_the_date"), PLACE_LABELS, PLACE_IDS, datePlacement, id -> {
                    datePlacement = id;
                    tv.setText(labelOf(PLACE_LABELS, PLACE_IDS, id));
                    redraw.accept(null);
                }));
        choiceCell(dateGrid1, tr("border.date_corner"), labelOf(CORNER_LABELS, CORNER_IDS, dateCorner), tv ->
                showChoiceMapping(tr("border.which_corner_to_stamp"), CORNER_LABELS, CORNER_IDS, dateCorner, id -> {
                    dateCorner = id;
                    tv.setText(labelOf(CORNER_LABELS, CORNER_IDS, id));
                    redraw.accept(null);
                }));
        dateInner.addView(dateGrid1, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout dateGrid2 = new LinearLayout(this);
        dateGrid2.setOrientation(LinearLayout.HORIZONTAL);
        choiceCell(dateGrid2, tr("border.date_style"), labelOf(STYLE_LABELS, STYLE_IDS, dateStyle), tv ->
                showChoiceMapping(tr("border.type_of_lettering_for_the"), STYLE_LABELS, STYLE_IDS, dateStyle, id -> {
                    dateStyle = id;
                    tv.setText(labelOf(STYLE_LABELS, STYLE_IDS, id));
                    redraw.accept(null);
                }));
        choiceCell(dateGrid2, tr("border.shooting_data"), dataText.isEmpty() ? tr("border.empty") : dataText, tv ->
                askText(tr("border.shooting_data_to_print_alongside"), dataText, v -> {
                    dataText = v;
                    tv.setText(v.isEmpty() ? tr("border.empty") : v);
                    redraw.accept(null);
                }));
        choiceCell(dateGrid2, tr("border.second_frame_date"), dateTextB.isEmpty() ? tr("border.empty") : dateTextB, tv ->
                askText(tr("border.date_for_the_second_frame"), dateTextB, v -> {
                    dateTextB = v;
                    tv.setText(v.isEmpty() ? tr("border.empty") : v);
                    redraw.accept(null);
                }));
        LinearLayout.LayoutParams dateGrid2Lp = new LinearLayout.LayoutParams(-1, dp(46));
        dateGrid2Lp.topMargin = dp(6);
        dateInner.addView(dateGrid2, dateGrid2Lp);

        TextView dateTip = label(
                tr("border.for_an_in_image_stamp"),
                7.5f, UiPalette.MUTED, tfHan);
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
        TextView back = label(tr("border.back"), 10f, UiPalette.BLUE, tfHan);
        back.setPadding(0, dp(8), dp(16), dp(8));
        back.setOnClickListener(v -> openEdgePage(-1));
        head.addView(back);
        TextView name = mixedLabel(title, 9f, UiPalette.MUTED);
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
        syncFormatLock();
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

    /**
     * Put the picture in a gate: label, frame length, crop, the switch, and the
     * list of gates that still fit.
     *
     * <p>**Both entries go through here.** The 片门格式 row and the 尺度 page's
     * millimetre cards are two ways to say the same thing, and they used to
     * carry two copies of this sequence -- which is how the cards ended up
     * changing the gate without cropping the picture to it.
     */
    private void applyGate(String id) {
        if (id == null) return;
        final int at = gateIndex(id);
        if (at < 0) return;
        final String before = overscanFormat;
        overscanFormat = id;
        // The frame that goes in this gate, not whatever the scale page was on.
        filmFormatMm = EDGE_MM[at];
        formatIndex = at;
        // **Choosing a gate is asking for the rebate.**
        overscanOn = true;
        if (overscanMark != null) overscanMark.setLit(true);
        if (edgeValue != null) edgeValue.setText(EDGE_LABELS[at]);
        // **The picture takes the gate's shape**, in the picture's own
        // orientation: a portrait frame is the portrait of the gate, not a
        // rotated landscape.
        final float cur = originalAspect();
        float want = (float) EDGE_RATIOS[at];
        if (cur > 0f && cur < 1f) want = 1f / want;
        // **Always re-cut, from the whole frame.** `cropToRatio` recomputes its
        // window from `originalAspect()` every time, so calling it is
        // idempotent -- and skipping it when the target merely *resembled the
        // source* left the previous gate's crop on the canvas: on a 3:4 picture,
        // 135 (which cuts to 2:3) followed by 135 half (whose target *is* 3:4)
        // kept the 2:3 window, so the frame no longer matched the gate and the
        // switch refused with "裁不成 135 半格的比例". 645 (0.741) and 6x8
        // (0.737) are the same distance from 3:4 and failed the same way, while
        // 6x7 (0.806) was far enough to be cut. The comparison has to be against
        // the *current window*, never the source.
        final boolean cropped = cur > 0f && Math.abs(currentAspect() / want - 1f) > 0.045f;
        if (cur > 0f) {
            cropToRatio(want);
            if (cropped && composeBody != null) showComposePage(composeSubPage);
        }
        rebuildFormatCards();
        refreshEdgeGroups();
        // The picture may have a new shape, so the list of gates that fit it has
        // to be the new one -- otherwise the switch would refuse the next tap.
        refreshEdgeFormats();
        // **Say when the gate did not take.** `refreshEdgeFormats` re-picks the
        // gate nearest to the frame when the one asked for no longer fits, and
        // that used to happen in silence -- which is exactly what "点了都没反应"
        // looks like from the other side of the screen.
        if (!edgeIds.contains(id)) {
            overscanFormat = edgeIds.isEmpty() ? before : edgeIds.get(0);
            if (edgeValue != null) edgeValue.setText(gateLabelsFallback(overscanFormat));
            refreshFormatFoot();
            Probe.log("gate", "refused " + id + " aspect=" + originalAspect() + " fits=" + edgeIds);
            showInAppToast(tr("border.can_t_crop_this_photo") + EDGE_LABELS[at] + tr("border.the_film_gate_is_still")
                    + gateLabelsFallback(overscanFormat));
        } else {
            onEdgeChanged();
            showInAppToast((cropped ? tr("border.cropped_to") + EDGE_RATIO_LABELS[at] + " · " : "")
                    + tr("border.film_gate") + EDGE_LABELS[at]);
        }
        Probe.log("gate", "picked " + id + " cropped=" + cropped
                + " aspect=" + originalAspect() + " fits=" + edgeIds);
    }

    private void showEdgePicker() {
        // **Every gate is usable, because choosing one now gives the picture
        // that shape.** This sheet used to grey out every gate the frame did
        // not already match, so a 3:2 picture offered exactly two rows and both
        // ran the film the same way -- "片边换不了方向" was a selector that had
        // nothing left to pick. A gate is a physical shape; picking it now
        // crops the frame, centred, to that shape *from the whole picture* -- a
        // gate is a shape, not an extra cut on top of the previous gate.
        final float ca = originalAspect();
        final double aspect = ca > 0 ? (double) Math.max(ca, 1f / ca) : 0.0;
        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<String> notes = new java.util.ArrayList<>();
        String selectedLabel = null;
        for (int i = 0; i < EDGE_IDS.length; ++i) {
            final boolean fits = aspect > 0
                    && Math.abs(aspect / EDGE_RATIOS[i] - 1.0) <= 0.045;
            labels.add(EDGE_LABELS[i]);
            notes.add(fits ? null : tr("border.centers_and_crops_to") + EDGE_RATIO_LABELS[i] + tr("border.to_fit_the_film_border"));
            if (EDGE_IDS[i].equals(overscanFormat)) selectedLabel = EDGE_LABELS[i];
        }
        showChoiceSheet(tr("border.choose_a_film_gate"), labels, notes, selectedLabel, null,
                picked -> {
                    final int i = labels.indexOf(picked);
                    if (i < 0) {
                        Probe.log("gate", "picked an unknown label: " + picked);
                        return;
                    }
                    applyGate(EDGE_IDS[i]);
                });
    }

    /**
     * Cut the current window to `want` (an aspect in the picture's own
     * orientation), centred, folded into the crop already applied.
     *
     * <p>Same arithmetic as `commitPendingCrop`, but without the composer's
     * proposal state: this is a decision the user made by picking a gate, not
     * something waiting for an 应用 button.
     */
    private void cropToRatio(float want) {
        // **From the whole frame, every time.** This used to fold the new
        // window into whatever the previous gate had left behind: 135 then 6x6
        // then 6x17 walked a 3:2 picture down to 0.84 x 1.00, then 0.56 x 1.00,
        // then 0.56 x 0.33 -- "一个个片边换着试，图片越裁越小". A gate is a
        // statement about the *whole* picture, so the window is recomputed from
        // the whole picture and then centred, never composed with the last one.
        final float have = Math.max(1e-4f, originalAspect());
        float nw, nh;
        if (want >= have) { nw = 1f; nh = have / want; }
        else { nw = want / have; nh = 1f; }
        geometryCropW = Math.min(1f, Math.max(0.01f, nw));
        geometryCropH = Math.min(1f, Math.max(0.01f, nh));
        geometryCropX = (1f - geometryCropW) * 0.5f;
        geometryCropY = (1f - geometryCropH) * 0.5f;
        cropRatioIndex = -1;
        Probe.log("crop", "gate-crop want=" + want + " W=" + geometryCropW
                + " H=" + geometryCropH + " X=" + geometryCropX + " Y=" + geometryCropY);
    }

    private View buildTabs() {
        final String[] names = {
                tr("navigation.film"), tr("navigation.print"), tr("navigation.optics"),
                tr("navigation.scale"), tr("navigation.compose"), tr("navigation.border")};
        tabBar = new app.spektralab.ui.TabBar(this, new app.spektralab.ui.TabBar.Host() {
            @Override public String label(int index) { return names[index]; }
            @Override public String compositionDescription() { return tr("navigation.composition"); }
            @Override public void onClick(int index) { onTabClicked(index); }
            @Override public TextView createLabel(String text, int color) {
                return mixedLabel(text, 12, color);
            }
        }, names.length, getResources().getDisplayMetrics().density);
        return tabBar.root;
    }

    private void onTabClicked(int index) {
        if (index == activeTabIndex) {
            setPanelCollapsed(!panelCollapsed);
            showInAppToast(panelCollapsed ? tr("navigation.panel_hidden_tap_this_tab") : tr("navigation.panel_shown"));
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

    // Preset persistence and share compaction are pure data work; see PresetStore.
    private org.json.JSONArray presetList() { return presetRepository.load(); }
    private void storePresetList(org.json.JSONArray list) { presetRepository.save(list); }
    private org.json.JSONObject lookCompact(Look l) { return presetRepository.compact(l); }

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
        final android.app.Dialog dialog = newDialog();
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundColor(UiPalette.PAPER);
        View cap = new View(this);
        cap.setBackgroundColor(UiPalette.BLUE);
        sheet.addView(cap, new LinearLayout.LayoutParams(-1, dp(3)));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), 0, dp(16), 0);
        head.addView(label(tr("preset.presets"), 12, UiPalette.INK, tfHan),
                new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = label(tr("crash.close"), 9, UiPalette.MUTED, tfHan);
        close.setPadding(dp(12), dp(14), 0, dp(14));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        sheet.addView(head, new LinearLayout.LayoutParams(-1, dp(54)));

        TextView note = label(tr("preset.presets_store_film_exposure_optics"),
                8.5f, UiPalette.MUTED, tfHan);
        note.setPadding(dp(16), 0, dp(16), dp(10));
        sheet.addView(note);

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        if (list.length() == 0) {
            TextView empty = label(tr("preset.no_presets_yet_edit_a"), 9, UiPalette.MUTED, tfHan);
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
            TextView name = label(p.optString("name", tr("preset.preset")), 10, UiPalette.INK, tfHan);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView apply = label(tr("preset.apply"), 8.5f, UiPalette.BLUE, tfHan);
            apply.setPadding(dp(8), dp(12), dp(8), dp(12));
            apply.setOnClickListener(v -> {
                dialog.dismiss();
                applyPreset(p);
            });
            row.addView(apply);

            TextView share = label(tr("editor.share"), 8.5f, UiPalette.MUTED, tfHan);
            share.setPadding(dp(8), dp(12), dp(8), dp(12));
            share.setOnClickListener(v -> sharePreset(p));
            row.addView(share);

            TextView del = label(tr("preset.delete"), 8.5f, UiPalette.MUTED, tfHan);
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
        TextView save = label(tr("preset.save_current_settings"), 8.5f, UiPalette.BLUE, tfHan);
        save.setGravity(android.view.Gravity.CENTER);
        save.setOnClickListener(v -> {
            dialog.dismiss();
            saveCurrentPreset();
        });
        foot.addView(save, new LinearLayout.LayoutParams(0, dp(34), 1f));
        TextView pull = label(tr("preset.import_from_clipboard"), 8.5f, UiPalette.INK, tfHan);
        pull.setGravity(android.view.Gravity.CENTER);
        pull.setOnClickListener(v -> {
            dialog.dismiss();
            importPresetFromClipboard();
        });
        foot.addView(pull, new LinearLayout.LayoutParams(0, dp(34), 1f));
        TextView push = label(tr("preset.share_current_settings"), 8.5f, UiPalette.INK, tfHan);
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
            showInAppToast(tr("preset.import_a_photo_and_choose"));
            return;
        }
        saveLookIntoFrames();
        final String fallback = stockDisplay(filmStock)
                + (printStock == null || printStock.isEmpty() ? "" : " · " + stockDisplay(printStock));
        askText(tr("preset.preset_name"), fallback, name -> {
            final String n = (name == null || name.trim().isEmpty()) ? tr("preset.untitled_preset") : name.trim();
            try {
                final org.json.JSONArray list = presetList();
                final org.json.JSONObject p = new org.json.JSONObject();
                p.put("name", n);
                p.put("at", System.currentTimeMillis());
                p.put("look", lookToJson(captureLook()));
                list.put(p);
                storePresetList(list);
                showInAppToast(tr("preset.saved_preset") + n + "”");
            } catch (Throwable t) {
                showInAppToast(tr("preset.could_not_save") + t.getMessage());
            }
        });
    }

    private void applyPreset(org.json.JSONObject p) {
        final org.json.JSONObject look = p == null ? null : p.optJSONObject("look");
        if (look == null) {
            showInAppToast(tr("preset.this_preset_is_empty"));
            return;
        }
        final Look l = lookFromJson(look);
        if (l == null) {
            showInAppToast(tr("preset.unrecognized_preset_format"));
            return;
        }
        // **Nothing is applied before the question is answered.** The gate a
        // preset carries may need a crop this photograph does not want, and
        // `applyForeignLook` is what asks.
        applyForeignLook(l, tr("preset.applied_preset") + p.optString("name", "") + "”");
    }

    private void sharePreset(org.json.JSONObject p) {
        if (p == null) return;
        try {
            final org.json.JSONObject out = new org.json.JSONObject();
            out.put("app", "SpektraLab Android");
            out.put("version", UpdateCheck.versionCode(this));
            out.put("name", p.optString("name", tr("preset.spektralab_settings")));
            final org.json.JSONObject raw = p.optJSONObject("look");
            out.put("look", raw == null ? null : lookCompact(lookFromJson(raw)));
            shareLookJson(out);
        } catch (Throwable t) {
            showInAppToast(tr("preset.could_not_share") + t.getMessage());
        }
    }

    private void shareCurrentLook() {
        if (frames.isEmpty() || filmStock == null) {
            showInAppToast(tr("preset.import_a_photo_and_choose"));
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
            showInAppToast(tr("preset.could_not_share") + t.getMessage());
        }
    }

    private void shareLookJson(org.json.JSONObject payload) {
        try {
            final android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(android.content.Intent.EXTRA_SUBJECT, payload.optString("name", tr("preset.spektralab_settings")));
            send.putExtra(android.content.Intent.EXTRA_TEXT,
                    tr("preset.spektralab_settings_copy_all_of") + payload.toString());
            startActivity(android.content.Intent.createChooser(send, tr("preset.share_settings")));
        } catch (Throwable t) {
            showInAppToast(tr("preset.could_not_share") + (t.getMessage() == null ? tr("preset.could_not_encode_the_settings") : t.getMessage()));
        }
    }

    private void importPresetFromClipboard() {
        try {
            final android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) {
                showInAppToast(tr("preset.no_text_on_the_clipboard"));
                return;
            }
            final android.content.ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
            final CharSequence cs = item == null ? null : item.coerceToText(this);
            final String text = cs == null ? "" : cs.toString();
            final int a = text.indexOf('{'), b = text.lastIndexOf('}');
            if (a < 0 || b <= a) {
                showInAppToast(tr("preset.no_preset_json_found_on"));
                return;
            }
            final org.json.JSONObject o = new org.json.JSONObject(text.substring(a, b + 1));
            final org.json.JSONObject look = o.optJSONObject("look") != null ? o.optJSONObject("look") : o;
            final Look l = lookFromJson(look);
            if (l == null) {
                showInAppToast(tr("preset.this_json_does_not_contain"));
                return;
            }
            applyForeignLook(l, tr("preset.imported_and_applied"));
        } catch (Throwable t) {
            showInAppToast(tr("preset.could_not_import") + (t.getMessage() == null ? tr("preset.invalid_format") : t.getMessage()));
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
    /**
     * Drop several chips at once.
     *
     * <p>Deletion walks the list from the back: `removeFrame` reindexes in
     * place, so removing 1 before 3 would silently leave 3 pointing at what
     * used to be 4. Every chip is still its own tap; this is the road for the
     * ordinary case of "I imported twelve RAW and want eight of them gone".
     */
    private void showBatchDeleteSheet() {
        if (batchExportRunning) {
            showInAppToast(tr("editor.wait_for_batch_export_to_2"));
            return;
        }
        if (frames.size() < 2) {
            showInAppToast(tr("preset.there_is_only_one_photo"));
            return;
        }
        final String[] labels = new String[frames.size()];
        for (int i = 0; i < frames.size(); ++i) {
            final Frame f = frames.get(i);
            labels[i] = (f.name == null || f.name.isEmpty() ? tr("preset.photo") + (i + 1) : f.name);
        }
        showMultiChoiceSheet(tr("preset.remove_which_photos"),
                tr("preset.removes_photos_and_their_edits"),
                labels, picked -> {
                    final java.util.ArrayList<Integer> indices =
                            new java.util.ArrayList<>(picked);
                    java.util.Collections.sort(indices, (a, b) -> b - a);
                    for (int i = 0; i < indices.size(); ++i) removeFrame(indices.get(i));
                    showInAppToast(tr("preset.removed") + indices.size() + tr("preset.photos"));
                });
    }

    private void showApplyLookSheet(final int sourceIndex) {
        if (sourceIndex < 0 || sourceIndex >= frames.size()) return;
        final java.util.ArrayList<Integer> targets = new java.util.ArrayList<>();
        for (int i = 0; i < frames.size(); ++i) {
            if (i != sourceIndex) targets.add(i);
        }
        if (targets.isEmpty()) {
            showInAppToast(tr("preset.there_are_no_other_photos"));
            return;
        }
        final String[] labels = new String[targets.size()];
        for (int i = 0; i < targets.size(); ++i) {
            final int index = targets.get(i);
            final Frame f = frames.get(index);
            labels[i] = (f.name == null || f.name.isEmpty() ? tr("preset.photo") + (index + 1) : f.name);
        }
        final String srcName = frames.get(sourceIndex).name == null
                ? tr("preset.current_photo") : frames.get(sourceIndex).name;
        showMultiChoiceSheet(tr("preset.apply_these_settings_to"),
                tr("preset.copies_the_current_film_exposure")
                        + tr("preset.original_files_are_not_changed"),
                labels, picked -> {
                    saveLookIntoFrames();
                    final Look src = copyLook(frames.get(sourceIndex).look);
                    if (src == null) {
                        showInAppToast(tr("preset.this_photo_has_no_settings"));
                        return;
                    }
                    int n = 0;
                    for (int k : picked) {
                        final int index = targets.get(k);
                        frames.get(index).look = copyLook(src);
                        // **A copied Look is a picture that has been printed.**
                        // `Frame.original` says "this chip sits on 原图", and the
                        // copy path left it alone -- so a target that had never
                        // picked a film opened on the original, drew no film at
                        // all, and the whole operation looked like it had done
                        // nothing ("应用全部调色似乎不起作用"). Keeping the flag
                        // is only right when the source itself has no film.
                        frames.get(index).original = (src.filmStock == null);
                        ++n;
                    }
                    saveSessionState();
                    rebuildFrameChips();
                    showInAppToast(tr("preset.applied_to") + n + tr("preset.photos_changes_appear_when_opened"));
                });
    }

    /** A bottom sheet with checkboxes; `onDone` gets the picked indices. */
    private void showMultiChoiceSheet(String title, String note, String[] labels,
                                      final java.util.function.Consumer<java.util.List<Integer>> onDone) {
        final android.app.Dialog dialog = newDialog();
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundColor(UiPalette.PAPER);
        View cap = new View(this);
        cap.setBackgroundColor(UiPalette.BLUE);
        sheet.addView(cap, new LinearLayout.LayoutParams(-1, dp(3)));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), 0, dp(16), 0);
        head.addView(label(title, 12, UiPalette.INK, tfHan), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = label(tr("crash.close"), 9, UiPalette.MUTED, tfHan);
        close.setPadding(dp(12), dp(14), 0, dp(14));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        sheet.addView(head, new LinearLayout.LayoutParams(-1, dp(54)));
        if (note != null && !note.isEmpty()) {
            TextView noteView = label(note, 8.5f, UiPalette.MUTED, tfHan);
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
            final Square sq = new Square(this, UiPalette.BLUE, UiPalette.toggleOff());
            marks[i] = sq;
            LinearLayout.LayoutParams sqLp = new LinearLayout.LayoutParams(dp(20), dp(20));
            sqLp.rightMargin = dp(12);
            row.addView(sq, sqLp);
            TextView name = label(labels[i], 10, UiPalette.INK, tfHan);
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
        final TextView all = label(tr("preset.select_all"), 9, UiPalette.MUTED, tfHan);
        all.setGravity(android.view.Gravity.CENTER);
        all.setOnClickListener(v -> {
            final boolean want = !all.isSelected();
            all.setSelected(want);
            all.setText(want ? tr("preset.deselect_all") : tr("preset.select_all"));
            for (int i = 0; i < picked.length; ++i) {
                picked[i] = want;
                if (marks[i] != null) marks[i].setLit(want);
            }
        });
        foot.addView(all, new LinearLayout.LayoutParams(0, dp(34), 1f));
        TextView apply = label(tr("preset.apply"), 9, UiPalette.BLUE, tfHan);
        apply.setGravity(android.view.Gravity.CENTER);
        apply.setOnClickListener(v -> {
            final java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
            for (int i = 0; i < picked.length; ++i) if (picked[i]) out.add(i);
            if (out.isEmpty()) {
                showInAppToast(tr("preset.select_at_least_one_photo"));
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
     * The same sheet with a line that never scrolls away.
     *
     * <p>The update announcement is the one place where a line has to survive
     * the reader: a seventy-item changelog scrolls, and the download address
     * inside it scrolls with it. Pinned above the list, it is on screen from
     * the moment the sheet opens until it closes.
     */
    void showActionSheet(String title, String note, String[] items,
                         java.util.function.IntConsumer onPick, Runnable onTitleLongPress,
                         String pinned) {
        showSheetBody(title, note, items, onPick, onTitleLongPress, pinned);
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
        showActionSheet(title, note, items, onPick, onTitleLongPress, null);
    }

    /** The body. `pinned` (may be null) sits above the note and does not scroll. */
    private void showSheetBody(String title, String note, String[] items,
                               java.util.function.IntConsumer onPick, final Runnable onTitleLongPress,
                               String pinned) {
        final android.app.Dialog dialog = newDialog();
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundColor(UiPalette.PAPER);
        View cap = new View(this);
        cap.setBackgroundColor(UiPalette.BLUE);
        sheet.addView(cap, new LinearLayout.LayoutParams(-1, dp(3)));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), 0, dp(16), 0);
        final TextView titleView = label(title, 12, UiPalette.INK, tfHan);
        if (onTitleLongPress != null) {
            titleView.setOnLongClickListener(v -> {
                dialog.dismiss();
                onTitleLongPress.run();
                return true;
            });
        }
        head.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = label(tr("crash.close"), 9, UiPalette.MUTED, tfHan);
        close.setPaintFlags(close.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        close.setPadding(dp(12), dp(14), 0, dp(14));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        sheet.addView(head, new LinearLayout.LayoutParams(-1, dp(54)));

        final int pinHeight;
        if (pinned != null && !pinned.isEmpty()) {
            final TextView pinView = label(pinned, 9f, UiPalette.BLUE, tfHan);
            pinView.setTextIsSelectable(true);
            pinView.setPadding(dp(16), 0, dp(16), dp(10));
            sheet.addView(pinView);
            pinHeight = dp(34);
        } else {
            pinHeight = 0;
        }
        if (note != null && !note.isEmpty()) {
            TextView noteView = label(note, 8.5f, UiPalette.MUTED, tfHan);
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
            final int reserved = dp(54) + pinHeight + items.length * dp(50) + dp(28);
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
            TextView row = label(items[i], 11, UiPalette.INK, tfHan);
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
        final android.app.Dialog dialog = newDialog();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(UiPalette.PAPER);
        View cap = new View(this);
        cap.setBackgroundColor(UiPalette.BLUE);
        card.addView(cap, new LinearLayout.LayoutParams(-1, dp(3)));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), 0, dp(16), 0);
        head.addView(mixedLabel(tr("feedback.feedback_community"), 8, UiPalette.BLUE),
                new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = label(tr("crash.close"), 9, UiPalette.MUTED, tfHan);
        close.setPaintFlags(close.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        close.setPadding(dp(12), dp(14), 0, dp(14));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        card.addView(head, new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), 0, dp(16), 4);
        TextView title = label(tr("feedback.need_help_or_want_to"), 17, UiPalette.INK, tfHan);
        body.addView(title);
        TextView copyText = label(tr("feedback.use_the_group_to_report")
                + tr("feedback.if_you_can_reproduce_the"), 10, UiPalette.MUTED, tfHan);
        copyText.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams copyTextLp = new LinearLayout.LayoutParams(-1, -2);
        copyTextLp.topMargin = dp(9);
        body.addView(copyText, copyTextLp);

        // **Two rooms, one door.** The QQ group is a number you type; the
        // WeChat group is a code you scan. A report should never stall on
        // "which app do I not have installed".
        final LinearLayout wxRow = new LinearLayout(this);
        wxRow.setOrientation(LinearLayout.HORIZONTAL);
        wxRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams wxRowLp = new LinearLayout.LayoutParams(-1, -2);
        wxRowLp.topMargin = dp(16);
        final android.widget.ImageView qr = new android.widget.ImageView(this);
        final int qrId = getResources().getIdentifier("wechat_qr", "drawable", getPackageName());
        if (qrId != 0) qr.setImageResource(qrId);
        qr.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        qr.setBackgroundColor(0xFFFFFFFF);
        qr.setPadding(dp(5), dp(5), dp(5), dp(5));
        wxRow.addView(qr, new LinearLayout.LayoutParams(dp(112), dp(112)));
        final LinearLayout wxText = new LinearLayout(this);
        wxText.setOrientation(LinearLayout.VERTICAL);
        wxText.setPadding(dp(14), 0, 0, 0);
        wxText.addView(label(tr("feedback.wechat_group"), 15, UiPalette.INK, tfHan));
        TextView wxHint = label(tr("feedback.scan_with_wechat_to_join"), 9.5f, UiPalette.MUTED, tfHan);
        LinearLayout.LayoutParams wxHintLp = new LinearLayout.LayoutParams(-1, -2);
        wxHintLp.topMargin = dp(4);
        wxText.addView(wxHint, wxHintLp);
        TextView wxHint2 = label(tr("feedback.if_the_qr_code_has"), 8.5f, UiPalette.MUTED, tfHan);
        wxHint2.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams wxHint2Lp = new LinearLayout.LayoutParams(-1, -2);
        wxHint2Lp.topMargin = dp(4);
        wxText.addView(wxHint2, wxHint2Lp);
        wxRow.addView(wxText, new LinearLayout.LayoutParams(0, -2, 1f));
        body.addView(wxRow, wxRowLp);

        View divider = new View(this);
        divider.setBackgroundColor(UiPalette.LINE);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(-1, dp(1));
        dividerLp.topMargin = dp(16);
        body.addView(divider, dividerLp);

        TextView number = label(QQ_GROUP, 20, UiPalette.BLUE, tfDisplay);
        number.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams numberLp = new LinearLayout.LayoutParams(-1, -2);
        numberLp.topMargin = dp(16);
        body.addView(number, numberLp);
        TextView hint = label(tr("feedback.copy_the_group_id_below"), 9, UiPalette.MUTED, tfHan);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(5);
        body.addView(hint, hintLp);
        card.addView(body, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView open = label(tr("feedback.open_qq"), 10, UiPalette.BLUE, tfHan);
        open.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable openBg = new android.graphics.drawable.GradientDrawable();
        openBg.setColor(UiPalette.PAPER);
        openBg.setStroke(dp(1), UiPalette.BLUE);
        open.setBackground(openBg);
        open.setOnClickListener(v -> openQqGroup());
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        openLp.rightMargin = dp(8);
        actions.addView(open, openLp);

        TextView copy = label(tr("feedback.copy_group_id"), 10, UiPalette.PAPER, tfHan);
        copy.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable copyBg = new android.graphics.drawable.GradientDrawable();
        copyBg.setColor(UiPalette.BLUE);
        copy.setBackground(copyBg);
        copy.setOnClickListener(v -> {
            if (copyToClipboard(tr("feedback.spektralab_feedback_group"), QQ_GROUP)) {
                copy.setText(tr("feedback.copied"));
            } else {
                copy.setText(tr("feedback.could_not_copy_press_and"));
            }
        });
        copy.setLongClickable(true);
        copy.setOnLongClickListener(v -> {
            copyToClipboard(tr("feedback.spektralab_feedback_group"), QQ_GROUP);
            return true;
        });
        actions.addView(copy, new LinearLayout.LayoutParams(0, dp(42), 1.45f));
        card.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        if (auto) {
            TextView mute = label(tr("feedback.don_t_show_automatically"), 8.5f, UiPalette.MUTED, tfHan);
            mute.setGravity(android.view.Gravity.CENTER);
            mute.setPaintFlags(mute.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            mute.setPadding(0, 0, 0, dp(14));
            mute.setOnClickListener(v -> {
                p.edit().putBoolean(PROMO_MUTED, true).apply();
                dialog.dismiss();
                showInAppToast(tr("feedback.automatic_prompts_are_off_the"));
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
            try { DebugSink.text("clipboard", String.valueOf(t)); } catch (Throwable spkIgnored) { Diagnostics.ignored("copyToClipboard:8198", spkIgnored); }
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
            } catch (Throwable spkIgnored) { Diagnostics.ignored("openQqGroup:8215", spkIgnored); }
        }
        copyToClipboard(tr("feedback.spektralab_feedback_group"), QQ_GROUP);
        showInAppToast(tr("feedback.group_id_copied_search_for"));
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
        showIndexedChoiceSheet(title, labels, notes, selected == null ? -1 : labels.indexOf(selected),
                selectable, index -> onPick.accept(labels.get(index)));
    }

    private void showIndexedChoiceSheet(String title, List<String> labels, List<String> notes,
                                        int selected, java.util.function.IntPredicate selectable,
                                        java.util.function.IntConsumer onPick) {
        final android.app.Dialog dialog = newDialog();
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundColor(UiPalette.PAPER);
        View cap = new View(this);
        cap.setBackgroundColor(UiPalette.BLUE);
        sheet.addView(cap, new LinearLayout.LayoutParams(-1, dp(3)));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), 0, dp(16), 0);
        head.addView(label(title, 12, UiPalette.INK, tfHan), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = label(tr("crash.close"), 9, UiPalette.MUTED, tfHan);
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
            final int choice = index;
            final String name = labels.get(index);
            final String note = (notes != null && index < notes.size()) ? notes.get(index) : null;
            final boolean enabled = selectable == null || selectable.test(index);
            final boolean active = index == selected;
            if (note != null && !note.isEmpty()) anyNote = true;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            // A refused row is still readable: it is an option the user should
            // know exists, and only its tint says it is not for this film.
            row.setAlpha(enabled ? 1.0f : 0.38f);
            row.setSelected(active);

            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            TextView text = label(name, 11, !enabled ? UiPalette.MUTED : (active ? UiPalette.BLUE : UiPalette.INK), tfHan);
            text.setMaxLines(1);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            column.addView(text, new LinearLayout.LayoutParams(-1, -2));
            if (note != null && !note.isEmpty()) {
                TextView sub = label(note, 8.5f, UiPalette.MUTED, tfHan);
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
            dotBg.setColor(active ? UiPalette.BLUE : 0x00000000);
            dotBg.setStroke(dp(1), active ? UiPalette.BLUE : UiPalette.MUTED);
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(13), dp(13));
            dotLp.leftMargin = dp(10);
            row.addView(dot, dotLp);

            if (enabled) {
                row.setClickable(true);
                row.setOnClickListener(v -> {
                    dialog.dismiss();
                    onPick.accept(choice);
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
    private static String sanitizeMeter(String m) { return LookCodec.sanitizeMeter(m); }

    private String meterName(String id) {
        if (id == null || id.isEmpty()) return tr("meter.off");
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
        showChoiceSheet(tr("film.metering_mode_sets_overall_exposure"), labels, notes, labels.get(at), null,
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
        meterChip.setTextColor(on ? UiPalette.BLUE : UiPalette.MUTED);
        meterChip.setBackground(outlineBox(on));
    }

    private void resetFilmPanel() {
        // 原图 is the reset: no stock, no render, the imported frame as it was.
        //
        // **The reset has to move the canvas itself.** This used to clear
        // `filmStock` and call `onStockChanged`, and that method returns early
        // when there is no film ("选择一张胶片开始渲染") -- so the last render
        // stayed on the screen and 重置 looked like a dead button. The state a
        // reset means is exactly the state an import lands in: the frame
        // itself on the canvas, no stock chosen, nothing for `toggleOriginal`
        // to flip back to.
        filmStock = null;
        renderEpoch++;
        clearBusyPlate();
        // No film, no chain: both pages go grey together.
        refreshPanelLocks();
        printStock = "kodak_portra_endura";
        meterMethod = isRawFile ? "protect_highlights" : "";
        refreshMeterChip();
        brandFilter = "all";
        showingOriginal = true;
        lastRenderedBitmap = null;
        cancelLivePreview();
        rebuildBrandRow();
        rebuildFilmCards();
        refreshPaperLabel();
        refreshHueOwnMark();
        if (compare != null) {
            compare.setAfter(null);
            if (beforeBitmap != null) compare.setVisibility(View.VISIBLE);
        }
        if (emptyState != null && beforeBitmap != null) emptyState.setVisibility(View.GONE);
        if (rawState != null) rawState.setVisibility(View.GONE);
        if (statusText != null && beforeBitmap != null) {
            statusText.setText(tr("film.original_ungraded_raw_preview_choose"));
        }
        if (histogram != null) histogram.setBitmap(null);
        setOriginalVisual(true);
        updateFilmSelection();
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
        refreshFormatFoot();
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
            showInAppToast(tr("film.import_a_photo_first"));
            return;
        }
        // An unrendered import is already the original: pressing this must not
        // become the thing that starts a render. **Unless a film is chosen** --
        // a frame reopened on 原图 carries its look but no bitmap, and it has to
        // be able to come back to the developed side.
        if (showingOriginal && lastRenderedBitmap == null && filmStock == null) {
            showInAppToast(tr("film.choose_a_film_to_render"));
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
            statusText.setText(tr("film.original_ungraded_raw_preview_choose"));
            setOriginalVisual(true);
            updateFilmSelection();
            // **The original is not the render.** Every slider on 光学 / 冲印
            // acts after the engine returns, so with the source on the canvas
            // they are pointing at a picture that cannot reach them -- and a
            // drag used to re-render, which threw the user back onto the film
            // without asking.
            refreshPanelLocks();
            schedulePersist();
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
            refreshPanelLocks();
            schedulePersist();
        } else if (session != null && uiReady) {
            renderImage();
        }
    }

    /** A drawn tool glyph: no font fallback tofu at 19 dp. */
    

    /** Only the gates whose shape this frame is: the engine's own 5 % rule. */
    /** The gate's own long/short, or 0 when the id is not one of ours. */
    private static double gateRatio(String id) {
        final int i = gateIndex(id);
        return i < 0 ? 0.0 : EDGE_RATIOS[i];
    }

    private String gateLabel(String id) {
        final int i = gateIndex(id);
        return i < 0 ? tr("editor.film_gate") : EDGE_LABELS[i];
    }

    /** "16:9" for the shapes people name, two decimals for the rest. */
    private static String ratioText(double a) {
        if (Math.abs(a - 16.0 / 9.0) < 0.02) return "16:9";
        if (Math.abs(a - 3.0 / 2.0) < 0.02) return "3:2";
        if (Math.abs(a - 4.0 / 3.0) < 0.02) return "4:3";
        if (Math.abs(a - 5.0 / 4.0) < 0.02) return "5:4";
        if (Math.abs(a - 1.0) < 0.02) return "1:1";
        if (Math.abs(a - 21.0 / 9.0) < 0.02) return "21:9";
        if (Math.abs(a - 9.0 / 16.0) < 0.02) return "9:16";
        if (Math.abs(a - 2.0 / 3.0) < 0.02) return "2:3";
        if (Math.abs(a - 3.0 / 4.0) < 0.02) return "3:4";
        if (Math.abs(a - 1.0 / 2.0) < 0.02) return "1:2";
        if (Math.abs(a - 1.0 / 3.0) < 0.02) return "1:3";
        return String.format(java.util.Locale.US, "%.2f:1", a);
    }

    /**
     * A look written on another photograph, applied to this one.
     *
     * <p>It carries that frame's crop and the gate it was cut for. When the
     * gate cannot hold the shape this photograph lands on, the engine refuses
     * the render outright -- so the choice has to be made before anything is
     * applied, and it belongs to the user: cropping to the gate restores his
     * rebate and throws picture away, not cropping keeps every pixel and drops
     * the rebate. Neither is a default worth guessing, which is why this asks.
     */
    private void applyForeignLook(final Look l, final String done) {
        if (l == null) return;
        final double gate = l.overscanOn ? gateRatio(l.overscanFormat) : 0.0;
        final double have = Math.max(1e-4, (double) originalAspect());
        final double cropW = l.geometryCropW > 0.01f ? l.geometryCropW : 1.0;
        final double cropH = l.geometryCropH > 0.01f ? l.geometryCropH : 1.0;
        final double raw = have * cropW / cropH;
        final double lands = Math.max(raw, 1.0 / raw);
        if (gate <= 0 || Math.abs(lands / gate - 1.0) <= 0.045) {
            applyLook(l);
            finishForeignLook(l, done, false);
            return;
        }

        final String name = gateLabel(l.overscanFormat);
        final int w = importedWidth > 0 ? importedWidth : sourceWidth;
        final int h = importedHeight > 0 ? importedHeight : sourceHeight;
        if (w <= 0 || h <= 0) {
            // No photograph yet: there is no shape to crop, and the preset can
            // still set the film and the sliders.
            applyLook(l);
            finishForeignLook(l, done, false);
            return;
        }
        // **The gate keeps the picture's own orientation.** A 2:3 portrait on a
        // 6x8 gate is that gate turned on its side, not a landscape strip cut
        // out of the middle -- so a portrait frame targets the reciprocal.
        final double want = have < 1.0 ? 1.0 / gate : gate;
        final boolean cutWide = want < have;
        final int cw = cutWide ? (int) Math.round(h * want) : w;
        final int ch = cutWide ? h : (int) Math.round(w / want);
        final int loss = (int) Math.round(100.0 * (1.0 - (cutWide ? (double) cw / w : (double) ch / h)));
        showActionSheet(tr("editor.this_preset_uses_a") + name + tr("editor.film_border"),
                tr("editor.photo_ratio") + ratioText(have) + ". " + name + tr("editor.gate_ratio") + ratioText(want) + ".\n\n"
                + tr("editor.matching_the_preset_border_requires") + (cutWide ? tr("editor.horizontal") : tr("editor.vertical")) + tr("editor.cropping_of") + loss
                + "% (" + w + "×" + h + " → " + cw + "×" + ch + ").\n"
                + tr("editor.without_cropping_only_the_settings"),
                new String[]{tr("editor.crop_to") + name, tr("editor.apply_settings_without_border"), tr("editor.cancel")},
                which -> {
                    if (which == 0) {
                        applyLook(l);
                        cropToRatio((float) want);
                        finishForeignLook(l, done, false);
                    } else if (which == 1) {
                        l.overscanOn = false;
                        applyLook(l);
                        finishForeignLook(l, done, true);
                    } else {
                        showInAppToast(tr("editor.settings_not_applied"));
                    }
                });
    }

    private void finishForeignLook(Look look, String done, boolean rebateClosed) {
        final boolean dropped = refreshEdgeFormats(true);
        saveLookIntoFrames();
        if (session != null) applyParams();
        if (uiReady && session != null) {
            showingOriginal = false;
            setOriginalVisual(false);
            renderImage();
        }
        showInAppToast(dropped || rebateClosed ? done + tr("editor.film_border_off") : done);
    }

    private boolean refreshEdgeFormats() {
        return refreshEdgeFormats(false);
    }

    /**
     * @param quiet true when the caller will explain the outcome itself: the
     *              preset path closes a rebate on the user's behalf and then
     *              says so in one sentence, and two toasts in a row only show
     *              the second one.
     * @return true when a rebate that was on had to be switched off
     */
    private boolean refreshEdgeFormats(boolean quiet) {
        boolean rebateClosed = false;
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
        // **No gate fits this frame.** The list is the engine's own 5 % rule, so
        // an empty list means the render will be refused -- and the format kept
        // the previous photograph's gate, which is how a restored 6x8 on a
        // 1.88:1 frame became E-1305 right after an import, a crop, or a batch
        // export. Ask for no rebate rather than for one that cannot be printed,
        // and say why the switch just went off.
        if (edgeIds.isEmpty()) {
            overscanFormat = "135";
            if (overscanOn) {
                overscanOn = false;
                rebateClosed = true;
                if (overscanMark != null) overscanMark.setLit(false);
                // The page's controls dim with the switch; without this the
                // rows stay lit while the feature they govern is off.
                refreshEdgeGroups();
                if (!quiet) showInAppToast(tr("editor.no_film_gate_matches_this"));
            }
        }
        if (edgeValue != null) {
            final int selected = edgeIds.indexOf(overscanFormat);
            edgeValue.setText(selected >= 0 ? edgeLabels.get(selected) : tr("editor.no_compatible_film_gate"));
        }
        try {
            DebugSink.text("overscan", "aspect=" + aspect + " gates=" + edgeIds
                    + " chosen=" + overscanFormat);
        } catch (Throwable spkIgnored) { Diagnostics.ignored("indexOf:8780", spkIgnored); }
        return rebateClosed;
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
                            try { prefetchEngine.trimPool(); } catch (Throwable spkIgnored) { Diagnostics.ignored("currentTimeMillis:8859", spkIgnored); }
                        }
                    }
                    if (thumb == null) continue;
                    try { DebugSink.text("prefetch-timing", id + " " + ms + " ms"); } catch (Throwable spkIgnored) { Diagnostics.ignored("currentTimeMillis:8863", spkIgnored); }
                    UiDispatcher.post(() -> {
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
                } catch (Throwable spkIgnored) { Diagnostics.ignored("currentTimeMillis:8882", spkIgnored); }
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
            } catch (Throwable spkIgnored) { Diagnostics.ignored("setPrefetchEdge:8904", spkIgnored); }
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
                try { DebugSink.text("prefetch", "render " + stock + ": " + t); } catch (Throwable spkIgnored) { Diagnostics.ignored("round:8968", spkIgnored); }
                return null;
            } finally {
                // Nothing is put back. The card left its own film on the
                // session; the next render or export states the user's film in
                // its own delta, so there is nothing here to keep in step.
                try { s.renderEnd(); } catch (Throwable spkIgnored) { Diagnostics.ignored("round:8974", spkIgnored); }
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
                try (InputStream in = openUri(sourceUri)) {
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
        } catch (Throwable spkIgnored) { Diagnostics.ignored("imprintDate:9160", spkIgnored); }
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
        row.addView(label(labelText, 8, UiPalette.INK, tfHan), new LinearLayout.LayoutParams(0, -2, 1f));
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
                statusText.setText(tr("print.rendering_film_settings_about_1"));
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
        head.addView(label(name, 8, UiPalette.INK, tfHan), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView value = label(String.format(java.util.Locale.US, "%.2f", initial), 8, UiPalette.BLUE, tfBody);
        head.addView(value);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(-1, -2);
        headLp.bottomMargin = dp(2);
        row.addView(head, headLp);
        if (hint != null && !hint.isEmpty()) {
            TextView note = label(hint, 7.5f, UiPalette.MUTED, tfHan);
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
        // **32 dp, and the same 32 as everywhere else.** It was 44 here, which
        // made this row's track four times thicker than the optics page's:
        // the thickness was derived from the height. `ScrubBar` maps the whole
        // width to the value, so the thumb never has to be caught precisely.
        row.addView(bar, new LinearLayout.LayoutParams(-1, dp(32)));
        parent.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
        return bar;
    }

    /** 3 dp track, square 9 dp thumb: input[type=range] in the page. */
    private void styleRange(SeekBar bar) {
        // **One track for every slider in the app, whatever the row's height.**
        //
        // The thickness used to come out of a fixed 10 dp inset, so the same
        // code drew a 3 dp track on a 32 dp row and a 15 dp one on a 44 dp row
        // -- two sliders that looked like they belonged to different apps.
        // `setLayerHeight` states the thickness outright and centres it, so the
        // track no longer depends on how tall the view was given.
        final int thickness = dp(3);
        android.graphics.drawable.ShapeDrawable back = new android.graphics.drawable.ShapeDrawable(
                new android.graphics.drawable.shapes.RectShape());
        back.getPaint().setColor(UiPalette.LINE);
        android.graphics.drawable.ShapeDrawable fill = new android.graphics.drawable.ShapeDrawable(
                new android.graphics.drawable.shapes.RectShape());
        fill.getPaint().setColor(UiPalette.BLUE);
        android.graphics.drawable.ClipDrawable clip = new android.graphics.drawable.ClipDrawable(
                fill, android.view.Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{back, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        layers.setLayerHeight(0, thickness);
        layers.setLayerGravity(0, android.view.Gravity.CENTER_VERTICAL);
        layers.setLayerHeight(1, thickness);
        layers.setLayerGravity(1, android.view.Gravity.CENTER_VERTICAL);
        bar.setProgressDrawable(layers);

        android.graphics.drawable.ShapeDrawable thumb = new android.graphics.drawable.ShapeDrawable(
                new android.graphics.drawable.shapes.RectShape());
        thumb.getPaint().setColor(UiPalette.BLUE);
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
        syncRotateGrid();
        for (int i = 0; i < panels.length; ++i) {
            panels[i].setVisibility(i == active ? View.VISIBLE : View.GONE);
        }
        if (tabBar != null) tabBar.select(active);
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
    /**
     * Grey the 光学 page out while there is no film, and say why.
     *
     * <p>**This page cannot do anything before a film exists**, and that is not
     * a policy: the parameters it owns never reach the engine (`setViewAdjust`
     * fills `g_adjust`, which is consumed by `copyArgbRange` and `grade_result`
     * -- both after `spk_render` returns). The 58-key delta has no optics keys
     * at all, so the film is rendered from the untouched source no matter what
     * these sliders say. Leaving the controls live taught the opposite.
     */
    private void refreshViewLock() {
        final boolean locked = !panelsLive();
        if (viewLockNote != null) viewLockNote.setVisibility(locked ? View.VISIBLE : View.GONE);
        if (viewRoot == null) return;
        for (int i = 0; i < viewRoot.getChildCount(); ++i) {
            final View child = viewRoot.getChildAt(i);
            if (child == viewLockNote) continue;          // the reason stays readable
            setEnabledDeepExcept(child, !locked, null);
        }
        // The page's own rules run again on the way out (a JPEG cannot re-decode
        // its white balance), or the blanket enable would undo them.
        if (!locked) refreshViewPanel();
    }

    /**
     * **What both panels act on.** A render, and only a render: the 光学 page
     * writes `g_adjust` (consumed after `spk_render`), the 冲印 page writes
     * print params, and neither can touch the decoded frame that 原图 shows.
     * So "no film" and "showing the original" are the same state as far as
     * these two pages are concerned.
     */
    private boolean panelsLive() {
        return filmStock != null && !showingOriginal;
    }

    /** Take the RENDERING plate off the canvas. Safe to call twice. */
    private void clearBusyPlate() {
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (busyLabel != null) busyLabel.setVisibility(View.GONE);
    }

    /** One switch for both pages: 光学 greys with a reason, 冲印 just greys. */
    private void refreshPanelLocks() {
        refreshViewLock();
        setOpticsUnlocked(panelsLive());
    }

    private void setOpticsUnlocked(boolean unlocked) {
        opticsUnlocked = unlocked;
        if (opticsPanel == null) return;
        // **Dimmed is not the same as disabled.** Alpha alone left every row
        // tappable: 配光, EDR, 保留胶片原色, 镜头滤镜, 预览色域, 保护基准 and the
        // knob strip all ran a render from a page that had no chain to render
        // into -- and the render then flipped `showingOriginal` off, which read
        // as "drag a print slider and the picture jumps back to the film".
        for (int i = 0; i < opticsPanel.getChildCount(); ++i)
            setEnabledDeepExcept(opticsPanel.getChildAt(i), unlocked, null);
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
            showInAppToast(tr("file.wait_for_batch_export_to"));
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
        showActionSheet(tr("file.import_photos"),
                tr("file.to_keep_shooting_metadata_exif")
                        + tr("file.the_system_photo_picker_may"), new String[]{
                tr("file.photo_library_fast_may_lose"),
                tr("file.file_browser_raw_jpeg_heic")
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
            showInAppToast(tr("file.no_files_have_been_exported"));
            return;
        }
        try {
            final Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(lastExportMime == null ? "image/*" : lastExportMime);
            send.putExtra(Intent.EXTRA_STREAM, lastExportUri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, tr("file.share_to")));
        } catch (Throwable t) {
            showErrorDialog(tr("file.could_not_share"), describe(t));
        }
    }

    private void showExportDialog() {
        if (batchExportRunning) {
            // **The only cancel.** A batch can run for ten minutes; without a
            // way out the user's choice is to kill the app.
            showActionSheet(tr("file.batch_export_in_progress"), tr("file.photos_are_exported_one_at"),
                    new String[]{tr("file.stop_batch_export"), tr("file.continue")}, which -> {
                        if (which == 0) batchExportAbort = true;
                    });
            return;
        }
        if (session == null) {
            showInAppToast(tr("film.import_a_photo_first"));
            return;
        }
        final String full = (sourceWidth > 0 && sourceHeight > 0)
                ? tr("file.full_size") + sourceWidth + "×" + sourceHeight : tr("file.full_size_2");
        // No ceiling to warn about: the export renders the frame that was
        // imported, and that may be the file's own size. What is worth saying
        // is that it takes time, not that it will be refused.
        final String fullNote = tr("file.original_dimensions_large_images_are");
        // What the driver says it can take, as opposed to what this build
        // currently lets through. The two were only ever visible as one number,
        // and it was always the smaller -- so a phone reporting 2 GiB of
        // maxStorageBufferRange looked like it was capped at 15 MP for no
        // stated reason. Name both, and say which one is temporary.
        final String driverLine;
        if (probe.maxStorageBufferRange > 0) {
            final double driverMp = probe.maxStorageBufferRange / 12.0 * 0.80 / 1e6;
            final double budgetMp = maxImportPixels() / 1e6;
            driverLine = AppLanguage.format("file.driver_buffer_limit_0f_mib",
                    probe.maxStorageBufferRange / 1048576.0, budgetMp);
        } else {
            driverLine = probe.valid ? tr("file.the_driver_did_not_report") : tr("file.could_not_read_device_information");
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
                ? tr("file.tiff_uses_16_bit_color")
                  + tr("file.after_exporting_use_share_next")
                : tr("file.tiff_uses_16_bit_color")
                  + tr("file.after_exporting_use_share_next_2"))
                + "\n" + driverLine
                + tr("file.dng_is_for_desktop_lightroom")
                  + tr("file.the_mobile_version_of_lightroom");
        final boolean batchable = frames.size() > 1;
        final java.util.ArrayList<String> rows = new java.util.ArrayList<>();
        if (batchable) rows.add(tr("file.export_all") + frames.size() + tr("file.photos_full_size_jpeg"));
        rows.add(tr("file.jpeg_live_quality"));
        rows.add(tr("file.jpeg_preview_quality_3400_px"));
        rows.add(tr("file.jpeg_full_size"));
        rows.add(tr("file.16_bit_tiff_live_quality"));
        rows.add(tr("file.16_bit_tiff_preview_quality"));
        rows.add(tr("file.16_bit_tiff_full_size"));
        rows.add(tr("file.dng_full_size_desktop_only"));
        showActionSheet(tr("file.export_photo"), note, rows.toArray(new String[0]), which0 -> {
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

    

    /**
     * The stage's picture, and the two ways to see what the film did: hold it
     * to see the frame before, or drag the middle line to keep both in view.
     *
     * Each side is drawn fit-inside, in its own frame: a rebate makes the
     * "after" a different shape from the raw, and cropping one to match the
     * other would misrepresent what is being compared.
     */
    

    /** .frame-guides: a 9 % inset rule, a centre cross, four corner marks. */
    

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
        final String budgetMessage = tr("import.original") + w + "×" + h + " (" + megapixels(w, h) + " MP)."
                + tr("import.full_size_renders_in_about") + Math.max(5, (int) Math.round((double) w * h / 1e6 * 1.4))
                + tr("import.s_the_gpu_needs_about") + nativeMb + tr("import.mb_of_contiguous_memory_available")
                + availMb + " MB."
                + (nativeMb * 10L > availMb * 8L
                        ? tr("import.android_may_close_the_app") : "")
                + tr("import.resize_to") + sw + "×" + sh + tr("import.faster_and_more_reliable_with")
                + tr("import.recommended_for_available_memory_about")
                + String.format(java.util.Locale.US, "%.1f", capPixels / 1e6) + " MP"
                + (overscanOn ? tr("import.includes_space_for_the_film") : "") + ".";
        showActionSheet(tr("import.choose_import_size"), budgetMessage, new String[]{
                tr("import.full_size") + w + "×" + h, tr("import.resize_to_2") + sw + "×" + sh, tr("editor.cancel")
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
        final String message = tr("import.raw_size") + w + "×" + h + " (" + megapixels(w, h) + " MP)."
                + tr("import.full_size_needs_about") + String.format(java.util.Locale.US, "%.1f", needBytes / 1e9)
                + tr("import.gb_for_decoding_and_rendering")
                + String.format(java.util.Locale.US, "%.1f", availBytes / 1e9) + " GB. "
                + tr("import.the_app_may_close_if") + seconds + tr("import.s")
                + tr("import.resize_to_3") + sw + "×" + sh + tr("import.faster_and_more_reliable_with")
                + tr("import.recommended_for_available_memory_about")
                + String.format(java.util.Locale.US, "%.1f", capPixels / 1e6) + " MP"
                + (overscanOn ? tr("import.includes_space_for_the_film") : "") + ".";
        showActionSheet(tr("import.choose_import_size"), message, new String[]{
                tr("import.full_size") + w + "×" + h, tr("import.resize_to_2") + sw + "×" + sh, tr("editor.cancel")
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
        return " · " + megapixels(w, h) + tr("import.mp_recommended")
                + String.format(java.util.Locale.US, "%.1f", suggest / 1e6) + tr("import.mp_rendering_will_be_slower");
    }

    private static String megapixels(int w, int h) {
        return String.format(java.util.Locale.US, "%.1f", (double) w * (double) h / 1e6);
    }

    /** The file's own size, from the header, before anything is decoded. */
    private int[] sniffImageSize(Uri uri) {
        try {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream head = openUri(uri)) {
                if (head != null) android.graphics.BitmapFactory.decodeStream(head, null, bounds);
            }
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                return new int[]{bounds.outWidth, bounds.outHeight};
            }
        } catch (Throwable t) {
            try { DebugSink.text("decode", "sniff " + t); } catch (Throwable spkIgnored) { Diagnostics.ignored("sniffImageSize:10547", spkIgnored); }
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
                try { DebugSink.text("canary", "threw: " + describe(t)); } catch (Throwable spkIgnored) { Diagnostics.ignored("startCanaryProbe:10584", spkIgnored); }
            }
            final String result = json;
            final long budget = maxImportPixels();
            UiDispatcher.post(() -> {
                try {
                    if (result != null) probe.applyCanary(result);
                    DebugSink.text("canary", (result == null ? "(no answer)" : result)
                            + "\n" + probe.describe());
                } catch (Throwable spkIgnored) { Diagnostics.ignored("maxImportPixels:10593", spkIgnored); }
                try { DeviceReport.submit(this, probe, budget); } catch (Throwable spkIgnored) { Diagnostics.ignored("maxImportPixels:10594", spkIgnored); }
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
                catch (Throwable spkIgnored) { Diagnostics.ignored("probeNow:10607", spkIgnored); }
            }
        }
        if (!probe.valid && engine != null) {
            try {
                final String json = engine.deviceProfile();
                probe = DeviceProbe.parse(json);
                rememberDevice();
                try { DebugSink.text("device-profile", "re-read " + probe.describe() + "\n" + json); }
                catch (Throwable spkIgnored) { Diagnostics.ignored("deviceProfile:10616", spkIgnored); }
            } catch (Throwable t) {
                try { DebugSink.text("device-profile", "re-read failed: " + describe(t)); }
                catch (Throwable spkIgnored) { Diagnostics.ignored("deviceProfile:10619", spkIgnored); }
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
        } catch (Throwable spkIgnored) { Diagnostics.ignored("setDebugStorageLimitMib:10643", spkIgnored); }
        try { DebugSink.text("debug-cap", "simulated storage range = " + mib + " MiB (0 = off)"); }
        catch (Throwable spkIgnored) { Diagnostics.ignored("setDebugStorageLimitMib:10645", spkIgnored); }
    }

    private int debugBandCap() {
        try {
            return getSharedPreferences(DEBUG_PREFS, MODE_PRIVATE).getInt(DEBUG_BAND_CAP, 0);
        } catch (Throwable ignored) { return 0; }
    }

    private void setDebugBandCap(int elements) {
        try {
            getSharedPreferences(DEBUG_PREFS, MODE_PRIVATE).edit().putInt(DEBUG_BAND_CAP, elements).apply();
        } catch (Throwable spkIgnored) { Diagnostics.ignored("setDebugBandCap:10657", spkIgnored); }
        try { DebugSink.text("debug-cap", "band element cap = " + elements + " (0 = engine default 2^21)"); }
        catch (Throwable spkIgnored) { Diagnostics.ignored("setDebugBandCap:10659", spkIgnored); }
    }

    private void setDebugPixelCapMp(int mp) {
        try {
            getSharedPreferences(DEBUG_PREFS, MODE_PRIVATE).edit().putInt(DEBUG_CAP_MP, mp).apply();
        } catch (Throwable spkIgnored) { Diagnostics.ignored("setDebugPixelCapMp:10665", spkIgnored); }
        try { DebugSink.text("debug-cap", "pixel ceiling override = " + mp + " MP (0 = off)"); }
        catch (Throwable spkIgnored) { Diagnostics.ignored("setDebugPixelCapMp:10667", spkIgnored); }
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
        final int[] bands = {0, 16777216, 100000000};
        final int currentCap = debugPixelCapMp();
        final int currentSim = debugStorageLimitMib();
        final int currentBand = debugBandCap();
        final java.util.List<String> labels = new java.util.ArrayList<>();
        for (int v : caps)
            labels.add((v == currentCap ? "· " : "") + (v == 0 ? tr("import.pixel_cap_automatic") : tr("import.pixel_cap") + v + " MP"));
        for (int v : sims)
            labels.add((v == currentSim ? "· " : "") + (v == 0 ? tr("import.simulated_driver_buffer_limit_off") : tr("import.simulated_driver_buffer_limit") + v + " MiB"));
        for (int v : bands)
            labels.add((v == currentBand ? "· " : "") + (v == 0 ? tr("import.band_limit_engine_default_2m")
                    : (v >= 100000000 ? tr("import.band_limit_off_one_band") : tr("import.band_limit") + (v / 1000000) + tr("import.m_elements"))));
        final long memoryCap = DeviceProbe.maxPixelsByMemory(availableMemoryBytes());
        final String note = tr("import.the_pixel_cap_only_affects")
                + String.format(java.util.Locale.US, "%.0f", memoryCap / 1e6)
                + tr("import.mp_and_the_hard_cap")
                + tr("import.restart_the_app_to_apply");
        showActionSheet(tr("settings.debug_pixel_cap_and_buffer"), note, labels.toArray(new String[0]), which -> {
            if (which < caps.length) setDebugPixelCapMp(caps[which]);
            else if (which < caps.length + sims.length) setDebugStorageLimitMib(sims[which - caps.length]);
            else setDebugBandCap(bands[which - caps.length - sims.length]);
            showAbout();
        });
    }

    private void showAbout() {
        probeNow();
        final String version = UpdateCheck.versionName(this) + " (" + UpdateCheck.versionCode(this) + ")";
        final int debugMp = debugPixelCapMp();
        final int debugSim = debugStorageLimitMib();
        final int debugBand = debugBandCap();
        final String debugLine = (debugMp == 0 && debugSim == 0 && appliedSimMib == 0 && debugBand == 0) ? ""
                : tr("settings.debug_pixel_cap") + (debugMp > 0 ? debugMp + " MP" : tr("settings.automatic"))
                  + tr("settings.effective_limit") + String.format(java.util.Locale.US, "%.1f", maxImportPixels(false) / 1e6) + " MP"
                  + (debugSim > 0 ? tr("settings.simulated_buffer_limit") + debugSim + " MiB" : "")
                  + (debugSim != appliedSimMib ? tr("settings.takes_effect_after_restart") : "")
                  + (debugBand > 0 ? tr("settings.band_limit") + (debugBand >= 100000000 ? tr("settings.off") : (debugBand / 1000000) + "M") : "");
        final String details = probe.describe() + debugLine
                + tr("settings.engine_spektralab_spektrafilm_gpl_3")
                + tr("settings.android_port_github_com_sainttaiyi")
                + tr("settings.diagnostics_consent_note");
        // The report switch lives here rather than in a settings screen: it is
        // one line of one dialog, and it is the only thing this app sends that
        // the user did not ask for. The label carries the state, because the
        // sheet's note cannot scroll and a longer paragraph would be clipped.
        final boolean reporting = DeviceReport.enabled(this);
        final boolean dark = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("dark", false);
        showActionSheet("SpektraLab Android " + version, details, new String[]{
                "Language · " + AppLanguage.current().name,
                tr("settings.check_for_updates"),
                tr("settings.presets_save_or_share_settings"),
                // **Askable, not only post-mortem.** A hang, a wrong picture or a
                // render that "took forever" leaves nothing on disk while the
                // process is alive -- and those are the reports that arrive as a
                // screenshot. One tap copies the same ring a crash would have
                // left behind.
                tr("settings.send_recent_activity_log_to"),
                reporting ? tr("settings.diagnostics_on") : tr("settings.diagnostics_off"),
                dark ? tr("settings.theme_dark_orange_restart_to") : tr("settings.theme_blue_and_white_restart"),
                tr("settings.feedback_groups_wechat_qq"), tr("settings.open_github")
        }, which -> {
            if (which == 0) {
                showLanguagePicker();
            } else if (which == 1) {
                UpdateCheck.check(this, true);
            } else if (which == 2) {
                showPresetsPanel();
            } else if (which == 3) {
                // **The only upload the user starts by hand**, and the only way
                // the ring leaves the phone when nothing has gone wrong yet.
                final String ring = CrashTrail.snapshot(this);
                final String head = "user-report\n" + reportHead()
                        + "steps=" + DebugSink.ringSize() + "\n";
                new Thread(() -> {
                    final boolean ok = DebugSink.report("steps", head,
                            ring.isEmpty() ? tr("settings.no_activity_logged_yet") : ring);
                    UiDispatcher.post(() -> showTextDialog(
                            ok ? tr("settings.recent_activity_uploaded") : tr("settings.upload_failed"),
                            tr("settings.report_id") + DebugSink.REF + "\n\n"
                            + (ring.isEmpty() ? tr("settings.no_activity_logged_yet") : ring)
                            + (ok ? tr("settings.upload_complete_no_need_to")
                                  : tr("settings.the_network_was_unavailable_copy")),
                            false));
                }, "SpektraLab-report").start();
            } else if (which == 4) {
                DeviceReport.setEnabled(this, !reporting);
                showAbout();
            } else if (which == 5) {
                // The palette is read once in onCreate; a restart is the whole
                // rebuild, and the current session's state is already saved on
                // pause, so nothing the user did is lost by asking for it.
                appPreferences.putBoolean("dark", !dark);
                // **Never `null` here.** `showSheetBody` calls
                // `onPick.accept(index)` on every row, so a null listener turned
                // each tap on 「好」 into a NullPointerException -- a hundred
                // sessions in a week.
                showActionSheet(tr("settings.theme_changed"), tr("settings.close_and_reopen_the_app"),
                        new String[]{tr("settings.ok")}, done -> { });
            } else if (which == 6) {
                showFeedbackGroup(false);
            } else if (which == 7) {
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/SAINTTaiYi/SpektraLab-Android/releases/latest")));
                } catch (Throwable spkIgnored) { Diagnostics.ignored("report:10787", spkIgnored); }
            }
        }, this::showPixelCapSheet);
    }

    private void showLanguagePicker() {
        final List<AppLanguage.Language> languages = AppLanguage.available();
        final int selected = languages.indexOf(AppLanguage.current());
        final List<String> names = new ArrayList<>();
        for (AppLanguage.Language language : languages) names.add(language.name);
        final List<String> notes = new ArrayList<>();
        for (AppLanguage.Language language : languages) {
            notes.add(java.util.Collections.frequency(names, language.name) > 1 ? language.tag : null);
        }
        showIndexedChoiceSheet(tr("settings.app_language"), names, notes, selected, null, which -> {
            if (which == selected) return;
            if (rendering || batchExportRunning
                    || (exportProgressBar != null && exportProgressBar.getVisibility() == View.VISIBLE)) {
                showInAppToast(tr("settings.wait_for_the_current_render"));
                return;
            }
            // onPause saves the current frame edits before the new activity opens.
            AppLanguage.select(this, languages.get(which));
            recreate();
        });
    }

    /** Reads assets/resources/stock_catalog.json (generated from the engine's profiles). */
    private void loadStockCatalog() {
        final app.spektralab.domain.StockCatalog catalog = stockCatalogRepository.load(getAssets());
        filmTargetPrints.clear();
        filmIds.clear();
        filmLabels.clear();
        filmTypes.clear();
        printIds.clear();
        printLabels.clear();
        for (int i = 0; i < catalog.filmIds.size(); ++i) {
            filmIds.add(catalog.filmIds.get(i));
            filmLabels.add(decorateStockLabel(catalog.filmIds.get(i),
                    catalog.filmLabels.get(i),
                    catalog.filmTypes.get(i),
                    catalog.filmUses.get(i)));
            filmTypes.add(catalog.filmTypes.get(i));
        }
        for (int i = 0; i < catalog.printIds.size(); ++i) {
            printIds.add(catalog.printIds.get(i));
            printLabels.add(decorateStockLabel(catalog.printIds.get(i),
                    catalog.printLabels.get(i),
                    "", catalog.printUses.get(i)));
        }
        filmTargetPrints.putAll(catalog.filmTargetPrints);
        rebuildBrandRow();
        rebuildFilmCards();
        refreshPaperLabel();
    }

    /** Translation stays in the UI layer; the repository only parses data. */
    private String decorateStockLabel(String id, String name, String type, String use) {
        final StringBuilder label = new StringBuilder(name);
        if ("positive".equals(type)) label.append(tr("film.slide"));
        else if (isMonochromeStock(id)) label.append(tr("film.b_w"));
        else if ("negative".equals(type)) label.append(tr("import.negative"));
        if ("cine".equals(use)) label.append(tr("import.cinema"));
        return label.toString();
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
            showInAppToast(tr("import.no_system_photo_picker_found"));
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
                catch (Throwable spkIgnored) { Diagnostics.ignored("launchFirstResolvable:10931", spkIgnored); }
                return true;
            } catch (android.content.ActivityNotFoundException missing) {
                // This ROM has no handler for this action; try the next one.
                Probe.log("picker", "missing " + intent.getAction());
                try { DebugSink.text("picker", "unresolvable " + intent.getAction()); }
                catch (Throwable spkIgnored) { Diagnostics.ignored("launchFirstResolvable:10937", spkIgnored); }
            } catch (Throwable t) {
                // A provider that vanished between the check and the launch, a
                // SecurityException, or a picker that throws from its own
                // onCreate. Next one.
                try { DebugSink.text("picker", intent.getAction() + " failed: " + t); }
                catch (Throwable spkIgnored) { Diagnostics.ignored("launchFirstResolvable:10943", spkIgnored); }
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
            showInAppToast(tr("import.no_file_picker_is_available"));
        }
    }

    /** Device-test hooks, set only by device-golden.ps1 through intent extras. */
    private static void readTestExtras(Intent intent) {
        if (intent == null) return;
        testBandRows = intent.getIntExtra("spk_band_rows", 0);
        testDeterministic = intent.getBooleanExtra("spk_deterministic", false);
        testAutoFilm = intent.getBooleanExtra("spk_auto_film", false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readTestExtras(intent);
        handleIncomingIntent(intent);
    }

    /**
     * The app declares an ACTION_VIEW filter for image/* but used to ignore the
     * intent entirely: opening a photograph from a file manager did nothing.
     * `launchMode="singleTop"` means this also has to be handled for an app that
     * is already in the foreground (onNewIntent above).
     */
    private void handleIncomingIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        final Uri uri = intent.getData();
        if (uri == null) return;
        // Consume it: a rotation or a return from another app must not re-import
        // the same photograph a second time.
        setIntent(new Intent());
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
            // importPicked makes its own private copy when the grant cannot persist.
        }
        UiDispatcher.post(() -> {
            if (!uiReady) { pendingIncomingUri = uri; return; }
            importPicked(uri);
        });
    }

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
            try { DebugSink.text("picker", "RESULT_OK with no data"); } catch (Throwable spkIgnored) { Diagnostics.ignored("getUri:11045", spkIgnored); }
            showInAppToast(tr("import.the_picker_did_not_return"));
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
        reopenToOriginal = false;
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

        showInAppToast(tr("import.copying_photos_into_app_storage"));
        new Thread(() -> {
            final String copied = importController.prepare(uri.toString());
            final Uri copy = copied == null ? null : Uri.parse(copied);
            UiDispatcher.post(() -> {
                if (copy == null) {
                    showErrorDialog(tr("import.import_failed"), tr("import.could_not_read_or_copy"));
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
        if (src == null) return null;
        final String copied = photoRepository.persist(src.toString());
        return copied == null ? null : Uri.parse(copied);
    }

    private boolean canReadUri(Uri uri) {
        return photoRepository.canRead(uri == null ? null : uri.toString());
    }

    /**
     * Open either kind of URI this app handles: a picker's content:// grant or
     * a file:// path from "open with" / our own private copies. ContentResolver
     * does not open file:// URIs on every device.
     */
    private InputStream openUri(Uri uri) throws java.io.FileNotFoundException {
        if (uri == null) return null;
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return new java.io.FileInputStream(uri.getPath());
        }
        return getContentResolver().openInputStream(uri);
    }

    private void loadImage(Uri uri) {
        CrashTrail.mark(this, "import:load " + uri);
        // Whatever is rendering belongs to the photograph being replaced.
        renderEpoch++;
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
                    + " native=" + importAtNativeSize); } catch (Throwable spkIgnored) { Diagnostics.ignored("sniffImageSize:11191", spkIgnored); }
        } catch (Throwable spkIgnored) { Diagnostics.ignored("sniffImageSize:11192", spkIgnored); }
        // A new photograph starts at the two entry cards, never on a page
        // whose switch belonged to the frame before it.
        CrashTrail.mark(this, "import:page");
        if (edgePageIndex >= 0) openEdgePage(-1);
        // A frame the strip remembers but the system will not open any more:
        // say so in one sentence instead of letting a SecurityException be
        // classified as [E-9000] 未归类.
        if (!canReadUri(uri)) {
            // **A revoked grant is permanent, so the chip goes with it.**
            // Keeping it meant this dialog on every launch -- sixty-six of them
            // in a week, the largest single error in the database -- and the
            // only way out was 「关闭」, which changed nothing. Removed once,
            // here, and the strip is honest afterwards.
            final int dead = currentFrame;
            showErrorDialog(tr("import.photo_access_expired"),
                    tr("import.android_revoked_temporary_access_to")
                    + tr("import.it_was_removed_from_the")
                    + tr("import.the_app_will_keep_its"));
            if (dead >= 0 && dead < frames.size() && frames.get(dead).uri.equals(uri)) {
                removeFrame(dead);
            }
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
        if (testDeterministic) {
            // Grain and the paper veil are seeded per frame; switch them off so
            // two renders of the same picture are byte-comparable.
            grainOn = 0f;
            glareActive = 0f;
        }
        if (testAutoFilm && filmStock == null) {
            for (String id : filmIds) {
                if (!"original".equals(id)) { filmStock = id; break; }
            }
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
                    showInAppToast(tr("import.e_1107_unsupported_format") + ext + tr("import.suffix"));
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
            try { if (engine != null) engine.trimPool(); } catch (Throwable spkIgnored) { Diagnostics.ignored("maxImportPixels:11315", spkIgnored); }
            lastRenderedBitmap = null;
            refreshPanelLocks();
            // The plate on the canvas belongs to the frame we just left.
            clearBusyPlate();
            dateText = null;
            setStage(0, null);
            // The white-balance half is RAW-only; tell it which file this is.
            refreshViewPanel();
            sourceBitmap = null;
            // `allocDirect` has no cleaner: dropping the field frees nothing.
            if (sourcePixels != null) { SpektraLabEngine.freeDirect(sourcePixels); sourcePixels = null; }
            // The previous file's unpacked plane is not this frame's problem.
            try { SpektraLabEngine.clearRawCache(); } catch (Throwable spkIgnored) { Diagnostics.ignored("maxImportPixels:11328", spkIgnored); }
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
                showActionSheet(tr("import.e_1107_this_device_cannot"),
                        tr("import.it_may_be_heic_heif")
                                + tr("import.try_the_file_browser_if"),
                        new String[]{tr("import.file_browser"), tr("editor.cancel")}, which -> {
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
            // The downscale decision is pure maths now (ImportPlanner), which
            // means it has JVM regression tests. "Force original" skips it.
            final long availBytes = availableMemoryBytes();
            final app.spektralab.domain.ImportPlanner.Plan plan = nativeSize
                    ? new app.spektralab.domain.ImportPlanner.Plan(
                            1.0, sourceBitmap.getWidth(), sourceBitmap.getHeight())
                    : app.spektralab.domain.ImportPlanner.plan(
                            sourceBitmap.getWidth(), sourceBitmap.getHeight(), capPixels, edgeCap);
            final double k = plan.scale;
            if (k < 1.0) {
                final int nw = plan.width;
                final int nh = plan.height;
                Bitmap scaled = Bitmap.createScaledBitmap(sourceBitmap, nw, nh, true);
                if (scaled != sourceBitmap) sourceBitmap.recycle();
                sourceBitmap = scaled;
                try { DebugSink.text("import-scale", "k=" + k + " -> " + nw + "x" + nh
                        + " avail=" + availBytes); } catch (Throwable spkIgnored) { Diagnostics.ignored("getHeight:11412", spkIgnored); }
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
                    + (availableMemoryBytes() >> 20) + "MB"); } catch (Throwable spkIgnored) { Diagnostics.ignored("getHeight:11436", spkIgnored); }
            // **malloc, not allocateDirect.** The runtime caps direct-buffer
            // memory at the heap growth limit (512 MB on the phone that
            // reported this), and a 50 MP frame is 576 MB of float32, so the
            // old call was refused with 4.3 GB free -- and the message blamed
            // the device. This one asks for memory that exists.
            try {
                sourcePixels = SpektraLabEngine.allocDirect(pixelBytes)
                        .order(ByteOrder.nativeOrder());
            } catch (Throwable oom) {
                throw new IllegalStateException(tr("import.this_image_needs") + (pixelBytes >> 20)
                        + tr("import.mb_of_memory_the_device")
                        + (availableMemoryBytes() >> 20) + " MB)."
                        + tr("import.import_the_photo_again_and"), oom);
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
            // **`createScaledBitmap` hands back the source itself when the
            // target size already matches**, and a bitmap straight out of
            // `BitmapFactory` is immutable, so that is the branch every picture
            // whose long edge is at most 1024 takes. The recycle below then left
            // `compare` holding a dead bitmap: the next draw threw
            // "Canvas: trying to use a recycled bitmap", and because the same
            // picture is re-imported by `restoreSessionState` the app died on
            // every launch until its data was cleared.
            Bitmap preview = Bitmap.createScaledBitmap(sourceBitmap, pw, ph, true);
            if (preview == sourceBitmap) {
                preview = sourceBitmap.copy(Bitmap.Config.ARGB_8888, false);
                if (preview == null) preview = sourceBitmap;
            }
            beforeBitmap = preview;
            compare.setBefore(beforeBitmap);
            if (sourceBitmap != beforeBitmap) sourceBitmap.recycle();
            sourceBitmap = null;
            statusText.setText(tr("import.local_preview"));
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
            final boolean stayOnOriginal = reopenToOriginal;
            reopenToOriginal = false;
            if (filmStock != null && stayOnOriginal) {
                // **Left on 原图, opens on 原图.** The look is still there -- the
                // film card is what the next tap develops -- but re-rendering
                // here is what made a chip jump back onto a film the user had
                // deliberately stepped off.
                showingOriginal = true;
                setOriginalVisual(true);
                updateFilmSelection();
                statusText.setText(tr("film.original_ungraded_raw_preview_choose"));
                if (!batchExportRunning) startPrefetch();
            } else if (filmStock != null) {
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
                statusText.setText(tr("import.not_rendered_choose_a_film") + importSizeNote(sourceWidth, sourceHeight));
                if (!batchExportRunning) startPrefetch();
            }
        } catch (Throwable t) {
            DebugSink.text("failure", AppFailure.of(AppFailure.Stage.IMPORT, t).stage
                    + ": " + describe(t));
            lastImportError = describeImportFailure(t);
            showErrorDialog(tr("import.import_failed"), lastImportError);
            try { DebugSink.text("import-failed", lastImportError); } catch (Throwable spkIgnored) { Diagnostics.ignored("max:11537", spkIgnored); }
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
            SpektraLabEngine.setEnv("SPK_BAND_ROWS",
                    testBandRows > 0 ? String.valueOf(testBandRows) : "512");
            // **The band ceiling, for the seam question only.** A frame wide
            // enough to need 37 bands shows 37 equally spaced lines on one GPU
            // family and none on another; raising this to the device's dispatch
            // wall turns 37 seams into 5. If the lines thin out in proportion,
            // the band boundary is the cause and no picture content is.
            SpektraLabEngine.setEnv("SPK_BAND_CAP",
                    debugBandCap() > 0 ? String.valueOf(debugBandCap()) : "0");
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
            rememberDevice();
            CrashTrail.markNow(this, "engine:probe " + (probe != null && probe.valid));
            try { DebugSink.text("device-profile", probe.describe() + "\n" + engine.deviceProfile()); }
            catch (Throwable spkIgnored) { Diagnostics.ignored("deviceProfile:11629", spkIgnored); }
            // A simulated limit is a claim about a device this one is not, so
            // the canary -- which measures the *real* driver and would raise the
            // range back to 2 GiB -- is not run against it.
            if (simMib == 0) startCanaryProbe();
            try { uploadCapabilities(); } catch (Throwable spkIgnored) { Diagnostics.ignored("deviceProfile:11634", spkIgnored); }
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
        try { ensureEngine(); } catch (Throwable spkIgnored) { Diagnostics.ignored("maxImportPixels:11700", spkIgnored); }
        probeNow();
        long byBinding = Long.MAX_VALUE;
        try {
            final int byNative = engine == null ? 0 : engine.maxFramePixels();
            if (byNative > 0) byBinding = byNative;
        } catch (Throwable spkIgnored) { Diagnostics.ignored("maxFramePixels:11706", spkIgnored); }
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
        // **`g_result` is one process-wide buffer, shared by every entry point
        // that fills it and every one that copies out of it.**
        // `nativeRenderBegin` fills it, `nativeExportSource` overwrites it, and
        // `nativeCopyArgbRange` reads it back -- and this method ran outside
        // `renderMutex`, so a canvas render and this export could interleave.
        // The batch-restore path made it concrete: the render asked for
        // 630x1167, this export replaced the buffer with 800x600, and the copy
        // came back `pixel copy incomplete 480000/735210` -- E-9000 right after
        // "批量导出完成". `synchronized` is re-entrant, so callers that already
        // hold the lock are unaffected.
        synchronized (renderMutex) {
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
                    // Sample a row at a time: a full int[] of a 2048-tier frame
                    // is 16 MB on the heap just to read every 97th pixel.
                    final int w = bitmap.getWidth(), h = bitmap.getHeight();
                    final int[] row = new int[w];
                    long sr = 0, sg = 0, sb = 0;
                    int n = 0, index = 0;
                    for (int y = 0; y < h; ++y) {
                        bitmap.getPixels(row, 0, w, 0, y, w, 1);
                        for (int x = 0; x < w; ++x, ++index) {
                            if (index % 97 != 0) continue;
                            sr += (row[x] >> 16) & 0xFF;
                            sg += (row[x] >> 8) & 0xFF;
                            sb += row[x] & 0xFF;
                            ++n;
                        }
                    }
                    if (n > 0) means = " mean=" + (sr / n) + "/" + (sg / n) + "/" + (sb / n);
                }
                DebugSink.text("raw-view", bitmap == null
                        ? "export: empty ev=" + ev
                        : "export " + bitmap.getWidth() + "x" + bitmap.getHeight()
                          + " ev=" + ev + means);
            } catch (Throwable spkIgnored) { Diagnostics.ignored("getHeight:11848", spkIgnored); }
        } catch (Throwable t) {
            try { DebugSink.text("raw-view", "export failed: " + t); } catch (Throwable spkIgnored) { Diagnostics.ignored("getHeight:11850", spkIgnored); }
        } finally {
            try { SpektraLabEngine.setSourceDisplayEv(0, false); } catch (Throwable spkIgnored) { Diagnostics.ignored("getHeight:11852", spkIgnored); }
            try { SpektraLabEngine.setSourceToneCurve(null); } catch (Throwable spkIgnored) { Diagnostics.ignored("getHeight:11853", spkIgnored); }
        }
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
            UiDispatcher.post(() -> {
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
        // Read once, on the UI thread, before the decode starts: the worker
        // below cannot be allowed to see a flag that a later tap already moved.
        final boolean stayOnOriginal = reopenToOriginal;
        reopenToOriginal = false;
        // **A different import is a different frame.** The cache keys on path +
        // mtime, and every import is copied to `files/import/<same name>`; two
        // copies inside one second share an mtime, so the second import could
        // be handed the first one's decoded pixels. Clearing here costs one
        // unpack and buys the guarantee that the pixels belong to this file.
        try { SpektraLabEngine.clearRawCache(); } catch (Throwable spkIgnored) { Diagnostics.ignored("loadRawImage:11898", spkIgnored); }
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
                showInAppToast(tr("import.could_not_get_the_raw"));
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
                        statusText.setText(tr("import.choose_import_size_2"));
                        showRawBudgetDialog(uri, dims[0], dims[1], cap);
                        return;
                    }
                }
            }

            statusText.setText(tr("import.decoding_raw"));
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
            refreshPanelLocks();
            clearBusyPlate();
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
                    try { if (engine != null) engine.trimPool(); } catch (Throwable spkIgnored) { Diagnostics.ignored("maxImportPixels:11988", spkIgnored); }
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
                    
                    try { DebugSink.text("open-params", "path=" + filePath + "\n" + params); } catch (Throwable spkIgnored) { Diagnostics.ignored("maxImportPixels:12002", spkIgnored); }
                    // The cap comes from the device, not from a constant: a
                    // plane is 12 B/px and a binding may not exceed the GPU's
                    // maxStorageBufferRange. Two limits apply, whichever is
                    // lower -- that range, and the 65 535-workgroup dispatch
                    // limit (16,776,960 px) this driver still loses blocks over.
                    final boolean nativeSize = importAtNativeSize;
                    importAtNativeSize = false;                   // one import only
                    if (nativeSize) {
                        try { DebugSink.text("import-mode", "native size, no cap"); } catch (Throwable spkIgnored) { Diagnostics.ignored("maxImportPixels:12011", spkIgnored); }
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
                    // **Under the same lock `close()` takes.** These two solves
                    // ran outside `renderMutex`, so a close from another path
                    // could release the session between the null check and the
                    // call -- which is exactly the dangling handle that killed
                    // the process in the meter (SL-261110-F858). The session
                    // field is also re-read inside the lock, so a swap that
                    // lands in between simply skips the solve.
                    try {
                        synchronized (renderMutex) {
                            if (session != null) session.solve("exposure");
                        }
                    } catch (Throwable spkIgnored) { Diagnostics.ignored("getDimensions:12069", spkIgnored); }
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
                        catch (Throwable spkIgnored) { Diagnostics.ignored("getDimensions:12089", spkIgnored); }
                        UiDispatcher.post(() -> statusText.setText(tr("import.raw_loaded_but_the_original")));
                    }
                    if (hasPreview) {
                        UiDispatcher.post(() -> {
                            beforeBitmap = preview;
                            compare.setBefore(preview);
                            setStage(1, getFileName(uri));
                            rememberFrame(uri);
                            rebuildFilmCards();
                            setStep(1);
                             statusText.setText(tr("import.raw_preview"));
                        });
                    }

                    // "Process" (配光): without a solved print exposure and
                    // filter pack the print runs on defaults and blows the
                    // highlights. The mac app makes the user press Process;
                    // this build does it once on open.
                    String solvedJson = null;
                    try {
                        synchronized (renderMutex) {
                            solvedJson = session == null ? null : session.solve("both");
                        }
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
                        } catch (Throwable spkIgnored) { Diagnostics.ignored("optJSONObject:12129", spkIgnored); }
                    }
                    if (hasPreview && solvedJson != null) {
                        double solvedEv = 0.0;
                        try {
                            solvedEv = new org.json.JSONObject(solvedJson)
                                    .getJSONObject("exposure_ev_by_method")
                                    .optDouble("balanced", 0.0);
                        } catch (Throwable spkIgnored) { Diagnostics.ignored("optJSONObject:12137", spkIgnored); }
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
                                UiDispatcher.post(() -> {
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
                                        } catch (Throwable spkIgnored) { Diagnostics.ignored("round:12159", spkIgnored); }
                                        rebuildFrameChips();
                                    }
                                    try {
                                        DebugSink.text("raw-view", "display ev=" + shownEv);
                                    } catch (Throwable spkIgnored) { Diagnostics.ignored("round:12164", spkIgnored); }
                                });
                            }
                        }
                    }
                    UiDispatcher.post(() -> {
                        if (session == null) return;
                        showingOriginal = true;
                        lastRenderedBitmap = null;
                        setOriginalVisual(true);
                        rebuildFilmCards();
                        if (filmStock == null) statusText.setText(tr("import.not_rendered_choose_a_film"));
                        // **No render from here.** `sourceWidth/Height` are
                        // still the previous frame's and `refreshEdgeFormats`
                        // has not run, so a render now used the old shape and
                        // the old gate: that is E-1305 the moment the user
                        // switches between a landscape and a portrait frame.
                        // The block after `dims` renders once this frame's own
                        // numbers are in.
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
                        try { if (session != null) session.close(); } catch (Throwable spkIgnored) { Diagnostics.ignored("round:12196", spkIgnored); }
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
                    try { DebugSink.text("raw-dims", "w=" + w + " h=" + h); } catch (Throwable spkIgnored) { Diagnostics.ignored("round:12217", spkIgnored); }

                    // **The session's own size, not `rawSourceSize()`.** That
                    // legacy query answers {0,0} on purpose -- its comment says
                    // callers must ask the session -- and reading it put
                    // "RAW 0×0 · 0.0 MP" on the status bar.
                    final boolean capped = importCapPixels > 0;
                    try {
                        DebugSink.text("raw-cap", (capped ? "capped at " + importCapPixels : "native")
                                + " -> session " + w + "x" + h);
                    } catch (Throwable spkIgnored) { Diagnostics.ignored("round:12227", spkIgnored); }

                    UiDispatcher.post(() -> {
                        refreshEdgeFormats();
                        setStep(1);
                        // **No sheet.** The raw arrives at the size it was
                        // imported at; the suggestion is a line in the status
                        // bar, not a gate.
                        statusText.setText("RAW " + w + "×" + h
                                + " · " + megapixels(w, h) + " MP"
                                + importSizeNote(w, h));
                        refreshViewPanel();
                        // **Render only now.** The frame's real size and the
                        // gates that fit it are both known, so the render the
                        // chip's Look asks for uses this picture's shape.
                        if (filmStock != null && stayOnOriginal) {
                            showingOriginal = true;
                            setOriginalVisual(true);
                            updateFilmSelection();
                            statusText.setText(tr("film.original_ungraded_raw_preview_choose"));
                            if (!batchExportRunning) startPrefetch();
                        } else if (filmStock != null) {
                            showingOriginal = false;
                            updateFilmSelection();
                            if (!batchExportRunning) renderImage();
                        } else if (!batchExportRunning) {
                            startPrefetch();
                        }
                    });
                } catch (Throwable t) {
                    DebugSink.text("failure", AppFailure.of(AppFailure.Stage.IMPORT, t).stage
                            + ": " + describe(t));
                    final String said = describeImportFailure(t);
                    lastImportError = said;
                    // A High-Efficiency NEF is not a preview job. Say what it
                    // is (E-1108, a compression this build has no decoder for)
                    // and what to change, and leave the file alone.
                    UiDispatcher.post(() -> {
                        statusText.setText(tr("import.raw_load_failed") + SpkError.shortForm(said));
                        showErrorDialog(tr("import.raw_load_failed_2"), said);
                    });
                }
            }).start();
        } catch (Throwable t) {
            DebugSink.text("failure", AppFailure.of(AppFailure.Stage.IMPORT, t).stage
                    + ": " + describe(t));
            lastImportError = describeImportFailure(t);
            showErrorDialog(tr("import.raw_load_failed_2"), lastImportError);
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
            catch (Throwable spkIgnored) { Diagnostics.ignored("showTextDialog:12304", spkIgnored); }
            return;
        }
        final String shownTitle = (title == null || title.isEmpty()) ? tr("import.error") : title;
        final String shownBody = (body == null || body.isEmpty()) ? tr("import.e_0000_unknown_error") : body;
        // **One row per failure.** The ring is already in memory; sending it
        // here is what makes an error report readable without the user having
        // to copy anything, and it costs one write instead of sixty-four.
        if (report) reportFailure(shownTitle, shownBody);
        UiDispatcher.post(() -> {
            final android.app.Dialog dialog = newDialog();
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(UiPalette.PAPER);
            View cap = new View(this);
            cap.setBackgroundColor(UiPalette.BLUE);
            card.addView(cap, new LinearLayout.LayoutParams(-1, dp(3)));

            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(android.view.Gravity.CENTER_VERTICAL);
            head.setPadding(dp(16), 0, dp(16), 0);
            TextView kicker = mixedLabel(tr("import.error_details"), 8, UiPalette.BLUE);
            kicker.setLetterSpacing(0.08f);
            head.addView(kicker, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView close = label(tr("crash.close"), 9, UiPalette.MUTED, tfHan);
            close.setPaintFlags(close.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            close.setPadding(dp(12), dp(14), 0, dp(14));
            close.setOnClickListener(v -> dialog.dismiss());
            head.addView(close);
            card.addView(head, new LinearLayout.LayoutParams(-1, dp(42)));

            ScrollView scroll = new ScrollView(this);
            LinearLayout bodyBox = new LinearLayout(this);
            bodyBox.setOrientation(LinearLayout.VERTICAL);
            bodyBox.setPadding(dp(16), 0, dp(16), 4);
            TextView titleView = label(shownTitle, 18, UiPalette.INK, tfHan);
            titleView.setLetterSpacing(-0.02f);
            bodyBox.addView(titleView);
            TextView advice = label(tr("import.copy_the_full_details_below") + DebugSink.REF, 10, UiPalette.MUTED, tfHan);
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
            TextView closeAction = label(tr("crash.close"), 10, UiPalette.BLUE, tfHan);
            closeAction.setGravity(android.view.Gravity.CENTER);
            android.graphics.drawable.GradientDrawable closeBg = new android.graphics.drawable.GradientDrawable();
            closeBg.setColor(UiPalette.PAPER);
            closeBg.setStroke(dp(1), UiPalette.BLUE);
            closeAction.setBackground(closeBg);
            closeAction.setOnClickListener(v -> dialog.dismiss());
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            closeLp.rightMargin = dp(8);
            actions.addView(closeAction, closeLp);
            TextView copy = label(tr("import.copy_error_details"), 10, UiPalette.PAPER, tfHan);
            copy.setGravity(android.view.Gravity.CENTER);
            android.graphics.drawable.GradientDrawable copyBg = new android.graphics.drawable.GradientDrawable();
            copyBg.setColor(UiPalette.BLUE);
            copy.setBackground(copyBg);
            copy.setOnClickListener(v -> {
                try {
                    // **The ref goes first.** It is the only line that leads
                    // back to the D1 rows; a screenshot of the dialog is not
                    // always possible, and a pasted body without it is a
                    // report with no address.
                    final String text = tr("settings.report_id") + DebugSink.REF
                            + "\n\n" + shownTitle + "\n\n" + shownBody
                            + "\n\nbuild=SpektraLab Android " + UpdateCheck.versionName(this)
                            + "\n" + probe.describe();
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("SpektraLab", text));
                    copy.setText(tr("feedback.copied"));
                } catch (Throwable t) {
                    copy.setText(tr("import.could_not_copy_press_and"));
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
                } catch (Throwable spkIgnored) { Diagnostics.ignored("getFilePathFromUri:12471", spkIgnored); }
                try (InputStream in = openUri(uri);
                     java.io.OutputStream out = new java.io.FileOutputStream(tempFile)) {
                    byte[] buf = new byte[64 * 1024];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                }
                if (expected > 0 && tempFile.length() != expected) {
                    try { DebugSink.text("import", "copy short: " + tempFile.length()
                            + " of " + expected + " for " + fileName); } catch (Throwable spkIgnored) { Diagnostics.ignored("getFilePathFromUri:12480", spkIgnored); }
                    tempFile.delete();
                    return null;
                }
                try { DebugSink.text("import", "copied " + tempFile.length() + " -> "
                        + tempFile.getAbsolutePath()); } catch (Throwable spkIgnored) { Diagnostics.ignored("getFilePathFromUri:12485", spkIgnored); }
                return tempFile.getAbsolutePath();
            } catch (Exception e) {
                try { DebugSink.text("import", "copy failed: " + e); } catch (Throwable spkIgnored) { Diagnostics.ignored("getFilePathFromUri:12488", spkIgnored); }
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
            try { DebugSink.text("exif", "path: " + t); } catch (Throwable spkIgnored) { Diagnostics.ignored("orientationOfPath:12514", spkIgnored); }
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
            if (file != null) try { file.close(); } catch (Throwable spkIgnored) { Diagnostics.ignored("shortAt:12553", spkIgnored); }
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
        try (InputStream in = openUri(uri)) {
            if (in != null) {
                return new android.media.ExifInterface(in).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Throwable spkIgnored) { Diagnostics.ignored("readOrientation:12579", spkIgnored); }
        try (android.os.ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r")) {
            if (fd != null) {
                return new android.media.ExifInterface(fd.getFileDescriptor()).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Throwable spkIgnored) { Diagnostics.ignored("readOrientation:12586", spkIgnored); }
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
            try (InputStream head = openUri(uri)) {
                if (head != null) BitmapFactory.decodeStream(head, null, bounds);
            } catch (Throwable t) {
                try { DebugSink.text("decode", "sniff " + t); } catch (Throwable spkIgnored) { Diagnostics.ignored("decodeImport:12608", spkIgnored); }
            }
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                int sample = 1;
                while ((long) (bounds.outWidth / (sample * 2)) * (bounds.outHeight / (sample * 2)) >= capPixels
                        || Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edgeCap) sample *= 2;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                try (InputStream stream = openUri(uri)) {
                    Bitmap decoded = BitmapFactory.decodeStream(stream, null, opts);
                    if (decoded != null) return decoded;
                    try { DebugSink.text("decode", "null at sample " + sample); }
                    catch (Throwable spkIgnored) { Diagnostics.ignored("decodeImport:12620", spkIgnored); }
                } catch (Throwable t) {
                    try { DebugSink.text("decode", "stream " + t); } catch (Throwable spkIgnored) { Diagnostics.ignored("decodeImport:12622", spkIgnored); }
                }
            } else {
                try { DebugSink.text("decode", "no bounds " + bounds.outWidth + "x" + bounds.outHeight); }
                catch (Throwable spkIgnored) { Diagnostics.ignored("decodeImport:12626", spkIgnored); }
            }
        } catch (Throwable t) {
            try { DebugSink.text("decode", "outer " + t); } catch (Throwable spkIgnored) { Diagnostics.ignored("decodeImport:12629", spkIgnored); }
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
                    + decoded.getHeight()); } catch (Throwable spkIgnored) { Diagnostics.ignored("max:12661", spkIgnored); }
            return decoded;
        } catch (Throwable t) {
            try { DebugSink.text("decode", "imagedecoder " + t); } catch (Throwable spkIgnored) { Diagnostics.ignored("max:12664", spkIgnored); }
            return null;
        }
    }

    /**
     * A failure the user can act on. `OutOfMemoryError` is an `Error`, not an
     * `Exception`, which is why the `catch (Exception)` this replaces let the
     * process die on the Honor report instead of saying 内存不足.
     */
    private String describeImportFailure(Throwable t) {
        try { DebugSink.text("import-error", String.valueOf(t)); } catch (Throwable spkIgnored) { Diagnostics.ignored("describeImportFailure:12675", spkIgnored); }
        if (t instanceof OutOfMemoryError) {
            // **Never tell the user to bring a smaller picture.** That turns our
            // own allocation into their composition problem, and the frame that
            // failed here is usually one the engine can render perfectly -- the
            // 576 MB import buffer was refused by a ledger, not by the device.
            // What is worth saying is which way out exists and that the file
            // itself is wanted if it keeps happening.
            return tr("render.e_1003_not_enough_memory")
                    + tr("render.if_it_keeps_happening_send");
        }
        return SpkError.format(describe(t));
    }

    private Bitmap applyExifRotation(Uri uri, Bitmap bitmap) {
        final int orientation = readOrientation(uri);
        try { DebugSink.text("exif", "orientation=" + orientation + " bitmap="
                + bitmap.getWidth() + "x" + bitmap.getHeight()); } catch (Throwable spkIgnored) { Diagnostics.ignored("readOrientation:12692", spkIgnored); }
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
            showInAppToast(tr("render.choose_a_film_to_start"));
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
        } catch (Throwable spkIgnored) { Diagnostics.ignored("applyParams:12785", spkIgnored); }
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
    /**
     * Push the geometry group on its own, right before a render.
     *
     * <p>Cheap (one stock rebuild, tens of milliseconds against a render that
     * costs a second) and unconditional, because the alternative -- trusting
     * the delta ledger to notice -- is what produced "一松手，重新渲染完，照片
     * 变回原来位置了": the canvas turned, the file did not. Always logs the
     * exact JSON, so the next report can say what left the app rather than
     * what should have.
     */
    private void sendGeometry() {
        if (session == null || filmStock == null) return;
        // **The snapshot, not the live fields.** See `geoSnapRot`: reading the
        // live ones here is what let a UI-thread write land between the commit
        // and this line and silently turn the picture back upright.
        final float rot = geoSnapValid ? geoSnapRot : geometryRotation;
        final int turns = geoSnapValid ? geoSnapTurns : geometryTurns;
        final boolean fh = geoSnapValid ? geoSnapFlipH : geometryFlipH;
        final boolean fv = geoSnapValid ? geoSnapFlipV : geometryFlipV;
        final float cropW = geoSnapValid ? geoSnapW : Math.max(0.01f, Math.min(1f, geometryCropW));
        final float cropH = geoSnapValid ? geoSnapH : Math.max(0.01f, Math.min(1f, geometryCropH));
        final float cropX = geoSnapValid ? geoSnapX : Math.max(0f, Math.min(1f - cropW, geometryCropX));
        final float cropY = geoSnapValid ? geoSnapY : Math.max(0f, Math.min(1f - cropH, geometryCropY));
        final String d = "{\"film_stock\":" + q(filmStock)
                + ",\"geometry_rotation_deg\":" + f("%.3f", -rot)
                + ",\"geometry_quarter_turns\":" + turns
                + ",\"geometry_flip_h\":" + b(fh)
                + ",\"geometry_flip_v\":" + b(fv)
                + ",\"geometry_crop_x\":" + f("%.6f", cropX)
                + ",\"geometry_crop_y\":" + f("%.6f", cropY)
                + ",\"geometry_crop_w\":" + f("%.6f", cropW)
                + ",\"geometry_crop_h\":" + f("%.6f", cropH) + "}";
        try {
            session.setParams(d);
            // The ledger no longer describes the session: the next full delta
            // has to restate everything, or the fields this call did not name
            // would be remembered as sent.
            deltaSession = null;
            deltaSent.clear();
            DebugSink.text("geo", "sent " + d);
            // **What the engine actually holds**, not what was sent. If these
            // two ever disagree the bug is inside `spk_set_params`; if they
            // agree and the canvas is still wrong, the bug is in the render.
            try {
                final String gp = session.getParams();
                final int at = gp.indexOf("\"geometry_rotation_deg\"");
                DebugSink.text("geo-check", at >= 0
                        ? gp.substring(at, Math.min(gp.length(), at + 44)) : "absent");
            } catch (Throwable spkIgnored) { Diagnostics.ignored("indexOf:12853", spkIgnored); }
        } catch (Throwable t) {
            // Put it back: a geometry edit that did not reach the engine must
            // be retried by the next render, not forgotten.
            pendingGeometry = true;
            try { DebugSink.textNow("geo", "FAILED " + t + " :: " + d); } catch (Throwable spkIgnored) { Diagnostics.ignored("indexOf:12858", spkIgnored); }
        }
    }

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
            showInAppToast(tr("render.restored_the_initial_edits"));
            return;
        }
        undoStack.pop();
        final String previous = undoStack.peek();
        if (previous == null) return;
        try {
            applyLook(lookFromJson(new org.json.JSONObject(previous)));
        } catch (Throwable spkIgnored) { Diagnostics.ignored("peek:12931", spkIgnored); }
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
    private static String q(String v) { return JsonQuote.quote(v); }

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
        return JsonFields.snippet(progressJson, "auto_exposure_ev", 40);
    }

    /**
     * Records the EV a render applied, from the progress document read **right
     * after** that render.
     */
    private void rememberAppliedEv(String progressJson, String tier) {
        final String text = appliedEvText(progressJson);
        final double applied = JsonFields.firstNumber(progressJson, "auto_exposure_ev");
        if (!Double.isNaN(applied)) {
            lastAppliedEv = applied;
            lastAppliedEvValid = true;
            lastAppliedEvUri = frameUri(currentFrame);
        }
        // One line per *change*, not per frame: the live loop draws at 11 Hz and
        // a steady EV is the normal case.
        if (lastAppliedEv != loggedEv) {
            loggedEv = lastAppliedEv;
            try {
                DebugSink.text("exposure", "tier=" + tier + " " + text
                        + " -> pinned " + lastAppliedEv + (lastAppliedEvValid ? "" : " (none)"));
            } catch (Throwable spkIgnored) { Diagnostics.ignored("substring:13067", spkIgnored); }
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
        // **Whether this stock is a slide, decided once.** Two switches below
        // are refused by the engine when it is: "black/white correction on a
        // directly scanned positive film is not implemented by the native
        // engine" -- seven sessions in a week, because the two sliders are on
        // the same page as everything else and nothing stops them being dragged
        // while a reversal film is loaded.
        final boolean scannedPositive = isPositiveStock(stock);
        kv.put("scan_film", b(scannedPositive));
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
        kv.put("scanner_white_correction", b(scannerWhite > 0.5f && !scannedPositive));
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
        kv.put("scanner_black_correction", b(scannerBlack > 0.5f && !scannedPositive));
        // **Ilford Multigrade has no calibrated EDR tone map.** Sending
        // `extended_dynamic_range` with that paper makes `spk_open` fail
        // outright -- "print profile 'ilford_multigrade_iv_rc' has no
        // calibrated EDR tone map" -- and the frame never opens at all. The
        // switch stays where the user put it; it simply cannot apply here.
        kv.put("extended_dynamic_range",
                b(edrOn > 0.5f && !"ilford_multigrade_iv_rc".equals(paper)));
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
        // **The engine turns the other way round.** `geometry_rotation_deg`
        // is counter-clockwise positive (measured: rendering +15 and undoing
        // it with PIL's +15 matches to 11.7/255, while -15 misses by 42.7),
        // and Android's `Canvas.rotate` is clockwise positive. The app keeps
        // the screen convention everywhere -- the gesture, the scale, the
        // reading, `Look` -- and flips the sign once, here and in
        // `sendGeometry`, at the boundary. Without it the picture turns one
        // way under the finger and comes back the other way when the render
        // lands, which is exactly "一松手，重新渲染完，照片变回原来位置了".
        kv.put("geometry_rotation_deg", f("%.3f", -geometryRotation));
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
                    + " keys=" + delta.split(",").length + " [" + keyNames(delta) + "]"
                    + " geo=" + kv.get("geometry_rotation_deg"));
        } catch (Throwable spkIgnored) { Diagnostics.ignored("deltaFor:13255", spkIgnored); }
        // **A JSON that does not parse is a build error, not a user error.**
        try {
            new org.json.JSONObject(delta);
        } catch (Throwable bad) {
            try { DebugSink.textNow("params-bad", bad + "\n" + delta); }
            catch (Throwable spkIgnored) { Diagnostics.ignored("deltaFor:13261", spkIgnored); }
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
                    try { stage = s == null ? null : s.stageSnapshot(); } catch (Throwable spkIgnored) { Diagnostics.ignored("Thread:13353", spkIgnored); }
                    CrashTrail.mark(this, tag + ":tick " + n + "s"
                            + (stage == null || stage.isEmpty() ? "" : " @ " + stage));
                }
            } catch (InterruptedException ignored) {
            } catch (Throwable spkIgnored) { Diagnostics.ignored("Thread:13358", spkIgnored); }
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
        return PixelHash.fnv1a(pixels, size, 7);
    }

    /** One line a second is enough to tell "blocked" from "never called". */
    private long lastLiveLog = 0;
    private void liveLog(String what) {
        final long now = System.currentTimeMillis();
        if (now - lastLiveLog < 1000) return;
        lastLiveLog = now;
        try { DebugSink.text("live", what); } catch (Throwable spkIgnored) { Diagnostics.ignored("currentTimeMillis:13411", spkIgnored); }
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
        int[] liveBins = null;
        synchronized (renderMutex) {
            // `renderImage` may have taken the session while this frame was
            // waiting for the lock.
            if (s != session || fullGen != fullGeneration.get()) return;
            try {
                // Capture the look for this frame, then let the domain use case
                // apply it and start the reprint. No mutable Activity field is
                // read after this point.
                final app.spektralab.domain.EditorState liveState =
                        new app.spektralab.domain.EditorState(
                                stock, paper, meterMethod, frame, showingOriginal,
                                rendering, geometrySeq);
                // **Reprint, not render.** The delta above only names what
                // moved, so a print- or view-layer change never touches the
                // cached negative and this skips the whole film side: 32 ms
                // against 200 ms at this size. A shoot-layer change drops that
                // cache inside `spk_set_params` itself, so the fallback is a
                // full develop -- correct, just slow, and only on the sliders
                // that genuinely need it (grain, halation, geometry, rebate).
                final long liveStart = System.currentTimeMillis();
                final int[] dims = renderController.begin(
                        s, liveState, "live", LIVE_LONG_EDGE, true,
                        (target, state, edge) -> applyParams(state.filmStock, (int) edge));
                final int w = dims[0], h = dims[1];
                if (w <= 0 || h <= 0) throw new RuntimeException("empty live frame");
                if (livePixels == null || livePixels.length < w * h) livePixels = new int[w * h];
                final int copied = s.copyArgbRange(livePixels, 0, h);
                if (copied != w * h) throw new RuntimeException("live copy " + copied + "/" + (w * h));
                bmp = newFrameBitmap(w, h);
                bmp.setPixels(livePixels, 0, w, 0, 0, w, h);
                s.renderEnd();
                try { rememberAppliedEv(s.progressJson(), "live"); } catch (Throwable spkIgnored) { Diagnostics.ignored("copyArgbRange:13549", spkIgnored); }
                liveHash = pixelHash(livePixels, w * h);
                // **The histograms move with the picture, not with the render
                // size.** Both of them used to wait for a full 2048 render, so
                // a drag left them frozen on the previous frame. Every third
                // live frame is enough to read as continuous and costs a
                // fraction of the frame's own budget (a strided count over an
                // array that already exists, never a Bitmap).
                if (histogram != null && (++liveHistCounter % 3) == 0)
                    liveBins = HistogramView.computeBins(livePixels, w, h);
                try { DebugSink.text("live", "frame " + w + "x" + h + " "
                        + (System.currentTimeMillis() - liveStart) + " ms "
                        + Long.toHexString(liveHash)); }
                catch (Throwable spkIgnored) { Diagnostics.ignored("copyArgbRange:13562", spkIgnored); }
            } catch (Throwable t) {
                try { s.renderEnd(); } catch (Throwable spkIgnored) { Diagnostics.ignored("copyArgbRange:13564", spkIgnored); }
                try { DebugSink.text("live", "fail " + describe(t)); } catch (Throwable spkIgnored) { Diagnostics.ignored("copyArgbRange:13565", spkIgnored); }
                return;
            }
        }
        final Bitmap out = bmp;
        final long hash = liveHash;
        final int[] histBins = liveBins;
        UiDispatcher.post(() -> {
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
            // Only a frame that is actually shown updates the bins; a dropped
            // one describes a picture the canvas never had.
            if (histBins != null && histogram != null) histogram.applyBins(histBins);
            // The other half of the comparison: `frame` says what was produced,
            // this says what the canvas was handed. If they disagree the loss is
            // between the two, not in the render.
            try { DebugSink.text("live", "canvas " + out.getWidth() + "x" + out.getHeight()
                    + " " + Long.toHexString(hash)); } catch (Throwable spkIgnored) { Diagnostics.ignored("copyArgbRange:13599", spkIgnored); }
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
                                + (freed >> 20) + " MB"); } catch (Throwable spkIgnored) { Diagnostics.ignored("toString:13633", spkIgnored); }
            }
        } catch (Throwable spkIgnored) { Diagnostics.ignored("toString:13635", spkIgnored); }
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
            catch (Throwable spkIgnored) { Diagnostics.ignored("renderImage:13656", spkIgnored); }
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
            showErrorDialog(tr("render.no_image_to_render"), lastImportError == null
                    ? tr("render.import_a_photo_first_tap")
                    : tr("render.the_last_import_did_not") + lastImportError);
            return;
        }
        if (filmStock == null) {
            showInAppToast(tr("render.choose_a_film_below"));
            return;
        }
        stopPrefetch();
        if (rendering) {
            // One native result buffer for the whole process: two renders would
            // interleave on it and hand back another frame's dimensions.
            try { DebugSink.text("render", "already rendering"); } catch (Throwable spkIgnored) { Diagnostics.ignored("renderImage:13680", spkIgnored); }
            Probe.log("render", "queued film=" + filmStock + " pending=" + pendingRender);
            statusText.setText(tr("render.rendering"));
            // Not dropped: a tap that lands mid-render is the *last* choice the
            // user made, so it becomes the next render instead of a toast.
            pendingRender = true;
            showInAppToast(tr("render.rendering_will_render_again_with"));
            return;
        }
        scheduleUndo();          // one entry per settled edit
        // UDF: capture one immutable snapshot on the UI thread. The worker
        // below must not read the Activity's mutable look fields any more.
        final app.spektralab.domain.EditorState editorState =
                new app.spektralab.domain.EditorState(
                        filmStock,
                        printStock == null ? "kodak_portra_endura" : printStock,
                        meterMethod, currentFrame, showingOriginal, rendering, geometrySeq);
        final String renderedStock = editorState.filmStock;
        // **The frame this render belongs to.** Deleting the chips (or opening
        // another photo) while a render is in flight used to let its callback
        // call setStage(1) on a canvas that no longer had a frame: the empty
        // state -- and with it the 选择照片 button -- vanished, which is the
        // "删掉全部缩略图后导入按钮有时候会消失" report.
        final int renderedFrame = editorState.frameIndex;
        final int renderedEpoch = renderEpoch;
        // **Which geometry this render is allowed to speak for.** A render the
        // commit did not wait for carries the angle from before it, and letting
        // it take the preview turn off is exactly the frame that jumps back.
        final int renderedGeometrySeq = editorState.geometrySequence;
        rendering = true;
        progressBar.setVisibility(View.VISIBLE);
        busyLabel.setVisibility(View.VISIBLE);
        statusText.setText(tr("render.rendering"));
        try { DebugSink.text("render", "begin " + sourceWidth + "x" + sourceHeight); }
        catch (Throwable spkIgnored) { Diagnostics.ignored("renderImage:13707", spkIgnored); }

        new Thread(() -> {
            try {
                synchronized (renderMutex) {
                if (session == null) {
                    // **A frame change can land between the check the UI thread
                    // made and this lock.** Throwing here turned that race into
                    // a dialog on top of a batch export that had already
                    // finished ("加片边批量导出后弹错 / 会话状态异常"). The frame
                    // is genuinely gone, so there is nothing to draw: hand the
                    // UI back the state this method claimed, and let the next
                    // import render. A rebate export makes every render 2-3 s
                    // slower, which is exactly why that window got wide enough
                    // to hit in the field and not on the bench.
                    CrashTrail.mark(this, "render:session-gone");
                    UiDispatcher.post(() -> {
                        rendering = false;
                        if (progressBar != null) progressBar.setVisibility(View.GONE);
                        if (busyLabel != null) busyLabel.setVisibility(View.GONE);
                    });
                    return;
                }
                // The queue stops here, but a card it started before still
                // finishes and can put a stock back onto this session. Take the
                // film the user picked, under the same lock the render runs
                // under, so the picture and the edge text cannot disagree.
                CrashTrail.markNow(MainActivity.this, "render:lock");
                // **Geometry first, and not through the delta ledger.** A crop
                // or a rotation is the one edit the canvas cannot show while it
                // is being made: the preview turns the *view* and the picture
                // itself stays where it was until the render comes back. So it
                // is the one edit that must not depend on `deltaSent` deciding
                // the value looks unchanged -- and it is sent with the stock,
                // which is what forces the rebuild a SHOOT-layer field needs.
                // **Geometry goes last, not first.** It used to be sent before
                // the full delta, on the theory that the delta would restate
                // the same value. A device report (`SL-261111-A06E`) shows a
                // render that came back with the *cover zoom* of a 12 degree
                // turn and no turn at all: the tree had the angle, the picture
                // did not. Whatever the full delta does to that field, the
                // geometry is now written after it, so the last word before
                // `renderBegin` is this one.
                final boolean geoOwed = pendingGeometry;
                pendingGeometry = false;
                // The use case applies the captured state and then renders;
                // no Activity field is read by the worker after this point.
                Thread ticker = startTick("render", session);
                CrashTrail.markNow(MainActivity.this, "render:call");
                int[] dims;
                try {
                    dims = renderController.begin(
                            session, editorState, "live", longEdge, false,
                            (target, state, edge) -> {
                                applyParams(state.filmStock, (int) edge);
                                if (geoOwed) {
                                    sendGeometry();
                                    try {
                                        final String gp = target.getParams();
                                        final int at = gp.indexOf("\"geometry_rotation_deg\"");
                                        DebugSink.text("geo-check", "after delta " + (at >= 0
                                                ? gp.substring(at, Math.min(gp.length(), at + 30)) : "absent"));
                                    } catch (Throwable spkIgnored) {
                                        Diagnostics.ignored("geo-check", spkIgnored);
                                    }
                                }
                                Probe.log("render", "apply film=" + state.filmStock
                                        + " long=" + edge + " src=" + sourceWidth + "x" + sourceHeight);
                                CrashTrail.markNow(MainActivity.this, "render:params");
                            });
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
                // **The canvas, not just the export.** The reports say the ruler
                // is already visible while editing, and `log_edge_profile` only
                // ever ran on an export -- three rounds of "send me the export"
                // is three rounds of making the user do the measuring. This is
                // the same read-only scan on the frame that is about to be
                // shown; it costs ~2 ms on a 2048 tier and is skipped on the
                // 800 px live path, where it would run ten times a second.
                if (longEdge >= 1024) {
                    try {
                        final String edgeLine = SpektraLabEngine.edgeReport();
                        final String verdict = edgeVerdictOfLine(edgeLine);
                        if (verdict != null) {
                            DebugSink.textNow("edge-canvas", verdict);
                        } else if (edgeLine != null && edgeCanvasSends < 4) {
                            // Below the threshold, but say what it measured:
                            // knowing that preview reads 40 and full reads 8 on
                            // the same picture is the whole answer.
                            ++edgeCanvasSends;
                            DebugSink.textNow("edge-weak", edgeLine);
                        }
                    } catch (Throwable spkIgnored) { Diagnostics.ignored("edgeVerdictOfLine:13811", spkIgnored); }
                }
                session.renderEnd();
                CrashTrail.markNow(MainActivity.this, "render:end");
                // What the meter did to *this* frame. A render that looks dark
                // is either the film's curve or an exposure the engine decided
                // on, and the two are indistinguishable by eye -- one line of
                // numbers settles it.
                try {
                    rememberAppliedEv(session.progressJson(), "full");
                } catch (Throwable spkIgnored) { Diagnostics.ignored("edgeVerdictOfLine:13821", spkIgnored); }
                // The rebate's own numbers, next to the buffer they belong to.
                // "the edge is wrong in the export but right in the preview" is
                // a question only these two lines can answer.
                try {
                    DebugSink.text("overscan-geom", "tier=live " + w + "x" + h + " "
                            + session.overscanGeometry());
                } catch (Throwable spkIgnored) { Diagnostics.ignored("edgeVerdictOfLine:13828", spkIgnored); }
                // The engine's log for this render. When a frame comes back as
                // one flat colour there is nothing else to read: it says which
                // tier was built, how big, and what each dispatch covered.
                uploadVkLog();

                final int fw = w, fh = h;
                // Diagnostic only: the hash and the two sample pixels are what the
                // "wrong picture" reports are compared against. The preview used to
                // also be JPEG-compressed and handed to DebugSink.bytes(), which is
                // a no-op -- pure work on the render thread for a byte array nobody
                // reads. Keep the numbers, drop the encode (see issue #1 in the audit).
                try {
                    final long hsh = PixelHash.fnv1a(pixels, pixels.length, 7);
                    final String hashLine = "w=" + w + " h=" + h + " hash=" + Long.toHexString(hsh)
                            + " band=" + testBandRows + " copy=" + copied
                            + " first=" + Integer.toHexString(pixels[0])
                            + " mid=" + Integer.toHexString(pixels[(h / 2) * w + w / 2]);
                    DebugSink.text("preview", hashLine);
                    // logcat hook for scripts/device-golden.ps1 (no network needed).
                    android.util.Log.i("SpektraLabHash", hashLine);
                } catch (Throwable spkIgnored) { Diagnostics.ignored("edgeVerdictOfLine:13846", spkIgnored); }
                UiDispatcher.post(() -> {
                    if (renderedEpoch != renderEpoch
                            || currentFrame != renderedFrame || session == null) {
                        try { DebugSink.text("render", "frame changed mid-render; dropped"); }
                        catch (Throwable spkIgnored) { Diagnostics.ignored("edgeVerdictOfLine:13851", spkIgnored); }
                        // **The overlay belongs to this generation too.**
                        //
                        // Returning here is what left the canvas saying
                        // RENDERING for ever: the strip had already switched to
                        // another photo, so this callback dropped its bitmap --
                        // and took the only `rendering = false` with it. Every
                        // later tap then answered "正在渲染", and a render that
                        // had queued up (`pendingRender`) never ran either,
                        // because `renderImage` bailed at the same flag.
                        rendering = false;
                        clearBusyPlate();
                        if (pendingRender) {
                            pendingRender = false;
                            if (filmStock != null && session != null) renderImage();
                        }
                        return;
                    }
                    showingOriginal = false;
                    compare.setAfter(bmp);
                    if (rotationPending && renderedGeometrySeq >= geometrySeq) {
                        rotationPending = false;
                        compare.setPreviewRotation(0f);
                    }
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
                    // A finished render is exactly what makes both pages live
                    // -- and it is the only place that does.
                    refreshPanelLocks();
                    setOriginalVisual(false);
                    updateFilmSelection();
                    final int[] bands = blackBands(bmp);
                    statusText.setText(tr("render.preview_ready") + fw + "×" + fh);
                    try {
                        DebugSink.text("bands", "render " + fw + "x" + fh
                                + " black rows top=" + bands[0] + " bottom=" + bands[1]
                                + " source=" + sourceWidth + "x" + sourceHeight);
                    } catch (Throwable spkIgnored) { Diagnostics.ignored("blackBands:13904", spkIgnored); }
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
                try { session.renderEnd(); } catch (Throwable spkIgnored) { Diagnostics.ignored("blackBands:13915", spkIgnored); }
                DebugSink.text("failure", AppFailure.of(AppFailure.Stage.RENDER, e).stage
                        + ": " + describe(e));
                final String msg = describe(e);
                UiDispatcher.post(() -> {
                    // **A rotation that failed must not stay half applied.** The
                    // numbers were committed before the render and the canvas
                    // still shows the view-space turn; leaving both is a picture
                    // that looks turned and exports straight. Put the angle back
                    // and take the preview rotation off in the same frame.
                    if (rotationPending) {
                        rotationPending = false;
                        geometryRotation = rotationBeforeDrag;
                        if (compare != null) compare.setPreviewRotation(0f);
                        if (rotateArc != null) rotateArc.setAngle(geometryRotation);
                    }
                    // **A failed render is not an edit.** `scheduleUndo` runs
                    // before the render does, so a failure would still push the
                    // state that caused it -- and undo then replayed the error
                    // instead of the picture before it.
                    cancelUndo();
                    statusText.setText(tr("render.render_failed") + SpkError.shortForm(msg));
                    progressBar.setVisibility(View.GONE);
                    busyLabel.setVisibility(View.GONE);
                    rendering = false;
                    // The foot is one 7 sp line: a failure has to be readable,
                    // and it has to arrive with enough context to be reported.
                    showErrorDialog(tr("render.render_failed_2"), SpkError.format(msg)
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
        UiDispatcher.post(() -> showActionSheet(tr("render.the_image_exceeds_this_device"),
                tr("render.import_and_render_again_at") + String.format(java.util.Locale.US, "%.1f", picking / 1e6)
                        + tr("render.mp_the_image_will_be"),
                new String[]{tr("render.retry"), tr("editor.cancel")}, which -> {
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
            showActionSheet(tr("print.no_photo_to_rebalance"), tr("print.import_a_photo_and_choose"),
                    new String[]{tr("print.ok")}, which -> { });
            return;
        }
        statusText.setText(tr("print.rebalancing_print"));
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
            UiDispatcher.post(() -> {
                if (err != null) {
                    statusText.setText(tr("print.could_not_rebalance_the_print"));
                    showErrorDialog(tr("print.could_not_rebalance_the_print"), err);
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
                } catch (Throwable spkIgnored) { Diagnostics.ignored("optJSONObject:14119", spkIgnored); }
                statusText.setText(tr("print.print_rebalanced") + balanced);
                showActionSheet(tr("print.color_timing_results"),
                        tr("print.metering") + balanced + tr("print.neutral_filters_based_on_the")
                                + tr("print.cyan") + ch + tr("print.magenta") + mg + tr("print.yellow") + ye
                                + tr("print.reset_enlarger_clears_brightness_yellow")
                                + tr("print.to_restore_the_neutral_balance"),
                        new String[]{tr("print.reset_enlarger"), tr("crash.close")}, which -> {
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
        } catch (Throwable spkIgnored) { Diagnostics.ignored("persistOrCopy:14160", spkIgnored); }
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
        } catch (Throwable spkIgnored) { Diagnostics.ignored("systemThumb:14182", spkIgnored); }
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
        UiDispatcher.post(() -> {
            if (statusText != null) statusText.setText(tr("export.adding") + total + tr("export.photos"));
        });
        new Thread(() -> {
            int added = 0, failed = 0;
            for (Uri u : uris) {
                final Uri target = persistOrCopy(u);
                if (target == null) { ++failed; continue; }
                if (addFrameOnly(target)) ++added; else ++failed;
            }
            final int a = added, f = failed;
            UiDispatcher.post(() -> {
                if (a > 0) {
                    saveSessionState();
                    rebuildFrameChips();
                }
                final String msg = tr("export.added") + a + tr("export.photos_2") + (f > 0 ? tr("export.skipped") + f + tr("export.photos_2") : "");
                if (statusText != null) statusText.setText(msg);
                showInAppToast(msg);
                if (a > 0 && currentFrame < 0 && !frames.isEmpty()) {
                    reopenFrame(frames.size() - a);
                }
            });
        }, "SpektraLab-batch-import").start();
    }

    private void uiWait(Runnable r, long ms) {
        // Already on the UI thread: run inline instead of posting and waiting
        // on a latch that can never be satisfied.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            r.run();
            return;
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        UiDispatcher.post(() -> {
            try { r.run(); }
            catch (Throwable t) {
                try { DebugSink.text("uiWait", describe(t)); } catch (Throwable spkIgnored) { Diagnostics.ignored("CountDownLatch:14241", spkIgnored); }
            } finally { latch.countDown(); }
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
            showInAppToast(tr("export.batch_export_is_already_running"));
            return;
        }
        if (frames.size() < 2) {
            showInAppToast(tr("export.only_one_photo_is_available"));
            return;
        }
        saveLookIntoFrames();
        final int total = frames.size();
        final int home = currentFrame;
        batchExportRunning = true;
        batchExportAbort = false;
        if (abortBatchPill != null) abortBatchPill.setVisibility(View.VISIBLE);
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
                // **The strip can shrink while the batch runs.** `total` was
                // captured when the loop started; deleting a chip (or the
                // restore path rewriting the strip) leaves fewer frames than
                // that, and `frames.get(i)` then throws
                // `IndexOutOfBoundsException: Index 3 out of bounds for length
                // 3` on the batch thread -- which looks like "批量导出结束了，
                // 然后报错" and loses the remaining frames.
                if (index >= frames.size()) { ++failed; continue; }
                final Frame frame = frames.get(index);
                final String name = frame.name == null
                        ? tr("export.photo") + (i + 1) + tr("export.suffix") : frame.name;
                if (frame.look == null || frame.look.filmStock == null) {
                    ++failed;               // nothing to print: no film chosen
                    continue;
                }
                UiDispatcher.post(() -> {
                    if (statusText != null) {
                        statusText.setText(tr("export.batch_export") + (index + 1) + "/" + total + ": " + name);
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
                } catch (Throwable spkIgnored) { Diagnostics.ignored("get:14331", spkIgnored); }
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
                    catch (Throwable spkIgnored) { Diagnostics.ignored("get:14353", spkIgnored); }
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
                try { if (engine != null) engine.trimPool(); } catch (Throwable spkIgnored) { Diagnostics.ignored("get:14369", spkIgnored); }
                // **The next frame starts from zero.** The decoded raw cache
                // holds one frame's unpacked plane, and the heap still carries
                // the last frame's bitmaps; six frames of that is what made the
                // fifth one die.
                try { SpektraLabEngine.clearRawCache(); } catch (Throwable spkIgnored) { Diagnostics.ignored("get:14374", spkIgnored); }
                System.gc();
            }
            } finally {
                // **The flag has to come down even when the loop throws**, or
                // every later batch is refused and every error dialog is eaten.
                final int fok = ok, ffail = failed;
                UiDispatcher.post(() -> {
                    batchExportRunning = false;
                    if (abortBatchPill != null) abortBatchPill.setVisibility(View.GONE);
                    if (exportProgressBar != null) exportProgressBar.setVisibility(View.GONE);
                    final String msg = tr("export.batch_export_finished") + fok + tr("export.succeeded")
                            + (ffail > 0 ? ", " + ffail + tr("export.failed_no_film_selected_or") : "");
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
                            UiDispatcher.post(() -> {
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
        } catch (Throwable spkIgnored) { Diagnostics.ignored("uploadStaleExportProgress:14439", spkIgnored); }
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
            try { DebugSink.text("vk-log", "read failed: " + t); } catch (Throwable spkIgnored) { Diagnostics.ignored("uploadVkLog:14466", spkIgnored); }
        }
    }

    private void uploadCapabilities() {
        try {
            String caps = engine != null ? engine.capabilities() : null;
            if (caps != null) DebugSink.text("capabilities", caps);
            String mem = engine != null ? engine.memoryReport() : null;
            if (mem != null) DebugSink.text("memory-report", mem);
            uploadStaleExportProgress();
        } catch (Throwable spkIgnored) { Diagnostics.ignored("uploadCapabilities:14477", spkIgnored); }
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
        } catch (Throwable spkIgnored) { Diagnostics.ignored("uploadExportProgress:14492", spkIgnored); }
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
        } catch (Throwable spkIgnored) { Diagnostics.ignored("sourceExifJson:14532", spkIgnored); }
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
                    } catch (Throwable spkIgnored) { Diagnostics.ignored("getExternalFilesDir:14560", spkIgnored); }
                }
                try {
                    final byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(head);
                    final StringBuilder hex = new StringBuilder();
                    for (int i = 0; i < 8; ++i) hex.append(String.format("%02x", digest[i]));
                    headHash = hex.toString();
                } catch (Throwable spkIgnored) { Diagnostics.ignored("StringBuilder:14567", spkIgnored); }
            }
        } catch (Throwable spkIgnored) { Diagnostics.ignored("StringBuilder:14569", spkIgnored); }
        try (android.database.Cursor c = getContentResolver().query(sourceUri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                final int idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) declared = String.valueOf(c.getLong(idx));
            }
        } catch (Throwable spkIgnored) { Diagnostics.ignored("getColumnIndex:14575", spkIgnored); }
        try (java.io.InputStream in = openUri(sourceUri)) {
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
        } catch (Throwable spkIgnored) { Diagnostics.ignored("ExifInterface:14587", spkIgnored); }
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
            try { sourceName = getFileName(sourceUri); } catch (Throwable spkIgnored) { Diagnostics.ignored("isEmpty:14599", spkIgnored); }
            DebugSink.text("exif", "jpeg source name=" + sourceName + " size=" + declared
                    + " head=" + headBytes + " B sha=" + headHash + " uri=" + sourceUri
                    + " native=" + java.util.Arrays.toString(nativeFields)
                    + " platform=" + java.util.Arrays.toString(platformFields)
                    + " -> " + java.util.Arrays.toString(merged));
        } catch (Throwable spkIgnored) { Diagnostics.ignored("isEmpty:14605", spkIgnored); }
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
        try (java.io.InputStream in = openUri(sourceUri)) {
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

    /**
     * **The one line in the export that would have caught the ruler.**
     *
     * <p>`log_edge_profile` (JNI) already reduces each edge to a per-line mean
     * and says whether the hot lines are evenly spaced: that is the `pN` in
     * `edge 2591x3106 stride=2591 L=15@814 p1 R=0@-1 p0 ...`. It was written
     * to the `.prog` file for a human to read, and nobody did. A picture's own
     * edge is rough in whatever rhythm it likes; a seam or a resample fold is
     * evenly spaced, which is exactly what "a column of little bars along the
     * left edge" describes.
     *
     * <p>Returns null when no edge is evenly spaced, otherwise the side, the
     * spacing, and the whole line -- enough to tell 17 bars 200 px apart on a
     * 3400 px canvas from a barcode that is supposed to be there.
     */
    private static String edgeRulerVerdict(String progPath) {
        try {
            final java.io.File f = new java.io.File(progPath);
            if (!f.exists() || f.length() <= 0 || f.length() > 65536) return null;
            final byte[] b = new byte[(int) f.length()];
            try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            }
            for (String line : new String(b, "UTF-8").split("\n")) {
                final String v = edgeVerdictOfLine(line);
                if (v != null) return v;
            }
        } catch (Throwable spkIgnored) { Diagnostics.ignored("edgeVerdictOfLine:14679", spkIgnored); }
        return null;
    }

    /** One `edge ...` line, judged. Null means "nothing a person would call a defect".
     *
     *  Three separate measurements live in that line, and they catch three
     *  different things:
     *
     *  - **`Lrow`** — the left band, row by row, second difference. This is the
     *    one that matters for "a ruler down the left edge": the report is a run
     *    of *short horizontal dashes*, a pixel tall and a dozen wide, one every
     *    ~6 % of the height. Averaging a whole column cannot see them (18 px of
     *    1500, one row of 2000 → 0.7/255), which is why four days of `edge`
     *    lines all read `p0`. Measured on the six samples the user sent:
     *    peak 125-235, 47-127 hot rows. On fourteen of our own exports: median
     *    peak 14.3, at most 9 hot rows. `100` and `40` sit in the gap.
     *  - **`Vmax`** — the steepest step between adjacent column means, anywhere
     *    in the frame. For a true 1 px vertical seam. Clean renders measure
     *    0.7-1.3; a 30 % line injected into the same file measures 33-36.
     *  - **`pN`** — evenly spaced hot lines on one edge, the old ruler test.
     *    `p1` is four hot lines one row apart, which is a hard edge, not a ruler.
     */
    /** How many `edge-canvas` lines this process has sent. Four is enough to
     *  see every tier once; more would be noise in a table that already reads
     *  one row per export. */
    private int edgeCanvasSends = 0;

    private static String edgeVerdictOfLine(String line) {
        final app.spektralab.domain.EdgeVerdict v =
                app.spektralab.domain.EdgeVerdict.fromLine(line);
        if (v == null) return null;
        switch (v.kind) {
            case "left_short_marks":
                return tr("export.left_edge_short_marks_peak") + v.first + " y=" + v.second
                        + tr("export.rows") + v.third + tr("export.spacing") + v.fourth
                        + tr("export.groups") + v.fifth + " · " + line;
            case "left_wide_band":
                return tr("export.left_edge_wide_band_peak") + v.first + " y=" + v.second
                        + tr("export.rows") + v.third + " · " + line;
            case "vertical_line":
                return tr("export.vertical_line_x") + v.first + " Δ=" + v.second + " · " + line;
            case "side_period":
                return v.first + " every " + v.second + " px · " + line;
            default:
                return null;
        }
    }

    /** The file just written does not match the canvas; the worker decides. */
    private volatile boolean exportCorruptNow = false;
    /** One automatic retry per user-initiated export. */
    private volatile boolean exportRetried = false;

    private void exportImage(String format, String tier) {
        exportRetried = false;
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
        final app.spektralab.domain.ExportPlan exportPlan =
                app.spektralab.domain.ExportPlan.forFormat(format);
        final boolean dng = ".dng".equals(exportPlan.extension);
        final boolean tiff = ".tif".equals(exportPlan.extension);
        final String ext = exportPlan.extension;
        final String mime = exportPlan.mime;
        if (sharePill != null) sharePill.setVisibility(View.GONE);
        if (engine == null || session == null) {
            showInAppToast(tr("export.import_a_photo_first"));
            if (onDone != null) onDone.run();
            return;
        }
        if (filmStock == null) {
            showInAppToast(tr("export.choose_a_film_before_exporting"));
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
                UiDispatcher.post(() -> {
                    statusText.setText(tr("export.exporting_preparing_full_size_render"));
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
                } catch (Throwable spkIgnored) { Diagnostics.ignored("equals:14820", spkIgnored); }

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
                } catch (Throwable spkIgnored) { Diagnostics.ignored("equals:14838", spkIgnored); }
                final long t0 = System.currentTimeMillis();
                // The stop pill used to be checked only between frames: a batch of
                // twenty pictures could not be stopped once one export started.
                if (batchExportAbort) throw new IllegalStateException(tr("export.stopped"));
                final java.util.concurrent.atomic.AtomicBoolean spinning =
                        new java.util.concurrent.atomic.AtomicBoolean(true);
                final java.util.concurrent.atomic.AtomicLong lastMem =
                        new java.util.concurrent.atomic.AtomicLong(-5);
                final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
                Runnable tick = new Runnable() {
                    @Override public void run() {
                        if (!spinning.get()) return;
                        long el = (System.currentTimeMillis() - t0) / 1000;
                        statusText.setText(tr("export.exporting") + tier + tr("export.rendering_for") + el + tr("export.s"));
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
                            } catch (Throwable spkIgnored) { Diagnostics.ignored("availableMemoryBytes:14867", spkIgnored); }
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
                        } catch (Throwable spkIgnored) { Diagnostics.ignored("currentTimeMillis:14889", spkIgnored); }
                        try { Thread.sleep(5000); } catch (InterruptedException ie) { break; }
                    }
                }, "SpektraLab-export-memory");
                memTicker.setDaemon(true);
                memTicker.start();
                UiDispatcher.post(tick);
                // A full-size export does not need the preview bitmaps resident.
                // Keep the frame and its Look; these two are only the display.
                final Bitmap savedBefore = beforeBitmap;
                final Bitmap savedAfter = lastRenderedBitmap;
                UiDispatcher.post(() -> {
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
                        } catch (Throwable spkIgnored) { Diagnostics.ignored("trimPool:14918", spkIgnored); }
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
                        boolean keepClosed = false;
                        // **A batch closes this session a moment later anyway.**
                        // Suspending writes a frame's worth of planes to the
                        // cache directory and resuming allocates them again --
                        // once per frame, for a session that is about to die.
                        if (keep != null && !batchExportRunning) {
                            try {
                                keepSuspended = keep.suspend();
                                DebugSink.text("export", "preview suspended=" + keepSuspended
                                        + "; report " + engine.memoryReport());
                            } catch (Throwable spkIgnored) { Diagnostics.ignored("trimPool:14950", spkIgnored); }
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
                                    catch (Throwable spkIgnored) { Diagnostics.ignored("getHeight:15012", spkIgnored); }
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
                                } catch (Throwable spkIgnored) { Diagnostics.ignored("getHeight:15085", spkIgnored); }
                                final String openJson = app.spektralab.domain.ExportPlan.openParams(
                                        printStock, exportAutoExposure, cameraEv, printExposure);
                                fresh = wbMul != null
                                        ? engine.openRawSessionTuned(rawFilePath, openJson,
                                                exportPixels, exportEdge, wbMul)
                                        : engine.openRawSession(rawFilePath, openJson,
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
                        // **Two working sets do not fit on the phones that fail.**
                        //
                        // `suspend` snapshots the preview's planes to the cache
                        // directory and drops the device copies -- but it can
                        // fail, and when it does this code used to carry on and
                        // open the export session anyway: one whole frame
                        // resident plus another being allocated. Measured on a
                        // 26 MP export (Adreno 650): `preview suspended=false`,
                        // then VkResult -2.
                        //
                        // So: if the suspend did not happen, or the room it left
                        // is still not enough for the export's own working set,
                        // close the preview outright. The frame comes back in the
                        // `finally` below -- a few seconds of re-import traded for
                        // an export that exists.
                        // **A batch closes this session itself, a frame later.** Closing
                        // it here too would also post a re-import after every
                        // frame, straight into the batch loop's next reopen.
                        if (fresh != null && keep != null && !batchExportRunning) {
                            final boolean wasSuspended = keepSuspended;
                            // 5.1 planes of 12 B/px plus the encoder's tables,
                            // measured on the host at 20.9 MP (`mem_export`).
                            final long needBytes = app.spektralab.domain.ExportSessionPlan
                                    .workingSetBytes(sourceWidth, sourceHeight);
                            // **More than half of what is left is more than the
                            // two of them can share.** `avail` is MemAvailable,
                            // so it already excludes the preview's own arena;
                            // this asks whether one export working set still
                            // fits in what remains after it.
                            final long avail = availableMemoryBytes();
                            if (app.spektralab.domain.ExportSessionPlan.closePreview(
                                    true, batchExportRunning, wasSuspended, needBytes, avail)) {
                                try { keep.close(); } catch (Throwable spkIgnored) { Diagnostics.ignored("availableMemoryBytes:15192", spkIgnored); }
                                if (session == keep) session = null;
                                keepSuspended = false;
                                keepClosed = true;
                                try {
                                    DebugSink.text("export", "preview closed for the export"
                                            + " suspended=" + wasSuspended
                                            + " need=" + (needBytes >> 20)
                                            + " avail=" + (avail >> 20) + " MiB");
                                } catch (Throwable spkIgnored) { Diagnostics.ignored("availableMemoryBytes:15201", spkIgnored); }
                            }
                        }
                        if (exportSession == null) {
                            // The frame's session went away between `export:lock`
                            // and here. Saying so is the honest end of this
                            // frame; dereferencing it was a null pointer.
                            try {
                                DebugSink.text("export-error", "tier=" + tier + " no session for the export");
                            } catch (Throwable spkIgnored) { Diagnostics.ignored("availableMemoryBytes:15210", spkIgnored); }
                            UiDispatcher.post(() -> {
                                showInAppToast(tr("export.skipping_this_photo_it_is"));
                                if (onDone != null) onDone.run();
                            });
                            return;
                        }
                        if (fresh != null) {
                            try { DebugSink.text("export", "fresh session for tier=" + tier); }
                            catch (Throwable spkIgnored) { Diagnostics.ignored("availableMemoryBytes:15219", spkIgnored); }
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
                            try { keep.resume(); } catch (Throwable spkIgnored) { Diagnostics.ignored("availableMemoryBytes:15231", spkIgnored); }
                            keepSuspended = false;
                            try { DebugSink.text("export", "no fresh session; preview resumed"); }
                            catch (Throwable spkIgnored) { Diagnostics.ignored("availableMemoryBytes:15234", spkIgnored); }
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
                                if (pinEv) {
                                    exportSession.pinAutoExposureEv(lastAppliedEv);
                                } else if (exportAutoExposure) {
                                    // **A batch walks the strip without rendering**,
                                    // so the canvas' measured EV belongs to the
                                    // frame before this one and cannot be pinned
                                    // here. Letting the fresh session meter on its
                                    // own was the other half of "导出只有片边、内容
                                    // 一片白": the same meter that occasionally
                                    // clamps on a full-size decode. Measure once,
                                    // explicitly, and pin that answer -- then
                                    // clamp the pathological tail, because a meter
                                    // that cannot see the frame returns the end of
                                    // its range and paints the whole negative
                                    // white.
                                    double ev = Double.NaN;
                                    try {
                                        final org.json.JSONObject solved =
                                                new org.json.JSONObject(exportSession.solve("both"));
                                        final org.json.JSONObject by =
                                                solved.optJSONObject("exposure_ev_by_method");
                                        final String m = exportMeter.isEmpty() ? "balanced" : exportMeter;
                                        if (by != null && by.has(m)) ev = by.optDouble(m, Double.NaN);
                                    } catch (Throwable ignored2) { ev = Double.NaN; }
                                    final boolean sane = !Double.isNaN(ev) && Math.abs(ev) <= 8.0;
                                    if (!sane) ev = lastAppliedEvValid ? lastAppliedEv : 0.0;
                                    exportSession.pinAutoExposureEv(ev);
                                    try {
                                        DebugSink.text("export-meter", "method=" + exportMeter
                                                + " solved=" + f("%.4f", ev)
                                                + (sane ? "" : " (clamped)")
                                                + " sameFrame=" + lastAppliedEvValid);
                                    } catch (Throwable spkIgnored) { Diagnostics.ignored("abs:15295", spkIgnored); }
                                } else {
                                    exportSession.clearPinAutoExposureEv();
                                }
                            } catch (Throwable spkIgnored) { Diagnostics.ignored("abs:15299", spkIgnored); }
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
                            } catch (Throwable spkIgnored) { Diagnostics.ignored("getParams:15314", spkIgnored); }
                            try {
                                DebugSink.text("export-exposure", "tier=" + tier
                                        + " auto=" + exportAutoExposure
                                        + " method=" + (exportMeter.isEmpty() ? "balanced" : exportMeter)
                                        + " pin=" + (pinEv ? f("%.4f", lastAppliedEv) + " same-frame" : "none")
                                        + " seenEv=" + f("%.4f", lastAppliedEv)
                                        + (lastAppliedEvValid ? "" : " (none)")
                                        + " solveEv=" + f("%.4f", meteredEv)
                                        + " offset=" + f("%.2f", cameraEv));
                            } catch (Throwable spkIgnored) { Diagnostics.ignored("getParams:15324", spkIgnored); }
                            try {
                                DebugSink.text("overscan-geom", "tier=" + tier + " before "
                                        + exportSession.overscanGeometry());
                                DebugSink.text("export-params", "tier=" + tier + " " + exportSession.getParams());
                            } catch (Throwable spkIgnored) { Diagnostics.ignored("getParams:15329", spkIgnored); }
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
                            } catch (Throwable spkIgnored) { Diagnostics.ignored("getParams:15361", spkIgnored); }
                            if (batchExportAbort) throw new IllegalStateException(tr("export.stopped"));
                            CrashTrail.markNow(this, "export:write " + tier + " " + format);
                            Thread wtick = startTick("export", exportSession);
                            try {
                                info = exportController.export(exportSession, format, tier,
                                        tmp.getAbsolutePath(), 95);
                            } finally {
                                stopTick(wtick);
                            }
                            CrashTrail.markNow(this, "export:written " + info[0] + "x" + info[1]);
                            try {
                                DebugSink.text("overscan-geom", "tier=" + tier + " " + info[0] + "x" + info[1]
                                        + " " + exportSession.overscanGeometry());
                            } catch (Throwable spkIgnored) { Diagnostics.ignored("getParams:15378", spkIgnored); }
                        } finally {
                            if (fresh != null) {
                                try { fresh.close(); } catch (Throwable spkIgnored) { Diagnostics.ignored("getParams:15381", spkIgnored); }
                                // **The export's pool goes back before the
                                // preview's planes come back.** `resume`
                                // allocates persistent buffers, and they cannot
                                // come out of an arena the export is still
                                // sitting in -- the whole reason the preview was
                                // suspended is that the two do not fit at once.
                                try { engine.trimPool(); } catch (Throwable spkIgnored) { Diagnostics.ignored("getParams:15388", spkIgnored); }
                            }
                            if (keepSuspended && keep != null) {
                                try { keep.resume(); } catch (Throwable spkIgnored) { Diagnostics.ignored("getParams:15391", spkIgnored); }
                            }
                            final boolean closedForExport = keepClosed;
                            if (closedForExport) {
                                // The preview session was closed to make room for
                                // the export, so the canvas has nothing behind it.
                                // Re-open the same frame the way a chip does.
                                UiDispatcher.post(() -> {
                                    if (!uiReady) return;
                                    if (currentFrame < 0 || currentFrame >= frames.size()) return;
                                    CrashTrail.mark(MainActivity.this, "export:reopen after close");
                                    reopenFrameForced(currentFrame);
                                });
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
                        } catch (Throwable spkIgnored) { Diagnostics.ignored("trimPool:15418", spkIgnored); }
                    }
                } finally {
                    spinning.set(false);
                    try { memTicker.interrupt(); } catch (Throwable spkIgnored) { Diagnostics.ignored("trimPool:15422", spkIgnored); }
                    UiDispatcher.post(() -> {
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
                    throw new RuntimeException(tr("export.the_engine_did_not_produce"));
                }

                // (The capture fields are written by the JPEG encoder itself,
                // handed over just above. This used to reopen the finished file
                // and save attributes through the platform class; its guard read
                // `"jpg".equals(ext)` against an `ext` of ".jpg", so for a whole
                // release no export carried any of it -- silently.)
                final int fw = w, fh = h;
                UiDispatcher.post(() -> statusText.setText(tr("export.exporting_saving_to_the_photo") + fw + "x" + fh + "..."));
                String fileName = "SpektraLab_" + System.currentTimeMillis() + ext;
                final Uri outUri = exportStore.write(tmp, fileName, ext, mime);
                // **Does the file look like the picture we think we wrote?**
                // Two measurements of the smoothed result: the mean difference
                // between neighbouring rows and between neighbouring columns.
                // A photograph has both (their ratio sits near 1); a frame
                // stretched along one axis does not. Cheap enough for every
                // export, and the only evidence a "garbled, once, never again"
                // report can leave behind.
                exportCorruptNow = false;
                {
                    // The canvas is only *this* frame's while a batch is not
                    // walking the strip behind our back.
                    final double ncc = batchExportRunning ? -1
                            : app.spektralab.data.ExportVerifier.matchesCanvas(tmp.getAbsolutePath(), lastRenderedBitmap);
                    if (ncc >= 0 && ncc < 0.55) {
                        exportCorruptNow = true;
                        try {
                            DebugSink.textNow("export-corrupt",
                                    String.format(java.util.Locale.US,
                                        "canvas NCC %.3f at %dx%d tier=%s overscan=%s — file kept? no, retrying",
                                        ncc, w, h, tier, overscanOn) + "\n" + reportHead());
                        } catch (Throwable spkIgnored) { Diagnostics.ignored("writeExportFile:15469", spkIgnored); }
                    } else if (ncc >= 0) {
                        try { DebugSink.text("export-selfcheck", String.format(
                                java.util.Locale.US, "canvas NCC %.3f", ncc)); }
                        catch (Throwable spkIgnored) { Diagnostics.ignored("writeExportFile:15473", spkIgnored); }
                    }
                }
                {
                    final String verdict = exportCorruptNow ? null : app.spektralab.data.ExportVerifier.inspect(tmp.getAbsolutePath());
                    if (verdict != null) {
                        try { DebugSink.textNow("export-selfcheck", verdict); } catch (Throwable spkIgnored) { Diagnostics.ignored("getAbsolutePath:15479", spkIgnored); }
                        // **Say it out loud.** This measurement has existed since
                        // the "garbled, once, never again" report, but it only
                        // ever reached the status bar -- and the field reports
                        // that followed all say the same thing: the user found
                        // out from the gallery. The 26.7 MP export behind
                        // SL-261110-F51B was 616 KB (0.023 B/px against a
                        // normal 0.5-1) and the app called it a success.
                        // The file stays -- the render cost minutes -- but it
                        // is named as suspect and the user is told to try again.
                        UiDispatcher.post(() -> {
                            if (statusText != null) statusText.setText(tr("export.the_export_did_not_pass") + verdict);
                            showInAppToast(tr("export.the_export_may_be_corrupted"));
                        });
                    }
                }
                // **And the one measurement the edge probe already made.**
                // It only ever reached a file nobody reads, which is why a
                // "column of bars on the left edge" report could not be tied to
                // a side, a spacing or a canvas size. One upload, only when the
                // answer is not null: a clean export stays silent.
                {
                    final String ruler = edgeRulerVerdict(tmp.getAbsolutePath() + ".prog");
                    if (ruler != null) {
                        // **Synchronous.** This runs on the export worker, not
                        // the UI thread, and the whole point is that the report
                        // survives the user switching away mid-export -- the
                        // queued path is exactly what lost the last three.
                        try {
                            DebugSink.textNow("edge-ruler", ruler + "\n" + reportHead());
                        } catch (Throwable spkIgnored) { Diagnostics.ignored("getAbsolutePath:15509", spkIgnored); }
                        UiDispatcher.post(() -> {
                            if (statusText != null) statusText.setText(tr("export.edge_check") + ruler);
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
                    // The exported bytes used to be copied into a byte[] of up to
                    // 30 MB and passed to DebugSink.bytes(), which discards them.
                    // The file size above is the whole diagnostic; do not read it
                    // back into the heap at the end of a minutes-long export.
                } catch (Throwable spkIgnored) { Diagnostics.ignored("getAbsolutePath:15526", spkIgnored); }
                final long secs = Math.max(1, (System.currentTimeMillis() - t0) / 1000);
                final long fRender = renderMs;
                final long bytes = tmp.exists() ? tmp.length() : 0;
                final String sizeLabel = bytes > 0
                        ? String.format("%.1f MB", bytes / 1048576.0) : "?";
                UiDispatcher.post(() -> {
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
                    final String canvasNote = canvas.isEmpty() ? "" : tr("export.canvas") + canvas;
                    final String tiffHint = tiff
                            ? tr("export.tiff_use_share_next_to")
                            : dng ? tr("export.dng_16_bit_linear_for") : "";
                    if (!exportCorruptNow)
                        showInAppToast(tr("export.saved") + fw + "x" + fh + canvasNote + " · " + sizeLabel + tiffHint);
                    else
                        showInAppToast(tr("export.the_export_did_not_pass_2"));
                    statusText.setText(tr("export.exported") + fw + "x" + fh + canvasNote + " · " + sizeLabel
                        + " (" + secs + tr("export.s_including") + (fRender / 1000.0) + tr("export.s_rendering") + tiffHint);
                    exportProgressBar.setVisibility(View.GONE);
                    setStep(2);
                });
            } catch (Throwable e) {
                final String msg = describe(e);
                DebugSink.text("failure", AppFailure.of(AppFailure.Stage.EXPORT, e).stage
                        + ": " + msg);
                try { DebugSink.text("export-error", "tier=" + tier + " " + msg); } catch (Throwable spkIgnored) { Diagnostics.ignored("describe:15561", spkIgnored); }
                uploadExportProgress(tmp);
                uploadVkLog();
                UiDispatcher.post(() -> {
                    statusText.setText(tr("export.export_failed") + SpkError.shortForm(msg));
                    showErrorDialog(tr("export.export_failed_2"), SpkError.format(msg)
                            + "\n\n" + sourceWidth + "×" + sourceHeight
                            + "\n" + probe.describe());
                    exportProgressBar.setVisibility(View.GONE);
                    setStep(2);
                });
                final int code = SpkError.codeOf(msg);
                if (code == 1001 || code == 1009) offerSmallerImport();
            } finally {
                if (tmp.exists()) tmp.delete();
                // **An export that came back as noise is not an export.**
                // The render cost fifteen seconds; a silent bad file costs the
                // user a trip to the gallery and a bug report. Retry once, on
                // the UI thread, and let the second attempt report.
                if (exportCorruptNow && !exportRetried && !batchExportRunning) {
                    exportRetried = true;
                    UiDispatcher.post(() -> {
                        if (statusText != null) statusText.setText(tr("export.the_export_did_not_pass_3"));
                        exportImage(format, tier, onDone);
                    });
                    return;
                }
                if (exportCorruptNow) {
                    UiDispatcher.post(() -> showInAppToast(tr("export.the_export_may_be_corrupted_2")));
                }
                if (onDone != null) UiDispatcher.post(onDone);
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
            } catch (Throwable spkIgnored) { Diagnostics.ignored("currentTimeMillis:15644", spkIgnored); }
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
        if (pendingIncomingUri != null && uiReady) {
            final Uri pending = pendingIncomingUri;
            pendingIncomingUri = null;
            importPicked(pending);
        }
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
        try { saveLookIntoFrames(); } catch (Throwable spkIgnored) { Diagnostics.ignored("onPause:15726", spkIgnored); }
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
        dismissDialogs();
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
