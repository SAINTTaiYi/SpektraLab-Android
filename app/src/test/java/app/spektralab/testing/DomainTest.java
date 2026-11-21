package app.spektralab.testing;

import app.spektralab.domain.EditorState;
import app.spektralab.domain.EngineApi;
import app.spektralab.domain.ImportPlanner;
import app.spektralab.domain.RenderLookUseCase;
import app.spektralab.domain.RenderPreviewUseCase;
import app.spektralab.domain.SessionApi;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Runnable JVM tests for the domain layer; no Android classes. */
public final class DomainTest {
    private static int failures = 0;

    private static void check(boolean ok, String what) {
        if (!ok) { System.err.println("FAIL: " + what); ++failures; }
    }

    private static void expectThrows(Runnable r, String what) {
        try { r.run(); check(false, what + " (no exception)"); }
        catch (RuntimeException expected) { /* expected */ }
    }

    public static void main(String[] args) {
        ImportPlanner.Plan small = ImportPlanner.plan(1000, 600, 1_000_000, 12000);
        check(small.scale == 1.0 && small.width == 1000 && small.height == 600,
                "within caps keeps the original size");

        ImportPlanner.Plan byArea = ImportPlanner.plan(2000, 1000, 1_000_000, 12000);
        check(byArea.scale < 1.0 && (long) byArea.width * byArea.height <= 1_000_100,
                "area cap is honoured");

        ImportPlanner.Plan byEdge = ImportPlanner.plan(4000, 3000, Integer.MAX_VALUE, 1000);
        check(byEdge.width == 1000 && byEdge.height == 750, "long-edge cap is honoured");

        ImportPlanner.Plan both = ImportPlanner.plan(4000, 2000, 1_000_000, 1000);
        check(both.width <= 1000 && (long) both.width * both.height <= 1_000_100,
                "the strictest cap wins");

        expectThrows(() -> ImportPlanner.plan(0, 10, 100, 100), "invalid dimensions rejected");

        RenderPreviewUseCase useCase = new RenderPreviewUseCase();
        FakeSession session = new FakeSession();
        int[] scratch = new int[640 * 480];
        RenderPreviewUseCase.Result result = useCase.render(session, "live", true, scratch);
        check(result.width == 640 && result.height == 480 && result.copied == 640 * 480,
                "preview result dimensions and copy count");
        check(session.calls.equals(Arrays.asList(
                        "renderBegin:live:true", "copyArgbRange:0:480", "renderEnd")),
                "render/copy/end call order: " + session.calls);

        FakeSession broken = new FakeSession();
        broken.copiedRows = 10;
        expectThrows(() -> useCase.render(broken, "live", false, scratch),
                "incomplete copy throws");
        check(broken.calls.contains("renderEnd"), "renderEnd runs after a failed copy");

        FakeSession closed = new FakeSession();
        closed.closed = true;
        expectThrows(() -> useCase.render(closed, "live", false, scratch), "closed session rejected");

        FakeSession smallScratch = new FakeSession();
        expectThrows(() -> useCase.render(smallScratch, "live", false, new int[10]),
                "small scratch buffer rejected");
        check(smallScratch.calls.contains("renderEnd"), "renderEnd runs after a bad scratch buffer");

        EngineApi engine = new FakeEngine();
        SessionApi opened = engine.openSession(ByteBuffer.allocate(4), 2, 2, 3);
        check(opened != null && !opened.isClosed(), "fake engine opens a session");
        check(engine.trimPool() == 1024L, "fake engine trims the pool");

        FakePhotoRepository photos = new FakePhotoRepository().readable("content://pick/1");
        check(photos.canRead("content://pick/1"), "fake repo reports a readable URI");
        String copy = photos.persist("content://pick/1");
        check(copy != null && photos.canRead(copy), "persist creates a readable copy");
        check("1".equals(photos.displayName("content://pick/1")), "display name from URI");

        EditorState state = new EditorState("film", "paper", "balanced", 3, false, false, 7);
        FakeSession renderSession = new FakeSession();
        int[] dims = new RenderLookUseCase().render(renderSession, state, "live", 2048, false,
                (target, captured, edge) -> {
                    check("film".equals(captured.filmStock) && edge == 2048,
                            "captured state reaches the params boundary");
                    target.setParams("{}");
                });
        check(dims[0] == 640 && dims[1] == 480, "render use case returns dimensions");
        check(renderSession.calls.equals(Arrays.asList("setParams", "renderBegin:live:false")),
                "params applied before render: " + renderSession.calls);
        FakeSession reprint = new FakeSession();
        new RenderLookUseCase().render(reprint, state, "live", 800, true, (s, st, e) -> { });
        check(reprint.calls.contains("renderBegin:live:true"), "reprint flag reaches the session");

        if (failures != 0) {
            System.err.println(failures + " domain check(s) failed");
            System.exit(1);
        }
        System.out.println("DomainTest: all checks passed");
    }
}
