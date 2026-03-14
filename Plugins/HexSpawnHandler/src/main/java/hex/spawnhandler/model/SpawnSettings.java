package hex.spawnhandler.model;

public record SpawnSettings(
        boolean enabled,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        int teleportDelayTicks,
        boolean finalTeleportEnabled,
        int finalTeleportDelayTicks
) {
}
