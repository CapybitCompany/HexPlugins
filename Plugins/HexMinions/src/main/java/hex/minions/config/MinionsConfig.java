package hex.minions.config;

import org.bukkit.configuration.file.FileConfiguration;

public record MinionsConfig(
        boolean enabled,
        int defaultTownLimit,
        int hardCap,
        String limitMetaKey,
        int minDistanceBetweenMinions,
        boolean relocationEnabled,
        boolean relocationRequireSameTown,
        boolean relocationUsePlayerYaw,
        int engineIntervalTicks,
        int maxActionsPerCycle,
        int dirtyFlushIntervalTicks,
        int dirtyFlushMaxRows,
        boolean requireLoadedChunk,
        boolean offlineEnabled,
        int offlineMaxHours,
        int offlineMaxActionsPerMinion,
        int labelRefreshTicks,
        int selectedContextTtlSeconds,
        boolean wikiTestMode
) {
    public static MinionsConfig load(FileConfiguration config) {
        return new MinionsConfig(
                config.getBoolean("minions.enabled", true),
                config.getInt("minions.limits.default-town-limit", 5),
                config.getInt("minions.limits.hard-cap", 30),
                config.getString("minions.limits.meta-key", "minions.limit"),
                config.getInt("minions.placement.min-distance-between-minions", 2),
                config.getBoolean("minions.relocation.enabled", true),
                config.getBoolean("minions.relocation.require-same-town", true),
                config.getBoolean("minions.relocation.use-player-yaw", true),
                Math.max(1, config.getInt("minions.engine.tick-interval-ticks", 20)),
                Math.max(1, config.getInt("minions.engine.max-actions-per-cycle", 1000)),
                Math.max(20, config.getInt("minions.engine.dirty-flush-interval-ticks", 100)),
                Math.max(25, config.getInt("minions.engine.dirty-flush-max-rows", 500)),
                config.getBoolean("minions.engine.require-loaded-chunk", false),
                config.getBoolean("minions.engine.offline.enabled", true),
                Math.max(0, config.getInt("minions.engine.offline.max-hours", 24)),
                Math.max(0, config.getInt("minions.engine.offline.max-actions-per-minion", 10000)),
                Math.max(20, config.getInt("minions.rendering.label-refresh-ticks", 40)),
                Math.max(10, config.getInt("minions.deluxemenus.selected-context.ttl-seconds", 120)),
                config.getBoolean("minions.testing.wiki-copy-items", false)
        );
    }
}

