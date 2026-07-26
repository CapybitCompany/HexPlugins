package hexcontrolregion;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class HexControlRegionPlugin extends JavaPlugin implements Listener {

    private String worldName;
    private double triggerY;
    private long cooldownMs;
    private boolean bypassOperators;
    private boolean spawnCommandEnabled;
    private String spawnCommand;
    private boolean fallbackEnabled;
    private String fallbackWorldName;
    private double fallbackX;
    private double fallbackY;
    private double fallbackZ;
    private float fallbackYaw;
    private float fallbackPitch;

    private final Map<UUID, Long> lastTeleportAttempt = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPluginConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("HexControlRegion enabled.");
    }

    @Override
    public void onDisable() {
        lastTeleportAttempt.clear();
        getLogger().info("HexControlRegion disabled.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || !worldName.equals(to.getWorld().getName())) {
            return;
        }
        if (to.getY() >= triggerY) {
            return;
        }

        Player player = event.getPlayer();
        if (shouldBypass(player) || isOnCooldown(player)) {
            return;
        }

        markCooldown(player);
        Location triggerLocation = player.getLocation().clone();
        if (dispatchSpawnCommand(player)) {
            getServer().getScheduler().runTaskLater(this, () -> teleportFallbackIfNotMoved(player, triggerLocation), 2L);
        } else {
            teleportFallback(player);
        }
    }

    private boolean shouldBypass(Player player) {
        return bypassOperators && player.isOp();
    }

    private boolean isOnCooldown(Player player) {
        long previous = lastTeleportAttempt.getOrDefault(player.getUniqueId(), 0L);
        return System.currentTimeMillis() - previous < cooldownMs;
    }

    private void markCooldown(Player player) {
        lastTeleportAttempt.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private boolean dispatchSpawnCommand(Player player) {
        if (!spawnCommandEnabled || spawnCommand.isBlank()) {
            return false;
        }

        String command = spawnCommand.replace("{player}", player.getName());
        return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private void teleportFallbackIfNotMoved(Player player, Location triggerLocation) {
        if (!player.isOnline()) {
            return;
        }

        Location location = player.getLocation();
        if (!triggerLocation.getWorld().equals(location.getWorld())) {
            return;
        }
        if (location.getY() >= triggerY || location.distanceSquared(triggerLocation) > 0.25D) {
            return;
        }

        teleportFallback(player);
    }

    private void teleportFallback(Player player) {
        if (!fallbackEnabled) {
            return;
        }

        World world = Bukkit.getWorld(fallbackWorldName);
        if (world == null) {
            getLogger().warning("Fallback world is not loaded: " + fallbackWorldName);
            return;
        }

        Location location = new Location(world, fallbackX, fallbackY, fallbackZ, fallbackYaw, fallbackPitch);
        player.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    private void loadPluginConfig() {
        reloadConfig();
        this.worldName = getConfig().getString("world", "world");
        this.triggerY = getConfig().getDouble("trigger-y", 108.0);
        this.cooldownMs = Math.max(0L, getConfig().getLong("cooldown-ms", 2000L));
        this.bypassOperators = getConfig().getBoolean("bypass-operators", true);
        this.spawnCommandEnabled = getConfig().getBoolean("spawn-command.enabled", true);
        this.spawnCommand = getConfig().getString("spawn-command.command", "spawn {player}");
        this.fallbackEnabled = getConfig().getBoolean("fallback-location.enabled", true);
        this.fallbackWorldName = getConfig().getString("fallback-location.world", worldName);
        this.fallbackX = getConfig().getDouble("fallback-location.x", 496.5);
        this.fallbackY = getConfig().getDouble("fallback-location.y", 148.0);
        this.fallbackZ = getConfig().getDouble("fallback-location.z", 898.5);
        this.fallbackYaw = (float) getConfig().getDouble("fallback-location.yaw", 0.0);
        this.fallbackPitch = (float) getConfig().getDouble("fallback-location.pitch", 0.0);
    }
}
