package hex.minions.listener;

import hex.core.api.HexApi;
import hex.minions.menu.MinionMenu;
import hex.minions.menu.MinionMenuHolder;
import hex.minions.service.MinionService;
import hex.minions.service.OperationResult;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class MinionMenuListener implements Listener {
    private final Plugin plugin;
    private final HexApi hex;
    private final MinionService service;
    private final MinionMenu menu;

    public MinionMenuListener(Plugin plugin, HexApi hex, MinionService service, MinionMenu menu) {
        this.plugin = plugin;
        this.hex = hex;
        this.service = service;
        this.menu = menu;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MinionMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) return;
        UUID id = holder.minionId();
        CompletableFuture<OperationResult> future = switch (event.getSlot()) {
            case 45 -> service.move(player, id, player.getLocation());
            case 48 -> service.collect(player, id);
            case 50 -> service.upgrade(player, id);
            case 53 -> service.pickup(player, id);
            default -> null;
        };
        if (future == null) return;
        future.thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
            hex.ui().send(player, result.messageKey(), result.tokens());
            if (event.getSlot() == 53 && result.success()) {
                player.closeInventory();
            } else if (result.success()) {
                menu.open(player, id);
            }
        }));
    }
}

