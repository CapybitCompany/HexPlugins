package hexpvpsmp.region;

/**
 * Immutable axis-aligned cuboid in a single world. Coordinates are inclusive
 * on min and exclusive on max for x/z, inclusive on y bounds — matching how
 * vanilla block bounds work. For "contains" checks against a player's
 * position, we use inclusive comparisons on all axes for simplicity.
 */
public record Cuboid(
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ
) {
    public Cuboid {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException(
                    "Cuboid bounds inverted: (" + minX + "," + minY + "," + minZ
                            + ") .. (" + maxX + "," + maxY + "," + maxZ + ")");
        }
    }

    public boolean contains(double x, double y, double z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /**
     * Minimum horizontal (x/z) distance from (x,z) to the cuboid edge.
     * If the point is inside the cuboid horizontally, returns the inward
     * distance to the closest x/z wall (positive). If outside horizontally,
     * returns the outward distance (positive). Y is ignored for red-line.
     */
    public double horizontalDistanceToEdge(double x, double z) {
        if (containsHorizontal(x, z)) {
            return Math.min(insideDelta(x, minX, maxX), insideDelta(z, minZ, maxZ));
        }
        double dx = outsideDelta(x, minX, maxX);
        double dz = outsideDelta(z, minZ, maxZ);
        return Math.sqrt(dx * dx + dz * dz);
    }

    public boolean containsHorizontal(double x, double z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
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
