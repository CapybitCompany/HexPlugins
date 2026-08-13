package hexcustomitems.listener;

import hexcustomitems.service.CustomItemRegistryService;
import hexcustomitems.service.MessageService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

public final class CustomItemsDropListener implements Listener {

    private final CustomItemRegistryService registryService;
    private final MessageService messageService;

    public CustomItemsDropListener(
            CustomItemRegistryService registryService,
            MessageService messageService
    ) {
        this.registryService = Objects.requireNonNull(registryService, "registryService");
        this.messageService = Objects.requireNonNull(messageService, "messageService");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        String itemId = registryService.resolveItemId(item);
        if (itemId == null) {
            return;
        }

        var definition = registryService.findById(itemId);
        if (definition == null || !definition.dropProtection()) {
            return;
        }

        event.setCancelled(true);
        messageService.sendDropBlocked(event.getPlayer());
    }
}
