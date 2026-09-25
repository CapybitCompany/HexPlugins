package hexposterunki.engine;

import hexposterunki.config.Cuboid;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Budgeted, multi-tick block scan on the main thread.
 *
 * <p>Region scans are linear in the region volume. Doing one synchronously froze the server for a
 * large fortress, and moving it to another thread is not an option because Bukkit world access is
 * main-thread only. So the scan is split: a fixed number of blocks per tick, resumable, with a
 * hard volume cap, and a completion callback that tells the caller whether it finished or was cut
 * short.
 */
public final class RegionScanner {

    @FunctionalInterface
    public interface BlockVisitor {
        void visit(int x, int y, int z);
    }

    /**
     * @param visited   blocks actually looked at
     * @param completed true when the whole region was covered
     * @param truncated true when the volume cap stopped the scan early
     */
    public record Result(long visited, boolean completed, boolean truncated) {
    }

    private final Plugin plugin;
    private BukkitTask task;
    private RegionCursor cursor;

    public RegionScanner(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public boolean running() {
        return task != null;
    }

    /**
     * Starts a scan. Any scan already running on this instance is cancelled first.
     *
     * @param budgetPerTick blocks visited per tick; must be positive
     * @param maxVolume     hard cap on visited blocks; 0 means "no cap"
     */
    public void start(Cuboid region, long budgetPerTick, long maxVolume,
                      BlockVisitor visitor, Consumer<Result> onDone) {
        cancel();
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(visitor, "visitor");

        cursor = new RegionCursor(region);

        long budget = Math.max(1L, budgetPerTick);
        long cap = maxVolume <= 0L ? Long.MAX_VALUE : maxVolume;

        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long processed = 0L;
            while (processed < budget) {
                if (cursor.finished()) {
                    finish(onDone, new Result(cursor.visited(), true, false));
                    return;
                }
                if (cursor.visited() >= cap) {
                    finish(onDone, new Result(cursor.visited(), false, true));
                    return;
                }
                visitor.visit(cursor.x(), cursor.y(), cursor.z());
                processed++;
                cursor.advance();
            }
        }, 1L, 1L);
    }

    private void finish(Consumer<Result> onDone, Result result) {
        cancel();
        if (onDone != null) {
            onDone.accept(result);
        }
    }

    public void cancel() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
