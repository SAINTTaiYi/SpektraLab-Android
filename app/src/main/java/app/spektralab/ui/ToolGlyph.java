package app.spektralab.ui;

import app.spektralab.ui.UiPalette;
import android.view.View;
import android.widget.ScrollView;

public final class ToolGlyph extends android.view.View {
        static final int COMPARE = 0, CAMERA = 1, EXPORT = 2;
        private final int kind;
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float stroke;

        public ToolGlyph(android.content.Context context, int kind, int color) {
            super(context);
            this.kind = kind;
            stroke = Math.max(1f, getResources().getDisplayMetrics().density);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(color);
        }

        public void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            final float w = getWidth(), h = getHeight(), p = stroke;
            if (kind == COMPARE) {
                canvas.drawRect(p, h * 0.20f, w - p, h * 0.82f, paint);
                canvas.drawLine(w * 0.5f, h * 0.20f, w * 0.5f, h * 0.82f, paint);
                canvas.drawLine(p, h * 0.68f, w * 0.42f, h * 0.38f, paint);
                canvas.drawLine(w - p, h * 0.68f, w * 0.58f, h * 0.38f, paint);
            } else if (kind == EXPORT) {
                // A tray, and an arrow leaving it.
                canvas.drawLine(p, h * 0.58f, p, h * 0.86f, paint);
                canvas.drawLine(p, h * 0.86f, w - p, h * 0.86f, paint);
                canvas.drawLine(w - p, h * 0.86f, w - p, h * 0.58f, paint);
                canvas.drawLine(w * 0.5f, h * 0.70f, w * 0.5f, h * 0.16f, paint);
                canvas.drawLine(w * 0.5f, h * 0.16f, w * 0.28f, h * 0.38f, paint);
                canvas.drawLine(w * 0.5f, h * 0.16f, w * 0.72f, h * 0.38f, paint);
            } else {
                canvas.drawRect(p, h * 0.28f, w - p, h * 0.82f, paint);
                canvas.drawLine(w * 0.27f, h * 0.28f, w * 0.36f, h * 0.16f, paint);
                canvas.drawLine(w * 0.36f, h * 0.16f, w * 0.64f, h * 0.16f, paint);
                canvas.drawLine(w * 0.64f, h * 0.16f, w * 0.73f, h * 0.28f, paint);
                canvas.drawCircle(w * 0.5f, h * 0.55f, h * 0.16f, paint);
            }
        }
    }
