package hexposterunki.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutpostStateMachineTest {

    private static final long NOW = 1_000_000L;

    private OutpostStateMachine started() {
        OutpostStateMachine machine = new OutpostStateMachine();
        machine.restoreEmpty();
        machine.beginRun("run-1", "fort_polnocny", NOW, 60_000L);
        return machine;
    }

    @Test
    void newMachineStartsInRecovering() {
        assertEquals(RunPhase.RECOVERING, new OutpostStateMachine().phase());
    }

    @Test
    void fullHappyPathWalksEveryPhase() {
        OutpostStateMachine machine = started();
        assertEquals(RunPhase.WAITING, machine.phase());
        assertEquals(1, machine.wave());

        machine.claim(UUID.randomUUID(), "Rycerze", 0L);
        assertEquals(RunPhase.PREPARATION, machine.phase());

        machine.startWaves();
        assertEquals(RunPhase.WAVES, machine.phase());

        machine.applyBossRoll("wladca_burzy");
        machine.toBoss();
        assertEquals(RunPhase.BOSS, machine.phase());

        machine.complete();
        assertEquals(RunPhase.COMPLETED, machine.phase());

        machine.toCooldown(NOW, 30_000L);
        assertEquals(RunPhase.COOLDOWN, machine.phase());
        assertEquals(NOW + 30_000L, machine.cooldownUntilMillis());
    }

    @Test
    void illegalTransitionIsRejected() {
        OutpostStateMachine machine = started();
        // WAITING may only move to PREPARATION or COOLDOWN.
        assertFalse(machine.canTransition(RunPhase.BOSS));
        assertThrows(IllegalStateException.class, () -> machine.transition(RunPhase.BOSS));
        assertEquals(RunPhase.WAITING, machine.phase());
    }

    @Test
    void wavesMayGoStraightToCompletedWhenNoBossWasRolled() {
        OutpostStateMachine machine = started();
        machine.claim(UUID.randomUUID(), "Rycerze", 0L);
        machine.startWaves();
        machine.applyBossRoll(null);

        assertEquals(BossStatus.NONE, machine.bossStatus());
        assertTrue(machine.canTransition(RunPhase.COMPLETED));
        machine.complete();
        assertEquals(RunPhase.COMPLETED, machine.phase());
    }

    @Test
    void everyMutationIncreasesTheRevision() {
        OutpostStateMachine machine = started();
        long afterStart = machine.revision();

        machine.claim(UUID.randomUUID(), "Rycerze", 0L);
        long afterClaim = machine.revision();
        assertTrue(afterClaim > afterStart);

        machine.setWave(2);
        assertTrue(machine.revision() > afterClaim);
    }

    @Test
    void restoreIsOnlyLegalWhileRecovering() {
        OutpostStateMachine live = started();
        RunSnapshot snapshot = live.snapshot();
        assertThrows(IllegalStateException.class, () -> live.restore(snapshot));
    }

    @Test
    void restoreRebuildsTheWholeRun() {
        OutpostStateMachine source = started();
        UUID town = UUID.randomUUID();
        source.claim(town, "Rycerze", 0L);
        source.startWaves();
        source.setWave(3);
        source.setMobsRemaining(7);
        source.applyBossRoll("pradawny_tytan");

        OutpostStateMachine restored = new OutpostStateMachine();
        restored.restore(source.snapshot());

        assertEquals(RunPhase.WAVES, restored.phase());
        assertEquals(3, restored.wave());
        assertEquals(7, restored.mobsRemaining());
        assertEquals(town, restored.controllingTown().orElseThrow());
        assertEquals("pradawny_tytan", restored.bossId().orElseThrow());
        assertEquals(BossStatus.PENDING, restored.bossStatus());
    }

    @Test
    void theRevisionFloorIsAdoptedButNeverLowered() {
        OutpostStateMachine machine = new OutpostStateMachine();
        machine.adoptRevisionFloor(1_000L);
        machine.restoreEmpty();
        assertTrue(machine.revision() > 1_000L, "pierwsza zmiana po odzyskaniu leży ponad zapisaną rewizją");

        long current = machine.revision();
        machine.adoptRevisionFloor(10L);
        assertEquals(current, machine.revision(), "niższa rewizja z bazy nie cofa licznika");
    }
}
