package hex.auctionbazaar.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * One single click/drag guard for ALL plugin GUIs. We match on
 * {@link GuiHolder} and cancel the event first, then dispatch the slot
 * action from the holder state.
 *
 * Important:
 *  - never read from the clicked ItemStack (anti-dupe)
 *  - shift-click, number key, drop, swap/offhand, double-click are blocked
 *  - drag events are blocked entirely
 */
public final class GuiInventoryListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryHolder topHolder = top.getHolder();
        if (!(topHolder instanceof GuiHolder holder)) {
            return;
        }
        // We handle any click that touches our top inventory OR could push
        // items into it (shift / number / swap).
        event.setCancelled(true);

        // Block dangerous actions outright.
        InventoryAction action = event.getAction();
        if (action == InventoryAction.COLLECT_TO_CURSOR
                || action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.HOTBAR_SWAP
                || action == InventoryAction.SWAP_WITH_CURSOR
                || event.getClick() == ClickType.NUMBER_KEY
                || event.getClick() == ClickType.SWAP_OFFHAND
                || event.getClick() == ClickType.DOUBLE_CLICK
                || event.getClick() == ClickType.DROP
                || event.getClick() == ClickType.CONTROL_DROP) {
            return;
        }

        // Ignore clicks outside the top inventory (e.g. player inventory).
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        GuiHolder.SlotAction slotAction = holder.actionAt(event.getSlot());
        if (slotAction == null) {
            return;
        }
        slotAction.run(new GuiHolder.ClickContext(
                holder,
                player,
                event.getSlot(),
                event.isShiftClick(),
                event.isRightClick()
        ));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof GuiHolder) {
            event.setCancelled(true);
        }
    }
}
