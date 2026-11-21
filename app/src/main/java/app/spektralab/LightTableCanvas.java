/*
 * SpektraLab Android - https://github.com/SAINTTaiYi/SpektraLab-Android
 * Copyright (C) 2026 SAINTTaiYi and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.spektralab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.Random;

/**
 * 观片台 —— Canvas 版，**逐层照抄网页 `assets/lt` 的那一版**。
 *
 * <p>网页的结构是：台面 → 溢光 → 辉光 → 投影 → 金属机身 → 内发光 → 玻璃 → 底片 →
 * 玻璃内阴影 → 底部控制边（铭牌 + 船型开关）→ 暗角。这里按同一顺序、同一组数字重画，
 * 所以两边应当是**像素级同一个样子**（除了 WebGL 的浮点噪声以外）。
 *
 * <p>预渲染三张图：云斑/颗粒 tile、辉光（1/4 分辨率放大，等效高斯）、灯箱投影。
 * 其余每帧现画。所有比例、颜色、间距都取自 index.html 与 app.js 里的字面量。
 */
final class LightTableCanvas extends View {

    interface OnToggle { void onToggle(boolean on); }

    // ── 网页里的字面量（照抄）────────────────────────────────────────────
    private static final int C_BENCH_0 = 0xFF24282C, C_BENCH_1 = 0xFF1D2124,
            C_BENCH_2 = 0xFF15181B, C_BENCH_3 = 0xFF0E1113, C_BENCH_4 = 0xFF090B0C;
    private static final int C_GLASS_OFF = 0xFF1E2124;
    private static final float PANEL_CORE_R = 0.977f, PANEL_CORE_G = 0.970f, PANEL_CORE_B = 0.954f;
    private static final float PANEL_RIM_R = 0.880f, PANEL_RIM_G = 0.865f, PANEL_RIM_B = 0.828f;
    private static final int C_FILM_OFF = 0xFF15100C, C_FILM_ON = 0xFF32200F;
    private static final int C_AMBER = 0xFFE08A3C;
    private static final float FILM_SCALE = 0.88f;     // 底片长边 / 玻璃宽

    // ── 布局（屏幕 px）────────────────────────────────────────────────
    private final RectF rigR = new RectF();
    private final RectF wellR = new RectF();
    private final RectF bezelR = new RectF();
    private final RectF filmR = new RectF();
    private final RectF frameR = new RectF();
    private final RectF rockerR = new RectF();
    private final RectF ledR = new RectF();
    private final RectF tmp = new RectF();

    private float dens;
    private float dp11, dp40, dp4, dp160;

    // ── 状态 ─────────────────────────────────────────────────────────
    private float lit, litFrom, litTo;
    private long litAt;
    private boolean litRun;
    private boolean on;

    private Bitmap photo, photoOld;
    private String key;
    private float swap = 1f;      // 1 = 已经落定
    private long swapAt;
    private boolean portrait, portraitOld;

    private OnToggle onToggle;

    // ── 预渲染 ───────────────────────────────────────────────────────
    private Bitmap grainBmp;
    private Paint grainPaint;
    private Bitmap glowBmp;
    private Paint glowPaint;
    private RectF glowDst = new RectF();
    private Bitmap brushBmp;
    private Paint brushPaint;
    private Bitmap vigBmp;
    private Paint vigPaint;
    private RectF vigDst = new RectF();
    private int vigW, vigH;

    // ── 画笔 ─────────────────────────────────────────────────────────
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pf = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path holePath = new Path();
    private final Camera cam = new Camera();
    private final Matrix mtx = new Matrix();
    private ColorMatrixColorFilter dimCf;
    private float dimCfFor = -1f, dimCfSat = -1f;

    LightTableCanvas(Context c) {
        super(c);
        dens = c.getResources().getDisplayMetrics().density;
        dp11 = dp(11f); dp40 = dp(40f); dp4 = dp(4f); dp160 = dp(160f);
        pf.setFilterBitmap(true);
        setClickable(true);
        setFocusable(false);
    }

    void setOnToggle(OnToggle l) { onToggle = l; }

    /** MainActivity 里沿用的名字（原来的网页件也是这么叫的）。 */
    void setOnLight(OnToggle l) { onToggle = l; }

    /** 不带参数的重载：同步状态用（不播 650ms 起辉）。 */
    void setLit(boolean want) { setLit(want, false); }

    // ── 对外 ─────────────────────────────────────────────────────────

    void setLit(boolean want, boolean animate) {
        if (!animate) {
            litTo = litFrom = lit = want ? 1f : 0f;
            litRun = false;
            on = want;
            invalidate();
            return;
        }
        if (litTo == (want ? 1f : 0f)) return;
        litFrom = lit;
        litTo = want ? 1f : 0f;
        litAt = SystemClock.uptimeMillis();
        litRun = true;
        on = want;
        postInvalidateOnAnimation();
    }

    boolean isLit() { return litTo > 0.5f; }

