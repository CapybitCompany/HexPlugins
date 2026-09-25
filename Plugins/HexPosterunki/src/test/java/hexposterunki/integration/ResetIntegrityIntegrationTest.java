package hexposterunki.integration;

import hexposterunki.domain.BossStatus;
import hexposterunki.domain.RunPhase;
import hexposterunki.engine.EngineMode;
import hexposterunki.persistence.PosterunkiRepository;
import hexposterunki.persistence.RewardClaim;
import hexposterunki.support.FakeBossAdapter;
import hexposterunki.support.OutpostTestHarness;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review finding 3: a reset must never turn into a victory, and nothing of the cleaned-up fight may
 * progress before the cleanup finished.
 *
 * <p>The multi-tick cleanup is delayed deliberately in two ways: by not running the scheduler (the
 * budgeted fire scan stays unfinished) or by holding the database thread (the block-snapshot
 * callback does not arrive). Meanwhile the engine keeps ticking and late events of the finished fight
 * arrive through the real engine and listener pipeline. Stored state is read with the production
 * repository SQL.
 */
class ResetIntegrityIntegrationTest {

    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER_TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private Player fighter(OutpostTestHarness harness) {
        Player player = harness.server.addPlayer("Obronca");
        harness.towns.assign(player.getUniqueId(), TOWN, "Rycerze");
        player.teleport(harness.inside(8, 64, 8));
        return player;
    }

    private void reachWaves(OutpostTestHarness harness) throws Exception {
        harness.engine.beginNewRun("fort", System.currentTimeMillis(), "test");
        harness.tick();
        harness.tick();
        harness.settle();
        assertEquals(RunPhase.WAVES, harness.engine.phase(), harness.engine.state().failureReason());
        assertEquals(2, harness.engine.liveMobs().size());
    }

    private void reachBoss(OutpostTestHarness harness, Player player) throws Exception {
        reachWaves(harness);
        for (LivingEntity mob : liveMobs(harness)) {
            harness.engine.onRunMobDeath(mob, player);
            mob.remove();
        }
        harness.settle();
        harness.tick();
        harness.settle();
        assertEquals(RunPhase.BOSS, harness.engine.phase(), harness.engine.state().failureReason());
        assertEquals(BossStatus.ALIVE, harness.engine.state().bossStatus());
    }

    private List<LivingEntity> liveMobs(OutpostTestHarness harness) {
        List<LivingEntity> mobs = new ArrayList<>();
        for (Entity entity : harness.world.getEntities()) {
            if (harness.engine.liveMobs().containsKey(entity.getUniqueId())) {
                mobs.add((LivingEntity) entity);
            }
        }
        return mobs;
    }

    private long zombies(OutpostTestHarness harness) {
        return harness.world.getEntities().stream().filter(entity -> entity instanceof Zombie && entity.isValid()).count();
    }

    /**
     * While the cleanup is held back: many engine ticks, a late kill of a former wave mob, a player of
     * the controlling town and a foreign town entering - none of it may progress the old fight.
     */
    private void assertNothingProgressesWhileCleaning(OutpostTestHarness harness, Player fighter, String runId,
                                                      List<LivingEntity> formerMobs) {
        UUID controller = harness.engine.state().controllingTown().orElse(null);
        Player intruder = harness.server.addPlayer("Najezdzca");
        harness.towns.assign(intruder.getUniqueId(), OTHER_TOWN, "Najeźdźcy");

        for (int i = 0; i < 25; i++) {
            harness.engine.tick();
        }
        for (LivingEntity mob : formerMobs) {
            harness.engine.onRunMobDeath(mob, fighter);
        }
        harness.engine.onEnterRegion(fighter);
        harness.engine.onEnterRegion(intruder);
        harness.engine.tick();

        assertEquals(RunPhase.RESETTING, harness.engine.phase(), "reset nie może zamienić się w zwycięstwo");
        assertEquals(EngineMode.RESETTING, harness.engine.mode());
        assertTrue(harness.engine.resetInProgress());
        assertEquals(controller, harness.engine.state().controllingTown().orElse(null), "brak zmiany kontroli");
        assertFalse(harness.engine.participationOf(fighter).mayDamage());
        assertTrue(harness.engine.participation().ledger().entries().isEmpty());
        assertTrue(harness.engine.liveMobs().isEmpty());
        assertEquals(0L, zombies(harness), "podczas resetu nie może pojawić się żaden przeciwnik");
        assertFalse(harness.rewards.ledger().isFrozen(runId), "reset nie zamraża żadnych nagród");

        // Synchronous repository reads: they also work while the database thread is held.
        PosterunkiRepository repository = harness.persistence.repository();
        assertTrue(repository.loadClaims(RewardClaim.Status.CLAIMED).isEmpty());
        assertTrue(repository.loadCompletionStats(runId).isEmpty(), "brak statystyk zwycięstwa");
    }

