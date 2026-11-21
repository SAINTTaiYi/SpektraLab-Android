package app.spektralab.data;

import android.content.res.AssetManager;

import app.spektralab.domain.StockCatalog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads the stock catalogue bundled in the APK; no UI or translation logic. */
public final class AssetStockCatalogRepository implements StockCatalogRepository {
    @Override
    public StockCatalog load(AssetManager assets) {
        final StockCatalog catalog = new StockCatalog();
        try (InputStream in = assets.open("resources/stock_catalog.json")) {
            final byte[] buf = new byte[in.available()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            final JSONObject root = new JSONObject(new String(buf, StandardCharsets.UTF_8));
            read(root.optJSONArray("films"), catalog.filmIds, catalog.filmLabels,
                    catalog.filmTypes, catalog.filmUses, catalog.filmTargetPrints);
            read(root.optJSONArray("prints"), catalog.printIds, catalog.printLabels,
                    null, catalog.printUses, null);
        } catch (Throwable t) {
            android.util.Log.w("SpektraLab", "stock catalog: " + t);
        }
        if (catalog.filmIds.isEmpty()) {
            catalog.filmIds.add("kodak_portra_400");
            catalog.filmLabels.add("Kodak Portra 400");
        }
        if (catalog.printIds.isEmpty()) {
            catalog.printIds.add("kodak_portra_endura");
            catalog.printLabels.add("Kodak Portra Endura");
        }
        return catalog;
    }

    private static void read(JSONArray array, java.util.List<String> ids,
                             java.util.List<String> names, java.util.List<String> types,
                             java.util.List<String> uses, java.util.Map<String, String> targets) {
        if (array == null) return;
        for (int i = 0; i < array.length(); ++i) {
            final JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            final String id = o.optString("id", "");
            if (id.isEmpty()) continue;
            ids.add(id);
            names.add(o.optString("name", id));
            if (types != null) types.add(o.optString("type", ""));
            if (uses != null) uses.add(o.optString("use", ""));
            if (targets != null) {
                final String target = o.optString("targetPrint", "");
                if (!target.isEmpty()) targets.put(id, target);
            }
        }
    }
}
