package app.spektralab.data;

import app.spektralab.Look;

import org.json.JSONArray;
import org.json.JSONObject;

/** Preset persistence boundary; the implementation owns the SharedPreferences. */
public interface PresetRepository {
    JSONArray load();

    void save(JSONArray presets);

    JSONObject compact(Look look);
}
