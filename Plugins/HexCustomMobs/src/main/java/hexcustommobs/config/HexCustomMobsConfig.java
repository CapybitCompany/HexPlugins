package hexcustommobs.config;

import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Biome;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public record HexCustomMobsConfig(
        boolean enabled,
        boolean debug,
        Spawn spawn,
        HpBar hpBar,
        Map<String, MobTemplate> mobs,
        List<BiomeRule> biomeRules
) {
    public HexCustomMobsConfig {
        spawn = Objects.requireNonNull(spawn, "spawn");
        hpBar = Objects.requireNonNull(hpBar, "hpBar");
        mobs = normalizeMobs(mobs);
        biomeRules = normalizeRules(biomeRules);
    }

    public Optional<BiomeRule> findRule(String worldName, Biome biome) {
        for (BiomeRule rule : biomeRules) {
            if (rule.matches(worldName, biome)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    private static Map<String, MobTemplate> normalizeMobs(Map<String, MobTemplate> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, MobTemplate> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, MobTemplate> entry : values.entrySet()) {
            String key = entry.getKey();
            MobTemplate mob = entry.getValue();
            if (key == null || key.isBlank() || mob == null) {
                continue;
            }
            normalized.put(key.trim().toLowerCase(Locale.ROOT), mob);
        }
        return Map.copyOf(normalized);
    }

    private static List<BiomeRule> normalizeRules(List<BiomeRule> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return List.copyOf(values);
    }

    public record Spawn(
            Set<CreatureSpawnEvent.SpawnReason> handledReasons,
            boolean cancelVanillaOnRuleMiss,
            int maxCustomMobsPerChunk
    ) {
        public Spawn {
            handledReasons = normalizeReasons(handledReasons);
            maxCustomMobsPerChunk = Math.max(1, maxCustomMobsPerChunk);
        }

        private static Set<CreatureSpawnEvent.SpawnReason> normalizeReasons(Set<CreatureSpawnEvent.SpawnReason> values) {
            if (values == null || values.isEmpty()) {
                return Set.of(CreatureSpawnEvent.SpawnReason.NATURAL);
            }
            Set<CreatureSpawnEvent.SpawnReason> normalized = new LinkedHashSet<>(values);
            return Set.copyOf(normalized);
        }
    }

    public record HpBar(
            boolean enabled,
            String format,
            int barLength,
            String barSymbolFull,
            String barSymbolEmpty,
            boolean showDecimals
    ) {
        public HpBar {
            format = fallback(format, "&c<name> &7[&a<health>&7/&a<max_health>&7]");
            barLength = Math.max(1, barLength);
            barSymbolFull = fallback(barSymbolFull, "|");
            barSymbolEmpty = fallback(barSymbolEmpty, ".");
        }
    }

    public record BiomeRule(
            String id,
            Set<String> worlds,
            Set<Biome> biomes,
            boolean replaceVanilla,
            double spawnChance,
            Map<String, Integer> mobWeights
    ) {
        public BiomeRule {
            id = fallback(id, "rule");
            worlds = normalizeWorlds(worlds);
            biomes = normalizeBiomes(biomes);
            spawnChance = clampChance(spawnChance);
            mobWeights = normalizeWeights(mobWeights);
        }

        public boolean matches(String worldName, Biome biome) {
            if (biome == null) {
                return false;
            }
            if (!worlds.isEmpty()) {
                if (worldName == null || worldName.isBlank()) {
                    return false;
                }
                if (!worlds.contains(worldName.toLowerCase(Locale.ROOT))) {
                    return false;
                }
            }
            return biomes.contains(biome);
        }

        private static Set<String> normalizeWorlds(Set<String> values) {
            if (values == null || values.isEmpty()) {
                return Set.of();
            }
            Set<String> normalized = new LinkedHashSet<>();
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                normalized.add(value.trim().toLowerCase(Locale.ROOT));
            }
            return Set.copyOf(normalized);
        }

        private static Set<Biome> normalizeBiomes(Set<Biome> values) {
            if (values == null || values.isEmpty()) {
                return Set.of();
            }
            return Set.copyOf(new LinkedHashSet<>(values));
        }

        private static Map<String, Integer> normalizeWeights(Map<String, Integer> values) {
            if (values == null || values.isEmpty()) {
                return Map.of();
            }
            Map<String, Integer> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> entry : values.entrySet()) {
                String key = entry.getKey();
                Integer weight = entry.getValue();
                if (key == null || key.isBlank() || weight == null || weight <= 0) {
                    continue;
                }
                normalized.put(key.trim().toLowerCase(Locale.ROOT), weight);
            }
            return Map.copyOf(normalized);
        }
    }

    public record MobTemplate(
            EntityType type,
            String displayName,
            double maxHealth,
            double attackDamage,
            double movementSpeed,
            double followRange,
            double armor,
            Equipment equipment,
            List<DropDefinition> drops,
            int expDrop
    ) {
        public MobTemplate {
            type = Objects.requireNonNull(type, "type");
            displayName = fallback(displayName, type.name());
            maxHealth = Math.max(1.0D, maxHealth);
            attackDamage = Math.max(0.0D, attackDamage);
            movementSpeed = Math.max(0.0D, movementSpeed);
            followRange = Math.max(1.0D, followRange);
            armor = Math.max(0.0D, armor);
            equipment = equipment == null ? Equipment.empty() : equipment;
            drops = drops == null ? List.of() : List.copyOf(drops);
            expDrop = Math.max(0, expDrop);
        }

        public Map<Attribute, Double> combatAttributes() {
            Map<Attribute, Double> attributes = new LinkedHashMap<>();
            attributes.put(Attribute.GENERIC_MAX_HEALTH, maxHealth);
            attributes.put(Attribute.GENERIC_ATTACK_DAMAGE, attackDamage);
            attributes.put(Attribute.GENERIC_MOVEMENT_SPEED, movementSpeed);
            attributes.put(Attribute.GENERIC_FOLLOW_RANGE, followRange);
            attributes.put(Attribute.GENERIC_ARMOR, armor);
            return attributes;
        }
    }

    public record Equipment(
            ItemDefinition helmet,
            ItemDefinition chestplate,
            ItemDefinition leggings,
            ItemDefinition boots,
            ItemDefinition mainHand,
            ItemDefinition offHand
    ) {
        public static Equipment empty() {
            return new Equipment(null, null, null, null, null, null);
        }
    }

    public record ItemDefinition(
            String customItemId,
            Material material,
            int amount,
            String name,
            List<String> lore,
            Integer customModelData,
            float dropChance
    ) {
        public ItemDefinition {
            customItemId = (customItemId == null || customItemId.isBlank()) ? null : customItemId.trim();
            if (material == Material.AIR) {
                material = null;
            }
            if (customItemId == null && material == null) {
                throw new IllegalArgumentException("ItemDefinition musi mieć customItemId lub material");
            }
            amount = Math.max(1, amount);
            lore = lore == null ? List.of() : List.copyOf(lore);
            dropChance = Math.max(0.0F, dropChance);
        }
    }

    public record DropDefinition(
            ItemDefinition item,
            double chance,
            int minAmount,
            int maxAmount
    ) {
        public DropDefinition {
            item = Objects.requireNonNull(item, "item");
            chance = clampChance(chance);
            minAmount = Math.max(1, minAmount);
            maxAmount = Math.max(minAmount, maxAmount);
        }
    }

    private static double clampChance(double value) {
        if (value < 0.0D) {
            return 0.0D;
        }
        if (value > 1.0D) {
            return 1.0D;
        }
        return value;
    }

    private static String fallback(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
