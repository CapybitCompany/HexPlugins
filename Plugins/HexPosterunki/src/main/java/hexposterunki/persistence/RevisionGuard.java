package hexposterunki.persistence;

/**
 * Keeps asynchronous state writes ordered and makes a failed write retryable.
 *
 * <p>A snapshot is produced on the main thread and written on a database thread. The guard has
 * three states per revision:
 * <ul>
 *   <li>{@link #begin(long)} claims a revision. It is refused for a revision that is not newer
 *       than the last committed one, and while another write is still in flight.</li>
 *   <li>{@link #commit(long)} marks the revision durable - only after the database actually
 *       confirmed the write.</li>
 *   <li>{@link #rollback(long)} releases the claim without marking it durable, so exactly the
 *       same revision can be written again after a transient failure.</li>
 * </ul>
 *
 * <p>The earlier version marked a revision as accepted before the write ran, which turned a failed
 * write into permanently lost state.
 */
public final class RevisionGuard {

    private long lastCommitted = -1L;
    private long inFlight = -1L;

    /** @return true when the caller owns the write slot for this revision. */
    public synchronized boolean begin(long revision) {
        if (revision <= lastCommitted) {
            return false;
        }
        if (inFlight >= 0L) {
            return false;
        }
        inFlight = revision;
        return true;
    }

    public synchronized void commit(long revision) {
        if (revision > lastCommitted) {
            lastCommitted = revision;
        }
        if (inFlight == revision) {
            inFlight = -1L;
        }
    }

    public synchronized void rollback(long revision) {
        if (inFlight == revision) {
            inFlight = -1L;
        }
    }

    public synchronized long lastCommitted() {
        return lastCommitted;
    }

    public synchronized boolean isWriting() {
        return inFlight >= 0L;
    }

    /** Raises the watermark after recovery loaded a persisted revision; never lowers it. */
    public synchronized void adoptAtLeast(long revision) {
        if (revision > lastCommitted) {
            lastCommitted = revision;
        }
    }

    public synchronized void reset() {
        lastCommitted = -1L;
        inFlight = -1L;
    }
}
