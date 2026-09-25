package hexposterunki.persistence;

import hexposterunki.domain.BossStatus;
import hexposterunki.domain.KillEntry;
import hexposterunki.domain.RunPhase;
import hexposterunki.domain.RunSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding 2 regression for the write-ordering layer.
 *
 * <p>The store and the async runner are test doubles at the database boundary; everything under
 * test - {@link SnapshotWriter} and {@link RevisionGuard} - is the production code that decides
 * ordering, retry and health. The SQL itself is covered by {@link ConsistentSnapshotSqlTest}.
 */
class SnapshotWriterTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID TOWN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    /** Lets the test decide exactly when a write completes and whether it fails. */
    private static final class ManualRunner implements SnapshotWriter.AsyncRunner {
        private final List<Runnable> queued = new ArrayList<>();
        private final List<CompletableFuture<Object>> futures = new ArrayList<>();

        @SuppressWarnings("unchecked")
        @Override
        public <T> CompletableFuture<T> run(Supplier<T> work) {
            CompletableFuture<Object> future = new CompletableFuture<>();
            futures.add(future);
            queued.add(() -> {
                try {
                    future.complete(work.get());
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
            return (CompletableFuture<T>) future;
        }

        boolean hasPending() {
            return !queued.isEmpty();
        }

        int queuedCount() {
            return queued.size();
        }

        /** Runs the oldest queued write. */
        void complete() {
            Runnable next = queued.remove(0);
            next.run();
        }
    }

    private static final class RecordingStore implements SnapshotStore {
        private final List<Long> written = new CopyOnWriteArrayList<>();
        private final List<Long> attempted = new CopyOnWriteArrayList<>();
        private volatile long failRevision = -1L;

        @Override
        public WriteResult saveConsistent(RunPersistenceSnapshot snapshot, long nowMillis) {
            attempted.add(snapshot.revision());
            if (snapshot.revision() == failRevision) {
                throw new IllegalStateException("symulowany błąd zapisu");
            }
            written.add(snapshot.revision());
            return WriteResult.WRITTEN;
        }
    }

    private static RunPersistenceSnapshot snapshot(long revision, int kills) {
        RunSnapshot run = new RunSnapshot("run-1", "fort", RunPhase.WAVES, 1, TOWN, "Rycerze",
                null, false, BossStatus.NONE, null, 0, 0, 0L, 0L, 0L, 0L, 0L, "", revision);
        List<KillEntry> participants = kills <= 0 ? List.of()
                : List.of(new KillEntry(PLAYER, TOWN, kills, 100L));
        return new RunPersistenceSnapshot(run, "fort", participants, List.of());
    }

    @Test
    void onlyOneWriteRunsAtATime() {
        RecordingStore store = new RecordingStore();
        ManualRunner runner = new ManualRunner();
        SnapshotWriter writer = new SnapshotWriter(store, runner, new RevisionGuard());

        writer.submit(snapshot(1L, 1));
        writer.submit(snapshot(2L, 2));
        writer.submit(snapshot(3L, 3));

        assertEquals(1, runner.queuedCount(),
                "drugi zapis nie jest zlecany przed zakończeniem pierwszego");
        runner.complete();
        assertEquals(List.of(1L), store.written);
        assertEquals(1, store.attempted.size());
        assertEquals(1, runner.queuedCount(), "dopiero teraz startuje kolejny zapis");
    }

    @Test
    void intermediateSnapshotsAreCoalescedToTheNewest() {
        RecordingStore store = new RecordingStore();
        ManualRunner runner = new ManualRunner();
        SnapshotWriter writer = new SnapshotWriter(store, runner, new RevisionGuard());

        writer.submit(snapshot(1L, 1));
        writer.submit(snapshot(2L, 2));
        writer.submit(snapshot(3L, 3));
        runner.complete();   // revision 1 lands, then the newest pending starts
        runner.complete();   // revision 3

        assertEquals(List.of(1L, 3L), store.written,
                "pośrednie stany są scalane - liczy się najnowszy");
        assertFalse(runner.hasPending());
    }

    @Test
    void anOlderSnapshotSubmittedLateIsNeverWritten() {
        RecordingStore store = new RecordingStore();
        ManualRunner runner = new ManualRunner();
        SnapshotWriter writer = new SnapshotWriter(store, runner, new RevisionGuard());

        writer.submit(snapshot(5L, 0));
        runner.complete();
        writer.submit(snapshot(4L, 9));
        if (runner.hasPending()) {
            runner.complete();
        }

        assertEquals(List.of(5L), store.written,
                "starsza rewizja nie może nadpisać nowszego stanu");
    }

    @Test
    void aFailedWriteIsRetriedWithTheSameRevisionAndDoesNotBurnIt() {
        RecordingStore store = new RecordingStore();
        store.failRevision = 2L;
        ManualRunner runner = new ManualRunner();
        RevisionGuard guard = new RevisionGuard();
        SnapshotWriter writer = new SnapshotWriter(store, runner, guard);

        writer.submit(snapshot(2L, 4));
        runner.complete();                       // fails

        assertFalse(writer.healthy());
        assertEquals(-1L, guard.lastCommitted(), "nieudany zapis nie może zaliczyć rewizji");
        assertTrue(writer.hasPendingWork());

        store.failRevision = -1L;
        writer.retryPending();
        runner.complete();

        assertTrue(writer.healthy());
        assertEquals(List.of(2L), store.written);
        assertEquals(2L, guard.lastCommitted());
    }

    @Test
    void healthGoesBadOnFailureAndRecoversOnSuccess() {
        RecordingStore store = new RecordingStore();
        store.failRevision = 1L;
        ManualRunner runner = new ManualRunner();
        SnapshotWriter writer = new SnapshotWriter(store, runner, new RevisionGuard());

        assertTrue(writer.healthy(), "przed pierwszym zapisem stan jest zdrowy");
        writer.submit(snapshot(1L, 0));
        runner.complete();
        assertFalse(writer.healthy());
        assertEquals(1L, writer.consecutiveFailures());

        store.failRevision = -1L;
        writer.submit(snapshot(2L, 0));
        runner.complete();
        assertTrue(writer.healthy());
        assertEquals(0L, writer.consecutiveFailures());
    }

    @Test
    void flushCompletesOnlyWhenNothingIsOutstanding() throws Exception {
        RecordingStore store = new RecordingStore();
        ManualRunner runner = new ManualRunner();
        SnapshotWriter writer = new SnapshotWriter(store, runner, new RevisionGuard());

        writer.submit(snapshot(1L, 0));
        CompletableFuture<Void> flush = writer.flush();
        assertFalse(flush.isDone());

        runner.complete();
        flush.get();
        assertTrue(flush.isDone());
    }

    @Test
    void flushWaitsForTheNewestQueuedSnapshotNotForTheWriteInFlight() throws Exception {
        RecordingStore store = new RecordingStore();
        ManualRunner runner = new ManualRunner();
        RevisionGuard guard = new RevisionGuard();
        SnapshotWriter writer = new SnapshotWriter(store, runner, guard);

        writer.submit(snapshot(1L, 1));          // A: in flight
        writer.submit(snapshot(2L, 2));          // B: queued behind A
        CompletableFuture<Void> flush = writer.flush();

        runner.complete();                        // A lands
        assertEquals(List.of(1L), store.written);
        assertFalse(flush.isDone(), "zapis A nie może zakończyć flush, gdy B wciąż czeka");
        assertEquals(1, runner.queuedCount(), "B startuje dopiero po A");

        runner.complete();                        // B lands
        flush.get();
        assertEquals(List.of(1L, 2L), store.written);
        assertEquals(2L, guard.lastCommitted(), "sukces oznacza, że ostatni przekazany stan jest trwały");
    }

    @Test
    void flushFailsWhenTheNewestSnapshotCannotBeWritten() {
        RecordingStore store = new RecordingStore();
        store.failRevision = 2L;
        ManualRunner runner = new ManualRunner();
        RevisionGuard guard = new RevisionGuard();
        SnapshotWriter writer = new SnapshotWriter(store, runner, guard);

        writer.submit(snapshot(1L, 1));
        writer.submit(snapshot(2L, 2));
        CompletableFuture<Void> flush = writer.flush();

        runner.complete();                        // A lands
        assertFalse(flush.isDone());
        runner.complete();                        // B fails

        assertTrue(flush.isCompletedExceptionally(), "błąd ostatniego zapisu nie może wyglądać jak sukces");
        assertEquals(1L, guard.lastCommitted());
        assertTrue(writer.hasPendingWork(), "B zostaje do ponowienia");
    }

    @Test
    void aFailedOlderWriteDoesNotEndTheFlushWhileANewerSnapshotIsQueued() throws Exception {
        RecordingStore store = new RecordingStore();
        store.failRevision = 1L;
        ManualRunner runner = new ManualRunner();
        RevisionGuard guard = new RevisionGuard();
        SnapshotWriter writer = new SnapshotWriter(store, runner, guard);

        writer.submit(snapshot(1L, 1));
        writer.submit(snapshot(2L, 2));
        CompletableFuture<Void> flush = writer.flush();

        runner.complete();                        // A fails, but B supersedes it
        assertFalse(flush.isDone(), "nowszy stan B zastępuje nieudany A i musi zostać zapisany");
        assertEquals(1, runner.queuedCount());

        runner.complete();                        // B lands
        flush.get();
        assertTrue(writer.healthy());
        assertEquals(List.of(2L), store.written);
        assertEquals(2L, guard.lastCommitted());
    }

    @Test
    void aRejectedExecutorFailsTheFlushInsteadOfHanging() {
        RecordingStore store = new RecordingStore();
        SnapshotWriter writer = new SnapshotWriter(store, new SnapshotWriter.AsyncRunner() {
            @Override
            public <T> CompletableFuture<T> run(Supplier<T> work) {
                throw new java.util.concurrent.RejectedExecutionException("executor zamknięty");
            }
        }, new RevisionGuard());

        writer.submit(snapshot(1L, 0));
        CompletableFuture<Void> flush = writer.flush();

        assertTrue(flush.isCompletedExceptionally());
        assertFalse(writer.healthy());
    }

    @Test
    void flushReportsAnOutstandingFailureInsteadOfPretendingSuccess() {
        RecordingStore store = new RecordingStore();
        store.failRevision = 1L;
        ManualRunner runner = new ManualRunner();
        SnapshotWriter writer = new SnapshotWriter(store, runner, new RevisionGuard());

        writer.submit(snapshot(1L, 0));
        CompletableFuture<Void> flush = writer.flush();
        runner.complete();

        assertTrue(flush.isCompletedExceptionally());
        assertThrows(Exception.class, flush::get);
    }
}
