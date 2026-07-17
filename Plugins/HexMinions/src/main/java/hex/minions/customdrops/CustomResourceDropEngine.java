package hex.minions.customdrops;

import hex.collections.api.CollectionProgressContext;
import hex.collections.api.CollectionSource;
import hex.core.api.HexApi;
import hex.minions.config.ResourceDefinition;
import hex.minions.service.MinionService;
import hex.towns.api.TownsApi;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lekki silnik customowych dropów. Na start obsługuje cynę: naturalny STONE/DEEPSLATE
 * przy naturalnej rudzie miedzi albo ghost-copper ma niewielką szansę na dodatkowy drop.
 *
 * Ważne wydajnościowo:
 * - BlockBreakEvent wykonuje tylko O(1): typ bloku, 6 sąsiadów, lookup w RAM, losowanie.
 * - Nie wykonujemy SQL w eventach kopania/stawiania.
 * - Dane trwałe są zapisywane per chunk, batchowo, a nie per kamień.
 */
public final class CustomResourceDropEngine implements Listener {
    private static final BlockFace[] SIX_FACES = new BlockFace[]{
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private final Plugin plugin;
    private final HexApi hex;
    private final TownsApi towns;
    private final MinionService minions;
    private final CustomResourceDropRepository repository;
    private final Map<ChunkKey, ResourceChunkData> loadedChunkData = new ConcurrentHashMap<>();
    private final Set<ChunkKey> dirtyChunks = ConcurrentHashMap.newKeySet();
    private final Queue<ChunkKey> loadQueue = new ConcurrentLinkedQueue<>();
    private final Set<ChunkKey> queuedLoads = ConcurrentHashMap.newKeySet();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private volatile CustomResourceDropSettings settings;
    private BukkitTask loadWorkerTask;
    private BukkitTask autosaveTask;

    public CustomResourceDropEngine(Plugin plugin, HexApi hex, TownsApi towns, MinionService minions) {
        this.plugin = plugin;
        this.hex = hex;
        this.towns = towns;
        this.minions = minions;
        this.repository = new CustomResourceDropRepository(hex.db().db());
        this.settings = CustomResourceDropSettings.load(plugin.getConfig());
    }

    public void start() {
        reload();
        hex.db().asyncRun(repository::ensureTables);
        loadWorkerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::drainLoadQueue, 5L, 5L);
        autosaveTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> saveDirtyChunks(settings.maxChunkSaveBatchSize(), false),
                Math.max(20L, settings.autosaveIntervalSeconds() * 20L),
                Math.max(20L, settings.autosaveIntervalSeconds() * 20L));
        enqueueCurrentlyLoadedChunks();
    }

    public void reload() {
        this.settings = CustomResourceDropSettings.load(plugin.getConfig());
    }

    public void shutdown() {
        if (loadWorkerTask != null) loadWorkerTask.cancel();
        if (autosaveTask != null) autosaveTask.cancel();
        loadWorkerTask = null;
        autosaveTask = null;
        saveDirtyChunks(Integer.MAX_VALUE, true);
        loadedChunkData.clear();
        dirtyChunks.clear();
        loadQueue.clear();
        queuedLoads.clear();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        enqueueLoad(new ChunkKey(event.getWorld().getName(), event.getChunk().getX(), event.getChunk().getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        ChunkKey key = new ChunkKey(event.getWorld().getName(), event.getChunk().getX(), event.getChunk().getZ());
        ResourceChunkData data = loadedChunkData.get(key);
        if (data != null && data.dirty()) {
            dirtyChunks.add(key);
            saveDirtyChunks(settings.maxChunkSaveBatchSize(), false);
        }
        loadedChunkData.remove(key);
        queuedLoads.remove(key);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!settings.enabled()) return;
        Block block = event.getBlockPlaced();
        if (!settings.relevantPlacedMaterial(block.getType())) return;
        BlockPos pos = BlockPos.of(block);
        ResourceChunkData data = dataForMutation(pos);
        data.addPlayerPlaced(pos);
        markDirty(pos);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!settings.enabled()) return;
        Block block = event.getBlock();
        Material type = block.getType();
        if (!settings.relevantPlacedMaterial(type)) return;

        BlockPos pos = BlockPos.of(block);
        ResourceChunkData data = loadedChunkData.get(ChunkKey.of(pos));
        if (data != null && data.hasPlayerPlaced(pos)) {
            data.removePlayerPlaced(pos);
            markDirty(pos);
            return;
        }

        if (settings.copperOreMaterial(type)) {
            handleCopperOreBreak(pos);
            return;
        }
        if (settings.stoneMaterial(type)) {
            handleStoneBreak(event, block, pos);
        }
    }

    private void handleCopperOreBreak(BlockPos pos) {
        ResourceChunkData data = loadedChunkData.get(ChunkKey.of(pos));
        if (data == null || !data.loaded()) {
            // Nie wiemy jeszcze, czy ta ruda była postawiona przez gracza. Nie ryzykujemy
            // fałszywego ghost-copper; chunk zostanie obsłużony po załadowaniu danych.
            enqueueLoad(ChunkKey.of(pos));
            return;
        }
        data.addGhostCopper(pos);
        markDirty(pos);
    }

    private void handleStoneBreak(BlockBreakEvent event, Block block, BlockPos pos) {
        ResourceChunkData current = loadedChunkData.get(ChunkKey.of(pos));
        if (current == null || !current.loaded()) {
            enqueueLoad(ChunkKey.of(pos));
            return;
        }
        if (!settings.silkTouchAllowed() && hasSilkTouch(event.getPlayer().getInventory().getItemInMainHand())) return;

        boolean eligible = false;
        List<BlockPos> adjacentGhosts = new ArrayList<>();
        for (BlockFace face : SIX_FACES) {
            BlockPos neighborPos = pos.relative(face);
            Block neighbor = blockAtIfChunkLoaded(block.getWorld(), neighborPos);
            if (neighbor != null && settings.copperOreMaterial(neighbor.getType()) && isKnownNatural(neighborPos)) {
                eligible = true;
            }
            if (hasGhostCopper(neighborPos)) {
                eligible = true;
                adjacentGhosts.add(neighborPos);
            }
        }

        if (eligible && ThreadLocalRandom.current().nextDouble() < chance(event.getPlayer().getInventory().getItemInMainHand())) {
            dropTin(event.getPlayer(), block.getLocation().add(0.5D, 0.5D, 0.5D));
        }
        for (BlockPos ghost : adjacentGhosts) cleanupGhostCopperIfNoAdjacentNaturalStone(block.getWorld(), ghost);
    }

    private double chance(ItemStack tool) {
        if (!settings.fortuneEnabled() || tool == null || tool.getType().isAir()) return settings.tinChance();
        int fortune = fortuneLevel(tool);
        if (fortune >= 3) return settings.fortune3Chance();
        if (fortune == 2) return settings.fortune2Chance();
        if (fortune == 1) return settings.fortune1Chance();
        return settings.tinChance();
    }

    private boolean hasSilkTouch(ItemStack item) {
        return item != null && item.containsEnchantment(Enchantment.SILK_TOUCH);
    }

    private int fortuneLevel(ItemStack item) {
        if (item == null || item.getEnchantments().isEmpty()) return 0;
        return item.getEnchantments().entrySet().stream()
                .filter(entry -> "fortune".equalsIgnoreCase(entry.getKey().getKey().getKey()))
                .mapToInt(Map.Entry::getValue)
                .max()
                .orElse(0);
    }

    private void dropTin(Player player, Location location) {
        ResourceDefinition resource = minions.definitions().resources().get(settings.tinDropResourceId());
        ItemStack stack = resource == null ? fallbackTinItem() : resourceStack(resource, 1);
        if (location.getWorld() != null) location.getWorld().dropItemNaturally(location, stack);
        addTinCollectionProgress(player, location);
    }

    private ItemStack fallbackTinItem() {
        ItemStack stack = new ItemStack(Material.RAW_IRON, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(miniMessage.deserialize("<gray>Ruda cyny</gray>"));
            meta.setCustomModelData(14001);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack resourceStack(ResourceDefinition resource, int amount) {
        ItemStack stack = new ItemStack(resource.material(), Math.max(1, Math.min(resource.stackSize(), amount)));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (resource.customModelData() > 0) meta.setCustomModelData(resource.customModelData());
            if (resource.displayName() != null && !resource.displayName().isBlank()) {
                meta.displayName(miniMessage.deserialize(resource.displayName()));
            }
            if ("spruce_resin".equalsIgnoreCase(resource.id())) {
                meta.setEnchantmentGlintOverride(true);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void addTinCollectionProgress(Player player, Location location) {
        try {
            Optional<UUID> townId = towns.townIdOf(player.getUniqueId());
            if (townId.isEmpty()) return;
            minions.collections().addProgress(new CollectionProgressContext()
                    .playerUuid(player.getUniqueId())
                    .townId(townId.get())
                    .collectionId("mining.tin")
                    .amount(1L)
                    .source(CollectionSource.NATURAL_BLOCK_BREAK)
                    .location(location)
                    .reason("custom-resource-drop:tin"));
        } catch (Throwable ignored) {
            // Drop nie może być blokowany awarią naliczania kolekcji.
        }
    }

    private boolean isKnownNatural(BlockPos pos) {
        ResourceChunkData data = loadedChunkData.get(ChunkKey.of(pos));
        return data != null && data.loaded() && !data.hasPlayerPlaced(pos);
    }

    private boolean hasGhostCopper(BlockPos pos) {
        ResourceChunkData data = loadedChunkData.get(ChunkKey.of(pos));
        return data != null && data.hasGhostCopper(pos);
    }

    private void cleanupGhostCopperIfNoAdjacentNaturalStone(World world, BlockPos ghostPos) {
        if (world == null) return;
        for (BlockFace face : SIX_FACES) {
            BlockPos neighborPos = ghostPos.relative(face);
            Block neighbor = blockAtIfChunkLoaded(world, neighborPos);
            if (neighbor == null) return; // nie wiemy, więc nie usuwamy ghosta zbyt agresywnie
            if (settings.stoneMaterial(neighbor.getType()) && isKnownNatural(neighborPos)) return;
        }
        ResourceChunkData data = loadedChunkData.get(ChunkKey.of(ghostPos));
        if (data != null) {
            data.removeGhostCopper(ghostPos);
            markDirty(ghostPos);
        }
    }

    private Block blockAtIfChunkLoaded(World world, BlockPos pos) {
        if (world == null || !world.getName().equals(pos.world())) return null;
        int chunkX = Math.floorDiv(pos.x(), 16);
        int chunkZ = Math.floorDiv(pos.z(), 16);
        if (!world.isChunkLoaded(chunkX, chunkZ)) return null;
        return world.getBlockAt(pos.x(), pos.y(), pos.z());
    }

    private ResourceChunkData dataForMutation(BlockPos pos) {
        ChunkKey key = ChunkKey.of(pos);
        return loadedChunkData.computeIfAbsent(key, ignored -> new ResourceChunkData(false));
    }

    private void markDirty(BlockPos pos) {
        dirtyChunks.add(ChunkKey.of(pos));
    }

    private void enqueueCurrentlyLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                enqueueLoad(new ChunkKey(world.getName(), chunk.getX(), chunk.getZ()));
            }
        }
    }

    private void enqueueLoad(ChunkKey key) {
        if (key == null || !isChunkCurrentlyLoaded(key)) return;
        loadedChunkData.computeIfAbsent(key, ignored -> new ResourceChunkData(false));
        if (queuedLoads.add(key)) loadQueue.add(key);
    }

    private void drainLoadQueue() {
        if (loadQueue.isEmpty()) return;
        List<ChunkKey> batch = new ArrayList<>();
        int max = settings.maxChunkLoadBatchSize();
        while (batch.size() < max) {
            ChunkKey key = loadQueue.poll();
            if (key == null) break;
            queuedLoads.remove(key);
            if (isChunkCurrentlyLoaded(key)) batch.add(key);
        }
        if (batch.isEmpty()) return;
        hex.db().async(() -> repository.loadBatch(batch)).thenAccept(loaded -> Bukkit.getScheduler().runTask(plugin, () -> applyLoadedData(batch, loaded)))
                .exceptionally(ex -> null);
    }

    private void applyLoadedData(List<ChunkKey> requested, Map<ChunkKey, CustomResourceDropRepository.LoadedChunkData> loaded) {
        for (ChunkKey key : requested) {
            ResourceChunkData data = loadedChunkData.get(key);
            if (data == null) continue;
            if (!isChunkCurrentlyLoaded(key) && !data.dirty()) {
                loadedChunkData.remove(key);
                continue;
            }
            CustomResourceDropRepository.LoadedChunkData loadedData = loaded.getOrDefault(key, new CustomResourceDropRepository.LoadedChunkData(Set.of(), Set.of()));
            data.mergeLoaded(loadedData.ghostCopper(), loadedData.playerPlaced());
            if (data.dirty()) dirtyChunks.add(key);
        }
    }

    private boolean isChunkCurrentlyLoaded(ChunkKey key) {
        World world = Bukkit.getWorld(key.world());
        return world != null && world.isChunkLoaded(key.chunkX(), key.chunkZ());
    }

    private void saveDirtyChunks(int max, boolean sync) {
        if (dirtyChunks.isEmpty()) return;
        List<ResourceChunkData.Snapshot> snapshots = new ArrayList<>();
        List<ChunkKey> keys = new ArrayList<>(dirtyChunks);
        Collections.sort(keys, java.util.Comparator.comparing(ChunkKey::world).thenComparingInt(ChunkKey::chunkX).thenComparingInt(ChunkKey::chunkZ));
        for (ChunkKey key : keys) {
            if (snapshots.size() >= max) break;
            ResourceChunkData data = loadedChunkData.get(key);
            if (data == null) {
                dirtyChunks.remove(key);
                continue;
            }
            if (!data.dirty()) {
                dirtyChunks.remove(key);
                continue;
            }
            snapshots.add(data.snapshotForSave(key));
            dirtyChunks.remove(key);
        }
        if (snapshots.isEmpty()) return;
        Runnable save = () -> repository.saveBatch(snapshots);
        if (sync) save.run(); else hex.db().asyncRun(save);
    }
}
