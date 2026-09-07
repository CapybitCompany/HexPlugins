package hex.parkour.service;

import hex.events.api.EventExecutionContext;
import hex.events.api.EventJoinRequest;
import hex.events.api.EventJoinResult;
import hex.events.api.EventStopReason;
import hex.events.api.LeaveReason;
import hex.parkour.config.ParkourConfig;
import hex.parkour.event.HexEventsBridge;
import hex.parkour.model.CuboidRegion;
import hex.parkour.model.LocationSpec;
import hex.parkour.model.MoneyPoint;
import hex.parkour.model.ParkourArena;
import hex.parkour.model.ParkourAttempt;
import hex.parkour.model.ParkourCheckpoint;
import hex.parkour.model.ParkourPlayerSession;
import hex.parkour.model.ParkourPlayerState;
import hex.parkour.persistence.SnapshotRepository;
import hex.parkour.persistence.StoredPlayerState;
import hex.parkour.persistence.ParkourTimesRepository;
import hex.parkour.util.DurationFormats;
import hex.parkour.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ParkourSessionService {
    private static final float WEST_YAW = 90.0f;
    private static final float LEVEL_PITCH = 0.0f;

    private final Plugin plugin;
    private final SnapshotRepository snapshots;
    private final ParkourTimesRepository times;
    private final ParkourItemService items;
    private final BossBarService bossBars;
    private final VisibilityService visibility;
    private final TerrainValidationService terrain;
    private final EffectService effects;
    private final EconomyRewardService economy;
    private final FinishRewardService finishRewards;
    private final Map<UUID, ParkourPlayerSession> sessions = new HashMap<>();
    private final Map<UUID, EventExecutionContext> activeInstances = new HashMap<>();
    private final Set<UUID> restoring = new HashSet<>();
    private ParkourConfig config;
    private HexEventsBridge eventsBridge;
    private BukkitTask tickTask;
    private long tickCounter;

    public ParkourSessionService(
            Plugin plugin,
            SnapshotRepository snapshots,
            ParkourTimesRepository times,
            ParkourItemService items,
            BossBarService bossBars,
            VisibilityService visibility,
            TerrainValidationService terrain,
            EffectService effects,
            EconomyRewardService economy,
            FinishRewardService finishRewards
    ) {
        this.plugin = plugin;
        this.snapshots = snapshots;
        this.times = times;
        this.items = items;
        this.bossBars = bossBars;
        this.visibility = visibility;
        this.terrain = terrain;
        this.effects = effects;
        this.economy = economy;
        this.finishRewards = finishRewards;
    }

    public void configure(ParkourConfig config) {
        this.config = config;
        ensureWorldLoaded();
        for (ParkourPlayerSession session : sessions.values()) {
            Player player = Bukkit.getPlayer(session.playerId());
            if (player == null || !player.isOnline()) continue;
            if (session.state() == ParkourPlayerState.PARKOUR_LOBBY) bossBars.show(player, config.lobbyBossBar());
            else {
                ParkourArena arena = arena(session.currentArenaId());
                if (arena != null) bossBars.show(player, arena.bossBar());
            }
        }
    }

    public void eventsBridge(HexEventsBridge eventsBridge) {
        this.eventsBridge = eventsBridge;
    }

    public boolean available() {
        return snapshots.available() && times.available() && config != null && config.valid() && ensureWorldLoaded() != null;
    }

    public String availabilityReason() {
        if (config == null) return "Config not loaded";
        if (!snapshots.available()) return "Snapshot storage unavailable";
        if (!times.available()) return "Time storage unavailable";
        if (!config.valid()) return "Invalid HexParkour config: " + String.join("; ", config.errors());
        if (ensureWorldLoaded() == null) return "World not loaded: " + config.worldName();
        return "";
    }

    public World ensureWorldLoaded() {
        if (config == null || config.worldName() == null || config.worldName().isBlank()) return null;
        World loaded = Bukkit.getWorld(config.worldName());
        if (loaded != null) return loaded;

        File worldFolder = new File(Bukkit.getWorldContainer(), config.worldName());
        File levelDat = new File(worldFolder, "level.dat");
        if (!worldFolder.isDirectory() || !levelDat.isFile()) {
            plugin.getLogger().warning("Parkour world folder is missing or invalid: " + worldFolder.getAbsolutePath());
            return null;
        }

        try {
            World world = Bukkit.createWorld(new WorldCreator(config.worldName()));
            if (world == null) {
                plugin.getLogger().warning("Bukkit returned null while loading parkour world: " + config.worldName());
                return null;
            }
            plugin.getLogger().info("Loaded parkour world: " + world.getName());
            return world;
        } catch (Throwable error) {
            plugin.getLogger().warning("Could not load parkour world '" + config.worldName() + "': " + rootMessage(error));
            return null;
        }
    }

    public void startTicking() {
        stopTicking();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stopTicking() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
    }

    public void startInstance(EventExecutionContext context) {
        activeInstances.put(context.instanceId(), context);
    }

    public EventJoinResult joinFromHexEvents(EventJoinRequest request) {
        if (!available()) return new EventJoinResult(EventJoinResult.Status.MODULE_UNAVAILABLE, availabilityReason());
        Player player = Bukkit.getPlayer(request.playerId());
        if (player == null || !player.isOnline()) return EventJoinResult.denied("Gracz offline");
        ParkourPlayerSession existing = sessions.get(player.getUniqueId());
        if (existing != null) {
            if (!existing.standalone() && existing.instanceId() != null && existing.instanceId().equals(request.instanceId())) {
                enterLobby(player, existing);
                return EventJoinResult.alreadyJoined();
            }
            return EventJoinResult.denied("Jesteś już w innej instancji Parkoura.");
        }
        try {
            Optional<StoredPlayerState> pending = snapshots.find(player.getUniqueId());
            if (pending.isPresent()) {
                return EventJoinResult.denied(config.message("join-denied-existing-snapshot", "Pending inventory restore."));
            }
            if (!snapshots.saveIfAbsent(player, request.instanceId())) {
                return EventJoinResult.denied(config.message("join-denied-existing-snapshot", "Pending inventory restore."));
            }
        } catch (Throwable error) {
            plugin.getLogger().severe("Could not persist player snapshot before parkour join: " + rootMessage(error));
            return EventJoinResult.error("Nie można zapisać snapshotu ekwipunku.");
        }
        ParkourPlayerSession session = new ParkourPlayerSession(player.getUniqueId(), request.instanceId());
        sessions.put(player.getUniqueId(), session);
        prepareParkourState(player);
        enterLobby(player, session);
        return EventJoinResult.joined();
    }

    public boolean prepareAutoWorldJoin(Player player, Location restoreLocation, Location target) {
        if (config == null || !config.autoJoinWorld() || target == null || target.getWorld() == null) return true;
        if (!target.getWorld().getName().equals(config.worldName())) return true;
        if (restoreLocation != null && restoreLocation.getWorld() != null
                && restoreLocation.getWorld().getName().equals(config.worldName())) return true;
        if (restoring.contains(player.getUniqueId())) return true;
        return beginStandaloneSession(player, restoreLocation, true);
    }

    public ParkourPlayerSession ensureParkourWorldSession(Player player) {
        if (config == null || !config.autoJoinWorld() || player == null || player.getWorld() == null) return session(player);
        if (!player.getWorld().getName().equals(config.worldName())) return session(player);
        ParkourPlayerSession session = sessions.get(player.getUniqueId());
        if (session != null) return session;
        if (!beginStandaloneSession(player, fallbackRestoreLocation(null), true)) return null;
        session = sessions.get(player.getUniqueId());
        if (session != null) enterLobby(player, session);
        return session;
    }

    public boolean enterStandaloneLobby(Player player) {
        if (!beginStandaloneSession(player, player.getLocation(), true)) return false;
        ParkourPlayerSession session = sessions.get(player.getUniqueId());
        if (session == null) return false;
        enterLobby(player, session);
        return true;
    }

    public boolean enterStandaloneArena(Player player, ParkourArena arena) {
        if (arena == null) return false;
        if (!beginStandaloneSession(player, player.getLocation(), true)) return false;
        ParkourPlayerSession session = sessions.get(player.getUniqueId());
        if (session == null) return false;
        enterArena(player, session, arena);
        return true;
    }

    private boolean beginStandaloneSession(Player player, Location restoreLocation, boolean sendMessages) {
        if (!available()) {
            if (sendMessages) player.sendMessage(Text.color(config == null
                    ? "&cHexParkour nie jest zaladowany."
                    : config.message("world-missing", "&cWorld missing.")));
            return false;
        }
        ParkourPlayerSession existing = sessions.get(player.getUniqueId());
        if (existing != null) return true;
        Location safeRestoreLocation = restoreLocation;
        if (safeRestoreLocation == null || safeRestoreLocation.getWorld() == null
                || safeRestoreLocation.getWorld().getName().equals(config.worldName())) {
            safeRestoreLocation = fallbackRestoreLocation(null);
        }
        if (safeRestoreLocation == null || safeRestoreLocation.getWorld() == null) {
            if (sendMessages) player.sendMessage(Text.color("&cNie znaleziono swiata powrotnego dla Parkour."));
            return false;
        }
        try {
            Optional<StoredPlayerState> pending = snapshots.find(player.getUniqueId());
            if (pending.isPresent()) {
                if (sendMessages) player.sendMessage(Text.color(config.message("join-denied-existing-snapshot", "Pending inventory restore.")));
                return false;
            }
            if (!snapshots.saveIfAbsent(player, null, safeRestoreLocation)) {
                if (sendMessages) player.sendMessage(Text.color(config.message("join-denied-existing-snapshot", "Pending inventory restore.")));
                return false;
            }
        } catch (Throwable error) {
            plugin.getLogger().severe("Could not persist player snapshot before standalone parkour join: " + rootMessage(error));
            if (sendMessages) player.sendMessage(Text.color("&cNie mozna zapisac snapshotu ekwipunku."));
            return false;
        }
        sessions.put(player.getUniqueId(), new ParkourPlayerSession(player.getUniqueId(), null, true));
        return true;
    }

    public void leaveFromHexEvents(UUID instanceId, UUID playerId, LeaveReason reason) {
        ParkourPlayerSession session = sessions.get(playerId);
        if (session == null) return;
        Player player = Bukkit.getPlayer(playerId);
        if (reason == LeaveReason.DISCONNECT || player == null || !player.isOnline()) {
            sessions.remove(playerId);
            markPending(playerId);
            if (player != null) cleanupVisuals(player);
            return;
        }
        restoreOnline(player, playerId, true);
    }

    public void requestPlayerLeave(Player player) {
        ParkourPlayerSession session = sessions.get(player.getUniqueId());
        if (session == null) return;
        if (session.standalone() || session.instanceId() == null) {
            restoreOnline(player, player.getUniqueId(), true);
            return;
        }
        if (eventsBridge != null && eventsBridge.leave(player, session.instanceId(), LeaveReason.PLAYER_REQUEST)) {
            scheduleLeaveFallback(player.getUniqueId(), session.instanceId());
            return;
        }
        plugin.getLogger().warning("HexEvents leave bridge unavailable; restoring player locally but participant state may remain in HexEvents.");
        restoreOnline(player, player.getUniqueId(), true);
    }

    public void stopInstance(UUID instanceId, EventStopReason reason) {
        activeInstances.remove(instanceId);
        for (ParkourPlayerSession session : List.copyOf(sessions.values())) {
            if (session.standalone() || session.instanceId() == null || !session.instanceId().equals(instanceId)) continue;
            Player player = Bukkit.getPlayer(session.playerId());
            sessions.remove(session.playerId());
            if (player != null && player.isOnline()) restoreOnline(player, session.playerId(), true);
            else markPending(session.playerId());
        }
        try {
            for (StoredPlayerState snapshot : snapshots.findByInstance(instanceId)) {
                if (!sessions.containsKey(snapshot.playerId())) snapshots.markRestorePending(snapshot.playerId());
            }
        } catch (Throwable error) {
            plugin.getLogger().warning("Could not mark offline snapshots restore-pending for " + instanceId + ": " + rootMessage(error));
        }
    }

    public void shutdown() {
        stopTicking();
        for (ParkourPlayerSession session : List.copyOf(sessions.values())) {
            Player player = Bukkit.getPlayer(session.playerId());
            sessions.remove(session.playerId());
            if (player != null && player.isOnline()) restoreOnline(player, session.playerId(), true);
            else markPending(session.playerId());
        }
        bossBars.hideAll();
    }

    public void handlePendingJoin(Player player) {
        if (!snapshots.available()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || sessions.containsKey(player.getUniqueId())) return;
            try {
                Optional<StoredPlayerState> snapshot = snapshots.find(player.getUniqueId());
                if (snapshot.isEmpty()) return;
                restoreOnline(player, player.getUniqueId(), true);
            } catch (Throwable error) {
                plugin.getLogger().warning("Could not restore pending parkour snapshot for " + player.getName() + ": " + rootMessage(error));
            }
        }, 1L);
    }

    public void handleQuit(Player player) {
        ParkourPlayerSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            if (config != null && player.getWorld() != null && player.getWorld().getName().equals(config.worldName())) {
                teleportToReturnWorld(player);
            }
            return;
        }
        cleanupVisuals(player);
        restoreBeforeDisconnect(player, player.getUniqueId());
    }

    public void handleWorldChange(Player player, World from) {
        if (config == null || restoring.contains(player.getUniqueId())) return;
        ParkourPlayerSession session = sessions.get(player.getUniqueId());
        if (player.getWorld().getName().equals(config.worldName())) {
            if (session == null && config.autoJoinWorld()) {
                beginStandaloneSession(player, fallbackRestoreLocation(from), true);
                session = sessions.get(player.getUniqueId());
            }
            if (session != null && session.state() == ParkourPlayerState.PARKOUR_LOBBY) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    ParkourPlayerSession current = sessions.get(player.getUniqueId());
                    if (player.isOnline() && current != null && current.state() == ParkourPlayerState.PARKOUR_LOBBY
                            && player.getWorld().getName().equals(config.worldName())) {
                        enterLobby(player, current);
                    }
                });
            }
            return;
        }
        if (session == null) return;
        if (from != null && from.getName().equals(config.worldName()) && !player.getWorld().getName().equals(config.worldName())) {
            requestPlayerLeave(player);
        }
    }

    public void handleMove(Player player, Location to) {
        if (config == null || to == null || restoring.contains(player.getUniqueId())) return;
        if (to.getWorld() != null && to.getWorld().getName().equals(config.worldName())) {
            ensureParkourWorldSession(player);
            if (restoring.contains(player.getUniqueId())) return;
        }
        ParkourPlayerSession session = sessions.get(player.getUniqueId());
        if (session == null) return;
        if (!to.getWorld().getName().equals(config.worldName())) return;
        if (!session.changedBlock(to.getBlockX(), to.getBlockY(), to.getBlockZ())) return;
        if (session.state() == ParkourPlayerState.PARKOUR_LOBBY) {
            handleLobbyMove(player, session, to);
            return;
        }
        handleArenaMove(player, session, to);
    }

    public boolean handlePortalTrigger(Player player, Location location) {
        if (config == null || location == null || location.getWorld() == null || restoring.contains(player.getUniqueId())) return false;
        if (!location.getWorld().getName().equals(config.worldName())) return false;
        ParkourPlayerSession session = ensureParkourWorldSession(player);
        if (session == null || session.state() != ParkourPlayerState.PARKOUR_LOBBY) return false;
        return triggerLobbyPortal(player, session, location);
    }

    public ParkourPlayerSession session(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public boolean active(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public boolean restoring(Player player) {
        return restoring.contains(player.getUniqueId());
    }

    public int activeCount(UUID instanceId) {
        int count = 0;
        for (ParkourPlayerSession session : sessions.values()) {
            if (session.instanceId() != null && session.instanceId().equals(instanceId)) count++;
        }
        return count;
    }

    public Iterable<ParkourPlayerSession> sessions() {
        return List.copyOf(sessions.values());
    }

    public ParkourConfig config() {
        return config;
    }

    public void enterLobby(Player player, ParkourPlayerSession session) {
        World world = ensureWorldLoaded();
        Location target = world == null ? null : lobbySpawnLocation();
        if (target == null) {
            player.sendMessage(Text.color(config.message("world-missing", "&cWorld missing.")));
            return;
        }
        session.lobby();
        prepareParkourState(player);
        teleportInternal(player, target);
        items.giveLobbyItems(player.getInventory(), config);
        bossBars.show(player, config.lobbyBossBar());
        visibility.apply(player, session, config.worldName());
    }

    public void enterArena(Player player, ParkourPlayerSession session, ParkourArena arena) {
        World world = ensureWorldLoaded();
        Location target = world == null ? null : parkourArenaSpawn(arena.spawn());
        if (target == null) {
            player.sendMessage(Text.color(config.message("world-missing", "&cWorld missing.")));
            return;
        }
        session.arena(arena.id());
        prepareParkourState(player);
        teleportInternal(player, target);
        items.giveArenaItems(player.getInventory(), config);
        bossBars.show(player, arena.bossBar());
        visibility.apply(player, session, config.worldName());
    }

    public void resetToCheckpoint(Player player, ParkourPlayerSession session, String messageKey) {
        if (session.state() == ParkourPlayerState.FINISHED) return;
        long now = System.currentTimeMillis();
        if (session.resetCooldownActive(now)) return;
        session.startResetCooldown(now, config.resetCooldownTicks());
        Location target = currentResetLocation(session);
        if (target == null) return;
        teleportInternal(player, target);
        effects.playSound(player, config.resetSound());
        String message = config.message(messageKey, config.message("reset", "&eReset."));
        if (!message.isBlank()) player.sendMessage(Text.color(message));
    }

    private void resetForTerrain(Player player, ParkourPlayerSession session) {
        if (session.state() == ParkourPlayerState.FINISHED) return;
        Location target = currentResetLocation(session);
        if (target == null) return;
        teleportInternal(player, target);
        effects.playSound(player, config.resetSound());
        String message = config.message("terrain-reset-subtitle", "&cBłąd");
        if (!message.isBlank()) player.sendTitle("", Text.color(message), 0, 40, 0);
    }

    public Location currentResetLocation(ParkourPlayerSession session) {
        ParkourArena arena = arena(session.currentArenaId());
        if (arena == null) return null;
        return resetLocation(session.attempt(), arena);
    }

    public Location lobbySpawnLocation() {
        return parkourSpawn(config == null ? null : config.lobbySpawn());
    }

    public void toggleVisibility(Player player, ParkourPlayerSession session) {
        visibility.toggle(player, session, config.worldName());
    }

    private void handleLobbyMove(Player player, ParkourPlayerSession session, Location to) {
        triggerLobbyPortal(player, session, to);
    }

    private boolean triggerLobbyPortal(Player player, ParkourPlayerSession session, Location to) {
        long now = System.currentTimeMillis();
        if (session.portalCooldownActive(now)) return false;
        for (ParkourArena arena : config.arenas().values()) {
            if (!arena.portal().contains(to)) continue;
            session.startPortalCooldown(now, config.portalCooldownTicks());
            enterArena(player, session, arena);
            return true;
        }
        return false;
    }

    private void handleArenaMove(Player player, ParkourPlayerSession session, Location to) {
        ParkourArena arena = arena(session.currentArenaId());
        if (arena == null) {
            enterLobby(player, session);
            return;
        }
        if (!arena.region().contains(to)) {
            resetToCheckpoint(player, session, "out-of-region-reset");
            return;
        }
        ParkourAttempt attempt = session.attempt();
        if (attempt == null) return;
        if (session.state() == ParkourPlayerState.FINISHED || attempt.finished()) return;
        long nowNanos = System.nanoTime();
        if (!attempt.timerStarted() && playerTriggerContains(arena.startRegion(), to)) {
            attempt.start(nowNanos);
            String message = config.message("timer-start-subtitle", "&aSTART");
            if (!message.isBlank()) player.sendTitle("", Text.color(message), 0, 40, 0);
            effects.playSound(player, config.startSound());
        }
        handleCheckpoint(player, arena, attempt, to);
        handleMoney(player, arena, attempt, to);
        handleFinish(player, session, arena, attempt, to, nowNanos);
        if (session.state() != ParkourPlayerState.FINISHED) {
            TerrainValidationService.ValidationResult validation = terrain.validate(player, arena);
            if (validation.reset()) resetForTerrain(player, session);
        }
    }

    private void handleCheckpoint(Player player, ParkourArena arena, ParkourAttempt attempt, Location to) {
        int nextOrder = attempt.checkpointOrder() + 1;
        ParkourCheckpoint next = arena.checkpointByOrder(nextOrder);
        if (attempt.finished() || next == null || !pointTriggerContains(next.region(), to)) return;
        attempt.checkpoint(next);
        String message = config.message("checkpoint-subtitle", "&dCheckpoint");
        if (!message.isBlank()) {
            player.sendTitle("", Text.color(message), 0, 35, 10);
        }
        effects.playSound(player, config.checkpointSound());
        effects.spawnRegionMarker(player, config.worldName(), next.region(), config.checkpointParticle());
    }

    private void handleMoney(Player player, ParkourArena arena, ParkourAttempt attempt, Location to) {
        for (MoneyPoint point : arena.moneyPoints().values()) {
            if (!pointTriggerContains(point.region(), to) || attempt.moneyPointClaimed(point.id())) continue;
            attempt.claimMoneyPoint(point.id());
            boolean paid = economy.deposit(player, point.value(), point.id(), config);
            if (!paid) {
                String failed = config.message("money-failed", "");
                if (!failed.isBlank()) player.sendMessage(Text.color(failed));
            }
            String subtitle = point.subtitle()
                    .replace("{amount}", point.value().toPlainString())
                    .replace("{point}", point.id());
            player.sendTitle("", Text.color(subtitle), 0, 35, 10);
            effects.playSound(player, config.moneySound());
            effects.spawnRegionMarker(player, config.worldName(), point.region(), config.moneyParticle());
        }
    }

    private void handleFinish(Player player, ParkourPlayerSession session, ParkourArena arena, ParkourAttempt attempt, Location to, long nowNanos) {
        if (!playerTriggerContains(arena.finishRegion(), to) || attempt.finished()) return;
        if (!attempt.timerStarted()) return;
        attempt.finish(nowNanos);
        session.finished();
        long timeMillis = Math.max(0L, attempt.elapsedNanos(nowNanos) / 1_000_000L);
        boolean finishRecorded = false;
        try {
            times.recordFinish(player.getUniqueId(), player.getName(), arena.id(), timeMillis);
            finishRecorded = true;
        } catch (Throwable error) {
            plugin.getLogger().warning("Could not save HexParkour finish time for "
                    + player.getName() + "/" + arena.id() + ": " + rootMessage(error));
            String message = config.message("finish-record-failed", "");
            if (!message.isBlank()) player.sendMessage(Text.color(message));
        }
        FinishRewardService.GrantResult reward = FinishRewardService.GrantResult.notQualified();
        if (finishRecorded) {
            reward = finishRewards.grantIfEligible(player, arena, timeMillis, config);
            handleFinishRewardMessage(player, reward);
        }
        player.sendTitle(Text.color(config.message("finish-title", "&6Ukończono!")), Text.color(finishTimeSubtitle(timeMillis)), 5, 60, 20);
        effects.playSound(player, config.finishSound());
    }

    private void handleFinishRewardMessage(Player player, FinishRewardService.GrantResult reward) {
        String message = switch (reward.status()) {
            case DISPATCHED -> config.message("finish-reward", "");
            case ALREADY_CLAIMED -> config.message("finish-reward-already-claimed", "");
            case STORAGE_FAILED, DISPATCH_FAILED -> config.message("finish-reward-failed", "");
            case NOT_QUALIFIED -> "";
        };
        if (!message.isBlank()) {
            player.sendMessage(Text.color(message.replace("{amount}", Integer.toString(reward.amount()))));
        }
    }

    private Location resetLocation(ParkourAttempt attempt, ParkourArena arena) {
        World world = ensureWorldLoaded();
        if (world == null) return null;
        if (attempt != null && attempt.checkpointOrder() > 0) {
            ParkourCheckpoint checkpoint = arena.checkpointByOrder(attempt.checkpointOrder());
            if (checkpoint != null) return parkourArenaSpawn(checkpoint.respawn());
        }
        return parkourArenaSpawn(arena.spawn());
    }

    private ParkourArena arena(String id) {
        return id == null ? null : config.arenas().get(id);
    }

    private void prepareParkourState(Player player) {
        player.setItemOnCursor(new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR));
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setExtraContents(null);
        for (var effect : List.copyOf(player.getActivePotionEffects())) player.removePotionEffect(effect.getType());
        player.setGameMode(GameMode.ADVENTURE);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setFallDistance(0.0f);
        player.setFireTicks(0);
        player.updateInventory();
    }

    private void restoreOnline(Player player, UUID playerId, boolean deleteAfterRestore) {
        cleanupVisuals(player);
        restoring.add(playerId);
        try {
            Optional<StoredPlayerState> state = snapshots.find(playerId);
            if (state.isEmpty()) {
                restoreWithoutSnapshot(player, playerId);
                return;
            }
            boolean restored = SnapshotRepository.restore(player, state.get());
            if (!restored) {
                snapshots.markRestorePending(playerId);
                player.sendMessage(Text.color(config.message("leave-failed", "&cRestore failed.")));
                return;
            }
            sessions.remove(playerId);
            if (deleteAfterRestore) snapshots.delete(playerId);
            player.sendMessage(Text.color(config.message("leave-restored", "&aRestored.")));
        } catch (Throwable error) {
            markPending(playerId);
            plugin.getLogger().warning("Could not restore parkour snapshot for " + player.getName() + ": " + rootMessage(error));
            player.sendMessage(Text.color(config.message("leave-failed", "&cRestore failed.")));
        } finally {
            Bukkit.getScheduler().runTaskLater(plugin, () -> restoring.remove(playerId), 2L);
        }
    }

    private void cleanupVisuals(Player player) {
        bossBars.hide(player.getUniqueId());
        visibility.showAll(player);
    }

    private void restoreBeforeDisconnect(Player player, UUID playerId) {
        try {
            Optional<StoredPlayerState> state = snapshots.find(playerId);
            if (state.isPresent() && SnapshotRepository.restore(player, state.get())) {
                snapshots.delete(playerId);
                return;
            }
            markPending(playerId);
            teleportToReturnWorld(player);
        } catch (Throwable error) {
            markPending(playerId);
            plugin.getLogger().warning("Could not restore parkour snapshot before disconnect for "
                    + player.getName() + ": " + rootMessage(error));
            teleportToReturnWorld(player);
        }
    }

    private boolean teleportToReturnWorld(Player player) {
        Location target = fallbackRestoreLocation(null);
        if (target == null) return false;
        player.teleport(target);
        return true;
    }

    private Location fallbackRestoreLocation(World preferredWorld) {
        if (preferredWorld != null && !preferredWorld.getName().equals(config.worldName())) {
            return preferredWorld.getSpawnLocation();
        }
        World configured = Bukkit.getWorld(config.returnWorldName());
        if (configured != null && !configured.getName().equals(config.worldName())) {
            return configured.getSpawnLocation();
        }
        for (World world : Bukkit.getWorlds()) {
            if (!world.getName().equals(config.worldName())) return world.getSpawnLocation();
        }
        return null;
    }

    private void scheduleLeaveFallback(UUID playerId, UUID instanceId) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ParkourPlayerSession current = sessions.get(playerId);
            if (current == null || current.standalone() || current.instanceId() == null || !current.instanceId().equals(instanceId)) return;
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) return;
            plugin.getLogger().warning("HexEvents did not complete Parkour leave request quickly; restoring player locally.");
            restoreOnline(player, playerId, true);
        }, 10L);
    }

    private void restoreWithoutSnapshot(Player player, UUID playerId) {
        sessions.remove(playerId);
        items.clearParkourInventory(player.getInventory());
        player.setItemOnCursor(new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR));
        player.setGameMode(Bukkit.getDefaultGameMode());
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setFallDistance(0.0f);
        player.setFireTicks(0);
        boolean moved = teleportToReturnWorld(player);
        player.sendMessage(Text.color(moved
                ? config.message("leave-restored", "&aRestored.")
                : config.message("leave-failed", "&cRestore failed.")));
        player.updateInventory();
    }

    private Location parkourSpawn(LocationSpec spec) {
        if (spec == null || config == null) return null;
        Location location = spec.toLocation(config.worldName());
        return location == null ? null : location.add(0.0, 1.0, 0.0);
    }

    private Location parkourArenaSpawn(LocationSpec spec) {
        Location location = parkourSpawn(spec);
        if (location == null) return null;
        location.setYaw(WEST_YAW);
        location.setPitch(LEVEL_PITCH);
        return location;
    }

    private boolean playerTriggerContains(CuboidRegion region, Location location) {
        return region != null && region.containsStandingLocation(location);
    }

    private boolean pointTriggerContains(CuboidRegion region, Location location) {
        return region != null && region.containsBlockNear(location, 1, 3);
    }

    private String finishTimeSubtitle(long timeMillis) {
        String time = DurationFormats.formatMillis(timeMillis, config.timerFormat());
        return config.actionbarFormat().replace("{time}", time);
    }

    private void markPending(UUID playerId) {
        try {
            snapshots.markRestorePending(playerId);
        } catch (Throwable error) {
            plugin.getLogger().warning("Could not mark parkour snapshot restore-pending for " + playerId + ": " + rootMessage(error));
        }
    }

    private void teleportInternal(Player player, Location target) {
        restoring.add(player.getUniqueId());
        player.teleport(target);
        Bukkit.getScheduler().runTaskLater(plugin, () -> restoring.remove(player.getUniqueId()), 2L);
    }

    private void tick() {
        if (config == null) return;
        tickCounter += 1L;
        long nowNanos = System.nanoTime();
        for (ParkourPlayerSession session : List.copyOf(sessions.values())) {
            Player player = Bukkit.getPlayer(session.playerId());
            if (player == null || !player.isOnline()) continue;
            if (config.actionbarEnabled() && (session.state() == ParkourPlayerState.ARENA || session.state() == ParkourPlayerState.FINISHED)) {
                player.sendActionBar(actionbar(session, nowNanos));
            }
            validateTerrainTick(player, session);
            if (tickCounter % config.visibilityRefreshTicks() == 0 && session.playersHidden()) {
                visibility.apply(player, session, config.worldName());
            }
            renderMarkers(player, session);
        }
    }

    private Component actionbar(ParkourPlayerSession session, long nowNanos) {
        ParkourAttempt attempt = session.attempt();
        if (attempt == null || !attempt.timerStarted()) return Text.component(config.actionbarNotStartedFormat());
        String time = formatDuration(attempt.elapsedNanos(nowNanos));
        return Text.component(config.actionbarFormat().replace("{time}", time));
    }

    private void renderMarkers(Player player, ParkourPlayerSession session) {
        if (session.state() == ParkourPlayerState.PARKOUR_LOBBY) return;
        if (session.state() == ParkourPlayerState.FINISHED) return;
        ParkourArena arena = arena(session.currentArenaId());
        ParkourAttempt attempt = session.attempt();
        if (arena == null || attempt == null) return;
        if (config.checkpointParticle().enabled() && tickCounter % config.checkpointParticle().intervalTicks() == 0) {
            ParkourCheckpoint next = arena.checkpointByOrder(attempt.checkpointOrder() + 1);
            if (next != null) effects.spawnRegionMarker(player, config.worldName(), next.region(), config.checkpointParticle());
        }
        if (config.moneyParticle().enabled() && tickCounter % config.moneyParticle().intervalTicks() == 0) {
            for (MoneyPoint point : arena.moneyPoints().values()) {
                if (!attempt.moneyPointClaimed(point.id())) {
                    effects.spawnRegionMarker(player, config.worldName(), point.region(), config.moneyParticle());
                }
            }
        }
    }

    private void validateTerrainTick(Player player, ParkourPlayerSession session) {
        if (restoring.contains(player.getUniqueId()) || session.state() != ParkourPlayerState.ARENA) return;
        if (player.getWorld() == null || !player.getWorld().getName().equals(config.worldName())) return;
        ParkourArena arena = arena(session.currentArenaId());
        ParkourAttempt attempt = session.attempt();
        if (arena == null || attempt == null || attempt.finished()) return;
        Location location = player.getLocation();
        if (!arena.region().contains(location)) {
            resetToCheckpoint(player, session, "out-of-region-reset");
            return;
        }
        handleCheckpoint(player, arena, attempt, location);
        handleMoney(player, arena, attempt, location);
        TerrainValidationService.ValidationResult validation = terrain.validate(player, arena);
        if (validation.reset()) resetForTerrain(player, session);
    }

    private String formatDuration(long nanos) {
        return DurationFormats.formatNanos(nanos, config.timerFormat());
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
