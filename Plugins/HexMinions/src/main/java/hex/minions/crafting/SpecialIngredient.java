package hex.minions.crafting;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public record SpecialIngredient(Material material, int amount, int customModelData, String specialItemId) {
    public static SpecialIngredient fromConfig(ConfigurationSection section) {
        if (section == null) return new SpecialIngredient(Material.AIR, 0, 0, "");
        Material material = Material.matchMaterial(section.getString("material", "AIR"));
        if (material == null) material = Material.AIR;
        return new SpecialIngredient(material, Math.max(1, section.getInt("amount", 1)), Math.max(0, section.getInt("custom-model-data", 0)), section.getString("special-item", ""));
    }

    public boolean matches(ItemStack item, SpecialItemRegistry registry) {
        if (specialItemId != null && !specialItemId.isBlank()) {
            if (item == null || item.getType().isAir() || item.getAmount() < amount) return false;
            return registry.readSpecialItemId(item).map(specialItemId::equalsIgnoreCase).orElse(false);
        }
        if (material == Material.AIR) return item == null || item.getType().isAir();
        if (item == null || item.getType() != material || item.getAmount() < amount) return false;
        if (customModelData > 0) {
            ItemMeta meta = item.getItemMeta();
            if (meta == null || !meta.hasCustomModelData() || meta.getCustomModelData() != customModelData) return false;
        }
        return true;
    }
}
