package hexnpc.data;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Cached, asynchronous arbitrary namespaced player data. */
public final class PlayerDataService {
    private final PlayerDataRepository repository;
    private final Logger logger;
    private final ConcurrentMap<UUID, ConcurrentMap<String, String>> cache = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> loaded = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<UUID, CompletableFuture<Void>> loads = new ConcurrentHashMap<>();
    private volatile CompletableFuture<Void> initFuture;
    private volatile boolean ready;

    public PlayerDataService(PlayerDataRepository repository, Logger logger) {
        this.repository = repository;
        this.logger = logger;
    }

    public boolean available() {
        return repository != null && repository.available();
    }

    public synchronized CompletableFuture<Void> init() {
        if (!available()) return failed(new IllegalStateException("HexCore database unavailable"));
        if (initFuture == null) {
            initFuture = repository.init().whenComplete((ignored, error) -> ready = error == null);
        }
        return initFuture;
    }

    /** True only after the persistence schema has initialized successfully. */
    public boolean ready() {
        return available() && ready;
    }

    private CompletableFuture<Void> readyFuture() {
        if (!available()) return failed(new IllegalStateException("HexCore database unavailable"));
        CompletableFuture<Void> current = initFuture;
        return current == null ? init() : current;
    }

    public CompletableFuture<Void> ensureLoaded(UUID playerId) {
        if (playerId == null) return failed(new IllegalArgumentException("playerId"));
        if (loaded.contains(playerId)) return CompletableFuture.completedFuture(null);
        if (!available()) return failed(new IllegalStateException("HexCore database unavailable"));
        return loads.computeIfAbsent(playerId, id -> readyFuture().thenCompose(ignored -> repository.load(id)).thenAccept(values -> {
            ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
            if (values != null) map.putAll(values);
            cache.put(id, map);
            loaded.add(id);
        }).whenComplete((ignored, error) -> {
            loads.remove(id);
            if (error != null && logger != null) logger.log(Level.WARNING,
                    "HexNPC: failed to load player data for " + id + ": " + error.getMessage());
        }));
    }

    public String getCached(UUID playerId, String key) {
        if (playerId == null || key == null) return "";
        Map<String, String> values = cache.get(playerId);
        return values == null ? "" : values.getOrDefault(key, "");
    }

    public boolean hasCached(UUID playerId, String key) {
        return !getCached(playerId, key).isEmpty();
    }

    public CompletableFuture<Void> set(UUID playerId, String key, String value) {
        if (!available()) return failed(new IllegalStateException("HexCore database unavailable"));
        return ensureLoaded(playerId).thenCompose(ignored -> repository.set(playerId, key, value)
                .thenRun(() -> cache.computeIfAbsent(playerId, id -> new ConcurrentHashMap<>())
                        .put(key, value == null ? "" : value)));
    }

    public CompletableFuture<Void> delete(UUID playerId, String key) {
        if (!available()) return failed(new IllegalStateException("HexCore database unavailable"));
        return ensureLoaded(playerId).thenCompose(ignored -> repository.delete(playerId, key)
                .thenRun(() -> {
                    Map<String, String> values = cache.get(playerId);
                    if (values != null) values.remove(key);
                }));
    }

    public void unload(UUID playerId) {
        if (playerId == null) return;
        cache.remove(playerId);
        loaded.remove(playerId);
        loads.remove(playerId);
    }

    public void clear() {
        cache.clear();
        loaded.clear();
        loads.clear();
    }

    private static <T> CompletableFuture<T> failed(Throwable t) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(t);
        return future;
    }
}
