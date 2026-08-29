package hex.towns.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class TownCoopDecisionMenuHolder implements InventoryHolder {
    private final Action action;
    private final UUID targetId;
    private final String targetName;
    private final UUID townId;
    private final boolean adminOverride;

    public TownCoopDecisionMenuHolder(Action action, UUID targetId, String targetName) {
        this(action, targetId, targetName, null, false);
    }

    public TownCoopDecisionMenuHolder(Action action, UUID targetId, String targetName, UUID townId, boolean adminOverride) {
        this.action = action;
        this.targetId = targetId;
        this.targetName = targetName == null || targetName.isBlank() ? targetId.toString().substring(0, 8) : targetName;
        this.townId = townId;
        this.adminOverride = adminOverride;
    }

    public Action action() { return action; }
    public UUID targetId() { return targetId; }
    public String targetName() { return targetName; }
    public UUID townId() { return townId; }
    public boolean adminOverride() { return adminOverride; }

    @Override
    public Inventory getInventory() { return null; }

    public enum Action {
        REQUEST_DECISION,
        MEMBER_KICK,
        MEMBER_PERMISSIONS
    }
}
