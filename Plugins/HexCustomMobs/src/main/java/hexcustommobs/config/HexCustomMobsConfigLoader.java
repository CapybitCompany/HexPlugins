package hexcustommobs.config;

import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public final class HexCustomMobsConfigLoader {

    public HexCustomMobsConfig load(FileConfiguration config, Logger logger) {
        boolean enabled = config.getBoolean("enabled", true);
        boolean debug = config.getBoolean("debug", false);

        HexCustomMobsConfig.Spawn spawn = new HexCustomMobsConfig.Spawn(
                parseSpawnReasons(config.getStringList("spawn.handled-reasons"), logger),
                config.getBoolean("spawn.cancel-vanilla-on-rule-miss", false),
                config.getInt("spawn.max-custom-mobs-per-chunk", 8)
        );

        HexCustomMobsConfig.HpBar hpBar = new HexCustomMobsConfig.HpBar(
                config.getBoolean("hp-bar.enabled", true),
                config.getString("hp-bar.format", "&c<name> &7[&a<health>&7/&a<max_health>&7]"),
                config.getInt("hp-bar.bar-length", 12),
                config.getString("hp-bar.bar-symbol-full", "|"),
                config.getString("hp-bar.bar-symbol-empty", "."),
                config.getBoolean("hp-bar.show-decimals", false)
        );

        Map<String, HexCustomMobsConfig.MobTemplate> mobs = parseMobs(config.getConfigurationSection("mobs"), logger);
        List<HexCustomMobsConfig.BiomeRule> rules = parseBiomeRules(config.getMapList("biome-rules"), logger);

        return new HexCustomMobsConfig(enabled, debug, spawn, hpBar, mobs, rules);
    }

    private Set<CreatureSpawnEvent.SpawnReason> parseSpawnReasons(List<String> values, Logger logger) {
        if (values == null || values.isEmpty()) {
            return Set.of(CreatureSpawnEvent.SpawnReason.NATURAL);
        }
        Set<CreatureSpawnEvent.SpawnReason> reasons = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                reasons.add(CreatureSpawnEvent.SpawnReason.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                logger.warning("HexCustomMobs: Nieznany spawn reason: " + value);
            }
        }
        if (reasons.isEmpty()) {
            reasons.add(CreatureSpawnEvent.SpawnReason.NATURAL);
        }
        return Set.copyOf(reasons);
    }

    private Map<String, HexCustomMobsConfig.MobTemplate> parseMobs(ConfigurationSection section, Logger logger) {
        if (section == null) {
            return Map.of();
        }
        Map<String, HexCustomMobsConfig.MobTemplate> mobs = new LinkedHashMap<>();
        for (String mobId : section.getKeys(false)) {
            ConfigurationSection mob = section.getConfigurationSection(mobId);
            if (mob == null) {
                continue;
            }
            EntityType entityType = parseEntityType(mob.getString("type"), logger, "mobs." + mobId + ".type");
            if (entityType == null || !entityType.isAlive()) {
                logger.warning("HexCustomMobs: Pomijam mob '" + mobId + "' - niepoprawny EntityType.");
                continue;
            }

            HexCustomMobsConfig.Equipment equipment = parseEquipment(mob.getConfigurationSection("equipment"), logger, "mobs." + mobId + ".equipment");
            List<HexCustomMobsConfig.DropDefinition> drops = parseDrops(mob.getMapList("drops"), logger, "mobs." + mobId + ".drops");

            HexCustomMobsConfig.MobTemplate template = new HexCustomMobsConfig.MobTemplate(
                    entityType,
                    mob.getString("display-name", entityType.name()),
                    mob.getDouble("max-health", 20.0D),
                    mob.getDouble("attack-damage", 2.0D),
                    mob.getDouble("movement-speed", 0.25D),
                    mob.getDouble("follow-range", 16.0D),
                    mob.getDouble("armor", 0.0D),
                    equipment,
                    drops,
                    mob.getInt("exp-drop", 0)
            );
            mobs.put(mobId.toLowerCase(Locale.ROOT), template);
        }
        return Map.copyOf(mobs);
    }

    private HexCustomMobsConfig.Equipment parseEquipment(ConfigurationSection section, Logger logger, String path) {
        if (section == null) {
            return HexCustomMobsConfig.Equipment.empty();
        }
        return new HexCustomMobsConfig.Equipment(
                parseItemSection(section.getConfigurationSection("helmet"), logger, path + ".helmet", 1, 0.0F),
                parseItemSection(section.getConfigurationSection("chestplate"), logger, path + ".chestplate", 1, 0.0F),
                parseItemSection(section.getConfigurationSection("leggings"), logger, path + ".leggings", 1, 0.0F),
                parseItemSection(section.getConfigurationSection("boots"), logger, path + ".boots", 1, 0.0F),
                parseItemSection(section.getConfigurationSection("main-hand"), logger, path + ".main-hand", 1, 0.0F),
                parseItemSection(section.getConfigurationSection("off-hand"), logger, path + ".off-hand", 1, 0.0F)
        );
    }

    private List<HexCustomMobsConfig.DropDefinition> parseDrops(List<Map<?, ?>> values, Logger logger, String path) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<HexCustomMobsConfig.DropDefinition> drops = new ArrayList<>();
        int index = 0;
        for (Map<?, ?> raw : values) {
            String prefix = path + "[" + index + "]";
            index++;

            HexCustomMobsConfig.ItemDefinition item = parseItemMap(raw, logger, prefix, 1, 0.0F);
            if (item == null) {
                continue;
            }

            HexCustomMobsConfig.DropDefinition drop = new HexCustomMobsConfig.DropDefinition(
                    item,
                    asDouble(raw.get("chance"), 1.0D),
                    asInt(raw.get("min-amount"), 1),
                    asInt(raw.get("max-amount"), Math.max(1, item.amount()))
            );
            drops.add(drop);
        }
        return List.copyOf(drops);
    }

    private List<HexCustomMobsConfig.BiomeRule> parseBiomeRules(List<Map<?, ?>> values, Logger logger) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<HexCustomMobsConfig.BiomeRule> rules = new ArrayList<>();
        int index = 0;
        for (Map<?, ?> raw : values) {
            String id = asString(raw.get("id"));
            if (id == null || id.isBlank()) {
                id = "rule_" + index;
            }
            index++;

            Set<String> worlds = parseStringSet(raw.get("worlds"));
            Set<Biome> biomes = parseBiomes(raw.get("biomes"), logger, id);
            Map<String, Integer> weights = parseWeights(raw.get("mobs"), logger, id);

            if (biomes.isEmpty() || weights.isEmpty()) {
                logger.warning("HexCustomMobs: Pomijam biome rule '" + id + "' - brak biome lub wag.");
                continue;
            }

            HexCustomMobsConfig.BiomeRule rule = new HexCustomMobsConfig.BiomeRule(
                    id,
                    worlds,
                    biomes,
                    asBoolean(raw.get("replace-vanilla"), true),
                    asDouble(raw.get("spawn-chance"), 1.0D),
                    weights
            );
            rules.add(rule);
        }
        return List.copyOf(rules);
    }

    private Set<Biome> parseBiomes(Object value, Logger logger, String ruleId) {
        Set<Biome> biomes = new LinkedHashSet<>();
        if (!(value instanceof Collection<?> entries)) {
            return Set.of();
        }
        for (Object entry : entries) {
            String name = asString(entry);
            if (name == null || name.isBlank()) {
                continue;
            }
            Biome biome = parseBiome(name);
            if (biome == null) {
                logger.warning("HexCustomMobs: Nieznany biome '" + name + "' w rule '" + ruleId + "'.");
                continue;
            }
            biomes.add(biome);
        }
        return Set.copyOf(biomes);
    }

    private Map<String, Integer> parseWeights(Object value, Logger logger, String ruleId) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String mobId = asString(entry.getKey());
            if (mobId == null || mobId.isBlank()) {
                continue;
            }
            int weight = asInt(entry.getValue(), 0);
            if (weight <= 0) {
                logger.warning("HexCustomMobs: Niepoprawna waga dla '" + mobId + "' w rule '" + ruleId + "'.");
                continue;
            }
            weights.put(mobId.toLowerCase(Locale.ROOT), weight);
        }
        return Map.copyOf(weights);
    }

    private Set<String> parseStringSet(Object value) {
        if (!(value instanceof Collection<?> entries)) {
            return Set.of();
        }
        Set<String> output = new LinkedHashSet<>();
        for (Object entry : entries) {
            String name = asString(entry);
            if (name == null || name.isBlank()) {
                continue;
            }
            output.add(name.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(output);
    }

    private HexCustomMobsConfig.ItemDefinition parseItemSection(
            ConfigurationSection section,
            Logger logger,
            String path,
            int defaultAmount,
            float defaultDropChance
    ) {
        if (section == null) {
            return null;
        }
        String customItemId = emptyToNull(section.getString("custom-item-id"));
        Material material = parseMaterialNullable(section.getString("material"), logger, path + ".material");
        if (customItemId == null && material == null) {
            return null;
        }
        return new HexCustomMobsConfig.ItemDefinition(
                customItemId,
                material,
                section.getInt("amount", defaultAmount),
                section.getString("name"),
                section.getStringList("lore"),
                section.contains("custom-model-data") ? section.getInt("custom-model-data") : null,
                (float) section.getDouble("drop-chance", defaultDropChance)
        );
    }

    private HexCustomMobsConfig.ItemDefinition parseItemMap(
            Map<?, ?> raw,
            Logger logger,
            String path,
            int defaultAmount,
            float defaultDropChance
    ) {
        String customItemId = emptyToNull(asString(raw.get("custom-item-id")));
        Material material = parseMaterialNullable(asString(raw.get("material")), logger, path + ".material");
        if (customItemId == null && material == null) {
            logger.warning("HexCustomMobs: Pomijam wpis " + path + " - brak custom-item-id i material.");
            return null;
        }
        return new HexCustomMobsConfig.ItemDefinition(
                customItemId,
                material,
                asInt(raw.get("amount"), defaultAmount),
                asString(raw.get("name")),
                asStringList(raw.get("lore")),
                asInteger(raw.get("custom-model-data")),
                (float) asDouble(raw.get("drop-chance"), defaultDropChance)
        );
    }

    private EntityType parseEntityType(String raw, Logger logger, String path) {
        if (raw == null || raw.isBlank()) {
            logger.warning("HexCustomMobs: Brak EntityType w " + path);
            return null;
        }
        try {
            return EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            logger.warning("HexCustomMobs: Niepoprawny EntityType '" + raw + "' w " + path);
            return null;
        }
    }

    private Material parseMaterial(String raw, Logger logger, String path) {
        if (raw == null || raw.isBlank()) {
            logger.warning("HexCustomMobs: Brak Material w " + path);
            return null;
        }
        Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        if (material == null) {
            logger.warning("HexCustomMobs: Niepoprawny Material '" + raw + "' w " + path);
        }
        return material;
    }

    private Material parseMaterialNullable(String raw, Logger logger, String path) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parseMaterial(raw, logger, path);
    }

    private Biome parseBiome(String raw) {
        try {
            Object value = Biome.class.getField(raw.trim().toUpperCase(Locale.ROOT)).get(null);
            if (value instanceof Biome biome) {
                return biome;
            }
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
        }
        return null;
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private String emptyToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private double asDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private boolean asBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        return fallback;
    }

    private List<String> asStringList(Object value) {
        if (!(value instanceof Collection<?> entries)) {
            return List.of();
        }
        List<String> output = new ArrayList<>();
        for (Object entry : entries) {
            String text = asString(entry);
            if (text == null) {
                continue;
            }
            output.add(text);
        }
        return List.copyOf(output);
    }
}
