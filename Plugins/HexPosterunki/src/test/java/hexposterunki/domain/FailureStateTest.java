package hexposterunki.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Findings 7 and 8 regression: a technical problem must never look like a win, and re-binding an
 * existing boss must never consume the respawn budget.
 */
class FailureStateTest {

    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private OutpostStateMachine inBossPhase() {
        OutpostStateMachine machine = new OutpostStateMachine();
        machine.restoreEmpty();
        machine.beginRun("run-1", "fort", 1_000L, 60_000L);
        machine.applyBossRoll("wladca_burzy");
        machine.claim(TOWN, "Rycerze", 1_000L);
        machine.startWaves();
        machine.toBoss();
        return machine;
    }

    @Test
    void everyCombatPhaseCanFailButFailingIsNotWinning() {
        for (RunPhase phase : new RunPhase[]{RunPhase.WAITING, RunPhase.PREPARATION,
                RunPhase.WAVES, RunPhase.BOSS}) {
            assertTrue(PhaseTransition.allowed(phase, RunPhase.FAILED), phase + " musi móc przejść w FAILED");
        }
        assertFalse(RunPhase.FAILED.isWon());
        assertFalse(RunPhase.FAILED.isLive());
        assertFalse(RunPhase.FAILED.hasCombat());
    }

    @Test
    void failedOnlyLeavesThroughAnExplicitAdminReset() {
        OutpostStateMachine machine = inBossPhase();
        machine.fail("spawn bossa odrzucony");

        assertEquals(RunPhase.FAILED, machine.phase());
        assertEquals("spawn bossa odrzucony", machine.failureReason());
        assertFalse(machine.canTransition(RunPhase.COMPLETED), "błąd techniczny nie może dać zwycięstwa");
        assertFalse(machine.canTransition(RunPhase.LOOTING));
        assertTrue(machine.canTransition(RunPhase.COOLDOWN));
    }

    @Test
    void theFailureReasonIsClearedByTheCooldown() {
        OutpostStateMachine machine = inBossPhase();
        machine.fail("boss zniknął");
        machine.toCooldown(5_000L, 0L);
        assertEquals("", machine.failureReason());
    }

    @Test
    void reattachingAnExistingBossConsumesNoRespawnBudget() {
        OutpostStateMachine machine = inBossPhase();
        UUID bossEntity = UUID.randomUUID();
        machine.bossSpawned(bossEntity);
        assertEquals(1, machine.bossSpawnAttempts());

        // Five restarts, each one re-binding the very same living boss.
        for (int restart = 0; restart < 5; restart++) {
            OutpostStateMachine restored = new OutpostStateMachine();
            restored.restore(machine.snapshot());
            restored.bossReattached(bossEntity);
            machine = restored;
        }

        assertEquals(1, machine.bossSpawnAttempts(),
                "ponowne podpięcie tego samego bossa nie zużywa budżetu prób");
        assertEquals(BossStatus.ALIVE, machine.bossStatus());
        assertEquals(bossEntity, machine.bossEntityId().orElseThrow());
    }

    @Test
    void aBossThatLaterReallyDisappearsCanStillBeRecreatedOnce() {
        OutpostStateMachine machine = inBossPhase();
        UUID bossEntity = UUID.randomUUID();
        machine.bossSpawned(bossEntity);
        for (int restart = 0; restart < 3; restart++) {
            machine.bossReattached(bossEntity);
        }
        assertEquals(1, machine.bossSpawnAttempts());

        machine.bossMissing();
        assertEquals(BossStatus.MISSING, machine.bossStatus());
        assertTrue(machine.bossStatus().wantsEntity(), "zaginiony boss wciąż podlega odtworzeniu");
        assertTrue(machine.bossSpawnAttempts() < 2, "budżet na jedno odtworzenie pozostał");

        machine.bossSpawned(UUID.randomUUID());
        assertEquals(2, machine.bossSpawnAttempts());
    }

    @Test
    void aVanishedBossIsNotTheSameAsAConfirmedDeath() {
        OutpostStateMachine machine = inBossPhase();
        machine.bossSpawned(UUID.randomUUID());

        machine.bossMissing();
        assertEquals(BossStatus.MISSING, machine.bossStatus());
        assertTrue(machine.bossStatus().wantsEntity());

        machine.bossDied();
        assertEquals(BossStatus.DEAD, machine.bossStatus());
        assertFalse(machine.bossStatus().wantsEntity(), "potwierdzona śmierć jest ostateczna");
    }

    @Test
    void aFailedSpawnAttemptStillCountsTowardsTheBudget() {
        OutpostStateMachine machine = inBossPhase();
        machine.bossSpawnFailed();
        machine.bossSpawnFailed();
        assertEquals(2, machine.bossSpawnAttempts());
    }
}
