package app.spektralab.domain;

/** Tiny preferences boundary; no Android types, so it can be faked in JVM tests. */
public interface AppPreferences {
    boolean getBoolean(String key, boolean fallback);

    void putBoolean(String key, boolean value);

    String getString(String key, String fallback);

    void putString(String key, String value);
}
