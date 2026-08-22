package hexnpc.listener;

import hexnpc.HexNpcPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

public final class PlayerLifecycleListener implements Listener {

    private final HexNpcPlugin plugin;

    public PlayerLifecycleListener(HexNpcPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (plugin.renderer() != null) {
            plugin.renderer().showTo(player);
        }
        if (plugin.playerDataService() != null && plugin.playerDataService().available()) {
            plugin.playerDataService().ensureLoaded(player.getUniqueId());
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (plugin.renderer() != null) {
            plugin.renderer().hideFrom(player);
            plugin.renderer().showTo(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (plugin.renderer() != null) {
            plugin.renderer().hideFrom(player);
        }
        if (plugin.dialogueService() != null) {
            plugin.dialogueService().onPlayerQuit(player.getUniqueId());
        }
        if (plugin.proximityService() != null) {
            plugin.proximityService().onPlayerQuit(player.getUniqueId());
        }
        if (plugin.workflowService() != null) plugin.workflowService().cancel(player.getUniqueId(), false);
        if (plugin.playerDataService() != null) plugin.playerDataService().unload(player.getUniqueId());
    }
}
