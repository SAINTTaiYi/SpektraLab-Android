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
import app.spektralab.ui.ScrubBar;
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

/** Worker extracted from MainActivity; delegates Android/Activity access to host. */
final class ExportWorker {
    private final MainActivity host;

    ExportWorker(MainActivity host) { this.host = host; }

    void run(String format, String tier, final Runnable onDone) {
        CrashTrail.mark(host, "export:begin");
        // **The export's exposure, decided once, before any branch.** The open
        // params, the forced delta and the pin all have to agree: the canvas'
        // meter flag, the canvas' EV, and only the *user's* offset in
        // `exposure_compensation_ev` (see `applyParamsTo`).
        final String exportMeter = host.sanitizeMeter(host.meterMethod);
        final boolean exportAutoExposure = !exportMeter.isEmpty();
        // Only the frame the canvas last measured may hand its EV over; a batch
        // walks the strip without rendering, so the previous frame's EV would
        // otherwise be pinned onto this one.
        final boolean pinEv = exportAutoExposure && host.appliedEvBelongsToCurrentFrame();
        // A live frame is 800 px and this is about to build a 4175 px one;
        // letting the two queue behind the same lock buys nothing.
        host.cancelLivePreview();
        final app.spektralab.domain.ExportPlan exportPlan =
                app.spektralab.domain.ExportPlan.forFormat(format);
        final boolean dng = ".dng".equals(exportPlan.extension);
        final boolean tiff = ".tif".equals(exportPlan.extension);
        final String ext = exportPlan.extension;
        final String mime = exportPlan.mime;
        if (host.sharePill != null) host.sharePill.setVisibility(View.GONE);
        if (host.engine == null || host.session == null) {
            host.showInAppToast(tr("export.import_a_photo_first"));
            if (onDone != null) onDone.run();
            return;
        }
        if (host.filmStock == null) {
            host.showInAppToast(tr("export.choose_a_film_before_exporting"));
            if (onDone != null) onDone.run();
            return;
        }
        // The shelf queue prints 800 px cards through this same session, and a
        // card that lands between "start export" and the first strip swaps
        // film_stock under the full-size render. That -- not the params delta --
        // is what used to come back as a flat grey.
        host.stopPrefetch();
        new Thread(() -> {
            java.io.File tmp = new java.io.File(host.getCacheDir(), "export_" + System.currentTimeMillis() + ext);
            try {
                UiDispatcher.post(() -> {
                    host.statusText.setText(tr("export.exporting_preparing_full_size_render"));
                    host.exportProgressBar.setVisibility(View.VISIBLE);
                    host.setStep(3);
                });
                // A stock delta before an export is fine -- measured, not
                // assumed (`full_after_delta`: mean 104.2 std 11945 before,
                // 102.4 std 12481 after, and the two hashes differ). It is the
                // same delta the preview renders with, which is the point.
                try {
                    DebugSink.text("export-params", "film=" + host.filmStock + " print=" + host.printStock
                            + " pex=" + host.printExposure + " (no delta sent)");
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
                            host.geometryCropX, host.geometryCropY, host.geometryCropW, host.geometryCropH,
                            host.geometryTurns, host.geometryRotation));
                    DebugSink.textNow("export-plan", "src=" + host.sourceWidth + "x" + host.sourceHeight
                            + " importCap=" + host.importCapPixels + " frameCap=" + host.maxImportPixels()
                            + " overscan=" + host.overscanOn + " tier=" + tier
                            + " avail=" + (host.availableMemoryBytes() >> 20) + "MiB");
                } catch (Throwable spkIgnored) { Diagnostics.ignored("equals:14838", spkIgnored); }
                final long t0 = System.currentTimeMillis();
                // The stop pill used to be checked only between frames: a batch of
                // twenty pictures could not be stopped once one export started.
                if (host.batchExportAbort) throw new IllegalStateException(tr("export.stopped"));
                final java.util.concurrent.atomic.AtomicBoolean spinning =
                        new java.util.concurrent.atomic.AtomicBoolean(true);
                final java.util.concurrent.atomic.AtomicLong lastMem =
                        new java.util.concurrent.atomic.AtomicLong(-5);
                final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
                Runnable tick = new Runnable() {
                    @Override public void run() {
                        if (!spinning.get()) return;
                        long el = (System.currentTimeMillis() - t0) / 1000;
                        host.statusText.setText(tr("export.exporting") + tier + tr("export.rendering_for") + el + tr("export.s"));
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
                                final long avail = host.availableMemoryBytes();
                                DebugSink.textNow("export-mem", el + "s avail=" + (avail >> 20)
                                        + "MiB " + (host.engine == null ? "no engine" : host.engine.memoryReport()));
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
                                    + (host.availableMemoryBytes() >> 20) + "MiB "
                                    + (host.engine == null ? "no engine" : host.engine.memoryReport());
                            // **On disk first.** `textNow` needs the network; a
                            // phone in flight mode -- or a process killed at the
                            // moment it mattered -- loses the sample entirely,
                            // which is why the 18-second export crash came back
                            // with none. The ring is a SharedPreferences write
                            // and survives the kill; the report picks it up.
                            CrashTrail.markNow(host, "export-mem " + line);
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
                final Bitmap savedBefore = host.beforeBitmap;
                final Bitmap savedAfter = host.lastRenderedBitmap;
                UiDispatcher.post(() -> {
                    host.beforeBitmap = null;
                    host.lastRenderedBitmap = null;
                    if (host.compare != null) host.compare.clear();
                });
                int[] info;
                try {
                    synchronized (host.renderMutex) {
                        CrashTrail.markNow(host, "export:lock");
                        // **Give the pool back before the export asks for more.**
                        // The preview that has just been rendered leaves its
                        // working set pooled, and a 40 MP export then wants the
                        // whole thing again. Measured on the owner's phone: the
                        // export dies at 153 s with the launcher behind it.
                        try {
                            final long freed = host.engine.trimPool();
                            DebugSink.text("export", "pre-export trim freed " + (freed >> 20)
                                    + " MiB; report " + host.engine.memoryReport());
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
                        final SpektraLabEngine.Session keep = host.session;
                        boolean keepSuspended = false;
                        boolean keepClosed = false;
                        // **A batch closes this session a moment later anyway.**
                        // Suspending writes a frame's worth of planes to the
                        // cache directory and resuming allocates them again --
                        // once per frame, for a session that is about to die.
                        if (keep != null && !host.batchExportRunning) {
                            try {
                                keepSuspended = keep.suspend();
                                DebugSink.text("export", "preview suspended=" + keepSuspended
                                        + "; report " + host.engine.memoryReport());
                            } catch (Throwable spkIgnored) { Diagnostics.ignored("trimPool:14950", spkIgnored); }
                        }
                        CrashTrail.markNow(host, "export:suspend " + keepSuspended);
                        SpektraLabEngine.Session fresh = null;
                        // **No area cap here.** This read `<= 30 MP`, a leftover
                        // from when `sourcePixels` was a `ByteBuffer.allocateDirect`
                        // capped by the heap growth limit. A 44 MP JPEG fell through
                        // it, had no `rawFilePath` either, and so exported on the
                        // *preview* session: `full` on a 2048 px source, which is
                        // both the wrong size and the shape that failed on the
                        // Mali report. The buffer is malloc'd now and the cap is
                        // thirty times smaller than what the phones can hold.
                        if (keep != null && host.sourcePixels != null && host.sourceWidth > 0 && host.sourceHeight > 0) {
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
                                final String path = host.sourceUri != null ? host.getFilePathFromUri(host.sourceUri) : null;
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
                                       > (long) host.sourceWidth * host.sourceHeight;
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
                                    fresh = host.engine.openSession(fullBuf, fw, fh, 3);
                                    CrashTrail.markNow(host,
                                            "export:full-size " + fw + "x" + fh
                                                    + " (import was " + host.sourceWidth + "x" + host.sourceHeight + ")");
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
                                try { fresh = host.engine.openSession(host.sourcePixels, host.sourceWidth, host.sourceHeight, 3); }
                                catch (Throwable ignored) { fresh = null; }
                            }
                            CrashTrail.markNow(host,
                                    "export:session " + (fresh != null ? "fresh" : "none"));
                        } else if (keep != null && host.rawFilePath != null) {
                            // **No budget gate here.** One was added in 1.4.1t and it
                            // refused frames the engine goes on to render: it compared
                            // the canvas against `maxPixelsByMemory(available)` before
                            // the export had trimmed the preview's pool, and 100 B/px
                            // is the whole-frame figure, not the banded one. Refusing
                            // is not the fix for a large frame -- rendering it is.
                            // A frame that genuinely does not fit fails in the engine
                            // and says which plane it wanted; that message is true,
                            // this one was a guess.
                            final int budgetPixels = host.importedWidth > 0 && host.importedHeight > 0
                                    ? host.importedWidth * host.importedHeight
                                    : (host.importCapPixels > 0 ? host.importCapPixels
                                       : host.sourceWidth * host.sourceHeight);
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
                                final float[] wbMul = host.wbUserMul;
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
                                    DebugSink.text("export-open", "pex=" + host.f("%.6f", host.printExposure)
                                            + " auto=" + exportAutoExposure
                                            + " pin=" + (pinEv ? host.f("%.6f", host.lastAppliedEv) : "none")
                                            + " offset=" + host.f("%.4f", host.cameraEv)
                                            + " paper=" + host.printStock + " raw=" + host.rawFilePath);
                                } catch (Throwable spkIgnored) { Diagnostics.ignored("getHeight:15085", spkIgnored); }
                                final String openJson = app.spektralab.domain.ExportPlan.openParams(
                                        host.printStock, exportAutoExposure, host.cameraEv, host.printExposure);
                                fresh = wbMul != null
                                        ? host.engine.openRawSessionTuned(host.rawFilePath, openJson,
                                                exportPixels, exportEdge, wbMul)
                                        : host.engine.openRawSession(host.rawFilePath, openJson,
                                                exportPixels, exportEdge);
                            } catch (Throwable ignored) { fresh = null; }
                            CrashTrail.markNow(host,
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
                        if (fresh != null && keep != null && !host.batchExportRunning) {
                            final boolean wasSuspended = keepSuspended;
                            // 5.1 planes of 12 B/px plus the encoder's tables,
                            // measured on the host at 20.9 MP (`mem_export`).
                            final long needBytes = app.spektralab.domain.ExportSessionPlan
                                    .workingSetBytes(host.sourceWidth, host.sourceHeight);
                            // **More than half of what is left is more than the
                            // two of them can share.** `avail` is MemAvailable,
                            // so it already excludes the preview's own arena;
                            // this asks whether one export working set still
                            // fits in what remains after it.
                            final long avail = host.availableMemoryBytes();
                            if (app.spektralab.domain.ExportSessionPlan.closePreview(
                                    true, host.batchExportRunning, wasSuspended, needBytes, avail)) {
                                try { keep.close(); } catch (Throwable spkIgnored) { Diagnostics.ignored("availableMemoryBytes:15192", spkIgnored); }
                                if (host.session == keep) host.session = null;
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
                                host.showInAppToast(tr("export.skipping_this_photo_it_is"));
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
                            host.applyParamsTo(exportSession, host.filmStock,
                                    host.printStock == null ? "kodak_portra_endura" : host.printStock,
                                    host.PREVIEW_LONG_EDGE, "sRGB", true);
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
                                    exportSession.pinAutoExposureEv(host.lastAppliedEv);
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
                                    if (!sane) ev = host.lastAppliedEvValid ? host.lastAppliedEv : 0.0;
                                    exportSession.pinAutoExposureEv(ev);
                                    try {
                                        DebugSink.text("export-meter", "method=" + exportMeter
                                                + " solved=" + host.f("%.4f", ev)
                                                + (sane ? "" : " (clamped)")
                                                + " sameFrame=" + host.lastAppliedEvValid);
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
                                        + " pin=" + (pinEv ? host.f("%.4f", host.lastAppliedEv) + " same-frame" : "none")
                                        + " seenEv=" + host.f("%.4f", host.lastAppliedEv)
                                        + (host.lastAppliedEvValid ? "" : " (none)")
                                        + " solveEv=" + host.f("%.4f", host.meteredEv)
                                        + " offset=" + host.f("%.2f", host.cameraEv));
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
                                if (host.rawFilePath != null && host.session != null) {
                                    final String[] meta = host.sourceExifForExport();
                                    SpektraLabEngine.setExportExif(meta[0], meta[1], meta[2],
                                            meta[3], meta[4], meta[5], meta[6]);
                                    DebugSink.text("exif", "raw source metadata: "
                                            + meta[0] + " / " + meta[1] + " / " + meta[2]
                                            + " / " + meta[3] + "s / f" + meta[4]
                                            + " / ISO" + meta[5] + " / " + meta[6] + "mm");
                                } else if (host.sourceUri != null) {
                                    final String[] meta = host.jpegSourceExif();
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
                            if (host.batchExportAbort) throw new IllegalStateException(tr("export.stopped"));
                            CrashTrail.markNow(host, "export:write " + tier + " " + format);
                            Thread wtick = host.startTick("export", exportSession);
                            try {
                                info = host.exportController.export(exportSession, format, tier,
                                        tmp.getAbsolutePath(), 95);
                            } finally {
                                host.stopTick(wtick);
                            }
                            CrashTrail.markNow(host, "export:written " + info[0] + "x" + info[1]);
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
                                try { host.engine.trimPool(); } catch (Throwable spkIgnored) { Diagnostics.ignored("getParams:15388", spkIgnored); }
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
                                    if (!host.uiReady) return;
                                    if (host.currentFrame < 0 || host.currentFrame >= host.frames.size()) return;
                                    CrashTrail.mark(host, "export:reopen after close");
                                    host.reopenFrameForced(host.currentFrame);
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
                            final long freed = host.engine.trimPool();
                            DebugSink.text("export", "pool trim freed "
                                    + (freed >> 20) + " MiB; report " + host.engine.memoryReport());
                        } catch (Throwable spkIgnored) { Diagnostics.ignored("trimPool:15418", spkIgnored); }
                    }
                } finally {
                    spinning.set(false);
                    try { memTicker.interrupt(); } catch (Throwable spkIgnored) { Diagnostics.ignored("trimPool:15422", spkIgnored); }
                    UiDispatcher.post(() -> {
                        if (host.beforeBitmap == null) host.beforeBitmap = savedBefore;
                        if (host.lastRenderedBitmap == null) host.lastRenderedBitmap = savedAfter;
                        if (host.compare != null) {
                            host.compare.setBefore(host.beforeBitmap);
                            host.compare.setAfter(host.lastRenderedBitmap);
                        }
                    });
                }
                host.uploadExportProgress(tmp);
                host.uploadVkLog();
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
                UiDispatcher.post(() -> host.statusText.setText(tr("export.exporting_saving_to_the_photo") + fw + "x" + fh + "..."));
                String fileName = "SpektraLab_" + System.currentTimeMillis() + ext;
                final Uri outUri = host.exportStore.write(tmp, fileName, ext, mime);
                // **Does the file look like the picture we think we wrote?**
                // Two measurements of the smoothed result: the mean difference
                // between neighbouring rows and between neighbouring columns.
                // A photograph has both (their ratio sits near 1); a frame
                // stretched along one axis does not. Cheap enough for every
                // export, and the only evidence a "garbled, once, never again"
                // report can leave behind.
                host.exportCorruptNow = false;
                {
                    // The canvas is only *this* frame's while a batch is not
                    // walking the strip behind our back.
                    final double ncc = host.batchExportRunning ? -1
                            : app.spektralab.data.ExportVerifier.matchesCanvas(tmp.getAbsolutePath(), host.lastRenderedBitmap);
                    if (ncc >= 0 && ncc < 0.55) {
                        host.exportCorruptNow = true;
                        try {
                            DebugSink.textNow("export-corrupt",
                                    String.format(java.util.Locale.US,
                                        "canvas NCC %.3f at %dx%d tier=%s overscan=%s — file kept? no, retrying",
                                        ncc, w, h, tier, host.overscanOn) + "\n" + host.reportHead());
                        } catch (Throwable spkIgnored) { Diagnostics.ignored("writeExportFile:15469", spkIgnored); }
                    } else if (ncc >= 0) {
                        try { DebugSink.text("export-selfcheck", String.format(
                                java.util.Locale.US, "canvas NCC %.3f", ncc)); }
                        catch (Throwable spkIgnored) { Diagnostics.ignored("writeExportFile:15473", spkIgnored); }
                    }
                }
                {
                    final String verdict = host.exportCorruptNow ? null : app.spektralab.data.ExportVerifier.inspect(tmp.getAbsolutePath());
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
                            if (host.statusText != null) host.statusText.setText(tr("export.the_export_did_not_pass") + verdict);
                            host.showInAppToast(tr("export.the_export_may_be_corrupted"));
                        });
                    }
                }
                // **And the one measurement the edge probe already made.**
                // It only ever reached a file nobody reads, which is why a
                // "column of bars on the left edge" report could not be tied to
                // a side, a spacing or a canvas size. One upload, only when the
                // answer is not null: a clean export stays silent.
                {
                    final String ruler = host.edgeRulerVerdict(tmp.getAbsolutePath() + ".prog");
                    if (ruler != null) {
                        // **Synchronous.** This runs on the export worker, not
                        // the UI thread, and the whole point is that the report
                        // survives the user switching away mid-export -- the
                        // queued path is exactly what lost the last three.
                        try {
                            DebugSink.textNow("edge-ruler", ruler + "\n" + host.reportHead());
                        } catch (Throwable spkIgnored) { Diagnostics.ignored("getAbsolutePath:15509", spkIgnored); }
                        UiDispatcher.post(() -> {
                            if (host.statusText != null) host.statusText.setText(tr("export.edge_check") + ruler);
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
                    host.lastExportUri = outUri;
                    host.lastExportMime = mime;
                    // A separate control, and only when it has something to do:
                    // every other format opens where it lands, and a permanent
                    // share button on a photograph app reads as "post to
                    // social", which is not what this is.
                    if (host.sharePill != null) host.sharePill.setVisibility((tiff || dng) ? View.VISIBLE : View.GONE);
                    // Dimensions and file size together: "3.3 MB" alone reads as
                    // a bug when the frame is 16 MP, and the reason is worth
                    // saying out loud rather than guessing at later.
                    final String canvas = host.overscanCanvasLabel();
                    final String canvasNote = canvas.isEmpty() ? "" : tr("export.canvas") + canvas;
                    final String tiffHint = tiff
                            ? tr("export.tiff_use_share_next_to")
                            : dng ? tr("export.dng_16_bit_linear_for") : "";
                    if (!host.exportCorruptNow)
                        host.showInAppToast(tr("export.saved") + fw + "x" + fh + canvasNote + " · " + sizeLabel + tiffHint);
                    else
                        host.showInAppToast(tr("export.the_export_did_not_pass_2"));
                    host.statusText.setText(tr("export.exported") + fw + "x" + fh + canvasNote + " · " + sizeLabel
                        + " (" + secs + tr("export.s_including") + (fRender / 1000.0) + tr("export.s_rendering") + tiffHint);
                    host.exportProgressBar.setVisibility(View.GONE);
                    host.setStep(2);
                });
            } catch (Throwable e) {
                final String msg = host.describe(e);
                DebugSink.text("failure", AppFailure.of(AppFailure.Stage.EXPORT, e).stage
                        + ": " + msg);
                try { DebugSink.text("export-error", "tier=" + tier + " " + msg); } catch (Throwable spkIgnored) { Diagnostics.ignored("describe:15561", spkIgnored); }
                host.uploadExportProgress(tmp);
                host.uploadVkLog();
                UiDispatcher.post(() -> {
                    host.statusText.setText(tr("export.export_failed") + SpkError.shortForm(msg));
                    host.showErrorDialog(tr("export.export_failed_2"), SpkError.format(msg)
                            + "\n\n" + host.sourceWidth + "×" + host.sourceHeight
                            + "\n" + host.probe.describe());
                    host.exportProgressBar.setVisibility(View.GONE);
                    host.setStep(2);
                });
                final int code = SpkError.codeOf(msg);
                if (code == 1001 || code == 1009) host.offerSmallerImport();
            } finally {
                if (tmp.exists()) tmp.delete();
                // **An export that came back as noise is not an export.**
                // The render cost fifteen seconds; a silent bad file costs the
                // user a trip to the gallery and a bug report. Retry once, on
                // the UI thread, and let the second attempt report.
                if (host.exportCorruptNow && !host.exportRetried && !host.batchExportRunning) {
                    host.exportRetried = true;
                    UiDispatcher.post(() -> {
                        if (host.statusText != null) host.statusText.setText(tr("export.the_export_did_not_pass_3"));
                        host.exportImage(format, tier, onDone);
                    });
                    return;
                }
                if (host.exportCorruptNow) {
                    UiDispatcher.post(() -> host.showInAppToast(tr("export.the_export_may_be_corrupted_2")));
                }
                if (onDone != null) UiDispatcher.post(onDone);
            }
        }).start();
      }
}
