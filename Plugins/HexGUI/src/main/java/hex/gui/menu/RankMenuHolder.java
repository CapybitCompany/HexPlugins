package hex.gui.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class RankMenuHolder implements InventoryHolder {
    @Override
    public Inventory getInventory() {
        throw new UnsupportedOperationException("RankMenuHolder jest wyłącznie markerem GUI.");
    }
}
