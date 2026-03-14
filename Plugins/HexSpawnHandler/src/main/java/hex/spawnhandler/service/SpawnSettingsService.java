package hex.spawnhandler.service;

import hex.spawnhandler.model.SpawnSettings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class SpawnSettingsService {

    private final Plugin plugin;
    private SpawnSettings settings;

    public SpawnSettingsService(Plugin plugin) {
        this.plugin = plugin;
        this.settings = new SpawnSettings(true, null, 0.0, 64.0, 0.0, 0.0F, 0.0F, 1, true, 60);
    }

    public void loadFromConfig() {
        FileConfiguration config = plugin.getConfig();

        boolean enabled = config.getBoolean("spawn.enabled", true);
        String worldName = normalizeWorld(config.getString("spawn.world", ""));
        double x = config.getDouble("spawn.x", 745.0D);
        double y = config.getDouble("spawn.y", -60.0D);
        double z = config.getDouble("spawn.z", 630.0D);
        float yaw = (float) config.getDouble("spawn.yaw", 0.0D);
        float pitch = (float) config.getDouble("spawn.pitch", 0.0D);
        int delay = Math.max(0, config.getInt("spawn.teleport-delay-ticks", 1));
        boolean finalTeleportEnabled = config.getBoolean("spawn.final-teleport.enabled", true);
        int finalTeleportDelayTicks = Math.max(0, config.getInt("spawn.final-teleport.delay-ticks", 60));

        this.settings = new SpawnSettings(
                enabled,
                worldName,
                x,
                y,
                z,
                yaw,
                pitch,
                delay,
                finalTeleportEnabled,
                finalTeleportDelayTicks
        );

        if (worldName != null && Bukkit.getWorld(worldName) == null) {
            plugin.getLogger().warning(
                    "Configured spawn world '" + worldName + "' was not found. Players will not be teleported until that world exists."
            );
        }
    }

    public SpawnSettings getSettings() {
        return settings;
    }

    public Location resolveSpawnLocation(Player player) {
        SpawnSettings current = settings;
        if (!current.enabled()) {
            return null;
        }

        World world;
        if (current.worldName() != null) {
            world = Bukkit.getWorld(current.worldName());
        } else {
            world = player.getWorld();
        }

        if (world == null) {
            return null;
        }

        return new Location(
                world,
                current.x(),
                current.y(),
                current.z(),
                current.yaw(),
                current.pitch()
        );
    }

    private static String normalizeWorld(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }
}
