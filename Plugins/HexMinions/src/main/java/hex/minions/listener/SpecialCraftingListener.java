package hex.minions.listener;

import hex.core.api.HexApi;
import hex.minions.crafting.SpecialIngredient;
import hex.minions.crafting.SpecialItemDefinition;
import hex.minions.crafting.SpecialItemRegistry;
import hex.minions.crafting.SpecialRecipeDefinition;
import hex.minions.menu.EnchantedCraftingMenuHolder;
import hex.minions.menu.MinionMenu;
import hex.minions.service.MinionService;
import hex.towns.api.TownsApi;
import org.bukkit.Bukkit;
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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Optional;

public final class SpecialCraftingListener implements Listener {
    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final int OUTPUT_SLOT = 24;
    private static final int CRAFT_SLOT = 33;
    private final Plugin plugin;
    private final HexApi hex;
    private final TownsApi towns;
    private final MinionService service;
    private final MinionMenu menu;

    public SpecialCraftingListener(Plugin plugin, HexApi hex, TownsApi towns, MinionService service, MinionMenu menu) {
        this.plugin = plugin; this.hex = hex; this.towns = towns; this.service = service; this.menu = menu;
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
        if (towns.townAt(event.getBlockPlaced().getLocation()).filter(t -> towns.isMember(event.getPlayer().getUniqueId(), t.id())).isEmpty()) {
            event.setCancelled(true);
            hex.ui().send(event.getPlayer(), "minions.special-crafting.error.place-town");
            return;
        }
        String blockKind = def.blockKind().isBlank() ? specialId.get() : def.blockKind();
        Material placedMaterial = registry.station(blockKind).map(station -> station.block()).orElse(def.material());
        event.getBlockPlaced().setType(placedMaterial);
        if (event.getBlockPlaced().getBlockData() instanceof Directional directional) {
            BlockData data = event.getBlockPlaced().getBlockData();
            if (data instanceof Directional oriented) {
                oriented.setFacing(event.getPlayer().getFacing().getOppositeFace());
                event.getBlockPlaced().setBlockData(oriented, false);
            }
        }
        registry.markSpecialBlock(event.getBlockPlaced(), blockKind);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakSpecialBlock(BlockBreakEvent event) {
        SpecialItemRegistry registry = service.specialItems();
        Optional<String> station = registry.readSpecialBlockId(event.getBlock());
        if (station.isEmpty()) return;
        event.setDropItems(false);
        registry.stations().values().stream()
                .filter(s -> s.id().equalsIgnoreCase(station.get()))
                .findFirst()
                .flatMap(s -> registry.item(s.specialItemId()))
                .ifPresent(def -> event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), registry.createItem(def.id(), 1)));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUseSpecialStation(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        Block block = event.getClickedBlock();
        Optional<String> stationId = service.specialItems().readSpecialBlockId(block);
        if (stationId.isEmpty()) return;
        if (stationId.get().toUpperCase(java.util.Locale.ROOT).startsWith("CABLE_")) return;
        event.setCancelled(true);
        if (towns.townAt(block.getLocation()).filter(t -> towns.isMember(event.getPlayer().getUniqueId(), t.id())).isEmpty()) {
            hex.ui().send(event.getPlayer(), "minions.special-crafting.error.place-town");
            return;
        }
        menu.openEnchantedCrafting(event.getPlayer(), stationId.get());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVanillaCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory() instanceof CraftingInventory crafting)) return;
        SpecialRecipeDefinition matched = null;
        SpecialRecipeDefinition materialMatched = null;
        for (SpecialRecipeDefinition recipe : service.specialItems().recipes().values()) {
            if (!"VANILLA_CRAFTING_TABLE".equalsIgnoreCase(recipe.station())) continue;
            if (matchesMatrix(recipe, crafting.getMatrix())) { matched = recipe; break; }
            if (materialMatched == null && matchesMaterialMatrix(recipe, crafting.getMatrix())) materialMatched = recipe;
        }
        if (matched == null) {
            // Bukkit vanilla recipes can only enforce material shape, not custom amounts or PDC/special-item IDs.
            // If the material shape matches one of our recipes but amounts/special IDs do not, cancel it so players
            // cannot craft a minion or special item with one plain ingredient in each slot.
            if (materialMatched != null) {
                event.setCancelled(true);
                hex.ui().send(player, "minions.special-crafting.error.no-match");
            }
            return;
        }
        var town = towns.townIdOf(player.getUniqueId());
        if (town.isEmpty() || !service.hasRecipeUnlocks(town.get(), matched)) {
            event.setCancelled(true);
            hex.ui().send(player, town.isEmpty() ? "minions.special-crafting.error.no-town" : "minions.special-crafting.error.locked");
            return;
        }
        consumeMatrix(matched, crafting.getMatrix());
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
            inv.setItem(OUTPUT_SLOT, new ItemStack(Material.GRAY_STAINED_GLASS_PANE));
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
        if (town.isEmpty()) { hex.ui().send(player, "minions.special-crafting.error.no-town"); return; }
        if (!service.hasRecipeUnlocks(town.get(), matched)) { hex.ui().send(player, "minions.special-crafting.error.locked"); return; }

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
                    Material expected = ingredient.material();
                    if (expected == Material.AIR && ingredient.specialItemId() != null && !ingredient.specialItemId().isBlank()) {
                        expected = service.specialItems().item(ingredient.specialItemId()).map(SpecialItemDefinition::material).orElse(Material.AIR);
                    }
                    if (expected == Material.AIR || item == null || item.getType() != expected) return false;
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
        ItemStack prototype = service.recipeOutput(recipe);
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

    private void consumeMatrix(SpecialRecipeDefinition recipe, ItemStack[] matrix) {
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
            char ch = recipe.shape().get(row).charAt(col);
            SpecialIngredient ingredient = recipe.ingredients().get(ch);
            if (ingredient == null || ch == ' ') continue;
            ItemStack item = matrix[row * 3 + col];
            if (item == null) continue;
            item.setAmount(item.getAmount() - Math.max(0, ingredient.amount() - 1));
        }
    }

    private boolean isGrid(int slot) {
        for (int gridSlot : GRID) if (gridSlot == slot) return true;
        return false;
    }
}