    void setPhoto(Bitmap bmp, String k) {
        if (bmp == null || bmp.isRecycled()) return;
        if (k != null && k.equals(key) && photo == bmp) return;
        if (photo != null && photo != bmp) {
            photoOld = photo;
            portraitOld = portrait;
            swap = 0f;
            swapAt = SystemClock.uptimeMillis();
        } else {
            swap = 1f;
        }
        photo = bmp;
        key = k;
        portrait = bmp.getHeight() > bmp.getWidth();
        layoutFilm();          // ← 底片的矩形要跟着这一张重算
        invalidate();
    }

    // ── 尺寸 ─────────────────────────────────────────────────────────

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutRects(w, h);
        buildGlow();
        buildVignette(w, h);
    }

    private void layoutRects(int w, int h) {
        final float rigH = Math.min(h * 0.66f, w * 1.18f);
        final float rigW = rigH * 5f / 7f;
        final float top = (h * 0.89f - rigH) * 0.5f;
        final float left = (w - rigW) * 0.5f;
        rigR.set(left, top, left + rigW, top + rigH);

        wellR.set(rigR.left + dp11, rigR.top + dp11, rigR.right - dp11, rigR.bottom - dp40);
        bezelR.set(rigR.left + dp11, rigR.bottom - dp40, rigR.right - dp11, rigR.bottom);

        final float rw = dp(29f), rh = dp(34f);
        rockerR.set(bezelR.right - rw, bezelR.centerY() - rh * 0.5f, bezelR.right, bezelR.centerY() + rh * 0.5f);
        final float led = dp(4f);
        ledR.set(rockerR.left - dp(9f) - led, bezelR.centerY() - led * 0.5f,
                rockerR.left - dp(9f), bezelR.centerY() + led * 0.5f);

        layoutFilm();
    }

    private void layoutFilm() {
        if (photo == null || wellR.isEmpty()) { filmR.setEmpty(); frameR.setEmpty(); return; }
        final float longSide = wellR.width() * FILM_SCALE;
        float fw, fh;
        if (portrait) { fh = longSide; fw = longSide * 35f / 40f; }
        else          { fw = longSide; fh = longSide * 35f / 40f; }
        final float cx = wellR.centerX(), cy = wellR.centerY();
        filmR.set(cx - fw * 0.5f, cy - fh * 0.5f, cx + fw * 0.5f, cy + fh * 0.5f);
        float fwid, fhei;
        if (portrait) { fwid = fw * 24f / 35f; fhei = fh * 36f / 40f; }
        else          { fwid = fw * 36f / 40f; fhei = fh * 24f / 35f; }
        frameR.set(cx - fwid * 0.5f, cy - fhei * 0.5f, cx + fwid * 0.5f, cy + fhei * 0.5f);
    }

    // ── 绘制 ─────────────────────────────────────────────────────────

    @Override protected void onDraw(Canvas c) {
        try {
            final long now = SystemClock.uptimeMillis();
            boolean more = false;
            if (litRun) {
                final float u = Math.min(1f, (now - litAt) / 650f);
                final float e = (float) ((1.0 - Math.exp(-4.6 * u)) / (1.0 - Math.exp(-4.6)));
                lit = litFrom + (litTo - litFrom) * e;
                if (u >= 1f) litRun = false;
                more = true;
            }
            if (swap < 1f) {
                final float u = Math.min(1f, (now - swapAt) / 600f);
                swap = u;
                if (u >= 1f) { photoOld = null; more = false; }
                else more = true;
            } else if (photoOld != null) {
                photoOld = null;
            }

            drawBench(c);
            drawSpill(c);
            drawGlow(c);
            drawRig(c);
            drawWell(c);
            drawBezel(c);
            drawVignette(c);

            if (more) postInvalidateOnAnimation();
        } catch (Throwable ignored) {
        }
    }

    /** 台面：135% × 110% 的椭圆径向渐变 + 拉丝 + 噪点。 */
    private void drawBench(Canvas c) {
        final int w = getWidth(), h = getHeight();
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(C_BENCH_4);
        c.drawRect(0, 0, w, h, p);

        final RadialGradient rg = new RadialGradient(w * 0.5f, h * 0.33f, Math.max(w, h) * 0.62f,
                new int[]{C_BENCH_0, C_BENCH_0, C_BENCH_1, C_BENCH_2, C_BENCH_3, C_BENCH_4},
                new float[]{0f, 0.06f, 0.26f, 0.52f, 0.76f, 1f}, Shader.TileMode.CLAMP);
        mtx.reset();
        mtx.setScale(1.35f, 1.10f, w * 0.5f, h * 0.33f);
        rg.setLocalMatrix(mtx);
        p.setShader(rg);
        c.drawRect(0, 0, w, h, p);

        // 拉丝（1 dp 一跳）
        if (brushPaint == null) buildBrush();
        if (brushPaint != null) {
            brushPaint.setAlpha(255);
            c.drawRect(0, 0, w, h, brushPaint);
        }
        // 桌面噪点
        if (grainPaint != null) {
            grainPaint.setAlpha((int) (255 * 0.10f));
            c.drawRect(0, 0, w, h, grainPaint);
        }
        p.setShader(null);
    }

    /** 灯箱往台面上打的溢光。网页是 `mix-blend-mode: screen` 的径向渐变。 */
    private void drawSpill(Canvas c) {
        if (lit <= 0.01f) return;
        final int w = getWidth(), h = getHeight();
        final float cx = rigR.centerX(), cy = rigR.centerY() - rigR.height() * 0.02f;
        final float r = Math.max(w, h) * 0.75f;
        final int a = (int) (255 * 0.29f * lit);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx, cy, r,
                new int[]{argb(a, 255, 249, 238), argb((int) (a * 0.68f), 255, 244, 226),
                        argb((int) (a * 0.32f), 255, 239, 214), argb((int) (a * 0.12f), 255, 234, 204),
                        0x00FFE6C4},
                new float[]{0f, 0.08f, 0.17f, 0.30f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
    }

    /** 灯箱外面的辉光（网页那 5 层 box-shadow + 一层大 blur）。 */
    private void drawGlow(Canvas c) {
        if (lit <= 0.01f || glowBmp == null || glowBmp.isRecycled()) return;
        glowPaint.setAlpha((int) (255 * Math.min(1f, lit)));
        c.drawBitmap(glowBmp, null, glowDst, glowPaint);
    }

    /** 金属机身 + 内发光。 */
    private void drawRig(Canvas c) {
        final float r = dp4;
        // 投影（网页 5 层）
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(0xFF000000);
        final float[] sh = {dp(2f), dp(4f), dp(14f), dp(34f), dp(66f)};
        final float[] sa = {0.92f, 0.72f, 0.72f, 0.62f, 0.55f};
        final float[] sy = {dp(1f), dp(2f), dp(14f), dp(34f), dp(66f)};
        // ★ 必须是「环」：填充会把灯箱内部也压掉 45%
        final Path ring = new Path();
        ring.setFillType(Path.FillType.EVEN_ODD);
        final RectF inner = new RectF(rigR);
        final RectF outer = new RectF();
        for (int i = 0; i < sh.length; ++i) {
            final float e = Math.max(0.5f, sh[i] * 0.5f);
            final float dye = sy[i] * 0.5f;
            outer.set(rigR.left - e, rigR.top - e + dye, rigR.right + e, rigR.bottom + e + dye);
            ring.reset();
            ring.addRoundRect(outer, r + e, r + e, Path.Direction.CW);
            ring.addRoundRect(inner, r, r, Path.Direction.CW);
            p.setAlpha((int) (255 * sa[i] * 0.18f));
            c.drawPath(ring, p);
        }

        // 机身：179deg 的枪黑渐变
        p.setShader(new LinearGradient(0f, rigR.top, 0f, rigR.bottom,
                new int[]{0xFF2C3136, 0xFF23272B, 0xFF1C2024, 0xFF181B1E,
                        0xFF1B1E22, 0xFF272B30, 0xFF31363B},
                new float[]{0f, 0.07f, 0.22f, 0.52f, 0.78f, 0.96f, 1f}, Shader.TileMode.CLAMP));
        p.setAlpha(255);
        c.drawRoundRect(rigR, r, r, p);
        p.setShader(null);

        // 外沿 1 px 冷金属倒角
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, dens * 0.8f));
        p.setColor(0x1CFFFFFF);
        c.drawRoundRect(rigR, r, r, p);
        p.setStyle(Paint.Style.FILL);

        // 内发光：玻璃的灯把金属内壁打亮
        if (lit > 0.02f) {
            final float g = Math.min(1f, lit);
            p.setShader(new LinearGradient(wellR.left, wellR.top, wellR.left, wellR.top + dp(26f),
                    argb((int) (255 * 0.20f * g), 255, 236, 204), 0x00FFF0D6, Shader.TileMode.CLAMP));
            c.drawRect(wellR.left, wellR.top, wellR.right, wellR.top + dp(26f), p);
            p.setShader(new LinearGradient(wellR.left, wellR.bottom, wellR.left, wellR.bottom - dp(26f),
                    argb((int) (255 * 0.20f * g), 255, 236, 204), 0x00FFF0D6, Shader.TileMode.CLAMP));
            c.drawRect(wellR.left, wellR.bottom - dp(26f), wellR.right, wellR.bottom, p);
            p.setShader(new LinearGradient(wellR.left, 0f, wellR.left + dp(26f), 0f,
                    argb((int) (255 * 0.20f * g), 255, 236, 204), 0x00FFF0D6, Shader.TileMode.CLAMP));
            c.drawRect(wellR.left, wellR.top, wellR.left + dp(26f), wellR.bottom, p);
            p.setShader(new LinearGradient(wellR.right, 0f, wellR.right - dp(26f), 0f,
                    argb((int) (255 * 0.20f * g), 255, 236, 204), 0x00FFF0D6, Shader.TileMode.CLAMP));
            c.drawRect(wellR.right - dp(26f), wellR.top, wellR.right, wellR.bottom, p);
            p.setShader(null);
        }
    }

    /** 玻璃 + 底片。 */
    private void drawWell(Canvas c) {
        final int save = c.save();
        // 网页这里是 border-radius:1.5px —— 视觉上等同直角，用 clipRect 更可靠
        c.clipRect(wellR);

        drawPanel(c);
        drawFilm(c);

        // well 内阴影（网页 .well::after 的四层）
        final float r = dp(1.5f);
        p.setStyle(Paint.Style.STROKE);
        p.setShader(null);
        p.setStrokeWidth(Math.max(1f, dens));
        p.setColor(argb((int) (255 * (0.62f - 0.34f * lit)), 0, 0, 0));
        c.drawRoundRect(wellR, r, r, p);
        p.setStyle(Paint.Style.FILL);
        c.restoreToCount(save);
    }

    /** 磨砂导光板：关灯是烟熏灰玻璃，开灯是 5300K 面板。 */
    private void drawPanel(Canvas c) {
        final float t = Math.min(1f, Math.max(0f, lit));
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setAlpha(255);
        p.setColorFilter(null);
        p.setXfermode(null);

        // 关灯：深烟熏灰 + 两道斜反光
        if (t < 0.999f) {
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            p.setColor(C_GLASS_OFF);
            p.setAlpha((int) (255 * (1f - t)));
            c.drawRect(wellR, p);
            p.setAlpha(255);

            final float a1 = 0.042f * (1f - t);
            p.setShader(new LinearGradient(wellR.left, wellR.top, wellR.right, wellR.bottom,
                    new int[]{argb((int) (255 * a1), 255, 255, 255), 0x00FFFFFF},
                    new float[]{0.18f, 0.75f}, Shader.TileMode.CLAMP));
            c.drawRect(wellR, p);
            p.setShader(null);
        }

        // 开灯：中心 #F8F6F0 → 边缘 #E0DCD3
        if (t > 0.001f) {
            final float w = wellR.width(), h = wellR.height();
            final float span = Math.min(w, h) * 0.52f;
            final float cx = wellR.centerX(), cy = wellR.centerY();

            p.setShader(new RadialGradient(cx, cy, span,
                    new int[]{col(PANEL_CORE_R, PANEL_CORE_G, PANEL_CORE_B),
                            col(PANEL_CORE_R, PANEL_CORE_G, PANEL_CORE_B),
                            col(PANEL_RIM_R, PANEL_RIM_G, PANEL_RIM_B)},
                    new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));
            p.setAlpha((int) (255 * t));
            c.drawRect(wellR, p);
            p.setShader(null);
            p.setAlpha(255);
        }

        // 云斑 + 颗粒（开灯才明显）
        if (grainPaint != null && t > 0.02f) {
            grainPaint.setAlpha((int) (255 * 0.55f * t));
            c.drawRect(wellR, grainPaint);
        }

        // 内沿：玻璃嵌槽的两道台阶
        final float e = dp(9f), e2 = dp(1.9f);
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(0x00000000);
        p.setAlpha((int) (255 * (0.11f * t)));
        drawEdgeShade(c, e);
        p.setAlpha((int) (255 * 0.52f * t));
        drawEdgeShade(c, e2);
        p.setAlpha(255);
    }

    private void drawEdgeShade(Canvas c, float e) {
        p.setShader(new LinearGradient(0f, wellR.top, 0f, wellR.top + e,
                0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(wellR.left, wellR.top, wellR.right, wellR.top + e, p);
        p.setShader(new LinearGradient(0f, wellR.bottom, 0f, wellR.bottom - e,
                0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(wellR.left, wellR.bottom - e, wellR.right, wellR.bottom, p);
        p.setShader(new LinearGradient(wellR.left, 0f, wellR.left + e, 0f,
                0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(wellR.left, wellR.top, wellR.left + e, wellR.bottom, p);
        p.setShader(new LinearGradient(wellR.right, 0f, wellR.right - e, 0f,
                0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(wellR.right - e, wellR.top, wellR.right, wellR.bottom, p);
        p.setShader(null);
    }

    /** 底片：接触阴影 + 片基（真镂空齿孔）+ 照片 + 印字。 */
    private void drawFilm(Canvas c) {
        if (photo == null || filmR.isEmpty()) return;

        // 换片：旧片抬起淡出、新片落下
        final boolean swapping = photoOld != null && swap < 1f;
        if (swapping) {
            final float eased = swap * swap * (3f - 2f * swap);
            final float out = Math.min(1f, swap / 0.45f);
            final float in = Math.max(0f, (swap - 0.30f) / 0.70f);
            drawOne(c, photoOld, portraitOld, 1f + 0.030f * out, (1f - out), true);
            drawOne(c, photo, portrait, 1f + 0.030f * (1f - in), in, false);
        } else {
            drawOne(c, photo, portrait, 1f, 1f, false);
        }
    }

    private void drawOne(Canvas c, Bitmap bmp, boolean port, float scale, float alpha, boolean old) {
        if (bmp == null || bmp.isRecycled() || alpha <= 0.004f) return;
        final int save = c.save();
        final float cx = wellR.centerX(), cy = wellR.centerY();
        c.scale(scale, scale, cx, cy);

        // 底片的几何（按这一张自己的方向算）
        final float longSide = wellR.width() * FILM_SCALE;
        float fw, fh;
        if (port) { fh = longSide; fw = longSide * 35f / 40f; }
        else      { fw = longSide; fh = longSide * 35f / 40f; }
        final float fl = cx - fw * 0.5f, ft = cy - fh * 0.5f;
        final float fr = fl + fw, fb = ft + fh;
        float wid, hei;
        if (port) { wid = fw * 24f / 35f; hei = fh * 36f / 40f; }
        else      { wid = fw * 36f / 40f; hei = fh * 24f / 35f; }
        final float gl = cx - wid * 0.5f, gt = cy - hei * 0.5f;
        final float gr = gl + wid, gb = gt + hei;

        // 接触阴影
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(0xFF000000);
        final float spread = dp(2.2f + 14f * 0f);
        for (int i = 4; i >= 0; --i) {
            final float u = i / 4f;
            final float e = spread * u;
            final float a = 0.32f * alpha * (1f - u) * (1f - u) * (1f - 0.72f * lit);
            if (a < 0.004f) continue;
            p.setAlpha((int) (255 * a));
            final float rr = dp(1.2f) + e;
            c.drawRoundRect(fl - e, ft - e + dp(0.9f), fr + e, fb + e + dp(0.9f), rr, rr, p);
        }

        // 照片（先暗态，再按 lit 叠亮态）
        final int a = (int) (255 * alpha);
        pf.setShader(null);
        pf.setFilterBitmap(true);
        if (lit < 0.995f) {
            pf.setColorFilter(dimFilter());
            pf.setAlpha((int) (a * (1f - lit)));
            c.drawBitmap(bmp, null, new RectF(gl, gt, gr, gb), pf);
        }
        if (lit > 0.005f) {
            pf.setColorFilter(null);
            pf.setAlpha((int) (a * Math.min(1f, lit)));
            c.drawBitmap(bmp, null, new RectF(gl, gt, gr, gb), pf);
        }
        pf.setColorFilter(null);
        pf.setAlpha(255);

        // 片基 + 齿孔（EVEN_ODD：外框 - 画幅 - 每个孔）
        // 片基是 40×35 mm（横）/ 35×40（竖），画幅 36×24
        final float mm = port ? (fw / 35f) : (fw / 40f);
        final float holeHW = 1.395f * mm, holeHH = 0.99f * mm;   // 半宽 / 半高
        final float period = 4.75f * mm, hr = 0.30f * mm;
        final float bandY = 14.75f * mm;                          // 齿孔带中心
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRoundRect(new RectF(fl, ft, fr, fb), dp(0.7f), dp(0.7f), Path.Direction.CW);
        path.addRect(gl, gt, gr, gb, Path.Direction.CW);
        holePath.reset();
        if (!port) {
            final float cy2 = (ft + fb) * 0.5f;
            final float topB = cy2 - bandY, botB = cy2 + bandY;
            final float startX = fl + 0.9f * mm;
            final int n = (int) Math.floor((fw / mm - 1.8f) / 4.75f);
            for (int k = 0; k <= n; ++k) {
                final float x = startX + k * period;
                path.addRoundRect(x - holeHW, topB - holeHH, x + holeHW, topB + holeHH, hr, hr, Path.Direction.CW);
                path.addRoundRect(x - holeHW, botB - holeHH, x + holeHW, botB + holeHH, hr, hr, Path.Direction.CW);
                holePath.addRoundRect(x - holeHW, topB - holeHH, x + holeHW, topB + holeHH, hr, hr, Path.Direction.CW);
                holePath.addRoundRect(x - holeHW, botB - holeHH, x + holeHW, botB + holeHH, hr, hr, Path.Direction.CW);
            }
        } else {
            final float cx2 = (fl + fr) * 0.5f;
            final float leftB = cx2 - bandY, rightB = cx2 + bandY;
            final float startY = ft + 0.9f * mm;
            final int n = (int) Math.floor((fh / mm - 1.8f) / 4.75f);
            for (int k = 0; k <= n; ++k) {
                final float y = startY + k * period;
                path.addRoundRect(leftB - holeHH, y - holeHW, leftB + holeHH, y + holeHW, hr, hr, Path.Direction.CW);
                path.addRoundRect(rightB - holeHH, y - holeHW, rightB + holeHH, y + holeHW, hr, hr, Path.Direction.CW);
                holePath.addRoundRect(leftB - holeHH, y - holeHW, leftB + holeHH, y + holeHW, hr, hr, Path.Direction.CW);
                holePath.addRoundRect(rightB - holeHH, y - holeHW, rightB + holeHH, y + holeHW, hr, hr, Path.Direction.CW);
            }
        }
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setAlpha(a);
        // ★ 齿孔里透出的是「灯箱的光」——必须画在片基之前，否则露出来的是接触阴影
        if (!holePath.isEmpty()) {
            p.setColor(blend(0xFF23262A, col(PANEL_CORE_R, PANEL_CORE_G, PANEL_CORE_B), lit));
            c.drawPath(holePath, p);
        }
        p.setColor(blend(C_FILM_OFF, C_FILM_ON, lit));
        c.drawPath(path, p);

        // 孔缘散射光
        if (lit > 0.03f) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, 0.22f * mm));
            p.setColor(0xFFFFE9C6);
            p.setAlpha((int) (150 * lit * alpha));
            c.drawPath(holePath, p);
        }
        // 片基外沿边缘光
        if (lit > 0.03f) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, dp(0.9f)));
            p.setColor(0xFFFFF2DC);
            p.setAlpha((int) (86 * lit * alpha));
            c.drawRoundRect(fl, ft, fr, fb, dp(0.7f), dp(0.7f), p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setAlpha(255);

        // 印字
        final float size = Math.min(1.02f * mm, dp(13f));
        if (size >= dp(4.2f)) {
            txt.setStyle(Paint.Style.FILL);
            txt.setTypeface(Typeface.MONOSPACE);
            txt.setTextSize(size);
            txt.setLetterSpacing(0.13f);
            txt.setTextAlign(Paint.Align.LEFT);
            txt.setColor(C_AMBER);
            txt.setAlpha((int) (255 * (0.18f + 0.74f * lit) * alpha));
            final String mark = "KODAK PORTRA 400 \u00B7 14A";
            if (!port) {
                c.drawText(mark, gl + 1.6f * mm, gb + 0.26f * mm + size, txt);
            } else {
                c.save();
                c.rotate(90f, gr + 1.9f * mm, gt);
                c.drawText(mark, gr + 1.9f * mm, gt + size * 0.9f, txt);
                c.restore();
            }
            txt.setLetterSpacing(0f);
            txt.setAlpha(255);
        }
        c.restoreToCount(save);
    }

    /** 底部控制边：铭牌 + LED + 船型开关。 */
    private void drawBezel(Canvas c) {
        // 铭牌
        txt.setTypeface(Typeface.MONOSPACE);
        txt.setTextSize(dp(8f));
        txt.setLetterSpacing(0.16f);
        txt.setTextAlign(Paint.Align.LEFT);
        txt.setColor(argb((int) (255 * 0.30f), 255, 255, 255));
        final Paint.FontMetrics fm = txt.getFontMetrics();
        final float ty = bezelR.centerY() - (fm.ascent + fm.descent) * 0.5f;
        c.drawText("SPEKTRA \u00B7 LT-01 \u00B7 5300K \u00B7 CRI 98", bezelR.left + dp(2f), ty, txt);
        txt.setLetterSpacing(0f);

        drawLed(c);
        drawRocker(c);
    }

    private void drawLed(Canvas c) {
        final float cx = ledR.centerX(), cy = ledR.centerY(), r = ledR.width() * 0.5f;
        // 光晕
        if (lit > 0.05f) {
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(cx, cy, r * 5.5f,
                    new int[]{argb((int) (255 * 0.62f * lit), 255, 158, 86),
                            argb((int) (255 * 0.26f * lit), 255, 118, 46),
                            argb((int) (255 * 0.09f * lit), 255, 96, 32), 0x00FF6014},
                    new float[]{0f, 0.26f, 0.50f, 0.74f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r * 5.5f, p);
            p.setShader(null);
        }
        // 珠
        final int core = lit > 0.5f ? 0xFFFFC49A : 0xFF3A3D41;
        final int edge = lit > 0.5f ? 0xFFB33F0E : 0xFF17191B;
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx - r * 0.24f, cy - r * 0.32f, r * 1.5f,
                new int[]{core, blend(edge, core, lit > 0.5f ? 0.75f : 0.0f), edge},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
    }

    private void drawRocker(Canvas c) {
        final float r = dp(2.5f);
        // 凹槽
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(0xFF0B0C0D);
        c.drawRoundRect(rockerR, r, r, p);

        // 面：rotateX(±9deg) 的透视
        final float ang = -9f + 18f * lit;      // -9（O 翘）→ +9（I 压下）
        final int sv = c.save();
        final float cx = rockerR.centerX(), cy = rockerR.centerY();
        mtx.reset();
        cam.save();
        cam.rotateX(ang);
        cam.getMatrix(mtx);
        cam.restore();
        mtx.preTranslate(-cx, -cy);
        mtx.postTranslate(cx, cy);
        c.concat(mtx);

        final float inset = dp(2f);
        tmp.set(rockerR.left + inset, rockerR.top + inset, rockerR.right - inset, rockerR.bottom - inset);
        p.setShader(new LinearGradient(0f, tmp.top, 0f, tmp.bottom,
                new int[]{0xFF33373C, 0xFF23262A, 0xFF1A1C1F, 0xFF141619, 0xFF1F2226, 0xFF2B2F34},
                new float[]{0f, 0.21f, 0.49f, 0.51f, 0.79f, 1f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(tmp, dp(1.5f), dp(1.5f), p);
        p.setShader(null);
        // 中缝
        p.setColor(0xCC000000);
        p.setStrokeWidth(Math.max(1f, dens * 0.5f));
        c.drawLine(tmp.left + tmp.width() * 0.12f, tmp.centerY(),
                tmp.right - tmp.width() * 0.12f, tmp.centerY(), p);
        c.restoreToCount(sv);

        // I / O
        txt.setTypeface(Typeface.MONOSPACE);
        txt.setTextSize(dp(7f));
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setLetterSpacing(0.02f);
        txt.setColor(argb((int) (255 * 0.42f), 255, 255, 255));
        final float yI = tmp.top + dp(6f) + dp(7f) * 0.5f;
        txt.setAlpha(255);
        c.drawText("I", tmp.centerX(), yI, txt);
        txt.setColor(argb((int) (255 * 0.22f), 255, 255, 255));
        c.drawText("O", tmp.centerX(), tmp.bottom - dp(5.5f) - dp(7f) * 0.5f, txt);
        txt.setLetterSpacing(0f);
    }

    /** 暗房暗角（网页 .vig-base + .vig-extra）。 */
    private void drawVignette(Canvas c) {
        if (vigBmp != null && !vigBmp.isRecycled()) {
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            p.setAlpha(255);
            c.drawBitmap(vigBmp, null, vigDst, pf);
        }
    }

    // ── 预渲染 ───────────────────────────────────────────────────────

    /** 云斑 + 颗粒：三个尺度的 value noise。 */
    private void ensureGrain() {
        if (grainBmp != null && !grainBmp.isRecycled()) return;
        try {
            final int N = 512;
            final int[] px = new int[N * N];
            final Random rnd = new Random(20261120L);
            // 三层，周期对应网页 133 / 51 / 19 px（在 512 的 tile 里）
            final float[][] layers = {{4f, 0.026f}, {10f, 0.024f}, {27f, 0.015f}};
            final float[] grids = new float[3 * 65 * 65];
            int gi = 0;
            for (int L = 0; L < 3; ++L) {
                for (int i = 0; i < 65 * 65; ++i) grids[gi++] = rnd.nextFloat();
            }
            for (int y = 0; y < N; ++y) {
                for (int x = 0; x < N; ++x) {
                    float n = 0f;
                    int base = 0;
                    for (int L = 0; L < 3; ++L) {
                        final float cells = layers[L][0];
                        final float amp = layers[L][1];
                        final float fx = x * cells / N, fy = y * cells / N;
                        final int xi = (int) fx, yi = (int) fy;
                        float tx = fx - xi, ty = fy - yi;
                        tx = tx * tx * (3f - 2f * tx);
                        ty = ty * ty * (3f - 2f * ty);
                        // 网格只有 65×65，索引必须回绕（cells 可以大于 64 的整数倍）
                        final int x0 = xi & 63, x1 = (xi + 1) & 63;
                        final int y0 = yi & 63, y1 = (yi + 1) & 63;
                        final int o00 = base + y0 * 65 + x0, o10 = base + y0 * 65 + x1;
                        final int o01 = base + y1 * 65 + x0, o11 = base + y1 * 65 + x1;
                        final float a0 = grids[o00] + (grids[o10] - grids[o00]) * tx;
                        final float a1 = grids[o01] + (grids[o11] - grids[o01]) * tx;
                        n += (a0 + (a1 - a0) * ty - 0.5f) * amp * 2f;
                        base += 65 * 65;
                    }
                    n += (rnd.nextFloat() - 0.5f) * 0.010f;
                    int al = Math.round(Math.abs(n) * 255f);
                    if (al > 255) al = 255;
                    if (al < 0) al = 0;
                    px[y * N + x] = (al << 24) | (n > 0f ? 0x00FFFFFF : 0x00000000);
                }
            }
            final Bitmap b = Bitmap.createBitmap(N, N, Bitmap.Config.ARGB_8888);
            b.setPixels(px, 0, N, 0, 0, N, N);
            grainBmp = b;
            grainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            grainPaint.setShader(new BitmapShader(b, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR));
        } catch (Throwable ignored) {
        }
    }

    /** 拉丝：1 dp 一横条。 */
    private void buildBrush() {
        try {
            final int n = Math.max(4, Math.round(dp4));
            final Bitmap b = Bitmap.createBitmap(1, n, Bitmap.Config.ARGB_8888);
            final int[] px = new int[n];
            for (int i = 0; i < n; ++i) {
                px[i] = (i == 0) ? 0x10FFFFFF : (i == 1 ? 0x14000000 : 0x00000000);
            }
            b.setPixels(px, 0, 1, 0, 0, 1, n);
            brushBmp = b;
            brushPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            brushPaint.setShader(new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        } catch (Throwable ignored) {
        }
    }

    /** 辉光：在 1/4 分辨率上叠 5 层圆角矩形，放大绘制 —— 等效高斯，且很便宜。 */
    private void buildGlow() {
        ensureGrain();
        final float pad = dp160;
        glowDst.set(rigR.left - pad, rigR.top - pad, rigR.right + pad, rigR.bottom + pad);
        final int gw = Math.max(8, (int) (glowDst.width() / 4f));
        final int gh = Math.max(8, (int) (glowDst.height() / 4f));
        try {
            final Bitmap b = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(b);
            final float sx = gw / glowDst.width(), sy = gh / glowDst.height();
            final RectF r = new RectF(rigR.left * sx - (glowDst.left * sx),
                    rigR.top * sy - (glowDst.top * sy),
                    rigR.right * sx - (glowDst.left * sx),
                    rigR.bottom * sy - (glowDst.top * sy));
            final float rad = dp4 * sx;
            final Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
            gp.setStyle(Paint.Style.FILL);
            gp.setColor(0xFFFFFFFF);
            // 5 层：4 / 10 / 26 / 64 / 150 dp，alpha .94/.64/.34/.14/.056
            // ★ 必须画「环」：填充的话会把灯箱内部整个盖白，面板就灰了。
            final float[] blur = {dp(4f), dp(10f), dp(26f), dp(64f), dp(150f)};
            final float[] al = {0.94f, 0.64f, 0.34f, 0.140f, 0.056f};
            final Path ring = new Path();
            ring.setFillType(Path.FillType.EVEN_ODD);
            final RectF inner = new RectF(r);
            final RectF outer = new RectF();
            for (int i = blur.length - 1; i >= 0; --i) {
                final float e = Math.max(0.35f, blur[i] * 0.5f * sx);
                outer.set(r.left - e, r.top - e, r.right + e, r.bottom + e);
                ring.reset();
                ring.addRoundRect(outer, rad + e, rad + e, Path.Direction.CW);
                ring.addRoundRect(inner, rad, rad, Path.Direction.CW);
                gp.setAlpha((int) (255 * al[i] * 0.95f));
                c.drawPath(ring, gp);
            }
            glowBmp = b;
            glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        } catch (Throwable ignored) {
            glowBmp = null;
        }
    }

    /** 暗角：四角沉黑、中央敞开。 */
    private void buildVignette(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (vigW == w && vigH == h && vigBmp != null) return;
        vigW = w; vigH = h;
        try {
            final Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            final Canvas c = new Canvas(b);
            final Paint vp = new Paint(Paint.ANTI_ALIAS_FLAG);
            final RadialGradient rg = new RadialGradient(w * 0.5f, h * 0.42f, Math.max(w, h) * 0.62f,
                    new int[]{0x00000000, 0x00000000,
                            argb((int) (255 * 0.13f), 0, 0, 0), argb((int) (255 * 0.40f), 0, 0, 0),
                            argb((int) (255 * 0.70f), 0, 0, 0), argb((int) (255 * 0.84f), 0, 0, 0)},
                    new float[]{0f, 0.46f, 0.66f, 0.82f, 0.93f, 1f}, Shader.TileMode.CLAMP);
            mtx.reset();
            mtx.setScale(1f, 1.06f, w * 0.5f, h * 0.42f);
            rg.setLocalMatrix(mtx);
            vp.setShader(rg);
            c.drawRect(0, 0, w, h, vp);
            vigBmp = b;
            vigDst.set(0, 0, w, h);
        } catch (Throwable ignored) {
            vigBmp = null;
        }
    }

    // ── 触摸：点开关 ─────────────────────────────────────────────────

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            final float x = e.getX(), y = e.getY();
            final float pad = dp(14f);
            if (x >= rockerR.left - pad && x <= rockerR.right + pad
                    && y >= rockerR.top - pad && y <= rockerR.bottom + pad) {
                final boolean want = !isLit();
                setLit(want, true);
                playClick();
                if (onToggle != null) onToggle.onToggle(want);
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private void playClick() {
        try {
            final android.media.ToneGenerator tg = new android.media.ToneGenerator(
                    android.media.AudioManager.STREAM_SYSTEM, 18);
            tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 22);
            postDelayed(tg::release, 220);
        } catch (Throwable ignored) {
        }
    }

    // ── 小工具 ───────────────────────────────────────────────────────

    private float dp(float v) { return v * dens; }

    private static int col(float r, float g, float b) {
        return 0xFF000000 | (Math.round(r * 255f) << 16) | (Math.round(g * 255f) << 8) | Math.round(b * 255f);
    }

    private static int argb(int a, int r, int g, int b) {
        if (a < 0) a = 0; if (a > 255) a = 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int blend(int c0, int c1, float t) {
        if (t <= 0f) return c0;
        if (t >= 1f) return c1;
        final int a = (int) (((c0 >>> 24) & 255) + ((((c1 >>> 24) & 255) - ((c0 >>> 24) & 255)) * t));
        final int r = (int) (((c0 >> 16) & 255) + ((((c1 >> 16) & 255) - ((c0 >> 16) & 255)) * t));
        final int g = (int) (((c0 >> 8) & 255) + ((((c1 >> 8) & 255) - ((c0 >> 8) & 255)) * t));
        final int b = (int) ((c0 & 255) + (((c1 & 255) - (c0 & 255)) * t));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private ColorFilter dimFilter() {
        final float L = lit;
        final float q = Math.round((0.30f + 0.70f * L) * 128f) / 128f;
        final float sa = Math.round((0.62f + 0.38f * L) * 128f) / 128f;
        if (q != dimCfFor || sa != dimCfSat || dimCf == null) {
            dimCfFor = q; dimCfSat = sa;
            final ColorMatrix m = new ColorMatrix();
            m.setSaturation(sa);
            final float c = 0.85f + 0.15f * L;
            final float t = (1f - c) * 128f;
            m.postConcat(new ColorMatrix(new float[]{
                    c, 0, 0, 0, t,
                    0, c, 0, 0, t,
                    0, 0, c, 0, t,
                    0, 0, 0, 1, 0}));
            final ColorMatrix d = new ColorMatrix(new float[]{
                    q, 0, 0, 0, 0,
                    0, q, 0, 0, 0,
                    0, 0, q, 0, 0,
                    0, 0, 0, 1, 0});
            m.postConcat(d);
            dimCf = new ColorMatrixColorFilter(m);
        }
        return dimCf;
    }
}
