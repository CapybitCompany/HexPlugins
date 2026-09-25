package hexposterunki.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** Reads {@code config.yml} into the immutable {@link PosterunkiConfig} model. */
public final class ConfigLoader {

    private static final Set<String> DEFAULT_SNAPSHOT_MATERIALS = Set.of(
            "OAK_DOOR", "SPRUCE_DOOR", "BIRCH_DOOR", "JUNGLE_DOOR", "ACACIA_DOOR", "DARK_OAK_DOOR",
            "MANGROVE_DOOR", "CHERRY_DOOR", "BAMBOO_DOOR", "CRIMSON_DOOR", "WARPED_DOOR", "IRON_DOOR",
            "OAK_TRAPDOOR", "SPRUCE_TRAPDOOR", "BIRCH_TRAPDOOR", "JUNGLE_TRAPDOOR", "ACACIA_TRAPDOOR",
            "DARK_OAK_TRAPDOOR", "MANGROVE_TRAPDOOR", "CHERRY_TRAPDOOR", "BAMBOO_TRAPDOOR",
            "CRIMSON_TRAPDOOR", "WARPED_TRAPDOOR", "IRON_TRAPDOOR",
            "OAK_FENCE_GATE", "SPRUCE_FENCE_GATE", "BIRCH_FENCE_GATE", "JUNGLE_FENCE_GATE",
            "ACACIA_FENCE_GATE", "DARK_OAK_FENCE_GATE", "MANGROVE_FENCE_GATE", "CHERRY_FENCE_GATE",
            "BAMBOO_FENCE_GATE", "CRIMSON_FENCE_GATE", "WARPED_FENCE_GATE",
            "LEVER", "STONE_BUTTON", "OAK_BUTTON", "SPRUCE_BUTTON", "BIRCH_BUTTON", "JUNGLE_BUTTON",
            "ACACIA_BUTTON", "DARK_OAK_BUTTON", "POLISHED_BLACKSTONE_BUTTON",
            "STONE_PRESSURE_PLATE", "OAK_PRESSURE_PLATE", "HEAVY_WEIGHTED_PRESSURE_PLATE",
            "LIGHT_WEIGHTED_PRESSURE_PLATE", "REDSTONE_TORCH", "REPEATER", "COMPARATOR");

    public PosterunkiConfig load(FileConfiguration configuration, Logger logger) {
        boolean enabled = configuration.getBoolean("enabled", true);
        boolean debug = configuration.getBoolean("debug", false);

        PosterunkiConfig.Timing timing = new PosterunkiConfig.Timing(
                configuration.getLong("timing.active-window-seconds", 3600L),
                configuration.getLong("timing.cooldown-seconds", 1800L),
                configuration.getLong("timing.preparation-seconds", 15L),
                configuration.getLong("timing.wave-delay-seconds", 10L),
                configuration.getLong("timing.grace-period-seconds", 120L),
                configuration.getLong("timing.loot-seconds", 120L),
                configuration.getLong("timing.tick-interval-ticks", 20L),
                configuration.getLong("timing.snapshot-interval-seconds", 30L));

        int requiredKills = configuration.getInt("kills.required", 10);
        boolean avoidRepeat = configuration.getBoolean("selection.avoid-immediate-repeat", true);

        List<WaveDefinition> waves = loadWaves(configuration, logger);
        BossConfig boss = loadBoss(configuration, logger);

        Set<String> snapshotMaterials = new LinkedHashSet<>();
        List<String> configured = configuration.getStringList("protection.snapshot-materials");
        if (configured.isEmpty()) {
            snapshotMaterials.addAll(DEFAULT_SNAPSHOT_MATERIALS);
        } else {
            for (String value : configured) {
                if (value != null && !value.isBlank()) {
                    snapshotMaterials.add(value.trim().toUpperCase(Locale.ROOT));
                }
            }
        }

        long maxSnapshotVolume = configuration.getLong("protection.max-snapshot-volume", 250_000L);
        PosterunkiConfig.Protection protection = new PosterunkiConfig.Protection(
                maxSnapshotVolume,
                configuration.getLong("protection.max-reset-scan-volume", maxSnapshotVolume),
                configuration.getLong("protection.scan-blocks-per-tick", 20_000L),
                configuration.getInt("protection.chunk-ticket-limit", 64),
                configuration.getLong("protection.containment-check-interval-ticks", 40L),
                configuration.getBoolean("protection.remove-ground-items-on-reset", false),
                snapshotMaterials);

        PosterunkiConfig.Ui ui = new PosterunkiConfig.Ui(
                configuration.getBoolean("ui.bossbar.enabled", true),
                configuration.getDouble("ui.bossbar.max-distance", 20_000.0D),
                configuration.getBoolean("ui.hologram.enabled", true),
                (float) configuration.getDouble("ui.hologram.view-range", 2.0D),
                configuration.getLong("ui.hologram.update-interval-ticks", 20L),
                configuration.getBoolean("ui.actionbar.enabled", true));

        PosterunkiConfig.Towns towns = new PosterunkiConfig.Towns(
                configuration.getString("towns.required-version", "*"));

        return new PosterunkiConfig(enabled, debug, timing, requiredKills, avoidRepeat,
                waves, boss, towns, protection, ui,
                loadRewards(configuration, logger), LootConfig.disabled());
    }

