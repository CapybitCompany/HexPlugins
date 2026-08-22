package hex.minions.menu;

import org.bukkit.block.BlockFace;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public record MachineStorageMenuHolder(String machineId, String blockKey, BlockFace face, int slots) implements InventoryHolder {
    public MachineStorageMenuHolder {
        machineId = machineId == null ? "" : machineId;
        blockKey = blockKey == null ? "" : blockKey;
        face = face == null ? BlockFace.UP : face;
        slots = Math.max(0, Math.min(27, slots));
    }

    @Override
    public @NotNull Inventory getInventory() {
        throw new UnsupportedOperationException("Holder only");
    }
}
