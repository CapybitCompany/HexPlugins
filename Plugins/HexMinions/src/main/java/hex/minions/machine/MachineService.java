package hex.minions.machine;

import hex.core.api.HexApi;
import hex.minions.service.MinionService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import hex.towns.model.ChunkPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class MachineService {
    private final Plugin plugin;
    private final HexApi hex;
    private final MinionService minions;
    private final MachineRegistry registry;
    private final MachineRuntimeRepository repository;
    private final Map<String, MachineRuntime> runtimes = new LinkedHashMap<>();
    private final Set<String> dirtyRuntimeKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> deletedRuntimeKeys = ConcurrentHashMap.newKeySet();
    private final int saveIntervalTicks;
    private final int saveMaxRows;
    private int tickCursor;
    private int saveCountdown;
    private int taskId = -1;
    private volatile boolean dbReady;
    private volatile boolean flushInFlight;

    public MachineService(Plugin plugin, HexApi hex, MinionService minions) {
        this.plugin = plugin;
        this.hex = hex;
        this.minions = minions;
        this.registry = MachineRegistry.load(plugin);
        this.repository = new MachineRuntimeRepository(hex.db().db());
        org.bukkit.configuration.file.FileConfiguration pluginConfig = plugin instanceof org.bukkit.plugin.java.JavaPlugin javaPlugin ? javaPlugin.getConfig() : null;
        this.saveIntervalTicks = Math.max(20, pluginConfig == null ? 200 : pluginConfig.getInt("minions.machines.persistence.flush-interval-ticks", 200));
        this.saveMaxRows = Math.max(25, pluginConfig == null ? 500 : pluginConfig.getInt("minions.machines.persistence.max-rows-per-flush", 500));
        loadAsync();
    }

    public MachineRegistry registry() { return registry; }
    public Collection<MachineRuntime> runtimes() { return List.copyOf(runtimes.values()); }
    public boolean dbReady() { return dbReady; }

    public void start() {
        if (taskId != -1) return;
        taskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L).getTaskId();
    }

    public void shutdown() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        taskId = -1;
        saveNow();
    }

    public MachineRuntime runtime(String blockKey, String machineId) {
        MachineRuntime existing = runtimes.get(blockKey);
        if (existing != null) {
            if (machineId != null && !machineId.isBlank() && !machineId.equalsIgnoreCase(existing.machineId())) {
                existing.machineId(machineId);
                markDirty(existing);
            }
            return existing;
        }
        MachineRuntime runtime = new MachineRuntime(blockKey, machineId);
        runtimes.put(blockKey, runtime);
        markDirty(runtime);
        return runtime;
    }

    public Optional<MachineRuntime> runtime(String blockKey) { return Optional.ofNullable(runtimes.get(blockKey)); }

    public void remove(String blockKey, Location dropLocation) {
        MachineRuntime removed = runtimes.remove(blockKey);
        if (removed != null && dropLocation != null) removed.drop(dropLocation);
        if (removed != null) {
            dirtyRuntimeKeys.remove(blockKey);
            deletedRuntimeKeys.add(blockKey);
        }
        saveSoon();
    }

    public void forgetMachinesInChunks(String worldName, List<ChunkPos> chunks) {
        if (worldName == null || chunks == null || chunks.isEmpty()) return;
        java.util.Set<String> chunkKeys = new java.util.HashSet<>();
        for (ChunkPos chunk : chunks) chunkKeys.add(worldName + ":" + chunk.x() + ":" + chunk.z());
        for (String blockKey : new ArrayList<>(runtimes.keySet())) {
            Block block = blockFromKey(blockKey);
            if (block == null) continue;
            String chunkKey = block.getWorld().getName() + ":" + block.getChunk().getX() + ":" + block.getChunk().getZ();
            if (chunkKeys.contains(chunkKey)) {
                runtimes.remove(blockKey);
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
        runtime.input(!machine.recipes().isEmpty() ? inv.getItem(machine.inputSlot()) : null);
        runtime.secondary(machine.hasSecondarySlot() ? inv.getItem(machine.secondarySlot()) : null);
        runtime.fuel((machine.energy().enabled() || machine.hasRecipeFuelSlot()) ? inv.getItem(machine.fuelSlot()) : null);
        runtime.output(inv.getItem(machine.outputSlot()));
        List<Integer> upgrades = upgradeSlots(machine);
        for (int i = 0; i < Math.min(3, upgrades.size()); i++) runtime.upgrade(i, inv.getItem(upgrades.get(i)));
        markDirty(runtime);
        saveSoon();
    }

    public void syncToInventory(MachineDefinition machine, MachineRuntime runtime, Inventory inv) {
        if (machine == null || runtime == null || inv == null) return;
        if (!machine.recipes().isEmpty()) inv.setItem(machine.inputSlot(), runtime.input());
        if (machine.hasSecondarySlot()) inv.setItem(machine.secondarySlot(), runtime.secondary());
        if (machine.energy().enabled() || machine.hasRecipeFuelSlot()) inv.setItem(machine.fuelSlot(), runtime.fuel());
        inv.setItem(machine.outputSlot(), runtime.output());
        List<Integer> upgrades = upgradeSlots(machine);
        for (int i = 0; i < Math.min(3, upgrades.size()); i++) inv.setItem(upgrades.get(i), runtime.upgrade(i));
    }

    public List<Integer> upgradeSlots(MachineDefinition machine) {
        if (machine == null || machine.upgradeSlots().isEmpty()) return List.of(38, 40, 42);
        return machine.upgradeSlots();
    }

    public boolean isEditableSlot(MachineDefinition machine, int slot) {
        if (machine == null) return false;
        if (slot == machine.outputSlot() || slot == machine.arrowSlot()) return false;
        if (slot == machine.inputSlot() && !machine.recipes().isEmpty()) return true;
        if (slot == machine.secondarySlot() && machine.hasSecondarySlot()) return true;
        if (slot == machine.fuelSlot() && (machine.energy().enabled() || machine.hasRecipeFuelSlot())) return true;
        if (slot == machine.energy().batterySlot()) return true;
        return upgradeSlots(machine).contains(slot);
    }

    public MachineRecipe match(MachineDefinition machine, MachineRuntime runtime) {
        if (machine == null || runtime == null) return null;
        for (MachineRecipe recipe : machine.recipes()) {
            if (!recipe.matchesInput(runtime.input(), minions.specialItems())) continue;
            if (!recipe.matchesSecondary(runtime.secondary(), minions.specialItems())) continue;
            if (!recipe.matchesFuel(runtime.fuel(), minions.specialItems())) continue;
            ItemStack output = output(recipe);
            ItemStack current = runtime.output();
            if (current != null && !current.getType().isAir() && !current.isSimilar(output)) continue;
            int currentAmount = current == null ? 0 : current.getAmount();
            if (currentAmount + output.getAmount() > Math.min(output.getMaxStackSize(), machine.defaultOutputStackSize())) continue;
            return recipe;
        }
        return null;
    }

    public ItemStack output(MachineRecipe recipe) {
        if (recipe.outputSpecialItem() != null && !recipe.outputSpecialItem().isBlank()) {
            return minions.specialItems().createItem(recipe.outputSpecialItem(), recipe.outputAmount());
        }
        ItemStack item = new ItemStack(recipe.outputMaterial() == Material.AIR ? Material.PAPER : recipe.outputMaterial(), recipe.outputAmount());
        if (recipe.outputCustomModelData() > 0) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setCustomModelData(recipe.outputCustomModelData());
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    public boolean collectOutput(MachineRuntime runtime, org.bukkit.entity.Player player) {
        ItemStack output = runtime.output();
        if (output == null || output.getType().isAir()) return false;
        runtime.output(null);
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
        refreshOpenMenus();
        if ((!dirtyRuntimeKeys.isEmpty() || !deletedRuntimeKeys.isEmpty()) && ++saveCountdown >= saveIntervalTicks) flushDirtyAsync();
    }

    private void tickGenerators(int secondSlot) {
        for (MachineRuntime runtime : new ArrayList<>(runtimes.values())) {
            if (Math.floorMod(runtime.blockKey().hashCode(), 10) != secondSlot) continue;
            Block block = blockFromKey(runtime.blockKey());
            MachineDefinition machine = block == null ? null : machineAt(block);
            if (machine == null || !machine.energy().enabled() || !machine.energy().generator()) continue;
            runtime.machineId(machine.id());
            tickFuel(machine, runtime, true);
            distributeEnergy(block, machine, runtime);
        }
    }

    private void tickConsumers(int secondSlot) {
        for (MachineRuntime runtime : new ArrayList<>(runtimes.values())) {
            if (Math.floorMod(runtime.blockKey().hashCode(), 10) != secondSlot) continue;
            Block block = blockFromKey(runtime.blockKey());
            MachineDefinition machine = block == null ? null : machineAt(block);
            if (machine == null || machine.energy().generator()) continue;
            runtime.machineId(machine.id());
            if (machine.energy().enabled()) tickFuel(machine, runtime, false);
            tickProcess(machine, runtime);
        }
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

    private void tickProcess(MachineDefinition machine, MachineRuntime runtime) {
        MachineRecipe recipe = match(machine, runtime);
        if (recipe == null) {
            if (!runtime.recipeId().isBlank() || runtime.progressSeconds() > 0) {
                runtime.resetProcess();
                markDirty(runtime);
            }
            return;
        }
        if (!recipe.id().equals(runtime.recipeId())) runtime.startProcess(recipe.id());
        if (machine.energy().enabled()) {
            int cost = Math.max(0, machine.energy().euPerSecond());
            if (!runtime.consumeEnergy(cost)) return;
        }
        runtime.addProgressSecond();
        if (runtime.progressSeconds() >= recipe.timeSeconds()) completeRecipe(machine, runtime, recipe);
        markDirty(runtime);
    }

    private void completeRecipe(MachineDefinition machine, MachineRuntime runtime, MachineRecipe recipe) {
        if (match(machine, runtime) == null) {
            runtime.resetProcess();
            return;
        }
        runtime.consumeInput(recipe.inputAmount());
        if (!recipe.secondarySpecialItem().isBlank() || recipe.secondaryMaterial() != Material.AIR) runtime.consumeSecondary(recipe.secondaryAmount());
        if (!recipe.fuelSpecialItem().isBlank() || recipe.fuelMaterial() != Material.AIR) runtime.consumeRecipeFuel(recipe.fuelAmount());
        if (ThreadLocalRandom.current().nextDouble() <= recipe.successChance()) addOutput(machine, runtime, output(recipe));
        runtime.resetProcess();
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

    private void distributeEnergy(Block generatorBlock, MachineDefinition generator, MachineRuntime generatorRuntime) {
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
        }
    }

    private ItemStack progressItem(MachineDefinition machine, MachineRuntime runtime) {
        MachineRecipe recipe = match(machine, runtime);
        if (recipe == null) return named(Material.ARROW, "§ePostęp", "§7Brak pasującej receptury albo pełny output.");
        int percent = Math.min(100, runtime.progressSeconds() * 100 / Math.max(1, recipe.timeSeconds()));
        String eu = machine.energy().enabled() ? "\n§7Energia: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU" : "";
        return named(Material.ARROW, "§ePostęp: §f" + percent + "%", "§7Czas: §f" + runtime.progressSeconds() + "§7/§f" + recipe.timeSeconds() + "s" + eu);
    }

    private ItemStack machineInfoItem(MachineDefinition machine, MachineRuntime runtime) {
        if (!machine.energy().enabled()) return named(Material.BOOK, machine.displayName(), "§7Maszyna konfigurowalna w machines.yml");
        if (machine.energy().generator()) {
            return named(Material.REDSTONE_BLOCK, machine.displayName(),
                    "§7Bufor: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU\n" +
                            "§7Spalanie: §f" + runtime.burnRemainingSeconds() + "s\n" +
                            "§7Zasila: §flewo, potem prawo");
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

    public Block blockFromKey(String blockKey) {
        try {
            String[] parts = blockKey.split(":");
            if (parts.length != 4) return null;
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) return null;
            return world.getBlockAt(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (Exception ignored) { return null; }
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
            for (MachineRuntime runtime : loaded) {
                if (runtime != null && runtime.blockKey() != null && !runtime.blockKey().isBlank()) {
                    runtimes.put(runtime.blockKey(), runtime);
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
        List<MachineRuntime> toSave = new ArrayList<>();
        for (String key : new ArrayList<>(dirtyRuntimeKeys)) {
            MachineRuntime runtime = runtimes.get(key);
            if (runtime != null) toSave.add(runtime);
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

    private record FuelValue(int eu, int burnSeconds) {}
}
