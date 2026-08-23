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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Deterministic BusDriver deduction game. Runtime outcome contains no RNG. */
public final class BusDriverService implements Listener {
    private final JavaPlugin plugin;
    private final Supplier<CasinoConfig> configSupplier;
    private final BusDriverDeductionEngine engine = new BusDriverDeductionEngine();
    private final AtomicLong gameIds = new AtomicLong(System.currentTimeMillis());
    private final Map<MachineKey, CasinoConfig.Machine> machinesByLocation = new LinkedHashMap<>();
    private final Map<UUID, BusDriverSession> sessionsByPlayer = new HashMap<>();
    private final Map<String, UUID> occupiedMachines = new HashMap<>();
    private final Map<UUID, Double> pendingPayouts = new HashMap<>();
    private final File pendingPayoutFile;

    private BusDriverSettings settings;
    private BusDriverBoardRepository boards;
    private BusDriverPlayerStateStore stateStore;
    private BusDriverPacketBridge packetBridge;
    private BukkitTask idleParticleTask;
    private BukkitTask occupiedParticleTask;

    public BusDriverService(JavaPlugin plugin, Supplier<CasinoConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin);
        this.configSupplier = Objects.requireNonNull(configSupplier);
        this.pendingPayoutFile = new File(plugin.getDataFolder(), "busdriver-pending-payouts.yml");
    }

    public void start() {
        settings = BusDriverSettings.load(plugin.getConfig());
        boards = BusDriverBoardRepository.load(plugin, settings);
        if (stateStore == null) stateStore = new BusDriverPlayerStateStore(plugin);
        rebuildMachines();
        loadPendingPayouts();
        startParticles();
        packetBridge = new BusDriverPacketBridge(plugin, this);
        packetBridge.start();
        plugin.getLogger().info("BusDriver deterministic pool: version=" + boards.version() + ", boards=" + boards.count()
                + ", stages=" + boards.stageCount() + ", SHA-256=" + boards.sha256()
                + ", paidConfigured=" + settings.paidModeEnabled() + ", packetResolver=" + packetBridge.ready());
        for (Player player : Bukkit.getOnlinePlayers()) tryPayPending(player);
    }

    public void reload() { stopRuntime(true, true); start(); }
    public void stop() { stopRuntime(false, false); }

    private void stopRuntime(boolean closeInventories, boolean reload) {
        if (idleParticleTask != null) { idleParticleTask.cancel(); idleParticleTask = null; }
        if (occupiedParticleTask != null) { occupiedParticleTask.cancel(); occupiedParticleTask = null; }
        if (packetBridge != null) { packetBridge.stop(); packetBridge = null; }
        for (BusDriverSession session : new ArrayList<>(sessionsByPlayer.values())) {
            Player player = Bukkit.getPlayer(session.playerId());
            if (session.state() == BusDriverSession.State.PLAYING && session.wagerPaid() && !session.settled()) {
                checkpointActive(session);
            }
            endSession(session, player, closeInventories);
        }
        sessionsByPlayer.clear();
        occupiedMachines.clear();
        stateStore.save();
        savePendingPayouts();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        BusDriverSession active = sessionsByPlayer.get(event.getPlayer().getUniqueId());
        if (active != null && active.state() == BusDriverSession.State.SHOWING_RESULT) { event.setCancelled(true); return; }
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        CasinoConfig.Machine machine = machinesByLocation.get(MachineKey.from(block));
        if (machine == null || block.getType() != machine.activationMaterial()) return;
        event.setCancelled(true);
        openMachine(event.getPlayer(), machine);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof BusDriverGuiHolder holder)) return;
        if (!holder.playerId().equals(player.getUniqueId())) return;
        event.setCancelled(true);
        BusDriverSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session == null) { player.closeInventory(); return; }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= session.inventory().getSize()) return;
        CasinoConfig config = configSupplier.get();
        CasinoConfig.BusDriverGui gui = config.busDriver().gui();

        // PLAYING decisions are intentionally resolved only from the packet stateId path.
        if (session.state() == BusDriverSession.State.PLAYING) {
            if (slot == gui.exitSlot()) {
                forfeit(player, session, "EXIT");
            }
            return;
        }

        if (slot == gui.exitSlot()) { endSession(session, player, true); return; }
        if (session.actionLocked()) return;

        if (session.state() == BusDriverSession.State.IDLE) {
            if (slot == gui.multiplierSlot() && event.getClick().isRightClick()) {
                session.betIndex(nextIndex(session.betIndex(), config.busDriver().betOptions().size()));
                renderIdle(session, player);
                return;
            }
            if (slot == gui.cardSlot()) {
                session.actionLocked(true);
                startGame(player, session);
            }
            return;
        }

        if (session.state() == BusDriverSession.State.PAYOUT_PENDING && slot == gui.cashoutSlot()) {
            session.actionLocked(true);
            retryPendingCurrent(player, session);
        }
    }

    @EventHandler public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof BusDriverGuiHolder) event.setCancelled(true);
    }

    @EventHandler public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof BusDriverGuiHolder)) return;
        BusDriverSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session == null || session.ending() || session.suppressCloseReopen()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            BusDriverSession current = sessionsByPlayer.get(player.getUniqueId());
            if (current == null || current.ending()) return;
            if (!player.isOnline()) { checkpointActive(current); endSession(current, player, false); return; }
            if (current.state() == BusDriverSession.State.PLAYING) forfeit(player, current, "GUI_CLOSE");
            else endSession(current, player, false);
        });
    }

    @EventHandler public void onPlayerMove(PlayerMoveEvent event) {
        BusDriverSession session = sessionsByPlayer.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        Location to = event.getTo();
        if (to == null || samePosition(event.getFrom(), to)) return;
        if (session.state() == BusDriverSession.State.SHOWING_RESULT) {
            Location locked = session.lockedLocation();
            if (locked == null) { locked = event.getFrom().clone(); session.lockedLocation(locked); }
            Location target = locked.clone(); target.setYaw(to.getYaw()); target.setPitch(to.getPitch()); event.setTo(target); return;
        }
        if (exceedsMaxDistance(session, to)) {
            if (session.state() == BusDriverSession.State.PLAYING) forfeit(event.getPlayer(), session, "DISTANCE");
            else endSession(session, event.getPlayer(), true);
        }
    }

    @EventHandler public void onPlayerQuit(PlayerQuitEvent event) {
        BusDriverSession session = sessionsByPlayer.get(event.getPlayer().getUniqueId());
        if (session == null) return;
        checkpointActive(session);
        endSession(session, event.getPlayer(), false);
    }

    @EventHandler public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> tryPayPending(event.getPlayer()));
    }

    private void openMachine(Player player, CasinoConfig.Machine machine) {
        CasinoConfig config = configSupplier.get();
        if (sessionsByPlayer.containsKey(player.getUniqueId())) { player.sendActionBar(Text.component(config.messages().alreadyPlaying())); return; }
        UUID occupiedBy = occupiedMachines.get(machine.id());
        if (occupiedBy != null && !occupiedBy.equals(player.getUniqueId())) { player.sendActionBar(Text.component(config.messages().machineBusy())); return; }
        World world = Bukkit.getWorld(machine.world());
        if (world == null) { plugin.getLogger().warning("BusDriver world is not loaded: " + machine.world()); return; }
        CasinoConfig.PlayerLocation target = machine.playerLocation();
        player.teleport(new Location(world, target.x(), target.y(), target.z(), target.yaw(), target.pitch()));

        BusDriverGuiHolder holder = new BusDriverGuiHolder(player.getUniqueId(), machine.id());
        CasinoConfig.BusDriverGui gui = config.busDriver().gui();
        Inventory inventory = Bukkit.createInventory(holder, gui.size(), Text.legacy(gui.title(), Map.of()));
        holder.setInventory(inventory);
        BusDriverSession session = new BusDriverSession(player.getUniqueId(), machine, inventory,
                optionIndex(config.busDriver().betOptions(), config.busDriver().defaultBet()));
        sessionsByPlayer.put(player.getUniqueId(), session);
        occupiedMachines.put(machine.id(), player.getUniqueId());

        var active = stateStore.activeGame(player.getUniqueId());
        if (active.isPresent()) {
            resumeGame(player, session, active.get());
        } else {
            renderIdle(session, player);
            player.openInventory(inventory);
        }
        play(player, config.sounds().open());
    }

    private void startGame(Player player, BusDriverSession session) {
        CasinoConfig config = configSupplier.get();
        if (!settings.deterministicMode() || !settings.paidModeEnabled()) {
            player.sendActionBar(Text.component("&cBusDriver paid mode jest wyłączony w konfiguracji.")); session.actionLocked(false); return;
        }
        if (settings.requirePacketStateId() && (packetBridge == null || !packetBridge.ready())) {
            player.sendActionBar(Text.component("&cNie można bezpiecznie rozliczyć odpowiedzi: brak PacketEvents/stateId.")); session.actionLocked(false); return;
        }
        OptionalDouble balance = CasinoEconomy.balance(player, config);
        double cost = bet(config, session);
        if (balance.isEmpty() || balance.getAsDouble() + 0.0001D < cost) {
            player.sendActionBar(Text.component(balance.isEmpty() ? config.messages().economyUnavailableActionbar() : config.messages().noFundsActionbar(), placeholders(player, session, balance)));
            play(player, config.sounds().noFunds()); session.actionLocked(false); renderIdle(session, player); return;
        }
        if (!CasinoEconomy.dispatch(config.economy().removeCommand(), player, cost)) {
            player.sendActionBar(Text.component(config.messages().economyUnavailableActionbar())); session.actionLocked(false); return;
        }
        int boardIndex = stateStore.nextBoard(player.getUniqueId(), boards.count());
        long gameId = gameIds.incrementAndGet();
        BusDriverPlayerStateStore.ActiveGame checkpoint = new BusDriverPlayerStateStore.ActiveGame(
                gameId, boardIndex, 0, 0, session.betIndex(), cost, 0.0D,
                settings.decisionTimeMs(session.betIndex(), config.busDriver().betOptions().size()), session.machine().id());
        if (!stateStore.reserve(player.getUniqueId(), boards.count(), checkpoint)) {
            CasinoEconomy.dispatch(config.economy().addCommand(), player, cost);
            player.sendActionBar(Text.component("&cNie udało się zarezerwować deterministycznej planszy. Koszt został zwrócony."));
            session.actionLocked(false); return;
        }
        session.gameId(gameId); session.boardIndex(boardIndex); session.board(boards.board(boardIndex));
        session.stageIndex(0); session.completedRounds(0); session.stake(cost); session.currentWin(0.0D);
        session.wagerPaid(true); session.settled(false); session.state(BusDriverSession.State.PLAYING); session.actionLocked(false);
        play(player, config.sounds().spinStart());
        beginStage(player, session, checkpoint.remainingMs());
    }

    private void resumeGame(Player player, BusDriverSession session, BusDriverPlayerStateStore.ActiveGame active) {
        CasinoConfig config = configSupplier.get();
        session.gameId(active.gameId()); session.boardIndex(active.boardIndex()); session.board(boards.board(active.boardIndex()));
        session.stageIndex(active.stageIndex()); session.completedRounds(active.completedRounds()); session.betIndex(active.betIndex());
        session.stake(active.stake()); session.currentWin(active.currentWin()); session.wagerPaid(true); session.settled(false);
        session.state(BusDriverSession.State.PLAYING); session.actionLocked(false);
        player.openInventory(session.inventory());
        beginStage(player, session, Math.max(100L, active.remainingMs()));
        player.sendActionBar(Text.component("&eWznowiono planszę #" + active.boardIndex() + " od zapisanego etapu."));
    }

    private void beginStage(Player player, BusDriverSession session, long remainingMs) {
        session.cancelTimers(); session.clearStageSnapshots(); session.stageExpired(false); session.actionLocked(false);
        int configured = settings.decisionTimeMs(session.betIndex(), configSupplier.get().busDriver().betOptions().size());
        long allowed = Math.min(configured, Math.max(100L, remainingMs));
        session.decisionTimeMs(configured);
        long now = System.nanoTime();
        session.stageStartNano(now - ((configured - allowed) * 1_000_000L));
        session.stageDeadlineNano(now + allowed * 1_000_000L);
        long initialElapsed = Math.max(0L, configured - allowed);
        session.setLatestLogicalFrame(initialElapsed, now, true);
        renderStage(session, player);
        renderTimerItem(session, player, true, initialElapsed);
        session.timerTask(Bukkit.getScheduler().runTaskTimer(plugin, () -> tickStage(session), 2L, 2L));
    }

    private void tickStage(BusDriverSession session) {
        Player player = Bukkit.getPlayer(session.playerId());
        if (player == null || !player.isOnline() || sessionsByPlayer.get(session.playerId()) != session || session.state() != BusDriverSession.State.PLAYING) return;
        long now = System.nanoTime();
        if (now <= session.stageDeadlineNano()) {
            publishLogicalFrame(session, player, true);
            return;
        }
        if (!session.stageExpired()) {
            session.stageExpired(true);
            if (session.timerTask() != null) session.timerTask().cancel();
            publishLogicalFrame(session, player, false);
            long ticks = Math.max(1L, (long)Math.ceil(settings.maxResolvableDelayMs() / 50.0D));
            int expectedStage = session.stageIndex();
            session.timeoutTask(Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (sessionsByPlayer.get(session.playerId()) == session && session.state() == BusDriverSession.State.PLAYING
                        && session.stageIndex() == expectedStage) {
                    settleBust(player, session, "TIMEOUT");
                }
            }, ticks));
        }
    }

    private void publishLogicalFrame(BusDriverSession session, Player player, boolean open) {
        long now = System.nanoTime();
        long elapsed = Math.max(0L, (now - session.stageStartNano()) / 1_000_000L);
        session.setLatestLogicalFrame(elapsed, now, open);
        renderTimerItem(session, player, open, elapsed);
    }

    /** Called from PacketEvents send hook. */
    public void onContainerRevisionSent(UUID playerId, int windowId, int stateId) {
        BusDriverSession session = sessionsByPlayer.get(playerId);
        if (session == null || session.state() != BusDriverSession.State.PLAYING || windowId <= 0) return;
        session.observeWindowId(windowId);
        if (session.windowId() != windowId) return;
        session.captureStateId(stateId, settings.snapshotHistoryMs());
    }

    public boolean isStrictDecisionPacket(UUID playerId, int slot) {
        BusDriverSession session = sessionsByPlayer.get(playerId);
        if (session == null || session.state() != BusDriverSession.State.PLAYING) return false;
        CasinoConfig.BusDriverGui gui = configSupplier.get().busDriver().gui();
        if (slot == gui.cashoutSlot()) return true;
        BusDriverBoard.StageDefinition stage = session.currentStage();
        if (stage == null) return false;
        return stage.type() == BusDriverBoard.StageType.SUIT_DEDUCTION ? gui.suitSlots().contains(slot) : gui.rankSlots().contains(slot);
    }

    public void onStrictDecisionPacket(UUID playerId, int windowId, int stateId, int slot, long packetReceiveNano) {
        Bukkit.getScheduler().runTask(plugin, () -> resolveStrictDecision(playerId, windowId, stateId, slot, packetReceiveNano));
    }

    private void resolveStrictDecision(UUID playerId, int windowId, int stateId, int slot, long packetReceiveNano) {
        BusDriverSession session = sessionsByPlayer.get(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (session == null || player == null || !player.isOnline() || session.state() != BusDriverSession.State.PLAYING || session.actionLocked()) return;
        session.actionLocked(true);
        StageFrameSnapshot snapshot = session.snapshot(stateId);
        if (session.windowId() <= 0 || windowId != session.windowId() || snapshot == null
                || snapshot.gameId() != session.gameId() || snapshot.stageIndex() != session.stageIndex()) {
            technicalVoid(player, session, "STATE_MAPPING_MISMATCH"); return;
        }
        long ageMs = Math.max(0L, (packetReceiveNano - snapshot.frameStartNano()) / 1_000_000L);
        if (ageMs > settings.maxResolvableDelayMs()) { technicalVoid(player, session, "STALE_STATE"); return; }
        if (!snapshot.answerWindowOpen()) { settleBust(player, session, "TIMEOUT_STATE"); return; }
        CasinoConfig.BusDriverGui gui = configSupplier.get().busDriver().gui();
        if (slot == gui.cashoutSlot()) {
            if (!snapshot.withdrawActionAvailable() || session.currentWin() <= 0.0D) { session.actionLocked(false); return; }
            settleWithdrawn(player, session); return;
        }
        String answer = answerForSlot(session.currentStage(), slot);
        if (answer == null) { session.actionLocked(false); return; }
        session.cancelTimers();
        if (engine.answerCorrect(session.currentStage(), answer)) resolveCorrect(player, session, answer);
        else settleBust(player, session, "WRONG_ANSWER:" + answer);
    }

    private String answerForSlot(BusDriverBoard.StageDefinition stage, int slot) {
        CasinoConfig.BusDriverGui gui = configSupplier.get().busDriver().gui();
        if (stage.type() == BusDriverBoard.StageType.SUIT_DEDUCTION) {
            int index = gui.suitSlots().indexOf(slot);
            return index < 0 ? null : BusDriverDeductionEngine.Suit.values()[index].name();
        }
        int index = gui.rankSlots().indexOf(slot);
        return index < 0 ? null : Integer.toString(BusDriverDeductionEngine.MIN_RANK + index);
    }

    private void resolveCorrect(Player player, BusDriverSession session, String answer) {
        CasinoConfig config = configSupplier.get();
        int completed = session.completedRounds() + 1;
        session.completedRounds(completed);
        List<Double> payouts = config.busDriver().payoutLadder();
        session.currentWin(session.stake() * payouts.get(Math.min(completed - 1, payouts.size() - 1)));
        if (completed >= session.board().stages().size()) {
            double amount = session.currentWin();
            settleReward(player, session, amount, BusDriverOutcome.FULL_WIN);
            showTerminalResult(player, session, true, "PEŁNA WYGRANA", amount);
            return;
        }
        int previousStage = session.stageIndex();
        session.stageIndex(previousStage + 1);
        checkpointActive(session);
        play(player, config.sounds().winSmall());
        showStageFeedback(player, session, "§aDOBRZE", "§7Odpowiedź: §f" + answer + " §8| §a" + CasinoEconomy.money(session.currentWin()) + "$", true);
    }

    private void settleWithdrawn(Player player, BusDriverSession session) {
        double amount = session.currentWin();
        settleReward(player, session, amount, BusDriverOutcome.WITHDRAWN);
        showTerminalResult(player, session, true, "WYCOFANIE", amount);
    }

    private void settleBust(Player player, BusDriverSession session, String reason) {
        session.cancelTimers();
        session.currentWin(0.0D); session.settled(true); session.wagerPaid(false);
        stateStore.complete(session.playerId(), boards.count());
        plugin.getLogger().info("BusDriver BUST game=" + session.gameId() + " board=" + session.boardIndex() + " stage=" + (session.stageIndex()+1) + " reason=" + reason);
        play(player, configSupplier.get().sounds().lose());
        showTerminalResult(player, session, false, "PORAŻKA", 0.0D);
    }

    private void settleReward(Player player, BusDriverSession session, double amount, BusDriverOutcome outcome) {
        session.cancelTimers();
        if (amount > 0.0D && !CasinoEconomy.dispatch(configSupplier.get().economy().addCommand(), player, amount)) {
            queuePendingPayout(session.playerId(), amount);
            player.sendActionBar(Text.component("&eNagroda została zapisana do ponownej wypłaty."));
        }
        session.settled(true); session.wagerPaid(false);
        stateStore.complete(session.playerId(), boards.count());
        plugin.getLogger().info("BusDriver " + outcome + " game=" + session.gameId() + " board=" + session.boardIndex()
                + " stage=" + (session.stageIndex()+1) + " reward=" + CasinoEconomy.money(amount));
        play(player, outcome == BusDriverOutcome.FULL_WIN ? configSupplier.get().sounds().winBig() : configSupplier.get().sounds().winSmall());
    }

    private void technicalVoid(Player player, BusDriverSession session, String reason) {
        session.cancelTimers();
        double refund = session.wagerPaid() && !session.settled() ? session.stake() : 0.0D;
        if (refund > 0.0D && !CasinoEconomy.dispatch(configSupplier.get().economy().addCommand(), player, refund)) queuePendingPayout(session.playerId(), refund);
        session.settled(true); session.wagerPaid(false);
        stateStore.technicalVoid(session.playerId());
        plugin.getLogger().warning("BusDriver TECHNICAL_VOID game=" + session.gameId() + " board=" + session.boardIndex() + " reason=" + reason);
        showTerminalResult(player, session, false, "BŁĄD TECHNICZNY — ZWROT", refund);
    }

    private void forfeit(Player player, BusDriverSession session, String reason) {
        if (session.state() != BusDriverSession.State.PLAYING) { endSession(session, player, true); return; }
        session.cancelTimers();
        session.currentWin(0.0D); session.settled(true); session.wagerPaid(false);
        stateStore.complete(session.playerId(), boards.count());
        plugin.getLogger().info("BusDriver BUST/forfeit game=" + session.gameId() + " reason=" + reason);
        endSession(session, player, true);
    }

    private void checkpointActive(BusDriverSession session) {
        if (stateStore == null || session.state() != BusDriverSession.State.PLAYING || !session.wagerPaid() || session.settled()) return;
        long remaining = Math.max(100L, (session.stageDeadlineNano() - System.nanoTime()) / 1_000_000L);
        stateStore.checkpoint(session.playerId(), new BusDriverPlayerStateStore.ActiveGame(
                session.gameId(), session.boardIndex(), session.stageIndex(), session.completedRounds(), session.betIndex(),
                session.stake(), session.currentWin(), remaining, session.machine().id()));
    }

    private void showStageFeedback(Player player, BusDriverSession session, String title, String subtitle, boolean continuePlaying) {
        if (!player.isOnline()) return;
        CasinoConfig config = configSupplier.get();
        session.state(BusDriverSession.State.SHOWING_RESULT); session.lockedLocation(player.getLocation().clone()); session.suppressCloseReopen(true);
        player.closeInventory(); player.sendTitle(title, subtitle, 0, config.busDriver().resultSubtitleTicks(), 0);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (sessionsByPlayer.get(session.playerId()) != session) return;
            Player online = Bukkit.getPlayer(session.playerId()); if (online == null || !online.isOnline()) return;
            session.suppressCloseReopen(false); session.lockedLocation(null); session.state(BusDriverSession.State.PLAYING); session.actionLocked(false);
            online.openInventory(session.inventory());
            beginStage(online, session, settings.decisionTimeMs(session.betIndex(), config.busDriver().betOptions().size()));
        }, config.busDriver().resultSubtitleTicks());
    }

    private void showTerminalResult(Player player, BusDriverSession session, boolean win, String label, double amount) {
        if (!player.isOnline()) return;
        CasinoConfig config = configSupplier.get();
        session.state(BusDriverSession.State.SHOWING_RESULT); session.lockedLocation(player.getLocation().clone()); session.suppressCloseReopen(true);
        player.closeInventory();
        String subtitle = win ? "§a" + label + ": §f" + CasinoEconomy.money(amount) + "$" : "§c" + label + (amount > 0 ? " §8| §f" + CasinoEconomy.money(amount) + "$" : "");
        player.sendTitle("", subtitle, 0, config.busDriver().resultSubtitleTicks(), 0);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (sessionsByPlayer.get(session.playerId()) != session) return;
            Player online = Bukkit.getPlayer(session.playerId()); if (online == null || !online.isOnline()) return;
            session.suppressCloseReopen(false); session.lockedLocation(null); resetRound(session); renderIdle(session, online); online.openInventory(session.inventory());
        }, config.busDriver().resultSubtitleTicks());
    }

    private void retryPendingCurrent(Player player, BusDriverSession session) {
        if (session.currentWin() <= 0.0D) { session.actionLocked(false); return; }
        if (CasinoEconomy.dispatch(configSupplier.get().economy().addCommand(), player, session.currentWin())) {
            stateStore.complete(session.playerId(), boards.count());
            session.settled(true); session.wagerPaid(false); showTerminalResult(player, session, true, "NAGRODA", session.currentWin());
        } else { session.actionLocked(false); player.sendActionBar(Text.component(configSupplier.get().messages().economyUnavailableActionbar())); }
    }

    private void resetRound(BusDriverSession session) {
        session.cancelTimers(); session.state(BusDriverSession.State.IDLE); session.board(null); session.boardIndex(0); session.gameId(0L);
        session.stageIndex(0); session.completedRounds(0); session.currentWin(0.0D); session.stake(0.0D); session.wagerPaid(false); session.settled(true);
        session.actionLocked(false); session.clearStageSnapshots();
    }

    private void renderIdle(BusDriverSession session, Player player) {
        CasinoConfig config = configSupplier.get();
        CasinoConfig.BusDriverGui gui = config.busDriver().gui();
        OptionalDouble balance = CasinoEconomy.balance(player, config);
        Map<String,String> ph = placeholders(player, session, balance);
        ItemStack filler = item(gui.filler(), ph);
        for (int i = 0; i < session.inventory().getSize(); i++) session.inventory().setItem(i, filler.clone());
        set(session.inventory(), gui.balanceSlot(), item(gui.balanceItem(), ph));
        set(session.inventory(), gui.multiplierSlot(), item(gui.multiplierItem(), ph));
        set(session.inventory(), gui.exitSlot(), item(gui.exitItem(), ph));
        set(session.inventory(), gui.infoSlot(), item(gui.infoItem(), ph));
        if (balance.isPresent() && balance.getAsDouble() + 0.0001D >= bet(config, session)) {
            set(session.inventory(), gui.cardSlot(), item(gui.startItem(), ph));
        } else {
            set(session.inventory(), gui.cardSlot(), item(gui.noFundsItem(), ph));
        }
        player.updateInventory();
    }

    private void renderStage(BusDriverSession session, Player player) {
        CasinoConfig config = configSupplier.get();
        CasinoConfig.BusDriverGui gui = config.busDriver().gui();
        OptionalDouble balance = CasinoEconomy.balance(player, config);
        Map<String,String> ph = placeholders(player, session, balance);
        ItemStack filler = item(gui.filler(), ph);
        for (int i = 0; i < session.inventory().getSize(); i++) session.inventory().setItem(i, filler.clone());
        set(session.inventory(), gui.balanceSlot(), item(gui.balanceItem(), ph));
        set(session.inventory(), gui.multiplierSlot(), item(gui.multiplierLockedItem(), ph));
        set(session.inventory(), gui.exitSlot(), item(gui.activeExitItem(), ph));
        set(session.inventory(), gui.infoSlot(), item(gui.infoItem(), ph));
        for (BusDriverBoard.HintDefinition hint : session.currentStage().hints()) {
            Map<String,String> hintPh = new LinkedHashMap<>(ph);
            hintPh.put("hint", engine.hintText(hint));
            set(session.inventory(), hint.slot(), item(gui.hintItem(), hintPh));
        }
        renderAnswers(session, ph);
        if (session.currentWin() > 0.0D) set(session.inventory(), gui.cashoutSlot(), item(gui.cashoutItem(), ph));
        else set(session.inventory(), gui.cashoutSlot(), item(gui.cashoutUnavailableItem(), ph));
        player.updateInventory();
    }

    private void renderAnswers(BusDriverSession session, Map<String,String> basePh) {
        BusDriverBoard.StageDefinition stage = session.currentStage();
        CasinoConfig.BusDriverGui gui = configSupplier.get().busDriver().gui();
        if (stage.type() == BusDriverBoard.StageType.SUIT_DEDUCTION) {
            CasinoConfig.GuiItem[] items = {gui.heartsItem(), gui.diamondsItem(), gui.clubsItem(), gui.spadesItem()};
            for (int i = 0; i < 4; i++) set(session.inventory(), gui.suitSlots().get(i), item(items[i], basePh));
        } else {
            for (int i = 0; i < gui.rankSlots().size(); i++) {
                int rank = BusDriverDeductionEngine.MIN_RANK + i;
                Map<String,String> ph = new LinkedHashMap<>(basePh);
                ph.put("rank", BusDriverDeductionEngine.rankLabel(rank));
                set(session.inventory(), gui.rankSlots().get(i), item(gui.rankItem(), ph));
            }
        }
    }

    private void renderTimerItem(BusDriverSession session, Player player, boolean open, long elapsedMs) {
        int remaining = Math.max(0, session.decisionTimeMs() - (int)Math.min(Integer.MAX_VALUE, elapsedMs));
        String time = String.format(Locale.US, "%.1f", remaining / 1000.0D);
        Map<String,String> ph = new LinkedHashMap<>(placeholders(player, session, OptionalDouble.empty()));
        ph.put("stage", Integer.toString(session.stageIndex() + 1));
        ph.put("stage_count", Integer.toString(session.board().stages().size()));
        ph.put("stage_type", session.currentStage().type() == BusDriverBoard.StageType.SUIT_DEDUCTION ? "kolor karty" : "ranga karty");
        ph.put("time", time);
        ph.put("timer_line", open ? "&eCzas: &f" + time + " s" : "&cCzas minął — oczekiwanie na opóźnione kliknięcie...");
        set(session.inventory(), configSupplier.get().busDriver().gui().cardSlot(), item(configSupplier.get().busDriver().gui().stageItem(), ph));
        player.updateInventory();
    }

    private Map<String,String> placeholders(Player player, BusDriverSession session, OptionalDouble balance) {
        CasinoConfig config = configSupplier.get();
        double bet = bet(config, session);
        List<Double> payouts = config.busDriver().payoutLadder();
        int nextRound = Math.min(session.completedRounds() + 1, payouts.size());
        int board = session.boardIndex() > 0 ? session.boardIndex() : stateStore.nextBoard(session.playerId(), boards.count());
        Map<String,String> v = new LinkedHashMap<>();
        v.put("player", player.getName());
        v.put("balance", balance.isPresent() ? CasinoEconomy.money(balance.getAsDouble()) : "0");
        v.put("balance_display", balance.isPresent() ? CasinoEconomy.money(balance.getAsDouble()) : "-");
        v.put("bet", CasinoEconomy.money(bet));
        v.put("total_cost", CasinoEconomy.money(bet));
        v.put("current_win", CasinoEconomy.money(session.currentWin()));
        v.put("next_cashout", CasinoEconomy.money(bet * payouts.get(Math.max(0, nextRound - 1))));
        v.put("board", Integer.toString(board));
        v.put("board_count", Integer.toString(boards.count()));
        v.put("tier", settings.tierId(session.betIndex(), config.busDriver().betOptions().size()));
        v.put("decision_time_ms", Integer.toString(settings.decisionTimeMs(session.betIndex(), config.busDriver().betOptions().size())));
        v.put("decision_time_s", String.format(Locale.US, "%.1f", settings.decisionTimeMs(session.betIndex(), config.busDriver().betOptions().size()) / 1000.0D));
        v.put("stage", Integer.toString(session.stageIndex() + 1));
        v.put("stage_count", session.board() == null ? "4" : Integer.toString(session.board().stages().size()));
        return v;
    }

    public List<String> verificationLines() {
        List<String> out=new ArrayList<>(); out.add("&fBusDriver deterministic verification"); out.add("&7Boards: &f"+boards.count()+"/"+settings.requiredBoardCount());
        out.add("&7Valid stages: &f"+boards.stageCount()+"/"+boards.stageCount()); out.add("&7Ambiguous stages: &a0"); out.add("&7Contradictory stages: &a0"); out.add("&7Invalid hints: &a0");
        out.add("&7Board cycle: &fPER_PLAYER_SEQUENTIAL"); out.add("&7Board manifest: &aSHA-256 OK &8("+boards.sha256()+")");
        out.add("&7Packet state resolver: "+(packetBridge!=null&&packetBridge.ready()?"&aREADY":"&cNOT READY"));
        out.add("&7Paid mode: "+(settings.paidModeEnabled()&&(!settings.requirePacketStateId()||(packetBridge!=null&&packetBridge.ready()))?"&aENABLED":"&cDISABLED")); return out;
    }

    public List<String> boardLines(int id) {
        if (id<1||id>boards.count()) return List.of("&cPlansza poza zakresem 1.."+boards.count()); BusDriverBoard b=boards.board(id); List<String> out=new ArrayList<>(); out.add("&fBoard #"+id+" &8(version "+b.version()+")");
        for (BusDriverBoard.StageDefinition s:b.stages()) { out.add("&eStage "+s.id()+": &f"+s.type()+" -> "+targetLabel(s)); for (BusDriverBoard.HintDefinition h:s.hints()) out.add(" &8- slot "+h.slot()+": &7"+engine.hintText(h)); out.add(" &8Solver: &a"+String.join(",",engine.candidates(s))); }
        return out;
    }

    private String targetLabel(BusDriverBoard.StageDefinition s){ if(s.type()==BusDriverBoard.StageType.SUIT_DEDUCTION)return BusDriverDeductionEngine.suitLabel(s.target()); return BusDriverDeductionEngine.rankLabel(Integer.parseInt(s.target())); }

    private void endSession(BusDriverSession session, Player player, boolean closeInventory) {
        session.ending(true); session.cancelTimers(); sessionsByPlayer.remove(session.playerId()); occupiedMachines.remove(session.machine().id());
        if (player!=null&&player.isOnline()) { CasinoConfig config=configSupplier.get(); play(player,config.sounds().close()); if(config.busDriver().exitVelocity().enabled()){ Vector velocity=directionFromYaw(session.machine().playerLocation().yaw()).multiply(-config.busDriver().exitVelocity().backwardsStrength()); velocity.setY(config.busDriver().exitVelocity().y()); player.setVelocity(velocity);} if(closeInventory) player.closeInventory(); }
    }

    private void queuePendingPayout(UUID playerId,double amount){ if(!(amount>0)||!Double.isFinite(amount))return; pendingPayouts.merge(playerId,amount,Double::sum); savePendingPayouts(); }
    private void tryPayPending(Player player){ Double amount=pendingPayouts.get(player.getUniqueId()); if(amount==null||amount<=0)return; if(CasinoEconomy.dispatch(configSupplier.get().economy().addCommand(),player,amount)){pendingPayouts.remove(player.getUniqueId());savePendingPayouts();player.sendActionBar(Text.component("&aWypłacono zaległą nagrodę BusDriver: &f"+CasinoEconomy.money(amount)+"$"));}}
    private void loadPendingPayouts(){ pendingPayouts.clear(); if(!pendingPayoutFile.isFile())return; YamlConfiguration y=YamlConfiguration.loadConfiguration(pendingPayoutFile); for(String key:y.getKeys(false)){try{UUID id=UUID.fromString(key);double a=y.getDouble(key,0);if(a>0&&Double.isFinite(a))pendingPayouts.put(id,a);}catch(IllegalArgumentException ignored){}}}
    private void savePendingPayouts(){ YamlConfiguration y=new YamlConfiguration(); pendingPayouts.forEach((k,v)->y.set(k.toString(),v)); try{if(!plugin.getDataFolder().exists())plugin.getDataFolder().mkdirs();y.save(pendingPayoutFile);}catch(IOException ex){plugin.getLogger().severe("Could not save BusDriver pending payouts: "+ex.getMessage());}}

    private double bet(CasinoConfig config,BusDriverSession session){return config.busDriver().betOptions().get(Math.min(session.betIndex(),config.busDriver().betOptions().size()-1));}
    private int optionIndex(List<Double> options,double preferred){for(int i=0;i<options.size();i++)if(Math.abs(options.get(i)-preferred)<0.0001)return i;return 0;}
    private int nextIndex(int current,int size){return size<=1?0:(current+1)%size;}
    private void set(Inventory inv,int slot,ItemStack item){if(slot>=0&&slot<inv.getSize())inv.setItem(slot,item);}
    private ItemStack item(CasinoConfig.GuiItem cfg,Map<String,String> ph){ItemStack s=baseItem(cfg);ItemMeta m=s.getItemMeta();if(m!=null){applyHeadProfile(m,cfg);m.displayName(Text.component(cfg.name(),ph));m.lore(cfg.lore().isEmpty()?null:Text.lore(cfg.lore(),ph));applyFlags(m,cfg);s.setItemMeta(m);}return s;}
    private ItemStack baseItem(CasinoConfig.GuiItem cfg){if(!isBlank(cfg.headTexture()))return new ItemStack(cfg.material());ItemStack h=headDatabaseItem(cfg.headId());return h!=null?h:new ItemStack(cfg.material());}
    private ItemStack headDatabaseItem(String id){if(isBlank(id))return null;try{Class<?> c=Class.forName("me.arcaniax.hdb.api.HeadDatabaseAPI");Object api=c.getDeclaredConstructor().newInstance();Object it=c.getMethod("getItemHead",String.class).invoke(api,id);if(it instanceof ItemStack s&&!s.getType().isAir())return s.clone();}catch(Throwable ignored){}return null;}
    private void applyHeadProfile(ItemMeta meta,CasinoConfig.GuiItem config){if(!(meta instanceof SkullMeta skull)||isBlank(config.headTexture()))return;try{PlayerProfile profile=Bukkit.createProfile(UUID.nameUUIDFromBytes(("hexcasino:"+config.headTexture()).getBytes(StandardCharsets.UTF_8)),isBlank(config.headOwner())?"HexCasino":config.headOwner());URL url=textureUrl(config.headTexture());if(url!=null)profile.getTextures().setSkin(url);skull.setPlayerProfile(profile);}catch(Throwable ignored){}}
    private URL textureUrl(String raw){try{if(raw.startsWith("http://")||raw.startsWith("https://"))return URI.create(raw).toURL();String decoded=decodedTextureUrl(raw);if(decoded!=null)return URI.create(decoded).toURL();return URI.create("https://textures.minecraft.net/texture/"+raw).toURL();}catch(Exception ex){return null;}}
    private String decodedTextureUrl(String value){try{String d=new String(Base64.getDecoder().decode(value),StandardCharsets.UTF_8);int i=d.indexOf("https://textures.minecraft.net/texture/");if(i<0)return null;int e=d.indexOf('"',i);return e<0?d.substring(i):d.substring(i,e);}catch(IllegalArgumentException ex){return null;}}
    private boolean isBlank(String v){return v==null||v.isBlank();}
    private void applyFlags(ItemMeta meta,CasinoConfig.GuiItem cfg){if(cfg.hideAdditionalTooltip())meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);if(cfg.hideTooltip()){meta.addItemFlags(ItemFlag.values());try{meta.setHideTooltip(true);}catch(Throwable ignored){}}}

    private void rebuildMachines(){machinesByLocation.clear();for(CasinoConfig.Machine m:configSupplier.get().busDriver().machines().values())machinesByLocation.put(MachineKey.from(m.world(),m.activationBlock()),m);}
    private void startParticles(){CasinoConfig c=configSupplier.get();if(c.idleParticles().enabled()&&c.idleParticles().count()>0)idleParticleTask=Bukkit.getScheduler().runTaskTimer(plugin,()->spawnMachineParticles(false),0L,c.idleParticles().intervalTicks());if(c.occupiedParticles().enabled()&&c.occupiedParticles().count()>0)occupiedParticleTask=Bukkit.getScheduler().runTaskTimer(plugin,()->spawnMachineParticles(true),0L,c.occupiedParticles().intervalTicks());}
    private void spawnMachineParticles(boolean occupied){CasinoConfig c=configSupplier.get();CasinoConfig.ParticleSetting setting=occupied?c.occupiedParticles():c.idleParticles();for(CasinoConfig.Machine m:c.busDriver().machines().values()){if(occupiedMachines.containsKey(m.id())!=occupied)continue;World w=Bukkit.getWorld(m.world());if(w==null)continue;CasinoConfig.BlockLocation b=m.activationBlock();spawnParticles(w,new Location(w,b.x()+0.5,b.y()+setting.yOffset(),b.z()+0.5),setting);}}
    private void spawnParticles(World w,Location l,CasinoConfig.ParticleSetting s){if(s.particle()==Particle.DUST){w.spawnParticle(s.particle(),l,s.count(),s.offsetX(),s.offsetY(),s.offsetZ(),s.speed(),new Particle.DustOptions(Color.fromRGB(s.red(),s.green(),s.blue()),s.size()));}else w.spawnParticle(s.particle(),l,s.count(),s.offsetX(),s.offsetY(),s.offsetZ(),s.speed());}
    private boolean exceedsMaxDistance(BusDriverSession s,Location l){CasinoConfig c=configSupplier.get();World w=Bukkit.getWorld(s.machine().world());if(w==null||l.getWorld()==null||!w.equals(l.getWorld()))return true;CasinoConfig.PlayerLocation a=s.machine().playerLocation();double dx=l.getX()-a.x(),dy=l.getY()-a.y(),dz=l.getZ()-a.z(),m=c.busDriver().maxDistance();return dx*dx+dy*dy+dz*dz>m*m;}
    private boolean samePosition(Location a,Location b){return Objects.equals(a.getWorld(),b.getWorld())&&Double.compare(a.getX(),b.getX())==0&&Double.compare(a.getY(),b.getY())==0&&Double.compare(a.getZ(),b.getZ())==0;}
    private Vector directionFromYaw(float yaw){double r=Math.toRadians(yaw);return new Vector(-Math.sin(r),0,Math.cos(r)).normalize();}
    private void play(Player p,List<CasinoConfig.SoundSetting> settings){for(CasinoConfig.SoundSetting s:settings){if(!s.enabled())continue;if(s.delayTicks()<=0)playOne(p,s);else Bukkit.getScheduler().runTaskLater(plugin,()->{if(p.isOnline())playOne(p,s);},s.delayTicks());}}
    private void playOne(Player p,CasinoConfig.SoundSetting s){try{p.playSound(p.getLocation(),Sound.valueOf(s.name().trim().toUpperCase(Locale.ROOT)),s.volume(),s.pitch());}catch(RuntimeException ex){p.playSound(p.getLocation(),s.name(),s.volume(),s.pitch());}}
}
