package hex.minions.machine;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record MachineDefinition(
        String id,
        boolean enabled,
        String displayName,
        Material baseBlock,
        String specialItemId,
        String stationId,
        String type,
        int inputSlot,
        List<Integer> inputSlots,
        int secondarySlot,
        int fuelSlot,
        int arrowSlot,
        int outputSlot,
        List<Integer> outputSlots,
        int inputStorageExtensionSlot,
        int outputStorageExtensionSlot,
        int fuelStorageExtensionSlot,
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
                if (recipeSection != null) {
                    MachineRecipe recipe = MachineRecipe.fromConfig(key, recipeSection);
                    if (!isTemporarilyHiddenMachineRecipe(recipe)) recipes.add(recipe);
                }
            }
        }
        if ("macerator".equalsIgnoreCase(id)) recipes = simplifiedMaceratorRecipes(recipes);
        return new MachineDefinition(
                id.toLowerCase(java.util.Locale.ROOT),
                section.getBoolean("enabled", true),
                section.getString("display-name", id),
                base,
                section.getString("special-item", id),
                section.getString("station-id", id.toUpperCase(java.util.Locale.ROOT)),
                section.getString("type", "GENERIC"),
                firstInputSlot(section),
                inputSlots(section),
                section.getInt("menu.secondary-slot", 21),
                section.getInt("menu.fuel-slot", 22),
                section.getInt("menu.arrow-slot", 23),
                firstOutputSlot(section),
                outputSlots(section),
                normalizedSlot(section.getInt("menu.input-storage-extension-slot", -1)),
                normalizedSlot(section.getInt("menu.output-storage-extension-slot", -1)),
                normalizedSlot(section.getInt("menu.fuel-storage-extension-slot", -1)),
                machineUpgradeSlots(section),
                recipes,
                Math.max(0, section.getInt("storage.slots", 0)),
                Math.max(1, section.getInt("storage.output-stack-size", 64)),
                MachineEnergyDefinition.fromConfig(section.getConfigurationSection("energy"))
        );
    }

    private static boolean isTemporarilyHiddenMachineRecipe(MachineRecipe recipe) {
        if (recipe == null) return true;
        return isHiddenSpecial(recipe.inputSpecialItem())
                || isHiddenSpecial(recipe.secondarySpecialItem())
                || isHiddenSpecial(recipe.fuelSpecialItem())
                || isHiddenSpecial(recipe.outputSpecialItem());
    }

    private static boolean isHiddenSpecial(String id) {
        if (id == null || id.isBlank()) return false;
        String normalized = id.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("bronze_ingot")
                || normalized.equals("bronze_dust")
                || normalized.equals("refined_wheat")
                || normalized.equals("refined_meat")
                || normalized.equals("biofuel")
                || normalized.equals("living_block")
                || normalized.equals("compost");
    }

    private static List<MachineRecipe> simplifiedMaceratorRecipes(List<MachineRecipe> configured) {
        Map<String, MachineRecipe> recipes = new LinkedHashMap<>();
        Set<String> replaced = Set.of(
                "coal_ore_to_dust", "redstone_ore_to_dust",
                "lapis_ore_to_dust", "deepslate_lapis_ore_to_dust",
                "redstone_block_to_redstone", "lapis_block_to_lapis",
                // Stare pliki machines.yml mogły nadal zawierać receptury bloków magazynowych.
                // Bloki 9x surowca nie są wejściem Maceratora i muszą być filtrowane także runtime.
                "iron_block_to_dust", "copper_block_to_dust",
                "gold_block_to_dust", "diamond_block_to_dust"
        );
        for (MachineRecipe recipe : configured) {
            if (recipe == null || replaced.contains(recipe.id())) continue;
            recipes.put(recipe.id(), recipe);
        }

        putMaceratorSpecial(recipes, "deepslate_iron_ore_to_dust", Material.DEEPSLATE_IRON_ORE, "iron_dust", 2);
        putMaceratorSpecial(recipes, "deepslate_gold_ore_to_dust", Material.DEEPSLATE_GOLD_ORE, "gold_dust", 2);
        putMaceratorSpecial(recipes, "deepslate_copper_ore_to_dust", Material.DEEPSLATE_COPPER_ORE, "copper_dust", 2);
        putMaceratorSpecial(recipes, "deepslate_diamond_ore_to_dust", Material.DEEPSLATE_DIAMOND_ORE, "diamond_dust", 2);

        recipes.put("lapis_ore_to_lapis", simpleMaceratorRecipe(
                "lapis_ore_to_lapis", Material.LAPIS_ORE, "", Material.LAPIS_LAZULI, 10));
        recipes.put("deepslate_lapis_ore_to_lapis", simpleMaceratorRecipe(
                "deepslate_lapis_ore_to_lapis", Material.DEEPSLATE_LAPIS_ORE, "", Material.LAPIS_LAZULI, 10));
        recipes.put("redstone_ore_to_redstone", simpleMaceratorRecipe(
                "redstone_ore_to_redstone", Material.REDSTONE_ORE, "", Material.REDSTONE, 6));
        recipes.put("deepslate_redstone_ore_to_redstone", simpleMaceratorRecipe(
                "deepslate_redstone_ore_to_redstone", Material.DEEPSLATE_REDSTONE_ORE, "", Material.REDSTONE, 6));
        return List.copyOf(recipes.values());
    }

    private static void putMaceratorSpecial(Map<String, MachineRecipe> recipes, String id, Material input, String outputSpecial, int amount) {
        recipes.put(id, simpleMaceratorRecipe(id, input, outputSpecial, Material.AIR, amount));
    }

    private static MachineRecipe simpleMaceratorRecipe(String id, Material input, String outputSpecial, Material outputMaterial, int amount) {
        return new MachineRecipe(
                id, "", input, 0, 1,
                "", Material.AIR, 1,
                "", Material.AIR, 1,
                outputSpecial, outputMaterial, 0, amount,
                1.0D, 8
        );
    }

    private static int normalizedSlot(int slot) {
        return slot >= 0 && slot < 54 ? slot : -1;
    }

    private static List<Integer> machineUpgradeSlots(ConfigurationSection section) {
        // Ulepszenia urządzeń elektrycznych są obecnie ukryte.
        // Zwracamy pustą listę także dla starych plików machines.yml pozostawionych na serwerze.
        return List.of();
    }

    private static int firstInputSlot(ConfigurationSection section) {
        List<Integer> slots = inputSlots(section);
        return slots.isEmpty() ? section.getInt("menu.input-slot", 20) : slots.get(0);
    }

    private static List<Integer> inputSlots(ConfigurationSection section) {
        List<Integer> slots = section.getIntegerList("menu.input-slots");
        if (slots == null || slots.isEmpty()) return List.of(section.getInt("menu.input-slot", 20));
        return List.copyOf(slots.stream().filter(slot -> slot >= 0 && slot < 54).distinct().toList());
    }

    private static int firstOutputSlot(ConfigurationSection section) {
        List<Integer> slots = outputSlots(section);
        return slots.isEmpty() ? section.getInt("menu.output-slot", 24) : slots.get(0);
    }

    private static List<Integer> outputSlots(ConfigurationSection section) {
        List<Integer> slots = section.getIntegerList("menu.output-slots");
        if (slots == null || slots.isEmpty()) return List.of(section.getInt("menu.output-slot", 24));
        return List.copyOf(slots.stream().filter(slot -> slot >= 0 && slot < 54).distinct().toList());
    }

    public boolean hasSecondarySlot() {
        if (inputSlots.size() > 1) return false;
        return recipes.stream().anyMatch(recipe -> !recipe.secondarySpecialItem().isBlank() || recipe.secondaryMaterial() != Material.AIR);
    }

    public boolean hasRecipeFuelSlot() {
        return recipes.stream().anyMatch(recipe -> !recipe.fuelSpecialItem().isBlank() || recipe.fuelMaterial() != Material.AIR);
    }
}
