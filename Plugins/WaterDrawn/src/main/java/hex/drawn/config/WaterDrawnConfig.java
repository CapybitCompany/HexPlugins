package hex.drawn.config;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class WaterDrawnConfig {

    private final boolean enabled;
    private final boolean allWorlds;
    private final Set<String> worlds;
    private final int drownSeconds;
    private final int checkIntervalTicks;
    private final double drownDamage;

    private final int headSubmergeBelowY;
    private final int drownWaterLevelY;
    private final int immediateWarningTicks;

    private final String drownMode;
    private final List<DrownRegion> regions;
    private final List<ExcludedDrownRegion> excludedRegions;

    private final String warningImmediateActionbar;
    private final String warningCountdownActionbar;
    private final String deathMessage;

    private final Sound countdownStartSound;
    private final float countdownStartSoundVolume;
    private final float countdownStartSoundPitch;

    private volatile int dynamicWaterLevel = Integer.MAX_VALUE;

    public WaterDrawnConfig(JavaPlugin plugin) {
        var cfg = plugin.getConfig();

        this.enabled = cfg.getBoolean("enabled", true);
        this.allWorlds = cfg.getBoolean("all-worlds", true);
        this.worlds = new HashSet<>();

        List<String> worldList = cfg.getStringList("worlds-list");
        for (String worldName : worldList) {
            worlds.add(worldName.toLowerCase());
        }

        this.drownSeconds = Math.max(1, cfg.getInt("drown-seconds", 5));
        this.checkIntervalTicks = Math.max(1, cfg.getInt("check-interval-ticks", 5));
        this.drownDamage = cfg.getDouble("drown-damage", 999.0D);

        this.headSubmergeBelowY = cfg.getInt("head-submerge-below-y", 63);
        this.drownWaterLevelY = cfg.getInt("drown-water-level-y", 320);
        this.immediateWarningTicks = Math.max(0, Math.min(drownSeconds * 20, (int) Math.round(cfg.getDouble("immediate-warning-seconds", 1.5D) * 20.0D)));

        String rawMode = cfg.getString("drown-mode", "regions");
        this.drownMode = "global".equalsIgnoreCase(rawMode) ? "global" : "regions";

        this.regions = new ArrayList<>();
        ConfigurationSection regionsSection = cfg.getConfigurationSection("regions");
        if (regionsSection != null) {
            for (String key : regionsSection.getKeys(false)) {
                ConfigurationSection rs = regionsSection.getConfigurationSection(key);
                if (rs == null) continue;

                String world = rs.getString("world", "world");
                int x1 = rs.getInt("x1", 0);
                int x2 = rs.getInt("x2", 0);
                int z1 = rs.getInt("z1", 0);
                int z2 = rs.getInt("z2", 0);
                int yMin = rs.getInt("y-min", -64);
                int yMax = rs.getInt("y-max", 320);

                regions.add(new DrownRegion(
                        world,
                        Math.min(x1, x2),
                        Math.min(z1, z2),
                        Math.max(x1, x2),
                        Math.max(z1, z2),
                        Math.min(yMin, yMax),
                        Math.max(yMin, yMax)
                ));
            }
        }

        this.excludedRegions = new ArrayList<>();
        ConfigurationSection excludedSection = cfg.getConfigurationSection("excluded-regions");
        if (excludedSection != null) {
            for (String key : excludedSection.getKeys(false)) {
                ConfigurationSection rs = excludedSection.getConfigurationSection(key);
                if (rs == null) continue;

                String world = rs.getString("world", "world");
                int x1 = rs.getInt("x1", 0);
                int x2 = rs.getInt("x2", 0);
                int z1 = rs.getInt("z1", 0);
                int z2 = rs.getInt("z2", 0);
                int yMin = rs.getInt("y-min", -64);
                int yMax = rs.getInt("y-max", 320);
                int unlockLevel = rs.getInt("unlock-at-water-level-y", 64);

                excludedRegions.add(new ExcludedDrownRegion(
                        world,
                        Math.min(x1, x2),
                        Math.min(z1, z2),
                        Math.max(x1, x2),
                        Math.max(z1, z2),
                        Math.min(yMin, yMax),
                        Math.max(yMin, yMax),
                        unlockLevel
                ));
            }
        }

        this.warningImmediateActionbar = colorize(cfg.getString("messages.warning-immediate-actionbar", "&c&lNatychmiast wyjdz z wody!"));
        this.warningCountdownActionbar = colorize(cfg.getString("messages.warning-countdown-actionbar", "&cTopisz sie! &f%seconds%s &cdo utoniecia"));
        this.deathMessage = colorize(cfg.getString("messages.death-message", "&c%player% utonal."));

        this.countdownStartSound = parseSound(cfg.getString("sounds.countdown-start.type", "BLOCK_NOTE_BLOCK_PLING"));
        this.countdownStartSoundVolume = (float) cfg.getDouble("sounds.countdown-start.volume", 1.0D);
        this.countdownStartSoundPitch = (float) cfg.getDouble("sounds.countdown-start.pitch", 1.0D);
    }

    private Sound parseSound(String name) {
        try {
            return Sound.valueOf(name.toUpperCase());
        } catch (Exception ignored) {
            return Sound.BLOCK_NOTE_BLOCK_PLING;
        }
    }

    private String colorize(String input) {
        return input.replace('&', '§');
    }

    public boolean isEnabled() { return enabled; }
    public int getDrownSeconds() { return drownSeconds; }
    public int getCheckIntervalTicks() { return checkIntervalTicks; }
    public double getDrownDamage() { return drownDamage; }
    public int getHeadSubmergeBelowY() { return headSubmergeBelowY; }
    public int getDrownWaterLevelY() { return drownWaterLevelY; }
    public int getImmediateWarningTicks() { return immediateWarningTicks; }

    public void setDynamicWaterLevel(int level) {
        this.dynamicWaterLevel = level;
    }

    public int getEffectiveDrownWaterLevelY() {
        return Math.min(drownWaterLevelY, dynamicWaterLevel);
    }

    public String getWarningImmediateActionbar() { return warningImmediateActionbar; }
    public String getWarningCountdownActionbar() { return warningCountdownActionbar; }
    public String getDeathMessage() { return deathMessage; }

    public Sound getCountdownStartSound() { return countdownStartSound; }
    public float getCountdownStartSoundVolume() { return countdownStartSoundVolume; }
    public float getCountdownStartSoundPitch() { return countdownStartSoundPitch; }

    public String getDrownMode() { return drownMode; }
    public int getRegionsCount() { return regions.size(); }
    public int getExcludedRegionsCount() { return excludedRegions.size(); }

    public boolean isWorldAllowed(World world) {
        if (allWorlds) return true;
        return worlds.contains(world.getName().toLowerCase());
    }

    public boolean isDrowningActiveAt(Location location) {
        if (location == null || location.getWorld() == null) return false;
        if (!isWorldAllowed(location.getWorld())) return false;

        if ("global".equalsIgnoreCase(drownMode)) {
            return !isExcludedAt(location);
        }

        for (DrownRegion region : regions) {
            if (region.contains(location)) {
                return !isExcludedAt(location);
            }
        }

        return false;
    }

    public boolean isExcludedAt(Location location) {
        int effectiveWaterLevel = getEffectiveDrownWaterLevelY();
        for (ExcludedDrownRegion region : excludedRegions) {
            if (region.contains(location) && region.isStillExcluded(effectiveWaterLevel)) {
                return true;
            }
        }
        return false;
    }
}
