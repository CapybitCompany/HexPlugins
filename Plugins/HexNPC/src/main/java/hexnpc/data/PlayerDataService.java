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
    private volatile Throwable initError;

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
            initFuture = repository.init().whenComplete((ignored, error) -> {
                ready = error == null;
                initError = error;
            });
        }
        return initFuture;
    }

    /** True only after the persistence schema has initialized successfully. */
    public boolean ready() {
        return available() && ready;
    }

    /** Human-readable diagnostic state; intended for admin logs/commands only. */
    public String status() {
        if (!available()) return "unavailable (HexCore DatabaseService not resolved)";
        if (ready) return "ready";
        CompletableFuture<Void> current = initFuture;
        if (current == null || !current.isDone()) return "initializing";
        Throwable error = initError;
        if (error == null) return "not-ready";
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        return "failed (" + cause.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message) + ")";
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

        CompletableFuture<Void> existing = loads.get(playerId);
        if (existing != null) return existing;

        CompletableFuture<Void> created = readyFuture()
                .thenCompose(ignored -> repository.load(playerId))
                .thenAccept(values -> {
                    ConcurrentMap<String, String> map = new ConcurrentHashMap<>();
                    if (values != null) map.putAll(values);
                    cache.put(playerId, map);
                    loaded.add(playerId);
                });

        CompletableFuture<Void> raced = loads.putIfAbsent(playerId, created);
        if (raced != null) return raced;

        // Attach cleanup only after the future is visible in the map. Using
        // computeIfAbsent(...whenComplete(remove)) can recursively mutate the same
        // ConcurrentHashMap bin when the backend completes synchronously.
        created.whenComplete((ignored, error) -> {
            loads.remove(playerId, created);
            if (error != null && logger != null) logger.log(Level.WARNING,
                    "HexNPC: failed to load player data for " + playerId + ": " + error.getMessage());
        });
        return created;
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
