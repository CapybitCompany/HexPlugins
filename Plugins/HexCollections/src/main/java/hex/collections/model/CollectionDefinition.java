package hex.collections.model;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record CollectionDefinition(
        String id,
        boolean enabled,
        String displayName,
        String scope,
        List<String> aliases,
        List<CollectionSource> sources,
        List<CollectionLevel> levels,
        Map<hex.collections.api.CollectionSource, SourceRule> sourceRules,
        Set<hex.collections.api.CollectionSource> validSources,
        int progressBarLength,
        String progressBarFilledChar,
        String progressBarEmptyChar
) {
    public static CollectionDefinition fromConfig(String id, ConfigurationSection section) {
        List<CollectionSource> sources = new ArrayList<>();
        for (var item : section.getMapList("sources")) {
            CollectionSource source = CollectionSource.fromMap(item);
            if (!source.triggerId().isBlank()) {
                sources.add(source);
            }
        }

        List<String> aliases = new ArrayList<>();
        aliases.add(id);
        aliases.add(id.replace('.', '_'));
        for (String alias : section.getStringList("aliases")) {
            if (alias != null && !alias.isBlank()) {
                aliases.add(alias);
            }
        }

        List<CollectionLevel> levels = loadLevels(section);
        Map<hex.collections.api.CollectionSource, SourceRule> sourceRules = loadSourceRules(section);
        Set<hex.collections.api.CollectionSource> validSources = loadValidSources(section, sourceRules.keySet());

        ConfigurationSection placeholder = section.getConfigurationSection("placeholder.progress_bar");
        int progressBarLength = placeholder == null ? 20 : Math.max(1, placeholder.getInt("length", 20));
        String filledChar = placeholder == null ? "■" : placeholder.getString("filled_char", "■");
        String emptyChar = placeholder == null ? "□" : placeholder.getString("empty_char", "□");

        return new CollectionDefinition(
                id,
                section.getBoolean("enabled", true),
                readDisplayName(id, section),
                section.getString("scope", "TOWN"),
                List.copyOf(new LinkedHashSet<>(aliases)),
                List.copyOf(sources),
                List.copyOf(levels),
                Map.copyOf(sourceRules),
                Set.copyOf(validSources),
                progressBarLength,
                filledChar.isEmpty() ? "■" : filledChar,
                emptyChar.isEmpty() ? "□" : emptyChar
        );
    }

    public long requiredFor(int level) {
        if (level <= 0) {
            return 0L;
        }
        for (CollectionLevel value : levels) {
            if (value.level() == level) {
                return Math.max(0L, value.required());
            }
        }
        return levels.isEmpty() ? 0L : Math.max(0L, levels.getLast().required());
    }

    public long nextRequired(int currentLevel) {
        return requiredFor(currentLevel + 1);
    }

    public int levelFor(long amount) {
        int level = 0;
        for (CollectionLevel value : levels) {
            if (amount >= value.required()) {
                level = Math.max(level, value.level());
            }
        }
        return level;
    }

    public boolean matchesAlias(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return false;
        }
        String normalized = normalizeKey(rawId);
        if (normalizeKey(id).equals(normalized)) {
            return true;
        }
        for (String alias : aliases) {
            if (normalizeKey(alias).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static String readDisplayName(String id, ConfigurationSection section) {
        String flat = section.getString("display-name");
        if (flat != null && !flat.isBlank()) {
            return flat;
        }
        ConfigurationSection display = section.getConfigurationSection("display");
        if (display != null) {
            String nested = display.getString("name");
            if (nested != null && !nested.isBlank()) {
                return nested;
            }
        }
        return id;
    }

    private static List<CollectionLevel> loadLevels(ConfigurationSection section) {
        List<CollectionLevel> levels = new ArrayList<>();

        ConfigurationSection levelsSection = section.getConfigurationSection("levels");
        if (levelsSection != null) {
            for (String key : levelsSection.getKeys(false)) {
                int level = intValue(key);
                if (level <= 0) {
                    continue;
                }
                ConfigurationSection value = levelsSection.getConfigurationSection(key);
                if (value == null) {
                    continue;
                }
                levels.add(new CollectionLevel(
                        level,
                        Math.max(0L, value.getLong("required", 0L)),
                        value.getString("display_name", "Level " + level),
                        Map.of(),
                        rewardMaps(value)
                ));
            }
        }

        if (levels.isEmpty()) {
            List<Map<?, ?>> tiers = section.getMapList("tiers");
            int index = 1;
            for (Map<?, ?> tier : tiers) {
                long required = tier.get("amount") instanceof Number number ? number.longValue() : 0L;
                levels.add(new CollectionLevel(index, Math.max(0L, required), section.getString("display-name", idFallback(section, index)), Map.of(), List.of()));
                index++;
            }
        }

        levels.sort(java.util.Comparator.comparingInt(CollectionLevel::level));
        return levels;
    }

    private static String idFallback(ConfigurationSection section, int level) {
        return readDisplayName(section.getName(), section) + " " + level;
    }

    private static Map<hex.collections.api.CollectionSource, SourceRule> loadSourceRules(ConfigurationSection section) {
        Map<hex.collections.api.CollectionSource, SourceRule> rules = new LinkedHashMap<>();
        ConfigurationSection sourceRules = section.getConfigurationSection("source_rules");
        if (sourceRules == null) {
            return rules;
        }

        for (String key : sourceRules.getKeys(false)) {
            hex.collections.api.CollectionSource source = sourceEnum(key);
            if (source == hex.collections.api.CollectionSource.UNKNOWN) {
                continue;
            }
            ConfigurationSection value = sourceRules.getConfigurationSection(key);
            if (value == null) {
                continue;
            }
            rules.put(source, new SourceRule(
                    source,
                    materials(value.getStringList("allowed_materials")),
                    Set.copyOf(value.getStringList("allowed_worlds")),
                    Set.copyOf(value.getStringList("blocked_worlds")),
                    value.getBoolean("allow_in_town_claims", false),
                    value.getBoolean("deny_player_placed_blocks", false),
                    value.getBoolean("deny_recently_broken_blocks", false)
            ));
        }
        return rules;
    }

    private static Set<hex.collections.api.CollectionSource> loadValidSources(ConfigurationSection section, Set<hex.collections.api.CollectionSource> fallback) {
        LinkedHashSet<hex.collections.api.CollectionSource> sources = new LinkedHashSet<>();
        for (String raw : section.getStringList("valid_sources")) {
            hex.collections.api.CollectionSource source = sourceEnum(raw);
            if (source != hex.collections.api.CollectionSource.UNKNOWN) {
                sources.add(source);
            }
        }
        if (sources.isEmpty()) {
            sources.addAll(fallback);
        }
        if (sources.isEmpty()) {
            sources.add(hex.collections.api.CollectionSource.CUSTOM_PLUGIN_GRANTED);
        }
        return sources;
    }

    private static Set<Material> materials(List<String> values) {
        LinkedHashSet<Material> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            Material material = Material.matchMaterial(value);
            if (material != null) {
                result.add(material);
            }
        }
        return result;
    }

    private static hex.collections.api.CollectionSource sourceEnum(String raw) {
        if (raw == null || raw.isBlank()) {
            return hex.collections.api.CollectionSource.UNKNOWN;
        }
        try {
            return hex.collections.api.CollectionSource.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return hex.collections.api.CollectionSource.UNKNOWN;
        }
    }

    private static List<Map<String, Object>> rewardMaps(ConfigurationSection section) {
        List<Map<String, Object>> rewards = new ArrayList<>();
        for (Map<?, ?> reward : section.getMapList("rewards")) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : reward.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            rewards.add(Map.copyOf(normalized));
        }
        return List.copyOf(rewards);
    }

    private static int intValue(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '.');
    }
}


