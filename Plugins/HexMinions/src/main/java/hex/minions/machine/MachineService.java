package hex.minions.machine;

import hex.core.api.HexApi;
import hex.collections.api.CollectionProgressContext;
import hex.collections.api.CollectionSource;
import hex.minions.service.MinionService;
import hex.minions.crafting.MachineUpgradeDefinition;
import hex.minions.config.ResourceDefinition;
import hex.minions.energy.CableService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.plugin.Plugin;
import hex.towns.model.ChunkPos;
import hex.towns.api.TownsApi;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runtime maszyn korzysta z istniejącego trwałego magazynu machine_runtimes.
 * Offline catch-up nie tworzy osobnego systemu stanu: dopina do zapisanego runtime brakujący znacznik last_active_at
 * i rozlicza zaległy czas dopiero wtedy, gdy blok maszyny jest znowu w załadowanym chunku albo gracz otworzy GUI.
 */
public final class MachineService {
    private final Plugin plugin;
    private final HexApi hex;
    private final MinionService minions;
    private final TownsApi towns;
    private final MachineRegistry registry;
    private final MachineRuntimeRepository repository;
    private final NamespacedKey machineMenuGuideKey;
    private final NamespacedKey recipeFuelUsesKey;
    private final NamespacedKey portableEnergyKey;
    private final NamespacedKey machineVisualKey;
    private CableService cableService;
    private final Map<String, MachineRuntime> runtimes = new LinkedHashMap<>();
    private final Map<String, Set<String>> runtimeKeysByChunk = new LinkedHashMap<>();
    private final Set<String> dirtyRuntimeKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> deletedRuntimeKeys = ConcurrentHashMap.newKeySet();
    private final Deque<OfflineCatchupJob> offlineCatchupQueue = new ArrayDeque<>();
    private final Set<String> queuedOfflineCatchupKeys = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> pendingEnergyByTown = new LinkedHashMap<>();
    private final Map<UUID, Location> pendingEnergyLocationByTown = new LinkedHashMap<>();
    private final Map<String, Long> externalEnergyReceivedAt = new ConcurrentHashMap<>();
    private final int saveIntervalTicks;
    private final int saveMaxRows;
    private int tickCursor;
    private int saveCountdown;
    private int collectionFlushCountdownTicks;
    private int taskId = -1;
    private volatile boolean dbReady;
    private volatile boolean flushInFlight;
    private volatile java.util.concurrent.CompletableFuture<Void> currentFlush = java.util.concurrent.CompletableFuture.completedFuture(null);
    /** Tombstone set prevents queued/runtime writes after a town enters destroy. */
    private final Set<UUID> purgingTowns = ConcurrentHashMap.newKeySet();
    /** Exact world/chunk tombstones protect legacy machine rows that predate town_uuid ownership. */
    private final Set<String> purgingMachineChunks = ConcurrentHashMap.newKeySet();
    private final boolean offlineEnabled;
    private final int offlineMaxSecondsPerMachine;
    private final int offlineMaxMachinesPerChunk;
    private final int offlineMaxOperationsPerMachine;
    private final int offlineMaxMachinesPerTick;
    private final int collectionProgressFlushTicks;
    private final int maxGeneratorsPerTown;
    private final int maxEnergyMachinesPerTown;
    private final int maxEnergyDevicesPerTown;
    private final Map<String, Integer> recipeFuelUsesBySpecialItemId;

    public MachineService(Plugin plugin, HexApi hex, MinionService minions, TownsApi towns) {
        this.plugin = plugin;
        this.hex = hex;
        this.minions = minions;
        this.towns = towns;
        this.registry = MachineRegistry.load(plugin);
        this.repository = new MachineRuntimeRepository(hex.db().db());
        this.machineMenuGuideKey = new NamespacedKey(plugin, "machine_menu_guide");
        this.recipeFuelUsesKey = new NamespacedKey(plugin, "machine_recipe_fuel_uses_remaining");
        this.portableEnergyKey = new NamespacedKey(plugin, "portable_energy_eu");
        this.machineVisualKey = new NamespacedKey(plugin, "machine_visual");
        this.recipeFuelUsesBySpecialItemId = loadRecipeFuelUses(plugin);
        org.bukkit.configuration.file.FileConfiguration pluginConfig = plugin instanceof org.bukkit.plugin.java.JavaPlugin javaPlugin ? javaPlugin.getConfig() : null;
        ConfigurationSection energy = energySection(pluginConfig);
        ConfigurationSection offline = energy == null ? null : energy.getConfigurationSection("offline_catchup");
        this.saveIntervalTicks = Math.max(20, pluginConfig == null ? 200 : pluginConfig.getInt("minions.machines.persistence.flush-interval-ticks", 200));
        this.saveMaxRows = Math.max(25, pluginConfig == null ? 500 : pluginConfig.getInt("minions.machines.persistence.max-rows-per-flush", 500));
        this.offlineEnabled = pluginConfig == null || getBoolean(pluginConfig, offline, true, "enabled", "minions.machines.offline.enabled");
        int defaultOfflineSeconds = pluginConfig == null ? 21600 : Math.max(60, pluginConfig.getInt("minions.engine.offline.max-hours", 6) * 3600);
        this.offlineMaxSecondsPerMachine = Math.max(60, getInt(pluginConfig, offline, defaultOfflineSeconds, "max_seconds_per_machine", "max-seconds-per-machine", "minions.machines.offline.max-seconds-per-machine"));
        this.offlineMaxMachinesPerChunk = Math.max(1, pluginConfig == null ? 512 : pluginConfig.getInt("minions.machines.offline.max-machines-per-chunk", 512));
        this.offlineMaxOperationsPerMachine = Math.max(1, getInt(pluginConfig, offline, 256, "max_operations_per_machine", "max-operations-per-machine", "minions.machines.offline.max-operations-per-machine"));
        this.offlineMaxMachinesPerTick = Math.max(1, getInt(pluginConfig, offline, 16, "max_machines_per_tick", "max-machines-per-tick", "minions.machines.offline.max-machines-per-tick", "energy.max_offline_catchup_machines_per_tick"));
        this.collectionProgressFlushTicks = Math.max(20, 20 * getInt(pluginConfig, energy, 5, "collection_progress_flush_seconds", "collection-progress-flush-seconds"));
        this.collectionFlushCountdownTicks = this.collectionProgressFlushTicks;
        this.maxGeneratorsPerTown = Math.max(0, getInt(pluginConfig, energy, 16, "max_generators_per_town", "max-generators-per-town"));
        this.maxEnergyMachinesPerTown = Math.max(0, getInt(pluginConfig, energy, 32, "max_energy_machines_per_town", "max-energy-machines-per-town"));
        this.maxEnergyDevicesPerTown = Math.min(32, Math.max(0, getInt(pluginConfig, energy, 32, "max_energy_devices_per_town", "max-energy-devices-per-town")));
        loadAsync();
    }

    public MachineRegistry registry() { return registry; }
    public MinionService minionsService() { return minions; }
    public Collection<MachineRuntime> runtimes() { return List.copyOf(runtimes.values()); }
    public boolean dbReady() { return dbReady; }
    public void attachCableService(CableService cableService) { this.cableService = cableService; }

    public void refreshCableVisuals() {
        if (cableService != null) cableService.refreshVisuals();
    }

    public void refreshCableVisualsNear(Block block) {
        if (cableService != null && block != null) cableService.refreshVisualsNear(block.getLocation());
    }