    private List<WaveDefinition> loadWaves(FileConfiguration configuration, Logger logger) {
        List<WaveDefinition> waves = new ArrayList<>();
        List<?> rawWaves = configuration.getList("waves");
        if (rawWaves == null) {
            logger.warning("[config] Brak sekcji 'waves' - fale nie zostaną uruchomione.");
            return waves;
        }
        int index = 0;
        for (Object rawWave : rawWaves) {
            index++;
            if (!(rawWave instanceof Map<?, ?> waveMap)) {
                logger.warning("[config] waves[" + index + "] nie jest mapą - pomijam.");
                continue;
            }
            int waveId = waveMap.get("id") instanceof Number number ? number.intValue() : index;
            List<WaveDefinition.Group> groups = new ArrayList<>();
            Object rawGroups = waveMap.get("groups");
            if (rawGroups instanceof List<?> groupList) {
                for (Object rawGroup : groupList) {
                    if (!(rawGroup instanceof Map<?, ?> groupMap)) {
                        continue;
                    }
                    Object mobId = groupMap.get("mob-id");
                    if (mobId == null || mobId.toString().isBlank()) {
                        logger.warning("[config] waves[" + index + "] ma grupę bez 'mob-id' - pomijam grupę.");
                        continue;
                    }
                    int count = groupMap.get("count") instanceof Number number ? number.intValue() : 1;
                    List<String> spawnPoints = new ArrayList<>();
                    if (groupMap.get("spawn-points") instanceof List<?> pointList) {
                        for (Object point : pointList) {
                            if (point != null) {
                                spawnPoints.add(point.toString().trim().toLowerCase(Locale.ROOT));
                            }
                        }
                    }
                    groups.add(new WaveDefinition.Group(
                            mobId.toString().trim().toLowerCase(Locale.ROOT), count, spawnPoints));
                }
            }
            if (groups.isEmpty()) {
                logger.warning("[config] waves[" + index + "] nie ma żadnej poprawnej grupy - pomijam falę.");
                continue;
            }
            waves.add(new WaveDefinition(waveId, groups));
        }
        return waves;
    }

    private BossConfig loadBoss(FileConfiguration configuration, Logger logger) {
        List<BossConfig.WeightedBoss> bosses = new ArrayList<>();
        ConfigurationSection section = configuration.getConfigurationSection("boss.bosses");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                int weight = section.getInt(key, 0);
                if (weight <= 0) {
                    logger.warning("[config] boss '" + key + "' ma wagę <= 0 - nie zostanie wylosowany.");
                    continue;
                }
                bosses.add(new BossConfig.WeightedBoss(key.trim(), weight));
            }
        }
        return new BossConfig(
                configuration.getBoolean("boss.enabled", true),
                configuration.getDouble("boss.spawn-chance", 0.35D),
                configuration.getString("boss.provider", "stormbossy"),
                configuration.getString("boss.required-version", "1.0"),
                configuration.getBoolean("boss.require-native-schedules-disabled", true),
                configuration.getBoolean("boss.expect-native-rewards", true),
                bosses);
    }

    private RewardsConfig loadRewards(FileConfiguration configuration, Logger logger) {
        boolean enabled = configuration.getBoolean("rewards.enabled", false);
        int places = configuration.getInt("rewards.places", 5);
        int maxAttempts = configuration.getInt("rewards.max-delivery-attempts", 3);
        Map<Integer, RewardsConfig.PlaceReward> rewards = new LinkedHashMap<>();
        ConfigurationSection section = configuration.getConfigurationSection("rewards.places-config");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                int place;
                try {
                    place = Integer.parseInt(key.trim());
                } catch (NumberFormatException exception) {
                    logger.warning("[config] rewards.places-config." + key + " nie jest liczbą - pomijam.");
                    continue;
                }
                ConfigurationSection placeSection = section.getConfigurationSection(key);
                if (placeSection == null) {
                    continue;
                }
                List<RewardsConfig.ItemReward> items = new ArrayList<>();
                List<?> rawItems = placeSection.getList("items");
                if (rawItems != null) {
                    for (Object rawItem : rawItems) {
                        if (!(rawItem instanceof Map<?, ?> itemMap)) {
                            continue;
                        }
                        Object material = itemMap.get("material");
                        if (material == null) {
                            continue;
                        }
                        int amount = itemMap.get("amount") instanceof Number number ? number.intValue() : 1;
                        String name = itemMap.get("name") == null ? null : itemMap.get("name").toString();
                        List<String> lore = new ArrayList<>();
                        if (itemMap.get("lore") instanceof List<?> loreList) {
                            for (Object line : loreList) {
                                if (line != null) {
                                    lore.add(line.toString());
                                }
                            }
                        }
                        items.add(new RewardsConfig.ItemReward(
                                material.toString().trim().toUpperCase(Locale.ROOT), amount, name, lore));
                    }
                }
                rewards.put(place, new RewardsConfig.PlaceReward(items, placeSection.getStringList("commands")));
            }
        }
        return new RewardsConfig(enabled, places, maxAttempts, rewards);
    }
}
