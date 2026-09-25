package hexposterunki.integration;

import hexposterunki.domain.RunPhase;
import hexposterunki.persistence.BlockSnapshotEntry;
import hexposterunki.support.OutpostTestHarness;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Final review, finding 1: the block states captured when a run starts are kept until the database
 * confirmed them. The write fails for real (the table is renamed inside H2), the world changes in the
 * meantime, and the repeated write must still carry the original states. A reset that happens while the
 * write is outstanding uses those captured states instead of an empty table, and a late write never
 * resurrects artifacts the reset already deleted.
 *
 * <p>Limit of this environment: MockBukkit does not implement {@code Bukkit.createBlockData(String)},
 * so writing the restored states back into the world cannot be exercised here - only which states the
 * plugin keeps, stores and hands to the restore. Applying them is Paper's part.
 */
class BlockSnapshotRetryIntegrationTest {

    private OutpostTestHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = new OutpostTestHarness();
    }

    /** A configured snapshot material inside the region, so the capture has something to record. */
    private void placeDoor() {
        harness.world.getBlockAt(5, 64, 5).setType(Material.OAK_DOOR);
    }

    @AfterEach
    void tearDown() throws Exception {
        harness.close();
    }

    private void cycles(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            harness.tick();
            Thread.sleep(5L);
        }
        harness.settle();
    }

    private String startRunWithCapture() throws Exception {
        assertTrue(harness.engine.beginNewRun("fort", System.currentTimeMillis(), "test"));
        String runId = harness.engine.state().runId().orElseThrow();
        for (int i = 0; i < 40 && harness.engine.context().blockStates().scanning(); i++) {
            harness.tick();
            Thread.sleep(5L);
        }
        assertFalse(harness.engine.context().blockStates().scanning(), "zrzut bloków musi się zakończyć");
        harness.settle();
        return runId;
    }

    private int storedRows(String runId) throws Exception {
        try (PreparedStatement statement = harness.database().prepareStatement(
                "SELECT COUNT(*) FROM posterunki_blocks WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private void finishReset() throws Exception {
        for (int i = 0; i < 200 && harness.engine.phase() == RunPhase.RESETTING; i++) {
            harness.server.getScheduler().performOneTick();
            Thread.sleep(5L);
        }
        harness.settle();
        assertEquals(RunPhase.COOLDOWN, harness.engine.phase());
    }

    @Test
    void aFailedCaptureWriteIsRepeatedWithTheOriginallyCapturedStates() throws Exception {
        placeDoor();
        harness.sql("ALTER TABLE posterunki_blocks RENAME TO niedostepne_bloki");
        String runId = startRunWithCapture();

        assertTrue(harness.persistence.pendingBlockSnapshot(runId).isPresent(), "zrzut czeka na zapis");
        assertEquals(1, harness.persistence.pendingBlockSnapshot(runId).orElseThrow().size());
        assertNotNull(harness.persistence.lastSideWriteError());

        // The fortress changes while the write is outstanding: the retry must not capture this state.
        harness.world.getBlockAt(5, 64, 5).setType(Material.AIR);
        harness.world.getBlockAt(6, 64, 5).setType(Material.OAK_DOOR);

        harness.sql("ALTER TABLE niedostepne_bloki RENAME TO posterunki_blocks");
        cycles(10);

        List<BlockSnapshotEntry> stored = harness.persistence.loadBlockSnapshot(runId).get();
        assertEquals(1, stored.size(), "pierwotny zrzut został dopisany po naprawie bazy");
        assertEquals(5, stored.get(0).x());
        assertEquals(5, stored.get(0).z());
        assertTrue(stored.get(0).blockData().contains("oak_door"), stored.get(0).blockData());
        assertFalse(harness.persistence.hasPendingSideWrites());
    }

    @Test
    void aResetWhileTheWriteIsOutstandingConsumesTheCapturedStatesInsteadOfTheEmptyTable() throws Exception {
        harness.sql("ALTER TABLE posterunki_blocks RENAME TO niedostepne_bloki");
        String runId = startRunWithCapture();
        assertTrue(harness.persistence.pendingBlockSnapshot(runId).isPresent(),
                "zrzut czeka na zapis, więc baza jest pusta");

        assertTrue(harness.engine.adminStop());
        finishReset();

        assertTrue(harness.persistence.pendingBlockSnapshot(runId).isEmpty(),
                "reset zużył zapamiętany zrzut; zakończony run już go nie potrzebuje");

        harness.sql("ALTER TABLE niedostepne_bloki RENAME TO posterunki_blocks");
        cycles(10);
        assertEquals(0, storedRows(runId), "spóźniony zapis nie przywraca usuniętych artefaktów");
        assertFalse(harness.persistence.hasPendingSideWrites());
    }

    @Test
    void aWriteStillInFlightWhenTheCleanupRunsLeavesNoArtifacts() throws Exception {
        String runId = "run-zapis-w-locie";
        Runnable release = harness.holdDatabase();
        try {
            harness.persistence.saveBlockSnapshot(runId,
                    List.of(new BlockSnapshotEntry(OutpostTestHarness.WORLD, 5, 64, 5, "minecraft:oak_door")));
            harness.persistence.clearRunArtifacts(runId);   // cleanup while the write is in flight
        } finally {
            release.run();
        }
        harness.settle();
        Thread.sleep(100L);

        assertEquals(0, storedRows(runId), "sprzątanie czeka na trwający zapis i usuwa jego wiersze");
        assertTrue(harness.persistence.pendingBlockSnapshot(runId).isEmpty());
    }

    @Test
    void theShutdownFlushReportsCapturedStatesItCouldNotStore() throws Exception {
        harness.sql("ALTER TABLE posterunki_blocks RENAME TO niedostepne_bloki");
        String runId = startRunWithCapture();

        String problem = harness.persistence.flushOnShutdown(harness.engine.captureSnapshot(), 1L);

        assertNotNull(problem, "niezapisany zrzut nie może zostać zgłoszony jako sukces");
        assertTrue(problem.contains("danych pomocniczych"), problem);
        assertTrue(harness.persistence.pendingBlockSnapshot(runId).isPresent());
        harness.sql("ALTER TABLE niedostepne_bloki RENAME TO posterunki_blocks");
    }
}
