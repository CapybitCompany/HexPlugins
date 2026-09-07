package hex.parkour.service;

import hex.parkour.model.ParkourPlayerSession;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;

public final class VisibilityService {
    private final Plugin plugin;

    public VisibilityService(Plugin plugin) {
        this.plugin = plugin;
    }

    public void toggle(Player player, ParkourPlayerSession session, String parkourWorld) {
        session.playersHidden(!session.playersHidden());
        apply(player, session, parkourWorld);
    }

    public void apply(Player player, ParkourPlayerSession session, String parkourWorld) {
        if (session.playersHidden()) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.getUniqueId().equals(player.getUniqueId()) && sameWorld(other, parkourWorld)) {
                    player.hidePlayer(plugin, other);
                }
            }
            return;
        }
        showAll(player);
    }

    public void refreshHidden(Collection<ParkourPlayerSession> sessions, String parkourWorld) {
        for (ParkourPlayerSession session : sessions) {
            if (!session.playersHidden()) continue;
            Player player = Bukkit.getPlayer(session.playerId());
            if (player != null && player.isOnline()) apply(player, session, parkourWorld);
        }
    }

    public void showAll(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) player.showPlayer(plugin, other);
    }

    private boolean sameWorld(Player player, String worldName) {
        return player.getWorld().getName().equals(worldName);
    }
}
