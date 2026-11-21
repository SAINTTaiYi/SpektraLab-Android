package app.spektralab.domain;

/** Apply a captured look to a session, then render one frame. */
public final class RenderLookUseCase {
    /** The Activity's parameter plumbing, expressed as a boundary. */
    public interface Params {
        void apply(SessionApi session, EditorState state, long longEdge);
    }

    public int[] render(SessionApi session, EditorState state, String tier,
                        long longEdge, boolean reprint, Params params) {
        if (session == null || session.isClosed()) {
            throw new IllegalStateException("session is null or closed");
        }
        params.apply(session, state, longEdge);
        return reprint ? session.renderBegin(tier, true) : session.renderBegin(tier);
    }
}
