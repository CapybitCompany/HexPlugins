package hex.minions.energy;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.minions.machine.MachineDefinition;
import hex.minions.machine.MachineRuntime;
import hex.minions.machine.MachineService;
import hex.minions.service.MinionService;
import hex.towns.api.TownPermission;
import hex.towns.api.TownsApi;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.block.BlockState;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pasywny cache kabli EU. Kable nie tickują samodzielnie: topologia jest przeliczana tylko przy zmianie świata,
 * a maszyny/generatory rozliczają EU przez gotowy cache tras. BlockDisplay jest wyłącznie wizualizacją segmentu.
 */
public final class CableService implements Listener {
    private final Plugin plugin;
    private final HexApi hex;
    private final TownsApi towns;
    private final MinionService minions;
    private final CableRepository repository;
    private final DatabaseWriteQueue writeQueue;
    private MachineService machines;
    private final NamespacedKey cableVisualKey;
    private final NamespacedKey cableVisualTownKey;
    private final NamespacedKey cableVisualTypeKey;
    private final NamespacedKey cableVisualObjectIdKey;
    private final Map<CableType, CableTypeConfig> configs = new EnumMap<>(CableType.class);
    private final Map<UUID, CableSegment> segmentsById = new LinkedHashMap<>();
    private final Map<BlockPos, UUID> occupancyByBlock = new LinkedHashMap<>();
    private final Map<Long, Set<UUID>> segmentsByChunk = new LinkedHashMap<>();
    private final Map<BlockPos, List<EnergyRoute>> routeCacheByProducer = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> displayEntitiesBySegment = new HashMap<>();
    private final Map<Long, Set<UUID>> displayEntitiesByChunk = new HashMap<>();
    private final Set<UUID> displayedSegments = new HashSet<>();
    private final ArrayDeque<UUID> displayRefreshQueue = new ArrayDeque<>();
    private final Set<UUID> queuedDisplayRefreshes = new HashSet<>();
    private final Map<BlockPos, Long> overloadedProducers = new ConcurrentHashMap<>();

    private final int maxSegmentsPerNetwork;
    private final int maxRoutesPerNetwork;
    private final int maxNetworkRebuildsPerTick;
    private final int maxDisplayEntitiesPerChunk;
    private final int maxDisplayRefreshesPerTick;
    private final int overloadedNetworkRetryTicks;
    private final int maxCablesPerTown;
    private int routeRebuildsThisTick;
    private int maintenanceTaskId = -1;
    private volatile boolean dbReady;

