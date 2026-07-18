package hexdailyrewards.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

import java.util.Objects;

public final class DailyRewardsInventoryListener implements Listener {

    private final DailyRewardsGui gui;

    public DailyRewardsInventoryListener(DailyRewardsGui gui) {
        this.gui = Objects.requireNonNull(gui, "gui");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof DailyRewardsGuiHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= top.getSize()) {
            return;
        }
        gui.handleClick(player, event.getRawSlot());
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof DailyRewardsGuiHolder)) {
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }
}

