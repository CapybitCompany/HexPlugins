package hex.areaeffects.config;

import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class AreaEffectsConfig {

    private final String world;
    private final int x1;
    private final int z1;
    private final int x2;
    private final int z2;
    private final int yMin;
    private final int yMax;

    private final int durationTicks;
    private final int stepTicks;

    private final Particle particle;
    private final int pointsPerStep;
    private final int countPerPoint;
    private final double offsetX;
    private final double offsetY;
    private final double offsetZ;
    private final double speed;

    private final boolean explosionEnabled;
    private final double explosionChance;
    private final float explosionPower;

    private final boolean soundEnabled;
    private final Sound sound;
    private final float volume;
    private final float pitch;

    private final String msgStarted;
    private final String msgStopped;
    private final String msgFinished;

    public AreaEffectsConfig(JavaPlugin plugin) {
        FileConfiguration cfg = plugin.getConfig();

        this.world = cfg.getString("world", "world");

        this.x1 = Math.min(cfg.getInt("area.x1", -10), cfg.getInt("area.x2", 10));
        this.z1 = Math.min(cfg.getInt("area.z1", -10), cfg.getInt("area.z2", 10));
        this.x2 = Math.max(cfg.getInt("area.x1", -10), cfg.getInt("area.x2", 10));
        this.z2 = Math.max(cfg.getInt("area.z1", -10), cfg.getInt("area.z2", 10));
        this.yMin = Math.min(cfg.getInt("area.y-min", 50), cfg.getInt("area.y-max", 70));
        this.yMax = Math.max(cfg.getInt("area.y-min", 50), cfg.getInt("area.y-max", 70));

        this.durationTicks = Math.max(20, cfg.getInt("duration-seconds", 20) * 20);
        this.stepTicks = Math.max(1, cfg.getInt("step-ticks", 2));

        this.particle = parseParticle(cfg.getString("particles.type", "EXPLOSION"));
        this.pointsPerStep = Math.max(1, cfg.getInt("particles.points-per-step", 4));
        this.countPerPoint = Math.max(1, cfg.getInt("particles.count-per-point", 2));
        this.offsetX = cfg.getDouble("particles.offset-x", 0.25D);
        this.offsetY = cfg.getDouble("particles.offset-y", 0.25D);
        this.offsetZ = cfg.getDouble("particles.offset-z", 0.25D);
        this.speed = cfg.getDouble("particles.speed", 0.02D);

        this.explosionEnabled = cfg.getBoolean("explosion.enabled", true);
        this.explosionChance = Math.max(0.0D, Math.min(1.0D, cfg.getDouble("explosion.chance-per-point", 0.1D)));
        this.explosionPower = (float) cfg.getDouble("explosion.power", 0.0D);

        this.soundEnabled = cfg.getBoolean("sound.enabled", true);
        this.sound = parseSound(cfg.getString("sound.type", "ENTITY_GENERIC_EXPLODE"));
        this.volume = (float) cfg.getDouble("sound.volume", 1.0D);
        this.pitch = (float) cfg.getDouble("sound.pitch", 1.0D);

        this.msgStarted = color(cfg.getString("messages.started", "&6[FX] &fStarted."));
        this.msgStopped = color(cfg.getString("messages.stopped", "&6[FX] &fStopped."));
        this.msgFinished = color(cfg.getString("messages.finished", "&6[FX] &fFinished."));
    }

    private String color(String input) {
        return input.replace('&', '§');
    }

    private Particle parseParticle(String name) {
        try {
            return Particle.valueOf(name.toUpperCase());
        } catch (Exception ignored) {
            return Particle.EXPLOSION;
        }
    }

    private Sound parseSound(String name) {
        try {
            return Sound.valueOf(name.toUpperCase());
        } catch (Exception ignored) {
            return Sound.ENTITY_GENERIC_EXPLODE;
        }
    }

    public String world() { return world; }
    public int x1() { return x1; }
    public int z1() { return z1; }
    public int x2() { return x2; }
    public int z2() { return z2; }
    public int yMin() { return yMin; }
    public int yMax() { return yMax; }

    public int durationTicks() { return durationTicks; }
    public int stepTicks() { return stepTicks; }

    public Particle particle() { return particle; }
    public int pointsPerStep() { return pointsPerStep; }
    public int countPerPoint() { return countPerPoint; }
    public double offsetX() { return offsetX; }
    public double offsetY() { return offsetY; }
    public double offsetZ() { return offsetZ; }
    public double speed() { return speed; }

    public boolean explosionEnabled() { return explosionEnabled; }
    public double explosionChance() { return explosionChance; }
    public float explosionPower() { return explosionPower; }

    public boolean soundEnabled() { return soundEnabled; }
    public Sound sound() { return sound; }
    public float volume() { return volume; }
    public float pitch() { return pitch; }

    public String msgStarted() { return msgStarted; }
    public String msgStopped() { return msgStopped; }
    public String msgFinished() { return msgFinished; }
}

