package hex.core.service.coins;

import hex.core.database.repository.CoinsRepository;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coins/balance service.
 *
 * Reads coins from external table: xeconomy.balance.
 *
 * TODO: In the future, consider async refresh + anti-stampede if UI calls become very frequent.
 */
public final class CoinsService {

    private final CoinsRepository repository;

    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long ttlMillis;

    /** Default TTL: 15s. */
    public CoinsService(CoinsRepository repository) {
        this(repository, Clock.systemUTC(), 15_000L);
    }

    public CoinsService(CoinsRepository repository, Clock clock, long ttlMillis) {
        this.repository = repository;
        this.clock = clock;
        this.ttlMillis = Math.max(0L, ttlMillis);
    }

    public int getCoins(UUID uuid) {
        if (uuid == null) return 0;
        if (repository == null) return 0;

        long now = clock.millis();
        CacheEntry entry = cache.get(uuid);
        if (entry != null && now < entry.expiresAtMillis) {
            return entry.coins;
        }

        int coins;
        try {
            coins = repository.findBalanceByUuid(uuid).orElse(0);
        } catch (Exception ex) {
            // TODO log rate-limit; optionally return stale value if present
            coins = 0;
        }

        cache.put(uuid, new CacheEntry(coins, now + ttlMillis));
        return coins;
    }

    public void invalidate(UUID uuid) {
        if (uuid != null) cache.remove(uuid);
    }

    public void invalidateAll() {
        cache.clear();
    }

    private static final class CacheEntry {
        private final int coins;
        private final long expiresAtMillis;

        private CacheEntry(int coins, long expiresAtMillis) {
            this.coins = coins;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
