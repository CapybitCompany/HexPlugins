package hex.panel2.listener;

import hex.panel2.util.AccessControl;
import hex.panel2.util.ForbiddenItems;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffectType;

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
        if (isForbidden(item)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cTen przedmiot jest zabroniony.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (AccessControl.isBypass(event.getPlayer())) {
            return;
        }

        if (isForbidden(event.getItemInHand())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cTen przedmiot jest zabroniony.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (AccessControl.isBypass(event.getPlayer())) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }
        if (isForbidden(item)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cTen przedmiot jest zabroniony.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPearlTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            return;
        }
        if (AccessControl.isBypass(event.getPlayer())) {
            return;
        }

        event.setCancelled(true);
        event.getPlayer().sendMessage("§cTen przedmiot jest zabroniony.");
    }

    private boolean isForbidden(ItemStack item) {
        Material type = item.getType();
        if (ForbiddenItems.isForbidden(type)) {
            return true;
        }
        return isBadOmenPotion(item);
    }

    private boolean isBadOmenPotion(ItemStack item) {
        Material type = item.getType();
        if (type != Material.POTION && type != Material.SPLASH_POTION && type != Material.LINGERING_POTION) {
            return false;
        }
        if (!(item.getItemMeta() instanceof PotionMeta potionMeta)) {
            return false;
        }

        if (potionMeta.hasCustomEffect(PotionEffectType.BAD_OMEN)) {
            return true;
        }

        var basePotionType = potionMeta.getBasePotionType();
        return basePotionType != null && basePotionType.name().contains("OMEN");
    }
}
