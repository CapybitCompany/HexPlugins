package hexposterunki.recovery;

import hexposterunki.domain.BossStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryPlannerTest {

    private static final String RUN = "run-1";

    private static UUID id(String suffix) {
        return UUID.fromString("00000000-0000-0000-0000-0000000000" + suffix);
    }

    private static RecoveryInput waves(int currentWave, int expectedAlive, List<FoundEntity> found) {
        return new RecoveryInput(RUN, currentWave, true, expectedAlive, found,
                false, null, BossStatus.NONE, null, 0, 2);
    }

    @Test
    void anIntactWaveNeedsNoAction() {
        RecoveryPlan plan = RecoveryPlanner.plan(waves(2, 2, List.of(
                new FoundEntity(id("01"), RUN, 2, false),
                new FoundEntity(id("02"), RUN, 2, false))));
        assertTrue(plan.isNoop());
    }

    @Test
    void surplusMobsAreRemovedSoARestartNeverDoublesAWave() {
        RecoveryPlan plan = RecoveryPlanner.plan(waves(1, 2, List.of(
                new FoundEntity(id("01"), RUN, 1, false),
                new FoundEntity(id("02"), RUN, 1, false),
                new FoundEntity(id("03"), RUN, 1, false),
                new FoundEntity(id("04"), RUN, 1, false))));
        assertEquals(2, plan.removeEntities().size());
        assertEquals(0, plan.spawnMissingMobs());
    }

    @Test
    void missingMobsOfTheCurrentWaveAreSpawnedAgain() {
        RecoveryPlan plan = RecoveryPlanner.plan(waves(3, 5, List.of(
                new FoundEntity(id("01"), RUN, 3, false),
                new FoundEntity(id("02"), RUN, 3, false))));
        assertEquals(3, plan.spawnMissingMobs());
        assertTrue(plan.removeEntities().isEmpty());
    }

    @Test
    void mobsOfAnotherWaveOrAnotherRunAreRemovedAndNeverCounted() {
        RecoveryPlan plan = RecoveryPlanner.plan(waves(3, 2, List.of(
                new FoundEntity(id("01"), RUN, 2, false),
                new FoundEntity(id("02"), "run-old", 3, false),
                new FoundEntity(id("03"), RUN, 3, false))));
        assertEquals(2, plan.removeEntities().size());
        assertTrue(plan.removeEntities().contains(id("01")));
        assertTrue(plan.removeEntities().contains(id("02")));
        assertEquals(1, plan.spawnMissingMobs());
    }

    @Test
    void theSurvivingSetIsStableAcrossRepeatedPlanning() {
        List<FoundEntity> found = List.of(
                new FoundEntity(id("0c"), RUN, 1, false),
                new FoundEntity(id("0a"), RUN, 1, false),
                new FoundEntity(id("0b"), RUN, 1, false));
        RecoveryPlan first = RecoveryPlanner.plan(waves(1, 1, found));
        RecoveryPlan second = RecoveryPlanner.plan(waves(1, 1, List.copyOf(found.reversed())));
        assertEquals(first.removeEntities(), second.removeEntities());
    }

    @Test
    void nonCombatPhasesRemoveEverythingAndSpawnNothing() {
        RecoveryInput input = new RecoveryInput(RUN, 1, false, 4, List.of(
                new FoundEntity(id("01"), RUN, 1, false),
                new FoundEntity(id("02"), RUN, 0, true)),
                false, "boss", BossStatus.PENDING, null, 0, 2);
        RecoveryPlan plan = RecoveryPlanner.plan(input);
        assertEquals(2, plan.removeEntities().size());
        assertEquals(0, plan.spawnMissingMobs());
        assertEquals(RecoveryPlan.BossAction.NONE, plan.bossAction());
    }

    // ------------------------------------------------------------------ boss

    private static RecoveryInput boss(BossStatus status, UUID stored, int attempts, List<FoundEntity> found) {
        return new RecoveryInput(RUN, 1, true, 0, found, true, "wladca_burzy", status, stored, attempts, 2);
    }

    @Test
    void aLivingStoredBossIsReattachedAndNeverSpawnedAgain() {
        RecoveryPlan plan = RecoveryPlanner.plan(boss(BossStatus.ALIVE, id("b1"), 1,
                List.of(new FoundEntity(id("b1"), RUN, 0, true))));
        assertEquals(RecoveryPlan.BossAction.REATTACH, plan.bossAction());
        assertEquals(id("b1"), plan.reattachBossId());
        assertTrue(plan.removeEntities().isEmpty());
    }

    @Test
    void duplicateBossEntitiesAreRemovedAndOnlyTheStoredOneSurvives() {
        RecoveryPlan plan = RecoveryPlanner.plan(boss(BossStatus.ALIVE, id("b1"), 1, List.of(
                new FoundEntity(id("b1"), RUN, 0, true),
                new FoundEntity(id("b2"), RUN, 0, true),
                new FoundEntity(id("b3"), RUN, 0, true))));
        assertEquals(RecoveryPlan.BossAction.REATTACH, plan.bossAction());
        assertEquals(2, plan.removeEntities().size());
        assertFalse(plan.removeEntities().contains(id("b1")));
    }

    @Test
    void aMissingBossIsRecreatedOnceWithTheSameStoredId() {
        RecoveryPlan plan = RecoveryPlanner.plan(boss(BossStatus.ALIVE, id("b1"), 1, List.of()));
        assertEquals(RecoveryPlan.BossAction.SPAWN, plan.bossAction());
    }

    @Test
    void theRespawnBudgetIsSpentAfterOneRecoverySpawn() {
        RecoveryPlan plan = RecoveryPlanner.plan(boss(BossStatus.ALIVE, id("b1"), 2, List.of()));
        assertEquals(RecoveryPlan.BossAction.NONE, plan.bossAction(),
                "boss ma być odtworzony najwyżej raz");
    }

    @Test
    void aPendingBossThatNeverSpawnedIsStillCreated() {
        RecoveryPlan plan = RecoveryPlanner.plan(boss(BossStatus.PENDING, null, 0, List.of()));
        assertEquals(RecoveryPlan.BossAction.SPAWN, plan.bossAction());
    }

    @Test
    void aDeadBossIsNeverBroughtBack() {
        RecoveryPlan plan = RecoveryPlanner.plan(boss(BossStatus.DEAD, null, 1, List.of()));
        assertEquals(RecoveryPlan.BossAction.NONE, plan.bossAction());
    }

    @Test
    void bossEntitiesOfADeadBossAreSweptAway() {
        RecoveryPlan plan = RecoveryPlanner.plan(boss(BossStatus.DEAD, null, 1,
                List.of(new FoundEntity(id("b9"), RUN, 0, true))));
        assertEquals(List.of(id("b9")), plan.removeEntities());
    }
}
