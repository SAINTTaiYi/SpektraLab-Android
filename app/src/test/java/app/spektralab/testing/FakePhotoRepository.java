package app.spektralab.testing;

import app.spektralab.domain.PhotoRepository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** In-memory photo repository double for JVM tests. */
public final class FakePhotoRepository implements PhotoRepository {
    private final Map<String, String> copies = new HashMap<>();
    private final Set<String> readable = new HashSet<>();

    public FakePhotoRepository readable(String uri) {
        readable.add(uri);
        return this;
    }

    @Override public String persist(String uri) {
        final String copy = "file:///copies/" + copies.size();
        copies.put(uri, copy);
        readable.add(copy);
        return copy;
    }

    @Override public boolean canRead(String uri) { return readable.contains(uri); }

    @Override public String displayName(String uri) {
        final int slash = uri == null ? -1 : uri.lastIndexOf('/');
        return slash < 0 ? "photo" : uri.substring(slash + 1);
    }
}
