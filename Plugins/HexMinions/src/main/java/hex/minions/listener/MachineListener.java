package hex.minions.listener;

import hex.core.api.HexApi;
import hex.core.api.compat.SoundCompatibility;
import hex.minions.machine.MachineDefinition;
import hex.minions.machine.MachineRecipe;
import hex.minions.machine.MachineRuntime;
import hex.minions.machine.MachineService;
import hex.minions.menu.MachineMenuHolder;
import hex.towns.api.TownsApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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
import org.bukkit.event.player.PlayerInteractEvent;
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
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final NamespacedKey machineVisualKey;

    public MachineListener(Plugin plugin, HexApi hex, TownsApi towns, MachineService machines) {
        this.plugin = plugin;
        this.hex = hex;
        this.towns = towns;
        this.machines = machines;
        this.machineVisualKey = new NamespacedKey(plugin, "machine_visual");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaceMachine(BlockPlaceEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Optional<String> stationId = machines.machineAt(event.getBlockPlaced()) == null
                    ? Optional.empty()
                    : Optional.of(machines.machineAt(event.getBlockPlaced()).stationId());
            stationId.flatMap(machines.registry()::byStation).ifPresent(machine -> {
                MachineRuntime runtime = machines.runtime(machines.key(event.getBlockPlaced().getLocation()), machine.id());
                runtime.touchActiveNow();
                machines.markRuntimeDirty(runtime);
                ensureVisual(event.getBlockPlaced(), machine);
                machines.recordEnergyGeneratorPlaced(event.getBlockPlaced(), machine);
                machines.saveSoon();
            });
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onUseMachine(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        MachineDefinition machine = machines.machineAt(event.getClickedBlock());
        if (machine == null) return;
        event.setCancelled(true);
        if (towns.townAt(event.getClickedBlock().getLocation()).filter(t -> towns.isMember(event.getPlayer().getUniqueId(), t.id())).isEmpty()) {
            event.getPlayer().sendMessage("§cMożesz obsługiwać tę maszynę tylko w swoim mieście.");
            return;
        }
        if (machines.isBronzeWrench(event.getItem())) {
            handleBronzeWrench(event.getPlayer(), event.getClickedBlock(), event.getBlockFace(), machine);
            return;
        }
        ensureVisual(event.getClickedBlock(), machine);
        openMachine(event.getPlayer(), event.getClickedBlock(), machine);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakMachine(BlockBreakEvent event) {
        MachineDefinition machine = machines.machineAt(event.getBlock());
        if (machine == null) return;
        String key = machines.key(event.getBlock().getLocation());
        machines.remove(key, event.getBlock().getLocation());
        cleanupVisuals(event.getBlock().getLocation());
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MachineMenuHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        MachineDefinition machine = machines.registry().machines().get(holder.machineId());
        if (machine == null) { event.setCancelled(true); return; }
        MachineRuntime runtime = machines.runtime(holder.blockKey(), machine.id());
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(top)) {
            int slot = event.getSlot();
            if (slot == machine.outputSlot()) {
                machines.syncFromInventory(machine, runtime, top);
                machines.collectOutput(runtime, player);
                machines.syncToInventory(machine, runtime, top);
                top.setItem(machine.arrowSlot(), progressItem(machine, runtime));
                event.setCancelled(true);
                return;
            }
            if (!machines.isEditableSlot(machine, slot)) event.setCancelled(true);
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            machines.syncFromInventory(machine, runtime, top);
            top.setItem(machine.arrowSlot(), progressItem(machine, runtime));
            top.setItem(4, machineInfoItem(machine, runtime));
            machines.saveSoon();
        });
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory top = event.getInventory();
        if (!(top.getHolder() instanceof MachineMenuHolder holder)) return;
        MachineDefinition machine = machines.registry().machines().get(holder.machineId());
        if (machine == null) return;
        MachineRuntime runtime = machines.runtime(holder.blockKey(), machine.id());
        machines.syncFromInventory(machine, runtime, top);
        machines.saveSoon();
    }


    private void handleBronzeWrench(Player player, Block block, BlockFace clickedFace, MachineDefinition machine) {
        if ("ACCUMULATOR".equalsIgnoreCase(machine.type())) {
            BlockFace currentInput = machines.accumulatorInputFace(block);
            if (clickedFace == null || clickedFace == BlockFace.SELF) {
                player.sendMessage("§cKliknij konkretny bok akumulatora, który ma zostać wejściem EU.");
                return;
            }
            if (clickedFace == currentInput) {
                player.sendMessage("§eTen bok jest już wejściem EU akumulatora.");
                return;
            }
            machines.setAccumulatorInputFace(block, clickedFace);
            cleanupVisuals(block.getLocation());
            ensureVisual(block, machine);
            SoundCompatibility.play(player, block.getLocation(), "BLOCK_ANVIL_USE", 0.7f, 1.35f);
            player.sendMessage("§aPrzeniesiono wejście EU akumulatora na bok: §f" + clickedFace.name() + "§a. Poprzedni bok stał się wyjściem.");
            return;
        }

        if (!machines.hasConfigurablePorts(machine)) {
            Location drop = block.getLocation().add(0.5, 0.5, 0.5);
            ItemStack machineItem = machines.createMachineItem(machine);
            machines.remove(machines.key(block.getLocation()), drop);
            cleanupVisuals(block.getLocation());
            block.setType(Material.AIR, false);
            if (machineItem != null && !machineItem.getType().isAir()) drop.getWorld().dropItemNaturally(drop, machineItem);
            SoundCompatibility.play(player, drop, "BLOCK_ANVIL_USE", 0.8f, 0.75f);
            player.sendMessage("§eRozkręcono maszynę. Item maszyny i zawartość wypadły obok.");
        }
    }

    private void openMachine(Player player, Block block, MachineDefinition machine) {
        String key = machines.key(block.getLocation());
        MachineRuntime runtime = machines.runtime(key, machine.id());
        machines.applyOfflineCatchup(block, machine, runtime);
        Inventory inv = Bukkit.createInventory(new MachineMenuHolder(machine.id(), key), 54, mini.deserialize(machine.displayName()));
        fill(inv, machine);
        machines.syncToInventory(machine, runtime, inv);
        inv.setItem(machine.arrowSlot(), progressItem(machine, runtime));
        inv.setItem(4, machineInfoItem(machine, runtime));
        player.openInventory(inv);
    }

    private void fill(Inventory inv, MachineDefinition machine) {
        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ", "");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        if (!machine.recipes().isEmpty()) for (int inputSlot : machine.inputSlots()) inv.setItem(inputSlot, null);
        if (machine.hasSecondarySlot()) inv.setItem(machine.secondarySlot(), null);
        if (machine.energy().enabled() || machine.hasRecipeFuelSlot()) inv.setItem(machine.fuelSlot(), null);
        inv.setItem(machine.outputSlot(), null);
        for (int slot : machines.upgradeSlots(machine)) inv.setItem(slot, null);
        if (machine.energy().batterySlot() >= 0 && machine.energy().batterySlot() < inv.getSize()) inv.setItem(machine.energy().batterySlot(), null);
    }

    private ItemStack progressItem(MachineDefinition machine, MachineRuntime runtime) {
        MachineRecipe recipe = machines.match(machine, runtime);
        if (recipe == null) return named(Material.ARROW, "§ePostęp", "§7Brak pasującej receptury albo pełny output.");
        int percent = Math.min(100, runtime.progressSeconds() * 100 / Math.max(1, recipe.timeSeconds()));
        String eu = machine.energy().enabled() ? "\n§7Energia: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU" : "";
        return named(Material.ARROW, "§ePostęp: §f" + percent + "%", "§7Czas: §f" + runtime.progressSeconds() + "§7/§f" + recipe.timeSeconds() + "s" + eu);
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
            if ("ACCUMULATOR".equalsIgnoreCase(machine.type())) {
                return named(Material.OAK_WOOD, machine.displayName(),
                        "§7Bufor: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU\n" +
                                "§7Wejście: §f" + machines.accumulatorInputFace(machines.blockFromKey(runtime.blockKey())).name() + "\n" +
                                "§7Wyjście: §fpozostałe strony\n" +
                                "§8Kluczem z brązu kliknij port wyjściowy, aby przenieść wejście.");
            }
            return named(Material.REDSTONE_BLOCK, machine.displayName(),
                    "§7Bufor: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU\n" +
                            "§7Spalanie: §f" + runtime.burnRemainingSeconds() + "s\n" +
                            "§7Zasila: §flewo, potem prawo");
        }
        if ("EXTRACTOR".equalsIgnoreCase(machine.type())) {
            return named(Material.DISPENSER, machine.displayName(),
                    "§7Bufor: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU\n" +
                            "§7Zużycie: §f" + machine.energy().euPerSecond() + " EU/s\n" +
                            "§7Proces: §f20 min, skompresowane drewno świerkowe -> żywica\n" +
                            "§7Awaryjne paliwo: §fredstone/blok redstone");
        }
        if ("ELECTRIC_MILL".equalsIgnoreCase(machine.type())) {
            return named(Material.COMPOSTER, machine.displayName(),
                    "§7Bufor: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU\n" +
                            "§7Zużycie: §f" + machine.energy().euPerSecond() + " EU/s\n" +
                            "§7Inputy: §f3 sloty, od lewej do prawej\n" +
                            "§7Pszenica: §f2% na paszę w 4 min\n" +
                            "§7Port EU: §ftył i dół, paliwo awaryjne: redstone/blok redstone");
        }
        return named(Material.REDSTONE, machine.displayName(),
                "§7Bufor: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU\n" +
                        "§7Zużycie: §f" + machine.energy().euPerSecond() + " EU/s\n" +
                        "§7Awaryjne paliwo: §fredstone/blok redstone");
    }

    private void ensureVisual(Block block, MachineDefinition machine) {
        String ownerKey = machines.key(block.getLocation());
        if (block.getWorld().getNearbyEntities(block.getLocation().add(0.5, 0.5, 0.5), 0.9, 0.9, 0.9).stream()
                .anyMatch(e -> ownerKey.equals(e.getPersistentDataContainer().get(machineVisualKey, PersistentDataType.STRING)))) return;
        if ("URANIUM_ENRICHER".equalsIgnoreCase(machine.type())) {
            spawnDisplay(block.getLocation(), block.getLocation(), Material.IRON_BLOCK, new Vector3f(-0.04f, -0.04f, -0.04f), new Vector3f(1.08f, 1.08f, 1.08f));
            spawnDisplay(block.getLocation(), block.getLocation().add(-0.06, 0.1, 0.5), Material.LAPIS_BLOCK, new Vector3f(0f, 0f, 0f), new Vector3f(0.10f, 0.80f, 0.08f));
            spawnDisplay(block.getLocation(), block.getLocation().add(0.96, 0.1, 0.5), Material.LAPIS_BLOCK, new Vector3f(0f, 0f, 0f), new Vector3f(0.10f, 0.80f, 0.08f));
        } else if ("SMELTING_FURNACE".equalsIgnoreCase(machine.type())) {
            spawnDisplay(block.getLocation(), block.getLocation(), Material.BRICKS, new Vector3f(-0.03f, -0.03f, -0.03f), new Vector3f(1.06f, 1.06f, 1.06f));
        } else if ("COAL_GENERATOR".equalsIgnoreCase(machine.type())) {
            spawnDisplay(block.getLocation(), block.getLocation(), Material.DEEPSLATE, new Vector3f(-0.03f, -0.03f, -0.03f), new Vector3f(1.06f, 1.06f, 1.06f));
            spawnDisplay(block.getLocation(), block.getLocation().add(0.24, 0.88, 0.24), Material.COAL_BLOCK, new Vector3f(0f, 0f, 0f), new Vector3f(0.52f, 0.18f, 0.52f));
        } else if ("SOLAR_PANEL_GENERATOR".equalsIgnoreCase(machine.type()) || "SOLAR_GENERATOR".equalsIgnoreCase(machine.type())) {
            spawnDisplay(block.getLocation(), block.getLocation(), Material.IRON_BLOCK, new Vector3f(-0.02f, -0.02f, -0.02f), new Vector3f(1.04f, 1.04f, 1.04f));
            spawnDisplay(block.getLocation(), block.getLocation().add(0.10, 1.02, 0.10), Material.COAL_BLOCK, new Vector3f(0f, 0f, 0f), new Vector3f(0.80f, 0.10f, 0.80f));
        } else if ("ACCUMULATOR".equalsIgnoreCase(machine.type())) {
            spawnAccumulatorVisual(block);
        } else if ("MACERATOR".equalsIgnoreCase(machine.type())) {
            spawnDisplay(block.getLocation(), block.getLocation(), Material.STONECUTTER, new Vector3f(-0.02f, -0.02f, -0.02f), new Vector3f(1.04f, 1.04f, 1.04f));
            spawnDisplay(block.getLocation(), block.getLocation().add(0.36, 0.78, 0.36), Material.FLINT, new Vector3f(0f, 0f, 0f), new Vector3f(0.28f, 0.28f, 0.28f));
        } else if ("COMPRESSOR".equalsIgnoreCase(machine.type())) {
            spawnCompressorVisual(block);
        } else if ("EXTRACTOR".equalsIgnoreCase(machine.type())) {
            spawnExtractorVisual(block);
        } else if ("ELECTRIC_MILL".equalsIgnoreCase(machine.type())) {
            spawnElectricMillVisual(block);
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
        if ("ELECTRIC_MILL".equalsIgnoreCase(machine.type())) {
            port(block.getLocation(), machines.facing(block).getOppositeFace(), Material.ORANGE_CONCRETE, true);
            port(block.getLocation(), BlockFace.DOWN, Material.ORANGE_CONCRETE, true);
            return;
        }
        BlockFace input = machines.facing(block).getOppositeFace();
        port(block.getLocation(), input, Material.ORANGE_CONCRETE, true);
    }

    private void spawnElectricMillVisual(Block block) {
        Location base = block.getLocation();
        spawnDisplay(base, base, Material.COMPOSTER, new Vector3f(-0.02f, -0.02f, -0.02f), new Vector3f(1.04f, 1.04f, 1.04f));
        // Kamienna podstawa: dolne 0.3 bloku, szersza o 0.1 w każdym kierunku.
        spawnDisplay(base, base, Material.COBBLESTONE, new Vector3f(-0.10f, -0.02f, -0.10f), new Vector3f(1.20f, 0.30f, 1.20f));
    }

    private void spawnExtractorVisual(Block block) {
        Location base = block.getLocation();
        spawnDisplay(base, base, Material.DISPENSER, new Vector3f(-0.02f, -0.02f, -0.02f), new Vector3f(1.04f, 1.04f, 1.04f));
        // Błękitne paski po bokach: szerokość 0.1, wysokość 0.8, wysunięcie 0.1 poza blok.
        spawnDisplay(base, base, Material.LIGHT_BLUE_CONCRETE, new Vector3f(-0.08f, 0.10f, 0.45f), new Vector3f(0.10f, 0.80f, 0.10f));
        spawnDisplay(base, base, Material.LIGHT_BLUE_CONCRETE, new Vector3f(0.98f, 0.10f, 0.45f), new Vector3f(0.10f, 0.80f, 0.10f));
    }

    private void spawnAccumulatorVisual(Block block) {
        Location base = block.getLocation();
        spawnDisplay(base, base, Material.OAK_WOOD, new Vector3f(-0.02f, -0.02f, -0.02f), new Vector3f(1.04f, 1.04f, 1.04f));
        BlockFace input = machines.accumulatorInputFace(block);
        port(base, input, Material.ORANGE_CONCRETE, true);
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP)) {
            if (face != input) port(base, face, Material.BLUE_CONCRETE, false);
        }
    }

    private void port(Location base, BlockFace face, Material material, boolean input) {
        float size = 0.30f;
        float depth = 0.10f;
        switch (face) {
            case NORTH -> spawnDisplay(base, base, material, new Vector3f(0.35f, 0.35f, -0.06f), new Vector3f(size, size, depth));
            case SOUTH -> spawnDisplay(base, base, material, new Vector3f(0.35f, 0.35f, 0.96f), new Vector3f(size, size, depth));
            case EAST -> spawnDisplay(base, base, material, new Vector3f(0.96f, 0.35f, 0.35f), new Vector3f(depth, size, size));
            case WEST -> spawnDisplay(base, base, material, new Vector3f(-0.06f, 0.35f, 0.35f), new Vector3f(depth, size, size));
            case UP -> spawnDisplay(base, base, material, new Vector3f(0.39f, 0.96f, 0.39f), new Vector3f(size, depth, size));
            case DOWN -> spawnDisplay(base, base, material, new Vector3f(0.39f, -0.06f, 0.39f), new Vector3f(size, depth, size));
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

    private void panel(Location base, Material material, float x, float y, float z, float sx, float sy, float sz, boolean enabled) {
        if (!enabled) return;
        spawnDisplay(base, base, material, new Vector3f(x, y, z), new Vector3f(sx, sy, sz));
    }

    private void spawnDisplay(Location ownerBlockLocation, Location spawnLocation, Material material, Vector3f translation, Vector3f scale) {
        BlockDisplay display = (BlockDisplay) spawnLocation.getWorld().spawnEntity(spawnLocation, EntityType.BLOCK_DISPLAY);
        display.setBlock(material.createBlockData());
        display.setTransformation(new Transformation(translation, new AxisAngle4f(), scale, new AxisAngle4f()));
        display.setBillboard(Display.Billboard.FIXED);
        display.setPersistent(true);
        display.getPersistentDataContainer().set(machineVisualKey, PersistentDataType.STRING, machines.key(ownerBlockLocation));
    }

    private void cleanupVisuals(Location loc) {
        String key = machines.key(loc);
        loc.getWorld().getEntities().stream()
                .filter(e -> key.equals(e.getPersistentDataContainer().get(machineVisualKey, PersistentDataType.STRING)))
                .forEach(org.bukkit.entity.Entity::remove);
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
}
