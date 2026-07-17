package pl.hexnetwork.hexnametags.persistence;

import hex.core.api.HexApi;
import hex.core.api.db.DatabaseService;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class NameTagPersistenceService {
    private final JavaPlugin plugin;
    private final HexApi hexApi;
    private final Map<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    private NameTagPersistenceSettings settings = NameTagPersistenceSettings.defaults();
    private NameTagRepository repository;
    private boolean available;

    public NameTagPersistenceService(JavaPlugin plugin, HexApi hexApi) {
        this.plugin = plugin;
        this.hexApi = hexApi;
    }

    public void reloadSettings() {
        this.settings = NameTagPersistenceSettings.fromConfig(plugin.getConfig().getConfigurationSection("database"));
        this.available = false;
        this.repository = null;

        if (!settings.enabled()) {
            plugin.getLogger().info("[DB] HexNameTags persistence disabled in config.yml.");
            return;
        }
        if (hexApi == null) {
            plugin.getLogger().warning("[DB] HexCore API not found. Persistent name tags disabled.");
            return;
        }
        DatabaseService database = hexApi.db();
        if (database == null || !isDatabaseAvailable(database)) {
            String reason = database == null ? "HexCore DatabaseService is null" : databaseUnavailableReason(database);
            plugin.getLogger().warning("[DB] HexCore database unavailable. Persistent name tags disabled. Reason: " + reason);
            return;
        }

        try {
            this.repository = new NameTagRepository(database.db(), settings.tableName());
            if (settings.createTable()) {
                this.repository.createTableIfMissing();
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "[DB] Could not initialize HexNameTags database repository. Persistence disabled.", exception);
            this.repository = null;
            return;
        }

        this.available = true;
        plugin.getLogger().info("[DB] HexNameTags persistence enabled. Table: " + repository.table()
                + ", cache TTL: " + settings.cacheTtlMillis() + " ms");
    }

    public boolean isAvailable() {
        return available && repository != null && hexApi != null && hexApi.db() != null && isDatabaseAvailable(hexApi.db());
    }

    public boolean shouldLoadOnlineOnStart() {
        return isAvailable() && settings.loadOnlineOnStart();
    }

    public CompletableFuture<Optional<PersistedNameTag>> loadPlayer(UUID playerUuid) {
        if (!isAvailable() || playerUuid == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(playerUuid);
        if (cached != null && settings.cacheTtlMillis() > 0L && now - cached.loadedAtMillis() <= settings.cacheTtlMillis()) {
            return CompletableFuture.completedFuture(cached.value());
        }

        return hexApi.db().async(() -> {
            Optional<PersistedNameTag> result = repository.findEnabled(playerUuid)
                    .filter(tag -> tag.targetType() == PersistedNameTag.TargetType.PLAYER)
                    .filter(tag -> !tag.lines().isEmpty());
            cache.put(playerUuid, new CacheEntry(result, System.currentTimeMillis()));
            return result;
        }).exceptionally(exception -> {
            plugin.getLogger().log(Level.WARNING, "[DB] Could not load name tag for player " + playerUuid, exception);
            return Optional.empty();
        });
    }

    public void savePlayer(UUID playerUuid, List<Component> lines) {
        if (!isAvailable() || !settings.savePlayerTags() || playerUuid == null || lines == null || lines.isEmpty()) {
            return;
        }

        PersistedNameTag optimistic = new PersistedNameTag(
                playerUuid,
                PersistedNameTag.TargetType.PLAYER,
                lines,
                "default",
                true,
                System.currentTimeMillis()
        );
        cache.put(playerUuid, new CacheEntry(Optional.of(optimistic), System.currentTimeMillis()));

        hexApi.db().async(() -> {
                    repository.upsertPlayer(playerUuid, lines, "default");
                    return null;
                })
                .exceptionally(exception -> {
                    plugin.getLogger().log(Level.WARNING, "[DB] Could not save name tag for player " + playerUuid, exception);
                    return null;
                });
    }

    public void deletePlayer(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        cache.remove(playerUuid);
        if (!isAvailable()) {
            return;
        }
        hexApi.db().async(() -> {
                    repository.delete(playerUuid);
                    return null;
                })
                .exceptionally(exception -> {
                    plugin.getLogger().log(Level.WARNING, "[DB] Could not delete name tag for player " + playerUuid, exception);
                    return null;
                });
    }

    private boolean isDatabaseAvailable(DatabaseService database) {
        try {
            Method method = database.getClass().getMethod("isAvailable");
            Object result = method.invoke(database);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (NoSuchMethodException ignored) {
            return true;
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "[DB] Could not check HexCore database availability. Assuming unavailable.", exception);
            return false;
        }
    }

    private String databaseUnavailableReason(DatabaseService database) {
        try {
            Method method = database.getClass().getMethod("unavailableReason");
            Object result = method.invoke(database);
            return result == null ? "unknown" : result.toString();
        } catch (NoSuchMethodException ignored) {
            return "unknown";
        } catch (Exception exception) {
            return exception.getMessage() == null ? "unknown" : exception.getMessage();
        }
    }

    public void clearCache() {
        cache.clear();
    }

    private record CacheEntry(Optional<PersistedNameTag> value, long loadedAtMillis) {
    }
}
