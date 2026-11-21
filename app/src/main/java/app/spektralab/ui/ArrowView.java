package app.spektralab.ui;

import app.spektralab.ui.UiPalette;
import android.view.View;
import android.widget.ScrollView;

public final class ArrowView extends android.view.View {
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float stroke;

        public ArrowView(android.content.Context context, int color) {
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