    private void assertStoredResetting(OutpostTestHarness harness) {
        PosterunkiRepository.LoadedState stored = harness.persistence.repository().loadAll();
        assertEquals(RunPhase.RESETTING, stored.run().phase(), "przerwany reset musi być zapisany jako reset");
        assertTrue(stored.participants().isEmpty());
        assertTrue(stored.entities().isEmpty(), "usunięte encje nie mogą zostać w bazie");
    }

    private void runCleanupToTheEnd(OutpostTestHarness harness) throws Exception {
        for (int i = 0; i < 200 && harness.engine.resetInProgress(); i++) {
            harness.server.getScheduler().performOneTick();
            Thread.sleep(5L);
        }
        harness.settle();
        assertFalse(harness.engine.resetInProgress(), "reset musi się zakończyć");
    }

    private void assertEndedInCooldown(OutpostTestHarness harness, long expectedCooldownMillis) throws Exception {
        assertEquals(RunPhase.COOLDOWN, harness.engine.phase());
        long remaining = harness.engine.state().cooldownUntilMillis() - System.currentTimeMillis();
        assertTrue(Math.abs(remaining - expectedCooldownMillis) < 10_000L,
                "oczekiwana przerwa " + expectedCooldownMillis + " ms, pozostało " + remaining + " ms");
        assertTrue(harness.regions.active().isEmpty(), "po resecie geometria nie jest już przypięta");
        harness.awaitWrites();
        assertEquals(RunPhase.COOLDOWN, harness.persistence.loadAll().get().run().phase());
    }

    @Test
    void adminStopDuringWavesWithADelayedScanNeverCompletesTheWave() throws Exception {
        try (OutpostTestHarness harness = new OutpostTestHarness()) {
            Player player = fighter(harness);
            reachWaves(harness);
            String runId = harness.engine.state().runId().orElseThrow();
            List<LivingEntity> formerMobs = liveMobs(harness);

            assertTrue(harness.engine.adminStop());
            harness.awaitWrites();          // no scheduler tick: the fire scan has not even started
            assertStoredResetting(harness);
            assertNothingProgressesWhileCleaning(harness, player, runId, formerMobs);

            runCleanupToTheEnd(harness);
            assertEndedInCooldown(harness, harness.currentConfig().timing().cooldownMillis());
        }
    }

    @Test
    void adminResetDuringWavesWithADelayedDatabaseCallbackNeverCompletesTheWave() throws Exception {
        try (OutpostTestHarness harness = new OutpostTestHarness()) {
            Player player = fighter(harness);
            reachWaves(harness);
            String runId = harness.engine.state().runId().orElseThrow();
            List<LivingEntity> formerMobs = liveMobs(harness);

            assertTrue(harness.engine.adminReset(null));
            harness.awaitWrites();
            assertStoredResetting(harness);

            Runnable release = harness.holdDatabase();
            try {
                for (int i = 0; i < 10; i++) {
                    harness.server.getScheduler().performOneTick();   // scan finishes, DB callback is held
                }
                assertTrue(harness.engine.resetInProgress(), "wywołanie z bazy jest wstrzymane");
                assertNothingProgressesWhileCleaning(harness, player, runId, formerMobs);
            } finally {
                release.run();
            }

            runCleanupToTheEnd(harness);
            assertEndedInCooldown(harness, 0L);
        }
    }

