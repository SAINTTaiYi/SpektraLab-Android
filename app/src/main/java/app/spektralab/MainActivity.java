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
import android.widget.TextView;
import android.widget.Toast;
import app.spektralab.engine.SpektraLabEngine;
import app.spektralab.ui.Chrome;
import app.spektralab.ui.FilmNames;
import app.spektralab.ui.ParamSheet;
import app.spektralab.ui.Sheet;
import app.spektralab.ui.SliderRow;
import app.spektralab.ui.Tokens;
import app.spektralab.ui.UiPicker;
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
    private final List<String> filmIds = new ArrayList<>(), filmLabels = new ArrayList<>();
    private final List<String> filmTypes = new ArrayList<>();
    private final List<String> filmTypeLabels = new ArrayList<>();
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
        try { DebugSink.text("app-start", "buildInfo=" + SpektraLabEngine.buildInfo()); } catch (Throwable ignored) {}
        
        buildUi();
        uiReady = true;

        // Silent, and late enough not to compete with the first import. It only
        // speaks when there is a newer build than this one.
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> UpdateCheck.check(this, false), 3000);
    }

    // ---------------------------------------------------------------------
    // 界面：极简 · 暗房
    //   一屏一个动作（选择照片 → 渲染胶片 → 导出），参数点「调色」拉开。
    //   颜色/字号/间距在 ui/Tokens，零件在 ui/Chrome，弹层在 ui/Sheet。
    //   规格：docs/redesign/05-minimal-spec.md
    // ---------------------------------------------------------------------
    // ---- 极简版界面（暗房）----
    // 颜色/字号/间距只有一个来源：app.spektralab.ui.Tokens；零件在 ui/Chrome。
    private View emptyState, rawState;                  // 舞台上的两种非照片状态
    private TextView statusText;                        // 10sp 提示行：只在进度/错误时说话
    private TextView rawName;                           // RAW 状态下的文件名
    private TextView primaryAction;                     // 唯一的描边按钮＝「下一步」
    private TextView actionSwap, actionTune;            // 换图 / 调色
    private View footBar;                               // 底部动作区（空状态整块隐藏）
    private TextView exportHint;                        // 「长按可选尺寸」的标识
    private ProgressBar progressBar, exportProgressBar; // 舞台底边的 1dp 进度线
    private UiPicker filmSpinner, printSpinner, formatSpinner, edgeSpinner;
    private SliderRow exposureRow, grainRow, halationRow, glareRow;
    private ParamSheet panel;                           // 「调节」弹层
    /** 光学滑杆只在有帧可改时可用。 */
    private boolean opticsUnlocked = false;
    /** 导出在飞：主按钮此时不可点。 */
    private boolean exporting = false;

    // RFC-032/031：片边与日期水印（引擎一直支持，旧界面藏在面板里）
    private boolean overscanOn = false;
    private String overscanFormat = "135";
    private boolean dateOn = false;
    private String dateText = null;
    private Square overscanMark, dateMark;

    /** 片边：引擎 overscan 认识的十种片基。 */
    private static final String[] EDGE_LABELS = {
            "135 全画幅", "135 半格", "135 XPan", "645",
            "6×6", "6×7", "6×8", "6×9", "6×12", "6×17"};
    private static final String[] EDGE_IDS = {
            "135", "135_half", "135_xpan", "120_645",
            "120_6x6", "120_6x7", "120_6x8", "120_6x9", "120_6x12", "120_6x17"};
    /** 每个片门的长/短，取自 overscan.cpp 的 kFormats；引擎按 5% 容差拒绝别的形状。 */
    private static final double[] EDGE_RATIOS = {
            36.25 / 24.3, 24.0 / 18.0, 65.0 / 24.0, 56.0 / 41.5,
            1.0, 69.5 / 56.0, 76.0 / 56.0, 84.0 / 56.0, 112.0 / 56.0, 168.0 / 56.0};
    private final java.util.List<String> edgeLabels = new ArrayList<>(), edgeIds = new ArrayList<>();

    /** 画幅：颗粒/光晕的物理尺度。 */
    private static final String[] FORMAT_NAMES = {"135 全画幅 · 36mm", "半格 · 24mm", "XPan · 65mm",
            "645 · 56mm", "6×6 · 56mm", "6×7 · 70mm", "6×9 · 84mm", "6×12 · 120mm", "6×17 · 170mm"};
    private static final float[] FORMAT_MM = {36f, 24f, 65f, 56f, 56f, 70f, 84f, 120f, 170f};

    // =====================================================================
    // 界面：一屏一个动作
    //
    //   空 → [选择照片] ; 有图 → [渲染胶片] ; 渲染完 → [导出]
    //
    // 参数不常驻屏幕：点「调色」拉开 ParamSheet。RAW 导入、全尺寸导出、
    // 关于都走长按，不占界面。规格见 docs/redesign/05-minimal-spec.md。
    // =====================================================================

    private void buildUi() {
        Tokens.loadFonts(this);
        Chrome.darkSystemBars(this);

        final android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(Tokens.ROOT);
        Chrome.applyInsets(root);
        setContentView(root);

        final LinearLayout app = new LinearLayout(this);
        app.setOrientation(LinearLayout.VERTICAL);
        root.addView(app, new android.widget.FrameLayout.LayoutParams(-1, -1));

        app.addView(buildStage(), new LinearLayout.LayoutParams(-1, 0, 1f));
        footBar = buildFoot();
        app.addView(footBar, new LinearLayout.LayoutParams(-1, -2));

        buildPanel();
        loadStockCatalog();
        refreshEdgeFormats();
        setStage(0, null);
        refreshActions();
    }

    /** 舞台：纯黑，照片自身是唯一亮部。 */
    private View buildStage() {
        final android.widget.FrameLayout stage = new android.widget.FrameLayout(this);
        stage.setBackgroundColor(Tokens.STAGE);
        final int side = Tokens.dp(this, Tokens.PAGE_SIDE);
        stage.setPadding(side, side, side, Tokens.dp(this, 12));

        emptyState = buildEmpty();
        stage.addView(emptyState, new android.widget.FrameLayout.LayoutParams(-1, -1));

        compare = new CompareView(this);
        compare.setVisibility(View.GONE);
        stage.addView(compare, new android.widget.FrameLayout.LayoutParams(-1, -1));

        rawState = buildRawState();
        rawState.setVisibility(View.GONE);
        stage.addView(rawState, new android.widget.FrameLayout.LayoutParams(-1, -1));

        // 渲染与导出共用这条 1dp 线，贴在舞台底边。
        final android.widget.FrameLayout busy = new android.widget.FrameLayout(this);
        progressBar = progressLine();
        exportProgressBar = progressLine();
        busy.addView(progressBar, new android.widget.FrameLayout.LayoutParams(
                -1, Math.max(1, Tokens.dp(this, 1))));
        busy.addView(exportProgressBar, new android.widget.FrameLayout.LayoutParams(
                -1, Math.max(1, Tokens.dp(this, 1))));
        final android.widget.FrameLayout.LayoutParams blp = new android.widget.FrameLayout.LayoutParams(
                -1, Math.max(1, Tokens.dp(this, 1)), android.view.Gravity.BOTTOM);
        stage.addView(busy, blp);
        return stage;
    }

    private ProgressBar progressLine() {
        final ProgressBar p = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        p.setIndeterminate(true);
        p.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Tokens.ACCENT));
        p.setVisibility(View.GONE);
        return p;
    }

    /** 空状态：词标 + 一句标题 + 一个按钮。全屏 4 个元素。 */
    private View buildEmpty() {
        final android.widget.FrameLayout box = new android.widget.FrameLayout(this);

        final TextView mark = Chrome.wordmark(this, "SpektraLab");
        mark.setPadding(Tokens.dp(this, 4), Tokens.dp(this, 8), 0, 0);
        mark.setOnLongClickListener(v -> { showAbout(); return true; });
        box.addView(mark, new android.widget.FrameLayout.LayoutParams(-2, -2,
                android.view.Gravity.TOP | android.view.Gravity.START));

        final LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        stack.setPadding(Tokens.dp(this, 4), 0, Tokens.dp(this, 4), 0);

        stack.addView(Chrome.title(this, "选择一张照片开始"),
                new LinearLayout.LayoutParams(-1, -2));

        final TextView sub = Chrome.hint(this, "RAW · JPEG · HEIC", false);
        sub.setGravity(android.view.Gravity.CENTER);
        final LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = Tokens.dp(this, 10);
        stack.addView(sub, slp);

        final TextView pick = Chrome.wideButton(this, "选择照片");
        pick.setOnClickListener(v -> pickImage());
        pick.setOnLongClickListener(v -> { showImportMenu(); return true; });
        final LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                -1, Tokens.dp(this, 52));
        plp.topMargin = Tokens.dp(this, 30);
        stack.addView(pick, plp);

        final TextView tip = Chrome.hint(this, "长按「选择照片」可导入 RAW", false);
        tip.setGravity(android.view.Gravity.CENTER);
        final LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = Tokens.dp(this, 14);
        stack.addView(tip, tlp);

        // 高度必须 wrap_content，否则 gravity=CENTER 不生效（内容会从顶部开始堆）
        stack.setTranslationY(-Tokens.dp(this, 26));
        box.addView(stack, new android.widget.FrameLayout.LayoutParams(
                -1, -2, android.view.Gravity.CENTER));
        return box;
    }

    /** RAW 已解码、还没渲染：告诉用户下一步只有一个按钮。 */
    private View buildRawState() {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER);

        box.addView(Chrome.title(this, "RAW LOADED"), new LinearLayout.LayoutParams(-1, -2));

        rawName = Tokens.text(this, "", Tokens.SP_BODY, Tokens.TEXT_2, Tokens.han(), 0f, false);
        rawName.setGravity(android.view.Gravity.CENTER);
        rawName.setSingleLine(true);
        rawName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        final LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = Tokens.dp(this, 12);
        box.addView(rawName, nlp);

        final TextView tip = Chrome.hint(this, "点「渲染胶片」得到胶片图", false);
        tip.setGravity(android.view.Gravity.CENTER);
        final LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = Tokens.dp(this, 10);
        box.addView(tip, tlp);
        return box;
    }

    /** 底部：一行提示 + 换图/调色 + 主按钮。 */
    private View buildFoot() {
        final LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.VERTICAL);
        foot.setPadding(Tokens.dp(this, Tokens.GUTTER), 0,
                Tokens.dp(this, Tokens.GUTTER), Tokens.dp(this, 14));

        statusText = Chrome.hint(this, "", false);
        statusText.setMinHeight(Tokens.dp(this, 20));
        statusText.setVisibility(View.INVISIBLE);
        foot.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);

        actionSwap = Chrome.textAction(this, "换图");
        actionSwap.setOnClickListener(v -> pickImage());
        actionSwap.setOnLongClickListener(v -> { showImportMenu(); return true; });
        bar.addView(actionSwap, new LinearLayout.LayoutParams(
                -2, Tokens.dp(this, Tokens.TOUCH_MIN)));

        actionTune = Chrome.textAction(this, "调色");
        actionTune.setOnClickListener(v -> { if (panel != null) panel.show(); });
        final LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                -2, Tokens.dp(this, Tokens.TOUCH_MIN));
        alp.leftMargin = Tokens.dp(this, 16);
        bar.addView(actionTune, alp);

        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        primaryAction = Chrome.primaryButton(this, "选择照片", true);
        primaryAction.setOnClickListener(v -> onPrimary());
        primaryAction.setOnLongClickListener(v -> { onPrimaryLong(); return true; });
        bar.addView(primaryAction, new LinearLayout.LayoutParams(
                -2, Tokens.dp(this, Tokens.BTN_HEIGHT_SM)));

        foot.addView(bar, new LinearLayout.LayoutParams(-1, Tokens.dp(this, Tokens.TOUCH_MIN)));

        // 「长按导出」的可用性标识：只在有图已渲染时出现，不占常态空间
        exportHint = Chrome.hint(this, "长按「导出」可选尺寸", false);
        exportHint.setGravity(android.view.Gravity.END);
        exportHint.setPadding(0, Tokens.dp(this, 2), 0, 0);
        foot.addView(exportHint, new LinearLayout.LayoutParams(-1, -2));
        return foot;
    }

    /** 主按钮＝下一步，用户不需要判断该点哪里。 */
    private void onPrimary() {
        if (rendering || exporting) return;
        if (lastRenderedBitmap != null) exportImage("live");
        else if (session != null) renderImage();
        else pickImage();
    }

    /** 长按：RAW 导入 / 全尺寸导出 / 导入方式，都不占界面。 */
    private void onPrimaryLong() {
        if (lastRenderedBitmap != null) showExportDialog();
        else if (session == null) pickRawFile();
        else showImportMenu();
    }

    private void refreshActions() {
        if (primaryAction == null) return;
        final boolean hasSession = session != null;
        final boolean rendered = lastRenderedBitmap != null;
        if (rendering) {
            Chrome.setPrimary(primaryAction, "渲染中…", false);
        } else if (exporting) {
            Chrome.setPrimary(primaryAction, "导出中…", false);
        } else if (rendered) {
            Chrome.setPrimary(primaryAction, "导出", true);
        } else if (hasSession) {
            Chrome.setPrimary(primaryAction, "渲染胶片", true);
        } else {
            Chrome.setPrimary(primaryAction, "选择照片", true);
        }
        actionSwap.setVisibility(rendered || hasSession ? View.VISIBLE : View.GONE);
        actionTune.setVisibility(rendered ? View.VISIBLE : View.GONE);

        // 空状态只留中间那一个按钮：底部动作区整块收起，避免两个「选择照片」同时出现
        final boolean barNeeded = hasSession || rendered || rendering || exporting;
        if (footBar != null) footBar.setVisibility(barNeeded ? View.VISIBLE : View.GONE);
        if (exportHint != null) {
            // 动作栏出现后高度固定：用 INVISIBLE 占位，避免"渲染完→导出"时底部跳一下
            if (!barNeeded) {
                exportHint.setVisibility(View.GONE);
            } else {
                exportHint.setVisibility(rendered && !rendering && !exporting
                        ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }

    /** 一句提示：空字符串＝不显示（高度仍占位，布局不跳）。 */
    private void say(CharSequence s, boolean danger) {
        if (statusText == null) return;
        statusText.setText(s == null ? "" : s);
        statusText.setTextColor(danger ? Tokens.DANGER : Tokens.TEXT_3);
        statusText.setVisibility((s == null || s.length() == 0) ? View.INVISIBLE : View.VISIBLE);
    }

    // ------------------------------------------------------------ 参数弹层

    private void buildPanel() {
        panel = new ParamSheet(this);

        // (0) 胶片 & 相纸：中文名，型号保留原文
        final LinearLayout films = new LinearLayout(this);
        films.setOrientation(LinearLayout.VERTICAL);
        filmSpinner = UiPicker.field(this, "胶片");
        printSpinner = UiPicker.field(this, "相纸");
        films.addView(filmSpinner.view(), new LinearLayout.LayoutParams(-1, -2));
        films.addView(printSpinner.view(), new LinearLayout.LayoutParams(-1, -2));
        panel.setGroup(0, films);

        // (1) 光学：三条常用，眩光收在「更多参数」
        final LinearLayout optics = new LinearLayout(this);
        optics.setOrientation(LinearLayout.VERTICAL);
        exposureRow = new SliderRow(this, "明暗", 0.25f, 4.0f, printExposure,
                v -> printExposure = v, v -> { applyParams(); rerenderIfPossible(); });
        grainRow = new SliderRow(this, "颗粒", 0.0f, 2.0f, grainAmount,
                v -> grainAmount = v, v -> { applyParams(); rerenderIfPossible(); });
        halationRow = new SliderRow(this, "光晕", 0.0f, 4.0f, halationAmount,
                v -> halationAmount = v, v -> { applyParams(); rerenderIfPossible(); });
        glareRow = new SliderRow(this, "眩光", 0.0f, 12.0f, glareAmount,
                v -> glareAmount = v, v -> { applyParams(); rerenderIfPossible(); });
        optics.addView(exposureRow, new LinearLayout.LayoutParams(-1, -2));
        optics.addView(grainRow, new LinearLayout.LayoutParams(-1, -2));
        optics.addView(halationRow, new LinearLayout.LayoutParams(-1, -2));
        optics.addView(glareRow, new LinearLayout.LayoutParams(-1, -2));
        glareRow.setVisibility(View.GONE);

        final LinearLayout more = new LinearLayout(this);
        more.setOrientation(LinearLayout.HORIZONTAL);
        more.setGravity(android.view.Gravity.CENTER_VERTICAL);
        more.setPadding(Tokens.dp(this, Tokens.GUTTER), 0, Tokens.dp(this, Tokens.GUTTER), 0);
        final TextView moreText = Tokens.text(this, "更多参数", 10f, Tokens.TEXT_3,
                Tokens.han(), 0f, false);
        more.addView(moreText, new LinearLayout.LayoutParams(0, -2, 1f));
        final View moreChev = new Chrome.Glyph(this, Tokens.TEXT_3, Chrome.Glyph.CHEVRON_DOWN);
        more.addView(moreChev, new LinearLayout.LayoutParams(
                Tokens.dp(this, 12), Tokens.dp(this, 12)));
        more.setOnClickListener(v -> {
            final boolean show = glareRow.getVisibility() != View.VISIBLE;
            glareRow.setVisibility(show ? View.VISIBLE : View.GONE);
            moreChev.setRotation(show ? 180f : 0f);
        });
        optics.addView(more, new LinearLayout.LayoutParams(-1, Tokens.dp(this, 48)));
        optics.addView(ParamSheet.hint(this, "数值可点：直接输入，或用 ± 按 0.01 微调"),
                new LinearLayout.LayoutParams(-1, -2));
        panel.setGroup(1, optics);

        // (2) 画幅（颗粒/光晕的物理尺度）
        final LinearLayout scale = new LinearLayout(this);
        scale.setOrientation(LinearLayout.VERTICAL);
        formatSpinner = UiPicker.field(this, "物理尺度");
        formatSpinner.setItems(java.util.Arrays.asList(FORMAT_NAMES));
        formatSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                 int position, long id) {
                if (position < 0 || position >= FORMAT_MM.length) return;
                filmFormatMm = FORMAT_MM[position];
                applyParams();
                rerenderIfPossible();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        scale.addView(formatSpinner.view(), new LinearLayout.LayoutParams(-1, -2));
        final TextView note = Chrome.hint(this, "135 全画幅按 36mm 计算", false);
        note.setPadding(Tokens.dp(this, Tokens.GUTTER), Tokens.dp(this, 12),
                Tokens.dp(this, Tokens.GUTTER), Tokens.dp(this, 10));
        scale.addView(note, new LinearLayout.LayoutParams(-1, -2));
        panel.setGroup(2, scale);

        // (3) 更多：片边 / 片边格式 / 日期水印
        final LinearLayout extra = new LinearLayout(this);
        extra.setOrientation(LinearLayout.VERTICAL);

        overscanMark = new Square(this, Tokens.ACCENT, Tokens.HAIR_STRONG);
        final LinearLayout overscanToggle = toggleRow("片边（胶片边缘）", overscanMark,
                overscanOn, on -> {
                    if (on && edgeIds.isEmpty()) {
                        overscanMark.setLit(false);
                        say("这张图的长宽比不对应任何片门", true);
                        return;
                    }
                    overscanOn = on;
                    onEdgeChanged();
                });
        extra.addView(overscanToggle, new LinearLayout.LayoutParams(
                -1, Tokens.dp(this, 48)));

        edgeSpinner = UiPicker.field(this, "片边格式");
        edgeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                 int position, long id) {
                if (position < 0 || position >= edgeIds.size()) return;
                overscanFormat = edgeIds.get(position);
                rerenderIfPossible();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        extra.addView(edgeSpinner.view(), new LinearLayout.LayoutParams(-1, -2));

        dateMark = new Square(this, Tokens.ACCENT, Tokens.HAIR_STRONG);
        final LinearLayout dateToggle = toggleRow("日期水印", dateMark, dateOn, on -> {
            dateOn = on;
            if (dateOn && dateText == null) dateText = imprintDate();
            onEdgeChanged();
        });
        extra.addView(dateToggle, new LinearLayout.LayoutParams(-1, Tokens.dp(this, 48)));
        panel.setGroup(3, extra);

        panel.setOnReset(this::resetLook);
        panel.setOnAbout(this::showAbout);
        panel.setTab(1);
    }

    private void rerenderIfPossible() {
        if (uiReady && lastRenderedBitmap != null) renderImage();
    }

    /** 一行开关：左标签 12sp，右 18dp 方块，点行即切。 */
    private LinearLayout toggleRow(String text, Square mark, boolean initial,
                                   java.util.function.Consumer<Boolean> onChange) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(Tokens.dp(this, Tokens.GUTTER), 0, Tokens.dp(this, Tokens.GUTTER), 0);

        final TextView label = Tokens.text(this, text, Tokens.SP_BODY, Tokens.TEXT_2,
                Tokens.han(), 0f, false);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

        mark.setLit(initial);
        row.addView(mark, new LinearLayout.LayoutParams(
                Tokens.dp(this, 18), Tokens.dp(this, 18)));

        final boolean[] state = {initial};
        row.setOnClickListener(v -> {
            state[0] = !state[0];
            mark.setLit(state[0]);
            onChange.accept(state[0]);
        });
        return row;
    }

    /** 舞台上显示的是哪一层：空 / 照片（对比）/ RAW 名。 */
    private void setStage(int mode, String name) {
        if (emptyState == null) return;
        if (mode == 0) compare.clear();
        emptyState.setVisibility(mode == 0 ? View.VISIBLE : View.GONE);
        compare.setVisibility(mode == 1 ? View.VISIBLE : View.GONE);
        rawState.setVisibility(mode == 2 ? View.VISIBLE : View.GONE);
        if (name != null && !name.isEmpty() && rawName != null) rawName.setText(name);
    }

    /** 光学滑杆只在有帧可改时可用。 */
    private void setOpticsUnlocked(boolean unlocked) {
        opticsUnlocked = unlocked;
        final SliderRow[] rows = {exposureRow, grainRow, halationRow, glareRow};
        for (SliderRow r : rows) if (r != null) r.setRowEnabled(unlocked);
    }

    /** 恢复默认：出厂配方 + 四条滑杆回中位。 */
    private void resetLook() {
        printExposure = 1.0f;
        grainAmount = 1.0f;
        halationAmount = 1.0f;
        glareAmount = 3.0f;
        if (exposureRow != null) exposureRow.setValue(printExposure);
        if (grainRow != null) grainRow.setValue(grainAmount);
        if (halationRow != null) halationRow.setValue(halationAmount);
        if (glareRow != null) glareRow.setValue(glareAmount);

        filmFormatMm = 36f;
        if (formatSpinner != null) formatSpinner.setSelection(0, false);

        final int film = filmIds.indexOf("kodak_portra_400");
        if (film >= 0 && filmSpinner != null) {
            filmStock = "kodak_portra_400";
            filmSpinner.setSelection(film, false);
        }
        final int print = printIds.indexOf("kodak_portra_endura");
        if (print >= 0 && printSpinner != null) {
            printStock = "kodak_portra_endura";
            printSpinner.setSelection(print, false);
        }

        applySessionParams();
        if (lastRenderedBitmap != null) renderImage();
        try { DebugSink.text("look", "reset"); } catch (Throwable ignored) { }
    }

    /** 只列出这张图真正对应的片门：引擎自己的 5% 规则。 */
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
        edgeSpinner.setItems(edgeLabels);
        final int keep = edgeIds.indexOf(overscanFormat);
        edgeSpinner.setSelection(keep >= 0 ? keep : Math.max(0, best), false);
        if (!edgeIds.isEmpty()) overscanFormat = edgeIds.get(edgeSpinner.getSelectedItemPosition());
        try {
            DebugSink.text("overscan", "aspect=" + aspect + " gates=" + edgeIds
                    + " chosen=" + overscanFormat);
        } catch (Throwable ignored) { }
    }

    private void onEdgeChanged() {
        applyParams();
        rerenderIfPossible();
    }

    /** 制造商印在片边上的字：这片胶卷自己的名字。 */
    private String edgeTextFor(String stock) {
        final int i = filmIds.indexOf(stock);
        final String name = (i >= 0 && i < filmLabels.size()) ? filmLabels.get(i) : "KODAK";
        return name.toUpperCase(java.util.Locale.US);
    }

    /** "'26 10 31"：优先用画面自己的 EXIF 拍摄日期，没有就用今天。 */
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

    /** 18dp 方块：off 是 1px 描边，on 是琥珀填充 + 深色勾。 */
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
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1.5f, stroke * 1.5f));
                paint.setColor(android.graphics.Color.TRANSPARENT);
                paint.setColor(Tokens.TEXT_ON_ACCENT);
                canvas.drawLine(w * 0.24f, h * 0.52f, w * 0.44f, h * 0.72f, paint);
                canvas.drawLine(w * 0.44f, h * 0.72f, w * 0.78f, h * 0.30f, paint);
            } else {
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setColor(offColor);
                canvas.drawRect(inset, inset, w - inset, h - inset, paint);
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




    /** Long-press on 01: the three import routes, unchanged from 1.2.5. */
    private void showImportMenu() {
        Sheet.list(this, "导入",
                java.util.Arrays.asList("从相册选择（照片 / JPEG）",
                        "从文件选择（RAW / HEIC / 任意格式）",
                        "按原始尺寸导入（可能失败）"),
                0, which -> {
                    if (which == 0) {
                        pickImage();
                    } else if (which == 1) {
                        pickRawFile();
                    } else {
                        confirmNativeImport();
                    }
                });
    }

    /** 04: tiers, not a hard-coded sensor — the sizes come from the session and the device. */
    private void showExportDialog() {
        final String full = (sourceWidth > 0 && sourceHeight > 0)
                ? "全尺寸 " + sourceWidth + "×" + sourceHeight
                : "全尺寸";
        final String fullNote = probe.isLimited()
                ? "（本机上限约 " + String.format(java.util.Locale.US, "%.1f", maxImportPixels() / 1e6) + " MP，可能失败）"
                : "（原图）";
        Sheet.list(this, "导出 · " + probe.describe(),
                java.util.Arrays.asList(
                        "长边 2048（最快）",
                        "长边 3400",
                        full + fullNote),
                0, which -> exportImage(which == 0 ? "live" : which == 1 ? "preview" : "full"));
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
            paint.setColor(0x66D9A55B);
            canvas.drawRect(x - 0.5f, 0f, x + 0.5f, getHeight(), paint);
            paint.setColor(Tokens.STAGE);
            canvas.drawRect(x - r, cy - r, x + r, cy + r, paint);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, density));
            paint.setColor(Tokens.ACCENT);
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

    /** Full-size import is kept, and honest about itself. */
    private void confirmNativeImport() {
        final String overDispatch =
                "\n\n另：超过 1677 万像素（约 5000×3350）时，部分驱动会丢掉画面块，"
                + "可能出现顶部未渲染的区域。";
        final String head = probe.isLimited()
                ? "本机可用内存与 GPU 上限合计约 "
                  + String.format(java.util.Locale.US, "%.1f", maxImportPixels() / 1e6)
                  + " MP，超过这个尺寸的图很可能渲染失败（错误码 E-1001）。"
                : "按原始尺寸导入（不做任何缩放）。";
        Sheet.confirm(this, "按原始尺寸导入", head + overDispatch, "继续", () -> {
            importAtNativeSize = true;
            pickRawFile();
        });
    }

    /** Long-press on the title: version, update check, and where the code lives. */
    private void showAbout() {
        final String version = UpdateCheck.versionName(this) + "（" + UpdateCheck.versionCode(this) + "）";
        final String body = probe.describe()
                + "\n\n引擎：SpektraLab / spektrafilm（GPL-3.0，Andrea Volpato 的光谱胶片模型）"
                + "\n移植：github.com/SAINTTaiYi/SpektraLab-Android";
        Sheet.listWithBody(this, "SpektraLab " + version, body,
                java.util.Arrays.asList("检查更新", "打开 GitHub"),
                which -> {
                    if (which == 0) {
                        UpdateCheck.check(this, true);
                    } else {
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
            readStocks(root.optJSONArray("films"), filmIds, filmLabels, filmTypes, filmTypeLabels, false);
            readStocks(root.optJSONArray("prints"), printIds, printLabels, null, null, true);
        } catch (Throwable t) {
            android.util.Log.w("SpektraLab", "stock catalog: " + t);
        }
        if (filmIds.isEmpty()) { filmIds.add("kodak_portra_400"); filmLabels.add("Kodak Portra 400"); }
        if (printIds.isEmpty()) { printIds.add("kodak_portra_endura"); printLabels.add("Kodak Portra Endura"); }

        filmSpinner.setItems(filmLabels, filmTypeLabels);
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

        printSpinner.setItems(printLabels, null);
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
                                   List<String> types, List<String> typeLabels, boolean paper) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            org.json.JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id");
            if (id.isEmpty()) continue;
            String name = o.optString("name", id);
            String type = o.optString("type", "");
            String use = o.optString("use", "");
            String stage = o.optString("stage", "");
            ids.add(id);
            // 列表显示中文名；型号保留原文（FilmNames 里有译名表 + 兜底推导）
            labels.add(paper ? FilmNames.paper(id, name) : FilmNames.film(id, name));
            if (types != null) types.add(type);
            if (typeLabels != null) typeLabels.add(FilmNames.typeLabel(stage, type, use));
        }
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
            Toast.makeText(this, "本机没有可用的图片选择器；请长按「导入」→「从文件选择」",
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
            Toast.makeText(this, "没有拿到图片（选择器返回空）。请长按「导入」→「从文件选择」再试一次",
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
                Sheet.message(this, "[E-1107] 这张图本机解不开",
                        "多半是 HEIC/HEIF 或 10-bit 的图（三星、iPhone 默认存这种）。\n"
                                + "换「从文件选择」再试一次，读不到的话请先另存为 JPEG。",
                        "从文件选择", this::pickRawFile);
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
            say("点「渲染胶片」生成效果", false);
            setStage(1, getFileName(uri));
            // Create engine and session
            ensureEngine();
            if (session != null) session.close();
            session = engine.openSession(sourcePixels, sourceWidth, sourceHeight, 3);
            applySessionParams();
            lastImportError = null;
            refreshActions();
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
     * Ceilings that hold on every device we have measured.
     *
     * 16,490,040 px is the largest frame this driver rendered without losing
     * blocks (16,983,680 lost the top ~200 rows); 65 535 workgroups x 256
     * threads is 16,776,960, and the failure appears just under it. 4700 is
     * the long edge we validated end to end, so a phone with a 2 GiB binding
     * limit still imports what we actually tested rather than the maximum the
     * hardware would accept.
     */
    private static final int IMPORT_PIXEL_FALLBACK = 16490040;
    private static final int IMPORT_LONG_EDGE = 4700;

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
        final long px = Math.min(Math.min(byBinding, byMemory), 100_000_000L);
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
            
            say("正在解码 RAW", false);
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
                        try { DebugSink.text("raw-view", rawView == null ? "export: empty"
                                : "export " + rawView.getWidth() + "x" + rawView.getHeight()); }
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
                            say(cameraShot ? "相机预览已载入" : "RAW 预览就绪", false);
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
                        if (shrunk) {
                            Toast.makeText(this, "原图超出本机 GPU 单缓冲上限，已按 " + w + "x" + h + " 导入",
                                    Toast.LENGTH_LONG).show();
                        }
                        say("RAW 已载入，点「渲染胶片」", false);
                        refreshActions();
                    });
                } catch (Throwable t) {
                    final String said = describeImportFailure(t);
                    lastImportError = said;
                    runOnUiThread(() -> {
                        say("RAW 载入失败 " + SpkError.shortForm(said), true);
                        refreshActions();
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
        return SpkError.format(t.getClass().getSimpleName() + ": " + t.getMessage());
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
            Sheet.message(this, "还没有可渲染的图像",
                    lastImportError == null
                            ? "先导入一张照片：点「选择照片」，长按它可以选 RAW / 按原始尺寸导入。"
                            : "上次导入没有完成：\n\n" + lastImportError,
                    "选择照片", this::pickImage);
            return;
        }
        if (rendering) {
            // The import renders on its own, so this is no longer only a
            // double-tap: two renders would interleave on the session's single
            // native result buffer.
            try { DebugSink.text("render", "already rendering"); } catch (Throwable ignored) { }
            say("渲染中", false);
            Toast.makeText(this, "正在渲染，请稍候…", Toast.LENGTH_SHORT).show();
            return;
        }
        rendering = true;
        refreshActions();
        progressBar.setVisibility(View.VISIBLE);
        refreshActions();
        say("渲染中", false);
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

                final int fw = w, fh = h;
                try {
                    long hsh = 1469598103934665603L;
                    for (int i = 0; i < pixels.length; i += 7) { hsh ^= pixels[i]; hsh *= 1099511628211L; }
                    long t0 = System.currentTimeMillis();
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, bo);
                    DebugSink.text("preview", "w=" + w + " h=" + h + " hash=" + Long.toHexString(hsh)
                            + " copy=" + copied + " jpegBytes=" + bo.size());
                    DebugSink.bytes("preview", "p.jpg", bo.toByteArray(), "image/jpeg");
                    DebugSink.text("preview-timing", "jpeg_ms=" + (System.currentTimeMillis() - t0));
                } catch (Throwable ignored) {}
                runOnUiThread(() -> {
                    compare.setAfter(bmp);
                    setStage(1, null);
                    lastRenderedBitmap = bmp;
                    setOpticsUnlocked(true);
                    final int[] bands = blackBands(bmp);
                    say("", false);
                    try {
                        DebugSink.text("bands", "render " + fw + "x" + fh
                                + " black rows top=" + bands[0] + " bottom=" + bands[1]
                                + " source=" + sourceWidth + "x" + sourceHeight);
                    } catch (Throwable ignored) { }
                    progressBar.setVisibility(View.GONE);
                    rendering = false;
                    refreshActions();
                });
            } catch (Throwable e) {
                try { session.renderEnd(); } catch (Throwable ignored) {}
                final String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                runOnUiThread(() -> {
                    say("渲染失败 " + SpkError.shortForm(msg), true);
                    progressBar.setVisibility(View.GONE);
                    rendering = false;
                    refreshActions();
                    // The foot is one 7 sp line: a failure has to be readable,
                    // and it has to arrive with enough context to be reported.
                    Sheet.message(this, "渲染失败",
                            SpkError.format(msg) + "\n\n" + sourceWidth + "×" + sourceHeight
                                    + "\n" + probe.describe(),
                            "知道了", null);
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
        runOnUiThread(() -> Sheet.confirm(this, "这张图超出本机的单缓冲上限",
                "按约 " + String.format(java.util.Locale.US, "%.1f", picking / 1e6)
                        + " MP 重新导入并渲染？画面会小一些，但能出图。", "重试", () -> {
                    pendingCapPixels = (int) picking;
                    loadImage(sourceUri);
                    if (session != null) renderImage();
                }));
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
                    say("导出中：准备渲染", false);
                    exporting = true;
                    refreshActions();
                    exportProgressBar.setVisibility(View.VISIBLE);
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
                        say("导出中：渲染 " + el + " 秒", false);
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
                runOnUiThread(() -> say("导出中：写入相册 " + fw + "x" + fh, false));
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
                    say("已导出 " + fw + "x" + fh + " · " + sizeLabel, false);
                    exportProgressBar.setVisibility(View.GONE);
                    exporting = false;
                    refreshActions();
                });
            } catch (Throwable e) {
                final String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                try { DebugSink.text("export-error", "tier=" + tier + " " + msg); } catch (Throwable ignored) {}
                uploadExportProgress(tmp);
                uploadVkLog();
                runOnUiThread(() -> {
                    say("导出失败 " + SpkError.shortForm(msg), true);
                    Sheet.message(this, "导出失败", SpkError.format(msg), "知道了", null);
                    exportProgressBar.setVisibility(View.GONE);
                    exporting = false;
                    refreshActions();
                });
            } finally {
                if (tmp.exists()) tmp.delete();
            }
        }).start();
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
