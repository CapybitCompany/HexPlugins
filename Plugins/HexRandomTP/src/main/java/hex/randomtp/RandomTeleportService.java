package hex.randomtp;

import hex.core.api.HexApi;
import hex.core.api.region.Region;
import hex.core.api.region.RegionKey;
import hex.core.api.ui.UiTokens;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

final class RandomTeleportService {
    private final HexRandomTpPlugin plugin;
    private final HexApi hexApi;
    private final CooldownService cooldownService;
    private final Set<UUID> searchesInProgress = ConcurrentHashMap.newKeySet();
    private final Set<BukkitTask> retryTasks = ConcurrentHashMap.newKeySet();

    RandomTeleportService(HexRandomTpPlugin plugin, HexApi hexApi, CooldownService cooldownService) {
        this.plugin = plugin;
        this.hexApi = hexApi;
        this.cooldownService = cooldownService;
    }

    void request(Player player) {
        if (!player.hasPermission("hexrandomtp.use")) {
            hexApi.ui().send(player, "randomtp.no-permission");
            return;
        }

        long remainingSeconds = cooldownService.remainingSeconds(player, plugin.rtpConfig());
        if (remainingSeconds > 0L) {
            hexApi.ui().send(player, "randomtp.cooldown", UiTokens.of("seconds", Long.toString(remainingSeconds)));
            return;
        }

        UUID playerId = player.getUniqueId();
        if (!searchesInProgress.add(playerId)) {
            hexApi.ui().send(player, "randomtp.already-searching");
            return;
        }

        World world = Bukkit.getWorld(plugin.rtpConfig().worldName());
        if (world == null) {
            searchesInProgress.remove(playerId);
            hexApi.ui().send(player, "randomtp.world-missing",
                    UiTokens.of("world", plugin.rtpConfig().worldName()));
            return;
        }

        hexApi.ui().send(player, "randomtp.searching");
        attempt(playerId, world, 1);
    }

    void shutdown() {
        for (BukkitTask task : Set.copyOf(retryTasks)) {
            task.cancel();
        }
        retryTasks.clear();
        searchesInProgress.clear();
    }

