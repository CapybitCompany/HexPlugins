package hex.minions.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public record ItemRequirement(
        String id,
        Material material,
        int amount,
        int customModelData,
        String displayName,
        boolean consume
) {
    public static ItemRequirement fromConfig(String id, ConfigurationSection section) {
        Material material = Material.matchMaterial(section.getString("material", "STONE"));
        if (material == null) material = Material.STONE;
        return new ItemRequirement(
                id,
                material,
                Math.max(1, section.getInt("amount", 1)),
                Math.max(0, section.getInt("custom-model-data", 0)),
                section.getString("display-name", material.name()),
                section.getBoolean("consume", true)
        );
    }

    public boolean matches(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getType() != material) return false;
        if (customModelData <= 0) return true;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasCustomModelData() && meta.getCustomModelData() == customModelData;
    }
}
