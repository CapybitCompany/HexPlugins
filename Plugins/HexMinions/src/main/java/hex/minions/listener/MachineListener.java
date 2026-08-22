package hex.minions.listener;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.minions.machine.MachineDefinition;
import hex.minions.machine.MachineRecipe;
import hex.minions.machine.MachineRuntime;
import hex.minions.machine.MachineService;
import hex.minions.menu.MachineMenuHolder;
import hex.minions.menu.MachineStorageMenuHolder;
import hex.minions.menu.MinionMenu;
import hex.minions.menu.MinionWikiHolder;
import hex.minions.service.MinionItemFactory;
import hex.towns.api.TownPermission;
import hex.towns.api.TownsApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Optional;

public final class MachineListener implements Listener {
    private final Plugin plugin;
    private final HexApi hex;
    private final TownsApi towns;
    private final MachineService machines;
    private final MinionMenu minionMenu;
    private final MinionItemFactory itemFactory;
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final NamespacedKey machineVisualKey;
    private final NamespacedKey machineVisualTownKey;
    private final NamespacedKey objectTypeKey;
    private final NamespacedKey objectIdKey;
    private final NamespacedKey bronzeWrenchUsesKey;

    public MachineListener(Plugin plugin, HexApi hex, TownsApi towns, MachineService machines) {
        this(plugin, hex, towns, machines, new MinionMenu(hex, machines.minionsService(), new MinionItemFactory(plugin)));
    }

    public MachineListener(Plugin plugin, HexApi hex, TownsApi towns, MachineService machines, MinionMenu minionMenu) {
        this.plugin = plugin;
        this.hex = hex;
        this.towns = towns;
        this.machines = machines;
        this.minionMenu = minionMenu;
        this.itemFactory = new MinionItemFactory(plugin);
        this.machineVisualKey = new NamespacedKey(plugin, "machine_visual");
        this.machineVisualTownKey = new NamespacedKey(plugin, "town_uuid");
        this.objectTypeKey = new NamespacedKey(plugin, "object_type");
        this.objectIdKey = new NamespacedKey(plugin, "object_id");
        this.bronzeWrenchUsesKey = new NamespacedKey(plugin, "bronze_wrench_uses");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceMachine(BlockPlaceEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> handleMachinePlaced(event.getBlockPlaced()));
    }

    /** Shared post-placement initialization for legacy BlockPlaceEvent and non-block carrier placement. */
    public void handleMachinePlaced(Block block) {
        if (block == null) return;
        MachineDefinition machine = machines.machineAt(block);
        if (machine == null) return;
        MachineRuntime runtime = machines.runtime(machines.key(block.getLocation()), machine.id());
        runtime.touchActiveNow();
        machines.markRuntimeDirty(runtime);
        ensureVisual(block, machine);
        machines.refreshCableVisualsNear(block);
        machines.recordEnergyGeneratorPlaced(block, machine);
        machines.saveSoon();
    }

