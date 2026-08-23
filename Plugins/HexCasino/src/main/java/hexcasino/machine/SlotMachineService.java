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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Deterministic, skill-based Reel Challenge. No outcome RNG exists in this service. */
public final class SlotMachineService implements Listener {
    private final JavaPlugin plugin;
    private final Supplier<CasinoConfig> configSupplier;
    private final SlotEngine slotEngine = new SlotEngine();
    private final AtomicLong gameIds = new AtomicLong(System.currentTimeMillis());
    private final Map<MachineKey, CasinoConfig.Machine> machinesByLocation = new LinkedHashMap<>();
    private final Map<UUID, SlotMachineSession> sessionsByPlayer = new ConcurrentHashMap<>();
    private final Map<String, UUID> occupiedMachines = new HashMap<>();

    private SkillSlotSettings settings;
    private DeterministicReelSetRepository reelSets;
    private SkillSlotPlayerStateStore stateStore;
    private SkillSlotAuditStore auditStore;
    private SlotPacketBridge packetBridge;
    private BukkitTask idleParticleTask;
    private BukkitTask occupiedParticleTask;

    private record PaidSettlement(double reward, boolean dispatched) { }

    public SlotMachineService(JavaPlugin plugin, Supplier<CasinoConfig> configSupplier) {
        this.plugin = Objects.requireNonNull(plugin);
        this.configSupplier = Objects.requireNonNull(configSupplier);
    }

    public void start() {
        settings = SkillSlotSettings.load(plugin.getConfig());
        reelSets = DeterministicReelSetRepository.load(plugin, settings);
        if (stateStore == null) stateStore = new SkillSlotPlayerStateStore(plugin);
        if (auditStore == null) auditStore = new SkillSlotAuditStore(plugin);
        rebuildMachines();
        startParticles();
        packetBridge = null;
        if (plugin.getServer().getPluginManager().isPluginEnabled("packetevents")) {
            try {
                packetBridge = new SlotPacketBridge(plugin, this);
                packetBridge.start();
            } catch (Throwable ex) {
                packetBridge = null;
                plugin.getLogger().severe("PacketEvents adapter unavailable; reward mode cannot resolve paid STOPs: " + ex.getMessage());
            }
        } else {
            plugin.getLogger().warning("PacketEvents is not installed; reward mode cannot resolve paid STOPs.");
        }
        logDeterministicStatus();
    }

    public void reload() {
        stopRuntime(true);
        start();
    }

    public void stop() {
        stopRuntime(false);
    }

    private void stopRuntime(boolean closeInventories) {
        if (idleParticleTask != null) { idleParticleTask.cancel(); idleParticleTask = null; }
        if (occupiedParticleTask != null) { occupiedParticleTask.cancel(); occupiedParticleTask = null; }
        if (packetBridge != null) { packetBridge.stop(); packetBridge = null; }
        for (SlotMachineSession session : new ArrayList<>(sessionsByPlayer.values())) {
            Player player = Bukkit.getPlayer(session.playerId());
            checkpointOrEnd(session, player, closeInventories);
        }
        sessionsByPlayer.clear();
        occupiedMachines.clear();
        if (stateStore != null) stateStore.save();
    }

