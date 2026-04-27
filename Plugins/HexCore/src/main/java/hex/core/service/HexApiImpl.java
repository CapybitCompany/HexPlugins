package hex.core.service;

import hex.core.api.HexApi;
import hex.core.api.config.ConfigService;
import hex.core.api.db.DatabaseService;
import hex.core.api.flags.FeatureFlagService;
import hex.core.api.region.RegionService;
import hex.core.api.ui.UiService;
import hex.core.service.coins.CoinsService;
import hex.core.service.cache.PlayerStatsCacheService;
import hex.core.service.ranking.RankingPointsService;
import hex.core.service.ranking.RankingPositionService;
import hex.core.service.ranking.RankingTopService;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class HexApiImpl implements HexApi {

    private final ConfigService configs;
    private final FeatureFlagService flags;
    private final UiService ui;
    private final RegionService regions;
    private final DatabaseService db;
    private final RankingPointsService rankingPoints;
    private final CoinsService coins;
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

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

        // Register core services for generic lookup.
        registerService(ConfigService.class, configs);
        registerService(FeatureFlagService.class, flags);
        registerService(UiService.class, ui);
        registerService(RegionService.class, regions);
        registerService(DatabaseService.class, db);
        registerService(RankingPointsService.class, rankingPoints);
        registerService(CoinsService.class, coins);

        // Default cache facade (ranking top/position are optional extensions).
        registerService(PlayerStatsCacheService.class,
                new PlayerStatsCacheService(coins, rankingPoints, null, null));
    }

    /**
     * Convenience overload kept for current HexCore wiring.
     * Internally still uses the stable 7-argument core constructor.
     */
    public HexApiImpl(ConfigService configs,
                      FeatureFlagService flags,
                      UiService ui,
                      RegionService regions,
                      DatabaseService db,
                      RankingPointsService rankingPoints,
                      CoinsService coins,
                      RankingPositionService rankingPosition,
                      RankingTopService rankingTop) {
        this(configs, flags, ui, regions, db, rankingPoints, coins);
        registerService(RankingPositionService.class, rankingPosition);
        registerService(RankingTopService.class, rankingTop);
        registerService(PlayerStatsCacheService.class,
                new PlayerStatsCacheService(coins, rankingPoints, rankingPosition, rankingTop));
    }

    public <T> HexApiImpl registerService(Class<T> type, T service) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(service, "service");
        services.put(type, service);
        return this;
    }

    @Override
    public <T> Optional<T> service(Class<T> type) {
        if (type == null) {
            return Optional.empty();
        }
        Object value = services.get(type);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(type.cast(value));
    }

    @Override public ConfigService configs() { return configs; }
    @Override public FeatureFlagService flags() { return flags; }
    @Override public UiService ui() { return ui; }
    @Override public RegionService regions() { return regions; }
    @Override public DatabaseService db() { return db; }
    @Override public RankingPointsService rankingPoints() { return rankingPoints; }
    @Override public CoinsService coins() { return coins; }
    @Override public PlayerStatsCacheService statsCache() {
        return service(PlayerStatsCacheService.class)
                .orElseGet(() -> new PlayerStatsCacheService(coins, rankingPoints, null, null));
    }
}
