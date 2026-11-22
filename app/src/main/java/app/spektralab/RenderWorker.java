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
final class RenderWorker {
    private final MainActivity host;

    RenderWorker(MainActivity host) { this.host = host; }

    void run(int longEdge) {
        // The full-size pass owns the canvas from here. A live frame still in
        // flight would otherwise land a moment later and put 800 px on top of
        // the result the user waited for.
        host.cancelLivePreview();
        host.fullGeneration.incrementAndGet();
        CrashTrail.mark(host, "render:begin");
        // **A decode in flight owns the session.** It is closed for a few
        // seconds while LibRaw re-demosaics; a render that walked in then would
        // either complain about a null session or queue behind the decode for
        // the whole of it, which is what "白平衡卡死" was.
        if (host.wbDecoding) { host.pendingRender = true; return; }
        // Every branch says something. The foot is one thin line, too quiet to
        // carry "nothing happened"; failures need to be readable.
        if (host.session == null) {
            try { DebugSink.text("render", "no session, lastImportError=" + host.lastImportError); }
            catch (Throwable spkIgnored) { Diagnostics.ignored("renderImage:13656", spkIgnored); }
            // **A frame in the strip means an import is on its way.** Coming
            // back from the background walks through here while `loadImage` is
            // still decoding -- there is a picture loading, so "还没有可渲染的
            // 图像" is the wrong sentence and the dialog is pure noise. Ask for
            // the render again once the import finishes; if the import really
            // failed, its own message is the one to show.
            if (!host.frames.isEmpty() && host.lastImportError == null) {
                host.pendingRender = true;
                return;
            }
            host.showErrorDialog(tr("render.no_image_to_render"), host.lastImportError == null
                    ? tr("render.import_a_photo_first_tap")
                    : tr("render.the_last_import_did_not") + host.lastImportError);
            return;
        }
        if (host.filmStock == null) {
            host.showInAppToast(tr("render.choose_a_film_below"));
            return;
        }
        host.stopPrefetch();
        if (host.rendering) {
            // One native result buffer for the whole process: two renders would
            // interleave on it and hand back another frame's dimensions.
            try { DebugSink.text("render", "already rendering"); } catch (Throwable spkIgnored) { Diagnostics.ignored("renderImage:13680", spkIgnored); }
            Probe.log("render", "queued film=" + host.filmStock + " pending=" + host.pendingRender);
            host.statusText.setText(tr("render.rendering"));
            // Not dropped: a tap that lands mid-render is the *last* choice the
            // user made, so it becomes the next render instead of a toast.
            host.pendingRender = true;
            host.showInAppToast(tr("render.rendering_will_render_again_with"));
            return;
        }
        host.scheduleUndo();          // one entry per settled edit
        // UDF: capture one immutable snapshot on the UI thread. The worker
        // below must not read the Activity's mutable look fields any more.
        final app.spektralab.domain.EditorState editorState =
                new app.spektralab.domain.EditorState(
                        host.filmStock,
                        host.printStock == null ? "kodak_portra_endura" : host.printStock,
                        host.meterMethod, host.currentFrame, host.showingOriginal, host.rendering, host.geometrySeq);
        final String renderedStock = editorState.filmStock;
        // **The frame this render belongs to.** Deleting the chips (or opening
        // another photo) while a render is in flight used to let its callback
        // call setStage(1) on a canvas that no longer had a frame: the empty
        // state -- and with it the 选择照片 button -- vanished, which is the
        // "删掉全部缩略图后导入按钮有时候会消失" report.
        final int renderedFrame = editorState.frameIndex;
        final int renderedEpoch = host.renderEpoch;
        // **Which geometry this render is allowed to speak for.** A render the
        // commit did not wait for carries the angle from before it, and letting
        // it take the preview turn off is exactly the frame that jumps back.
        final int renderedGeometrySeq = editorState.geometrySequence;
        host.rendering = true;
        host.progressBar.setVisibility(View.VISIBLE);
        host.busyLabel.setVisibility(View.VISIBLE);
        host.statusText.setText(tr("render.rendering"));
        try { DebugSink.text("render", "begin " + host.sourceWidth + "x" + host.sourceHeight); }
        catch (Throwable spkIgnored) { Diagnostics.ignored("renderImage:13707", spkIgnored); }

        new Thread(() -> {
            try {
                synchronized (host.renderMutex) {
                if (host.session == null) {
                    // **A frame change can land between the check the UI thread
                    // made and this lock.** Throwing here turned that race into
                    // a dialog on top of a batch export that had already
                    // finished ("加片边批量导出后弹错 / 会话状态异常"). The frame
                    // is genuinely gone, so there is nothing to draw: hand the
                    // UI back the state this method claimed, and let the next
                    // import render. A rebate export makes every render 2-3 s
                    // slower, which is exactly why that window got wide enough
                    // to hit in the field and not on the bench.
                    CrashTrail.mark(host, "render:session-gone");
                    UiDispatcher.post(() -> {
                        host.rendering = false;
                        if (host.progressBar != null) host.progressBar.setVisibility(View.GONE);
                        if (host.busyLabel != null) host.busyLabel.setVisibility(View.GONE);
                    });
                    return;
                }
                // The queue stops here, but a card it started before still
                // finishes and can put a stock back onto this session. Take the
                // film the user picked, under the same lock the render runs
                // under, so the picture and the edge text cannot disagree.
                CrashTrail.markNow(host, "render:lock");
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
                final boolean geoOwed = host.pendingGeometry;
                host.pendingGeometry = false;
                // The use case applies the captured state and then renders;
                // no Activity field is read by the worker after this point.
                Thread ticker = host.startTick("render", host.session);
                CrashTrail.markNow(host, "render:call");
                int[] dims;
                try {
                    dims = host.renderController.begin(
                            host.session, editorState, "live", longEdge, false,
                            (target, state, edge) -> {
                                host.applyParams(state.filmStock, (int) edge);
                                if (geoOwed) {
                                    host.sendGeometry();
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
                                        + " long=" + edge + " src=" + host.sourceWidth + "x" + host.sourceHeight);
                                CrashTrail.markNow(host, "render:params");
                            });
                } finally {
                    host.stopTick(ticker);
                }
                int w = dims[0], h = dims[1];
                CrashTrail.markNow(host, "render:returned " + w + "x" + h);
                if (w <= 0 || h <= 0) throw new RuntimeException("engine returned empty frame");

                CrashTrail.markNow(host, "render:alloc " + w + "x" + h);
                Bitmap bmp = host.newFrameBitmap(w, h);
                int[] pixels = new int[w * h];
                CrashTrail.markNow(host, "render:copy");
                int copied = host.session.copyArgbRange(pixels, 0, h);
                if (copied != w * h) {
                    throw new RuntimeException("pixel copy incomplete " + copied + "/" + (w * h));
                }
                bmp.setPixels(pixels, 0, w, 0, 0, w, h);
                CrashTrail.markNow(host, "render:setPixels");
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
                        final String verdict = host.edgeVerdictOfLine(edgeLine);
                        if (verdict != null) {
                            DebugSink.textNow("edge-canvas", verdict);
                        } else if (edgeLine != null && host.edgeCanvasSends < 4) {
                            // Below the threshold, but say what it measured:
                            // knowing that preview reads 40 and full reads 8 on
                            // the same picture is the whole answer.
                            ++host.edgeCanvasSends;
                            DebugSink.textNow("edge-weak", edgeLine);
                        }
                    } catch (Throwable spkIgnored) { Diagnostics.ignored("edgeVerdictOfLine:13811", spkIgnored); }
                }
                host.session.renderEnd();
                CrashTrail.markNow(host, "render:end");
                // What the meter did to *this* frame. A render that looks dark
                // is either the film's curve or an exposure the engine decided
                // on, and the two are indistinguishable by eye -- one line of
                // numbers settles it.
                try {
                    host.rememberAppliedEv(host.session.progressJson(), "full");
                } catch (Throwable spkIgnored) { Diagnostics.ignored("edgeVerdictOfLine:13821", spkIgnored); }
                // The rebate's own numbers, next to the buffer they belong to.
                // "the edge is wrong in the export but right in the preview" is
                // a question only these two lines can answer.
                try {
                    DebugSink.text("overscan-geom", "tier=live " + w + "x" + h + " "
                            + host.session.overscanGeometry());
                } catch (Throwable spkIgnored) { Diagnostics.ignored("edgeVerdictOfLine:13828", spkIgnored); }
                // The engine's log for this render. When a frame comes back as
                // one flat colour there is nothing else to read: it says which
                // tier was built, how big, and what each dispatch covered.
                host.uploadVkLog();

                final int fw = w, fh = h;
                // Diagnostic only: the hash and the two sample pixels are what the
                // "wrong picture" reports are compared against. The preview used to
                // also be JPEG-compressed and handed to DebugSink.bytes(), which is
                // a no-op -- pure work on the render thread for a byte array nobody
                // reads. Keep the numbers, drop the encode (see issue #1 in the audit).
                try {
                    final long hsh = PixelHash.fnv1a(pixels, pixels.length, 7);
                    final String hashLine = "w=" + w + " h=" + h + " hash=" + Long.toHexString(hsh)
                            + " band=" + host.testBandRows + " copy=" + copied
                            + " first=" + Integer.toHexString(pixels[0])
                            + " mid=" + Integer.toHexString(pixels[(h / 2) * w + w / 2]);
                    DebugSink.text("preview", hashLine);
                    // logcat hook for scripts/device-golden.ps1 (no network needed).
                    android.util.Log.i("SpektraLabHash", hashLine);
                } catch (Throwable spkIgnored) { Diagnostics.ignored("edgeVerdictOfLine:13846", spkIgnored); }
                UiDispatcher.post(() -> {
                    if (renderedEpoch != host.renderEpoch
                            || host.currentFrame != renderedFrame || host.session == null) {
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
                        host.rendering = false;
                        host.clearBusyPlate();
                        if (host.pendingRender) {
                            host.pendingRender = false;
                            if (host.filmStock != null && host.session != null) host.renderImage();
                        }
                        return;
                    }
                    host.showingOriginal = false;
                    host.compare.setAfter(bmp);
                    if (host.rotationPending && renderedGeometrySeq >= host.geometrySeq) {
                        host.rotationPending = false;
                        host.compare.setPreviewRotation(0f);
                    }
                    host.setStage(1, null);
                    // Give the pool back if this render left it far larger than
                    // the frame -- ten shoot-layer edits in a row is what parks
                    // gigabytes and gets the launcher killed. Off the UI thread:
                    // trimming destroys buffers and waits on the device.
                    new Thread(host::trimPoolIfBloated, "pool-trim").start();
                    host.setStep(2);
                    host.lastRenderedBitmap = bmp;
                    // The histogram describes what the canvas shows, so it moves
                    // with the render, not with the tab that displays it.
                    if (host.histogram != null) host.histogram.setBitmap(bmp);
                    host.refreshComparePill();
                    host.cacheFilmThumb(renderedStock, bmp);
                    host.updateCardThumb(renderedStock);
                    host.updateFilmSelection();
                    // A user render stops the queue so it can own the GPU. Put
                    // it back afterwards, or the rest of the shelf never fills.
                    host.startPrefetch();
                    // A finished render is exactly what makes both pages live
                    // -- and it is the only place that does.
                    host.refreshPanelLocks();
                    host.setOriginalVisual(false);
                    host.updateFilmSelection();
                    final int[] bands = host.blackBands(bmp);
                    host.statusText.setText(tr("render.preview_ready") + fw + "×" + fh);
                    try {
                        DebugSink.text("bands", "render " + fw + "x" + fh
                                + " black rows top=" + bands[0] + " bottom=" + bands[1]
                                + " source=" + host.sourceWidth + "x" + host.sourceHeight);
                    } catch (Throwable spkIgnored) { Diagnostics.ignored("blackBands:13904", spkIgnored); }
                    host.progressBar.setVisibility(View.GONE);
                    host.busyLabel.setVisibility(View.GONE);
                    host.rendering = false;
                    // The canvas now shows this look; it is also the version
                    // that has to survive a kill from recents.
                    host.schedulePersist();
                    if (host.pendingRender) { host.pendingRender = false; host.renderImage(); }
                });
            }
            } catch (Throwable e) {
                try { host.session.renderEnd(); } catch (Throwable spkIgnored) { Diagnostics.ignored("blackBands:13915", spkIgnored); }
                DebugSink.text("failure", AppFailure.of(AppFailure.Stage.RENDER, e).stage
                        + ": " + host.describe(e));
                final String msg = host.describe(e);
                UiDispatcher.post(() -> {
                    // **A rotation that failed must not stay half applied.** The
                    // numbers were committed before the render and the canvas
                    // still shows the view-space turn; leaving both is a picture
                    // that looks turned and exports straight. Put the angle back
                    // and take the preview rotation off in the same frame.
                    if (host.rotationPending) {
                        host.rotationPending = false;
                        host.geometryRotation = host.rotationBeforeDrag;
                        if (host.compare != null) host.compare.setPreviewRotation(0f);
                        if (host.rotateArc != null) host.rotateArc.setAngle(host.geometryRotation);
                    }
                    // **A failed render is not an edit.** `scheduleUndo` runs
                    // before the render does, so a failure would still push the
                    // state that caused it -- and undo then replayed the error
                    // instead of the picture before it.
                    host.cancelUndo();
                    host.statusText.setText(tr("render.render_failed") + SpkError.shortForm(msg));
                    host.progressBar.setVisibility(View.GONE);
                    host.busyLabel.setVisibility(View.GONE);
                    host.rendering = false;
                    // The foot is one 7 sp line: a failure has to be readable,
                    // and it has to arrive with enough context to be reported.
                    host.showErrorDialog(tr("render.render_failed_2"), SpkError.format(msg)
                            + "\n\n" + host.sourceWidth + "×" + host.sourceHeight
                            + "\n" + host.probe.describe());
                    if (host.pendingRender) { host.pendingRender = false; host.renderImage(); }
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
                if (code == 1001 || code == 1009) host.offerSmallerImport();
            }
        }).start();
      }
}
