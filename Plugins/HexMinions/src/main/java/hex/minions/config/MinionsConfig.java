package hex.minions.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

public record MinionsConfig(
        boolean enabled,
        int defaultTownLimit,
        int hardCap,
        String limitMetaKey,
        Map<Integer, Integer> distinctTypeProgression,
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
        boolean wikiTestMode,
        boolean auditLogEnabled,
        int auditRetentionDays,
        int auditCleanupIntervalHours
) {
    public static MinionsConfig load(FileConfiguration config) {
        int defaultLimit = Math.max(1, config.getInt("minions.limits.default-town-limit", 5));
        int hardCap = Math.max(defaultLimit, config.getInt("minions.limits.hard-cap", 15));
        return new MinionsConfig(
                config.getBoolean("minions.enabled", true),
                defaultLimit,
                hardCap,
                config.getString("minions.limits.meta-key", "minions.manual-limit-bonus"),
                loadDistinctTypeProgression(config, defaultLimit, hardCap),
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
                config.getBoolean("minions.testing.wiki-copy-items", false),
                config.getBoolean("minions.safety.audit-log", true),
                Math.max(1, config.getInt("minions.safety.audit-retention-days", 14)),
                Math.max(1, config.getInt("minions.safety.audit-cleanup-interval-hours", 24))
        );
    }

    private static Map<Integer, Integer> loadDistinctTypeProgression(FileConfiguration config, int defaultLimit, int hardCap) {
        ConfigurationSection section = config.getConfigurationSection("minions.limits.distinct-type-progression");
        LinkedHashMap<Integer, Integer> result = new LinkedHashMap<>();
        if (section != null) {
            section.getKeys(false).stream()
                    .map(key -> {
                        try { return Integer.parseInt(key); } catch (NumberFormatException ignored) { return -1; }
                    })
                    .filter(key -> key > 0)
                    .sorted()
                    .forEach(key -> result.put(key, Math.max(defaultLimit, Math.min(hardCap, section.getInt(String.valueOf(key), defaultLimit)))));
        }
        if (result.isEmpty()) {
            result.put(3, 6);
            result.put(6, 7);
            result.put(9, 8);
            result.put(12, 9);
            result.put(15, 10);
            result.put(17, 11);
            result.put(19, 12);
            result.put(21, 13);
            result.put(23, 14);
            result.put(25, 15);
        }
        return Map.copyOf(result);
    }
}
