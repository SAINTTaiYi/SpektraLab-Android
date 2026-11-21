package app.spektralab.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Parsed stock catalogue, free of Android UI state. */
public final class StockCatalog {
    public final List<String> filmIds = new ArrayList<>();
    public final List<String> filmLabels = new ArrayList<>();
    public final List<String> filmTypes = new ArrayList<>();
    public final List<String> filmUses = new ArrayList<>();
    public final List<String> printIds = new ArrayList<>();
    public final List<String> printLabels = new ArrayList<>();
    public final List<String> printUses = new ArrayList<>();
    public final Map<String, String> filmTargetPrints = new HashMap<>();
}
