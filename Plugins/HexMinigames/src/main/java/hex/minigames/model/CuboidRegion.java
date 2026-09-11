package hex.minigames.model;

import org.bukkit.Location;

public final class CuboidRegion {
    private final String worldName;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public CuboidRegion(String worldName, BlockPosition pos1, BlockPosition pos2) {
        this.worldName = worldName;
        this.minX = Math.min(pos1.x(), pos2.x());
        this.minY = Math.min(pos1.y(), pos2.y());
        this.minZ = Math.min(pos1.z(), pos2.z());
        this.maxX = Math.max(pos1.x(), pos2.x());
        this.maxY = Math.max(pos1.y(), pos2.y());
        this.maxZ = Math.max(pos1.z(), pos2.z());
    }

    public boolean contains(Location location) {
        if (location == null || location.getWorld() == null) return false;
        if (!location.getWorld().getName().equals(worldName)) return false;
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean contains(BlockPosition position) {
        if (position == null) return false;
        return position.x() >= minX && position.x() <= maxX
                && position.y() >= minY && position.y() <= maxY
                && position.z() >= minZ && position.z() <= maxZ;
    }

    public boolean contains(CuboidRegion region) {
        if (region == null || !worldName.equals(region.worldName())) return false;
        return contains(new BlockPosition(region.minX(), region.minY(), region.minZ()))
                && contains(new BlockPosition(region.maxX(), region.maxY(), region.maxZ()));
    }

    public String worldName() {
        return worldName;
    }

    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }
}
