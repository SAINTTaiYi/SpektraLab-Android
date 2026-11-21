package app.spektralab.domain;

import java.nio.ByteBuffer;

/** Engine operations used by the editor, independent of the concrete JNI class. */
public interface EngineApi {
    SessionApi openSession(ByteBuffer pixels, int width, int height, int channels);

    SessionApi openRawSession(String filePath, String paramsJson, int maxPixels, int maxLongEdge);

    long trimPool();

    String memoryReport();

    String deviceProfile();
}
