package hexposterunki.persistence;

import hex.core.api.HexApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * Async facade over {@link PosterunkiRepository}.
 *
 * <p>All database work goes through {@code HexApi.db().async(...)}; the main thread never blocks.
 * Consistent run state goes through {@link SnapshotWriter}, which keeps writes ordered, retries a
 * failed write and exposes health so the engine can pause instead of running on unsaved state.
 * Reward claims of a won run are part of that consistent state (see {@link CompletionRecord}); there
 * is deliberately no separate claim insert that could succeed or fail on its own.
 *
 * <p>Side writes that carry a result determined on the main thread - the captured block states of a
 * run and the final status of a reward delivery - are kept exactly as they were determined until the
 * database confirmed them ({@link #retryPendingSideWrites()}). They are never recomputed for a retry:
 * re-reading the world would capture an already changed fortress, and re-running a delivery would hand
 * out items or console commands twice. Per key only one write is in flight, so a slow older write can
 * never overwrite a newer state.
 *
 * <p>The repository may be {@code null} when HexCore has no usable database. Every call then
 * degrades into a no-op and {@link #available()} is false, which blocks the event operation while
 * leaving the region protection alive.
 */
public final class PersistenceService {

    private final HexApi api;
    private final PosterunkiRepository repository;
    private final Logger logger;
    private final RevisionGuard revisionGuard = new RevisionGuard();
    private final SnapshotWriter snapshotWriter;

    /** Guards the pending side writes; their completions arrive on database threads. */
    private final Object sideWrites = new Object();
    private final Map<String, List<BlockSnapshotEntry>> pendingBlockSnapshots = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<?>> blockWritesInFlight = new LinkedHashMap<>();
    private final Map<String, PendingDeliveryStatus> pendingDeliveryStatuses = new LinkedHashMap<>();
    private final Set<String> deliveryWritesInFlight = new LinkedHashSet<>();
    private String lastSideWriteError;

    public PersistenceService(HexApi api, PosterunkiRepository repository, Logger logger) {
        this.api = api;
        this.repository = repository;
        this.logger = logger;
        this.snapshotWriter = repository == null ? null
                : new SnapshotWriter(repository, new AsyncRunnerAdapter(api), revisionGuard);
    }

    public boolean available() {
        return repository != null;
    }

    public PosterunkiRepository repository() {
        return repository;
    }

    public RevisionGuard revisionGuard() {
        return revisionGuard;
    }

    /** True when persistence is usable and the last write attempt succeeded. */
    public boolean healthy() {
        return snapshotWriter != null && snapshotWriter.healthy();
    }

    public String lastError() {
        return snapshotWriter == null ? "brak połączenia z bazą danych" : snapshotWriter.lastError();
    }

    public boolean hasPendingWork() {
        return snapshotWriter != null && snapshotWriter.hasPendingWork();
    }

    /** Highest revision the database confirmed; -1 before the first confirmed write. */
    public long lastCommittedRevision() {
        return revisionGuard.lastCommitted();
    }

    public void retryPendingWrites() {
        if (snapshotWriter != null) {
            snapshotWriter.retryPending();
        }
        retryPendingSideWrites();
    }

    /** Number of determined results waiting for the database: block states and delivery statuses. */
    public int pendingSideWrites() {
        synchronized (sideWrites) {
            return pendingBlockSnapshots.size() + pendingDeliveryStatuses.size();
        }
    }

    public boolean hasPendingSideWrites() {
        return pendingSideWrites() > 0;
    }

    /** Message of the last failed side write, or null when none failed since the last success. */
    public String lastSideWriteError() {
        synchronized (sideWrites) {
            return lastSideWriteError;
        }
    }

    /**
     * Main thread. Writes every determined result the database has not confirmed yet - unchanged, and
     * only one write per key at a time.
     */
    public void retryPendingSideWrites() {
        List<Runnable> attempts = new ArrayList<>();
        synchronized (sideWrites) {
            pendingBlockSnapshots.forEach((runId, entries) -> {
                if (!blockWritesInFlight.containsKey(runId)) {
                    attempts.add(() -> writeBlockSnapshot(runId, entries));
                }
            });
            pendingDeliveryStatuses.values().forEach(pending -> {
                if (!deliveryWritesInFlight.contains(pending.key())) {
                    attempts.add(() -> writeDeliveryStatus(pending));
                }
            });
        }
        attempts.forEach(Runnable::run);
    }

    // ---------------------------------------------------------------- bootstrap

    public CompletableFuture<Void> ensureTables() {
        if (repository == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("brak połączenia z bazą danych"));
        }
        return api.db().asyncRun(repository::ensureTables);
    }

    public CompletableFuture<PosterunkiRepository.LoadedState> loadAll() {
        if (repository == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("brak połączenia z bazą danych"));
        }
        return api.db().async(() -> {
            long now = System.currentTimeMillis();
            int quarantined = repository.quarantineInterruptedDeliveries(now);
            if (quarantined > 0 && logger != null) {
                logger.warning("[rewards] " + quarantined + " nagród przerwanych przy wyłączeniu serwera"
                        + " oznaczono jako wymagające decyzji administratora.");
            }
            PosterunkiRepository.LoadedState state = repository.loadAll();
            if (state.global() != null) {
                revisionGuard.adoptAtLeast(state.global().revision());
            }
            return state;
        });
    }

    // ---------------------------------------------------------------- consistent state

    /** Main thread. Queues one consistent state write. */
    public void save(RunPersistenceSnapshot snapshot) {
        if (snapshotWriter == null) {
            return;
        }
        snapshotWriter.submit(snapshot);
    }

    /**
     * Final ordered write on plugin disable. A normal logout resets kill progress, but a server
     * shutdown must not be mistaken for one, so the outstanding state is flushed before going down.
     *
     * <p>Blocks the calling (main) thread until the given snapshot - and nothing older - is confirmed
     * durable, because HexCore shuts its database executor down right after this plugin. A failed
     * write, a timeout or an unconfirmed revision is reported, never swallowed.
     *
     * @return null when the snapshot is durable, a Polish problem description otherwise
     */
    public String flushOnShutdown(RunPersistenceSnapshot snapshot, long timeoutSeconds) {
        if (snapshotWriter == null) {
            return null;
        }
        long timeout = Math.max(1L, timeoutSeconds);
        snapshotWriter.submit(snapshot);
        String problem = null;
        try {
            snapshotWriter.flush().get(timeout, TimeUnit.SECONDS);
            if (snapshotWriter.lastCommittedRevision() < snapshot.revision()) {
                problem = "zapis rewizji " + snapshot.revision() + " nie został potwierdzony";
            }
        } catch (TimeoutException exception) {
            problem = "przekroczono limit czasu zapisu (" + timeout + " s) - stan mógł nie zostać zapisany";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            problem = "przerwano oczekiwanie na zapis stanu";
        } catch (Exception exception) {
            problem = SnapshotWriter.rootMessage(exception);
        }
        String sideProblem = flushSideWrites(timeout);
        if (problem == null) {
            problem = sideProblem;
        } else if (sideProblem != null) {
            problem = problem + "; " + sideProblem;
        }
        if (problem != null && logger != null) {
            logger.severe("[db] Nie udało się zapisać stanu przy wyłączaniu: " + problem);
        }
        return problem;
    }

    /**
     * Last attempt at the determined side results before the server goes down. Whatever is still not
     * stored afterwards is reported: those block states and delivery statuses only exist in memory and
     * will not be there after the restart.
     */
    private String flushSideWrites(long timeoutSeconds) {
        long deadline = System.currentTimeMillis() + Math.max(1L, timeoutSeconds) * 1000L;
        retryPendingSideWrites();
        while (hasPendingSideWrites() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            retryPendingSideWrites();
        }
        int outstanding = pendingSideWrites();
        if (outstanding == 0) {
            return null;
        }
        String error = lastSideWriteError();
        return "nie zapisano danych pomocniczych (" + outstanding + ": stan bloków / statusy nagród)"
                + (error == null ? "" : ": " + error) + " - po restarcie nie będzie można ich odtworzyć";
    }

    // ---------------------------------------------------------------- side tables

    /**
     * Stores the block states captured for a run. The captured content is kept until the database
     * confirmed it, so a failed write is repeated with exactly these states instead of a second,
     * meanwhile changed capture of the world.
     */
    public void saveBlockSnapshot(String runId, List<BlockSnapshotEntry> entries) {
        if (repository == null) {
            return;
        }
        List<BlockSnapshotEntry> captured = List.copyOf(entries);
        boolean writeNow;
        synchronized (sideWrites) {
            pendingBlockSnapshots.put(runId, captured);
            writeNow = !blockWritesInFlight.containsKey(runId);
        }
        if (writeNow) {
            writeBlockSnapshot(runId, captured);
        }
    }

    /**
     * The block states captured for this run while they are not confirmed stored. A reset uses them
     * directly instead of reading an empty table and mistaking that for "nothing was captured".
     */
    public Optional<List<BlockSnapshotEntry>> pendingBlockSnapshot(String runId) {
        synchronized (sideWrites) {
            return Optional.ofNullable(pendingBlockSnapshots.get(runId));
        }
    }

    private void writeBlockSnapshot(String runId, List<BlockSnapshotEntry> captured) {
        CompletableFuture<Void> write = api.db().asyncRun(() -> repository.saveBlockSnapshot(runId, captured));
        synchronized (sideWrites) {
            blockWritesInFlight.put(runId, write);
        }
        write.whenComplete((ignored, error) -> {
            List<BlockSnapshotEntry> newer = null;
            synchronized (sideWrites) {
                blockWritesInFlight.remove(runId, write);
                if (error == null) {
                    lastSideWriteError = null;
                    if (pendingBlockSnapshots.get(runId) == captured) {
                        pendingBlockSnapshots.remove(runId);
                    } else {
                        newer = pendingBlockSnapshots.get(runId);
                    }
                } else {
                    lastSideWriteError = SnapshotWriter.rootMessage(error);
                }
            }
            if (error != null && logger != null) {
                logger.severe("[db] Nie udało się zapisać stanu bloków runu " + runId + ": "
                        + SnapshotWriter.rootMessage(error) + ". Zapis zostanie powtórzony.");
            }
            if (newer != null) {
                writeBlockSnapshot(runId, newer);
            }
        });
    }

    public CompletableFuture<List<BlockSnapshotEntry>> loadBlockSnapshot(String runId) {
        if (repository == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return api.db().async(() -> repository.loadBlockSnapshot(runId));
    }

    /**
     * Drops everything stored for a finished run. A block-state write that is still in flight is waited
     * for first, so a late write can never resurrect artifacts this cleanup already deleted; a write
     * that is only pending is discarded, because the run no longer needs it.
     */
    public void clearRunArtifacts(String runId) {
        if (repository == null) {
            return;
        }
        CompletableFuture<?> inFlight;
        synchronized (sideWrites) {
            pendingBlockSnapshots.remove(runId);
            inFlight = blockWritesInFlight.get(runId);
        }
        Runnable work = () -> {
            repository.clearBlockSnapshot(runId);
            repository.clearRunEntities(runId);
            repository.clearLoot(runId);
        };
        CompletableFuture<Void> cleared = inFlight == null
                ? api.db().asyncRun(work)
                : inFlight.handle((ignored, error) -> null).thenCompose(ignored -> api.db().asyncRun(work));
        cleared.exceptionally(error -> {
            if (logger != null) {
                logger.severe("[db] clearRunArtifacts nie powiodło się: " + SnapshotWriter.rootMessage(error));
            }
            return null;
        });
    }

    public CompletableFuture<Boolean> markContainerFilled(String runId, String containerId) {
        if (repository == null) {
            return CompletableFuture.completedFuture(false);
        }
        long now = System.currentTimeMillis();
        return api.db().async(() -> repository.markContainerFilled(runId, containerId, now));
    }

    public void audit(String runId, String outpostId, String event, String actor, String data) {
        long now = System.currentTimeMillis();
        fireAndForget("audit", () -> repository.audit(runId, outpostId, event, actor, data, now));
    }

    // ---------------------------------------------------------------- reward claims

    public CompletableFuture<Optional<RewardClaim>> beginDelivery(String key) {
        if (repository == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        long now = System.currentTimeMillis();
        return api.db().async(() -> repository.beginDelivery(key, now));
    }

    /**
     * Stores the outcome of a delivery attempt: the target status and how many payload components were
     * confirmed handed over. The outcome is kept until the database confirmed it, so a failed write is
     * repeated instead of leaving the claim stuck in {@code DELIVERING} until the next restart - and the
     * already executed items or console commands are never run again for it. A newer outcome (a second
     * attempt, an administrator's decision) replaces a pending one and is never overwritten by it.
     */
    public void finishDelivery(String key, RewardClaim.Status status, int progress, String error) {
        if (repository == null) {
            return;
        }
        PendingDeliveryStatus pending = new PendingDeliveryStatus(key, status, progress, error);
        boolean writeNow;
        synchronized (sideWrites) {
            pendingDeliveryStatuses.put(key, pending);
            writeNow = !deliveryWritesInFlight.contains(key);
        }
        if (writeNow) {
            writeDeliveryStatus(pending);
        }
    }

    private void writeDeliveryStatus(PendingDeliveryStatus pending) {
        long now = System.currentTimeMillis();
        synchronized (sideWrites) {
            deliveryWritesInFlight.add(pending.key());
        }
        api.db().asyncRun(() -> repository.finishDelivery(pending.key(), pending.status(), pending.progress(),
                pending.error(), now)).whenComplete((ignored, error) -> {
            PendingDeliveryStatus newer = null;
            synchronized (sideWrites) {
                deliveryWritesInFlight.remove(pending.key());
                if (error == null) {
                    lastSideWriteError = null;
                    if (pendingDeliveryStatuses.get(pending.key()) == pending) {
                        pendingDeliveryStatuses.remove(pending.key());
                    } else {
                        newer = pendingDeliveryStatuses.get(pending.key());
                    }
                } else {
                    lastSideWriteError = SnapshotWriter.rootMessage(error);
                }
            }
            if (error != null && logger != null) {
                logger.severe("[db] Nie udało się zapisać statusu nagrody " + pending.key() + ": "
                        + SnapshotWriter.rootMessage(error) + ". Zapis zostanie powtórzony.");
            }
            if (newer != null) {
                writeDeliveryStatus(newer);
            }
        });
    }

    /** One determined delivery outcome waiting for the database. */
    private record PendingDeliveryStatus(String key, RewardClaim.Status status, int progress, String error) {
    }

    public CompletableFuture<List<RewardClaim>> loadClaims(RewardClaim.Status status) {
        if (repository == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return api.db().async(() -> repository.loadClaims(status));
    }

    public CompletableFuture<List<RewardClaim>> loadClaimsForPlayer(UUID playerId, RewardClaim.Status status) {
        if (repository == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return api.db().async(() -> repository.loadClaimsForPlayer(playerId, status));
    }

    // ---------------------------------------------------------------- helpers

    private void fireAndForget(String what, Runnable work) {
        if (repository == null) {
            return;
        }
        api.db().asyncRun(work).exceptionally(error -> {
            if (logger != null) {
                logger.severe("[db] " + what + " nie powiodło się: " + SnapshotWriter.rootMessage(error));
            }
            return null;
        });
    }

    /** Bridges {@code HexApi.db().async} into the writer without leaking HexCore types into it. */
    private record AsyncRunnerAdapter(HexApi api) implements SnapshotWriter.AsyncRunner {
        @Override
        public <T> CompletableFuture<T> run(java.util.function.Supplier<T> work) {
            return api.db().async(work);
        }
    }
}
