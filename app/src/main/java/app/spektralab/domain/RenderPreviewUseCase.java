package app.spektralab.domain;

/** Render one preview frame through a session, with guaranteed cleanup. */
public final class RenderPreviewUseCase {
    public static final class Result {
        public final int width;
        public final int height;
        public final int copied;

        public Result(int width, int height, int copied) {
            this.width = width;
            this.height = height;
            this.copied = copied;
        }
    }

    public Result render(SessionApi session, String tier, boolean reprint, int[] scratch) {
        if (session == null || session.isClosed()) {
            throw new IllegalStateException("session is null or closed");
        }
        final int[] dims = session.renderBegin(tier, reprint);
        if (dims == null || dims.length < 2 || dims[0] <= 0 || dims[1] <= 0) {
            session.renderEnd();
            throw new IllegalStateException("render produced no dimensions");
        }
        final int width = dims[0], height = dims[1];
        try {
            if (scratch == null || scratch.length < (long) width * height) {
                throw new IllegalArgumentException("scratch buffer too small");
            }
            final int copied = session.copyArgbRange(scratch, 0, height);
            if (copied != width * height) {
                throw new IllegalStateException("pixel copy incomplete " + copied + "/" + width * height);
            }
            return new Result(width, height, copied);
        } finally {
            session.renderEnd();
        }
    }
}
