package hex.areaeffects.service;

import hex.areaeffects.AreaEffectsPlugin;
import hex.areaeffects.config.AreaEffectsConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ThreadLocalRandom;

public class AreaEffectsService {

    private final AreaEffectsPlugin plugin;
    private AreaEffectsConfig config;

    private BukkitTask task;
    private boolean running;
    private int elapsed;

    public AreaEffectsService(AreaEffectsPlugin plugin, AreaEffectsConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void updateConfig(AreaEffectsConfig config) {
        this.config = config;
    }

    public boolean isRunning() {
        return running;
    }

    public String status() {
        if (!running) {
            return "IDLE";
        }
        return "RUNNING " + elapsed + "/" + config.durationTicks() + " ticks";
    }

    public void start() {
        if (running) {
            return;
        }

        World world = Bukkit.getWorld(config.world());
        if (world == null) {
            plugin.getLogger().warning("World not found: " + config.world());
            return;
        }

        running = true;
        elapsed = 0;
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(world), 1L, config.stepTicks());
        Bukkit.broadcastMessage(config.msgStarted());
    }

    public void stop() {
        running = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        Bukkit.broadcastMessage(config.msgStopped());
    }

    private void tick(World world) {
        if (!running) {
            return;
        }

        elapsed += config.stepTicks();

        for (int i = 0; i < config.pointsPerStep(); i++) {
            Location point = randomPoint(world);

            world.spawnParticle(
                    config.particle(),
                    point,
                    config.countPerPoint(),
                    config.offsetX(),
                    config.offsetY(),
                    config.offsetZ(),
                    config.speed()
            );

            if (config.explosionEnabled() && ThreadLocalRandom.current().nextDouble() <= config.explosionChance()) {
                world.createExplosion(point.getX(), point.getY(), point.getZ(), config.explosionPower(), false, false);
            }

            if (config.soundEnabled()) {
                world.playSound(point, config.sound(), config.volume(), config.pitch());
            }
        }

        if (elapsed >= config.durationTicks()) {
            running = false;
            if (task != null) {
                task.cancel();
                task = null;
            }
            Bukkit.broadcastMessage(config.msgFinished());
        }
    }

    private Location randomPoint(World world) {
        ThreadLocalRandom r = ThreadLocalRandom.current();

        int x = r.nextInt(config.x1(), config.x2() + 1);
        int z = r.nextInt(config.z1(), config.z2() + 1);
        int y = r.nextInt(config.yMin(), config.yMax() + 1);

        return new Location(world, x + 0.5D, y + 0.5D, z + 0.5D);
    }
}

