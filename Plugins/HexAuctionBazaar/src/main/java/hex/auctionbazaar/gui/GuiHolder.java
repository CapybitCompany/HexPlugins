package hex.auctionbazaar.gui;

import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Unique marker for all plugin GUIs. Click/drag listeners check
 * {@code inventory.getHolder() instanceof GuiHolder}, cancel the event
 * immediately, and then delegate to the holder state - never to the
 * clicked ItemStack object.
 */
public final class GuiHolder implements InventoryHolder {

    public enum Kind {
        AUCTION_BROWSE,
        AUCTION_MY_LISTINGS,
        AUCTION_CLAIMS,
        AUCTION_CONFIRM_BUY,
        BAZAAR_MAIN,
        BAZAAR_ITEM,
        BAZAAR_QUANTITY
    }

    private final Kind kind;
    private final Map<Integer, SlotAction> slots = new HashMap<>();
    private final Map<String, Object> state = new HashMap<>();
    private Inventory inventory;

    public GuiHolder(Kind kind) {
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public Kind kind() {
        return kind;
    }

    public void bindInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("inventory not bound yet");
        }
        return inventory;
    }

    public void setSlotAction(int slot, SlotAction action) {
        if (action == null) {
            slots.remove(slot);
        } else {
            slots.put(slot, action);
        }
    }

    public SlotAction actionAt(int slot) {
        return slots.get(slot);
    }

    public void putState(String key, Object value) {
        state.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T state(String key) {
        return (T) state.get(key);
    }

    public boolean isPluginType(InventoryType type) {
        return type == InventoryType.CHEST;
    }

    /** Click action bound to a slot. Always read from holder state, never from the click. */
    @FunctionalInterface
    public interface SlotAction {
        void run(ClickContext ctx);
    }

    public record ClickContext(
            GuiHolder holder,
            org.bukkit.entity.Player player,
            int slot,
            boolean isShift,
            boolean isRight
    ) {
    }
}
