package hex.flood.config;

import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Odczytuje i przechowuje konfiguracje WaterLevelFlood (tylko mechanika podnoszenia wody).
 */
public class FloodConfig {

    private final String worldName;
    private final int x1, z1, x2, z2;
    private final int yMin, yMax;
    private final int startWaterLevel;
    private final int riseBlocks;
    private final int durationSeconds;
    private final int blocksPerTick;
    private final Set<Material> replaceableBlocks;

    private final String msgStarted;
    private final String msgStopped;
    private final String msgReset;
    private final String msgStatus;

    public FloodConfig(JavaPlugin plugin) {
        var cfg = plugin.getConfig();

        this.worldName = cfg.getString("world", "world");

        int rx1 = cfg.getInt("region.x1", -100);
        int rx2 = cfg.getInt("region.x2", 100);
        int rz1 = cfg.getInt("region.z1", -100);
        int rz2 = cfg.getInt("region.z2", 100);
        this.x1 = Math.min(rx1, rx2);
        this.x2 = Math.max(rx1, rx2);
        this.z1 = Math.min(rz1, rz2);
        this.z2 = Math.max(rz1, rz2);
        this.yMin = cfg.getInt("region.y-min", 50);
        this.yMax = cfg.getInt("region.y-max", 90);

        this.startWaterLevel = cfg.getInt("start-water-level", 55);
        this.riseBlocks = cfg.getInt("rise-blocks", 20);
        this.durationSeconds = cfg.getInt("duration-seconds", 480);
        this.blocksPerTick = cfg.getInt("blocks-per-tick", 3000);

        this.replaceableBlocks = EnumSet.noneOf(Material.class);
        List<String> blockNames = cfg.getStringList("replaceable-blocks");
        for (String name : blockNames) {
            try {
                Material mat = Material.valueOf(name.toUpperCase().trim());
                replaceableBlocks.add(mat);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Nieznany material w replaceable-blocks: " + name);
            }
        }

        replaceableBlocks.add(Material.AIR);
        replaceableBlocks.add(Material.CAVE_AIR);
        replaceableBlocks.add(Material.VOID_AIR);
        replaceableBlocks.add(Material.WATER);

        this.msgStarted = colorize(cfg.getString("messages.flood-started",
                "&c&l[TITANIC] &eWoda zaczyna sie podnosic!"));
        this.msgStopped = colorize(cfg.getString("messages.flood-stopped",
                "&a&l[TITANIC] &eWoda przestala sie podnosic."));
        this.msgReset = colorize(cfg.getString("messages.flood-reset",
                "&a&l[TITANIC] &eWoda zostala zresetowana."));
        this.msgStatus = colorize(cfg.getString("messages.flood-status",
                "&6&l[TITANIC] &eStatus: &f%status% &7| Poziom: &b%level% &7| Docelowy: &b%target%"));
    }

    private String colorize(String msg) {
        return msg.replace('&', '§');
    }

    public String getWorldName() { return worldName; }

    public int getX1() { return x1; }
    public int getZ1() { return z1; }
    public int getX2() { return x2; }
    public int getZ2() { return z2; }
    public int getYMin() { return yMin; }
    public int getYMax() { return yMax; }

    public int getStartWaterLevel() { return startWaterLevel; }
    public int getRiseBlocks() { return riseBlocks; }
    public int getTargetWaterLevel() { return startWaterLevel + riseBlocks; }
    public int getDurationSeconds() { return durationSeconds; }
    public int getBlocksPerTick() { return blocksPerTick; }

    public Set<Material> getReplaceableBlocks() { return replaceableBlocks; }

    public int getRegionWidth() { return x2 - x1 + 1; }
    public int getRegionDepth() { return z2 - z1 + 1; }
    public long getLayerBlockCount() { return (long) getRegionWidth() * getRegionDepth(); }

    public String getMsgStarted() { return msgStarted; }
    public String getMsgStopped() { return msgStopped; }
    public String getMsgReset() { return msgReset; }
    public String getMsgStatus() { return msgStatus; }
}
