package hex.minions.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class MinionStorageChestMenuHolder implements InventoryHolder {
    private final UUID minionId;

    public MinionStorageChestMenuHolder(UUID minionId) {
        this.minionId = minionId;
    }

    public UUID minionId() {
        return minionId;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
