package app.spektralab;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A designed card the finished photograph is placed into.
 *
 * <p>The five cards under {@code assets/frames/} are editorial layouts: a
 * ground, a window, and printing that runs over the picture. Each is stored as
 * two PNGs at the card's own size — {@code under} (everything behind the
 * photograph) and {@code over} (everything printed on top of it) — plus the
 * window rectangle. Composition is three steps: under, the photograph cropped
 * to the window, over.
 *
 * <p><b>The film's name and gauge are not in the artwork.</b> A layout that
 * always prints "KODAK PORTRA 300" is wrong the moment somebody loads C200, so
 * those two letterings are cut out of the overlay and drawn at compose time at
 * the same box, size and colour ({@code text.txt}). "FILM" and the frame index
 * stay baked: they name nothing.
 *
 * <p><b>The card's size is the print size.</b> A template is a piece of
 * stationery, not a resize knob, so the composite is whatever the template
 * declares (2000 x 1823, 1301 x 2000, …) regardless of how many pixels the
 * engine rendered. That is also why the decorated preview and the file are the
 * same picture — there is no second scale to get wrong.
 */
public final class FrameTemplate {

    /** One lettering drawn at compose time: where, how big, and in what colour. */
    public static final class Text {
        public final String slot;          // "name" or "format"
        public final float x, y, w, h;
        public final int rgb;

        Text(String slot, float x, float y, float w, float h, int rgb) {
            this.slot = slot; this.x = x; this.y = y; this.w = w; this.h = h; this.rgb = rgb;
        }
    }

    /** One card: its size, the window the photograph goes into, and its lettering. */
    public static final class Spec {
        public final String id, name;
        public final int w, h, wx, wy, ww, wh;
        final List<Text> texts = new ArrayList<>();

        Spec(String id, String name, int w, int h, int wx, int wy, int ww, int wh) {
            this.id = id;
            this.name = name;
            this.w = w;
            this.h = h;
            this.wx = wx;
            this.wy = wy;
            this.ww = ww;
            this.wh = wh;
        }
    }

    private static List<Spec> all;
    /** One decoded card at a time: the two halves are what the canvas draws. */
    private static String cachedId;
    private static Bitmap cachedUnder, cachedOver;

    private FrameTemplate() { }

