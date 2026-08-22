package hex.minions.robot;

import hex.core.api.HexApi;
import hex.minions.crafting.RobotUpgradeDefinition;
import hex.minions.diagnostics.PlaceableItemPolicy;
import hex.minions.menu.RobotListMenuHolder;
import hex.minions.menu.RobotMenuHolder;
import hex.minions.service.MinionService;
import hex.towns.api.TownsApi;
import hex.towns.model.Town;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class MiningRobotManager implements Listener {
    private static final int[] LIST_SLOTS = {20, 24};
    private static final int[] UPGRADE_SLOTS = {10, 11, 12};
    private static final int STORAGE_UPGRADE_SLOT = 16;
    private static final int TOGGLE_SLOT = 22;
    private static final int FUEL_SLOT = 28;
    private static final int PICKAXE_SLOT = 30;
    private static final int[] STORAGE_SLOTS = {32, 33, 34, 35, 37, 38, 39, 40, 41, 42, 43, 44};
    private static final int SUMMON_SLOT = 48;
    private static final int PICKUP_SLOT = 50;
    private static final int CLOSE_SLOT = 53;

    private final Plugin plugin;
    private final HexApi hex;
    private final TownsApi towns;
    private final MinionService minionService;
    private final NamespacedKey visualKey;
    private final NamespacedKey visualTownKey;
    private final NamespacedKey visualTypeKey;
    private final NamespacedKey visualObjectIdKey;
    private final ConcurrentMap<UUID, MiningRobot> robots = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UUID> robotsByBlock = new ConcurrentHashMap<>();
    private RobotConfig config;
    private Material robotPhysicalBlock;
    private File dataFile;
    private YamlConfiguration dataYaml;
    private BukkitTask tickTask;
    private BukkitTask saveTask;
    private long tick;
    private int cursor;
    private volatile boolean dirty;

    public MiningRobotManager(Plugin plugin, HexApi hex, TownsApi towns, MinionService minionService) {
        this.plugin = plugin;
        this.hex = hex;
        this.towns = towns;
        this.minionService = minionService;
        this.visualKey = new NamespacedKey(plugin, "mining_robot_visual");
        this.visualTownKey = new NamespacedKey(plugin, "town_uuid");
        this.visualTypeKey = new NamespacedKey(plugin, "object_type");
        this.visualObjectIdKey = new NamespacedKey(plugin, "object_id");
        reload();
    }

    public void reload() {
        boolean wasEnabled = this.config != null && this.config.enabled();
        File robotsConfigFile = new File(plugin.getDataFolder(), "robots.yml");
        this.config = RobotConfig.load(YamlConfiguration.loadConfiguration(robotsConfigFile));
        File specialItemsFile = new File(plugin.getDataFolder(), "special-items.yml");
        if (!specialItemsFile.exists()) plugin.saveResource("special-items.yml", false);
        ConfigurationSection stations = YamlConfiguration.loadConfiguration(specialItemsFile).getConfigurationSection("crafting-stations");
        this.robotPhysicalBlock = PlaceableItemPolicy.configuredStationBlock(this.config.stationId(), stations).orElse(null);
        if (wasEnabled && !this.config.enabled()) {
            shutdownTasksOnly();
            for (MiningRobot robot : robots.values()) cleanupVisuals(robot.location());
            robots.clear();
            robotsByBlock.clear();
            dirty = false;
        }
    }

    public void load() {
        dataFile = new File(plugin.getDataFolder(), "robot-data.yml");
        dataYaml = YamlConfiguration.loadConfiguration(dataFile);
        robots.clear();
        robotsByBlock.clear();
        dirty = false;
        if (!config.enabled()) return;
        var root = dataYaml.getConfigurationSection("robots");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String path = "robots." + key + ".";
                UUID owner = UUID.fromString(dataYaml.getString(path + "owner", ""));
                UUID town = parseUuid(dataYaml.getString(path + "town", ""));
                World world = Bukkit.getWorld(dataYaml.getString(path + "world", ""));
                if (world == null) continue;
                Location loc = new Location(world, dataYaml.getInt(path + "x"), dataYaml.getInt(path + "y"), dataYaml.getInt(path + "z"));
                BlockFace facing = parseFace(dataYaml.getString(path + "facing", "NORTH"));
                MiningRobot robot = new MiningRobot(id, owner, town, loc, facing);
                robot.setActive(dataYaml.getBoolean(path + "active", false));
                robot.setFuelSecondsRemaining(dataYaml.getInt(path + "fuel-seconds", 0));
                robot.setNextWorkTick(dataYaml.getLong(path + "next-work-tick", 0L));
                readArray(path + "upgrades", robot.upgrades());
                robot.setStorageUpgrade(readItem(path + "storage-upgrade"));
                robot.setFuel(readItem(path + "fuel"));
                robot.setPickaxe(readItem(path + "pickaxe"));
                readArray(path + "storage", robot.storage());
                if (!validLoadedRobot(robot)) {
                    plugin.getLogger().warning("[RobotLifecycle] quarantined orphan robot=" + robot.id() + " town=" + robot.townId());
                    cleanupVisuals(loc);
                    dataYaml.set("robots." + key, null);
                    dirty = true;
                    continue;
                }
                index(robot);
            } catch (Exception ex) {
                plugin.getLogger().warning("Nie udało się wczytać robota górniczego " + key + ": " + ex.getMessage());
            }
        }
        if (dirty) saveYamlSnapshot(dataYaml);
    }

    public void start() {
        shutdownTasksOnly();
        if (!config.enabled()) return;
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickRobots, 20L, 1L);
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveIfDirty, 20L * 30L, 20L * 30L);
    }

    public void shutdown() {
        shutdownTasksOnly();
        if (config.enabled()) saveNow();
        for (MiningRobot robot : robots.values()) cleanupVisuals(robot.location());
    }

    private void shutdownTasksOnly() {
        if (tickTask != null) tickTask.cancel();
        if (saveTask != null) saveTask.cancel();
        tickTask = null;
        saveTask = null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlaceRobotItem(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isRobotItemIdentity(item)) return;
        // Hidden content remains disabled. Once the existing feature flag is deliberately enabled,
        // this same path handles the non-block BRICK carrier without needing BlockPlaceEvent.
        if (!config.enabled()) return;

        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        event.setCancelled(true);

        Block target = event.getClickedBlock().getRelative(event.getBlockFace());
        Optional<String> validationError = validateRobotPlacement(player, target, true);
        if (validationError.isPresent()) {
            player.sendMessage(validationError.get());
            return;
        }
        if (robotPhysicalBlock == null || !robotPhysicalBlock.isBlock()) {
            plugin.getLogger().severe("Robot " + config.robotItemId() + " nie ma poprawnego fizycznego bloku dla stacji " + config.stationId() + ".");
            player.sendMessage("§cRobot nie może zostać postawiony z powodu błędu konfiguracji.");
            return;
        }

        Material previous = target.getType();
        try {
            target.setType(robotPhysicalBlock, false);
            orientRobotBlock(target, player);
            minionService.specialItems().markSpecialBlock(target, config.stationId());
            MiningRobot robot = initializePlacedRobot(player, target);
            if (robot == null) throw new IllegalStateException("Nie udało się zainicjalizować runtime robota.");
        } catch (Throwable throwable) {
            rollbackRobotAt(target);
            minionService.specialItems().unmarkSpecialBlock(target);
            target.setType(previous, false);
            plugin.getLogger().warning("Nie udało się postawić robota górniczego: " + throwable.getMessage());
            player.sendMessage("§cNie udało się postawić robota. Przedmiot nie został zużyty.");
            return;
        }
        consumeRobotItem(player, item);
    }

    /** Compatibility validation for legacy block-carrier robot items. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void validateRobotPlace(BlockPlaceEvent event) {
        if (!isRobotItem(event.getItemInHand())) return;
        Optional<String> error = validateRobotPlacement(event.getPlayer(), event.getBlockPlaced(), false);
        if (error.isPresent()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(error.get());
        }
    }

    /** Compatibility initialization for legacy block-carrier robot items. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterRobotPlace(BlockPlaceEvent event) {
        if (!isRobotItem(event.getItemInHand())) return;
        Block block = event.getBlockPlaced();
        if (!isRobotBlock(block)) return;
        MiningRobot robot = initializePlacedRobot(event.getPlayer(), block);
        if (robot != null) return;
        rollbackRobotAt(block);
        minionService.specialItems().unmarkSpecialBlock(block);
        try { event.getBlockReplacedState().update(true, false); }
        catch (Throwable ignored) { block.setType(Material.AIR, false); }
        plugin.getLogger().warning("Legacy placement robota nie został zainicjalizowany; przywrócono poprzedni stan bloku.");
    }

    private Optional<String> validateRobotPlacement(Player player, Block target, boolean requireAir) {
        if (player == null || target == null) return Optional.of("§cNieprawidłowe miejsce dla robota górniczego.");
        if (!config.enabled()) return Optional.of("§cRobot górniczy jest obecnie wyłączony.");
        if (requireAir && !target.getType().isAir()) return Optional.of("§cW tym miejscu nie można postawić robota górniczego.");
        UUID playerId = player.getUniqueId();
        if (robotsByOwner(playerId).size() >= config.maxPerPlayer()) {
            return Optional.of("§cMożesz mieć maksymalnie " + config.maxPerPlayer() + " roboty górnicze.");
        }
        if (!canRobotModify(playerId, target.getLocation())) {
            return Optional.of("§cRobota górniczego możesz postawić tylko tam, gdzie możesz kopać i budować.");
        }
        return Optional.empty();
    }

    private MiningRobot initializePlacedRobot(Player player, Block block) {
        if (player == null || block == null || !config.enabled()) return null;
        UUID owner = player.getUniqueId();
        Optional<Town> town = towns.townAt(block.getLocation());
        MiningRobot robot = new MiningRobot(UUID.randomUUID(), owner, town.map(Town::id).orElse(null), block.getLocation(), facingFromPlayer(player));
        try {
            index(robot);
            render(robot);
            markDirty();
            return robot;
        } catch (Throwable throwable) {
            cleanupVisuals(block.getLocation());
            robots.remove(robot.id());
            robotsByBlock.remove(key(block.getLocation()), robot.id());
            plugin.getLogger().warning("Inicjalizacja robota górniczego nie powiodła się: " + throwable.getMessage());
            return null;
        }
    }

    private void rollbackRobotAt(Block block) {
        if (block == null) return;
        UUID id = robotsByBlock.remove(key(block.getLocation()));
        if (id != null) robots.remove(id);
        cleanupVisuals(block.getLocation());
    }

    private void orientRobotBlock(Block block, Player player) {
        BlockData data = block.getBlockData();
        if (!(data instanceof Directional directional)) return;
        directional.setFacing(facingFromPlayer(player));
        block.setBlockData(directional, false);
    }

    private void consumeRobotItem(Player player, ItemStack item) {
        if (player == null || item == null || player.getGameMode() == GameMode.CREATIVE) return;
        if (item.getAmount() <= 1) player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        else item.setAmount(item.getAmount() - 1);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onUseRobot(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        if (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        MiningRobot robot = robotAt(event.getClickedBlock()).orElse(null);
        if (robot == null) return;
        event.setCancelled(true);
        if (!canAccess(event.getPlayer(), robot)) {
            event.getPlayer().sendMessage("§cNie masz dostępu do tego robota górniczego.");
            return;
        }
        openRobot(event.getPlayer(), robot.id());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBreakRobot(BlockBreakEvent event) {
        MiningRobot robot = robotAt(event.getBlock()).orElse(null);
        if (robot == null) return;
        event.setCancelled(true);
        if (!canAccess(event.getPlayer(), robot)) {
            event.getPlayer().sendMessage("§cNie masz dostępu do tego robota górniczego.");
            return;
        }
        event.getPlayer().sendMessage("§eOtwórz menu robota i użyj przycisku podniesienia, żeby bezpiecznie go zabrać.");
        openRobot(event.getPlayer(), robot.id());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!config.enabled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof RobotListMenuHolder holder) {
            event.setCancelled(true);
            if (!holder.viewerId().equals(player.getUniqueId())) return;
            int index = indexOf(LIST_SLOTS, event.getRawSlot());
            if (index >= 0) {
                List<MiningRobot> owned = robotsByOwner(player.getUniqueId());
                if (index < owned.size()) openRobot(player, owned.get(index).id());
            } else if (event.getRawSlot() == CLOSE_SLOT) {
                player.closeInventory();
            }
            return;
        }
        if (!(top.getHolder() instanceof RobotMenuHolder holder)) return;
        if (!holder.viewerId().equals(player.getUniqueId())) { event.setCancelled(true); return; }
        MiningRobot robot = robots.get(holder.robotId());
        if (robot == null) { event.setCancelled(true); player.closeInventory(); return; }
        boolean topClick = event.getClickedInventory() != null && event.getClickedInventory().equals(top);
        if (!topClick) {
            if (event.isShiftClick()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sanitizeRobotEquipment(player, robot, top);
                    syncFromMenu(robot, top);
                    applyMenuGuides(robot, top);
                    markDirty();
                });
            }
            return;
        }
        int slot = event.getSlot();
        if (slot == TOGGLE_SLOT) {
            event.setCancelled(true);
            syncFromMenu(robot, top);
            toggleRobot(player, robot);
            openRobot(player, robot.id());
            return;
        }
        if (slot == SUMMON_SLOT) {
            event.setCancelled(true);
            syncFromMenu(robot, top);
            summonRobot(player, robot);
            openRobot(player, robot.id());
            return;
        }
        if (slot == PICKUP_SLOT) {
            event.setCancelled(true);
            syncFromMenu(robot, top);
            pickupRobot(player, robot);
            return;
        }
        if (slot == CLOSE_SLOT) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        if (isEditableRobotSlot(robot, slot)) {
            ItemStack cursorItem = clean(event.getCursor());
            ItemStack current = top.getItem(slot);
            if (cursorItem != null && !isValidRobotSlotItem(robot, top, slot, cursorItem)) {
                event.setCancelled(true);
                player.sendMessage("§cTen przedmiot nie pasuje do wybranego slotu robota.");
                return;
            }
            if (isRobotMenuGuide(current)) {
                event.setCancelled(true);
                if (cursorItem != null) {
                    top.setItem(slot, cursorItem.clone());
                    event.setCursor(null);
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                sanitizeRobotEquipment(player, robot, top);
                syncFromMenu(robot, top);
                applyMenuGuides(robot, top);
                markDirty();
            });
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!config.enabled()) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof RobotMenuHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player) || !holder.viewerId().equals(player.getUniqueId())) return;
        boolean touchesEquipment = event.getRawSlots().stream().anyMatch(raw -> raw < top.getSize() && isRobotEquipmentSlot(raw));
        if (!touchesEquipment) return;
        MiningRobot robot = robots.get(holder.robotId());
        if (robot == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            sanitizeRobotEquipment(player, robot, top);
            syncFromMenu(robot, top);
            applyMenuGuides(robot, top);
            markDirty();
        });
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!config.enabled()) return;
        Inventory top = event.getInventory();
        if (!(top.getHolder() instanceof RobotMenuHolder holder)) return;
        MiningRobot robot = robots.get(holder.robotId());
        if (robot == null) return;
        Player player = event.getPlayer() instanceof Player p ? p : null;
        sanitizeRobotEquipment(player, robot, top);
        syncFromMenu(robot, top);
        markDirty();
    }

    public void openList(Player player) {
        if (!config.enabled()) return;
        Inventory inv = Bukkit.createInventory(new RobotListMenuHolder(player.getUniqueId()), 54, "§8Roboty górnicze");
        fill(inv);
        List<MiningRobot> owned = robotsByOwner(player.getUniqueId());
        inv.setItem(4, named(Material.PLAYER_HEAD, "§bRoboty górnicze", List.of(
                "§7Limit: §f" + owned.size() + "§7/§f" + config.maxPerPlayer(),
                "§7Każdy robot ma własne paliwo, kilof i magazyn.",
                "§8Kliknij slot robota, aby otworzyć jego menu."
        )));
        for (int i = 0; i < LIST_SLOTS.length; i++) {
            if (i < owned.size()) {
                MiningRobot robot = owned.get(i);
                inv.setItem(LIST_SLOTS[i], named(Material.PLAYER_HEAD, "§aRobot górniczy #" + (i + 1), robotLore(robot)));
            } else {
                inv.setItem(LIST_SLOTS[i], named(Material.GRAY_STAINED_GLASS_PANE, "§7Wolny slot robota", List.of("§8Postaw item Robota górniczego tam, gdzie możesz kopać.")));
            }
        }
        inv.setItem(CLOSE_SLOT, named(Material.BARRIER, "§cZamknij", List.of()));
        player.openInventory(inv);
    }

    public void openRobot(Player player, UUID robotId) {
        if (!config.enabled()) return;
        MiningRobot robot = robots.get(robotId);
        if (robot == null) { player.sendMessage("§cNie znaleziono robota."); return; }
        if (!canAccess(player, robot)) { player.sendMessage("§cBrak dostępu do tego robota."); return; }
        Inventory inv = Bukkit.createInventory(new RobotMenuHolder(player.getUniqueId(), robot.id()), 54, "§8Robot górniczy");
        fill(inv);
        inv.setItem(4, named(Material.STONE, "§bRobot górniczy", robotLore(robot)));
        inv.setItem(9, named(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "§bUlepszenia", List.of(
                "§7Moduły szybkości, paliwa i ochrony kilofa.",
                "§7Każdy typ modułu może być użyty tylko raz."
        )));
        inv.setItem(15, named(Material.CHEST, "§eRozszerzenie magazynu", List.of("§7Własne rozszerzenie robota, nie zwykły magazyn miniona.")));
        inv.setItem(27, named(Material.SUGAR, "§fPaliwo", List.of(
                "§7Kostki cukru: 1 szt. = " + effectiveFuelSecondsPerItem(robot) + " sekund pracy.",
                "§8Wartość bazowa: " + config.fuelSecondsPerItem() + " sekund."
        )));
        inv.setItem(29, named(Material.DIAMOND_PICKAXE, "§bKilof", List.of("§7Obsługiwany jest tylko diamentowy kilof.")));
        for (int i = 0; i < UPGRADE_SLOTS.length; i++) inv.setItem(UPGRADE_SLOTS[i], cloneOrNull(robot.upgrades()[i]));
        inv.setItem(STORAGE_UPGRADE_SLOT, cloneOrNull(robot.storageUpgrade()));
        inv.setItem(FUEL_SLOT, cloneOrNull(robot.fuel()));
        inv.setItem(PICKAXE_SLOT, cloneOrNull(robot.pickaxe()));
        for (int i = 0; i < STORAGE_SLOTS.length; i++) {
            if (i < unlockedStorageSlots(robot)) inv.setItem(STORAGE_SLOTS[i], cloneOrNull(robot.storage()[i]));
            else inv.setItem(STORAGE_SLOTS[i], named(Material.BARRIER, "§cZablokowany slot magazynu", List.of("§7Włóż rozszerzenie magazynu robota.")));
        }
        applyMenuGuides(robot, inv);
        player.openInventory(inv);
    }

    private void applyMenuGuides(MiningRobot robot, Inventory inv) {
        for (int slot : UPGRADE_SLOTS) {
            ItemStack current = inv.getItem(slot);
            if (current == null || current.getType().isAir()) {
                inv.setItem(slot, named(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "§bSlot ulepszenia robota", List.of(
                        "§7Włóż dedykowany moduł robota.",
                        "§8Szybkość / paliwo / ochrona kilofa."
                )));
            }
        }
        inv.setItem(TOGGLE_SLOT, robot.active()
                ? named(Material.LIME_DYE, "§aWłączony", List.of("§7Kliknij, aby zatrzymać robota."))
                : named(Material.BARRIER, "§cWyłączony", List.of("§7Kliknij, aby uruchomić, jeśli ma paliwo i diamentowy kilof.")));
        inv.setItem(SUMMON_SLOT, named(Material.ENDER_PEARL, "§bPrzywołaj do siebie", List.of(
                "§7Przenosi robota blisko ciebie, jeśli jest miejsce.",
                "§cPrzywołanie wyłącza robota i resetuje aktualnie zużywane paliwo."
        )));
        inv.setItem(PICKUP_SLOT, named(Material.HOPPER, "§ePodnieś robota", List.of(
                "§7Działa tylko, gdy robot jest w promieniu 2 bloków.",
                "§7Zawartość robota zostanie wysypana obok niego."
        )));
        inv.setItem(CLOSE_SLOT, named(Material.BARRIER, "§cZamknij", List.of()));
    }

    private void tickRobots() {
        if (!config.enabled()) return;
        tick++;
        List<MiningRobot> active = robots.values().stream().filter(MiningRobot::active).toList();
        if (active.isEmpty()) return;
        int processed = 0;
        while (processed < config.tickBatchSize() && processed < active.size()) {
            if (cursor >= active.size()) cursor = 0;
            MiningRobot robot = active.get(cursor++);
            if (tick >= robot.nextWorkTick()) {
                processRobot(robot);
                processed++;
            } else {
                processed++;
            }
        }
    }

    private void processRobot(MiningRobot robot) {
        Location loc = robot.location();
        if (loc == null || loc.getWorld() == null || !loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return;
        if (!hasUsablePickaxe(robot)) { stopRobot(robot); return; }
        if (!ensureFuel(robot)) { stopRobot(robot); return; }
        Block target = nextMineTarget(robot);
        boolean worked;
        if (target == null) {
            worked = moveForward(robot);
        } else {
            mineBlock(robot, target);
            worked = true;
        }
        int intervalTicks = effectiveBlockIntervalTicks(robot);
        if (worked) {
            int secondsUsed = Math.max(1, (int) Math.ceil(intervalTicks / 20.0D));
            robot.setFuelSecondsRemaining(Math.max(0, robot.fuelSecondsRemaining() - secondsUsed));
        }
        robot.setNextWorkTick(tick + intervalTicks);
        markDirty();
    }

    private Block nextMineTarget(MiningRobot robot) {
        Location base = robot.location();
        if (base == null) return null;
        List<Block> targets = targetPlane(robot);
        for (Block block : targets) {
            if (canMine(robot, block)) return block;
        }
        return null;
    }

    private List<Block> targetPlane(MiningRobot robot) {
        Location base = robot.location();
        List<Block> result = new ArrayList<>(9);
        if (base == null || base.getWorld() == null) return result;
        BlockFace forward = robot.facing();
        Vec f = Vec.of(forward);
        Vec up = upVector(forward);
        Vec right = rightVector(forward);
        int bx = base.getBlockX();
        int by = base.getBlockY();
        int bz = base.getBlockZ();
        for (int v = 1; v >= -1; v--) {
            for (int h = -1; h <= 1; h++) {
                int x = bx + f.x + right.x * h + up.x * v;
                int y = by + f.y + right.y * h + up.y * v;
                int z = bz + f.z + right.z * h + up.z * v;
                result.add(base.getWorld().getBlockAt(x, y, z));
            }
        }
        return result;
    }

    private boolean canMine(MiningRobot robot, Block block) {
        if (block == null || block.getType().isAir()) return false;
        if (block.isLiquid()) return false;
        if (block.getType() == Material.BEDROCK || block.getType() == Material.BARRIER || block.getType() == Material.END_PORTAL_FRAME || block.getType() == Material.END_PORTAL) return false;
        if (!canRobotModify(robot, block.getLocation())) return false;
        if (config.protectedTownOnly()) {
            Optional<Town> town = towns.townAt(block.getLocation());
            if (town.isEmpty() || robot.townId() == null || !town.get().id().equals(robot.townId())) return false;
        }
        return true;
    }

    private void mineBlock(MiningRobot robot, Block block) {
        ItemStack tool = robot.pickaxe();
        List<ItemStack> drops = new ArrayList<>(block.getDrops(tool));
        Location dropLoc = block.getLocation().add(0.5, 0.5, 0.5);
        block.setType(Material.AIR, false);
        for (ItemStack drop : drops) {
            if (drop == null || drop.getType().isAir()) continue;
            ItemStack left = deposit(robot, drop);
            if (left != null && !left.getType().isAir()) dropLoc.getWorld().dropItemNaturally(dropLoc, left);
        }
        damagePickaxe(robot);
        dropLoc.getWorld().playSound(dropLoc, Sound.BLOCK_STONE_BREAK, 0.4f, 1.0f);
    }

    private boolean moveForward(MiningRobot robot) {
        Location old = robot.location();
        if (old == null) return false;
        Vec f = Vec.of(robot.facing());
        Location next = old.clone().add(f.x, f.y, f.z);
        if (!canRobotModify(robot, old) || !canRobotModify(robot, next)) { stopRobot(robot); return false; }
        if (config.protectedTownOnly()) {
            Optional<Town> town = towns.townAt(next);
            if (town.isEmpty() || robot.townId() == null || !town.get().id().equals(robot.townId())) { stopRobot(robot); return false; }
        }
        Block oldBlock = old.getBlock();
        Block nextBlock = next.getBlock();
        if (!nextBlock.getType().isAir()) return false;
        if (robotPhysicalBlock == null || !robotPhysicalBlock.isBlock()) { stopRobot(robot); return false; }
        cleanupVisuals(old);
        robotsByBlock.remove(key(old));
        oldBlock.setType(Material.AIR, false);
        nextBlock.setType(robotPhysicalBlock, false);
        minionService.specialItems().markSpecialBlock(nextBlock, config.stationId());
        robot.moveTo(next, robot.facing());
        robotsByBlock.put(key(next), robot.id());
        render(robot);
        return true;
    }

    private boolean ensureFuel(MiningRobot robot) {
        if (robot.fuelSecondsRemaining() > 0) return true;
        ItemStack fuel = robot.fuel();
        if (!isSpecialItem(fuel, config.fuelItemId()) || fuel.getAmount() <= 0) return false;
        fuel.setAmount(fuel.getAmount() - 1);
        if (fuel.getAmount() <= 0) robot.setFuel(null);
        robot.setFuelSecondsRemaining(effectiveFuelSecondsPerItem(robot));
        return true;
    }

    private boolean hasUsablePickaxe(MiningRobot robot) {
        ItemStack pickaxe = robot.pickaxe();
        return pickaxe != null && pickaxe.getType() == Material.DIAMOND_PICKAXE && pickaxe.getAmount() == 1;
    }

    private void damagePickaxe(MiningRobot robot) {
        if (ThreadLocalRandom.current().nextDouble() < pickaxeDamageSaveChance(robot)) return;
        ItemStack pickaxe = robot.pickaxe();
        ItemMeta meta = pickaxe == null ? null : pickaxe.getItemMeta();
        if (!(meta instanceof Damageable damageable)) return;
        int max = pickaxe.getType().getMaxDurability();
        damageable.setDamage(damageable.getDamage() + 1);
        if (damageable.getDamage() >= max) {
            robot.setPickaxe(null);
            stopRobot(robot);
        } else {
            pickaxe.setItemMeta(meta);
        }
    }

    private ItemStack deposit(MiningRobot robot, ItemStack stack) {
        int remaining = stack.getAmount();
        int unlocked = unlockedStorageSlots(robot);
        for (int i = 0; i < unlocked && remaining > 0; i++) {
            ItemStack current = robot.storage()[i];
            if (current == null || current.getType().isAir()) {
                ItemStack copy = stack.clone();
                copy.setAmount(Math.min(copy.getMaxStackSize(), remaining));
                robot.storage()[i] = copy;
                remaining -= copy.getAmount();
            } else if (current.isSimilar(stack) && current.getAmount() < current.getMaxStackSize()) {
                int add = Math.min(current.getMaxStackSize() - current.getAmount(), remaining);
                current.setAmount(current.getAmount() + add);
                remaining -= add;
            }
        }
        if (remaining <= 0) return null;
        ItemStack left = stack.clone();
        left.setAmount(remaining);
        return left;
    }

    private int unlockedStorageSlots(MiningRobot robot) {
        int slots = config.baseStorageSlots();
        if (isSpecialItem(robot.storageUpgrade(), config.storageUpgradeItemId())) slots += config.storageUpgradeSlots();
        return Math.max(1, Math.min(config.maxStorageSlots(), slots));
    }

    private void toggleRobot(Player player, MiningRobot robot) {
        if (robot.active()) {
            stopRobot(robot);
            player.sendMessage("§eRobot górniczy zatrzymany.");
            return;
        }
        if (!hasUsablePickaxe(robot)) { player.sendMessage("§cRobot wymaga diamentowego kilofa."); return; }
        if (robot.fuelSecondsRemaining() <= 0 && !isSpecialItem(robot.fuel(), config.fuelItemId())) { player.sendMessage("§cRobot wymaga kostek cukru w slocie paliwa."); return; }
        robot.setActive(true);
        robot.setNextWorkTick(tick + effectiveBlockIntervalTicks(robot));
        render(robot);
        markDirty();
        player.sendMessage("§aRobot górniczy uruchomiony.");
    }

    private void stopRobot(MiningRobot robot) {
        robot.setActive(false);
        robot.setNextWorkTick(0L);
        markDirty();
    }

    private void summonRobot(Player player, MiningRobot robot) {
        if (robotPhysicalBlock == null || !robotPhysicalBlock.isBlock()) {
            player.sendMessage("§cRobot ma nieprawidłową konfigurację fizycznego bloku.");
            return;
        }
        Optional<Location> target = findSummonLocation(player);
        if (target.isEmpty()) { player.sendMessage("§cNie ma miejsca na przywołanie robota obok ciebie."); return; }
        Location old = robot.location();
        if (old != null) {
            cleanupVisuals(old);
            robotsByBlock.remove(key(old));
            if (isRobotBlock(old.getBlock())) old.getBlock().setType(Material.AIR, false);
        }
        robot.stopAndResetFuelUse();
        robot.moveTo(target.get(), facingFromPlayer(player));
        robot.setTownId(towns.townAt(target.get()).map(Town::id).orElse(null));
        Block block = target.get().getBlock();
        block.setType(robotPhysicalBlock, false);
        minionService.specialItems().markSpecialBlock(block, config.stationId());
        robotsByBlock.put(key(target.get()), robot.id());
        render(robot);
        markDirty();
        player.sendMessage("§aPrzywołano robota górniczego.");
    }

    private Optional<Location> findSummonLocation(Player player) {
        Location base = player.getLocation();
        List<Location> candidates = new ArrayList<>();
        BlockFace front = player.getFacing();
        candidates.add(base.getBlock().getRelative(front).getLocation());
        candidates.add(base.getBlock().getLocation());
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                candidates.add(base.getBlock().getRelative(dx, 0, dz).getLocation());
            }
        }
        for (Location loc : candidates) {
            if (loc.getWorld() == null) continue;
            if (!loc.getBlock().getType().isAir()) continue;
            if (!canRobotModify(player.getUniqueId(), loc)) continue;
            return Optional.of(loc);
        }
        return Optional.empty();
    }

    private void pickupRobot(Player player, MiningRobot robot) {
        Location loc = robot.location();
        if (loc == null || loc.getWorld() == null || !loc.getWorld().equals(player.getWorld()) || loc.distanceSquared(player.getLocation()) > 4.0D) {
            player.sendMessage("§cMusisz stać maksymalnie 2 bloki od robota, żeby go podnieść.");
            return;
        }
        dropContents(robot, loc.clone().add(0.5, 0.5, 0.5));
        cleanupVisuals(loc);
        robotsByBlock.remove(key(loc));
        if (isRobotBlock(loc.getBlock())) loc.getBlock().setType(Material.AIR, false);
        robots.remove(robot.id());
        ItemStack item = minionService.specialItems().createItem(config.robotItemId(), 1);
        player.getInventory().addItem(item).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        markDirty();
        player.closeInventory();
        player.sendMessage("§aPodniesiono robota górniczego.");
    }

    private void dropContents(MiningRobot robot, Location drop) {
        for (ItemStack item : robot.upgrades()) drop(drop, item);
        drop(drop, robot.storageUpgrade());
        drop(drop, robot.fuel());
        drop(drop, robot.pickaxe());
        for (ItemStack item : robot.storage()) drop(drop, item);
    }

    private void drop(Location drop, ItemStack item) {
        if (drop == null || drop.getWorld() == null || item == null || item.getType().isAir()) return;
        drop.getWorld().dropItemNaturally(drop, item.clone());
    }

    private boolean isValidRobotSlotItem(MiningRobot robot, Inventory inv, int slot, ItemStack item) {
        if (item == null || item.getType().isAir()) return true;
        if (indexOf(UPGRADE_SLOTS, slot) >= 0) {
            Optional<RobotUpgradeDefinition> definition = minionService.specialItems().robotUpgradeByItem(item)
                    .filter(upgrade -> upgrade.supportsRobotType("MINER"));
            if (definition.isEmpty()) return false;
            String id = definition.get().specialItemId();
            for (int other : UPGRADE_SLOTS) {
                if (other == slot) continue;
                String existing = minionService.specialItems().readSpecialItemId(clean(inv.getItem(other))).orElse("");
                if (existing.equalsIgnoreCase(id)) return false;
            }
            return true;
        }
        if (slot == STORAGE_UPGRADE_SLOT) return isSpecialItem(item, config.storageUpgradeItemId());
        if (slot == FUEL_SLOT) return isSpecialItem(item, config.fuelItemId());
        if (slot == PICKAXE_SLOT) return item.getType() == Material.DIAMOND_PICKAXE && item.getAmount() == 1;
        return true;
    }

    private boolean isRobotMenuGuide(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        return item.getType() == Material.LIGHT_BLUE_STAINED_GLASS_PANE;
    }

    private void sanitizeRobotEquipment(Player player, MiningRobot robot, Inventory inv) {
        if (robot == null || inv == null) return;
        Set<String> usedUpgradeIds = new HashSet<>();
        for (int slot : UPGRADE_SLOTS) {
            ItemStack item = cleanRobotUpgradeItem(inv.getItem(slot));
            Optional<RobotUpgradeDefinition> definition = minionService.specialItems().robotUpgradeByItem(item)
                    .filter(upgrade -> upgrade.supportsRobotType("MINER"));
            String itemId = minionService.specialItems().readSpecialItemId(item).orElse("").toLowerCase(Locale.ROOT);
            boolean valid = item == null || (definition.isPresent() && usedUpgradeIds.add(itemId));
            if (!valid) {
                inv.setItem(slot, null);
                returnToPlayer(player, item);
                if (player != null) player.sendMessage("§cTen slot przyjmuje wyłącznie unikalne ulepszenia robota górniczego.");
            }
        }
        sanitizeExactSpecialSlot(player, inv, STORAGE_UPGRADE_SLOT, config.storageUpgradeItemId(), "rozszerzenie magazynu robota");
        sanitizeExactSpecialSlot(player, inv, FUEL_SLOT, config.fuelItemId(), "kostki cukru");
        ItemStack pickaxe = clean(inv.getItem(PICKAXE_SLOT));
        if (pickaxe != null && (pickaxe.getType() != Material.DIAMOND_PICKAXE || pickaxe.getAmount() != 1)) {
            inv.setItem(PICKAXE_SLOT, null);
            returnToPlayer(player, pickaxe);
            if (player != null) player.sendMessage("§cRobot obsługuje wyłącznie pojedynczy diamentowy kilof.");
        }
    }

    private void sanitizeExactSpecialSlot(Player player, Inventory inv, int slot, String requiredId, String label) {
        ItemStack item = clean(inv.getItem(slot));
        if (item == null || isSpecialItem(item, requiredId)) return;
        inv.setItem(slot, null);
        returnToPlayer(player, item);
        if (player != null) player.sendMessage("§cTen slot przyjmuje tylko " + label + ".");
    }

    private void returnToPlayer(Player player, ItemStack item) {
        if (player == null || item == null || item.getType().isAir()) return;
        player.getInventory().addItem(item).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    private boolean isRobotEquipmentSlot(int slot) {
        return indexOf(UPGRADE_SLOTS, slot) >= 0 || slot == STORAGE_UPGRADE_SLOT || slot == FUEL_SLOT || slot == PICKAXE_SLOT;
    }

    private List<RobotUpgradeDefinition> activeRobotUpgrades(MiningRobot robot) {
        if (robot == null) return List.of();
        List<RobotUpgradeDefinition> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (ItemStack item : robot.upgrades()) {
            minionService.specialItems().robotUpgradeByItem(item)
                    .filter(upgrade -> upgrade.supportsRobotType("MINER"))
                    .filter(upgrade -> ids.add(upgrade.specialItemId()))
                    .ifPresent(result::add);
        }
        return result;
    }

    private int effectiveBlockIntervalTicks(MiningRobot robot) {
        double multiplier = 1.0D;
        for (RobotUpgradeDefinition upgrade : activeRobotUpgrades(robot)) multiplier *= upgrade.workIntervalMultiplier();
        return Math.max(1, (int) Math.round(config.blockIntervalTicks() * multiplier));
    }

    private int effectiveFuelSecondsPerItem(MiningRobot robot) {
        double multiplier = 1.0D;
        for (RobotUpgradeDefinition upgrade : activeRobotUpgrades(robot)) multiplier *= upgrade.fuelDurationMultiplier();
        return Math.max(1, (int) Math.round(config.fuelSecondsPerItem() * multiplier));
    }

    private double pickaxeDamageSaveChance(MiningRobot robot) {
        double missProduct = 1.0D;
        for (RobotUpgradeDefinition upgrade : activeRobotUpgrades(robot)) missProduct *= (1.0D - upgrade.pickaxeDamageSaveChance());
        return Math.max(0.0D, Math.min(1.0D, 1.0D - missProduct));
    }

    private void syncFromMenu(MiningRobot robot, Inventory inv) {
        if (inv == null) return;
        for (int i = 0; i < UPGRADE_SLOTS.length; i++) robot.upgrades()[i] = cleanRobotUpgradeItem(inv.getItem(UPGRADE_SLOTS[i]));
        ItemStack storageUpgrade = clean(inv.getItem(STORAGE_UPGRADE_SLOT));
        robot.setStorageUpgrade(storageUpgrade);
        ItemStack fuel = clean(inv.getItem(FUEL_SLOT));
        robot.setFuel(fuel);
        ItemStack pickaxe = clean(inv.getItem(PICKAXE_SLOT));
        robot.setPickaxe(pickaxe);
        int unlocked = unlockedStorageSlots(robot);
        for (int i = 0; i < STORAGE_SLOTS.length; i++) {
            if (i < unlocked) robot.storage()[i] = clean(inv.getItem(STORAGE_SLOTS[i]));
        }
    }

    private boolean isEditableRobotSlot(MiningRobot robot, int slot) {
        if (indexOf(UPGRADE_SLOTS, slot) >= 0) return true;
        if (slot == STORAGE_UPGRADE_SLOT || slot == FUEL_SLOT || slot == PICKAXE_SLOT) return true;
        int storageIndex = indexOf(STORAGE_SLOTS, slot);
        return storageIndex >= 0 && storageIndex < unlockedStorageSlots(robot);
    }

    private ItemStack cleanRobotUpgradeItem(ItemStack item) {
        if (isRobotMenuGuide(item)) return null;
        return clean(item);
    }

    private ItemStack clean(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        if (item.getType() == Material.BLACK_STAINED_GLASS_PANE || item.getType() == Material.BARRIER) return null;
        return item.clone();
    }

    private void render(MiningRobot robot) {
        Location loc = robot.location();
        if (loc == null || loc.getWorld() == null) return;
        cleanupVisuals(loc);
        spawnBlock(robot, Material.STONE, new Vector3f(-0.02f, -0.02f, -0.02f), new Vector3f(1.04f, 1.04f, 1.04f));
        panel(robot, Material.IRON_BLOCK, -0.06f, -0.06f, -0.06f, 0.10f, 0.56f, 1.12f, robot.facing() != BlockFace.WEST);
        panel(robot, Material.IRON_BLOCK, 0.96f, -0.06f, -0.06f, 0.10f, 0.56f, 1.12f, robot.facing() != BlockFace.EAST);
        panel(robot, Material.IRON_BLOCK, -0.06f, -0.06f, -0.06f, 1.12f, 0.56f, 0.10f, robot.facing() != BlockFace.NORTH);
        panel(robot, Material.IRON_BLOCK, -0.06f, -0.06f, 0.96f, 1.12f, 0.56f, 0.10f, robot.facing() != BlockFace.SOUTH);
        panel(robot, Material.IRON_BLOCK, -0.06f, -0.08f, -0.06f, 1.12f, 0.10f, 1.12f, true);
        eyes(robot);
        pickaxeVisual(robot);
    }

    private void panel(MiningRobot robot, Material material, float x, float y, float z, float sx, float sy, float sz, boolean enabled) {
        if (enabled) spawnBlock(robot, material, new Vector3f(x, y, z), new Vector3f(sx, sy, sz));
    }

    private void eyes(MiningRobot robot) {
        Location base = robot.location();
        if (base == null) return;
        BlockFace f = robot.facing();
        if (f == BlockFace.NORTH) {
            spawnBlock(robot, Material.BLACK_CONCRETE, new Vector3f(0.22f, 0.55f, -0.08f), new Vector3f(0.18f, 0.18f, 0.08f));
            spawnBlock(robot, Material.BLACK_CONCRETE, new Vector3f(0.60f, 0.55f, -0.08f), new Vector3f(0.18f, 0.18f, 0.08f));
        } else if (f == BlockFace.SOUTH) {
            spawnBlock(robot, Material.BLACK_CONCRETE, new Vector3f(0.22f, 0.55f, 1.00f), new Vector3f(0.18f, 0.18f, 0.08f));
            spawnBlock(robot, Material.BLACK_CONCRETE, new Vector3f(0.60f, 0.55f, 1.00f), new Vector3f(0.18f, 0.18f, 0.08f));
        } else if (f == BlockFace.EAST) {
            spawnBlock(robot, Material.BLACK_CONCRETE, new Vector3f(1.00f, 0.55f, 0.22f), new Vector3f(0.08f, 0.18f, 0.18f));
            spawnBlock(robot, Material.BLACK_CONCRETE, new Vector3f(1.00f, 0.55f, 0.60f), new Vector3f(0.08f, 0.18f, 0.18f));
        } else if (f == BlockFace.WEST) {
            spawnBlock(robot, Material.BLACK_CONCRETE, new Vector3f(-0.08f, 0.55f, 0.22f), new Vector3f(0.08f, 0.18f, 0.18f));
            spawnBlock(robot, Material.BLACK_CONCRETE, new Vector3f(-0.08f, 0.55f, 0.60f), new Vector3f(0.08f, 0.18f, 0.18f));
        } else {
            spawnBlock(robot, Material.BLACK_CONCRETE, new Vector3f(0.22f, 1.00f, 0.22f), new Vector3f(0.18f, 0.08f, 0.18f));
            spawnBlock(robot, Material.BLACK_CONCRETE, new Vector3f(0.60f, 1.00f, 0.60f), new Vector3f(0.18f, 0.08f, 0.18f));
        }
    }

    private void pickaxeVisual(MiningRobot robot) {
        Location base = robot.location();
        if (base == null || base.getWorld() == null) return;
        Vec right = rightVector(robot.facing());
        // ItemDisplay jest spawniony od razu przy prawej ściance robota. Poprzednia wersja spawnowała go w środku
        // bloku i dopiero przesuwała transformacją, przez co kilof wyglądał jak zatopiony w korpusie.
        Location loc = base.clone().add(0.5 + right.x * 0.72, 0.42, 0.5 + right.z * 0.72);
        loc.setYaw(yawForOutwardVector(right));
        loc.setPitch(0f);
        ItemDisplay display = (ItemDisplay) base.getWorld().spawnEntity(loc, EntityType.ITEM_DISPLAY);
        display.setItemStack(new ItemStack(Material.DIAMOND_PICKAXE));
        display.setTransformation(new Transformation(
                new Vector3f(0.0f, 0.0f, 0.0f),
                new AxisAngle4f((float) Math.toRadians(45), 0f, 0f, 1f),
                new Vector3f(0.55f, 0.55f, 0.55f),
                new AxisAngle4f()
        ));
        display.setBillboard(Display.Billboard.FIXED);
        display.setBrightness(new Display.Brightness(15, 15));
        display.setPersistent(true);
        display.getPersistentDataContainer().set(visualKey, PersistentDataType.STRING, key(robot.location()));
        if (robot.townId() != null) display.getPersistentDataContainer().set(visualTownKey, PersistentDataType.STRING, robot.townId().toString());
        display.getPersistentDataContainer().set(visualTypeKey, PersistentDataType.STRING, "robot_visual");
        display.getPersistentDataContainer().set(visualObjectIdKey, PersistentDataType.STRING, robot.id().toString());
    }

    private float yawForOutwardVector(Vec outward) {
        if (outward == null) return -90f;
        if (outward.x > 0) return -90f;
        if (outward.x < 0) return 90f;
        if (outward.z > 0) return 0f;
        if (outward.z < 0) return 180f;
        return -90f;
    }

    private void spawnBlock(MiningRobot robot, Material material, Vector3f translation, Vector3f scale) {
        Location loc = robot.location();
        if (loc == null || loc.getWorld() == null) return;
        BlockDisplay display = (BlockDisplay) loc.getWorld().spawnEntity(loc, EntityType.BLOCK_DISPLAY);
        display.setBlock(material.createBlockData());
        display.setTransformation(new Transformation(translation, new AxisAngle4f(), scale, new AxisAngle4f()));
        display.setBillboard(Display.Billboard.FIXED);
        display.setBrightness(new Display.Brightness(15, 15));
        display.setPersistent(true);
        display.getPersistentDataContainer().set(visualKey, PersistentDataType.STRING, key(loc));
        if (robot.townId() != null) display.getPersistentDataContainer().set(visualTownKey, PersistentDataType.STRING, robot.townId().toString());
        display.getPersistentDataContainer().set(visualTypeKey, PersistentDataType.STRING, "robot_visual");
        display.getPersistentDataContainer().set(visualObjectIdKey, PersistentDataType.STRING, robot.id().toString());
    }

    private void cleanupVisuals(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        String key = key(loc);
        for (Entity entity : loc.getWorld().getNearbyEntities(loc.clone().add(0.5, 0.5, 0.5), 2.0, 2.0, 2.0)) {
            String stored = entity.getPersistentDataContainer().get(visualKey, PersistentDataType.STRING);
            if (key.equals(stored)) entity.remove();
        }
    }

    private void index(MiningRobot robot) {
        robots.put(robot.id(), robot);
        robotsByBlock.put(key(robot.location()), robot.id());
    }

    private Optional<MiningRobot> robotAt(Block block) {
        if (!config.enabled() || block == null) return Optional.empty();
        UUID id = robotsByBlock.get(key(block.getLocation()));
        if (id != null) return Optional.ofNullable(robots.get(id));
        if (isRobotBlock(block)) {
            for (MiningRobot robot : robots.values()) {
                if (key(block.getLocation()).equals(key(robot.location()))) return Optional.of(robot);
            }
        }
        return Optional.empty();
    }

    private boolean isRobotBlock(Block block) {
        return config.enabled() && block != null && minionService.specialItems().readSpecialBlockId(block).map(id -> id.equalsIgnoreCase(config.stationId())).orElse(false);
    }

    private boolean isRobotItem(ItemStack item) {
        return config.enabled() && isRobotItemIdentity(item);
    }

    private boolean isRobotItemIdentity(ItemStack item) {
        return isSpecialItem(item, config.robotItemId());
    }

    private boolean isSpecialItem(ItemStack item, String id) {
        if (item == null || item.getType().isAir() || id == null) return false;
        return minionService.specialItems().readSpecialItemId(item).map(found -> found.equalsIgnoreCase(id)).orElse(false);
    }

    private boolean canAccess(Player player, MiningRobot robot) {
        if (player == null || robot == null) return false;
        if (robot.ownerId().equals(player.getUniqueId())) return true;
        return robot.townId() != null && towns.canActAsMember(player.getUniqueId(), robot.townId());
    }

    private List<MiningRobot> robotsByOwner(UUID owner) {
        return robots.values().stream()
                .filter(robot -> robot.ownerId().equals(owner))
                .sorted(Comparator.comparing(robot -> robot.id().toString()))
                .toList();
    }

    private List<String> robotLore(MiningRobot robot) {
        List<String> lore = new ArrayList<>();
        lore.add("§7Status: " + (robot.active() ? "§aWłączony" : "§cWyłączony"));
        lore.add("§7Paliwo: §6" + robot.fuelSecondsRemaining() + " s");
        lore.add("§7Tempo pracy: §6" + effectiveBlockIntervalTicks(robot) + " ticków/blok");
        lore.add("§7Paliwo na kostkę: §6" + effectiveFuelSecondsPerItem(robot) + " s");
        lore.add("§7Ochrona kilofa: §6" + Math.round(pickaxeDamageSaveChance(robot) * 100.0D) + "%");
        lore.add("§7Magazyn: §6" + usedStorageSlots(robot) + "§7/§6" + unlockedStorageSlots(robot) + " slotów");
        lore.add("");
        lore.add("§eKliknij, aby otworzyć menu.");
        return lore;
    }

    private int usedStorageSlots(MiningRobot robot) {
        int used = 0;
        for (int i = 0; i < unlockedStorageSlots(robot); i++) {
            ItemStack item = robot.storage()[i];
            if (item != null && !item.getType().isAir()) used++;
        }
        return used;
    }

    private void fill(Inventory inv) {
        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
    }

    private ItemStack named(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material == null ? Material.STONE : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name == null ? " " : name));
            if (lore != null && !lore.isEmpty()) meta.lore(lore.stream().map(Component::text).toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack cloneOrNull(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }

    private static int indexOf(int[] array, int value) {
        for (int i = 0; i < array.length; i++) if (array[i] == value) return i;
        return -1;
    }

    private BlockFace facingFromPlayer(Player player) {
        if (player.getLocation().getPitch() < -55) return BlockFace.UP;
        if (player.getLocation().getPitch() > 55) return BlockFace.DOWN;
        return player.getFacing();
    }

    private BlockFace parseFace(String raw) {
        try { return BlockFace.valueOf(raw == null ? "NORTH" : raw.toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { return BlockFace.NORTH; }
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private boolean validLoadedRobot(MiningRobot robot) {
        if (robot == null || robot.townId() == null || robot.location() == null || robot.location().getWorld() == null) return false;
        if (towns.findTown(robot.townId()).isEmpty()) return false;
        Optional<Town> at = towns.townAt(robot.location());
        if (at.isEmpty() || !robot.townId().equals(at.get().id())) return false;
        return isRobotBlock(robot.location().getBlock());
    }

    private boolean canRobotModify(MiningRobot robot, Location location) {
        if (robot == null || robot.townId() == null || location == null || location.getWorld() == null) return false;
        if (towns.findTown(robot.townId()).isEmpty()) return false;
        Optional<Town> at = towns.townAt(location);
        if (at.isEmpty() || !robot.townId().equals(at.get().id())) return false;
        return towns.isMember(robot.ownerId(), robot.townId());
    }

    public java.util.concurrent.CompletableFuture<Void> purgeTown(UUID townUuid, Set<hex.towns.model.ChunkPos> chunks) {
        if (townUuid == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        java.util.concurrent.CompletableFuture<Void> result = new java.util.concurrent.CompletableFuture<>();
        Runnable work = () -> {
            try {
                int removed = 0;
                for (MiningRobot robot : new ArrayList<>(robots.values())) {
                    if (!townUuid.equals(robot.townId())) continue;
                    stopRobot(robot);
                    Location loc = robot.location();
                    if (loc != null) {
                        cleanupVisuals(loc);
                        robotsByBlock.remove(key(loc), robot.id());
                        if (isRobotBlock(loc.getBlock())) loc.getBlock().setType(Material.AIR, false);
                    }
                    robots.remove(robot.id());
                    removed++;
                }
                int persistedRemoved = purgePersistedTown(townUuid);
                if (removed > 0) {
                    markDirty();
                    if (config.enabled()) saveNow();
                }
                if (removed > 0 || persistedRemoved > 0) {
                    plugin.getLogger().info("[RobotLifecycle] purge town=" + townUuid + " runtime=" + removed + " persisted=" + persistedRemoved);
                }
                result.complete(null);
            } catch (Throwable error) { result.completeExceptionally(error); }
        };
        if (Bukkit.isPrimaryThread()) work.run(); else Bukkit.getScheduler().runTask(plugin, work);
        return result;
    }


    private synchronized int purgePersistedTown(UUID townUuid) {
        if (townUuid == null) return 0;
        if (dataFile == null) dataFile = new File(plugin.getDataFolder(), "robot-data.yml");
        YamlConfiguration yaml = dataYaml != null ? dataYaml : YamlConfiguration.loadConfiguration(dataFile);
        var root = yaml.getConfigurationSection("robots");
        if (root == null) return 0;
        int removed = 0;
        for (String key : new ArrayList<>(root.getKeys(false))) {
            String rawTown = yaml.getString("robots." + key + ".town", "");
            if (townUuid.equals(parseUuid(rawTown))) {
                String path = "robots." + key + ".";
                removePersistedRobotWorldObjects(yaml, path);
                yaml.set("robots." + key, null);
                removed++;
            }
        }
        if (removed > 0) {
            dataYaml = yaml;
            saveYamlSnapshot(yaml);
        }
        return removed;
    }


    private void removePersistedRobotWorldObjects(YamlConfiguration yaml, String path) {
        if (yaml == null || path == null) return;
        World world = Bukkit.getWorld(yaml.getString(path + "world", ""));
        if (world == null) return;
        int x = yaml.getInt(path + "x");
        int y = yaml.getInt(path + "y");
        int z = yaml.getInt(path + "z");
        org.bukkit.Chunk chunk = world.getChunkAt(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        if (!chunk.isLoaded()) chunk.load(true);
        Location location = new Location(world, x, y, z);
        cleanupVisuals(location);
        Block block = world.getBlockAt(x, y, z);
        // Works even while the robot feature flag is disabled: ownership is proven by the
        // exact persisted robot location plus the Hex special-block station marker.
        boolean ownedCarrier = minionService.specialItems().readSpecialBlockId(block)
                .map(id -> id.equalsIgnoreCase(config.stationId())).orElse(false);
        if (ownedCarrier) {
            minionService.specialItems().unmarkSpecialBlock(block);
            block.setType(Material.AIR, false);
        }
    }

    private void saveYamlSnapshot(YamlConfiguration yaml) {
        if (yaml == null || dataFile == null) return;
        try {
            yaml.save(dataFile);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("Nie udało się zapisać robot-data.yml: " + ex.getMessage());
        }
    }

    private boolean canRobotModify(UUID playerId, Location location) {
        if (playerId == null || location == null || location.getWorld() == null) return false;
        Optional<Town> town = towns.townAt(location);
        if (town.isEmpty()) return false;
        Town t = town.get();
        boolean inHeartChunk = t.world().equals(location.getWorld().getName())
                && t.heart().x() == (location.getBlockX() >> 4)
                && t.heart().z() == (location.getBlockZ() >> 4);
        if (inHeartChunk) return false;
        return towns.canActAsMember(playerId, t.id());
    }

    private Vec upVector(BlockFace forward) {
        if (forward == BlockFace.UP || forward == BlockFace.DOWN) return new Vec(0, 0, -1);
        return new Vec(0, 1, 0);
    }

    private Vec rightVector(BlockFace forward) {
        return switch (forward) {
            case NORTH -> new Vec(1, 0, 0);
            case SOUTH -> new Vec(-1, 0, 0);
            case EAST -> new Vec(0, 0, 1);
            case WEST -> new Vec(0, 0, -1);
            case UP -> new Vec(1, 0, 0);
            case DOWN -> new Vec(1, 0, 0);
            default -> new Vec(1, 0, 0);
        };
    }

    private String key(Location loc) {
        if (loc == null || loc.getWorld() == null) return "";
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private void readArray(String path, ItemStack[] target) {
        List<?> list = dataYaml.getList(path, List.of());
        for (int i = 0; i < target.length && i < list.size(); i++) {
            Object value = list.get(i);
            target[i] = value instanceof ItemStack item ? item : null;
        }
    }

    private ItemStack readItem(String path) {
        Object value = dataYaml.get(path);
        return value instanceof ItemStack item ? item : null;
    }

    private void saveIfDirty() {
        if (config.enabled() && dirty) saveNow();
    }

    private synchronized void saveNow() {
        if (!config.enabled() || dataFile == null) return;
        YamlConfiguration out = new YamlConfiguration();
        for (MiningRobot robot : robots.values()) {
            String path = "robots." + robot.id() + ".";
            out.set(path + "owner", robot.ownerId().toString());
            out.set(path + "town", robot.townId() == null ? "" : robot.townId().toString());
            out.set(path + "world", robot.worldName());
            out.set(path + "x", robot.x());
            out.set(path + "y", robot.y());
            out.set(path + "z", robot.z());
            out.set(path + "facing", robot.facing().name());
            out.set(path + "active", robot.active());
            out.set(path + "fuel-seconds", robot.fuelSecondsRemaining());
            out.set(path + "next-work-tick", robot.nextWorkTick());
            out.set(path + "upgrades", itemArray(robot.upgrades()));
            out.set(path + "storage-upgrade", robot.storageUpgrade());
            out.set(path + "fuel", robot.fuel());
            out.set(path + "pickaxe", robot.pickaxe());
            out.set(path + "storage", itemArray(robot.storage()));
        }
        try {
            out.save(dataFile);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("Nie udało się zapisać robotów górniczych: " + ex.getMessage());
        }
    }

    private void markDirty() { dirty = true; }

    private List<ItemStack> itemArray(ItemStack[] items) {
        List<ItemStack> list = new ArrayList<>(items.length);
        for (ItemStack item : items) list.add(item == null ? null : item.clone());
        return list;
    }

    private record Vec(int x, int y, int z) {
        static Vec of(BlockFace face) {
            return switch (face) {
                case NORTH -> new Vec(0, 0, -1);
                case SOUTH -> new Vec(0, 0, 1);
                case EAST -> new Vec(1, 0, 0);
                case WEST -> new Vec(-1, 0, 0);
                case UP -> new Vec(0, 1, 0);
                case DOWN -> new Vec(0, -1, 0);
                default -> new Vec(0, 0, -1);
            };
        }
    }
}
