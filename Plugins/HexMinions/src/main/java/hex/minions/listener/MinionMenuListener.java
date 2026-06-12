package hex.minions.listener;

import hex.core.api.HexApi;
import hex.minions.menu.MinionMenu;
import hex.minions.menu.MinionMenuHolder;
import hex.minions.menu.MinionWikiHolder;
import hex.minions.menu.MinionStorageChestMenuHolder;
import hex.minions.menu.SpecialRecipeMenuHolder;
import hex.minions.service.MinionService;
import hex.minions.service.OperationResult;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class MinionMenuListener implements Listener {
    private final Plugin plugin;
    private final HexApi hex;
    private final MinionService service;
    private final MinionMenu menu;
    private final BukkitTask refreshTask;

    public MinionMenuListener(Plugin plugin, HexApi hex, MinionService service, MinionMenu menu) {
        this.plugin = plugin;
        this.hex = hex;
        this.service = service;
        this.menu = menu;
        this.refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOpenMinionMenus, 10L, 10L);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof MinionMenuHolder holder) {
            handleMinionMenu(event, top, holder);
            return;
        }
        if (top.getHolder() instanceof MinionWikiHolder holder) {
            handleWikiMenu(event, top, holder);
            return;
        }
        if (top.getHolder() instanceof MinionStorageChestMenuHolder holder) {
            handleStorageChestMenu(event, top, holder);
            return;
        }
        if (top.getHolder() instanceof SpecialRecipeMenuHolder holder) {
            handleRecipeMenu(event, holder);
        }
    }

    private void handleMinionMenu(InventoryClickEvent event, Inventory top, MinionMenuHolder holder) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        UUID id = holder.minionId();
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(top)) {
            int slot = event.getSlot();
            if (menu.isStorageSlot(slot)) {
                event.setCancelled(true);
                int visibleStorageSlot = visibleStorageSlotIndex(slot);
                service.withdrawStorageSlot(player, id, visibleStorageSlot).thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!result.success()) {
                        hex.ui().send(player, result.messageKey(), result.tokens());
                    }
                    menu.refreshMinionInventory(player, id, top);
                }));
                return;
            }
            if (menu.isAddonSlot(slot)) {
                ItemStack cursor = event.getCursor();
                if (cursor != null && !cursor.getType().isAir() && !menu.isAllowedInAddonSlot(id, cursor)) {
                    event.setCancelled(true);
                    hex.ui().send(player, "minions.error.invalid-menu-item");
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    service.saveMinionMenu(id, top);
                    menu.refreshMinionInventory(player, id, top);
                });
                return;
            }
            if (slot == MinionMenu.STORAGE_CHEST_SLOT) {
                ItemStack cursor = event.getCursor();
                if (cursor != null && !cursor.getType().isAir()) {
                    event.setCancelled(true);
                    OperationResult result = service.installStorageChestFromMenu(player, id, cursor);
                    hex.ui().send(player, result.messageKey(), result.tokens());
                    menu.open(player, id);
                    return;
                }
                event.setCancelled(true);
                menu.openStorageChest(player, id);
                return;
            }
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) return;
        if (event.getSlot() == 47) {
            menu.openWiki(player);
            return;
        }
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

    private void handleStorageChestMenu(InventoryClickEvent event, Inventory top, MinionStorageChestMenuHolder holder) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(top) && event.getSlot() == 49) {
            event.setCancelled(true);
            menu.saveStorageChestMenu(holder.minionId(), top);
            menu.open(player, holder.minionId());
            return;
        }
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(top) && event.getSlot() >= 45) {
            event.setCancelled(true);
        }
    }

    private void handleRecipeMenu(InventoryClickEvent event, SpecialRecipeMenuHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;
        if (event.getSlot() == 45) {
            if (holder.returnTypeId() == null || holder.returnTypeId().isBlank()) menu.openWiki(player);
            else menu.openWikiType(player, holder.returnTypeId());
        }
    }


    private int visibleStorageSlotIndex(int slot) {
        for (int i = 0; i < MinionMenu.STORAGE_SLOTS.length; i++) {
            if (MinionMenu.STORAGE_SLOTS[i] == slot) return i;
        }
        return -1;
    }

    private void refreshOpenMinionMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof MinionMenuHolder holder) {
                menu.refreshMinionInventory(player, holder.minionId(), top);
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getInventory();
        if (top.getHolder() instanceof MinionMenuHolder holder) {
            menu.saveMinionMenu(holder.minionId(), top);
            return;
        }
        if (top.getHolder() instanceof MinionStorageChestMenuHolder holder) {
            menu.saveStorageChestMenu(holder.minionId(), top);
        }
    }

    private void handleWikiMenu(InventoryClickEvent event, Inventory top, MinionWikiHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) return;
        if (holder.index()) {
            if (event.getSlot() == 45) {
                player.closeInventory();
                return;
            }
            String typeId = menu.wikiTypeAtSlot(event.getSlot());
            if (!typeId.isBlank()) menu.openWikiType(player, typeId);
            return;
        }
        if (holder.machineIndex()) {
            if (event.getSlot() == 45) {
                menu.openWikiType(player, holder.typeId());
                return;
            }
            String machineId = menu.wikiMachineAtSlot(holder.typeId(), event.getSlot());
            if (!machineId.isBlank()) menu.openWikiMachine(player, holder.typeId(), machineId);
            return;
        }
        if (holder.machine()) {
            if (event.getSlot() == 45) {
                menu.openWikiMachines(player, holder.typeId());
                return;
            }
            String processId = menu.wikiMachineProcessAtSlot(holder.machineId(), event.getSlot());
            if (!processId.isBlank()) menu.openWikiMachineRecipe(player, holder.typeId(), holder.machineId(), processId);
            return;
        }
        if (holder.machineRecipe()) {
            if (event.getSlot() == 45) {
                menu.openWikiMachine(player, holder.typeId(), holder.machineId());
            }
            return;
        }
        if (event.getSlot() == 45) {
            menu.openWiki(player);
            return;
        }
        if (event.getSlot() == 43) {
            menu.openWikiMachines(player, holder.typeId());
            return;
        }
        String recipeId = menu.wikiRecipeAtSlot(holder.typeId(), event.getSlot());
        if (!recipeId.isBlank()) menu.openRecipe(player, recipeId, holder.typeId());
    }
}


