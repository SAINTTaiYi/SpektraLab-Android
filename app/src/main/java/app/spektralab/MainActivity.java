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
import android.widget.ProgressBar;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
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
    /** Set by the long-press on 导入: keep the decoder's own size and say it may fail. */
    private boolean importAtNativeSize = false;
    /** A cap the last E-1001 offered, good for exactly one import. */
    private int pendingCapPixels = 0;
    /** The user has already said yes to this frame's downscale. One import. */
    private boolean importBudgetAccepted = false;
    /** One retry offer per import; a dialog loop would be worse than the error. */
    private boolean e1001Offered = false;
    /** A render is in flight: one native result buffer, one renderer. */
    private volatile boolean rendering = false;
    private SpektraLabEngine.Session session;
    private boolean isRawFile = false;
    private String rawFilePath = null;
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
    private Spinner filmSpinner, printSpinner;
    private final List<String> filmIds = new ArrayList<>(), filmLabels = new ArrayList<>();
    private final List<String> filmTypes = new ArrayList<>();
    private final List<String> printIds = new ArrayList<>(), printLabels = new ArrayList<>();
    private String filmStock = "kodak_portra_400";
    private String printStock = "kodak_portra_endura";
    private float printExposure = 1.0f;
    private float grainAmount = 1.0f;      // 0..2, engine default 1
    private float halationAmount = 1.0f;   // 0..4
    private float glareAmount = 3.0f;      // 0..30 (percent x100)
    private float filmFormatMm = 36.0f;    // frame's long edge, 135 = 36 mm
    private boolean uiReady = false;
    private String lastImportError = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        // Silent, and late enough not to compete with the first import. It only
        // speaks when there is a newer build than this one.
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> UpdateCheck.check(this, false), 3000);
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
    private TextView fileLabel, previewIndex, rawName, formatNote, busyLabel;
    private SeekBar exposureBar, grainBar, halationBar, glareBar;
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
    private Spinner edgeSpinner;
    /** True while `refreshEdgeFormats` rebuilds the adapter: the spinner fires
     *  onItemSelected for that too, and it is not a user choice. */
    private boolean edgeSpinnerBusy = false;
    private final List<String> edgeLabels = new ArrayList<>(), edgeIds = new ArrayList<>();
    private Spinner formatSpinner;
    private final View[] stepCells = new View[4];
    private final View[] stepBars = new View[4];
    private final View[] tabCells = new View[4];
    private final View[] tabBars = new View[4];
    private final TextView[] tabLabels = new TextView[4];
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

    /** The page is paper and blue now, so the bars have to stop being black. */
    private void lightSystemBars() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    c.setSystemBarsAppearance(mask, mask);
                }
            } else {
                int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
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
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }

    private void buildUi() {
        loadUiFonts();
        lightSystemBars();

        final android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(UI_GROUND);
        applySystemInsets(root);
        setContentView(root);

        LinearLayout app = new LinearLayout(this);
        app.setOrientation(LinearLayout.VERTICAL);
        app.setBackgroundColor(UI_PAPER);
        root.addView(app, new android.widget.FrameLayout.LayoutParams(-1, -1));

        // ---- intro: padding 11/16/10, the wordmark in blue at weight 300 ----
        LinearLayout intro = new LinearLayout(this);
        intro.setOrientation(LinearLayout.VERTICAL);
        intro.setPadding(dp(16), dp(11), dp(16), dp(10));

        LinearLayout wordRow = new LinearLayout(this);
        wordRow.setOrientation(LinearLayout.HORIZONTAL);
        wordRow.setGravity(android.view.Gravity.TOP);

        final float wordSp = wordmarkSp();
        TextView word = label("SpektraLab", wordSp, UI_BLUE, tfDisplay);
        word.setLetterSpacing(-0.07f);
        word.setSingleLine(true);
        // `margin-left: -0.055em`: the negative side bearing of the S, so the
        // word optically aligns with the hairlines around it.
        word.setTranslationX(-0.055f * wordSp * getResources().getDisplayMetrics().scaledDensity);
        word.setOnLongClickListener(v -> { showAbout(); return true; });
        wordRow.addView(word);

        TextView version = label("v" + UpdateCheck.versionName(this), 7, UI_BLUE, tfBody);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);
        vlp.leftMargin = dp(7);
        vlp.topMargin = dp(5);
        wordRow.addView(version, vlp);
        intro.addView(wordRow, new LinearLayout.LayoutParams(-1, -2));
        app.addView(intro, new LinearLayout.LayoutParams(-1, -2));

        // ---- workflow: 46 px, four cells, 1&4 blue, 2&3 blue-light --------
        LinearLayout workflow = new LinearLayout(this);
        workflow.setOrientation(LinearLayout.HORIZONTAL);
        final String[] stepNames = {"导入", "换图", "渲染", "导出"};
        for (int i = 0; i < 4; ++i) {
            final int index = i;
            android.widget.FrameLayout cell = new android.widget.FrameLayout(this);
            cell.setBackgroundColor(i == 0 || i == 3 ? UI_BLUE : UI_BLUE_LIGHT);

            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.HORIZONTAL);
            content.setGravity(android.view.Gravity.CENTER_VERTICAL);
            content.setPadding(dp(10), dp(8), dp(10), dp(8));

            TextView number = label(String.format(java.util.Locale.US, "%02d", i + 1), 25, UI_PAPER, tfDisplay);
            number.setLetterSpacing(-0.065f);
            content.addView(number);

            TextView name = label(stepNames[i], 8, UI_PAPER, tfHan);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
            nlp.leftMargin = dp(7);
            content.addView(name, nlp);

            cell.addView(content, new android.widget.FrameLayout.LayoutParams(-1, -1));

            View bar = new View(this);
            bar.setBackgroundColor(UI_PAPER);
            bar.setAlpha(0.75f);
            bar.setVisibility(View.INVISIBLE);
            cell.addView(bar, new android.widget.FrameLayout.LayoutParams(-1, dp(2), android.view.Gravity.BOTTOM));

            cell.setOnClickListener(v -> onWorkflow(index));
            if (i == 3) {
                // And 04's old shortcut: long-press exports the full frame.
                cell.setOnLongClickListener(v -> { exportImage("full"); return true; });
            }
            workflow.addView(cell, new LinearLayout.LayoutParams(0, dp(46), 1f));
            stepCells[i] = cell;
            stepBars[i] = bar;
        }
        app.addView(workflow, new LinearLayout.LayoutParams(-1, dp(46)));

        // ---- lab: preview (grows) + controls (178 px) ----------------------
        LinearLayout lab = new LinearLayout(this);
        lab.setOrientation(LinearLayout.VERTICAL);

        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setBackgroundColor(UI_BLUE);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), 0, dp(16), 0);
        TextView headTitle = label("Image preview", 7, UI_PAPER, tfBody);
        headTitle.setAllCaps(true);
        headTitle.setLetterSpacing(0.05f);
        head.addView(headTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        previewIndex = label("(Empty / 000)", 7, UI_PAPER, tfBody);
        previewIndex.setAlpha(0.58f);
        head.addView(previewIndex);
        preview.addView(head, new LinearLayout.LayoutParams(-1, dp(25)));
        preview.addView(hairline(0x2EFFFFFF), new LinearLayout.LayoutParams(-1, dp(1)));

        // .preview-stage: the blue field, the frame guides, and the three
        // things that can live on it (empty state / RAW name / the frame).
        android.widget.FrameLayout stage = new android.widget.FrameLayout(this);
        stage.setBackgroundColor(UI_BLUE);
        stage.addView(new GuideView(this, dp(11)), new android.widget.FrameLayout.LayoutParams(-1, -1));

        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(android.view.Gravity.CENTER);
        empty.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView emptyIndex = label("(001) / Source", 6, UI_PAPER, tfBody);
        emptyIndex.setLetterSpacing(0.1f);
        emptyIndex.setAlpha(0.6f);
        empty.addView(emptyIndex, new LinearLayout.LayoutParams(-2, -2));

        TextView emptyTitle = label("NO IMAGE", emptyTitleSp(), UI_PAPER, tfDisplay);
        emptyTitle.setLetterSpacing(-0.065f);
        LinearLayout.LayoutParams etlp = new LinearLayout.LayoutParams(-2, -2);
        etlp.topMargin = dp(10);
        empty.addView(emptyTitle, etlp);

        TextView emptyCopy = label("Select a RAW or JPEG to begin", 6, UI_PAPER, tfBody);
        emptyCopy.setAllCaps(true);
        emptyCopy.setLetterSpacing(0.07f);
        emptyCopy.setAlpha(0.65f);
        LinearLayout.LayoutParams eclp = new LinearLayout.LayoutParams(-2, -2);
        eclp.topMargin = dp(10);
        eclp.bottomMargin = dp(16);
        empty.addView(emptyCopy, eclp);

        // The page leaves .load-button to the browser's own grey. On blue the
        // same weight inverts better: paper fill, blue label.
        LinearLayout load = new LinearLayout(this);
        load.setOrientation(LinearLayout.HORIZONTAL);
        load.setGravity(android.view.Gravity.CENTER);
        load.setBackgroundColor(UI_PAPER);
        load.setPadding(dp(18), 0, dp(18), 0);
        load.setOnClickListener(v -> showImportMenu());
        TextView loadText = label("Select image", 7, UI_BLUE, tfBody);
        loadText.setAllCaps(true);
        loadText.setLetterSpacing(0.04f);
        load.addView(loadText);
        // "↗" is in neither Inter nor OPPO Sans, and a tofu box is not an
        // accent: this one is drawn.
        ArrowView loadArrow = new ArrowView(this, UI_BLUE);
        LinearLayout.LayoutParams lalp = new LinearLayout.LayoutParams(dp(14), dp(14));
        lalp.leftMargin = dp(8);
        load.addView(loadArrow, lalp);
        empty.addView(load, new LinearLayout.LayoutParams(-2, dp(34)));

        stage.addView(empty, new android.widget.FrameLayout.LayoutParams(-1, -1));
        emptyState = empty;

        compare = new CompareView(this);
        compare.setVisibility(View.GONE);
        stage.addView(compare, new android.widget.FrameLayout.LayoutParams(-1, -1));

        LinearLayout raw = new LinearLayout(this);
        raw.setOrientation(LinearLayout.VERTICAL);
        raw.setGravity(android.view.Gravity.CENTER);
        raw.setPadding(dp(24), dp(16), dp(24), dp(16));
        raw.setVisibility(View.GONE);
        TextView rawTitle = label("RAW LOADED", 34, UI_PAPER, tfDisplay);
        rawTitle.setLetterSpacing(-0.055f);
        raw.addView(rawTitle, new LinearLayout.LayoutParams(-2, -2));
        rawName = label("", 9, UI_PAPER, tfHan);
        rawName.setAlpha(0.7f);
        rawName.setLineSpacing(dp(4), 1.0f);
        rawName.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams rnlp = new LinearLayout.LayoutParams(-2, -2);
        rnlp.topMargin = dp(13);
        raw.addView(rawName, rnlp);
        stage.addView(raw, new android.widget.FrameLayout.LayoutParams(-1, -1));
        rawState = raw;

        // A render is 1-5 s and a 2 px rule is too quiet to carry it: this is
        // the page's own vocabulary (a word in the middle of the blue field).
        busyLabel = label("Rendering", 20, UI_PAPER, tfDisplay);
        busyLabel.setAllCaps(true);
        busyLabel.setLetterSpacing(0.12f);
        busyLabel.setAlpha(0.9f);
        busyLabel.setGravity(android.view.Gravity.CENTER);
        busyLabel.setVisibility(View.GONE);
        stage.addView(busyLabel, new android.widget.FrameLayout.LayoutParams(-1, -1));

        preview.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));

        // The two jobs the page leaves out (it has no renderer): a 2 px rule
        // that appears in the panel while either one is running.
        // A fixed 2 px slot, so the panel does not jump when a job starts; the
        // two rules share it and are never both meaningful at once.
        android.widget.FrameLayout busy = new android.widget.FrameLayout(this);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(UI_PAPER));
        progressBar.setVisibility(View.GONE);
        busy.addView(progressBar, new android.widget.FrameLayout.LayoutParams(-1, -1));
        exportProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        exportProgressBar.setIndeterminate(true);
        exportProgressBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(UI_PAPER));
        exportProgressBar.setVisibility(View.GONE);
        busy.addView(exportProgressBar, new android.widget.FrameLayout.LayoutParams(-1, -1));
        preview.addView(busy, new LinearLayout.LayoutParams(-1, dp(2)));

        // .preview-foot: 23 px, 6 px, hairline over it.
        preview.addView(hairline(0x2EFFFFFF), new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(android.view.Gravity.CENTER_VERTICAL);
        foot.setPadding(dp(16), 0, dp(16), 0);
        fileLabel = label("No source selected", 7, UI_PAPER, tfBody);
        fileLabel.setAlpha(0.68f);
        fileLabel.setSingleLine(true);
        fileLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        foot.addView(fileLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        statusText = label("Waiting for input", 7, UI_PAPER, tfBody);
        statusText.setAllCaps(true);
        statusText.setSingleLine(true);
        statusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusText.setGravity(android.view.Gravity.END);
        // Never let a full sentence squeeze the file name out of the foot.
        statusText.setMaxWidth(Math.round(getResources().getConfiguration().screenWidthDp
                * 0.62f * getResources().getDisplayMetrics().density));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = dp(14);
        foot.addView(statusText, slp);
        preview.addView(foot, new LinearLayout.LayoutParams(-1, dp(23)));
        lab.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ---- controls: 178 px, padding 0/16/8 ------------------------------
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(16), 0, dp(16), dp(8));
        controls.setBackgroundColor(UI_PAPER);

        LinearLayout controlHead = new LinearLayout(this);
        controlHead.setOrientation(LinearLayout.HORIZONTAL);
        controlHead.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView lookTitle = label("Look settings", 7, UI_INK, tfBody);
        lookTitle.setAllCaps(true);
        lookTitle.setLetterSpacing(0.05f);
        controlHead.addView(lookTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView resetButton = label("Reset", 7, UI_MUTED, tfBody);
        resetButton.setAllCaps(true);
        resetButton.setLetterSpacing(0.04f);
        resetButton.setPaintFlags(resetButton.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        resetButton.setPadding(0, dp(3), 0, dp(3));
        resetButton.setOnClickListener(v -> resetLook());
        controlHead.addView(resetButton);
        controls.addView(controlHead, new LinearLayout.LayoutParams(-1, dp(26)));
        controls.addView(hairline(UI_LINE), new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        final String[] tabNames = {"胶片", "光学", "画幅", "边框"};
        for (int i = 0; i < tabNames.length; ++i) {
            final int index = i;
            android.widget.FrameLayout cell = new android.widget.FrameLayout(this);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView number = label(String.format(java.util.Locale.US, "%02d", i + 1), 6, UI_MUTED, tfBody);
            number.setAlpha(0.65f);
            row.addView(number);
            TextView name = label(tabNames[i], 8, UI_MUTED, tfHan);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
            nlp.leftMargin = dp(6);
            row.addView(name, nlp);
            cell.addView(row, new android.widget.FrameLayout.LayoutParams(-1, -1));
            View bar = new View(this);
            bar.setBackgroundColor(UI_BLUE);
            bar.setVisibility(View.INVISIBLE);
            android.widget.FrameLayout.LayoutParams blp = new android.widget.FrameLayout.LayoutParams(
                    -1, dp(2), android.view.Gravity.BOTTOM);
            blp.rightMargin = dp(14);
            cell.addView(bar, blp);
            cell.setOnClickListener(v -> setTab(index));
            tabs.addView(cell, new LinearLayout.LayoutParams(0, dp(29), 1f));
            tabCells[i] = cell;
            tabBars[i] = bar;
        }
        controls.addView(tabs, new LinearLayout.LayoutParams(-1, dp(29)));
        controls.addView(hairline(UI_LINE), new LinearLayout.LayoutParams(-1, dp(1)));

        android.widget.FrameLayout panelHolder = new android.widget.FrameLayout(this);
        panelHolder.setPadding(0, dp(10), 0, 0);

        // (01) film & paper: two selects, 11 px apart
        LinearLayout filmPanel = new LinearLayout(this);
        filmPanel.setOrientation(LinearLayout.VERTICAL);
        filmSpinner = new Spinner(this);
        filmPanel.addView(selectRow("胶片", filmSpinner), new LinearLayout.LayoutParams(-1, -2));
        printSpinner = new Spinner(this);
        LinearLayout.LayoutParams printLp = new LinearLayout.LayoutParams(-1, -2);
        printLp.topMargin = dp(11);
        filmPanel.addView(selectRow("相纸 / 拷贝片", printSpinner), printLp);
        panelHolder.addView(filmPanel, new android.widget.FrameLayout.LayoutParams(-1, -1));
        panels[0] = filmPanel;

        // (02) optics: four equal rows (grid-template-rows: repeat(4, 1fr))
        opticsPanel = new LinearLayout(this);
        opticsPanel.setOrientation(LinearLayout.VERTICAL);
        // A disabled SeekBar swallows nothing, so a tap on a locked row lands
        // here and can say why it did nothing.
        opticsPanel.setOnClickListener(v -> {
            if (!opticsUnlocked) {
                Toast.makeText(this, session == null ? "先导入一张照片" : "先点 03 渲染一次，再调光学",
                        Toast.LENGTH_SHORT).show();
            }
        });
        exposureBar = rangeRow(opticsPanel, "放大机曝光", 0.25f, 4.0f, printExposure, v -> printExposure = v);
        grainBar = rangeRow(opticsPanel, "颗粒", 0.0f, 2.0f, grainAmount, v -> grainAmount = v);
        halationBar = rangeRow(opticsPanel, "光晕", 0.0f, 4.0f, halationAmount, v -> halationAmount = v);
        glareBar = rangeRow(opticsPanel, "眩光", 0.0f, 12.0f, glareAmount, v -> glareAmount = v);
        panelHolder.addView(opticsPanel, new android.widget.FrameLayout.LayoutParams(-1, -1));
        panels[1] = opticsPanel;

        // (03) physical scale: one select and the reference line
        LinearLayout formatPanel = new LinearLayout(this);
        formatPanel.setOrientation(LinearLayout.VERTICAL);
        formatSpinner = new Spinner(this);
        formatPanel.addView(selectRow("颗粒 / 光晕的物理尺度", formatSpinner), new LinearLayout.LayoutParams(-1, -2));
        final String[] formatNames = {"135 全画幅 · 36mm", "半格 · 24mm", "XPan · 65mm", "645 · 56mm",
                "6×6 · 56mm", "6×7 · 70mm", "6×9 · 84mm", "6×12 · 120mm", "6×17 · 170mm"};
        final float[] formatMm = {36f, 24f, 65f, 56f, 56f, 70f, 84f, 120f, 170f};
        formatSpinner.setAdapter(inkAdapter(java.util.Arrays.asList(formatNames)));
        formatSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                filmFormatMm = formatMm[position];
                if (formatNote != null) formatNote.setText(formatNames[position]);
                applyParams();
                if (uiReady && lastRenderedBitmap != null) renderImage();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView metaLabel = label("Optical reference", 7, UI_MUTED, tfBody);
        metaLabel.setAllCaps(true);
        metaLabel.setLetterSpacing(0.025f);
        meta.addView(metaLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        formatNote = label(formatNames[0], 7, UI_MUTED, tfHan);
        meta.addView(formatNote);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-1, -2);
        metaLp.topMargin = dp(16);
        formatPanel.addView(meta, metaLp);
        panelHolder.addView(formatPanel, new android.widget.FrameLayout.LayoutParams(-1, -1));
        panels[2] = formatPanel;

        // (04) 边框: the rebate and the date back. Both are SHOOT-scope state
        // that the engine has carried all along (RFC-032 / RFC-031) and the
        // phone build had no way to reach.
        LinearLayout edgePanel = new LinearLayout(this);
        edgePanel.setOrientation(LinearLayout.VERTICAL);
        overscanMark = new Square(this, UI_BLUE, 0x66171717);
        edgePanel.addView(toggleRow("片边（胶片边缘）", overscanMark, overscanOn, on -> {
            if (on && edgeIds.isEmpty()) {
                // The engine lays the rebate out for the gate's shape and
                // refuses anything else, so say so instead of failing a render.
                overscanMark.setLit(false);
                Toast.makeText(this, "这张图的长宽比不对应任何片门", Toast.LENGTH_SHORT).show();
                return;
            }
            overscanOn = on;
            onEdgeChanged();
        }), new LinearLayout.LayoutParams(-1, dp(26)));

        edgeSpinner = new Spinner(this);
        LinearLayout.LayoutParams edgeLp = new LinearLayout.LayoutParams(-1, -2);
        edgeLp.topMargin = dp(4);
        edgePanel.addView(selectRow("片边格式", edgeSpinner), edgeLp);
        edgeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (edgeSpinnerBusy || position < 0 || position >= edgeIds.size()) return;
                final String picked = edgeIds.get(position);
                if (picked.equals(overscanFormat)) return;
                overscanFormat = picked;
                // **The rebate format is a parameter, not a render-time flag.**
                // Without this the engine kept the gate the session was opened
                // with, and every choice after the first came back as the first
                // one -- "换边框后好像没反应，都是最开始设置的样式" exactly.
                applyParams();
                if (uiReady && lastRenderedBitmap != null) renderImage();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        dateMark = new Square(this, UI_BLUE, 0x66171717);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(-1, dp(26));
        dateLp.topMargin = dp(4);
        edgePanel.addView(toggleRow("日期水印", dateMark, dateOn, on -> {
            dateOn = on;
            if (dateOn && dateText == null) dateText = imprintDate();
            onEdgeChanged();
        }), dateLp);

        panelHolder.addView(edgePanel, new android.widget.FrameLayout.LayoutParams(-1, -1));
        panels[3] = edgePanel;

        controls.addView(panelHolder, new LinearLayout.LayoutParams(-1, 0, 1f));
        lab.addView(controls, new LinearLayout.LayoutParams(-1, dp(178)));
        app.addView(lab, new LinearLayout.LayoutParams(-1, 0, 1f));

        loadStockCatalog();
        refreshEdgeFormats();
        setTab(0);
        setStep(0);
        // Nothing to adjust yet: there is no frame until one is rendered.
        setOpticsUnlocked(false);
    }

    /** Only the gates whose shape this frame is: the engine's own 5 % rule. */
    private void refreshEdgeFormats() {
        if (edgeSpinner == null) return;
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
        edgeSpinnerBusy = true;
        try {
            edgeSpinner.setAdapter(inkAdapter(edgeLabels));
            final int keep = edgeIds.indexOf(overscanFormat);
            edgeSpinner.setSelection(keep >= 0 ? keep : Math.max(0, best), false);
            if (!edgeIds.isEmpty()) overscanFormat = edgeIds.get(edgeSpinner.getSelectedItemPosition());
        } finally {
            edgeSpinnerBusy = false;
        }
        try {
            DebugSink.text("overscan", "aspect=" + aspect + " gates=" + edgeIds
                    + " chosen=" + overscanFormat);
        } catch (Throwable ignored) { }
    }

    private void onEdgeChanged() {
        applyParams();
        if (uiReady && lastRenderedBitmap != null) renderImage();
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

    /** .select-field: a 7 px muted label, then 24 px of row and a blue ↓. */
    private View selectRow(String labelText, Spinner spinner) {
        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.VERTICAL);
        field.addView(label(labelText, 7, UI_MUTED, tfHan), new LinearLayout.LayoutParams(-1, -2));

        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        spinner.setBackground(null);
        spinner.setGravity(android.view.Gravity.CENTER_VERTICAL);
        spinner.setPadding(0, 0, dp(18), 0);
        wrap.addView(spinner, new android.widget.FrameLayout.LayoutParams(-1, dp(24)));
        TextView arrow = label("↓", 10, UI_BLUE, tfBody);
        android.widget.FrameLayout.LayoutParams arrowLp = new android.widget.FrameLayout.LayoutParams(
                -2, -2, android.view.Gravity.RIGHT | android.view.Gravity.BOTTOM);
        arrowLp.bottomMargin = dp(5);
        arrow.setClickable(false);
        arrow.setFocusable(false);
        wrap.addView(arrow, arrowLp);

        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(-1, dp(26));
        wrapLp.topMargin = dp(3);
        field.addView(wrap, wrapLp);
        return field;
    }

    /** .range-field: an 8 px head with the value in blue, then the 10 px bar. */
    private SeekBar rangeRow(LinearLayout parent, String name, final float lo, final float hi,
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

        SeekBar bar = new SeekBar(this);
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
                if (uiReady && lastRenderedBitmap != null) renderImage();
            }
        });
        row.addView(bar, new LinearLayout.LayoutParams(-1, dp(10)));
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
        bar.setPadding(dp(5), 0, dp(5), 0);
    }

    /** Spinner rows in ink on paper, whatever the platform theme is doing. */
    private ArrayAdapter<String> inkAdapter(final java.util.List<String> items) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            private View row(int position, boolean dropdown) {
                String s = getItem(position);
                TextView t = label(s == null ? "" : s, dropdown ? 13 : 9, UI_INK, tfHan);
                t.setSingleLine(!dropdown);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                if (dropdown) {
                    t.setPadding(dp(16), dp(12), dp(16), dp(12));
                    t.setBackgroundColor(UI_PAPER);
                }
                return t;
            }
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                return row(position, false);
            }
            @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                return row(position, true);
            }
        };
    }

    private void setStep(int active) {
        for (int i = 0; i < stepBars.length; ++i) {
            stepBars[i].setVisibility(i == active ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void setTab(int active) {
        for (int i = 0; i < panels.length; ++i) {
            panels[i].setVisibility(i == active ? View.VISIBLE : View.GONE);
            tabBars[i].setVisibility(i == active ? View.VISIBLE : View.INVISIBLE);
            android.view.ViewGroup row = (android.view.ViewGroup) tabCells[i];
            row = (android.view.ViewGroup) row.getChildAt(0);
            for (int j = 0; j < row.getChildCount(); ++j) {
                ((TextView) row.getChildAt(j)).setTextColor(i == active ? UI_BLUE : UI_MUTED);
            }
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
        SeekBar[] bars = {exposureBar, grainBar, halationBar, glareBar};
        for (SeekBar bar : bars) if (bar != null) bar.setEnabled(unlocked);
    }

    /** Which of the three things on the stage is showing. */
    private void setStage(int mode, String name) {
        if (mode == 0) compare.clear();
        emptyState.setVisibility(mode == 0 ? View.VISIBLE : View.GONE);
        compare.setVisibility(mode == 1 ? View.VISIBLE : View.GONE);
        rawState.setVisibility(mode == 2 ? View.VISIBLE : View.GONE);
        previewIndex.setText(mode == 0 ? "(Empty / 000)" : "(Source / 001)");
        if (name != null && !name.isEmpty()) {
            fileLabel.setText(name);
            rawName.setText(name);
        }
    }

    /** The four cells are the old top bar: 01/02 open a picker, 03 renders, 04 exports. */
    private void onWorkflow(int step) {
        if (step == 0) {
            // A tap on 01 asks where from. The long-press that used to carry
            // this was invisible: nobody found the RAW route by accident, and
            // the users who did were the ones who had read the release note.
            showImportMenu();
        } else if (step == 1) {
            statusText.setText("选择要替换的照片…");
            showImportMenu();
        } else if (step == 2) {
            renderImage();
        } else {
            showExportDialog();
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
        new android.app.AlertDialog.Builder(this)
                .setTitle("导入")
                .setItems(new String[]{
                        "从相册选择（照片）",
                        "从文件管理器选择（RAW / HEIC / 任意文件）"},
                        (d, which) -> {
                            if (which == 0) {
                                pickImage();
                            } else {
                                pickRawFile();
                            }
                        })
                .show();
    }

    /** 04: tiers, not a hard-coded sensor — the sizes come from the session and the device. */
    private void showExportDialog() {
        final String full = (sourceWidth > 0 && sourceHeight > 0)
                ? "全尺寸 " + sourceWidth + "×" + sourceHeight
                : "全尺寸";
        final String fullNote = probe.isLimited()
                ? "（本机上限约 " + String.format(java.util.Locale.US, "%.1f", maxImportPixels() / 1e6) + " MP，可能导出失败）"
                : "（原图）";
        new android.app.AlertDialog.Builder(this)
                .setTitle("导出 · " + probe.describe())
                .setItems(new String[]{
                        "预览 · 长边 2048（最快）",
                        "预览 · 长边 3400",
                        full + fullNote},
                        (d, which) -> exportImage(which == 0 ? "live" : which == 1 ? "preview" : "full"))
                .show();
    }

    /** Reset: the page's four defaults and 135, then one render if there is a frame. */
    private void resetLook() {
        exposureBar.setProgress(Math.round((1.0f - 0.25f) / (4.0f - 0.25f) * 1000f));
        grainBar.setProgress(500);
        halationBar.setProgress(250);
        glareBar.setProgress(250);
        int film = filmIds.indexOf("kodak_portra_400");
        if (film >= 0) filmSpinner.setSelection(film, false);
        int print = printIds.indexOf("kodak_portra_endura");
        if (print >= 0) printSpinner.setSelection(print, false);
        formatSpinner.setSelection(0, false);
        applySessionParams();
        if (lastRenderedBitmap != null) renderImage();
        DebugSink.text("look", "reset");
    }

    /** .load-button's ↗, at 14 px: a stem and a head, nothing else. */
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
        new android.app.AlertDialog.Builder(this)
                .setTitle("这张图超过本机预算")
                .setMessage("原图 " + w + "×" + h + "（" + megapixels(w, h) + " MP），这台机器一次能渲染约 "
                        + String.format(java.util.Locale.US, "%.1f", capPixels / 1e6) + " MP。"
                        + "\n\n按 " + sw + "×" + sh + " 导入一定出得来；"
                        + "按原尺寸可能在本机渲染失败（E-1001）。")
                .setPositiveButton("按 " + sw + "×" + sh + " 导入", (d, x) -> {
                    importBudgetAccepted = true;
                    loadImage(uri);
                })
                .setNeutralButton("按原尺寸", (d, x) -> {
                    importAtNativeSize = true;
                    loadImage(uri);
                })
                .setNegativeButton("取消", null)
                .show();
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
    private void showAbout() {
        final String version = UpdateCheck.versionName(this) + "（" + UpdateCheck.versionCode(this) + "）";
        new android.app.AlertDialog.Builder(this)
                .setTitle("SpektraLab Android " + version)
                .setMessage(probe.describe()
                        + "\n\n引擎：SpektraLab / spektrafilm（GPL-3.0，Andrea Volpato 的光谱胶片模型）"
                        + "\n移植：github.com/SAINTTaiYi/SpektraLab-Android")
                .setPositiveButton("检查更新", (d, w) -> UpdateCheck.check(this, true))
                .setNeutralButton("打开 GitHub", (d, w) -> {
                    try {
                        startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/SAINTTaiYi/SpektraLab-Android/releases/latest")));
                    } catch (Throwable ignored) { }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** Reads assets/resources/stock_catalog.json (generated from the engine's profiles). */
    private void loadStockCatalog() {
        try (InputStream in = getAssets().open("resources/stock_catalog.json")) {
            byte[] buf = new byte[in.available()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            org.json.JSONObject root = new org.json.JSONObject(new String(buf, java.nio.charset.StandardCharsets.UTF_8));
            readStocks(root.optJSONArray("films"), filmIds, filmLabels, filmTypes);
            readStocks(root.optJSONArray("prints"), printIds, printLabels, null);
        } catch (Throwable t) {
            android.util.Log.w("SpektraLab", "stock catalog: " + t);
        }
        if (filmIds.isEmpty()) { filmIds.add("kodak_portra_400"); filmLabels.add("Kodak Portra 400"); }
        if (printIds.isEmpty()) { printIds.add("kodak_portra_endura"); printLabels.add("Kodak Portra Endura"); }

        ArrayAdapter<String> fa = inkAdapter(filmLabels);
        fa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filmSpinner.setAdapter(fa);
        filmSpinner.setSelection(Math.max(0, filmIds.indexOf(filmStock)), false);
        filmSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                final String picked = filmIds.get(position);
                if (picked.equals(filmStock)) return;
                // A slide is projected, not printed: pairing it with a paper is
                // not a look, it is a blown-out frame (measured: mean 196/187/176
                // against Portra Endura). Upstream greys the papers out here; we
                // do not have a film-only view yet, so refuse the pair.
                if (position < filmTypes.size() && "positive".equals(filmTypes.get(position))) {
                    Toast.makeText(MainActivity.this, "反转片（幻灯）不能印到相纸上——本版暂只支持负片", Toast.LENGTH_LONG).show();
                    filmSpinner.setSelection(Math.max(0, filmIds.indexOf(filmStock)), false);
                    return;
                }
                filmStock = picked;
                onStockChanged();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        ArrayAdapter<String> pa = inkAdapter(printLabels);
        pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        printSpinner.setAdapter(pa);
        printSpinner.setSelection(Math.max(0, printIds.indexOf(printStock)), false);
        printSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (printIds.get(position).equals(printStock)) return;
                printStock = printIds.get(position);
                onStockChanged();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private static void readStocks(org.json.JSONArray array, List<String> ids, List<String> labels,
                                   List<String> types) {
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
            else if ("negative".equals(type)) label.append(" · 负片");
            if ("cine".equals(use)) label.append(" · 电影");
            ids.add(id);
            labels.add(label.toString());
            if (types != null) types.add(type);
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
            Toast.makeText(this, "本机没有可用的图片选择器；请点「导入」→「从文件管理器选择」",
                    Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "本机没有可用的文件选择器", Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "没有拿到图片（选择器返回空）。请点「导入」→「从文件管理器选择」再试一次",
                    Toast.LENGTH_LONG).show();
            return;
        }
        loadImage(uri);
    }

    private void loadImage(Uri uri) {
        sourceUri = uri;
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
                    Toast.makeText(this, "[E-1107] 暂不支持 " + ext + " 格式",
                            Toast.LENGTH_LONG).show();
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
            if (!importAtNativeSize && !importBudgetAccepted) {
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

            // Free the previous frame before decoding: a 20 MP pick wants a
            // ~200 MB direct buffer and the old session still holds its planes
            // (this OOM was 241 MB with 201 MB left in the heap).
            if (session != null) { session.close(); session = null; }
            lastRenderedBitmap = null;
            setOpticsUnlocked(false);
            dateText = null;
            setStage(0, null);
            sourceBitmap = null;
            sourcePixels = null;
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
                new android.app.AlertDialog.Builder(this)
                        .setTitle("[E-1107] 这张图本机解不开")
                        .setMessage("多半是 HEIC/HEIF 或 10-bit 的图（三星、iPhone 默认存这种）。\n"
                                + "换「从文件选择」再试一次，读不到的话请先另存为 JPEG。")
                        .setPositiveButton("从文件选择", (d, w) -> pickRawFile())
                        .setNegativeButton("取消", null)
                        .show();
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
            setStep(1);
            
            // Create engine and session
            ensureEngine();
            if (session != null) session.close();
            session = engine.openSession(sourcePixels, sourceWidth, sourceHeight, 3);
            applySessionParams();
            lastImportError = null;
            // The frame on screen stays the *source*, unrendered, until 03 is
            // pressed: that is the flow the page describes, and it keeps a slow
            // render off the import path.
        } catch (Throwable t) {
            lastImportError = describeImportFailure(t);
            Toast.makeText(this, lastImportError, Toast.LENGTH_LONG).show();
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
            uploadCapabilities();
            // The probe is what turns "some devices fail" into a number this
            // build can act on: binding range -> pixels -> the import cap and
            // the export tiers below.
            probe = DeviceProbe.parse(engine.deviceProfile());
            try { DebugSink.text("device-profile", probe.describe() + "\n" + engine.deviceProfile()); }
            catch (Throwable ignored) { }
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

    private int maxImportPixels() {
        try { ensureEngine(); } catch (Throwable ignored) { }
        // Three ceilings, and the smallest wins: what one binding can name,
        // what the phone has free right now, and a hard cap so a device with
        // 24 GB does not accept a 300 MP frame nobody meant to open.
        final long byBinding = probe.maxPixelsByBinding();
        final long byMemory = DeviceProbe.maxPixelsByMemory(availableMemoryBytes());
        // The hard cap is measured, not chosen: 42.7 MP renders here, 44.8 MP
        // takes the device down, and the pool's peak is 6.3 planes (76 B/px).
        final long px = Math.min(Math.min(byBinding, byMemory), DeviceProbe.HARD_PIXEL_CAP);
        return (int) Math.min(Math.max(px, 1_000_000L), Integer.MAX_VALUE);
    }

    /**
     * The camera's own preview, out of the RAW container.
     *
     * Every mainstream raw carries a JPEG rendering of the frame -- Sony,
     * Nikon, Canon's CR2, DNG, Fuji. It is what Lightroom shows before it
     * develops anything, and the only way this app can put a raw on screen
     * without running the film pipeline first: the decode happens on the GPU,
     * so there is no bitmap of the raw itself to show.
     */
    private Bitmap embeddedPreview(String path) {
        final long t0 = System.currentTimeMillis();
        java.io.RandomAccessFile file = null;
        final java.util.List<long[]> spans = new java.util.ArrayList<>();
        try {
            file = new java.io.RandomAccessFile(path, "r");
            final long size = file.length();
            if (size < 8192) return null;
            final byte[] chunk = new byte[1 << 20];
            long start = -1, pos = 0;
            // Every complete JPEG in the file, not just the biggest guess: a
            // container holds a thumbnail, a preview, and sometimes runs of raw
            // data that happen to look like markers.
            while (pos < size) {
                file.seek(pos);
                final int n = file.read(chunk);
                if (n <= 0) break;
                for (int i = 0; i + 1 < n; ++i) {
                    final int a = chunk[i] & 0xFF, b = chunk[i + 1] & 0xFF;
                    if (start < 0) {
                        // FF D8: the span starts one byte before the D8, which
                        // is the previous chunk's last byte when the pair
                        // straddles a boundary (i == 0).
                        if (a == 0xD8 && b == 0xFF) start = pos + i - 1;
                    } else if (a == 0xFF && b == 0xD9) {
                        addPreviewSpan(spans, start, pos + i + 2);
                        start = -1;
                    }
                }
                pos += Math.max(1, n - 1);      // a marker can straddle two chunks
            }
            // A preview whose EOI was cut (or never stored) still decodes.
            if (start >= 0) addPreviewSpan(spans, start, size);
            if (spans.isEmpty()) {
                try { DebugSink.text("raw-preview", "no jpeg markers in " + size + "B"); } catch (Throwable ignored) { }
                return null;
            }
            spans.sort((x, y) -> Long.compare(y[1], x[1]));

            final int tries = Math.min(32, spans.size());
            final byte[] head = new byte[1 << 18];      // 256 KB: every sane header fits
            for (int k = 0; k < tries; ++k) {
                final long at = spans.get(k)[0], len = spans.get(k)[1];
                // A real JPEG declares itself in its first few KB. Raw data
                // that happens to hold the two markers usually does not, and
                // this check costs 64 KB instead of sixteen megabytes.
                try {
                    final int probe = (int) Math.min(len, head.length);
                    file.seek(at);
                    file.readFully(head, 0, probe);
                    final android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(head, 0, probe, bounds);
                    if (bounds.outWidth < 320 || bounds.outHeight < 320) continue;

                    final byte[] jpeg = new byte[(int) len];
                    file.seek(at);
                    file.readFully(jpeg);
                    final Bitmap bmp = decodePreview(jpeg);
                    try {
                        DebugSink.text("raw-preview", (bmp == null ? "reject" : bmp.getWidth() + "x" + bmp.getHeight())
                                + " span#" + k + " at=" + at + " len=" + len + " of " + spans.size()
                                + " head=" + bounds.outWidth + "x" + bounds.outHeight);
                    } catch (Throwable ignored) { }
                    if (bmp != null) return bmp;
                } catch (Throwable t) {
                    try { DebugSink.text("raw-preview", "span#" + k + " threw " + t); } catch (Throwable ignored) { }
                }
            }
            return null;
        } catch (Throwable t) {
            try { DebugSink.text("raw-preview", "failed " + t); } catch (Throwable ignored) { }
            return null;
        } finally {
            if (file != null) try { file.close(); } catch (Throwable ignored) { }
            try { DebugSink.text("raw-preview-timing", (System.currentTimeMillis() - t0) + " ms"); }
            catch (Throwable ignored) { }
        }
    }

    private static void addPreviewSpan(java.util.List<long[]> spans, long start, long end) {
        final long len = end - start;
        // Below 8 KB it is a thumbnail; above 16 MB it is sensor data that
        // happened to contain the markers.
        if (start < 0 || len < 8192 || len > (16L << 20)) return;
        spans.add(new long[]{start, len});
    }

    private static Bitmap decodePreview(byte[] jpeg) {
        try {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
            if (bounds.outWidth < 320 || bounds.outHeight < 320) return null;
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2;
            final BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
        } catch (Throwable t) {
            return null;
        }
    }

    private void loadRawImage(Uri uri) {
        try {
            String filePath = getFilePathFromUri(uri);
            rawFilePath = filePath;
            if (filePath == null) {
                Toast.makeText(this, "无法获取RAW文件路径", Toast.LENGTH_SHORT).show();
                return;
            }
            
            statusText.setText("Decoding RAW…");
            // The JPEG path drops the old frame before it decodes; this one did
            // not, so a RAW import left the *previous* photo on screen until a
            // render replaced it.
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
                    if (session != null) session.close();
                    
                    String params = String.format(
                        "{\"film_stock\":\"%s\",\"print_stock\":\"%s\",\"input_color_space\":\"ProPhoto RGB\",\"input_cctf_decoding\":false,\"output_color_space\":\"sRGB\",\"print_exposure\":%.2f,\"preview_long_edge\":2048}",
                        filmStock, printStock, printExposure
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
                    // a raw -- flat, and dark. The camera's own JPEG is the
                    // fallback for a container the engine will not decode twice.
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
                    boolean fromCamera = false;
                    if (rawView == null) {
                        Bitmap camera = embeddedPreview(filePath);
                        if (camera != null) {
                            int apply = orientationOfPath(filePath);
                            if ((apply == 6 || apply == 8 || apply == 5 || apply == 7)
                                    && camera.getHeight() > camera.getWidth()) {
                                apply = 0;      // the camera already turned it
                            }
                            Bitmap turned = rotateForOrientation(camera, apply);
                            if (turned != camera) { camera.recycle(); camera = turned; }
                            rawView = camera;
                            fromCamera = true;
                        }
                    }
                    final Bitmap preview = rawView;
                    final boolean cameraShot = fromCamera;
                    final boolean hasPreview = preview != null;
                    if (hasPreview) {
                        runOnUiThread(() -> {
                            beforeBitmap = preview;
                            compare.setBefore(preview);
                            setStage(1, getFileName(uri));
                            setStep(1);
                            statusText.setText(cameraShot ? "Embedded preview" : "Raw preview");
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
                        if (shrunk) {
                            // The raw has to be decoded before its size is known,
                            // so this asks afterwards -- but it still asks, and
                            // it offers the escape hatch the long-press used to
                            // hide.
                            final int rawW = src[0], rawH = src[1];
                            new android.app.AlertDialog.Builder(this)
                                    .setTitle("原图超过本机预算")
                                    .setMessage("原图 " + rawW + "×" + rawH + "（" + megapixels(rawW, rawH)
                                            + " MP），这台机器一次能渲染约 "
                                            + String.format(java.util.Locale.US, "%.1f", maxImportPixels() / 1e6)
                                            + " MP，已按 " + w + "×" + h + " 导入。"
                                            + "\n\n按原尺寸导入可能渲染失败（E-1001）。")
                                    .setPositiveButton("就这样", null)
                                    .setNegativeButton("按原尺寸重来", (d, x) -> {
                                        importAtNativeSize = true;
                                        loadRawImage(uri);
                                    })
                                    .show();
                        }
                        statusText.setText("RAW metadata loaded");
                    });
                } catch (Throwable t) {
                    final String said = describeImportFailure(t);
                    lastImportError = said;
                    runOnUiThread(() -> {
                        statusText.setText("RAW加载失败 " + SpkError.shortForm(said));
                        Toast.makeText(this, said, Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        } catch (Throwable t) {
            lastImportError = describeImportFailure(t);
            Toast.makeText(this, "RAW加载失败: " + lastImportError, Toast.LENGTH_LONG).show();
        }
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
        applySessionParams();
        if (session == null || !uiReady) return;
        if (lastRenderedBitmap != null) renderImage();
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
        session.setParams(String.format(
            "{\"output_color_space\":\"sRGB\",\"preview_long_edge\":2048,"
            + "\"print_exposure\":%.2f,\"grain_amount\":%.3f,\"halation_amount\":%.3f,"
            + "\"glare_amount\":%.3f,\"film_format_mm\":%.1f,"
            + "\"overscan_active\":%s,\"overscan_format\":\"%s\",\"overscan_edge_text\":\"%s\","
            + "\"date_imprint_active\":%s,\"date_imprint_text\":\"%s\",\"date_imprint_style\":\"lcd\"}",
            printExposure, grainAmount, halationAmount, glareAmount, filmFormatMm,
            overscanOn ? "true" : "false", overscanFormat, edgeTextFor(filmStock),
            dateOn ? "true" : "false", dateOn ? imprintDate() : ""
        ));
    }

    /** The half that describes the file: once per session, and after a stock change. */
    private void applySessionParams() {
        if (session == null) return;
        session.setParams(String.format(
            "{\"film_stock\":\"%s\",\"print_stock\":\"%s\",\"input_color_space\":\"%s\","
            + "\"input_cctf_decoding\":%s}",
            filmStock, printStock,
            isRawFile ? "ProPhoto RGB" : "sRGB",
            isRawFile ? "false" : "true"));
        applyParams();
    }

    private void renderImage() {
        // Every branch says something. A 7 sp foot is too quiet to carry
        // "nothing happened", and the first thing a user does after import is
        // tap 03 and watch the picture.
        if (session == null) {
            try { DebugSink.text("render", "no session, lastImportError=" + lastImportError); }
            catch (Throwable ignored) { }
            new android.app.AlertDialog.Builder(this)
                    .setTitle("还没有可渲染的图像")
                    .setMessage(lastImportError == null
                            ? "先导入一张照片：点 01 导入，长按 01 可以选 RAW / HEIC 或按原始尺寸导入。"
                            : "上次导入没有完成：\n\n" + lastImportError)
                    .setPositiveButton("导入", (d, w) -> pickImage())
                    .setNegativeButton("关闭", null)
                    .show();
            return;
        }
        if (rendering) {
            // The import renders on its own, so this is no longer only a
            // double-tap: two renders would interleave on the session's single
            // native result buffer.
            try { DebugSink.text("render", "already rendering"); } catch (Throwable ignored) { }
            statusText.setText("Rendering…");
            Toast.makeText(this, "正在渲染，请稍候…", Toast.LENGTH_SHORT).show();
            return;
        }
        rendering = true;
        progressBar.setVisibility(View.VISIBLE);
        busyLabel.setVisibility(View.VISIBLE);
        statusText.setText("Rendering…");
        try { DebugSink.text("render", "begin " + sourceWidth + "x" + sourceHeight); }
        catch (Throwable ignored) { }

        new Thread(() -> {
            try {
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
                    compare.setAfter(bmp);
                    setStage(1, null);
                    setStep(2);
                    lastRenderedBitmap = bmp;
                    setOpticsUnlocked(true);
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
                });
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
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("渲染失败")
                            .setMessage(SpkError.format(msg)
                                    + "\n\n" + sourceWidth + "×" + sourceHeight
                                    + "\n" + probe.describe())
                            .setPositiveButton("知道了", null)
                            .show();
                });
                // E-1001 is "a plane is bigger than one binding may name", and
                // the only cure is a smaller session. The import cap already
                // answers for a normal import, so reaching here means the frame
                // came in past it -- a native-size import, or a build that had
                // no cap. Offering the retry beats printing a code and leaving
                // the user to find the long-press menu.
                if (SpkError.codeOf(msg) == 1001) offerSmallerImport();
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
        long target = probe.maxPixelsByBinding();
        if (target == Long.MAX_VALUE) target = DeviceProbe.DISPATCH_PIXEL_CAP;
        target = (long) (target * 0.75);
        final long byMemory = DeviceProbe.maxPixelsByMemory(availableMemoryBytes());
        if (byMemory > 0) target = Math.min(target, byMemory);
        target = Math.max(1_000_000L, Math.min(target, 100_000_000L));
        final long picking = target;
        runOnUiThread(() -> new android.app.AlertDialog.Builder(this)
                .setTitle("这张图超出本机的单缓冲上限")
                .setMessage("按约 " + String.format("%.1f", picking / 1e6)
                        + " MP 重新导入并渲染？画面会小一些，但能出图。")
                .setPositiveButton("重试", (d, w) -> {
                    pendingCapPixels = (int) picking;
                    loadImage(sourceUri);
                    if (session != null) renderImage();
                })
                .setNegativeButton("取消", null)
                .show());
    }

    private void exportImage() {
        exportImage("preview");
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

    private void exportImage(String tier) {
        if (engine == null || session == null) {
            Toast.makeText(this, "请先导入照片", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            java.io.File tmp = new java.io.File(getCacheDir(), "export_" + System.currentTimeMillis() + ".jpg");
            try {
                runOnUiThread(() -> {
                    statusText.setText("导出中：准备全尺寸渲染（分条）...");
                    exportProgressBar.setVisibility(View.VISIBLE);
                    setStep(3);
                });
                // **No spk_set_params here.** The engine treats the *presence* of
                // film_stock/print_stock in a delta as a stock change and rebuilds
                // the pipeline, which resets session state; the render that
                // follows then comes back as a flat grey. The session already
                // carries the film/print it was opened with.
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
                    info = session.exportJpeg(tmp.getAbsolutePath(), 95, 512, tier);
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
                String fileName = "SpektraLab_" + System.currentTimeMillis() + ".jpg";
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
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
                    DebugSink.text("export", "tier=" + tier + " w=" + w + " h=" + h + " renderMs=" + renderMs
                            + " bytes=" + len);
                    if (len > 0 && len < 30L * 1024 * 1024) {
                        byte[] buf = new byte[(int) len];
                        try (java.io.InputStream in = new java.io.FileInputStream(tmp)) {
                            int off = 0, n;
                            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
                        }
                        DebugSink.bytes("export", "out-" + tier + ".jpg", buf, "image/jpeg");
                    }
                } catch (Throwable ignored) {}
                final long secs = Math.max(1, (System.currentTimeMillis() - t0) / 1000);
                final long fRender = renderMs;
                final long bytes = tmp.exists() ? tmp.length() : 0;
                final String sizeLabel = bytes > 0
                        ? String.format("%.1f MB", bytes / 1048576.0) : "?";
                runOnUiThread(() -> {
                    // Dimensions and file size together: "3.3 MB" alone reads as
                    // a bug when the frame is 16 MP, and the reason is worth
                    // saying out loud rather than guessing at later.
                    Toast.makeText(this, "已保存 " + fw + "x" + fh + " · " + sizeLabel,
                            Toast.LENGTH_LONG).show();
                    statusText.setText("已导出 " + fw + "x" + fh + " · " + sizeLabel
                        + "（" + secs + " 秒，其中渲染 " + (fRender / 1000.0) + " 秒）");
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
                    Toast.makeText(this, SpkError.format(msg), Toast.LENGTH_LONG).show();
                    exportProgressBar.setVisibility(View.GONE);
                    setStep(2);
                });
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
    protected void onDestroy() {
        if (session != null) session.close();
        if (engine != null) engine.close();
        super.onDestroy();
    }
}
