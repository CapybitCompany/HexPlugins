package hex.minions.robot;

import org.bukkit.configuration.file.FileConfiguration;

public record RobotConfig(
        boolean enabled,
        int maxPerPlayer,
        int baseStorageSlots,
        int maxStorageSlots,
        int storageUpgradeSlots,
        int blockIntervalTicks,
        int fuelSecondsPerItem,
        int tickBatchSize,
        String robotItemId,
        String stationId,
        String fuelItemId,
        String storageUpgradeItemId,
        boolean protectedTownOnly
) {
    public static RobotConfig load(FileConfiguration config) {
        String root = "robots.miner.";
        // Funkcja jest celowo wyłączona na obecnym etapie, także gdy starszy robots.yml ma enabled: true.
        return new RobotConfig(
                false,
                Math.max(1, config.getInt(root + "max-per-player", 2)),
                Math.max(1, config.getInt(root + "base-storage-slots", 6)),
                Math.max(1, config.getInt(root + "max-storage-slots", 12)),
                Math.max(0, config.getInt(root + "storage-upgrade-slots", 6)),
                Math.max(1, config.getInt(root + "block-interval-ticks", 20)),
                Math.max(1, config.getInt(root + "fuel-seconds-per-item", 60)),
                Math.max(1, config.getInt(root + "tick-batch-size", 50)),
                config.getString(root + "robot-item-id", "miner_robot"),
                config.getString(root + "station-id", "ROBOT_MINER"),
                config.getString(root + "fuel-item-id", "sugar_cube"),
                config.getString(root + "storage-upgrade-item-id", "miner_robot_storage"),
                config.getBoolean(root + "protected-town-only", false)
        );
    }
}
