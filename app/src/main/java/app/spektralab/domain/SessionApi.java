package app.spektralab.domain;

/**
 * The session operations the editor actually uses. Implemented by the JNI
 * facade and by a test fake; no Android UI types cross this boundary.
 */
public interface SessionApi {
    void setParams(String paramsJson);

    String getParams();

    int[] renderBegin(String tier);

    int[] renderBegin(String tier, boolean reprint);

    int copyArgbRange(int[] dst, int rowOffset, int rowCount);

    void renderEnd();

    String solve(String target);

    int[] exportJpeg(String path, int quality, int stripRows, String tier);

    int[] exportTiff(String path, String tier);

    int[] exportDng(String path, String tier);

    boolean suspend();

    boolean resume();

    String overscanGeometry();

    String progressJson();

    String stageSnapshot();

    int[] getDimensions();

    void pinAutoExposureEv(double ev);

    void clearPinAutoExposureEv();

    boolean isClosed();
}
