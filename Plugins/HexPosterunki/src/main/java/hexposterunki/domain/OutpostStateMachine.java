package hexposterunki.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The authoritative, Bukkit-free state machine of the single server-wide outpost encounter.
 *
 * <p>Every mutation goes through {@link #transition(RunPhase)} or a named helper and bumps
 * {@link #revision()}. The revision also orders the rest of the persisted aggregate (kill ledger,
 * tracked entities), so the engine calls {@link #markChanged()} when only those changed. The engine
 * owns exactly one instance and only touches it on the main thread; persistence consumes
 * {@link #snapshot()}.
 */
public final class OutpostStateMachine {

    private RunPhase phase = RunPhase.RECOVERING;
    private String runId;
    private String outpostId;
    private int wave;
    private UUID controllingTown;
    private String controllingTownName;
    private String bossId;
    private boolean bossRolled;
    private BossStatus bossStatus = BossStatus.NONE;
    private UUID bossEntityId;
    private int bossSpawnAttempts;
    private int mobsRemaining;
    private long startedAtMillis;
    private long windowExpiresAtMillis;
    private long cooldownUntilMillis;
    private long nextPhaseAtMillis;
    private long lootUntilMillis;
    private String failureReason = "";
    private long resetCooldownMillis;
    private long revision;

    public RunPhase phase() {
        return phase;
    }

    public Optional<String> runId() {
        return Optional.ofNullable(runId);
    }

    public Optional<String> outpostId() {
        return Optional.ofNullable(outpostId);
    }

    public int wave() {
        return wave;
    }

    public Optional<UUID> controllingTown() {
        return Optional.ofNullable(controllingTown);
    }

    public String controllingTownName() {
        return controllingTownName == null ? "" : controllingTownName;
    }

    public Optional<String> bossId() {
        return Optional.ofNullable(bossId);
    }

    public boolean bossRolled() {
        return bossRolled;
    }

    public BossStatus bossStatus() {
        return bossStatus;
    }

    public Optional<UUID> bossEntityId() {
        return Optional.ofNullable(bossEntityId);
    }

    public int bossSpawnAttempts() {
        return bossSpawnAttempts;
    }

    public int mobsRemaining() {
        return mobsRemaining;
    }

    public long startedAtMillis() {
        return startedAtMillis;
    }

    public long windowExpiresAtMillis() {
        return windowExpiresAtMillis;
    }

    public long cooldownUntilMillis() {
        return cooldownUntilMillis;
    }

    public long nextPhaseAtMillis() {
        return nextPhaseAtMillis;
    }

    public long lootUntilMillis() {
        return lootUntilMillis;
    }

    public String failureReason() {
        return failureReason == null ? "" : failureReason;
    }

    public long revision() {
        return revision;
    }

    /** Cooldown that follows the running {@link RunPhase#RESETTING}. */
    public long resetCooldownMillis() {
        return resetCooldownMillis;
    }

    /**
     * Claims a new revision for a change of the persisted aggregate that lives outside this
     * machine - kill standings or tracked entities. Without it such a change would be written under
     * an already stored revision and discarded as stale.
     */
    public void markChanged() {
        revision++;
    }

    /**
     * Raises the revision to at least {@code persistedRevision} without changing any content.
     *
     * <p>Startup recovery calls this with the highest revision found in the database - also when the
     * stored run is discarded. A fresh machine otherwise starts near zero, below the database
     * watermark, and every new state would be rejected as stale. The next mutation then lands above
     * the persisted revision. The revision is never lowered.
     */
    public void adoptRevisionFloor(long persistedRevision) {
        if (persistedRevision > revision) {
            revision = persistedRevision;
        }
    }

    // ---------------------------------------------------------------- transitions

    /**
     * Applies a phase move after validating it against {@link PhaseTransition}.
     *
     * @throws IllegalStateException when the move is not part of the declared lifecycle.
     */
    public void transition(RunPhase target) {
        Objects.requireNonNull(target, "target");
        if (!PhaseTransition.allowed(phase, target)) {
            throw new IllegalStateException("Illegal outpost phase transition: " + phase + " -> " + target);
        }
        phase = target;
        revision++;
    }

    public boolean canTransition(RunPhase target) {
        return PhaseTransition.allowed(phase, target);
    }

    /** Announces a freshly picked location. Valid from COOLDOWN and from startup RECOVERING. */
    public void beginRun(String newRunId, String newOutpostId, long nowMillis, long activeWindowMillis) {
        this.runId = Objects.requireNonNull(newRunId, "runId");
        this.outpostId = Objects.requireNonNull(newOutpostId, "outpostId");
        this.wave = 1;
        this.controllingTown = null;
        this.controllingTownName = null;
        this.bossId = null;
        this.bossRolled = false;
        this.bossStatus = BossStatus.NONE;
        this.bossEntityId = null;
        this.bossSpawnAttempts = 0;
        this.mobsRemaining = 0;
        this.startedAtMillis = nowMillis;
        this.windowExpiresAtMillis = activeWindowMillis <= 0L ? 0L : nowMillis + activeWindowMillis;
        this.cooldownUntilMillis = 0L;
        this.nextPhaseAtMillis = 0L;
        this.lootUntilMillis = 0L;
        this.failureReason = "";
        transition(RunPhase.WAITING);
    }

    /** First town member entering the region takes control and arms the encounter. */
    public void claim(UUID townId, String townName, long preparationEndsAtMillis) {
        Objects.requireNonNull(townId, "townId");
        this.controllingTown = townId;
        this.controllingTownName = townName == null ? "" : townName;
        this.nextPhaseAtMillis = preparationEndsAtMillis;
        transition(RunPhase.PREPARATION);
    }

    /**
     * Hands control to another town without touching wave, mobs or boss state, exactly as a
     * direct takeover must behave.
     */
    public void takeover(UUID townId, String townName) {
        Objects.requireNonNull(townId, "townId");
        this.controllingTown = townId;
        this.controllingTownName = townName == null ? "" : townName;
        revision++;
    }

    public void startWaves() {
        this.nextPhaseAtMillis = 0L;
        transition(RunPhase.WAVES);
    }

    public void setWave(int value) {
        this.wave = Math.max(1, value);
        revision++;
    }

    public void setMobsRemaining(int value) {
        this.mobsRemaining = Math.max(0, value);
        revision++;
    }

    /** Deadline of the currently pending phase timer; 0 clears it. */
    public void setNextPhaseAt(long value) {
        this.nextPhaseAtMillis = Math.max(0L, value);
        revision++;
    }

    /** Stores the single per-run boss roll. {@code null} means "this run has no boss". */
    public void applyBossRoll(String rolledBossId) {
        this.bossRolled = true;
        this.bossId = rolledBossId;
        this.bossStatus = rolledBossId == null ? BossStatus.NONE : BossStatus.PENDING;
        revision++;
    }

    /** A new boss entity was actually created; this consumes one spawn attempt. */
    public void bossSpawned(UUID entityId) {
        this.bossEntityId = Objects.requireNonNull(entityId, "entityId");
        this.bossStatus = BossStatus.ALIVE;
        this.bossSpawnAttempts++;
        revision++;
    }

    /**
     * Re-binds an already existing boss entity found during recovery.
     *
     * <p>Deliberately separate from {@link #bossSpawned(UUID)}: nothing was created, so the
     * respawn budget must stay untouched no matter how often the server restarts.
     */
    public void bossReattached(UUID entityId) {
        this.bossEntityId = Objects.requireNonNull(entityId, "entityId");
        this.bossStatus = BossStatus.ALIVE;
        revision++;
    }

    /** Counts a spawn attempt that produced no entity, so recovery never retries forever. */
    public void bossSpawnFailed() {
        this.bossSpawnAttempts++;
        revision++;
    }

    public void bossDied() {
        this.bossStatus = BossStatus.DEAD;
        this.bossEntityId = null;
        revision++;
    }

    /** The boss entity disappeared without a death event - not a victory. */
    public void bossMissing() {
        this.bossStatus = BossStatus.MISSING;
        this.bossEntityId = null;
        revision++;
    }

    public void toBoss() {
        this.nextPhaseAtMillis = 0L;
        transition(RunPhase.BOSS);
    }

    public void complete() {
        this.nextPhaseAtMillis = 0L;
        transition(RunPhase.COMPLETED);
    }

    /** Opens the loot window for the (now frozen) winning town. */
    public void startLooting(long lootUntil) {
        this.lootUntilMillis = Math.max(0L, lootUntil);
        transition(RunPhase.LOOTING);
    }

    public void toCooldown(long nowMillis, long cooldownMillis) {
        this.cooldownUntilMillis = nowMillis + Math.max(0L, cooldownMillis);
        this.controllingTown = null;
        this.controllingTownName = null;
        this.mobsRemaining = 0;
        this.bossEntityId = null;
        this.nextPhaseAtMillis = 0L;
        this.lootUntilMillis = 0L;
        this.failureReason = "";
        this.resetCooldownMillis = 0L;
        transition(RunPhase.COOLDOWN);
    }

    /**
     * Enters the persisted cleanup phase. Timers stop, the encounter is no longer a fight, and the
     * cooldown that follows is stored so a restart can finish the very same reset.
     */
    public void beginReset(long cooldownMillis) {
        this.resetCooldownMillis = Math.max(0L, cooldownMillis);
        this.mobsRemaining = 0;
        // The reset removes the boss entity; a stored id would make it look tracked after a restart.
        this.bossEntityId = null;
        this.nextPhaseAtMillis = 0L;
        this.lootUntilMillis = 0L;
        transition(RunPhase.RESETTING);
    }

    /** The cleanup finished: the stored cooldown starts now. */
    public void finishReset(long nowMillis) {
        if (phase != RunPhase.RESETTING) {
            throw new IllegalStateException("finishReset() is only valid while RESETTING, current=" + phase);
        }
        toCooldown(nowMillis, resetCooldownMillis);
    }

    /**
     * Moves every running deadline of a live run by {@code deltaMillis}. Used when the engine resumes
     * after a persistence pause, so a paused preparation, wave delay, loot window or activity window
     * does not silently elapse while nobody could act.
     */
    public void shiftTimers(long deltaMillis) {
        if (deltaMillis <= 0L) {
            return;
        }
        if (nextPhaseAtMillis > 0L) {
            nextPhaseAtMillis += deltaMillis;
        }
        if (windowExpiresAtMillis > 0L) {
            windowExpiresAtMillis += deltaMillis;
        }
        if (lootUntilMillis > 0L) {
            lootUntilMillis += deltaMillis;
        }
        revision++;
    }

    /**
     * Enters the technical failure state. Used for rejected spawns, an unusable boss engine and
     * an exhausted boss respawn budget - never a victory path.
     */
    public void fail(String reason) {
        this.failureReason = reason == null ? "" : reason;
        this.nextPhaseAtMillis = 0L;
        transition(RunPhase.FAILED);
    }

    /**
     * Grace-period wipe: the region stayed empty of eligible players. The encounter falls back
     * to wave 1 on the same location and loses its controlling town, but the boss roll of this
     * run is intentionally kept so wiping cannot be used to farm fresh rolls.
     */
    public void wipeToWaveOne() {
        this.wave = 1;
        this.controllingTown = null;
        this.controllingTownName = null;
        this.mobsRemaining = 0;
        this.bossEntityId = null;
        this.nextPhaseAtMillis = 0L;
        if (bossRolled && bossId != null) {
            this.bossStatus = BossStatus.PENDING;
        }
        this.bossSpawnAttempts = 0;
        transition(RunPhase.WAITING);
    }

    // ---------------------------------------------------------------- persistence

    public RunSnapshot snapshot() {
        return new RunSnapshot(runId, outpostId, phase, wave, controllingTown, controllingTownName,
                bossId, bossRolled, bossStatus, bossEntityId, bossSpawnAttempts, mobsRemaining,
                startedAtMillis, windowExpiresAtMillis, cooldownUntilMillis, nextPhaseAtMillis,
                lootUntilMillis, failureReason, revision, resetCooldownMillis);
    }

    /**
     * Restores persisted state during startup recovery. Only legal out of {@link RunPhase#RECOVERING}
     * so a live engine can never be silently rewound.
     */
    public void restore(RunSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (phase != RunPhase.RECOVERING) {
            throw new IllegalStateException("restore() is only valid while RECOVERING, current=" + phase);
        }
        this.runId = snapshot.runId();
        this.outpostId = snapshot.outpostId();
        this.wave = Math.max(1, snapshot.wave());
        this.controllingTown = snapshot.controllingTown();
        this.controllingTownName = snapshot.controllingTownName();
        this.bossId = snapshot.bossId();
        this.bossRolled = snapshot.bossRolled();
        this.bossStatus = snapshot.bossStatus() == null ? BossStatus.NONE : snapshot.bossStatus();
        this.bossEntityId = snapshot.bossEntityId();
        this.bossSpawnAttempts = Math.max(0, snapshot.bossSpawnAttempts());
        this.mobsRemaining = Math.max(0, snapshot.mobsRemaining());
        this.startedAtMillis = snapshot.startedAtMillis();
        this.windowExpiresAtMillis = snapshot.windowExpiresAtMillis();
        this.cooldownUntilMillis = snapshot.cooldownUntilMillis();
        this.nextPhaseAtMillis = snapshot.nextPhaseAtMillis();
        this.lootUntilMillis = snapshot.lootUntilMillis();
        this.failureReason = snapshot.failureReason();
        this.resetCooldownMillis = Math.max(0L, snapshot.resetCooldownMillis());
        this.revision = Math.max(this.revision, snapshot.revision());
        transition(snapshot.phase());
    }

    /** Used when no persisted state exists at all. */
    public void restoreEmpty() {
        if (phase != RunPhase.RECOVERING) {
            throw new IllegalStateException("restoreEmpty() is only valid while RECOVERING, current=" + phase);
        }
        transition(RunPhase.COOLDOWN);
    }
}
