package hexnpc.event;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class EventGuyMenuHolder implements InventoryHolder {

    private final String shopId;
    private final String eventCommand;
    private Inventory inventory;

    EventGuyMenuHolder(String shopId, String eventCommand) {
        this.shopId = Objects.requireNonNull(shopId, "shopId");
        this.eventCommand = Objects.requireNonNull(eventCommand, "eventCommand");
    }

    void bind(Inventory inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("EventGuyMenuHolder accessed before its inventory was bound");
        }
        return inventory;
    }

    public String shopId() {
        return shopId;
    }

    public String eventCommand() {
        return eventCommand;
    }
}
