package hexpvpsmp.region;

import java.util.Locale;
import java.util.Objects;

public record ProtectedRegion(
        RegionId id,
        String world,
        RegionType type,
        Cuboid cuboid
) {
    public ProtectedRegion {
        id = Objects.requireNonNull(id, "id");
        world = Objects.requireNonNull(world, "world").trim().toLowerCase(Locale.ROOT);
        if (world.isEmpty()) {
            throw new IllegalArgumentException("world is blank");
        }
        type = Objects.requireNonNull(type, "type");
        cuboid = Objects.requireNonNull(cuboid, "cuboid");
    }
}
