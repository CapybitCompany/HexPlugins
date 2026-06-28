package hex.minions.customdrops;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public record CustomResourceDropSettings(
        boolean enabled,
        double tinChance,
        boolean silkTouchAllowed,
        boolean fortuneEnabled,
        double fortune1Chance,
        double fortune2Chance,
        double fortune3Chance,
        int autosaveIntervalSeconds,
        int maxChunkLoadBatchSize,
        int maxChunkSaveBatchSize,
        String tinDropResourceId,
        Set<Material> brokenMaterials,
        Set<Material> adjacentOres
) {
    public static CustomResourceDropSettings load(FileConfiguration config) {
        String root = "custom_resources.tin.";
        return new CustomResourceDropSettings(
                config.getBoolean(root + "enabled", true),
                Math.max(0.0D, Math.min(1.0D, config.getDouble(root + "chance", 0.002D))),
                config.getBoolean(root + "silk_touch_allowed", false),
                config.getBoolean(root + "fortune.enabled", false),
                chance(config, root + "fortune.fortune_1_chance", 0.0025D),
                chance(config, root + "fortune.fortune_2_chance", 0.0030D),
                chance(config, root + "fortune.fortune_3_chance", 0.0035D),
                Math.max(1, config.getInt(root + "autosave_interval_seconds", 10)),
                Math.max(1, config.getInt(root + "max_chunk_load_batch_size", 100)),
                Math.max(1, config.getInt(root + "max_chunk_save_batch_size", 100)),
                config.getString(root + "drop_resource", "tin_ore"),
                materials(config.getStringList(root + "broken_materials"), Set.of(Material.STONE, Material.DEEPSLATE)),
                materials(config.getStringList(root + "adjacent_ores"), Set.of(Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE))
        );
    }

    public boolean relevantPlacedMaterial(Material material) {
        return brokenMaterials.contains(material) || adjacentOres.contains(material);
    }

    public boolean stoneMaterial(Material material) {
        return brokenMaterials.contains(material);
    }

    public boolean copperOreMaterial(Material material) {
        return adjacentOres.contains(material);
    }

    private static double chance(FileConfiguration config, String path, double def) {
        return Math.max(0.0D, Math.min(1.0D, config.getDouble(path, def)));
    }

    private static Set<Material> materials(java.util.List<String> raw, Set<Material> fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        Set<Material> result = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null || value.isBlank()) continue;
            Material material = Material.matchMaterial(value.toUpperCase(Locale.ROOT));
            if (material != null) result.add(material);
        }
        return result.isEmpty() ? fallback : Set.copyOf(result);
    }
}
