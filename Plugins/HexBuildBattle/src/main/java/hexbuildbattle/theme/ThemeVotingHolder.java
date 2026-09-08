package hexbuildbattle.theme;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class ThemeVotingHolder implements InventoryHolder {

    private Inventory inventory;

    public void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
