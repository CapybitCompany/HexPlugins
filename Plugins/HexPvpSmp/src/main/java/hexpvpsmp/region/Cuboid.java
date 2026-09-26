package hexpvpsmp.region;

import org.bukkit.Location;

/**
 * Immutable horizontal (X/Z) area representing a vertically-unbounded column:
 * the region extends from the bottom of the world to the top. Y is intentionally
 * ignored for every containment check so spawn / no-build protection cannot be
 * bypassed by building above or tunnelling below the configured box.
 *
 * <p>Bounds are inclusive on all four X/Z edges.
 */
public record Cuboid(
        double minX, double minZ,
        double maxX, double maxZ
) {
    public Cuboid {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException(
                    "Cuboid bounds inverted: (" + minX + "," + minZ
                            + ") .. (" + maxX + "," + maxZ + ")");
        }
    }

    /** True if (x,z) is inside the column. Y is not considered. */
    public boolean contains(double x, double z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /** True if the location's (x,z) is inside the column. Y is not considered. */
    public boolean contains(Location location) {
        return location != null && contains(location.getX(), location.getZ());
    }

    /**
     * Minimum horizontal (x/z) distance from (x,z) to the column edge.
     * If the point is inside, returns the inward distance to the closest x/z
     * wall (positive). If outside, returns the outward distance (positive).
     */
    public double horizontalDistanceToEdge(double x, double z) {
        if (contains(x, z)) {
            return Math.min(insideDelta(x, minX, maxX), insideDelta(z, minZ, maxZ));
        }
        double dx = outsideDelta(x, minX, maxX);
        double dz = outsideDelta(z, minZ, maxZ);
        return Math.sqrt(dx * dx + dz * dz);
    }

    public double centerX() {
        return (minX + maxX) * 0.5D;
    }

    public double centerZ() {
        return (minZ + maxZ) * 0.5D;
    }

    /** Distance to the nearest wall when the point is between min and max. */
    private static double insideDelta(double v, double min, double max) {
        return Math.min(v - min, max - v);
    }

    /** Outward distance on a single axis: 0 if the point is between min and max. */
    private static double outsideDelta(double v, double min, double max) {
        if (v < min) {
            return min - v;
        }
        if (v > max) {
            return v - max;
        }
        return 0.0D;
    }
}
