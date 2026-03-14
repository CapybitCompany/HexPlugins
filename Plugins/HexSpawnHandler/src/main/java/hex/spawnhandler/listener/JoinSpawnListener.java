package hex.spawnhandler.listener;

import hex.spawnhandler.model.SpawnSettings;
import hex.spawnhandler.service.SpawnSettingsService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

public final class JoinSpawnListener implements Listener {

    private final Plugin plugin;
    private final SpawnSettingsService spawnSettingsService;

    public JoinSpawnListener(Plugin plugin, SpawnSettingsService spawnSettingsService) {
        this.plugin = plugin;
        this.spawnSettingsService = spawnSettingsService;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location targetLocation = spawnSettingsService.resolveSpawnLocation(player);
        if (targetLocation == null) {
            return;
        }

        SpawnSettings settings = spawnSettingsService.getSettings();
        if (settings.teleportDelayTicks() <= 0) {
            player.teleport(targetLocation);
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                player.teleport(targetLocation);
            }
        }, settings.teleportDelayTicks());
    }
}
