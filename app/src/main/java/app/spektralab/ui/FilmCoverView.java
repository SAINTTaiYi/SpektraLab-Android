package app.spektralab.ui;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;

public final class FilmCoverView extends android.view.View {

public static final class Cover {
    int body, band, ink, bandInk, accent;
    String wordmark = "KODAK", model = "", tech = "COLOR PRINT FILM", iso = "";
}

public static Cover coverFor(String id, String caption) {
    final Cover v = new Cover();
    v.body = 0xFFF2EDE3;
    v.band = 0xFFF5C518;
    v.ink = 0xFF171717;
    v.bandInk = 0xFFDA291C;
    v.accent = 0xFFDA291C;
    if (id == null) id = "";

    if (id.startsWith("fujifilm")) {
        v.wordmark = "FUJIFILM";
        v.body = 0xFFF4F2EC;
        v.band = 0xFF007A3D;
        v.bandInk = 0xFFF4F2EC;
        v.ink = 0xFF0B6B38;
        v.accent = 0xFF6D2C91;
    } else if (id.startsWith("ilford")) {
        v.wordmark = "ILFORD";
        v.body = 0xFF141414;
        v.band = 0xFF141414;
        v.bandInk = 0xFFF4F2EC;
        v.ink = 0xFFF4F2EC;
        v.accent = 0xFFF4F2EC;
        v.tech = "BLACK & WHITE FILM";
    } else if (id.startsWith("cinestill")) {
        v.wordmark = "CINESTILL";
        v.body = 0xFFC8102E;
        v.band = 0xFF101010;
        v.bandInk = 0xFFF7F4EF;
        v.ink = 0xFFF7F4EF;
        v.accent = 0xFF2AA3DC;
        v.tech = "CINE FILM · 36 EXP";
    }

    if (id.equals("kodak_gold_200")) {
        v.body = 0xFFE6007E; v.ink = 0xFFFFFFFF;
    } else if (id.equals("kodak_colorplus_200")) {
        v.body = 0xFFEE2E24; v.ink = 0xFFFFFFFF;
    } else if (id.equals("kodak_ultramax_400")) {
        v.body = 0xFF0B5FA5; v.ink = 0xFFFFFFFF;
    } else if (id.equals("kodak_ektar_100")) {
        v.body = 0xFFF2A900; v.ink = 0xFF171717;
    } else if (id.startsWith("kodak_portra")) {
        v.band = 0xFFE8B90C;
    } else if (id.equals("kodak_tri_x_400") || id.equals("kodak_tmax_100")) {
        v.body = 0xFFF2F0EA; v.band = 0xFF141414;
        v.bandInk = 0xFFF2F0EA; v.ink = 0xFF141414;
        v.tech = "BLACK & WHITE FILM";
    } else if (id.startsWith("kodak_vision3")) {
        v.body = 0xFF1B1B1B; v.ink = 0xFFF4F2EC;
        v.tech = "CINE FILM · 400 FT";
        if (id.endsWith("50d")) v.band = 0xFFF2C300;
        else if (id.endsWith("250d")) v.band = 0xFFE07A24;
        else if (id.endsWith("200t")) v.band = 0xFFD64545;
        else v.band = 0xFFC8202F;
    } else if (id.equals("kodak_verita_200d")) {
        v.body = 0xFF1F6E43; v.ink = 0xFFF4F2EC;
    } else if (id.equals("kodak_ektachrome_100")) {
        v.body = 0xFF101010; v.band = 0xFFF2C300;
        v.bandInk = 0xFF171717; v.ink = 0xFFF4F2EC;
        v.tech = "COLOUR REVERSAL";
    } else if (id.equals("kodak_kodachrome_64")) {
        v.body = 0xFFC8102E; v.ink = 0xFFF7F4EF;
        v.tech = "COLOUR REVERSAL";
    } else if (id.equals("fujifilm_c200")) {
        v.body = 0xFF009A44; v.band = 0xFF6D2C91;
        v.bandInk = 0xFFFFFFFF; v.ink = 0xFFFFFFFF;
    } else if (id.equals("fujifilm_xtra_400")) {
        v.body = 0xFF007A3D; v.ink = 0xFFFFFFFF; v.bandInk = 0xFFFFFFFF;
    } else if (id.equals("fujifilm_pro_400h")) {
        v.body = 0xFF007A3D; v.band = 0xFFF4F2EC;
        v.bandInk = 0xFF007A3D; v.ink = 0xFFFFFFFF;
    } else if (id.equals("fujifilm_neopan_acros_100_ii")) {
        v.body = 0xFFF4F2EC; v.band = 0xFF141414;
        v.bandInk = 0xFFF4F2EC; v.ink = 0xFF141414;
        v.tech = "BLACK & WHITE FILM";
    } else if (id.equals("fujifilm_velvia_100")) {
        v.body = 0xFF101010; v.band = 0xFFC8102E;
        v.bandInk = 0xFFF4F2EC; v.ink = 0xFFF4F2EC;
        v.tech = "COLOUR REVERSAL";
    } else if (id.equals("fujifilm_provia_100f")) {
        v.body = 0xFFF4F2EC; v.band = 0xFF007A3D;
        v.ink = 0xFF0B6B38; v.tech = "COLOUR REVERSAL";
    }

    // The name, minus the brand it already shows in the band.
    String m = caption == null ? "" : caption;
    final int dot = m.indexOf(" · ");
    if (dot > 0) m = m.substring(0, dot);
    for (String b : new String[]{"Kodak ", "Fujifilm ", "Ilford ", "CineStill "}) {
        if (m.startsWith(b)) { m = m.substring(b.length()); break; }
    }
    v.model = m;

    final java.util.regex.Matcher mt =
            java.util.regex.Pattern.compile("(\\d{2,4})\\s*(D|T)?").matcher(m);
    while (mt.find()) v.iso = mt.group(1) + (mt.group(2) == null ? "" : mt.group(2));
    return v;
}

private final android.graphics.Paint paint =
        new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
private final android.graphics.Path clip = new android.graphics.Path();
private final android.graphics.RectF full = new android.graphics.RectF();
private final int body, band, ink, bandInk, accent;
private final String wordmark, model, tech, iso;
private android.graphics.LinearGradient gloss;
private int glossW, glossH;

public FilmCoverView(android.content.Context c, String id, String caption) {
    super(c);
    final Cover v = coverFor(id, caption);
    body = v.body;
    band = v.band;
    ink = v.ink;
    bandInk = v.bandInk;
    accent = v.accent;
    wordmark = v.wordmark;
    model = v.model;
    tech = v.tech;
    iso = v.iso;
}

private android.graphics.Typeface condensed() {
    return android.graphics.Typeface.create(
            "sans-serif-condensed", android.graphics.Typeface.BOLD);
}

@Override protected void onDraw(android.graphics.Canvas c) {
    final int w = getWidth(), h = getHeight();
    if (w <= 0 || h <= 0) return;
    final float d = getResources().getDisplayMetrics().density;
    final float pad = Math.max(2f, w * 0.055f);

    full.set(0, 0, w, h);
    clip.reset();
    clip.addRoundRect(full, 2f * d, 2f * d, android.graphics.Path.Direction.CW);
    c.save();
    c.clipPath(clip);

    paint.setStyle(android.graphics.Paint.Style.FILL);
    paint.setShader(null);
    paint.setColor(body);
    c.drawRect(0, 0, w, h, paint);

    final float bandH = h * 0.30f;
    paint.setColor(band);
    c.drawRect(0, 0, w, bandH, paint);

    // --- the band: wordmark left, speed right -------------------
    paint.setTypeface(condensed());
    paint.setColor(bandInk);
    paint.setTextAlign(android.graphics.Paint.Align.LEFT);
    float ws = Math.max(5f * d, w * 0.125f);
    paint.setTextSize(ws);
    final float isoRoom = iso.isEmpty() ? 0f : w * 0.26f;
    float wmW = paint.measureText(wordmark);
    if (wmW > w - pad * 2 - isoRoom) {
        ws *= (w - pad * 2 - isoRoom) / wmW;
        paint.setTextSize(ws);
    }
    final float base = bandH * 0.5f + ws * 0.36f;
    c.drawText(wordmark, pad, base, paint);

    if (!iso.isEmpty()) {
        paint.setTypeface(android.graphics.Typeface.create(
                "sans-serif", android.graphics.Typeface.NORMAL));
        paint.setTextSize(Math.max(4.5f * d, w * 0.072f));
        paint.setTextAlign(android.graphics.Paint.Align.RIGHT);
        c.drawText(iso, w - pad, base, paint);
        paint.setTextAlign(android.graphics.Paint.Align.LEFT);
    }

    // --- the name, one or two lines ------------------------------
    paint.setTypeface(condensed());
    paint.setColor(ink);
    final float room = w - pad * 2;
    // The foot's optical top, measured from its own baseline: the two
    // text blocks used to be laid out independently, and a long name
    // ("Neopan 100 Acros II") ended 0.2 dp above the fine print.
    final float footSize = Math.max(4f * d, w * 0.058f);
    final float footTop = h - 4.2f * d - footSize * 0.95f;
    final float avail = footTop - bandH - 2.5f * d;
    final String[] lines = twoLines(model, room, Math.max(5f * d, w * 0.15f));
    float size = Math.max(5f * d, w * 0.15f);
    for (;;) {
        paint.setTextSize(size);
        float widest = 0f;
        for (String ln : lines) widest = Math.max(widest, paint.measureText(ln));
        // Both constraints, not just the width: a name that fits
        // sideways can still be too tall for the body.
        final boolean fits = widest <= room && size * 1.06f * lines.length <= avail;
        if (fits || size <= 4.2f * d) break;
        size -= 0.4f * d;
    }
    final float lh = size * 1.06f;
    float y = bandH + 2.5f * d + (avail - lh * lines.length) * 0.5f + size * 0.82f;
    for (String ln : lines) {
        c.drawText(ln, pad, y, paint);
        y += lh;
    }

    // --- the foot: what kind of film this is ---------------------
    paint.setTypeface(android.graphics.Typeface.create(
            "sans-serif", android.graphics.Typeface.NORMAL));
    paint.setTextSize(footSize);
    paint.setColor(ink);
    paint.setAlpha(0xB8);
    c.drawText(tech, pad, h - 4.2f * d, paint);
    paint.setAlpha(255);

    // The one graphic device every box has: a short rule under the band.
    paint.setColor(accent);
    c.drawRect(pad, bandH, pad + w * 0.24f, bandH + Math.max(1.5f, 1.1f * d), paint);

    // A coat of gloss, so the swatch reads as printed matter.
    if (gloss == null || glossW != w || glossH != h) {
        gloss = new android.graphics.LinearGradient(0, 0, w, h,
                0x1EFFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP);
        glossW = w;
        glossH = h;
    }
    paint.setShader(gloss);
    c.drawRect(0, 0, w, h, paint);
    paint.setShader(null);

    paint.setStyle(android.graphics.Paint.Style.STROKE);
    paint.setStrokeWidth(1f);
    paint.setColor(0x33000000);
    c.drawRect(0, 0, w, h, paint);
    c.restore();
}

/** One line if it fits, else split at the space nearest the middle. */
private String[] twoLines(String s, float room, float maxSize) {
    paint.setTextSize(maxSize);
    if (paint.measureText(s) <= room) return new String[]{s};
    int best = -1;
    float bestScore = Float.MAX_VALUE;
    for (int i = 0; i < s.length(); ++i) {
        if (s.charAt(i) != ' ') continue;
        final float a = paint.measureText(s.substring(0, i));
        final float b = paint.measureText(s.substring(i + 1));
        final float score = Math.max(a, b);
        if (score < bestScore) { bestScore = score; best = i; }
    }
    if (best < 0) return new String[]{s};
    return new String[]{s.substring(0, best), s.substring(best + 1)};
}
    }
