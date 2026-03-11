package hex.panel2.model;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;
import java.util.UUID;

public final class Panel {

    private UUID owner;
    private final Location min;
    private final Location max;

    public Panel(UUID owner, Location first, Location second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");

        World world = Objects.requireNonNull(first.getWorld(), "Panel world cannot be null");
        if (!world.equals(second.getWorld())) {
            throw new IllegalArgumentException("Panel corners must be in the same world");
        }

        int minX = Math.min(first.getBlockX(), second.getBlockX());
        int maxX = Math.max(first.getBlockX(), second.getBlockX());
        int minY = Math.min(first.getBlockY(), second.getBlockY());
        int maxY = Math.max(first.getBlockY(), second.getBlockY());
        int minZ = Math.min(first.getBlockZ(), second.getBlockZ());
        int maxZ = Math.max(first.getBlockZ(), second.getBlockZ());

        this.owner = owner;
        this.min = new Location(world, minX, minY, minZ);
        this.max = new Location(world, maxX, maxY, maxZ);
    }

    public synchronized UUID getOwner() {
        return owner;
    }

    public synchronized boolean isOwned() {
        return owner != null;
    }

    public synchronized boolean isOwner(UUID playerId) {
        return owner != null && owner.equals(playerId);
    }

    public synchronized void setOwner(UUID owner) {
        this.owner = owner;
    }

    public boolean contains(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        if (!location.getWorld().equals(min.getWorld())) {
            return false;
        }

        int x = location.getBlockX();
        int z = location.getBlockZ();
        return x >= min.getBlockX() && x <= max.getBlockX()
                && z >= min.getBlockZ() && z <= max.getBlockZ();
    }

    public Location getCenter() {
        World world = Objects.requireNonNull(min.getWorld(), "world");
        double centerX = (min.getBlockX() + max.getBlockX() + 1) / 2.0;
        double centerZ = (min.getBlockZ() + max.getBlockZ() + 1) / 2.0;
        double centerY = min.getBlockY() + 1.0;
        return new Location(world, centerX, centerY, centerZ);
    }

    public Location getMin() {
        return min.clone();
    }

    public Location getMax() {
        return max.clone();
    }
}
