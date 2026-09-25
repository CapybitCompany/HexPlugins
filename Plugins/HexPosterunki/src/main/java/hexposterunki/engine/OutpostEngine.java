package hexposterunki.engine;

import hex.core.api.ui.UiTokens;
import hexposterunki.boss.BossSpawnResult;
import hexposterunki.config.LootConfig;
import hexposterunki.config.OutpostDefinition;
import hexposterunki.config.PointDef;
import hexposterunki.config.PosterunkiConfig;
import hexposterunki.config.WaveDefinition;
import hexposterunki.domain.BossStatus;
import hexposterunki.domain.ControlResolver;
import hexposterunki.domain.KillEntry;
import hexposterunki.domain.OutpostStateMachine;
import hexposterunki.domain.Presence;
import hexposterunki.domain.RankingCalculator;
import hexposterunki.domain.RunPhase;
import hexposterunki.domain.RunSnapshot;
import hexposterunki.persistence.AuditEvent;
import hexposterunki.persistence.BlockSnapshotEntry;
import hexposterunki.persistence.CompletionRecord;
import hexposterunki.persistence.OutpostDefinitionCodec;
import hexposterunki.persistence.PosterunkiRepository;
import hexposterunki.persistence.RunPersistenceSnapshot;
import hexposterunki.persistence.TrackedEntity;
import hexposterunki.rewards.FrozenRewards;
import hexposterunki.ui.UiKeys;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates the single server-wide outpost encounter on the main thread.
 *
 * <p>The engine owns the {@link OutpostStateMachine}, drives phase timers and delegates spawning,
 * protection, UI, persistence, rewards and recovery to dedicated services. Listeners contain no
 * logic; they translate a Bukkit event into one call here.
 *
 * <p>Two invariants shape the code:
 * <ul>
 *   <li>A technical failure never produces a victory. Rejected spawns, an unusable boss engine or
 *       an exhausted respawn budget move the run into {@link RunPhase#FAILED}.</li>
 *   <li>Nothing that matters proceeds on unsaved state. {@link #mode()} is the one operating state
 *       the scheduler tick <i>and</i> every event handler consult: while persistence is paused, the
 *       engine is stopped or a reset runs, no damage, kill credit, control change, completion or
 *       reward claim can happen. Region protection keeps working regardless.</li>
 *   <li>The revision covers the whole persisted aggregate. {@link #captureSnapshot()} claims a new
 *       revision whenever anything that is written changed - kill standings and tracked entities
 *       included - and reuses the revision when nothing did.</li>
 * </ul>
 */
public final class OutpostEngine {

    /** Total boss spawn attempts per run: the normal spawn plus at most one recovery respawn. */
    public static final int MAX_BOSS_SPAWNS = 2;

    /** Consecutive ticks a boss entity may be unresolvable before it counts as missing. */
    private static final int BOSS_MISSING_GRACE_TICKS = 3;

    /** Delay before an interrupted cleanup is attempted again (2 seconds). */
    private static final long RESET_RETRY_DELAY_TICKS = 40L;

    private EngineContext ctx;
    private final OutpostStateMachine state = new OutpostStateMachine();
    private final ParticipationService participation;
    private final Map<UUID, TrackedEntity> liveMobs = new LinkedHashMap<>();

    private boolean operational;
    private String stoppedReason = "";
    private String lastOutpostId;
    private long emptySince;
    private long lastSnapshotAt;
    private int lastPersistedMobs = -1;
    private int bossMissingTicks;
    private boolean resetInProgress;
    private boolean wipeInProgress;
    private Object resetToken;
    /** Session-local notification of the requested reset; kept across retries, cleared when it ran. */
    private Runnable pendingResetAction;
    private BukkitTask resetRetryTask;
    /** Told whenever a run failed or a reset finished, so the owner can reconcile the operating state. */
    private Runnable operationListener;
    private boolean persistencePaused;
    private long pausedSinceMillis;

    /** Content and revision of the last captured snapshot, to decide whether a new revision is due. */
    private PersistedContent lastCapturedContent;
    private long lastCapturedRevision = -1L;

    /**
     * Standings and claims of a won run that are not confirmed durable yet. They ride along with
     * every snapshot until the database confirmed a revision that carried them.
     */
    private CompletionRecord pendingCompletion;
    private long completionRevision;

    /**
     * The definition the active run started with. Configuration reloads may change the catalog,
     * but an in-flight run keeps its geometry until it ends.
     */
    private OutpostDefinition activeDefinition;

    /**
     * Loot containers of the current run whose one-time fill is not confirmed by the database yet,
     * by container id. A failed claim stays here and is attempted again by the next running tick.
     */
    private final Map<String, PendingLootFill> pendingLootFills = new LinkedHashMap<>();

    /** Run whose block-state capture finished, and how many states it produced (for honest reporting). */
    private String capturedBlockStatesRunId;
    private int capturedBlockStates;

    /** {@link #activeDefinition} as stored with the run, see {@link OutpostDefinitionCodec}. */
    private String activeDefinitionEncoded;

    public OutpostEngine(EngineContext ctx) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.participation = new ParticipationService(ctx.towns());
    }

    // ---------------------------------------------------------------- lifecycle

    public OutpostStateMachine state() {
        return state;
    }

    public ParticipationService participation() {
        return participation;
    }

    public boolean operational() {
        return operational;
    }

    public String stoppedReason() {
        return stoppedReason;
    }

    public void setOperational(boolean value, String reason) {
        String newReason = value ? "" : (reason == null ? "" : reason);
        boolean changed = this.operational != value || !this.stoppedReason.equals(newReason);
        this.operational = value;
        this.stoppedReason = newReason;
        if (changed && !value && !newReason.isBlank()) {
            ctx.logger().severe("[engine] Obsługa posterunków zatrzymana: " + newReason);
        }
    }

    public String lastOutpostId() {
        return lastOutpostId;
    }

    public void setLastOutpostId(String value) {
        this.lastOutpostId = value;
    }

    public Map<UUID, TrackedEntity> liveMobs() {
        return liveMobs;
    }

    public boolean resetInProgress() {
        return resetInProgress;
    }

    /**
     * The operating state shared by the scheduler and all event handlers.
     *
     * <p>A persistence pause is left only by the tick ({@link #tick()}), which resumes in a controlled
     * way; until then handlers keep treating the encounter as paused even if a write just succeeded.
     */
    public EngineMode mode() {
        if (resetInProgress || state.phase() == RunPhase.RESETTING) {
            return EngineMode.RESETTING;
        }
        if (wipeInProgress) {
            return EngineMode.WIPING;
        }
        if (!operational) {
            return EngineMode.STOPPED;
        }
        if (persistencePaused || !ctx.persistence().healthy()) {
            return EngineMode.PERSISTENCE_PAUSED;
        }
        return EngineMode.RUNNING;
    }

    public EngineContext context() {
        return ctx;
    }

    /**
     * Registers the owner of the operating state. The engine only ever <i>stops</i> operation
     * (a technical failure); whether operation may run again after a failure was cleaned up is decided
     * by the owner - the plugin, which knows the configuration, the bootstrap state and the scheduler.
     * The listener runs on the main thread after a run failed and after every finished reset.
     */
    public void setOperationListener(Runnable listener) {
        this.operationListener = listener;
    }

    private void notifyOperationListener() {
        if (operationListener != null) {
            operationListener.run();
        }
    }

    /**
     * Swaps the collaborators after a reload.
     *
     * <p>Used when an integration adapter was re-resolved: updating the plugin field alone would
     * leave the engine calling the previous boss adapter or a stale CustomMobs handle. The state
     * machine, the participants and the tracked entities are untouched, so a live run survives.
     */
    public void rebind(EngineContext replacement) {
        this.ctx = Objects.requireNonNull(replacement, "replacement");
        participation.rebindTowns(replacement.towns());
        if (activeDefinition != null) {
            replacement.regions().pinActive(activeDefinition);
        }
    }

    /**
     * Pins the definition an active run uses, so a reload can neither move the fight nor strip the
     * protection from the place where it happens. Released once the reset finished.
     */
    public void bindActiveDefinition(OutpostDefinition definition) {
        this.activeDefinition = Objects.requireNonNull(definition, "definition");
        this.activeDefinitionEncoded = OutpostDefinitionCodec.encode(definition);
        ctx.regions().pinActive(definition);
    }

    private void releaseActiveDefinition() {
        this.activeDefinition = null;
        this.activeDefinitionEncoded = null;
        ctx.regions().releaseActive();
    }

    /** True when this exact geometry is the one the current run was started with. */
    public boolean isActiveGeometry(OutpostDefinition definition) {
        return definition != null && activeDefinition != null && activeDefinition.equals(definition)
                && state.outpostId().map(definition.id()::equals).orElse(false);
    }

    // ---------------------------------------------------------------- queries used by listeners

    public RunPhase phase() {
        return state.phase();
    }

    /**
     * The geometry of the current run.
     *
     * <p>While a run holds its location - from the announcement through the reset - only the bound
     * definition counts. The catalog is never consulted for it: after a reload or a restart the id
     * may point somewhere else, and an unknown geometry must stay unknown. Only in the cooldown,
     * when no location is held, does the id resolve against the catalog (status, admin cleanup).
     */
    public Optional<OutpostDefinition> activeOutpost() {
        if (activeDefinition != null && state.outpostId().map(activeDefinition.id()::equals).orElse(false)) {
            return Optional.of(activeDefinition);
        }
        if (state.phase() == RunPhase.COOLDOWN) {
            return state.outpostId().flatMap(id -> ctx.catalog().get().find(id));
        }
        return Optional.empty();
    }

    /** True when the location lies inside the region of the currently running outpost. */
    public boolean activeRegionContains(Location location) {
        if (location == null || location.getWorld() == null || !state.phase().isLive()) {
            return false;
        }
        return activeOutpost()
                .map(outpost -> outpost.region().contains(location.getWorld().getName(),
                        location.getBlockX(), location.getBlockY(), location.getBlockZ()))
                .orElse(false);
    }

    public boolean isRunMob(Entity entity) {
        if (entity == null) {
            return false;
        }
        // Only entities this run actually registered count; a stray tagged leftover does not.
        return liveMobs.containsKey(entity.getUniqueId())
                || state.runId().map(runId -> ctx.tags().belongsTo(entity, runId)
                && !ctx.tags().isBoss(entity) && !ctx.tags().isDisplay(entity)).orElse(false);
    }

    public boolean isRunBoss(Entity entity) {
        if (entity == null) {
            return false;
        }
        return state.bossEntityId().map(id -> id.equals(entity.getUniqueId())).orElse(false)
                || state.runId().map(runId -> ctx.tags().belongsTo(entity, runId) && ctx.tags().isBoss(entity))
                .orElse(false);
    }

    public boolean isEncounterEntity(Entity entity) {
        return isRunMob(entity) || isRunBoss(entity);
    }

    /**
     * Full eligibility answer for damage and kill crediting.
     *
     * <p>Evaluated at the moment of impact, so a projectile fired before its shooter died or left
     * the region is judged by the shooter's state right now.
     */
    public Participation participationOf(Player player) {
        if (player == null) {
            return Participation.NOT_ALIVE;
        }
        Participation gated = ParticipationRules.gate(mode(), state.phase().hasCombat());
        if (gated != null) {
            return gated;
        }
        return ParticipationRules.evaluate(
                state.phase().hasCombat(),
                activeOutpost().isPresent(),
                player.isOnline(),
                !player.isDead(),
                player.getGameMode() == GameMode.SPECTATOR,
                activeRegionContains(player.getLocation()),
                participation.isInside(player.getUniqueId()),
                participation.townOf(player.getUniqueId()).orElse(null),
                state.controllingTown().orElse(null));
    }

    /** Same as {@link #participationOf(Player)} but lets an admin bypass override a refusal. */
    public Participation participationOrBypass(Player player, boolean bypass) {
        return ParticipationRules.withBypass(participationOf(player), bypass);
    }

    /** The town that owns the loot right now: the controller, or the frozen winner after victory. */
    public Optional<UUID> lootOwner() {
        return state.controllingTown();
    }

    // ---------------------------------------------------------------- tick

    /** Called by the scheduler on the main thread. */
    public void tick() {
        boolean cleaning = resetInProgress || state.phase() == RunPhase.RESETTING;
        if (!operational && !cleaning) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!checkPersistenceHealth(now)) {
            return;
        }
        try {
            if (cleaning) {
                // No wave, boss, control or loot progression while the fortress is cleaned up: an
                // emptied wave must never be mistaken for a cleared one. A requested cleanup goes on
                // even when operation is stopped - a failed run is exactly what an admin cleans up.
                if (!resetInProgress) {
                    resumeInterruptedReset();
                }
                updateDisplays();
                return;
            }
            confirmDurableCompletion();
            ctx.persistence().retryPendingSideWrites();
            attemptPendingLootFills();
            syncPresence(now);
            enforceLootAccess();
            switch (state.phase()) {
                case WAITING -> tickWaiting(now);
                case PREPARATION -> tickPreparation(now);
                case WAVES -> tickWaves(now);
                case BOSS -> tickBoss(now);
                case COMPLETED -> startLooting(now);
                case LOOTING -> tickLooting(now);
                case COOLDOWN -> tickCooldown(now);
                case FAILED, RECOVERING -> {
                    // FAILED waits for an administrator; RECOVERING is owned by the recovery service.
                }
            }
            updateDisplays();
            maybeSnapshot(now);
        } catch (RuntimeException exception) {
            ctx.logger().severe("[engine] " + exception);
            failRun("wyjątek w pętli zdarzenia: " + exception.getMessage());
        }
    }

    /**
     * Pauses the engine while state cannot be persisted, and resumes once it can.
     *
     * @return true when the tick may continue
     */
    private boolean checkPersistenceHealth(long now) {
        boolean healthy = ctx.persistence().healthy();
        if (!healthy && !persistencePaused) {
            persistencePaused = true;
            pausedSinceMillis = now;
            ctx.logger().severe("[engine] Wstrzymuję wydarzenie: zapis stanu nie powiódł się ("
                    + ctx.persistence().lastError() + "). Ochrona regionów pozostaje aktywna.");
            ctx.persistence().audit(state.runId().orElse(null), state.outpostId().orElse(null),
                    AuditEvent.PERSISTENCE_PAUSED, "system", ctx.persistence().lastError());
        } else if (healthy && persistencePaused) {
            resumeAfterPersistencePause(now);
        }
        if (!healthy) {
            ctx.persistence().retryPendingWrites();
            return false;
        }
        return true;
    }

    /**
     * Controlled resume once writes succeed again: running deadlines move by the paused time, the
     * grace-period clock and the boss-missing counter restart, and the resumed state is stored.
     */
    private void resumeAfterPersistencePause(long now) {
        long pausedMillis = Math.max(0L, now - pausedSinceMillis);
        persistencePaused = false;
        pausedSinceMillis = 0L;
        if (state.phase().isLive()) {
            state.shiftTimers(pausedMillis);
        }
        emptySince = 0L;
        bossMissingTicks = 0;
        ctx.logger().info("[engine] Zapis stanu działa ponownie, wznawiam wydarzenie po "
                + (pausedMillis / 1000L) + " s przerwy.");
        audit(AuditEvent.PERSISTENCE_RESUMED, "system", "pauza_ms=" + pausedMillis);
        persistState();
    }

    /**
     * Reconciles the tracked presence set with reality. Movement listeners cover the normal case,
     * but a run can start while players already stand inside, and a player can end up outside
     * without a move event.
     */
    private void syncPresence(long now) {
        Optional<OutpostDefinition> outpost = activeOutpost();
        if (outpost.isEmpty() || !state.phase().isLive()) {
            if (!participation.insideIds().isEmpty()) {
                participation.clearPresence();
            }
            return;
        }
        OutpostDefinition definition = outpost.get();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location location = player.getLocation();
            boolean inside = location.getWorld() != null
                    && definition.region().contains(location.getWorld().getName(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
            boolean tracked = participation.isInside(player.getUniqueId());
            if (inside && !tracked) {
                onEnterRegion(player);
            } else if (!inside && tracked) {
                onLeaveRegion(player, UiKeys.PROGRESS_LOST_LEAVE, "leave-region");
            }
        }
    }

    private void tickWaiting(long now) {
        resolveControl(now);
        if (state.phase() != RunPhase.WAITING) {
            return;
        }
        long expires = state.windowExpiresAtMillis();
        if (expires > 0L && now >= expires) {
            activeOutpost().ifPresent(outpost ->
                    ctx.ui().broadcast(UiKeys.ANNOUNCE_EXPIRED, UiTokens.of("name", outpost.displayName())));
            audit(AuditEvent.RUN_RESET, "system", "reason=window-expired");
            beginReset(ctx.config().get().timing().cooldownMillis(), null);
        }
    }

    private void tickPreparation(long now) {
        resolveControl(now);
        if (state.phase() != RunPhase.PREPARATION) {
            return;
        }
        if (state.nextPhaseAtMillis() > 0L && now >= state.nextPhaseAtMillis()) {
            state.startWaves();
            startWave(state.wave());
            persistState();
        }
    }

    private void tickWaves(long now) {
        resolveControl(now);
        if (state.phase() != RunPhase.WAVES) {
            return;
        }
        refreshLiveMobs();
        if (!liveMobs.isEmpty()) {
            return;
        }
        PosterunkiConfig config = ctx.config().get();
        if (state.nextPhaseAtMillis() == 0L) {
            ctx.ui().broadcast(UiKeys.WAVE_CLEARED, UiTokens.of("wave", String.valueOf(state.wave())));
            if (state.wave() >= config.waveCount()) {
                onAllWavesCleared();
                return;
            }
            state.setNextPhaseAt(now + config.timing().waveDelaySeconds() * 1000L);
            persistState();
            return;
        }
        if (now >= state.nextPhaseAtMillis()) {
            state.setWave(state.wave() + 1);
            startWave(state.wave());
            persistState();
        }
    }

    private void tickBoss(long now) {
        resolveControl(now);
        if (state.phase() != RunPhase.BOSS) {
            return;
        }
        if (state.bossStatus() != BossStatus.ALIVE) {
            return;
        }
        Optional<UUID> bossEntity = state.bossEntityId();
        if (bossEntity.isEmpty()) {
            return;
        }
        Entity entity = Bukkit.getEntity(bossEntity.get());
        if (entity != null && entity.isValid() && !entity.isDead()) {
            bossMissingTicks = 0;
            return;
        }
        // A missing entity is not a confirmed death: it may be a chunk hiccup or a foreign remove.
        // Only a real EntityDeathEvent counts as "defeated".
        bossMissingTicks++;
        if (bossMissingTicks < BOSS_MISSING_GRACE_TICKS) {
            return;
        }
        bossMissingTicks = 0;
        onBossVanished();
    }

    private void tickLooting(long now) {
        if (state.lootUntilMillis() > 0L && now < state.lootUntilMillis()) {
            return;
        }
        ctx.ui().broadcast(UiKeys.LOOTING_ENDED, new UiTokens());
        beginReset(ctx.config().get().timing().cooldownMillis(), null);
    }

    private void tickCooldown(long now) {
        if (resetInProgress) {
            // Never start a new run on a location whose reset is still running.
            return;
        }
        if (state.cooldownUntilMillis() > 0L && now < state.cooldownUntilMillis()) {
            return;
        }
        beginNewRun(null, now, "auto");
    }

    // ---------------------------------------------------------------- run lifecycle

    /**
     * Picks (or takes) a location and announces it.
     *
     * @param forcedOutpostId admin override, or null for the automatic weighted pick
     */
    public boolean beginNewRun(String forcedOutpostId, long now, String actor) {
        if (!mode().allowsProgress()) {
            // Never on a location that is still being reset, never on unsaved or stopped state.
            return false;
        }
        PosterunkiConfig config = ctx.config().get();
        Optional<OutpostDefinition> picked = forcedOutpostId == null
                ? ctx.selector().select(ctx.catalog().get().all(), lastOutpostId, config.avoidImmediateRepeat(), ctx.random())
                : ctx.catalog().get().find(forcedOutpostId);
        if (picked.isEmpty()) {
            ctx.logger().warning("[engine] Brak dostępnego posterunku do uruchomienia.");
            return false;
        }
        OutpostDefinition outpost = picked.get();

        String runId = UUID.randomUUID().toString();
        state.beginRun(runId, outpost.id(), now, config.timing().activeWindowMillis());
        bindActiveDefinition(outpost);
        lastOutpostId = outpost.id();
        emptySince = 0L;
        bossMissingTicks = 0;
        liveMobs.clear();
        lastPersistedMobs = -1;
        participation.clear();
        ctx.loot().clearUnlocked();
        pendingLootFills.clear();
        ctx.rewards().resetLedger();

        // The boss is rolled exactly once per run, right here, and persisted with the run.
        // A grace-period wipe keeps this roll, so wiping cannot be used to farm fresh rolls.
        Optional<String> rolledBoss = ctx.bossRoller().roll(config.boss(), ctx.random());
        state.applyBossRoll(rolledBoss.orElse(null));

        ctx.chunkTickets().acquire(outpost, config.protection().chunkTicketLimit());
        capturedBlockStatesRunId = null;
        ctx.blockStates().captureAsync(outpost, config.protection(), snapshot -> {
            // A capture that outlived its run must not store states for a run being cleaned up.
            if (RunGuard.stillValid(runId, state.runId().orElse(null), state.phase(), resetInProgress)) {
                capturedBlockStatesRunId = runId;
                capturedBlockStates = snapshot.size();
                ctx.persistence().saveBlockSnapshot(runId, snapshot);
            }
        });

        audit(AuditEvent.OUTPOST_SELECTED, actor, "outpost=" + outpost.id());
        audit(AuditEvent.RUN_STARTED, actor, "outpost=" + outpost.id() + " boss=" + rolledBoss.orElse("-"));
        audit(AuditEvent.BOSS_ROLLED, actor, "boss=" + rolledBoss.orElse("brak"));

        announceActivation(outpost);
        persistState();
        return true;
    }

    private void announceActivation(OutpostDefinition outpost) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            long distance = distanceTo(player, outpost);
            ctx.ui().send(player, UiKeys.ANNOUNCE_ACTIVATED, UiTokens.of("name", outpost.displayName())
                    .put("distance", distance < 0 ? "—" : String.valueOf(distance)));
        }
        ctx.ui().broadcastSound(Sound.ENTITY_ENDER_DRAGON_GROWL);
    }

    private long distanceTo(Player player, OutpostDefinition outpost) {
        if (!player.getWorld().getName().equals(outpost.world())) {
            return -1L;
        }
        return Math.round(Math.sqrt(outpost.region().horizontalDistanceSquared(
                player.getLocation().getX(), player.getLocation().getZ())));
    }

    /** Moves the run into the technical failure state. Never a victory, never a loot payout. */
    public void failRun(String reason) {
        if (state.phase() == RunPhase.FAILED) {
            return;
        }
        ctx.logger().severe("[engine] Błąd wydarzenia: " + reason);
        if (state.canTransition(RunPhase.FAILED)) {
            state.fail(reason);
        }
        audit(AuditEvent.RUN_FAILED, "system", reason);
        ctx.ui().broadcast(UiKeys.ANNOUNCE_FAILED, new UiTokens());
        setOperational(false, reason);
        persistState();
        notifyOperationListener();
    }

    // ---------------------------------------------------------------- control

    private void resolveControl(long now) {
        if (!mode().allowsProgress() || !state.phase().allowsControlChange()) {
            return;
        }
        PosterunkiConfig config = ctx.config().get();
        List<Presence> presences = participation.presences();

        if (presences.isEmpty()) {
            if (emptySince == 0L) {
                emptySince = now;
            } else if (state.phase().hasCombat()
                    && now - emptySince >= config.timing().gracePeriodMillis()) {
                wipeToWaveOne();
            }
            return;
        }
        emptySince = 0L;

        Optional<UUID> resolved = ControlResolver.resolve(state.controllingTown().orElse(null), presences);
        if (resolved.isEmpty()) {
            return;
        }
        UUID town = resolved.get();
        if (state.controllingTown().map(town::equals).orElse(false)) {
            return;
        }

        String townName = ctx.towns().townNameOr(town, "?");
        if (state.phase() == RunPhase.WAITING) {
            state.claim(town, townName, now + config.timing().preparationSeconds() * 1000L);
            audit(AuditEvent.TOWN_CLAIMED, townName, "town=" + town);
            activeOutpost().ifPresent(outpost -> {
                ctx.ui().broadcast(UiKeys.CLAIM_BROADCAST, UiTokens.of("town", townName)
                        .put("name", outpost.displayName()));
                for (Player player : participation.onlineInside()) {
                    ctx.ui().title(player, UiKeys.CLAIM_TITLE, UiKeys.CLAIM_SUBTITLE,
                            UiTokens.of("name", outpost.displayName()));
                    ctx.ui().sound(player, Sound.ENTITY_PLAYER_LEVELUP);
                }
                if (config.loot().fillPhase() == LootConfig.FillPhase.RUN_START) {
                    fillLoot(outpost);
                }
            });
        } else {
            // Direct takeover: wave, live mobs and boss state stay exactly as they are.
            state.takeover(town, townName);
            audit(AuditEvent.TOWN_TAKEOVER, townName, "town=" + town + " wave=" + state.wave());
            ctx.ui().broadcast(UiKeys.TAKEOVER_BROADCAST, UiTokens.of("town", townName));
            for (Player player : participation.onlineInside()) {
                ctx.ui().title(player, UiKeys.TAKEOVER_TITLE, UiKeys.TAKEOVER_SUBTITLE,
                        UiTokens.of("town", townName));
                ctx.ui().sound(player, Sound.ENTITY_WITHER_SPAWN);
            }
            // Loot windows opened by the previous holder close immediately.
            enforceLootAccess();
        }
        persistState();
    }

    /**
     * Grace period elapsed with nobody eligible inside: remove the encounter, fall back to wave 1
     * on the same location and clear all kill counters. The boss roll of this run is kept.
     */
    private void wipeToWaveOne() {
        // Freeze the fight before anything is removed: stopping the boss may fire its death event right
        // here, and an abandoned fight must not turn into a victory, loot or reward claims.
        wipeInProgress = true;
        try {
            removeEncounterEntities();
            participation.clear();
            pendingLootFills.clear();
            emptySince = 0L;
            lastPersistedMobs = -1;
            bossMissingTicks = 0;
            state.wipeToWaveOne();
        } finally {
            wipeInProgress = false;
        }
        activeOutpost().ifPresent(outpost ->
                ctx.ui().broadcast(UiKeys.ANNOUNCE_WIPED, UiTokens.of("name", outpost.displayName())));
        audit(AuditEvent.RUN_RESET, "system", "reason=grace-period-empty wave=1");
        persistState();
    }

    // ---------------------------------------------------------------- waves

    private void startWave(int waveNumber) {
        PosterunkiConfig config = ctx.config().get();
        WaveDefinition wave = config.wave(waveNumber);
        Optional<OutpostDefinition> outpost = activeOutpost();
        if (wave == null || outpost.isEmpty()) {
            failRun("brak definicji fali " + waveNumber + " lub posterunku");
            return;
        }
        state.setNextPhaseAt(0L);
        liveMobs.clear();

        int expected = wave.totalMobs();
        WaveSpawner.SpawnResult result = ctx.waveSpawner()
                .spawnWave(outpost.get(), wave, state.runId().orElseThrow(), participation.presences().size());
        result.problems().forEach(problem -> ctx.logger().warning("[wave] " + problem));
        result.spawned().forEach(entity -> liveMobs.put(entity.entityId(), entity));

        if (result.count() == 0 && expected > 0) {
            // An empty wave would look "cleared" one tick later and hand out an undeserved victory.
            failRun("fala " + waveNumber + " nie utworzyła żadnego moba: "
                    + String.join("; ", result.problems()));
            return;
        }
        if (result.count() < expected) {
            audit(AuditEvent.WAVE_STARTED, "system",
                    "wave=" + waveNumber + " spawned=" + result.count() + " expected=" + expected
                            + " problems=" + String.join("; ", result.problems()));
        }

        syncMobsRemaining();
        audit(AuditEvent.WAVE_STARTED, "system", "wave=" + waveNumber + " mobs=" + result.count());
        ctx.ui().broadcast(UiKeys.WAVE_STARTED, UiTokens.of("wave", String.valueOf(waveNumber))
                .put("total", String.valueOf(config.waveCount())));
        persistState();
    }

    private void onAllWavesCleared() {
        PosterunkiConfig config = ctx.config().get();
        state.setNextPhaseAt(0L);
        if (config.loot().fillPhase() == LootConfig.FillPhase.WAVES_CLEARED) {
            activeOutpost().ifPresent(this::fillLoot);
        }
        if (state.bossId().isPresent() && config.boss().enabled()
                && state.bossStatus() != BossStatus.DEAD) {
            state.toBoss();
            persistState();
            spawnBoss();
            return;
        }
        completeRun();
    }

    private void refreshLiveMobs() {
        List<UUID> gone = new ArrayList<>();
        for (UUID id : liveMobs.keySet()) {
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || entity.isDead() || !entity.isValid()) {
                gone.add(id);
            }
        }
        if (!gone.isEmpty()) {
            gone.forEach(liveMobs::remove);
            syncMobsRemaining();
            persistState();
        }
    }

    private void syncMobsRemaining() {
        if (lastPersistedMobs == liveMobs.size()) {
            return;
        }
        lastPersistedMobs = liveMobs.size();
        state.setMobsRemaining(liveMobs.size());
    }

    // ---------------------------------------------------------------- boss

    private void spawnBoss() {
        Optional<OutpostDefinition> outpost = activeOutpost();
        Optional<String> bossId = state.bossId();
        if (outpost.isEmpty() || bossId.isEmpty()) {
            failRun("brak posterunku lub wylosowanego bossa w fazie BOSS");
            return;
        }
        if (state.bossSpawnAttempts() >= MAX_BOSS_SPAWNS) {
            failRun("wyczerpany limit prób przywołania bossa '" + bossId.get() + "'");
            return;
        }
        if (ctx.bossAdapter() == null || !ctx.bossAdapter().health().ready()) {
            failRun("adapter bossa niegotowy: "
                    + (ctx.bossAdapter() == null ? "brak" : ctx.bossAdapter().health().message()));
            return;
        }
        World world = Bukkit.getWorld(outpost.get().world());
        if (world == null) {
            failRun("świat '" + outpost.get().world() + "' nie jest załadowany");
            return;
        }
        PointDef point = outpost.get().bossSpawn();
        Location location = new Location(world, point.x(), point.y(), point.z(), point.yaw(), point.pitch());

        BossSpawnResult result = ctx.bossAdapter().spawn(bossId.get(), location);
        if (!result.success()) {
            state.bossSpawnFailed();
            audit(AuditEvent.BOSS_SPAWNED, "system", "boss=" + bossId.get() + " ok=false reason=" + result.message());
            // A rejected spawn is a technical failure, never a completed encounter.
            failRun("nie udało się przywołać bossa '" + bossId.get() + "': " + result.message());
            return;
        }
        Entity entity = Bukkit.getEntity(result.entityId());
        if (entity != null) {
            ctx.tags().tagBoss(entity, state.runId().orElseThrow(), outpost.get().id());
        }
        state.bossSpawned(result.entityId());
        bossMissingTicks = 0;
        audit(AuditEvent.BOSS_SPAWNED, "system", "boss=" + bossId.get() + " entity=" + result.entityId());

        ctx.ui().broadcast(UiKeys.BOSS_BROADCAST, UiTokens.of("name", outpost.get().displayName())
                .put("boss", bossId.get()));
        for (Player player : participation.onlineInside()) {
            ctx.ui().title(player, UiKeys.BOSS_TITLE, UiKeys.BOSS_SUBTITLE, UiTokens.of("boss", bossId.get()));
            ctx.ui().sound(player, Sound.ENTITY_WITHER_SPAWN);
        }
        persistState();
    }

    /**
     * Re-binds an existing boss entity found during recovery.
     *
     * <p>Explicitly does not consume a spawn attempt: nothing was created, so any number of
     * restarts with a living boss leaves the respawn budget intact.
     */
    public void reattachBoss(UUID entityId) {
        state.bossReattached(entityId);
        bossMissingTicks = 0;
        audit(AuditEvent.BOSS_REATTACHED, "system", "entity=" + entityId);
    }

    /** Called by the listener when the run boss actually dies. */
    public void onBossDeath(Entity entity) {
        if (!isRunBoss(entity)) {
            return;
        }
        EngineMode mode = mode();
        boolean fightingThisBoss = state.phase() == RunPhase.BOSS
                && state.bossEntityId().map(entity.getUniqueId()::equals).orElse(false);
        if (!mode.allowsProgress() || !fightingThisBoss) {
            // A death that cannot be stored (paused persistence, stopped engine), that a reset or a
            // grace wipe caused, or that arrives late for a boss the run no longer fights is never a
            // victory. The state - including the boss roll - stays untouched; a boss that is really
            // gone while the run fights it is handled like any vanished boss: respawn or failure.
            audit(AuditEvent.BOSS_MISSING, "system", "boss=" + state.bossId().orElse("-")
                    + " smierc-zignorowana tryb=" + mode.name() + " faza=" + state.phase().name()
                    + (fightingThisBoss ? "" : " encja-nieaktualna"));
            return;
        }
        state.bossDied();
        bossMissingTicks = 0;
        audit(AuditEvent.BOSS_DIED, "system", "boss=" + state.bossId().orElse("-"));
        ctx.ui().broadcast(UiKeys.BOSS_DEFEATED, UiTokens.of("boss", state.bossId().orElse("-")));
        completeRun();
    }

    /**
     * The boss entity cannot be found and no death event arrived. This is a technical problem, so
     * it either gets the remaining recovery spawn or stops the run - it never counts as a win.
     */
    private void onBossVanished() {
        state.bossMissing();
        audit(AuditEvent.BOSS_MISSING, "system",
                "boss=" + state.bossId().orElse("-") + " attempts=" + state.bossSpawnAttempts());
        if (state.bossSpawnAttempts() < MAX_BOSS_SPAWNS) {
            ctx.logger().warning("[boss] Boss zniknął bez śmierci - próbuję przywołać ponownie.");
            spawnBoss();
            return;
        }
        failRun("boss '" + state.bossId().orElse("-") + "' zniknął, a limit prób został wyczerpany");
    }

    // ---------------------------------------------------------------- kills

    /**
     * Called by the listener for a kill on a run mob.
     *
     * <p>The mob always stops counting toward the wave, but the kill is only credited to a player
     * who is a full participant at that moment.
     */
    public void onRunMobDeath(LivingEntity mob, Player killer) {
        UUID mobId = mob.getUniqueId();
        if (!liveMobs.containsKey(mobId)) {
            // Either not ours at all, or a second event for a mob that was already counted.
            // Neither may produce another kill credit.
            return;
        }
        EngineMode mode = mode();
        if (!mode.allowsProgress()) {
            // Frozen encounter: nobody is credited and the wave does not count down now. The next
            // running tick drops the dead entity from the wave without crediting anyone.
            if (killer != null) {
                audit(AuditEvent.PLAYER_PROGRESS_LOST, killer.getName(),
                        "kill-odrzucone reason=" + Participation.SUSPENDED.polishReason() + " tryb=" + mode.name());
            }
            return;
        }
        liveMobs.remove(mobId);
        syncMobsRemaining();

        if (killer == null) {
            persistState();
            return;
        }
        Participation eligibility = participationOf(killer);
        if (!eligibility.mayEarnKill()) {
            // Wave progress still counts down; the kill simply has no owner.
            audit(AuditEvent.PLAYER_PROGRESS_LOST, killer.getName(),
                    "kill-odrzucone reason=" + eligibility.polishReason());
            persistState();
            return;
        }
        participation.recordKill(killer.getUniqueId(), System.currentTimeMillis());
        persistState();
    }

    /** Resets one player's progress and tells them why. Reasons map to the Polish UI templates. */
    public void loseProgress(Player player, String reasonKey, String auditReason) {
        boolean hadProgress = participation.resetProgress(player.getUniqueId());
        if (!hadProgress) {
            return;
        }
        if (player.isOnline() && reasonKey != null) {
            ctx.ui().send(player, reasonKey, new UiTokens());
            ctx.ui().sound(player, Sound.ENTITY_ITEM_BREAK);
        }
        audit(AuditEvent.PLAYER_PROGRESS_LOST, player.getName(), "reason=" + auditReason);
        persistState();
    }

    // ---------------------------------------------------------------- presence

    public void onEnterRegion(Player player) {
        long now = System.currentTimeMillis();
        if (!participation.markInside(player.getUniqueId(), now)) {
            return;
        }
        if (participation.townOf(player.getUniqueId()).isEmpty()) {
            ctx.ui().send(player, UiKeys.NO_TOWN, new UiTokens());
        }
        resolveControl(now);
    }

    public void onLeaveRegion(Player player, String reasonKey, String auditReason) {
        if (!participation.markOutside(player.getUniqueId())) {
            return;
        }
        // Reward claims are frozen at victory and are not affected by leaving afterwards.
        loseProgress(player, reasonKey, auditReason);
        closeLootInventory(player);
        resolveControl(System.currentTimeMillis());
    }

    /** Server shutdown must not be treated as a voluntary leave; the caller decides. */
    public void onQuit(Player player, boolean shuttingDown) {
        participation.markOutside(player.getUniqueId());
        ctx.displays().hide(player);
        if (shuttingDown) {
            return;
        }
        loseProgress(player, null, "logout");
    }

    // ---------------------------------------------------------------- completion / loot phase

    private void completeRun() {
        PosterunkiConfig config = ctx.config().get();
        long now = System.currentTimeMillis();
        state.complete();

        Optional<OutpostDefinition> outpost = activeOutpost();
        if (config.loot().fillPhase() == LootConfig.FillPhase.COMPLETED) {
            outpost.ifPresent(this::fillLoot);
        }

        UUID town = state.controllingTown().orElse(null);
        String townName = state.controllingTownName().isBlank() ? "?" : state.controllingTownName();
        String runId = state.runId().orElse(null);
        List<KillEntry> entries = List.copyOf(participation.ledger().entries());

        if (runId != null && town != null) {
            // Entitlements are frozen here, once, and survive leaving, logging out and restarts.
            // They are written in the same transaction as the COMPLETED phase below.
            String outpostId = state.outpostId().orElse("-");
            FrozenRewards frozen = ctx.rewards().freezeClaims(runId, outpostId, town,
                    entries, config.requiredKills(), config.rewards());
            pendingCompletion = new CompletionRecord(runId, outpostId, now,
                    statRows(frozen.ranking(), entries), frozen.claims());
            completionRevision = 0L;
        }
        if (config.rewards().enabled()) {
            notifyIneligible(entries, town, config.requiredKills());
        }

        outpost.ifPresent(definition -> {
            ctx.ui().broadcast(UiKeys.ANNOUNCE_COMPLETED, UiTokens.of("town", townName)
                    .put("name", definition.displayName()));
            for (Player player : participation.onlineInside()) {
                ctx.ui().title(player, UiKeys.VICTORY_TITLE, UiKeys.VICTORY_SUBTITLE,
                        UiTokens.of("name", definition.displayName()).put("town", townName));
                ctx.ui().sound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE);
            }
        });
        audit(AuditEvent.COMPLETED, townName, "wave=" + state.wave() + " boss=" + state.bossId().orElse("-"));
        persistState();
    }

    /** Opens the loot window after a victory. Containers and drops stay available until it ends. */
    private void startLooting(long now) {
        PosterunkiConfig config = ctx.config().get();
        long lootMillis = config.timing().lootMillis();
        if (lootMillis <= 0L) {
            beginReset(config.timing().cooldownMillis(), null);
            return;
        }
        state.startLooting(now + lootMillis);
        audit(AuditEvent.LOOTING_STARTED, "system", "seconds=" + config.timing().lootSeconds());
        activeOutpost().ifPresent(outpost -> ctx.ui().broadcast(UiKeys.LOOTING_BROADCAST,
                UiTokens.of("town", state.controllingTownName().isBlank() ? "?" : state.controllingTownName())
                        .put("seconds", String.valueOf(config.timing().lootSeconds()))
                        .put("name", outpost.displayName())));
        persistState();
    }

    private void notifyIneligible(List<KillEntry> entries, UUID town, int requiredKills) {
        if (town == null) {
            return;
        }
        for (KillEntry entry : entries) {
            if (entry.kills() >= requiredKills || !town.equals(entry.townId())) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.playerId());
            if (player != null && player.isOnline()) {
                ctx.ui().send(player, UiKeys.REWARD_NOT_ELIGIBLE,
                        UiTokens.of("required", String.valueOf(requiredKills)));
            }
        }
    }

    private static List<PosterunkiRepository.StatRow> statRows(List<KillEntry> ranking, List<KillEntry> entries) {
        Map<UUID, Integer> places = new LinkedHashMap<>();
        for (int i = 0; i < ranking.size(); i++) {
            places.put(ranking.get(i).playerId(), i + 1);
        }
        List<PosterunkiRepository.StatRow> rows = new ArrayList<>();
        for (KillEntry entry : entries) {
            rows.add(new PosterunkiRepository.StatRow(entry.playerId(), entry.townId(), entry.kills(),
                    places.getOrDefault(entry.playerId(), 0)));
        }
        return rows;
    }

    /**
     * Runs once the database confirmed a revision that carried the frozen completion: only now are
     * the claims real, so only now are they announced and handed to the delivery.
     */
    private void confirmDurableCompletion() {
        if (pendingCompletion == null || completionRevision <= 0L
                || ctx.persistence().lastCommittedRevision() < completionRevision) {
            return;
        }
        CompletionRecord durable = pendingCompletion;
        pendingCompletion = null;
        completionRevision = 0L;
        if (durable.claims().isEmpty()) {
            return;
        }
        ctx.persistence().audit(durable.runId(), durable.outpostId(), AuditEvent.REWARD_CLAIMED, "system",
                "claims=" + durable.claims().size());
        ctx.rewards().deliverOutstanding();
    }

    // ---------------------------------------------------------------- reset

    /**
     * Cleans the fortress back to its resting state and then enters the configured cooldown.
     *
     * @param afterCooldown runs on the main thread once the cooldown was entered; may be null
     * @return false when a reset is already running or the current phase cannot be reset
     */
    public boolean beginReset(Runnable afterCooldown) {
        return beginReset(ctx.config().get().timing().cooldownMillis(), afterCooldown);
    }

    /**
     * Cleans the fortress back to its resting state.
     *
     * <p>The run enters the persisted {@link RunPhase#RESETTING} phase <i>before</i> anything is
     * removed, so neither a tick nor a late death event can read the emptied wave as a victory, and
     * a restart during the multi-tick cleanup finishes the cleanup instead of resuming the fight.
     * The cleanup is split into a fast part and budgeted scans over several ticks. Chunk tickets are
     * held until everything finished, and no new run may start on this location before then.
     *
     * @param cooldownMillis cooldown that starts once the fortress is clean
     * @param afterCooldown  runs on the main thread once the cooldown was entered; may be null
     * @return false when a reset is already running or the current phase cannot be reset
     */
    public boolean beginReset(long cooldownMillis, Runnable afterCooldown) {
        if (resetInProgress) {
            return false;
        }
        if (state.phase() != RunPhase.RESETTING && !state.canTransition(RunPhase.RESETTING)) {
            ctx.logger().warning("[reset] Reset niemożliwy w fazie " + state.phase().polishLabel() + ".");
            return false;
        }
        if (state.phase() == RunPhase.RECOVERING) {
            return false;
        }
        resetInProgress = true;
        Object token = new Object();
        resetToken = token;
        pendingResetAction = afterCooldown;
        cancelResetRetry();
        pendingLootFills.clear();

        PosterunkiConfig config = ctx.config().get();
        Optional<OutpostDefinition> outpost = activeOutpost();
        String runId = state.runId().orElse(null);

        ctx.blockStates().cancelCapture();
        removeEncounterEntities();
        ctx.displays().removeHologram();
        ctx.displays().clearBossBars();
        closeAllLootInventories();

        outpost.ifPresent(definition -> {
            ctx.loot().reset(definition, config.loot());
            removeTemporaryEntities(definition, config.protection().removeGroundItems());
        });

        participation.clear();
        liveMobs.clear();
        lastPersistedMobs = -1;
        emptySince = 0L;
        bossMissingTicks = 0;
        if (state.phase() != RunPhase.RESETTING) {
            state.beginReset(cooldownMillis);
        }
        audit(AuditEvent.RUN_RESET, "system", "outpost=" + state.outpostId().orElse("-")
                + " cooldown_ms=" + state.resetCooldownMillis());
        persistState();

        if (runId == null) {
            finishReset(token);
            return true;
        }
        if (outpost.isEmpty()) {
            // The geometry of this run is unknown (data from an older build). Only what does not
            // depend on it is cleaned: tagged entities in every world and the stored block states.
            // Fire, containers and loose items inside the old region are NOT checked - the audit says
            // so instead of pretending the location is clean.
            ctx.logger().warning("[reset] Brak zapisanej geometrii runu " + runId + ": usuwam oznaczone encje"
                    + " i przywracam zapisane bloki, ale ogień, kontenery i przedmioty wymagają ręcznej kontroli.");
            audit(AuditEvent.RUN_RESET, "system", "bez-geometrii: ogien/kontenery/przedmioty niesprawdzone");
            restoreStoredBlocksThenFinish(token, runId);
            return true;
        }

        // 1) budgeted fire sweep, 2) restore stored block states, 3) drop stored artifacts.
        ctx.blockStates().clearFireAsync(outpost.get(), config.protection(),
                cleared -> restoreStoredBlocksThenFinish(token, runId));
        return true;
    }

    private void restoreStoredBlocksThenFinish(Object token, String runId) {
        Optional<List<BlockSnapshotEntry>> captured = ctx.persistence().pendingBlockSnapshot(runId);
        if (captured.isPresent()) {
            // The database has not confirmed these states yet, but they are the ones this run started
            // with - restoring them from memory beats reading an empty table.
            ctx.logger().warning("[reset] Stan bloków runu " + runId + " nie został jeszcze zapisany w bazie;"
                    + " przywracam zapamiętany stan z chwili startu runu.");
            if (!captured.get().isEmpty()) {
                ctx.blockStates().restore(captured.get());
            }
            ctx.persistence().clearRunArtifacts(runId);
            finishReset(token);
            return;
        }
        ctx.persistence().loadBlockSnapshot(runId).thenAccept(snapshot ->
                Bukkit.getScheduler().runTask(ctx.plugin(), () -> {
                    if (token != resetToken) {
                        return;
                    }
                    if (!snapshot.isEmpty()) {
                        ctx.blockStates().restore(snapshot);
                    } else if (!runId.equals(capturedBlockStatesRunId) || capturedBlockStates > 0) {
                        // Either nothing was ever stored for this run or the capture never finished:
                        // say so instead of reporting a successfully restored, empty fortress.
                        ctx.logger().warning("[reset] Brak zapisanego stanu bloków runu " + runId
                                + " - nie przywracam drzwi ani przełączników; sprawdź teren posterunku.");
                    }
                    ctx.persistence().clearRunArtifacts(runId);
                    finishReset(token);
                })).exceptionally(error -> {
            ctx.logger().warning("[reset] Nie udało się wczytać snapshotu bloków: " + error.getMessage()
                    + ". Reset zostanie powtórzony.");
            Bukkit.getScheduler().runTask(ctx.plugin(), () -> abortResetForRetry(token));
            return null;
        });
    }

    /**
     * Continues a cleanup that a restart or a failed step interrupted. The stored cooldown and the
     * pending notification are kept; nothing of the interrupted run is resumed.
     */
    public boolean resumeInterruptedReset() {
        if (state.phase() != RunPhase.RESETTING) {
            return false;
        }
        return beginReset(state.resetCooldownMillis(), pendingResetAction);
    }

    /** Admin stop: clean the world and drop into the regular cooldown. Never a completion. */
    public boolean adminStop() {
        return beginReset(ctx.config().get().timing().cooldownMillis(), null);
    }

    /**
     * Admin reset: clean the world and skip the cooldown.
     *
     * <p>Deliberately does not re-enable operation. Whether the event may run again afterwards
     * depends on the configuration and the bootstrap state, which only the operation listener's
     * owner knows (see {@link #setOperationListener(Runnable)}).
     *
     * @param afterCooldown optional session-local notification once the cleanup finished
     */
    public boolean adminReset(Runnable afterCooldown) {
        return beginReset(0L, afterCooldown);
    }

    private void finishReset(Object token) {
        if (token != resetToken || !resetInProgress) {
            return;
        }
        resetToken = null;
        resetInProgress = false;
        cancelResetRetry();
        ctx.chunkTickets().release();
        Runnable action = pendingResetAction;
        pendingResetAction = null;
        if (state.phase() != RunPhase.RESETTING) {
            // Something else (a technical failure) took over while cleaning; leave it to the admin.
            persistState();
            notifyOperationListener();
            return;
        }
        state.finishReset(System.currentTimeMillis());
        releaseActiveDefinition();
        emptySince = 0L;
        lastPersistedMobs = -1;
        persistState();
        if (action != null) {
            action.run();
        }
        notifyOperationListener();
    }

    /**
     * A cleanup step failed (e.g. the stored block states could not be read). The fortress is not clean
     * yet: the run stays in RESETTING with its stored cooldown, the location stays pinned and its chunk
     * tickets stay held, and the cleanup is attempted again - by the next engine tick or, when no tick
     * runs, by a scheduled retry. No admin command is needed.
     */
    private void abortResetForRetry(Object token) {
        if (token != resetToken || !resetInProgress) {
            return;
        }
        resetToken = null;
        resetInProgress = false;
        scheduleResetRetry();
    }

    private void scheduleResetRetry() {
        if (resetRetryTask != null) {
            return;
        }
        resetRetryTask = Bukkit.getScheduler().runTaskLater(ctx.plugin(), () -> {
            resetRetryTask = null;
            if (resetInProgress || state.phase() != RunPhase.RESETTING) {
                return;
            }
            if (!ctx.persistence().healthy()) {
                // The reset intent must be durable before the world is touched again.
                ctx.persistence().retryPendingWrites();
                scheduleResetRetry();
                return;
            }
            resumeInterruptedReset();
        }, RESET_RETRY_DELAY_TICKS);
    }

    private void cancelResetRetry() {
        if (resetRetryTask != null) {
            resetRetryTask.cancel();
            resetRetryTask = null;
        }
    }

    private void removeEncounterEntities() {
        String runId = state.runId().orElse(null);
        if (runId == null) {
            return;
        }
        state.bossEntityId().ifPresent(bossId -> {
            if (ctx.bossAdapter() != null) {
                ctx.bossAdapter().stop(bossId);
            }
            Entity entity = Bukkit.getEntity(bossId);
            if (entity != null) {
                entity.remove();
            }
        });
        for (UUID id : List.copyOf(liveMobs.keySet())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        liveMobs.clear();
        // Anything still tagged with this run (e.g. spawned before a crash) is swept too. Without a
        // known geometry every world is searched: the run tag alone identifies the entities.
        Optional<OutpostDefinition> outpost = activeOutpost();
        List<World> worlds = outpost.isPresent()
                ? java.util.Optional.ofNullable(Bukkit.getWorld(outpost.get().world())).map(List::of).orElse(List.of())
                : Bukkit.getWorlds();
        for (World world : worlds) {
            for (Entity entity : world.getEntities()) {
                if (ctx.tags().belongsTo(entity, runId)) {
                    entity.remove();
                }
            }
        }
    }

    /**
     * Removes the encounter's own leftovers.
     *
     * <p>Ground items are deliberately left alone unless explicitly configured: a player death drop
     * or an unrelated item inside the region must not be mistaken for event residue.
     */
    private void removeTemporaryEntities(OutpostDefinition outpost, boolean removeGroundItems) {
        World world = Bukkit.getWorld(outpost.world());
        if (world == null) {
            return;
        }
        for (Entity entity : world.getEntities()) {
            Location location = entity.getLocation();
            if (!outpost.region().contains(outpost.world(), location.getBlockX(), location.getBlockY(),
                    location.getBlockZ())) {
                continue;
            }
            if (ctx.tags().isTagged(entity)) {
                entity.remove();
                continue;
            }
            if (entity instanceof org.bukkit.entity.Projectile
                    || entity instanceof org.bukkit.entity.TNTPrimed) {
                entity.remove();
                continue;
            }
            if (removeGroundItems && entity instanceof org.bukkit.entity.Item) {
                entity.remove();
            }
        }
    }

    // ---------------------------------------------------------------- loot access

    private void fillLoot(OutpostDefinition outpost) {
        LootConfig lootConfig = ctx.config().get().loot();
        if (!lootConfig.enabled()) {
            return;
        }
        String runId = state.runId().orElse(null);
        if (runId == null) {
            return;
        }
        for (String containerName : outpost.lootContainers().keySet()) {
            String containerId = LootService.containerId(outpost.id(), containerName);
            pendingLootFills.putIfAbsent(containerId, new PendingLootFill(runId, outpost, containerName, containerId));
        }
        attemptPendingLootFills();
    }

    /**
     * Registers the fills a restarted run is still owed: the run already passed its configured fill
     * point, but the database holds no claim for these containers (the claim failed or never ran).
     * Attempted by the next running tick, so a blocked event operation does not hand out loot.
     *
     * @param claimedContainers container ids the database already records as filled for this run
     */
    public void resumeDueLootFills(Collection<String> claimedContainers) {
        LootConfig lootConfig = ctx.config().get().loot();
        String runId = state.runId().orElse(null);
        Optional<OutpostDefinition> outpost = activeOutpost();
        if (!lootConfig.enabled() || runId == null || outpost.isEmpty()
                || !lootFillDue(lootConfig.fillPhase(), state.phase())) {
            return;
        }
        for (String containerName : outpost.get().lootContainers().keySet()) {
            String containerId = LootService.containerId(outpost.get().id(), containerName);
            if (!claimedContainers.contains(containerId)) {
                pendingLootFills.putIfAbsent(containerId,
                        new PendingLootFill(runId, outpost.get(), containerName, containerId));
            }
        }
    }

    /** True once a run in {@code phase} has passed the point at which its containers are filled. */
    private static boolean lootFillDue(LootConfig.FillPhase fillPhase, RunPhase phase) {
        return switch (fillPhase) {
            case RUN_START -> phase == RunPhase.PREPARATION || phase == RunPhase.WAVES || phase == RunPhase.BOSS
                    || phase == RunPhase.COMPLETED || phase == RunPhase.LOOTING;
            case WAVES_CLEARED -> phase == RunPhase.BOSS || phase == RunPhase.COMPLETED || phase == RunPhase.LOOTING;
            case COMPLETED -> phase == RunPhase.COMPLETED || phase == RunPhase.LOOTING;
        };
    }

    /** Main thread. Claims every pending fill that is not already waiting for the database. */
    private void attemptPendingLootFills() {
        Iterator<PendingLootFill> pending = pendingLootFills.values().iterator();
        while (pending.hasNext()) {
            PendingLootFill fill = pending.next();
            if (!lootFillStillApplies(fill)) {
                pending.remove();
                continue;
            }
            if (fill.inFlight) {
                continue;
            }
            fill.inFlight = true;
            ctx.persistence().markContainerFilled(fill.runId, fill.containerId).whenComplete((won, error) ->
                    Bukkit.getScheduler().runTask(ctx.plugin(), () -> completeLootFill(fill, won, error)));
        }
    }

    private boolean lootFillStillApplies(PendingLootFill fill) {
        return pendingLootFills.get(fill.containerId) == fill
                && RunGuard.stillValid(fill.runId, state.runId().orElse(null), state.phase(), resetInProgress)
                && isActiveGeometry(fill.outpost);
    }

    /**
     * Main thread. Applies the database's answer to a fill claim.
     *
     * <p>The claim (a row in {@code posterunki_loot}) and the items in the world are not one transaction.
     * So the world is only filled after this caller's own claim was confirmed. A failed claim stays
     * pending. If a later attempt finds the claim already present, an earlier failed attempt may have
     * been committed after all - whether items were placed is unclear, so the container is unlocked but
     * never filled again, and the uncertainty is logged and audited.
     */
    private void completeLootFill(PendingLootFill fill, Boolean won, Throwable error) {
        fill.inFlight = false;
        if (!lootFillStillApplies(fill)) {
            // The run ended, a reset or wipe started or the location changed while the database answered.
            pendingLootFills.remove(fill.containerId, fill);
            return;
        }
        if (error != null) {
            fill.failedBefore = true;
            ctx.logger().warning("[loot] Nie udało się zarejestrować napełnienia kontenera '" + fill.containerId
                    + "': " + error.getMessage() + ". Kolejna próba nastąpi w następnym cyklu.");
            return;
        }
        pendingLootFills.remove(fill.containerId);
        if (Boolean.TRUE.equals(won)) {
            ctx.loot().fill(fill.outpost, fill.containerName, ctx.config().get().loot());
            return;
        }
        ctx.loot().markUnlocked(fill.containerId);
        if (fill.failedBefore) {
            ctx.logger().warning("[loot] Kontener '" + fill.containerId + "' jest już zapisany jako napełniony,"
                    + " choć wcześniejsza próba zakończyła się błędem. Nie wiadomo, czy przedmioty trafiły do"
                    + " skrzyni - nie napełniam jej ponownie, sprawdź ją ręcznie.");
            audit(AuditEvent.LOOT_UNCERTAIN, "system", "container=" + fill.containerId);
        }
    }

    /** One container fill of one run that the database has not confirmed yet. */
    private static final class PendingLootFill {
        private final String runId;
        private final OutpostDefinition outpost;
        private final String containerName;
        private final String containerId;
        private boolean inFlight;
        private boolean failedBefore;

        private PendingLootFill(String runId, OutpostDefinition outpost, String containerName, String containerId) {
            this.runId = runId;
            this.outpost = outpost;
            this.containerName = containerName;
            this.containerId = containerId;
        }
    }

    /**
     * True when this player may open a loot container of the active outpost.
     *
     * <p>Unlocked is not the same as public: the loot belongs to the controlling town (before the
     * win) and to the frozen winner (during the loot phase).
     */
    public boolean mayOpenLoot(Player player) {
        Optional<UUID> owner = lootOwner();
        if (owner.isEmpty()) {
            return false;
        }
        return participation.townOf(player.getUniqueId()).map(owner.get()::equals).orElse(false);
    }

    /**
     * Closes loot containers that are open in the hands of someone who may no longer use them.
     *
     * <p>Checking {@code InventoryOpenEvent} alone is not enough: a takeover, a death or a reset
     * happens while the inventory is already open.
     */
    public void enforceLootAccess() {
        Optional<OutpostDefinition> outpost = activeOutpost();
        if (outpost.isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!hasOutpostInventoryOpen(player, outpost.get())) {
                continue;
            }
            if (player.hasPermission("hexposterunki.admin.bypass")) {
                continue;
            }
            if (!mayOpenLoot(player) || !state.phase().isLive()) {
                player.closeInventory();
            }
        }
    }

    private void closeAllLootInventories() {
        activeOutpost().ifPresent(outpost -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (hasOutpostInventoryOpen(player, outpost)) {
                    player.closeInventory();
                }
            }
        });
    }

    private void closeLootInventory(Player player) {
        activeOutpost().ifPresent(outpost -> {
            if (hasOutpostInventoryOpen(player, outpost)
                    && !player.hasPermission("hexposterunki.admin.bypass")) {
                player.closeInventory();
            }
        });
    }

    private boolean hasOutpostInventoryOpen(Player player, OutpostDefinition outpost) {
        org.bukkit.inventory.Inventory top = player.getOpenInventory().getTopInventory();
        if (top == null) {
            return false;
        }
        return hexposterunki.region.InventoryLocations.blocksOf(top).stream()
                .anyMatch(location -> location.getWorld() != null
                        && outpost.region().contains(location.getWorld().getName(),
                        location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    // ---------------------------------------------------------------- displays

    private void updateDisplays() {
        PosterunkiConfig config = ctx.config().get();
        Optional<OutpostDefinition> outpost = activeOutpost();
        if (outpost.isEmpty() || !state.phase().isLive()) {
            ctx.displays().clearBossBars();
            ctx.displays().removeHologram();
            return;
        }
        String status = statusLabel();
        String town = state.controllingTownName().isBlank() ? "—" : state.controllingTownName();

        ctx.displays().updateBossBar(outpost.get(), status, config.ui());
        ctx.displays().updateHologram(outpost.get(), state.runId().orElse("-"), status, town, config.ui());

        if (!config.ui().actionbarEnabled()) {
            return;
        }
        if (state.phase() == RunPhase.LOOTING) {
            long secondsLeft = Math.max(0L, (state.lootUntilMillis() - System.currentTimeMillis()) / 1000L);
            for (Player player : participation.onlineInside()) {
                ctx.ui().actionBar(player, UiKeys.LOOTING_ACTIONBAR,
                        UiTokens.of("seconds", String.valueOf(secondsLeft)).put("town", town));
            }
            return;
        }
        List<KillEntry> entries = List.copyOf(participation.ledger().entries());
        UUID controlling = state.controllingTown().orElse(null);
        for (Player player : participation.onlineInside()) {
            int kills = participation.ledger().kills(player.getUniqueId());
            int rank = RankingCalculator.rankOf(entries, controlling, config.requiredKills(), player.getUniqueId());
            ctx.ui().actionBar(player, UiKeys.ACTIONBAR_STATUS, UiTokens.of("phase", status)
                    .put("kills", String.valueOf(kills))
                    .put("required", String.valueOf(config.requiredKills()))
                    .put("rank", rank == 0 ? "—" : String.valueOf(rank))
                    .put("town", town));
        }
    }

    /** Short Polish status used by boss bar, hologram and action bar. */
    public String statusLabel() {
        return switch (state.phase()) {
            case WAVES -> "Fala " + state.wave() + " • pozostało " + liveMobs.size();
            case BOSS -> "Boss: " + state.bossId().orElse("—");
            case LOOTING -> "Zbieranie łupów • "
                    + Math.max(0L, (state.lootUntilMillis() - System.currentTimeMillis()) / 1000L) + " s";
            default -> state.phase().polishLabel();
        };
    }

    // ---------------------------------------------------------------- persistence helpers

    /** Captures the whole consistent state on the main thread and queues one ordered write. */
    public void persistState() {
        ctx.persistence().save(captureSnapshot());
    }

    /**
     * Captures the persisted aggregate and assigns its revision.
     *
     * <p>The revision must cover everything that is written, not only the state machine. A kill
     * reset, a removed entity or a changed pointer that did not touch the state machine would
     * otherwise reuse a revision the database already holds and be discarded as stale. So a new
     * revision is claimed whenever the captured content differs from the last capture under the
     * same revision. An unchanged capture keeps its revision, which lets a retry of the same state
     * reuse it instead of burning a new one.
     */
    public RunPersistenceSnapshot captureSnapshot() {
        List<TrackedEntity> entities = new ArrayList<>(liveMobs.values());
        state.bossEntityId().ifPresent(bossId -> entities.add(
                new TrackedEntity(bossId, TrackedEntity.Kind.BOSS, state.bossId().orElse(null), 0)));
        List<KillEntry> participants = List.copyOf(participation.ledger().entries());
        PersistedContent content = new PersistedContent(state.snapshot().withRevision(0L), lastOutpostId,
                participants, List.copyOf(entities), activeDefinitionEncoded);

        CompletionRecord completion = completionForSnapshot();
        boolean attachesCompletion = completion != null && completionRevision == 0L;
        long current = state.revision();
        long committed = ctx.persistence().lastCommittedRevision();
        if (lastCapturedContent == null) {
            if (current <= committed) {
                // Nothing captured by this engine yet and still at or below what the database holds:
                // start above the durable watermark, otherwise every write would be discarded as stale.
                state.adoptRevisionFloor(committed);
                state.markChanged();
            }
        } else if (current <= lastCapturedRevision
                && (attachesCompletion || !content.equals(lastCapturedContent))) {
            state.markChanged();
        }
        RunSnapshot run = state.snapshot();
        if (attachesCompletion) {
            completionRevision = run.revision();
        }
        lastCapturedContent = content;
        lastCapturedRevision = run.revision();
        return new RunPersistenceSnapshot(run, lastOutpostId, participants, entities, completion,
                activeDefinitionEncoded);
    }

    /** The frozen completion while it is not confirmed durable; null once it is. */
    private CompletionRecord completionForSnapshot() {
        if (pendingCompletion == null) {
            return null;
        }
        if (completionRevision > 0L && ctx.persistence().lastCommittedRevision() >= completionRevision) {
            return null;
        }
        return pendingCompletion;
    }

    /** Frozen, completion-independent view of what a snapshot writes. */
    private record PersistedContent(RunSnapshot run, String lastOutpostId, List<KillEntry> participants,
                                    List<TrackedEntity> entities, String outpostDefinition) {
    }

    private void maybeSnapshot(long now) {
        long interval = ctx.config().get().timing().snapshotIntervalSeconds() * 1000L;
        if (now - lastSnapshotAt < interval) {
            return;
        }
        lastSnapshotAt = now;
        persistState();
    }

    public void audit(String event, String actor, String data) {
        ctx.persistence().audit(state.runId().orElse(null), state.outpostId().orElse(null), event, actor, data);
    }

    public RunSnapshot snapshot() {
        return state.snapshot();
    }
}
