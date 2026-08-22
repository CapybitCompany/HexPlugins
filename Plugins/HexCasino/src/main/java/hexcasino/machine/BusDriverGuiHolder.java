package hexcasino.machine;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class BusDriverGuiHolder implements InventoryHolder {

    private final UUID playerId;
    private final String machineId;
    private Inventory inventory;

    public BusDriverGuiHolder(UUID playerId, String machineId) {
        this.playerId = playerId;
        this.machineId = machineId;
    }

    public UUID playerId() {
        return playerId;
    }

    public String machineId() {
        return machineId;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