    /** Roll back only runtime/visual state created by a failed non-block placement. */
    public void rollbackPlacedMachine(Block block) {
        if (block == null) return;
        Location location = block.getLocation().clone();
        machines.remove(machines.key(location), null);
        cleanupVisuals(location);
        machines.refreshCableVisualsNear(block);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onUseMachine(PlayerInteractEvent event) {
        if (event.isCancelled() && event.useItemInHand() == org.bukkit.event.Event.Result.DENY) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) return;
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        // A placement click must be owned by the placement listener, not by the GUI listener of
        // the machine that happened to be clicked as the support block.
        if (machines.minionsService().specialItems().readSpecialItemId(held)
                .flatMap(machines.minionsService().specialItems()::item)
                .map(definition -> definition.placeable())
                .orElse(false)) return;
        if (machines.minionsService().storageChestIdForItem(held).isPresent()) return;
        if (tryOpenExternalStorage(event.getPlayer(), event.getClickedBlock())) {
            event.setCancelled(true);
            return;
        }
        MachineDefinition machine = machines.machineAt(event.getClickedBlock());
        if (machine == null) return;
        event.setCancelled(true);
        if (towns.townAt(event.getClickedBlock().getLocation()).filter(t -> towns.can(event.getPlayer().getUniqueId(), t.id(), TownPermission.MACHINE_USE)).isEmpty()) {
            hex.ui().send(event.getPlayer(), "minions.machine.error.not-town");
            return;
        }
        if (machines.isBronzeWrench(event.getItem())) {
            handleBronzeWrench(event.getPlayer(), event.getClickedBlock(), event.getBlockFace(), machine, event.getHand(), event.getItem());
            return;
        }
        ensureVisual(event.getClickedBlock(), machine);
        openMachine(event.getPlayer(), event.getClickedBlock(), machine);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakMachine(BlockBreakEvent event) {
        MachineDefinition machine = machines.machineAt(event.getBlock());
        if (machine == null) return;
        var town = towns.townAt(event.getBlock().getLocation()).orElse(null);
        if (town == null || !towns.can(event.getPlayer().getUniqueId(), town.id(), TownPermission.MACHINE_BREAK)) {
            event.setCancelled(true);
            hex.ui().send(event.getPlayer(), "minions.machine.error.not-town");
            return;
        }
        String key = machines.key(event.getBlock().getLocation());
        machines.removeGeneratorTopStorage(event.getBlock(), event.getBlock().getLocation());
        machines.removeElectricFurnaceStorage(event.getBlock(), event.getBlock().getLocation());
        Location brokenLocation = event.getBlock().getLocation().clone();
        machines.remove(key, brokenLocation);
        cleanupVisuals(brokenLocation);
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Drugi przebieg usuwa ewentualny BlockDisplay utworzony przez zaplanowany refresh
            // w tym samym ticku, już po obsłudze BlockBreakEvent.
            cleanupVisuals(brokenLocation);
            machines.refreshCableVisualsNear(brokenLocation.getBlock());
        });
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof MachineStorageMenuHolder storageHolder) {
            handleStorageClick(event, storageHolder);
            return;
        }
        if (!(top.getHolder() instanceof MachineMenuHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        MachineDefinition machine = machines.registry().machines().get(holder.machineId());
        if (machine == null) { event.setCancelled(true); return; }
        MachineRuntime runtime = machines.runtime(holder.blockKey(), machine.id());
        boolean clickedTop = event.getClickedInventory() != null && event.getClickedInventory().equals(top);
        int clickedSlot = clickedTop ? event.getSlot() : -1;
        machines.sanitizeMenuGuides(top);
        // Shift-click omija walidację konkretnych slotów Bukkitowego inventory.
        // W menu maszyn wymagamy jawnego włożenia itemu do wybranego pola.
        if (!clickedTop && event.isShiftClick()) {
            event.setCancelled(true);
            machines.applyMenuGuides(machine, top);
            return;
        }
        if (clickedTop && clickedSlot >= 0 && clickedSlot < top.getSize() && top.getItem(clickedSlot) == null) {
            event.setCurrentItem(null);
        }
        if (clickedTop) {
            int slot = clickedSlot;
            if (slot == 53 && machines.usesProcessingInputs(machine)) {
                event.setCancelled(true);
                String returnId = "URANIUM_ENRICHER".equalsIgnoreCase(machine.type()) ? "uranium" : MinionWikiHolder.ELECTRONICS_RETURN_ID;
                minionMenu.openWikiMachine(player, returnId, machine.id());
                return;
            }
            int outputIndex = machines.usesProcessingInputs(machine) ? machine.outputSlots().indexOf(slot) : -1;
            if (outputIndex >= 0) {
                machines.syncFromInventory(machine, runtime, top);
                machines.collectOutput(runtime, outputIndex, player);
                machines.syncToInventory(machine, runtime, top);
                if (machines.usesProcessingInputs(machine)) top.setItem(machine.arrowSlot(), progressItem(machine, runtime));
                top.setItem(4, machineInfoItem(machine, runtime));
                machines.applyMenuGuides(machine, top);
                event.setCancelled(true);
                return;
            }
            if (!machines.isEditableSlot(machine, slot)) {
                event.setCancelled(true);
            } else if (machines.isExternalStorageUpgradeSlot(machine, slot)) {
                ItemStack cursor = event.getCursor();
                ItemStack current = event.getCurrentItem();
                boolean puttingStorage = cursor != null && !cursor.getType().isAir();
                boolean takingOrClearing = cursor == null || cursor.getType().isAir();
                if (puttingStorage) {
                    Block machineBlock = machines.blockFromKey(holder.blockKey());
                    if (isUraniumStorageChest(cursor)) {
                        event.setCancelled(true);
                        player.sendMessage("§cSkrzynka uranowa nie może być używana jako magazyn maszyny.");
                        return;
                    }
                    if (!machines.isValidExternalStorageItem(cursor)) {
                        event.setCancelled(true);
                        player.sendMessage("§cTen slot przyjmuje wyłącznie rozszerzenie magazynu miniona.");
                        return;
                    }
                    var town = towns.townAt(machineBlock.getLocation()).orElse(null);
                    if (town == null || !machines.minionsService().validateOrAdoptProgressionItem(cursor, town.id(), "machine_storage")) {
                        event.setCancelled(true);
                        player.sendMessage("§cTo rozszerzenie magazynu należy do innego miasta.");
                        return;
                    }
                    if (!machines.canInstallExternalStorageAt(machineBlock, machine, slot, cursor)) {
                        event.setCancelled(true);
                        String side = slot == machine.inputStorageExtensionSlot() ? "nad maszyną" : "pod maszyną";
                        player.sendMessage("§cBrak wolnego miejsca " + side + " na skrzynię magazynu.");
                        return;
                    }
                } else if (takingOrClearing && current != null && !current.getType().isAir() && machines.isValidExternalStorageItem(current)) {
                    // Wyjęcie rozszerzenia jest dozwolone. Skrzynia zostanie usunięta i wysypana przy następnym cyklu/niszczeniu maszyny.
                }
            } else if (machines.isGeneratorStorageUpgradeSlot(machine, slot)) {
                ItemStack cursor = event.getCursor();
                if (cursor != null && !cursor.getType().isAir() && isUraniumStorageChest(cursor)) {
                    event.setCancelled(true);
                    player.sendMessage("§cSkrzynka uranowa nie może być używana jako magazyn maszyny.");
                    return;
                }
                if (cursor != null && !cursor.getType().isAir() && !machines.isValidExternalStorageItem(cursor)) {
                    event.setCancelled(true);
                    player.sendMessage("§cTen slot przyjmuje wyłącznie rozszerzenie magazynu miniona.");
                    return;
                }
                if (cursor != null && !cursor.getType().isAir()) {
                    Block machineBlock = machines.blockFromKey(holder.blockKey());
                    var town = machineBlock == null ? null : towns.townAt(machineBlock.getLocation()).orElse(null);
                    if (town == null || !machines.minionsService().validateOrAdoptProgressionItem(cursor, town.id(), "machine_storage")) {
                        event.setCancelled(true);
                        player.sendMessage("§cTo rozszerzenie magazynu należy do innego miasta.");
                        return;
                    }
                }
                if (cursor != null && !cursor.getType().isAir()
                        && !machines.canInstallGeneratorStorageAt(machines.blockFromKey(holder.blockKey()), machine, cursor)) {
                    event.setCancelled(true);
                    player.sendMessage("§cBrak wolnego miejsca nad maszyną na skrzynię magazynu paliwa.");
                    return;
                }
            } else if (machines.isPortableEnergyChargeSlot(machine, slot)) {
                ItemStack cursor = event.getCursor();
                if (cursor != null && !cursor.getType().isAir() && !machines.isPortableEnergyItem(cursor)) {
                    event.setCancelled(true);
                    player.sendMessage("§cTen slot przyjmuje wyłącznie baterię lub diament energetyczny.");
                    return;
                }
            } else if (machines.upgradeSlots(machine).contains(slot)) {
                ItemStack cursor = event.getCursor();
                if (cursor != null && !cursor.getType().isAir() && !machines.isValidMachineUpgradeItem(machine, cursor)) {
                    event.setCancelled(true);
                    player.sendMessage("§cTo ulepszenie nie pasuje do tego urządzenia.");
                    return;
                }
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            machines.syncFromInventory(machine, runtime, top);
            machines.ensureExternalStorage(machines.blockFromKey(holder.blockKey()), machine, runtime);
            if (machines.usesProcessingInputs(machine)) top.setItem(machine.arrowSlot(), progressItem(machine, runtime));
            top.setItem(4, machineInfoItem(machine, runtime));
            machines.applyMenuGuides(machine, top);
            machines.saveSoon();
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MachineStorageMenuHolder holder)) return;
        int[] visibleSlots = centeredMachineStorageSlots(top.getSize(), holder.slots());
        for (int raw : event.getRawSlots()) {
            if (raw < 0 || raw >= top.getSize()) continue;
            boolean usable = false;
            for (int visible : visibleSlots) {
                if (visible == raw) { usable = true; break; }
            }
            if (!usable) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getInventory();
        if (top.getHolder() instanceof MachineStorageMenuHolder storageHolder) {
            saveExternalStorage(storageHolder, top);
            return;
        }
        if (!(top.getHolder() instanceof MachineMenuHolder holder)) return;
        MachineDefinition machine = machines.registry().machines().get(holder.machineId());
        if (machine == null) return;
        MachineRuntime runtime = machines.runtime(holder.blockKey(), machine.id());
        machines.sanitizeMenuGuides(top);
        machines.syncFromInventory(machine, runtime, top);
        machines.saveSoon();
    }


    private boolean tryOpenExternalStorage(Player player, Block clicked) {
        if (clicked == null || !(clicked.getState() instanceof Chest)) return false;
        for (BlockFace face : List.of(BlockFace.UP, BlockFace.DOWN)) {
            Block machineBlock = face == BlockFace.UP ? clicked.getRelative(BlockFace.DOWN) : clicked.getRelative(BlockFace.UP);
            MachineDefinition machine = machines.machineAt(machineBlock);
            if (machine == null) continue;
            if (face == BlockFace.UP && machine.inputStorageExtensionSlot() < 0) continue;
            if (face == BlockFace.DOWN && machine.outputStorageExtensionSlot() < 0) continue;
            MachineRuntime runtime = machines.runtime(machines.key(machineBlock.getLocation()), machine.id());
            int slots = machines.externalStorageSlots(runtime, face);
            if (slots <= 0) continue;
            if (towns.townAt(machineBlock.getLocation()).filter(t -> towns.can(player.getUniqueId(), t.id(), TownPermission.MACHINE_USE)).isEmpty()) {
                hex.ui().send(player, "minions.machine.error.not-town");
                return true;
            }
            openExternalStorage(player, machineBlock, machine, runtime, face, slots);
            return true;
        }
        return false;
    }

    private void openExternalStorage(Player player, Block machineBlock, MachineDefinition machine, MachineRuntime runtime, BlockFace face, int slots) {
        Chest chest = machines.externalStorageChest(machineBlock, face, slots, true);
        if (chest == null) return;
        Inventory source = chest.getBlockInventory();
        int usableSlots = Math.max(1, Math.min(slots, source.getSize()));
        int storageRows = Math.max(1, (usableSlots + 8) / 9);
        int size = Math.max(18, Math.min(54, (storageRows + 1) * 9));
        Inventory inv = Bukkit.createInventory(new MachineStorageMenuHolder(machine.id(), machines.key(machineBlock.getLocation()), face, usableSlots), size,
                mini.deserialize(usableSlots >= 7 ? "<gold>Duży magazyn maszyny</gold>" : usableSlots >= 5 ? "<gold>Średni magazyn maszyny</gold>" : "<yellow>Magazyn maszyny</yellow>"));

        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, "", "");
        for (int i = 0; i < size; i++) inv.setItem(i, filler);
        int[] visibleSlots = centeredMachineStorageSlots(size, usableSlots);
        for (int i = 0; i < Math.min(visibleSlots.length, source.getSize()); i++) {
            inv.setItem(visibleSlots[i], source.getItem(i));
        }
        for (int i = usableSlots; i < source.getSize(); i++) {
            ItemStack excess = source.getItem(i);
            if (excess == null || excess.getType().isAir()) continue;
            source.setItem(i, null);
            player.getInventory().addItem(excess).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
        inv.setItem(size - 5, named(Material.BARRIER, "§cZamknij", ""));
        player.openInventory(inv);
    }

    private void handleStorageClick(InventoryClickEvent event, MachineStorageMenuHolder holder) {
        if (!(event.getWhoClicked() instanceof Player player)) { event.setCancelled(true); return; }
        Inventory top = event.getView().getTopInventory();
        int raw = event.getRawSlot();
        int closeSlot = top.getSize() - 5;
        if (raw == closeSlot) { event.setCancelled(true); player.closeInventory(); return; }
        if (raw >= 0 && raw < top.getSize()) {
            boolean usable = false;
            for (int visible : centeredMachineStorageSlots(top.getSize(), holder.slots())) {
                if (visible == raw) { usable = true; break; }
            }
            if (!usable) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.isShiftClick()) event.setCancelled(true);
    }

    private void saveExternalStorage(MachineStorageMenuHolder holder, Inventory inv) {
        MachineDefinition machine = machines.registry().machines().get(holder.machineId());
        Block machineBlock = machines.blockFromKey(holder.blockKey());
        if (machine == null || machineBlock == null) return;
        Chest chest = machines.externalStorageChest(machineBlock, holder.face(), holder.slots(), true);
        if (chest == null) return;
        Inventory target = chest.getBlockInventory();
        int[] visibleSlots = centeredMachineStorageSlots(inv.getSize(), holder.slots());
        for (int i = 0; i < Math.min(Math.min(holder.slots(), target.getSize()), visibleSlots.length); i++) {
            target.setItem(i, inv.getItem(visibleSlots[i]));
        }
        machines.saveSoon();
    }

    private int[] centeredMachineStorageSlots(int inventorySize, int usableSlots) {
        int contentRows = Math.max(1, inventorySize / 9 - 1);
        int count = Math.max(0, Math.min(usableSlots, contentRows * 9));
        int[] slots = new int[count];
        int written = 0;
        int rowsNeeded = Math.max(1, (count + 8) / 9);
        int firstRow = Math.max(0, (contentRows - rowsNeeded) / 2);
        for (int row = 0; row < rowsNeeded && written < count; row++) {
            int inRow = Math.min(9, count - written);
            int startCol = Math.max(0, (9 - inRow) / 2);
            for (int col = 0; col < inRow; col++) {
                slots[written++] = (firstRow + row) * 9 + startCol + col;
            }
        }
        return slots;
    }

    private void handleBronzeWrench(Player player, Block block, BlockFace clickedFace, MachineDefinition machine, EquipmentSlot hand, ItemStack wrench) {
        if (clickedFace == null || !List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST).contains(clickedFace)) {
            player.sendMessage("§cKliknij kluczem w jedną z bocznych ścian maszyny, aby ustawić ją jako przód.");
            return;
        }
        BlockData data = block.getBlockData();
        if (!(data instanceof Directional directional) || !directional.getFaces().contains(clickedFace)) {
            player.sendMessage("§cTego modelu maszyny nie można obrócić w wybranym kierunku.");
            return;
        }
        if (directional.getFacing() == clickedFace) {
            player.sendMessage("§eTa ściana jest już przodem maszyny.");
            return;
        }
        directional.setFacing(clickedFace);
        block.setBlockData(directional, false);
        if ("ACCUMULATOR".equalsIgnoreCase(machine.type())) {
            machines.setAccumulatorInputFace(block, clickedFace.getOppositeFace());
        }
        cleanupVisuals(block.getLocation());
        ensureVisual(block, machine);
        machines.refreshCableVisualsNear(block);
        damageBronzeWrench(player, hand, wrench);
        playSound(player, block.getLocation(), "BLOCK_ANVIL_USE", 0.7f, 1.35f);
        player.sendMessage("§aUstawiono przód maszyny na ścianę §f" + clickedFace.name() + "§a.");
    }

    private void damageBronzeWrench(Player player, EquipmentSlot hand, ItemStack wrench) {
        if (wrench == null || wrench.getType().isAir()) return;
        ItemMeta meta = wrench.getItemMeta();
        if (meta == null) return;
        int used = meta.getPersistentDataContainer().getOrDefault(bronzeWrenchUsesKey, PersistentDataType.INTEGER, 0) + 1;
        if (used >= 10) {
            if (hand == EquipmentSlot.OFF_HAND) player.getInventory().setItemInOffHand(null);
            else player.getInventory().setItemInMainHand(null);
            playSound(player, player.getLocation(), "ENTITY_ITEM_BREAK", 1.0f, 1.0f);
            player.sendMessage("§cKlucz z brązu zużył się po 10 użyciach.");
            return;
        }
        meta.getPersistentDataContainer().set(bronzeWrenchUsesKey, PersistentDataType.INTEGER, used);
        if (meta instanceof org.bukkit.inventory.meta.Damageable damageable && wrench.getType().getMaxDurability() > 0) {
            int max = wrench.getType().getMaxDurability();
            damageable.setDamage(Math.min(max - 1, Math.max(0, (int) Math.round(max * (used / 10.0D)))));
        }
        wrench.setItemMeta(meta);
        if (hand == EquipmentSlot.OFF_HAND) player.getInventory().setItemInOffHand(wrench);
        else player.getInventory().setItemInMainHand(wrench);
        player.sendActionBar(Component.text("Klucz z brązu: " + (10 - used) + "/10 użyć"));
    }

    private void playSound(Player player, Location location, String soundName, float volume, float pitch) {
        if (player == null || location == null || soundName == null || soundName.isBlank()) {
            return;
        }

        try {
            player.playSound(location, Sound.valueOf(soundName), volume, pitch);
        } catch (IllegalArgumentException ignored) {
            // Sound is unavailable on this Minecraft version.
        }
    }

    private void openMachine(Player player, Block block, MachineDefinition machine) {
        String key = machines.key(block.getLocation());
        MachineRuntime runtime = machines.runtime(key, machine.id());
        machines.applyOfflineCatchup(block, machine, runtime);
        Inventory inv = Bukkit.createInventory(new MachineMenuHolder(machine.id(), key), 54, mini.deserialize(machine.displayName()));
        fill(inv, machine);
        machines.syncToInventory(machine, runtime, inv);
        if (machines.usesProcessingInputs(machine)) inv.setItem(machine.arrowSlot(), progressItem(machine, runtime));
        inv.setItem(4, machineInfoItem(machine, runtime));
        machines.applyMenuGuides(machine, inv);
        if (machines.usesProcessingInputs(machine)) inv.setItem(53, named(Material.KNOWLEDGE_BOOK, "§eWszystkie receptury", ""));
        player.openInventory(inv);
    }

    private void fill(Inventory inv, MachineDefinition machine) {
        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ", "");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        if (machines.usesProcessingInputs(machine)) for (int inputSlot : machine.inputSlots()) inv.setItem(inputSlot, null);
        if (machine.hasSecondarySlot()) inv.setItem(machine.secondarySlot(), null);
        if (machines.usesFuelSlot(machine)) inv.setItem(machine.fuelSlot(), null);
        if (machines.usesProcessingInputs(machine)) for (int outputSlot : machine.outputSlots()) inv.setItem(outputSlot, null);
        for (int slot : machines.accessorySlots(machine)) inv.setItem(slot, null);
        if (machines.isPortableEnergyChargeSlot(machine, machine.energy().batterySlot())
                && machine.energy().batterySlot() >= 0 && machine.energy().batterySlot() < inv.getSize()) {
            inv.setItem(machine.energy().batterySlot(), null);
        }
    }

    private ItemStack progressItem(MachineDefinition machine, MachineRuntime runtime) {
        if ("ELECTRIC_FURNACE".equalsIgnoreCase(machine.type())) return electricFurnaceProgressItem(machine, runtime);
        if ("ELECTRIC_MILL".equalsIgnoreCase(machine.type())) return externalMachineProgressItem(machine, runtime, "kompostora");
        if ("MEAT_REFINERY".equalsIgnoreCase(machine.type())) return externalMachineProgressItem(machine, runtime, "rafinatora mięsa");
        MachineRecipe recipe = machines.match(machine, runtime);
        if (recipe == null) return named(Material.ARROW, "§ePostęp", "§7Oczekuje na surowce, miejsce lub energię.");
        int progress = Math.min(recipe.timeSeconds(), runtime.progressSeconds());
        int percent = Math.min(100, progress * 100 / Math.max(1, recipe.timeSeconds()));
        return named(Material.ARROW, "§ePostęp: §f" + percent + "%", "§7Czas: §f" + progress + "§7/§f" + recipe.timeSeconds() + " s");
    }

    private ItemStack electricFurnaceProgressItem(MachineDefinition machine, MachineRuntime runtime) {
        return named(Material.ARROW, "§ePostęp", electricProgressLine(runtime, 0) + "\n" + electricProgressLine(runtime, 1));
    }

    private ItemStack externalMachineProgressItem(MachineDefinition machine, MachineRuntime runtime, String label) {
        String recipe = runtime.recipeId();
        if (recipe == null || recipe.isBlank()) {
            return named(Material.ARROW, "§ePostęp " + label, "§7Oczekuje na surowce, miejsce lub energię.");
        }
        int total = machine.recipes().stream()
                .filter(r -> recipe.startsWith(r.id() + "@"))
                .findFirst()
                .map(MachineRecipe::timeSeconds)
                .orElse(30);
        int progress = Math.min(total, runtime.progressSeconds());
        int percent = Math.min(100, progress * 100 / Math.max(1, total));
        return named(Material.ARROW, "§ePostęp " + label + ": §f" + percent + "%", "§7Czas: §f" + progress + "§7/§f" + total + " s");
    }

    private String electricProgressLine(MachineRuntime runtime, int slot) {
        String recipe = runtime.recipeIdAt(slot);
        if (recipe == null || recipe.isBlank()) return "§7Slot " + (slot + 1) + ": §8oczekuje";
        int total = 9;
        int progress = Math.min(total, runtime.progressSecondsAt(slot));
        int percent = Math.min(100, progress * 100 / Math.max(1, total));
        return "§7Slot " + (slot + 1) + ": §f" + percent + "% §8(" + progress + "/" + total + " s)";
    }

    private String machineDescription(MachineDefinition machine) {
        if (machine == null) return "Urządzenie technologiczne.";
        return switch (machine.type().toUpperCase(java.util.Locale.ROOT)) {
            case "URANIUM_ENRICHER" -> "Wzbogaca uran do wzbogaconego uranu.";
            case "SMELTING_FURNACE" -> "Przetapia surowce i pozwala wytwarzać stal. Nie wymaga zasilania EU.";
            case "ELECTRIC_FURNACE" -> "Szybko przetapia surowce i obsługuje produkcję stali.";
            case "COAL_GENERATOR" -> "Spala paliwo i zasila urządzenia energią EU.";
            case "SOLAR_PANEL_GENERATOR", "SOLAR_GENERATOR" -> "Wytwarza energię ze światła słonecznego.";
            case "ACCUMULATOR" -> "Magazynuje energię i przekazuje ją dalej do sieci.";
            case "MACERATOR" -> "Kruszy rudy i surowce na pyły do dalszego przetwarzania.";
            case "EXTRACTOR" -> "Pozyskuje żywicę ze skompresowanego drewna świerkowego.";
            case "COMPRESSOR" -> "Kompresuje i łączy materiały w zaawansowane surowce.";
            case "ELECTRIC_MILL" -> "Przetwarza materiały organiczne w elektrycznym kompostorze.";
            case "MEAT_REFINERY" -> "Rafinuje mięso do dalszego wykorzystania technologicznego.";
            default -> "Urządzenie technologiczne.";
        };
    }

    private static String formatNumber(long value) {
        return String.format(java.util.Locale.US, "%,d", Math.max(0L, value)).replace(',', ' ');
    }

    private static String formatRate(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001D) return formatNumber((long) Math.rint(value));
        return String.format(java.util.Locale.US, "%.1f", value).replace('.', ',');
    }

    private ItemStack machineInfoItem(MachineDefinition machine, MachineRuntime runtime) {
        Material icon = machine.energy().generator() ? Material.REDSTONE_BLOCK
                : "ACCUMULATOR".equalsIgnoreCase(machine.type()) ? Material.BARREL
                : machine.baseBlock();
        StringBuilder lore = new StringBuilder("§7").append(machineDescription(machine));
        if (machine.energy().enabled()) {
            lore.append("\n§7Energia: §a").append(formatNumber(runtime.energy())).append("/").append(formatNumber(machines.capacity(machine, runtime))).append(" EU");
            if (machine.energy().generator()) {
                lore.append("\n§7Generowanie: §a").append(formatRate(machines.effectiveGenerationPerSecond(machine, runtime))).append(" EU/s");
                lore.append("\n§7Transfer: §a").append(formatNumber(machines.effectiveTransferPerSecond(machine, runtime))).append(" EU/s");
            } else if ("ACCUMULATOR".equalsIgnoreCase(machine.type())) {
                lore.append("\n§7Transfer: §a").append(formatNumber(machines.effectiveTransferPerSecond(machine, runtime))).append(" EU/s");
            } else {
                lore.append("\n§7Zużycie: §a").append(formatNumber(machines.effectiveEnergyPerSecond(machine, runtime))).append(" EU/s");
            }
        }
        return machineInfo(machine, runtime, icon, machine.displayName(), lore.toString());
    }

    private ItemStack machineInfo(MachineDefinition machine, MachineRuntime runtime, Material material, String name, String lore) {
        ItemStack icon = machine == null ? null : machines.createMachineItem(machine);
        if (icon == null || icon.getType().isAir()) icon = new ItemStack(material);
        if (machine == null || machine.upgradeSlots().isEmpty()) return named(icon, name, lore);
        String upgradeLore = machines.activeMachineUpgrade(machine, runtime)
                .map(upgrade -> {
                    int savedPercent = Math.max(0, (int) Math.round((1.0D - upgrade.energyConsumptionMultiplier()) * 100.0D));
                    if (savedPercent > 0) return "\n§aUlepszenie: §f" + machines.machineUpgradeDisplayName(upgrade) + "\n§7Zużycie energii: §a-" + savedPercent + "%";
                    return "\n§aUlepszenie: §f" + machines.machineUpgradeDisplayName(upgrade);
                })
                .orElse("");
        return named(icon, name, lore + upgradeLore);
    }

    private static String prettyId(String raw) {
        if (raw == null || raw.isBlank()) return "brak";
        String text = raw.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private void ensureVisual(Block block, MachineDefinition machine) {
        // Po zmianach wizuali starsze BlockDisplay mogą nadal istnieć w świecie.
        // Usuwamy je i odtwarzamy aktualny wariant przy każdym place/interact, zamiast zostawiać stary model.
        cleanupVisuals(block.getLocation());
        if ("URANIUM_ENRICHER".equalsIgnoreCase(machine.type())) {
            spawnUraniumEnricherVisual(block);
        } else if ("SMELTING_FURNACE".equalsIgnoreCase(machine.type())) {
            spawnSmeltingFurnaceVisual(block);
        } else if ("COAL_GENERATOR".equalsIgnoreCase(machine.type())) {
            spawnDisplay(block.getLocation(), block.getLocation(), Material.DEEPSLATE, new Vector3f(-0.03f, -0.03f, -0.03f), new Vector3f(1.06f, 1.06f, 1.06f));
            spawnDisplay(block.getLocation(), block.getLocation().add(0.24, 0.88, 0.24), Material.COAL_BLOCK, new Vector3f(0f, 0f, 0f), new Vector3f(0.52f, 0.18f, 0.52f));
        } else if ("SOLAR_PANEL_GENERATOR".equalsIgnoreCase(machine.type()) || "SOLAR_GENERATOR".equalsIgnoreCase(machine.type())) {
            spawnDisplay(block.getLocation(), block.getLocation(), Material.IRON_BLOCK, new Vector3f(-0.02f, -0.02f, -0.02f), new Vector3f(1.04f, 1.04f, 1.04f));
            // Panel ma przykrywać całą górną powierzchnię bloku.
            spawnDisplay(block.getLocation(), block.getLocation(), Material.COAL_BLOCK, new Vector3f(-0.02f, 1.02f, -0.02f), new Vector3f(1.04f, 0.10f, 1.04f));
        } else if ("ACCUMULATOR".equalsIgnoreCase(machine.type())) {
            spawnAccumulatorVisual(block, machine, machines.runtime(machines.key(block.getLocation()), machine.id()));
        } else if ("MACERATOR".equalsIgnoreCase(machine.type())) {
            spawnMaceratorVisual(block);
        } else if ("COMPRESSOR".equalsIgnoreCase(machine.type())) {
            spawnCompressorVisual(block);
        } else if ("EXTRACTOR".equalsIgnoreCase(machine.type())) {
            spawnExtractorVisual(block);
        } else if ("ELECTRIC_FURNACE".equalsIgnoreCase(machine.type())) {
            spawnElectricFurnaceVisual(block);
        } else if ("ELECTRIC_MILL".equalsIgnoreCase(machine.type())) {
            spawnElectricMillVisual(block);
        } else if ("MEAT_REFINERY".equalsIgnoreCase(machine.type())) {
            spawnMeatRefineryVisual(block);
        }
        spawnEnergyPorts(block, machine);
    }


    private void spawnEnergyPorts(Block block, MachineDefinition machine) {
        if (!machine.energy().enabled()) return;
        if ("ACCUMULATOR".equalsIgnoreCase(machine.type())) return; // akumulator ma pełny zestaw portów w swoim visualu.
        if (machine.energy().generator()) {
            port(block.getLocation(), machines.leftOf(block), Material.BLUE_CONCRETE, false);
            port(block.getLocation(), machines.rightOf(block), Material.BLUE_CONCRETE, false);
            return;
        }
        if ("URANIUM_ENRICHER".equalsIgnoreCase(machine.type())) {
            port(block.getLocation(), machines.leftOf(block), Material.ORANGE_CONCRETE, true);
            port(block.getLocation(), machines.rightOf(block), Material.ORANGE_CONCRETE, true);
            return;
        }
        if ("MACERATOR".equalsIgnoreCase(machine.type())) {
            for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.DOWN)) {
                port(block.getLocation(), face, Material.ORANGE_CONCRETE, true);
            }
            return;
        }
        // Wszystkie zwykłe urządzenia elektryczne pobierające prąd mają wejścia z tyłu, lewej, prawej i od dołu.
        port(block.getLocation(), machines.facing(block).getOppositeFace(), Material.ORANGE_CONCRETE, true);
        port(block.getLocation(), machines.leftOf(block), Material.ORANGE_CONCRETE, true);
        port(block.getLocation(), machines.rightOf(block), Material.ORANGE_CONCRETE, true);
        port(block.getLocation(), BlockFace.DOWN, Material.ORANGE_CONCRETE, true);
    }


    private void spawnSmeltingFurnaceVisual(Block block) {
        Location base = block.getLocation();
        BlockFace front = machines.facing(block);
        panel(base, Material.BRICKS, -0.035f, -0.035f, -0.035f, 0.05f, 1.07f, 1.07f, front != BlockFace.WEST);
        panel(base, Material.BRICKS, 0.985f, -0.035f, -0.035f, 0.05f, 1.07f, 1.07f, front != BlockFace.EAST);
        panel(base, Material.BRICKS, -0.035f, -0.035f, -0.035f, 1.07f, 1.07f, 0.05f, front != BlockFace.NORTH);
        panel(base, Material.BRICKS, -0.035f, -0.035f, 0.985f, 1.07f, 1.07f, 0.05f, front != BlockFace.SOUTH);
        panel(base, Material.BRICKS, -0.035f, 0.985f, -0.035f, 1.07f, 0.05f, 1.07f, true);
    }
    private void spawnUraniumEnricherVisual(Block block) {
        Location base = block.getLocation();
        BlockFace front = machines.facing(block);
        BlockData dispenser = Material.DISPENSER.createBlockData();
        if (dispenser instanceof Directional directional) directional.setFacing(front);
        spawnDisplay(base, base, dispenser, new Vector3f(-0.01f, -0.01f, -0.01f), new Vector3f(1.02f, 1.02f, 1.02f));
        // Żelazna obudowa jako panele, z otwartym przodem na podajnik.
        panel(base, Material.IRON_BLOCK, -0.04f, -0.04f, -0.04f, 0.08f, 1.08f, 1.08f, front != BlockFace.WEST);
        panel(base, Material.IRON_BLOCK, 0.96f, -0.04f, -0.04f, 0.08f, 1.08f, 1.08f, front != BlockFace.EAST);
        panel(base, Material.IRON_BLOCK, -0.04f, -0.04f, -0.04f, 1.08f, 1.08f, 0.08f, front != BlockFace.NORTH);
        panel(base, Material.IRON_BLOCK, -0.04f, -0.04f, 0.96f, 1.08f, 1.08f, 0.08f, front != BlockFace.SOUTH);
        panel(base, Material.IRON_BLOCK, -0.04f, 0.96f, -0.04f, 1.08f, 0.08f, 1.08f, true);
        panel(base, Material.IRON_BLOCK, -0.04f, -0.04f, -0.04f, 1.08f, 0.08f, 1.08f, true);
        sideStrip(base, front, machines.leftOf(block), Material.LIME_CONCRETE, 0.15f, 0.70f);
        sideStrip(base, front, machines.rightOf(block), Material.LIME_CONCRETE, 0.15f, 0.70f);
    }

    private void spawnMaceratorVisual(Block block) {
        Location base = block.getLocation();
        BlockData dispenser = Material.DISPENSER.createBlockData();
        if (dispenser instanceof Directional directional) directional.setFacing(BlockFace.UP);
        spawnDisplay(base, base, dispenser, new Vector3f(-0.01f, -0.01f, -0.01f), new Vector3f(1.02f, 1.02f, 1.02f));
        // Dyspenser skierowany w górę jest rdzeniem maceratora, a dookoła ma cienkie żelazne płyty 0.1 bloku.
        panel(base, Material.IRON_BLOCK, -0.06f, -0.02f, -0.06f, 0.10f, 1.04f, 1.12f, true);
        panel(base, Material.IRON_BLOCK, 0.96f, -0.02f, -0.06f, 0.10f, 1.04f, 1.12f, true);
        panel(base, Material.IRON_BLOCK, -0.06f, -0.02f, -0.06f, 1.12f, 1.04f, 0.10f, true);
        panel(base, Material.IRON_BLOCK, -0.06f, -0.02f, 0.96f, 1.12f, 1.04f, 0.10f, true);
    }

    private void sideStrip(Location base, BlockFace front, BlockFace side, Material material, float width, float height) {
        float y = 0.15f;
        float zOrX = 0.42f;
        if (side == BlockFace.WEST) {
            brightDisplay(base, base, material, new Vector3f(-0.08f, y, zOrX), new Vector3f(0.10f, height, width));
        } else if (side == BlockFace.EAST) {
            brightDisplay(base, base, material, new Vector3f(0.98f, y, zOrX), new Vector3f(0.10f, height, width));
        } else if (side == BlockFace.NORTH) {
            brightDisplay(base, base, material, new Vector3f(zOrX, y, -0.08f), new Vector3f(width, height, 0.10f));
        } else if (side == BlockFace.SOUTH) {
            brightDisplay(base, base, material, new Vector3f(zOrX, y, 0.98f), new Vector3f(width, height, 0.10f));
        }
    }

    private void spawnElectricMillVisual(Block block) {
        Location base = block.getLocation();
        BlockFace front = machines.facing(block);
        // Blok bazowy to BARREL, bo COMPOSTER nie zapisuje PDC. Wizualnie urządzenie nadal jest kompostownikiem.
        spawnDisplay(base, base, Material.COMPOSTER, new Vector3f(-0.02f, -0.02f, -0.02f), new Vector3f(1.04f, 1.04f, 1.04f));
        // Miedziane płyty o grubości 0.1 bloku otaczają boki, z frontem zostawionym dla czytelności.
        panel(base, Material.COPPER_BLOCK, -0.06f, -0.02f, -0.06f, 0.10f, 1.04f, 1.12f, front != BlockFace.WEST);
        panel(base, Material.COPPER_BLOCK, 0.96f, -0.02f, -0.06f, 0.10f, 1.04f, 1.12f, front != BlockFace.EAST);
        panel(base, Material.COPPER_BLOCK, -0.06f, -0.02f, -0.06f, 1.12f, 1.04f, 0.10f, front != BlockFace.NORTH);
        panel(base, Material.COPPER_BLOCK, -0.06f, -0.02f, 0.96f, 1.12f, 1.04f, 0.10f, front != BlockFace.SOUTH);
        panel(base, Material.COPPER_BLOCK, -0.06f, -0.06f, -0.06f, 1.12f, 0.10f, 1.12f, true);
    }

    private void spawnExtractorVisual(Block block) {
        Location base = block.getLocation();
        spawnDisplay(base, base, Material.DISPENSER, new Vector3f(-0.02f, -0.02f, -0.02f), new Vector3f(1.04f, 1.04f, 1.04f));
        // Błękitne paski po bokach: szerokość 0.1, wysokość 0.8, wysunięcie 0.1 poza blok.
        spawnDisplay(base, base, Material.LIGHT_BLUE_CONCRETE, new Vector3f(-0.08f, 0.10f, 0.45f), new Vector3f(0.10f, 0.80f, 0.10f));
        spawnDisplay(base, base, Material.LIGHT_BLUE_CONCRETE, new Vector3f(0.98f, 0.10f, 0.45f), new Vector3f(0.10f, 0.80f, 0.10f));
    }

    private Material accumulatorBodyMaterial(MachineDefinition machine) {
        if (machine == null) return Material.OAK_WOOD;
        if ("advanced_accumulator".equalsIgnoreCase(machine.id())) return Material.IRON_BLOCK;
        if ("super_capacitor".equalsIgnoreCase(machine.id())) return Material.DIAMOND_BLOCK;
        return Material.OAK_WOOD;
    }

    private Material accumulatorInfoIconMaterial(MachineDefinition machine) {
        if (machine == null) return Material.OAK_WOOD;
        if ("advanced_accumulator".equalsIgnoreCase(machine.id())) return Material.IRON_BLOCK;
        if ("super_capacitor".equalsIgnoreCase(machine.id())) return Material.DIAMOND_BLOCK;
        return Material.OAK_WOOD;
    }

    private void spawnAccumulatorVisual(Block block, MachineDefinition machine, MachineRuntime runtime) {
        Location base = block.getLocation();
        BlockDisplay body = spawnDisplay(base, base, accumulatorBodyMaterial(machine), new Vector3f(-0.02f, -0.02f, -0.02f), new Vector3f(1.04f, 1.04f, 1.04f));
        if ("advanced_accumulator".equalsIgnoreCase(machine.id())) {
            body.setBrightness(new Display.Brightness(5, 5));
        }
        BlockFace input = machines.accumulatorInputFace(block);
        port(base, input, Material.ORANGE_CONCRETE, true);
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP)) {
            if (face != input) port(base, face, Material.BLUE_CONCRETE, false);
        }
        spawnAccumulatorChargeBar(base, input.getOppositeFace(), machine, runtime);
    }

    private void spawnAccumulatorChargeBar(Location base, BlockFace face, MachineDefinition machine, MachineRuntime runtime) {
        if (base == null || face == null || machine == null || runtime == null) return;
        int capacity = Math.max(1, machines.capacity(machine, runtime));
        float charged = Math.max(0f, Math.min(0.8f, 0.8f * runtime.energy() / (float) capacity));
        float missing = Math.max(0f, 0.8f - charged);
        if (charged > 0.01f) accumulatorBarPart(base, face, Material.LIME_CONCRETE, 0.10f, charged);
        if (missing > 0.01f) accumulatorBarPart(base, face, Material.RED_CONCRETE, 0.10f + charged, missing);
    }

    private void accumulatorBarPart(Location base, BlockFace face, Material material, float bottom, float height) {
        float h = Math.max(0.01f, height);
        float y = Math.min(0.90f - h, Math.max(0.10f, bottom));
        float side = 0.10f;
        float depth = 0.10f;
        // Pozycja jest liczona jako 0.2 od lewej krawędzi, gdy patrzymy na daną ścianę akumulatora na wprost.
        switch (face) {
            case NORTH -> brightDisplay(base, base, material, new Vector3f(0.70f, y, -0.08f), new Vector3f(side, h, depth));
            case SOUTH -> brightDisplay(base, base, material, new Vector3f(0.20f, y, 0.98f), new Vector3f(side, h, depth));
            case EAST -> brightDisplay(base, base, material, new Vector3f(0.98f, y, 0.70f), new Vector3f(depth, h, side));
            case WEST -> brightDisplay(base, base, material, new Vector3f(-0.08f, y, 0.20f), new Vector3f(depth, h, side));
            case UP -> brightDisplay(base, base, material, new Vector3f(0.20f, 0.98f, 0.70f), new Vector3f(side, depth, h));
            case DOWN -> brightDisplay(base, base, material, new Vector3f(0.20f, -0.08f, 0.20f), new Vector3f(side, depth, h));
            default -> { }
        }
    }

    private void port(Location base, BlockFace face, Material material, boolean input) {
        float size = 0.30f;
        float depth = 0.10f;
        switch (face) {
            case NORTH -> brightDisplay(base, base, material, new Vector3f(0.35f, 0.35f, -0.06f), new Vector3f(size, size, depth));
            case SOUTH -> brightDisplay(base, base, material, new Vector3f(0.35f, 0.35f, 0.96f), new Vector3f(size, size, depth));
            case EAST -> brightDisplay(base, base, material, new Vector3f(0.96f, 0.35f, 0.35f), new Vector3f(depth, size, size));
            case WEST -> brightDisplay(base, base, material, new Vector3f(-0.06f, 0.35f, 0.35f), new Vector3f(depth, size, size));
            case UP -> brightDisplay(base, base, material, new Vector3f(0.35f, 0.96f, 0.35f), new Vector3f(size, depth, size));
            case DOWN -> brightDisplay(base, base, material, new Vector3f(0.35f, -0.06f, 0.35f), new Vector3f(size, depth, size));
            default -> { }
        }
    }

    private void spawnCompressorVisual(Block block) {
        Location base = block.getLocation();
        BlockFace front = machines.facing(block);
        // Żelazna obudowa jest rozbita na panele, więc przód dyspensera zostaje widoczny.
        panel(base, Material.IRON_BLOCK, -0.035f, -0.035f, -0.035f, 0.05f, 1.07f, 1.07f, front != BlockFace.WEST);
        panel(base, Material.IRON_BLOCK, 0.985f, -0.035f, -0.035f, 0.05f, 1.07f, 1.07f, front != BlockFace.EAST);
        panel(base, Material.IRON_BLOCK, -0.035f, -0.035f, -0.035f, 1.07f, 1.07f, 0.05f, front != BlockFace.NORTH);
        panel(base, Material.IRON_BLOCK, -0.035f, -0.035f, 0.985f, 1.07f, 1.07f, 0.05f, front != BlockFace.SOUTH);
        panel(base, Material.IRON_BLOCK, -0.035f, 0.985f, -0.035f, 1.07f, 0.05f, 1.07f, true);
        spawnDisplay(base, base.clone().add(0.20, 1.02, 0.20), Material.OBSIDIAN, new Vector3f(0f, 0f, 0f), new Vector3f(0.60f, 0.05f, 0.60f));
    }

    private void spawnMeatRefineryVisual(Block block) {
        Location base = block.getLocation();
        // Rdzeń maszyny jest niskim detektorem światła z PDC, więc nie zasłania piły renderowanej jako BlockDisplay.
        spawnDisplay(base, base, Material.STONECUTTER, new Vector3f(-0.07f, -0.07f, -0.07f), new Vector3f(1.14f, 1.14f, 1.14f));
        brightPanel(base, Material.IRON_BLOCK, -0.05f, -0.05f, -0.05f, 0.10f, 1.05f, 1.10f, 4);
        brightPanel(base, Material.IRON_BLOCK, 0.95f, -0.05f, -0.05f, 0.10f, 1.05f, 1.10f, 4);
        brightPanel(base, Material.IRON_BLOCK, -0.05f, -0.05f, -0.05f, 1.10f, 1.05f, 0.10f, 4);
        brightPanel(base, Material.IRON_BLOCK, -0.05f, -0.05f, 0.95f, 1.10f, 1.05f, 0.10f, 4);
        brightPanel(base, Material.IRON_BLOCK, -0.05f, -0.05f, -0.05f, 1.10f, 0.10f, 1.10f, 4);
    }

    private void brightPanel(Location base, Material material, float x, float y, float z, float sx, float sy, float sz, int brightness) {
        BlockDisplay display = spawnDisplay(base, base, material, new Vector3f(x, y, z), new Vector3f(sx, sy, sz));
        display.setBrightness(new Display.Brightness(clampLight(brightness), clampLight(brightness)));
    }

    private void spawnElectricFurnaceVisual(Block block) {
        Location base = block.getLocation();
        BlockFace front = machines.facing(block);
        // Piec zostaje właściwym blokiem z widocznym frontem, a netherrackowe ściany są cienkimi panelami DisplayBlock.
        panel(base, Material.NETHERRACK, -0.035f, -0.035f, -0.035f, 0.05f, 1.07f, 1.07f, front != BlockFace.WEST);
        panel(base, Material.NETHERRACK, 0.985f, -0.035f, -0.035f, 0.05f, 1.07f, 1.07f, front != BlockFace.EAST);
        panel(base, Material.NETHERRACK, -0.035f, -0.035f, -0.035f, 1.07f, 1.07f, 0.05f, front != BlockFace.NORTH);
        panel(base, Material.NETHERRACK, -0.035f, -0.035f, 0.985f, 1.07f, 1.07f, 0.05f, front != BlockFace.SOUTH);
        panel(base, Material.NETHERRACK, -0.035f, 0.985f, -0.035f, 1.07f, 0.05f, 1.07f, true);
    }

    private void panel(Location base, Material material, float x, float y, float z, float sx, float sy, float sz, boolean enabled) {
        if (!enabled) return;
        spawnDisplay(base, base, material, new Vector3f(x, y, z), new Vector3f(sx, sy, sz));
    }

    private BlockDisplay brightDisplay(Location ownerBlockLocation, Location spawnLocation, Material material, Vector3f translation, Vector3f scale) {
        return spawnDisplay(ownerBlockLocation, spawnLocation, material, translation, scale);
    }

    private BlockDisplay spawnDisplay(Location ownerBlockLocation, Location spawnLocation, Material material, Vector3f translation, Vector3f scale) {
        return spawnDisplay(ownerBlockLocation, spawnLocation, material.createBlockData(), translation, scale);
    }

    private BlockDisplay spawnDisplay(Location ownerBlockLocation, Location spawnLocation, BlockData blockData, Vector3f translation, Vector3f scale) {
        BlockDisplay display = (BlockDisplay) spawnLocation.getWorld().spawnEntity(spawnLocation, EntityType.BLOCK_DISPLAY);
        display.setBlock(blockData);
        display.setTransformation(new Transformation(translation, new AxisAngle4f(), scale, new AxisAngle4f()));
        display.setBillboard(Display.Billboard.FIXED);
        applyConfiguredVisualBrightness(display, ownerBlockLocation, blockData.getMaterial());
        display.setPersistent(true);
        String ownerKey = machines.key(ownerBlockLocation);
        display.getPersistentDataContainer().set(machineVisualKey, PersistentDataType.STRING, ownerKey);
        towns.townAt(ownerBlockLocation).ifPresent(town ->
                display.getPersistentDataContainer().set(machineVisualTownKey, PersistentDataType.STRING, town.id().toString()));
        display.getPersistentDataContainer().set(objectTypeKey, PersistentDataType.STRING, "machine_visual");
        display.getPersistentDataContainer().set(objectIdKey, PersistentDataType.STRING, ownerKey);
        return display;
    }

    private void applyConfiguredVisualBrightness(BlockDisplay display) {
        applyConfiguredVisualBrightness(display, display == null ? null : display.getLocation(), null);
    }

    private void applyConfiguredVisualBrightness(BlockDisplay display, Location sampleLocation, Material material) {
        if (display == null) return;
        if (!plugin.getConfig().getBoolean("minions.machines.visual-lighting.override-enabled", true)) return;
        if (isHighlightedLightingMaterial(material)) {
            int blockLight = clampLight(plugin.getConfig().getInt("minions.machines.visual-lighting.highlighted-materials.block-light", 15));
            int skyLight = clampLight(plugin.getConfig().getInt("minions.machines.visual-lighting.highlighted-materials.sky-light", 15));
            display.setBrightness(new Display.Brightness(blockLight, skyLight));
            return;
        }
        // Wartość -1 oznacza światło z okolicy, a nie z dokładnego punktu Displaya.
        // Display często siedzi w środku bloku maszyny albo na przecięciu kilku paneli, co potrafi zwrócić 0 światła.
        int configuredBlockLight = plugin.getConfig().getInt("minions.machines.visual-lighting.block-light", -1);
        int configuredSkyLight = plugin.getConfig().getInt("minions.machines.visual-lighting.sky-light", -1);
        Location effectiveSample = sampleLocation == null ? display.getLocation() : sampleLocation;
        int blockLight = configuredBlockLight >= 0
                ? clampLight(configuredBlockLight)
                : sampledLight(effectiveSample, true);
        int skyLight = configuredSkyLight >= 0
                ? clampLight(configuredSkyLight)
                : sampledLight(effectiveSample, false);
        blockLight = Math.max(blockLight, clampLight(plugin.getConfig().getInt("minions.machines.visual-lighting.min-block-light", 0)));
        skyLight = Math.max(skyLight, clampLight(plugin.getConfig().getInt("minions.machines.visual-lighting.min-sky-light", 0)));
        display.setBrightness(new Display.Brightness(clampLight(blockLight), clampLight(skyLight)));
    }

    private boolean isHighlightedLightingMaterial(Material material) {
        if (material == null) return false;
        if (!plugin.getConfig().getBoolean("minions.machines.visual-lighting.highlighted-materials.enabled", true)) return false;
        List<String> names = plugin.getConfig().getStringList("minions.machines.visual-lighting.highlighted-materials.materials");
        if (names.isEmpty()) {
            return material == Material.BLUE_CONCRETE || material == Material.ORANGE_CONCRETE || material == Material.LIGHT_BLUE_CONCRETE;
        }
        String current = material.name();
        for (String name : names) {
            if (current.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private int sampledLight(Location location, boolean blockLight) {
        if (location == null || location.getWorld() == null) return 0;
        if (!plugin.getConfig().getBoolean("minions.machines.visual-lighting.sample-nearby-blocks", true)) {
            Block block = location.getBlock();
            return blockLight ? block.getLightFromBlocks() : block.getLightFromSky();
        }
        int radius = Math.max(0, Math.min(3, plugin.getConfig().getInt("minions.machines.visual-lighting.sample-radius-blocks", 1)));
        int best = 0;
        int baseX = location.getBlockX();
        int baseY = location.getBlockY();
        int baseZ = location.getBlockZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block block = location.getWorld().getBlockAt(baseX + dx, baseY + dy, baseZ + dz);
                    int value = blockLight ? block.getLightFromBlocks() : block.getLightFromSky();
                    if (value > best) best = value;
                    if (best >= 15) return 15;
                }
            }
        }
        return best;
    }

    private int clampLight(int value) {
        return Math.max(0, Math.min(15, value));
    }

    private void cleanupVisuals(Location loc) {
        // Centralny cleanup usuwa zarówno aktualne displaye z PDC, jak i stare nieotagowane
        // wizualizacje zakotwiczone w bloku maszyny. Dzięki temu break/rotate/interact korzystają
        // z dokładnie tej samej logiki co self-healing runtime'ów.
        machines.cleanupMachineVisuals(loc, true);
    }

    private ItemStack named(Material material, String name, String lore) {
        return named(new ItemStack(material), name, lore);
    }

    private ItemStack named(ItemStack template, String name, String lore) {
        ItemStack item = template == null ? new ItemStack(Material.PAPER) : template.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(parseComponent(name));
            if (lore != null && !lore.isBlank()) meta.lore(java.util.Arrays.stream(lore.split("\\n")).map(this::parseComponent).toList());
            if ((name == null || name.isBlank()) && (lore == null || lore.isBlank())) hideTooltip(meta);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void hideTooltip(ItemMeta meta) {
        if (meta != null) meta.setHideTooltip(true);
    }

    private Component parseComponent(String text) {
        if (text == null) return Component.empty();
        if (text.contains("<") && text.contains(">")) return mini.deserialize(text);
        if (text.indexOf('§') >= 0) return LegacyComponentSerializer.legacySection().deserialize(text);
        return Component.text(text);
    }

    private boolean isUraniumStorageChest(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        return machines.minionsService().specialItems().readSpecialItemId(item)
                .map("iron_uranium_chest"::equalsIgnoreCase).orElse(false)
                || itemFactory.readStorageChestItem(item)
                .map(data -> "iron_uranium".equalsIgnoreCase(data.id())).orElse(false);
    }

}
