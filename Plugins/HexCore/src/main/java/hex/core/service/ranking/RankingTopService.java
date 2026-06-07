package hex.core.service.ranking;

import hex.core.api.db.DatabaseService;
import hex.core.database.model.RankingTopEntry;
import hex.core.database.repository.RankingPointsRepository;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leaderboards cache for TOP N queries.
 *
 * This is separated from RankingPointsService because TOP queries are shared across all players.
 */
public final class RankingTopService {

    public enum Scope {
        GLOBAL,
        SEASON
    }

    private static final int TOP_LIMIT = 5;

    private final RankingPointsRepository repository;
    private final DatabaseService database;
    private final Clock clock;
    private final long ttlMillis;
    private final Set<Scope> refreshInFlight = ConcurrentHashMap.newKeySet();

    private volatile CacheEntry globalCache;
    private volatile CacheEntry seasonCache;

    /** Default TTL: 15s. */
    public RankingTopService(RankingPointsRepository repository) {
        this(repository, null);
    }

    /** Default TTL: 15s. */
    public RankingTopService(RankingPointsRepository repository, DatabaseService database) {
        this(repository, database, Clock.systemUTC(), 15_000L);
    }

    public RankingTopService(RankingPointsRepository repository, Clock clock, long ttlMillis) {
        this(repository, null, clock, ttlMillis);
    }

    public RankingTopService(RankingPointsRepository repository, DatabaseService database, Clock clock, long ttlMillis) {
        this.repository = repository;
        this.database = database;
        this.clock = clock;
        this.ttlMillis = Math.max(0L, ttlMillis);
    }

    public RankingTopEntry getTopGlobal(int position) {
        return getTop(Scope.GLOBAL, position);
    }

    public RankingTopEntry getTopSeason(int position) {
        return getTop(Scope.SEASON, position);
    }

    public void invalidateAll() {
        globalCache = null;
        seasonCache = null;
    }

    private RankingTopEntry getTop(Scope scope, int position) {
        if (repository == null) {
            return empty(position);
        }

        int idx = position - 1;
        if (idx < 0 || idx >= TOP_LIMIT) {
            return empty(position);
        }

        long now = clock.millis();
        CacheEntry cache = (scope == Scope.GLOBAL) ? globalCache : seasonCache;
        if (cache == null || now >= cache.expiresAtMillis) {
            if (database != null) {
                refreshAsync(scope);
                if (cache == null) {
                    return empty(position);
                }
            } else {
                cache = refresh(scope, now);
                if (scope == Scope.GLOBAL) globalCache = cache; else seasonCache = cache;
            }
        }

        if (idx >= cache.entries.size()) {
            return empty(position);
        }
        return cache.entries.get(idx);
    }

    private CacheEntry refresh(Scope scope, long now) {
        try {
            List<RankingTopEntry> list = (scope == Scope.GLOBAL)
                    ? repository.findTopGlobal(TOP_LIMIT)
                    : repository.findTopSeason(TOP_LIMIT);
            return new CacheEntry(list, now + ttlMillis);
        } catch (Exception ex) {
            // TODO log rate-limit. Fallback to empty list.
            return new CacheEntry(List.of(), now + ttlMillis);
        }
    }

    private void refreshAsync(Scope scope) {
        if (repository == null || database == null || !refreshInFlight.add(scope)) {
            return;
        }

        database.async(() -> (scope == Scope.GLOBAL)
                        ? repository.findTopGlobal(TOP_LIMIT)
                        : repository.findTopSeason(TOP_LIMIT))
                .whenComplete((list, error) -> {
                    try {
                        if (error == null && list != null) {
                            CacheEntry cache = new CacheEntry(list, clock.millis() + ttlMillis);
                            if (scope == Scope.GLOBAL) globalCache = cache; else seasonCache = cache;
                        }
                    } finally {
                        refreshInFlight.remove(scope);
                    }
                });
    }

    private static RankingTopEntry empty(int position) {
        // You can customize fallback formatting in placeholder providers.
        return new RankingTopEntry(null, "-", 0);
    }

    private record CacheEntry(List<RankingTopEntry> entries, long expiresAtMillis) {
    }
}

