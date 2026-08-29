package hex.events.persistence;

import hex.core.api.db.DatabaseService;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Ordered asynchronous persistence executor.
 *
 * Each logical lane is serialized independently, so unrelated players/events do
 * not share one global JDBC bottleneck. A barrier is failure-aware: it completes
 * exceptionally when any write submitted to that lane since the previous
 * barrier failed, while later operations are still allowed to continue.
 *
 * No Bukkit/world/inventory operation may be passed here.
 */
public final class PersistenceExecutor {
    public static final String GLOBAL = "global";

    private final DatabaseService database;
    private final Plugin plugin;
    private final Map<String, Lane> lanes = new HashMap<>();

    public PersistenceExecutor(DatabaseService database, Plugin plugin) {
        this.database = Objects.requireNonNull(database, "database");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public CompletableFuture<Void> write(Runnable work) { return write(GLOBAL, work); }

    public synchronized CompletableFuture<Void> write(String laneKey, Runnable work) {
        Lane lane = lane(laneKey);
        CompletableFuture<Void> next = lane.tail.handle((ignored, previousError) -> null)
                .thenCompose(ignored -> database.asyncRun(work));
        lane.tail = next.handle((ignored, error) -> null);
        lane.pending.add(next);
        return next;
    }

    public <T> CompletableFuture<T> submit(Supplier<T> work) { return submit(GLOBAL, work); }

    public synchronized <T> CompletableFuture<T> submit(String laneKey, Supplier<T> work) {
        Lane lane = lane(laneKey);
        CompletableFuture<T> next = lane.tail.handle((ignored, previousError) -> null)
                .thenCompose(ignored -> database.async(work));
        lane.tail = next.handle((ignored, error) -> null);
        lane.pending.add(next);
        return next;
    }

    /** Read after all work already submitted to this lane has finished. */
    public <T> CompletableFuture<T> read(Supplier<T> work) { return read(GLOBAL, work); }

    public synchronized <T> CompletableFuture<T> read(String laneKey, Supplier<T> work) {
        CompletableFuture<Void> tail = lane(laneKey).tail;
        return tail.thenCompose(ignored -> database.async(work));
    }

    /** Independent async work for thread-safe external providers. */
    public <T> CompletableFuture<T> io(Supplier<T> work) { return database.async(work); }

    public void fireAndForget(String operation, Runnable work) {
        fireAndForget(GLOBAL, operation, work);
    }

    public void fireAndForget(String laneKey, String operation, Runnable work) {
        write(laneKey, work).whenComplete((ignored, error) -> {
            if (error != null) plugin.getLogger().severe("Persistence error [" + operation + "]: " + rootMessage(error));
        });
    }

    /**
     * Failure-aware durability barrier for the default lane. It only covers work
     * submitted before this call and does not poison subsequent writes forever.
     */
    public CompletableFuture<Void> barrier() { return barrier(GLOBAL); }

    public synchronized CompletableFuture<Void> barrier(String laneKey) {
        Lane lane = lane(laneKey);
        if (lane.pending.isEmpty()) return lane.tail;
        CompletableFuture<?>[] snapshot = lane.pending.toArray(CompletableFuture[]::new);
        lane.pending.clear();
        return CompletableFuture.allOf(snapshot);
    }

    /** Waits for all currently known lanes. Useful during shutdown/reload. */
    public synchronized CompletableFuture<Void> barrierAll() {
        List<CompletableFuture<Void>> waits = new ArrayList<>();
        for (String key : List.copyOf(lanes.keySet())) waits.add(barrier(key));
        return CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new));
    }

    private Lane lane(String key) {
        String normalized = key == null || key.isBlank() ? GLOBAL : key;
        return lanes.computeIfAbsent(normalized, ignored -> new Lane());
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static final class Lane {
        private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
        private final List<CompletableFuture<?>> pending = new ArrayList<>();
    }
}
