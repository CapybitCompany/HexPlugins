package hex.core.service;

import hex.core.api.HexApi;
import hex.core.api.config.ConfigService;
import hex.core.api.db.DatabaseService;
import hex.core.api.flags.FeatureFlagService;
import hex.core.api.region.RegionService;
import hex.core.api.ui.UiService;

public final class HexApiImpl implements HexApi {

    private final ConfigService configs;
    private final FeatureFlagService flags;
    private final UiService ui;
    private final RegionService regions;
    private final DatabaseService db;

    public HexApiImpl(ConfigService configs, FeatureFlagService flags, UiService ui, RegionService regions,  DatabaseService db) {
        this.configs = configs;
        this.flags = flags;
        this.ui = ui;
        this.regions = regions;
        this.db = db;
    }

    @Override public ConfigService configs() { return configs; }
    @Override public FeatureFlagService flags() { return flags; }
    @Override public UiService ui() { return ui; }
    @Override public RegionService regions() { return regions; }
    @Override public DatabaseService db() { return db; }
}
