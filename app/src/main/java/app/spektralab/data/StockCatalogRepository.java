package app.spektralab.data;

import android.content.res.AssetManager;

import app.spektralab.domain.StockCatalog;

/** Source of the film/paper catalogue shipped in the APK. */
public interface StockCatalogRepository {
    StockCatalog load(AssetManager assets);
}
