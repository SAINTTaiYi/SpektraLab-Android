/*
 * SpektraLab Android - https://github.com/SAINTTaiYi/SpektraLab-Android
 * Copyright (C) 2026 SAINTTaiYi and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.spektralab;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;

/**
 * 内嵌的「暗房观片台」：`assets/lt` 里那一份网页，一比一跑在 WebView 里。
 *
 * <p>灯箱、底片、齿孔真镂空、辉光、开关音效**全部由网页的 WebGL / Web Audio 负责**——
 * 这里只是四个动作：装、给照片、给灯、收开关事件。任何在 Java 里重画一遍的尝试都
 * 会和浏览器里那一版分叉，所以这一页的视觉只有一个真相，就是那份 HTML。
 */
final class LightTableWeb extends android.widget.FrameLayout {

    interface OnLight { void onLight(boolean on); }
    interface OnFail { void onFail(String why); }

    private static final String PAGE = "file:///android_asset/lt/index.html#host=1";

    private final WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private OnLight onLight;
    private OnFail onFail;

    private boolean ready;
    private boolean destroyed;
    private String pendingPhoto;
    private Boolean pendingLit;
    private String lastKey;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    LightTableWeb(Context c) {
        super(c);
        setBackgroundColor(0xFF0E0D0B);

        web = new WebView(c);
        web.setBackgroundColor(0xFF0E0D0B);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(OVER_SCROLL_NEVER);
        web.setLongClickable(false);
        web.setHapticFeedbackEnabled(false);

        final WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        try { s.setAllowFileAccessFromFileURLs(true); } catch (Throwable ignored) {}
        try { s.setAllowUniversalAccessFromFileURLs(true); } catch (Throwable ignored) {}
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "LTBridge");
        addView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        web.loadUrl(PAGE);

        // 兜底 1：JS 万一起不来，3.5 s 后照样把照片推进去（照片是 data URL，不依赖 ready）
        ui.postDelayed(new Runnable() {
            @Override public void run() { flush(); }
        }, 3500L);

        // 兜底 2：6.5 s 还没 ready → WebGL 多半被 ROM 阉了。叫主界面退回原生那一版。
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!ready && !destroyed && onFail != null) onFail.onFail("not-ready");
            }
        }, 6500L);
    }

    private final class Bridge {
        @JavascriptInterface
        public void onLight(final boolean on) {
            ui.post(new Runnable() {
                @Override public void run() {
                    if (onLight != null) onLight.onLight(on);
                }
            });
        }

        @JavascriptInterface
        public void ready() {
            ui.post(new Runnable() {
                @Override public void run() { ready = true; flush(); }
            });
        }

        @JavascriptInterface
        public String version() { return "lt01"; }
    }

    void setOnLight(OnLight l) { onLight = l; }

    void setOnFail(OnFail l) { onFail = l; }

    /** 把一张成片放上灯箱。key 相同就不重复编码。 */
    void setPhoto(final Bitmap src, final String key) {
        if (src == null || src.isRecycled() || destroyed) return;
        if (key != null && key.equals(lastKey) && pendingPhoto == null && ready) return;
        lastKey = key;
        new Thread(new Runnable() {
            @Override public void run() {
                String url = null;
                try {
                    Bitmap b = src;
                    final int w = b.getWidth(), h = b.getHeight();
                    final int longEdge = Math.max(w, h);
                    if (longEdge > 1600) {
                        final float k = 1600f / longEdge;
                        b = Bitmap.createScaledBitmap(src,
                                Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
                    }
                    final ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 19);
                    b.compress(Bitmap.CompressFormat.JPEG, 86, bos);
                    if (b != src) b.recycle();
                    url = "data:image/jpeg;base64,"
                            + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                } catch (Throwable ignored) {
                    return;
                }
                final String u = url;
                ui.post(new Runnable() {
                    @Override public void run() {
                        pendingPhoto = u;
                        flush();
                    }
                });
            }
        }, "lt-web-encode").start();
    }

    /** 灯：与原生那一盏同步。 */
    void setLit(boolean on) {
        pendingLit = on;
        flush();
    }

    boolean isOn() { return pendingLit != null && pendingLit; }

    private void flush() {
        if (destroyed) return;
        try {
            if (pendingPhoto != null) {
                final String p = pendingPhoto;
                pendingPhoto = null;
                web.evaluateJavascript("LTHost&&LTHost.photo('" + p + "')", null);
            }
            if (pendingLit != null) {
                final boolean b = pendingLit;
                pendingLit = null;
                web.evaluateJavascript("LTHost&&LTHost.lightQuiet(" + b + ")", null);
            }
        } catch (Throwable ignored) {
        }
    }

    void onPause() {
        try { web.onPause(); web.pauseTimers(); } catch (Throwable ignored) {}
    }

    void onResume() {
        try { web.onResume(); web.resumeTimers(); } catch (Throwable ignored) {}
    }

    void release() {
        destroyed = true;
        try { web.stopLoading(); } catch (Throwable ignored) {}
        try { web.removeJavascriptInterface("LTBridge"); } catch (Throwable ignored) {}
        try { web.destroy(); } catch (Throwable ignored) {}
    }
}
