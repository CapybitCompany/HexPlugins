package hex.core.service.cache;

import hex.core.service.coins.CoinsService;
import hex.core.service.ranking.RankingPointsService;
import hex.core.service.ranking.RankingPositionService;
import hex.core.service.ranking.RankingTopService;

import java.util.UUID;

/**
 * Small facade for clearing/refreshing stat caches.
 *
 * Why it exists:
 * - Placeholder/UI reads are cached for ~15s to protect DB.
 * - After a purchase / points update you sometimes need *immediate* consistency.
 *
 * This service provides a stable place to add:
 * - write-through updates (set new value into cache)
 * - async refresh
 * - cache stampede protection
 */
public final class PlayerStatsCacheService {

    private final CoinsService coins;
    private final RankingPointsService rankingPoints;
    private final RankingPositionService rankingPosition;
    private final RankingTopService rankingTop;

    public PlayerStatsCacheService(
            CoinsService coins,
            RankingPointsService rankingPoints,
            RankingPositionService rankingPosition,
            RankingTopService rankingTop
    ) {
        this.coins = coins;
        this.rankingPoints = rankingPoints;
        this.rankingPosition = rankingPosition;
        this.rankingTop = rankingTop;
    }

    /**
     * Call this after you have changed player's balance in DB.
     *
     * Default behavior: invalidate so next read re-fetches fresh value.
     * If you want strict immediate update, use {@link #refreshCoins(UUID)}.
     */
    public void onCoinsChanged(UUID uuid) {
        if (coins != null) coins.invalidate(uuid);
    }

    /**
     * Synchronous refresh from DB and populate cache immediately.
     *
     * WARNING: this runs on the calling thread. Prefer calling from async task
     * if the change is triggered by a command/event on main thread.
     */
    public int refreshCoins(UUID uuid) {
        if (coins == null) return 0;
        return coins.refresh(uuid);
    }

    /**
     * Call this after you have changed player's ranking_points in DB.
     */
    public void onRankingPointsChanged(UUID uuid) {
        if (rankingPoints != null) rankingPoints.invalidate(uuid);
        if (rankingPosition != null) rankingPosition.invalidate(uuid);
        // Player points update may change TOP lists.
        if (rankingTop != null) rankingTop.invalidateAll();
    }
}

