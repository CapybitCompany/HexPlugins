package hex.events.schedule;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

public final class EventScheduler {
    private final Plugin plugin;
    private final Clock clock;
    private final Consumer<ScheduledTransition> handler;
    private final PriorityQueue<ScheduledTransition> queue = new PriorityQueue<>();
    private final Set<String> keys = new HashSet<>();
    private BukkitTask task;

    public EventScheduler(Plugin plugin, Clock clock, Consumer<ScheduledTransition> handler) {
        this.plugin = plugin;
        this.clock = clock;
        this.handler = handler;
    }

    public void replace(Collection<ScheduledTransition> transitions, long periodTicks) {
        stop();
        queue.clear();
        keys.clear();
        add(transitions);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, Math.max(1L, periodTicks));
    }

    public void add(Collection<ScheduledTransition> transitions) {
        for (ScheduledTransition transition : transitions) {
            if (keys.add(transition.key())) queue.add(transition);
        }
    }

    private void tick() {
        Instant now = clock.instant();
        int guard = 0;
        while (!queue.isEmpty() && !queue.peek().at().isAfter(now) && guard++ < 500) {
            ScheduledTransition transition = queue.poll();
            keys.remove(transition.key());
            try { handler.accept(transition); }
            catch (Throwable throwable) { plugin.getLogger().severe("Błąd transition " + transition + ": " + throwable); }
        }
        if (guard >= 500) plugin.getLogger().warning("Scheduler transition guard reached; remaining transitions zostaną obsłużone w kolejnym ticku.");
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
    }

    public int queuedTransitions() { return queue.size(); }
    public boolean containsKey(String key) { return keys.contains(key); }
}
