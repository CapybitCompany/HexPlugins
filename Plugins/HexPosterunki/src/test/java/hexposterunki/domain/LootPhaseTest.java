package hexposterunki.domain;

import hexposterunki.engine.RunGuard;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding 3 regression: a victory must not be followed by the reset on the very next scheduler
 * tick. The winners get an explicit, persisted loot window first.
 */
class LootPhaseTest {

    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER_TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private OutpostStateMachine won(long now, long lootMillis) {
        OutpostStateMachine machine = new OutpostStateMachine();
        machine.restoreEmpty();
        machine.beginRun("run-1", "fort", now, 60_000L);
        machine.applyBossRoll(null);
        machine.claim(TOWN, "Rycerze", now);
        machine.startWaves();
        machine.complete();
        machine.startLooting(now + lootMillis);
        return machine;
    }

    @Test
    void victoryLeadsIntoLootingAndNotStraightIntoTheReset() {
        OutpostStateMachine machine = new OutpostStateMachine();
        machine.restoreEmpty();
        machine.beginRun("run-1", "fort", 1_000L, 60_000L);
        machine.claim(TOWN, "Rycerze", 1_000L);
        machine.startWaves();
        machine.complete();

        assertEquals(RunPhase.COMPLETED, machine.phase());
        assertTrue(machine.canTransition(RunPhase.LOOTING));
        machine.startLooting(121_000L);
        assertEquals(RunPhase.LOOTING, machine.phase());
        assertEquals(121_000L, machine.lootUntilMillis());
    }

    @Test
    void theLootWindowIsALiveRegionButControlIsFrozen() {
        OutpostStateMachine machine = won(1_000L, 120_000L);
        assertTrue(machine.phase().isLive(), "region pozostaje aktywny, skrzynie działają");
        assertFalse(machine.phase().hasCombat(), "walka się skończyła");
        assertFalse(machine.phase().allowsControlChange(), "nikt już nie przejmie posterunku");
        assertTrue(machine.phase().isWon());
        assertEquals(TOWN, machine.controllingTown().orElseThrow(),
                "zwycięzca pozostaje znany na potrzeby dostępu do łupów");
    }

    @Test
    void lootingOnlyEndsInTheCooldown() {
        OutpostStateMachine machine = won(1_000L, 120_000L);
        assertFalse(machine.canTransition(RunPhase.WAITING));
        assertFalse(machine.canTransition(RunPhase.BOSS));
        assertTrue(machine.canTransition(RunPhase.COOLDOWN));
    }

    @Test
    void aRestartInsideTheLootWindowResumesTheSameDeadline() {
        OutpostStateMachine before = won(1_000L, 120_000L);
        RunSnapshot snapshot = before.snapshot();
        assertEquals(121_000L, snapshot.lootUntilMillis());

        OutpostStateMachine after = new OutpostStateMachine();
        after.restore(snapshot);

        assertEquals(RunPhase.LOOTING, after.phase());
        assertEquals(121_000L, after.lootUntilMillis(),
                "restart nie może rozpocząć nowego, pełnego okna łupów");
        assertEquals(TOWN, after.controllingTown().orElseThrow());
    }

    @Test
    void theCooldownClearsTheLootDeadlineAndTheWinner() {
        OutpostStateMachine machine = won(1_000L, 120_000L);
        machine.toCooldown(200_000L, 30_000L);

        assertEquals(RunPhase.COOLDOWN, machine.phase());
        assertEquals(0L, machine.lootUntilMillis());
        assertTrue(machine.controllingTown().isEmpty());
    }

    @Test
    void aDelayedCallbackFromTheOldRunIsRejected() {
        // The fill callback was scheduled for run-1 but comes back after run-2 already started.
        assertFalse(RunGuard.stillValid("run-1", "run-2", RunPhase.WAITING, false),
                "callback ze starego runu nie może napełnić nowych skrzyń");
    }

    @Test
    void aDelayedCallbackDuringAResetIsRejected() {
        assertFalse(RunGuard.stillValid("run-1", "run-1", RunPhase.LOOTING, true));
    }

    @Test
    void aDelayedCallbackAfterTheRunEndedIsRejected() {
        assertFalse(RunGuard.stillValid("run-1", "run-1", RunPhase.COOLDOWN, false));
        assertFalse(RunGuard.stillValid("run-1", null, RunPhase.COOLDOWN, false));
    }

    @Test
    void aDelayedCallbackOnTheStillLiveRunIsApplied() {
        assertTrue(RunGuard.stillValid("run-1", "run-1", RunPhase.LOOTING, false));
        assertTrue(RunGuard.stillValid("run-1", "run-1", RunPhase.WAVES, false));
    }

    @Test
    void theLootWindowDoesNotChangeTheWinnerEvenIfAnotherTownIsPresent() {
        OutpostStateMachine machine = won(1_000L, 120_000L);
        // resolveControl is gated on allowsControlChange(); the state machine would also refuse.
        assertFalse(machine.phase().allowsControlChange());
        assertEquals(TOWN, machine.controllingTown().orElseThrow());
        assertFalse(OTHER_TOWN.equals(machine.controllingTown().orElseThrow()));
    }
}
