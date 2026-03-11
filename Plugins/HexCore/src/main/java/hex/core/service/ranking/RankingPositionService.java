package hex.core.service.ranking;

import hex.core.database.repository.RankingPointsRepository;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves player's rank position (1-based) for global and season rankings.
 */
public final class RankingPositionService {

    private final RankingPointsRepository repository;

    private final Map<UUID, CacheEntry> globalCache = new ConcurrentHashMap<>();
    private final Map<UUID, CacheEntry> seasonCache = new ConcurrentHashMap<>();

    private final Clock clock;
    private final long ttlMillis;

    /** Default TTL: 15s. */
    public RankingPositionService(RankingPointsRepository repository) {
        this(repository, Clock.systemUTC(), 15_000L);
    }

    public RankingPositionService(RankingPointsRepository repository, Clock clock, long ttlMillis) {
        this.repository = repository;
        this.clock = clock;
        this.ttlMillis = Math.max(0L, ttlMillis);
    }

    public int getGlobalRank(UUID uuid) {
        return get(uuid, true);
    }

    public int getSeasonRank(UUID uuid) {
        return get(uuid, false);
    }

    public void invalidate(UUID uuid) {
        if (uuid == null) return;
        globalCache.remove(uuid);
        seasonCache.remove(uuid);
    }

    public void invalidateAll() {
        globalCache.clear();
        seasonCache.clear();
    }

    private int get(UUID uuid, boolean global) {
        if (uuid == null) return -1;
        if (repository == null) return -1;

        long now = clock.millis();
        Map<UUID, CacheEntry> cache = global ? globalCache : seasonCache;

        CacheEntry entry = cache.get(uuid);
        if (entry != null && now < entry.expiresAtMillis) {
            return entry.value;
        }

        int pos;
        try {
            pos = global ? repository.findGlobalRankPosition(uuid) : repository.findSeasonRankPosition(uuid);
        } catch (Exception ex) {
            pos = -1;
        }

        cache.put(uuid, new CacheEntry(pos, now + ttlMillis));
        return pos;
    }

    private record CacheEntry(int value, long expiresAtMillis) {
    }
}

