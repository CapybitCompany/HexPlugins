package hexabovename.listener;

import hexabovename.HexAboveNamePlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

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
        if (plugin.renderService() != null) {
            plugin.renderService().handleJoin(event.getPlayer());
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

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (plugin.renderService() != null) {
            plugin.renderService().handleRespawn(event.getPlayer());
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (plugin.renderService() != null) {
            plugin.renderService().handleWorldChange(event.getPlayer());
        }
    }
}
