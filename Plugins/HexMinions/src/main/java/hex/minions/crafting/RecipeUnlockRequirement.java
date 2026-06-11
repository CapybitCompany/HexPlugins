package hex.minions.crafting;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

public record RecipeUnlockRequirement(Map<String, Integer> townMinionLevels, Map<String, Long> collections) {
    public static RecipeUnlockRequirement empty() { return new RecipeUnlockRequirement(Map.of(), Map.of()); }

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
        return new RecipeUnlockRequirement(Map.copyOf(levels), Map.copyOf(collections));
    }

    public boolean isEmpty() { return townMinionLevels.isEmpty() && collections.isEmpty(); }
}
