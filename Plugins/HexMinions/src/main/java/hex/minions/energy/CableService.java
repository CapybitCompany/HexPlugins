package hex.minions.energy;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
import hex.minions.machine.MachineDefinition;
import hex.minions.machine.MachineRuntime;
import hex.minions.machine.MachineService;
import hex.minions.service.MinionService;
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
    private final Map<CableType, CableTypeConfig> configs = new EnumMap<>(CableType.class);
    private final Map<UUID, CableSegment> segmentsById = new LinkedHashMap<>();
    private final Map<BlockPos, UUID> occupancyByBlock = new LinkedHashMap<>();
    private final Map<Long, Set<UUID>> segmentsByChunk = new LinkedHashMap<>();
    private final Map<BlockPos, List<EnergyRoute>> routeCacheByProducer = new ConcurrentHashMap<>();
    private final Set<UUID> displayedSegments = new HashSet<>();

    private final int maxSegmentsPerNetwork;
    private final int maxRoutesPerNetwork;
    private volatile boolean dbReady;

    public CableService(Plugin plugin, HexApi hex, TownsApi towns, MinionService minions) {
        this.plugin = plugin;
        this.hex = hex;
        this.towns = towns;
        this.minions = minions;
        this.repository = new CableRepository(hex.db().db());
        this.writeQueue = new DatabaseWriteQueue(plugin, hex, repository);
        this.cableVisualKey = new NamespacedKey(plugin, "energy_cable_segment");
        loadConfig();
        ConfigurationSection energy = energySection();
        this.maxSegmentsPerNetwork = Math.max(1, energy == null ? 256 : energy.getInt("max-segments-per-network", energy.getInt("max_segments_per_network", 256)));
        this.maxRoutesPerNetwork = Math.max(1, energy == null ? 512 : energy.getInt("max-routes-per-network", energy.getInt("max_routes_per_network", 512)));
        loadPersistedAsync();
    }

    public void attachMachines(MachineService machines) { this.machines = machines; }

    public void clearRouteCache() {
        routeCacheByProducer.clear();
    }

    public void refreshVisuals() {
        routeCacheByProducer.clear();
        if (!dbReady) return;
        refreshLoadedDisplays();
    }


    public void shutdown() {
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
            for (CableSegment segment : loaded) {
                if (segment == null) continue;
                if (canLoadSegment(segment)) addSegmentToMemory(segment);
            }
            routeCacheByProducer.clear();
            dbReady = true;
            respawnLoadedDisplays();
            plugin.getLogger().info("HexMinions energy cables loaded=" + segmentsById.size());
        })).exceptionally(ex -> {
            plugin.getLogger().warning("Nie udało się wczytać kabli EU: " + rootMessage(ex));
            dbReady = true;
            return null;
        });
    }

    private boolean canLoadSegment(CableSegment segment) {
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
    public void onPlaceCable(BlockPlaceEvent event) {
        Optional<String> special = minions.specialItems().readSpecialItemId(event.getItemInHand());
        CableType type = special.map(CableType::fromSpecialItem).orElse(null);
        if (type == null) return;
        event.setCancelled(true);
        if (!dbReady) {
            hex.ui().send(event.getPlayer(), "minions.cable.loading");
            return;
        }
        Player player = event.getPlayer();
        Block startBlock = event.getBlockPlaced();
        Placement placement = resolvePlacement(player, startBlock, type);
        if (placement == null) {
            hex.ui().send(player, "minions.cable.invalid-shape");
            return;
        }
        String error = validateSegment(placement.start(), placement.end(), type, event.getBlockPlaced(), event.getBlockReplacedState().getType());
        if (error != null) {
            hex.ui().send(player, "minions.cable.validation-error", UiTokens.of("error", error));
            return;
        }
        if (towns.townAt(startBlock.getLocation()).filter(t -> towns.isMember(player.getUniqueId(), t.id())).isEmpty()) {
            hex.ui().send(player, "minions.cable.place.not-town");
            return;
        }
        CableSegment segment = createSegment(placement.start(), placement.end(), placement.axis(), type);
        addSegmentToMemory(segment);
        consumeOne(player, event.getItemInHand());
        routeCacheByProducer.clear();
        refreshLoadedDisplays();
        writeQueue.enqueueInsertCable(segment);
        writeQueue.flushAsync();
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
            routeCacheByProducer.clear();
            long chunkKey = CableSegment.chunkKey(event.getChunk().getX(), event.getChunk().getZ());
            Set<UUID> ids = segmentsByChunk.get(chunkKey);
            if (ids == null || ids.isEmpty()) return;
            refreshLoadedDisplays();
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        routeCacheByProducer.clear();
        long chunkKey = CableSegment.chunkKey(event.getChunk().getX(), event.getChunk().getZ());
        Set<UUID> ids = segmentsByChunk.get(chunkKey);
        if (ids == null || ids.isEmpty()) return;
        for (UUID id : ids) cleanupDisplay(id);
    }


    private boolean canModifyCable(Player player, CableSegment segment) {
        if (player == null || segment == null) return false;
        if (player.isOp() || player.hasPermission("hexminions.cables.admin")) return true;
        World world = Bukkit.getWorld(segment.world());
        if (world == null) return false;
        Location start = segment.start().block(world).getLocation();
        return towns.townAt(start).filter(town -> towns.isMember(player.getUniqueId(), town.id())).isPresent();
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

    private String validateSegment(BlockPos start, BlockPos end, CableType type, Block placedBlock, Material replacedMaterial) {
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

    private CableSegment createSegment(BlockPos start, BlockPos end, Axis placementAxis, CableType type) {
        Axis segmentAxis = axis(start, end);
        if (distance(start, end) == 0 && placementAxis != null) segmentAxis = placementAxis;
        return new CableSegment(UUID.randomUUID(), start.world(), start, end, segmentAxis, type, distance(start, end) + 1);
    }

    private void addSegmentToMemory(CableSegment segment) {
        segmentsById.put(segment.id(), segment);
        for (BlockPos p : line(segment.start(), segment.end())) occupancyByBlock.put(p, segment.id());
        for (long chunkKey : segment.chunkKeys()) segmentsByChunk.computeIfAbsent(chunkKey, k -> new HashSet<>()).add(segment.id());
        routeCacheByProducer.clear();
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
        if (ids == null || ids.isEmpty()) return;
        boolean changed = false;
        for (UUID id : new ArrayList<>(ids)) {
            CableSegment segment = segmentsById.remove(id);
            if (segment == null) continue;
            changed = true;
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
        routeCacheByProducer.clear();
        refreshLoadedDisplays();
        writeQueue.flushAsync();
        if (player != null) hex.ui().send(player, "minions.cable.remove.success");
    }

    public int transferFromGenerator(Block generatorBlock, MachineDefinition generator, MachineRuntime generatorRuntime) {
        if (generatorBlock == null || generator == null || generatorRuntime == null || generatorRuntime.energy() <= 0 || machines == null) return 0;
        // Transfer po kablach jest dozwolony tylko przez aktualnie załadowaną trasę.
        // Nie force-loadujemy chunków i nie wysyłamy EU do maszyn/akumulatorów w niezaładowanym chunku.
        if (!isBlockLoaded(generatorBlock)) return 0;
        BlockPos producer = BlockPos.of(generatorBlock);
        List<EnergyRoute> routes = routeCacheByProducer.computeIfAbsent(producer, p -> buildRoutes(generatorBlock, generator));
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
            int need = Math.max(0, consumerRuntime.capacity(consumer) - consumerRuntime.energy());
            if (need <= 0) continue;
            int routeLimit = Math.max(0, Math.min(generator.energy().transferPerSecond(), route.bottleneckEuPerSecond()));
            int loss = (int) Math.ceil(route.lossEu());
            int deliver = Math.min(need, Math.max(0, Math.min(routeLimit, generatorRuntime.energy() - loss)));
            if (deliver <= 0) continue;
            int cost = deliver + loss;
            generatorRuntime.energy(generatorRuntime.energy() - cost);
            consumerRuntime.addEnergy(deliver, consumerRuntime.capacity(consumer));
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
        return routes;
    }

    private boolean isValidPort(MachineDefinition machine, Block block, BlockFace sideCableTouches, boolean consumer) {
        if (machine == null || block == null) return false;
        if ("ACCUMULATOR".equalsIgnoreCase(machine.type())) {
            if (consumer) return sideCableTouches == machines.accumulatorInputFace(block);
            return sideCableTouches != machines.accumulatorInputFace(block);
        }
        if (machine.energy().generator()) return !consumer && outputFaces(block, machine).contains(sideCableTouches);
        return consumer && inputFaces(block, machine).contains(sideCableTouches);
    }

    private List<BlockFace> outputFaces(Block block, MachineDefinition machine) {
        if ("ACCUMULATOR".equalsIgnoreCase(machine.type())) {
            BlockFace input = machines.accumulatorInputFace(block);
            return faces().stream().filter(f -> f != input).toList();
        }
        if (machine.energy().generator()) return List.of(machines.leftOf(block), machines.rightOf(block));
        return List.of();
    }

    private List<BlockFace> inputFaces(Block block, MachineDefinition machine) {
        if ("ACCUMULATOR".equalsIgnoreCase(machine.type())) return List.of(machines.accumulatorInputFace(block));
        if ("MACERATOR".equalsIgnoreCase(machine.type())) {
            return List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.DOWN);
        }
        // Domyślne urządzenia pobierające EU przyjmują prąd z tyłu, lewej, prawej i od dołu.
        return List.of(machines.facing(block).getOppositeFace(), machines.leftOf(block), machines.rightOf(block), BlockFace.DOWN);
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
        cleanupAllDisplays();
        respawnLoadedDisplays();
    }

    private void respawnLoadedDisplays() {
        for (CableSegment segment : segmentsById.values()) spawnDisplayIfLoaded(segment);
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
            BlockDisplay display = (BlockDisplay) world.spawnEntity(base, EntityType.BLOCK_DISPLAY);
            display.setBlock(material.createBlockData());
            display.setTransformation(new Transformation(new Vector3f(0.30f, 0.30f, 0.30f), new AxisAngle4f(), new Vector3f(0.40f, 0.40f, 0.40f), new AxisAngle4f()));
            display.setBillboard(Display.Billboard.FIXED);
            applyConfiguredVisualBrightness(display, base, material);
            display.setViewRange(cfg.viewRange());
            display.setPersistent(false);
            display.getPersistentDataContainer().set(cableVisualKey, PersistentDataType.STRING, segment.id().toString());
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
        BlockDisplay display = (BlockDisplay) world.spawnEntity(base, EntityType.BLOCK_DISPLAY);
        display.setBlock(material.createBlockData());
        display.setTransformation(new Transformation(translation, new AxisAngle4f(), scale, new AxisAngle4f()));
        display.setBillboard(Display.Billboard.FIXED);
        applyConfiguredVisualBrightness(display, base, material);
        display.setViewRange(viewRange);
        display.setPersistent(false);
        display.getPersistentDataContainer().set(cableVisualKey, PersistentDataType.STRING, segment.id().toString());
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
        String id = segmentId.toString();
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (id.equals(e.getPersistentDataContainer().get(cableVisualKey, PersistentDataType.STRING))) e.remove();
            }
        }
        displayedSegments.remove(segmentId);
    }

    private void cleanupAllDisplays() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e.getPersistentDataContainer().has(cableVisualKey, PersistentDataType.STRING)) e.remove();
            }
        }
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
