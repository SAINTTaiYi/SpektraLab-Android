package app.spektralab;

import org.json.JSONObject;

/** Pure JSON mapping for {@link Look}; no Activity or Android view state. */
final class LookCodec {
    private static final String[] VALID_METERS = {
            "", "balanced", "protect_highlights", "protect_shadows",
            "center", "center_weighted", "average", "median"};

    private LookCodec() {}

    static String sanitizeMeter(String m) {
        if (m == null || m.isEmpty()) return "";
        for (String id : VALID_METERS) if (id.equals(m)) return m;
        return "balanced";
    }

    static org.json.JSONObject toJson(Look l) {
        final org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("film", l.filmStock == null ? "" : l.filmStock);
            o.put("print", l.printStock == null ? "" : l.printStock);
            o.put("exp", (double) l.printExposure);
            o.put("mtr", l.meterMethod == null ? "" : l.meterMethod);
            o.put("grain", (double) l.grainAmount);
            o.put("halation", (double) l.halationAmount);
            o.put("glare", (double) l.glareAmount);
            o.put("highlight", (double) l.highlightRoom);
            o.put("shadow", (double) l.shadowRoom);
            o.put("boost", (double) l.halationBoost);
            o.put("scatter", (double) l.halationScatter);
            o.put("scatterr", (double) l.scatterRadius);
            o.put("lens", (double) l.lensBlur);
            o.put("vT", (double) l.viewTemperature);
            o.put("vTn", (double) l.viewTint);
            o.put("vE", (double) l.viewExposure);
            o.put("vC", (double) l.viewContrast);
            o.put("vB", (double) l.viewBrightness);
            o.put("vS", (double) l.viewSaturation);
            o.put("vHi", (double) l.viewHighlights);
            o.put("vSh", (double) l.viewShadows);
            o.put("vBk", (double) l.viewBlack);
            o.put("vWh", (double) l.viewWhite);
            o.put("vV", (double) l.viewVignette);
            o.put("cEv", (double) l.cameraEv);
            o.put("vVb", (double) l.viewVibrance);
            o.put("wb", l.wbPreset);
            o.put("wbK", (double) l.wbKelvin);
            o.put("wbT", (double) l.wbTint);
            if (l.wbMul != null && l.wbMul.length == 4) {
                final org.json.JSONArray wbArr = new org.json.JSONArray();
                for (float v : l.wbMul) wbArr.put((double) v);
                o.put("wbm", wbArr);
            }
            o.put("gturn", l.geometryTurns);
            o.put("grot", (double) l.geometryRotation);
            o.put("gcw", (double) l.geometryCropW);
            o.put("gch", (double) l.geometryCropH);
            o.put("gcx", (double) l.geometryCropX);
            o.put("gcy", (double) l.geometryCropY);
            o.put("gcri", l.cropRatioIndex);
            o.put("gfh", l.geometryFlipH);
            o.put("gfv", l.geometryFlipV);
            o.put("yf", (double) l.yFilter);
            o.put("mf", (double) l.mFilter);
            o.put("fscale", (double) l.filterScale);
            o.put("preflash", (double) l.preflash);
            o.put("fog", (double) l.edgeFog);
            o.put("leaks", (double) l.edgeLeaks);
            o.put("frameno", (double) l.frameNumber);
            o.put("halon", (double) l.halationOn);
            o.put("grainon", (double) l.grainOn);
            o.put("coupon", (double) l.couplersOn);
            o.put("ahrem", (double) l.antihalationRemoved);
            o.put("scw", (double) l.scannerWhite);
            o.put("scb", (double) l.scannerBlack);
            o.put("edr", (double) l.edrOn);
            o.put("gla", (double) l.glareActive);
            o.put("slb", (double) l.scannerLensBlur);
            o.put("hhe", (double) l.halationHeadroom);
            o.put("dcg", (double) l.densityGamma);
            o.put("dint", l.digitalIntermediate);
            o.put("occtf", l.outputCctf);
            o.put("hcor", l.hueCorrection);
            o.put("rolloff", (double) l.shadowRolloff);
            for (int i = 0; i < 4; ++i) {
                o.put("cbh" + i, (double) l.cbHue[i]);
                o.put("cbs" + i, (double) l.cbSat[i]);
                o.put("cbl" + i, (double) l.cbLum[i]);
            }
            for (int i = 0; i < 5; ++i) {
                final float[] flat = l.curves[i];
                if (flat == null) continue;
                final org.json.JSONArray arr = new org.json.JSONArray();
                for (int k = 0; k < flat.length; ++k) arr.put((double) flat[k]);
                o.put("cv" + i, arr);
            }
            o.put("lift", (double) l.maxLift);
            o.put("cfilter", l.cameraFilter == null ? "" : l.cameraFilter);
            o.put("snorm", l.sceneNorm == null ? "power" : l.sceneNorm);
            o.put("emode", l.edgeMode == null ? "strip" : l.edgeMode);
            o.put("egate", l.edgeGate == null ? "auto" : l.edgeGate);
            o.put("eholes", l.edgeHoles == null ? "white" : l.edgeHoles);
            o.put("ecarrier", l.edgeCarrier == null ? "none" : l.edgeCarrier);
            o.put("dplace", l.datePlacement == null ? "frame" : l.datePlacement);
            o.put("dcorn", l.dateCorner == null ? "br" : l.dateCorner);
            o.put("dstyle", l.dateStyle == null ? "lcd" : l.dateStyle);
            o.put("dinsx", (double) l.dateInsetX);
            o.put("dinsy", (double) l.dateInsetY);
            o.put("etext", l.edgeTextOn);
            o.put("efnum", (double) l.edgeFNumber);
            o.put("dsize", (double) l.dateSize);
            o.put("dev", (double) l.dateEv);
            o.put("dtext", l.dataText == null ? "" : l.dataText);
            o.put("dtextb", l.dateTextB == null ? "" : l.dateTextB);
            o.put("cseed", (double) l.cameraSeed);
            o.put("fseed", (double) l.frameSeed);
            o.put("mm", (double) l.filmFormatMm);
            o.put("overscan", l.overscanOn);
            o.put("format", l.overscanFormat == null ? "135" : l.overscanFormat);
            o.put("date", l.dateOn);
        } catch (Throwable ignored) { }
        return o;
    }

    static Look fromJson(org.json.JSONObject o) {
        final Look l = new Look();
        if (o == null) return l;
        final String film = o.optString("film", "");
        l.filmStock = film.isEmpty() ? null : film;
        final String paper = o.optString("print", "");
        l.printStock = paper.isEmpty() ? "kodak_portra_endura" : paper;
        l.printExposure = (float) o.optDouble("exp", 1.0);
        // `mm` is the name; an older state has only the boolean, and `true`
        // there meant exactly this default.
        // **Not `mm`** -- that key already belonged to `filmFormatMm`, and the
        // two writes landed in the same object: the format (24.0) overwrote the
        // meter name, and the next render sent `auto_exposure_method: "24"`,
        // which the engine refuses. `mtr` is this field's own key.
        l.meterMethod = sanitizeMeter(o.has("mtr") ? o.optString("mtr", "protect_highlights")
                : (o.optBoolean("meter", true) ? "protect_highlights" : ""));
        l.grainAmount = (float) o.optDouble("grain", 1.0);
        l.halationAmount = (float) o.optDouble("halation", 1.0);
        l.glareAmount = (float) o.optDouble("glare", 1.0);
        l.highlightRoom = (float) o.optDouble("highlight", 0.0);
        l.shadowRoom = (float) o.optDouble("shadow", 0.0);
        l.halationBoost = (float) o.optDouble("boost", 0.0);
        l.halationScatter = (float) o.optDouble("scatter", 1.0);
        l.scatterRadius = (float) o.optDouble("scatterr", 0.0);
        l.lensBlur = (float) o.optDouble("lens", 0.0);
        l.viewTemperature = (float) o.optDouble("vT", 0.0);
        l.viewTint = (float) o.optDouble("vTn", 0.0);
        l.viewExposure = (float) o.optDouble("vE", 0.0);
        l.viewContrast = (float) o.optDouble("vC", 0.0);
        l.viewBrightness = (float) o.optDouble("vB", 0.0);
        l.viewSaturation = (float) o.optDouble("vS", 0.0);
        l.viewHighlights = (float) o.optDouble("vHi", 0.0);
        l.viewShadows = (float) o.optDouble("vSh", 0.0);
        l.viewBlack = (float) o.optDouble("vBk", 0.0);
        l.viewWhite = (float) o.optDouble("vWh", 0.0);
        l.viewVignette = (float) o.optDouble("vV", 0.0);
        l.cameraEv = (float) o.optDouble("cEv", 0.0);
        l.viewVibrance = (float) o.optDouble("vVb", 0.0);
        l.wbPreset = o.optInt("wb", 0);
        l.wbKelvin = (float) o.optDouble("wbK", 5500.0);
        l.wbTint = (float) o.optDouble("wbT", 0.0);
        final org.json.JSONArray wbArr = o.optJSONArray("wbm");
        if (wbArr != null && wbArr.length() == 4) {
            l.wbMul = new float[4];
            for (int i = 0; i < 4; ++i) l.wbMul[i] = (float) wbArr.optDouble(i, 1.0);
        }
        l.geometryTurns = o.optInt("gturn", 0);
        l.geometryRotation = (float) o.optDouble("grot", 0.0);
        l.geometryCropW = (float) o.optDouble("gcw", 1.0);
        l.geometryCropH = (float) o.optDouble("gch", 1.0);
        l.geometryCropX = (float) o.optDouble("gcx", 0.0);
        l.geometryCropY = (float) o.optDouble("gcy", 0.0);
        l.cropRatioIndex = o.optInt("gcri", -1);
        l.geometryFlipH = o.optBoolean("gfh", false);
        l.geometryFlipV = o.optBoolean("gfv", false);
        l.yFilter = (float) o.optDouble("yf", 0.0);
        l.mFilter = (float) o.optDouble("mf", 0.0);
        l.filterScale = (float) o.optDouble("fscale", 1.0);
        l.preflash = (float) o.optDouble("preflash", 0.0);
        l.edgeFog = (float) o.optDouble("fog", 1.0);
        l.edgeLeaks = (float) o.optDouble("leaks", 0.0);
        l.frameNumber = (float) o.optDouble("frameno", 0.0);
        l.halationOn = (float) o.optDouble("halon", 1.0);
        l.grainOn = (float) o.optDouble("grainon", 1.0);
        l.couplersOn = (float) o.optDouble("coupon", 1.0);
        l.antihalationRemoved = (float) o.optDouble("ahrem", 0.0);
        l.scannerWhite = (float) o.optDouble("scw", 0.0);
        l.scannerBlack = (float) o.optDouble("scb", 0.0);
        l.edrOn = (float) o.optDouble("edr", 0.0);
        l.glareActive = (float) o.optDouble("gla", 1.0);
        l.scannerLensBlur = (float) o.optDouble("slb", 0.0);
        l.halationHeadroom = (float) o.optDouble("hhe", 4.0);
        l.densityGamma = (float) o.optDouble("dcg", 1.0);
        l.digitalIntermediate = o.optBoolean("dint", false);
        l.outputCctf = o.optBoolean("occtf", true);
        l.hueCorrection = (float) o.optDouble("hcor", 1.0);
        // **1.5.4a shipped two wrong defaults for a few hours.** It sent
        // `halation_headroom_ev` 0 (engine default 4) and `output_cctf_encoding`
        // false (engine default true); either alone darkens every frame, and
        // together they made the print look broken. No UI could reach those
        // fields then, so a stored pair of exactly 0 + false can only be that
        // build's fingerprint -- lift it to what the engine actually uses.
        if (l.halationHeadroom <= 0f && !l.outputCctf) {
            l.halationHeadroom = 4f;
            l.outputCctf = true;
        }
        l.shadowRolloff = (float) o.optDouble("rolloff", 2.0);
        for (int i = 0; i < 4; ++i) {
            l.cbHue[i] = (float) o.optDouble("cbh" + i, 0.0);
            l.cbSat[i] = (float) o.optDouble("cbs" + i, 0.0);
            l.cbLum[i] = (float) o.optDouble("cbl" + i, 0.0);
        }
        for (int i = 0; i < 5; ++i) {
            final org.json.JSONArray arr = o.optJSONArray("cv" + i);
            if (arr == null) continue;
            final float[] flat = new float[arr.length()];
            for (int k = 0; k < arr.length(); ++k) flat[k] = (float) arr.optDouble(k, 0.0);
            l.curves[i] = flat;
        }
        l.maxLift = (float) o.optDouble("lift", 4.0);
        l.cameraFilter = o.optString("cfilter", "");
        l.sceneNorm = o.optString("snorm", "power");
        l.edgeMode = o.optString("emode", "strip");
        l.edgeGate = o.optString("egate", "auto");
        l.edgeHoles = o.optString("eholes", "white");
        l.edgeCarrier = o.optString("ecarrier", "none");
        l.datePlacement = o.optString("dplace", "frame");
        l.dateCorner = o.optString("dcorn", "br");
        l.dateStyle = o.optString("dstyle", "lcd");
        l.dateInsetX = (float) o.optDouble("dinsx", 3.0);
        l.dateInsetY = (float) o.optDouble("dinsy", 2.4);
        l.edgeTextOn = o.optBoolean("etext", true);
        l.edgeFNumber = (float) o.optDouble("efnum", 0.0);
        l.dateSize = (float) o.optDouble("dsize", 1.0);
        l.dateEv = (float) o.optDouble("dev", 3.5);
        l.dataText = o.optString("dtext", "");
        l.dateTextB = o.optString("dtextb", "");
        l.cameraSeed = o.optInt("cseed", 1);
        l.frameSeed = o.optInt("fseed", 1);
        l.filmFormatMm = (float) o.optDouble("mm", 36.0);
        l.overscanOn = o.optBoolean("overscan", false);
        final String format = o.optString("format", "135");
        l.overscanFormat = format.isEmpty() ? "135" : format;
        l.dateOn = o.optBoolean("date", false);
        return l;
    }

}
