package app.spektralab.domain;

/** An immutable snapshot of the look decisions a render is allowed to observe. */
public final class EditorState {
    public final String filmStock;
    public final String printStock;
    public final String meterMethod;
    public final int frameIndex;
    public final boolean showingOriginal;
    public final boolean rendering;
    public final int geometrySequence;

    public EditorState(String filmStock, String printStock, String meterMethod,
                       int frameIndex, boolean showingOriginal, boolean rendering,
                       int geometrySequence) {
        this.filmStock = filmStock;
        this.printStock = printStock;
        this.meterMethod = meterMethod;
        this.frameIndex = frameIndex;
        this.showingOriginal = showingOriginal;
        this.rendering = rendering;
        this.geometrySequence = geometrySequence;
    }
}
