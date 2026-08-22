package hex.minions.listener;

import hex.core.api.HexApi;
import hex.minions.menu.MinionMenu;
import hex.minions.menu.MinionMenuHolder;
import hex.minions.menu.MinionWikiHolder;
import hex.minions.menu.MinionStorageChestMenuHolder;
import hex.minions.menu.SpecialRecipeMenuHolder;
import hex.minions.service.MinionService;
import hex.minions.service.OperationResult;
import hex.towns.api.TownPermission;
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

        // Pozwalamy normalnie podnieść item z własnego EQ na kursor, żeby można było
        // włożyć booster/update/skrzynkę storage do odpowiedniego slotu w menu miniona.
        // Shift-click z dolnego EQ blokujemy, bo Bukkit próbowałby automatycznie przerzucać
        // itemy do dekoracyjnych/placeholderowych slotów GUI.
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
            if (event.isShiftClick()) event.setCancelled(true);
            return;
        }

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
            event.setCancelled(true);
            handleAddonSlotClick(player, top, id, slot, event.getCursor(), event.isRightClick());
            return;
        }
        if (slot == MinionMenu.STORAGE_CHEST_SLOT) {
            ItemStack cursor = event.getCursor();
            event.setCancelled(true);
            if (event.isRightClick() && (cursor == null || cursor.getType().isAir())) {
                OperationResult result = service.uninstallStorageChestFromMenu(player, id);
                hex.ui().send(player, result.messageKey(), result.tokens());
                menu.open(player, id);
                return;
            }
            if (cursor != null && !cursor.getType().isAir()) {
                boolean uraniumChest = service.specialItems().readSpecialItemId(cursor)
                        .map("iron_uranium_chest"::equalsIgnoreCase).orElse(false);
                if (uraniumChest) {
                    player.sendMessage("§cSkrzynka uranowa służy wyłącznie do przechowywania wzbogaconego uranu i nie może rozszerzać magazynu miniona.");
                    menu.refreshMinionInventory(player, id, top);
                    return;
                }
                OperationResult result = service.installStorageChestFromMenu(player, id, cursor);
                hex.ui().send(player, result.messageKey(), result.tokens());
                menu.open(player, id);
                return;
            }
            menu.openStorageChest(player, id);
            return;
        }

        event.setCancelled(true);
        if (event.getSlot() == MinionMenu.ELECTRONICS_WIKI_SLOT) {
            menu.openElectronicsWiki(player);
            return;
        }
        if (event.getSlot() == MinionMenu.MINION_WIKI_SLOT) {
            menu.openWiki(player);
            return;
        }
        CompletableFuture<OperationResult> future = switch (event.getSlot()) {
            case MinionMenu.MOVE_SLOT -> service.move(player, id, player.getLocation());
            case MinionMenu.COLLECT_SLOT -> service.collect(player, id);
            case MinionMenu.UPGRADE_SLOT -> service.upgrade(player, id);
            case MinionMenu.PICKUP_SLOT -> service.pickup(player, id);
            default -> null;
        };
        if (future == null) return;
        future.thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
            hex.ui().send(player, result.messageKey(), result.tokens());
            if (event.getSlot() == MinionMenu.PICKUP_SLOT && result.success()) {
                player.closeInventory();
            } else if (result.success()) {
                menu.open(player, id);
            }
        }));
    }

    private void handleStorageChestMenu(InventoryClickEvent event, Inventory top, MinionStorageChestMenuHolder holder) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(top)) {
            if (event.getSlot() == top.getSize() - 5) {
                event.setCancelled(true);
                menu.saveStorageChestMenu(holder.minionId(), top);
                menu.open(player, holder.minionId());
                return;
            }
            if (!menu.isStorageChestMenuSlot(holder.minionId(), top.getSize(), event.getSlot())) {
                event.setCancelled(true);
            }
            return;
        }
        if (event.isShiftClick()) event.setCancelled(true);
    }

    private void handleRecipeMenu(InventoryClickEvent event, SpecialRecipeMenuHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;
        if (tryCopyWikiItemForTesting(event, player, event.getView().getTopInventory())) return;
        if (menu.isUsesHolder(holder)) {
            if (event.getSlot() == 45) {
                if (MinionWikiHolder.ELECTRONICS_RETURN_ID.equals(holder.returnTypeId())) menu.openElectronicsWiki(player);
                else if (holder.returnTypeId() == null || holder.returnTypeId().isBlank()) menu.openWiki(player);
                else menu.openWikiType(player, holder.returnTypeId());
                return;
            }
            String entry = menu.wikiUsageEntryAtSlot(holder, event.getSlot());
            if (!entry.isBlank()) menu.openWikiUsageEntry(player, entry, holder.returnTypeId());
            return;
        }
        if (event.getSlot() == 53) {
            menu.toggleWikiViewMode(player);
            menu.openRecipe(player, holder.recipeId(), holder.returnTypeId());
            return;
        }
        if (event.getSlot() == 45) {
            if (MinionWikiHolder.ELECTRONICS_RETURN_ID.equals(holder.returnTypeId())) menu.openElectronicsWiki(player);
            else if (holder.returnTypeId() == null || holder.returnTypeId().isBlank()) menu.openWiki(player);
            else menu.openWikiType(player, holder.returnTypeId());
            return;
        }
        ItemStack clicked = event.getView().getTopInventory().getItem(event.getSlot());
        if (!menu.pluginItemId(clicked).isBlank()) {
            if (event.isRightClick()) menu.openWikiItemUsages(player, clicked, holder.returnTypeId());
            else menu.openWikiCraftingForItem(player, clicked, holder.returnTypeId());
        }
    }



    private void handleAddonSlotClick(Player player, Inventory top, UUID minionId, int slot, ItemStack cursor, boolean rightClick) {
        String slotId = addonSlotId(slot);
        ItemStack current = service.addonItem(minionId, slotId);
        boolean cursorHasItem = cursor != null && !cursor.getType().isAir();
        boolean currentIsRealAddon = current != null && !current.getType().isAir();

        if (rightClick && !cursorHasItem) {
            if (currentIsRealAddon) {
                if (!canRemoveMinionAsset(player, minionId)) {
                    hex.ui().send(player, "minions.error.not-member");
                    menu.refreshMinionInventory(player, minionId, top);
                    return;
                }
                ItemStack removed = service.removeAddonItem(minionId, slotId);
                if (removed != null && !removed.getType().isAir()) {
                    player.getInventory().addItem(removed).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
                }
            }
            menu.refreshMinionInventory(player, minionId, top);
            return;
        }

        if (cursorHasItem) {
            if (!menu.isAllowedInAddonSlot(minionId, slot, cursor)) {
                hex.ui().send(player, "minions.error.invalid-menu-item");
                menu.refreshMinionInventory(player, minionId, top);
                return;
            }
            if (currentIsRealAddon) {
                if (!canRemoveMinionAsset(player, minionId)) {
                    hex.ui().send(player, "minions.error.not-member");
                    menu.refreshMinionInventory(player, minionId, top);
                    return;
                }
                ItemStack removed = service.removeAddonItem(minionId, slotId);
                if (removed != null && !removed.getType().isAir()) {
                    player.getInventory().addItem(removed).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
                }
            }
            top.setItem(slot, cursor.clone());
            player.setItemOnCursor(null);
            service.saveMinionMenu(minionId, top);
            menu.refreshMinionInventory(player, minionId, top);
            return;
        }

        if (currentIsRealAddon) {
            if (!canRemoveMinionAsset(player, minionId)) {
                hex.ui().send(player, "minions.error.not-member");
                menu.refreshMinionInventory(player, minionId, top);
                return;
            }
            ItemStack removed = service.removeAddonItem(minionId, slotId);
            if (removed != null && !removed.getType().isAir()) {
                player.setItemOnCursor(removed);
            }
            menu.refreshMinionInventory(player, minionId, top);
            return;
        }

        // Placeholdery slotów boostera/ulepszenia/storage są tylko informacyjne.
        // Nie pozwalamy podnieść ich kursorem ani zapisać jako realnego addonu.
        menu.refreshMinionInventory(player, minionId, top);
    }


    private boolean canRemoveMinionAsset(Player player, UUID minionId) {
        return service.townUuidOfMinion(minionId)
                .map(townId -> service.towns().can(player.getUniqueId(), townId, TownPermission.MINION_PICKUP))
                .orElse(false);
    }


    private String addonSlotId(int slot) {
        if (slot == MinionMenu.ADDON_SLOT_1) return "addon_1";
        if (slot == MinionMenu.ADDON_SLOT_2) return "addon_2";
        return "";
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
            } else if (top.getHolder() instanceof MinionWikiHolder holder && holder.machineRecipe()) {
                menu.refreshWikiMachineRecipe(top, holder.machineId(), holder.machineRecipeId());
            } else if (top.getHolder() instanceof SpecialRecipeMenuHolder holder && !menu.isUsesHolder(holder)) {
                menu.refreshSpecialRecipe(top, holder.recipeId());
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

    private void handlePluginWikiClick(InventoryClickEvent event, Player player, ItemStack clicked, String returnTypeId) {
        if (menu.pluginItemId(clicked).isBlank()) return;
        if (event.isRightClick()) menu.openWikiItemUsages(player, clicked, returnTypeId);
        else menu.openWikiCraftingForItem(player, clicked, returnTypeId);
    }

    private boolean tryCopyWikiItemForTesting(InventoryClickEvent event, Player player, Inventory top) {
        if (!service.wikiTestMode() || !event.isShiftClick()) return false;
        ItemStack item = top.getItem(event.getSlot());
        if (item == null || item.getType().isAir()) return false;
        switch (item.getType()) {
            case BLACK_STAINED_GLASS_PANE, GRAY_STAINED_GLASS_PANE, RED_STAINED_GLASS_PANE, ORANGE_STAINED_GLASS_PANE, YELLOW_STAINED_GLASS_PANE, LIME_STAINED_GLASS_PANE, GREEN_STAINED_GLASS_PANE, PURPLE_STAINED_GLASS_PANE, ARROW, BARRIER -> { return false; }
            default -> { }
        }
        ItemStack copy = item.clone();
        player.getInventory().addItem(copy).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        hex.ui().send(player, "minions.wiki.test-copy.success");
        return true;
    }

    private void handleWikiMenu(InventoryClickEvent event, Inventory top, MinionWikiHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) return;
        if (tryCopyWikiItemForTesting(event, player, top)) return;
        if (holder.index()) {
            if (event.getSlot() == 53) {
                menu.toggleWikiViewMode(player);
                menu.openWikiPage(player, holder.page());
                return;
            }
            if (event.getSlot() == 45) {
                player.closeInventory();
                return;
            }
            if (event.getSlot() == 48 && holder.page() > 0) {
                menu.openWikiPage(player, holder.page() - 1);
                return;
            }
            if (event.getSlot() == 50 && menu.wikiHasPage(holder.page() + 1)) {
                menu.openWikiPage(player, holder.page() + 1);
                return;
            }
            String typeId = menu.wikiTypeAtSlot(player, event.getSlot(), holder.page());
            if (!typeId.isBlank()) menu.openWikiType(player, typeId);
            return;
        }
        if (holder.machineIndex()) {
            if (event.getSlot() == 53) {
                menu.toggleWikiViewMode(player);
                if (holder.electronicsReturn()) menu.openElectronicsWiki(player);
                else menu.openWikiMachines(player, holder.typeId());
                return;
            }
            if (event.getSlot() == 45) {
                if (holder.electronicsReturn()) player.closeInventory();
                else menu.openWikiType(player, holder.typeId());
                return;
            }
            if (holder.electronicsReturn()) {
                ItemStack clicked = top.getItem(event.getSlot());
                if (event.isLeftClick()) {
                    if (menu.openWikiCraftingForItem(player, clicked, MinionWikiHolder.ELECTRONICS_RETURN_ID)) return;
                    menu.openElectronicsEntryAtSlot(player, event.getSlot());
                    return;
                }
                if (event.isRightClick()) {
                    if (menu.isElectronicsMachineAtSlot(player, event.getSlot())) {
                        menu.openElectronicsEntryAtSlot(player, event.getSlot());
                    } else if (!menu.pluginItemId(clicked).isBlank()) {
                        menu.openWikiItemUsages(player, clicked, MinionWikiHolder.ELECTRONICS_RETURN_ID);
                    }
                    return;
                }
                return;
            }
            if (event.isRightClick() && !menu.pluginItemId(top.getItem(event.getSlot())).isBlank()) {
                menu.openWikiItemUsages(player, top.getItem(event.getSlot()), holder.typeId());
                return;
            }
            String machineId = menu.wikiMachineAtSlot(player, holder.typeId(), event.getSlot());
            if (!machineId.isBlank()) menu.openWikiMachine(player, holder.typeId(), machineId);
            return;
        }
        if (holder.machine()) {
            if (event.getSlot() == 53) {
                menu.toggleWikiViewMode(player);
                menu.openWikiMachine(player, holder.typeId(), holder.machineId());
                return;
            }
            if (event.getSlot() == 45) {
                if (holder.electronicsReturn()) menu.openElectronicsWiki(player);
                else menu.openWikiType(player, holder.typeId());
                return;
            }
            if (event.getSlot() == 48 && holder.page() > 0) {
                menu.openWikiMachinePage(player, holder.typeId(), holder.machineId(), holder.page() - 1);
                return;
            }
            if (event.getSlot() == 50 && menu.wikiMachineHasPage(holder.machineId(), holder.page() + 1)) {
                menu.openWikiMachinePage(player, holder.typeId(), holder.machineId(), holder.page() + 1);
                return;
            }
            String processId = menu.wikiMachineProcessAtSlot(holder.machineId(), event.getSlot(), holder.page());
            if (!processId.isBlank()) {
                ItemStack clicked = top.getItem(event.getSlot());
                if (event.isRightClick() && !menu.pluginItemId(clicked).isBlank()) menu.openWikiItemUsages(player, clicked, holder.typeId());
                else menu.openWikiMachineRecipe(player, holder.typeId(), holder.machineId(), processId);
                return;
            }
            handlePluginWikiClick(event, player, top.getItem(event.getSlot()), holder.typeId());
            return;
        }
        if (holder.machineRecipe()) {
            if (event.getSlot() == 53) {
                menu.toggleWikiViewMode(player);
                menu.openWikiMachineRecipe(player, holder.typeId(), holder.machineId(), holder.machineRecipeId());
                return;
            }
            if (event.getSlot() == 45) {
                menu.openWikiMachine(player, holder.typeId(), holder.machineId());
                return;
            }
            handlePluginWikiClick(event, player, top.getItem(event.getSlot()), holder.typeId());
            return;
        }
        if (event.getSlot() == 53) {
            menu.toggleWikiViewMode(player);
            menu.openWikiTypePage(player, holder.typeId(), holder.page());
            return;
        }
        if (event.getSlot() == 45) {
            menu.openWiki(player);
            return;
        }
        if (event.getSlot() == 48 && holder.page() > 0) {
            menu.openWikiTypePage(player, holder.typeId(), holder.page() - 1);
            return;
        }
        if (event.getSlot() == 50 && menu.wikiSpecialHasPage(player, holder.typeId(), holder.page() + 1)) {
            menu.openWikiTypePage(player, holder.typeId(), holder.page() + 1);
            return;
        }
        String recipeId = menu.wikiRecipeAtSlot(player, holder.typeId(), event.getSlot(), holder.page());
        if (!recipeId.isBlank()) {
            ItemStack clicked = top.getItem(event.getSlot());
            if (event.isRightClick() && !menu.pluginItemId(clicked).isBlank()) menu.openWikiItemUsages(player, clicked, holder.typeId());
            else menu.openWikiSpecialEntry(player, recipeId, holder.typeId());
            return;
        }
        handlePluginWikiClick(event, player, top.getItem(event.getSlot()), holder.typeId());
    }
}


