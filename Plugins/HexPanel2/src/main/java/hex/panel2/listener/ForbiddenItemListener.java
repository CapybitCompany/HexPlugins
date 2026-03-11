package hex.panel2.listener;

import hex.panel2.util.AccessControl;
import hex.panel2.util.ForbiddenItems;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public final class ForbiddenItemListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (AccessControl.isBypass(event.getPlayer())) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        if (ForbiddenItems.isForbidden(item.getType())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cTen przedmiot jest zabroniony.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (AccessControl.isBypass(event.getPlayer())) {
            return;
        }

        if (ForbiddenItems.isForbidden(event.getItemInHand().getType())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cTen przedmiot jest zabroniony.");
        }
    }
}
