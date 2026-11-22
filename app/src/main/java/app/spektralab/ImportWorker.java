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
final class ImportWorker {
    private final MainActivity host;

    ImportWorker(MainActivity host) { this.host = host; }

    void run(Uri uri) {
        CrashTrail.mark(host, "import:load " + uri);
        // Whatever is rendering belongs to the photograph being replaced.
        host.renderEpoch++;
        // **The previous photograph's dimensions are not this one's.**
        // `originalAspect()` prefers `importedWidth` when it is set, and only
        // the RAW path ever set it. So a RAW followed by a JPEG kept the RAW's
        // shape: on a 6912x4530 raw then a 1280x1708 jpeg, `refreshEdgeFormats`
        // logged `aspect=1.5258` for the *portrait* frame it had just imported,
        // picked the 135 gate, and the render then refused it with
        // "a 1280 x 1708 frame is not the 135 gate's shape" -- E-1305.
        host.importedWidth = 0;
        host.importedHeight = 0;
        // A profile belongs to one file. The RAW branch below attaches this
        // frame's; a JPEG has none, and the canvas falls back to plain sRGB.
        host.sourceToneCurve = null;
        // **The previous frame's gate is not this frame's shape.** A 6x17 chosen
        // for a panorama stayed selected for the next picture, and the render
        // then refused a 1365x1024 frame with "is not the 120_6x17 gate's
        // shape". `refreshEdgeFormats` re-picks from the new aspect a few lines
        // later, so this only clears the stale choice.
        host.overscanFormat = "135";
        // **The one number that separates the two hypotheses.** A death inside
        // the decode leaves nothing but `import:load` behind, and at that point
        // "this file is corrupt" and "this file is 50 MP and the kernel
        // reclaimed us" look identical. The header costs a few milliseconds and
        // answers it before anything large is allocated.
        try {
            final int[] sz = host.sniffImageSize(uri);
            final long want = sz == null ? 0L : (long) sz[0] * sz[1] * 12L;
            CrashTrail.mark(host, "import:size "
                    + (sz == null ? "unknown" : sz[0] + "x" + sz[1])
                    + " need=" + (want >> 20) + "MB"
                    + " avail=" + (host.availableMemoryBytes() >> 20) + "MB");
            try { DebugSink.text("import-size", (sz == null ? "unknown" : sz[0] + "x" + sz[1])
                    + " need=" + (want >> 20) + "MB avail=" + (host.availableMemoryBytes() >> 20) + "MB"
                    + " native=" + host.importAtNativeSize); } catch (Throwable spkIgnored) { Diagnostics.ignored("sniffImageSize:11191", spkIgnored); }
        } catch (Throwable spkIgnored) { Diagnostics.ignored("sniffImageSize:11192", spkIgnored); }
        // A new photograph starts at the two entry cards, never on a page
        // whose switch belonged to the frame before it.
        CrashTrail.mark(host, "import:page");
        if (host.edgePageIndex >= 0) host.openEdgePage(-1);
        // A frame the strip remembers but the system will not open any more:
        // say so in one sentence instead of letting a SecurityException be
        // classified as [E-9000] 未归类.
        if (!host.canReadUri(uri)) {
            // **A revoked grant is permanent, so the chip goes with it.**
            // Keeping it meant this dialog on every launch -- sixty-six of them
            // in a week, the largest single error in the database -- and the
            // only way out was 「关闭」, which changed nothing. Removed once,
            // here, and the strip is honest afterwards.
            final int dead = host.currentFrame;
            host.showErrorDialog(tr("import.photo_access_expired"),
                    tr("import.android_revoked_temporary_access_to")
                    + tr("import.it_was_removed_from_the")
                    + tr("import.the_app_will_keep_its"));
            if (dead >= 0 && dead < host.frames.size() && host.frames.get(dead).uri.equals(uri)) {
                host.removeFrame(dead);
            }
            return;
        }
        CrashTrail.mark(host, "import:canread");
        host.sourceUri = uri;
        // A different file means a different sensor balance; the previous
        // frame's gains must not be handed to it.
        host.wbUserMul = null;
        // The frame's own look, stated before any session is built: a chip
        // returns to a photograph *and* to what was done to it; a pick from ＋
        // starts with no film at all.
        if (host.reopeningFrame && host.currentFrame >= 0 && host.currentFrame < host.frames.size()) {
            host.applyLook(host.frames.get(host.currentFrame).look);
        } else {
            // **A reopen with no frame to reopen is a new photograph.** This
            // used to leave the global fields untouched when the index was
            // gone (every chip deleted, then ＋), so the new frame inherited
            // the previous one's print exposure and meter switch -- reported
            // as "重新导入一张还是黑灰", gone after a restart for the obvious
            // reason.
            host.reopeningFrame = false;
            host.applyLook(host.defaultLook());
        }
        if (host.testDeterministic) {
            // Grain and the paper veil are seeded per frame; switch them off so
            // two renders of the same picture are byte-comparable.
            host.grainOn = 0f;
            host.glareActive = 0f;
        }
        if (host.testAutoFilm && host.filmStock == null) {
            for (String id : host.filmIds) {
                if (!"original".equals(id)) { host.filmStock = id; break; }
            }
        }
        CrashTrail.mark(host, "import:look");
        try {
            // Check if this is a RAW file by extension
            String fileName = host.getFileName(uri);
            CrashTrail.markNow(host, "import:name " + fileName);
            final String lower = fileName == null ? "" : fileName.toLowerCase();
            host.isRawFile = lower.endsWith(".rw2") || lower.endsWith(".raw")
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
                    host.showInAppToast(tr("import.e_1107_unsupported_format") + ext + tr("import.suffix"));
                    return;
                }
            }
            
