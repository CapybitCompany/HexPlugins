package hexcustomitems.listener;

import hexcustomitems.service.CustomItemRegistryService;
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

    private final CustomItemRegistryService registryService;
    private final CustomItemUseService useService;

    public CustomItemsInteractListener(
            CustomItemRegistryService registryService,
            CustomItemUseService useService
    ) {
        this.registryService = Objects.requireNonNull(registryService, "registryService");
        this.useService = Objects.requireNonNull(useService, "useService");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
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

        String itemId = registryService.resolveItemId(item);
        if (itemId == null) {
            return;
        }

        event.setCancelled(true);
        useService.tryUseItem(event.getPlayer(), event.getHand(), item, action, event.getClickedBlock());
    }
}
