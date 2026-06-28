package hex.minions.energy;

import hex.core.api.HexApi;
import hex.minions.machine.MachineDefinition;
import hex.minions.machine.MachineRuntime;
import hex.minions.machine.MachineService;
import hex.minions.service.MinionService;
import hex.towns.api.TownsApi;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
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
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
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


    public void shutdown() {
        writeQueue.flushAsync();
        cleanupAllDisplays();
    }

    private void loadConfig() {
        configs.put(CableType.COPPER, new CableTypeConfig("Miedziany Kabel", 3, 0.25, 32, Material.COPPER_BLOCK, 0.12, 32f));
        configs.put(CableType.GOLD, new CableTypeConfig("Złoty Kabel", 10, 0.15, 96, Material.GOLD_BLOCK, 0.12, 32f));
        configs.put(CableType.GLASS, new CableTypeConfig("Szklany Kabel", 25, 0.05, 256, Material.LIGHT_BLUE_STAINED_GLASS, 0.10, 48f));
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
            event.getPlayer().sendMessage("§cSystem kabli jeszcze się ładuje. Spróbuj ponownie za chwilę.");
            return;
        }
        Player player = event.getPlayer();
        Block startBlock = event.getBlockPlaced();
        Placement placement = resolvePlacement(player, startBlock, type);
        if (placement == null) {
            player.sendMessage("§cKabel musi być prostym odcinkiem i dotykać portu maszyny albo końca kabla.");
            return;
        }
        String error = validateSegment(placement.start(), placement.end(), type);
        if (error != null) {
            player.sendMessage("§c" + error);
            return;
        }
        if (towns.townAt(startBlock.getLocation()).filter(t -> towns.isMember(player.getUniqueId(), t.id())).isEmpty()) {
            player.sendMessage("§cKable możesz stawiać tylko w swoim mieście.");
            return;
        }
        CableSegment segment = createSegment(placement.start(), placement.end(), type);
        addSegmentToMemory(segment);
        consumeOne(player, event.getItemInHand());
        routeCacheByProducer.clear();
        spawnDisplayIfLoaded(segment);
        writeQueue.enqueueInsertCable(segment);
        writeQueue.flushAsync();
        player.sendMessage("§aPołożono " + configs.get(type).displayName() + " §7(" + segment.length() + "m). §8Kabel jest pasywnym segmentem sieci EU.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakCableHelper(BlockBreakEvent event) {
        BlockPos pos = BlockPos.of(event.getBlock());
        UUID id = occupancyByBlock.get(pos);
        if (id == null) return;
        event.setCancelled(true);
        removeCable(id, true, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onUseCable(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_AIR) return;
        UUID id = null;
        if (event.getClickedBlock() != null) id = occupancyByBlock.get(BlockPos.of(event.getClickedBlock()));
        if (id == null) id = findCableInSight(event.getPlayer(), 6.0);
        if (id == null) return;
        event.setCancelled(true);
        CableSegment segment = segmentsById.get(id);
        if (segment == null) return;
        CableTypeConfig cfg = configs.get(segment.type());
        event.getPlayer().sendMessage("§7Kabel: §f" + cfg.displayName() + " §8| §7długość: §f" + segment.length() + "m §8| §7limit: §f" + cfg.maxEuPerSecond() + " EU/s §8| §7strata: §f" + cfg.lossEuPerMeter() + " EU/m");
        if (event.getPlayer().isSneaking()) removeCable(segment.id(), true, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            routeCacheByProducer.clear();
            long chunkKey = CableSegment.chunkKey(event.getChunk().getX(), event.getChunk().getZ());
            Set<UUID> ids = segmentsByChunk.get(chunkKey);
            if (ids == null || ids.isEmpty()) return;
            for (UUID id : ids) {
                CableSegment segment = segmentsById.get(id);
                if (segment != null) spawnDisplayIfLoaded(segment);
            }
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

    private UUID findCableInSight(Player player, double maxDistance) {
        if (player == null || player.getWorld() == null) return null;
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector dir = eye.getDirection().normalize();
        for (double d = 0.0; d <= maxDistance; d += 0.20) {
            Location point = eye.clone().add(dir.clone().multiply(d));
            UUID id = occupancyByBlock.get(BlockPos.of(point));
            if (id != null) return id;
        }
        return null;
    }

    private Placement resolvePlacement(Player player, Block firstBlock, CableType type) {
        BlockFace face = player.getFacing();
        if (face == BlockFace.UP || face == BlockFace.DOWN) face = BlockFace.NORTH;
        CableTypeConfig cfg = configs.get(type);
        BlockPos start = BlockPos.of(firstBlock);
        BlockPos end = start;
        for (int i = 1; i < cfg.maxSegmentLength(); i++) {
            Block next = firstBlock.getRelative(face, i);
            BlockPos np = BlockPos.of(next);
            if (occupancyByBlock.containsKey(np) || machines.machineAt(next) != null) break;
            end = np;
            if (touchesEndpoint(end, previousOnLine(start, end))) break;
        }
        return new Placement(start, end);
    }

    private BlockPos previousOnLine(BlockPos start, BlockPos end) {
        if (start.equals(end)) return null;
        int dx = Integer.compare(end.x(), start.x());
        int dy = Integer.compare(end.y(), start.y());
        int dz = Integer.compare(end.z(), start.z());
        return new BlockPos(end.world(), end.x() - dx, end.y() - dy, end.z() - dz);
    }

    private boolean touchesEndpoint(BlockPos pos, BlockPos ignoreNeighbor) {
        World world = Bukkit.getWorld(pos.world());
        if (world == null || machines == null) return false;
        for (BlockFace face : faces()) {
            BlockPos n = pos.relative(face);
            if (n.equals(ignoreNeighbor)) continue;
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

    private String validateSegment(BlockPos start, BlockPos end, CableType type) {
        if (machines == null) return "System maszyn nie jest jeszcze gotowy.";
        if (!start.world().equals(end.world())) return "Kabel musi być w jednym świecie.";
        Axis axis = axis(start, end);
        if (axis == null) return "Kabel musi być prostym odcinkiem po osi X/Y/Z.";
        int length = distance(start, end) + 1;
        CableTypeConfig cfg = configs.get(type);
        if (length > cfg.maxSegmentLength()) return "Ten kabel może mieć maksymalnie " + cfg.maxSegmentLength() + " bloki.";
        World world = Bukkit.getWorld(start.world());
        if (world == null) return "Nie znaleziono świata kabla.";
        List<BlockPos> proposedLine = line(start, end);
        for (BlockPos p : proposedLine) {
            if (occupancyByBlock.containsKey(p)) return "Kable nie mogą się przecinać ani nachodzić na siebie.";
            if (machines.machineAt(p.block(world)) != null) return "Kabel nie może przechodzić przez maszynę.";
            if (degreeIfPlaced(p, start, end) > 2) return "Rozgałęzienie kabla wymaga Junction Box / rozdzielacza.";
        }
        BlockPos afterStart = proposedLine.size() > 1 ? proposedLine.get(1) : null;
        BlockPos beforeEnd = proposedLine.size() > 1 ? proposedLine.get(proposedLine.size() - 2) : null;
        if (!touchesEndpoint(start, afterStart) || !touchesEndpoint(end, beforeEnd)) {
            return "Oba końce kabla muszą dotykać portu EU maszyny/generatora/akumulatora albo końca innego kabla.";
        }
        return null;
    }

    private CableSegment createSegment(BlockPos start, BlockPos end, CableType type) {
        return new CableSegment(UUID.randomUUID(), start.world(), start, end, axis(start, end), type, distance(start, end) + 1);
    }

    private void addSegmentToMemory(CableSegment segment) {
        segmentsById.put(segment.id(), segment);
        for (BlockPos p : line(segment.start(), segment.end())) occupancyByBlock.put(p, segment.id());
        for (long chunkKey : segment.chunkKeys()) segmentsByChunk.computeIfAbsent(chunkKey, k -> new HashSet<>()).add(segment.id());
        routeCacheByProducer.clear();
    }

    private void removeCable(UUID id, boolean drop, Player player) {
        CableSegment segment = segmentsById.remove(id);
        if (segment == null) return;
        for (BlockPos p : line(segment.start(), segment.end())) occupancyByBlock.remove(p);
        for (long chunkKey : segment.chunkKeys()) {
            Set<UUID> set = segmentsByChunk.get(chunkKey);
            if (set != null) {
                set.remove(segment.id());
                if (set.isEmpty()) segmentsByChunk.remove(chunkKey);
            }
        }
        cleanupDisplay(segment.id());
        routeCacheByProducer.clear();
        writeQueue.enqueueDeleteCable(segment.id());
        writeQueue.flushAsync();
        if (drop) {
            World world = Bukkit.getWorld(segment.world());
            if (world != null) {
                ItemStack item = minions.specialItems().createItem(specialItemId(segment.type()), 1);
                world.dropItemNaturally(segment.start().block(world).getLocation().add(0.5, 0.5, 0.5), item);
            }
        }
        if (player != null) player.sendMessage("§eUsunięto cały segment kabla, nie pojedynczy metr.");
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
        if ("ELECTRIC_MILL".equalsIgnoreCase(machine.type())) return List.of(machines.facing(block).getOppositeFace(), BlockFace.DOWN);
        return List.of(machines.facing(block).getOppositeFace());
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
        Location base = segment.start().block(world).getLocation().add(0.5, 0.5, 0.5);
        float a = Math.max(0.03f, crossA);
        float b = Math.max(0.03f, crossB);
        Vector3f scale = switch (segment.axis()) {
            case X -> new Vector3f(segment.length(), a, b);
            case Y -> new Vector3f(a, segment.length(), b);
            case Z -> new Vector3f(a, b, segment.length());
        };
        Vector3f translation = switch (segment.axis()) {
            case X -> new Vector3f((segment.end().x() - segment.start().x()) / 2.0f, offsetA - a / 2f, offsetB - b / 2f);
            case Y -> new Vector3f(offsetA - a / 2f, (segment.end().y() - segment.start().y()) / 2.0f, offsetB - b / 2f);
            case Z -> new Vector3f(offsetA - a / 2f, offsetB - b / 2f, (segment.end().z() - segment.start().z()) / 2.0f);
        };
        BlockDisplay display = (BlockDisplay) world.spawnEntity(base, EntityType.BLOCK_DISPLAY);
        display.setBlock(material.createBlockData());
        display.setTransformation(new Transformation(translation, new AxisAngle4f(), scale, new AxisAngle4f()));
        display.setBillboard(Display.Billboard.FIXED);
        display.setViewRange(viewRange);
        display.setPersistent(false);
        display.getPersistentDataContainer().set(cableVisualKey, PersistentDataType.STRING, segment.id().toString());
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

    private record Placement(BlockPos start, BlockPos end) {}
    private record PathNode(BlockPos pos, List<BlockPos> path, double loss, int bottleneck) {}
}