            if (host.isRawFile) {
                host.loadRawImage(uri);
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
            if (!host.importAtNativeSize && !host.importBudgetAccepted && !host.suppressBudgetDialog) {
                final int[] full = host.sniffImageSize(uri);
                if (full != null) {
                    final long area = (long) full[0] * (long) full[1];
                    final int cap = host.maxImportPixels();
                    if (area > cap || Math.max(full[0], full[1]) > host.IMPORT_LONG_EDGE) {
                        host.showImportBudgetDialog(uri, full[0], full[1], cap);
                        return;
                    }
                }
            }
            host.importBudgetAccepted = false;

            // Retire the shelf queue *before* the session it is holding goes
            // away. Its generation is the only thing that makes a card which
            // is already rendering drop its bitmap instead of filing the old
            // photograph's picture under this photograph's films: without this
            // bump the queue wrote last frame's thumbnails into `filmThumbs`,
            // every card then looked right in the map (`containsKey`), and the
            // new queue skipped them forever -- cards showing the previous
            // photo until each one was tapped.
            host.stopPrefetch();
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
            synchronized (host.renderMutex) {
                if (host.session != null) { host.session.close(); host.session = null; }
            }
            // **The old frame's planes go before the new one asks for its own.**
            // `spk_open` allocates the decoded source while the previous
            // session's pool is still resident. On a 16 GB phone a 32 MP pool
            // plus a 50 MP frame is more than the app's cgroup will hand out,
            // and it surfaces as `cannot allocate Vulkan result pixels` ->
            // E-1003 even though the frame alone would have fit. The export
            // path already trims for exactly this reason.
            try { if (host.engine != null) host.engine.trimPool(); } catch (Throwable spkIgnored) { Diagnostics.ignored("maxImportPixels:11315", spkIgnored); }
            host.lastRenderedBitmap = null;
            host.refreshPanelLocks();
            // The plate on the canvas belongs to the frame we just left.
            host.clearBusyPlate();
            host.dateText = null;
            host.setStage(0, null);
            // The white-balance half is RAW-only; tell it which file this is.
            host.refreshViewPanel();
            host.sourceBitmap = null;
            // `allocDirect` has no cleaner: dropping the field frees nothing.
            if (host.sourcePixels != null) { SpektraLabEngine.freeDirect(host.sourcePixels); host.sourcePixels = null; }
            // The previous file's unpacked plane is not this frame's problem.
            try { SpektraLabEngine.clearRawCache(); } catch (Throwable spkIgnored) { Diagnostics.ignored("maxImportPixels:11328", spkIgnored); }
            host.filmThumbs.clear();
            // The canvas is not a control. `setOriginalVisual` only paints the
            // 原图 button; what CompareView holds is `after` (the last render)
            // and it survives a new session. That was the whole "I changed the
            // photo and the canvas still shows the old one" report: the frame
            // was never dropped, so the next render was the first thing to
            // replace it.
            if (host.compare != null) host.compare.clear();
            System.gc();

            // Cap by area, the way the RAW path does. inSampleSize only halves,
            // so one exact scale follows if the sample still overshoots.
            final boolean nativeSize = host.importAtNativeSize;
            host.importAtNativeSize = false;                       // one import only
            // A cap the last render failure handed us (see offerSmallerImport):
            // one import's worth, then back to the device's own answer.
            final int forcedCap = host.pendingCapPixels;
            host.pendingCapPixels = 0;
            host.e1001Offered = false;
            // An explicit "render smaller" from the failure sheet outranks the
            // native default: the user asked for that one.
            final int capPixels = forcedCap > 0 ? forcedCap
                    : (nativeSize ? Integer.MAX_VALUE : host.maxImportPixels());
            final int edgeCap = nativeSize ? Integer.MAX_VALUE : host.IMPORT_LONG_EDGE;
            // The cleanup above (shelf queue, session close, gc) is its own
            // stretch; without this mark a death there would still be reported
            // as `import:look` and blamed on the decoder.
            CrashTrail.mark(host, "import:decoding");
            host.sourceBitmap = host.decodeImport(uri, capPixels, edgeCap);
            if (host.sourceBitmap == null) {
                // The old message here was "无法解码图片", which gave the user
                // nothing to act on. A HEIC/HEIF this phone's decoder refuses is
                // the common case, so the dialog carries the way out instead of
                // describing it.
                host.showActionSheet(tr("import.e_1107_this_device_cannot"),
                        tr("import.it_may_be_heic_heif")
                                + tr("import.try_the_file_browser_if"),
                        new String[]{tr("import.file_browser"), tr("editor.cancel")}, which -> {
                            if (which == 0) host.pickRawFile();
                        });
                return;
            }
            host.sourceBitmap = host.applyExifRotation(uri, host.sourceBitmap);
            // **Where the import actually is.** `import:load` covers everything
            // from the pick to the session, which is a fifty-line stretch with
            // a 200 MB bitmap and a 600 MB direct buffer in it; when the system
            // reclaims the process mid-way the trail cannot tell a bad file
            // from a decoder that handed back 50 MP it could not then convert.
            CrashTrail.mark(host, "import:decoded " + host.sourceBitmap.getWidth() + "x"
                    + host.sourceBitmap.getHeight());
            final long area = (long) host.sourceBitmap.getWidth() * host.sourceBitmap.getHeight();
            final int longEdge = Math.max(host.sourceBitmap.getWidth(), host.sourceBitmap.getHeight());
            // Three ceilings in three units, and the strictest wins: area is
            // quadratic in the scale, the long edge is linear, and the third is
            // what this phone can actually hold -- the import's float buffer is
            // 12 B/px, which is 240 MB at 20 MP and the largest single
            // allocation the app makes. Asking before taking it is what turns
            // an OutOfMemoryError (an Error, so the old `catch (Exception)`
            // never saw it and the process died) into a smaller import.
            // The downscale decision is pure maths now (ImportPlanner), which
            // means it has JVM regression tests. "Force original" skips it.
            final long availBytes = host.availableMemoryBytes();
            final app.spektralab.domain.ImportPlanner.Plan plan = nativeSize
                    ? new app.spektralab.domain.ImportPlanner.Plan(
                            1.0, host.sourceBitmap.getWidth(), host.sourceBitmap.getHeight())
                    : app.spektralab.domain.ImportPlanner.plan(
                            host.sourceBitmap.getWidth(), host.sourceBitmap.getHeight(), capPixels, edgeCap);
            final double k = plan.scale;
            if (k < 1.0) {
                final int nw = plan.width;
                final int nh = plan.height;
                Bitmap scaled = Bitmap.createScaledBitmap(host.sourceBitmap, nw, nh, true);
                if (scaled != host.sourceBitmap) host.sourceBitmap.recycle();
                host.sourceBitmap = scaled;
                try { DebugSink.text("import-scale", "k=" + k + " -> " + nw + "x" + nh
                        + " avail=" + availBytes); } catch (Throwable spkIgnored) { Diagnostics.ignored("getHeight:11412", spkIgnored); }
            }
            host.sourceWidth = host.sourceBitmap.getWidth();
            host.sourceHeight = host.sourceBitmap.getHeight();
            // The JPEG path sets these too, so nothing has to infer a shape from
            // whatever the last raw happened to be.
            host.importedWidth = host.sourceWidth;
            host.importedHeight = host.sourceHeight;
            host.refreshEdgeFormats();

            // Convert a row at a time: a full int[] of 20 MP would be another
            // 80 MB on the Java heap next to the direct buffer.
            host.rawFilePath = null;
            // 12 bytes a pixel, outside the Java heap, and the largest single
            // allocation this app makes: 240 MB at 20 MP, 600 MB at 50. The
            // engine takes float32 only (the Metal zero-copy door is refused by
            // the Vulkan backend), so it cannot be made smaller -- but it can be
            // made *legible*: an OutOfMemoryError here is caught and explained,
            // and only a kernel reclaim leaves the trail without this mark.
            final long pixelBytes = (long) host.sourceWidth * host.sourceHeight * 3L * 4L;
            CrashTrail.mark(host, "import:pixels " + (pixelBytes >> 20) + "MB avail="
                    + (host.availableMemoryBytes() >> 20) + "MB");
            try { DebugSink.text("import-pixels", host.sourceWidth + "x" + host.sourceHeight
                    + " need=" + (pixelBytes >> 20) + "MB avail="
                    + (host.availableMemoryBytes() >> 20) + "MB"); } catch (Throwable spkIgnored) { Diagnostics.ignored("getHeight:11436", spkIgnored); }
            // **malloc, not allocateDirect.** The runtime caps direct-buffer
            // memory at the heap growth limit (512 MB on the phone that
            // reported this), and a 50 MP frame is 576 MB of float32, so the
            // old call was refused with 4.3 GB free -- and the message blamed
            // the device. This one asks for memory that exists.
            try {
                host.sourcePixels = SpektraLabEngine.allocDirect(pixelBytes)
                        .order(ByteOrder.nativeOrder());
            } catch (Throwable oom) {
                throw new IllegalStateException(tr("import.this_image_needs") + (pixelBytes >> 20)
                        + tr("import.mb_of_memory_the_device")
                        + (host.availableMemoryBytes() >> 20) + " MB)."
                        + tr("import.import_the_photo_again_and"), oom);
            }
            final int[] rowPixels = new int[host.sourceWidth];
            for (int y = 0; y < host.sourceHeight; y++) {
                host.sourceBitmap.getPixels(rowPixels, 0, host.sourceWidth, 0, y, host.sourceWidth, 1);
                for (int px : rowPixels) {
                    host.sourcePixels.putFloat(((px >> 16) & 0xFF) / 255.0f);
                    host.sourcePixels.putFloat(((px >> 8) & 0xFF) / 255.0f);
                    host.sourcePixels.putFloat((px & 0xFF) / 255.0f);
                }
            }
            host.sourcePixels.rewind();
            
            final int pw = Math.max(1, Math.round(host.sourceWidth * Math.min(1f, 1024f / Math.max(host.sourceWidth, host.sourceHeight))));
            final int ph = Math.max(1, Math.round(host.sourceHeight * Math.min(1f, 1024f / Math.max(host.sourceWidth, host.sourceHeight))));
            // **`createScaledBitmap` hands back the source itself when the
            // target size already matches**, and a bitmap straight out of
            // `BitmapFactory` is immutable, so that is the branch every picture
            // whose long edge is at most 1024 takes. The recycle below then left
            // `compare` holding a dead bitmap: the next draw threw
            // "Canvas: trying to use a recycled bitmap", and because the same
            // picture is re-imported by `restoreSessionState` the app died on
            // every launch until its data was cleared.
            Bitmap preview = Bitmap.createScaledBitmap(host.sourceBitmap, pw, ph, true);
            if (preview == host.sourceBitmap) {
                preview = host.sourceBitmap.copy(Bitmap.Config.ARGB_8888, false);
                if (preview == null) preview = host.sourceBitmap;
            }
            host.beforeBitmap = preview;
            host.compare.setBefore(host.beforeBitmap);
            if (host.sourceBitmap != host.beforeBitmap) host.sourceBitmap.recycle();
            host.sourceBitmap = null;
            host.statusText.setText(tr("import.local_preview"));
            host.setStage(1, host.getFileName(uri));
            host.rememberFrame(uri);
            host.rebuildFilmCards();
            host.setStep(1);
            
            // Create engine and session
            CrashTrail.mark(host, "import:engine");
            host.ensureEngine();
            CrashTrail.mark(host, "import:engine-ok");
            synchronized (host.renderMutex) {
                if (host.session != null) { host.session.close(); host.session = null; }
            }
            host.session = host.engine.openSession(host.sourcePixels, host.sourceWidth, host.sourceHeight, 3);
            CrashTrail.mark(host, "import:session");
            host.applySessionParams();
            host.lastImportError = null;
            // An import is not a look. The frame lands unrendered with no film
            // chosen; the first render happens when the user picks a stock.
            host.showingOriginal = true;
            host.lastRenderedBitmap = null;
            host.setOriginalVisual(true);
            host.rebuildFilmCards();
            final boolean stayOnOriginal = host.reopenToOriginal;
            host.reopenToOriginal = false;
            if (host.filmStock != null && stayOnOriginal) {
                // **Left on 原图, opens on 原图.** The look is still there -- the
                // film card is what the next tap develops -- but re-rendering
                // here is what made a chip jump back onto a film the user had
                // deliberately stepped off.
                host.showingOriginal = true;
                host.setOriginalVisual(true);
                host.updateFilmSelection();
                host.statusText.setText(tr("film.original_ungraded_raw_preview_choose"));
                if (!host.batchExportRunning) host.startPrefetch();
            } else if (host.filmStock != null) {
                // A chip came back with a look: develop it again rather than
                // making the user re-pick a film that was never unpicked.
                host.showingOriginal = false;
                host.updateFilmSelection();
                // **Not while a batch is exporting.** Each frame is opened only
                // to hand it to `exportImage`, which builds its own full-size
                // session; a preview pass here is a second render of the same
                // frame, and it holds `rendering` up long enough that the next
                // parameter touch answers "渲染中". The batch's order is
                // load -> export, with nothing in between.
                if (!host.batchExportRunning) host.renderImage();
            } else {
                host.statusText.setText(tr("import.not_rendered_choose_a_film") + host.importSizeNote(host.sourceWidth, host.sourceHeight));
                if (!host.batchExportRunning) host.startPrefetch();
            }
        } catch (Throwable t) {
            DebugSink.text("failure", AppFailure.of(AppFailure.Stage.IMPORT, t).stage
                    + ": " + host.describe(t));
            host.lastImportError = host.describeImportFailure(t);
            host.showErrorDialog(tr("import.import_failed"), host.lastImportError);
            try { DebugSink.text("import-failed", host.lastImportError); } catch (Throwable spkIgnored) { Diagnostics.ignored("max:11537", spkIgnored); }
        }
      }
}