    public CableService(Plugin plugin, HexApi hex, TownsApi towns, MinionService minions) {
        this.plugin = plugin;
        this.hex = hex;
        this.towns = towns;
        this.minions = minions;
        this.repository = new CableRepository(hex.db().db());
        this.writeQueue = new DatabaseWriteQueue(plugin, hex, repository);
        this.cableVisualKey = new NamespacedKey(plugin, "energy_cable_segment");
        this.cableVisualTownKey = new NamespacedKey(plugin, "town_uuid");
        this.cableVisualTypeKey = new NamespacedKey(plugin, "object_type");
        this.cableVisualObjectIdKey = new NamespacedKey(plugin, "object_id");
        loadConfig();
        ConfigurationSection energy = energySection();
        this.maxSegmentsPerNetwork = Math.max(1, energy == null ? 256 : energy.getInt("max-segments-per-network", energy.getInt("max_segments_per_network", 256)));
        this.maxRoutesPerNetwork = Math.max(1, energy == null ? 512 : energy.getInt("max-routes-per-network", energy.getInt("max_routes_per_network", 512)));
        this.maxNetworkRebuildsPerTick = Math.max(1, energy == null ? 4 : energy.getInt("max-network-rebuilds-per-tick", energy.getInt("max_network_rebuilds_per_tick", 4)));
        this.maxDisplayEntitiesPerChunk = Math.max(1, energy == null ? 64 : energy.getInt("max-display-entities-per-chunk", energy.getInt("max_display_entities_per_chunk", 64)));
        this.maxDisplayRefreshesPerTick = Math.max(1, energy == null ? 64 : energy.getInt("max-display-refreshes-per-tick", energy.getInt("max_display_refreshes_per_tick", 64)));
        this.overloadedNetworkRetryTicks = Math.max(20, 20 * (energy == null ? 30 : energy.getInt("overloaded-network-retry-seconds", energy.getInt("overloaded_network_retry_seconds", 30))));
        this.maxCablesPerTown = Math.max(0, energy == null ? 256 : energy.getInt("max-cables-per-town", energy.getInt("max_cables_per_town", 256)));
        this.maintenanceTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::maintenanceTick, 1L, 1L).getTaskId();
        loadPersistedAsync();
    }

    public void attachMachines(MachineService machines) { this.machines = machines; }

    public void clearRouteCache() {
        routeCacheByProducer.clear();
        overloadedProducers.clear();
    }

    public void refreshVisuals() {
        clearRouteCache();
        if (!dbReady) return;
        for (UUID id : new ArrayList<>(segmentsById.keySet())) enqueueDisplayRefresh(id);
    }

    public void refreshVisualsNear(Location location) {
        if (location == null) return;
        BlockPos center = BlockPos.of(location);
        // Zmiana maszyny/generatora obok kabla potrafi zmienić poprawność wcześniej zapamiętanej pustej trasy.
        // Najbezpieczniej wyczyścić cache tras, bo topologia EU zmienia się rzadko, a cache odbuduje się leniwie przy ticku generatora.
        clearRouteCache();
        refreshSegmentsNear(center);
    }


    public void shutdown() {
        if (maintenanceTaskId != -1) Bukkit.getScheduler().cancelTask(maintenanceTaskId);
        maintenanceTaskId = -1;
        writeQueue.flushAsync();
        cleanupAllDisplays();
    }

    private void loadConfig() {
        configs.put(CableType.COPPER, new CableTypeConfig("Miedziany Kabel", 2, 0.25, 32, Material.COPPER_BLOCK, 0.12, 32f));
        configs.put(CableType.GOLD, new CableTypeConfig("Złoty Kabel", 5, 0.15, 96, Material.GOLD_BLOCK, 0.12, 32f));
        configs.put(CableType.GLASS, new CableTypeConfig("Szklany Kabel", 10, 0.05, 256, Material.LIGHT_BLUE_STAINED_GLASS, 0.10, 48f));
        ConfigurationSection root = cablesSection();
        if (root == null) return;
        for (CableType type : CableType.values()) {
            ConfigurationSection s = root.getConfigurationSection(type.name().toLowerCase(Locale.ROOT));
            if (s == null) continue;
            Material display = Material.matchMaterial(s.getString("display_material", configs.get(type).displayMaterial().name()));
            if (display == null) display = configs.get(type).displayMaterial();
            configs.put(type, new CableTypeConfig(
                    s.getString("display_name", configs.get(type).displayName()),
                    Math.max(1, s.getInt("max_segment_length", configs.get(type).maxSegmentLength())),
                    Math.max(0, s.getDouble("loss_eu_per_meter", configs.get(type).lossEuPerMeter())),
                    Math.max(1, s.getInt("max_eu_per_second", configs.get(type).maxEuPerSecond())),
                    display,
                    Math.max(0.03, s.getDouble("display_thickness", configs.get(type).displayThickness())),
                    (float) Math.max(1, s.getDouble("view_range", configs.get(type).viewRange()))
            ));
        }
    }

    private void loadPersistedAsync() {
        hex.db().async(() -> {
            repository.ensureTables();
            return repository.loadAll();
        }).thenAccept(loaded -> Bukkit.getScheduler().runTask(plugin, () -> {
            cleanupAllDisplays();
            segmentsById.clear();
            occupancyByBlock.clear();
            segmentsByChunk.clear();
            for (CableSegment loadedSegment : loaded) {
                if (loadedSegment == null) continue;
                CableSegment segment = loadedSegment;
                if (segment.townUuid() == null) {
                    UUID migratedOwner = resolveLegacyTownOwner(segment);
                    if (migratedOwner == null) {
                        plugin.getLogger().warning("[CableMigration] LEGACY_ORPHAN cable=" + segment.id() + " world=" + segment.world());
                        continue;
                    }
                    segment = new CableSegment(segment.id(), migratedOwner, segment.world(), segment.start(), segment.end(), segment.axis(), segment.type(), segment.length());
                    CableSegment migrated = segment;
                    hex.db().asyncRun(() -> repository.assignTown(migrated.id(), migrated.townUuid()));
                }
                // A persisted owner is not enough to resurrect a cable. Destroyed/DESTROYING
                // towns are intentionally absent from the active API; keep such records out of
                // RAM until the durable cleanup namespace removes them from DB.
                if (segment.townUuid() != null && towns.findTown(segment.townUuid()).isEmpty()) {
                    plugin.getLogger().warning("[CableLifecycle] ORPHAN cable=" + segment.id() + " town=" + segment.townUuid() + " kept out of runtime");
                    continue;
                }
                if (canLoadSegment(segment)) addSegmentToMemory(segment);
            }
            clearRouteCache();
            dbReady = true;
            queueLoadedDisplays();
            plugin.getLogger().info("HexMinions energy cables loaded=" + segmentsById.size());
        })).exceptionally(ex -> {
            plugin.getLogger().warning("Nie udało się wczytać kabli EU: " + rootMessage(ex));
            dbReady = true;
            return null;
        });
    }

    private boolean canLoadSegment(CableSegment segment) {
        if (segment == null || segment.type() != CableType.COPPER) return false;
        for (BlockPos pos : line(segment.start(), segment.end())) {
            if (occupancyByBlock.containsKey(pos)) {
                plugin.getLogger().warning("Pominięto zdublowany/kolizyjny kabel EU z DB: " + segment.id());
                return false;
            }
        }
        return true;
    }

    private ConfigurationSection energySection() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("energy");
        if (s != null) return s;
        return plugin.getConfig().getConfigurationSection("minions.energy");
    }

    private ConfigurationSection cablesSection() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("cables");
        if (s != null) return s;
        s = plugin.getConfig().getConfigurationSection("energy.cables");
        if (s != null) return s;
        return plugin.getConfig().getConfigurationSection("minions.energy.cables");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlaceCableItem(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
        CableType type = minions.specialItems().readSpecialItemId(item).map(CableType::fromSpecialItem).orElse(null);
        if (type == null) return;
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        event.setCancelled(true);
        Block target = event.getClickedBlock().getRelative(event.getBlockFace());
        attemptPlaceCable(event.getPlayer(), item, target, type, null, null);
    }

    /** Compatibility path for legacy block-carrier items already present in player inventories. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlaceCable(BlockPlaceEvent event) {
        CableType type = minions.specialItems().readSpecialItemId(event.getItemInHand()).map(CableType::fromSpecialItem).orElse(null);
        if (type == null) return;
        event.setCancelled(true);
        attemptPlaceCable(event.getPlayer(), event.getItemInHand(), event.getBlockPlaced(), type, event.getBlockPlaced(), event.getBlockReplacedState().getType());
    }

    private void attemptPlaceCable(Player player, ItemStack item, Block startBlock, CableType type, Block placedBlock, Material replacedMaterial) {
        if (!dbReady) { hex.ui().send(player, "minions.cable.loading"); return; }
        if (startBlock == null) return;
        Placement placement = resolvePlacement(player, startBlock, type);
        if (placement == null) { hex.ui().send(player, "minions.cable.invalid-shape"); return; }
        Optional<hex.towns.model.Town> town = towns.townAt(startBlock.getLocation()).filter(t -> towns.can(player.getUniqueId(), t.id(), TownPermission.MACHINE_USE));
        if (town.isEmpty()) { hex.ui().send(player, "minions.cable.place.not-town"); return; }
        String error = validateSegment(placement.start(), placement.end(), type, placedBlock, replacedMaterial, town.get().id());
        if (error != null) { hex.ui().send(player, "minions.cable.validation-error", UiTokens.of("error", error)); return; }
        if (maxCablesPerTown > 0 && cableCount(town.get().id()) + 1 > maxCablesPerTown) {
            hex.ui().send(player, "minions.energy.limit-reached", UiTokens.of("error", "Limit kabli w mieście: " + maxCablesPerTown + ".")); return;
        }
        CableSegment segment = createSegment(placement.start(), placement.end(), placement.axis(), type, town.get().id());
        try {
            addSegmentToMemory(segment);
            clearRouteCache();
            refreshSegmentAndNeighbors(segment);
            // Acceptance into the write-behind queue is the final synchronous persistence step.
            // A town purge installs its fence before collecting runtime segments, so rejection here
            // must roll the in-memory placement back and must not consume the player's item.
            if (!writeQueue.enqueueInsertCable(segment)) {
                rollbackSegmentInMemory(segment);
                hex.ui().send(player, "minions.cable.place.not-town");
                return;
            }
        } catch (Throwable throwable) {
            rollbackSegmentInMemory(segment);
            plugin.getLogger().warning("Cable placement rolled back: " + rootMessage(throwable));
            hex.ui().send(player, "minions.cable.validation-error", UiTokens.of("error", "Nie udało się zakończyć placementu kabla. Przedmiot nie został zużyty."));
            return;
        }
        consumeOne(player, item);
        try { writeQueue.flushAsync(); }
        catch (Throwable throwable) { plugin.getLogger().warning("Cable DB flush scheduling failed after accepted placement: " + rootMessage(throwable)); }
        hex.ui().send(player, "minions.cable.place.success", UiTokens.of("cable", configs.get(type).displayName()).put("length", String.valueOf(segment.length())));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakCableHelper(BlockBreakEvent event) {
        BlockPos pos = BlockPos.of(event.getBlock());
        UUID id = occupancyByBlock.get(pos);
        if (id == null) return;
        CableSegment segment = segmentsById.get(id);
        if (segment == null) return;
        event.setCancelled(true);
        if (!canModifyCable(event.getPlayer(), segment)) {
            hex.ui().send(event.getPlayer(), "minions.cable.remove.not-town");
            return;
        }
        removeCable(id, true, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onUseCable(PlayerInteractEvent event) {
        if (event.isCancelled() && event.useItemInHand() == org.bukkit.event.Event.Result.DENY) return;
        ItemStack interactionItem = event.getItem();
        if (minions.specialItems().readSpecialItemId(interactionItem).map(CableType::fromSpecialItem).orElse(null) != null) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                && event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.LEFT_CLICK_BLOCK
                && event.getAction() != Action.LEFT_CLICK_AIR) return;
        UUID id = null;
        if (event.getClickedBlock() != null) id = occupancyByBlock.get(BlockPos.of(event.getClickedBlock()));
        if (id == null) id = findCableInSight(event.getPlayer(), 7.0);
        if (id == null) return;
        event.setCancelled(true);
        CableSegment segment = segmentsById.get(id);
        if (segment == null) return;

        boolean leftClick = event.getAction() == Action.LEFT_CLICK_BLOCK || event.getAction() == Action.LEFT_CLICK_AIR;
        if (leftClick) {
            if (!canModifyCable(event.getPlayer(), segment)) {
                hex.ui().send(event.getPlayer(), "minions.cable.remove.not-town");
                return;
            }
            removeCable(segment.id(), true, event.getPlayer());
            return;
        }

        CableTypeConfig cfg = configs.get(segment.type());
        hex.ui().send(event.getPlayer(), "minions.cable.info", UiTokens.of("cable", cfg.displayName()).put("length", String.valueOf(segment.length())).put("limit", String.valueOf(cfg.maxEuPerSecond())).put("loss", String.valueOf(cfg.lossEuPerMeter())));
        if (event.getPlayer().isSneaking()) {
            if (!canModifyCable(event.getPlayer(), segment)) {
                hex.ui().send(event.getPlayer(), "minions.cable.remove.not-town");
                return;
            }
            removeCable(segment.id(), true, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlacedThroughCable(BlockPlaceEvent event) {
        if (event == null || event.getBlockPlaced() == null) return;
        removeCablesObstructedBy(List.of(event.getBlockPlaced()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockGrowThroughCable(BlockGrowEvent event) {
        if (event == null || event.getBlock() == null) return;
        removeCablesObstructedBy(List.of(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockSpreadThroughCable(BlockSpreadEvent event) {
        if (event == null || event.getBlock() == null) return;
        removeCablesObstructedBy(List.of(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFormThroughCable(BlockFormEvent event) {
        if (event == null || event.getBlock() == null) return;
        removeCablesObstructedBy(List.of(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityBlockFormThroughCable(EntityBlockFormEvent event) {
        if (event == null || event.getBlock() == null) return;
        removeCablesObstructedBy(List.of(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrowThroughCable(StructureGrowEvent event) {
        if (event == null || event.getBlocks().isEmpty()) return;
        List<Block> blocks = new ArrayList<>();
        for (BlockState state : event.getBlocks()) {
            if (state != null && state.getBlock() != null) blocks.add(state.getBlock());
        }
        removeCablesObstructedBy(blocks);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            invalidateRouteCacheInChunk(event.getChunk().getWorld().getName(), event.getChunk().getX(), event.getChunk().getZ());
            long chunkKey = CableSegment.chunkKey(event.getChunk().getX(), event.getChunk().getZ());
            Set<UUID> ids = segmentsByChunk.get(chunkKey);
            if (ids == null || ids.isEmpty()) return;
            for (UUID id : new ArrayList<>(ids)) enqueueDisplayRefresh(id);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        invalidateRouteCacheInChunk(event.getChunk().getWorld().getName(), event.getChunk().getX(), event.getChunk().getZ());
        long chunkKey = CableSegment.chunkKey(event.getChunk().getX(), event.getChunk().getZ());
        Set<UUID> ids = segmentsByChunk.get(chunkKey);
        if (ids != null) for (UUID id : new ArrayList<>(ids)) cleanupDisplay(id);
        cleanupDisplaysInChunk(chunkKey);
    }


    private void maintenanceTick() {
        routeRebuildsThisTick = 0;
        processDisplayRefreshQueue();
    }

    private void processDisplayRefreshQueue() {
        int processed = 0;
        while (processed++ < maxDisplayRefreshesPerTick && !displayRefreshQueue.isEmpty()) {
            UUID id = displayRefreshQueue.removeFirst();
            queuedDisplayRefreshes.remove(id);
            CableSegment segment = segmentsById.get(id);
            cleanupDisplay(id);
            if (segment != null) spawnDisplayIfLoaded(segment);
        }
    }

    private void enqueueDisplayRefresh(UUID id) {
        if (id == null || !segmentsById.containsKey(id)) return;
        if (queuedDisplayRefreshes.add(id)) displayRefreshQueue.addLast(id);
    }

    private void refreshSegmentAndNeighbors(CableSegment segment) {
        if (segment == null) return;
        enqueueDisplayRefresh(segment.id());
        refreshSegmentNeighborsOnly(segment);
    }

    private void refreshSegmentNeighborsOnly(CableSegment segment) {
        if (segment == null) return;
        for (UUID id : neighboringSegmentIds(segment)) enqueueDisplayRefresh(id);
    }

    private void refreshSegmentsNear(BlockPos center) {
        if (center == null) return;
        for (BlockFace face : faces()) {
            UUID id = occupancyByBlock.get(center.relative(face));
            if (id != null) enqueueDisplayRefresh(id);
        }
        UUID direct = occupancyByBlock.get(center);
        if (direct != null) enqueueDisplayRefresh(direct);
    }

    private Set<UUID> neighboringSegmentIds(CableSegment segment) {
        Set<UUID> ids = new HashSet<>();
        for (BlockPos pos : line(segment.start(), segment.end())) {
            for (BlockFace face : faces()) {
                UUID id = occupancyByBlock.get(pos.relative(face));
                if (id != null && !id.equals(segment.id())) ids.add(id);
            }
        }
        return ids;
    }

    private void invalidateRouteCacheForSegment(CableSegment segment) {
        if (segment == null) return;
        Set<BlockPos> affected = new HashSet<>(line(segment.start(), segment.end()));
        routeCacheByProducer.entrySet().removeIf(entry -> routeTouches(entry.getKey(), entry.getValue(), affected));
        overloadedProducers.keySet().removeIf(affected::contains);
    }

    private void invalidateRouteCacheNear(BlockPos center) {
        if (center == null) return;
        Set<BlockPos> affected = new HashSet<>();
        affected.add(center);
        for (BlockFace face : faces()) affected.add(center.relative(face));
        routeCacheByProducer.entrySet().removeIf(entry -> routeTouches(entry.getKey(), entry.getValue(), affected));
        overloadedProducers.keySet().removeIf(affected::contains);
    }

    private void invalidateRouteCacheInChunk(String world, int chunkX, int chunkZ) {
        routeCacheByProducer.entrySet().removeIf(entry -> blockInChunk(entry.getKey(), world, chunkX, chunkZ)
                || entry.getValue().stream().anyMatch(route -> route.consumerPos() != null && blockInChunk(route.consumerPos(), world, chunkX, chunkZ)
                || route.cablePath().stream().anyMatch(pos -> blockInChunk(pos, world, chunkX, chunkZ))));
        overloadedProducers.keySet().removeIf(pos -> blockInChunk(pos, world, chunkX, chunkZ));
    }

    private boolean routeTouches(BlockPos producer, List<EnergyRoute> routes, Set<BlockPos> affected) {
        if (affected.contains(producer)) return true;
        if (routes == null) return false;
        for (EnergyRoute route : routes) {
            if (route == null) continue;
            if (affected.contains(route.consumerPos())) return true;
            for (BlockPos pos : route.cablePath()) if (affected.contains(pos)) return true;
        }
        return false;
    }

    private boolean blockInChunk(BlockPos pos, String world, int chunkX, int chunkZ) {
        return pos != null && pos.world().equals(world) && Math.floorDiv(pos.x(), 16) == chunkX && Math.floorDiv(pos.z(), 16) == chunkZ;
    }

    private int cableCount(UUID townId) {
        if (townId == null) return 0;
        int count = 0;
        for (CableSegment segment : segmentsById.values()) if (townId.equals(segment.townUuid())) count++;
        return count;
    }

    private boolean canSpawnDisplayAt(Location location) {
        if (location == null) return false;
        long chunkKey = CableSegment.chunkKey(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        return displayEntitiesByChunk.getOrDefault(chunkKey, Set.of()).size() < maxDisplayEntitiesPerChunk;
    }

    private void registerDisplay(CableSegment segment, BlockDisplay display) {
        if (segment == null || display == null) return;
        UUID entityId = display.getUniqueId();
        displayEntitiesBySegment.computeIfAbsent(segment.id(), ignored -> new HashSet<>()).add(entityId);
        long chunkKey = CableSegment.chunkKey(display.getLocation().getBlockX() >> 4, display.getLocation().getBlockZ() >> 4);
        displayEntitiesByChunk.computeIfAbsent(chunkKey, ignored -> new HashSet<>()).add(entityId);
    }

    private void cleanupDisplaysInChunk(long chunkKey) {
        Set<UUID> entityIds = displayEntitiesByChunk.remove(chunkKey);
        if (entityIds == null || entityIds.isEmpty()) return;
        for (UUID entityId : new ArrayList<>(entityIds)) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) entity.remove();
            for (Map.Entry<UUID, Set<UUID>> entry : displayEntitiesBySegment.entrySet()) entry.getValue().remove(entityId);
        }
        displayEntitiesBySegment.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        displayedSegments.removeIf(id -> !displayEntitiesBySegment.containsKey(id));
    }


    private boolean canModifyCable(Player player, CableSegment segment) {
        if (player == null || segment == null) return false;
        if (player.isOp() || player.hasPermission("hexminions.cables.admin")) return true;
        return segment.townUuid() != null && towns.can(player.getUniqueId(), segment.townUuid(), TownPermission.MACHINE_BREAK);
    }

    private UUID findCableInSight(Player player, double maxDistance) {
        if (player == null || player.getWorld() == null) return null;
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector dir = eye.getDirection().normalize();
        for (double d = 0.0; d <= maxDistance; d += 0.10) {
            Location point = eye.clone().add(dir.clone().multiply(d));
            UUID id = occupancyByBlock.get(BlockPos.of(point));
            if (id != null) return id;
        }
        return null;
    }

    private Placement resolvePlacement(Player player, Block firstBlock, CableType type) {
        CableTypeConfig cfg = configs.get(type);
        if (cfg == null || firstBlock == null) return null;
        Placement fallback = null;
        EnumSet<Axis> triedAxes = EnumSet.noneOf(Axis.class);
        for (BlockFace face : placementDirections(player)) {
            if (face == null || face == BlockFace.SELF) continue;
            Axis axis = axis(face);
            if (axis == null || !triedAxes.add(axis)) continue;
            Placement placement = buildPlacement(firstBlock, face, cfg, axis);
            if (placement == null) continue;
            if (fallback == null) fallback = placement;
            if (placementTouchesEndpoint(placement)) return placement;
        }
        return fallback;
    }

    private Placement buildPlacement(Block firstBlock, BlockFace preferredFace, CableTypeConfig cfg, Axis axis) {
        BlockPos center = BlockPos.of(firstBlock);
        int maxAdditional = Math.max(0, cfg.maxSegmentLength() - 1);
        BlockPos forward = extendCable(firstBlock, preferredFace, center, maxAdditional);
        int usedForward = distance(center, forward);
        BlockPos backward = extendCable(firstBlock, preferredFace.getOppositeFace(), center, Math.max(0, maxAdditional - usedForward));
        return new Placement(backward, forward, axis);
    }

    private BlockPos extendCable(Block firstBlock, BlockFace face, BlockPos center, int maxAdditional) {
        BlockPos end = center;
        for (int i = 1; i <= maxAdditional; i++) {
            Block next = firstBlock.getRelative(face, i);
            BlockPos np = BlockPos.of(next);
            if (occupancyByBlock.containsKey(np) || (machines != null && machines.machineAt(next) != null)) break;
            if (!isClearCableRouteBlock(next, null, null)) break;
            end = np;
            if (touchesAnyConnection(end, new HashSet<>(line(center, end)))) break;
        }
        return end;
    }

    private boolean placementTouchesEndpoint(Placement placement) {
        if (placement == null) return false;
        return lineTouchesConnection(placement.start(), placement.end());
    }

    private List<BlockFace> placementDirections(Player player) {
        List<BlockFace> ordered = new ArrayList<>();
        if (player != null) {
            org.bukkit.util.Vector dir = player.getEyeLocation().getDirection();
            double ax = Math.abs(dir.getX());
            double ay = Math.abs(dir.getY());
            double az = Math.abs(dir.getZ());
            BlockFace horizontalPrimary;
            BlockFace horizontalSecondary;
            if (ax >= az) {
                horizontalPrimary = dir.getX() >= 0 ? BlockFace.EAST : BlockFace.WEST;
                horizontalSecondary = dir.getZ() >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
            } else {
                horizontalPrimary = dir.getZ() >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
                horizontalSecondary = dir.getX() >= 0 ? BlockFace.EAST : BlockFace.WEST;
            }
            BlockFace verticalPrimary = dir.getY() >= 0 ? BlockFace.UP : BlockFace.DOWN;
            if (ay > Math.max(ax, az) * 1.25) addDirection(ordered, verticalPrimary);
            addDirection(ordered, horizontalPrimary);
            addDirection(ordered, horizontalPrimary.getOppositeFace());
            addDirection(ordered, horizontalSecondary);
            addDirection(ordered, horizontalSecondary.getOppositeFace());
            addDirection(ordered, verticalPrimary);
            addDirection(ordered, verticalPrimary.getOppositeFace());
            addDirection(ordered, player.getFacing());
        }
        for (BlockFace face : faces()) addDirection(ordered, face);
        return ordered;
    }

    private void addDirection(List<BlockFace> ordered, BlockFace face) {
        if (face == null || face == BlockFace.SELF || ordered.contains(face)) return;
        ordered.add(face);
    }

    private Axis axis(BlockFace face) {
        if (face == null) return null;
        if (face == BlockFace.EAST || face == BlockFace.WEST) return Axis.X;
        if (face == BlockFace.UP || face == BlockFace.DOWN) return Axis.Y;
        if (face == BlockFace.NORTH || face == BlockFace.SOUTH) return Axis.Z;
        return null;
    }

    private BlockPos previousOnLine(BlockPos start, BlockPos end) {
        if (start.equals(end)) return null;
        int dx = Integer.compare(end.x(), start.x());
        int dy = Integer.compare(end.y(), start.y());
        int dz = Integer.compare(end.z(), start.z());
        return new BlockPos(end.world(), end.x() - dx, end.y() - dy, end.z() - dz);
    }

    private boolean lineTouchesConnection(BlockPos start, BlockPos end) {
        return lineTouchesConnection(line(start, end));
    }

    private boolean lineTouchesConnection(List<BlockPos> proposedLine) {
        Set<BlockPos> proposed = new HashSet<>(proposedLine);
        for (BlockPos pos : proposedLine) {
            if (touchesAnyConnection(pos, proposed)) return true;
        }
        return false;
    }

    private boolean touchesAnyConnection(BlockPos pos, Set<BlockPos> proposedLine) {
        World world = Bukkit.getWorld(pos.world());
        if (world == null || machines == null) return false;
        for (BlockFace face : faces()) {
            BlockPos n = pos.relative(face);
            if (proposedLine != null && proposedLine.contains(n)) continue;
            if (occupancyByBlock.containsKey(n)) return true;
            Block block = n.block(world);
            MachineDefinition machine = machines.machineAt(block);
            if (machine != null) {
                BlockFace cableSideOnMachine = face.getOppositeFace();
                if (isValidPort(machine, block, cableSideOnMachine, true) || isValidPort(machine, block, cableSideOnMachine, false)) return true;
            }
        }
        return false;
    }

    private String validateSegment(BlockPos start, BlockPos end, CableType type, Block placedBlock, Material replacedMaterial, UUID ownerTownUuid) {
        if (machines == null) return "System maszyn nie jest jeszcze gotowy.";
        if (!start.world().equals(end.world())) return "Kabel musi być w jednym świecie.";
        Axis axis = axis(start, end);
        if (axis == null) return "Kabel musi być prostym odcinkiem po osi X/Y/Z.";
        int length = distance(start, end) + 1;
        CableTypeConfig cfg = configs.get(type);
        if (length > cfg.maxSegmentLength()) return "Ten kabel może mieć maksymalnie " + cfg.maxSegmentLength() + " bloki.";
        World world = Bukkit.getWorld(start.world());
        if (world == null) return "Nie znaleziono świata kabla.";
        BlockPos placedPos = placedBlock == null ? null : BlockPos.of(placedBlock);
        List<BlockPos> proposedLine = line(start, end);
        for (BlockPos p : proposedLine) {
            Optional<hex.towns.model.Town> ownerAtBlock = towns.townAt(p.block(world).getLocation());
            if (ownerAtBlock.isEmpty() || ownerTownUuid == null || !ownerTownUuid.equals(ownerAtBlock.get().id())) {
                return "Cały segment kabla musi znajdować się wewnątrz claimów jednego miasta.";
            }
            if (occupancyByBlock.containsKey(p)) return "Kable nie mogą nachodzić na ten sam blok.";
            Block block = p.block(world);
            if (machines.machineAt(block) != null) return "Kabel nie może przechodzić przez maszynę.";
            Material originalMaterial = p.equals(placedPos) ? replacedMaterial : null;
            if (!isClearCableRouteBlock(block, placedPos, originalMaterial)) {
                return "Na trasie kabla nie może stać żaden blok, roślina, woda ani inny obiekt.";
            }
        }
        if (!lineTouchesConnection(proposedLine)) {
            return "Kabel musi dotykać portu EU maszyny/generatora/akumulatora albo istniejącego kabla.";
        }
        return null;
    }

    private CableSegment createSegment(BlockPos start, BlockPos end, Axis placementAxis, CableType type, UUID townUuid) {
        Axis segmentAxis = axis(start, end);
        if (distance(start, end) == 0 && placementAxis != null) segmentAxis = placementAxis;
        return new CableSegment(UUID.randomUUID(), townUuid, start.world(), start, end, segmentAxis, type, distance(start, end) + 1);
    }

    private void addSegmentToMemory(CableSegment segment) {
        segmentsById.put(segment.id(), segment);
        for (BlockPos p : line(segment.start(), segment.end())) occupancyByBlock.put(p, segment.id());
        for (long chunkKey : segment.chunkKeys()) segmentsByChunk.computeIfAbsent(chunkKey, k -> new HashSet<>()).add(segment.id());
        invalidateRouteCacheForSegment(segment);
    }

    private void rollbackSegmentInMemory(CableSegment segment) {
        if (segment == null) return;
        segmentsById.remove(segment.id());
        for (BlockPos p : line(segment.start(), segment.end())) {
            occupancyByBlock.remove(p, segment.id());
        }
        for (long chunkKey : segment.chunkKeys()) {
            Set<UUID> set = segmentsByChunk.get(chunkKey);
            if (set == null) continue;
            set.remove(segment.id());
            if (set.isEmpty()) segmentsByChunk.remove(chunkKey);
        }
        invalidateRouteCacheForSegment(segment);
        cleanupDisplay(segment.id());
        try { refreshSegmentNeighborsOnly(segment); } catch (Throwable ignored) { }
        clearRouteCache();
    }

    private void removeCablesObstructedBy(Iterable<Block> blocks) {
        if (blocks == null || !dbReady) return;
        Set<UUID> ids = new HashSet<>();
        for (Block block : blocks) {
            if (block == null || block.getType().isAir()) continue;
            UUID id = occupancyByBlock.get(BlockPos.of(block));
            if (id != null) ids.add(id);
        }
        if (!ids.isEmpty()) removeCables(ids, false, null);
    }

    private boolean isClearCableRouteBlock(Block block, BlockPos replacedPos, Material replacedMaterial) {
        if (block == null) return false;
        Material material = block.getType();
        if (replacedPos != null && replacedPos.equals(BlockPos.of(block)) && replacedMaterial != null) material = replacedMaterial;
        return material.isAir();
    }

    private void removeCable(UUID id, boolean drop, Player player) {
        if (id == null) return;
        removeCables(Set.of(id), drop, player);
    }

    private void removeCables(Set<UUID> ids, boolean drop, Player player) {
        removeCables(ids, drop, player, true);
    }

    private void removeCables(Set<UUID> ids, boolean drop, Player player, boolean clearGlobalRouteCache) {
        if (ids == null || ids.isEmpty()) return;
        boolean changed = false;
        for (UUID id : new ArrayList<>(ids)) {
            CableSegment segment = segmentsById.remove(id);
            if (segment == null) continue;
            changed = true;
            invalidateRouteCacheForSegment(segment);
            refreshSegmentNeighborsOnly(segment);
            for (BlockPos p : line(segment.start(), segment.end())) occupancyByBlock.remove(p);
            for (long chunkKey : segment.chunkKeys()) {
                Set<UUID> set = segmentsByChunk.get(chunkKey);
                if (set != null) {
                    set.remove(segment.id());
                    if (set.isEmpty()) segmentsByChunk.remove(chunkKey);
                }
            }
            cleanupDisplay(segment.id());
            writeQueue.enqueueDeleteCable(segment.id());
            if (drop) {
                World world = Bukkit.getWorld(segment.world());
                if (world != null) {
                    ItemStack item = minions.specialItems().createItem(specialItemId(segment.type()), 1);
                    world.dropItemNaturally(segment.start().block(world).getLocation().add(0.5, 0.5, 0.5), item);
                }
            }
        }
        if (!changed) return;
        // Normal player removal may invalidate arbitrary producer routes. Town purge already
        // invalidates each affected segment and its neighbors, so it deliberately avoids a
        // global network-cache rebuild/clear.
        if (clearGlobalRouteCache) clearRouteCache();
        writeQueue.flushAsync();
        if (player != null) hex.ui().send(player, "minions.cable.remove.success");
    }

    /** Idempotent, ownership-aware town purge. Must be called on the Bukkit main thread for runtime/display cleanup. */
    public CablePurgeResult purgeTown(UUID townUuid, String worldName, Set<hex.towns.model.ChunkPos> destroyedChunks) {
        if (townUuid == null) return new CablePurgeResult(0, 0);
        Set<Long> snapshotChunks = snapshotChunkKeys(destroyedChunks);
        Set<UUID> ids = collectTownCableIds(townUuid, worldName, snapshotChunks);
        int runtimeRemoved = ids.size();
        removeCables(ids, false, null, false);
        return new CablePurgeResult(runtimeRemoved, ids.size());
    }

    public java.util.concurrent.CompletableFuture<Void> purgeTownAsync(UUID townUuid, String worldName, Set<hex.towns.model.ChunkPos> destroyedChunks) {
        if (townUuid == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        // Fence first: no queued cable write for this town may execute after cleanup starts.
        writeQueue.blockTown(townUuid);
        Set<Long> snapshotChunks = snapshotChunkKeys(destroyedChunks);
        java.util.concurrent.CompletableFuture<Set<UUID>> collect = new java.util.concurrent.CompletableFuture<>();
        Runnable worldWork = () -> {
            try {
                collect.complete(collectTownCableIds(townUuid, worldName, snapshotChunks));
            } catch (Throwable error) {
                collect.completeExceptionally(error);
            }
        };
        if (Bukkit.isPrimaryThread()) worldWork.run(); else Bukkit.getScheduler().runTask(plugin, worldWork);
        return collect.thenCompose(this::removeCablesForTownPurgeBatched)
                // removeCables enqueues DB deletes. More importantly, an INSERT queued before the fence may
                // already be executing; drain everything before the final authoritative DELETE.
                .thenCompose(ignored -> writeQueue.drainAsync().handle((v, error) -> {
                    if (error != null) plugin.getLogger().warning("[TownCleanup] cable in-flight write failed before final purge: " + error.getMessage());
                    return null;
                }))
                .thenCompose(ignored -> hex.db().asyncRun(() -> {
                    repository.deleteByTown(townUuid);
                    // Legacy records have no owner. Delete only if every chunk touched by the exact segment is in the destroy snapshot.
                    for (CableSegment segment : repository.loadAll()) {
                        if (segment.townUuid() == null && legacySegmentFullyInsideSnapshot(segment, worldName, snapshotChunks)) repository.deleteCable(segment.id());
                    }
                    repository.verifyTownPurged(townUuid, worldName, snapshotChunks);
                }));
    }

    public java.util.concurrent.CompletableFuture<Void> purgeTownByOwnerAsync(UUID townUuid) {
        if (townUuid == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        writeQueue.blockTown(townUuid);
        java.util.concurrent.CompletableFuture<Set<UUID>> collect = new java.util.concurrent.CompletableFuture<>();
        Runnable worldWork = () -> {
            try {
                Set<UUID> ids = new HashSet<>();
                for (CableSegment segment : new ArrayList<>(segmentsById.values())) if (townUuid.equals(segment.townUuid())) ids.add(segment.id());
                collect.complete(ids);
            } catch (Throwable error) { collect.completeExceptionally(error); }
        };
        if (Bukkit.isPrimaryThread()) worldWork.run(); else Bukkit.getScheduler().runTask(plugin, worldWork);
        return collect.thenCompose(this::removeCablesForTownPurgeBatched)
                .thenCompose(ignored -> writeQueue.drainAsync().handle((v, error) -> null))
                .thenCompose(ignored -> hex.db().asyncRun(() -> {
                    repository.deleteByTown(townUuid);
                    repository.verifyTownPurged(townUuid, null, Set.of());
                }));
    }

    /**
     * Bounded main-thread purge. Cable segments are exact owned objects, so batching them avoids
     * a large single-tick display/index mutation without falling back to any chunk/world scan.
     */
    private java.util.concurrent.CompletableFuture<Void> removeCablesForTownPurgeBatched(Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) return java.util.concurrent.CompletableFuture.completedFuture(null);
        java.util.concurrent.CompletableFuture<Void> result = new java.util.concurrent.CompletableFuture<>();
        Runnable initialize = () -> {
            try {
                List<UUID> targets = new ArrayList<>(ids);
                targets.sort(java.util.Comparator.comparing(UUID::toString));
                class Step implements Runnable {
                    private static final int SEGMENTS_PER_TICK = 16;
                    int cursor;
                    @Override public void run() {
                        try {
                            int end = Math.min(targets.size(), cursor + SEGMENTS_PER_TICK);
                            if (cursor < end) {
                                removeCables(new HashSet<>(targets.subList(cursor, end)), false, null, false);
                                cursor = end;
                            }
                            if (cursor >= targets.size()) {
                                result.complete(null);
                            } else {
                                Bukkit.getScheduler().runTask(plugin, this);
                            }
                        } catch (Throwable error) {
                            result.completeExceptionally(error);
                        }
                    }
                }
                new Step().run();
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        };
        if (Bukkit.isPrimaryThread()) initialize.run(); else Bukkit.getScheduler().runTask(plugin, initialize);
        return result;
    }

    private Set<Long> snapshotChunkKeys(Set<hex.towns.model.ChunkPos> destroyedChunks) {
        Set<Long> snapshotChunks = new HashSet<>();
        if (destroyedChunks != null) {
            for (hex.towns.model.ChunkPos chunk : destroyedChunks) snapshotChunks.add(CableSegment.chunkKey(chunk.x(), chunk.z()));
        }
        return snapshotChunks;
    }

    private Set<UUID> collectTownCableIds(UUID townUuid, String worldName, Set<Long> snapshotChunks) {
        Set<UUID> ids = new HashSet<>();
        for (CableSegment segment : new ArrayList<>(segmentsById.values())) {
            if (townUuid.equals(segment.townUuid()) || (segment.townUuid() == null && legacySegmentFullyInsideSnapshot(segment, worldName, snapshotChunks))) ids.add(segment.id());
        }
        return ids;
    }

    private boolean legacySegmentFullyInsideSnapshot(CableSegment segment, String worldName, Set<Long> snapshotChunks) {
        if (segment == null || worldName == null || !worldName.equals(segment.world()) || snapshotChunks.isEmpty()) return false;
        for (BlockPos pos : line(segment.start(), segment.end())) {
            if (!snapshotChunks.contains(CableSegment.chunkKey(Math.floorDiv(pos.x(), 16), Math.floorDiv(pos.z(), 16)))) return false;
        }
        return true;
    }

    private UUID resolveLegacyTownOwner(CableSegment segment) {
        if (segment == null) return null;
        World world = Bukkit.getWorld(segment.world());
        if (world == null) return null;
        UUID owner = null;
        for (BlockPos pos : line(segment.start(), segment.end())) {
            Optional<hex.towns.model.Town> at = towns.townAt(pos.block(world).getLocation());
            if (at.isEmpty()) return null;
            if (owner == null) owner = at.get().id();
            else if (!owner.equals(at.get().id())) return null;
        }
        return owner;
    }

    public record CablePurgeResult(int runtimeSegmentsRemoved, int queuedDbDeletes) {}

    public int transferFromGenerator(Block generatorBlock, MachineDefinition generator, MachineRuntime generatorRuntime) {
        if (generatorBlock == null || generator == null || generatorRuntime == null || generatorRuntime.energy() <= 0 || machines == null) return 0;
        // Transfer po kablach jest dozwolony tylko przez aktualnie załadowaną trasę.
        // Nie force-loadujemy chunków i nie wysyłamy EU do maszyn/akumulatorów w niezaładowanym chunku.
        if (!isBlockLoaded(generatorBlock)) return 0;
        BlockPos producer = BlockPos.of(generatorBlock);
        Long overloadedUntil = overloadedProducers.get(producer);
        if (overloadedUntil != null && overloadedUntil > System.currentTimeMillis()) return 0;
        if (overloadedUntil != null) overloadedProducers.remove(producer);
        List<EnergyRoute> routes = routeCacheByProducer.get(producer);
        if (routes == null) {
            if (routeRebuildsThisTick >= maxNetworkRebuildsPerTick) return 0;
            routeRebuildsThisTick++;
            routes = buildRoutes(generatorBlock, generator);
            routeCacheByProducer.put(producer, routes);
        }
        int movedTotal = 0;
        for (EnergyRoute route : routes) {
            if (generatorRuntime.energy() <= 0) break;
            if (!isLoaded(route.consumerPos())) {
                // Odbiornik poza zasięgiem graczy nie pracuje i nie przyjmuje energii.
                // Po ponownym załadowaniu chunka cache tras zostanie wyczyszczony przez ChunkLoadEvent.
                continue;
            }
            Block consumerBlock = route.consumerPos().block(generatorBlock.getWorld());
            MachineDefinition consumer = machines.machineAt(consumerBlock);
            if (consumer == null || !consumer.energy().enabled() || consumer.energy().generator()) continue;
            MachineRuntime consumerRuntime = machines.runtime(machines.key(consumerBlock.getLocation()), consumer.id());
            int need = Math.max(0, machines.capacity(consumer, consumerRuntime) - consumerRuntime.energy());
            if (need <= 0) continue;
            int routeLimit = Math.max(0, Math.min(machines.effectiveTransferPerSecond(generator, generatorRuntime), route.bottleneckEuPerSecond()));
            int loss = (int) Math.ceil(route.lossEu());
            int deliver = Math.min(need, Math.max(0, Math.min(routeLimit, generatorRuntime.energy() - loss)));
            if (deliver <= 0) continue;
            int cost = deliver + loss;
            generatorRuntime.energy(generatorRuntime.energy() - cost);
            consumerRuntime.addEnergy(deliver, machines.capacity(consumer, consumerRuntime));
            machines.markExternalEnergyReceived(consumerRuntime);
            machines.markRuntimeDirty(generatorRuntime);
            machines.markRuntimeDirty(consumerRuntime);
            movedTotal += deliver;
        }
        return movedTotal;
    }

    private List<EnergyRoute> buildRoutes(Block generatorBlock, MachineDefinition generator) {
        List<EnergyRoute> routes = new ArrayList<>();
        if (machines == null || generatorBlock == null || !isBlockLoaded(generatorBlock)) return routes;
        World world = generatorBlock.getWorld();
        ArrayDeque<PathNode> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        for (BlockFace output : outputFaces(generatorBlock, generator)) {
            BlockPos cable = BlockPos.of(generatorBlock.getRelative(output));
            // Start trasy również musi być w załadowanym chunku. Kabel w niezaładowanym chunku pozostaje tylko
            // wpisem w cache/DB; nie bierze udziału w aktywnym transferze do momentu chunk load.
            UUID id = isLoaded(cable) ? occupancyByBlock.get(cable) : null;
            if (id == null) continue;
            CableSegment seg = segmentsById.get(id);
            CableTypeConfig cfg = seg == null ? null : configs.get(seg.type());
            if (cfg == null) continue;
            queue.add(new PathNode(cable, List.of(cable), cfg.lossEuPerMeter(), cfg.maxEuPerSecond()));
            visited.add(cable);
        }
        boolean overloaded = false;
        while (!queue.isEmpty() && visited.size() <= maxSegmentsPerNetwork && routes.size() < maxRoutesPerNetwork) {
            PathNode node = queue.removeFirst();
            for (BlockFace face : faces()) {
                BlockPos next = node.pos().relative(face);
                UUID nextCable = isLoaded(next) ? occupancyByBlock.get(next) : null;
                if (nextCable != null && visited.add(next)) {
                    CableSegment seg = segmentsById.get(nextCable);
                    CableTypeConfig cfg = seg == null ? null : configs.get(seg.type());
                    if (cfg != null) {
                        List<BlockPos> path = new ArrayList<>(node.path());
                        path.add(next);
                        queue.add(new PathNode(next, path, node.loss() + cfg.lossEuPerMeter(), Math.min(node.bottleneck(), cfg.maxEuPerSecond())));
                    }
                    continue;
                }
                if (!isLoaded(next)) continue;
                Block block = next.block(world);
                MachineDefinition machine = machines.machineAt(block);
                if (machine != null && isValidPort(machine, block, face.getOppositeFace(), true)) {
                    routes.add(new EnergyRoute(next, node.path(), node.loss(), node.bottleneck()));
                }
            }
        }
        if (!queue.isEmpty() && (visited.size() > maxSegmentsPerNetwork || routes.size() >= maxRoutesPerNetwork)) {
            overloaded = true;
        }
        if (overloaded) {
            overloadedProducers.put(BlockPos.of(generatorBlock), System.currentTimeMillis() + overloadedNetworkRetryTicks * 50L);
            return List.of();
        }
        return routes;
    }

    private boolean isValidPort(MachineDefinition machine, Block block, BlockFace sideCableTouches, boolean consumer) {
        if (machine == null || block == null) return false;
        if ("ACCUMULATOR".equalsIgnoreCase(machine.type())) {
            if (consumer) return sideCableTouches == machines.accumulatorInputFace(block);
            return sideCableTouches != machines.accumulatorInputFace(block);
        }
        if (machine.energy().generator()) return !consumer && outputFaces(block, machine).contains(sideCableTouches);
        return consumer && machines.canReceiveEnergyFrom(block, machine, sideCableTouches);
    }

    private List<BlockFace> outputFaces(Block block, MachineDefinition machine) {
        if ("ACCUMULATOR".equalsIgnoreCase(machine.type())) {
            BlockFace input = machines.accumulatorInputFace(block);
            return faces().stream().filter(f -> f != input).toList();
        }
        if (machine.energy().generator()) return List.of(machines.leftOf(block), machines.rightOf(block));
        return List.of();
    }

    private int degreeIfPlaced(BlockPos p, BlockPos start, BlockPos end) {
        int degree = 0;
        Set<BlockPos> proposed = new HashSet<>(line(start, end));
        for (BlockFace face : faces()) {
            BlockPos n = p.relative(face);
            if (occupancyByBlock.containsKey(n) || proposed.contains(n)) degree++;
        }
        return degree;
    }

    private List<BlockFace> faces() { return List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN); }

    private Axis axis(BlockPos a, BlockPos b) {
        int changed = 0;
        if (a.x() != b.x()) changed++;
        if (a.y() != b.y()) changed++;
        if (a.z() != b.z()) changed++;
        if (changed > 1) return null;
        if (a.x() != b.x()) return Axis.X;
        if (a.y() != b.y()) return Axis.Y;
        return Axis.Z;
    }

    private int distance(BlockPos a, BlockPos b) { return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z()); }

    private List<BlockPos> line(BlockPos a, BlockPos b) {
        List<BlockPos> out = new ArrayList<>();
        int dx = Integer.compare(b.x(), a.x()), dy = Integer.compare(b.y(), a.y()), dz = Integer.compare(b.z(), a.z());
        int steps = distance(a, b);
        for (int i = 0; i <= steps; i++) out.add(new BlockPos(a.world(), a.x() + dx * i, a.y() + dy * i, a.z() + dz * i));
        return out;
    }

    private void spawnDisplayIfLoaded(CableSegment segment) {
        // Jeden segment kabla = jeden BlockDisplay. Jeżeli segment przechodzi przez granicę chunków,
        // renderujemy go dopiero wtedy, gdy wszystkie chunki segmentu są załadowane.
        // Dzięki temu nie pokazujemy wizualizacji kabla w części świata, która jest już poza aktywnym obszarem.
        if (segment == null || displayedSegments.contains(segment.id()) || !allChunksLoaded(segment)) return;
        spawnDisplay(segment);
        displayedSegments.add(segment.id());
    }

    private void refreshLoadedDisplays() {
        queueLoadedDisplays();
    }

    private void respawnLoadedDisplays() {
        queueLoadedDisplays();
    }

    private void queueLoadedDisplays() {
        for (CableSegment segment : segmentsById.values()) enqueueDisplayRefresh(segment.id());
    }

    private void spawnDisplay(CableSegment segment) {
        CableTypeConfig cfg = configs.get(segment.type());
        if (cfg == null) return;
        switch (segment.type()) {
            case COPPER -> spawnWrappedMetalCable(segment, Material.BROWN_CONCRETE, cfg.viewRange());
            case GOLD -> spawnWrappedMetalCable(segment, Material.YELLOW_CONCRETE, cfg.viewRange());
            case GLASS -> spawnCablePart(segment, Material.GLASS, 0.30f, 0.30f, 0.0f, 0.0f, cfg.viewRange());
        }
        spawnCableConnectors(segment, cfg);
        spawnCableConnectionArms(segment, cfg);
        spawnCablePortArms(segment, cfg);
    }

    private void spawnWrappedMetalCable(CableSegment segment, Material coreMaterial, float viewRange) {
        // Rdzeń 0.2 oraz czarna izolacja 0.4 jako cztery paski wokół rdzenia.
        // Dzięki temu kolorowy środek jest widoczny, zamiast być ukryty w pełnym czarnym prostopadłościanie.
        spawnCablePart(segment, coreMaterial, 0.20f, 0.20f, 0.0f, 0.0f, viewRange);
        spawnCablePart(segment, Material.BLACK_CONCRETE, 0.10f, 0.40f, 0.15f, 0.0f, viewRange);
        spawnCablePart(segment, Material.BLACK_CONCRETE, 0.10f, 0.40f, -0.15f, 0.0f, viewRange);
        spawnCablePart(segment, Material.BLACK_CONCRETE, 0.20f, 0.10f, 0.0f, 0.15f, viewRange);
        spawnCablePart(segment, Material.BLACK_CONCRETE, 0.20f, 0.10f, 0.0f, -0.15f, viewRange);
    }

    private void spawnCablePart(CableSegment segment, Material material, float crossA, float crossB, float offsetA, float offsetB, float viewRange) {
        World world = Bukkit.getWorld(segment.world());
        if (world == null || material == null) return;
        int minX = Math.min(segment.start().x(), segment.end().x());
        int minY = Math.min(segment.start().y(), segment.end().y());
        int minZ = Math.min(segment.start().z(), segment.end().z());
        Location base = new Location(world, minX, minY, minZ);
        if (!canSpawnDisplayAt(base)) return;
        float a = Math.max(0.03f, crossA);
        float b = Math.max(0.03f, crossB);
        Vector3f scale = switch (segment.axis()) {
            case X -> new Vector3f(segment.length(), a, b);
            case Y -> new Vector3f(a, segment.length(), b);
            case Z -> new Vector3f(a, b, segment.length());
        };
        Vector3f translation = switch (segment.axis()) {
            case X -> new Vector3f(0.0f, 0.5f + offsetA - a / 2f, 0.5f + offsetB - b / 2f);
            case Y -> new Vector3f(0.5f + offsetA - a / 2f, 0.0f, 0.5f + offsetB - b / 2f);
            case Z -> new Vector3f(0.5f + offsetA - a / 2f, 0.5f + offsetB - b / 2f, 0.0f);
        };
        BlockDisplay display = (BlockDisplay) world.spawnEntity(base, EntityType.BLOCK_DISPLAY);
        display.setBlock(material.createBlockData());
        display.setTransformation(new Transformation(translation, new AxisAngle4f(), scale, new AxisAngle4f()));
        display.setBillboard(Display.Billboard.FIXED);
        applyConfiguredVisualBrightness(display, base, material);
        display.setViewRange(viewRange);
        display.setPersistent(false);
        display.getPersistentDataContainer().set(cableVisualKey, PersistentDataType.STRING, segment.id().toString());
        if (segment.townUuid() != null) display.getPersistentDataContainer().set(cableVisualTownKey, PersistentDataType.STRING, segment.townUuid().toString());
        display.getPersistentDataContainer().set(cableVisualTypeKey, PersistentDataType.STRING, "cable_visual");
        display.getPersistentDataContainer().set(cableVisualObjectIdKey, PersistentDataType.STRING, segment.id().toString());
        registerDisplay(segment, display);
    }

    private void spawnCableConnectors(CableSegment segment, CableTypeConfig cfg) {
        World world = Bukkit.getWorld(segment.world());
        if (world == null || cfg == null) return;
        Material material = switch (segment.type()) {
            case COPPER, GOLD -> Material.BLACK_CONCRETE;
            case GLASS -> Material.GLASS;
        };
        for (BlockPos pos : line(segment.start(), segment.end())) {
            int degree = cableDegree(pos);
            if (degree <= 2 && !touchesMachinePort(pos)) continue;
            Location base = pos.block(world).getLocation();
            if (!canSpawnDisplayAt(base)) continue;
            BlockDisplay display = (BlockDisplay) world.spawnEntity(base, EntityType.BLOCK_DISPLAY);
            display.setBlock(material.createBlockData());
            display.setTransformation(new Transformation(new Vector3f(0.30f, 0.30f, 0.30f), new AxisAngle4f(), new Vector3f(0.40f, 0.40f, 0.40f), new AxisAngle4f()));
            display.setBillboard(Display.Billboard.FIXED);
            applyConfiguredVisualBrightness(display, base, material);
            display.setViewRange(cfg.viewRange());
            display.setPersistent(false);
            display.getPersistentDataContainer().set(cableVisualKey, PersistentDataType.STRING, segment.id().toString());
        if (segment.townUuid() != null) display.getPersistentDataContainer().set(cableVisualTownKey, PersistentDataType.STRING, segment.townUuid().toString());
        display.getPersistentDataContainer().set(cableVisualTypeKey, PersistentDataType.STRING, "cable_visual");
        display.getPersistentDataContainer().set(cableVisualObjectIdKey, PersistentDataType.STRING, segment.id().toString());
            registerDisplay(segment, display);
        }
    }

    private void spawnCableConnectionArms(CableSegment segment, CableTypeConfig cfg) {
        World world = Bukkit.getWorld(segment.world());
        if (world == null || cfg == null) return;
        Material material = switch (segment.type()) {
            case COPPER -> Material.BROWN_CONCRETE;
            case GOLD -> Material.YELLOW_CONCRETE;
            case GLASS -> Material.GLASS;
        };
        float thickness = segment.type() == CableType.GLASS ? 0.16f : 0.18f;
        Set<BlockPos> cableLine = new HashSet<>(line(segment.start(), segment.end()));
        for (BlockPos pos : cableLine) {
            for (BlockFace face : faces()) {
                BlockPos neighbor = pos.relative(face);
                UUID neighborId = occupancyByBlock.get(neighbor);
                if (neighborId == null) continue;
                if (segment.id().equals(neighborId) && cableLine.contains(neighbor)) continue;
                if (axis(face) == segment.axis()) continue;
                // Gdy sąsiadem jest inny kabel, każdy segment dorysowuje krótki odcinek do granicy bloku.
                // Dzięki temu kable stawiane obok siebie łączą się wizualnie tak samo jak kabel z portem maszyny.
                spawnCableArm(segment, pos, face, material, thickness, cfg.viewRange());
            }
        }
    }

    private void spawnCablePortArms(CableSegment segment, CableTypeConfig cfg) {
        World world = Bukkit.getWorld(segment.world());
        if (world == null || cfg == null) return;
        Material material = switch (segment.type()) {
            case COPPER -> Material.BROWN_CONCRETE;
            case GOLD -> Material.YELLOW_CONCRETE;
            case GLASS -> Material.GLASS;
        };
        float thickness = segment.type() == CableType.GLASS ? 0.16f : 0.18f;
        Set<BlockPos> cableLine = new HashSet<>(line(segment.start(), segment.end()));
        for (BlockPos pos : cableLine) {
            for (BlockFace face : faces()) {
                if (axis(face) == segment.axis() && cableLine.contains(pos.relative(face))) continue;
                if (!touchesMachinePortOnFace(pos, face)) continue;
                // Zagięcie do portu maszyny: główna rura zostaje na swojej osi, a krótki odcinek 90°
                // fizycznie dochodzi do wejścia/wyjścia urządzenia. Daje to kształt L/T/+ bez dodatkowego tickowania.
                spawnCableArm(segment, pos, face, material, thickness, cfg.viewRange());
            }
        }
    }

    private void spawnCableArm(CableSegment segment, BlockPos pos, BlockFace face, Material material, float thickness, float viewRange) {
        World world = Bukkit.getWorld(pos.world());
        if (world == null || material == null || face == null) return;
        float t = Math.max(0.05f, thickness);
        Vector3f scale = switch (axis(face)) {
            case X -> new Vector3f(0.50f, t, t);
            case Y -> new Vector3f(t, 0.50f, t);
            case Z -> new Vector3f(t, t, 0.50f);
        };
        Vector3f translation = switch (face) {
            case EAST -> new Vector3f(0.50f, 0.50f - t / 2f, 0.50f - t / 2f);
            case WEST -> new Vector3f(0.00f, 0.50f - t / 2f, 0.50f - t / 2f);
            case UP -> new Vector3f(0.50f - t / 2f, 0.50f, 0.50f - t / 2f);
            case DOWN -> new Vector3f(0.50f - t / 2f, 0.00f, 0.50f - t / 2f);
            case SOUTH -> new Vector3f(0.50f - t / 2f, 0.50f - t / 2f, 0.50f);
            case NORTH -> new Vector3f(0.50f - t / 2f, 0.50f - t / 2f, 0.00f);
            default -> new Vector3f(0.50f - t / 2f, 0.50f - t / 2f, 0.50f - t / 2f);
        };
        Location base = pos.block(world).getLocation();
        if (!canSpawnDisplayAt(base)) return;
        BlockDisplay display = (BlockDisplay) world.spawnEntity(base, EntityType.BLOCK_DISPLAY);
        display.setBlock(material.createBlockData());
        display.setTransformation(new Transformation(translation, new AxisAngle4f(), scale, new AxisAngle4f()));
        display.setBillboard(Display.Billboard.FIXED);
        applyConfiguredVisualBrightness(display, base, material);
        display.setViewRange(viewRange);
        display.setPersistent(false);
        display.getPersistentDataContainer().set(cableVisualKey, PersistentDataType.STRING, segment.id().toString());
        if (segment.townUuid() != null) display.getPersistentDataContainer().set(cableVisualTownKey, PersistentDataType.STRING, segment.townUuid().toString());
        display.getPersistentDataContainer().set(cableVisualTypeKey, PersistentDataType.STRING, "cable_visual");
        display.getPersistentDataContainer().set(cableVisualObjectIdKey, PersistentDataType.STRING, segment.id().toString());
        registerDisplay(segment, display);
    }

    private boolean touchesMachinePortOnFace(BlockPos pos, BlockFace face) {
        if (pos == null || face == null || machines == null) return false;
        World world = Bukkit.getWorld(pos.world());
        if (world == null) return false;
        Block block = pos.relative(face).block(world);
        MachineDefinition machine = machines.machineAt(block);
        if (machine == null) return false;
        BlockFace cableSideOnMachine = face.getOppositeFace();
        return isValidPort(machine, block, cableSideOnMachine, true) || isValidPort(machine, block, cableSideOnMachine, false);
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

    private int cableDegree(BlockPos pos) {
        int degree = 0;
        for (BlockFace face : faces()) if (occupancyByBlock.containsKey(pos.relative(face))) degree++;
        return degree;
    }

    private boolean touchesMachinePort(BlockPos pos) {
        if (machines == null) return false;
        World world = Bukkit.getWorld(pos.world());
        if (world == null) return false;
        for (BlockFace face : faces()) {
            Block block = pos.relative(face).block(world);
            MachineDefinition machine = machines.machineAt(block);
            if (machine != null && (isValidPort(machine, block, face.getOppositeFace(), true) || isValidPort(machine, block, face.getOppositeFace(), false))) return true;
        }
        return false;
    }

    private void cleanupDisplay(UUID segmentId) {
        if (segmentId == null) return;
        Set<UUID> entities = displayEntitiesBySegment.remove(segmentId);
        if (entities != null) {
            for (UUID entityId : entities) {
                Entity entity = Bukkit.getEntity(entityId);
                if (entity != null) entity.remove();
                for (Set<UUID> chunkEntities : displayEntitiesByChunk.values()) chunkEntities.remove(entityId);
            }
            displayEntitiesByChunk.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        }
        displayedSegments.remove(segmentId);
    }

    private void cleanupAllDisplays() {
        for (UUID segmentId : new ArrayList<>(displayEntitiesBySegment.keySet())) cleanupDisplay(segmentId);
        // Jednorazowy fallback na start/reload pluginu: usuwa stare Displaye bez wpisu w runtime rejestrze.
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e.getPersistentDataContainer().has(cableVisualKey, PersistentDataType.STRING)) e.remove();
            }
        }
        displayEntitiesBySegment.clear();
        displayEntitiesByChunk.clear();
        displayedSegments.clear();
    }

    private boolean allChunksLoaded(CableSegment segment) {
        World world = Bukkit.getWorld(segment.world());
        if (world == null) return false;
        for (long key : segment.chunkKeys()) {
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) key;
            if (!world.isChunkLoaded(chunkX, chunkZ)) return false;
        }
        return true;
    }

    private boolean isLoaded(BlockPos pos) {
        World world = Bukkit.getWorld(pos.world());
        return world != null && world.isChunkLoaded(Math.floorDiv(pos.x(), 16), Math.floorDiv(pos.z(), 16));
    }

    private boolean isBlockLoaded(Block block) {
        if (block == null || block.getWorld() == null) return false;
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    private void consumeOne(Player player, ItemStack hand) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        hand.setAmount(Math.max(0, hand.getAmount() - 1));
    }

    private String specialItemId(CableType type) {
        return switch (type) {
            case COPPER -> "copper_cable";
            case GOLD -> "gold_cable";
            case GLASS -> "glass_cable";
        };
    }

    private String rootMessage(Throwable throwable) {
        Throwable t = throwable;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private record Placement(BlockPos start, BlockPos end, Axis axis) {}
    private record PathNode(BlockPos pos, List<BlockPos> path, double loss, int bottleneck) {}
}