    private void attempt(UUID playerId, World world, int attemptNumber) {
        if (!plugin.isEnabled()) {
            searchesInProgress.remove(playerId);
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            searchesInProgress.remove(playerId);
            return;
        }

        RtpConfig config = plugin.rtpConfig();
        if (attemptNumber > config.maxAttempts()) {
            searchesInProgress.remove(playerId);
            hexApi.ui().send(player, "randomtp.no-location");
            return;
        }

        int x = randomInclusive(config.minX(), config.maxX());
        int z = randomInclusive(config.minZ(), config.maxZ());
        if (config.isForbiddenCoordinate(x, z)) {
            scheduleRetry(playerId, world, attemptNumber + 1);
            return;
        }

        int chunkX = Math.floorDiv(x, 16);
        int chunkZ = Math.floorDiv(z, 16);
        if (config.isForbiddenChunk(chunkX, chunkZ)) {
            scheduleRetry(playerId, world, attemptNumber + 1);
            return;
        }

        world.getChunkAtAsync(chunkX, chunkZ, config.generateNewChunks()).whenComplete((chunk, throwable) -> {
            if (!plugin.isEnabled()) {
                searchesInProgress.remove(playerId);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!plugin.isEnabled() || !searchesInProgress.contains(playerId)) {
                        return;
                    }

                    Player currentPlayer = Bukkit.getPlayer(playerId);
                    if (currentPlayer == null || !currentPlayer.isOnline()) {
                        searchesInProgress.remove(playerId);
                        return;
                    }

                    if (throwable != null || chunk == null) {
                        plugin.getLogger().warning("Nie udało się załadować chunka " + chunkX + ", " + chunkZ
                                + " podczas RTP: " + (throwable == null ? "brak wyniku" : throwable.getMessage()));
                        scheduleRetry(playerId, world, attemptNumber + 1);
                        return;
                    }

                    Location safeLocation = findSafeLocation(world, x, z, plugin.rtpConfig());
                    if (safeLocation == null) {
                        scheduleRetry(playerId, world, attemptNumber + 1);
                        return;
                    }

                    safeLocation.setYaw(currentPlayer.getLocation().getYaw());
                    safeLocation.setPitch(currentPlayer.getLocation().getPitch());
                    teleport(currentPlayer, safeLocation);
                });
        });
    }

    private void scheduleRetry(UUID playerId, World world, int nextAttempt) {
        long delay = plugin.rtpConfig().retryDelayTicks();
        if (delay <= 0L) {
            attempt(playerId, world, nextAttempt);
            return;
        }

        final BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            retryTasks.remove(holder[0]);
            attempt(playerId, world, nextAttempt);
        }, delay);
        retryTasks.add(holder[0]);
    }

    private Location findSafeLocation(World world, int x, int z, RtpConfig config) {
        // Ponowna kontrola chroni także przed zmianą konfiguracji podczas trwającego wyszukiwania.
        if (config.isForbiddenCoordinate(x, z)) {
            return null;
        }

        int surfaceY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        int lowestY = Math.max(config.minSurfaceY(), world.getMinHeight());
        int highestY = Math.min(config.maxSurfaceY(), world.getMaxHeight() - 3);
        if (surfaceY < lowestY || surfaceY > highestY) {
            return null;
        }

        Block surface = world.getBlockAt(x, surfaceY, z);
        Block feet = world.getBlockAt(x, surfaceY + 1, z);
        Block head = world.getBlockAt(x, surfaceY + 2, z);

        if (!surface.getType().isSolid() || config.forbiddenSurfaceBlocks().contains(surface.getType())) {
            return null;
        }
        if (!isSafeAir(feet, config) || !isSafeAir(head, config)) {
            return null;
        }

        Biome biome = world.getBiome(x, surfaceY, z);
        if (biome == null || config.forbiddenBiomes().contains(
                biome.getKey().toString().toLowerCase(Locale.ROOT))) {
            return null;
        }

        Location destination = new Location(world, x + 0.5D, surfaceY + 1.0D, z + 0.5D);
        if (config.respectWorldBorder() && !world.getWorldBorder().isInside(destination)) {
            return null;
        }
        if (isInsideForbiddenHexCoreRegion(destination, config)) {
            return null;
        }

        return destination;
    }

    private boolean isSafeAir(Block block, RtpConfig config) {
        Material type = block.getType();
        return block.isPassable()
                && !block.isLiquid()
                && !config.forbiddenSurfaceBlocks().contains(type)
                && type != Material.FIRE
                && type != Material.SOUL_FIRE;
    }

    private boolean isInsideForbiddenHexCoreRegion(Location location, RtpConfig config) {
        if (config.excludeAnyHexCoreRegion()) {
            for (Region region : hexApi.regions().listAll()) {
                if (region.contains(location)) {
                    return true;
                }
            }
        }

        for (RegionKey key : config.forbiddenRegionKeys()) {
            if (hexApi.regions().contains(key, location)) {
                return true;
            }
        }

        for (String namespace : config.forbiddenRegionNamespaces()) {
            for (Region region : hexApi.regions().listNamespace(namespace)) {
                if (region.contains(location)) {
                    return true;
                }
            }
        }

        return false;
    }

    private void teleport(Player player, Location destination) {
        player.teleportAsync(destination).whenComplete((success, throwable) -> {
            if (!plugin.isEnabled()) {
                searchesInProgress.remove(player.getUniqueId());
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                searchesInProgress.remove(player.getUniqueId());
                if (!plugin.isEnabled() || !player.isOnline()) {
                    return;
                }

                if (throwable != null || !Boolean.TRUE.equals(success)) {
                    if (throwable != null) {
                        plugin.getLogger().warning("Błąd teleportacji RTP gracza " + player.getName()
                                + ": " + throwable.getMessage());
                    }
                    hexApi.ui().send(player, "randomtp.teleport-failed");
                    return;
                }

                cooldownService.markSuccessfulUse(player);
                hexApi.ui().send(player, "randomtp.success",
                        UiTokens.of("x", Integer.toString(destination.getBlockX()))
                                .put("y", Integer.toString(destination.getBlockY()))
                                .put("z", Integer.toString(destination.getBlockZ())));
            });
        });
    }

    private static int randomInclusive(int min, int max) {
        if (min == max) {
            return min;
        }
        long range = (long) max - min + 1L;
        return (int) (min + ThreadLocalRandom.current().nextLong(range));
    }
}
