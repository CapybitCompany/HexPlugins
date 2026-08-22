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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Computes the effective town member limit without storing rank data in HexTowns' database.
 * Results are cached per town, while LuckPerms remains the source of truth for permissions.
 */
public final class TownMemberLimitService {
    private final Plugin plugin;
    private final ConcurrentMap<UUID, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, RefreshEntry> refreshes = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> townVersions = new ConcurrentHashMap<>();
    private final AtomicLong globalVersion = new AtomicLong();
    private final AtomicBoolean missingPermissionProviderLogged = new AtomicBoolean(false);
    private volatile TownsConfig config;
    private volatile Object luckPerms;

    public TownMemberLimitService(Plugin plugin, TownsConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void reloadConfig(TownsConfig config) {
        this.config = config;
        clear();
    }

    public int cachedOrBase(Town town, Collection<UUID> members) {
        TownsConfig current = config;
        if (!current.dynamicMemberLimitEnabled()) {
            return current.maxMembers();
        }
        CacheEntry entry = cache.get(town.id());
        long now = System.currentTimeMillis();
        if (entry != null && entry.expiresAtMillis() > now) {
            return entry.maxMembers();
        }
        refreshAsync(town, members);
        return entry == null ? current.maxMembers() : entry.maxMembers();
    }

    public CompletableFuture<Integer> resolveAsync(Town town, Collection<UUID> members) {
        TownsConfig current = config;
        if (!current.dynamicMemberLimitEnabled()) {
            return CompletableFuture.completedFuture(current.maxMembers());
        }
        CacheEntry entry = cache.get(town.id());
        if (entry != null && entry.expiresAtMillis() > System.currentTimeMillis()) {
            return CompletableFuture.completedFuture(entry.maxMembers());
        }

        // Never let a user-facing caller wait indefinitely for LuckPerms/storage.
        // The refresh continues in the background and may populate the cache later,
        // while this particular caller receives the best bounded fallback.
        int fallback = entry == null ? current.maxMembers() : entry.maxMembers();
        return refreshAsync(town, members)
                .completeOnTimeout(fallback, current.memberLimitLookupTimeoutMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(error -> fallback);
    }

    public DebugInfo debug(Town town) {
        if (town == null) return new DebugInfo(config.maxMembers(), false, false, -1L, -1L);
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(town.id());
        boolean fresh = entry != null && entry.expiresAtMillis() > now;
        long remaining = entry == null ? -1L : Math.max(0L, entry.expiresAtMillis() - now);
        long estimatedAge = entry == null ? -1L : Math.max(0L,
                config.memberLimitCacheSeconds() * 1000L - remaining);
        return new DebugInfo(entry == null ? config.maxMembers() : entry.maxMembers(), fresh,
                refreshes.containsKey(town.id()), estimatedAge, remaining);
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

    /** Player-facing rank label used by COOP menus. Uses online Bukkit permissions first and
     * falls back to LuckPerms cached user data without blocking the main thread. */
    public String rankDisplay(UUID playerId) {
        if (playerId == null) return "§7Gracz";
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) return rankDisplayOnline(online);
        Object provider = resolveLuckPerms();
        if (provider == null) return "§7Gracz";
        try {
            Object userManager = provider.getClass().getMethod("getUserManager").invoke(provider);
            Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, playerId);
            return rankDisplayUser(user);
        } catch (Throwable ignored) {
            return "§7Gracz";
        }
    }

    private String rankDisplayOnline(Player player) {
        TownsConfig current = config;
        if (player.hasPermission("nte.media")) return "§dMedia";
        if (hasOnlinePermission(player, current.elitePermission())) return "§cElita";
        if (hasOnlinePermission(player, current.svipPermission())) return "§6SVIP";
        if (hasOnlinePermission(player, current.vipPermission())) return "§eVIP";
        return "§7Gracz";
    }

    private String rankDisplayUser(Object user) {
        if (user == null) return "§7Gracz";
        try {
            TownsConfig current = config;
            if (hasPermission(user, "nte.media")) return "§dMedia";
            if (hasPermission(user, current.elitePermission())) return "§cElita";
            if (hasPermission(user, current.svipPermission())) return "§6SVIP";
            if (hasPermission(user, current.vipPermission())) return "§eVIP";
        } catch (Throwable ignored) {
        }
        return "§7Gracz";
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
                    value = previous == null ? config.maxMembers() : previous.maxMembers();
                    plugin.getLogger().warning("Nie udało się odświeżyć limitu członków miasta " + townId + ": " + rootMessage(error));
                }
                TownsConfig current = config;
                int bounded = Math.max(current.maxMembers(), Math.min(current.maximumMembers(), value));
                if (version.equals(versionOf(townId))) {
                    cache.put(townId, new CacheEntry(bounded, System.currentTimeMillis() + current.memberLimitCacheSeconds() * 1000L));
                }
                refreshes.remove(townId, createdEntry);
                created.complete(bounded);
            });
        });
        return created;
    }

    private int calculate(Set<UUID> members, Map<UUID, Integer> onlineBonuses) {
        TownsConfig current = config;
        int result = current.maxMembers();
        Object provider = resolveLuckPerms();
        for (UUID memberId : members) {
            int bonus = provider == null ? onlineBonuses.getOrDefault(memberId, 0) : permissionBonus(provider, memberId, onlineBonuses.getOrDefault(memberId, 0));
            result += bonus;
            if (result >= current.maximumMembers()) return current.maximumMembers();
        }
        return Math.min(result, current.maximumMembers());
    }

    private int permissionBonus(Object provider, UUID playerId, int fallback) {
        try {
            Object userManager = provider.getClass().getMethod("getUserManager").invoke(provider);

            // Fast path: LuckPerms already has the user cached. This covers online players
            // and most recently seen members without any storage access.
            try {
                Object cachedUser = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, playerId);
                if (cachedUser != null) return permissionBonusFromUser(cachedUser);
            } catch (NoSuchMethodException ignored) {
                // Older/alternate LP API shape: fall through to bounded loadUser().
            }

            Method loadUser = userManager.getClass().getMethod("loadUser", UUID.class);
            Object loaded = loadUser.invoke(userManager, playerId);
            if (!(loaded instanceof CompletableFuture<?> future)) return fallback;
            Object user = future.get(config.memberLimitLookupTimeoutMillis(), TimeUnit.MILLISECONDS);
            return user == null ? fallback : permissionBonusFromUser(user);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private int permissionBonusFromUser(Object user) throws ReflectiveOperationException {
        TownsConfig current = config;
        if (hasAnyPermission(user, current.elitePermissions())) return current.eliteMemberBonus();
        if (hasPermission(user, current.svipPermission())) return current.svipMemberBonus();
        if (hasPermission(user, current.vipPermission())) return current.vipMemberBonus();
        return 0;
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
            // Fallback below supports online players through Bukkit permissions.
        }
        if (missingPermissionProviderLogged.compareAndSet(false, true)) {
            plugin.getLogger().warning("LuckPerms API nie jest dostępne. Bonusowe sloty rang offline będą rozpoznawane dopiero, gdy gracz jest online.");
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
        if (hasAnyOnlinePermission(player, current.elitePermissions())) return current.eliteMemberBonus();
        if (hasOnlinePermission(player, current.svipPermission())) return current.svipMemberBonus();
        if (hasOnlinePermission(player, current.vipPermission())) return current.vipMemberBonus();
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

    public record DebugInfo(int cachedOrBaseLimit, boolean fresh, boolean refreshing,
                            long estimatedCacheAgeMillis, long cacheRemainingMillis) {
    }

    private record CacheEntry(int maxMembers, long expiresAtMillis) {
    }

    private record Version(long global, long town) {
    }

    private record RefreshEntry(Version version, CompletableFuture<Integer> future) {
    }
}
