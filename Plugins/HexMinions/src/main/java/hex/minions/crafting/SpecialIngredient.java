package hex.minions.crafting;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public record SpecialIngredient(Material material, int amount, int customModelData, String specialItemId, List<Material> materials) {
    public SpecialIngredient(Material material, int amount, int customModelData, String specialItemId) {
        this(material, amount, customModelData, specialItemId, List.of());
    }

    public static SpecialIngredient fromConfig(ConfigurationSection section) {
        if (section == null) return new SpecialIngredient(Material.AIR, 0, 0, "", List.of());
        List<Material> acceptedMaterials = new ArrayList<>();
        for (String raw : section.getStringList("materials")) {
            Material candidate = Material.matchMaterial(raw == null ? "" : raw);
            if (candidate != null && candidate != Material.AIR) acceptedMaterials.add(candidate);
        }
        Material material = Material.matchMaterial(section.getString("material", acceptedMaterials.isEmpty() ? "AIR" : acceptedMaterials.get(0).name()));
        if (material == null) material = Material.AIR;
        return new SpecialIngredient(material, Math.max(1, section.getInt("amount", 1)), Math.max(0, section.getInt("custom-model-data", 0)), section.getString("special-item", ""), List.copyOf(acceptedMaterials));
    }

    public boolean matches(ItemStack item, SpecialItemRegistry registry) {
        if (specialItemId != null && !specialItemId.isBlank()) {
            if (item == null || item.getType().isAir() || item.getAmount() < amount) return false;
            return registry.readSpecialItemId(item).map(specialItemId::equalsIgnoreCase).orElse(false);
        }
        if (material == Material.AIR && materials.isEmpty()) return item == null || item.getType().isAir();
        if (item == null || !matchesMaterial(item.getType()) || item.getAmount() < amount) return false;
        if (customModelData > 0) {
            ItemMeta meta = item.getItemMeta();
            if (meta == null || !meta.hasCustomModelData() || meta.getCustomModelData() != customModelData) return false;
        }
        return true;
    }

    public boolean hasMaterialChoices() {
        return materials != null && !materials.isEmpty();
    }

    public List<Material> materialChoices() {
        return materials == null || materials.isEmpty() ? List.of(material) : materials;
    }

    public boolean matchesMaterial(Material tested) {
        if (tested == null) return false;
        if (materials != null && !materials.isEmpty()) return materials.contains(tested);
        return tested == material;
    }
}
