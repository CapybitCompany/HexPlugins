package hex.minions.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public record StorageChestDefinition(
        String id,
        boolean enabled,
        Material material,
        String displayName,
        List<String> lore,
        int customModelData,
        int slots,
        boolean shapedRecipeEnabled,
        List<String> recipeShape,
        Map<Character, Material> recipeIngredients
) {
    public static StorageChestDefinition fromConfig(String id, ConfigurationSection section) {
        Material material = parseMaterial(section.getString("item.material", "CHEST"), Material.CHEST);
        return new StorageChestDefinition(
                id.toLowerCase(Locale.ROOT),
                section.getBoolean("enabled", true),
                material,
                section.getString("item.display-name", "<gold>Minion Storage</gold>"),
                List.copyOf(section.getStringList("item.lore")),
                Math.max(0, section.getInt("item.custom-model-data", 0)),
                Math.max(1, section.getInt("slots", 3)),
                section.getBoolean("recipe.enabled", true),
                List.copyOf(section.getStringList("recipe.shape")),
                ingredients(section.getConfigurationSection("recipe.ingredients"))
        );
    }

    private static Map<Character, Material> ingredients(ConfigurationSection section) {
        java.util.Map<Character, Material> result = new java.util.LinkedHashMap<>();
        if (section == null) return Map.of();
        for (String key : section.getKeys(false)) {
            if (key == null || key.isBlank()) continue;
            Material material = parseMaterial(section.getString(key), null);
            if (material != null) result.put(key.charAt(0), material);
        }
        return Map.copyOf(result);
    }

    private static Material parseMaterial(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        Material material = Material.matchMaterial(raw);
        return material == null ? fallback : material;
    }
}
