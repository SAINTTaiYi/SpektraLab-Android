package app.spektralab.domain;

/** Photo-import storage operations, using URI strings so it stays testable. */
public interface PhotoRepository {
    /** Copy a picked URI into app-owned storage; returns the new URI or null. */
    String persist(String uri);

    boolean canRead(String uri);

    String displayName(String uri);
}
