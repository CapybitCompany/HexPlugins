package hex.core.service;

import hex.core.api.HexApi;
import hex.core.api.config.ConfigService;
import hex.core.api.db.DatabaseService;
import hex.core.api.flags.FeatureFlagService;
import hex.core.api.region.RegionService;
import hex.core.api.ui.UiService;
import hex.core.service.coins.CoinsService;
import hex.core.service.ranking.RankingPointsService;

public final class HexApiImpl implements HexApi {

    private final ConfigService configs;
    private final FeatureFlagService flags;
    private final UiService ui;
    private final RegionService regions;
    private final DatabaseService db;
    private final RankingPointsService rankingPoints;
    private final CoinsService coins;

    public HexApiImpl(ConfigService configs,
                      FeatureFlagService flags,
                      UiService ui,
                      RegionService regions,
                      DatabaseService db,
                      RankingPointsService rankingPoints,
                      CoinsService coins) {
        this.configs = configs;
        this.flags = flags;
        this.ui = ui;
        this.regions = regions;
        this.db = db;
        this.rankingPoints = rankingPoints;
        this.coins = coins;
    }

    @Override public ConfigService configs() { return configs; }
    @Override public FeatureFlagService flags() { return flags; }
    @Override public UiService ui() { return ui; }
    @Override public RegionService regions() { return regions; }
    @Override public DatabaseService db() { return db; }
    @Override public RankingPointsService rankingPoints() { return rankingPoints; }
    @Override public CoinsService coins() { return coins; }
}
