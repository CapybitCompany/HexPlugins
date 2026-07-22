package hexafkzone.listener;

import hexafkzone.AfkZoneService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Objects;

public final class AfkZoneListener implements Listener {

    private final AfkZoneService service;

    public AfkZoneListener(AfkZoneService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (sameBlock(event.getFrom(), event.getTo())) {
            return;
        }
        service.updatePlayer(event.getPlayer(), event.getTo());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        service.updatePlayer(event.getPlayer(), event.getTo());
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        service.updatePlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        service.remove(player);
    }

    private boolean sameBlock(Location from, Location to) {
        if (from == null || to == null || from.getWorld() == null || to.getWorld() == null) {
            return false;
        }
        return from.getWorld().equals(to.getWorld())
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ();
    }
}
