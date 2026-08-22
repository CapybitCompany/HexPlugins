package hex.towns.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class TownCoopDecisionMenuHolder implements InventoryHolder {
    private final Action action;
    private final UUID targetId;
    private final String targetName;

    public TownCoopDecisionMenuHolder(Action action, UUID targetId, String targetName) {
        this.action = action;
        this.targetId = targetId;
        this.targetName = targetName == null || targetName.isBlank() ? targetId.toString().substring(0, 8) : targetName;
    }

    public Action action() { return action; }
    public UUID targetId() { return targetId; }
    public String targetName() { return targetName; }

    @Override
    public Inventory getInventory() { return null; }

    public enum Action {
        REQUEST_DECISION,
        MEMBER_KICK,
        MEMBER_PERMISSIONS
    }
}
