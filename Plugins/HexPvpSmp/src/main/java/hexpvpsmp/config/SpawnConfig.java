package hexpvpsmp.config;

import hexpvpsmp.region.Cuboid;

public record SpawnConfig(
        boolean enabled,
        Cuboid region,
        RedLineConfig redLine
) {
    public SpawnConfig {
        if (enabled && region == null) {
            throw new IllegalArgumentException("SpawnConfig.region is required when enabled");
        }
        if (redLine == null) {
            redLine = RedLineConfig.disabled();
        }
    }

    public static SpawnConfig disabled() {
        return new SpawnConfig(false, null, RedLineConfig.disabled());
    }
}
