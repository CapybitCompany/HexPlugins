package hex.minions.machine;

import hex.minions.crafting.SpecialItemRegistry;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

public record MachineRecipe(
        String id,
        String inputSpecialItem,
        Material inputMaterial,
        int inputCustomModelData,
        int inputAmount,
        String secondarySpecialItem,
        Material secondaryMaterial,
        int secondaryAmount,
        String fuelSpecialItem,
        Material fuelMaterial,
        int fuelAmount,
        String outputSpecialItem,
        Material outputMaterial,
        int outputCustomModelData,
        int outputAmount,
        double successChance,
        int timeSeconds
) {
    public static MachineRecipe fromConfig(String id, ConfigurationSection section) {
        Material input = Material.matchMaterial(section.getString("input.material", "AIR"));
        if (input == null) input = Material.AIR;
        Material secondary = Material.matchMaterial(section.getString("secondary.material", "AIR"));
        if (secondary == null) secondary = Material.AIR;
        Material fuel = Material.matchMaterial(section.getString("fuel.material", "AIR"));
        if (fuel == null) fuel = Material.AIR;
        Material output = Material.matchMaterial(section.getString("output.material", "AIR"));
        if (output == null) output = Material.AIR;
        return new MachineRecipe(
                id.toLowerCase(java.util.Locale.ROOT),
                section.getString("input.special-item", ""),
                input,
                Math.max(0, section.getInt("input.custom-model-data", 0)),
                Math.max(1, section.getInt("input.amount", 1)),
                section.getString("secondary.special-item", ""),
                secondary,
                Math.max(1, section.getInt("secondary.amount", 1)),
                section.getString("fuel.special-item", ""),
                fuel,
                Math.max(1, section.getInt("fuel.amount", 1)),
                section.getString("output.special-item", ""),
                output,
                Math.max(0, section.getInt("output.custom-model-data", 0)),
                Math.max(1, section.getInt("output.amount", 1)),
                Math.max(0D, Math.min(1D, section.getDouble("success-chance", 1.0D))),
                Math.max(1, section.getInt("time-seconds", 8))
        );
    }

    public boolean matchesInput(ItemStack item, SpecialItemRegistry registry) {
        return matches(item, inputSpecialItem, inputMaterial, inputCustomModelData, inputAmount, registry);
    }

    public boolean matchesSecondary(ItemStack item, SpecialItemRegistry registry) {
        if ((secondarySpecialItem == null || secondarySpecialItem.isBlank()) && secondaryMaterial == Material.AIR) return true;
        return matches(item, secondarySpecialItem, secondaryMaterial, 0, secondaryAmount, registry);
    }

    public boolean matchesFuel(ItemStack item, SpecialItemRegistry registry) {
        if ((fuelSpecialItem == null || fuelSpecialItem.isBlank()) && fuelMaterial == Material.AIR) return true;
        return matches(item, fuelSpecialItem, fuelMaterial, 0, fuelAmount, registry);
    }

    private boolean matches(ItemStack item, String special, Material material, int customModelData, int amount, SpecialItemRegistry registry) {
        if (item == null || item.getType().isAir() || item.getAmount() < amount) return false;
        if (special != null && !special.isBlank()) return registry.readSpecialItemId(item).map(special::equalsIgnoreCase).orElse(false);
        if (material != Material.AIR && item.getType() != material) return false;
        if (customModelData > 0) {
            var meta = item.getItemMeta();
            return meta != null && meta.hasCustomModelData() && meta.getCustomModelData() == customModelData;
        }
        return material != Material.AIR;
    }
}
