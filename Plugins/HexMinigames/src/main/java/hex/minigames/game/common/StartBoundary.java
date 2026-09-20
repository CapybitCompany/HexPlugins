package hex.minigames.game.common;

import org.bukkit.Location;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

/** Pushes players back across a start line without cancelling gravity or vertical movement. */
public final class StartBoundary {
    private StartBoundary() { }

    public static void pushBack(PlayerMoveEvent event, double boundaryZ, int forwardSign) {
        Location to = event.getTo();
        if (to == null || (to.getZ() - boundaryZ) * forwardSign <= 0) return;
        Location safe = to.clone();
        safe.setZ(boundaryZ - forwardSign * 0.4);
        event.setTo(safe);
        Vector velocity = event.getPlayer().getVelocity();
        event.getPlayer().setVelocity(new Vector(velocity.getX(), Math.min(0, velocity.getY()), -forwardSign * 0.75));
    }

    /** Keeps a player within the plot horizontally while preserving falling and jumping. */
    public static void pushInside(PlayerMoveEvent event, hex.minigames.model.CuboidRegion region) {
        Location to = event.getTo();
        if (to == null || to.getWorld() == null || !region.worldName().equals(to.getWorld().getName())) return;
        Location safe = to.clone();
        Vector velocity = event.getPlayer().getVelocity();
        boolean pushed = false;
        if (to.getX() < region.minX() + 0.1) {
            safe.setX(region.minX() + 0.3); velocity.setX(0.4); pushed = true;
        } else if (to.getX() > region.maxX() + 0.9) {
            safe.setX(region.maxX() + 0.7); velocity.setX(-0.4); pushed = true;
        }
        if (to.getZ() < region.minZ() + 0.1) {
            safe.setZ(region.minZ() + 0.3); velocity.setZ(0.4); pushed = true;
        } else if (to.getZ() > region.maxZ() + 0.9) {
            safe.setZ(region.maxZ() + 0.7); velocity.setZ(-0.4); pushed = true;
        }
        if (pushed) {
            velocity.setY(Math.min(0, velocity.getY()));
            event.setTo(safe);
            event.getPlayer().setVelocity(velocity);
        }
    }
}
