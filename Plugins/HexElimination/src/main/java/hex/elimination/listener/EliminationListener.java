package hex.elimination.listener;

import hex.core.api.ui.UiTokens;
import hex.elimination.HexEliminationPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public class EliminationListener implements Listener {

    private final HexEliminationPlugin plugin;

    public EliminationListener(HexEliminationPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        plugin.getEliminationService().eliminate(player);

        if (plugin.getConfig().getBoolean("settings.strike-lightning-on-death", true)) {
            Location location = player.getLocation();
            location.getWorld().strikeLightningEffect(location);
        }

        plugin.ui().broadcast("elimination.kill.announce",
                UiTokens.of("victim", player.getName()));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> plugin.getEliminationService().applyRespawnRule(player));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> plugin.getEliminationService().applyRespawnRule(player));
    }
}
