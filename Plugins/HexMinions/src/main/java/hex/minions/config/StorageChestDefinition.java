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
        Material placedMaterial,
        String displayName,
        List<String> lore,
        int customModelData,
        int slots,
        boolean shapedRecipeEnabled,
        List<String> recipeShape,
        Map<Character, Material> recipeIngredients
) {
    public static StorageChestDefinition fromConfig(String id, ConfigurationSection section) {
        Material material = parseMaterial(section.getString("item.material", "CHEST_MINECART"), Material.CHEST_MINECART);
        Material placedMaterial = parseMaterial(section.getString("item.placed-material", "CHEST"), Material.CHEST);
        if (!placedMaterial.isBlock()) placedMaterial = Material.CHEST;
        int customModelData = Math.max(0, section.getInt("item.custom-model-data", 0));
        // Legacy small storage used CMD=0, which rendered as a vanilla chest minecart.
        // It shares the resource-pack model with the storage_expander special item.
        if ("small".equalsIgnoreCase(id) && customModelData <= 0) customModelData = 10003;
        return new StorageChestDefinition(
                id.toLowerCase(Locale.ROOT),
                section.getBoolean("enabled", true),
                material,
                placedMaterial,
                section.getString("item.display-name", "<gold>Magazyn miniona</gold>"),
                List.copyOf(section.getStringList("item.lore")),
                customModelData,
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
