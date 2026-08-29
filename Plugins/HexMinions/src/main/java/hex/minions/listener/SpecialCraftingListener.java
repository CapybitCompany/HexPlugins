package hex.minions.listener;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.minions.crafting.SpecialIngredient;
import hex.minions.crafting.SpecialItemDefinition;
import hex.minions.crafting.SpecialItemRegistry;
import hex.minions.crafting.SpecialRecipeDefinition;
import hex.minions.menu.EnchantedCraftingMenuHolder;
import hex.minions.menu.MinionMenu;
import hex.minions.machine.MachineDefinition;
import hex.minions.machine.MachineService;
import hex.minions.service.MinionService;
import hex.towns.api.TownPermission;
import hex.towns.api.TownsApi;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class SpecialCraftingListener implements Listener {
    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final int OUTPUT_SLOT = 24;
    private static final int CRAFT_SLOT = 33;
    private final Plugin plugin;
    private final HexApi hex;
    private final TownsApi towns;
    private final MinionService service;
    private final MinionMenu menu;
    private final MachineService machines;
    private final MachineListener machineListener;

    public SpecialCraftingListener(Plugin plugin, HexApi hex, TownsApi towns, MinionService service, MinionMenu menu, MachineService machines, MachineListener machineListener) {
        this.plugin = plugin; this.hex = hex; this.towns = towns; this.service = service; this.menu = menu; this.machines = machines; this.machineListener = machineListener;
    }

    private String townBoundKind(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        if (service.isTownBoundProgressionItem(item)) return "minion_asset";
        String specialId = service.specialItems().readSpecialItemId(item).orElse("");
        if (specialId.isBlank() || machines == null) return null;
        boolean machine = machines.registry().machines().values().stream()
                .anyMatch(def -> def.specialItemId() != null && def.specialItemId().equalsIgnoreCase(specialId));
        return machine ? "machine" : null;
    }

    private ItemStack bindRecipeOutput(Player player, ItemStack item) {
        if (player == null || item == null) return item;
        String kind = townBoundKind(item);
        if (kind == null) return item;
        UUID townId = towns.townIdOf(player.getUniqueId()).orElse(null);
        return townId == null ? item : towns.townBoundItems().bind(item, townId, kind);
    }

    private boolean validateMachineBinding(ItemStack item, UUID townId) {
        String kind = townBoundKind(item);
        return kind == null || towns.townBoundItems().validateOrAdopt(item, townId, kind);
    }

    private org.bukkit.block.BlockFace defaultFacingFor(String blockKind, Player player) {
        if ("MACERATOR".equalsIgnoreCase(blockKind)) return org.bukkit.block.BlockFace.UP;
        return player == null ? org.bukkit.block.BlockFace.NORTH : player.getFacing().getOppositeFace();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlaceSpecialItem(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        SpecialItemRegistry registry = service.specialItems();
        Optional<String> specialId = registry.readSpecialItemId(held);
        if (specialId.isEmpty()) return;
        SpecialItemDefinition def = registry.item(specialId.get()).orElse(null);
        if (def == null || !def.placeable()) return;
        String blockKind = def.blockKind().isBlank() ? specialId.get() : def.blockKind();
        String upperKind = blockKind.toUpperCase(java.util.Locale.ROOT);
        if (upperKind.startsWith("CABLE_") || "IRON_URANIUM_CHEST".equals(upperKind) || upperKind.startsWith("ROBOT_")) return;

        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        event.setCancelled(true);
        Block target = event.getClickedBlock().getRelative(event.getBlockFace());
        if (!target.getType().isAir()) { hex.ui().send(player, "minions.special-crafting.error.no-space"); return; }
        var targetTown = towns.townAt(target.getLocation()).orElse(null);
        if (targetTown == null || !towns.can(player.getUniqueId(), targetTown.id(), TownPermission.BUILD)) {
            hex.ui().send(player, "minions.special-crafting.error.place-town"); return;
        }
        MachineDefinition machine = machines == null ? null : machines.registry().byStation(blockKind).orElse(null);
        if (machine != null && !validateMachineBinding(held, targetTown.id())) {
            towns.audit(targetTown.id(), player.getUniqueId(), "TOWN_BOUND_DENIED", "kind=machine,action=place,machine=" + machine.id());
            player.sendMessage("§cTa maszyna należy do innego miasta.");
            return;
        }
        if (machine != null && !towns.can(player.getUniqueId(), targetTown.id(), TownPermission.MACHINE_USE)) {
            hex.ui().send(player, "minions.machine.error.not-town"); return;
        }
        if (machine != null && towns.isHeartProtected(target.getLocation())) {
            hex.ui().send(player, "minions.special-crafting.error.near-heart"); return;
        }
        if (machine != null) {
            Optional<String> limitError = machines.validateEnergyMachinePlacement(target, machine);
            if (limitError.isPresent()) {
                hex.ui().send(player, "minions.energy.limit-reached", UiTokens.of("error", limitError.get())); return;
            }
        }
        Material placedMaterial = physicalBlockFor(blockKind);
        if (placedMaterial == null || !placedMaterial.isBlock()) {
            plugin.getLogger().severe("Brak fizycznego bloku dla placeable custom itemu " + specialId.get() + " (block-kind=" + blockKind + ")"); return;
        }
        try {
            target.setType(placedMaterial, false);
            orient(target, blockKind, player);
            registry.markSpecialBlock(target, blockKind);
            if (machine != null && machineListener != null) {
                machineListener.handleMachinePlaced(target);
                towns.audit(targetTown.id(), player.getUniqueId(), "MACHINE_PLACE", "machine=" + machine.id() + ",location=" + target.getX() + "," + target.getY() + "," + target.getZ());
            }
        } catch (Throwable throwable) {
            if (machine != null && machineListener != null) {
                try { machineListener.rollbackPlacedMachine(target); } catch (Throwable ignored) { }
            }
            registry.unmarkSpecialBlock(target);
            target.setType(Material.AIR, false);
            plugin.getLogger().severe("Nie udało się postawić custom itemu " + specialId.get() + ": " + throwable.getMessage());
            return;
        }
        consumeOne(player, held);
        try { Bukkit.getScheduler().runTask(plugin, () -> registry.markSpecialBlock(target, blockKind)); }
        catch (Throwable throwable) { plugin.getLogger().warning("Nie udało się zaplanować ponownego zapisu PDC custom bloku " + specialId.get() + ": " + throwable.getMessage()); }
    }


    private Material physicalBlockFor(String blockKind) {
        if (blockKind == null || blockKind.isBlank()) return null;
        if (machines != null) {
            MachineDefinition machine = machines.registry().byStation(blockKind).orElse(null);
            if (machine != null && machine.baseBlock() != null && machine.baseBlock().isBlock()) return machine.baseBlock();
        }
        return service.specialItems().station(blockKind).map(station -> station.block()).filter(Material::isBlock).orElse(null);
    }

    private void orient(Block block, String blockKind, Player player) {
        BlockData data = block.getBlockData();
        if (!(data instanceof Directional directional)) return;
        directional.setFacing(defaultFacingFor(blockKind, player));
        block.setBlockData(directional, false);
    }

    private void consumeOne(Player player, ItemStack held) {
        if (player == null || held == null || player.getGameMode() == GameMode.CREATIVE) return;
        if (held.getAmount() <= 1) player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        else held.setAmount(held.getAmount() - 1);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceSpecialBlock(BlockPlaceEvent event) {
        SpecialItemRegistry registry = service.specialItems();
        Optional<String> specialId = registry.readSpecialItemId(event.getItemInHand());
        if (specialId.isEmpty()) return;
        SpecialItemDefinition def = registry.item(specialId.get()).orElse(null);
        if (def == null) return;
        if (!def.placeable()) {
            event.setCancelled(true);
            hex.ui().send(event.getPlayer(), "minions.special-crafting.error.not-placeable");
            return;
        }
        String blockKind = def.blockKind().isBlank() ? specialId.get() : def.blockKind();
        boolean robotBlock = blockKind.toUpperCase(java.util.Locale.ROOT).startsWith("ROBOT_");
        if (!robotBlock && towns.townAt(event.getBlockPlaced().getLocation()).filter(t -> towns.can(event.getPlayer().getUniqueId(), t.id(), TownPermission.BUILD)).isEmpty()) {
            event.setCancelled(true);
            hex.ui().send(event.getPlayer(), "minions.special-crafting.error.place-town");
            return;
        }
        MachineDefinition machine = machines == null ? null : machines.registry().byStation(blockKind).orElse(null);
        if (machine != null) {
            var town = towns.townAt(event.getBlockPlaced().getLocation()).orElse(null);
            if (town == null || !validateMachineBinding(event.getItemInHand(), town.id())) {
                event.setCancelled(true);
                if (town != null) towns.audit(town.id(), event.getPlayer().getUniqueId(), "TOWN_BOUND_DENIED", "kind=machine,action=place,machine=" + machine.id());
                event.getPlayer().sendMessage("§cTa maszyna należy do innego miasta.");
                return;
            }
            if (!towns.can(event.getPlayer().getUniqueId(), town.id(), TownPermission.MACHINE_USE)) {
                event.setCancelled(true);
                hex.ui().send(event.getPlayer(), "minions.machine.error.not-town");
                return;
            }
        }
        if (machine != null && towns.isHeartProtected(event.getBlockPlaced().getLocation())) {
            event.setCancelled(true);
            hex.ui().send(event.getPlayer(), "minions.special-crafting.error.near-heart");
            return;
        }
        if (machine != null) {
            Optional<String> limitError = machines.validateEnergyMachinePlacement(event.getBlockPlaced(), machine);
            if (limitError.isPresent()) {
                event.setCancelled(true);
                hex.ui().send(event.getPlayer(), "minions.energy.limit-reached", UiTokens.of("error", limitError.get()));
                return;
            }
        }
        Material placedMaterial = physicalBlockFor(blockKind);
        if (placedMaterial == null || !placedMaterial.isBlock()) {
            event.setCancelled(true);
            plugin.getLogger().severe("Brak fizycznego bloku dla placeable custom itemu " + specialId.get() + " (block-kind=" + blockKind + ")");
            return;
        }
        event.getBlockPlaced().setType(placedMaterial);
        if (event.getBlockPlaced().getBlockData() instanceof Directional) {
            BlockData data = event.getBlockPlaced().getBlockData();
            if (data instanceof Directional oriented) {
                oriented.setFacing(defaultFacingFor(blockKind, event.getPlayer()));
                event.getBlockPlaced().setBlockData(oriented, false);
            }
        }
        registry.markSpecialBlock(event.getBlockPlaced(), blockKind);
        if (machine != null) {
            var town = towns.townAt(event.getBlockPlaced().getLocation()).orElse(null);
            if (town != null) towns.audit(town.id(), event.getPlayer().getUniqueId(), "MACHINE_PLACE", "machine=" + machine.id() + ",location=" + event.getBlockPlaced().getX() + "," + event.getBlockPlaced().getY() + "," + event.getBlockPlaced().getZ());
        }
        // TileState stołu enchantingu jest w pełni gotowy dopiero po zakończeniu eventu.
        // Ponowne oznaczenie w następnym ticku zabezpiecza stół przed utratą PDC.
        Block placed = event.getBlockPlaced();
        Bukkit.getScheduler().runTask(plugin, () -> registry.markSpecialBlock(placed, blockKind));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreakSpecialBlock(BlockBreakEvent event) {
        SpecialItemRegistry registry = service.specialItems();
        Optional<String> station = registry.readSpecialBlockId(event.getBlock());
        if (station.isEmpty()) return;
        if (station.get().toUpperCase(java.util.Locale.ROOT).startsWith("ROBOT_")) return;
        if (registry.station(station.get()).isEmpty()) {
            // Stary blok wyłączonej stacji traci znacznik i od tej chwili zachowuje się jak vanilla.
            registry.unmarkSpecialBlock(event.getBlock());
            return;
        }
        MachineDefinition machine = machines == null ? null : machines.registry().byStation(station.get()).orElse(null);
        var town = towns.townAt(event.getBlock().getLocation()).orElse(null);
        if (machine != null && (town == null || !towns.can(event.getPlayer().getUniqueId(), town.id(), TownPermission.MACHINE_BREAK))) {
            event.setCancelled(true);
            hex.ui().send(event.getPlayer(), "minions.machine.error.not-town");
            return;
        }
        registry.unmarkSpecialBlock(event.getBlock());
        event.setDropItems(false);
        registry.stations().values().stream()
                .filter(s -> s.id().equalsIgnoreCase(station.get()))
                .findFirst()
                .flatMap(s -> registry.item(s.specialItemId()))
                .ifPresent(def -> {
                    ItemStack drop = registry.createItem(def.id(), 1);
                    if (machine != null && town != null) drop = towns.townBoundItems().bind(drop, town.id(), "machine");
                    event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), drop);
                });
        if (machine != null && town != null) towns.audit(town.id(), event.getPlayer().getUniqueId(), "MACHINE_BREAK", "machine=" + machine.id() + ",location=" + event.getBlock().getX() + "," + event.getBlock().getY() + "," + event.getBlock().getZ());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onUseSpecialStation(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) return;
        // Respect placement/protection handlers that already consumed this interaction.
        // This also prevents a minion/storage placement from opening the station GUI in the same click.
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        // Some dedicated placement listeners run at the same priority as this handler.
        // Do not cancel their event just because the clicked block happens to be a special station.
        SpecialItemRegistry registry = service.specialItems();
        Optional<String> heldSpecialId = registry.readSpecialItemId(held);
        if (heldSpecialId.flatMap(registry::item).map(SpecialItemDefinition::placeable).orElse(false)) return;
        if (service.storageChestIdForItem(held).isPresent()) return;

        Block block = event.getClickedBlock();
        Optional<String> stationId = service.specialItems().readSpecialBlockId(block);
        if (stationId.isEmpty()) return;
        if (service.specialItems().station(stationId.get()).isEmpty()) {
            service.specialItems().unmarkSpecialBlock(block);
            return;
        }
        String stationUpper = stationId.get().toUpperCase(java.util.Locale.ROOT);
        if (stationUpper.startsWith("CABLE_") || stationUpper.startsWith("ROBOT_")) return;
        // Maszyny mają własny listener i własne GUI. W szczególności użycie klucza
        // z brązu nie może zostać przechwycone jako otwarcie craftingu.
        if (machines != null && machines.machineAt(block) != null) return;
        // setCancelled alone is not sufficient on every Paper/Purpur interaction path.
        // Explicit DENY prevents the vanilla enchanting inventory from opening.
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        event.setCancelled(true);
        if (towns.townAt(block.getLocation()).filter(t -> towns.canActAsMember(event.getPlayer().getUniqueId(), t.id())).isEmpty()) {
            hex.ui().send(event.getPlayer(), "minions.special-crafting.error.place-town");
            return;
        }
        String id = stationId.get();
        // Vanilla próbuje otworzyć ekran enchantingu po evencie. Zamykamy go i
        // otwieramy customowe GUI w następnym ticku, kiedy vanilla już zakończyła obsługę.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            player.closeInventory();
            menu.openEnchantedCrafting(player, id);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareVanillaCraft(PrepareItemCraftEvent event) {
        Inventory rawInventory = event.getInventory();
        if (!(rawInventory instanceof CraftingInventory crafting)) return;
        ItemStack[] matrix = crafting.getMatrix();
        SpecialRecipeDefinition recipe = resolveCustomVanillaRecipe(event.getRecipe(), matrix);
        if (recipe == null) return;

        if (matrix.length != 9 || !matchesMatrix(recipe, matrix)) {
            crafting.setResult(null);
            return;
        }
        if (event.getView().getPlayer() instanceof Player player) {
            var town = towns.townIdOf(player.getUniqueId());
            if (!service.developerMode(player) && (town.isEmpty() || !service.hasRecipeUnlocks(town.get(), recipe))) {
                crafting.setResult(null);
                return;
            }
            crafting.setResult(bindRecipeOutput(player, service.recipeOutput(recipe)));
        } else {
            crafting.setResult(service.recipeOutput(recipe));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanillaCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory rawInventory = event.getInventory();
        if (!(rawInventory instanceof CraftingInventory crafting)) return;
        ItemStack[] matrix = crafting.getMatrix();
        SpecialRecipeDefinition matched = resolveCustomVanillaRecipe(event.getRecipe(), matrix);
        if (matched == null) return; // genuinely ordinary vanilla recipe

        // From this point the entire custom transaction is handled by HexMinions. Leaving even
        // one unit to vanilla causes Shift+Click to repeat the recipe using vanilla's 1-item-per-slot logic.
        event.setCancelled(true);
        if (matrix.length != 9 || !matchesMatrix(matched, matrix)) {
            hex.ui().send(player, "minions.special-crafting.error.no-match");
            return;
        }

        var town = towns.townIdOf(player.getUniqueId());
        if (!service.developerMode(player) && (town.isEmpty() || !service.hasRecipeUnlocks(town.get(), matched))) {
            hex.ui().send(player, town.isEmpty() ? "minions.special-crafting.error.no-town" : "minions.special-crafting.error.locked");
            return;
        }

        int crafts = event.isShiftClick() ? maxCrafts(matched, matrix) : 1;
        if (crafts <= 0) {
            hex.ui().send(player, "minions.special-crafting.error.no-match");
            return;
        }

        ItemStack prototype = bindRecipeOutput(player, service.recipeOutput(matched));
        ItemStack mergedCursor = event.isShiftClick() ? null : mergedCraftCursor(player.getItemOnCursor(), prototype);
        if (!event.isShiftClick() && mergedCursor == null) return;

        consumeMatrix(matched, crafting, crafts);
        if (event.isShiftClick()) giveRecipeOutput(player, matched, crafts);
        else player.setItemOnCursor(mergedCursor);
        Bukkit.getScheduler().runTask(plugin, player::updateInventory);
    }

    private SpecialRecipeDefinition resolveCustomVanillaRecipe(Recipe bukkitRecipe, ItemStack[] matrix) {
        SpecialRecipeDefinition keyed = customVanillaRecipe(bukkitRecipe);
        if (keyed != null && matrix != null && matrix.length == 9 && matchesMatrix(keyed, matrix)) return keyed;
        if (matrix == null || matrix.length != 9) return null;
        for (SpecialRecipeDefinition recipe : service.specialItems().recipes().values()) {
            if (recipe == null || !recipe.enabled() || !"VANILLA_CRAFTING_TABLE".equalsIgnoreCase(recipe.station())) continue;
            if (matchesMatrix(recipe, matrix)) return recipe;
        }
        return keyed;
    }

    private SpecialRecipeDefinition customVanillaRecipe(Recipe bukkitRecipe) {
        if (!(bukkitRecipe instanceof Keyed keyed)) return null;
        NamespacedKey key = keyed.getKey();
        String value = key.getKey();
        if (!value.startsWith("special_") || value.length() <= "special_".length()) return null;
        String recipeId = value.substring("special_".length());
        SpecialRecipeDefinition recipe = service.specialItems().recipe(recipeId).orElse(null);
        if (recipe == null || !"VANILLA_CRAFTING_TABLE".equalsIgnoreCase(recipe.station())) return null;
        // Do not intercept another plugin's recipe that happens to use the same key suffix.
        return new NamespacedKey(plugin, "special_" + recipe.id()).equals(key) ? recipe : null;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof EnchantedCraftingMenuHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(top)) {
            int slot = event.getSlot();
            if (slot == OUTPUT_SLOT) {
                event.setCancelled(true);
                craftCustom(player, holder.stationId(), top, event.isShiftClick());
                return;
            }
            if (slot == CRAFT_SLOT) {
                // Dawny zielony przycisk craftingu jest już tylko tłem menu.
                // Crafting odbywa się po kliknięciu w slot wyniku, jak w vanilla craftingu.
                event.setCancelled(true);
                return;
            }
            if (slot >= 45 || (!isGrid(slot) && event.getClickedInventory().equals(top))) {
                event.setCancelled(true);
                return;
            }
        }
        schedulePreviewRefresh(holder.stationId(), top);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof EnchantedCraftingMenuHolder holder)) return;
        boolean touchesTop = false;
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < top.getSize()) {
                touchesTop = true;
                if (!isGrid(rawSlot)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
        if (touchesTop) schedulePreviewRefresh(holder.stationId(), top);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getInventory();
        if (!(top.getHolder() instanceof EnchantedCraftingMenuHolder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        for (int slot : GRID) {
            ItemStack item = top.getItem(slot);
            if (item != null && !item.getType().isAir()) player.getInventory().addItem(item).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
    }


    private void schedulePreviewRefresh(String station, Inventory inv) {
        Bukkit.getScheduler().runTask(plugin, () -> refreshPreview(station, inv));
    }

    private void refreshPreview(String station, Inventory inv) {
        SpecialRecipeDefinition matched = null;
        for (SpecialRecipeDefinition recipe : service.specialItems().recipes().values()) {
            if (!recipe.station().equalsIgnoreCase(station)) continue;
            if (matchesInventory(recipe, inv)) { matched = recipe; break; }
        }
        if (matched == null) {
            ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta fillerMeta = filler.getItemMeta();
            if (fillerMeta != null) {
                fillerMeta.setHideTooltip(true);
                filler.setItemMeta(fillerMeta);
            }
            inv.setItem(OUTPUT_SLOT, filler);
        } else {
            inv.setItem(OUTPUT_SLOT, service.recipeOutput(matched));
        }
    }

    private void craftCustom(Player player, String station, Inventory inv, boolean craftAll) {
        SpecialRecipeDefinition matched = null;
        for (SpecialRecipeDefinition recipe : service.specialItems().recipes().values()) {
            if (!recipe.station().equalsIgnoreCase(station)) continue;
            if (matchesInventory(recipe, inv)) { matched = recipe; break; }
        }
        if (matched == null) { hex.ui().send(player, "minions.special-crafting.error.no-match"); return; }
        var town = towns.townIdOf(player.getUniqueId());
        if (!service.developerMode(player)) {
            if (town.isEmpty()) { hex.ui().send(player, "minions.special-crafting.error.no-town"); return; }
            if (!service.hasRecipeUnlocks(town.get(), matched)) { hex.ui().send(player, "minions.special-crafting.error.locked"); return; }
        }

        int crafts = craftAll ? maxCrafts(matched, inv) : 1;
        if (crafts <= 0) { hex.ui().send(player, "minions.special-crafting.error.no-match"); return; }
        consumeInventory(matched, inv, crafts);
        giveRecipeOutput(player, matched, crafts);
        refreshPreview(station, inv);
    }

    private boolean matchesInventory(SpecialRecipeDefinition recipe, Inventory inv) {
        for (int row = 0; row < 3; row++) {
            String line = recipe.shape().get(row);
            for (int col = 0; col < 3; col++) {
                char ch = line.charAt(col);
                SpecialIngredient ingredient = recipe.ingredients().get(ch);
                ItemStack item = inv.getItem(GRID[row * 3 + col]);
                if (ch == ' ' || ingredient == null) { if (item != null && !item.getType().isAir()) return false; }
                else if (!ingredient.matches(item, service.specialItems())) return false;
            }
        }
        return true;
    }

    private boolean matchesMatrix(SpecialRecipeDefinition recipe, ItemStack[] matrix) {
        for (int row = 0; row < 3; row++) {
            String line = recipe.shape().get(row);
            for (int col = 0; col < 3; col++) {
                char ch = line.charAt(col);
                SpecialIngredient ingredient = recipe.ingredients().get(ch);
                ItemStack item = matrix[row * 3 + col];
                if (ch == ' ' || ingredient == null) { if (item != null && !item.getType().isAir()) return false; }
                else if (!ingredient.matches(item, service.specialItems())) return false;
            }
        }
        return true;
    }


    private boolean matchesMaterialMatrix(SpecialRecipeDefinition recipe, ItemStack[] matrix) {
        for (int row = 0; row < 3; row++) {
            String line = recipe.shape().get(row);
            for (int col = 0; col < 3; col++) {
                char ch = line.charAt(col);
                SpecialIngredient ingredient = recipe.ingredients().get(ch);
                ItemStack item = matrix[row * 3 + col];
                if (ch == ' ' || ingredient == null) {
                    if (item != null && !item.getType().isAir()) return false;
                } else {
                    if (ingredient.specialItemId() != null && !ingredient.specialItemId().isBlank()) {
                        Material expected = service.specialItems().item(ingredient.specialItemId()).map(SpecialItemDefinition::material).orElse(Material.AIR);
                        if (expected == Material.AIR || item == null || item.getType() != expected) return false;
                    } else if (ingredient.material() == Material.AIR || item == null || !ingredient.matchesMaterial(item.getType())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private int maxCrafts(SpecialRecipeDefinition recipe, Inventory inv) {
        int max = Integer.MAX_VALUE;
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
            char ch = recipe.shape().get(row).charAt(col);
            SpecialIngredient ingredient = recipe.ingredients().get(ch);
            if (ingredient == null || ch == ' ') continue;
            ItemStack item = inv.getItem(GRID[row * 3 + col]);
            if (!ingredient.matches(item, service.specialItems())) return 0;
            max = Math.min(max, item.getAmount() / Math.max(1, ingredient.amount()));
        }
        return max == Integer.MAX_VALUE ? 0 : Math.max(0, max);
    }

    private void consumeInventory(SpecialRecipeDefinition recipe, Inventory inv, int crafts) {
        int multiplier = Math.max(1, crafts);
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
            char ch = recipe.shape().get(row).charAt(col);
            SpecialIngredient ingredient = recipe.ingredients().get(ch);
            if (ingredient == null || ch == ' ') continue;
            ItemStack item = inv.getItem(GRID[row * 3 + col]);
            if (item == null) continue;
            item.setAmount(item.getAmount() - ingredient.amount() * multiplier);
            if (item.getAmount() <= 0) inv.setItem(GRID[row * 3 + col], null);
        }
    }

    private void giveRecipeOutput(Player player, SpecialRecipeDefinition recipe, int crafts) {
        ItemStack prototype = bindRecipeOutput(player, service.recipeOutput(recipe));
        if (prototype == null || prototype.getType().isAir()) return;
        int total = Math.max(1, prototype.getAmount()) * Math.max(1, crafts);
        int maxStack = Math.max(1, prototype.getMaxStackSize());
        while (total > 0) {
            int chunk = Math.min(maxStack, total);
            ItemStack stack = prototype.clone();
            stack.setAmount(chunk);
            player.getInventory().addItem(stack).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            total -= chunk;
        }
    }

    private int maxCrafts(SpecialRecipeDefinition recipe, ItemStack[] matrix) {
        if (matrix == null || matrix.length != 9) return 0;
        int max = Integer.MAX_VALUE;
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
            char ch = recipe.shape().get(row).charAt(col);
            SpecialIngredient ingredient = recipe.ingredients().get(ch);
            if (ingredient == null || ch == ' ') continue;
            ItemStack item = matrix[row * 3 + col];
            if (!ingredient.matches(item, service.specialItems())) return 0;
            max = Math.min(max, item.getAmount() / Math.max(1, ingredient.amount()));
        }
        return max == Integer.MAX_VALUE ? 0 : Math.max(0, max);
    }

    private void consumeMatrix(SpecialRecipeDefinition recipe, CraftingInventory crafting, int crafts) {
        ItemStack[] matrix = crafting.getMatrix();
        int multiplier = Math.max(1, crafts);
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
            char ch = recipe.shape().get(row).charAt(col);
            SpecialIngredient ingredient = recipe.ingredients().get(ch);
            if (ingredient == null || ch == ' ') continue;
            int slot = row * 3 + col;
            ItemStack item = matrix[slot];
            if (item == null) continue;
            int remaining = item.getAmount() - ingredient.amount() * multiplier;
            if (remaining <= 0) matrix[slot] = null;
            else {
                ItemStack reduced = item.clone();
                reduced.setAmount(remaining);
                matrix[slot] = reduced;
            }
        }
        crafting.setMatrix(matrix);
    }

    private ItemStack mergedCraftCursor(ItemStack cursor, ItemStack result) {
        if (result == null || result.getType().isAir()) return null;
        if (cursor == null || cursor.getType().isAir()) return result.clone();
        if (!cursor.isSimilar(result)) return null;
        int combined = cursor.getAmount() + result.getAmount();
        if (combined > cursor.getMaxStackSize()) return null;
        ItemStack merged = cursor.clone();
        merged.setAmount(combined);
        return merged;
    }

    private boolean isGrid(int slot) {
        for (int gridSlot : GRID) if (gridSlot == slot) return true;
        return false;
    }
}
