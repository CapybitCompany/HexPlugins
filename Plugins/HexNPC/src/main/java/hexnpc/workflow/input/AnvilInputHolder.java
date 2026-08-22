package hexnpc.workflow.input;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class AnvilInputHolder implements InventoryHolder {
    private final UUID playerId;
    private Inventory inventory;

    public AnvilInputHolder(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID playerId() { return playerId; }
    void bind(Inventory inventory) { this.inventory = inventory; }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) throw new IllegalStateException("AnvilInputHolder accessed before bind");
        return inventory;
    }
}
