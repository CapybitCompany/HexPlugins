package hexchests.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class HexChestsGuiHolder implements InventoryHolder {

    public enum Mode {
        PREVIEW,
        OPENING
    }

    private final UUID playerId;
    private final String chestId;
    private final Mode mode;
    private Inventory inventory;

    public HexChestsGuiHolder(UUID playerId, String chestId, Mode mode) {
        this.playerId = playerId;
        this.chestId = chestId;
        this.mode = mode;
    }

    public UUID playerId() {
        return playerId;
    }

    public String chestId() {
        return chestId;
    }

    public Mode mode() {
        return mode;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
