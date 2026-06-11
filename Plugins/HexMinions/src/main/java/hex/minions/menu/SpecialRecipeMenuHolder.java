package hex.minions.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public record SpecialRecipeMenuHolder(String recipeId, String returnTypeId) implements InventoryHolder {
    @Override public Inventory getInventory() { return null; }
}
