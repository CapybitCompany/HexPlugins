package hexbuildbattle.arena;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

public record CuboidRegion(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public CuboidRegion {
        int realMinX = Math.min(minX, maxX);
        int realMaxX = Math.max(minX, maxX);
        int realMinY = Math.min(minY, maxY);
        int realMaxY = Math.max(minY, maxY);
        int realMinZ = Math.min(minZ, maxZ);
        int realMaxZ = Math.max(minZ, maxZ);
        minX = realMinX;
        maxX = realMaxX;
        minY = realMinY;
        maxY = realMaxY;
        minZ = realMinZ;
        maxZ = realMaxZ;
    }

    public boolean contains(Location location) {
        if (location == null || location.getWorld() == null || !location.getWorld().equals(world)) {
            return false;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return contains(x, y, z);
    }

    public boolean contains(Block block) {
        return block != null
                && block.getWorld().equals(world)
                && contains(block.getX(), block.getY(), block.getZ());
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public int volume() {
        return (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    public CuboidRegion withY(int y) {
        return new CuboidRegion(world, minX, y, minZ, maxX, y, maxZ);
    }

    public Location clamp(Location location) {
        if (location == null) {
            return new Location(world, minX + 0.5D, minY + 0.5D, minZ + 0.5D);
        }
        return new Location(
                world,
                clamp(location.getX(), minX + 0.001D, maxX + 0.999D),
                clamp(location.getY(), minY + 0.001D, maxY + 0.999D),
                clamp(location.getZ(), minZ + 0.001D, maxZ + 0.999D),
                location.getYaw(),
                location.getPitch()
        );
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
