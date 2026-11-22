package app.spektralab.domain;

/** Applies a captured look and starts one frame on a session. */
public final class RenderController {
    private final RenderLookUseCase look = new RenderLookUseCase();
    private final RenderPreviewUseCase preview = new RenderPreviewUseCase();

    public int[] begin(SessionApi session, EditorState state, String tier,
                       long longEdge, boolean reprint, RenderLookUseCase.Params params) {
        return look.render(session, state, tier, longEdge, reprint, params);
    }

    public RenderPreviewUseCase.Result preview(SessionApi session, String tier,
                                                boolean reprint, int[] scratch) {
        return preview.render(session, tier, reprint, scratch);
    }
}
