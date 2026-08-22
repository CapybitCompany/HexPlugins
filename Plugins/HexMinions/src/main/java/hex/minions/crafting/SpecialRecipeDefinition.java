package hex.minions.crafting;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record SpecialRecipeDefinition(
        String id,
        boolean enabled,
        String station,
        List<String> shape,
        Map<Character, SpecialIngredient> ingredients,
        String outputSpecialItem,
        String outputMinionType,
        int outputMinionTier,
        Material outputMaterial,
        int outputAmount,
        int outputCustomModelData,
        RecipeUnlockRequirement unlock
) {
    public static SpecialRecipeDefinition fromConfig(String id, ConfigurationSection section) {
        Map<Character, SpecialIngredient> ingredients = new LinkedHashMap<>();
        ConfigurationSection ing = section.getConfigurationSection("ingredients");
        if (ing != null) {
            for (String key : ing.getKeys(false)) {
                if (key == null || key.isBlank()) continue;
                ingredients.put(key.charAt(0), SpecialIngredient.fromConfig(ing.getConfigurationSection(key)));
            }
        }
        Material outputMaterial = Material.matchMaterial(section.getString("output.material", "AIR"));
        if (outputMaterial == null) outputMaterial = Material.AIR;
        return new SpecialRecipeDefinition(
                id.toLowerCase(java.util.Locale.ROOT),
                section.getBoolean("enabled", true),
                section.getString("station", "VANILLA_CRAFTING_TABLE"),
                normalizeShape(section.getStringList("shape")),
                Map.copyOf(ingredients),
                section.getString("output.special-item", ""),
                section.getString("output.minion-type", ""),
                Math.max(1, section.getInt("output.minion-tier", 1)),
                outputMaterial,
                Math.max(1, section.getInt("output.amount", 1)),
                Math.max(0, section.getInt("output.custom-model-data", 0)),
                RecipeUnlockRequirement.fromConfig(section.getConfigurationSection("unlock"))
        );
    }

    private static List<String> normalizeShape(List<String> raw) {
        java.util.ArrayList<String> rows = new java.util.ArrayList<>(raw);
        while (rows.size() < 3) rows.add("   ");
        return rows.stream().limit(3).map(row -> (row + "   ").substring(0, 3)).toList();
    }
}
