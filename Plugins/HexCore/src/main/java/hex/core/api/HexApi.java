package hex.core.api;

import hex.core.api.config.ConfigService;
import hex.core.api.db.DatabaseService;
import hex.core.api.flags.FeatureFlagService;
import hex.core.api.region.RegionService;
import hex.core.api.ui.UiService;
import hex.core.service.coins.CoinsService;
import hex.core.service.ranking.RankingPointsService;
import hex.core.service.cache.PlayerStatsCacheService;

import java.util.NoSuchElementException;
import java.util.Optional;

public interface HexApi {
    ConfigService configs();
    FeatureFlagService flags();
    UiService ui();
    RegionService regions();
    DatabaseService db();
    RankingPointsService rankingPoints();
    CoinsService coins();

    /**
     * Generic optional service lookup (extension point for future modules).
     * Core services still have dedicated getters for convenience.
     */
    default <T> Optional<T> service(Class<T> type) {
        return Optional.empty();
    }

    default <T> T requireService(Class<T> type) {
        return service(type).orElseThrow(() -> new NoSuchElementException("Service not found: " + type));
    }

    /**
     * Facade for invalidating/refreshing cached player stats used by UI/PlaceholderAPI.
     */
    PlayerStatsCacheService statsCache();
}