    public void start() {
        if (taskId != -1) return;
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L).getTaskId();
    }

    public void shutdown() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        taskId = -1;
        flushPendingEnergyProgress();
        saveNow();
    }

    public MachineRuntime runtime(String blockKey, String machineId) {
        MachineRuntime existing = runtimes.get(blockKey);
        if (existing != null) {
            indexRuntime(existing);
            if (machineId != null && !machineId.isBlank() && !machineId.equalsIgnoreCase(existing.machineId())) {
                existing.machineId(machineId);
                markDirty(existing);
            }
            return existing;
        }
        MachineRuntime runtime = new MachineRuntime(blockKey, machineId);
        runtime.townUuid(resolveTownForBlockKey(blockKey));
        runtime.touchActiveNow();
        // A DESTROYING town is intentionally no longer discoverable through normal townAt() lookups.
        // For legacy/null-owner runtimes the exact purge chunk snapshot is therefore the write fence.
        if (isRuntimeFenced(runtime)) return runtime;
        runtimes.put(blockKey, runtime);
        indexRuntime(runtime);
        markDirty(runtime);
        return runtime;
    }

    public Optional<MachineRuntime> runtime(String blockKey) { return Optional.ofNullable(runtimes.get(blockKey)); }

    public void remove(String blockKey, Location dropLocation) {
        MachineRuntime removed = runtimes.remove(blockKey);
        if (removed != null && dropLocation != null) {
            // Legacy machine upgrades may predate town-bound PDC. Before returning runtime
            // contents to the world, adopt every progression upgrade to the machine's town.
            if (removed.townUuid() != null) {
                for (int i = 0; i < 3; i++) {
                    ItemStack upgrade = removed.upgrade(i);
                    if (upgrade != null && !upgrade.getType().isAir()) {
                        removed.upgrade(i, minions.bindProgressionItem(upgrade, removed.townUuid(), "machine_storage"));
                    }
                }
            }
            removed.drop(dropLocation);
        }
        if (removed != null) {
            deindexRuntime(removed);
            dirtyRuntimeKeys.remove(blockKey);
            deletedRuntimeKeys.add(blockKey);
        }
        saveSoon();
    }

    public void observeChunk(Chunk chunk) {
        if (chunk == null || !dbReady) return;
        // Najpierw uzgadniamy oznaczone wizualizacje z realnymi blokami. Dzięki temu stary
        // BlockDisplay nie przeżywa usunięcia maszyny, a istniejąca maszyna może odtworzyć
        // brakujący runtime po awarii/starym zapisie.
        cleanupOrphanMachineVisuals(chunk);

        int handled = 0;
        List<MachineRuntime> inChunk = new ArrayList<>();
        Set<String> keys = runtimeKeysByChunk.get(chunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()));
        if (keys == null || keys.isEmpty()) return;
        for (String key : new ArrayList<>(keys)) {
            MachineRuntime runtime = runtimes.get(key);
            if (runtime != null) inChunk.add(runtime);
        }
        // Generatory i akumulatory najpierw uzupełniają bufory/sieć, a dopiero potem maszyny konsumujące rozliczają procesy.
        inChunk.sort(java.util.Comparator.comparingInt(this::offlineOrder));
        for (MachineRuntime runtime : inChunk) {
            if (++handled > offlineMaxMachinesPerChunk) break;
            Block block = blockFromKeyIfLoaded(runtime.blockKey());
            if (block == null) continue;
            MachineDefinition machine = reconcileLoadedRuntime(runtime, block);
            if (machine == null) continue;
            enqueueOfflineCatchup(block, machine, runtime);
            ensureActiveStamp(runtime, false);
        }
        if (handled > 0) saveSoon();
    }

    public void observeChunkUnload(Chunk chunk) {
        if (chunk == null || !dbReady) return;
        Set<String> keys = runtimeKeysByChunk.get(chunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()));
        if (keys == null || keys.isEmpty()) return;
        for (String key : new ArrayList<>(keys)) {
            MachineRuntime runtime = runtimes.get(key);
            if (runtime == null) continue;
            ensureActiveStamp(runtime, true);
        }
        if (!dirtyRuntimeKeys.isEmpty()) flushDirtyAsync();
    }

    public void forgetMachinesInChunks(String worldName, List<ChunkPos> chunks) {
        forgetMachinesInChunks(worldName, chunks, null);
    }

    /** Targeted cleanup: explicit town ownership wins; legacy fallback is exact runtime location inside the destroy snapshot. */
    public MachinePurgeResult forgetMachinesInChunks(String worldName, List<ChunkPos> chunks, UUID townUuid) {
        if (worldName == null || chunks == null || chunks.isEmpty()) return new MachinePurgeResult(0, 0);
        java.util.Set<String> chunkKeys = new java.util.HashSet<>();
        for (ChunkPos chunk : chunks) chunkKeys.add(worldName + ":" + chunk.x() + ":" + chunk.z());
        int removedCount = 0;
        int orphanCount = 0;
        for (MachineRuntime runtime : new ArrayList<>(runtimes.values())) {
            if (!matchesPurgeTarget(runtime, worldName, chunkKeys, townUuid)) continue;
            boolean legacy = runtime.townUuid() == null;
            processMachinePurgeTarget(runtime, townUuid, legacy);
            removedCount++;
            if (legacy) orphanCount++;
        }
        if (!deletedRuntimeKeys.isEmpty()) saveSoon();
        return new MachinePurgeResult(removedCount, orphanCount);
    }

    public java.util.concurrent.CompletableFuture<Void> purgeTownAsync(UUID townUuid, String worldName, java.util.Set<ChunkPos> chunks) {
        if (townUuid == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        purgingTowns.add(townUuid);
        List<ChunkPos> snapshot = chunks == null ? List.of() : List.copyOf(chunks);
        java.util.Set<String> chunkKeys = new java.util.HashSet<>();
        if (worldName != null) for (ChunkPos chunk : snapshot) chunkKeys.add(worldName + ":" + chunk.x() + ":" + chunk.z());
        purgingMachineChunks.addAll(chunkKeys);
        return purgeMachineTargetsBatched(runtime -> matchesPurgeTarget(runtime, worldName, chunkKeys, townUuid), townUuid)
                // A legacy NULL-owner runtime may already be in an async save snapshot. Drain that
                // snapshot before the final DELETE so it cannot reappear afterwards.
                .thenCompose(ignored -> awaitCurrentFlush())
                .thenCompose(ignored -> hex.db().asyncRun(() -> {
                    repository.deleteByTown(townUuid);
                    repository.deleteLegacyInChunks(worldName, chunkKeys);
                    repository.verifyTownPurged(townUuid, worldName, chunkKeys);
                }));
    }

    public java.util.concurrent.CompletableFuture<Void> purgeTownByOwnerAsync(UUID townUuid) {
        if (townUuid == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        purgingTowns.add(townUuid);
        return purgeMachineTargetsBatched(runtime -> townUuid.equals(runtime.townUuid()), townUuid)
                .thenCompose(ignored -> awaitCurrentFlush())
                .thenCompose(ignored -> hex.db().asyncRun(() -> {
                    repository.deleteByTown(townUuid);
                    repository.verifyTownPurged(townUuid, null, Set.of());
                }));
    }

    private boolean matchesPurgeTarget(MachineRuntime runtime, String worldName, Set<String> chunkKeys, UUID townUuid) {
        if (runtime == null) return false;
        // Explicit persisted ownership is authoritative even if a corrupted/legacy runtime somehow
        // ended up outside the current town world. World/chunk matching is only the fallback for
        // old NULL-owner rows.
        if (townUuid != null && townUuid.equals(runtime.townUuid())) return true;
        if (runtime.townUuid() != null) return false;
        LocationParts parts = LocationParts.parse(runtime.blockKey());
        if (parts == null || worldName == null || !parts.world().equals(worldName)) return false;
        String chunkKey = parts.world() + ":" + Math.floorDiv(parts.x(), 16) + ":" + Math.floorDiv(parts.z(), 16);
        return chunkKeys.contains(chunkKey);
    }

    /**
     * Executes at most one machine location per tick. This bounds synchronous chunk loads and
     * Bukkit world mutations while preserving exact runtime ownership; there is no chunk/world scan.
     */
    private java.util.concurrent.CompletableFuture<Void> purgeMachineTargetsBatched(java.util.function.Predicate<MachineRuntime> selector, UUID townUuid) {
        java.util.concurrent.CompletableFuture<Void> result = new java.util.concurrent.CompletableFuture<>();
        Runnable initialize = () -> {
            try {
                List<MachineRuntime> targets = new ArrayList<>();
                for (MachineRuntime runtime : runtimes.values()) if (selector.test(runtime)) targets.add(runtime);
                targets.sort(java.util.Comparator.comparing(MachineRuntime::blockKey));
                if (targets.isEmpty()) {
                    result.complete(null);
                    return;
                }
                class Step implements Runnable {
                    int cursor;
                    @Override public void run() {
                        try {
                            if (cursor >= targets.size()) {
                                if (!deletedRuntimeKeys.isEmpty()) saveSoon();
                                result.complete(null);
                                return;
                            }
                            MachineRuntime runtime = targets.get(cursor++);
                            processMachinePurgeTarget(runtime, townUuid, runtime.townUuid() == null);
                            if (cursor >= targets.size()) {
                                if (!deletedRuntimeKeys.isEmpty()) saveSoon();
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

    private void processMachinePurgeTarget(MachineRuntime runtime, UUID expectedTownUuid, boolean legacyFallback) {
        if (runtime == null) return;
        LocationParts parts = LocationParts.parse(runtime.blockKey());
        if (parts == null) return;
        World world = Bukkit.getWorld(parts.world());
        if (world != null) {
            Chunk chunk = world.getChunkAt(Math.floorDiv(parts.x(), 16), Math.floorDiv(parts.z(), 16));
            if (!chunk.isLoaded()) chunk.load(true);
            Block block = world.getBlockAt(parts.x(), parts.y(), parts.z());
            MachineDefinition actual = machineAt(block);
            // Strong ownership: runtime town UUID. Legacy fallback is accepted only when this exact
            // runtime location still contains a Hex machine marker.
            boolean ownerMatches = expectedTownUuid != null && expectedTownUuid.equals(runtime.townUuid());
            if ((ownerMatches || legacyFallback) && actual != null) {
                cleanupMachineVisuals(block.getLocation(), true);
                removeOwnedMachineStorage(block, actual, runtime);
                minions.specialItems().unmarkSpecialBlock(block);
                block.setType(Material.AIR, false);
            } else if (ownerMatches) {
                // Carrier already gone: tagged visuals at the exact runtime anchor may still remain.
                cleanupMachineVisuals(block.getLocation(), true);
            }
        }
        MachineRuntime removed = runtimes.remove(runtime.blockKey());
        if (removed != null) deindexRuntime(removed);
        dirtyRuntimeKeys.remove(runtime.blockKey());
        deletedRuntimeKeys.add(runtime.blockKey());
    }

    /** Removes only storage whose ownership can be derived from the concrete machine runtime. */
    private void removeOwnedMachineStorage(Block block, MachineDefinition machine, MachineRuntime runtime) {
        if (block == null || machine == null || runtime == null) return;
        if ("COAL_GENERATOR".equalsIgnoreCase(machine.type()) && generatorStorageSlots(runtime) > 0) {
            Block top = block.getRelative(BlockFace.UP);
            if (top.getState() instanceof Chest chest) {
                chest.getBlockInventory().clear();
                top.setType(Material.AIR, false);
            }
        }
        if (hasExternalStorage(machine)) {
            for (BlockFace face : List.of(BlockFace.UP, BlockFace.DOWN)) {
                Block storage = block.getRelative(face);
                if (storage.getState() instanceof Chest chest && isManagedExternalStorageChest(chest)) {
                    chest.getBlockInventory().clear();
                    storage.setType(Material.AIR, false);
                }
            }
        }
    }

    public record MachinePurgeResult(int removed, int legacyOrphans) {}

    public MachineDefinition machineAt(Block block) {
        if (block == null) return null;
        return minions.specialItems().readSpecialBlockId(block).flatMap(registry::byStation).orElse(null);
    }

    public void syncFromInventory(MachineDefinition machine, MachineRuntime runtime, Inventory inv) {
        if (machine == null || runtime == null || inv == null) return;
        syncInputsFromInventory(machine, runtime, inv);
        runtime.secondary(machine.hasSecondarySlot() ? inv.getItem(machine.secondarySlot()) : null);
        if (usesFuelSlot(machine)) runtime.fuel(inv.getItem(machine.fuelSlot()));
        syncOutputsFromInventory(machine, runtime, inv);
        List<Integer> accessories = accessorySlots(machine);
        for (int i = 0; i < Math.min(3, accessories.size()); i++) runtime.upgrade(i, inv.getItem(accessories.get(i)));
        if (machine.energy().enabled()) runtime.energy(Math.min(runtime.energy(), capacity(machine, runtime)));
        markDirty(runtime);
        saveSoon();
    }

    public void syncToInventory(MachineDefinition machine, MachineRuntime runtime, Inventory inv) {
        if (machine == null || runtime == null || inv == null) return;
        syncInputsToInventory(machine, runtime, inv);
        if (machine.hasSecondarySlot()) inv.setItem(machine.secondarySlot(), runtime.secondary());
        if (usesFuelSlot(machine)) inv.setItem(machine.fuelSlot(), runtime.fuel());
        syncOutputsToInventory(machine, runtime, inv);
        List<Integer> accessories = accessorySlots(machine);
        for (int i = 0; i < Math.min(3, accessories.size()); i++) inv.setItem(accessories.get(i), runtime.upgrade(i));
    }

    private void syncInputsFromInventory(MachineDefinition machine, MachineRuntime runtime, Inventory inv) {
        if (machine == null || runtime == null || inv == null || (!usesProcessingInputs(machine))) {
            if (runtime != null) {
                runtime.input(null);
                runtime.extraInput(0, null);
                runtime.extraInput(1, null);
            }
            return;
        }
        List<Integer> slots = machine.inputSlots();
        runtime.input(slots.size() > 0 ? inv.getItem(slots.get(0)) : null);
        runtime.extraInput(0, slots.size() > 1 ? inv.getItem(slots.get(1)) : null);
        runtime.extraInput(1, slots.size() > 2 ? inv.getItem(slots.get(2)) : null);
    }

    private void syncInputsToInventory(MachineDefinition machine, MachineRuntime runtime, Inventory inv) {
        if (machine == null || runtime == null || inv == null || !usesProcessingInputs(machine)) return;
        List<Integer> slots = machine.inputSlots();
        if (slots.size() > 0) inv.setItem(slots.get(0), runtime.input());
        if (slots.size() > 1) inv.setItem(slots.get(1), runtime.extraInput(0));
        if (slots.size() > 2) inv.setItem(slots.get(2), runtime.extraInput(1));
    }

    private void syncOutputsFromInventory(MachineDefinition machine, MachineRuntime runtime, Inventory inv) {
        if (machine == null || runtime == null || inv == null) return;
        if (!usesProcessingInputs(machine)) return;
        List<Integer> slots = machine.outputSlots();
        runtime.output(slots.size() > 0 ? inv.getItem(slots.get(0)) : null);
        runtime.output2(slots.size() > 1 ? inv.getItem(slots.get(1)) : null);
    }

    private void syncOutputsToInventory(MachineDefinition machine, MachineRuntime runtime, Inventory inv) {
        if (machine == null || runtime == null || inv == null || !usesProcessingInputs(machine)) return;
        List<Integer> slots = machine.outputSlots();
        if (slots.size() > 0) inv.setItem(slots.get(0), runtime.output());
        if (slots.size() > 1) inv.setItem(slots.get(1), runtime.outputAt(1));
    }

    public boolean usesProcessingInputs(MachineDefinition machine) {
        return machine != null && (!machine.recipes().isEmpty() || isElectricFurnace(machine));
    }

    public boolean usesFuelSlot(MachineDefinition machine) {
        if (machine == null) return false;
        if (machine.hasRecipeFuelSlot()) return true;
        if (!machine.energy().enabled()) return false;
        if (machine.energy().generator()) {
            return !machine.energy().fuelEu().isEmpty() || !machine.energy().fuelBurnSeconds().isEmpty();
        }
        return !machine.energy().fallbackFuelEu().isEmpty() || !machine.energy().fallbackFuelBurnSeconds().isEmpty();
    }

    public List<Integer> upgradeSlots(MachineDefinition machine) {
        if (machine == null || machine.upgradeSlots().isEmpty()) return List.of();
        return machine.upgradeSlots();
    }

    public List<Integer> accessorySlots(MachineDefinition machine) {
        if (machine == null) return List.of();
        java.util.LinkedHashSet<Integer> result = new java.util.LinkedHashSet<>();
        if (machine.inputStorageExtensionSlot() >= 0) result.add(machine.inputStorageExtensionSlot());
        if (machine.outputStorageExtensionSlot() >= 0) result.add(machine.outputStorageExtensionSlot());
        if (machine.fuelStorageExtensionSlot() >= 0) result.add(machine.fuelStorageExtensionSlot());
        result.addAll(machine.upgradeSlots());
        if (machine.energy().enabled() && (machine.energy().generator() || isAccumulator(machine)) && machine.energy().batterySlot() >= 0) {
            result.add(machine.energy().batterySlot());
        }
        return result.stream().limit(3).toList();
    }

    public Optional<MachineUpgradeDefinition> activeMachineUpgrade(MachineDefinition machine, MachineRuntime runtime) {
        if (machine == null || runtime == null || !machine.energy().enabled()) return Optional.empty();
        for (int i = 0; i < 3; i++) {
            Optional<MachineUpgradeDefinition> upgrade = minions.specialItems().machineUpgradeByItem(runtime.upgrade(i));
            if (upgrade.isPresent() && upgrade.get().supportsMachineType(machine.type())) return upgrade;
        }
        return Optional.empty();
    }

    public String machineUpgradeDisplayName(MachineUpgradeDefinition upgrade) {
        if (upgrade == null) return "brak";
        return minions.specialItems().item(upgrade.specialItemId())
                .map(item -> stripMiniTags(item.displayName()))
                .filter(name -> !name.isBlank())
                .orElseGet(() -> prettyId(upgrade.id()));
    }

    public boolean isValidMachineUpgradeItem(MachineDefinition machine, ItemStack item) {
        if (item == null || item.getType().isAir()) return true;
        return minions.specialItems().machineUpgradeByItem(item)
                .filter(upgrade -> upgrade.supportsMachineType(machine == null ? "" : machine.type()))
                .isPresent();
    }

    public boolean isGeneratorStorageUpgradeSlot(MachineDefinition machine, int slot) {
        return machine != null && "COAL_GENERATOR".equalsIgnoreCase(machine.type())
                && machine.fuelStorageExtensionSlot() >= 0 && slot == machine.fuelStorageExtensionSlot();
    }

    public boolean canInstallGeneratorStorageAt(Block machineBlock, MachineDefinition machine, ItemStack item) {
        if (machineBlock == null || machine == null || !"COAL_GENERATOR".equalsIgnoreCase(machine.type())) return false;
        if (item == null || item.getType().isAir()) return true;
        if (!isValidExternalStorageItem(item)) return false;
        Block storageBlock = machineBlock.getRelative(BlockFace.UP);
        return storageBlock.getType().isAir() || storageBlock.getState() instanceof Chest;
    }

    public int capacity(MachineDefinition machine, MachineRuntime runtime) {
        if (machine == null || runtime == null || !machine.energy().enabled()) return 0;
        int base = runtime.capacity(machine);
        Optional<MachineUpgradeDefinition> upgrade = activeMachineUpgrade(machine, runtime);
        int extra = upgrade.map(MachineUpgradeDefinition::extraBufferCapacity).orElse(0);
        double multiplier = upgrade.map(MachineUpgradeDefinition::bufferCapacityMultiplier).orElse(1.0D);
        return Math.max(0, (int) Math.round((base + extra) * multiplier));
    }

    public int effectiveEnergyPerSecond(MachineDefinition machine, MachineRuntime runtime) {
        if (machine == null || !machine.energy().enabled()) return 0;
        int base = Math.max(0, machine.energy().euPerSecond());
        if (base <= 0 || machine.energy().generator()) return base;
        double multiplier = activeMachineUpgrade(machine, runtime)
                .map(MachineUpgradeDefinition::energyConsumptionMultiplier)
                .orElse(1.0D);
        return Math.max(1, (int) Math.ceil(base * multiplier));
    }

    public int effectiveGenerationPerSecond(MachineDefinition machine, MachineRuntime runtime) {
        if (machine == null || !machine.energy().enabled() || !machine.energy().generator()) return 0;
        int base = Math.max(0, machine.energy().euPerSecond());
        double multiplier = activeMachineUpgrade(machine, runtime)
                .map(MachineUpgradeDefinition::energyGenerationMultiplier)
                .orElse(1.0D);
        return Math.max(0, (int) Math.round(base * multiplier));
    }

    public int effectiveTransferPerSecond(MachineDefinition machine, MachineRuntime runtime) {
        if (machine == null || !machine.energy().enabled()) return 0;
        int base = Math.max(0, machine.energy().transferPerSecond());
        double multiplier = activeMachineUpgrade(machine, runtime)
                .map(MachineUpgradeDefinition::energyTransferMultiplier)
                .orElse(1.0D);
        return Math.max(0, (int) Math.round(base * multiplier));
    }

    public boolean isEditableSlot(MachineDefinition machine, int slot) {
        if (machine == null) return false;
        if (machine.outputSlots().contains(slot) || slot == machine.arrowSlot()) return false;
        if (usesProcessingInputs(machine) && machine.inputSlots().contains(slot)) return true;
        if (slot == machine.secondarySlot() && machine.hasSecondarySlot()) return true;
        if (slot == machine.fuelSlot() && usesFuelSlot(machine)) return true;
        if ((machine.energy().generator() || isAccumulator(machine)) && slot == machine.energy().batterySlot()) return true;
        return accessorySlots(machine).contains(slot);
    }

    public MachineRecipe match(MachineDefinition machine, MachineRuntime runtime) {
        Match match = findMatch(machine, runtime);
        return match == null ? null : match.recipe();
    }

    private Match findMatch(MachineDefinition machine, MachineRuntime runtime) {
        if (machine == null || runtime == null) return null;
        int inputCount = Math.max(1, machine.inputSlots().size());
        for (int inputIndex = 0; inputIndex < inputCount; inputIndex++) {
            ItemStack candidateInput = runtime.inputAt(inputIndex);
            for (MachineRecipe recipe : machine.recipes()) {
                if (!recipe.matchesInput(candidateInput, minions.specialItems())) continue;
                if (!recipe.matchesSecondary(runtime.secondary(), minions.specialItems())) continue;
                if (!recipe.matchesFuel(runtime.fuel(), minions.specialItems())) continue;
                ItemStack output = output(recipe);
                ItemStack current = runtime.output();
                ItemStack normalizedCurrent = normalizeLegacyVanillaMachineOutput(current);
                if (normalizedCurrent != current) {
                    runtime.output(normalizedCurrent);
                    current = normalizedCurrent;
                    markDirty(runtime);
                }
                if (current != null && !current.getType().isAir() && !current.isSimilar(output)) continue;
                int currentAmount = current == null ? 0 : current.getAmount();
                if (currentAmount + output.getAmount() > Math.min(output.getMaxStackSize(), machine.defaultOutputStackSize())) continue;
                return new Match(recipe, inputIndex);
            }
        }
        return null;
    }

    public ItemStack output(MachineRecipe recipe) {
        if (recipe.outputSpecialItem() != null && !recipe.outputSpecialItem().isBlank()) {
            return minions.specialItems().createItem(recipe.outputSpecialItem(), recipe.outputAmount());
        }
        ItemStack item = new ItemStack(recipe.outputMaterial() == Material.AIR ? Material.PAPER : recipe.outputMaterial(), recipe.outputAmount());
        // Material-only machine outputs must stay truly vanilla. Giving IRON_INGOT/GOLD_INGOT/etc.
        // a custom display name makes them a different stack from ordinary smelted resources.
        if (recipe.outputCustomModelData() <= 0) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setCustomModelData(recipe.outputCustomModelData());
            ResourceDefinition resource = resourceByMaterial(recipe.outputMaterial(), recipe.outputCustomModelData());
            if (resource != null) {
                meta.displayName(MiniMessage.miniMessage().deserialize(resource.displayName()));
                if ("spruce_resin".equalsIgnoreCase(resource.id())) meta.setEnchantmentGlintOverride(true);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ResourceDefinition resourceByMaterial(Material material, int customModelData) {
        if (material == null || material == Material.AIR) return null;
        for (ResourceDefinition resource : minions.definitions().resources().values()) {
            if (resource.material() != material) continue;
            if (customModelData > 0 && resource.customModelData() != customModelData) continue;
            if (customModelData == 0 && resource.customModelData() > 0) continue;
            return resource;
        }
        return null;
    }

    private ItemStack normalizeLegacyVanillaMachineOutput(ItemStack item) {
        if (item == null || item.getType().isAir()) return item;
        if (minions.specialItems().readSpecialItemId(item).isPresent()) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.hasCustomModelData() || !meta.hasDisplayName()) return item;

        boolean plainMachineOutput = registry.machines().values().stream()
                .flatMap(machine -> machine.recipes().stream())
                .anyMatch(recipe -> (recipe.outputSpecialItem() == null || recipe.outputSpecialItem().isBlank())
                        && recipe.outputCustomModelData() <= 0
                        && recipe.outputMaterial() == item.getType());
        if (!plainMachineOutput) return item;
        ResourceDefinition legacyResource = resourceByMaterial(item.getType(), 0);
        if (legacyResource == null) return item;
        Component expectedLegacyName = MiniMessage.miniMessage().deserialize(legacyResource.displayName());
        if (!expectedLegacyName.equals(meta.displayName())) return item;

        ItemStack normalized = item.clone();
        ItemMeta clean = normalized.getItemMeta();
        if (clean != null) {
            clean.displayName(null);
            clean.setEnchantmentGlintOverride(null);
            normalized.setItemMeta(clean);
        }
        return normalized;
    }

    /** Removes only the exact legacy display names that older machine builds put on vanilla outputs. */
    public int normalizeLegacyMachineOutputs(org.bukkit.entity.Player player) {
        if (player == null) return 0;
        int changed = normalizeLegacyMachineOutputs(player.getInventory());
        changed += normalizeLegacyMachineOutputs(player.getEnderChest());
        return changed;
    }

    private int normalizeLegacyMachineOutputs(Inventory inventory) {
        if (inventory == null) return 0;
        int changed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack before = inventory.getItem(slot);
            ItemStack after = normalizeLegacyVanillaMachineOutput(before);
            if (after != before) {
                inventory.setItem(slot, after);
                changed++;
            }
        }
        return changed;
    }

    public boolean collectOutput(MachineRuntime runtime, org.bukkit.entity.Player player) {
        return collectOutput(runtime, 0, player);
    }

    public boolean collectOutput(MachineRuntime runtime, int outputIndex, org.bukkit.entity.Player player) {
        ItemStack output = runtime.outputAt(outputIndex);
        if (output == null || output.getType().isAir()) return false;
        output = normalizeLegacyVanillaMachineOutput(output);
        runtime.outputAt(outputIndex, null);
        player.getInventory().addItem(output).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        markDirty(runtime);
        saveSoon();
        return true;
    }

    public void tick() {
        int slot = Math.floorMod(tickCursor++, 20);
        if (!dbReady) return;
        if (slot < 10) tickGenerators(slot);
        else tickConsumers(slot - 10);
        drainOfflineCatchupQueue();
        refreshOpenMenus();
        if (--collectionFlushCountdownTicks <= 0) flushPendingEnergyProgress();
        if ((!dirtyRuntimeKeys.isEmpty() || !deletedRuntimeKeys.isEmpty()) && ++saveCountdown >= saveIntervalTicks) flushDirtyAsync();
    }

    private void tickGenerators(int secondSlot) {
        for (String key : loadedRuntimeKeysForBucket(secondSlot)) {
            MachineRuntime runtime = runtimes.get(key);
            if (runtime == null) continue;
            Block block = blockFromKeyIfLoaded(runtime.blockKey());
            if (block == null) continue;
            MachineDefinition machine = reconcileLoadedRuntime(runtime, block);
            if (machine == null || !machine.energy().enabled() || !machine.energy().generator()) continue;
            tickGeneratorEnergy(block, machine, runtime);
            int delivered = distributeEnergy(block, machine, runtime);
            int chargedPortable = chargePortableEnergyItem(machine, runtime);
            if (delivered + chargedPortable > 0) recordEnergyGenerated(block, (long) delivered + chargedPortable);
            ensureActiveStamp(runtime, false);
        }
    }

    private void tickConsumers(int secondSlot) {
        for (String key : loadedRuntimeKeysForBucket(secondSlot)) {
            MachineRuntime runtime = runtimes.get(key);
            if (runtime == null) continue;
            Block block = blockFromKeyIfLoaded(runtime.blockKey());
            if (block == null) continue;
            MachineDefinition machine = reconcileLoadedRuntime(runtime, block);
            if (machine == null || machine.energy().generator()) continue;
            if (isAccumulator(machine)) {
                tickFuel(machine, runtime, false);
                distributeAccumulatorEnergy(block, machine, runtime);
                chargePortableEnergyItem(machine, runtime);
                ensureActiveStamp(runtime, false);
                continue;
            }
            if (machine.energy().enabled()) tickFuel(machine, runtime, false);
            if (isElectricFurnace(machine)) tickElectricFurnace(block, machine, runtime);
            else if (isExternalOutputProcessor(machine)) tickExternalOutputProcessor(block, machine, runtime);
            else tickProcess(machine, runtime);
            ensureActiveStamp(runtime, false);
        }
    }

    public void applyOfflineCatchup(Block block, MachineDefinition machine, MachineRuntime runtime) {
        if (!offlineEnabled || block == null || machine == null || runtime == null) return;
        // Ten mechanizm aktualizuje istniejący runtime maszyny: input/output/fuel/EU/progress były już zapisywane
        // w MachineRuntimeRepository. Dodany last_active_at mówi tylko, ile sekund należy doliczyć po powrocie chunka.
        long now = System.currentTimeMillis();
        long last = runtime.lastActiveAtMillis();
        if (last <= 0L) {
            runtime.lastActiveAtMillis(now);
            markDirty(runtime);
            return;
        }
        int elapsedSeconds = (int) Math.min(Math.max(0L, (now - last) / 1000L), (long) offlineMaxSecondsPerMachine);
        if (elapsedSeconds <= 1) return;
        int beforeEnergy = runtime.energy();
        int beforeProgress = runtime.progressSeconds();
        int beforeProgress1 = runtime.progressSecondsAt(1);
        int beforeBurn = runtime.burnRemainingSeconds();
        ItemStack beforeInput = runtime.input();
        ItemStack beforeExtraInput0 = runtime.extraInput(0);
        ItemStack beforeExtraInput1 = runtime.extraInput(1);
        ItemStack beforeOutput = runtime.output();
        ItemStack beforeOutput1 = runtime.outputAt(1);
        int operations = Math.min(elapsedSeconds, offlineMaxOperationsPerMachine);
        boolean generatorCatchup = machine.energy().enabled() && machine.energy().generator();
        for (int i = 0; i < operations; i++) {
            if (generatorCatchup) {
                tickGeneratorEnergy(block, machine, runtime);
                // Nie skanujemy/transferujemy tras kabli dla każdej sekundy offline.
                // Co 10 uproszczonych kroków robimy jeden transfer, a finalny transfer po pętli.
                if (i % 10 == 9) {
                    int delivered = distributeEnergy(block, machine, runtime);
                    if (delivered > 0) recordEnergyGenerated(block, delivered);
                }
            } else if (isAccumulator(machine)) {
                tickFuel(machine, runtime, false);
                distributeAccumulatorEnergy(block, machine, runtime);
                chargePortableEnergyItem(machine, runtime);
            } else {
                if (machine.energy().enabled()) tickFuel(machine, runtime, false);
                if (isElectricFurnace(machine)) tickElectricFurnace(block, machine, runtime);
                else if (isExternalOutputProcessor(machine)) tickExternalOutputProcessor(block, machine, runtime);
                else tickProcess(machine, runtime);
            }
        }
        if (generatorCatchup) {
            int delivered = distributeEnergy(block, machine, runtime);
            int chargedPortable = chargePortableEnergyItem(machine, runtime);
            if (delivered + chargedPortable > 0) recordEnergyGenerated(block, (long) delivered + chargedPortable);
        }
        runtime.lastActiveAtMillis(now);
        if (beforeEnergy != runtime.energy() || beforeProgress != runtime.progressSeconds() || beforeProgress1 != runtime.progressSecondsAt(1) || beforeBurn != runtime.burnRemainingSeconds()
                || !similarItem(beforeInput, runtime.input()) || !similarItem(beforeExtraInput0, runtime.extraInput(0)) || !similarItem(beforeExtraInput1, runtime.extraInput(1)) || !similarItem(beforeOutput, runtime.output()) || !similarItem(beforeOutput1, runtime.outputAt(1))) {
            markDirty(runtime);
        } else {
            // Sam znacznik czasu też jest ważny: bez niego maszyna po kolejnym loadzie liczyłaby ten sam czas drugi raz.
            markDirty(runtime);
        }
    }

    private int tickGeneratorEnergy(Block block, MachineDefinition machine, MachineRuntime runtime) {
        if (isSolarGenerator(machine)) {
            int before = runtime.energy();

            // Panel słoneczny po zmianie balansu nie korzysta z żadnego paliwa.
            // Stare runtime'y mogą jeszcze zawierać redstone lub aktywne spalanie z poprzedniej wersji:
            // oddajemy ukryty item do świata i zerujemy stan spalania zamiast pozwalać mu dalej zasilać panel.
            ItemStack legacyFuel = runtime.fuel();
            if (legacyFuel != null && !legacyFuel.getType().isAir()) {
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 1.0, 0.5), legacyFuel);
                runtime.fuel(null);
                markDirty(runtime);
            }
            if (runtime.burnRemainingSeconds() > 0 || runtime.burnEuRemaining() > 0) {
                runtime.stopBurn();
                markDirty(runtime);
            }

            if (hasFullSunlight(block)) {
                int capacity = capacity(machine, runtime);
                int amount = effectiveGenerationPerSecond(machine, runtime);
                if (capacity > 0 && amount > 0 && runtime.energy() < capacity) runtime.addEnergy(amount, capacity);
            }
            int generated = Math.max(0, runtime.energy() - before);
            if (generated > 0) markDirty(runtime);
            return generated;
        }
        if ("COAL_GENERATOR".equalsIgnoreCase(machine.type())) pullFuelFromTopStorage(block, machine, runtime);
        int before = runtime.energy();
        tickFuel(machine, runtime, true);
        return Math.max(0, runtime.energy() - before);
    }

    private boolean isSolarGenerator(MachineDefinition machine) {
        return machine != null && ("SOLAR_PANEL_GENERATOR".equalsIgnoreCase(machine.type()) || "SOLAR_GENERATOR".equalsIgnoreCase(machine.type()));
    }

    private boolean isAccumulator(MachineDefinition machine) {
        return machine != null && "ACCUMULATOR".equalsIgnoreCase(machine.type());
    }

    private boolean canReceiveFromAccumulator(MachineDefinition machine) {
        return machine != null && machine.energy().enabled() && !machine.energy().generator() && !isAccumulator(machine);
    }

    /**
     * Wspólna walidacja strony wejścia EU dla bezpośredniego transferu i sieci kablowej.
     * incomingFace oznacza stronę odbiornika, z której fizycznie dociera energia.
     */
    public boolean canReceiveEnergyFrom(Block consumerBlock, MachineDefinition consumer, BlockFace incomingFace) {
        if (consumerBlock == null || consumer == null || incomingFace == null) return false;
        if (!consumer.energy().enabled() || consumer.energy().generator()) return false;
        if (isAccumulator(consumer)) return incomingFace == accumulatorInputFace(consumerBlock);
        if ("URANIUM_ENRICHER".equalsIgnoreCase(consumer.type())) {
            return incomingFace == leftOf(consumerBlock) || incomingFace == rightOf(consumerBlock);
        }
        if ("MACERATOR".equalsIgnoreCase(consumer.type())) {
            return incomingFace == BlockFace.NORTH
                    || incomingFace == BlockFace.EAST
                    || incomingFace == BlockFace.SOUTH
                    || incomingFace == BlockFace.WEST
                    || incomingFace == BlockFace.DOWN;
        }
        return incomingFace == facing(consumerBlock).getOppositeFace()
                || incomingFace == leftOf(consumerBlock)
                || incomingFace == rightOf(consumerBlock)
                || incomingFace == BlockFace.DOWN;
    }

    private boolean hasFullSunlight(Block block) {
        if (block == null || block.getWorld() == null) return false;
        Block above = block.getRelative(BlockFace.UP);
        return above.getLightLevel() >= 15;
    }

    public boolean isEnergyGenerator(MachineDefinition machine) {
        return machine != null && machine.energy().enabled() && machine.energy().generator();
    }

    public void recordEnergyGeneratorPlaced(Block block, MachineDefinition machine) {
        // Kolekcja energii ma reprezentowac realnie wygenerowane i przyjete EU,
        // a nie samo postawienie generatora/panelu.
    }

    private void recordEnergyGenerated(Block block, long amount) {
        if (block == null || amount <= 0) return;
        try {
            minions.towns().townAt(block.getLocation()).ifPresent(town -> {
                pendingEnergyByTown.merge(town.id(), amount, Long::sum);
                pendingEnergyLocationByTown.put(town.id(), block.getLocation());
            });
        } catch (Throwable ignored) {
        }
    }

    private void flushPendingEnergyProgress() {
        collectionFlushCountdownTicks = collectionProgressFlushTicks;
        if (pendingEnergyByTown.isEmpty()) return;
        Map<UUID, Long> snapshot = new LinkedHashMap<>(pendingEnergyByTown);
        Map<UUID, Location> locations = new LinkedHashMap<>(pendingEnergyLocationByTown);
        pendingEnergyByTown.clear();
        pendingEnergyLocationByTown.clear();
        for (Map.Entry<UUID, Long> entry : snapshot.entrySet()) {
            long amount = entry.getValue() == null ? 0L : entry.getValue();
            if (amount <= 0) continue;
            try {
                minions.collections().addProgress(new CollectionProgressContext()
                        .townId(entry.getKey())
                        .collectionId("industrial.energy")
                        .amount(amount)
                        .source(CollectionSource.CUSTOM_PLUGIN_GRANTED)
                        .location(locations.get(entry.getKey()))
                        .reason("machine.energy.generated.flush"));
            } catch (Throwable ignored) {
            }
        }
    }

    private void pullFuelFromTopStorage(Block block, MachineDefinition machine, MachineRuntime runtime) {
        if (block == null || machine == null || runtime == null || !"COAL_GENERATOR".equalsIgnoreCase(machine.type())) return;
        int slots = generatorStorageSlots(runtime);
        if (slots <= 0) return;
        Block top = block.getRelative(BlockFace.UP);
        if (top.getType() == Material.AIR) top.setType(Material.CHEST, false);
        if (!(top.getState() instanceof Chest chest)) return;
        ItemStack current = runtime.fuel();
        if (current != null && !current.getType().isAir() && current.getAmount() >= current.getMaxStackSize()) return;
        Inventory inventory = chest.getBlockInventory();
        int limit = Math.min(slots, inventory.getSize());
        for (int i = 0; i < limit; i++) {
            ItemStack candidate = inventory.getItem(i);
            if (candidate == null || candidate.getType().isAir()) continue;
            if (fuelValue(machine, candidate, true).eu() <= 0) continue;
            if (current != null && !current.getType().isAir() && !current.isSimilar(candidate)) continue;
            ItemStack fuel = current == null || current.getType().isAir() ? candidate.clone() : current.clone();
            int move = Math.min(candidate.getAmount(), fuel.getMaxStackSize() - fuel.getAmount());
            if (current == null || current.getType().isAir()) {
                move = Math.min(candidate.getAmount(), fuel.getMaxStackSize());
                fuel.setAmount(move);
            } else {
                fuel.setAmount(fuel.getAmount() + move);
            }
            candidate.setAmount(candidate.getAmount() - move);
            if (candidate.getAmount() <= 0) inventory.setItem(i, null);
            runtime.fuel(fuel);
            markDirty(runtime);
            return;
        }
    }

    public void removeGeneratorTopStorage(Block block, Location dropLocation) {
        if (block == null) return;
        MachineDefinition machine = machineAt(block);
        if (machine == null || !"COAL_GENERATOR".equalsIgnoreCase(machine.type())) return;
        Block top = block.getRelative(BlockFace.UP);
        if (!(top.getState() instanceof Chest chest)) return;
        Location drop = dropLocation == null ? top.getLocation().add(0.5, 0.5, 0.5) : dropLocation;
        for (ItemStack item : chest.getBlockInventory().getContents()) {
            if (item != null && !item.getType().isAir()) drop.getWorld().dropItemNaturally(drop, item);
        }
        top.setType(Material.AIR, false);
    }

    private int generatorStorageSlots(MachineRuntime runtime) {
        if (runtime == null) return 0;
        int best = 0;
        for (int i = 0; i < 3; i++) {
            String id = minions.specialItems().readSpecialItemId(runtime.upgrade(i)).orElse("").toLowerCase(java.util.Locale.ROOT);
            best = Math.max(best, switch (id) {
                case "storage_expander" -> 3;
                case "medium_minion_storage" -> 5;
                case "large_minion_storage" -> 7;
                default -> 0;
            });
        }
        return best;
    }

    private void tickFuel(MachineDefinition machine, MachineRuntime runtime, boolean generatorFuel) {
        int capacity = capacity(machine, runtime);
        if (capacity <= 0) return;
        int beforeEnergy = runtime.energy();
        int beforeBurn = runtime.burnRemainingSeconds();
        runtime.burnTick(capacity);
        if (beforeEnergy != runtime.energy() || beforeBurn != runtime.burnRemainingSeconds()) markDirty(runtime);
        if (runtime.energy() >= capacity || runtime.burnRemainingSeconds() > 0) return;

        // Konsument korzysta ze źródła zapasowego dopiero wtedy, gdy nie dostaje świeżo energii
        // z generatora/akumulatora/kabla. Słabe źródło (10 EU/s) może jednak doładowywać bufor
        // przez kilka sekund, aż uzbiera koszt jednej sekundy pracy maszyny. Dzięki temu urządzenia
        // o poborze >10 EU/s działają wolniej/okresowo zamiast blokować się po pierwszych 10 EU.
        if (!generatorFuel) {
            int fallbackTarget = isAccumulator(machine)
                    ? capacity
                    : Math.min(capacity, Math.max(1, effectiveEnergyPerSecond(machine, runtime)));
            if (runtime.energy() >= fallbackTarget) return;
            long lastExternal = externalEnergyReceivedAt.getOrDefault(runtime.blockKey(), 0L);
            if (System.currentTimeMillis() - lastExternal < 2000L) return;
            ItemStack portable = runtime.fuel();
            int stored = portableEnergyStored(portable);
            if (stored > 0) {
                int moved = Math.min(Math.max(0, capacity - runtime.energy()), stored);
                if (moved > 0) {
                    runtime.addEnergy(moved, capacity);
                    runtime.fuel(updatePortableEnergy(portable, stored - moved));
                    markDirty(runtime);
                }
                return;
            }
        }

        FuelValue value = fuelValue(machine, runtime.fuel(), generatorFuel);
        if (value.eu <= 0) return;
        int eu = value.eu;
        if (generatorFuel) {
            double multiplier = activeMachineUpgrade(machine, runtime)
                    .map(MachineUpgradeDefinition::energyGenerationMultiplier)
                    .orElse(1.0D);
            eu = Math.max(1, (int) Math.round(eu * multiplier));
        }
        runtime.consumeFuelItem();
        runtime.startBurn(eu, value.burnSeconds);
        runtime.burnTick(capacity);
        markDirty(runtime);
    }


    private void tickElectricFurnace(Block block, MachineDefinition machine, MachineRuntime runtime) {
        if (block == null || machine == null || runtime == null) return;
        ensureElectricFurnaceStorage(block, runtime);
        pullElectricFurnaceInputs(block, runtime);
        List<ElectricFurnaceJob> jobs = electricFurnaceJobs(block, machine, runtime, true);
        if (jobs.isEmpty()) {
            resetIdleElectricFurnaceProcesses(runtime, Set.of());
            return;
        }
        if (!runtime.consumeEnergy(effectiveEnergyPerSecond(machine, runtime))) return;
        Set<Integer> activeSlots = new HashSet<>();
        for (ElectricFurnaceJob job : jobs) {
            activeSlots.add(job.processSlot());
            if (!job.recipeId().equals(runtime.recipeIdAt(job.processSlot()))) runtime.startProcessAt(job.processSlot(), job.recipeId());
            runtime.addProgressSecondAt(job.processSlot());
        }
        resetIdleElectricFurnaceProcesses(runtime, activeSlots);
        for (ElectricFurnaceJob job : jobs) {
            if (runtime.progressSecondsAt(job.processSlot()) < job.timeSeconds()) continue;
            completeElectricFurnaceJob(block, machine, runtime, job);
        }
        markDirty(runtime);
    }

    private void tickExternalOutputProcessor(Block block, MachineDefinition machine, MachineRuntime runtime) {
        if (block == null || machine == null || runtime == null) return;
        ensureElectricFurnaceStorage(block, runtime);
        pullElectricComposterInput(block, machine, runtime);
        Match active = findExternalOutputMatch(block, machine, runtime, true);
        MachineRecipe recipe = active == null ? null : active.recipe();
        if (recipe == null) {
            if (!runtime.recipeId().isBlank() || runtime.progressSeconds() > 0) {
                runtime.resetProcess();
                markDirty(runtime);
            }
            return;
        }
        String processKey = recipe.id() + "@" + active.inputIndex();
        if (!processKey.equals(runtime.recipeId())) runtime.startProcess(processKey);
        if (machine.energy().enabled()) {
            int cost = effectiveEnergyPerSecond(machine, runtime);
            if (!runtime.consumeEnergy(cost)) return;
        }
        runtime.addProgressSecond();
        if (runtime.progressSeconds() >= recipe.timeSeconds()) completeExternalOutputRecipe(block, machine, runtime, recipe, active.inputIndex());
        markDirty(runtime);
    }

    private void pullElectricComposterInput(Block block, MachineDefinition machine, MachineRuntime runtime) {
        ItemStack current = runtime.input();
        if (current != null && !current.getType().isAir()) return;
        ItemStack pulled = pullFromMachineInputStorage(block, machine, runtime, null, 64);
        if (pulled != null && !pulled.getType().isAir()) {
            runtime.input(pulled);
            markDirty(runtime);
        }
    }

    private Match findExternalOutputMatch(Block block, MachineDefinition machine, MachineRuntime runtime, boolean createOutputChest) {
        if (machine == null || runtime == null) return null;
        int inputCount = Math.max(1, machine.inputSlots().size());
        for (int inputIndex = 0; inputIndex < inputCount; inputIndex++) {
            ItemStack candidateInput = runtime.inputAt(inputIndex);
            for (MachineRecipe recipe : machine.recipes()) {
                if (!recipe.matchesInput(candidateInput, minions.specialItems())) continue;
                if (!recipe.matchesSecondary(runtime.secondary(), minions.specialItems())) continue;
                if (!recipe.matchesFuel(runtime.fuel(), minions.specialItems())) continue;
                if (!canAcceptExternalOutput(block, machine, runtime, output(recipe), createOutputChest)) continue;
                return new Match(recipe, inputIndex);
            }
        }
        return null;
    }

    private void completeExternalOutputRecipe(Block block, MachineDefinition machine, MachineRuntime runtime, MachineRecipe recipe, int inputIndex) {
        Match active = findExternalOutputMatch(block, machine, runtime, true);
        String processKey = recipe.id() + "@" + inputIndex;
        if (active == null || !active.recipe().id().equals(recipe.id()) || active.inputIndex() != inputIndex || !processKey.equals(runtime.recipeId())) {
            runtime.resetProcess();
            return;
        }
        ItemStack beforeInput = runtime.inputAt(inputIndex);
        boolean success = ThreadLocalRandom.current().nextDouble() <= recipe.successChance();
        ItemStack output = success ? output(recipe) : null;
        if (success && !insertExternalOutput(block, machine, runtime, output)) return;
        runtime.consumeInputAt(inputIndex, recipe.inputAmount());
        if (!recipe.secondarySpecialItem().isBlank() || recipe.secondaryMaterial() != Material.AIR) runtime.consumeSecondary(recipe.secondaryAmount());
        if (!recipe.fuelSpecialItem().isBlank() || recipe.fuelMaterial() != Material.AIR) consumeRecipeFuel(runtime, recipe);
        refillMachineInput(block, machine, runtime, inputIndex, beforeInput);
        if (success) recordMachineOutput(runtime, recipe);
        runtime.resetProcess();
    }

    private boolean canAcceptExternalOutput(Block block, MachineDefinition machine, MachineRuntime runtime, ItemStack item, boolean createOutputChest) {
        if (item == null || item.getType().isAir()) return false;
        ItemStack[] virtualChest = electricOutputChestContents(block, runtime, createOutputChest);
        if (reserveIntoVirtualInventory(virtualChest, item)) return true;
        return canAddOutputAt(machine, runtime, item, 0);
    }

    private boolean canAddOutputAt(MachineDefinition machine, MachineRuntime runtime, ItemStack output, int outputIndex) {
        if (machine == null || runtime == null || output == null || output.getType().isAir()) return false;
        ItemStack current = runtime.outputAt(outputIndex);
        int max = Math.min(output.getMaxStackSize(), machine.defaultOutputStackSize());
        if (current == null || current.getType().isAir()) return output.getAmount() <= max;
        return current.isSimilar(output) && current.getAmount() + output.getAmount() <= max;
    }

    private boolean insertExternalOutput(Block block, MachineDefinition machine, MachineRuntime runtime, ItemStack output) {
        if (insertIntoElectricOutputStorage(block, runtime, output)) return true;
        return addOutputAt(machine, runtime, output, 0);
    }

    private void refillMachineInput(Block block, MachineDefinition machine, MachineRuntime runtime, int inputIndex, ItemStack template) {
        if (template == null || template.getType().isAir()) return;
        ItemStack current = runtime.inputAt(inputIndex);
        int currentAmount = current == null || current.getType().isAir() ? 0 : current.getAmount();
        int need = Math.max(0, template.getMaxStackSize() - currentAmount);
        if (need <= 0) return;
        ItemStack pulled = pullFromMachineInputStorage(block, machine, runtime, template, need);
        if (pulled == null || pulled.getType().isAir()) return;
        if (current == null || current.getType().isAir()) {
            runtime.inputAt(inputIndex, pulled);
        } else if (current.isSimilar(pulled)) {
            current.setAmount(Math.min(current.getMaxStackSize(), current.getAmount() + pulled.getAmount()));
            runtime.inputAt(inputIndex, current);
        }
    }

    private ItemStack pullFromMachineInputStorage(Block block, MachineDefinition machine, MachineRuntime runtime, ItemStack template, int maxAmount) {
        int slots = electricInputStorageSlots(runtime);
        if (slots <= 0 || block == null || machine == null) return null;
        Chest chest = electricStorageChest(block, BlockFace.UP, slots, true);
        if (chest == null) return null;
        Inventory inventory = chest.getBlockInventory();
        int limit = Math.min(slots, inventory.getSize());
        for (int i = 0; i < limit; i++) {
            ItemStack candidate = inventory.getItem(i);
            if (candidate == null || candidate.getType().isAir()) continue;
            if (template != null && !candidate.isSimilar(template)) continue;
            if (template == null && !isMachineInputCandidate(machine, candidate)) continue;
            int move = Math.min(Math.max(1, maxAmount), candidate.getAmount());
            ItemStack result = candidate.clone();
            result.setAmount(move);
            candidate.setAmount(candidate.getAmount() - move);
            if (candidate.getAmount() <= 0) inventory.setItem(i, null);
            return result;
        }
        return null;
    }

    private boolean isMachineInputCandidate(MachineDefinition machine, ItemStack item) {
        if (machine == null || item == null || item.getType().isAir()) return false;
        for (MachineRecipe recipe : machine.recipes()) {
            if (recipe.matchesInput(item, minions.specialItems())) return true;
        }
        return false;
    }

    private void resetIdleElectricFurnaceProcesses(MachineRuntime runtime, Set<Integer> activeSlots) {
        for (int slot = 0; slot < 2; slot++) {
            if (!activeSlots.contains(slot) && (!runtime.recipeIdAt(slot).isBlank() || runtime.progressSecondsAt(slot) > 0)) runtime.resetProcessAt(slot);
        }
    }

    private List<ElectricFurnaceJob> electricFurnaceJobs(Block block, MachineDefinition machine, MachineRuntime runtime, boolean createOutputChest) {
        if (!isElectricFurnace(machine)) return List.of();
        List<ElectricFurnaceJob> jobs = new ArrayList<>();
        ItemStack[] virtualOutputs = new ItemStack[]{runtime.outputAt(0), runtime.outputAt(1)};
        ItemStack[] virtualChest = electricOutputChestContents(block, runtime, createOutputChest);
        ElectricFurnaceJob steel = steelJob(machine, runtime, virtualOutputs, virtualChest);
        if (steel != null) {
            jobs.add(steel);
            return jobs;
        }
        for (int inputIndex = 0; inputIndex < Math.min(2, Math.max(1, machine.inputSlots().size())); inputIndex++) {
            ItemStack input = runtime.inputAt(inputIndex);
            ElectricVanillaRecipe recipe = vanillaFurnaceRecipe(input);
            if (recipe == null) {
                if (!runtime.recipeIdAt(inputIndex).isBlank()) runtime.resetProcessAt(inputIndex);
                continue;
            }
            ItemStack output = recipe.output();
            int preferredOutput = reserveElectricOutput(machine, virtualOutputs, virtualChest, output, inputIndex);
            if (preferredOutput < -1) continue;
            jobs.add(new ElectricFurnaceJob("vanilla:" + recipe.key(), inputIndex, inputIndex, -1, 1, 0, output, recipe.timeSeconds(), preferredOutput));
        }
        return jobs;
    }

    private ElectricFurnaceJob steelJob(MachineDefinition machine, MachineRuntime runtime, ItemStack[] virtualOutputs, ItemStack[] virtualChest) {
        int ironSlot = -1;
        int coalSlot = -1;
        for (int i = 0; i < 2; i++) {
            ItemStack item = runtime.inputAt(i);
            if (item == null || item.getType().isAir()) continue;
            if (item.getType() == Material.IRON_INGOT && item.getAmount() >= 1) ironSlot = i;
            if (item.getType() == Material.COAL && item.getAmount() >= 8) coalSlot = i;
        }
        if (ironSlot < 0 || coalSlot < 0 || ironSlot == coalSlot) return null;
        ItemStack output = minions.specialItems().createItem("steel_ingot", 1);
        int preferredOutput = reserveElectricOutput(machine, virtualOutputs, virtualChest, output, ironSlot);
        if (preferredOutput < -1) return null;
        return new ElectricFurnaceJob("electric_steel:" + ironSlot + ":" + coalSlot, 0, ironSlot, coalSlot, 1, 8, output, electricFurnaceDefaultTimeSeconds(), preferredOutput);
    }

    private void completeElectricFurnaceJob(Block block, MachineDefinition machine, MachineRuntime runtime, ElectricFurnaceJob job) {
        ItemStack beforePrimary = runtime.inputAt(job.primaryInputSlot());
        ItemStack beforeSecondary = job.secondaryInputSlot() >= 0 ? runtime.inputAt(job.secondaryInputSlot()) : null;
        if (!insertElectricOutput(block, machine, runtime, job.output(), job.preferredOutputIndex())) return;
        runtime.consumeInputAt(job.primaryInputSlot(), job.primaryAmount());
        if (job.secondaryInputSlot() >= 0) runtime.consumeInputAt(job.secondaryInputSlot(), job.secondaryAmount());
        refillElectricInput(block, runtime, job.primaryInputSlot(), beforePrimary);
        if (job.secondaryInputSlot() >= 0) refillElectricInput(block, runtime, job.secondaryInputSlot(), beforeSecondary);
        runtime.resetProcessAt(job.processSlot());
        if (job.secondaryInputSlot() >= 0) runtime.resetProcessAt(1);
    }

    private int reserveElectricOutput(MachineDefinition machine, ItemStack[] virtualOutputs, ItemStack[] virtualChest, ItemStack output, int preferredOutputIndex) {
        if (output == null || output.getType().isAir()) return -2;
        if (reserveIntoVirtualInventory(virtualChest, output)) return -1;
        int preferred = Math.max(0, Math.min(1, preferredOutputIndex));
        if (reserveIntoVirtualOutput(machine, virtualOutputs, output, preferred)) return preferred;
        int other = preferred == 0 ? 1 : 0;
        if (reserveIntoVirtualOutput(machine, virtualOutputs, output, other)) return other;
        return -2;
    }

    private boolean reserveIntoVirtualInventory(ItemStack[] contents, ItemStack item) {
        if (contents == null || item == null || item.getType().isAir()) return false;
        int remaining = item.getAmount();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack current = contents[i];
            if (current == null || current.getType().isAir()) {
                int move = Math.min(remaining, item.getMaxStackSize());
                ItemStack copy = item.clone();
                copy.setAmount(move);
                contents[i] = copy;
                remaining -= move;
            } else if (current.isSimilar(item)) {
                int move = Math.min(remaining, Math.max(0, current.getMaxStackSize() - current.getAmount()));
                current.setAmount(current.getAmount() + move);
                remaining -= move;
            }
        }
        return remaining <= 0;
    }

    private boolean reserveIntoVirtualOutput(MachineDefinition machine, ItemStack[] outputs, ItemStack item, int index) {
        if (outputs == null || item == null || index < 0 || index >= outputs.length) return false;
        ItemStack current = outputs[index];
        int max = Math.min(item.getMaxStackSize(), machine.defaultOutputStackSize());
        if (current == null || current.getType().isAir()) {
            ItemStack copy = item.clone();
            copy.setAmount(Math.min(max, item.getAmount()));
            if (copy.getAmount() < item.getAmount()) return false;
            outputs[index] = copy;
            return true;
        }
        if (!current.isSimilar(item)) return false;
        if (current.getAmount() + item.getAmount() > max) return false;
        current.setAmount(current.getAmount() + item.getAmount());
        return true;
    }

    private boolean insertElectricOutput(Block block, MachineDefinition machine, MachineRuntime runtime, ItemStack output, int preferredOutputIndex) {
        if (insertIntoElectricOutputStorage(block, runtime, output)) return true;
        int preferred = preferredOutputIndex < 0 ? 0 : Math.max(0, Math.min(1, preferredOutputIndex));
        if (addOutputAt(machine, runtime, output, preferred)) return true;
        return addOutputAt(machine, runtime, output, preferred == 0 ? 1 : 0);
    }

    private boolean addOutputAt(MachineDefinition machine, MachineRuntime runtime, ItemStack output, int outputIndex) {
        ItemStack current = runtime.outputAt(outputIndex);
        int max = Math.min(output.getMaxStackSize(), machine.defaultOutputStackSize());
        if (current == null || current.getType().isAir()) {
            if (output.getAmount() > max) return false;
            runtime.outputAt(outputIndex, output);
            return true;
        }
        if (!current.isSimilar(output) || current.getAmount() + output.getAmount() > max) return false;
        current.setAmount(current.getAmount() + output.getAmount());
        runtime.outputAt(outputIndex, current);
        return true;
    }

    private void ensureElectricFurnaceStorage(Block block, MachineRuntime runtime) {
        if (block == null || runtime == null) return;
        int inputSlots = electricInputStorageSlots(runtime);
        if (inputSlots > 0) electricStorageChest(block, BlockFace.UP, inputSlots, true);
        int outputSlots = electricOutputStorageSlots(runtime);
        if (outputSlots > 0) electricStorageChest(block, BlockFace.DOWN, outputSlots, true);
    }

    private void pullElectricFurnaceInputs(Block block, MachineRuntime runtime) {
        for (int inputIndex = 0; inputIndex < 2; inputIndex++) {
            ItemStack current = runtime.inputAt(inputIndex);
            if (current != null && !current.getType().isAir()) continue;
            ItemStack pulled = pullFromElectricInputStorage(block, runtime, null, 64);
            if (pulled != null && !pulled.getType().isAir()) {
                runtime.inputAt(inputIndex, pulled);
                markDirty(runtime);
            }
        }
    }

    private void refillElectricInput(Block block, MachineRuntime runtime, int inputIndex, ItemStack template) {
        if (template == null || template.getType().isAir()) return;
        ItemStack current = runtime.inputAt(inputIndex);
        int currentAmount = current == null || current.getType().isAir() ? 0 : current.getAmount();
        int need = Math.max(0, template.getMaxStackSize() - currentAmount);
        if (need <= 0) return;
        ItemStack pulled = pullFromElectricInputStorage(block, runtime, template, need);
        if (pulled == null || pulled.getType().isAir()) return;
        if (current == null || current.getType().isAir()) {
            runtime.inputAt(inputIndex, pulled);
        } else if (current.isSimilar(pulled)) {
            current.setAmount(Math.min(current.getMaxStackSize(), current.getAmount() + pulled.getAmount()));
            runtime.inputAt(inputIndex, current);
        }
    }

    private ItemStack pullFromElectricInputStorage(Block block, MachineRuntime runtime, ItemStack template, int maxAmount) {
        int slots = electricInputStorageSlots(runtime);
        if (slots <= 0 || block == null) return null;
        Chest chest = electricStorageChest(block, BlockFace.UP, slots, true);
        if (chest == null) return null;
        Inventory inventory = chest.getBlockInventory();
        int limit = Math.min(slots, inventory.getSize());
        for (int i = 0; i < limit; i++) {
            ItemStack candidate = inventory.getItem(i);
            if (candidate == null || candidate.getType().isAir()) continue;
            if (template != null && !candidate.isSimilar(template)) continue;
            if (template == null && !isElectricFurnaceInputCandidate(candidate)) continue;
            int move = Math.min(Math.max(1, maxAmount), candidate.getAmount());
            ItemStack result = candidate.clone();
            result.setAmount(move);
            candidate.setAmount(candidate.getAmount() - move);
            if (candidate.getAmount() <= 0) inventory.setItem(i, null);
            return result;
        }
        return null;
    }

    private boolean insertIntoElectricOutputStorage(Block block, MachineRuntime runtime, ItemStack item) {
        int slots = electricOutputStorageSlots(runtime);
        if (slots <= 0 || block == null || item == null || item.getType().isAir()) return false;
        Chest chest = electricStorageChest(block, BlockFace.DOWN, slots, true);
        if (chest == null) return false;
        return insertIntoLimitedInventory(chest.getBlockInventory(), slots, item);
    }

    private boolean insertIntoLimitedInventory(Inventory inventory, int slots, ItemStack item) {
        if (inventory == null || item == null || item.getType().isAir()) return false;
        int limit = Math.min(slots, inventory.getSize());
        int remaining = item.getAmount();
        for (int i = 0; i < limit && remaining > 0; i++) {
            ItemStack current = inventory.getItem(i);
            if (current == null || current.getType().isAir() || !current.isSimilar(item)) continue;
            int move = Math.min(remaining, Math.max(0, current.getMaxStackSize() - current.getAmount()));
            if (move <= 0) continue;
            current.setAmount(current.getAmount() + move);
            remaining -= move;
        }
        for (int i = 0; i < limit && remaining > 0; i++) {
            ItemStack current = inventory.getItem(i);
            if (current != null && !current.getType().isAir()) continue;
            int move = Math.min(remaining, item.getMaxStackSize());
            ItemStack copy = item.clone();
            copy.setAmount(move);
            inventory.setItem(i, copy);
            remaining -= move;
        }
        return remaining <= 0;
    }

    private ItemStack[] electricOutputChestContents(Block block, MachineRuntime runtime, boolean create) {
        int slots = electricOutputStorageSlots(runtime);
        if (slots <= 0 || block == null) return null;
        Chest chest = electricStorageChest(block, BlockFace.DOWN, slots, create);
        if (chest == null) return null;
        Inventory inventory = chest.getBlockInventory();
        int limit = Math.min(slots, inventory.getSize());
        ItemStack[] contents = new ItemStack[limit];
        for (int i = 0; i < limit; i++) {
            ItemStack item = inventory.getItem(i);
            contents[i] = item == null || item.getType().isAir() ? null : item.clone();
        }
        return contents;
    }

    private Chest electricStorageChest(Block block, BlockFace face, int slots, boolean create) {
        if (block == null || slots <= 0) return null;
        Block storageBlock = block.getRelative(face);
        Material targetMaterial = slots >= 7 ? Material.TRAPPED_CHEST : Material.CHEST;
        if (storageBlock.getType() == Material.AIR && create) {
            storageBlock.setType(targetMaterial, false);
        } else if (storageBlock.getState() instanceof Chest existing && storageBlock.getType() != targetMaterial) {
            ItemStack[] contents = java.util.Arrays.stream(existing.getBlockInventory().getContents())
                    .map(item -> item == null ? null : item.clone())
                    .toArray(ItemStack[]::new);
            storageBlock.setType(targetMaterial, false);
            if (storageBlock.getState() instanceof Chest converted) {
                converted.getBlockInventory().setContents(contents);
                converted.update(true, false);
            }
        }
        if (!(storageBlock.getState() instanceof Chest chest)) return null;
        String name = slots >= 7 ? "§6Duży magazyn maszyny" : slots >= 5 ? "§6Średni magazyn maszyny" : "§eMagazyn maszyny";
        if (!name.equals(chest.getCustomName())) {
            chest.setCustomName(name);
            chest.update(true, false);
        }
        return chest;
    }

    public int externalStorageSlots(MachineRuntime runtime, BlockFace face) {
        return face == BlockFace.DOWN ? electricOutputStorageSlots(runtime) : electricInputStorageSlots(runtime);
    }

    public Chest externalStorageChest(Block machineBlock, BlockFace face, int slots, boolean create) {
        return electricStorageChest(machineBlock, face, slots, create);
    }

    private int electricInputStorageSlots(MachineRuntime runtime) {
        return storageSlotsFromItem(runtime == null ? null : runtime.upgrade(0));
    }

    private int electricOutputStorageSlots(MachineRuntime runtime) {
        return storageSlotsFromItem(runtime == null ? null : runtime.upgrade(1));
    }

    private int storageSlotsFromItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return 0;
        int configured = minions.storageChestSlotsFromItem(item);
        if (configured > 0) return configured;
        String id = minions.specialItems().readSpecialItemId(item).orElse("").toLowerCase(java.util.Locale.ROOT);
        return switch (id) {
            case "storage_expander" -> 3;
            case "medium_minion_storage" -> 5;
            case "large_minion_storage" -> 7;
            default -> 0;
        };
    }

    private boolean isElectricFurnaceInputCandidate(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        if (item.getType() == Material.IRON_INGOT || item.getType() == Material.COAL) return true;
        return vanillaFurnaceRecipe(item) != null;
    }

    private ElectricVanillaRecipe vanillaFurnaceRecipe(ItemStack input) {
        if (input == null || input.getType().isAir()) return null;
        ItemStack one = input.clone();
        one.setAmount(1);
        java.util.Iterator<org.bukkit.inventory.Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            org.bukkit.inventory.Recipe recipe = iterator.next();
            if (!(recipe instanceof org.bukkit.inventory.FurnaceRecipe furnace)) continue;
            org.bukkit.inventory.RecipeChoice choice = furnace.getInputChoice();
            if (choice == null || !choice.test(one)) continue;
            ItemStack result = furnace.getResult();
            if (result == null || result.getType().isAir()) continue;
            return new ElectricVanillaRecipe(furnace.getKey().toString(), result.clone(), Math.max(1, furnace.getCookingTime() / 20 - 1));
        }
        return null;
    }

    private int electricFurnaceDefaultTimeSeconds() {
        return 9;
    }

    public boolean isExternalStorageMachine(MachineDefinition machine) {
        return hasExternalStorage(machine);
    }

    public boolean isExternalStorageUpgradeSlot(MachineDefinition machine, int slot) {
        if (!hasExternalStorage(machine)) return false;
        return slot == machine.inputStorageExtensionSlot() || slot == machine.outputStorageExtensionSlot();
    }

    public boolean isValidExternalStorageItem(ItemStack item) {
        return storageSlotsFromItem(item) > 0;
    }

    public boolean canInstallExternalStorageAt(Block machineBlock, MachineDefinition machine, int menuSlot, ItemStack item) {
        if (machineBlock == null || machine == null || !isExternalStorageUpgradeSlot(machine, menuSlot)) return false;
        if (item == null || item.getType().isAir()) return true;
        if (!isValidExternalStorageItem(item)) return false;
        BlockFace face = menuSlot == machine.inputStorageExtensionSlot() ? BlockFace.UP : BlockFace.DOWN;
        Block storageBlock = machineBlock.getRelative(face);
        return storageBlock.getType().isAir() || storageBlock.getState() instanceof Chest;
    }

    public void ensureExternalStorage(Block block, MachineDefinition machine, MachineRuntime runtime) {
        if (!hasExternalStorage(machine) || block == null || runtime == null) return;
        reconcileExternalStorage(block, BlockFace.UP, electricInputStorageSlots(runtime));
        reconcileExternalStorage(block, BlockFace.DOWN, electricOutputStorageSlots(runtime));
    }

    private void reconcileExternalStorage(Block machineBlock, BlockFace face, int slots) {
        if (slots > 0) {
            electricStorageChest(machineBlock, face, slots, true);
            return;
        }
        Block storageBlock = machineBlock.getRelative(face);
        if (!(storageBlock.getState() instanceof Chest chest) || !isManagedExternalStorageChest(chest)) return;
        removeStorageChest(storageBlock, machineBlock.getLocation().add(0.5, 0.5, 0.5));
    }

    private boolean isManagedExternalStorageChest(Chest chest) {
        if (chest == null || chest.getCustomName() == null) return false;
        String name = org.bukkit.ChatColor.stripColor(chest.getCustomName());
        return name != null && (name.equalsIgnoreCase("Magazyn maszyny")
                || name.equalsIgnoreCase("Średni magazyn maszyny")
                || name.equalsIgnoreCase("Duży magazyn maszyny")
                // Wsteczna kompatybilność dla magazynów utworzonych przed zmianą nazewnictwa UI.
                || name.equalsIgnoreCase("Mały magazyn miniona")
                || name.equalsIgnoreCase("Rozszerzenie magazynu miniona")
                || name.equalsIgnoreCase("Średni magazyn miniona")
                || name.equalsIgnoreCase("Duży magazyn miniona"));
    }

    public void removeElectricFurnaceStorage(Block block, Location dropLocation) {
        if (block == null) return;
        MachineDefinition machine = machineAt(block);
        if (!hasExternalStorage(machine)) return;
        removeStorageChest(block.getRelative(BlockFace.UP), dropLocation);
        removeStorageChest(block.getRelative(BlockFace.DOWN), dropLocation);
    }

    private void removeStorageChest(Block block, Location dropLocation) {
        if (block == null || !(block.getState() instanceof Chest chest)) return;
        Location drop = dropLocation == null ? block.getLocation().add(0.5, 0.5, 0.5) : dropLocation;
        for (ItemStack item : chest.getBlockInventory().getContents()) {
            if (item != null && !item.getType().isAir()) drop.getWorld().dropItemNaturally(drop, item);
        }
        block.setType(Material.AIR, false);
    }

    private boolean isElectricFurnace(MachineDefinition machine) {
        return machine != null && "ELECTRIC_FURNACE".equalsIgnoreCase(machine.type());
    }

    private boolean isElectricComposter(MachineDefinition machine) {
        return machine != null && "ELECTRIC_MILL".equalsIgnoreCase(machine.type());
    }

    private boolean isMeatRefinery(MachineDefinition machine) {
        return machine != null && "MEAT_REFINERY".equalsIgnoreCase(machine.type());
    }

    private boolean isMacerator(MachineDefinition machine) {
        return machine != null && "MACERATOR".equalsIgnoreCase(machine.type());
    }

    private boolean isExternalOutputProcessor(MachineDefinition machine) {
        return isElectricComposter(machine) || isMeatRefinery(machine) || isMacerator(machine);
    }

    private boolean hasExternalStorage(MachineDefinition machine) {
        return isElectricFurnace(machine) || isExternalOutputProcessor(machine);
    }

    private void tickProcess(MachineDefinition machine, MachineRuntime runtime) {
        Match active = findMatch(machine, runtime);
        MachineRecipe recipe = active == null ? null : active.recipe();
        if (recipe == null) {
            if (!runtime.recipeId().isBlank() || runtime.progressSeconds() > 0) {
                runtime.resetProcess();
                markDirty(runtime);
            }
            return;
        }
        String processKey = recipe.id() + "@" + active.inputIndex();
        if (!processKey.equals(runtime.recipeId())) runtime.startProcess(processKey);
        if (machine.energy().enabled()) {
            int cost = effectiveEnergyPerSecond(machine, runtime);
            if (!runtime.consumeEnergy(cost)) return;
        }
        runtime.addProgressSecond();
        if (runtime.progressSeconds() >= recipe.timeSeconds()) completeRecipe(machine, runtime, recipe, active.inputIndex());
        markDirty(runtime);
    }

    private void completeRecipe(MachineDefinition machine, MachineRuntime runtime, MachineRecipe recipe, int inputIndex) {
        Match active = findMatch(machine, runtime);
        String processKey = recipe.id() + "@" + inputIndex;
        if (active == null || !active.recipe().id().equals(recipe.id()) || active.inputIndex() != inputIndex || !processKey.equals(runtime.recipeId())) {
            runtime.resetProcess();
            return;
        }
        runtime.consumeInputAt(inputIndex, recipe.inputAmount());
        if (!recipe.secondarySpecialItem().isBlank() || recipe.secondaryMaterial() != Material.AIR) runtime.consumeSecondary(recipe.secondaryAmount());
        if (!recipe.fuelSpecialItem().isBlank() || recipe.fuelMaterial() != Material.AIR) consumeRecipeFuel(runtime, recipe);
        if (ThreadLocalRandom.current().nextDouble() <= recipe.successChance()) {
            addOutput(machine, runtime, output(recipe));
            recordMachineOutput(runtime, recipe);
        }
        runtime.resetProcess();
    }


    private void recordMachineOutput(MachineRuntime runtime, MachineRecipe recipe) {
        MaceratorCollectionFix.record(this, runtime, recipe);
    }

    private void addOutput(MachineDefinition machine, MachineRuntime runtime, ItemStack output) {
        ItemStack current = runtime.output();
        if (current == null || current.getType().isAir()) {
            runtime.output(output);
            return;
        }
        if (!current.isSimilar(output)) return;
        current.setAmount(Math.min(Math.min(current.getMaxStackSize(), machine.defaultOutputStackSize()), current.getAmount() + output.getAmount()));
        runtime.output(current);
    }

    private void distributeAccumulatorEnergy(Block accumulatorBlock, MachineDefinition accumulator, MachineRuntime accumulatorRuntime) {
        if (accumulatorBlock == null || accumulatorRuntime.energy() <= 0) return;
        if (cableService != null) cableService.transferFromGenerator(accumulatorBlock, accumulator, accumulatorRuntime);
        BlockFace input = accumulatorInputFace(accumulatorBlock);
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)) {
            if (face == input) continue;
            Block target = accumulatorBlock.getRelative(face);
            MachineDefinition consumer = machineAt(target);
            if (!canReceiveFromAccumulator(consumer)) continue;
            if (!canReceiveEnergyFrom(target, consumer, face.getOppositeFace())) continue;
            MachineRuntime consumerRuntime = runtime(key(target.getLocation()), consumer.id());
            int need = Math.max(0, capacity(consumer, consumerRuntime) - consumerRuntime.energy());
            int move = Math.min(Math.min(effectiveTransferPerSecond(accumulator, accumulatorRuntime), need), accumulatorRuntime.energy());
            if (move <= 0) continue;
            accumulatorRuntime.energy(accumulatorRuntime.energy() - move);
            consumerRuntime.addEnergy(move, capacity(consumer, consumerRuntime));
            markExternalEnergyReceived(consumerRuntime);
            markDirty(accumulatorRuntime);
            markDirty(consumerRuntime);
        }
    }

    private int distributeEnergy(Block generatorBlock, MachineDefinition generator, MachineRuntime generatorRuntime) {
        int deliveredTotal = cableService == null ? 0 : cableService.transferFromGenerator(generatorBlock, generator, generatorRuntime);
        for (BlockFace face : List.of(leftOf(generatorBlock), rightOf(generatorBlock))) {
            Block target = generatorBlock.getRelative(face);
            MachineDefinition consumer = machineAt(target);
            if (consumer == null || !consumer.energy().enabled() || consumer.energy().generator()) continue;
            if (!canReceiveEnergyFrom(target, consumer, face.getOppositeFace())) continue;
            MachineRuntime consumerRuntime = runtime(key(target.getLocation()), consumer.id());
            int need = Math.max(0, capacity(consumer, consumerRuntime) - consumerRuntime.energy());
            int move = Math.min(Math.min(effectiveTransferPerSecond(generator, generatorRuntime), need), generatorRuntime.energy());
            if (move <= 0) continue;
            generatorRuntime.energy(generatorRuntime.energy() - move);
            consumerRuntime.addEnergy(move, capacity(consumer, consumerRuntime));
            deliveredTotal += move;
            markExternalEnergyReceived(consumerRuntime);
            markDirty(generatorRuntime);
            markDirty(consumerRuntime);
        }
        return deliveredTotal;
    }

    public void markExternalEnergyReceived(MachineRuntime runtime) {
        if (runtime != null) externalEnergyReceivedAt.put(runtime.blockKey(), System.currentTimeMillis());
    }

    public boolean isPortableEnergyItem(ItemStack item) {
        return portableEnergyCapacity(item) > 0;
    }

    public boolean isPortableEnergyChargeSlot(MachineDefinition machine, int slot) {
        return machine != null && machine.energy().enabled()
                && (machine.energy().generator() || isAccumulator(machine))
                && slot == machine.energy().batterySlot();
    }

    private int portableEnergyCapacity(ItemStack item) {
        String id = minions.specialItems().readSpecialItemId(item).orElse("").toLowerCase(java.util.Locale.ROOT);
        return switch (id) {
            case "battery" -> 20_000;
            default -> 0;
        };
    }

    private int portableEnergyStored(ItemStack item) {
        int capacity = portableEnergyCapacity(item);
        if (capacity <= 0 || item == null || item.getType().isAir()) return 0;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 0;
        return Math.max(0, Math.min(capacity, meta.getPersistentDataContainer().getOrDefault(portableEnergyKey, PersistentDataType.INTEGER, 0)));
    }

    private ItemStack updatePortableEnergy(ItemStack item, int stored) {
        if (item == null || item.getType().isAir()) return item;
        int capacity = portableEnergyCapacity(item);
        if (capacity <= 0) return item;
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return copy;
        int safe = Math.max(0, Math.min(capacity, stored));
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(portableEnergyKey, PersistentDataType.INTEGER, safe);
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.removeIf(line -> LegacyComponentSerializer.legacySection().serialize(line).contains("Stan naładowania:"));
        lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Stan naładowania: §b" + safe + " §7/ §f" + capacity + " EU"));
        meta.lore(lore);
        copy.setItemMeta(meta);
        return copy;
    }

    private int chargePortableEnergyItem(MachineDefinition machine, MachineRuntime runtime) {
        if (machine == null || runtime == null || runtime.energy() <= 0) return 0;
        if (!machine.energy().generator() && !isAccumulator(machine)) return 0;
        int slot = machine.energy().batterySlot();
        List<Integer> accessories = accessorySlots(machine);
        int index = accessories.indexOf(slot);
        if (index < 0 || index >= 3) return 0;
        ItemStack item = runtime.upgrade(index);
        int portableCapacity = portableEnergyCapacity(item);
        if (portableCapacity <= 0) return 0;
        int stored = portableEnergyStored(item);
        int missing = portableCapacity - stored;
        if (missing <= 0) return 0;
        int move = Math.min(Math.min(Math.max(1, effectiveTransferPerSecond(machine, runtime)), missing), runtime.energy());
        if (move <= 0) return 0;
        runtime.energy(runtime.energy() - move);
        runtime.upgrade(index, updatePortableEnergy(item, stored + move));
        markDirty(runtime);
        return move;
    }

    private Map<String, Integer> loadRecipeFuelUses(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "special-items.yml");
        if (!file.exists()) plugin.saveResource("special-items.yml", false);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("custom-fuels");
        if (root == null) return Map.of();
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null || !section.getBoolean("enabled", true)) continue;
            String specialItemId = section.getString("special-item", id);
            int uses = Math.max(1, section.getInt("machine-recipe-uses", 1));
            if (specialItemId != null && !specialItemId.isBlank() && uses > 1) {
                result.put(specialItemId.toLowerCase(java.util.Locale.ROOT), uses);
            }
        }
        return Map.copyOf(result);
    }

    private void consumeRecipeFuel(MachineRuntime runtime, MachineRecipe recipe) {
        if (runtime == null || recipe == null) return;
        ItemStack fuel = runtime.fuel();
        String specialId = minions.specialItems().readSpecialItemId(fuel).orElse("").toLowerCase(java.util.Locale.ROOT);
        int usesPerItem = recipeFuelUsesBySpecialItemId.getOrDefault(specialId, 0);
        boolean recipeAcceptsCoalFuel = recipe.fuelMaterial() == Material.COAL && (recipe.fuelSpecialItem() == null || recipe.fuelSpecialItem().isBlank());
        if (usesPerItem <= 1 || !recipeAcceptsCoalFuel || fuel == null || fuel.getType().isAir()) {
            runtime.consumeRecipeFuel(recipe.fuelAmount());
            return;
        }
        ItemStack copy = fuel.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) {
            runtime.consumeRecipeFuel(recipe.fuelAmount());
            return;
        }
        int stackAmount = Math.max(0, copy.getAmount());
        int remainingUses = meta.getPersistentDataContainer().getOrDefault(recipeFuelUsesKey, PersistentDataType.INTEGER, usesPerItem);
        int cost = Math.max(1, recipe.fuelAmount());
        while (cost > 0 && stackAmount > 0) {
            if (remainingUses > cost) {
                remainingUses -= cost;
                cost = 0;
            } else {
                cost -= remainingUses;
                stackAmount--;
                remainingUses = usesPerItem;
            }
        }
        if (stackAmount <= 0) {
            runtime.fuel(null);
            return;
        }
        copy.setAmount(stackAmount);
        if (remainingUses >= usesPerItem) meta.getPersistentDataContainer().remove(recipeFuelUsesKey);
        else meta.getPersistentDataContainer().set(recipeFuelUsesKey, PersistentDataType.INTEGER, remainingUses);
        copy.setItemMeta(meta);
        runtime.fuel(copy);
    }

    private FuelValue fuelValue(MachineDefinition machine, ItemStack item, boolean generatorFuel) {
        if (item == null || item.getType().isAir()) return new FuelValue(0, 0);
        String specialId = minions.specialItems().readSpecialItemId(item).orElse("");
        if (!specialId.isBlank()) {
            int eu = generatorFuel ? machine.energy().fuelEu(specialId) : machine.energy().fallbackFuelEu(specialId);
            int burn = generatorFuel ? machine.energy().fuelBurnSeconds(specialId, 8) : machine.energy().fallbackFuelBurnSeconds(specialId, 8);
            if (eu > 0) return new FuelValue(eu, burn);
        }
        String material = item.getType().name().toLowerCase(java.util.Locale.ROOT);
        int eu = generatorFuel ? machine.energy().fuelEu(material) : machine.energy().fallbackFuelEu(material);
        int burn = generatorFuel ? machine.energy().fuelBurnSeconds(material, 8) : machine.energy().fallbackFuelBurnSeconds(material, 8);
        return new FuelValue(eu, burn);
    }

    private void refreshOpenMenus() {
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            Inventory inv = player.getOpenInventory().getTopInventory();
            if (!(inv.getHolder() instanceof hex.minions.menu.MachineMenuHolder holder)) continue;
            MachineDefinition machine = registry.machines().get(holder.machineId());
            MachineRuntime runtime = runtimes.get(holder.blockKey());
            if (machine == null || runtime == null) continue;
            syncToInventory(machine, runtime, inv);
            if (usesProcessingInputs(machine)) inv.setItem(machine.arrowSlot(), progressItem(machine, runtime));
            inv.setItem(4, machineInfoItem(machine, runtime));
            applyMenuGuides(machine, inv);
        }
    }

    public void sanitizeMenuGuides(Inventory inv) {
        if (inv == null) return;
        for (int slot = 0; slot < inv.getSize(); slot++) {
            if (isMenuGuide(inv.getItem(slot))) {
                inv.setItem(slot, null);
            }
        }
    }

    public void applyMenuGuides(MachineDefinition machine, Inventory inv) {
        if (machine == null || inv == null) return;
        Set<Integer> guidedSlots = new HashSet<>();

        if (usesProcessingInputs(machine)) {
            for (int inputSlot : machine.inputSlots()) {
                if (guidedSlots.add(inputSlot)) setGuide(inv, inputSlot, Material.GREEN_STAINED_GLASS_PANE, "§aWejście", "§7Włóż surowiec przetwarzany przez maszynę.");
            }
            if (machine.hasSecondarySlot() && guidedSlots.add(machine.secondarySlot())) {
                setGuide(inv, machine.secondarySlot(), Material.GREEN_STAINED_GLASS_PANE, "§aDrugie wejście", "§7Włóż drugi składnik wymagany przez niektóre procesy.");
            }
            for (int outputSlot : machine.outputSlots()) {
                if (guidedSlots.add(outputSlot)) setGuide(inv, outputSlot, Material.YELLOW_STAINED_GLASS_PANE, "§eWynik", "§7Tutaj pojawi się gotowy produkt.");
            }
        }

        if (usesFuelSlot(machine) && guidedSlots.add(machine.fuelSlot())) {
            boolean emergencyPower = machine.energy().enabled() && !machine.energy().generator() && !machine.hasRecipeFuelSlot();
            if (emergencyPower) setGuide(inv, machine.fuelSlot(), Material.RED_STAINED_GLASS_PANE, "§cZasilanie Redstone", "§7Włóż redstone, gdy urządzenie potrzebuje lokalnego zasilania.");
            else setGuide(inv, machine.fuelSlot(), Material.BLUE_STAINED_GLASS_PANE, "§9Paliwo", "§7Włóż paliwo wymagane przez tę maszynę.");
        }

        List<Integer> upgrades = upgradeSlots(machine);
        if (hasExternalStorage(machine)) {
            int inputStorageSlot = machine.inputStorageExtensionSlot();
            int outputStorageSlot = machine.outputStorageExtensionSlot();
            if (inputStorageSlot >= 0 && guidedSlots.add(inputStorageSlot)) setGuide(inv, inputStorageSlot, Material.ORANGE_STAINED_GLASS_PANE, "§6Magazyn wejściowy", "§7Rozszerza miejsce na surowce do przetworzenia.");
            if (outputStorageSlot >= 0 && guidedSlots.add(outputStorageSlot)) setGuide(inv, outputStorageSlot, Material.PURPLE_STAINED_GLASS_PANE, "§dMagazyn wyjściowy", "§7Rozszerza miejsce na gotowe produkty.");
        }
        if ("COAL_GENERATOR".equalsIgnoreCase(machine.type())) {
            int fuelStorageSlot = machine.fuelStorageExtensionSlot();
            if (fuelStorageSlot >= 0 && guidedSlots.add(fuelStorageSlot)) setGuide(inv, fuelStorageSlot, Material.ORANGE_STAINED_GLASS_PANE, "§6Magazyn paliwa", "§7Rozszerza zapas paliwa generatora.");
        }
        for (int upgradeSlot : upgrades) {
            if (!guidedSlots.add(upgradeSlot)) continue;
            setGuide(inv, upgradeSlot, Material.LIME_STAINED_GLASS_PANE, "§aUlepszenie", "§7Włóż moduł poprawiający parametry urządzenia.");
        }

        int batterySlot = machine.energy().batterySlot();
        if (machine.energy().enabled() && (machine.energy().generator() || isAccumulator(machine))
                && batterySlot >= 0 && guidedSlots.add(batterySlot)) {
            setGuide(inv, batterySlot, Material.GREEN_STAINED_GLASS_PANE, "§aŁadowanie", "§7Włóż baterię lub przedmiot, który chcesz naładować.");
        }

        // Po zbudowaniu wszystkich aktywnych slotów żadna przypadkowa dziura nie powinna
        // zostać jako AIR. Czarne szyby są wyłącznie dekoracją i mają całkowicie ukryty tooltip.
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack current = inv.getItem(slot);
            if (current == null || current.getType().isAir()) inv.setItem(slot, hiddenFiller());
        }
    }

    private void setGuide(Inventory inv, int slot, Material material, String name, String lore) {
        if (slot < 0 || slot >= inv.getSize()) return;
        ItemStack current = inv.getItem(slot);
        if (current != null && !current.getType().isAir() && !isMenuGuide(current)) return;
        inv.setItem(slot, guide(material, name, lore));
    }

    private ItemStack guide(Material material, String name, String lore) {
        ItemStack item = named(material, name, lore);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(machineMenuGuideKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isMenuGuide(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(machineMenuGuideKey, PersistentDataType.BYTE);
    }

    private ItemStack progressItem(MachineDefinition machine, MachineRuntime runtime) {
        if (isElectricFurnace(machine)) return electricFurnaceProgressItem(machine, runtime);
        if (isExternalOutputProcessor(machine)) return externalOutputProgressItem(machine, runtime);
        MachineRecipe recipe = match(machine, runtime);
        if (recipe == null) return named(Material.ARROW, "§ePostęp", "§7Oczekuje na surowce, miejsce lub energię.");
        int percent = Math.min(100, runtime.progressSeconds() * 100 / Math.max(1, recipe.timeSeconds()));
        return named(Material.ARROW, "§ePostęp: §f" + percent + "%", "§7Czas: §f" + runtime.progressSeconds() + "§7/§f" + recipe.timeSeconds() + " s");
    }

    private ItemStack externalOutputProgressItem(MachineDefinition machine, MachineRuntime runtime) {
        String recipeId = runtime.recipeId();
        if (recipeId == null || recipeId.isBlank()) {
            return named(Material.ARROW, "§ePostęp", "§7Oczekuje na surowce, miejsce lub energię.");
        }
        int total = machine.recipes().stream()
                .filter(recipe -> recipeId.startsWith(recipe.id() + "@"))
                .findFirst()
                .map(MachineRecipe::timeSeconds)
                .orElse(30);
        int progress = Math.min(total, runtime.progressSeconds());
        int percent = Math.min(100, progress * 100 / Math.max(1, total));
        return named(Material.ARROW, "§ePostęp: §f" + percent + "%", "§7Czas: §f" + progress + "§7/§f" + total + " s");
    }

    private ItemStack electricFurnaceProgressItem(MachineDefinition machine, MachineRuntime runtime) {
        return named(Material.ARROW, "§ePostęp", electricProgressLine(runtime, 0) + "\n" + electricProgressLine(runtime, 1));
    }

    private String electricProgressLine(MachineRuntime runtime, int slot) {
        String recipe = runtime.recipeIdAt(slot);
        if (recipe == null || recipe.isBlank()) return "§7Slot " + (slot + 1) + ": §8oczekuje";
        int total = electricFurnaceDefaultTimeSeconds();
        int progress = Math.min(total, runtime.progressSecondsAt(slot));
        int percent = Math.min(100, progress * 100 / Math.max(1, total));
        return "§7Slot " + (slot + 1) + ": §f" + percent + "% §8(" + progress + "/" + total + " s)";
    }

    private String machinePlayerDescription(MachineDefinition machine) {
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
            case "ELECTRIC_MILL" -> "Przetwarza materiały organiczne w elektrycznym kompostowniku.";
            case "MEAT_REFINERY" -> "Rafinuje mięso do dalszego wykorzystania technologicznego.";
            default -> "Urządzenie technologiczne.";
        };
    }

    private static String formatUiNumber(long value) {
        return String.format(java.util.Locale.US, "%,d", Math.max(0L, value)).replace(',', ' ');
    }

    private static String formatUiRate(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001D) return formatUiNumber((long) Math.rint(value));
        return String.format(java.util.Locale.US, "%.1f", value).replace('.', ',');
    }

    private ItemStack machineInfoItem(MachineDefinition machine, MachineRuntime runtime) {
        Material icon = machine.energy().generator() ? Material.REDSTONE_BLOCK
                : isAccumulator(machine) ? Material.BARREL
                : machine.baseBlock();
        StringBuilder stats = new StringBuilder("§7").append(machinePlayerDescription(machine));
        if (machine.energy().enabled()) {
            stats.append("\n§7Energia: §a").append(formatUiNumber(runtime.energy())).append('/').append(formatUiNumber(capacity(machine, runtime))).append(" EU");
            if (machine.energy().generator()) {
                stats.append("\n§7Generowanie: §a").append(formatUiRate(effectiveGenerationPerSecond(machine, runtime))).append(" EU/s");
                stats.append("\n§7Transfer: §a").append(formatUiNumber(effectiveTransferPerSecond(machine, runtime))).append(" EU/s");
            } else if (isAccumulator(machine)) {
                stats.append("\n§7Transfer: §a").append(formatUiNumber(effectiveTransferPerSecond(machine, runtime))).append(" EU/s");
            } else {
                stats.append("\n§7Zużycie: §a").append(formatUiNumber(effectiveEnergyPerSecond(machine, runtime))).append(" EU/s");
            }
        }
        return machineInfo(machine, runtime, icon, machine.displayName(), stats.toString());
    }

    private ItemStack machineInfo(MachineDefinition machine, MachineRuntime runtime, Material material, String name, String lore) {
        ItemStack icon = machine == null ? null : createMachineItem(machine);
        if (icon == null || icon.getType().isAir()) icon = new ItemStack(material);
        if (machine == null || machine.upgradeSlots().isEmpty()) return named(icon, name, lore);
        String upgradeLore = activeMachineUpgrade(machine, runtime)
                .map(upgrade -> {
                    int savedPercent = Math.max(0, (int) Math.round((1.0D - upgrade.energyConsumptionMultiplier()) * 100.0D));
                    String first = "\n§aUlepszenie: §f" + machineUpgradeDisplayName(upgrade);
                    if (savedPercent > 0) return first + "\n§7Zużycie energii: §a-" + savedPercent + "%";
                    if (Math.abs(upgrade.energyGenerationMultiplier() - 1.0D) > 0.0001D) {
                        return first + "\n§7Generowanie: §a+" + (int) Math.round((upgrade.energyGenerationMultiplier() - 1.0D) * 100.0D) + "%";
                    }
                    if (Math.abs(upgrade.energyTransferMultiplier() - 1.0D) > 0.0001D) {
                        return first + "\n§7Transfer: §a+" + (int) Math.round((upgrade.energyTransferMultiplier() - 1.0D) * 100.0D) + "%";
                    }
                    if (upgrade.extraBufferCapacity() > 0) return first + "\n§7Pojemność: §a+" + formatUiNumber(upgrade.extraBufferCapacity()) + " EU";
                    return first;
                })
                .orElse("");
        return named(icon, name, lore + upgradeLore);
    }

    private static String stripMiniTags(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("<[^>]+>", "").replace('&', '§').trim();
    }

    private static String prettyId(String raw) {
        if (raw == null || raw.isBlank()) return "brak";
        String text = raw.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
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

    private ItemStack hiddenFiller() {
        return named(Material.BLACK_STAINED_GLASS_PANE, "", "");
    }

    private void hideTooltip(ItemMeta meta) {
        if (meta != null) meta.setHideTooltip(true);
    }

    private Component parseComponent(String text) {
        if (text == null) return Component.empty();
        if (text.contains("<") && text.contains(">")) return MiniMessage.miniMessage().deserialize(text);
        if (text.indexOf('§') >= 0) return LegacyComponentSerializer.legacySection().deserialize(text);
        return Component.text(text);
    }

    private int offlineOrder(MachineRuntime runtime) {
        MachineDefinition machine = runtime == null ? null : registry.machines().get(runtime.machineId());
        if (machine == null) return 3;
        if (machine.energy().enabled() && machine.energy().generator()) return 0;
        if (isAccumulator(machine)) return 1;
        return 2;
    }

    private boolean isGeneratorRuntime(MachineRuntime runtime) {
        if (runtime == null) return false;
        MachineDefinition machine = registry.machines().get(runtime.machineId());
        return machine != null && machine.energy().enabled() && (machine.energy().generator() || isAccumulator(machine));
    }

    public Optional<String> validateEnergyMachinePlacement(Block block, MachineDefinition machine) {
        if (block == null || machine == null || !machine.energy().enabled()) return Optional.empty();
        Optional<hex.towns.model.Town> town = minions.towns().townAt(block.getLocation());
        if (town.isEmpty()) return Optional.empty();
        EnergyDeviceCounts counts = energyDeviceCounts(town.get().id());
        int generators = counts.generators() + (machine.energy().generator() ? 1 : 0);
        int consumers = counts.consumers() + (isEnergyConsumer(machine) ? 1 : 0);
        int devices = counts.devices() + 1;
        if (maxGeneratorsPerTown > 0 && generators > maxGeneratorsPerTown) {
            return Optional.of("Limit generatorów w mieście: " + maxGeneratorsPerTown + ".");
        }
        if (maxEnergyMachinesPerTown > 0 && consumers > maxEnergyMachinesPerTown) {
            return Optional.of("Limit maszyn energetycznych w mieście: " + maxEnergyMachinesPerTown + ".");
        }
        if (maxEnergyDevicesPerTown > 0 && devices > maxEnergyDevicesPerTown) {
            return Optional.of("Łączny limit urządzeń energetycznych w mieście: " + maxEnergyDevicesPerTown + ".");
        }
        return Optional.empty();
    }

    public int countEnergyMachines(UUID townId) {
        return townId == null ? 0 : energyDeviceCounts(townId).consumers();
    }

    public int maxEnergyMachines(UUID townId) {
        return maxEnergyMachinesPerTown;
    }

    public int countEnergyGenerators(UUID townId) {
        return townId == null ? 0 : energyDeviceCounts(townId).generators();
    }

    public int maxEnergyGenerators(UUID townId) {
        return maxGeneratorsPerTown;
    }

    public int countEnergyDevices(UUID townId) {
        return townId == null ? 0 : energyDeviceCounts(townId).devices();
    }

    public int maxEnergyDevices(UUID townId) {
        return maxEnergyDevicesPerTown;
    }

    private EnergyDeviceCounts energyDeviceCounts(UUID townId) {
        int generators = 0;
        int consumers = 0;
        int devices = 0;
        // Kopia jest celowa: reconciliation może usunąć osierocony runtime z głównej mapy.
        for (MachineRuntime runtime : new ArrayList<>(runtimes.values())) {
            if (runtime == null || runtime.blockKey() == null) continue;
            LocationParts parts = LocationParts.parse(runtime.blockKey());
            if (parts == null) continue;
            World world = Bukkit.getWorld(parts.world());
            if (world == null) continue;

            Location location = new Location(world, parts.x(), parts.y(), parts.z());
            MachineDefinition machine;
            int chunkX = Math.floorDiv(parts.x(), 16);
            int chunkZ = Math.floorDiv(parts.z(), 16);
            if (world.isChunkLoaded(chunkX, chunkZ)) {
                Block block = world.getBlockAt(parts.x(), parts.y(), parts.z());
                machine = reconcileLoadedRuntime(runtime, block);
                if (machine == null) continue;
            } else {
                // Nie ładujemy chunków tylko po to, żeby otworzyć menu miasta. Dla niezaładowanych
                // chunków ufamy runtime do czasu naturalnego ChunkLoadEvent, który wykona reconciliation.
                machine = registry.machines().get(runtime.machineId());
            }

            if (machine == null || !machine.energy().enabled()) continue;
            Optional<hex.towns.model.Town> town = minions.towns().townAt(location);
            if (town.isEmpty() || !town.get().id().equals(townId)) continue;
            devices++;
            if (machine.energy().generator()) generators++;
            else if (isEnergyConsumer(machine)) consumers++;
        }
        return new EnergyDeviceCounts(generators, consumers, devices);
    }

    private boolean isEnergyConsumer(MachineDefinition machine) {
        return machine != null && machine.energy().enabled() && !machine.energy().generator() && !isAccumulator(machine);
    }

    private List<String> loadedRuntimeKeysForBucket(int secondSlot) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : runtimeKeysByChunk.entrySet()) {
            ChunkKeyParts chunk = ChunkKeyParts.parse(entry.getKey());
            if (chunk == null || !isChunkLoaded(chunk.world(), chunk.x(), chunk.z())) continue;
            for (String key : new ArrayList<>(entry.getValue())) {
                if (Math.floorMod(key.hashCode(), 10) == secondSlot) keys.add(key);
            }
        }
        return keys;
    }

    private void enqueueOfflineCatchup(Block block, MachineDefinition machine, MachineRuntime runtime) {
        if (!offlineEnabled || block == null || machine == null || runtime == null) return;
        String key = runtime.blockKey();
        if (!queuedOfflineCatchupKeys.add(key)) return;
        offlineCatchupQueue.addLast(new OfflineCatchupJob(block.getLocation(), machine.id(), key));
    }

    private void drainOfflineCatchupQueue() {
        if (offlineCatchupQueue.isEmpty()) return;
        int processed = 0;
        while (processed++ < offlineMaxMachinesPerTick && !offlineCatchupQueue.isEmpty()) {
            OfflineCatchupJob job = offlineCatchupQueue.removeFirst();
            queuedOfflineCatchupKeys.remove(job.blockKey());
            Block block = job.location().getBlock();
            if (!isBlockLoaded(block)) continue;
            MachineRuntime runtime = runtimes.get(job.blockKey());
            MachineDefinition machine = runtime == null ? null : machineAt(block);
            if (runtime == null || machine == null) continue;
            runtime.machineId(machine.id());
            applyOfflineCatchup(block, machine, runtime);
        }
    }

    private void indexRuntime(MachineRuntime runtime) {
        LocationParts parts = LocationParts.parse(runtime == null ? null : runtime.blockKey());
        if (parts == null) return;
        runtimeKeysByChunk.computeIfAbsent(chunkKey(parts.world(), Math.floorDiv(parts.x(), 16), Math.floorDiv(parts.z(), 16)), ignored -> new HashSet<>()).add(runtime.blockKey());
    }

    private void deindexRuntime(MachineRuntime runtime) {
        LocationParts parts = LocationParts.parse(runtime == null ? null : runtime.blockKey());
        if (parts == null) return;
        String chunkKey = chunkKey(parts.world(), Math.floorDiv(parts.x(), 16), Math.floorDiv(parts.z(), 16));
        Set<String> keys = runtimeKeysByChunk.get(chunkKey);
        if (keys == null) return;
        keys.remove(runtime.blockKey());
        if (keys.isEmpty()) runtimeKeysByChunk.remove(chunkKey);
    }

    private boolean isChunkLoaded(String worldName, int chunkX, int chunkZ) {
        World world = Bukkit.getWorld(worldName);
        return world != null && world.isChunkLoaded(chunkX, chunkZ);
    }

    private boolean isBlockLoaded(Block block) {
        return block != null && block.getWorld() != null && block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    private static String chunkKey(String world, int chunkX, int chunkZ) {
        return world + ":" + chunkX + ":" + chunkZ;
    }

    private static ConfigurationSection energySection(org.bukkit.configuration.file.FileConfiguration config) {
        if (config == null) return null;
        ConfigurationSection s = config.getConfigurationSection("energy");
        if (s != null) return s;
        return config.getConfigurationSection("minions.energy");
    }

    private static int getInt(org.bukkit.configuration.file.FileConfiguration config, ConfigurationSection section, int def, String... keys) {
        if (section != null) {
            for (String key : keys) {
                if (!key.contains(".") && section.contains(key)) return section.getInt(key, def);
            }
        }
        if (config != null) {
            for (String key : keys) {
                if (config.contains(key)) return config.getInt(key, def);
            }
        }
        return def;
    }

    private static boolean getBoolean(org.bukkit.configuration.file.FileConfiguration config, ConfigurationSection section, boolean def, String... keys) {
        if (section != null) {
            for (String key : keys) {
                if (!key.contains(".") && section.contains(key)) return section.getBoolean(key, def);
            }
        }
        if (config != null) {
            for (String key : keys) {
                if (config.contains(key)) return config.getBoolean(key, def);
            }
        }
        return def;
    }

    private boolean isRuntimeInChunk(MachineRuntime runtime, String worldName, int chunkX, int chunkZ) {
        LocationParts parts = LocationParts.parse(runtime == null ? null : runtime.blockKey());
        return parts != null && parts.world().equals(worldName) && Math.floorDiv(parts.x(), 16) == chunkX && Math.floorDiv(parts.z(), 16) == chunkZ;
    }

    private boolean similarItem(ItemStack a, ItemStack b) {
        if (a == null || a.getType().isAir()) return b == null || b.getType().isAir();
        if (b == null || b.getType().isAir()) return false;
        return a.getAmount() == b.getAmount() && a.isSimilar(b);
    }

    private void ensureActiveStamp(MachineRuntime runtime, boolean dirty) {
        if (runtime == null) return;
        runtime.touchActiveNow();
        if (dirty) markDirty(runtime);
    }

    private Block blockFromKeyIfLoaded(String blockKey) {
        LocationParts parts = LocationParts.parse(blockKey);
        if (parts == null) return null;
        World world = Bukkit.getWorld(parts.world());
        if (world == null || !world.isChunkLoaded(Math.floorDiv(parts.x(), 16), Math.floorDiv(parts.z(), 16))) return null;
        return world.getBlockAt(parts.x(), parts.y(), parts.z());
    }

    public Block blockFromKey(String blockKey) {
        try {
            String[] parts = blockKey.split(":");
            if (parts.length != 4) return null;
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) return null;
            return world.getBlockAt(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (Exception ignored) { return null; }
    }


    public boolean isBronzeWrench(ItemStack item) {
        // Klucz z brązu jest zachowany w danych wyłącznie pod przyszły rozwój.
        return false;
    }

    public ItemStack createMachineItem(MachineDefinition machine) {
        if (machine == null || machine.specialItemId() == null || machine.specialItemId().isBlank()) return new ItemStack(Material.AIR);
        return minions.specialItems().createItem(machine.specialItemId(), 1);
    }

    public BlockFace accumulatorInputFace(Block block) {
        if (block == null) return BlockFace.WEST;
        MachineRuntime runtime = runtimes.get(key(block.getLocation()));
        if (runtime != null && runtime.accumulatorInputFace() != null && !runtime.accumulatorInputFace().isBlank()) {
            try { return BlockFace.valueOf(runtime.accumulatorInputFace()); } catch (IllegalArgumentException ignored) { }
        }
        return leftOf(block);
    }

    public void setAccumulatorInputFace(Block block, BlockFace face) {
        if (block == null || face == null) return;
        MachineDefinition machine = machineAt(block);
        if (machine == null || !"ACCUMULATOR".equalsIgnoreCase(machine.type())) return;
        MachineRuntime runtime = runtime(key(block.getLocation()), machine.id());
        runtime.accumulatorInputFace(face.name());
        markDirty(runtime);
        saveSoon();
        if (cableService != null) cableService.clearRouteCache();
    }

    public boolean hasConfigurablePorts(MachineDefinition machine) {
        return machine != null && "ACCUMULATOR".equalsIgnoreCase(machine.type());
    }

    public BlockFace leftOf(Block block) {
        return switch (facing(block)) {
            case NORTH -> BlockFace.WEST;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            case WEST -> BlockFace.SOUTH;
            default -> BlockFace.WEST;
        };
    }

    public BlockFace rightOf(Block block) {
        return switch (facing(block)) {
            case NORTH -> BlockFace.EAST;
            case SOUTH -> BlockFace.WEST;
            case EAST -> BlockFace.SOUTH;
            case WEST -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    public BlockFace facing(Block block) {
        if (block == null) return BlockFace.NORTH;
        BlockData data = block.getBlockData();
        if (data instanceof Directional directional) return directional.getFacing();
        return BlockFace.NORTH;
    }

    /**
     * Uzgadnia zapisany runtime z realnym blokiem w załadowanym chunku.
     * - brak maszyny => runtime jest osierocony i zostaje usunięty bez dropienia zawartości,
     * - inny typ maszyny => runtime dostaje aktualne machineId,
     * - poprawna maszyna => zwracamy definicję używaną przez tick/liczniki.
     */
    private MachineDefinition reconcileLoadedRuntime(MachineRuntime runtime, Block block) {
        if (runtime == null || block == null) return null;
        MachineDefinition actual = machineAt(block);
        if (actual == null) {
            // Runtime jest dowodem, że w tej dokładnej pozycji wcześniej stała maszyna. Usuwamy więc
            // również legacy BlockDisplay bez PDC zakotwiczone dokładnie w tym bloku; dzięki temu
            // stare wizualizacje nie zostają nawet wtedy, gdy po maszynie postawiono już zwykły blok.
            cleanupMachineVisuals(block.getLocation(), true);
            remove(runtime.blockKey(), null);
            return null;
        }

        // Never let a persisted machine become an active wilderness/orphan machine after restart.
        // Explicit ownership is authoritative; legacy runtimes may be migrated only when the exact
        // machine block currently belongs to one ACTIVE town. Otherwise this exact Hex-owned carrier
        // is quarantined/removed when its chunk is observed, without scanning the rest of the chunk.
        UUID runtimeTown = runtime.townUuid();
        if (runtimeTown != null) {
            if (towns.findTown(runtimeTown).isEmpty()) {
                processMachinePurgeTarget(runtime, runtimeTown, false);
                plugin.getLogger().warning("[MachineLifecycle] removed orphan machine runtime=" + runtime.blockKey() + " town=" + runtimeTown);
                return null;
            }
            Optional<hex.towns.model.Town> at = towns.townAt(block.getLocation());
            if (at.isEmpty() || !runtimeTown.equals(at.get().id())) {
                processMachinePurgeTarget(runtime, runtimeTown, false);
                plugin.getLogger().warning("[MachineLifecycle] removed machine outside owner claims runtime=" + runtime.blockKey() + " town=" + runtimeTown);
                return null;
            }
        } else {
            Optional<hex.towns.model.Town> at = towns.townAt(block.getLocation());
            if (at.isEmpty()) {
                processMachinePurgeTarget(runtime, null, true);
                plugin.getLogger().warning("[MachineLifecycle] removed legacy orphan machine runtime=" + runtime.blockKey());
                return null;
            }
            runtime.townUuid(at.get().id());
            markDirty(runtime);
            saveSoon();
        }

        if (!actual.id().equalsIgnoreCase(runtime.machineId())) {
            runtime.machineId(actual.id());
            markDirty(runtime);
            saveSoon();
        }
        return actual;
    }

    /**
     * Usuwa wizualizacje należące do konkretnego bloku maszyny. includeLegacy usuwa również
     * stare, całkowicie nieotagowane BlockDisplay utworzone przez wcześniejsze wersje pluginu,
     * ale tylko wtedy, gdy encja została zakotwiczona dokładnie w koordynatach bloku maszyny.
     */
    public int cleanupMachineVisuals(Location ownerLocation, boolean includeLegacy) {
        if (ownerLocation == null || ownerLocation.getWorld() == null) return 0;
        String ownerKey = key(ownerLocation);
        int removed = 0;
        Location center = ownerLocation.clone().add(0.5D, 0.5D, 0.5D);
        for (org.bukkit.entity.Entity entity : ownerLocation.getWorld().getNearbyEntities(center, 1.25D, 1.5D, 1.25D)) {
            if (!(entity instanceof BlockDisplay display)) continue;
            String taggedOwner = display.getPersistentDataContainer().get(machineVisualKey, PersistentDataType.STRING);
            boolean taggedMatch = ownerKey.equals(taggedOwner);
            boolean legacyMatch = includeLegacy
                    && taggedOwner == null
                    && display.getPersistentDataContainer().getKeys().isEmpty()
                    && display.getLocation().getBlockX() == ownerLocation.getBlockX()
                    && display.getLocation().getBlockY() == ownerLocation.getBlockY()
                    && display.getLocation().getBlockZ() == ownerLocation.getBlockZ();
            if (taggedMatch || legacyMatch) {
                display.remove();
                removed++;
            }
        }
        return removed;
    }

    /**
     * Self-healing wizualizacji przy ChunkLoad. Otagowany display bez istniejącej maszyny
     * zostaje usunięty. Jeżeli blok maszyny istnieje, ale runtime zniknął, runtime odtwarzamy.
     */
    private void cleanupOrphanMachineVisuals(Chunk chunk) {
        if (chunk == null) return;
        for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
            if (!(entity instanceof BlockDisplay display)) continue;
            String ownerKey = display.getPersistentDataContainer().get(machineVisualKey, PersistentDataType.STRING);
            if (ownerKey == null || ownerKey.isBlank()) continue;
            LocationParts parts = LocationParts.parse(ownerKey);
            if (parts == null || !parts.world().equals(chunk.getWorld().getName())) {
                display.remove();
                continue;
            }
            if (Math.floorDiv(parts.x(), 16) != chunk.getX() || Math.floorDiv(parts.z(), 16) != chunk.getZ()) continue;
            Block ownerBlock = chunk.getWorld().getBlockAt(parts.x(), parts.y(), parts.z());
            MachineDefinition actual = machineAt(ownerBlock);
            if (actual == null) {
                display.remove();
                continue;
            }
            Optional<hex.towns.model.Town> activeTown = towns.townAt(ownerBlock.getLocation());
            if (activeTown.isEmpty()) {
                // The exact display anchor plus Hex special-block marker is strong ownership proof.
                // Do not resurrect this machine in wilderness after its town disappeared.
                cleanupMachineVisuals(ownerBlock.getLocation(), true);
                minions.specialItems().unmarkSpecialBlock(ownerBlock);
                ownerBlock.setType(Material.AIR, false);
                continue;
            }
            if (!runtimes.containsKey(ownerKey)) runtime(ownerKey, actual.id());
        }
    }

    private UUID resolveTownForBlockKey(String blockKey) {
        if (towns == null) return null;
        LocationParts parts = LocationParts.parse(blockKey);
        if (parts == null) return null;
        World world = Bukkit.getWorld(parts.world());
        if (world == null) return null;
        return towns.townAt(new Location(world, parts.x(), parts.y(), parts.z())).map(town -> town.id()).orElse(null);
    }

    public String key(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    public void markRuntimeDirty(MachineRuntime runtime) { markDirty(runtime); }

    private void markDirty(MachineRuntime runtime) {
        if (runtime == null || isRuntimeFenced(runtime)) return;
        dirtyRuntimeKeys.add(runtime.blockKey());
    }

    private boolean isRuntimeFenced(MachineRuntime runtime) {
        if (runtime == null) return false;
        if (runtime.townUuid() != null) return purgingTowns.contains(runtime.townUuid());
        LocationParts parts = LocationParts.parse(runtime.blockKey());
        if (parts == null) return false;
        return purgingMachineChunks.contains(parts.world() + ":" + Math.floorDiv(parts.x(), 16) + ":" + Math.floorDiv(parts.z(), 16));
    }

    private void loadAsync() {
        hex.db().async(() -> {
            repository.ensureTables();
            return repository.loadAll();
        }).thenAccept(loaded -> Bukkit.getScheduler().runTask(plugin, () -> {
            runtimes.clear();
            runtimeKeysByChunk.clear();
            dirtyRuntimeKeys.clear();
            deletedRuntimeKeys.clear();
            for (MachineRuntime runtime : loaded) {
                if (runtime != null && runtime.blockKey() != null && !runtime.blockKey().isBlank()) {
                    if (runtime.townUuid() == null) {
                        UUID owner = resolveTownForBlockKey(runtime.blockKey());
                        if (owner != null) {
                            runtime.townUuid(owner);
                            dirtyRuntimeKeys.add(runtime.blockKey());
                        }
                    }
                    if (isRuntimeFenced(runtime)) continue;
                    runtimes.put(runtime.blockKey(), runtime);
                    indexRuntime(runtime);
                }
            }
            dbReady = true;
            if (!dirtyRuntimeKeys.isEmpty()) saveSoon();
            // ChunkLoadEvent mógł wystąpić zanim async load runtime'ów się zakończył. Uzgadniamy
            // więc również wszystkie chunki już załadowane w momencie gotowości bazy.
            for (World world : Bukkit.getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) observeChunk(chunk);
            }
            plugin.getLogger().info("HexMinions machine runtimes loaded=" + runtimes.size());
        })).exceptionally(ex -> {
            plugin.getLogger().severe("Nie udało się wczytać runtime maszyn: " + rootMessage(ex));
            dbReady = true;
            return null;
        });
    }

    public void saveSoon() {
        saveCountdown = Math.max(saveCountdown, saveIntervalTicks - 20);
    }

    public void flushDirtyAsync() {
        if (flushInFlight || (dirtyRuntimeKeys.isEmpty() && deletedRuntimeKeys.isEmpty())) return;
        List<MachineRuntime> toSave = new ArrayList<>();
        List<String> toDelete = new ArrayList<>();
        for (String key : new ArrayList<>(deletedRuntimeKeys)) {
            if (toDelete.size() >= saveMaxRows) break;
            if (deletedRuntimeKeys.remove(key)) toDelete.add(key);
        }
        for (String key : new ArrayList<>(dirtyRuntimeKeys)) {
            if (toSave.size() >= saveMaxRows) break;
            MachineRuntime runtime = runtimes.get(key);
            if (runtime != null && isRuntimeFenced(runtime)) {
                dirtyRuntimeKeys.remove(key);
                continue;
            }
            if (runtime != null && dirtyRuntimeKeys.remove(key)) toSave.add(runtime);
        }
        if (toSave.isEmpty() && toDelete.isEmpty()) {
            saveCountdown = 0;
            return;
        }
        saveCountdown = 0;
        flushInFlight = true;
        java.util.concurrent.CompletableFuture<Void> marker = new java.util.concurrent.CompletableFuture<>();
        currentFlush = marker;
        hex.db().asyncRun(() -> {
            repository.deleteBatch(toDelete);
            repository.saveBatch(toSave);
        }).whenComplete((ok, error) -> {
            if (error == null) marker.complete(null); else marker.completeExceptionally(error);
            Bukkit.getScheduler().runTask(plugin, () -> {
                flushInFlight = false;
                if (error != null) {
                    for (MachineRuntime runtime : toSave) {
                        if (!isRuntimeFenced(runtime)) dirtyRuntimeKeys.add(runtime.blockKey());
                    }
                    deletedRuntimeKeys.addAll(toDelete);
                    plugin.getLogger().warning("Nie udało się zapisać batcha runtime maszyn: " + rootMessage(error));
                }
                if (!dirtyRuntimeKeys.isEmpty() || !deletedRuntimeKeys.isEmpty()) saveCountdown = saveIntervalTicks;
            });
        });
    }

    private java.util.concurrent.CompletableFuture<Void> awaitCurrentFlush() {
        java.util.concurrent.CompletableFuture<Void> snapshot = currentFlush;
        return snapshot.handle((ignored, error) -> {
            if (error != null) plugin.getLogger().warning("[TownCleanup] machine in-flight flush failed before final purge: " + rootMessage(error));
            return null;
        });
    }

    public void saveNow() {
        // Przy wyłączaniu zapisujemy pełny snapshot istniejącego runtime maszyn, nie tylko dirty-set.
        // Dzięki temu last_active_at, bufory EU i postęp procesów nie tracą kilku ostatnich sekund po restarcie.
        List<MachineRuntime> toSave = runtimes.values().stream()
                .filter(runtime -> runtime == null || !isRuntimeFenced(runtime))
                .toList();
        for (MachineRuntime runtime : toSave) {
            if (runtime != null && runtime.lastActiveAtMillis() <= 0L) runtime.touchActiveNow();
        }
        List<String> toDelete = new ArrayList<>(deletedRuntimeKeys);
        dirtyRuntimeKeys.clear();
        deletedRuntimeKeys.clear();
        saveCountdown = 0;
        try {
            repository.deleteBatch(toDelete);
            repository.saveBatch(toSave);
        } catch (Throwable exception) {
            plugin.getLogger().warning("Nie udało się zapisać runtime maszyn przy wyłączaniu: " + rootMessage(exception));
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable t = throwable;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private record Match(MachineRecipe recipe, int inputIndex) {}
    private record ElectricFurnaceJob(String recipeId, int processSlot, int primaryInputSlot, int secondaryInputSlot, int primaryAmount, int secondaryAmount, ItemStack output, int timeSeconds, int preferredOutputIndex) {}
    private record ElectricVanillaRecipe(String key, ItemStack output, int timeSeconds) {}
    private record EnergyDeviceCounts(int generators, int consumers, int devices) {}
    private record OfflineCatchupJob(Location location, String machineId, String blockKey) {}

    private record ChunkKeyParts(String world, int x, int z) {
        static ChunkKeyParts parse(String key) {
            try {
                if (key == null || key.isBlank()) return null;
                String[] parts = key.split(":");
                if (parts.length != 3) return null;
                return new ChunkKeyParts(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            } catch (Exception ignored) { return null; }
        }
    }

    private record LocationParts(String world, int x, int y, int z) {
        static LocationParts parse(String blockKey) {
            try {
                if (blockKey == null || blockKey.isBlank()) return null;
                String[] parts = blockKey.split(":");
                if (parts.length != 4) return null;
                return new LocationParts(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
            } catch (Exception ignored) { return null; }
        }
    }

    private record FuelValue(int eu, int burnSeconds) {}
}
