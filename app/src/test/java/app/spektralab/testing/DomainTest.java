package app.spektralab.testing;

import app.spektralab.domain.AppPreferences;
import app.spektralab.domain.EdgeVerdict;
import app.spektralab.domain.ExportController;
import app.spektralab.domain.ImportController;
import app.spektralab.domain.ExportPlan;
import app.spektralab.domain.ExportSessionPlan;
import app.spektralab.domain.EditorState;
import app.spektralab.domain.JsonFields;
import app.spektralab.domain.EngineApi;
import app.spektralab.domain.ImportPlanner;
import app.spektralab.domain.RenderLookUseCase;
import app.spektralab.domain.RenderController;
import app.spektralab.domain.RenderPreviewUseCase;
import app.spektralab.domain.PixelHash;
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

        AppPreferences prefs = new FakeAppPreferences();
        check(!prefs.getBoolean("dark", false), "preferences fallback");
        prefs.putBoolean("dark", true);
        check(prefs.getBoolean("dark", false), "preferences write/read round trip");

        String doc = "{\"auto_exposure_ev\": 0.0756, \"canvas_w\":4182}";
        check(JsonFields.firstInt(doc, "canvas_w") == 4182, "integer field read");
        check(Math.abs(JsonFields.firstNumber(doc, "auto_exposure_ev") - 0.0756) < 1e-9,
                "double field read with a leading space");
        check(JsonFields.firstInt(doc, "missing") == -1, "missing integer is -1");
        check(Double.isNaN(JsonFields.firstNumber(doc, "missing")), "missing double is NaN");
        check(JsonFields.snippet(doc, "auto_exposure_ev", 24).startsWith("\"auto_exposure_ev\""),
                "field snippet");
        int[] pixels = {1, 2, 3, 4, 5};
        long expected = 1469598103934665603L;
        for (int i = 0; i < pixels.length; i += 7) { expected ^= pixels[i]; expected *= 1099511628211L; }
        check(PixelHash.fnv1a(pixels, pixels.length, 7) == expected, "pixel hash matches FNV-1a");

        EdgeVerdict shortMarks = EdgeVerdict.fromLine(
                "edge 4000x3000 Lrow=111@430 n63 g117 Y=100,217 Wrow=5@1 n0");
        check(shortMarks != null && "left_short_marks".equals(shortMarks.kind)
                && "111".equals(shortMarks.first), "short-mark verdict from a diagnostic line");
        EdgeVerdict vertical = EdgeVerdict.fromLine("edge 4000x3000 Vmax=33@1524 Lrow=1@0 n=0");
        check(vertical != null && "vertical_line".equals(vertical.kind)
                && "1524".equals(vertical.first), "vertical-line verdict");
        check(EdgeVerdict.fromLine("edge 4000x3000 Lrow=3@0 n0 Wrow=2@0 n0") == null,
                "clean edge line has no verdict");

        ExportPlan jpeg = ExportPlan.forFormat("jpeg");
        ExportPlan tiff = ExportPlan.forFormat("tiff");
        ExportPlan dng = ExportPlan.forFormat("dng");
        check(".jpg".equals(jpeg.extension) && "image/jpeg".equals(jpeg.mime), "JPEG plan");
        check(".tif".equals(tiff.extension) && "image/tiff".equals(tiff.mime), "TIFF plan");
        check(".dng".equals(dng.extension) && "image/x-adobe-dng".equals(dng.mime), "DNG plan");
        String params = ExportPlan.openParams("kodak_portra_400", false, 0.0756, 0.41);
        check(params.contains("\"auto_exposure\":false")
                && params.contains("\"exposure_compensation_ev\":0.075600")
                && params.contains("\"print_exposure\":0.410000")
                && params.contains("\"print_stock\":\"kodak_portra_400\""),
                "export open params carry the canvas exposure");
        check(ExportPlan.openParams("a\"b", true, 0, 1).contains("\"print_stock\":\"a\\\"b\""),
                "paper name is JSON escaped");

        long need = ExportSessionPlan.workingSetBytes(2000, 1000);
        check(need > 0 && need > (long) 2000 * 1000 * 12, "export working set includes planes");
        check(!ExportSessionPlan.closePreview(false, false, false, need, 0),
                "no fresh session keeps the preview");
        check(!ExportSessionPlan.closePreview(true, true, false, need, 0),
                "batch export keeps the preview for its next reopen");
        check(ExportSessionPlan.closePreview(true, false, false, need, Long.MAX_VALUE),
                "a failed suspension closes the preview");
        check(!ExportSessionPlan.closePreview(true, false, true, need, Long.MAX_VALUE),
                "a suspended preview is kept when memory is plentiful");
        check(ExportSessionPlan.closePreview(true, false, true, need, need),
                "a suspended preview is closed when memory is tight");

        ExportController exports = new ExportController();
        FakeSession exportSession = new FakeSession();
        exports.export(exportSession, "jpeg", "preview", "/tmp/out.jpg", 95);
        check(exportSession.calls.contains("exportJpeg"), "JPEG export dispatched");
        FakeSession tiffSession = new FakeSession();
        exports.export(tiffSession, "tiff", "full", "/tmp/out.tif", 95);
        check(tiffSession.calls.contains("exportTiff"), "TIFF export dispatched");
        FakeSession dngSession = new FakeSession();
        exports.export(dngSession, "dng", "full", "/tmp/out.dng", 95);
        check(dngSession.calls.contains("exportDng"), "DNG export dispatched");

        ImportController imports = new ImportController(new FakePhotoRepository());
        String saved = imports.prepare("content://pick/1");
        check(saved != null && saved.startsWith("file:///copies/"), "import copies a picked URI");
        check(imports.plan(4000, 2000, 1_000_000, 1000).width <= 1000,
                "import plan honours the controller caps");

        FakeSession renderSession2 = new FakeSession();
        int[] dims2 = new RenderController().begin(renderSession2, state, "live", 800, true,
                (s, st, e) -> s.setParams("{}"));
        check(dims2[0] == 640 && renderSession2.calls.contains("renderBegin:live:true"),
                "render controller applies params then reprints");

        if (failures != 0) {
            System.err.println(failures + " domain check(s) failed");
            System.exit(1);
        }
        System.out.println("DomainTest: all checks passed");
    }
}
