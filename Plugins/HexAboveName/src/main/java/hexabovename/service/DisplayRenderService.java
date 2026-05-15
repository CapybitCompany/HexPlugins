package hexabovename.service;

import hexabovename.HexAboveNamePlugin;
import hexabovename.config.HexAboveNameConfig;
import hexabovename.util.LegacyTextUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DisplayRenderService {

    private final HexAboveNamePlugin plugin;
    private final HexAboveNameConfig config;
    private final DisplayTextCacheService cacheService;
    private final Map<UUID, TextDisplay> displays = new ConcurrentHashMap<>();
    private BukkitTask task;

    public DisplayRenderService(
            HexAboveNamePlugin plugin,
            HexAboveNameConfig config,
            DisplayTextCacheService cacheService
    ) {
        this.plugin = plugin;
        this.config = config;
        this.cacheService = cacheService;
    }

    public void start() {
        stopTask();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, config.render().updateIntervalTicks());
    }

    public void stop() {
        stopTask();
        removeAllDisplays();
    }

    public void removeDisplayFor(UUID uuid) {
        TextDisplay display = displays.remove(uuid);
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    private void tick() {
        Set<UUID> online = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            online.add(uuid);

            String rawText = cacheService.getText(uuid);
            if (rawText == null || rawText.isBlank() || !isVisibleForPlayer(player)) {
                removeDisplayFor(uuid);
                continue;
            }

            updateOrCreate(player, LegacyTextUtil.colorize(rawText));
        }

        List<UUID> stale = new ArrayList<>();
        for (UUID uuid : displays.keySet()) {
            if (!online.contains(uuid)) {
                stale.add(uuid);
            }
        }
        for (UUID uuid : stale) {
            removeDisplayFor(uuid);
        }
    }

    private void updateOrCreate(Player player, String text) {
        UUID uuid = player.getUniqueId();
        TextDisplay display = displays.get(uuid);

        if (display == null || !display.isValid() || display.isDead()) {
            display = spawnDisplay(player, text);
            if (display == null) {
                return;
            }
            displays.put(uuid, display);
        }

        Location targetLocation = targetLocation(player);
        if (!sameWorld(display.getWorld(), targetLocation.getWorld())) {
            display.remove();
            TextDisplay recreated = spawnDisplay(player, text);
            if (recreated == null) {
                displays.remove(uuid);
                return;
            }
            displays.put(uuid, recreated);
            display = recreated;
        }

        display.teleport(targetLocation);
        if (!text.equals(display.getText())) {
            display.setText(text);
        }
        applyOwnerVisibility(player, display);
    }

    private TextDisplay spawnDisplay(Player player, String text) {
        Location spawn = targetLocation(player);
        World world = spawn.getWorld();
        if (world == null) {
            return null;
        }
        return world.spawn(spawn, TextDisplay.class, entity -> {
            entity.setText(text);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setGravity(false);
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setShadowed(false);
            entity.setSeeThrough(false);
            entity.setDefaultBackground(false);
        });
    }

    private void applyOwnerVisibility(Player player, TextDisplay display) {
        player.showEntity(plugin, display);
        if (!config.render().showToSelf()) {
            player.hideEntity(plugin, display);
        }
    }

    private boolean isVisibleForPlayer(Player player) {
        if (!player.isOnline() || player.isDead()) {
            return false;
        }
        return config.isWorldAllowed(player.getWorld().getName());
    }

    private Location targetLocation(Player player) {
        return player.getLocation().clone().add(0.0D, config.render().yOffset(), 0.0D);
    }

    private void removeAllDisplays() {
        for (TextDisplay display : displays.values()) {
            if (display != null && display.isValid()) {
                display.remove();
            }
        }
        displays.clear();
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private boolean sameWorld(World a, World b) {
        if (a == null || b == null) {
            return false;
        }
        return a.getUID().equals(b.getUID());
    }
}
