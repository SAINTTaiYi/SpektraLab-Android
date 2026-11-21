package app.spektralab.data;

import android.content.Context;
import android.content.SharedPreferences;

import app.spektralab.domain.AppPreferences;

/** SharedPreferences implementation of {@link AppPreferences}. */
public final class SharedPreferencesAppPreferences implements AppPreferences {
    private final SharedPreferences prefs;

    public SharedPreferencesAppPreferences(Context context, String name) {
        prefs = context.getApplicationContext().getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    @Override public boolean getBoolean(String key, boolean fallback) {
        return prefs.getBoolean(key, fallback);
    }

    @Override public void putBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    @Override public String getString(String key, String fallback) {
        return prefs.getString(key, fallback);
    }

    @Override public void putString(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }
}
