package app.spektralab.testing;

import app.spektralab.domain.SessionApi;

import java.util.ArrayList;
import java.util.List;

/** Recording session double for JVM tests; no native code or Android classes. */
public final class FakeSession implements SessionApi {
    public final List<String> calls = new ArrayList<>();
    public int[] dimensions = {640, 480};
    public int copiedRows = -1; // -1 means "copy exactly width*height"
    public boolean closed = false;
    public String lastParams;

    @Override public void setParams(String paramsJson) {
        calls.add("setParams");
        lastParams = paramsJson;
    }

    @Override public String getParams() {
        calls.add("getParams");
        return lastParams == null ? "{}" : lastParams;
    }

    @Override public int[] renderBegin(String tier) {
        calls.add("renderBegin:" + tier + ":false");
        return dimensions.clone();
    }

    @Override public int[] renderBegin(String tier, boolean reprint) {
        calls.add("renderBegin:" + tier + ":" + reprint);
        return dimensions.clone();
    }

    @Override public int copyArgbRange(int[] dst, int rowOffset, int rowCount) {
        calls.add("copyArgbRange:" + rowOffset + ":" + rowCount);
        return copiedRows < 0 ? dimensions[0] * dimensions[1] : copiedRows;
    }

    @Override public void renderEnd() { calls.add("renderEnd"); }

    @Override public String solve(String target) {
        calls.add("solve:" + target);
        return "{}";
    }

    @Override public int[] exportJpeg(String path, int quality, int stripRows, String tier) {
        calls.add("exportJpeg");
        return new int[]{dimensions[0], dimensions[1], 1, 1};
    }

    @Override public int[] exportTiff(String path, String tier) {
        calls.add("exportTiff");
        return new int[]{dimensions[0], dimensions[1], 1, 1};
    }

    @Override public int[] exportDng(String path, String tier) {
        calls.add("exportDng");
        return new int[]{dimensions[0], dimensions[1], 1, 1};
    }

    @Override public boolean suspend() { calls.add("suspend"); return true; }

    @Override public boolean resume() { calls.add("resume"); return true; }

    @Override public String overscanGeometry() { return "{\"valid\":false}"; }

    @Override public String progressJson() { return "{}"; }

    @Override public String stageSnapshot() { return "fake"; }

    @Override public int[] getDimensions() { return dimensions.clone(); }

    @Override public void pinAutoExposureEv(double ev) { calls.add("pin:" + ev); }

    @Override public void clearPinAutoExposureEv() { calls.add("clearPin"); }

    @Override public boolean isClosed() { return closed; }
}
