package hexposterunki.config;

import java.util.Objects;

/**
 * Axis-aligned protection and event region of one outpost. Deliberately Bukkit-free so region
 * containment, chunk math and validation can be unit-tested without a server.
 */
public record Cuboid(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public Cuboid {
        Objects.requireNonNull(world, "world");
    }

    public static Cuboid of(String world, BlockVec a, BlockVec b) {
        return new Cuboid(world,
                Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
                Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
    }

    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean contains(String worldName, double x, double y, double z) {
        return contains(worldName, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public boolean contains(BlockVec vec) {
        return contains(world, vec.x(), vec.y(), vec.z());
    }

    public boolean contains(PointDef point) {
        return contains(world, point.blockX(), point.blockY(), point.blockZ());
    }

    /** Volume in blocks; used to cap the block-state snapshot scan. */
    public long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    public int chunkMinX() {
        return minX >> 4;
    }

    public int chunkMaxX() {
        return maxX >> 4;
    }

    public int chunkMinZ() {
        return minZ >> 4;
    }

    public int chunkMaxZ() {
        return maxZ >> 4;
    }

    public int chunkCount() {
        return (chunkMaxX() - chunkMinX() + 1) * (chunkMaxZ() - chunkMinZ() + 1);
    }

    public double centerX() {
        return (minX + maxX) / 2.0 + 0.5;
    }

    public double centerY() {
        return (minY + maxY) / 2.0;
    }

    public double centerZ() {
        return (minZ + maxZ) / 2.0 + 0.5;
    }

    /** Squared horizontal distance from a point to the closest region column, 0 inside. */
    public double horizontalDistanceSquared(double x, double z) {
        double dx = x < minX ? minX - x : (x > maxX + 1 ? x - (maxX + 1) : 0.0);
        double dz = z < minZ ? minZ - z : (z > maxZ + 1 ? z - (maxZ + 1) : 0.0);
        return dx * dx + dz * dz;
    }
}
