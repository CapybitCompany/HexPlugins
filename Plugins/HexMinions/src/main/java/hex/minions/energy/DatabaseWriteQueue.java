package hex.minions.energy;

import hex.core.api.HexApi;
import org.bukkit.plugin.Plugin;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Write-behind kolejka dla trwałości systemu energii. SQL jest wykonywany asynchronicznie i tylko przy zmianach
 * topologii/stanu, nigdy przy zwykłym ticku energetycznym.
 */
public final class DatabaseWriteQueue {
    private final Plugin plugin;
    private final HexApi hex;
    private final CableRepository cableRepository;
    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    public DatabaseWriteQueue(Plugin plugin) {
        this(plugin, null, null);
    }

    public DatabaseWriteQueue(Plugin plugin, HexApi hex, CableRepository cableRepository) {
        this.plugin = plugin;
        this.hex = hex;
        this.cableRepository = cableRepository;
    }

    public void enqueueInsertCable(CableSegment segment) {
        if (segment != null && cableRepository != null) queue.add(() -> cableRepository.insertCable(segment));
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

    public void flushAsync() {
        if (queue.isEmpty() || !flushing.compareAndSet(false, true)) return;
        Runnable flush = () -> {
            try {
                Runnable op;
                while ((op = queue.poll()) != null) {
                    try { op.run(); } catch (Throwable throwable) { plugin.getLogger().warning("Energy DB queue op failed: " + throwable.getMessage()); }
                }
            } finally {
                flushing.set(false);
                if (!queue.isEmpty()) flushAsync();
            }
        };
        if (hex != null) hex.db().asyncRun(flush);
        else org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, flush);
    }
}
