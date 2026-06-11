package hex.towns.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TownsConfig {
    private final Set<String> worldWhitelist;
    private final int initialRadius;
    private final int maxChunks;
    private final int bufferChunks;
    private final int minDistanceChunks;
    private final boolean creationConfirmRequired;
    private final String defaultNameTemplate;
    private final int maxNameLength;
    private final int startingGrowthPoints;
    private final boolean growthSyncEnabled;
    private final int growthSyncIntervalTicks;
    private final int maxMembers;
    private final int requestTtlSeconds;
    private final int confirmWindowSeconds;
    private final int visualRadiusChunks;
    private final int visualRefreshTicks;
    private final int visualStateRefreshTicks;
    private final int visualPillarHeight;
    private final int visualPillarStep;
    private final int visualEdgeStep;
    private final int visualMaxBlocksPerTickGlobal;
    private final float visualDisplayWidth;
    private final float visualEdgeThickness;
    private final float visualDisplayViewRange;
    private final int mapRadiusChunks;
    private final int bucketSize;
    private final Material visualBlock;

    private TownsConfig(FileConfiguration config) {
        this.worldWhitelist = new HashSet<>(config.getStringList("towns.world-whitelist"));
        this.initialRadius = Math.max(0, config.getInt("towns.size.initial-radius", 1));
        this.maxChunks = Math.max(1, config.getInt("towns.size.max-chunks", 49));
        this.bufferChunks = Math.max(1, config.getInt("towns.size.buffer-chunks-between-towns", 1));
        this.minDistanceChunks = Math.max(0, config.getInt("towns.creation.min-distance-chunks", 10));
        this.creationConfirmRequired = config.getBoolean("towns.creation.confirm-required", true);
        this.defaultNameTemplate = config.getString("towns.naming.default-template", "Town {number}");
        this.maxNameLength = Math.max(3, Math.min(64, config.getInt("towns.naming.max-length", 16)));
        this.startingGrowthPoints = Math.max(0, config.getInt("towns.growth.starting-points", 0));
        this.growthSyncEnabled = config.getBoolean("towns.growth.sync.enabled", true);
        this.growthSyncIntervalTicks = Math.max(20, config.getInt("towns.growth.sync.interval-ticks", 200));
        this.maxMembers = Math.max(1, config.getInt("towns.coop.max-members", 3));
        this.requestTtlSeconds = Math.max(10, config.getInt("towns.coop.request-ttl-seconds", 120));
        this.confirmWindowSeconds = Math.max(5, config.getInt("towns.destroy.confirm-window-seconds", 30));
        this.visualRadiusChunks = Math.max(1, config.getInt("towns.visual-check.radius-chunks", 6));
        this.visualRefreshTicks = Math.max(20, config.getInt("towns.visual-check.refresh-ticks", 40));
        this.visualStateRefreshTicks = Math.max(0, config.getInt("towns.visual-check.state-refresh-ticks", 200));
        this.visualPillarHeight = Math.max(6, config.getInt("towns.visual-check.pillar-height", 18));
        this.visualPillarStep = Math.max(1, config.getInt("towns.visual-check.pillar-step", 4));
        this.visualEdgeStep = Math.max(1, config.getInt("towns.visual-check.edge-step", 4));
        this.visualMaxBlocksPerTickGlobal = Math.max(50, config.getInt("towns.visual-check.max-block-displays-per-tick-global", config.getInt("towns.visual-check.max-blocks-per-tick-global", 20000)));
        this.visualDisplayWidth = clampFloat((float) config.getDouble("towns.visual-check.display-width", 0.15D), 0.05F, 1.0F);
        this.visualEdgeThickness = clampFloat((float) config.getDouble("towns.visual-check.edge-thickness", 0.15D), 0.05F, 1.0F);
        this.visualDisplayViewRange = clampFloat((float) config.getDouble("towns.visual-check.display-view-range", 64.0D), 8.0F, 256.0F);
        this.mapRadiusChunks = Math.max(4, Math.min(24, config.getInt("towns.map.radius-chunks", 8)));
        this.bucketSize = Math.max(4, config.getInt("towns.scale.distance-check-bucket-size", 16));
        this.visualBlock = Material.matchMaterial(config.getString("towns.visual-check.block", "LIME_STAINED_GLASS")) == null
                ? Material.LIME_STAINED_GLASS
                : Material.matchMaterial(config.getString("towns.visual-check.block", "LIME_STAINED_GLASS"));
    }

    public static TownsConfig load(FileConfiguration config) {
        return new TownsConfig(config);
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public boolean isWorldAllowed(String worldName) {
        return worldWhitelist.isEmpty() || worldWhitelist.contains(worldName);
    }

    public List<String> worldWhitelist() { return List.copyOf(worldWhitelist); }
    public int initialRadius() { return initialRadius; }
    public int maxChunks() { return maxChunks; }
    public int bufferChunks() { return bufferChunks; }
    public int minDistanceChunks() { return minDistanceChunks; }
    public boolean creationConfirmRequired() { return creationConfirmRequired; }
    public String defaultNameTemplate() { return defaultNameTemplate; }
    public int maxNameLength() { return maxNameLength; }
    public int startingGrowthPoints() { return startingGrowthPoints; }
    public boolean growthSyncEnabled() { return growthSyncEnabled; }
    public int growthSyncIntervalTicks() { return growthSyncIntervalTicks; }
    public int maxMembers() { return maxMembers; }
    public int requestTtlSeconds() { return requestTtlSeconds; }
    public int confirmWindowSeconds() { return confirmWindowSeconds; }
    public int visualRadiusChunks() { return visualRadiusChunks; }
    public int visualRefreshTicks() { return visualRefreshTicks; }
    public int visualStateRefreshTicks() { return visualStateRefreshTicks; }
    public int visualPillarHeight() { return visualPillarHeight; }
    public int visualPillarStep() { return visualPillarStep; }
    public int visualEdgeStep() { return visualEdgeStep; }
    public int visualMaxBlocksPerTickGlobal() { return visualMaxBlocksPerTickGlobal; }
    public float visualDisplayWidth() { return visualDisplayWidth; }
    public float visualEdgeThickness() { return visualEdgeThickness; }
    public float visualDisplayViewRange() { return visualDisplayViewRange; }
    public int mapRadiusChunks() { return mapRadiusChunks; }
    public int bucketSize() { return bucketSize; }
    public Material visualBlock() { return visualBlock; }
}