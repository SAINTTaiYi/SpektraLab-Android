package app.spektralab.testing;

import app.spektralab.domain.EngineApi;
import app.spektralab.domain.SessionApi;

import java.nio.ByteBuffer;

/** Engine double for JVM tests. */
public final class FakeEngine implements EngineApi {
    public final FakeSession session = new FakeSession();
    public int trimCalls = 0;

    @Override public SessionApi openSession(ByteBuffer pixels, int width, int height, int channels) {
        session.dimensions = new int[]{width, height};
        return session;
    }

    @Override public SessionApi openRawSession(String path, String params, int maxPixels, int maxLongEdge) {
        return session;
    }

    @Override public long trimPool() { ++trimCalls; return 1024L; }

    @Override public String memoryReport() { return "{\"total\":0}"; }

    @Override public String deviceProfile() { return "{\"valid\":true}"; }
}
