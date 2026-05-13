package mysterybox.listener;

import mysterybox.gui.MysteryBoxOpeningService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Objects;

public final class MysteryBoxGuiListener implements Listener {

    private final MysteryBoxOpeningService openingService;

    public MysteryBoxGuiListener(MysteryBoxOpeningService openingService) {
        this.openingService = Objects.requireNonNull(openingService, "openingService");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        openingService.handleInventoryClick(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        openingService.handleInventoryDrag(event);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent event) {
        openingService.cancelAndRefund(event.getPlayer());
    }
}
