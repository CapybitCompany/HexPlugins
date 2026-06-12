package hex.minions.crafting;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

public record RecipeUnlockRequirement(Map<String, Integer> townMinionLevels, Map<String, Long> collections, Map<String, Integer> collectionLevels, List<CollectionTierCount> collectionTierCounts) {
    public static RecipeUnlockRequirement empty() { return new RecipeUnlockRequirement(Map.of(), Map.of(), Map.of(), List.of()); }

    public record CollectionTierCount(int count, int tier, boolean distinct) {}

    public static RecipeUnlockRequirement fromConfig(ConfigurationSection section) {
        if (section == null) return empty();
        Map<String, Integer> levels = new LinkedHashMap<>();
        ConfigurationSection minions = section.getConfigurationSection("town-minion-levels");
        if (minions != null) {
            for (String key : minions.getKeys(false)) levels.put(key.toLowerCase(java.util.Locale.ROOT), Math.max(1, minions.getInt(key)));
        }
        Map<String, Long> collections = new LinkedHashMap<>();
        ConfigurationSection coll = section.getConfigurationSection("collections");
        if (coll != null) {
            for (String key : coll.getKeys(false)) collections.put(key, Math.max(0L, coll.getLong(key)));
        }
        Map<String, Integer> collectionLevels = new LinkedHashMap<>();
        ConfigurationSection collLevels = section.getConfigurationSection("collection-levels");
        if (collLevels != null) {
            for (String key : collLevels.getKeys(false)) collectionLevels.put(key, Math.max(1, collLevels.getInt(key)));
        }
        List<CollectionTierCount> tierCounts = new ArrayList<>();
        for (Map<?, ?> raw : section.getMapList("collection-tier-counts")) {
            int count = raw.get("count") instanceof Number number ? Math.max(1, number.intValue()) : 1;
            int tier = raw.get("tier") instanceof Number number ? Math.max(1, number.intValue()) : 1;
            boolean distinct = raw.get("distinct") instanceof Boolean value && value;
            tierCounts.add(new CollectionTierCount(count, tier, distinct));
        }
        ConfigurationSection singleCount = section.getConfigurationSection("collection-tier-count");
        if (singleCount != null) {
            tierCounts.add(new CollectionTierCount(Math.max(1, singleCount.getInt("count", 1)), Math.max(1, singleCount.getInt("tier", 1)), singleCount.getBoolean("distinct", false)));
        }
        return new RecipeUnlockRequirement(Map.copyOf(levels), Map.copyOf(collections), Map.copyOf(collectionLevels), List.copyOf(tierCounts));
    }

    public boolean isEmpty() { return townMinionLevels.isEmpty() && collections.isEmpty() && collectionLevels.isEmpty() && collectionTierCounts.isEmpty(); }
}
