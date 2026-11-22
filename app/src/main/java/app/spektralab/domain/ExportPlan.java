package app.spektralab.domain;

import java.util.Locale;

/** Pure export decisions: file type and the JSON a fresh session opens with. */
public final class ExportPlan {
    public final String extension;
    public final String mime;

    private ExportPlan(String extension, String mime) {
        this.extension = extension;
        this.mime = mime;
    }

    public static ExportPlan forFormat(String format) {
        final boolean tiff = "tiff".equals(format);
        final boolean dng = "dng".equals(format);
        return new ExportPlan(dng ? ".dng" : tiff ? ".tif" : ".jpg",
                dng ? "image/x-adobe-dng" : tiff ? "image/tiff" : "image/jpeg");
    }

    /**
     * The opening parameters for a fresh export session. The meter flag and
     * enlarger exposure must match the canvas, otherwise the file is printed
     * with engine defaults (a known source of white frames).
     */
    public static String openParams(String paper, boolean autoExposure,
                                    double cameraEv, double printExposure) {
        final String stock = paper == null ? "kodak_portra_endura" : paper;
        return "{\"print_stock\":" + JsonQuote.quote(stock)
                + ",\"input_color_space\":\"ProPhoto RGB\""
                + ",\"input_cctf_decoding\":false,\"output_color_space\":\"sRGB\""
                + ",\"auto_exposure\":" + autoExposure
                + ",\"exposure_compensation_ev\":" + fmt("%.6f", cameraEv)
                + ",\"print_exposure\":" + fmt("%.6f", printExposure)
                + ",\"preview_long_edge\":2048}";
    }

    private static String fmt(String pattern, double value) {
        return String.format(Locale.US, pattern, value);
    }
}