    private void logDeterministicStatus() {
        CasinoConfig config = configSupplier.get();
        int p1 = layout(config, 1).winningPatterns().size();
        int p3 = layout(config, 3).winningPatterns().size();
        int p5 = layout(config, 5).winningPatterns().size();
        boolean bridge = packetBridge != null && packetBridge.ready();
        plugin.getLogger().info("Reel Challenge deterministic pool: version=" + reelSets.version()
                + ", sets=" + reelSets.count() + ", SHA-256=" + reelSets.sha256()
                + ", patterns={1-line:" + p1 + ",3x3:" + p3 + ",5x3:" + p5 + "}");
        plugin.getLogger().info("Reel Challenge paid mode: configured=" + settings.paidModeEnabled()
                + ", packetStateResolver=" + bridge + ", humanSkillValidated=" + settings.humanSkillValidated()
                + ", visualStateValidated=" + settings.visualStateValidated()
                + ", packetLagValidated=" + settings.packetLagValidated()
                + ", valueFlowReviewed=" + settings.valueFlowReviewed()
                + ", uiReviewed=" + settings.uiReviewed()
                + ", READY=" + settings.paidModeReady(bridge));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
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
        if (event.getView().getTopInventory().getHolder() instanceof ReelPreviewGuiHolder preview) {
            if (!preview.playerId().equals(player.getUniqueId())) return;
            event.setCancelled(true);
            handlePreviewClick(player, preview, event.getRawSlot());
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof SlotMachineGuiHolder holder)) return;
        if (!holder.playerId().equals(player.getUniqueId())) return;
        event.setCancelled(true);
        SlotMachineSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session == null) { player.closeInventory(); return; }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= session.inventory().getSize()) return;
        CasinoConfig config = configSupplier.get();

        if (slot == config.gui().exitSlot()) {
            if (session.state() == SlotMachineSession.State.ROLLING) {
                player.sendActionBar(Text.component("&eNajpierw zakończ aktywną próbę."));
            } else {
                endSession(session, player, true);
            }
            return;
        }

        if (session.state() == SlotMachineSession.State.ROLLING) {
            if (slot == config.gui().actionSlot() && !session.strictPacketInput()) {
                // Practice fallback only. Paid mode never enters a non-strict session.
                resolvePracticeFallbackStop(player, session);
            }
            return;
        }
        if (session.state() != SlotMachineSession.State.IDLE) return;

        if (slot == config.gui().betSlot()) {
            adjustLayout(player, session);
        } else if (slot == config.gui().difficultySlot()) {
            adjustDifficulty(player, session);
        } else if (slot == config.gui().actionSlot()) {
            startPaid(player, session);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        boolean machineGui = event.getView().getTopInventory().getHolder() instanceof SlotMachineGuiHolder;
        boolean previewGui = event.getView().getTopInventory().getHolder() instanceof ReelPreviewGuiHolder;
        if (!machineGui && !previewGui) return;
        SlotMachineSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session == null || session.ending() || session.suppressCloseReopen()) return;
        // Closing the sequence preview manually should return to the machine instead of leaving
        // a hidden occupied session. Preview-to-preview transitions set suppressCloseReopen first.
        Bukkit.getScheduler().runTask(plugin, () -> {
            SlotMachineSession current = sessionsByPlayer.get(player.getUniqueId());
            if (current == null || current.ending() || !player.isOnline()) return;

            // A delayed reopen may have been queued by the close event that belongs to an
            // intentional machine <-> preview transition. If the player is already back in
            // either Reel Challenge view, reopening the same inventory again would close it,
            // fire another InventoryCloseEvent and create an endless one-tick reopen loop
            // (visible as GUI flickering between the real inventory and an empty view).
            if (isOwnedReelChallengeView(player, current)) return;

            transitionInventory(player, current, current.inventory());
        });
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        SlotMachineSession session = sessionsByPlayer.get(event.getPlayer().getUniqueId());
        if (session == null || session.state() != SlotMachineSession.State.SHOWING_RESULT) return;
        Location to = event.getTo();
        if (to == null || samePosition(event.getFrom(), to)) return;
        Location locked = session.lockedLocation();
        if (locked == null) { locked = event.getFrom().clone(); session.lockedLocation(locked); }
        Location target = locked.clone();
        target.setYaw(to.getYaw()); target.setPitch(to.getPitch());
        event.setTo(target);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        SlotMachineSession session = sessionsByPlayer.get(event.getPlayer().getUniqueId());
        if (session != null) checkpointOrEnd(session, event.getPlayer(), false);
    }

    private void openMachine(Player player, CasinoConfig.Machine machine) {
        CasinoConfig config = configSupplier.get();
        SlotMachineSession existing = sessionsByPlayer.get(player.getUniqueId());
        if (existing != null) {
            player.sendActionBar(Text.component("&eMasz już otwartą próbę zręcznościową."));
            return;
        }
        UUID occupiedBy = occupiedMachines.get(machine.id());
        if (occupiedBy != null && !occupiedBy.equals(player.getUniqueId())) {
            player.sendActionBar(Text.component(config.messages().machineBusy()));
            return;
        }
        World world = Bukkit.getWorld(machine.world());
        if (world == null) return;
        CasinoConfig.PlayerLocation target = machine.playerLocation();
        player.teleport(new Location(world, target.x(), target.y(), target.z(), target.yaw(), target.pitch()));

        SlotMachineGuiHolder holder = new SlotMachineGuiHolder(player.getUniqueId(), machine.id());
        Inventory inventory = Bukkit.createInventory(holder, config.gui().size(), Text.legacy(config.gui().title(), Map.of()));
        holder.setInventory(inventory);
        SlotLayout initialLayout = layout(config, config.slotMachine().defaultReels());
        int difficultyIndex = settings.difficultyIndex(settings.defaultDifficulty());
        SlotMachineSession session = new SlotMachineSession(player.getUniqueId(), player.getName(), machine, inventory,
                0, difficultyIndex, initialLayout, initialSymbolsForLayout(config, initialLayout));
        int nextSet = stateStore.currentNextSet(player.getUniqueId(), settings);
        session.practiceSetIndex(nextSet);
        sessionsByPlayer.put(player.getUniqueId(), session);
        occupiedMachines.put(machine.id(), player.getUniqueId());

        Optional<SkillSlotPlayerStateStore.ActiveGame> active = stateStore.activeGame(player.getUniqueId());
        if (active.isPresent()) {
            resumePaidGame(player, session, active.get());
        } else {
            render(session, player);
            player.openInventory(inventory);
        }
        play(player, config.sounds().open());
    }

    private void resumePaidGame(Player player, SlotMachineSession session, SkillSlotPlayerStateStore.ActiveGame active) {
        CasinoConfig config = configSupplier.get();
        SlotLayout restoredLayout = layout(config, active.layoutReels());
        session.changeLayout(restoredLayout, initialSymbolsForLayout(config, restoredLayout));
        session.costIndex(optionIndex(settings.baseCosts(), active.baseCost()));
        session.difficultyIndex(settings.difficultyIndex(active.difficultyId()));
        SlotDifficulty difficulty = settings.difficulty(active.difficultyId());
        boolean strict = packetBridge != null && packetBridge.ready();
        if (!strict) {
            player.sendActionBar(Text.component("&cAktywna próba czeka na PacketEvents. Wynik nie zostanie oszacowany z opóźnienia."));
            render(session, player);
            player.openInventory(session.inventory());
            return;
        }
        session.resumeGame(active.gameId(), reelSets.set(active.reelSetIndex()), active.chargedStake(),
                System.nanoTime(), difficulty.frameMs() * 1_000_000L, true, active.positions(), active.stopped(), active.resolvedStops());
        startRollTask(player, session);
        render(session, player);
        player.openInventory(session.inventory());
        player.sendActionBar(Text.component("&eWznowiono tę samą deterministyczną próbę i zestaw #" + active.reelSetIndex() + "."));
    }

    private void startPaid(Player player, SlotMachineSession session) {
        boolean bridgeReady = packetBridge != null && packetBridge.ready();
        if (!settings.paidModeReady(bridgeReady)) {
            player.sendActionBar(Text.component("&cTryb z nagrodami wymaga aktywnego resolvera PacketEvents/stateId."));
            return;
        }
        if (!stateStore.canStartPaid(player.getUniqueId(), settings)) {
            double current = stateStore.state(player.getUniqueId(), settings).dailyGrossRewards();
            player.sendActionBar(Text.component("&eDzienny limit nagród Arcade osiągnięty: &f" + money(current) + "$&e. Kolejna próba jutro."));
            return;
        }
        OptionalDouble balance = CasinoEconomy.balance(player, configSupplier.get());
        if (balance.isEmpty()) {
            player.sendActionBar(Text.component("&cNie można odczytać salda $."));
            return;
        }
        double charged = chargedStake(session);
        if (balance.getAsDouble() + 0.0001D < charged) {
            player.sendActionBar(Text.component("&cBrak środków. Koszt próby: &f" + money(charged) + "$"));
            return;
        }
        if (!CasinoEconomy.dispatch(configSupplier.get().economy().removeCommand(), player, charged)) {
            player.sendActionBar(Text.component("&cNie udało się pobrać kosztu próby."));
            return;
        }

        int setIndex = stateStore.currentNextSet(player.getUniqueId(), settings);
        DeterministicReelSet set = reelSets.set(setIndex);
        SlotDifficulty difficulty = selectedDifficulty(session);
        long gameId = gameIds.incrementAndGet();
        int[] initialPositions = java.util.Arrays.copyOf(set.startPositions(), session.layout().stopUnitCount());
        boolean[] initialStopped = new boolean[session.layout().stopUnitCount()];
        SkillSlotPlayerStateStore.ActiveGame initialCheckpoint = new SkillSlotPlayerStateStore.ActiveGame(
                gameId, setIndex, session.layout().reels(), selectedBaseCost(session), difficulty.id(), charged,
                initialPositions, initialStopped, List.of());
        if (!stateStore.reserveAndCheckpoint(player.getUniqueId(), settings, initialCheckpoint)) {
            CasinoEconomy.dispatch(configSupplier.get().economy().addCommand(), player, charged);
            player.sendActionBar(Text.component("&cNie udało się zarezerwować deterministycznego zestawu. Koszt próby zwrócono."));
            return;
        }
        session.startGame(gameId, set, false, charged, System.nanoTime(), difficulty.frameMs() * 1_000_000L, true);
        session.computeSnapshot(System.nanoTime(), slotEngine);
        checkpoint(session);
        startRollTask(player, session);
        play(player, configSupplier.get().sounds().spinStart());
        player.sendActionBar(Text.component("&aStart próby &f#" + setIndex + " &8| &7" + difficulty.displayName()
                + " &8| &7" + difficulty.frameMs() + " ms/pozycję"));
        render(session, player);
    }

    private void startPractice(Player player, SlotMachineSession session, int setIndex) {
        if (!settings.practiceEnabled()) return;
        SlotDifficulty difficulty = selectedDifficulty(session);
        DeterministicReelSet set = reelSets.set(setIndex);
        boolean strict = packetBridge != null && packetBridge.ready();
        session.startGame(gameIds.incrementAndGet(), set, true, 0.0D, System.nanoTime(),
                difficulty.frameMs() * 1_000_000L, strict);
        session.computeSnapshot(System.nanoTime(), slotEngine);
        startRollTask(player, session);
        player.sendActionBar(Text.component("&bTRENING &8| &7Zestaw #" + setIndex + " &8| &7Brak kosztu i nagrody"));
        render(session, player);
    }

    private void startRollTask(Player player, SlotMachineSession session) {
        if (session.rollTask() != null) session.rollTask().cancel();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> rollTick(player.getUniqueId()), 0L, 1L);
        session.rollTask(task);
    }

    private void rollTick(UUID playerId) {
        SlotMachineSession session = sessionsByPlayer.get(playerId);
        if (session == null || session.state() != SlotMachineSession.State.ROLLING) return;
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return;
        long now = System.nanoTime();
        FrameSnapshot before = session.currentSnapshot();
        FrameSnapshot snapshot = session.computeSnapshot(now, slotEngine);
        session.pruneSnapshots(now, settings.snapshotHistoryMs());
        session.displaySymbols(snapshot.visibleSymbols());
        boolean frameChanged = before == null || before.frameSeq() != snapshot.frameSeq();
        if (frameChanged) {
            renderReels(session);
            renderControls(session, player);
            play(player, configSupplier.get().sounds().rollTick());
        }
        if (session.reachedCycleEnd(now) && !session.allStopped()) {
            session.stopAllAtCurrentFrame(now);
            checkpoint(session);
            render(session, player);
            finishGame(player, session);
        }
    }

    /** PacketEvents send callback; safe to call off-main-thread. */
    public void onContainerRevisionSent(UUID playerId, int windowId, int stateId) {
        SlotMachineSession session = sessionsByPlayer.get(playerId);
        if (session != null) session.bindContainerState(windowId, stateId);
    }

    /** True only for the active action button in a strict packet-resolved session. */
    public boolean isStrictStopPacket(UUID playerId, int rawSlot) {
        SlotMachineSession session = sessionsByPlayer.get(playerId);
        return session != null && session.state() == SlotMachineSession.State.ROLLING
                && session.strictPacketInput() && rawSlot == configSupplier.get().gui().actionSlot();
    }

    /** PacketEvents receive callback. The packet is already cancelled before this method schedules Bukkit work. */
    public void onStrictStopPacket(UUID playerId, int windowId, int stateId, long receiveNano) {
        SlotMachineSession session = sessionsByPlayer.get(playerId);
        if (session == null || session.state() != SlotMachineSession.State.ROLLING) return;
        SlotMachineSession.PacketStopResolution resolution =
                session.resolvePacketStop(windowId, stateId, receiveNano, settings.maxResolvableDelayMs());
        if (!resolution.resolved()) {
            plugin.getLogger().warning("ReelChallenge FAIL-CLOSED STOP rejected: player=" + playerId
                    + " game=" + session.gameId() + " stateId=" + stateId
                    + " reason=" + resolution.rejection() + " rejectedCount=" + session.rejectedStopCount());
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && sessionsByPlayer.get(playerId) == session) {
                    player.sendActionBar(Text.component("&cSTOP nie został rozliczony (&f" + resolution.rejection()
                            + "&c). Gra zachowała ten sam zestaw i została zsynchronizowana ponownie."));
                    // Fail closed: never substitute current frame or ping-based estimate for a paid STOP.
                    render(session, player);
                }
            });
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sessionsByPlayer.get(playerId) != session) return;
            checkpoint(session); // disk/YAML checkpoint stays on the Bukkit thread, never the packet thread
            completeResolvedStop(playerId, session, resolution.stop());
        });
    }

    private void resolvePracticeFallbackStop(Player player, SlotMachineSession session) {
        Optional<ResolvedStop> resolved = session.resolvePracticeCurrentFrameStop(System.nanoTime());
        resolved.ifPresent(stop -> completeResolvedStop(player.getUniqueId(), session, stop));
    }

    private void completeResolvedStop(UUID playerId, SlotMachineSession session, ResolvedStop stop) {
        if (sessionsByPlayer.get(playerId) != session || session.state() != SlotMachineSession.State.ROLLING) return;
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return;
        FrameSnapshot snapshot = session.computeSnapshot(System.nanoTime(), slotEngine);
        session.displaySymbols(snapshot.visibleSymbols());
        render(session, player);
        play(player, configSupplier.get().sounds().columnStop());
        player.sendActionBar(Text.component("&aSTOP &f" + (stop.stopUnit() + 1) + "/" + session.stopUnitCount()
                + " &8| &7klatka #" + stop.frameSeq()));
        if (session.allStopped()) finishGame(player, session);
    }

    private void finishGame(Player player, SlotMachineSession session) {
        if (session.rollTask() != null) { session.rollTask().cancel(); session.rollTask(null); }
        int[] positions = session.stoppedPositionsCopy();
        SlotOutcome outcome = slotEngine.outcomeFromStoppedPositions(session.activeSet(), session.layout(), positions);
        session.displaySymbols(outcome.symbols());
        Map<String, Double> rewardMultipliers = rewardMultipliers(session.layout());
        SlotSpinResult result = slotEngine.evaluate(outcome, session.layout(), configSupplier.get(),
                session.practice() ? chargedStake(session) : session.chargedStake(), rewardMultipliers);
        session.state(SlotMachineSession.State.SHOWING_RESULT);
        render(session, player);

        if (session.practice()) {
            player.sendActionBar(Text.component("&bTRENING &8| &7Trafione układy: &f" + result.winningPatternCount()
                    + " &8| &7Symulowana nagroda: &f" + money(result.win()) + "$"));
            if (result.win() > 0.0D) highlightWin(session, player, result);
            scheduleReturnToIdle(player, session);
            return;
        }

        PaidSettlement settlement = settlePaidResult(player, session, result);
        if (settlement.reward() > 0.0D && settlement.dispatched()) {
            highlightWin(session, player, result);
            showPaidWinSubtitle(session, player, settlement.reward());
            return;
        }

        // Losses and payout-dispatch failures keep the final board visible briefly. A real
        // credited win uses the original close-inventory + green subtitle flow below.
        scheduleReturnToIdle(player, session);
    }

    private PaidSettlement settlePaidResult(Player player, SlotMachineSession session, SlotSpinResult result) {
        if (session.payoutSettled()) return new PaidSettlement(0.0D, false);
        session.payoutSettled(true);
        SkillSlotPlayerStateStore.State dailyStateBefore = stateStore.state(player.getUniqueId(), settings);
        double dailyBefore = dailyStateBefore.dailyGrossRewards();

        // The economy command is text-based and receives a two-decimal amount. Use exactly the
        // same rounded number everywhere else so the displayed reward, daily limit and audit are
        // identical to the amount that HexEconomy is asked to add.
        double paidReward = CasinoEconomy.roundMoney(result.win());
        OptionalDouble balanceBeforeReward = paidReward > 0.0D
                ? CasinoEconomy.balance(player, configSupplier.get()) : OptionalDouble.empty();
        boolean rewardDispatched = paidReward <= 0.0D;
        if (paidReward > 0.0D) {
            rewardDispatched = CasinoEconomy.dispatch(configSupplier.get().economy().addCommand(), player, paidReward);
            if (!rewardDispatched) {
                plugin.getLogger().severe("Failed to dispatch deterministic Reel Challenge reward: game=" + session.gameId()
                        + " player=" + player.getUniqueId() + " amount=" + money(paidReward));
                player.sendMessage(Text.component("&cNie udało się wykonać wypłaty nagrody &f" + money(paidReward)
                        + "$&c. Numer gry: &f#" + session.gameId() + "&c. Nie uruchamiaj kolejnej próby i zgłoś ten numer administracji."));
            } else {
                stateStore.addReward(player.getUniqueId(), settings, paidReward);
                scheduleRewardBalanceRefresh(player.getUniqueId(), session.gameId(), balanceBeforeReward, paidReward);
            }
        }
        double daily = stateStore.state(player.getUniqueId(), settings).dailyGrossRewards();
        if (paidReward > 0.0D && rewardDispatched) {
            player.sendActionBar(Text.component("&aNagroda: &f+" + money(paidReward) + "$ &8| &7Układy: &f"
                    + result.winningPatternCount() + " &8| &7Dzisiaj: &f" + money(daily) + "$"));
            play(player, paidReward >= session.chargedStake() * 2.0D
                    ? configSupplier.get().sounds().winBig() : configSupplier.get().sounds().winSmall());
            if (result.bestSymbol() != null) spawnSymbolWinParticles(player, result.bestSymbol());
        } else if (paidReward <= 0.0D) {
            player.sendActionBar(Text.component("&7Brak nagrody. Wynik jest funkcją zatrzymanych pozycji — bez losowania."));
            play(player, configSupplier.get().sounds().lose());
        }
        if (daily + 1.0E-9 >= settings.dailyRewardThreshold()) {
            player.sendMessage(Text.component("&eOsiągnięto dzienny limit nagród Arcade. Ta nagroda została wypłacona w całości; kolejna płatna próba będzie dostępna jutro."));
        }
        stateStore.clearActiveGame(player.getUniqueId());
        auditGame(player, session, result, paidReward, dailyStateBefore.rewardDate(), dailyBefore, daily, rewardDispatched);
        return new PaidSettlement(paidReward, rewardDispatched);
    }

    private void scheduleReturnToIdle(Player player, SlotMachineSession session) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (sessionsByPlayer.get(player.getUniqueId()) != session) return;
            session.state(SlotMachineSession.State.IDLE);
            session.lockedLocation(null);
            if (!session.practice()) stateStore.clearActiveGame(player.getUniqueId());
            render(session, player);
        }, Math.max(1, configSupplier.get().slotMachine().resultSubtitleTicks()));
    }

    /** Restores the original win presentation: hide the menu, show the green reward subtitle, then reopen. */
    private void showPaidWinSubtitle(SlotMachineSession session, Player player, double paidReward) {
        if (sessionsByPlayer.get(session.playerId()) != session || player == null || !player.isOnline()) return;
        CasinoConfig config = configSupplier.get();
        session.lockedLocation(player.getLocation().clone());
        session.suppressCloseReopen(true);
        player.closeInventory();
        player.sendTitle(
                "",
                Text.legacy(config.messages().winSubtitle(), Map.of("win", money(paidReward))),
                0,
                Math.max(1, config.slotMachine().resultSubtitleTicks()),
                0
        );

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            SlotMachineSession latest = sessionsByPlayer.get(session.playerId());
            Player online = Bukkit.getPlayer(session.playerId());
            if (latest != session || online == null || !online.isOnline()) return;
            session.state(SlotMachineSession.State.IDLE);
            session.lockedLocation(null);
            stateStore.clearActiveGame(session.playerId());
            render(session, online);
            transitionInventory(online, session, session.inventory());

            OptionalDouble refreshed = CasinoEconomy.balance(online, configSupplier.get());
            if (refreshed.isPresent()) {
                online.sendActionBar(Text.component("&aNagroda dopisana: &f+" + money(paidReward)
                        + "$ &8| &7Saldo: &f" + money(refreshed.getAsDouble()) + "$"));
            }
        }, Math.max(1, config.slotMachine().resultSubtitleTicks()));
    }

    private void scheduleRewardBalanceRefresh(UUID playerId, long gameId, OptionalDouble balanceBefore, double reward) {
        if (balanceBefore.isEmpty()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> verifyRewardBalance(playerId, gameId, balanceBefore.getAsDouble(), reward, 1), 5L);
    }

    private void verifyRewardBalance(UUID playerId, long gameId, double balanceBefore, double reward, int attempt) {
        Player online = Bukkit.getPlayer(playerId);
        if (online == null || !online.isOnline()) return;
        OptionalDouble current = CasinoEconomy.balance(online, configSupplier.get());
        if (current.isPresent() && current.getAsDouble() + 0.011D >= balanceBefore + reward) return;
        if (attempt < 3) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> verifyRewardBalance(playerId, gameId, balanceBefore, reward, attempt + 1), 10L);
            return;
        }
        plugin.getLogger().warning("ReelChallenge reward command was dispatched but balance placeholder did not confirm the increase within verification window: game="
                + gameId + " player=" + playerId + " before=" + money(balanceBefore) + " expectedReward=" + money(reward)
                + " observed=" + (current.isPresent() ? money(current.getAsDouble()) : "unavailable"));
    }

    private void auditGame(Player player, SlotMachineSession session, SlotSpinResult result, double paidReward,
                           java.time.LocalDate dailyDate, double dailyBefore, double dailyAfter, boolean rewardDispatched) {
        StringBuilder stops = new StringBuilder();
        for (ResolvedStop stop : session.resolvedStops()) {
            if (!stops.isEmpty()) stops.append(';');
            long delayMs = Math.max(0L, (stop.packetReceiveNano() - stop.frameStartNano()) / 1_000_000L);
            stops.append(stop.stopUnit()).append('@').append(stop.frameSeq()).append("/state=")
                    .append(stop.containerStateId()).append("/delayMs=").append(delayMs);
        }
        plugin.getLogger().info("ReelChallenge AUDIT game=" + session.gameId() + " player=" + player.getUniqueId()
                + " set=" + session.activeSet().index() + " reelSetVersion=" + reelSets.version()
                + " layout=" + layoutName(session.layout()) + " difficulty=" + selectedDifficulty(session).id()
                + " charged=" + money(session.chargedStake()) + " patterns=" + result.winningPatternCount()
                + " reward=" + money(paidReward) + " rawReward=" + result.win() + " dispatched=" + rewardDispatched
                + " dailyBefore=" + money(dailyBefore) + " dailyAfter=" + money(dailyAfter) + " stops=[" + stops + "]");

        auditStore.record(new SkillSlotAuditStore.AuditRecord(
                session.gameId(), player.getUniqueId(), reelSets.version(), session.activeSet().index(),
                session.layout().reels(), selectedBaseCost(session), selectedDifficulty(session).id(),
                selectedDifficulty(session).frameMs(), session.chargedStake(), session.stoppedPositionsCopy(),
                session.resolvedStops(), rewardMultipliers(session.layout()), result.winningPatternCount(), paidReward, rewardDispatched, dailyDate,
                dailyBefore, dailyAfter, dailyAfter + 1.0E-9 >= settings.dailyRewardThreshold()
        ));
    }

    private void checkpoint(SlotMachineSession session) {
        if (session.practice() || session.activeSet() == null) return;
        FrameSnapshot snapshot = session.currentSnapshot();
        int[] positions = snapshot != null ? snapshot.reelPositions() : session.currentPositionsCopy();
        stateStore.checkpoint(session.playerId(), new SkillSlotPlayerStateStore.ActiveGame(
                session.gameId(), session.activeSet().index(), session.layout().reels(), selectedBaseCost(session),
                selectedDifficulty(session).id(), session.chargedStake(), positions, session.stoppedMaskCopy(), session.resolvedStops()));
    }

    private void checkpointOrEnd(SlotMachineSession session, Player player, boolean closeInventory) {
        if (session.ending()) return;
        session.ending(true);
        if (session.rollTask() != null) { session.rollTask().cancel(); session.rollTask(null); }
        if (session.state() == SlotMachineSession.State.ROLLING && !session.practice()) {
            session.computeSnapshot(System.nanoTime(), slotEngine);
            checkpoint(session); // frozen checkpoint; same set resumes later, no reroll/refund fishing
        }
        sessionsByPlayer.remove(session.playerId());
        occupiedMachines.remove(session.machine().id());
        if (player != null && player.isOnline()) {
            play(player, configSupplier.get().sounds().close());
            CasinoConfig.ExitVelocity velocityCfg = configSupplier.get().slotMachine().exitVelocity();
            if (velocityCfg.enabled() && session.state() != SlotMachineSession.State.ROLLING) {
                Vector velocity = player.getLocation().getDirection().multiply(-velocityCfg.backwardsStrength());
                velocity.setY(velocityCfg.y()); player.setVelocity(velocity);
            }
            if (closeInventory) player.closeInventory();
        }
    }

    private void endSession(SlotMachineSession session, Player player, boolean closeInventory) {
        checkpointOrEnd(session, player, closeInventory);
    }

    private void adjustLayout(Player player, SlotMachineSession session) {
        List<Integer> options = List.of(1, 3, 5);
        int current = options.indexOf(session.layout().reels());
        int nextReels = options.get((current + 1) % options.size());
        SlotLayout next = layout(configSupplier.get(), nextReels);
        session.changeLayout(next, initialSymbolsForLayout(configSupplier.get(), next));
        render(session, player);
    }

    private void adjustDifficulty(Player player, SlotMachineSession session) {
        int start = session.difficultyIndex();
        for (int step = 1; step <= settings.difficulties().size(); step++) {
            int index = (start + step) % settings.difficulties().size();
            if (settings.difficulties().get(index).enabled()) {
                session.difficultyIndex(index); break;
            }
        }
        render(session, player);
    }

    private void render(SlotMachineSession session, Player player) {
        CasinoConfig config = configSupplier.get();
        ItemStack filler = item(config.gui().filler());
        for (int slot = 0; slot < session.inventory().getSize(); slot++) session.inventory().setItem(slot, filler.clone());
        renderReels(session);
        renderControls(session, player);
    }

    private void renderReels(SlotMachineSession session) {
        CasinoConfig config = configSupplier.get();
        String[] symbols = session.displaySymbolsCopy();
        for (int i = 0; i < session.layout().inventorySlots().size(); i++) {
            CasinoConfig.Symbol symbol = config.symbolsById().get(symbols[i]);
            if (symbol == null) symbol = config.symbols().getFirst();
            ItemStack stack = symbolItem(symbol);
            // The currently controllable physical reel is visually marked by glow; no blur/near-miss effect.
            int activeUnit = session.state() == SlotMachineSession.State.ROLLING ? session.stoppedCount() : -1;
            if (activeUnit >= 0 && session.layout().stopUnitCellIndexes(Math.min(activeUnit, session.stopUnitCount() - 1)).contains(i)) {
                ItemMeta meta = stack.getItemMeta();
                if (meta != null) { meta.setEnchantmentGlintOverride(true); stack.setItemMeta(meta); }
            }
            session.inventory().setItem(session.layout().inventorySlots().get(i), stack);
        }
    }

    private void renderControls(SlotMachineSession session, Player player) {
        CasinoConfig config = configSupplier.get();
        CasinoConfig.Gui gui = config.gui();
        OptionalDouble balance = CasinoEconomy.balance(player, config);
        SlotDifficulty difficulty = selectedDifficulty(session);
        double charged = chargedStake(session);
        int nextSet = stateStore.currentNextSet(player.getUniqueId(), settings);
        double daily = stateStore.state(player.getUniqueId(), settings).dailyGrossRewards();
        boolean paidReady = settings.paidModeReady(packetBridge != null && packetBridge.ready());
        boolean dailyReady = stateStore.canStartPaid(player.getUniqueId(), settings);

        Map<String, String> ph = slotPlaceholders(session, balance, difficulty, charged, nextSet, daily);
        set(session.inventory(), gui.balanceSlot(), item(gui.balanceItem(), ph));
        set(session.inventory(), gui.betSlot(), item(gui.betItem(), ph));
        set(session.inventory(), gui.difficultySlot(), item(gui.difficultyItem(), ph));
        set(session.inventory(), gui.exitSlot(), item(gui.exitItem(), ph));
        set(session.inventory(), gui.infoSlot(), rewardLegend(session, ph));

        if (session.state() == SlotMachineSession.State.ROLLING) {
            if (session.layout().reels() == 1) {
                set(session.inventory(), gui.stopLineLeftSlot(), item(gui.stopLineLeftItem(), ph));
                set(session.inventory(), gui.stopLineRightSlot(), item(gui.stopLineRightItem(), ph));
            }
            set(session.inventory(), gui.actionSlot(), item(gui.rollingItem(), ph));
        } else if (!paidReady) {
            set(session.inventory(), gui.actionSlot(), item(gui.rewardModeUnavailableItem(), ph));
        } else if (!dailyReady) {
            set(session.inventory(), gui.actionSlot(), item(gui.dailyLimitItem(), ph));
        } else if (balance.isPresent() && balance.getAsDouble() + 0.0001D >= charged) {
            set(session.inventory(), gui.actionSlot(), item(gui.spinAvailableItem(), ph));
        } else {
            set(session.inventory(), gui.actionSlot(), item(gui.spinUnavailableItem(), ph));
        }
        player.updateInventory();
    }

    private Map<String, String> slotPlaceholders(SlotMachineSession session,
                                                  OptionalDouble balance,
                                                  SlotDifficulty difficulty,
                                                  double charged,
                                                  int nextSet,
                                                  double daily) {
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("balance", balance.isPresent() ? money(balance.getAsDouble()) : "0");
        ph.put("balance_display", balance.isPresent() ? money(balance.getAsDouble()) : "-");
        ph.put("base_bet", money(selectedBaseCost(session)));
        ph.put("total_cost", money(charged));
        ph.put("charged", money(charged));
        ph.put("layout", layoutName(session.layout()));
        ph.put("difficulty", difficulty.displayName());
        ph.put("frame_ms", Integer.toString(difficulty.frameMs()));
        ph.put("next_set", Integer.toString(nextSet));
        ph.put("current_reel", Integer.toString(Math.min(session.stoppedCount() + 1, session.stopUnitCount())));
        ph.put("reel_count", Integer.toString(session.stopUnitCount()));
        ph.put("daily_rewards", money(daily));
        ph.put("daily_threshold", money(settings.dailyRewardThreshold()));
        return ph;
    }

    private ItemStack rewardLegend(SlotMachineSession session, Map<String, String> placeholders) {
        CasinoConfig config = configSupplier.get();
        CasinoConfig.GuiItem cfg = config.gui().infoItem();
        double patternStake = chargedStake(session) / session.layout().winningPatterns().size();
        Map<String, Double> effectiveRewards = rewardMultipliers(session.layout());
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : cfg.lore()) {
            if ("{symbol_payouts}".equals(line)) {
                int index = 1;
                for (CasinoConfig.Symbol symbol : config.symbols()) {
                    double reward = effectiveRewards.getOrDefault(symbol.id(), 0.0D);
                    Map<String, String> linePh = new LinkedHashMap<>(placeholders);
                    linePh.put("index", Integer.toString(index++));
                    linePh.put("symbol", symbol.id());
                    linePh.put("legend_name", symbol.legendName());
                    linePh.put("legend_name_plain", stripColors(symbol.legendName()));
                    linePh.put("reward_multiplier", money(reward));
                    linePh.put("payout", money(patternStake * reward));
                    lore.add(Text.component(config.gui().infoSymbolLine(), linePh));
                }
            } else {
                lore.add(Text.component(line, placeholders));
            }
        }
        ItemStack stack = item(cfg, placeholders);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.lore(lore.isEmpty() ? null : lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void openPreview(Player player, int setIndex, int reelIndex, int page) {
        CasinoConfig config = configSupplier.get();
        CasinoConfig.ReelPreviewGui gui = config.gui().preview();
        DeterministicReelSet set = reelSets.set(setIndex);
        int safeReel = Math.floorMod(reelIndex, 5);
        int safePage = Math.max(0, Math.min(1, page));
        ReelPreviewGuiHolder holder = new ReelPreviewGuiHolder(player.getUniqueId(), setIndex, safeReel, safePage);
        Map<String,String> ph = Map.of(
                "set", Integer.toString(setIndex),
                "reel", Integer.toString(safeReel + 1),
                "page", Integer.toString(safePage + 1)
        );
        Inventory inv = Bukkit.createInventory(holder, gui.size(), Text.legacy(gui.title(), ph));
        holder.inventory(inv);
        ItemStack filler = item(gui.filler(), ph);
        for (int i = 0; i < gui.size(); i++) inv.setItem(i, filler.clone());
        ReelStrip strip = set.reel(safeReel);
        int from = safePage * 45;
        int to = Math.min(strip.size(), from + 45);
        for (int position = from; position < to && position - from < gui.size(); position++) {
            CasinoConfig.Symbol symbol = config.symbolsById().get(strip.symbolAt(position));
            ItemStack symbolStack = symbol == null ? new ItemStack(Material.BARRIER) : symbolItem(symbol);
            ItemMeta meta = symbolStack.getItemMeta();
            if (meta != null) {
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                lore.add(Text.component("&7Pozycja: &f" + position));
                meta.lore(lore);
                symbolStack.setItemMeta(meta);
            }
            inv.setItem(position - from, symbolStack);
        }
        inv.setItem(gui.backSlot(), item(gui.backItem(), ph));
        inv.setItem(gui.previousReelSlot(), item(gui.previousReelItem(), ph));
        inv.setItem(gui.nextReelSlot(), item(gui.nextReelItem(), ph));
        inv.setItem(gui.previousPageSlot(), item(gui.previousPageItem(), ph));
        inv.setItem(gui.nextPageSlot(), item(gui.nextPageItem(), ph));
        SlotMachineSession session = sessionsByPlayer.get(player.getUniqueId());
        if (session != null) transitionInventory(player, session, inv);
        else player.openInventory(inv);
    }

    private void handlePreviewClick(Player player, ReelPreviewGuiHolder preview, int slot) {
        CasinoConfig.ReelPreviewGui gui = configSupplier.get().gui().preview();
        if (slot == gui.backSlot()) {
            SlotMachineSession session = sessionsByPlayer.get(player.getUniqueId());
            if (session != null) transitionInventory(player, session, session.inventory());
        } else if (slot == gui.previousReelSlot()) {
            openPreview(player, preview.reelSetIndex(), preview.reelIndex() - 1, preview.page());
        } else if (slot == gui.nextReelSlot()) {
            openPreview(player, preview.reelSetIndex(), preview.reelIndex() + 1, preview.page());
        } else if (slot == gui.previousPageSlot()) {
            openPreview(player, preview.reelSetIndex(), preview.reelIndex(), 0);
        } else if (slot == gui.nextPageSlot()) {
            openPreview(player, preview.reelSetIndex(), preview.reelIndex(), 1);
        }
    }

    private void transitionInventory(Player player, SlotMachineSession session, Inventory target) {
        if (session == null || session.ending() || !player.isOnline()) return;
        session.suppressCloseReopen(true);
        player.openInventory(target);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sessionsByPlayer.get(player.getUniqueId()) == session) {
                session.suppressCloseReopen(false);
            }
        });
    }

    private boolean isOwnedReelChallengeView(Player player, SlotMachineSession session) {
        if (player == null || session == null) return false;
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top == null) return false;
        Object holder = top.getHolder();
        if (holder instanceof SlotMachineGuiHolder machineHolder) {
            return machineHolder.playerId().equals(session.playerId());
        }
        if (holder instanceof ReelPreviewGuiHolder previewHolder) {
            return previewHolder.playerId().equals(session.playerId());
        }
        return false;
    }

    private void highlightWin(SlotMachineSession session, Player player, SlotSpinResult result) {
        if (!configSupplier.get().slotMachine().highlight().enabled()) return;
        Set<Integer> winningSlots = new LinkedHashSet<>();
        for (WinningPatternHit hit : result.hits()) {
            for (GridPoint point : hit.pattern().points()) winningSlots.add(session.layout().inventorySlot(point));
        }
        for (int slot : winningSlots) {
            ItemStack existing = session.inventory().getItem(slot);
            if (existing == null) continue;
            ItemMeta meta = existing.getItemMeta();
            if (meta != null) { meta.setEnchantmentGlintOverride(true); existing.setItemMeta(meta); session.inventory().setItem(slot, existing); }
        }
        player.updateInventory();
    }

    public List<String> auditGameDetails(long gameId) {
        if (auditStore == null) return List.of("&cMagazyn audytu nie jest gotowy.");
        Optional<SkillSlotAuditStore.AuditRecord> found = auditStore.find(gameId);
        if (found.isEmpty()) return List.of("&cNie znaleziono gry &f#" + gameId + "&c.");
        SkillSlotAuditStore.AuditRecord record = found.get();
        List<String> lines = new ArrayList<>();
        lines.add("&6Reel Challenge audit &f#" + gameId);
        lines.add("&7Gracz: &f" + record.playerId());
        lines.add("&7Zestaw: &f#" + record.reelSetIndex() + " &8(" + record.reelSetVersion() + ")");
        lines.add("&7Układ: &f" + (record.layoutReels() == 1 ? "1 linia" : record.layoutReels() + "×3"));
        lines.add("&7Poziom: &f" + record.difficultyId() + " &8| &7Tempo: &f" + record.frameMs() + " ms");
        lines.add("&7Koszt próby: &f" + money(record.chargedStake()) + "$ &8| &7Nagroda: &f" + money(record.grossPayout()) + "$");
        lines.add("&7Trafione układy: &f" + record.winningPatterns() + " &8| &7Wypłata dispatch: &f" + record.rewardDispatched());
        lines.add("&7Dziennie: &f" + money(record.dailyBefore()) + "$ -> " + money(record.dailyAfter()) + "$"
                + (record.lockedAfter() ? " &8| &eLOCK" : ""));
        for (ResolvedStop stop : record.stops()) {
            long delayMs = Math.max(0L, (stop.packetReceiveNano() - stop.frameStartNano()) / 1_000_000L);
            lines.add("&8STOP " + (stop.stopUnit() + 1) + ": frame=" + stop.frameSeq() + " state="
                    + stop.containerStateId() + " pos=" + stop.resolvedPosition() + " delay=" + delayMs + "ms");
        }
        return List.copyOf(lines);
    }

    public SkillSlotAuditStore.Verification verifyAuditGame(long gameId) {
        if (auditStore == null || reelSets == null) return new SkillSlotAuditStore.Verification(false, "audit store not ready", 0, 0);
        Optional<SkillSlotAuditStore.AuditRecord> found = auditStore.find(gameId);
        if (found.isEmpty()) return new SkillSlotAuditStore.Verification(false, "game not found", 0, 0);
        SkillSlotAuditStore.AuditRecord record = found.get();
        if (!reelSets.version().equals(record.reelSetVersion())) {
            return new SkillSlotAuditStore.Verification(false,
                    "reel-set version mismatch: recorded=" + record.reelSetVersion() + ", loaded=" + reelSets.version(), 0, 0);
        }
        try {
            SlotLayout layout = layout(configSupplier.get(), record.layoutReels());
            SlotOutcome outcome = slotEngine.outcomeFromStoppedPositions(reelSets.set(record.reelSetIndex()), layout, record.positions());
            SlotSpinResult recomputed = slotEngine.evaluate(outcome, layout, configSupplier.get(), record.chargedStake(), record.rewards());
            double recomputedPaidReward = CasinoEconomy.roundMoney(recomputed.win());
            boolean payoutOk = Math.abs(recomputedPaidReward - record.grossPayout()) < 0.000001D;
            boolean patternsOk = recomputed.winningPatternCount() == record.winningPatterns();
            boolean ok = payoutOk && patternsOk;
            String message = ok ? "deterministic replay OK"
                    : "mismatch: payout=" + recomputedPaidReward + "/" + record.grossPayout()
                    + ", patterns=" + recomputed.winningPatternCount() + "/" + record.winningPatterns();
            return new SkillSlotAuditStore.Verification(ok, message, recomputedPaidReward, recomputed.winningPatternCount());
        } catch (RuntimeException ex) {
            return new SkillSlotAuditStore.Verification(false, "verification error: " + ex.getMessage(), 0, 0);
        }
    }

    private Map<String, Double> rewardMultipliers(SlotLayout layout) {
        return settings.rewardsForLayout(layout.reels());
    }

    private double selectedBaseCost(SlotMachineSession session) {
        return settings.baseCosts().get(Math.min(session.costIndex(), settings.baseCosts().size() - 1));
    }

    private SlotDifficulty selectedDifficulty(SlotMachineSession session) {
        return settings.difficulties().get(Math.min(session.difficultyIndex(), settings.difficulties().size() - 1));
    }

    private double chargedStake(SlotMachineSession session) {
        return selectedBaseCost(session) * selectedDifficulty(session).costMultiplier();
    }

    private SlotLayout layout(CasinoConfig config, int reels) {
        return SlotLayout.centered(reels, 3, config.gui().reelGridSlots());
    }

    private String[] initialSymbolsForLayout(CasinoConfig config, SlotLayout layout) {
        List<Integer> maxGrid = config.gui().reelGridSlots();
        String[] symbols = new String[layout.cellCount()];
        for (int i = 0; i < layout.inventorySlots().size(); i++) {
            int maxIndex = maxGrid.indexOf(layout.inventorySlots().get(i));
            symbols[i] = maxIndex >= 0 && maxIndex < config.initialSymbols().size()
                    ? config.initialSymbols().get(maxIndex) : config.symbols().getFirst().id();
        }
        return symbols;
    }

    private int optionIndex(List<Double> options, double preferred) {
        for (int i = 0; i < options.size(); i++) if (Math.abs(options.get(i) - preferred) < 0.0001D) return i;
        return 0;
    }

    private int nextIndex(int current, int size) { return size <= 1 ? 0 : (current + 1) % size; }
    private String layoutName(SlotLayout layout) { return layout.reels() == 1 ? "1 linia" : layout.reels() + "×3"; }
    private String money(double value) { return CasinoEconomy.money(value); }

    private void rebuildMachines() {
        machinesByLocation.clear();
        for (CasinoConfig.Machine machine : configSupplier.get().machines().values())
            machinesByLocation.put(MachineKey.from(machine.world(), machine.activationBlock()), machine);
    }

    private void startParticles() {
        CasinoConfig config = configSupplier.get();
        if (config.idleParticles().enabled() && config.idleParticles().count() > 0)
            idleParticleTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> spawnMachineParticles(false), 0L, config.idleParticles().intervalTicks());
        if (config.occupiedParticles().enabled() && config.occupiedParticles().count() > 0)
            occupiedParticleTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> spawnMachineParticles(true), 0L, config.occupiedParticles().intervalTicks());
    }

    private void spawnMachineParticles(boolean occupied) {
        CasinoConfig config = configSupplier.get();
        CasinoConfig.ParticleSetting setting = occupied ? config.occupiedParticles() : config.idleParticles();
        for (CasinoConfig.Machine machine : config.machines().values()) {
            if (occupiedMachines.containsKey(machine.id()) != occupied) continue;
            World world = Bukkit.getWorld(machine.world()); if (world == null) continue;
            CasinoConfig.BlockLocation b = machine.activationBlock();
            spawnParticles(world, new Location(world, b.x()+0.5D, b.y()+setting.yOffset(), b.z()+0.5D), setting);
        }
    }

    private void spawnParticles(World world, Location location, CasinoConfig.ParticleSetting setting) {
        if (setting.particle() == Particle.DUST) {
            world.spawnParticle(setting.particle(), location, setting.count(), setting.offsetX(), setting.offsetY(), setting.offsetZ(),
                    setting.speed(), new Particle.DustOptions(Color.fromRGB(setting.red(), setting.green(), setting.blue()), setting.size()));
        } else {
            world.spawnParticle(setting.particle(), location, setting.count(), setting.offsetX(), setting.offsetY(), setting.offsetZ(), setting.speed());
        }
    }

    private void spawnSymbolWinParticles(Player player, CasinoConfig.Symbol symbol) {
        CasinoConfig.ParticleSetting setting = symbol.winParticles();
        if (setting.enabled() && setting.count() > 0)
            spawnParticles(player.getWorld(), player.getLocation().clone().add(0.0D, setting.yOffset(), 0.0D), setting);
    }

    private ItemStack symbolItem(CasinoConfig.Symbol symbol) {
        ItemStack stack = new ItemStack(symbol.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.component(symbol.displayName()));
            if (!symbol.lore().isEmpty()) meta.lore(Text.lore(symbol.lore(), Map.of()));
            meta.addItemFlags(ItemFlag.values()); stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack item(CasinoConfig.GuiItem cfg) {
        return item(cfg, Map.of());
    }

    private ItemStack item(CasinoConfig.GuiItem cfg, Map<String, String> placeholders) {
        ItemStack stack = new ItemStack(cfg.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.component(cfg.name(), placeholders));
            meta.lore(cfg.lore().isEmpty() ? null : Text.lore(cfg.lore(), placeholders));
            if (cfg.hideAdditionalTooltip()) meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            if (cfg.hideTooltip()) {
                meta.addItemFlags(ItemFlag.values());
                try { meta.setHideTooltip(true); } catch (Throwable ignored) { }
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void set(Inventory inv, int slot, ItemStack stack) { if (slot >= 0 && slot < inv.getSize()) inv.setItem(slot, stack); }

    private String stripColors(String legacy) {
        if (legacy == null) return "";
        return legacy.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
    }

    private boolean samePosition(Location first, Location second) {
        return first.getWorld() == second.getWorld()
                && Math.abs(first.getX()-second.getX()) < 1.0E-6
                && Math.abs(first.getY()-second.getY()) < 1.0E-6
                && Math.abs(first.getZ()-second.getZ()) < 1.0E-6;
    }

    private void play(Player player, List<CasinoConfig.SoundSetting> settings) {
        for (CasinoConfig.SoundSetting setting : settings) {
            if (!setting.enabled()) continue;
            Runnable task = () -> {
                try { player.playSound(player.getLocation(), Sound.valueOf(setting.name().trim().toUpperCase(Locale.ROOT)), setting.volume(), setting.pitch()); }
                catch (RuntimeException ex) { player.playSound(player.getLocation(), setting.name(), setting.volume(), setting.pitch()); }
            };
            if (setting.delayTicks() <= 0) task.run(); else Bukkit.getScheduler().runTaskLater(plugin, task, setting.delayTicks());
        }
    }
}
