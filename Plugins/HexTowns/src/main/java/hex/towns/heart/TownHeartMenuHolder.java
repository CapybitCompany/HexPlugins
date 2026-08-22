package hex.towns.heart;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class TownHeartMenuHolder implements InventoryHolder {
    public enum Kind { CONFIRM, NAME_ANVIL, BASE }
    private final Kind kind;
    private final UUID playerId;
    private final UUID townId;

    public TownHeartMenuHolder(Kind kind, UUID playerId, UUID townId) {
        this.kind = kind;
        this.playerId = playerId;
        this.townId = townId;
    }

    public Kind kind() { return kind; }
    public UUID playerId() { return playerId; }
    public UUID townId() { return townId; }

    @Override
    public @NotNull Inventory getInventory() {
        throw new UnsupportedOperationException("Virtual holder only.");
    }
}
