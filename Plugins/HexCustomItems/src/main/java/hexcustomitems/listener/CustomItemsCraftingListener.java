package hexcustomitems.listener;

import hexcustomitems.config.HexCustomItemsConfig;
import hexcustomitems.model.CustomItemDefinition;
import hexcustomitems.service.CustomItemRegistryService;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.Map;
import java.util.function.Supplier;

public final class CustomItemsCraftingListener implements Listener {

    private final Supplier<HexCustomItemsConfig> configSupplier;
    private final CustomItemRegistryService registryService;

    public CustomItemsCraftingListener(Supplier<HexCustomItemsConfig> configSupplier, CustomItemRegistryService registryService) {
        this.configSupplier = configSupplier;
        this.registryService = registryService;
    }

    @EventHandler
    public void onPrepare(PrepareItemCraftEvent event) {
        HexCustomItemsConfig.RecipeSpec recipe = matchingRecipe(event.getInventory());
        if (recipe == null) {
            return;
        }
        CustomItemDefinition result = registryService.findById(recipe.result());
        if (result == null) {
            return;
        }
        event.getInventory().setResult(registryService.createItem(result, recipe.amount()));
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (!(event.getInventory() instanceof CraftingInventory inventory)) {
            return;
        }
        HexCustomItemsConfig.RecipeSpec recipe = matchingRecipe(inventory);
        if (recipe == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getWhoClicked().getInventory().firstEmpty() < 0) {
            return;
        }
        CustomItemDefinition result = registryService.findById(recipe.result());
        if (result == null) {
            return;
        }
        ItemStack[] matrix = inventory.getMatrix();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = matrix[i];
            HexCustomItemsConfig.IngredientSpec ingredient = ingredientAt(recipe, i);
            if (stack == null || ingredient == null) {
                continue;
            }
            stack.setAmount(stack.getAmount() - ingredient.amount());
            matrix[i] = stack.getAmount() <= 0 ? null : stack;
        }
        inventory.setMatrix(matrix);
        event.getWhoClicked().getInventory().addItem(registryService.createItem(result, recipe.amount()));
        inventory.setResult(null);
    }

    private HexCustomItemsConfig.RecipeSpec matchingRecipe(CraftingInventory inventory) {
        HexCustomItemsConfig config = configSupplier.get();
        if (!config.recipes().enabled()) {
            return null;
        }
        for (HexCustomItemsConfig.RecipeSpec recipe : config.recipes().items().values()) {
            if (matches(inventory, recipe)) {
                return recipe;
            }
        }
        return null;
    }

    private boolean matches(CraftingInventory inventory, HexCustomItemsConfig.RecipeSpec recipe) {
        ItemStack[] matrix = inventory.getMatrix();
        for (int i = 0; i < 9; i++) {
            HexCustomItemsConfig.IngredientSpec ingredient = ingredientAt(recipe, i);
            ItemStack stack = matrix[i];
            if (ingredient == null) {
                if (stack != null && stack.getType() != Material.AIR) {
                    return false;
                }
                continue;
            }
            if (!matchesIngredient(stack, ingredient)) {
                return false;
            }
        }
        return true;
    }

    private HexCustomItemsConfig.IngredientSpec ingredientAt(HexCustomItemsConfig.RecipeSpec recipe, int slot) {
        int row = slot / 3;
        int col = slot % 3;
        char symbol = recipe.shape().get(row).charAt(col);
        if (symbol == ' ') {
            return null;
        }
        return recipe.ingredients().get(String.valueOf(symbol));
    }

    private boolean matchesIngredient(ItemStack stack, HexCustomItemsConfig.IngredientSpec ingredient) {
        if (stack == null || stack.getType() == Material.AIR || stack.getAmount() < ingredient.amount()) {
            return false;
        }
        if (ingredient.material() != null && ingredient.material() != Material.AIR && stack.getType() != ingredient.material()) {
            return false;
        }
        if (ingredient.customItemId() != null) {
            String id = registryService.resolveItemId(stack);
            if (!ingredient.customItemId().equals(id)) {
                return false;
            }
        }
        if (ingredient.enchantment() != null) {
            Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(ingredient.enchantment()));
            if (enchantment == null) {
                return false;
            }
            int level = stack.getEnchantmentLevel(enchantment);
            if (stack.getItemMeta() instanceof EnchantmentStorageMeta storage) {
                level = Math.max(level, storage.getStoredEnchantLevel(enchantment));
            }
            return level >= ingredient.enchantmentLevel();
        }
        return true;
    }
}
