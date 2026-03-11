package hex.panel2.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;

public final class CraftRestrictionListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        event.setCancelled(true);
        if (event.getWhoClicked() != null) {
            event.getWhoClicked().sendMessage("§cCrafting jest wylaczony.");
        }
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        event.getInventory().setResult(null);
    }
}
