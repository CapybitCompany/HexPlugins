package hexposterunki.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Serialises consistent state writes.
 *
 * <p>HexCore runs database work on a thread pool, so two writes submitted in order can complete
 * out of order. This writer removes the race instead of trying to detect it:
 * <ul>
 *   <li>at most one write is in flight at any time;</li>
 *   <li>a newer snapshot submitted while a write runs simply replaces the pending one, so the
 *       database always converges on the newest state and intermediate states are coalesced;</li>
 *   <li>a failed write keeps its snapshot pending and does not burn the revision, so the very same
 *       state is retried instead of being lost;</li>
 *   <li>while a write is failing the writer reports unhealthy, which pauses the engine rather than
 *       letting kills, spawns and rewards run on unsaved state;</li>
 *   <li>{@link #flush()} only reports success once the newest submitted state is durable - never
 *       after an older write while a newer one is still queued.</li>
 * </ul>
 */
public final class SnapshotWriter {

    /** Executes the blocking store call off the main thread. */
    @FunctionalInterface
    public interface AsyncRunner {
        <T> CompletableFuture<T> run(Supplier<T> work);
    }

    private final SnapshotStore store;
    private final AsyncRunner runner;
    private final RevisionGuard guard;
    private final Function<Throwable, String> errorFormatter;

    private final Object lock = new Object();
    private RunPersistenceSnapshot pending;
    private boolean writing;
    private long lastSubmittedRevision = -1L;
    private String lastError;
    private long consecutiveFailures;
    private final List<CompletableFuture<Void>> idleWaiters = new ArrayList<>();

    public SnapshotWriter(SnapshotStore store, AsyncRunner runner, RevisionGuard guard) {
        this(store, runner, guard, SnapshotWriter::rootMessage);
    }

    public SnapshotWriter(SnapshotStore store, AsyncRunner runner, RevisionGuard guard,
                          Function<Throwable, String> errorFormatter) {
        this.store = Objects.requireNonNull(store, "store");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.errorFormatter = Objects.requireNonNull(errorFormatter, "errorFormatter");
    }

    /** Main thread. Queues the newest state; older queued states are dropped. */
    public void submit(RunPersistenceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        synchronized (lock) {
            lastSubmittedRevision = Math.max(lastSubmittedRevision, snapshot.revision());
            if (pending != null && pending.revision() > snapshot.revision()) {
                // Never step back to an older snapshot than the one already queued.
                return;
            }
            pending = snapshot;
        }
        pump();
    }

    /** @return true when the last write attempt succeeded (or nothing was written yet). */
    public boolean healthy() {
        synchronized (lock) {
            return lastError == null;
        }
    }

    public String lastError() {
        synchronized (lock) {
            return lastError;
        }
    }

    public long consecutiveFailures() {
        synchronized (lock) {
            return consecutiveFailures;
        }
    }

    public long lastCommittedRevision() {
        return guard.lastCommitted();
    }

    /** Highest revision ever handed to {@link #submit}. */
    public long lastSubmittedRevision() {
        synchronized (lock) {
            return lastSubmittedRevision;
        }
    }

    public boolean hasPendingWork() {
        synchronized (lock) {
            return pending != null || writing;
        }
    }

    /**
     * @return a future completed once the newest submitted state is durable: nothing is pending, no
     * write is in flight and the last attempt succeeded. A write that finishes while a newer snapshot
     * is still queued does not complete it. Completes exceptionally when the newest state could not
     * be written.
     */
    public CompletableFuture<Void> flush() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        String error;
        synchronized (lock) {
            if (pending != null || writing) {
                idleWaiters.add(future);
                error = null;
            } else {
                error = lastError == null ? "" : lastError;
            }
        }
        if (error == null) {
            pump();
        } else {
            settle(List.of(future), error.isEmpty() ? null : error);
        }
        return future;
    }

    private void pump() {
        RunPersistenceSnapshot snapshot;
        List<CompletableFuture<Void>> settled = List.of();
        synchronized (lock) {
            if (writing || pending == null) {
                return;
            }
            if (pending.revision() <= guard.lastCommitted()) {
                // This revision is already durable, and a revision always denotes one content.
                pending = null;
                lastError = null;
                settled = drainWaiters();
                snapshot = null;
            } else if (!guard.begin(pending.revision())) {
                return;
            } else {
                snapshot = pending;
                pending = null;
                writing = true;
            }
        }
        if (snapshot == null) {
            settle(settled, null);
            return;
        }

        long revision = snapshot.revision();
        long now = System.currentTimeMillis();
        CompletableFuture<SnapshotStore.WriteResult> write;
        try {
            write = runner.run(() -> store.saveConsistent(snapshot, now));
        } catch (RuntimeException rejected) {
            // The executor refused the work (e.g. already shut down): a failed write, not a hang.
            write = CompletableFuture.failedFuture(rejected);
        }
        write.whenComplete((result, error) -> finish(snapshot, revision, error));
    }

    private void finish(RunPersistenceSnapshot snapshot, long revision, Throwable error) {
        boolean pumpAgain;
        List<CompletableFuture<Void>> settled = List.of();
        String settledError = null;
        synchronized (lock) {
            writing = false;
            if (error == null) {
                guard.commit(revision);
                lastError = null;
                consecutiveFailures = 0;
                // A newer state is still queued: waiters must wait for that one, not for this write.
                pumpAgain = pending != null;
                if (!pumpAgain) {
                    settled = drainWaiters();
                }
            } else {
                guard.rollback(revision);
                lastError = errorFormatter.apply(error);
                consecutiveFailures++;
                boolean superseded = pending != null && pending.revision() > revision;
                if (!superseded) {
                    // Keep the failed snapshot so exactly this state is retried.
                    pending = snapshot;
                }
                // A newer queued state replaces the failed one and deserves its own attempt. Without
                // one the failure stands until an explicit retry.
                pumpAgain = superseded;
                if (!pumpAgain) {
                    settled = drainWaiters();
                    settledError = lastError;
                }
            }
        }
        settle(settled, settledError);
        if (pumpAgain) {
            pump();
        }
    }

    private List<CompletableFuture<Void>> drainWaiters() {
        if (idleWaiters.isEmpty()) {
            return List.of();
        }
        List<CompletableFuture<Void>> waiters = List.copyOf(idleWaiters);
        idleWaiters.clear();
        return waiters;
    }

    /** Completes outside the lock, so dependent stages can never run while it is held. */
    private static void settle(List<CompletableFuture<Void>> waiters, String error) {
        for (CompletableFuture<Void> waiter : waiters) {
            if (error == null) {
                waiter.complete(null);
            } else {
                waiter.completeExceptionally(new IllegalStateException(error));
            }
        }
    }

    /** Retries the pending snapshot after a failure, e.g. from the scheduler or an admin reload. */
    public void retryPending() {
        pump();
    }

    static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
