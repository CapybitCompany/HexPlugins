package hexpvpsmp.region;

import java.util.Locale;
import java.util.Objects;

/**
 * A single block coordinate in a world that is whitelisted for player use
 * inside otherwise-protected spawn. Players may open/use it; it is still
 * protected from being broken or destroyed.
 *
 * <p>Coordinates are block coordinates (integers). Double chests should be
 * listed as two entries (one per half).
 */
public record PublicChest(
        String world,
        int x,
        int y,
        int z
) {
    public PublicChest {
        world = Objects.requireNonNull(world, "world").trim().toLowerCase(Locale.ROOT);
        if (world.isEmpty()) {
            throw new IllegalArgumentException("public-chest world is blank");
        }
    }

    public boolean matches(String worldName, int bx, int by, int bz) {
        return worldName != null
                && world.equals(worldName.toLowerCase(Locale.ROOT))
                && x == bx && y == by && z == bz;
    }
}
