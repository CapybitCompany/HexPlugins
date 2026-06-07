package hex.collections.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.Set;

public record CollectionsSettings(
        int flushIntervalSeconds,
        boolean flushOnLevelUp,
        long recentlyBrokenTtlMs,
        int cleanupIntervalTicks,
        boolean blockBreakInTownClaimsEnabled,
        boolean debugDenied
) {
    public static CollectionsSettings load(FileConfiguration config) {
        return new CollectionsSettings(
                Math.max(5, config.getInt("storage.flush_interval_seconds", 45)),
                config.getBoolean("storage.flush_on_level_up", true),
                Math.max(1000L, config.getLong("anti_exploit.recently_broken.ttl_ms", 30_000L)),
                Math.max(200, config.getInt("anti_exploit.recently_broken.cleanup_interval_ticks", 1200)),
                config.getBoolean("anti_exploit.private_town_rules.block_break_collection_enabled", false),
                config.getBoolean("anti_exploit.debug.log_denied_progress", false)
        );
    }
}

