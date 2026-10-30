package app.spektralab;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
    private SpektraLabEngine.Session session;
    private boolean isRawFile = false;
    private String rawFilePath = null;
    private Bitmap sourceBitmap;
    private Uri sourceUri;
    private Bitmap lastRenderedBitmap;
    private ByteBuffer sourcePixels;
    private int sourceWidth, sourceHeight;
    private ImageView imageView;
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
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(0xFF0A0A0A);
        layout.setFitsSystemWindows(true);
        layout.setPadding(0, getStatusBarHeight(), 0, 0);
        
        // Top bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(16, 16, 16, 16);
        topBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        
        TextView title = new TextView(this);
        title.setText("SpektraLab");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setSingleLine(true);
        topBar.addView(title, new LinearLayout.LayoutParams(0, -2, 0.45f));
        // No menu bar on a phone, so the title carries About and the manual
        // update check: long-press it.
        title.setOnLongClickListener(v -> { showAbout(); return true; });
        
        Button importBtn = new Button(this);
        importBtn.setText("导入");
        importBtn.setTextSize(13);
        importBtn.setBackgroundResource(android.R.drawable.btn_default);
        importBtn.setTextColor(0xFF80D0FF);
        importBtn.setOnClickListener(v -> pickImage());
        // Full-size import is kept, and honest about itself: on a device whose
        // binding range is small this can only fail, and it says so before it
        // tries.
        importBtn.setOnLongClickListener(v -> {
            final String overDispatch =
                    "\n\n另：超过 1677 万像素（约 5000×3350）时，部分驱动会丢掉画面块，"
                    + "可能出现顶部未渲染的区域。";
            final String warning = (probe.isLimited()
                    ? "本机 GPU 单缓冲上限约 " + String.format("%.1f", probe.maxMegapixels())
                      + " MP，超过这个尺寸的图很可能渲染失败（错误码 E-1001）。"
                    : "按原始尺寸导入（不做任何缩放）。") + overDispatch
                    + "\n\n仍要按原始尺寸导入吗？";
            new android.app.AlertDialog.Builder(this)
                    .setTitle("按原始尺寸导入")
                    .setMessage(warning)
                    .setPositiveButton("继续", (d, w2) -> { importAtNativeSize = true; pickImage(); })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });
        topBar.addView(importBtn, new LinearLayout.LayoutParams(0, -2, 0.28f));

        Button reimportBtn = new Button(this);
        reimportBtn.setText("换图");
        reimportBtn.setTextSize(13);
        reimportBtn.setBackgroundResource(android.R.drawable.btn_default);
        reimportBtn.setTextColor(0xFFFFC080);
        reimportBtn.setOnClickListener(v -> {
            // A fresh pick replaces the frame outright: drop the shown bitmap so
            // nothing of the previous photo lingers while the new one decodes.
            lastRenderedBitmap = null;
            imageView.setImageDrawable(null);
            statusText.setText("选择要替换的照片...");
            pickImage();
        });
        topBar.addView(reimportBtn, new LinearLayout.LayoutParams(0, -2, 0.30f));
        
        Button renderBtn = new Button(this);
        renderBtn.setText("渲染");
        renderBtn.setTextSize(13);
        renderBtn.setBackgroundResource(android.R.drawable.btn_default);
        renderBtn.setTextColor(0xFF80FF80);
        topBar.addView(renderBtn);
        
        Button exportBtn = new Button(this);
        exportBtn.setText("导出");
        exportBtn.setTextSize(13);
        exportBtn.setBackgroundResource(android.R.drawable.btn_default);
        exportBtn.setTextColor(0xFFFFD080);
        // Tap asks which size; long-press goes straight to the full frame.
        exportBtn.setOnClickListener(v -> {
            // Tiers, not a hard-coded sensor: the sizes come from the session
            // and the device. A phone that can only take 9 MP exports 9 MP,
            // and saying 3523x4700 there would be a lie the user can check.
            final String full = (sourceWidth > 0 && sourceHeight > 0)
                    ? "全尺寸 " + sourceWidth + "×" + sourceHeight
                    : "全尺寸";
            final String fullNote = probe.isLimited()
                    ? "（本机上限约 " + String.format("%.1f", probe.maxMegapixels()) + " MP，可能导出失败）"
                    : "（原图）";
            new android.app.AlertDialog.Builder(this)
                    .setTitle("导出 · " + probe.describe())
                    .setItems(new String[]{
                            "预览 · 长边 2048（最快）",
                            "预览 · 长边 3400",
                            full + fullNote},
                            (d, which) -> exportImage(which == 0 ? "live" : which == 1 ? "preview" : "full"))
                    .show();
        });
        exportBtn.setOnLongClickListener(v -> { exportImage("full"); return true; });
        topBar.addView(exportBtn);
        renderBtn.setBackgroundResource(android.R.drawable.btn_default);
        renderBtn.setTextColor(0xFF80FF80);
        renderBtn.setOnClickListener(v -> renderImage());
        layout.addView(topBar);
        
        // Image view
        imageView = new ImageView(this);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setAdjustViewBounds(true);
        // Use fixed height based on screen aspect ratio to avoid excessive top padding
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenH = dm.heightPixels;
        int imageH = (int)(screenH * 0.42); // leave room for the controls below
        layout.addView(imageView, new LinearLayout.LayoutParams(-1, imageH));
        
        // Progress bar
        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);
        layout.addView(progressBar, new LinearLayout.LayoutParams(-1, -2));
        
        // Export progress bar (indeterminate)
        exportProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        exportProgressBar.setIndeterminate(true);
        exportProgressBar.setVisibility(View.GONE);
        layout.addView(exportProgressBar, new LinearLayout.LayoutParams(-1, -2));
        
        // Status
        statusText = new TextView(this);
        statusText.setText("选择一张 RAW 或 JPEG 开始");
        statusText.setTextColor(0xFF888888);
        statusText.setPadding(16, 8, 16, 8);
        layout.addView(statusText);
        
        // Controls
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(16, 8, 16, 16);
        
        // Film stock: every profile the engine ships, read from the assets at
        // startup so a new stock never needs a code change.
        TextView filmLabel = new TextView(this);
        filmLabel.setText("胶片");
        filmLabel.setTextColor(0xFFFFFFFF);
        controls.addView(filmLabel);
        filmSpinner = new Spinner(this);
        controls.addView(filmSpinner, new LinearLayout.LayoutParams(-1, -2));

        TextView printLabel = new TextView(this);
        printLabel.setText("相纸 / 拷贝片");
        printLabel.setTextColor(0xFFFFFFFF);
        controls.addView(printLabel);
        printSpinner = new Spinner(this);
        controls.addView(printSpinner, new LinearLayout.LayoutParams(-1, -2));
        loadStockCatalog();
        
        // Exposure slider
        TextView expLabel = new TextView(this);
        expLabel.setText("放大机曝光: 1.00");
        expLabel.setTextColor(0xFFFFFFFF);
        controls.addView(expLabel);
        
        SeekBar exposureBar = new SeekBar(this);
        exposureBar.setMax(375); // 0.25 to 4.0 in steps of 0.01
        exposureBar.setProgress(100); // 1.0
        exposureBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                printExposure = 0.25f + progress * 0.01f;
                expLabel.setText(String.format("放大机曝光: %.2f", printExposure));
                applyParams();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        controls.addView(exposureBar);
        
        addAmountSlider(controls, "颗粒", 0f, 2f, grainAmount,
                v -> { grainAmount = v; applyParams(); });
        addAmountSlider(controls, "光晕", 0f, 4f, halationAmount,
                v -> { halationAmount = v; applyParams(); });
        addAmountSlider(controls, "眩光", 0f, 30f, glareAmount,
                v -> { glareAmount = v; applyParams(); });
        addFilmFormatRow(controls);

        // Six sections no longer fit a phone screen, so the controls scroll.
        android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.addView(controls);
        layout.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(layout);
        uiReady = true;

        // Silent, and late enough not to compete with the first import. It only
        // speaks when there is a newer build than this one.
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> UpdateCheck.check(this, false), 3000);
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

        ArrayAdapter<String> fa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, filmLabels);
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

        ArrayAdapter<String> pa = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, printLabels);
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

    private void pickImage() {
        // **Not image/\*.** Sony's .ARW and several other RAW containers are
        // typed application/octet-stream by the storage layer, so a picker
        // filtered to image/* hides them completely -- which the user reads,
        // correctly, as "this app cannot import RAW". Ask for any file, and
        // name the RAW types as preferences where the picker honours them.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "image/*",
                "application/octet-stream",
                "image/x-sony-arw", "image/x-canon-cr2", "image/x-canon-cr3",
                "image/x-nikon-nef", "image/x-adobe-dng", "image/x-panasonic-rw2",
                "image/x-panasonic-raw", "image/x-olympus-orf", "image/x-fuji-raf",
                "image/x-pentax-pef", "image/x-samsung-srw", "image/x-sigma-x3f"
        });
        startActivityForResult(intent, 1);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) loadImage(uri);
        }
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
            imageView.setImageDrawable(null);
            sourceBitmap = null;
            sourcePixels = null;
            System.gc();

            // Cap by area, the way the RAW path does. inSampleSize only halves,
            // so one exact scale follows if the sample still overshoots.
            final boolean nativeSize = importAtNativeSize;
            importAtNativeSize = false;                       // one import only
            final int capPixels = nativeSize ? Integer.MAX_VALUE : maxImportPixels();
            final int edgeCap = nativeSize ? Integer.MAX_VALUE : IMPORT_LONG_EDGE;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream head = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(head, null, bounds);
                if (head != null) head.close();
            }
            int sample = 1;
            while ((long) (bounds.outWidth / (sample * 2)) * (bounds.outHeight / (sample * 2)) >= capPixels
                    || Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edgeCap) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap decoded;
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                decoded = BitmapFactory.decodeStream(stream, null, opts);
            }
            sourceBitmap = decoded == null ? null : applyExifRotation(uri, decoded);
            if (sourceBitmap == null) {
                Toast.makeText(this, "无法解码图片", Toast.LENGTH_SHORT).show();
                return;
            }
            final long area = (long) sourceBitmap.getWidth() * sourceBitmap.getHeight();
            final int longEdge = Math.max(sourceBitmap.getWidth(), sourceBitmap.getHeight());
            if (area > capPixels || longEdge > edgeCap) {
                // Two ceilings in different units: area is quadratic in the
                // scale, the long edge is linear. Take the stricter one.
                final double kArea = Math.sqrt((double) capPixels / (double) area);
                final double kEdge = (double) edgeCap / (double) longEdge;
                final double k = Math.min(1.0, Math.min(kArea, kEdge));
                sourceBitmap = Bitmap.createScaledBitmap(sourceBitmap,
                        Math.max(1, (int) Math.round(sourceBitmap.getWidth() * k)),
                        Math.max(1, (int) Math.round(sourceBitmap.getHeight() * k)), true);
            }
            sourceWidth = sourceBitmap.getWidth();
            sourceHeight = sourceBitmap.getHeight();

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
            imageView.setImageBitmap(Bitmap.createScaledBitmap(sourceBitmap, pw, ph, true));
            sourceBitmap.recycle();
            sourceBitmap = null;
            statusText.setText("已加载 " + sourceWidth + "x" + sourceHeight + "，点击渲染");
            
            // Create engine and session
            ensureEngine();
            if (session != null) session.close();
            session = engine.openSession(sourcePixels, sourceWidth, sourceHeight, 3);
            applyParams();
        } catch (Exception e) {
            Toast.makeText(this, SpkError.format(e.getMessage()), Toast.LENGTH_LONG).show();
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
    private int maxImportPixels() {
        try { ensureEngine(); } catch (Throwable ignored) { }
        final long px = probe.maxPixels();          // already min'd with the dispatch cap
        return (int) Math.min(px, Integer.MAX_VALUE);
    }

    private void loadRawImage(Uri uri) {
        try {
            String filePath = getFilePathFromUri(uri);
            rawFilePath = filePath;
            if (filePath == null) {
                Toast.makeText(this, "无法获取RAW文件路径", Toast.LENGTH_SHORT).show();
                return;
            }
            
            statusText.setText("RAW解码中...");
            
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
                        statusText.setText(shrunk
                                ? "RAW已加载 " + w + "x" + h + "（原图 " + src[0] + "x" + src[1] + "，超本机 GPU 上限已缩小）"
                                : "RAW已加载 " + w + "x" + h + "，点击渲染");
                        Toast.makeText(this, shrunk
                                ? "原图超出本机 GPU 单缓冲上限，已按 " + w + "x" + h + " 导入"
                                : "RAW加载成功", Toast.LENGTH_LONG).show();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        statusText.setText("RAW加载失败 " + SpkError.shortForm(e.getMessage()));
                        Toast.makeText(this, SpkError.format(e.getMessage()), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        } catch (Exception e) {
            Toast.makeText(this, "RAW加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
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

    private Bitmap applyExifRotation(Uri uri, Bitmap bitmap) {
        final int orientation = readOrientation(uri);
        try { DebugSink.text("exif", "orientation=" + orientation + " bitmap="
                + bitmap.getWidth() + "x" + bitmap.getHeight()); } catch (Throwable ignored) { }
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
        applyParams();
        if (session == null || !uiReady) return;
        if (lastRenderedBitmap != null) {
            renderImage();
        } else {
            statusText.setText("已选择 " + filmStock + " / " + printStock);
        }
    }


    /** A labelled 0..1000 SeekBar mapped onto [lo, hi]; renders when released. */
    private void addAmountSlider(LinearLayout parent, String name, float lo, float hi, float initial,
                                 java.util.function.Consumer<Float> apply) {
        TextView tv = new TextView(this);
        tv.setText(String.format("%s: %.2f", name, initial));
        tv.setTextColor(0xFFFFFFFF);
        parent.addView(tv);
        SeekBar bar = new SeekBar(this);
        bar.setMax(1000);
        bar.setProgress(Math.round((initial - lo) / (hi - lo) * 1000f));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                float v = lo + (hi - lo) * progress / 1000f;
                tv.setText(String.format("%s: %.2f", name, v));
                apply.accept(v);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {
                if (uiReady && lastRenderedBitmap != null) renderImage();
            }
        });
        parent.addView(bar);
    }

    /** Frame size: grain and halation are scaled by the frame's long edge in mm. */
    private void addFilmFormatRow(LinearLayout parent) {
        TextView label = new TextView(this);
        label.setText("画幅（颗粒 / 光晕的物理尺度）");
        label.setTextColor(0xFFFFFFFF);
        parent.addView(label);
        final String[] names = {"135 全画幅 · 36mm", "半格 · 24mm", "XPan · 65mm", "645 · 56mm",
                "6×6 · 56mm", "6×7 · 70mm", "6×9 · 84mm", "6×12 · 120mm", "6×17 · 170mm"};
        final float[] mm = {36f, 24f, 65f, 56f, 56f, 70f, 84f, 120f, 170f};
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                filmFormatMm = mm[pos];
                applyParams();
                if (uiReady && lastRenderedBitmap != null) renderImage();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        parent.addView(sp);
    }

    private void applyParams() {
        if (session != null) {
            session.setParams(String.format(
                "{\"film_stock\":\"%s\",\"print_stock\":\"%s\",\"input_color_space\":\"%s\",\"input_cctf_decoding\":%s,"
                + "\"output_color_space\":\"sRGB\",\"print_exposure\":%.2f,\"preview_long_edge\":2048,"
                + "\"grain_amount\":%.3f,\"halation_amount\":%.3f,\"glare_amount\":%.3f,\"film_format_mm\":%.1f}",
                filmStock, printStock,
                isRawFile ? "ProPhoto RGB" : "sRGB",
                isRawFile ? "false" : "true",
                printExposure, grainAmount, halationAmount, glareAmount, filmFormatMm
            ));
        }
    }

    private void renderImage() {
        if (session == null) {
            Toast.makeText(this, "请先加载图片", Toast.LENGTH_SHORT).show();
            return;
        }
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText("渲染中...");

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
                    imageView.setImageBitmap(bmp);
                    lastRenderedBitmap = bmp;
                    statusText.setText("渲染完成 " + fw + "x" + fh);
                    progressBar.setVisibility(View.GONE);
                });
            } catch (Throwable e) {
                try { session.renderEnd(); } catch (Throwable ignored) {}
                final String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                runOnUiThread(() -> {
                    statusText.setText("渲染失败 " + SpkError.format(msg));
                    progressBar.setVisibility(View.GONE);
                });
            }
        }).start();
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
                runOnUiThread(() -> {
                    Toast.makeText(this, "已保存 " + fw + "x" + fh, Toast.LENGTH_LONG).show();
                    statusText.setText("已导出 " + fw + "x" + fh + "（" + secs + " 秒，其中渲染 "
                        + (fRender / 1000.0) + " 秒）");
                    exportProgressBar.setVisibility(View.GONE);
                });
            } catch (Throwable e) {
                final String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                try { DebugSink.text("export-error", "tier=" + tier + " " + msg); } catch (Throwable ignored) {}
                uploadExportProgress(tmp);
                uploadVkLog();
                runOnUiThread(() -> {
                    statusText.setText("导出失败 " + SpkError.shortForm(msg));
                    Toast.makeText(this, SpkError.format(msg), Toast.LENGTH_LONG).show();
                    exportProgressBar.setVisibility(View.GONE);
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
