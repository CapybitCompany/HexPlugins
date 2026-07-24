package hexcustomitems.listener;

import hexcustomitems.service.CustomItemUseService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

public final class CustomItemsInteractListener implements Listener {

    private final CustomItemUseService useService;

    public CustomItemsInteractListener(CustomItemUseService useService) {
        this.useService = Objects.requireNonNull(useService, "useService");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) {
            return;
        }

        // Nur abbrechen, wenn wirklich ein aktuell verwaltetes, nutzbares Custom-Item verarbeitet wurde.
        // Stale PDC-Items (ID nicht mehr in der Registry) oder Items ohne gültige Aktionen liefern false
        // und blockieren den Rechtsklick daher nicht.
        if (useService.tryUseItem(event.getPlayer(), event.getHand(), item)) {
            event.setCancelled(true);
        }
    }
}
