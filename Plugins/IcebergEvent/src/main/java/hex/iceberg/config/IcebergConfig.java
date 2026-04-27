package hex.iceberg.config;

import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

public class IcebergConfig {

    private final String world;

    private final int startX;
    private final int startY;
    private final int startZ;

    private final int targetX;
    private final int targetY;
    private final int targetZ;

    private final int movementDurationTicks;
    private final int movementStepTicks;
    private final int removeDelayTicks;

    private final int radiusX;
    private final int radiusY;
    private final int radiusZ;

    private final Material coreMaterial;
    private final Material shellMaterial;
    private final Material topMaterial;

    // Spike
    private final boolean spikeEnabled;
    private final double spikeHeightMultiplier;
    private final int spikeRadius;

    // Iceburst
    private final boolean iceburstEnabled;
    private final int iceburstBlockCount;
    private final double iceburstMinVelocityY;
    private final double iceburstMaxVelocityY;
    private final double iceburstSpreadXZ;
    private final int iceburstRemoveAfterTicks;

    private final String msgStarted;
    private final String msgStopped;
    private final String msgArrived;
    private final String msgRemoved;

    public IcebergConfig(JavaPlugin plugin) {
        var cfg = plugin.getConfig();

        this.world = cfg.getString("world", "world");

        this.startX = cfg.getInt("start.x", 0);
        this.startY = cfg.getInt("start.y", 64);
        this.startZ = cfg.getInt("start.z", 0);

        this.targetX = cfg.getInt("target.x", 40);
        this.targetY = cfg.getInt("target.y", 64);
        this.targetZ = cfg.getInt("target.z", 40);

        this.movementDurationTicks = Math.max(20, cfg.getInt("movement-duration-seconds", 120) * 20);
        this.movementStepTicks = Math.max(1, cfg.getInt("movement-step-ticks", 4));
        this.removeDelayTicks = Math.max(0, cfg.getInt("remove-after-arrival-seconds", 10) * 20);

        this.radiusX = Math.max(2, cfg.getInt("shape.radius-x", 6));
        this.radiusY = Math.max(2, cfg.getInt("shape.radius-y", 4));
        this.radiusZ = Math.max(2, cfg.getInt("shape.radius-z", 6));

        this.coreMaterial = parseMaterial(cfg.getString("materials.core", "PACKED_ICE"), Material.PACKED_ICE);
        this.shellMaterial = parseMaterial(cfg.getString("materials.shell", "BLUE_ICE"), Material.BLUE_ICE);
        this.topMaterial = parseMaterial(cfg.getString("materials.top", "SNOW_BLOCK"), Material.SNOW_BLOCK);

        // Spike
        this.spikeEnabled = cfg.getBoolean("spike.enabled", true);
        this.spikeHeightMultiplier = Math.max(1.0, cfg.getDouble("spike.height-multiplier", 2.5));
        this.spikeRadius = Math.max(1, cfg.getInt("spike.radius", 2));

        // Iceburst
        this.iceburstEnabled = cfg.getBoolean("iceburst.enabled", true);
        this.iceburstBlockCount = Math.max(1, cfg.getInt("iceburst.block-count", 100));
        this.iceburstMinVelocityY = cfg.getDouble("iceburst.min-velocity-y", 0.5);
        this.iceburstMaxVelocityY = cfg.getDouble("iceburst.max-velocity-y", 1.2);
        this.iceburstSpreadXZ = cfg.getDouble("iceburst.spread-xz", 0.4);
        this.iceburstRemoveAfterTicks = Math.max(20, cfg.getInt("iceburst.remove-after-ticks", 90));

        this.msgStarted = colorize(cfg.getString("messages.started", "&b[Iceberg] &fStart."));
        this.msgStopped = colorize(cfg.getString("messages.stopped", "&b[Iceberg] &fStop."));
        this.msgArrived = colorize(cfg.getString("messages.arrived", "&b[Iceberg] &fArrived."));
        this.msgRemoved = colorize(cfg.getString("messages.removed", "&b[Iceberg] &fRemoved."));
    }

    private Material parseMaterial(String name, Material fallback) {
        try {
            return Material.valueOf(name.toUpperCase());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String colorize(String input) {
        return input.replace('&', '§');
    }

    public String world() { return world; }

    public int startX() { return startX; }
    public int startY() { return startY; }
    public int startZ() { return startZ; }

    public int targetX() { return targetX; }
    public int targetY() { return targetY; }
    public int targetZ() { return targetZ; }

    public int movementDurationTicks() { return movementDurationTicks; }
    public int movementStepTicks() { return movementStepTicks; }
    public int removeDelayTicks() { return removeDelayTicks; }

    public int radiusX() { return radiusX; }
    public int radiusY() { return radiusY; }
    public int radiusZ() { return radiusZ; }

    public Material coreMaterial() { return coreMaterial; }
    public Material shellMaterial() { return shellMaterial; }
    public Material topMaterial() { return topMaterial; }

    public boolean spikeEnabled() { return spikeEnabled; }
    public double spikeHeightMultiplier() { return spikeHeightMultiplier; }
    public int spikeRadius() { return spikeRadius; }

    public boolean iceburstEnabled() { return iceburstEnabled; }
    public int iceburstBlockCount() { return iceburstBlockCount; }
    public double iceburstMinVelocityY() { return iceburstMinVelocityY; }
    public double iceburstMaxVelocityY() { return iceburstMaxVelocityY; }
    public double iceburstSpreadXZ() { return iceburstSpreadXZ; }
    public int iceburstRemoveAfterTicks() { return iceburstRemoveAfterTicks; }

    public String msgStarted() { return msgStarted; }
    public String msgStopped() { return msgStopped; }
    public String msgArrived() { return msgArrived; }
    public String msgRemoved() { return msgRemoved; }
}

