package hex.minions.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class RobotMenuHolder implements InventoryHolder {
    private final UUID viewerId;
    private final UUID robotId;

    public RobotMenuHolder(UUID viewerId, UUID robotId) {
        this.viewerId = viewerId;
        this.robotId = robotId;
    }

    public UUID viewerId() { return viewerId; }
    public UUID robotId() { return robotId; }

    @Override
    public Inventory getInventory() { return null; }
}
