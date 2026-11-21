package app.spektralab.ui;

public final class RatioMark extends android.view.View {
        private final float aspect;
        private final int colour;
        private final float density;

        public RatioMark(android.content.Context c, float aspect, int colour) {
            super(c);
            this.aspect = aspect > 0.01f ? aspect : 1f;
            this.colour = colour;
            this.density = c.getResources().getDisplayMetrics().density;
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            final float pad = density * 7f;
            final float boxW = Math.max(0f, getWidth() - pad * 2f);
            final float boxH = Math.max(0f, getHeight() - pad * 2f);
            float w = boxW;
            float h = boxW / aspect;
            if (h > boxH) { h = boxH; w = boxH * aspect; }
            final float cx = getWidth() / 2f, cy = getHeight() / 2f;
            final android.graphics.Paint paint =
                    new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(density * 1.1f);
            paint.setColor(colour);
            canvas.drawRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, paint);
        }
    }
