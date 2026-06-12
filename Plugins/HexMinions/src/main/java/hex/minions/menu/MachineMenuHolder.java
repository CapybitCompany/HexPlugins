package hex.minions.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class MachineMenuHolder implements InventoryHolder {
    private final String machineId;
    private final String blockKey;

    public MachineMenuHolder(String machineId, String blockKey) {
        this.machineId = machineId;
        this.blockKey = blockKey;
    }

    public String machineId() { return machineId; }
    public String blockKey() { return blockKey; }

    @Override public @NotNull Inventory getInventory() { throw new UnsupportedOperationException(); }
}
