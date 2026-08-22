package hexcasino.machine;

import hexcasino.CasinoEconomy;
import hexcasino.Text;
import hexcasino.config.CasinoConfig;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class WheelOfFortuneService implements Listener {

    private static final boolean TEMPORARILY_UNAVAILABLE = true;

    private final JavaPlugin plugin;
    private final Supplier<CasinoConfig> configSupplier;
    private final Map<MachineKey, CasinoConfig.Machine> machinesByLocation = new LinkedHashMap<>();
    private final Map<UUID, WheelSession> sessionsByPlayer = new HashMap<>();
    private final Map<String, UUID> occupiedMachines = new HashMap<>();

    private BukkitTask idleParticleTask;
    private BukkitTask occupiedParticleTask;

    public WheelOfFortuneService(JavaPlugin plugin, Supplier<CasinoConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configSupplier = Objects.requireNonNull(configSupplier, "configSupplier");
    }

    public void start() {
        rebuildMachines();
        startParticles();
    }

    public void reload() {
        stop(true);
        start();
    }

    public void stop() {
        stop(false);
    }

    private void stop(boolean closeInventories) {
        if (idleParticleTask != null) {
            idleParticleTask.cancel();
            idleParticleTask = null;
        }
        if (occupiedParticleTask != null) {
            occupiedParticleTask.cancel();
            occupiedParticleTask = null;
        }
        for (WheelSession session : new ArrayList<>(sessionsByPlayer.values())) {
            Player player = Bukkit.getPlayer(session.playerId());
            endSession(session, player, closeInventories);
        }
        sessionsByPlayer.clear();
        occupiedMachines.clear();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        WheelSession activeSession = sessionsByPlayer.get(event.getPlayer().getUniqueId());
        if (activeSession != null && activeSession.state() == WheelSession.State.SHOWING_RESULT) {
            event.setCancelled(true);
            return;
        }

        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }

        CasinoConfig.Machine machine = machinesByLocation.get(MachineKey.from(block));
        if (machine == null || block.getType() != machine.activationMaterial()) {
            return;
        }

        event.setCancelled(true);
        openMachine(event.getPlayer(), machine);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof WheelGuiHolder holder)) {
            return;
        }
        if (!holder.playerId().equals(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        WheelSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session == null) {
            player.closeInventory();
            return;
        }

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= session.inventory().getSize()) {
            return;
        }

        CasinoConfig config = configSupplier.get();
        CasinoConfig.WheelGui gui = config.wheelOfFortune().gui();
        if (slot == gui.exitSlot()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                WheelSession current = sessionsByPlayer.get(player.getUniqueId());
                if (current != null) {
                    endSession(current, player, true);
                }
            });
            return;
        }

        if (session.state() != WheelSession.State.IDLE) {
            return;
        }

        if (slot == gui.multiplierSlot() && event.getClick().isLeftClick()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                WheelSession current = sessionsByPlayer.get(player.getUniqueId());
                if (current != null && current.state() == WheelSession.State.IDLE) {
                    current.multiplierIndex(nextIndex(current.multiplierIndex(), config.wheelOfFortune().multiplierOptions().size()));
                    render(current, player);
                }
            });
            return;
        }

        if (slot == gui.actionSlot()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                WheelSession current = sessionsByPlayer.get(player.getUniqueId());
                if (current != null && current.state() == WheelSession.State.IDLE) {
                    startSpin(player, current);
                }
            });
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof WheelGuiHolder)) {
            return;
        }

        WheelSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session == null || session.ending() || session.suppressCloseReopen()) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            WheelSession current = sessionsByPlayer.get(player.getUniqueId());
            if (current != null && !current.ending() && player.isOnline()) {
                player.openInventory(current.inventory());
            }
        });
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        WheelSession session = sessionsByPlayer.get(event.getPlayer().getUniqueId());
        if (session == null || session.state() != WheelSession.State.SHOWING_RESULT) {
            return;
        }

        Location to = event.getTo();
        if (to == null || samePosition(event.getFrom(), to)) {
            return;
        }

        Location locked = session.lockedLocation();
        if (locked == null) {
            locked = event.getFrom().clone();
            session.lockedLocation(locked);
        }
        Location target = locked.clone();
        target.setYaw(to.getYaw());
        target.setPitch(to.getPitch());
        event.setTo(target);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        WheelSession session = sessionsByPlayer.get(event.getPlayer().getUniqueId());
        if (session != null) {
            endSession(session, event.getPlayer(), false);
        }
    }

    private void openMachine(Player player, CasinoConfig.Machine machine) {
        CasinoConfig config = configSupplier.get();
        if (TEMPORARILY_UNAVAILABLE) {
            player.sendActionBar(Text.component(config.messages().machineUnavailable()));
            play(player, config.sounds().noFunds());
            return;
        }
        if (sessionsByPlayer.containsKey(player.getUniqueId())) {
            player.sendActionBar(Text.component(config.messages().alreadyPlaying()));
            return;
        }

        UUID occupiedBy = occupiedMachines.get(machine.id());
        if (occupiedBy != null && !occupiedBy.equals(player.getUniqueId())) {
            player.sendActionBar(Text.component(config.messages().machineBusy()));
            return;
        }

        World world = Bukkit.getWorld(machine.world());
        if (world == null) {
            plugin.getLogger().warning("Wheel of Fortune world is not loaded: " + machine.world());
            return;
        }

        CasinoConfig.PlayerLocation target = machine.playerLocation();
        player.teleport(new Location(world, target.x(), target.y(), target.z(), target.yaw(), target.pitch()));

        WheelGuiHolder holder = new WheelGuiHolder(player.getUniqueId(), machine.id());
        CasinoConfig.WheelGui gui = config.wheelOfFortune().gui();
        Inventory inventory = Bukkit.createInventory(holder, gui.size(), Text.legacy(gui.title(), Map.of()));
        holder.setInventory(inventory);

        WheelSession session = new WheelSession(
                player.getUniqueId(),
                machine,
                inventory,
                optionIndex(config.wheelOfFortune().multiplierOptions(), config.wheelOfFortune().defaultMultiplier())
        );
        sessionsByPlayer.put(player.getUniqueId(), session);
        occupiedMachines.put(machine.id(), player.getUniqueId());
        render(session, player);
        player.openInventory(inventory);
        play(player, config.sounds().open());
    }

    private void endSession(WheelSession session, Player player, boolean closeInventory) {
        session.ending(true);
        if (session.spinTask() != null) {
            session.spinTask().cancel();
            session.spinTask(null);
        }
        sessionsByPlayer.remove(session.playerId());
        occupiedMachines.remove(session.machine().id());

        if (player != null && player.isOnline()) {
            CasinoConfig config = configSupplier.get();
            play(player, config.sounds().close());
            if (config.wheelOfFortune().exitVelocity().enabled()) {
                Vector velocity = player.getLocation().getDirection()
                        .multiply(-config.wheelOfFortune().exitVelocity().backwardsStrength());
                velocity.setY(config.wheelOfFortune().exitVelocity().y());
                player.setVelocity(velocity);
            }
            if (closeInventory) {
                player.closeInventory();
            }
        }
    }

    private void startSpin(Player player, WheelSession session) {
        CasinoConfig config = configSupplier.get();
        OptionalDouble balance = CasinoEconomy.balance(player, config);
        double cost = multiplier(config, session);
        if (balance.isEmpty()) {
            player.sendActionBar(Text.component(config.messages().economyUnavailableActionbar()));
            play(player, config.sounds().noFunds());
            return;
        }
        if (balance.getAsDouble() + 0.0001D < cost) {
            player.sendActionBar(Text.component(config.messages().noFundsActionbar(), placeholders(player, session, balance)));
            play(player, config.sounds().noFunds());
            render(session, player);
            return;
        }
        if (!CasinoEconomy.dispatch(config.economy().removeCommand(), player, cost)) {
            player.sendActionBar(Text.component(config.messages().economyUnavailableActionbar()));
            play(player, config.sounds().noFunds());
            return;
        }

        session.state(WheelSession.State.SPINNING);
        int slotCount = config.wheelOfFortune().gui().wheelSlots().size();
        int targetIndex = pickTargetWheelIndex(config);
        int loops = ThreadLocalRandom.current().nextInt(2, 5);
        int distance = (targetIndex - session.wheelIndex() + slotCount) % slotCount;
        int[] remainingSteps = {Math.max(1, loops * slotCount + distance)};
        play(player, config.sounds().spinStart());
        render(session, player);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            WheelSession current = sessionsByPlayer.get(session.playerId());
            Player online = Bukkit.getPlayer(session.playerId());
            if (current != session || online == null || !online.isOnline()) {
                if (current != null) {
                    endSession(current, null, false);
                }
                return;
            }
            int currentIndex = (session.wheelIndex() + 1) % slotCount;
            session.wheelIndex(currentIndex);
            renderWheel(session);
            renderControls(session, online);
            play(online, configSupplier.get().sounds().rollTick());
            remainingSteps[0]--;
            if (remainingSteps[0] <= 0) {
                finishSpin(online, session);
            }
        }, 0L, config.wheelOfFortune().spinTickInterval());
        session.spinTask(task);
    }

    private void finishSpin(Player player, WheelSession session) {
        if (session.spinTask() != null) {
            session.spinTask().cancel();
            session.spinTask(null);
        }

        CasinoConfig config = configSupplier.get();
        CasinoConfig.WheelSegment segment = segmentAt(config, session.wheelIndex());
        double win = multiplier(config, session) * segment.multiplier();
        if (win > 0.0D) {
            CasinoEconomy.dispatch(config.economy().addCommand(), player, win);
            play(player, win >= multiplier(config, session) * 10.0D ? config.sounds().winBig() : config.sounds().winSmall());
        } else {
            play(player, config.sounds().lose());
        }
        showResult(session, player, segment, win);
    }

    private void showResult(WheelSession session, Player player, CasinoConfig.WheelSegment segment, double win) {
        if (player == null || !player.isOnline()) {
            return;
        }
        CasinoConfig config = configSupplier.get();
        session.state(WheelSession.State.SHOWING_RESULT);
        session.lockedLocation(player.getLocation().clone());
        session.suppressCloseReopen(true);
        player.closeInventory();
        player.sendTitle(
                Text.legacy(segment.name(), Map.of()),
                win > 0.0D ? "§aWygrana: §f" + CasinoEconomy.money(win) + "$" : "§cPrzegrana",
                0,
                config.wheelOfFortune().resultSubtitleTicks(),
                0
        );

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            WheelSession current = sessionsByPlayer.get(session.playerId());
            Player online = Bukkit.getPlayer(session.playerId());
            if (current != session || online == null || !online.isOnline()) {
                return;
            }
            session.suppressCloseReopen(false);
            session.lockedLocation(null);
            session.state(WheelSession.State.IDLE);
            render(session, online);
            online.openInventory(session.inventory());
        }, config.wheelOfFortune().resultSubtitleTicks());
    }

    private void render(WheelSession session, Player player) {
        CasinoConfig config = configSupplier.get();
        ItemStack filler = item(config.wheelOfFortune().gui().filler(), placeholders(player, session, CasinoEconomy.balance(player, config)));
        for (int slot = 0; slot < session.inventory().getSize(); slot++) {
            session.inventory().setItem(slot, filler.clone());
        }
        renderWheel(session);
        renderControls(session, player);
    }

    private void renderWheel(WheelSession session) {
        CasinoConfig config = configSupplier.get();
        CasinoConfig.WheelGui gui = config.wheelOfFortune().gui();
        for (int index = 0; index < gui.wheelSlots().size(); index++) {
            int slot = gui.wheelSlots().get(index);
            CasinoConfig.WheelSegment segment = segmentAt(config, index);
            session.inventory().setItem(slot, segmentItem(segment, index == session.wheelIndex()));
        }
    }

    private void renderControls(WheelSession session, Player player) {
        CasinoConfig config = configSupplier.get();
        CasinoConfig.WheelGui gui = config.wheelOfFortune().gui();
        OptionalDouble balance = CasinoEconomy.balance(player, config);
        Map<String, String> placeholders = placeholders(player, session, balance);
        set(session.inventory(), gui.balanceSlot(), item(gui.balanceItem(), placeholders));
        set(session.inventory(), gui.multiplierSlot(), item(gui.multiplierItem(), placeholders));
        set(session.inventory(), gui.exitSlot(), item(gui.exitItem(), placeholders));
        set(session.inventory(), gui.infoSlot(), infoItem(gui.infoItem(), gui.infoSegmentLine(), placeholders));

        if (session.state() == WheelSession.State.SPINNING) {
            set(session.inventory(), gui.actionSlot(), item(gui.rollingItem(), placeholders));
        } else if (balance.isPresent() && balance.getAsDouble() + 0.0001D >= multiplier(config, session)) {
            set(session.inventory(), gui.actionSlot(), item(gui.spinItem(), placeholders));
        } else {
            set(session.inventory(), gui.actionSlot(), item(gui.noFundsItem(), placeholders));
        }
        player.updateInventory();
    }

    private ItemStack infoItem(CasinoConfig.GuiItem config, String segmentLine, Map<String, String> placeholders) {
        ItemStack stack = new ItemStack(config.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.component(config.name(), placeholders));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            for (String line : config.lore()) {
                if ("{segment_payouts}".equals(line)) {
                    lore.addAll(segmentPayoutLines(segmentLine));
                } else {
                    lore.add(Text.component(line, placeholders));
                }
            }
            meta.lore(lore.isEmpty() ? null : lore);
            applyFlags(meta, config);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private List<net.kyori.adventure.text.Component> segmentPayoutLines(String segmentLine) {
        CasinoConfig config = configSupplier.get();
        List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
        int index = 1;
        for (CasinoConfig.WheelSegment segment : config.wheelOfFortune().segments()) {
            lines.add(Text.component(segmentLine, Map.of(
                    "index", Integer.toString(index),
                    "segment", segment.id(),
                    "segment_name", segment.name(),
                    "multiplier", CasinoEconomy.money(segment.multiplier())
            )));
            index++;
        }
        return lines;
    }

    private ItemStack segmentItem(CasinoConfig.WheelSegment segment, boolean highlighted) {
        ItemStack stack = new ItemStack(segment.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            Map<String, String> placeholders = Map.of(
                    "segment", segment.id(),
                    "segment_name", segment.name(),
                    "multiplier", CasinoEconomy.money(segment.multiplier())
            );
            String name = highlighted ? "&e&l> " + segment.name() : segment.name();
            meta.displayName(Text.component(name, placeholders));
            meta.lore(segment.lore().isEmpty() ? null : Text.lore(segment.lore(), placeholders));
            meta.addItemFlags(ItemFlag.values());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack item(CasinoConfig.GuiItem config, Map<String, String> placeholders) {
        ItemStack stack = new ItemStack(config.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.component(config.name(), placeholders));
            meta.lore(config.lore().isEmpty() ? null : Text.lore(config.lore(), placeholders));
            applyFlags(meta, config);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void applyFlags(ItemMeta meta, CasinoConfig.GuiItem config) {
        if (config.hideAdditionalTooltip()) {
            meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        }
        if (config.hideTooltip()) {
            meta.addItemFlags(ItemFlag.values());
            try {
                meta.setHideTooltip(true);
            } catch (Throwable ignored) {
                // Keeps compatibility if a different 1.21 server API is used during local tests.
            }
        }
    }

    private int pickTargetWheelIndex(CasinoConfig config) {
        CasinoConfig.WheelSegment segment = pickWeightedSegment(config);
        List<Integer> matchingIndexes = new ArrayList<>();
        List<String> layout = config.wheelOfFortune().segmentLayout();
        int slotCount = config.wheelOfFortune().gui().wheelSlots().size();
        for (int index = 0; index < slotCount; index++) {
            String id = layout.get(index % layout.size());
            if (id.equals(segment.id())) {
                matchingIndexes.add(index);
            }
        }
        if (matchingIndexes.isEmpty()) {
            return ThreadLocalRandom.current().nextInt(slotCount);
        }
        return matchingIndexes.get(ThreadLocalRandom.current().nextInt(matchingIndexes.size()));
    }

    private CasinoConfig.WheelSegment pickWeightedSegment(CasinoConfig config) {
        double totalWeight = config.wheelOfFortune().segments().stream()
                .mapToDouble(segment -> Math.max(0.0D, segment.chanceWeight()))
                .sum();
        if (totalWeight <= 0.0D) {
            return config.wheelOfFortune().segments().get(ThreadLocalRandom.current().nextInt(config.wheelOfFortune().segments().size()));
        }
        double roll = ThreadLocalRandom.current().nextDouble(totalWeight);
        double cursor = 0.0D;
        for (CasinoConfig.WheelSegment segment : config.wheelOfFortune().segments()) {
            cursor += Math.max(0.0D, segment.chanceWeight());
            if (roll < cursor) {
                return segment;
            }
        }
        return config.wheelOfFortune().segments().getLast();
    }

    private CasinoConfig.WheelSegment segmentAt(CasinoConfig config, int index) {
        List<String> layout = config.wheelOfFortune().segmentLayout();
        String id = layout.get(index % layout.size());
        CasinoConfig.WheelSegment segment = config.wheelOfFortune().segmentsById().get(id);
        return segment == null ? config.wheelOfFortune().segments().getFirst() : segment;
    }

    private void rebuildMachines() {
        machinesByLocation.clear();
        CasinoConfig config = configSupplier.get();
        for (CasinoConfig.Machine machine : config.wheelOfFortune().machines().values()) {
            machinesByLocation.put(MachineKey.from(machine.world(), machine.activationBlock()), machine);
        }
    }

    private void startParticles() {
        CasinoConfig config = configSupplier.get();
        if (!TEMPORARILY_UNAVAILABLE && config.idleParticles().enabled() && config.idleParticles().count() > 0) {
            idleParticleTask = Bukkit.getScheduler().runTaskTimer(plugin,
                    () -> spawnMachineParticles(false),
                    0L,
                    config.idleParticles().intervalTicks());
        }
        if (config.occupiedParticles().enabled() && config.occupiedParticles().count() > 0) {
            occupiedParticleTask = Bukkit.getScheduler().runTaskTimer(plugin,
                    () -> spawnMachineParticles(true),
                    0L,
                    config.occupiedParticles().intervalTicks());
        }
    }

    private void spawnMachineParticles(boolean occupied) {
        CasinoConfig config = configSupplier.get();
        CasinoConfig.ParticleSetting setting = occupied ? config.occupiedParticles() : config.idleParticles();
        for (CasinoConfig.Machine machine : config.wheelOfFortune().machines().values()) {
            boolean unavailableOrOccupied = TEMPORARILY_UNAVAILABLE || occupiedMachines.containsKey(machine.id());
            if (unavailableOrOccupied != occupied) {
                continue;
            }
            World world = Bukkit.getWorld(machine.world());
            if (world == null) {
                continue;
            }
            CasinoConfig.BlockLocation block = machine.activationBlock();
            Location location = new Location(world, block.x() + 0.5D, block.y() + setting.yOffset(), block.z() + 0.5D);
            spawnParticles(world, location, setting);
        }
    }

    private void spawnParticles(World world, Location location, CasinoConfig.ParticleSetting setting) {
        if (setting.particle() == Particle.DUST) {
            Particle.DustOptions dust = new Particle.DustOptions(
                    Color.fromRGB(setting.red(), setting.green(), setting.blue()),
                    setting.size()
            );
            world.spawnParticle(setting.particle(), location, setting.count(), setting.offsetX(), setting.offsetY(),
                    setting.offsetZ(), setting.speed(), dust);
            return;
        }
        world.spawnParticle(setting.particle(), location, setting.count(), setting.offsetX(), setting.offsetY(),
                setting.offsetZ(), setting.speed());
    }

    private Map<String, String> placeholders(Player player, WheelSession session, OptionalDouble balance) {
        double multiplier = multiplier(configSupplier.get(), session);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", player.getName());
        values.put("uuid", player.getUniqueId().toString());
        values.put("balance", balance.isPresent() ? CasinoEconomy.money(balance.getAsDouble()) : "0");
        values.put("balance_display", balance.isPresent() ? CasinoEconomy.money(balance.getAsDouble()) : "-");
        values.put("multiplier", CasinoEconomy.money(multiplier));
        values.put("bet_per_line", CasinoEconomy.money(multiplier));
        values.put("total_cost", CasinoEconomy.money(multiplier));
        return values;
    }

    private double multiplier(CasinoConfig config, WheelSession session) {
        return config.wheelOfFortune().multiplierOptions()
                .get(Math.min(session.multiplierIndex(), config.wheelOfFortune().multiplierOptions().size() - 1));
    }

    private int optionIndex(List<Double> options, double preferred) {
        for (int index = 0; index < options.size(); index++) {
            if (Math.abs(options.get(index) - preferred) < 0.0001D) {
                return index;
            }
        }
        return 0;
    }

    private int nextIndex(int current, int size) {
        if (size <= 1) {
            return 0;
        }
        return (current + 1) % size;
    }

    private void set(Inventory inventory, int slot, ItemStack item) {
        if (slot >= 0 && slot < inventory.getSize()) {
            inventory.setItem(slot, item);
        }
    }

    private boolean samePosition(Location first, Location second) {
        return Objects.equals(first.getWorld(), second.getWorld())
                && Double.compare(first.getX(), second.getX()) == 0
                && Double.compare(first.getY(), second.getY()) == 0
                && Double.compare(first.getZ(), second.getZ()) == 0;
    }

    private void play(Player player, List<CasinoConfig.SoundSetting> settings) {
        for (CasinoConfig.SoundSetting setting : settings) {
            if (!setting.enabled()) {
                continue;
            }
            if (setting.delayTicks() <= 0) {
                playOne(player, setting);
            } else {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        playOne(player, setting);
                    }
                }, setting.delayTicks());
            }
        }
    }

    private void playOne(Player player, CasinoConfig.SoundSetting setting) {
        try {
            Sound sound = Sound.valueOf(setting.name().trim().toUpperCase(Locale.ROOT));
            player.playSound(player.getLocation(), sound, setting.volume(), setting.pitch());
        } catch (RuntimeException ex) {
            player.playSound(player.getLocation(), setting.name(), setting.volume(), setting.pitch());
        }
    }
}
