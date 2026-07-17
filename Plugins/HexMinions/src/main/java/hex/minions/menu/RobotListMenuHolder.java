package hex.minions.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class RobotListMenuHolder implements InventoryHolder {
    private final UUID viewerId;

    public RobotListMenuHolder(UUID viewerId) {
        this.viewerId = viewerId;
    }

    public UUID viewerId() { return viewerId; }

    @Override
    public Inventory getInventory() { return null; }
}
