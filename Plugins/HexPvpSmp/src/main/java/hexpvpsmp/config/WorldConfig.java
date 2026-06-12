package hexpvpsmp.config;

import java.util.Locale;
import java.util.Objects;

public record WorldConfig(
        String world,
        boolean enabled,
        SpawnConfig spawn
) {
    public WorldConfig {
        world = Objects.requireNonNull(world, "world").trim().toLowerCase(Locale.ROOT);
        if (world.isEmpty()) {
            throw new IllegalArgumentException("world is blank");
        }
        spawn = spawn == null ? SpawnConfig.disabled() : spawn;
    }
}
