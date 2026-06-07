package hex.minions.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class MinionMenuHolder implements InventoryHolder {
    private final UUID minionId;

    public MinionMenuHolder(UUID minionId) {
        this.minionId = minionId;
    }

    public UUID minionId() {
        return minionId;
    }

    @Override
    public @NotNull Inventory getInventory() {
        throw new UnsupportedOperationException("Holder only");
    }
}