    /** Every card the build ships, in the order `index.txt` lists them. */
    public static synchronized List<Spec> all(Context context) {
        if (all != null) return all;
        final List<Spec> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                context.getAssets().open("frames/index.txt"), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                final String[] p = line.split("\\|");
                if (p.length < 8) continue;
                try {
                    final Spec s = new Spec(p[0], p[1],
                            Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()),
                            Integer.parseInt(p[4].trim()), Integer.parseInt(p[5].trim()),
                            Integer.parseInt(p[6].trim()), Integer.parseInt(p[7].trim()));
                    readTexts(context, s);
                    out.add(s);
                } catch (NumberFormatException ignored) {
                    // A malformed row is one card missing, not a reason to lose the rest.
                }
            }
        } catch (Throwable t) {
            DebugSink.text("frame", "index unreadable: " + t);
        }
        all = out;
        return out;
    }

    /** `slot|boxX|boxY|boxW|boxH|RRGGBB`, one per line. Absent for cards with no lettering. */
    private static void readTexts(Context context, Spec s) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                context.getAssets().open("frames/" + s.id + "/text.txt"), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                final String[] p = line.split("\\|");
                if (p.length < 6) continue;
                s.texts.add(new Text(p[0].trim(),
                        Float.parseFloat(p[1].trim()), Float.parseFloat(p[2].trim()),
                        Float.parseFloat(p[3].trim()), Float.parseFloat(p[4].trim()),
                        Integer.parseInt(p[5].trim(), 16)));
            }
        } catch (Throwable ignored) {
            // A card without lettering is a valid card.
        }
    }

    /** The card with this id, or null -- an old Look may name one we dropped. */
    public static Spec byId(Context context, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Spec s : all(context)) if (s.id.equals(id)) return s;
        return null;
    }

    /** Drop the decoded halves; called when the choice changes so memory follows. */
    public static synchronized void forget() {
        if (cachedUnder != null) cachedUnder.recycle();
        if (cachedOver != null) cachedOver.recycle();
        cachedUnder = cachedOver = null;
        cachedId = null;
    }

    private static synchronized Bitmap half(Context context, Spec s, String part) {
        final Bitmap kept = "under".equals(part) ? cachedUnder : cachedOver;
        if (s.id.equals(cachedId) && kept != null && !kept.isRecycled()) return kept;
        Bitmap b = null;
        try {
            final BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            b = BitmapFactory.decodeStream(context.getAssets().open(
                    "frames/" + s.id + "/" + part + ".png"), null, o);
        } catch (IOException e) {
            DebugSink.text("frame", "missing " + s.id + "/" + part + ": " + e);
        }
        if ("under".equals(part)) cachedUnder = b; else cachedOver = b;
        cachedId = s.id;
        return b;
    }

    /**
     * The card with the photograph in it, at the card's own size.
     *
     * <p>{@code film} and {@code format} fill the two cut-out letterings; either
     * may be empty, in which case that line is simply left blank.
     *
     * <p>Returns null when the assets are missing, so the caller can fall back to
     * the plain render rather than show an empty card.
     */
    public static synchronized Bitmap compose(Context context, Spec s, Bitmap photo,
                                              String film, String format) {
        if (s == null || photo == null || photo.isRecycled()) return null;
        final Bitmap under = half(context, s, "under");
        final Bitmap over = half(context, s, "over");
        Bitmap out;
        try {
            out = Bitmap.createBitmap(s.w, s.h, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            DebugSink.text("frame", "createBitmap " + s.w + "x" + s.h + ": " + t);
            return null;
        }
        final Canvas c = new Canvas(out);
        final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (under != null) c.drawBitmap(under, 0f, 0f, p);
        // Cover-crop, centred: the window's shape is the card's, not the camera's.
        final float k = Math.max((float) s.ww / photo.getWidth(), (float) s.wh / photo.getHeight());
        final int cw = Math.max(1, Math.round(s.ww / k));
        final int ch = Math.max(1, Math.round(s.wh / k));
        final int cx = (photo.getWidth() - cw) / 2, cy = (photo.getHeight() - ch) / 2;
        c.drawBitmap(photo, new Rect(cx, cy, cx + cw, cy + ch),
                new Rect(s.wx, s.wy, s.wx + s.ww, s.wy + s.wh), p);
        if (over != null) c.drawBitmap(over, 0f, 0f, p);
        drawLetters(c, s, film, format);
        return out;
    }

    /**
     * The two cut-out lines, stretched to their original boxes.
     *
     * <p>The artwork sets them in a tight heavy grotesque; the app's own bold
     * sans, condensed by `setTextScaleX` to the box's width, is the closest
     * thing it can draw without shipping the designer's font.
     */
    private static void drawLetters(Canvas c, Spec s, String film, String format) {
        if (s.texts.isEmpty()) return;
        for (Text t : s.texts) {
            final String value = "name".equals(t.slot) ? film : ("format".equals(t.slot) ? format : null);
            if (value == null || value.isEmpty()) continue;
            final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            tp.setColor(0xFF000000 | t.rgb);
            // The box is the *ink* box, so the type is sized by cap height
            // (~0.72 em) rather than by the em: at `t.h` the letters would come
            // out a third smaller than the artwork they replace.
            tp.setTextSize(t.h / 0.72f);
            final float w = tp.measureText(value);
            if (w <= 0f) continue;
            final float sx = t.w / w;
            if (sx < 0.5f) {
                // A long name shrinks instead of being crushed into stripes.
                tp.setTextSize(tp.getTextSize() * Math.max(0.45f, sx * 2f));
                final float w2 = tp.measureText(value);
                tp.setTextScaleX(w2 > 0f ? Math.min(2f, t.w / w2) : 1f);
            } else {
                tp.setTextScaleX(Math.min(2f, sx));
            }
            final Paint.FontMetrics fm = tp.getFontMetrics();
            final float baseline = t.y + t.h / 2f - (fm.ascent + fm.descent) / 2f;
            c.drawText(value, t.x, baseline, tp);
        }
    }

    /** A gauge's name for the film edge: "35mm", "SUPER 35", "120", … */
    public static String formatLabel(String gauge) {
        if (gauge == null) return "";
        switch (gauge) {
            case "super35": return "SUPER 35";
            case "16mm": return "16mm";
            case "super8": return "SUPER 8";
            case "4x5": return "4 × 5";
            case "135":
            case "135_half":
            case "135_xpan": return "35mm";
            default: break;
        }
        if (gauge.startsWith("instax")) return "INSTAX";
        if (gauge.startsWith("120")) return "120";
        return gauge.toUpperCase(Locale.US);
    }
}
