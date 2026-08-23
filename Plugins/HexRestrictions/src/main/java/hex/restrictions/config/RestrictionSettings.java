package hex.restrictions.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public record RestrictionSettings(
        boolean enabled,
        Set<Material> forbiddenItems,
        Set<String> forbiddenEnchantments,
        boolean blockRuntimeEffects,
        boolean scanPlayersOnJoin,
        boolean scanPlayersOnRespawn,
        boolean periodicPlayerScan,
        long playerScanIntervalTicks,
        boolean scanContainersOnOpen,
        boolean scanContainersOnChunkLoad,
        boolean scanLoadedChunksOnEnable,
        int chunksPerTick,
        boolean cleanVillagerTrades,
        boolean logBlockedActions,
        boolean logScanSummaries
) {
    public static RestrictionSettings load(FileConfiguration config, java.util.logging.Logger logger) {
        Set<Material> materials = new LinkedHashSet<>();
        for (String raw : config.getStringList("restrictions.forbidden-items")) {
            Material material = parseMaterial(raw);
            if (material == null) {
                logger.warning("Unknown forbidden item material in config: '" + raw + "'. Entry ignored.");
                continue;
            }
            if (material.isAir()) {
                logger.warning("AIR cannot be used as a forbidden item. Entry ignored.");
                continue;
            }
            materials.add(material);
        }

        Set<String> enchantments = new LinkedHashSet<>();
        for (String raw : config.getStringList("restrictions.forbidden-enchantments")) {
            String normalized = normalizeEnchantmentKey(raw);
            if (normalized == null) {
                logger.warning("Invalid forbidden enchantment key in config: '" + raw + "'. Entry ignored.");
                continue;
            }
            enchantments.add(normalized);
        }

        return new RestrictionSettings(
                config.getBoolean("restrictions.enabled", true),
                Collections.unmodifiableSet(materials),
                Collections.unmodifiableSet(enchantments),
                config.getBoolean("restrictions.block-runtime-effects", true),
                config.getBoolean("scanning.players.on-join", true),
                config.getBoolean("scanning.players.on-respawn", true),
                config.getBoolean("scanning.players.periodic", true),
                Math.max(20L, config.getLong("scanning.players.interval-ticks", 100L)),
                config.getBoolean("scanning.containers.on-open", true),
                config.getBoolean("scanning.containers.on-chunk-load", true),
                config.getBoolean("scanning.containers.scan-loaded-chunks-on-enable", true),
                Math.max(1, config.getInt("scanning.containers.chunks-per-tick", 8)),
                config.getBoolean("scanning.villagers.remove-existing-forbidden-trades-on-chunk-scan", true),
                config.getBoolean("logging.blocked-actions", false),
                config.getBoolean("logging.scan-summaries", true)
        );
    }

    public boolean isForbiddenEnchantmentKey(String key) {
        return key != null && forbiddenEnchantments.contains(normalizeEnchantmentKey(key));
    }

    public static String normalizeEnchantmentKey(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return null;
        if (!value.contains(":")) value = "minecraft:" + value;
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) return null;
        return value;
    }

    private static Material parseMaterial(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.isEmpty()) return null;
        int separator = value.indexOf(':');
        if (separator >= 0 && separator < value.length() - 1) {
            value = value.substring(separator + 1);
        }
        return Material.matchMaterial(value.toUpperCase(Locale.ROOT));
    }
}
