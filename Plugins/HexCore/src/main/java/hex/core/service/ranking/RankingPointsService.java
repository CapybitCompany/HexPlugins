package hex.core.service.ranking;

import hex.core.api.db.DatabaseService;
import hex.core.database.model.RankingPointsRecord;
import hex.core.database.repository.RankingPointsRepository;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RankingPointsService {

    /**
     * Repoztytorium może być null, jeśli DB jest wyłączone lub nieobsługiwane w danym środowisku.
     */
    private final RankingPointsRepository repository;
    private final DatabaseService database;

    /**
     * Cache read-through z TTL (per UUID). Chroni DB przed spamem przy UI/placeholderach.
     */
    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Set<UUID> refreshInFlight = ConcurrentHashMap.newKeySet();

    private final Clock clock;
    private final long ttlMillis;

    /**
     * Domyślny TTL: 15s (celowo krótko, bo to statystyki w MC i mogą się zmieniać).
     * Jeśli okaże się, że nadal jest dużo odpytań, podnieś do 30-60s.
     */
    public RankingPointsService(RankingPointsRepository repository) {
        this(repository, null);
    }

    public RankingPointsService(RankingPointsRepository repository, DatabaseService database) {
        this(repository, database, Clock.systemUTC(), 15_000L);
    }

    public RankingPointsService(RankingPointsRepository repository, Clock clock, long ttlMillis) {
        this(repository, null, clock, ttlMillis);
    }

    public RankingPointsService(RankingPointsRepository repository, DatabaseService database, Clock clock, long ttlMillis) {
        this.repository = repository;
        this.database = database;
        this.clock = clock;
        this.ttlMillis = Math.max(0L, ttlMillis);
    }

    public int getGlobalPoints(UUID uuid) {
        return get(uuid).globalPoints();
    }

    public int getSeasonPoints(UUID uuid) {
        return get(uuid).seasonPoints();
    }

    /**
     * TODO: wywołuj po update punktów (gdy dojdzie write-path), żeby od razu odświeżać gracza.
     */
    public void invalidate(UUID uuid) {
        if (uuid != null) cache.remove(uuid);
    }

    /**
     * TODO: przy sezonowym resecie rankingów (albo admin-reload) przydaje się globalne czyszczenie.
     */
    public void invalidateAll() {
        cache.clear();
    }

    private RankingPointsRecord get(UUID uuid) {
        if (uuid == null) {
            // brak gracza -> zachowujemy się jak brak rekordu
            return new RankingPointsRecord(null, 0, 0, null);
        }

        // DB wyłączona -> zawsze 0
        if (repository == null) {
            return new RankingPointsRecord(uuid, 0, 0, null);
        }

        long now = clock.millis();

        CacheEntry entry = cache.get(uuid);
        if (entry != null && now < entry.expiresAtMillis) {
            return entry.record;
        }

        if (database != null) {
            refreshAsync(uuid);
            return entry == null ? new RankingPointsRecord(uuid, 0, 0, null) : entry.record;
        }

        // Miss/expired -> odczyt z DB i zapis do cache.
        // Negative caching: jeśli nie ma rekordu, też zapamiętujemy "0" na ttlMillis.
        RankingPointsRecord record;
        try {
            record = repository.findByUuid(uuid)
                    .orElseGet(() -> new RankingPointsRecord(uuid, 0, 0, null));
        } catch (Exception ex) {
            // Bez wywalania wątków/UI przy chwilowym błędzie DB.
            // TODO: rozważ log rate-limit + zwracanie "stale" jeśli entry != null.
            record = new RankingPointsRecord(uuid, 0, 0, null);
        }

        cache.put(uuid, new CacheEntry(record, now + ttlMillis));
        return record;
    }

    private void refreshAsync(UUID uuid) {
        if (uuid == null || repository == null || database == null || !refreshInFlight.add(uuid)) {
            return;
        }

        database.async(() -> repository.findByUuid(uuid)
                        .orElseGet(() -> new RankingPointsRecord(uuid, 0, 0, null)))
                .whenComplete((record, error) -> {
                    try {
                        if (error == null && record != null) {
                            cache.put(uuid, new CacheEntry(record, clock.millis() + ttlMillis));
                        }
                    } finally {
                        refreshInFlight.remove(uuid);
                    }
                });
    }

    private static final class CacheEntry {
        private final RankingPointsRecord record;
        private final long expiresAtMillis;

        private CacheEntry(RankingPointsRecord record, long expiresAtMillis) {
            this.record = record;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
