package hex.minions.listener;

import hex.core.api.HexApi;
import hex.core.api.ui.UiTokens;
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
                machines.refreshCableVisualsNear(event.getBlockPlaced());
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
            hex.ui().send(event.getPlayer(), "minions.machine.error.not-town");
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
        machines.removeGeneratorTopStorage(event.getBlock(), event.getBlock().getLocation());
        machines.removeElectricFurnaceStorage(event.getBlock(), event.getBlock().getLocation());
        machines.remove(key, event.getBlock().getLocation());
        cleanupVisuals(event.getBlock().getLocation());
        Bukkit.getScheduler().runTask(plugin, () -> machines.refreshCableVisualsNear(event.getBlock()));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MachineMenuHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        MachineDefinition machine = machines.registry().machines().get(holder.machineId());
        if (machine == null) { event.setCancelled(true); return; }
        MachineRuntime runtime = machines.runtime(holder.blockKey(), machine.id());
        boolean clickedTop = event.getClickedInventory() != null && event.getClickedInventory().equals(top);
        int clickedSlot = clickedTop ? event.getSlot() : -1;
        machines.sanitizeMenuGuides(top);
        if (clickedTop && clickedSlot >= 0 && clickedSlot < top.getSize() && top.getItem(clickedSlot) == null) {
            event.setCurrentItem(null);
        }
        if (clickedTop) {
            int slot = clickedSlot;
            int outputIndex = machine.outputSlots().indexOf(slot);
            if (outputIndex >= 0) {
                machines.syncFromInventory(machine, runtime, top);
                machines.collectOutput(runtime, outputIndex, player);
                machines.syncToInventory(machine, runtime, top);
                top.setItem(machine.arrowSlot(), progressItem(machine, runtime));
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
                    if (!machines.canInstallExternalStorageAt(machineBlock, machine, slot, cursor)) {
                        event.setCancelled(true);
                        hex.ui().send(player, "minions.storage-chest.error.no-space-left");
                        return;
                    }
                } else if (takingOrClearing && current != null && !current.getType().isAir() && machines.isValidExternalStorageItem(current)) {
                    // Wyjęcie rozszerzenia jest dozwolone. Skrzynia zostanie usunięta i wysypana przy następnym cyklu/niszczeniu maszyny.
                }
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            machines.syncFromInventory(machine, runtime, top);
            machines.ensureExternalStorage(machines.blockFromKey(holder.blockKey()), machine, runtime);
            top.setItem(machine.arrowSlot(), progressItem(machine, runtime));
            top.setItem(4, machineInfoItem(machine, runtime));
            machines.applyMenuGuides(machine, top);
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
        machines.sanitizeMenuGuides(top);
        machines.syncFromInventory(machine, runtime, top);
        machines.saveSoon();
    }


    private void handleBronzeWrench(Player player, Block block, BlockFace clickedFace, MachineDefinition machine) {
        if ("ACCUMULATOR".equalsIgnoreCase(machine.type())) {
            BlockFace currentInput = machines.accumulatorInputFace(block);
            if (clickedFace == null || clickedFace == BlockFace.SELF) {
                hex.ui().send(player, "minions.machine.accumulator.input-face-required");
                return;
            }
            if (clickedFace == currentInput) {
                hex.ui().send(player, "minions.machine.accumulator.input-face-same");
                return;
            }
            machines.setAccumulatorInputFace(block, clickedFace);
            cleanupVisuals(block.getLocation());
            ensureVisual(block, machine);
            machines.refreshCableVisualsNear(block);
            SoundCompatibility.play(player, block.getLocation(), "BLOCK_ANVIL_USE", 0.7f, 1.35f);
            hex.ui().send(player, "minions.machine.accumulator.input-face-changed", UiTokens.of("face", clickedFace.name()));
            return;
        }

        if (!machines.hasConfigurablePorts(machine)) {
            Location drop = block.getLocation().add(0.5, 0.5, 0.5);
            ItemStack machineItem = machines.createMachineItem(machine);
            machines.removeGeneratorTopStorage(block, drop);
            machines.removeElectricFurnaceStorage(block, drop);
            machines.remove(machines.key(block.getLocation()), drop);
            cleanupVisuals(block.getLocation());
            block.setType(Material.AIR, false);
            if (machineItem != null && !machineItem.getType().isAir()) drop.getWorld().dropItemNaturally(drop, machineItem);
            SoundCompatibility.play(player, drop, "BLOCK_ANVIL_USE", 0.8f, 0.75f);
            hex.ui().send(player, "minions.machine.dismantle.success");
        }
    }

    private void openMachine(Player player, Block block, MachineDefinition machine) {
        String key = machines.key(block.getLocation());
        MachineRuntime runtime = machines.runtime(key, machine.id());
        machines.applyOfflineCatchup(block, machine, runtime);
        Inventory inv = Bukkit.createInventory(new MachineMenuHolder(machine.id(), key), 54, mini.deserialize(machine.displayName()));
        fill(inv, machine);
        decorateMachineLayout(inv, machine);
        machines.syncToInventory(machine, runtime, inv);
        inv.setItem(machine.arrowSlot(), progressItem(machine, runtime));
        inv.setItem(4, machineInfoItem(machine, runtime));
        machines.applyMenuGuides(machine, inv);
        player.openInventory(inv);
    }

    private void fill(Inventory inv, MachineDefinition machine) {
        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ", "");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        if (!machine.recipes().isEmpty() || "ELECTRIC_FURNACE".equalsIgnoreCase(machine.type())) for (int inputSlot : machine.inputSlots()) inv.setItem(inputSlot, null);
        if (machine.hasSecondarySlot()) inv.setItem(machine.secondarySlot(), null);
        if (machine.energy().enabled() || machine.hasRecipeFuelSlot()) inv.setItem(machine.fuelSlot(), null);
        for (int outputSlot : machine.outputSlots()) inv.setItem(outputSlot, null);
        for (int slot : machines.upgradeSlots(machine)) inv.setItem(slot, null);
        if (machine.energy().batterySlot() >= 0 && machine.energy().batterySlot() < inv.getSize()) inv.setItem(machine.energy().batterySlot(), null);
    }

    private void decorateMachineLayout(Inventory inv, MachineDefinition machine) {
        if (machine == null) return;
        if ("SMELTING_FURNACE".equalsIgnoreCase(machine.type())) {
            inv.setItem(11, named(Material.ORANGE_STAINED_GLASS_PANE, "§6Sloty wsadu", "§7Wsad trafia do dwóch slotów poniżej: X X."));
            inv.setItem(12, named(Material.ORANGE_STAINED_GLASS_PANE, "§6Sloty wsadu", "§7Drugi składnik/wsad jest obok pierwszego."));
            inv.setItem(30, named(Material.YELLOW_STAINED_GLASS_PANE, "§eSlot paliwa", "§7Paliwo wkładaj w pusty slot po lewej od tej ikonki, pod wsadem."));
            inv.setItem(25, named(Material.GREEN_STAINED_GLASS_PANE, "§aRezultat", "§7Wynik pojawi się w pustym slocie po lewej."));
            return;
        }
        if ("ELECTRIC_FURNACE".equalsIgnoreCase(machine.type())) {
            inv.setItem(11, named(Material.ORANGE_STAINED_GLASS_PANE, "§6Dwa sloty wsadu", "§7Działa jak dwa niezależne piece. Stal: żelazo + 8 węgla."));
            inv.setItem(12, named(Material.ORANGE_STAINED_GLASS_PANE, "§6Uzupełnianie z góry", "§7Rozszerzenie w lewym slocie tworzy skrzynię wejściową nad piecem."));
            inv.setItem(30, named(Material.RED_STAINED_GLASS_PANE, "§cRedstone awaryjny", "§7Redstone zasila piec awaryjnie, gdy brakuje EU."));
            inv.setItem(26, named(Material.GREEN_STAINED_GLASS_PANE, "§aSkrzynia wyjściowa", "§7Rozszerzenie w prawym slocie tworzy skrzynię pod piecem."));
            return;
        }
        if ("ELECTRIC_MILL".equalsIgnoreCase(machine.type())) {
            inv.setItem(11, named(Material.ORANGE_STAINED_GLASS_PANE, "§6Wsad kompostora", "§7Włóż pszenicę albo inne skonfigurowane inputy z machines.yml."));
            inv.setItem(12, named(Material.ORANGE_STAINED_GLASS_PANE, "§6Skrzynia wejściowa", "§7Rozszerzenie w lewym slocie tworzy skrzynię wejściową nad kompostorem."));
            inv.setItem(30, named(Material.RED_STAINED_GLASS_PANE, "§cRedstone awaryjny", "§7Redstone zasila kompostor awaryjnie, gdy brakuje EU."));
            inv.setItem(25, named(Material.GREEN_STAINED_GLASS_PANE, "§aSkrzynia wyjściowa", "§7Rozszerzenie w prawym slocie tworzy skrzynię pod kompostorem."));
            return;
        }
        if ("MEAT_REFINERY".equalsIgnoreCase(machine.type())) {
            inv.setItem(11, named(Material.ORANGE_STAINED_GLASS_PANE, "§6Wsad rafinatora", "§7Włóż dowolne mięso skonfigurowane w machines.yml."));
            inv.setItem(12, named(Material.ORANGE_STAINED_GLASS_PANE, "§6Skrzynia wejściowa", "§7Rozszerzenie w lewym slocie tworzy skrzynię wejściową nad rafinatorem."));
            inv.setItem(30, named(Material.RED_STAINED_GLASS_PANE, "§cRedstone awaryjny", "§7Redstone zasila rafinator awaryjnie, gdy brakuje EU."));
            inv.setItem(25, named(Material.GREEN_STAINED_GLASS_PANE, "§aSkrzynia wyjściowa", "§7Rozszerzenie w prawym slocie tworzy skrzynię pod rafinatorem."));
            return;
        }
        if (!machine.recipes().isEmpty()) {
            inv.setItem(11, named(Material.ORANGE_STAINED_GLASS_PANE, "§6Input", "§7Puste sloty procesu przyjmują składniki."));
            inv.setItem(25, named(Material.GREEN_STAINED_GLASS_PANE, "§aOutput", "§7Wynik pojawia się w slocie rezultatu."));
        }
        if (machine.energy().enabled() || machine.hasRecipeFuelSlot()) {
            inv.setItem(31, named(Material.YELLOW_STAINED_GLASS_PANE, "§ePaliwo / energia awaryjna", "§7Tu trafia paliwo przepalania lub redstone awaryjny."));
        }
    }

    private ItemStack progressItem(MachineDefinition machine, MachineRuntime runtime) {
        if ("ELECTRIC_FURNACE".equalsIgnoreCase(machine.type())) return electricFurnaceProgressItem(machine, runtime);
        if ("ELECTRIC_MILL".equalsIgnoreCase(machine.type())) return externalMachineProgressItem(machine, runtime, "kompostora");
        if ("MEAT_REFINERY".equalsIgnoreCase(machine.type())) return externalMachineProgressItem(machine, runtime, "rafinatora mięsa");
        MachineRecipe recipe = machines.match(machine, runtime);
        if (recipe == null) return named(Material.ARROW, "§ePostęp", "§7Brak pasującej receptury albo pełny output.");
        int percent = Math.min(100, runtime.progressSeconds() * 100 / Math.max(1, recipe.timeSeconds()));
        String eu = machine.energy().enabled() ? "\n§7Energia: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU" : "";
        return named(Material.ARROW, "§ePostęp: §f" + percent + "%", "§7Czas: §f" + runtime.progressSeconds() + "§7/§f" + recipe.timeSeconds() + "s" + eu);
    }

    private ItemStack electricFurnaceProgressItem(MachineDefinition machine, MachineRuntime runtime) {
        String eu = "§7Energia: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU";
        return named(Material.ARROW, "§ePostęp pieca elektrycznego", eu + "\n" + electricProgressLine(runtime, 0) + "\n" + electricProgressLine(runtime, 1) + "\n§7Zużycie przy pracy: §f" + machine.energy().euPerSecond() + " EU/s");
    }

    private ItemStack externalMachineProgressItem(MachineDefinition machine, MachineRuntime runtime, String label) {
        String eu = "§7Energia: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU";
        String recipe = runtime.recipeId();
        if (recipe == null || recipe.isBlank()) {
            return named(Material.ARROW, "§ePostęp " + label, eu + "\n§7Status: §8oczekuje na poprawny input, miejsce na output albo energię\n§7Zużycie przy pracy: §f" + machine.energy().euPerSecond() + " EU/s");
        }
        int total = machine.recipes().stream()
                .filter(r -> recipe.startsWith(r.id() + "@"))
                .findFirst()
                .map(MachineRecipe::timeSeconds)
                .orElse(30);
        int progress = Math.min(total, runtime.progressSeconds());
        int percent = Math.min(100, progress * 100 / Math.max(1, total));
        return named(Material.ARROW, "§ePostęp " + label + ": §f" + percent + "%", eu + "\n§7Czas: §f" + progress + "§7/§f" + total + "s\n§7Zużycie przy pracy: §f" + machine.energy().euPerSecond() + " EU/s");
    }

    private String electricProgressLine(MachineRuntime runtime, int slot) {
        String recipe = runtime.recipeIdAt(slot);
        if (recipe == null || recipe.isBlank()) return "§7Slot " + (slot + 1) + ": §8oczekuje";
        int total = 9;
        int progress = Math.min(total, runtime.progressSecondsAt(slot));
        int percent = Math.min(100, progress * 100 / Math.max(1, total));
        return "§7Slot " + (slot + 1) + ": §f" + percent + "% §8(" + progress + "/" + total + "s)";
    }

    private ItemStack machineInfoItem(MachineDefinition machine, MachineRuntime runtime) {
        if (!machine.energy().enabled()) return named(Material.BOOK, machine.displayName(), "§7Maszyna konfigurowalna w machines.yml");
        if ("ACCUMULATOR".equalsIgnoreCase(machine.type())) {
            return named(accumulatorInfoIconMaterial(machine), machine.displayName(),
                    "§7Bufor: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU\n" +
                            "§7Maks. wyjście: §f" + machine.energy().transferPerSecond() + " EU/s\n" +
                            "§7Wejście: §f" + machines.accumulatorInputFace(machines.blockFromKey(runtime.blockKey())).name() + "\n" +
                            "§7Wyjście: §fpozostałe strony\n" +
                            "§8Kluczem z brązu kliknij port wyjściowy, aby przenieść wejście.");
        }
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
        if ("ELECTRIC_FURNACE".equalsIgnoreCase(machine.type())) {
            return named(Material.FURNACE, machine.displayName(),
                    "§7Bufor: §f" + runtime.energy() + "§7/§f" + runtime.capacity(machine) + " EU\n" +
                            "§7Zużycie: §f" + machine.energy().euPerSecond() + " EU/s tylko podczas przepalania\n" +
                            "§7Inputy: §f2 niezależne sloty jak dwa piece\n" +
                            "§7Outputy: §f2 sloty + priorytet skrzyni pod piecem\n" +
                            "§7Paliwo awaryjne: §fredstone/blok redstone");
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

    private void ensureVisual(Block block, MachineDefinition machine) {
        // Po zmianach wizuali starsze BlockDisplay mogą nadal istnieć w świecie.
        // Usuwamy je i odtwarzamy aktualny wariant przy każdym place/interact, zamiast zostawiać stary model.
        cleanupVisuals(block.getLocation());
        if ("URANIUM_ENRICHER".equalsIgnoreCase(machine.type())) {
            spawnUraniumEnricherVisual(block);
        } else if ("SMELTING_FURNACE".equalsIgnoreCase(machine.type())) {
            spawnDisplay(block.getLocation(), block.getLocation(), Material.BRICKS, new Vector3f(-0.03f, -0.03f, -0.03f), new Vector3f(1.06f, 1.06f, 1.06f));
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
        int capacity = Math.max(1, runtime.capacity(machine));
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
        // Bazowym blokiem jest DISPENSER ze względu na PDC, a wizualnie w środku stoi piła.
        spawnDisplay(base, base, Material.STONECUTTER, new Vector3f(-0.01f, -0.01f, -0.01f), new Vector3f(1.02f, 1.02f, 1.02f));
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
        display.getPersistentDataContainer().set(machineVisualKey, PersistentDataType.STRING, machines.key(ownerBlockLocation));
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
