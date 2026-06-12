package hex.minions.listener;

import hex.core.api.HexApi;
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
                ensureVisual(event.getBlockPlaced(), machine);
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

    private void openMachine(Player player, Block block, MachineDefinition machine) {
        String key = machines.key(block.getLocation());
        MachineRuntime runtime = machines.runtime(key, machine.id());
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
        if (!machine.recipes().isEmpty()) inv.setItem(machine.inputSlot(), null);
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
        } else if ("MACERATOR".equalsIgnoreCase(machine.type())) {
            spawnDisplay(block.getLocation(), block.getLocation(), Material.STONECUTTER, new Vector3f(-0.02f, -0.02f, -0.02f), new Vector3f(1.04f, 1.04f, 1.04f));
            spawnDisplay(block.getLocation(), block.getLocation().add(0.36, 0.78, 0.36), Material.FLINT, new Vector3f(0f, 0f, 0f), new Vector3f(0.28f, 0.28f, 0.28f));
        } else if ("COMPRESSOR".equalsIgnoreCase(machine.type())) {
            spawnCompressorVisual(block);
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
