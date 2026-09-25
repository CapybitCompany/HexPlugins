package hexposterunki.engine;

import hexposterunki.config.Cuboid;

import java.util.Objects;

/**
 * Resumable position inside a cuboid.
 *
 * <p>Extracted from {@link RegionScanner} so the traversal itself - "every block exactly once, in a
 * stable order, resumable across ticks, stopping at a volume cap" - can be verified without a
 * server.
 */
public final class RegionCursor {

    private final Cuboid region;
    private int x;
    private int y;
    private int z;
    private long visited;
    private boolean finished;

    public RegionCursor(Cuboid region) {
        this.region = Objects.requireNonNull(region, "region");
        this.x = region.minX();
        this.y = region.minY();
        this.z = region.minZ();
        this.finished = region.volume() <= 0L;
    }

    public boolean finished() {
        return finished;
    }

    public long visited() {
        return visited;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    /**
     * Advances to the next block.
     *
     * @return false once the whole region has been covered
     */
    public boolean advance() {
        if (finished) {
            return false;
        }
        visited++;
        z++;
        if (z > region.maxZ()) {
            z = region.minZ();
            y++;
            if (y > region.maxY()) {
                y = region.minY();
                x++;
                if (x > region.maxX()) {
                    finished = true;
                    return false;
                }
            }
        }
        return true;
    }
}
