package hex.minions.machine;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

public record MachineDefinition(
        String id,
        boolean enabled,
        String displayName,
        Material baseBlock,
        String specialItemId,
        String stationId,
        String type,
        int inputSlot,
        int secondarySlot,
        int fuelSlot,
        int arrowSlot,
        int outputSlot,
        List<Integer> upgradeSlots,
        List<MachineRecipe> recipes,
        int defaultStorageSlots,
        int defaultOutputStackSize,
        MachineEnergyDefinition energy
) {
    public static MachineDefinition fromConfig(String id, ConfigurationSection section) {
        Material base = Material.matchMaterial(section.getString("base-block", "FURNACE"));
        if (base == null) base = Material.FURNACE;
        List<MachineRecipe> recipes = new ArrayList<>();
        ConfigurationSection recipeRoot = section.getConfigurationSection("recipes");
        if (recipeRoot != null) {
            for (String key : recipeRoot.getKeys(false)) {
                ConfigurationSection recipeSection = recipeRoot.getConfigurationSection(key);
                if (recipeSection != null) recipes.add(MachineRecipe.fromConfig(key, recipeSection));
            }
        }
        return new MachineDefinition(
                id.toLowerCase(java.util.Locale.ROOT),
                section.getBoolean("enabled", true),
                section.getString("display-name", id),
                base,
                section.getString("special-item", id),
                section.getString("station-id", id.toUpperCase(java.util.Locale.ROOT)),
                section.getString("type", "GENERIC"),
                section.getInt("menu.input-slot", 20),
                section.getInt("menu.secondary-slot", 21),
                section.getInt("menu.fuel-slot", 22),
                section.getInt("menu.arrow-slot", 23),
                section.getInt("menu.output-slot", 24),
                section.getIntegerList("menu.upgrade-slots"),
                recipes,
                Math.max(0, section.getInt("storage.slots", 0)),
                Math.max(1, section.getInt("storage.output-stack-size", 64)),
                MachineEnergyDefinition.fromConfig(section.getConfigurationSection("energy"))
        );
    }

    public boolean hasSecondarySlot() {
        return recipes.stream().anyMatch(recipe -> !recipe.secondarySpecialItem().isBlank() || recipe.secondaryMaterial() != Material.AIR);
    }

    public boolean hasRecipeFuelSlot() {
        return recipes.stream().anyMatch(recipe -> !recipe.fuelSpecialItem().isBlank() || recipe.fuelMaterial() != Material.AIR);
    }
}
