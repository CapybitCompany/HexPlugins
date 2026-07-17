package hex.minions.machine;

import hex.core.api.HexApi;
import hex.collections.api.CollectionProgressContext;
import hex.collections.api.CollectionSource;
import hex.minions.service.MinionService;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.Plugin;
import hex.towns.model.ChunkPos;

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
    private final MachineRegistry registry;
    private final MachineRuntimeRepository repository;
    private final NamespacedKey machineMenuGuideKey;
    private final NamespacedKey recipeFuelUsesKey;
    private CableService cableService;
    private final Map<String, MachineRuntime> runtimes = new LinkedHashMap<>();
    private final Map<String, Set<String>> runtimeKeysByChunk = new LinkedHashMap<>();
    private final Set<String> dirtyRuntimeKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> deletedRuntimeKeys = ConcurrentHashMap.newKeySet();
    private final Deque<OfflineCatchupJob> offlineCatchupQueue = new ArrayDeque<>();
    private final Set<String> queuedOfflineCatchupKeys = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> pendingEnergyByTown = new LinkedHashMap<>();
    private final Map<UUID, Location> pendingEnergyLocationByTown = new LinkedHashMap<>();
    private final int saveIntervalTicks;
    private final int saveMaxRows;
    private int tickCursor;
    private int saveCountdown;
    private int collectionFlushCountdownTicks;
    private int taskId = -1;
    private volatile boolean dbReady;
    private volatile boolean flushInFlight;
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

    public MachineService(Plugin plugin, HexApi hex, MinionService minions) {
        this.plugin = plugin;
        this.hex = hex;
        this.minions = minions;
        this.registry = MachineRegistry.load(plugin);
        this.repository = new MachineRuntimeRepository(hex.db().db());
        this.machineMenuGuideKey = new NamespacedKey(plugin, "machine_menu_guide");
        this.recipeFuelUsesKey = new NamespacedKey(plugin, "machine_recipe_fuel_uses_remaining");
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
        this.maxEnergyDevicesPerTown = Math.max(0, getInt(pluginConfig, energy, 64, "max_energy_devices_per_town", "max-energy-devices-per-town"));
        loadAsync();
    }

    public MachineRegistry registry() { return registry; }
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
        runtime.touchActiveNow();
        runtimes.put(blockKey, runtime);
        indexRuntime(runtime);
        markDirty(runtime);
        return runtime;
    }

    public Optional<MachineRuntime> runtime(String blockKey) { return Optional.ofNullable(runtimes.get(blockKey)); }

    public void remove(String blockKey, Location dropLocation) {
        MachineRuntime removed = runtimes.remove(blockKey);
        if (removed != null && dropLocation != null) removed.drop(dropLocation);
        if (removed != null) {
            deindexRuntime(removed);
            dirtyRuntimeKeys.remove(blockKey);
            deletedRuntimeKeys.add(blockKey);
        }
        saveSoon();
    }

    public void observeChunk(Chunk chunk) {
        if (chunk == null || !dbReady) return;
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
            MachineDefinition machine = block == null ? null : machineAt(block);
            if (machine == null) continue;
            runtime.machineId(machine.id());
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
        if (worldName == null || chunks == null || chunks.isEmpty()) return;
        java.util.Set<String> chunkKeys = new java.util.HashSet<>();
        for (ChunkPos chunk : chunks) chunkKeys.add(worldName + ":" + chunk.x() + ":" + chunk.z());
        for (String blockKey : new ArrayList<>(runtimes.keySet())) {
            LocationParts parts = LocationParts.parse(blockKey);
            if (parts == null || !parts.world().equals(worldName)) continue;
            String chunkKey = parts.world() + ":" + Math.floorDiv(parts.x(), 16) + ":" + Math.floorDiv(parts.z(), 16);
            if (chunkKeys.contains(chunkKey)) {
                MachineRuntime removed = runtimes.remove(blockKey);
                if (removed != null) deindexRuntime(removed);
                dirtyRuntimeKeys.remove(blockKey);
                deletedRuntimeKeys.add(blockKey);
            }
        }
        if (!deletedRuntimeKeys.isEmpty()) saveSoon();
    }

    public MachineDefinition machineAt(Block block) {
        if (block == null) return null;
        return minions.specialItems().readSpecialBlockId(block).flatMap(registry::byStation).orElse(null);
    }

    public void syncFromInventory(MachineDefinition machine, MachineRuntime runtime, Inventory inv) {
        if (machine == null || runtime == null || inv == null) return;
        syncInputsFromInventory(machine, runtime, inv);
        runtime.secondary(machine.hasSecondarySlot() ? inv.getItem(machine.secondarySlot()) : null);
        runtime.fuel((machine.energy().enabled() || machine.hasRecipeFuelSlot()) ? inv.getItem(machine.fuelSlot()) : null);
        syncOutputsFromInventory(machine, runtime, inv);
        List<Integer> upgrades = upgradeSlots(machine);
        for (int i = 0; i < Math.min(3, upgrades.size()); i++) runtime.upgrade(i, inv.getItem(upgrades.get(i)));
        markDirty(runtime);
        saveSoon();
    }

    public void syncToInventory(MachineDefinition machine, MachineRuntime runtime, Inventory inv) {
        if (machine == null || runtime == null || inv == null) return;
        syncInputsToInventory(machine, runtime, inv);
        if (machine.hasSecondarySlot()) inv.setItem(machine.secondarySlot(), runtime.secondary());
        if (machine.energy().enabled() || machine.hasRecipeFuelSlot()) inv.setItem(machine.fuelSlot(), runtime.fuel());
        syncOutputsToInventory(machine, runtime, inv);
        List<Integer> upgrades = upgradeSlots(machine);
        for (int i = 0; i < Math.min(3, upgrades.size()); i++) inv.setItem(upgrades.get(i), runtime.upgrade(i));
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
        List<Integer> slots = machine.outputSlots();
        runtime.output(slots.size() > 0 ? inv.getItem(slots.get(0)) : null);
        runtime.output2(slots.size() > 1 ? inv.getItem(slots.get(1)) : null);
    }

    private void syncOutputsToInventory(MachineDefinition machine, MachineRuntime runtime, Inventory inv) {
        if (machine == null || runtime == null || inv == null) return;
        List<Integer> slots = machine.outputSlots();
        if (slots.size() > 0) inv.setItem(slots.get(0), runtime.output());
        if (slots.size() > 1) inv.setItem(slots.get(1), runtime.outputAt(1));
    }

    private boolean usesProcessingInputs(MachineDefinition machine) {
        return machine != null && (!machine.recipes().isEmpty() || isElectricFurnace(machine));
    }

    public List<Integer> upgradeSlots(MachineDefinition machine) {
        if (machine == null || machine.upgradeSlots().isEmpty()) return List.of(38, 40, 42);
        return machine.upgradeSlots();
    }

    public boolean isEditableSlot(MachineDefinition machine, int slot) {
        if (machine == null) return false;
        if (machine.outputSlots().contains(slot) || slot == machine.arrowSlot()) return false;
        if (usesProcessingInputs(machine) && machine.inputSlots().contains(slot)) return true;
        if (slot == machine.secondarySlot() && machine.hasSecondarySlot()) return true;
        if (slot == machine.fuelSlot() && (machine.energy().enabled() || machine.hasRecipeFuelSlot())) return true;
        if (slot == machine.energy().batterySlot()) return true;
        return upgradeSlots(machine).contains(slot);
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
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (recipe.outputCustomModelData() > 0) meta.setCustomModelData(recipe.outputCustomModelData());
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

    public boolean collectOutput(MachineRuntime runtime, org.bukkit.entity.Player player) {
        return collectOutput(runtime, 0, player);
    }

    public boolean collectOutput(MachineRuntime runtime, int outputIndex, org.bukkit.entity.Player player) {
        ItemStack output = runtime.outputAt(outputIndex);
        if (output == null || output.getType().isAir()) return false;
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
            MachineDefinition machine = block == null ? null : machineAt(block);
            if (machine == null || !machine.energy().enabled() || !machine.energy().generator()) continue;
            runtime.machineId(machine.id());
            int generated = tickGeneratorEnergy(block, machine, runtime);
            if (generated > 0) recordEnergyGenerated(block, generated);
            distributeEnergy(block, machine, runtime);
            ensureActiveStamp(runtime, false);
        }
    }

    private void tickConsumers(int secondSlot) {
        for (String key : loadedRuntimeKeysForBucket(secondSlot)) {
            MachineRuntime runtime = runtimes.get(key);
            if (runtime == null) continue;
            Block block = blockFromKeyIfLoaded(runtime.blockKey());
            MachineDefinition machine = block == null ? null : machineAt(block);
            if (machine == null || machine.energy().generator()) continue;
            runtime.machineId(machine.id());
            if (isAccumulator(machine)) {
                distributeAccumulatorEnergy(block, machine, runtime);
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
                int generated = tickGeneratorEnergy(block, machine, runtime);
                if (generated > 0) recordEnergyGenerated(block, generated);
                // Nie skanujemy/transferujemy tras kabli dla każdej sekundy offline.
                // Co 10 uproszczonych kroków robimy jeden transfer, a finalny transfer po pętli.
                if (i % 10 == 9) distributeEnergy(block, machine, runtime);
            } else if (isAccumulator(machine)) {
                distributeAccumulatorEnergy(block, machine, runtime);
            } else {
                if (machine.energy().enabled()) tickFuel(machine, runtime, false);
                if (isElectricFurnace(machine)) tickElectricFurnace(block, machine, runtime);
                else if (isExternalOutputProcessor(machine)) tickExternalOutputProcessor(block, machine, runtime);
                else tickProcess(machine, runtime);
            }
        }
        if (generatorCatchup) distributeEnergy(block, machine, runtime);
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
            if (!hasFullSunlight(block)) return 0;
            int before = runtime.energy();
            int capacity = runtime.capacity(machine);
            int amount = Math.max(0, machine.energy().euPerSecond());
            if (capacity <= 0 || amount <= 0 || before >= capacity) return 0;
            runtime.addEnergy(amount, capacity);
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
        int capacity = runtime.capacity(machine);
        if (capacity <= 0) return;
        int beforeEnergy = runtime.energy();
        int beforeBurn = runtime.burnRemainingSeconds();
        runtime.burnTick(capacity);
        if (beforeEnergy != runtime.energy() || beforeBurn != runtime.burnRemainingSeconds()) markDirty(runtime);
        if (runtime.energy() >= capacity || runtime.burnRemainingSeconds() > 0) return;
        FuelValue value = fuelValue(machine, runtime.fuel(), generatorFuel);
        if (value.eu <= 0) return;
        runtime.consumeFuelItem();
        runtime.startBurn(value.eu, value.burnSeconds);
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
        if (!runtime.consumeEnergy(Math.max(0, machine.energy().euPerSecond()))) return;
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
            int cost = Math.max(0, machine.energy().euPerSecond());
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
        if (storageBlock.getType() == Material.AIR && create) storageBlock.setType(Material.CHEST, false);
        if (!(storageBlock.getState() instanceof Chest chest)) return null;
        return chest;
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
            case "iron_uranium_chest" -> 5;
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
        List<Integer> slots = upgradeSlots(machine);
        return !slots.isEmpty() && (slot == slots.get(0) || (slots.size() > 1 && slot == slots.get(1)));
    }

    public boolean isValidExternalStorageItem(ItemStack item) {
        return storageSlotsFromItem(item) > 0;
    }

    public boolean canInstallExternalStorageAt(Block machineBlock, MachineDefinition machine, int menuSlot, ItemStack item) {
        if (machineBlock == null || machine == null || !isExternalStorageUpgradeSlot(machine, menuSlot)) return false;
        if (item == null || item.getType().isAir()) return true;
        if (!isValidExternalStorageItem(item)) return false;
        List<Integer> slots = upgradeSlots(machine);
        BlockFace face = menuSlot == slots.get(0) ? BlockFace.UP : BlockFace.DOWN;
        Block storageBlock = machineBlock.getRelative(face);
        return storageBlock.getType().isAir() || storageBlock.getState() instanceof Chest;
    }

    public void ensureExternalStorage(Block block, MachineDefinition machine, MachineRuntime runtime) {
        if (!hasExternalStorage(machine)) return;
        ensureElectricFurnaceStorage(block, runtime);
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

    private boolean isExternalOutputProcessor(MachineDefinition machine) {
        return isElectricComposter(machine) || isMeatRefinery(machine);
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
            int cost = Math.max(0, machine.energy().euPerSecond());
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
        if (runtime == null || recipe == null || minions.collections() == null) return;
        String specialItem = recipe.outputSpecialItem() == null ? "" : recipe.outputSpecialItem().toLowerCase(java.util.Locale.ROOT);
        String collectionId = switch (specialItem) {
            case "enriched_uranium" -> "industrial.enriched_uranium";
            default -> "";
        };
        if (collectionId.isBlank()) return;
        Block block = blockFromKey(runtime.blockKey());
        if (block == null) return;
        Location location = block.getLocation();
        try {
            minions.towns().townAt(location).ifPresent(town -> minions.collections().addProgress(new CollectionProgressContext()
                    .townId(town.id())
                    .collectionId(collectionId)
                    .amount(Math.max(1, recipe.outputAmount()))
                    .source(CollectionSource.CUSTOM_PLUGIN_GRANTED)
                    .location(location)
                    .reason("machine.output." + specialItem)));
        } catch (Throwable ignored) {
        }
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
            MachineRuntime consumerRuntime = runtime(key(target.getLocation()), consumer.id());
            int need = Math.max(0, consumerRuntime.capacity(consumer) - consumerRuntime.energy());
            int move = Math.min(Math.min(accumulator.energy().transferPerSecond(), need), accumulatorRuntime.energy());
            if (move <= 0) continue;
            accumulatorRuntime.energy(accumulatorRuntime.energy() - move);
            consumerRuntime.addEnergy(move, consumerRuntime.capacity(consumer));
            markDirty(accumulatorRuntime);
            markDirty(consumerRuntime);
        }
    }

    private void distributeEnergy(Block generatorBlock, MachineDefinition generator, MachineRuntime generatorRuntime) {
        if (cableService != null) cableService.transferFromGenerator(generatorBlock, generator, generatorRuntime);
        for (BlockFace face : List.of(leftOf(generatorBlock), rightOf(generatorBlock))) {
            Block target = generatorBlock.getRelative(face);
            MachineDefinition consumer = machineAt(target);
            if (consumer == null || !consumer.energy().enabled() || consumer.energy().generator()) continue;
            MachineRuntime consumerRuntime = runtime(key(target.getLocation()), consumer.id());
            int need = Math.max(0, consumerRuntime.capacity(consumer) - consumerRuntime.energy());
            int move = Math.min(Math.min(generator.energy().transferPerSecond(), need), generatorRuntime.energy());
            if (move <= 0) continue;
            generatorRuntime.energy(generatorRuntime.energy() - move);
            consumerRuntime.addEnergy(move, consumerRuntime.capacity(consumer));
            markDirty(generatorRuntime);
            markDirty(consumerRuntime);
        }
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
            inv.setItem(machine.arrowSlot(), progressItem(machine, runtime));
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
        for (int inputSlot : machine.inputSlots()) {
            setGuide(inv, inputSlot, Material.GREEN_STAINED_GLASS_PANE, "§aInput materiału", "§7Włóż tutaj materiał do przetworzenia.");
        }
        if (machine.hasSecondarySlot()) {
            setGuide(inv, machine.secondarySlot(), Material.GREEN_STAINED_GLASS_PANE, "§aDrugi input", "§7Włóż tutaj drugi składnik receptury.");
        }
        for (int outputSlot : machine.outputSlots()) {
            setGuide(inv, outputSlot, Material.YELLOW_STAINED_GLASS_PANE, "§eRezultat", "§7Tutaj pojawi się wynik pracy maszyny.");
        }
        if (machine.energy().enabled() || machine.hasRecipeFuelSlot()) {
            boolean emergencyPower = machine.energy().enabled() && !machine.energy().generator() && !machine.hasRecipeFuelSlot();
            if (emergencyPower) {
                setGuide(inv, machine.fuelSlot(), Material.RED_STAINED_GLASS_PANE, "§cAwaryjne zasilanie", "§7Włóż redstone lub inne awaryjne paliwo EU.");
            } else {
                setGuide(inv, machine.fuelSlot(), Material.BLUE_STAINED_GLASS_PANE, "§9Paliwo", "§7Włóż tutaj paliwo, np. węgiel dla generatora lub pieca.");
            }
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
        if (recipe == null) return named(Material.ARROW, "§ePostęp", "§7Brak pasującej receptury albo pełny output.");
        int percent = Math.min(100, runtime.progressSeconds() * 100 / Math.max(1, recipe.timeSeconds()));
        String eu = machine.energy().enabled() ? "\n§7Energia: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU" : "";
        return named(Material.ARROW, "§ePostęp: §f" + percent + "%", "§7Czas: §f" + runtime.progressSeconds() + "§7/§f" + recipe.timeSeconds() + "s" + eu);
    }

    private ItemStack externalOutputProgressItem(MachineDefinition machine, MachineRuntime runtime) {
        String eu = "§7Energia: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU";
        String recipeId = runtime.recipeId();
        if (recipeId == null || recipeId.isBlank()) {
            return named(Material.ARROW, "§ePostęp", eu + "\n§7Status: §8oczekuje na poprawny input, miejsce na output albo energię\n§7Zużycie przy pracy: §f" + machine.energy().euPerSecond() + " EU/s");
        }
        int total = machine.recipes().stream()
                .filter(recipe -> recipeId.startsWith(recipe.id() + "@"))
                .findFirst()
                .map(MachineRecipe::timeSeconds)
                .orElse(30);
        int progress = Math.min(total, runtime.progressSeconds());
        int percent = Math.min(100, progress * 100 / Math.max(1, total));
        return named(Material.ARROW, "§ePostęp: §f" + percent + "%", eu + "\n§7Czas: §f" + progress + "§7/§f" + total + "s\n§7Zużycie przy pracy: §f" + machine.energy().euPerSecond() + " EU/s");
    }

    private ItemStack electricFurnaceProgressItem(MachineDefinition machine, MachineRuntime runtime) {
        String eu = "§7Energia: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU";
        String slot1 = electricProgressLine(runtime, 0);
        String slot2 = electricProgressLine(runtime, 1);
        return named(Material.ARROW, "§ePostęp pieca elektrycznego", eu + "\n" + slot1 + "\n" + slot2 + "\n§7Zużycie przy pracy: §f" + machine.energy().euPerSecond() + " EU/s");
    }

    private String electricProgressLine(MachineRuntime runtime, int slot) {
        String recipe = runtime.recipeIdAt(slot);
        if (recipe == null || recipe.isBlank()) return "§7Slot " + (slot + 1) + ": §8oczekuje";
        int total = electricFurnaceDefaultTimeSeconds();
        int progress = Math.min(total, runtime.progressSecondsAt(slot));
        int percent = Math.min(100, progress * 100 / Math.max(1, total));
        return "§7Slot " + (slot + 1) + ": §f" + percent + "% §8(" + progress + "/" + total + "s)";
    }

    private ItemStack machineInfoItem(MachineDefinition machine, MachineRuntime runtime) {
        if (!machine.energy().enabled()) return named(Material.BOOK, machine.displayName(), "§7Maszyna konfigurowalna w machines.yml");
        if (machine.energy().generator()) {
            if ("SOLAR_PANEL_GENERATOR".equalsIgnoreCase(machine.type()) || "SOLAR_GENERATOR".equalsIgnoreCase(machine.type())) {
                return named(Material.DAYLIGHT_DETECTOR, machine.displayName(),
                        "§7Bufor: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU\n" +
                                "§7Produkcja: §f" + machine.energy().euPerSecond() + " EU/s przy świetle 15\n" +
                                "§7Zasila: §flewo, potem prawo");
            }
            return named(Material.REDSTONE_BLOCK, machine.displayName(),
                    "§7Bufor: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU\n" +
                            "§7Spalanie: §f" + runtime.burnRemainingSeconds() + "s\n" +
                            "§7Zasila: §flewo, potem prawo");
        }
        if ("ELECTRIC_FURNACE".equalsIgnoreCase(machine.type())) {
            return named(Material.FURNACE, machine.displayName(),
                    "§7Bufor: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU\n" +
                            "§7Zużycie: §f" + machine.energy().euPerSecond() + " EU/s tylko podczas przepalania\n" +
                            "§7Inputy: §f2 niezależne sloty jak dwa piece\n" +
                            "§7Outputy: §f2 sloty + priorytet skrzyni pod piecem\n" +
                            "§7Paliwo awaryjne: §fredstone/blok redstone");
        }
        if ("ELECTRIC_MILL".equalsIgnoreCase(machine.type())) {
            return named(Material.COMPOSTER, machine.displayName(),
                    "§7Bufor: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU\n" +
                            "§7Zużycie: §f" + machine.energy().euPerSecond() + " EU/s podczas pracy\n" +
                            "§7Input: §f1 slot + opcjonalna skrzynia nad kompostorem\n" +
                            "§7Output: §f1 slot + priorytet skrzyni pod kompostorem\n" +
                            "§7Procesy i szanse: §fmachines.yml");
        }
        if ("MEAT_REFINERY".equalsIgnoreCase(machine.type())) {
            return named(Material.STONECUTTER, machine.displayName(),
                    "§7Bufor: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU\n" +
                            "§7Zużycie: §f" + machine.energy().euPerSecond() + " EU/s podczas pracy\n" +
                            "§7Input: §f1 slot + opcjonalna skrzynia nad rafinatorem\n" +
                            "§7Output: §f1 slot + priorytet skrzyni pod rafinatorem\n" +
                            "§7Procesy i szanse: §fmachines.yml");
        }
        return named(Material.REDSTONE, machine.displayName(),
                "§7Bufor: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU\n" +
                        "§7Zużycie: §f" + machine.energy().euPerSecond() + " EU/s\n" +
                        "§7Awaryjne paliwo: §fredstone/blok redstone");
    }

    private ItemStack named(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            if (lore != null && !lore.isBlank()) meta.lore(java.util.Arrays.stream(lore.split("\\n")).map(Component::text).toList());
            item.setItemMeta(meta);
        }
        return item;
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

    private EnergyDeviceCounts energyDeviceCounts(UUID townId) {
        int generators = 0;
        int consumers = 0;
        int devices = 0;
        for (MachineRuntime runtime : runtimes.values()) {
            if (runtime == null || runtime.blockKey() == null) continue;
            MachineDefinition machine = registry.machines().get(runtime.machineId());
            if (machine == null || !machine.energy().enabled()) continue;
            Block block = blockFromKey(runtime.blockKey());
            if (block == null) continue;
            Optional<hex.towns.model.Town> town = minions.towns().townAt(block.getLocation());
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
        return minions.specialItems().readSpecialItemId(item).map(id -> id.equalsIgnoreCase("bronze_wrench")).orElse(false);
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

    public String key(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    public void markRuntimeDirty(MachineRuntime runtime) { markDirty(runtime); }

    private void markDirty(MachineRuntime runtime) {
        if (runtime == null) return;
        dirtyRuntimeKeys.add(runtime.blockKey());
    }

    private void loadAsync() {
        hex.db().async(() -> {
            repository.ensureTables();
            return repository.loadAll();
        }).thenAccept(loaded -> Bukkit.getScheduler().runTask(plugin, () -> {
            runtimes.clear();
            runtimeKeysByChunk.clear();
            for (MachineRuntime runtime : loaded) {
                if (runtime != null && runtime.blockKey() != null && !runtime.blockKey().isBlank()) {
                    runtimes.put(runtime.blockKey(), runtime);
                    indexRuntime(runtime);
                }
            }
            dirtyRuntimeKeys.clear();
            deletedRuntimeKeys.clear();
            dbReady = true;
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
            if (runtime != null && dirtyRuntimeKeys.remove(key)) toSave.add(runtime);
        }
        if (toSave.isEmpty() && toDelete.isEmpty()) {
            saveCountdown = 0;
            return;
        }
        saveCountdown = 0;
        flushInFlight = true;
        hex.db().asyncRun(() -> {
            repository.deleteBatch(toDelete);
            repository.saveBatch(toSave);
        }).whenComplete((ok, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            flushInFlight = false;
            if (error != null) {
                for (MachineRuntime runtime : toSave) dirtyRuntimeKeys.add(runtime.blockKey());
                deletedRuntimeKeys.addAll(toDelete);
                plugin.getLogger().warning("Nie udało się zapisać batcha runtime maszyn: " + rootMessage(error));
            }
            if (!dirtyRuntimeKeys.isEmpty() || !deletedRuntimeKeys.isEmpty()) saveCountdown = saveIntervalTicks;
        }));
    }

    public void saveNow() {
        // Przy wyłączaniu zapisujemy pełny snapshot istniejącego runtime maszyn, nie tylko dirty-set.
        // Dzięki temu last_active_at, bufory EU i postęp procesów nie tracą kilku ostatnich sekund po restarcie.
        List<MachineRuntime> toSave = new ArrayList<>(runtimes.values());
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
