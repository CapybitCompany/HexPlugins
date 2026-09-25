package hexposterunki.domain;

import java.util.UUID;

/**
 * Immutable view of the global outpost state. This is exactly what gets persisted and what
 * recovery reads back, so it must stay free of Bukkit types.
 *
 * @param nextPhaseAtMillis   deadline of the running phase timer (preparation end, wave delay);
 *                            0 when no timer is pending. Persisted so a restart resumes it.
 * @param lootUntilMillis     end of the loot window; 0 outside {@link RunPhase#LOOTING}
 * @param failureReason       Polish diagnosis while the run sits in {@link RunPhase#FAILED}
 * @param revision            monotonically increasing counter. Writers compare it so a slow write
 *                            can never overwrite a newer state. It covers the whole persisted
 *                            aggregate (participants and tracked entities included), not only
 *                            the fields of this record.
 * @param resetCooldownMillis cooldown that follows an unfinished {@link RunPhase#RESETTING}; kept
 *                            so a restart during the cleanup ends in the cooldown that was chosen
 */
public record RunSnapshot(
        String runId,
        String outpostId,
        RunPhase phase,
        int wave,
        UUID controllingTown,
        String controllingTownName,
        String bossId,
        boolean bossRolled,
        BossStatus bossStatus,
        UUID bossEntityId,
        int bossSpawnAttempts,
        int mobsRemaining,
        long startedAtMillis,
        long windowExpiresAtMillis,
        long cooldownUntilMillis,
        long nextPhaseAtMillis,
        long lootUntilMillis,
        String failureReason,
        long revision,
        long resetCooldownMillis
) {

    /** Snapshot without a pending reset cooldown. */
    public RunSnapshot(String runId, String outpostId, RunPhase phase, int wave, UUID controllingTown,
                       String controllingTownName, String bossId, boolean bossRolled, BossStatus bossStatus,
                       UUID bossEntityId, int bossSpawnAttempts, int mobsRemaining, long startedAtMillis,
                       long windowExpiresAtMillis, long cooldownUntilMillis, long nextPhaseAtMillis,
                       long lootUntilMillis, String failureReason, long revision) {
        this(runId, outpostId, phase, wave, controllingTown, controllingTownName, bossId, bossRolled,
                bossStatus, bossEntityId, bossSpawnAttempts, mobsRemaining, startedAtMillis,
                windowExpiresAtMillis, cooldownUntilMillis, nextPhaseAtMillis, lootUntilMillis,
                failureReason, revision, 0L);
    }

    public boolean hasRun() {
        return runId != null && outpostId != null;
    }

    public boolean hasBoss() {
        return bossRolled && bossId != null && bossStatus != BossStatus.NONE;
    }

    /** Same content under another revision; used to compare persisted content independent of it. */
    public RunSnapshot withRevision(long value) {
        return new RunSnapshot(runId, outpostId, phase, wave, controllingTown, controllingTownName, bossId,
                bossRolled, bossStatus, bossEntityId, bossSpawnAttempts, mobsRemaining, startedAtMillis,
                windowExpiresAtMillis, cooldownUntilMillis, nextPhaseAtMillis, lootUntilMillis,
                failureReason, value, resetCooldownMillis);
    }
}
