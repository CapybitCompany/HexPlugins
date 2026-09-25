package hexposterunki.persistence;

import hexposterunki.domain.BossStatus;
import hexposterunki.domain.RunPhase;
import hexposterunki.domain.RunSnapshot;
import hexposterunki.support.TestHexApi;
import hexposterunki.support.TestUiService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review finding 2 against the production {@link PersistenceService} and repository SQL: the
 * shutdown flush only reports success once the last handed-over state is stored, and reports
 * failures and timeouts as problems.
 *
 * <p>The database runs on one background thread exactly like HexCore's executor; the test can hold
 * that thread to put an older write "in flight" while the final snapshot is queued.
 */
class ShutdownFlushSqlTest {

    private Connection connection;
    private TestHexApi api;
    private PersistenceService persistence;

    @BeforeEach
    void setUp() throws Exception {
        connection = SqlTestSupport.open();
        PosterunkiRepository repository = SqlTestSupport.freshRepository(connection);
        api = new TestHexApi(new TestUiService().service(), SqlTestSupport.db(connection));
        Logger logger = Logger.getLogger("ShutdownFlushSqlTest");
        logger.setLevel(java.util.logging.Level.OFF);
        persistence = new PersistenceService(api, repository, logger);
    }

    @AfterEach
    void tearDown() throws Exception {
        api.shutdown();
        connection.close();
    }

    private static RunPersistenceSnapshot snapshot(long revision, RunPhase phase) {
        RunSnapshot run = new RunSnapshot("run-1", "fort", phase, 1, null, null, null, false,
                BossStatus.NONE, null, 0, 0, 1_000L, 0L, 0L, 0L, 0L, "", revision);
        return new RunPersistenceSnapshot(run, "fort", List.of(), List.of());
    }

    private long storedRevision() {
        return persistence.repository().loadAll().global().revision();
    }

    @Test
    void theShutdownFlushReturnsOnlyAfterTheFinalSnapshotIsStored() throws Exception {
        Runnable release = api.holdDatabase();
        persistence.save(snapshot(1L, RunPhase.WAITING));        // A: queued on the held DB thread
        persistence.save(snapshot(2L, RunPhase.PREPARATION));    // B: pending behind A

        AtomicReference<String> result = new AtomicReference<>("nie zakończono");
        Thread shutdown = new Thread(() -> result.set(
                persistence.flushOnShutdown(snapshot(3L, RunPhase.WAVES), 5L)));
        shutdown.start();
        Thread.sleep(150L);
        assertTrue(shutdown.isAlive(), "flush nie może się zakończyć, zanim ostatni stan trafi do bazy");

        release.run();
        shutdown.join(10_000L);

        assertNull(result.get(), "zapis powinien się udać");
        assertEquals(3L, storedRevision(), "w bazie musi być ostatni przekazany stan");
        assertEquals(RunPhase.WAVES, persistence.repository().loadAll().run().phase());
    }

    @Test
    void aFailingFinalWriteIsReportedAsAProblem() throws Exception {
        persistence.save(snapshot(1L, RunPhase.WAITING));
        for (int i = 0; i < 200 && persistence.hasPendingWork(); i++) {
            Thread.sleep(5L);
        }
        try (Statement sql = connection.createStatement()) {
            sql.execute("ALTER TABLE posterunki_state RENAME TO ukryty_stan");
        }

        String problem = persistence.flushOnShutdown(snapshot(2L, RunPhase.WAVES), 5L);

        assertNotNull(problem, "nieudany ostatni zapis nie może zostać zgłoszony jako sukces");
        try (Statement sql = connection.createStatement()) {
            sql.execute("ALTER TABLE ukryty_stan RENAME TO posterunki_state");
        }
        assertEquals(1L, storedRevision());
    }

    @Test
    void aTimeoutIsReportedAsAProblem() {
        Runnable release = api.holdDatabase();
        try {
            String problem = persistence.flushOnShutdown(snapshot(1L, RunPhase.WAITING), 1L);
            assertNotNull(problem, "przekroczenie czasu nie może wyglądać jak sukces");
            assertTrue(problem.contains("limit czasu"), problem);
        } finally {
            release.run();
        }
    }
}
