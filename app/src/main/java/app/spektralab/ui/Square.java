package app.spektralab.ui;

import app.spektralab.ui.UiPalette;
import android.view.View;
import android.widget.ScrollView;

public final class Square extends android.view.View {
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int onColor, offColor;
        private final float stroke;
        private boolean lit;

        public Square(android.content.Context context, int onColor, int offColor) {
            super(context);
            this.onColor = onColor;
            this.offColor = offColor;
            stroke = Math.max(1f, getResources().getDisplayMetrics().density);
        }

        public void setLit(boolean on) {
            lit = on;
            invalidate();
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            final float w = getWidth(), h = getHeight(), inset = stroke / 2f;
            if (lit) {
                paint.setStyle(android.graphics.Paint.Style.FILL);
                paint.setColor(onColor);
                canvas.drawRect(0, 0, w, h, paint);
            } else {
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setColor(offColor);
                canvas.drawRect(inset, inset, w - inset, h - inset, paint);
            }
        }
    }
