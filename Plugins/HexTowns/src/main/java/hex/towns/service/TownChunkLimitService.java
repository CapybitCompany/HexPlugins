package hex.towns.service;

import hex.towns.config.TownsConfig;
import hex.towns.model.Town;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Effective claim capacity: base 49 + best rank bonus per town member, capped at 69.
 * Rank data is not persisted by HexTowns; LuckPerms remains the source of truth.
 */
public final class TownChunkLimitService {
    private final Plugin plugin;
    private final ConcurrentMap<UUID, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, RefreshEntry> refreshes = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> townVersions = new ConcurrentHashMap<>();
    private final AtomicLong globalVersion = new AtomicLong();
    private final AtomicBoolean missingPermissionProviderLogged = new AtomicBoolean(false);
    private volatile TownsConfig config;
    private volatile Object luckPerms;

    public TownChunkLimitService(Plugin plugin, TownsConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void reloadConfig(TownsConfig config) {
        this.config = config;
        clear();
    }

    public int cachedOrBase(Town town, Collection<UUID> members) {
        TownsConfig current = config;
        if (!current.dynamicChunkLimitEnabled()) return current.maxChunks();
        CacheEntry entry = cache.get(town.id());
        long now = System.currentTimeMillis();
        if (entry != null && entry.expiresAtMillis() > now) return entry.maxChunks();
        refreshAsync(town, members);
        return entry == null ? current.maxChunks() : entry.maxChunks();
    }

    public CompletableFuture<Integer> resolveAsync(Town town, Collection<UUID> members) {
        TownsConfig current = config;
        if (!current.dynamicChunkLimitEnabled()) return CompletableFuture.completedFuture(current.maxChunks());
        CacheEntry entry = cache.get(town.id());
        if (entry != null && entry.expiresAtMillis() > System.currentTimeMillis()) {
            return CompletableFuture.completedFuture(entry.maxChunks());
        }
        return refreshAsync(town, members);
    }

    public void invalidate(UUID townId) {
        if (townId == null) return;
        cache.remove(townId);
        townVersions.merge(townId, 1L, Long::sum);
    }

    public void clear() {
        globalVersion.incrementAndGet();
        cache.clear();
        refreshes.clear();
        townVersions.clear();
    }

    private CompletableFuture<Integer> refreshAsync(Town town, Collection<UUID> members) {
        UUID townId = town.id();
        Version version = versionOf(townId);
        RefreshEntry active = refreshes.get(townId);
        if (active != null && active.version().equals(version)) return active.future();

        CompletableFuture<Integer> created = new CompletableFuture<>();
        RefreshEntry createdEntry = new RefreshEntry(version, created);
        while (true) {
            active = refreshes.get(townId);
            if (active != null && active.version().equals(version)) return active.future();
            if (active == null) {
                if (refreshes.putIfAbsent(townId, createdEntry) == null) break;
            } else if (refreshes.replace(townId, active, createdEntry)) {
                break;
            }
        }

        Set<UUID> snapshot = Set.copyOf(members);
        captureOnlineBonuses(snapshot).whenComplete((onlineBonuses, captureError) -> {
            Map<UUID, Integer> fallback = captureError == null ? onlineBonuses : Map.of();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                int value;
                try {
                    value = calculate(snapshot, fallback);
                } catch (Throwable error) {
                    CacheEntry previous = cache.get(townId);
                    value = previous == null ? config.maxChunks() : previous.maxChunks();
                    plugin.getLogger().warning("Nie udało się odświeżyć limitu chunków miasta " + townId + ": " + rootMessage(error));
                }
                TownsConfig current = config;
                int bounded = Math.max(current.maxChunks(), Math.min(current.maximumChunks(), value));
                if (version.equals(versionOf(townId))) {
                    cache.put(townId, new CacheEntry(bounded, System.currentTimeMillis() + current.chunkLimitCacheSeconds() * 1000L));
                }
                refreshes.remove(townId, createdEntry);
                created.complete(bounded);
            });
        });
        return created;
    }

    private int calculate(Set<UUID> members, Map<UUID, Integer> onlineBonuses) {
        TownsConfig current = config;
        int result = current.maxChunks();
        Object provider = resolveLuckPerms();
        for (UUID memberId : members) {
            int fallback = onlineBonuses.getOrDefault(memberId, 0);
            int bonus = provider == null ? fallback : permissionBonus(provider, memberId, fallback);
            result += bonus;
            if (result >= current.maximumChunks()) return current.maximumChunks();
        }
        return Math.min(result, current.maximumChunks());
    }

    private int permissionBonus(Object provider, UUID playerId, int fallback) {
        try {
            Object userManager = provider.getClass().getMethod("getUserManager").invoke(provider);
            Method loadUser = userManager.getClass().getMethod("loadUser", UUID.class);
            Object loaded = loadUser.invoke(userManager, playerId);
            if (!(loaded instanceof CompletableFuture<?> future)) return fallback;
            Object user = future.join();
            if (user == null) return fallback;
            TownsConfig current = config;
            if (hasAnyPermission(user, current.chunkElitePermissions())) return current.chunkEliteBonus();
            if (hasPermission(user, current.chunkSvipPermission())) return current.chunkSvipBonus();
            if (hasPermission(user, current.chunkVipPermission())) return current.chunkVipBonus();
            return 0;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private boolean hasPermission(Object user, String permission) throws ReflectiveOperationException {
        if (permission == null || permission.isBlank()) return false;
        Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
        Object permissionData = cachedData.getClass().getMethod("getPermissionData").invoke(cachedData);
        Object tristate = permissionData.getClass().getMethod("checkPermission", String.class).invoke(permissionData, permission);
        Object result = tristate.getClass().getMethod("asBoolean").invoke(tristate);
        return result instanceof Boolean value && value;
    }

    private boolean hasAnyPermission(Object user, Collection<String> permissions) throws ReflectiveOperationException {
        if (permissions == null || permissions.isEmpty()) return false;
        for (String permission : permissions) {
            if (hasPermission(user, permission)) return true;
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object resolveLuckPerms() {
        Object current = luckPerms;
        if (current != null) return current;
        try {
            Class type = Class.forName("net.luckperms.api.LuckPerms");
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(type);
            if (registration != null) {
                current = registration.getProvider();
                luckPerms = current;
                return current;
            }
        } catch (ClassNotFoundException ignored) {
        }
        if (missingPermissionProviderLogged.compareAndSet(false, true)) {
            plugin.getLogger().warning("LuckPerms API nie jest dostępne. Bonus chunków rang offline będzie widoczny po wejściu gracza.");
        }
        return null;
    }

    private CompletableFuture<Map<UUID, Integer>> captureOnlineBonuses(Set<UUID> members) {
        CompletableFuture<Map<UUID, Integer>> future = new CompletableFuture<>();
        Runnable capture = () -> {
            Map<UUID, Integer> result = new HashMap<>();
            for (UUID memberId : members) {
                Player player = Bukkit.getPlayer(memberId);
                if (player != null) result.put(memberId, onlinePermissionBonus(player));
            }
            future.complete(Map.copyOf(result));
        };
        if (Bukkit.isPrimaryThread()) capture.run();
        else Bukkit.getScheduler().runTask(plugin, capture);
        return future;
    }

    private int onlinePermissionBonus(Player player) {
        TownsConfig current = config;
        if (hasAnyOnlinePermission(player, current.chunkElitePermissions())) return current.chunkEliteBonus();
        if (hasOnlinePermission(player, current.chunkSvipPermission())) return current.chunkSvipBonus();
        if (hasOnlinePermission(player, current.chunkVipPermission())) return current.chunkVipBonus();
        return 0;
    }

    private boolean hasOnlinePermission(Player player, String permission) {
        return permission != null && !permission.isBlank() && player.hasPermission(permission);
    }

    private boolean hasAnyOnlinePermission(Player player, Collection<String> permissions) {
        if (permissions == null || permissions.isEmpty()) return false;
        for (String permission : permissions) {
            if (hasOnlinePermission(player, permission)) return true;
        }
        return false;
    }

    private Version versionOf(UUID townId) {
        return new Version(globalVersion.get(), townVersions.getOrDefault(townId, 0L));
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record CacheEntry(int maxChunks, long expiresAtMillis) {}
    private record Version(long global, long town) {}
    private record RefreshEntry(Version version, CompletableFuture<Integer> future) {}
}
