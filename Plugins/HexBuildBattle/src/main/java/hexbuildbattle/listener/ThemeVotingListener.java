package hexbuildbattle.listener;

import hexbuildbattle.theme.ThemeVotingService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class ThemeVotingListener implements Listener {

    private final ThemeVotingService themeVotingService;

    public ThemeVotingListener(ThemeVotingService themeVotingService) {
        this.themeVotingService = themeVotingService;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            themeVotingService.handleClick(player, event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof hexbuildbattle.theme.ThemeVotingHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            themeVotingService.handleClose(player, event);
        }
    }
}
