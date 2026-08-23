package hexleszek.listener;

import hexleszek.HexLeszekPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class LeszekPlayerListener implements Listener {

    private final HexLeszekPlugin plugin;

    public LeszekPlayerListener(HexLeszekPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.updateTrackedPlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.updateTrackedPlayer(event.getPlayer());
        plugin.storage().save();
    }
}
