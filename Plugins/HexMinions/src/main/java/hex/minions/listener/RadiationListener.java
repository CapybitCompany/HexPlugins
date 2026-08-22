package hex.minions.listener;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.minions.menu.MinionMenu;
import hex.minions.menu.MinionMenuHolder;
import hex.minions.service.MinionItemFactory;
import hex.minions.service.MinionService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.IdentityHashMap;
import java.util.Map;

public final class RadiationListener implements Listener {
    private static final int[] URANIUM_STORAGE_SLOTS = {11, 12, 13, 14, 15};
    private final Plugin plugin;
    private final HexApi hex;
    private final MinionService service;
    private final MinionItemFactory itemFactory;
    private final Map<InventoryClickEvent, ItemStack> maskedStorageClicks = new IdentityHashMap<>();

    public RadiationListener(Plugin plugin, HexApi hex, MinionService service) {
        this.plugin = plugin;
        this.hex = hex;
        this.service = service;
        this.itemFactory = new MinionItemFactory(plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::damagePlayers, 20L, 20L);
    }

    private void damagePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            int enriched = countEnriched(player.getInventory());
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top != null && top.getHolder() instanceof Chest && !isUraniumChest(top)) {
                enriched += countEnriched(top);
            }
            if (enriched <= 0) continue;
            double reduction = radiationReduction(player);
            double damage = (2.0D * enriched) * Math.max(0.0D, 1.0D - reduction);
            if (damage > 0.0D) player.damage(damage);
            hex.ui().sendActionBar(player, "minions.radiation.actionbar", UiTokens.of("amount", String.valueOf(enriched)).put("protection", String.valueOf(Math.round(reduction * 100.0D))));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDropEnrichedUranium(PlayerDropItemEvent event) {
        if (!isEnriched(event.getItemDrop().getItemStack())) return;
        Player player = event.getPlayer();
        boolean inOwnTown = service.towns().townAt(player.getLocation())
                .filter(town -> service.towns().canActAsMember(player.getUniqueId(), town.id()))
                .isPresent();
        if (inOwnTown) return;
        event.setCancelled(true);
        player.sendMessage("§cWzbogacony uran możesz wyrzucić wyłącznie na terenie własnego miasta.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlaceUraniumChestItem(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer(); ItemStack item = player.getInventory().getItemInMainHand();
        if (!isUraniumChestItem(item)) return;
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY); event.setUseItemInHand(org.bukkit.event.Event.Result.DENY); event.setCancelled(true);
        Block target = event.getClickedBlock().getRelative(event.getBlockFace());
        if (!target.getType().isAir()) { player.sendMessage("§cW tym miejscu nie można postawić skrzynki uranowej."); return; }
        if (service.towns().townAt(target.getLocation()).filter(town -> service.towns().canActAsMember(player.getUniqueId(), town.id())).isEmpty()) {
            hex.ui().send(player, "minions.special-crafting.error.place-town"); return;
        }
        if (service.adjacentMinion(target.getLocation()).isPresent()) { player.sendMessage("§cSkrzynka uranowa nie może być używana jako magazyn miniona."); return; }
        if (service.touchesOtherChest(target.getLocation())) { player.sendMessage("§cSkrzynka uranowa nie może łączyć się z inną skrzynią."); return; }
        if (service.specialItems().readSpecialBlockId(target.getRelative(BlockFace.UP)).isPresent() || service.specialItems().readSpecialBlockId(target.getRelative(BlockFace.DOWN)).isPresent()) {
            player.sendMessage("§cSkrzynka uranowa nie może być używana jako górny ani dolny magazyn maszyny."); return;
        }
        Material previous = target.getType();
        try {
            target.setType(Material.CHEST, false);
            itemFactory.markStorageChestBlock(target, "iron_uranium");
            if (!itemFactory.readStorageChestBlockId(target).map("iron_uranium"::equalsIgnoreCase).orElse(false)) {
                throw new IllegalStateException("uranium chest PDC was not persisted");
            }
        } catch (Throwable throwable) {
            itemFactory.unmarkStorageChestBlock(target);
            target.setType(previous, false);
            plugin.getLogger().warning("Placement skrzynki uranowej został wycofany: " + throwable.getMessage());
            player.sendMessage("§cNie udało się postawić skrzynki uranowej. Przedmiot nie został zużyty.");
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            if (item.getAmount() <= 1) player.getInventory().setItemInMainHand(new ItemStack(Material.AIR)); else item.setAmount(item.getAmount() - 1);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlaceUraniumChest(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        boolean uraniumItem = isUraniumChestItem(event.getItemInHand());
        if (!uraniumItem) {
            if (event.isCancelled()) return;
            if ((placed.getType() == Material.CHEST || placed.getType() == Material.TRAPPED_CHEST) && touchesUraniumChest(placed)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cNie można łączyć zwykłej skrzyni ze skrzynką uranową.");
                return;
            }
            if (service.specialItems().readSpecialBlockId(placed).isPresent()
                    && (isUraniumChestBlock(placed.getRelative(BlockFace.UP)) || isUraniumChestBlock(placed.getRelative(BlockFace.DOWN)))) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cMaszyny nie można postawić w miejscu, w którym skrzynka uranowa pełniłaby rolę magazynu.");
            }
            return;
        }
        if (event.isCancelled()) return;
        if (service.adjacentMinion(placed.getLocation()).isPresent()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cSkrzynka uranowa nie może być używana jako magazyn miniona.");
            return;
        }
        if (service.touchesOtherChest(placed.getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cSkrzynka uranowa nie może łączyć się z inną skrzynią.");
            return;
        }
        if (service.specialItems().readSpecialBlockId(placed.getRelative(BlockFace.UP)).isPresent()
                || service.specialItems().readSpecialBlockId(placed.getRelative(BlockFace.DOWN)).isPresent()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cSkrzynka uranowa nie może być używana jako górny ani dolny magazyn maszyny.");
            return;
        }
        placed.setType(Material.CHEST, false);
        // Ten znacznik zastępuje znacznik specjalnej stacji, dzięki czemu blok otwiera się jak skrzynia.
        itemFactory.markStorageChestBlock(placed, "iron_uranium");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreakUraniumChest(BlockBreakEvent event) {
        if (!itemFactory.readStorageChestBlockId(event.getBlock()).map("iron_uranium"::equalsIgnoreCase).orElse(false)) return;
        event.setDropItems(false);
        if (event.getBlock().getState() instanceof Chest chest) {
            for (ItemStack content : chest.getBlockInventory().getContents()) {
                if (content != null && !content.getType().isAir() && !isUraniumFiller(content)) {
                    event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), content);
                }
            }
            chest.getBlockInventory().clear();
        }
        event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), service.specialItems().createItem("iron_uranium_chest", 1));
    }

    /**
     * MinionMenuListener wykonuje instalację skrzyni bezpośrednio w swoim evencie.
     * Na LOWEST podmieniamy więc na moment kursor na nieobsługiwany item, a na HIGHEST
     * przywracamy prawdziwą skrzynkę. To zabezpiecza też sloty magazynowe maszyn.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void maskUraniumChestInStorageMenus(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Inventory top = event.getView().getTopInventory();
        Object holder = top.getHolder();
        boolean minionStorageSlot = holder instanceof MinionMenuHolder && event.getSlot() == MinionMenu.STORAGE_CHEST_SLOT;
        boolean machineMenu = holder != null && holder.getClass().getName().startsWith("hex.minions.menu.MachineMenuHolder");
        if ((!minionStorageSlot && !machineMenu) || event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) return;
        ItemStack cursor = event.getCursor();
        if (!isUraniumChestItem(cursor)) return;
        maskedStorageClicks.put(event, cursor.clone());
        event.setCursor(new ItemStack(Material.BARRIER));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack masked = maskedStorageClicks.remove(event);
        if (masked != null) {
            player.setItemOnCursor(masked);
            event.setCancelled(true);
            player.sendMessage("§cSkrzynka uranowa służy wyłącznie do przechowywania wzbogaconego uranu i nie może być magazynem miniona ani maszyny.");
            return;
        }

        Inventory top = event.getView().getTopInventory();
        boolean uraniumChest = isUraniumChest(top);
        if (uraniumChest && event.getClickedInventory() != null && event.getClickedInventory().equals(top) && !isUraniumStorageSlot(event.getSlot())) {
            event.setCancelled(true);
            return;
        }
        ItemStack deposited = depositedIntoTop(event, top, player);
        if (deposited == null || deposited.getType().isAir()) return;
        if (uraniumChest) {
            if (isEnriched(deposited)) return;
            event.setCancelled(true);
            player.sendMessage("§cDo skrzynki uranowej można wkładać wyłącznie wzbogacony uran.");
            return;
        }
        if (isEnriched(deposited)) {
            event.setCancelled(true);
            hex.ui().send(player, "minions.radiation.chest-blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        boolean touchesTop = event.getRawSlots().stream().anyMatch(slot -> slot < top.getSize());
        if (!touchesTop) return;
        ItemStack deposited = event.getOldCursor();
        if (isUraniumChest(top)) {
            boolean touchesBlocked = event.getRawSlots().stream().anyMatch(slot -> slot < top.getSize() && !isUraniumStorageSlot(slot));
            if (touchesBlocked) {
                event.setCancelled(true);
                return;
            }
            if (isEnriched(deposited)) return;
            event.setCancelled(true);
            player.sendMessage("§cDo skrzynki uranowej można wkładać wyłącznie wzbogacony uran.");
            return;
        }
        if (isEnriched(deposited)) {
            event.setCancelled(true);
            hex.ui().send(player, "minions.radiation.chest-blocked");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        ItemStack item = event.getItem();
        if (isUraniumChest(event.getDestination())) {
            if (!isEnriched(item)) event.setCancelled(true);
            return;
        }
        if (isEnriched(item)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        if (isUraniumChest(event.getInventory())) {
            if (!isEnriched(item)) event.setCancelled(true);
            return;
        }
        if (isEnriched(item)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpenChest(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof Chest)) return;
        if (isUraniumChest(event.getInventory())) {
            prepareUraniumChestLayout(event.getInventory());
            return;
        }
        int enriched = countEnriched(event.getInventory());
        if (enriched <= 0) return;
        double reduction = radiationReduction(player);
        double damage = (2.0D * enriched) * Math.max(0.0D, 1.0D - reduction);
        if (damage > 0.0D) player.damage(damage);
        hex.ui().send(player, "minions.radiation.chest-warning", UiTokens.of("protection", String.valueOf(Math.round(reduction * 100.0D))));
    }

    private ItemStack depositedIntoTop(InventoryClickEvent event, Inventory top, Player player) {
        Inventory clicked = event.getClickedInventory();
        boolean clickedTop = clicked != null && clicked.equals(top);
        if (clickedTop && event.getCursor() != null && !event.getCursor().getType().isAir()) return event.getCursor();
        if (!clickedTop && event.isShiftClick()) return event.getCurrentItem();
        if (clickedTop && event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0) {
            return player.getInventory().getItem(event.getHotbarButton());
        }
        if (clickedTop && event.getClick() == ClickType.SWAP_OFFHAND) return player.getInventory().getItemInOffHand();
        return null;
    }

    private boolean touchesUraniumChest(Block block) {
        return isUraniumChestBlock(block.getRelative(BlockFace.NORTH))
                || isUraniumChestBlock(block.getRelative(BlockFace.SOUTH))
                || isUraniumChestBlock(block.getRelative(BlockFace.EAST))
                || isUraniumChestBlock(block.getRelative(BlockFace.WEST));
    }

    private boolean isUraniumChestBlock(Block block) {
        return itemFactory.readStorageChestBlockId(block).map("iron_uranium"::equalsIgnoreCase).orElse(false);
    }

    private boolean isUraniumChest(Inventory inventory) {
        if (inventory == null || !(inventory.getHolder() instanceof Chest chest)) return false;
        return itemFactory.readStorageChestBlockId(chest.getBlock()).map("iron_uranium"::equalsIgnoreCase).orElse(false);
    }

    private boolean isUraniumChestItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        return service.specialItems().readSpecialItemId(item).map("iron_uranium_chest"::equalsIgnoreCase).orElse(false)
                || itemFactory.readStorageChestItem(item).map(data -> "iron_uranium".equalsIgnoreCase(data.id())).orElse(false);
    }

    private void prepareUraniumChestLayout(Inventory inventory) {
        if (inventory == null) return;
        java.util.ArrayList<ItemStack> contents = new java.util.ArrayList<>();
        for (ItemStack item : inventory.getContents()) {
            if (item == null || item.getType().isAir() || isUraniumFiller(item)) continue;
            contents.add(item.clone());
        }
        if (contents.size() <= URANIUM_STORAGE_SLOTS.length) {
            inventory.clear();
            for (int i = 0; i < contents.size(); i++) inventory.setItem(URANIUM_STORAGE_SLOTS[i], contents.get(i));
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isUraniumStorageSlot(slot)) continue;
            ItemStack existing = inventory.getItem(slot);
            if (existing == null || existing.getType().isAir() || isUraniumFiller(existing)) inventory.setItem(slot, uraniumFiller());
        }
    }

    private boolean isUraniumStorageSlot(int slot) {
        for (int allowed : URANIUM_STORAGE_SLOTS) if (allowed == slot) return true;
        return false;
    }

    private ItemStack uraniumFiller() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            meta.setHideTooltip(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isUraniumFiller(ItemStack item) {
        if (item == null || item.getType() != Material.BLACK_STAINED_GLASS_PANE) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && " ".equals(meta.getDisplayName());
    }

    private double radiationReduction(Player player) {
        if (player == null) return 0.0D;
        double reduction = 0.0D;
        reduction += armorPieceReduction(player.getInventory().getHelmet(), "hazmat_helmet", 0.20D);
        reduction += armorPieceReduction(player.getInventory().getChestplate(), "hazmat_chestplate", 0.40D);
        reduction += armorPieceReduction(player.getInventory().getLeggings(), "hazmat_leggings", 0.30D);
        reduction += armorPieceReduction(player.getInventory().getBoots(), "hazmat_boots", 0.10D);
        return Math.max(0.0D, Math.min(1.0D, reduction));
    }

    private double armorPieceReduction(ItemStack item, String specialId, double value) {
        if (item == null || item.getType() == Material.AIR) return 0.0D;
        return service.specialItems().readSpecialItemId(item).map(id -> id.equalsIgnoreCase(specialId) ? value : 0.0D).orElse(0.0D);
    }

    private int countEnriched(Inventory inventory) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) if (isEnriched(item)) count += item.getAmount();
        return count;
    }

    private boolean isEnriched(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        return service.specialItems().readSpecialItemId(item).map("enriched_uranium"::equalsIgnoreCase).orElse(false);
    }
}
