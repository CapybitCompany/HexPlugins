package hexabovename.service;

import hexabovename.repository.DisplayTextRepository;
import hexabovename.repository.PlayerSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

public final class DisplayTextCacheService {

    private final Plugin plugin;
    private final Logger logger;
    private final DisplayTextRepository repository;
    private final ExecutorService executor;
    private final AtomicReference<Map<UUID, String>> cacheRef = new AtomicReference<>(Map.of());
    private final AtomicBoolean refreshRunning = new AtomicBoolean(false);
    private BukkitTask task;

    public DisplayTextCacheService(
            Plugin plugin,
            Logger logger,
            DisplayTextRepository repository
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.repository = repository;
        this.executor = Executors.newSingleThreadExecutor(new DaemonThreadFactory());
    }

    public void start(long refreshIntervalTicks) {
        stopTask();
        requestRefresh();
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::requestRefresh, refreshIntervalTicks, refreshIntervalTicks);
    }

    public void stop() {
        stopTask();
        executor.shutdownNow();
        cacheRef.set(Map.of());
    }

    public void requestRefresh() {
        if (!refreshRunning.compareAndSet(false, true)) {
            return;
        }

        List<PlayerSnapshot> snapshot = createSnapshot();
        if (snapshot.isEmpty()) {
            cacheRef.set(Map.of());
            refreshRunning.set(false);
            return;
        }

        CompletableFuture
                .supplyAsync(() -> load(snapshot), executor)
                .whenComplete((loaded, error) -> {
                    if (error != null) {
                        logger.warning("Nie udało się odświeżyć cache display text: " + error.getMessage());
                    } else if (loaded != null) {
                        cacheRef.set(Map.copyOf(loaded));
                    }
                    refreshRunning.set(false);
                });
    }

    public String getText(UUID uuid) {
        return cacheRef.get().get(uuid);
    }

    private Map<UUID, String> load(List<PlayerSnapshot> snapshot) {
        try {
            return repository.loadDisplayTexts(snapshot);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private List<PlayerSnapshot> createSnapshot() {
        List<PlayerSnapshot> snapshot = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            snapshot.add(new PlayerSnapshot(player.getUniqueId(), player.getName()));
        }
        return snapshot;
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "HexAboveName-Storage");
            thread.setDaemon(true);
            return thread;
        }
    }
}
