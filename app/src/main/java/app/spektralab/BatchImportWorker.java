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
final class BatchImportWorker {
    private final MainActivity host;

    BatchImportWorker(MainActivity host) { this.host = host; }

    void run(final java.util.List<Uri> uris) {
        final int total = uris.size();
        UiDispatcher.post(() -> {
            if (host.statusText != null) host.statusText.setText(tr("export.adding") + total + tr("export.photos"));
        });
        new Thread(() -> {
            int added = 0, failed = 0;
            for (Uri u : uris) {
                final Uri target = host.persistOrCopy(u);
                if (target == null) { ++failed; continue; }
                if (host.addFrameOnly(target)) ++added; else ++failed;
            }
            final int a = added, f = failed;
            UiDispatcher.post(() -> {
                if (a > 0) {
                    host.saveSessionState();
                    host.rebuildFrameChips();
                }
                final String msg = tr("export.added") + a + tr("export.photos_2") + (f > 0 ? tr("export.skipped") + f + tr("export.photos_2") : "");
                if (host.statusText != null) host.statusText.setText(msg);
                host.showInAppToast(msg);
                if (a > 0 && host.currentFrame < 0 && !host.frames.isEmpty()) {
                    host.reopenFrame(host.frames.size() - a);
                }
            });
        }, "SpektraLab-batch-import").start();
      }
}
