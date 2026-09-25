package hexposterunki.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An outpost left empty for the whole grace period falls back to wave 1 on the same location,
 * loses its controller and all kill counters - but keeps the boss roll of this run, so wiping
 * cannot be used to farm fresh rolls.
 */
class GraceWipeTest {

    private OutpostStateMachine midFight(String bossId) {
        OutpostStateMachine machine = new OutpostStateMachine();
        machine.restoreEmpty();
        machine.beginRun("run-1", "fort_polnocny", 1_000L, 60_000L);
        machine.applyBossRoll(bossId);
        machine.claim(UUID.randomUUID(), "Rycerze", 0L);
        machine.startWaves();
        machine.setWave(3);
        machine.setMobsRemaining(12);
        return machine;
    }

    @Test
    void wipeResetsWaveControlAndMobsButKeepsTheOutpost() {
        OutpostStateMachine machine = midFight("wladca_burzy");
        String outpostId = machine.outpostId().orElseThrow();
        String runId = machine.runId().orElseThrow();

        machine.wipeToWaveOne();

        assertEquals(RunPhase.WAITING, machine.phase());
        assertEquals(1, machine.wave());
        assertEquals(0, machine.mobsRemaining());
        assertTrue(machine.controllingTown().isEmpty());
        assertEquals(outpostId, machine.outpostId().orElseThrow(), "ta sama twierdza pozostaje aktywna");
        assertEquals(runId, machine.runId().orElseThrow(), "to wciąż ten sam run");
    }

    @Test
    void wipeKeepsTheBossRollOfThisRun() {
        OutpostStateMachine machine = midFight("pradawny_tytan");
        machine.wipeToWaveOne();

        assertTrue(machine.bossRolled());
        assertEquals("pradawny_tytan", machine.bossId().orElseThrow());
        assertEquals(BossStatus.PENDING, machine.bossStatus());
        assertEquals(0, machine.bossSpawnAttempts(), "boss może zostać ponownie przywołany w tym runie");
    }

    @Test
    void wipeOfARunWithoutBossStaysWithoutBoss() {
        OutpostStateMachine machine = midFight(null);
        machine.wipeToWaveOne();

        assertTrue(machine.bossRolled());
        assertTrue(machine.bossId().isEmpty());
        assertEquals(BossStatus.NONE, machine.bossStatus());
        assertFalse(machine.canTransition(RunPhase.BOSS), "z WAITING nie da się przejść do BOSS");
    }

    @Test
    void killLedgerIsClearedSeparatelyAndCompletely() {
        KillLedger ledger = new KillLedger();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ledger.recordKill(first, UUID.randomUUID(), 10L);
        ledger.recordKill(second, UUID.randomUUID(), 20L);

        ledger.clear();

        assertTrue(ledger.isEmpty());
        assertEquals(0, ledger.kills(first));
        assertEquals(0, ledger.kills(second));
    }
}
