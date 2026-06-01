package hexabovename.service;

import hexabovename.HexAboveNamePlugin;
import hexabovename.config.HexAboveNameConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DisplayRenderService {

    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    private final HexAboveNamePlugin plugin;
    private final HexAboveNameConfig config;
    private final HexAboveNameConfig.TitleSystem titleConfig;
    private final DisplayTextCacheService cacheService;
    private final Map<UUID, TrackedDisplay> trackedDisplays = new ConcurrentHashMap<>();
    private final Map<UUID, String> rawTextCache = new ConcurrentHashMap<>();
    private final Map<UUID, Component> componentTextCache = new ConcurrentHashMap<>();
    private final Set<UUID> movingPlayers = ConcurrentHashMap.newKeySet();
    private final double movementThresholdSquared;

    private BukkitTask movingTask;
    private BukkitTask idleTask;
    private BukkitTask cleanupTask;

    public DisplayRenderService(
            HexAboveNamePlugin plugin,
            HexAboveNameConfig config,
            DisplayTextCacheService cacheService
    ) {
        this.plugin = plugin;
        this.config = config;
        this.titleConfig = config.titleSystem();
        this.cacheService = cacheService;
        this.movementThresholdSquared = titleConfig.movementThreshold() * titleConfig.movementThreshold();
    }

    public void start() {
        stopTasks();
        removeAllDisplays();
        if (!titleConfig.enabled()) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshForPlayer(player, false);
        }

        if (titleConfig.updateMode() == HexAboveNameConfig.UpdateMode.ALWAYS) {
            movingTask = Bukkit.getScheduler().runTaskTimer(
                    plugin,
                    this::tickAlways,
                    1L,
                    titleConfig.movingUpdateIntervalTicks()
            );
            scheduleCleanupTask();
            return;
        }

        movingTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::tickMovingPlayers,
                1L,
                titleConfig.movingUpdateIntervalTicks()
        );
        idleTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::tickIdlePlayers,
                1L,
                titleConfig.idleCheckIntervalTicks()
        );
        scheduleCleanupTask();
    }

    public void stop() {
        stopTasks();
        removeAllDisplays();
    }

    public void handleJoin(Player player) {
        if (!titleConfig.enabled()) {
            return;
        }
        refreshForPlayer(player, true);
    }

    public void handleRespawn(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            removeDisplayFor(player.getUniqueId());
            refreshForPlayer(player, true);
        });
    }

    public void handleWorldChange(Player player) {
        removeDisplayFor(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> refreshForPlayer(player, true));
    }

    public void removeDisplayFor(UUID uuid) {
        TrackedDisplay tracked = trackedDisplays.remove(uuid);
        if (tracked != null) {
            TextDisplay display = tracked.display;
            if (display.isValid()) {
                display.remove();
            }
        }
        movingPlayers.remove(uuid);
        rawTextCache.remove(uuid);
        componentTextCache.remove(uuid);
    }

    private void tickAlways() {
        if (!titleConfig.enabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayer(player, true, true);
        }
    }

    private void tickMovingPlayers() {
        if (!titleConfig.enabled()) {
            return;
        }
        if (movingPlayers.isEmpty()) {
            return;
        }

        for (UUID uuid : Set.copyOf(movingPlayers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                removeDisplayFor(uuid);
                continue;
            }

            boolean keepMoving = updatePlayer(player, true, false);
            if (!keepMoving) {
                movingPlayers.remove(uuid);
            }
        }
    }

    private void tickIdlePlayers() {
        if (!titleConfig.enabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (movingPlayers.contains(uuid)) {
                continue;
            }
            boolean startedMoving = updatePlayer(player, false, false);
            if (startedMoving) {
                movingPlayers.add(uuid);
            }
        }
    }

    private void refreshForPlayer(Player player, boolean preferMovingUpdates) {
        if (player == null || !player.isOnline()) {
            return;
        }
        boolean moving = updatePlayer(player, preferMovingUpdates, true);
        if (moving || preferMovingUpdates) {
            movingPlayers.add(player.getUniqueId());
            return;
        }
        movingPlayers.remove(player.getUniqueId());
    }

    private boolean updatePlayer(Player player, boolean movingPhase, boolean forceTeleport) {
        UUID uuid = player.getUniqueId();
        if (!isVisibleForPlayer(player)) {
            removeDisplayFor(uuid);
            return false;
        }

        String rawText = resolveRawText(uuid);
        if (rawText == null) {
            removeDisplayFor(uuid);
            return false;
        }
        Component text = resolveText(uuid, rawText);

        Location playerLocation = player.getLocation();
        TrackedDisplay tracked = trackedDisplays.get(uuid);
        if (tracked == null || !isDisplayValid(tracked.display) || !sameWorld(tracked.display.getWorld(), player.getWorld())) {
            if (tracked != null && tracked.display.isValid()) {
                tracked.display.remove();
            }
            TextDisplay display = spawnDisplay(player, text);
            if (display == null) {
                removeDisplayFor(uuid);
                return false;
            }
            tracked = new TrackedDisplay(display, playerLocation.clone(), rawText);
            trackedDisplays.put(uuid, tracked);
        }

        if (!rawText.equals(tracked.lastRawText)) {
            tracked.display.text(text);
            tracked.lastRawText = rawText;
        }

        boolean moved = hasMoved(tracked.lastPlayerLocation, playerLocation);
        if (forceTeleport || moved) {
            Location target = targetLocation(playerLocation);
            if (shouldTeleport(tracked.display, target) || forceTeleport) {
                tracked.display.teleport(target);
            }
            tracked.lastPlayerLocation = playerLocation.clone();
            tracked.idleChecksWithoutMovement = 0;
            return movingPhase || moved;
        }

        if (movingPhase) {
            tracked.idleChecksWithoutMovement++;
            return tracked.idleChecksWithoutMovement < 2;
        }
        return false;
    }

    private TextDisplay spawnDisplay(Player player, Component text) {
        Location spawn = targetLocation(player.getLocation());
        World world = spawn.getWorld();
        if (world == null) {
            return null;
        }

        TextDisplay display = world.spawn(spawn, TextDisplay.class, entity -> {
            entity.text(text);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setGravity(false);
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setShadowed(titleConfig.shadowed());
            entity.setSeeThrough(false);
            entity.setDefaultBackground(false);
            entity.setVisibleByDefault(true);
            entity.setInterpolationDelay(0);
            entity.setTeleportDuration(titleConfig.teleportDurationTicks());
            entity.setInterpolationDuration(titleConfig.interpolationDurationTicks());
        });

        applyOwnerVisibility(player, display);
        return display;
    }

    private void applyOwnerVisibility(Player player, TextDisplay display) {
        if (titleConfig.showToSelf()) {
            player.showEntity(plugin, display);
            return;
        }
        player.hideEntity(plugin, display);
    }

    private boolean isVisibleForPlayer(Player player) {
        if (!player.isOnline() || player.isDead()) {
            return false;
        }
        return config.isWorldAllowed(player.getWorld().getName());
    }

    private String resolveRawText(UUID uuid) {
        String cached = cacheService.getText(uuid);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        if (titleConfig.defaultTitle().isBlank()) {
            return null;
        }
        return titleConfig.defaultTitle();
    }

    private Component resolveText(UUID uuid, String rawText) {
        String cachedRaw = rawTextCache.get(uuid);
        if (rawText.equals(cachedRaw)) {
            Component cachedComponent = componentTextCache.get(uuid);
            if (cachedComponent != null) {
                return cachedComponent;
            }
        }

        Component colored = LEGACY_SERIALIZER.deserialize(rawText);
        rawTextCache.put(uuid, rawText);
        componentTextCache.put(uuid, colored);
        return colored;
    }

    private Location targetLocation(Location playerLocation) {
        return playerLocation.clone().add(0.0D, titleConfig.yOffset(), 0.0D);
    }

    private boolean hasMoved(Location previous, Location current) {
        if (previous == null || current == null) {
            return true;
        }
        if (!sameWorld(previous.getWorld(), current.getWorld())) {
            return true;
        }
        return previous.distanceSquared(current) > movementThresholdSquared;
    }

    private boolean shouldTeleport(TextDisplay display, Location target) {
        Location current = display.getLocation();
        if (!sameWorld(current.getWorld(), target.getWorld())) {
            return true;
        }
        return current.distanceSquared(target) > movementThresholdSquared;
    }

    private void cleanupOfflinePlayers() {
        for (UUID uuid : Set.copyOf(trackedDisplays.keySet())) {
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || !online.isOnline()) {
                removeDisplayFor(uuid);
            }
        }
    }

    private void removeAllDisplays() {
        for (TrackedDisplay tracked : trackedDisplays.values()) {
            if (tracked.display != null && tracked.display.isValid()) {
                tracked.display.remove();
            }
        }
        trackedDisplays.clear();
        movingPlayers.clear();
        rawTextCache.clear();
        componentTextCache.clear();
    }

    private void stopTasks() {
        if (movingTask != null) {
            movingTask.cancel();
            movingTask = null;
        }
        if (idleTask != null) {
            idleTask.cancel();
            idleTask = null;
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    private void scheduleCleanupTask() {
        long cleanupIntervalTicks = Math.max(40L, titleConfig.idleCheckIntervalTicks());
        cleanupTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::cleanupOfflinePlayers,
                cleanupIntervalTicks,
                cleanupIntervalTicks
        );
    }

    private boolean sameWorld(World a, World b) {
        if (a == null || b == null) {
            return false;
        }
        return a.getUID().equals(b.getUID());
    }

    private boolean isDisplayValid(TextDisplay display) {
        return display != null && display.isValid() && !display.isDead();
    }

    private static final class TrackedDisplay {
        private final TextDisplay display;
        private Location lastPlayerLocation;
        private String lastRawText;
        private int idleChecksWithoutMovement;

        private TrackedDisplay(TextDisplay display, Location lastPlayerLocation, String lastRawText) {
            this.display = display;
            this.lastPlayerLocation = lastPlayerLocation;
            this.lastRawText = lastRawText;
            this.idleChecksWithoutMovement = 0;
        }
    }
}
