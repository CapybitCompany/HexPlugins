package hex.parkour.model;

import org.bukkit.Location;

public final class CuboidRegion {
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public CuboidRegion(BlockVector pos1, BlockVector pos2) {
        this.minX = Math.min(pos1.x(), pos2.x());
        this.minY = Math.min(pos1.y(), pos2.y());
        this.minZ = Math.min(pos1.z(), pos2.z());
        this.maxX = Math.max(pos1.x(), pos2.x());
        this.maxY = Math.max(pos1.y(), pos2.y());
        this.maxZ = Math.max(pos1.z(), pos2.z());
    }

    public boolean contains(Location location) {
        if (location == null) return false;
        return containsBlock(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public boolean containsStandingLocation(Location location) {
        if (location == null) return false;
        return containsStandingBlock(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public boolean containsStandingBlock(int x, int y, int z) {
        return containsBlockBelow(x, y, z, 1);
    }

    public boolean containsBlockBelow(Location location, int blocksDown) {
        if (location == null) return false;
        return containsBlockBelow(location.getBlockX(), location.getBlockY(), location.getBlockZ(), blocksDown);
    }

    public boolean containsBlockBelow(int x, int y, int z, int blocksDown) {
        for (int offset = 0; offset <= Math.max(0, blocksDown); offset++) {
            if (containsBlock(x, y - offset, z)) return true;
        }
        return false;
    }

    public boolean containsBlockNear(Location location, int blocksUp, int blocksDown) {
        if (location == null) return false;
        return containsBlockNear(location.getBlockX(), location.getBlockY(), location.getBlockZ(), blocksUp, blocksDown);
    }

    public boolean containsBlockNear(int x, int y, int z, int blocksUp, int blocksDown) {
        for (int offset = -Math.max(0, blocksDown); offset <= Math.max(0, blocksUp); offset++) {
            if (containsBlock(x, y + offset, z)) return true;
        }
        return false;
    }

    private boolean containsBlock(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public double centerX() {
        return (minX + maxX + 1) / 2.0;
    }

    public double centerY() {
        return (minY + maxY + 1) / 2.0;
    }

    public double centerZ() {
        return (minZ + maxZ + 1) / 2.0;
    }

    public double safeRespawnY() {
        return maxY + 1.0;
    }

    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }
}
