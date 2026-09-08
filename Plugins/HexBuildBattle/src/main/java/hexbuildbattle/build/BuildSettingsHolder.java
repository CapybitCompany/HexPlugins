package hexbuildbattle.build;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class BuildSettingsHolder implements InventoryHolder {

    private final BuildSettingsMenuType type;
    private Inventory inventory;

    public BuildSettingsHolder(BuildSettingsMenuType type) {
        this.type = type;
    }

    public BuildSettingsMenuType type() {
        return type;
    }

    public void attach(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
