package hexpvpsmp.combat;

import hexpvpsmp.HexPvpSmpPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Clear the tag before another plugin restores a quitter's inventory and original world. */
public final class ExcludedWorldListener implements Listener {
    private final HexPvpSmpPlugin plugin;
    public ExcludedWorldListener(HexPvpSmpPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) { clear(event.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) { clear(event.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (plugin.config() != null && plugin.config().excludesWorld(event.getFrom().getName())) {
            plugin.combatTagService().untag(event.getPlayer().getUniqueId());
            plugin.messageService().clearCooldowns(event.getPlayer().getUniqueId());
        }
        clear(event.getPlayer());
    }

    private void clear(Player player) {
        if (plugin.config() != null && plugin.config().excludes(player.getLocation())) {
            plugin.combatTagService().untag(player.getUniqueId());
            plugin.messageService().clearCooldowns(player.getUniqueId());
        }
    }
}
