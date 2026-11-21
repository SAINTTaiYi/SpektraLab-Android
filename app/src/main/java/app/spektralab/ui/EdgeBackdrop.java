package app.spektralab.ui;

import app.spektralab.ui.UiPalette;

public final class EdgeBackdrop extends android.graphics.drawable.Drawable {
        private final int top;
        private final android.graphics.Paint paint = new android.graphics.Paint();
        public EdgeBackdrop(int top) { this.top = top; }
        @Override public void draw(android.graphics.Canvas canvas) {
            final android.graphics.Rect b = getBounds();
            paint.setColor(UiPalette.PAPER);
            canvas.drawRect(b, paint);
            if (top > 0) {
                paint.setColor(UiPalette.STAGE);
                canvas.drawRect(b.left, b.top, b.right, b.top + top, paint);
            }
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }
