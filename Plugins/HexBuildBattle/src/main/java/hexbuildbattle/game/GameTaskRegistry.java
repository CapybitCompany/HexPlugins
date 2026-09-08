package hexbuildbattle.game;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;

public final class GameTaskRegistry {

    private final JavaPlugin plugin;
    private final Map<String, BukkitTask> tasks = new HashMap<>();

    public GameTaskRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void runRepeating(String key, Runnable runnable, long delayTicks, long periodTicks) {
        cancel(key);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks);
        tasks.put(key, task);
    }

    public void runLater(String key, Runnable runnable, long delayTicks) {
        cancel(key);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            tasks.remove(key);
            runnable.run();
        }, delayTicks);
        tasks.put(key, task);
    }

    public void cancel(String key) {
        BukkitTask existing = tasks.remove(key);
        if (existing != null) {
            existing.cancel();
        }
    }

    public void cancelAll() {
        for (BukkitTask task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
    }
}
