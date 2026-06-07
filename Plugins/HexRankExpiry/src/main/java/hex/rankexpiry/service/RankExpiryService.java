package hex.rankexpiry.service;

import hex.core.api.HexApi;
import hex.rankexpiry.config.RankExpirySettings;
import hex.rankexpiry.database.LuckyPermsRankRepository;
import hex.rankexpiry.model.RankExpiry;
import org.bukkit.plugin.Plugin;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class RankExpiryService {
    private final Plugin plugin;
    private final HexApi hexApi;
    private final Clock clock;
    private final ConcurrentMap<UUID, CachedRank> cache = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private volatile LuckyPermsRankRepository repository;
    private volatile RankExpirySettings settings;

    public RankExpiryService(Plugin plugin, HexApi hexApi, LuckyPermsRankRepository repository, RankExpirySettings settings, Clock clock) {
        this.plugin = plugin;
        this.hexApi = hexApi;
        this.repository = repository;
        this.settings = settings;
        this.clock = clock;
    }

    public void reload(LuckyPermsRankRepository repository, RankExpirySettings settings) {
        this.repository = repository;
        this.settings = settings;
        this.cache.clear();
        this.inFlight.clear();
    }

    public RankLookup lookup(UUID uuid) {
        CachedRank cached = cache.get(uuid);
        if (cached == null) {
            refresh(uuid);
            return RankLookup.loadingLookup();
        }

        long now = nowEpochSeconds();
        if (cached.isStale(now, settings.cacheTtlSeconds())) {
            refresh(uuid);
        }

        Optional<RankExpiry> activeRank = cached.rank().filter(rank -> rank.activeAt(now));
        return RankLookup.cached(activeRank);
    }

    public CompletableFuture<Optional<RankExpiry>> refreshNow(UUID uuid) {
        inFlight.add(uuid);
        long now = nowEpochSeconds();
        LuckyPermsRankRepository activeRepository = repository;
        return hexApi.db().async(() -> activeRepository.findActiveRank(hexApi.db().db(), uuid, now))
                .handle((rank, throwable) -> {
                    inFlight.remove(uuid);
                    if (throwable != null) {
                        plugin.getLogger().warning("Could not load LuckyPerms rank expiry for uuid=" + uuid + ": " + rootMessage(throwable));
                        return Optional.<RankExpiry>empty();
                    }
                    cache.put(uuid, new CachedRank(rank, nowEpochSeconds()));
                    return rank;
                });
    }

    public void refresh(UUID uuid) {
        if (!inFlight.add(uuid)) {
            return;
        }

        long now = nowEpochSeconds();
        LuckyPermsRankRepository activeRepository = repository;
        hexApi.db().async(() -> activeRepository.findActiveRank(hexApi.db().db(), uuid, now))
                .whenComplete((rank, throwable) -> {
                    inFlight.remove(uuid);
                    if (throwable != null) {
                        plugin.getLogger().warning("Could not refresh LuckyPerms rank expiry for uuid=" + uuid + ": " + rootMessage(throwable));
                        return;
                    }
                    cache.put(uuid, new CachedRank(rank, nowEpochSeconds()));
                });
    }

    public void invalidate(UUID uuid) {
        cache.remove(uuid);
        inFlight.remove(uuid);
    }

    public long nowEpochSeconds() {
        return Instant.now(clock).getEpochSecond();
    }

    public RankExpirySettings settings() {
        return settings;
    }

    public String format(String template, RankExpiry rank) {
        long now = nowEpochSeconds();
        long days = rank.daysRemaining(now);
        long seconds = rank.secondsRemaining(now);
        return template
                .replace("{rank}", rank.displayName())
                .replace("{permission}", rank.permission())
                .replace("{days}", Long.toString(days))
                .replace("{day_word}", dayWord(days))
                .replace("{seconds}", Long.toString(seconds))
                .replace("{expiry}", Long.toString(rank.expiryEpochSeconds()));
    }

    public static String dayWord(long days) {
        return days == 1L ? "dzień" : "dni";
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        Set<Throwable> seen = new HashSet<>();
        while (current.getCause() != null && seen.add(current.getCause())) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record RankLookup(boolean loading, Optional<RankExpiry> rank) {
        private static RankLookup loadingLookup() {
            return new RankLookup(true, Optional.empty());
        }

        private static RankLookup cached(Optional<RankExpiry> rank) {
            return new RankLookup(false, rank);
        }
    }

    private record CachedRank(Optional<RankExpiry> rank, long cachedAtEpochSeconds) {
        private boolean isStale(long nowEpochSeconds, long ttlSeconds) {
            return cachedAtEpochSeconds + ttlSeconds <= nowEpochSeconds;
        }
    }
}
