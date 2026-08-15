package hexcasino.machine;

import hexcasino.Text;
import hexcasino.config.CasinoConfig;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
import org.bukkit.event.inventory.ClickType;
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

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SlotMachineService implements Listener {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final DecimalFormat MONEY_FORMAT =
            new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US));

    private final JavaPlugin plugin;
    private final Supplier<CasinoConfig> configSupplier;
    private final Map<MachineKey, CasinoConfig.Machine> machinesByLocation = new LinkedHashMap<>();
    private final Map<UUID, SlotMachineSession> sessionsByPlayer = new HashMap<>();
    private final Map<String, UUID> occupiedMachines = new HashMap<>();

    private BukkitTask idleParticleTask;
    private BukkitTask occupiedParticleTask;

    public SlotMachineService(JavaPlugin plugin, Supplier<CasinoConfig> configSupplier) {
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
        for (SlotMachineSession session : new ArrayList<>(sessionsByPlayer.values())) {
            Player player = Bukkit.getPlayer(session.playerId());
            endSession(session, player, closeInventories);
        }
        sessionsByPlayer.clear();
        occupiedMachines.clear();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        SlotMachineSession activeSession = sessionsByPlayer.get(event.getPlayer().getUniqueId());
        if (activeSession != null && activeSession.state() == SlotMachineSession.State.SHOWING_RESULT) {
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
        if (!(event.getView().getTopInventory().getHolder() instanceof SlotMachineGuiHolder holder)) {
            return;
        }
        if (!holder.playerId().equals(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        SlotMachineSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session == null) {
            player.closeInventory();
            return;
        }

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= session.inventory().getSize()) {
            return;
        }

        CasinoConfig config = configSupplier.get();
        if (slot == config.gui().exitSlot()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                SlotMachineSession current = sessionsByPlayer.get(player.getUniqueId());
                if (current != null) {
                    endSession(current, player, true);
                }
            });
            return;
        }

        if (session.state() == SlotMachineSession.State.ROLLING) {
            if (slot == config.gui().actionSlot()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    SlotMachineSession current = sessionsByPlayer.get(player.getUniqueId());
                    if (current != null && current.state() == SlotMachineSession.State.ROLLING) {
                        stopNextColumn(player, current);
                    }
                });
            }
            return;
        }
        if (session.state() != SlotMachineSession.State.IDLE) {
            return;
        }

        if (slot == config.gui().betSlot()) {
            ClickType clickType = event.getClick();
            Bukkit.getScheduler().runTask(plugin, () -> {
                SlotMachineSession current = sessionsByPlayer.get(player.getUniqueId());
                if (current != null && current.state() == SlotMachineSession.State.IDLE) {
                    adjustBet(player, current, clickType);
                }
            });
            return;
        }

        if (slot == config.gui().actionSlot()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                SlotMachineSession current = sessionsByPlayer.get(player.getUniqueId());
                if (current != null && current.state() == SlotMachineSession.State.IDLE) {
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
        if (!(event.getView().getTopInventory().getHolder() instanceof SlotMachineGuiHolder)) {
            return;
        }

        SlotMachineSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session == null || session.ending()) {
            return;
        }
        if (session.suppressCloseReopen()) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            SlotMachineSession current = sessionsByPlayer.get(player.getUniqueId());
            if (current != null && !current.ending() && player.isOnline()) {
                player.openInventory(current.inventory());
            }
        });
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        SlotMachineSession session = sessionsByPlayer.get(event.getPlayer().getUniqueId());
        if (session == null || session.state() != SlotMachineSession.State.SHOWING_RESULT) {
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
        SlotMachineSession session = sessionsByPlayer.get(event.getPlayer().getUniqueId());
        if (session != null) {
            endSession(session, event.getPlayer(), false);
        }
    }

    private void openMachine(Player player, CasinoConfig.Machine machine) {
        CasinoConfig config = configSupplier.get();
        SlotMachineSession existing = sessionsByPlayer.get(player.getUniqueId());
        if (existing != null) {
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
            plugin.getLogger().warning("Machine world is not loaded: " + machine.world());
            return;
        }

        CasinoConfig.PlayerLocation target = machine.playerLocation();
        player.teleport(new Location(world, target.x(), target.y(), target.z(), target.yaw(), target.pitch()));

        SlotMachineGuiHolder holder = new SlotMachineGuiHolder(player.getUniqueId(), machine.id());
        Inventory inventory = Bukkit.createInventory(holder, config.gui().size(), Text.legacy(config.gui().title(), Map.of()));
        holder.setInventory(inventory);

        String[] initialSymbols = config.initialSymbols().toArray(String[]::new);
        SlotMachineSession session = new SlotMachineSession(
                player.getUniqueId(),
                machine,
                inventory,
                optionIndex(config.slotMachine().betPerLineOptions(), config.slotMachine().defaultBetPerLine()),
                optionIndex(config.slotMachine().lineOptions(), config.slotMachine().defaultLines()),
                initialSymbols
        );

        sessionsByPlayer.put(player.getUniqueId(), session);
        occupiedMachines.put(machine.id(), player.getUniqueId());
        render(session, player);
        player.openInventory(inventory);
        play(player, config.sounds().open());
    }

    private void endSession(SlotMachineSession session, Player player, boolean closeInventory) {
        session.ending(true);
        if (session.rollTask() != null) {
            session.rollTask().cancel();
            session.rollTask(null);
        }
        sessionsByPlayer.remove(session.playerId());
        occupiedMachines.remove(session.machine().id());

        if (player != null && player.isOnline()) {
            play(player, configSupplier.get().sounds().close());
            if (configSupplier.get().slotMachine().exitVelocity().enabled()) {
                Vector velocity = player.getLocation().getDirection()
                        .multiply(-configSupplier.get().slotMachine().exitVelocity().backwardsStrength());
                velocity.setY(configSupplier.get().slotMachine().exitVelocity().y());
                player.setVelocity(velocity);
            }
            if (closeInventory) {
                player.closeInventory();
            }
        }
    }

    private void adjustBet(Player player, SlotMachineSession session, ClickType clickType) {
        CasinoConfig config = configSupplier.get();
        if (clickType.isLeftClick()) {
            session.betIndex(nextIndex(session.betIndex(), config.slotMachine().betPerLineOptions().size()));
        } else if (clickType.isRightClick()) {
            session.lineIndex(nextIndex(session.lineIndex(), config.slotMachine().lineOptions().size()));
        } else {
            return;
        }
        render(session, player);
        player.sendActionBar(Text.component(config.messages().betChangedActionbar(), placeholders(player, session, balance(player))));
    }

    private int nextIndex(int current, int size) {
        if (size <= 1) {
            return 0;
        }
        return (current + 1) % size;
    }

    private void startSpin(Player player, SlotMachineSession session) {
        CasinoConfig config = configSupplier.get();
        OptionalDouble balance = balance(player);
        if (balance.isEmpty()) {
            player.sendActionBar(Text.component(config.messages().economyUnavailableActionbar(), placeholders(player, session, balance)));
            play(player, config.sounds().noFunds());
            return;
        }
        if (balance.getAsDouble() + 0.0001D < totalCost(config, session)) {
            player.sendActionBar(Text.component(config.messages().noFundsActionbar(), placeholders(player, session, balance)));
            play(player, config.sounds().noFunds());
            render(session, player);
            return;
        }

        if (!dispatchEconomyCommand(config.economy().removeCommand(), player, totalCost(config, session))) {
            player.sendActionBar(Text.component(config.messages().economyUnavailableActionbar(), placeholders(player, session, balance)));
            play(player, config.sounds().noFunds());
            return;
        }

        session.state(SlotMachineSession.State.ROLLING);
        session.resetStoppedColumns();
        player.sendActionBar(Text.component(config.messages().spinStartActionbar(), placeholders(player, session, balance)));
        play(player, config.sounds().spinStart());
        render(session, player);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> rollTick(player.getUniqueId()), 0L,
                config.slotMachine().rollTickInterval());
        session.rollTask(task);
    }

    private void rollTick(UUID playerId) {
        SlotMachineSession session = sessionsByPlayer.get(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (session == null || player == null || !player.isOnline()) {
            if (session != null) {
                endSession(session, null, false);
            }
            return;
        }
        if (session.state() != SlotMachineSession.State.ROLLING) {
            return;
        }

        for (int column = 0; column < 3; column++) {
            if (session.columnStopped(column)) {
                continue;
            }
            for (int row = 0; row < 3; row++) {
                session.symbol((row * 3) + column, randomSymbolByWeight().id());
            }
        }
        renderReels(session);
        renderControls(session, player);
        play(player, configSupplier.get().sounds().rollTick());
    }

    private void stopNextColumn(Player player, SlotMachineSession session) {
        int column = session.stopNextColumn();
        if (column < 0) {
            return;
        }
        play(player, configSupplier.get().sounds().columnStop());
        renderControls(session, player);
        if (session.allColumnsStopped()) {
            finishSpin(player, session);
        }
    }

    private void finishSpin(Player player, SlotMachineSession session) {
        if (session.rollTask() != null) {
            session.rollTask().cancel();
            session.rollTask(null);
        }
        session.state(SlotMachineSession.State.IDLE);

        CasinoConfig config = configSupplier.get();
        SpinResult result = evaluate(session);
        if (result.win() <= 0.0D && applyWinAssist(session)) {
            renderReels(session);
            result = evaluate(session);
        }
        OptionalDouble balance = balance(player);
        if (result.win() > 0.0D) {
            session.state(SlotMachineSession.State.SHOWING_RESULT);
            dispatchEconomyCommand(config.economy().addCommand(), player, result.win());
            CasinoConfig.Symbol bestSymbol = result.bestSymbol();
            String actionbar = bestSymbol != null && !bestSymbol.winActionbar().isBlank()
                    ? bestSymbol.winActionbar()
                    : config.messages().winActionbar();
            player.sendActionBar(Text.component(actionbar, resultPlaceholders(player, session, balance, result)));
            List<CasinoConfig.SoundSetting> winSounds = bestSymbol != null && !bestSymbol.winSounds().isEmpty()
                    ? bestSymbol.winSounds()
                    : (result.win() >= totalCost(config, session) * 5.0D ? config.sounds().winBig() : config.sounds().winSmall());
            play(player, winSounds);
            if (bestSymbol != null) {
                spawnSymbolWinParticles(player, bestSymbol);
            }
            highlightWin(session, player, result);
        } else {
            player.sendActionBar(Text.component(config.messages().loseActionbar(), placeholders(player, session, balance)));
            play(player, config.sounds().lose());
            render(session, player);
        }
    }

    private SpinResult evaluate(SlotMachineSession session) {
        CasinoConfig config = configSupplier.get();
        int activeLines = activeLines(config, session);
        List<CasinoConfig.WinningLine> winningLines = new ArrayList<>();
        double win = 0.0D;
        CasinoConfig.Symbol bestSymbol = null;
        for (int index = 0; index < activeLines && index < config.winningLines().size(); index++) {
            CasinoConfig.WinningLine line = config.winningLines().get(index);
            if (line.slots().size() != 3) {
                continue;
            }
            int firstIndex = reelIndex(config, line.slots().get(0));
            int secondIndex = reelIndex(config, line.slots().get(1));
            int thirdIndex = reelIndex(config, line.slots().get(2));
            if (firstIndex < 0 || secondIndex < 0 || thirdIndex < 0) {
                continue;
            }

            String first = session.symbol(firstIndex);
            if (first.equals(session.symbol(secondIndex)) && first.equals(session.symbol(thirdIndex))) {
                CasinoConfig.Symbol symbol = config.symbolsById().get(first);
                if (symbol != null) {
                    win += betPerLine(config, session) * symbol.multiplier();
                    winningLines.add(line);
                    if (bestSymbol == null || symbol.multiplier() > bestSymbol.multiplier()) {
                        bestSymbol = symbol;
                    }
                }
            }
        }
        return new SpinResult(win, List.copyOf(winningLines), bestSymbol);
    }

    private boolean applyWinAssist(SlotMachineSession session) {
        CasinoConfig config = configSupplier.get();
        CasinoConfig.WinAssist assist = config.slotMachine().winAssist();
        if (!assist.enabled() || assist.chancePercent() <= 0.0D) {
            return false;
        }
        if (ThreadLocalRandom.current().nextDouble(100.0D) >= assist.chancePercent()) {
            return false;
        }

        int activeLines = activeLines(config, session);
        if (activeLines <= 0) {
            return false;
        }
        CasinoConfig.WinningLine line = config.winningLines().get(ThreadLocalRandom.current().nextInt(activeLines));
        CasinoConfig.Symbol symbol = randomSymbolByWeight();
        for (int slot : line.slots()) {
            int index = reelIndex(config, slot);
            if (index >= 0) {
                session.symbol(index, symbol.id());
            }
        }
        return true;
    }

    private void highlightWin(SlotMachineSession session, Player player, SpinResult result) {
        CasinoConfig config = configSupplier.get();
        if (!config.slotMachine().highlight().enabled() || result.lines().isEmpty()) {
            showWinSubtitle(session, player, result);
            return;
        }

        Set<Integer> slots = new LinkedHashSet<>();
        for (CasinoConfig.WinningLine line : result.lines()) {
            slots.addAll(line.slots());
        }
        ItemStack highlight = item(config.gui().highlightItem(), resultPlaceholders(player, session, balance(player), result));
        flashWin(session, player, result, slots, highlight, 0, true);
    }

    private void flashWin(SlotMachineSession session,
                          Player player,
                          SpinResult result,
                          Set<Integer> slots,
                          ItemStack highlight,
                          int shownFlashes,
                          boolean showHighlight) {
        SlotMachineSession current = sessionsByPlayer.get(session.playerId());
        Player online = Bukkit.getPlayer(session.playerId());
        if (current != session || online == null || !online.isOnline()) {
            return;
        }

        CasinoConfig config = configSupplier.get();
        int flashCount = config.slotMachine().highlight().flashCount();
        if (showHighlight) {
            for (int slot : slots) {
                if (slot >= 0 && slot < session.inventory().getSize()) {
                    session.inventory().setItem(slot, highlight.clone());
                }
            }
            renderControls(session, online);
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    flashWin(session, online, result, slots, highlight, shownFlashes + 1, false),
                    config.slotMachine().highlight().durationTicks());
            return;
        }

        renderReels(session);
        renderControls(session, online);
        if (shownFlashes >= flashCount) {
            showWinSubtitle(session, online, result);
            return;
        }

        Bukkit.getScheduler().runTaskLater(plugin, () ->
                flashWin(session, online, result, slots, highlight, shownFlashes, true),
                config.slotMachine().highlight().durationTicks());
    }

    private void showWinSubtitle(SlotMachineSession session, Player player, SpinResult result) {
        SlotMachineSession current = sessionsByPlayer.get(session.playerId());
        if (current != session || player == null || !player.isOnline()) {
            return;
        }

        CasinoConfig config = configSupplier.get();
        session.lockedLocation(player.getLocation().clone());
        session.suppressCloseReopen(true);
        player.closeInventory();
        player.sendTitle(
                "",
                Text.legacy(config.messages().winSubtitle(), resultPlaceholders(player, session, balance(player), result)),
                0,
                config.slotMachine().resultSubtitleTicks(),
                0
        );

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            SlotMachineSession latest = sessionsByPlayer.get(session.playerId());
            Player online = Bukkit.getPlayer(session.playerId());
            if (latest != session || online == null || !online.isOnline()) {
                return;
            }
            session.suppressCloseReopen(false);
            session.lockedLocation(null);
            session.state(SlotMachineSession.State.IDLE);
            render(session, online);
            online.openInventory(session.inventory());
        }, config.slotMachine().resultSubtitleTicks());
    }

    private boolean samePosition(Location first, Location second) {
        return Objects.equals(first.getWorld(), second.getWorld())
                && Double.compare(first.getX(), second.getX()) == 0
                && Double.compare(first.getY(), second.getY()) == 0
                && Double.compare(first.getZ(), second.getZ()) == 0;
    }

    private void render(SlotMachineSession session, Player player) {
        CasinoConfig config = configSupplier.get();
        ItemStack filler = item(config.gui().filler(), placeholders(player, session, balance(player)));
        for (int slot = 0; slot < session.inventory().getSize(); slot++) {
            session.inventory().setItem(slot, filler.clone());
        }
        renderReels(session);
        renderControls(session, player);
    }

    private void renderReels(SlotMachineSession session) {
        CasinoConfig config = configSupplier.get();
        for (int index = 0; index < config.gui().reelSlots().size(); index++) {
            CasinoConfig.Symbol symbol = config.symbolsById().get(session.symbol(index));
            if (symbol == null) {
                symbol = config.symbols().getFirst();
            }
            session.inventory().setItem(config.gui().reelSlots().get(index), symbolItem(symbol));
        }
    }

    private void renderControls(SlotMachineSession session, Player player) {
        CasinoConfig config = configSupplier.get();
        OptionalDouble balance = balance(player);
        Map<String, String> placeholders = placeholders(player, session, balance);
        set(session.inventory(), config.gui().balanceSlot(), item(config.gui().balanceItem(), placeholders));
        set(session.inventory(), config.gui().betSlot(), item(config.gui().betItem(), placeholders));
        set(session.inventory(), config.gui().exitSlot(), item(config.gui().exitItem(), placeholders));
        set(session.inventory(), config.gui().infoSlot(), infoItem(config.gui().infoItem(), placeholders,
                betPerLine(config, session)));

        if (session.state() == SlotMachineSession.State.ROLLING) {
            set(session.inventory(), config.gui().actionSlot(), item(config.gui().rollingItem(), placeholders));
        } else if (balance.isPresent() && balance.getAsDouble() + 0.0001D >= totalCost(config, session)) {
            set(session.inventory(), config.gui().actionSlot(), item(config.gui().spinAvailableItem(), placeholders));
        } else {
            set(session.inventory(), config.gui().actionSlot(), item(config.gui().spinUnavailableItem(), placeholders));
        }
        player.updateInventory();
    }

    private ItemStack infoItem(CasinoConfig.GuiItem config, Map<String, String> placeholders, double betPerLine) {
        ItemStack stack = new ItemStack(config.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.component(config.name(), placeholders));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            for (String line : config.lore()) {
                if ("{symbol_payouts}".equals(line)) {
                    lore.addAll(symbolPayoutLines(betPerLine));
                } else {
                    lore.add(Text.component(line, placeholders));
                }
            }
            meta.lore(lore.isEmpty() ? null : lore);
            if (config.hideAdditionalTooltip()) {
                meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            }
            if (config.hideTooltip()) {
                hideTooltip(meta);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private List<net.kyori.adventure.text.Component> symbolPayoutLines(double betPerLine) {
        CasinoConfig config = configSupplier.get();
        List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
        int index = 1;
        for (CasinoConfig.Symbol symbol : config.symbols()) {
            Map<String, String> values = Map.of(
                    "index", Integer.toString(index),
                    "symbol", symbol.id(),
                    "symbol_name", symbol.displayName(),
                    "legend_name", symbol.legendName(),
                    "bet_per_line", money(betPerLine),
                    "multiplier", money(symbol.multiplier()),
                    "payout", money(betPerLine * symbol.multiplier())
            );
            lines.add(Text.component(config.gui().infoSymbolLine(), values));
            index++;
        }
        return lines;
    }

    private ItemStack symbolItem(CasinoConfig.Symbol symbol) {
        ItemStack stack = new ItemStack(symbol.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            Map<String, String> placeholders = Map.of(
                    "symbol", symbol.id(),
                    "symbol_name", symbol.displayName(),
                    "legend_name", symbol.legendName(),
                    "multiplier", money(symbol.multiplier())
            );
            meta.displayName(Text.component(symbol.displayName(), placeholders));
            if (symbol.lore().isEmpty()) {
                meta.lore(null);
            } else {
                meta.lore(Text.lore(symbol.lore(), placeholders));
            }
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
            if (config.lore().isEmpty()) {
                meta.lore(null);
            } else {
                meta.lore(Text.lore(config.lore(), placeholders));
            }
            if (config.hideAdditionalTooltip()) {
                meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            }
            if (config.hideTooltip()) {
                hideTooltip(meta);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void set(Inventory inventory, int slot, ItemStack item) {
        if (slot >= 0 && slot < inventory.getSize()) {
            inventory.setItem(slot, item);
        }
    }

    private void rebuildMachines() {
        machinesByLocation.clear();
        CasinoConfig config = configSupplier.get();
        for (CasinoConfig.Machine machine : config.machines().values()) {
            machinesByLocation.put(MachineKey.from(machine.world(), machine.activationBlock()), machine);
        }
    }

    private void startParticles() {
        CasinoConfig config = configSupplier.get();
        if (config.idleParticles().enabled() && config.idleParticles().count() > 0) {
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
        for (CasinoConfig.Machine machine : config.machines().values()) {
            if (occupiedMachines.containsKey(machine.id()) != occupied) {
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

    private void spawnSymbolWinParticles(Player player, CasinoConfig.Symbol symbol) {
        CasinoConfig.ParticleSetting setting = symbol.winParticles();
        if (!setting.enabled() || setting.count() <= 0) {
            return;
        }
        Location location = player.getLocation().clone().add(0.0D, setting.yOffset(), 0.0D);
        spawnParticles(player.getWorld(), location, setting);
    }

    private OptionalDouble balance(Player player) {
        CasinoConfig config = configSupplier.get();
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return OptionalDouble.empty();
        }
        try {
            return parseMoney(PlaceholderAPI.setPlaceholders(player, config.economy().balancePlaceholder()));
        } catch (NoClassDefFoundError ex) {
            return OptionalDouble.empty();
        }
    }

    private OptionalDouble parseMoney(String raw) {
        String plain = ChatColor.stripColor(raw == null ? "" : raw).trim();
        plain = plain.replace(" ", "").replace("$", "");
        if (plain.contains(",") && plain.contains(".")) {
            plain = plain.replace(",", "");
        } else {
            plain = plain.replace(",", ".");
        }
        Matcher matcher = NUMBER_PATTERN.matcher(plain);
        if (!matcher.find()) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(Double.parseDouble(matcher.group()));
        } catch (NumberFormatException ex) {
            return OptionalDouble.empty();
        }
    }

    private boolean dispatchEconomyCommand(String rawCommand, Player player, double amount) {
        String command = Text.apply(rawCommand, Map.of(
                "player", player.getName(),
                "uuid", player.getUniqueId().toString(),
                "amount", money(amount)
        )).trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        return !command.isBlank() && Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private Map<String, String> placeholders(Player player, SlotMachineSession session, OptionalDouble balance) {
        CasinoConfig config = configSupplier.get();
        double bet = betPerLine(config, session);
        int lines = activeLines(config, session);
        double cost = totalCost(config, session);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", player.getName());
        values.put("uuid", player.getUniqueId().toString());
        values.put("balance", balance.isPresent() ? money(balance.getAsDouble()) : "0");
        values.put("balance_display", balance.isPresent() ? money(balance.getAsDouble()) : "-");
        values.put("bet_per_line", money(bet));
        values.put("lines", Integer.toString(lines));
        values.put("total_cost", money(cost));
        values.put("stopped_columns", Integer.toString(session.stoppedColumnCount()));
        return values;
    }

    private Map<String, String> resultPlaceholders(Player player,
                                                   SlotMachineSession session,
                                                   OptionalDouble balance,
                                                   SpinResult result) {
        Map<String, String> values = placeholders(player, session, balance);
        values.put("win", money(result.win()));
        values.put("winning_lines", winningLineNames(result.lines()));
        CasinoConfig.Symbol bestSymbol = result.bestSymbol();
        values.put("symbol", bestSymbol == null ? "" : bestSymbol.id());
        values.put("symbol_name", bestSymbol == null ? "" : bestSymbol.displayName());
        values.put("legend_name", bestSymbol == null ? "" : bestSymbol.legendName());
        values.put("multiplier", bestSymbol == null ? "0" : money(bestSymbol.multiplier()));
        values.put("line_win", bestSymbol == null ? "0" : money(betPerLine(configSupplier.get(), session) * bestSymbol.multiplier()));
        return values;
    }

    private String winningLineNames(List<CasinoConfig.WinningLine> lines) {
        if (lines.isEmpty()) {
            return "-";
        }
        return String.join(", ", lines.stream().map(CasinoConfig.WinningLine::name).toList());
    }

    private double betPerLine(CasinoConfig config, SlotMachineSession session) {
        return config.slotMachine().betPerLineOptions().get(Math.min(session.betIndex(), config.slotMachine().betPerLineOptions().size() - 1));
    }

    private int activeLines(CasinoConfig config, SlotMachineSession session) {
        return Math.min(config.slotMachine().lineOptions().get(Math.min(session.lineIndex(), config.slotMachine().lineOptions().size() - 1)),
                config.winningLines().size());
    }

    private double totalCost(CasinoConfig config, SlotMachineSession session) {
        return betPerLine(config, session) * activeLines(config, session);
    }

    private CasinoConfig.Symbol randomSymbolByWeight() {
        CasinoConfig config = configSupplier.get();
        double totalWeight = config.symbols().stream()
                .mapToDouble(symbol -> Math.max(0.0D, symbol.chanceWeight()))
                .sum();
        if (totalWeight <= 0.0D) {
            return config.symbols().get(ThreadLocalRandom.current().nextInt(config.symbols().size()));
        }

        double roll = ThreadLocalRandom.current().nextDouble(totalWeight);
        double cursor = 0.0D;
        for (CasinoConfig.Symbol symbol : config.symbols()) {
            cursor += Math.max(0.0D, symbol.chanceWeight());
            if (roll < cursor) {
                return symbol;
            }
        }
        return config.symbols().getLast();
    }

    private int reelIndex(CasinoConfig config, int inventorySlot) {
        return config.gui().reelSlots().indexOf(inventorySlot);
    }

    private int optionIndex(List<Double> options, double preferred) {
        for (int index = 0; index < options.size(); index++) {
            if (Math.abs(options.get(index) - preferred) < 0.0001D) {
                return index;
            }
        }
        return 0;
    }

    private int optionIndex(List<Integer> options, int preferred) {
        int index = options.indexOf(preferred);
        return index < 0 ? 0 : index;
    }

    private String money(double value) {
        return MONEY_FORMAT.format(value);
    }

    private void hideTooltip(ItemMeta meta) {
        meta.addItemFlags(ItemFlag.values());
        try {
            meta.setHideTooltip(true);
        } catch (Throwable ignored) {
            // Keeps compatibility if a different 1.21 server API is used during local tests.
        }
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

    private record SpinResult(double win, List<CasinoConfig.WinningLine> lines, CasinoConfig.Symbol bestSymbol) {
    }
}
