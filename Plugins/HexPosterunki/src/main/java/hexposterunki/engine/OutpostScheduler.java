package hexposterunki.engine;

import hexposterunki.config.PosterunkiConfig;
import hexposterunki.listener.EncounterListener;
import hexposterunki.rewards.RewardService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Owns the repeating main-thread tasks: the encounter tick, the containment sweep and the
 * outstanding-reward sweep.
 *
 * <p>Started only after the bootstrap finished and only when at least one valid outpost exists, so
 * a broken configuration never produces a running scheduler. {@link #start()} always stops the
 * previous tasks first, which makes a double reload unable to leave two schedulers behind.
 */
public final class OutpostScheduler {

    /** How often outstanding reward claims are retried, in ticks (5 minutes). */
    private static final long REWARD_SWEEP_TICKS = 20L * 60L * 5L;

    private final Plugin plugin;
    private final OutpostEngine engine;
    private final EncounterListener encounterListener;
    private final RewardService rewards;
    private final Supplier<PosterunkiConfig> config;

    private BukkitTask tickTask;
    private BukkitTask containmentTask;
    private BukkitTask rewardTask;

    public OutpostScheduler(Plugin plugin, OutpostEngine engine, EncounterListener encounterListener,
                            RewardService rewards, Supplier<PosterunkiConfig> config) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.encounterListener = Objects.requireNonNull(encounterListener, "encounterListener");
        this.rewards = Objects.requireNonNull(rewards, "rewards");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void start() {
        stop();
        PosterunkiConfig.Timing timing = config.get().timing();
        long tickInterval = timing.tickIntervalTicks();
        long containmentInterval = config.get().protection().containmentCheckIntervalTicks();

        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, engine::tick, tickInterval, tickInterval);
        containmentTask = Bukkit.getScheduler().runTaskTimer(plugin, encounterListener::containEntities,
                containmentInterval, containmentInterval);
        rewardTask = Bukkit.getScheduler().runTaskTimer(plugin, rewards::deliverOutstanding,
                REWARD_SWEEP_TICKS, REWARD_SWEEP_TICKS);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (containmentTask != null) {
            containmentTask.cancel();
            containmentTask = null;
        }
        if (rewardTask != null) {
            rewardTask.cancel();
            rewardTask = null;
        }
    }

    public boolean running() {
        return tickTask != null;
    }
}
