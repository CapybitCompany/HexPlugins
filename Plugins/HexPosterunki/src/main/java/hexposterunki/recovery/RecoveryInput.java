package hexposterunki.recovery;

import hexposterunki.domain.BossStatus;

import java.util.List;
import java.util.UUID;

/**
 * Everything the planner needs. Pure data, so the whole recovery decision is unit-testable.
 *
 * @param combatPhase        true while the persisted phase may legitimately hold mobs/boss
 * @param expectedAliveMobs  persisted number of live mobs of the current wave
 * @param found              entities discovered in the (ticket-loaded) outpost chunks
 * @param maxBossSpawns      total allowed boss spawn attempts per run; the initial spawn counts,
 *                           so the default of 2 means recovery may recreate a missing boss once
 */
public record RecoveryInput(
        String runId,
        int currentWave,
        boolean combatPhase,
        int expectedAliveMobs,
        List<FoundEntity> found,
        boolean bossPhase,
        String bossId,
        BossStatus bossStatus,
        UUID storedBossEntityId,
        int bossSpawnAttempts,
        int maxBossSpawns
) {

    public RecoveryInput {
        found = found == null ? List.of() : List.copyOf(found);
        expectedAliveMobs = Math.max(0, expectedAliveMobs);
        maxBossSpawns = Math.max(1, maxBossSpawns);
    }
}
