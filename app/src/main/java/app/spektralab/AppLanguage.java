package app.spektralab;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The editor's language, independent of the system language and engine settings. */
final class AppLanguage {
    private static final String ENGLISH = "en";
    private static final String ASSET_DIR = "locales";
    private static final String PREFS = "ui";
    private static final String KEY_LANGUAGE = "language";
    private static final Language EMPTY_ENGLISH = new Language(ENGLISH, "English", Collections.emptyMap());
    private static Map<String, Language> catalogs;
    private static Language english = EMPTY_ENGLISH;
    private static volatile Language selected = EMPTY_ENGLISH;

    static final class Language {
        final String tag;
        final String name;
        final Locale locale;
        private final Map<String, String> strings;

        private Language(String tag, String name, Map<String, String> strings) {
            this.tag = tag;
            this.name = name;
            this.locale = Locale.forLanguageTag(tag);
            this.strings = strings;
        }
    }

    private AppLanguage() {}

    static synchronized void load(Context context) {
        if (catalogs == null) {
            catalogs = readCatalogs(context.getAssets());
            english = catalogs.get(ENGLISH);
        }
        final String tag = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, ENGLISH);
        final Language match = catalogs.get(tag);
        selected = match == null ? english : match;
    }

    static void select(Context context, Language language) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LANGUAGE, language.tag).apply();
    }

    static Language current() {
        return selected;
    }

    static synchronized List<Language> available() {
        return catalogs == null ? Collections.singletonList(EMPTY_ENGLISH)
                : new ArrayList<>(catalogs.values());
    }

    static String tr(String key) {
        final Language language = selected;
        final String value = language.strings.get(key);
        if (value != null) return value;
        final String fallback = english.strings.get(key);
        return fallback == null ? key : fallback;
    }

    static String format(String key, Object... args) {
        return String.format(current().locale, tr(key), args);
    }

    private static Map<String, Language> readCatalogs(AssetManager assets) {
        Map<String, Language> languages = new LinkedHashMap<>();
        languages.put(ENGLISH, EMPTY_ENGLISH);
        try {
            String[] files = assets.list(ASSET_DIR);
            if (files != null) {
                java.util.Arrays.sort(files);
                for (String file : files) {
                    if (!file.endsWith(".json")) continue;
                    try {
                        Language language = readCatalog(assets, file);
                        languages.put(language.tag, language);
                    } catch (Exception e) {
                        Log.w("SpektraLab", "Ignoring invalid locale catalog " + file, e);
                    }
                }
            }
        } catch (IOException e) {
            Log.w("SpektraLab", "Could not list locale catalogs", e);
        }
        return languages;
    }

    private static Language readCatalog(AssetManager assets, String file) throws Exception {
        try (InputStream in = assets.open(ASSET_DIR + "/" + file);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            JSONObject json = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            String tag = json.getString("tag");
            String name = json.getString("name").trim();
            String canonical = new Locale.Builder().setLanguageTag(tag).build().toLanguageTag();
            if (!tag.equals(canonical) || "und".equals(tag) || !file.equals(tag + ".json") || name.isEmpty()) {
                throw new IOException("Expected a language name and a canonical tag matching the filename");
            }
            JSONObject source = json.getJSONObject("strings");
            Map<String, String> strings = new LinkedHashMap<>();
            for (Iterator<String> keys = source.keys(); keys.hasNext();) {
                String key = keys.next();
                Object value = source.get(key);
                if (!(value instanceof String)) throw new IOException("Expected a string for " + key);
                strings.put(key, (String) value);
            }
            return new Language(tag, name, strings);
        }
    }
}
