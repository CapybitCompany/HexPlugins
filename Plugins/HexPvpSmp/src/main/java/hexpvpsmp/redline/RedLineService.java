package hexpvpsmp.redline;

import hexpvpsmp.HexPvpSmpPlugin;
import hexpvpsmp.config.HexPvpConfig;
import hexpvpsmp.config.RedLineConfig;
import hexpvpsmp.config.SpawnConfig;
import hexpvpsmp.config.WorldConfig;
import hexpvpsmp.region.Cuboid;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Objects;
import java.util.Optional;

/**
 * Coordinate-based red line: if the player is inside the spawn cuboid AND
 * their horizontal distance to the edge is &le; warning-distance, send an
 * actionbar warning. No WorldGuard required.
 */
public final class RedLineService implements Listener {

    private final HexPvpSmpPlugin plugin;

    public RedLineService(HexPvpSmpPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || sameBlock(from, to)) {
            return;
        }
        check(event.getPlayer(), to);
    }

    public void check(Player player, Location at) {
        HexPvpConfig config = plugin.config();
        if (config == null || !config.enabled() || at == null || at.getWorld() == null) {
            return;
        }
        Optional<WorldConfig> worldConfig = config.world(at.getWorld().getName());
        if (worldConfig.isEmpty() || !worldConfig.get().enabled()) {
            return;
        }
        SpawnConfig spawn = worldConfig.get().spawn();
        if (!spawn.enabled() || spawn.region() == null) {
            return;
        }
        RedLineConfig redLine = spawn.redLine();
        if (!redLine.enabled() || redLine.warningDistance() <= 0.0D) {
            return;
        }
        Cuboid c = spawn.region();
        if (!c.containsHorizontal(at.getX(), at.getZ())) {
            return; // already outside spawn; let the safezone listener handle entry blocks
        }
        double distance = c.horizontalDistanceToEdge(at.getX(), at.getZ());
        if (distance <= redLine.warningDistance()) {
            plugin.messageService().sendActionBar(player, redLine.message());
        }
    }

    private boolean sameBlock(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) {
            return false;
        }
        if (!Objects.equals(a.getWorld().getUID(), b.getWorld().getUID())) {
            return false;
        }
        return a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }
}
