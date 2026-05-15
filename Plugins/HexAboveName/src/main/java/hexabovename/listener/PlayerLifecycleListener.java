package hexabovename.listener;

import hexabovename.HexAboveNamePlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

public final class PlayerLifecycleListener implements Listener {

    private final HexAboveNamePlugin plugin;

    public PlayerLifecycleListener(HexAboveNamePlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.cacheService() != null) {
            plugin.cacheService().requestRefresh();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (plugin.renderService() != null) {
            plugin.renderService().removeDisplayFor(event.getPlayer().getUniqueId());
        }
        if (plugin.cacheService() != null) {
            plugin.cacheService().requestRefresh();
        }
    }
}
