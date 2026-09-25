package hexposterunki.persistence;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;

/**
 * Repeats the persistence side writes that are still waiting for the database.
 *
 * <p>Deliberately tied to the persistence service, not to the event operation. Determined results -
 * the captured block states of a run and the final status of a reward delivery - have to reach the
 * database even when no event may run: a failed run, {@code enabled: false} or a startup blocker all
 * stop the event scheduler, while rewards earned earlier are still handed over when their player
 * joins. Before this task existed, such a status stayed {@code DELIVERING} until the next restart.
 *
 * <p>The sweep only hands the already determined write back to {@link PersistenceService}; it never
 * executes items or console commands again, never touches the run state and never releases the event
 * operation. Nothing blocks the main thread: the write itself runs on a database thread.
 */
public final class SideWriteRetryTask {

    /** One sweep per second - often enough to settle quickly, rare enough not to hammer a broken database. */
    public static final long DEFAULT_INTERVAL_TICKS = 20L;

    private final Plugin plugin;
    private final PersistenceService persistence;
    private final long intervalTicks;

    private BukkitTask task;
    private long sweeps;

    public SideWriteRetryTask(Plugin plugin, PersistenceService persistence) {
        this(plugin, persistence, DEFAULT_INTERVAL_TICKS);
    }

    public SideWriteRetryTask(Plugin plugin, PersistenceService persistence, long intervalTicks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.intervalTicks = Math.max(1L, intervalTicks);
    }

    /**
     * Starts the sweep unless it already runs, so a repeated bootstrap or reload cannot leave two
     * tasks behind. Without a usable database there is nothing to retry and no task is started.
     */
    public void start() {
        if (task != null || !persistence.available()) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public boolean running() {
        return task != null;
    }

    /** Number of sweeps that actually ran; used by the lifecycle tests to prove there is only one task. */
    public long sweeps() {
        return sweeps;
    }

    private void sweep() {
        sweeps++;
        if (persistence.hasPendingSideWrites()) {
            persistence.retryPendingSideWrites();
        }
    }
}
