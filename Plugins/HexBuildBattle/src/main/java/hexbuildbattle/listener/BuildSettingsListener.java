package hexbuildbattle.listener;

import hexbuildbattle.arena.Arena;
import hexbuildbattle.build.BuildSettingsHolder;
import hexbuildbattle.build.BuildSettingsService;
import hexbuildbattle.game.GameManager;
import hexbuildbattle.game.GameState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Optional;

public final class BuildSettingsListener implements Listener {

    private final GameManager gameManager;
    private final BuildSettingsService buildSettingsService;

    public BuildSettingsListener(GameManager gameManager, BuildSettingsService buildSettingsService) {
        this.gameManager = gameManager;
        this.buildSettingsService = buildSettingsService;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCompassUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!buildSettingsService.isCompass(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (gameManager.session().state() == GameState.BUILDING && gameManager.isActiveParticipant(player.getUniqueId())) {
            buildSettingsService.openRoot(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof BuildSettingsHolder)) {
            return;
        }

        Optional<Arena> arena = gameManager.assignedArena(player.getUniqueId());
        if (gameManager.session().state() != GameState.BUILDING || arena.isEmpty()) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        buildSettingsService.handleClick(player, event, arena.get());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof BuildSettingsHolder holder)) {
            return;
        }
        Optional<Arena> arena = gameManager.assignedArena(player.getUniqueId());
        if (gameManager.session().state() != GameState.BUILDING || arena.isEmpty()) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        if (holder.type() == hexbuildbattle.build.BuildSettingsMenuType.FLOOR) {
            buildSettingsService.handleDrag(player, event, arena.get());
        } else {
            event.setCancelled(true);
        }
    }
}
