package app.spektralab.domain;

/** Coordinates photo persistence and downscale planning for an import. */
public final class ImportController {
    private final PhotoRepository photos;

    public ImportController(PhotoRepository photos) {
        this.photos = photos;
    }

    /** Copy a picked URI into app-owned storage; null when it cannot be read. */
    public String prepare(String uri) {
        if (uri == null) return null;
        return photos.persist(uri);
    }

    public ImportPlanner.Plan plan(int width, int height, int capPixels, int edgeCap) {
        return ImportPlanner.plan(width, height, capPixels, edgeCap);
    }
}
