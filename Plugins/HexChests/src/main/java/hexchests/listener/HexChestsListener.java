package hexchests.listener;

import hexchests.ChestService;
import hexchests.gui.HexChestsGuiHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;

import java.util.Objects;

public final class HexChestsListener implements Listener {

    private final ChestService chestService;

    public HexChestsListener(ChestService chestService) {
        this.chestService = Objects.requireNonNull(chestService, "chestService");
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getClickedBlock() == null) {
            return;
        }
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        var chest = chestService.chestAt(event.getClickedBlock());
        if (chest.isEmpty()) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            chestService.handleLeftClick(player, chest.get());
        } else {
            chestService.handleRightClick(player, chest.get(), player.getInventory().getItemInMainHand());
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof HexChestsGuiHolder)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof HexChestsGuiHolder)) {
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof HexChestsGuiHolder holder)
                || holder.mode() != HexChestsGuiHolder.Mode.OPENING
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        chestService.reopenIfOpening(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        chestService.remove(event.getPlayer());
    }
}
