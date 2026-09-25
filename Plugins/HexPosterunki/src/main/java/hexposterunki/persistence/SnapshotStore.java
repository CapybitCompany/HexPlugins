package hexposterunki.persistence;

/**
 * Database boundary used by {@link SnapshotWriter}.
 *
 * <p>Extracted so the write-ordering and retry behaviour can be exercised without a database,
 * while the production implementation ({@link PosterunkiRepository}) stays the only place that
 * knows SQL.
 */
public interface SnapshotStore {

    /**
     * Writes the whole snapshot in one transaction.
     *
     * @return {@link WriteResult#WRITTEN} when the snapshot landed, {@link WriteResult#STALE} when
     * the stored revision is already newer and the write was deliberately skipped.
     * @throws RuntimeException when the transaction failed; nothing may have been written.
     */
    WriteResult saveConsistent(RunPersistenceSnapshot snapshot, long nowMillis);

    enum WriteResult {
        WRITTEN,
        STALE
    }
}
