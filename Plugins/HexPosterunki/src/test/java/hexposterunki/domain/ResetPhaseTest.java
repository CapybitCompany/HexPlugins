package hexposterunki.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review findings 3 and 6, pure state-machine part: the persisted reset phase leads nowhere but into
 * the cooldown, and the helpers used by revision coverage and the controlled resume behave as stated.
 */
class ResetPhaseTest {

    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private OutpostStateMachine inWaves() {
        OutpostStateMachine machine = new OutpostStateMachine();
        machine.restoreEmpty();
        machine.beginRun("run-1", "fort", 1_000L, 60_000L);
        machine.applyBossRoll("wladca_burzy");
        machine.claim(TOWN, "Rycerze", 1_000L);
        machine.startWaves();
        return machine;
    }

    @Test
    void theResetPhaseCarriesNoFightAndNoVictory() {
        assertFalse(RunPhase.RESETTING.isLive());
        assertFalse(RunPhase.RESETTING.hasCombat());
        assertFalse(RunPhase.RESETTING.allowsControlChange());
        assertFalse(RunPhase.RESETTING.isWon());
    }

    @Test
    void everyPhaseThatHoldsALocationCanBeResetButTheResetOnlyEndsInTheCooldown() {
        for (RunPhase phase : new RunPhase[]{RunPhase.WAITING, RunPhase.PREPARATION, RunPhase.WAVES,
                RunPhase.BOSS, RunPhase.COMPLETED, RunPhase.LOOTING, RunPhase.FAILED, RunPhase.COOLDOWN}) {
            assertTrue(PhaseTransition.allowed(phase, RunPhase.RESETTING), phase + " -> RESETTING");
        }
        for (RunPhase target : RunPhase.values()) {
            boolean expected = target == RunPhase.COOLDOWN || target == RunPhase.FAILED;
            assertEquals(expected, PhaseTransition.allowed(RunPhase.RESETTING, target), "RESETTING -> " + target);
        }
    }

    @Test
    void theStoredCooldownIsAppliedWhenTheResetFinishesAndSurvivesARestore() {
        OutpostStateMachine machine = inWaves();
        machine.toBoss();
        machine.bossSpawned(UUID.randomUUID());
        machine.beginReset(45_000L);

        assertEquals(RunPhase.RESETTING, machine.phase());
        assertTrue(machine.bossEntityId().isEmpty(), "usunięty boss nie jest już śledzony");

        OutpostStateMachine restored = new OutpostStateMachine();
        restored.restore(machine.snapshot());
        assertEquals(RunPhase.RESETTING, restored.phase());
        assertEquals(45_000L, restored.resetCooldownMillis());

        restored.finishReset(10_000L);
        assertEquals(RunPhase.COOLDOWN, restored.phase());
        assertEquals(55_000L, restored.cooldownUntilMillis());
        assertEquals(0L, restored.resetCooldownMillis());
    }

    @Test
    void markChangedAndShiftTimersClaimANewRevision() {
        OutpostStateMachine machine = inWaves();
        machine.setNextPhaseAt(20_000L);
        long revision = machine.revision();

        machine.markChanged();
        assertTrue(machine.revision() > revision);

        long before = machine.revision();
        long windowBefore = machine.windowExpiresAtMillis();
        machine.shiftTimers(3_000L);
        assertEquals(23_000L, machine.nextPhaseAtMillis());
        assertEquals(windowBefore + 3_000L, machine.windowExpiresAtMillis());
        assertEquals(0L, machine.lootUntilMillis(), "nieuruchomiony timer pozostaje wyłączony");
        assertTrue(machine.revision() > before);
    }
}
