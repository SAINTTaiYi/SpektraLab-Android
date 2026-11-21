package app.spektralab;

/** Runnable on a plain JVM: no Android classes are referenced. */
public final class PureLogicTest {
    private static int failures = 0;

    private static void check(boolean ok, String what) {
        if (!ok) { System.err.println("FAIL: " + what); ++failures; }
    }

    public static void main(String[] args) {
        check(PixelBudget.maxPixelsByMemory(0) == PixelBudget.HARD_PIXEL_CAP,
                "unreadable memory falls back to the hard cap");
        check(PixelBudget.maxPixelsByMemory(-1) == PixelBudget.HARD_PIXEL_CAP,
                "negative memory falls back to the hard cap");
        final long reserve = 1536L << 20;
        check(PixelBudget.maxPixelsByMemory(reserve) == 0, "reserve leaves no budget");
        final long perPixel = 12L * 7 + 16;
        check(PixelBudget.maxPixelsByMemory(reserve + 100 * perPixel) == 100,
                "budget divides by 100 B/px");
        check(PixelBudget.maxPixelsByMemory(reserve + 1000 * perPixel)
                > PixelBudget.maxPixelsByMemory(reserve + 100 * perPixel),
                "budget is monotonic");

        check("\"\"".equals(JsonQuote.quote(null)), "null becomes an empty string");
        check("\"a\"".equals(JsonQuote.quote("a")), "plain string");
        check("\"a\\\"b\"".equals(JsonQuote.quote("a\"b")), "quote is escaped");
        check("\"a\\\\b\"".equals(JsonQuote.quote("a\\b")), "backslash is escaped");
        check("\"a\\u000ab\"".equals(JsonQuote.quote("a\nb")), "control char is escaped");
        check("\"中文\"".equals(JsonQuote.quote("中文")), "non-ASCII passes through");

        check(AppFailure.fromTitle("导入失败") == AppFailure.Stage.IMPORT,
                "import title is classified as IMPORT");
        check(AppFailure.fromTitle("RAW 加载失败") == AppFailure.Stage.IMPORT,
                "RAW title is classified as IMPORT");
        check(AppFailure.fromTitle("渲染失败") == AppFailure.Stage.RENDER,
                "render title is classified as RENDER");
        check(AppFailure.fromTitle("导出失败") == AppFailure.Stage.EXPORT,
                "export title is classified as EXPORT");
        check(AppFailure.fromTitle("关于") == AppFailure.Stage.OTHER,
                "unrelated title is OTHER");
        check(AppFailure.of(AppFailure.Stage.EXPORT, new RuntimeException("x")).stage
                == AppFailure.Stage.EXPORT, "wrapped failure keeps its stage");

        if (failures != 0) {
            System.err.println(failures + " pure-logic check(s) failed");
            System.exit(1);
        }
        System.out.println("PureLogicTest: all checks passed");
    }
}
