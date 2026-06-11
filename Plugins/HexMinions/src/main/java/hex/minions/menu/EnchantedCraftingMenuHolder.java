package hex.minions.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public record EnchantedCraftingMenuHolder(String stationId) implements InventoryHolder {
    @Override public Inventory getInventory() { return null; }
}
