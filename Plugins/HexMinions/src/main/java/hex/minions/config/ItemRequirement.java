package hex.minions.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public record ItemRequirement(
        String id,
        String specialItemId,
        Material material,
        int amount,
        int customModelData,
        String displayName,
        boolean consume
) {
    public static ItemRequirement fromConfig(String id, ConfigurationSection section) {
        String specialItemId = section.getString("special-item", "");
        Material material = Material.matchMaterial(section.getString("material", "STONE"));
        if (material == null) material = Material.STONE;
        String displayName = section.getString("display-name", specialItemId == null || specialItemId.isBlank() ? material.name() : specialItemId);
        int configuredAmount = Math.max(1, section.getInt("amount", 1));
        int normalizedAmount = configuredAmount > 128
                ? Math.max(192, (int) Math.floor(configuredAmount / 64.0D + 0.5D) * 64)
                : configuredAmount;
        return new ItemRequirement(
                id,
                specialItemId == null ? "" : specialItemId,
                material,
                normalizedAmount,
                Math.max(0, section.getInt("custom-model-data", 0)),
                displayName,
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
