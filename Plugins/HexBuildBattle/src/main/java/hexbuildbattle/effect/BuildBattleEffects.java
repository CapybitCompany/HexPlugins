package hexbuildbattle.effect;

import hexbuildbattle.arena.Arena;
import hexbuildbattle.config.ConfigParsers;
import hexbuildbattle.config.ConfigService;
import hexbuildbattle.config.PluginConfig;
import hexbuildbattle.game.GameTaskRegistry;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.HashSet;

public final class BuildBattleEffects {

    private final JavaPlugin plugin;
    private final ConfigService configService;
    private final GameTaskRegistry taskRegistry;
    private final Random random = new Random();
    private final Set<String> goatTasks = new HashSet<>();
    private int goatEffectCounter;

    public BuildBattleEffects(JavaPlugin plugin, ConfigService configService, GameTaskRegistry taskRegistry) {
        this.plugin = plugin;
        this.configService = configService;
        this.taskRegistry = taskRegistry;
    }

    public void playGoatEffect(Arena arena, Collection<Player> viewers) {
        int durationTicks = Math.min(300, Math.max(20, configService.ratings().getInt("goat-effect.duration-seconds", 6) * 20));
        int intervalTicks = Math.max(1, configService.ratings().getInt("goat-effect.interval-ticks", 2));
        int particleCount = Math.max(1, Math.min(220, configService.ratings().getInt("goat-effect.particle-count", 45)));
        Particle particle = ConfigParsers.particle(
                configService.ratings().getString("goat-effect.particle"),
                Particle.END_ROD,
                plugin.getLogger(),
                "ratings.yml:goat-effect.particle"
        );
        String taskKey = "goat-effect-" + (++goatEffectCounter);
        goatTasks.add(taskKey);
        final int[] ticksLived = {0};
        taskRegistry.runRepeating(taskKey, () -> {
            Location center = new Location(
                    arena.buildRegion().world(),
                    arena.judgingCenter().getX(),
                    Math.min(arena.moduleRegion().maxY() - 1.0D, arena.buildRegion().maxY() + 4.0D),
                    arena.judgingCenter().getZ()
            );
            for (Player viewer : viewers) {
                for (int i = 0; i < particleCount; i++) {
                    viewer.spawnParticle(particle, randomBuildLocation(arena, 2.0D, center.getY()), 1,
                            0.08D, 0.12D, 0.08D, 0.01D);
                }
                viewer.spawnParticle(Particle.FIREWORK, center, 4, 5.5D, 2.2D, 5.5D, 0.03D);
            }

            ticksLived[0] += intervalTicks;
            if (ticksLived[0] >= durationTicks) {
                goatTasks.remove(taskKey);
                taskRegistry.cancel(taskKey);
            }
        }, 0L, intervalTicks);
    }

    public void playSnowEffect(Arena arena, Player viewer) {
        PluginConfig.SnowEffectSettings settings = configService.config().snowEffect();
        Location base = viewer.getLocation().add(0.0D, settings.yOffset(), 0.0D);
        viewer.spawnParticle(
                settings.particle(),
                base,
                settings.particleCount(),
                settings.radius(),
                settings.height(),
                settings.radius(),
                settings.speed()
        );
    }

    public void cleanupGoatEffects() {
        for (String taskKey : List.copyOf(goatTasks)) {
            taskRegistry.cancel(taskKey);
        }
        goatTasks.clear();
    }

    public void playResultsEffect(Arena arena, Collection<Player> viewers) {
        Location center = arena.judgingCenter().clone().add(0.0D, 2.5D, 0.0D);
        for (Player viewer : viewers) {
            viewer.spawnParticle(Particle.FIREWORK, center, 120, 8.0D, 5.0D, 8.0D, 0.06D);
            viewer.spawnParticle(Particle.END_ROD, center, 80, 7.0D, 4.0D, 7.0D, 0.04D);
            configService.config().sounds().countdownStart().play(viewer);
        }
        playWinnerFireworks(arena);
    }

    private void playWinnerFireworks(Arena arena) {
        String taskKey = "winner-fireworks-" + (++goatEffectCounter);
        goatTasks.add(taskKey);
        final int[] volleys = {0};
        taskRegistry.runRepeating(taskKey, () -> {
            for (int i = 0; i < 3; i++) {
                spawnWinnerFirework(arena);
            }
            volleys[0]++;
            if (volleys[0] >= 8) {
                goatTasks.remove(taskKey);
                taskRegistry.cancel(taskKey);
            }
        }, 0L, 10L);
    }

    private void spawnWinnerFirework(Arena arena) {
        Location location = randomBuildLocation(arena, 2.0D, arena.judgingCenter().getY() + 3.0D);
        arena.buildRegion().world().spawn(location, Firework.class, firework -> {
            FireworkMeta meta = firework.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(randomFireworkType())
                    .withColor(randomFireworkColor(), randomFireworkColor())
                    .withFade(Color.WHITE)
                    .trail(true)
                    .flicker(true)
                    .build());
            meta.setPower(1 + random.nextInt(2));
            firework.setFireworkMeta(meta);
        });
    }

    private FireworkEffect.Type randomFireworkType() {
        FireworkEffect.Type[] types = {
                FireworkEffect.Type.BALL_LARGE,
                FireworkEffect.Type.BURST,
                FireworkEffect.Type.STAR
        };
        return types[random.nextInt(types.length)];
    }

    private Color randomFireworkColor() {
        Color[] colors = {
                Color.AQUA,
                Color.FUCHSIA,
                Color.LIME,
                Color.ORANGE,
                Color.YELLOW,
                Color.RED
        };
        return colors[random.nextInt(colors.length)];
    }

    private Location randomBuildLocation(Arena arena, double ySpread, double yBase) {
        double x = arena.buildRegion().minX() + 0.5D
                + random.nextInt(arena.buildRegion().maxX() - arena.buildRegion().minX() + 1);
        double y = Math.min(arena.moduleRegion().maxY() - 0.5D, yBase + random.nextDouble() * ySpread);
        double z = arena.buildRegion().minZ() + 0.5D
                + random.nextInt(arena.buildRegion().maxZ() - arena.buildRegion().minZ() + 1);
        return new Location(arena.buildRegion().world(), x, y, z);
    }
}
