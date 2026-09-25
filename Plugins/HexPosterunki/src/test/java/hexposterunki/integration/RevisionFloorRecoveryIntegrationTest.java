package hexposterunki.integration;

import hexposterunki.domain.BossStatus;
import hexposterunki.domain.RunPhase;
import hexposterunki.domain.RunSnapshot;
import hexposterunki.persistence.PosterunkiRepository;
import hexposterunki.persistence.RunPersistenceSnapshot;
import hexposterunki.persistence.SnapshotStore;
import hexposterunki.support.OutpostTestHarness;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review round 2, finding 2: when recovery discards a stored run, the new engine still continues
 * above the revision the database holds. Every check reads the stored state with the production
 * repository SQL, and each new state must survive a further restart.
 */
class RevisionFloorRecoveryIntegrationTest {

    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static void startFight(OutpostTestHarness harness) throws Exception {
        Player player = harness.server.addPlayer("Obronca");
        harness.towns.assign(player.getUniqueId(), TOWN, "Rycerze");
        player.teleport(harness.inside(8, 64, 8));
        assertTrue(harness.engine.beginNewRun("fort", System.currentTimeMillis(), "test"));
        harness.tick();
        harness.tick();
        harness.settle();
        assertEquals(RunPhase.WAVES, harness.engine.phase());
    }

    /** Damages the stored state, restarts, and checks that a new run is really stored and restored. */
    private void newRunsAreStoredAfterRecovering(long highestStoredRevision, boolean consistent, String... damage)
            throws Exception {
        OutpostTestHarness harness = new OutpostTestHarness();
        try {
            startFight(harness);
            for (String statement : damage) {
                harness.sql(statement);
            }
            assertEquals(consistent, harness.persistence.repository().loadAll().consistent());
        } catch (Throwable failure) {
            harness.close();
            throw failure;
        }

        OutpostTestHarness restarted = harness.restart();
        String newRunId;
        try {
            restarted.recover();
            restarted.awaitWrites();
            assertEquals(RunPhase.COOLDOWN, restarted.engine.phase());
            assertTrue(restarted.persistence.healthy(), restarted.persistence.lastError());
            PosterunkiRepository.LoadedState afterRecovery = restarted.persistence.repository().loadAll();
            assertTrue(afterRecovery.global().revision() > highestStoredRevision,
                    "stan po odzyskaniu musi zostać zapisany ponad rewizją " + highestStoredRevision);
            assertEquals(RunPhase.COOLDOWN, afterRecovery.global().phase());

            assertTrue(restarted.engine.beginNewRun("fort", System.currentTimeMillis(), "test"));
            newRunId = restarted.engine.state().runId().orElseThrow();
            restarted.awaitWrites();
            assertTrue(restarted.persistence.healthy());
            PosterunkiRepository.LoadedState stored = restarted.persistence.repository().loadAll();
            assertEquals(newRunId, stored.global().runId(), "nowy run musi trafić do bazy");
            assertTrue(stored.consistent());
            assertEquals(RunPhase.WAITING, stored.run().phase());

            // Retrying the unchanged state keeps its revision; an older write is still rejected.
            long revision = restarted.engine.captureSnapshot().revision();
            assertEquals(revision, restarted.engine.captureSnapshot().revision());
            RunSnapshot older = new RunSnapshot("stary-run", "fort", RunPhase.WAVES, 1, null, null, null, false,
                    BossStatus.NONE, null, 0, 0, 0L, 0L, 0L, 0L, 0L, "", revision - 1);
            assertEquals(SnapshotStore.WriteResult.STALE, restarted.persistence.repository().saveConsistent(
                    new RunPersistenceSnapshot(older, "fort", List.of(), List.of()), 1L),
                    "ochrona przed starszymi zapisami pozostaje");
        } catch (Throwable failure) {
            restarted.close();
            throw failure;
        }

        try (OutpostTestHarness again = restarted.restart()) {
            again.recover();
            assertEquals(RunPhase.WAITING, again.engine.phase(), "nowy run przetrwał kolejny restart");
            assertEquals(newRunId, again.engine.state().runId().orElseThrow());
        }
    }

    @Test
    void aDiscardedInconsistentRunDoesNotSuppressNewStates() throws Exception {
        newRunsAreStoredAfterRecovering(1000L, false, "UPDATE posterunki_state SET revision = 1000 WHERE id = 1");
    }

    @Test
    void aPointerWhoseRunRowIsMissingDoesNotSuppressNewStates() throws Exception {
        newRunsAreStoredAfterRecovering(700L, false,
                "DELETE FROM posterunki_runs", "UPDATE posterunki_state SET revision = 700 WHERE id = 1");
    }

    @Test
    void aPointerWithoutARunDoesNotSuppressNewStates() throws Exception {
        newRunsAreStoredAfterRecovering(500L, true,
                "UPDATE posterunki_state SET run_id = NULL, outpost_id = NULL, revision = 500 WHERE id = 1");
    }

    @Test
    void aDiscardedRunRowWithAHigherRevisionThanThePointerRaisesTheFloorToo() throws Exception {
        OutpostTestHarness harness = new OutpostTestHarness();
        try {
            startFight(harness);
            harness.sql("UPDATE posterunki_runs SET revision = 2000");
            assertFalse(harness.persistence.repository().loadAll().consistent());
        } catch (Throwable failure) {
            harness.close();
            throw failure;
        }
        try (OutpostTestHarness restarted = harness.restart()) {
            restarted.recover();
            assertTrue(restarted.engine.state().revision() > 2000L,
                    "nowa rewizja leży ponad każdą zapisaną rewizją, także odrzuconego runu");
        }
    }
}
