package hexpvpsmp.config;

import hexpvpsmp.region.ProtectedRegion;
import hexpvpsmp.region.PublicChest;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record WorldConfig(
        String world,
        boolean enabled,
        SpawnConfig spawn,
        List<ProtectedRegion> noBuildZones,
        List<PublicChest> publicChests
) {
    public WorldConfig {
        world = Objects.requireNonNull(world, "world").trim().toLowerCase(Locale.ROOT);
        if (world.isEmpty()) {
            throw new IllegalArgumentException("world is blank");
        }
        spawn = spawn == null ? SpawnConfig.disabled() : spawn;
        noBuildZones = noBuildZones == null ? List.of() : List.copyOf(noBuildZones);
        publicChests = publicChests == null ? List.of() : List.copyOf(publicChests);
    }
}