    @Test
    void adminStopDuringTheBossFightWithADelayedScanNeverCompletesTheRun() throws Exception {
        FakeBossAdapter boss = new FakeBossAdapter();
        try (OutpostTestHarness harness = OutpostTestHarness.withBoss(boss)) {
            Player player = fighter(harness);
            reachBoss(harness, player);
            String runId = harness.engine.state().runId().orElseThrow();

            assertTrue(harness.engine.adminStop());
            harness.awaitWrites();
            assertStoredResetting(harness);
            assertNothingProgressesWhileCleaning(harness, player, runId, List.of());

            runCleanupToTheEnd(harness);
            assertEndedInCooldown(harness, harness.currentConfig().timing().cooldownMillis());
            assertEquals(1, boss.spawns(), "boss nie może zostać przywołany ponownie");
        }
    }

    @Test
    void adminResetDuringTheBossFightIgnoresTheBossDeathItCausesAndADelayedCallback() throws Exception {
        FakeBossAdapter boss = new FakeBossAdapter();
        boss.killOnStop(true);   // stopping the boss fires a real EntityDeathEvent mid-reset
        try (OutpostTestHarness harness = OutpostTestHarness.withBoss(boss)) {
            Player player = fighter(harness);
            reachBoss(harness, player);
            String runId = harness.engine.state().runId().orElseThrow();

            assertTrue(harness.engine.adminReset(null));
            assertNotEquals(BossStatus.DEAD, harness.engine.state().bossStatus(),
                    "śmierć wywołana resetem nie jest pokonaniem bossa");
            assertNotEquals(RunPhase.COMPLETED, harness.engine.phase());
            harness.awaitWrites();
            assertStoredResetting(harness);

            Runnable release = harness.holdDatabase();
            try {
                for (int i = 0; i < 10; i++) {
                    harness.server.getScheduler().performOneTick();
                }
                assertNothingProgressesWhileCleaning(harness, player, runId, List.of());
            } finally {
                release.run();
            }

            runCleanupToTheEnd(harness);
            assertEndedInCooldown(harness, 0L);
            assertTrue(harness.persistence.repository().loadCompletionStats(runId).isEmpty());
        }
    }

    @Test
    void aRestartDuringTheCleanupFinishesTheResetInsteadOfResumingTheFight() throws Exception {
        FakeBossAdapter boss = new FakeBossAdapter();
        OutpostTestHarness harness = OutpostTestHarness.withBoss(boss);
        String runId;
        try {
            Player player = fighter(harness);
            reachBoss(harness, player);
            runId = harness.engine.state().runId().orElseThrow();

            assertTrue(harness.engine.adminStop());
            harness.awaitWrites();
            assertStoredResetting(harness);
        } catch (Throwable failure) {
            harness.close();
            throw failure;
        }

        try (OutpostTestHarness restarted = harness.restart()) {
            restarted.recover();

            assertNotEquals(RunPhase.BOSS, restarted.engine.phase(), "przerwany run nie wraca jako walka");
            assertEquals(RunPhase.RESETTING, restarted.engine.phase());
            assertTrue(restarted.engine.resetInProgress(), "reset jest kontynuowany");
            assertTrue(restarted.engine.liveMobs().isEmpty());
            assertEquals(0L, zombies(restarted), "odzyskanie nie może przywołać mobów ani bossa");
            assertEquals(1, boss.spawns(), "odzyskanie nie może przywołać bossa ponownie");
            for (int i = 0; i < 10; i++) {
                restarted.engine.tick();
            }
            assertEquals(RunPhase.RESETTING, restarted.engine.phase());

            runCleanupToTheEnd(restarted);
            assertEndedInCooldown(restarted, restarted.currentConfig().timing().cooldownMillis());
            assertEquals(1, boss.spawns());
            assertTrue(restarted.persistence.repository().loadClaims(RewardClaim.Status.CLAIMED).isEmpty());
            assertTrue(restarted.persistence.repository().loadCompletionStats(runId).isEmpty());
        } finally {
            harness.close();
        }
    }
}
