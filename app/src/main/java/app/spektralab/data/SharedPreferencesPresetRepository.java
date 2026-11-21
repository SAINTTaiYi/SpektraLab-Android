package app.spektralab.data;

import android.content.Context;
import android.content.SharedPreferences;

import app.spektralab.Look;
import app.spektralab.PresetStore;

import org.json.JSONArray;
import org.json.JSONObject;

/** Backs presets with the same SharedPreferences the old UI used. */
public final class SharedPreferencesPresetRepository implements PresetRepository {
    private final SharedPreferences prefs;

    public SharedPreferencesPresetRepository(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences("presets", Context.MODE_PRIVATE);
    }

    @Override public JSONArray load() {
        try { return new JSONArray(prefs.getString("list", "[]")); }
        catch (Throwable t) { return new JSONArray(); }
    }

    @Override public void save(JSONArray presets) {
        prefs.edit().putString("list", presets.toString()).apply();
    }

    @Override public JSONObject compact(Look look) { return PresetStore.compact(look); }
}
