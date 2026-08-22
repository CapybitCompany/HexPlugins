package hex.minions.energy;

import hex.core.api.HexApi;
import org.bukkit.plugin.Plugin;

import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Write-behind kolejka dla trwałości systemu energii. SQL jest wykonywany asynchronicznie i tylko przy zmianach
 * topologii/stanu, nigdy przy zwykłym ticku energetycznym.
 *
 * Town purge uses a persistent in-memory write fence. Once a town is blocked, queued cable INSERTs for that town
 * are discarded both when enqueued and again immediately before execution. purge then drains the current queue
 * before its final DELETE, so an older write cannot resurrect a cable after cleanup.
 */
public final class DatabaseWriteQueue {
    private final Plugin plugin;
    private final HexApi hex;
    private final CableRepository cableRepository;
    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final Set<UUID> blockedTowns = ConcurrentHashMap.newKeySet();
    private boolean flushing;
    private CompletableFuture<Void> currentFlush = CompletableFuture.completedFuture(null);

    public DatabaseWriteQueue(Plugin plugin) {
        this(plugin, null, null);
    }

    public DatabaseWriteQueue(Plugin plugin, HexApi hex, CableRepository cableRepository) {
        this.plugin = plugin;
        this.hex = hex;
        this.cableRepository = cableRepository;
    }

    public void blockTown(UUID townUuid) {
        if (townUuid != null) blockedTowns.add(townUuid);
    }

    public boolean isTownBlocked(UUID townUuid) {
        return townUuid != null && blockedTowns.contains(townUuid);
    }

    /**
     * @return true when the placement was accepted into the persistence queue; false when a town purge fence rejected it.
     */
    public boolean enqueueInsertCable(CableSegment segment) {
        if (segment == null || cableRepository == null) return false;
        UUID townUuid = segment.townUuid();
        if (isTownBlocked(townUuid)) return false;
        queue.add(() -> {
            if (!isTownBlocked(townUuid)) cableRepository.insertCable(segment);
        });
        return true;
    }

    public void enqueueDeleteCable(UUID cableId) {
        if (cableId != null && cableRepository != null) queue.add(() -> cableRepository.deleteCable(cableId));
    }

    public void enqueueUpdateCableNetwork(UUID cableId, UUID networkId) {
        if (cableId != null && cableRepository != null) queue.add(() -> cableRepository.updateCableNetwork(cableId, networkId));
    }

    public void enqueueInsertMachine(Object machine) { }
    public void enqueueDeleteMachine(UUID machineId) { }
    public void enqueueUpdateMachineState(Object machine) { }

    public int size() { return queue.size(); }

    /** Flushes all currently queued operations and any operations appended while this flush is running. */
    public synchronized CompletableFuture<Void> flushAsync() {
        if (flushing) return currentFlush;
        if (queue.isEmpty()) return CompletableFuture.completedFuture(null);

        flushing = true;
        CompletableFuture<Void> marker = new CompletableFuture<>();
        currentFlush = marker;
        Runnable flush = () -> {
            Throwable failure = null;
            try {
                Runnable op;
                while ((op = queue.poll()) != null) {
                    try {
                        op.run();
                    } catch (Throwable throwable) {
                        plugin.getLogger().warning("Energy DB queue op failed: " + throwable.getMessage());
                        if (failure == null) failure = throwable;
                    }
                }
            } finally {
                synchronized (DatabaseWriteQueue.this) {
                    flushing = false;
                }
            }

            final Throwable firstFailure = failure;
            CompletableFuture<Void> tail;
            synchronized (DatabaseWriteQueue.this) {
                tail = queue.isEmpty() ? CompletableFuture.completedFuture(null) : flushAsync();
            }
            tail.whenComplete((ignored, tailError) -> {
                Throwable error = firstFailure != null ? firstFailure : tailError;
                if (error == null) marker.complete(null);
                else marker.completeExceptionally(error);
            });
        };

        if (hex != null) {
            hex.db().asyncRun(flush).exceptionally(error -> {
                synchronized (DatabaseWriteQueue.this) { flushing = false; }
                marker.completeExceptionally(error);
                return null;
            });
        } else {
            org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, flush);
        }
        return marker;
    }

    /** Wait point used by destructive town cleanup before its final repository DELETE. */
    public CompletableFuture<Void> drainAsync() {
        return flushAsync();
    }
}
