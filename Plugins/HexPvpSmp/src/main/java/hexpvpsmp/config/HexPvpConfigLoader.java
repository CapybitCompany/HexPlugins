package hexpvpsmp.config;

import hexpvpsmp.region.Cuboid;
import hexpvpsmp.region.ProtectedRegion;
import hexpvpsmp.region.RegionId;
import hexpvpsmp.region.RegionType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

public final class HexPvpConfigLoader {

    public HexPvpConfig load(FileConfiguration config, Logger logger) {
        boolean enabled = config.getBoolean("enabled", true);
        boolean debug = config.getBoolean("debug", false);

        CombatConfig combat = loadCombat(config);
        SafezoneConfig safezones = loadSafezones(config);
        Map<String, WorldConfig> worlds = loadWorlds(config.getConfigurationSection("worlds"), logger);
        TownsConfig towns = loadTowns(config.getConfigurationSection("towns"), logger);

        return new HexPvpConfig(enabled, debug, combat, safezones, worlds, towns);
    }

    private CombatConfig loadCombat(FileConfiguration config) {
        int durationSeconds = config.getInt("combat.duration-seconds", 15);
        boolean actionbar = config.getBoolean("combat.actionbar-enabled", true);
        int actionbarTicks = config.getInt("combat.actionbar-update-ticks", 10);
        List<String> allowed = config.getStringList("combat.allowed-commands");
        // Backwards-compat: accept the old "kill-player" key as a fallback for "enabled".
        // isSet() returns true only for user-overridden values; contains() would also
        // count the JAR-resource default, which would silently override an old user key.
        boolean combatLogEnabled = config.isSet("combat.combat-log.enabled")
                ? config.getBoolean("combat.combat-log.enabled", true)
                : config.getBoolean("combat.combat-log.kill-player", true);
        CombatConfig.CombatLog combatLog = new CombatConfig.CombatLog(
                combatLogEnabled,
                config.getBoolean("combat.combat-log.drop-inventory", true),
                config.getBoolean("combat.combat-log.drop-exp", true),
                config.getString("combat.combat-log.broadcast", "")
        );
        return CombatConfig.fromList(durationSeconds, actionbar, actionbarTicks, allowed, combatLog);
    }

    private SafezoneConfig loadSafezones(FileConfiguration config) {
        return new SafezoneConfig(
                config.getBoolean("safezones.block-entry-while-combat", true),
                config.getString("safezones.entry-message", ""),
                config.getString("safezones.pvp-deny-message", ""),
                config.getInt("safezones.warning-cooldown-ticks", 20)
        );
    }

    private Map<String, WorldConfig> loadWorlds(ConfigurationSection section, Logger logger) {
        if (section == null) {
            return Map.of();
        }
        Map<String, WorldConfig> output = new LinkedHashMap<>();
        for (String world : section.getKeys(false)) {
            ConfigurationSection w = section.getConfigurationSection(world);
            if (w == null) {
                continue;
            }
            String normalizedWorld = world.toLowerCase(Locale.ROOT);
            boolean enabled = w.getBoolean("enabled", true);
            SpawnConfig spawn = loadSpawn(w.getConfigurationSection("spawn"), logger, world);
            output.put(normalizedWorld, new WorldConfig(normalizedWorld, enabled, spawn));
        }
        return Map.copyOf(output);
    }

    private SpawnConfig loadSpawn(ConfigurationSection section, Logger logger, String worldKey) {
        if (section == null) {
            return SpawnConfig.disabled();
        }
        boolean enabled = section.getBoolean("enabled", false);
        Cuboid cuboid = readCuboid(section.getConfigurationSection("region"), logger,
                "worlds." + worldKey + ".spawn.region");
        if (cuboid == null && enabled) {
            logger.warning("HexPvpSmp: spawn enabled in '" + worldKey + "' but region is invalid. Disabling.");
            enabled = false;
        }
        RedLineConfig redLine = loadRedLine(section.getConfigurationSection("red-line"));
        return new SpawnConfig(enabled, cuboid, redLine);
    }

    private RedLineConfig loadRedLine(ConfigurationSection section) {
        if (section == null) {
            return RedLineConfig.disabled();
        }
        return new RedLineConfig(
                section.getBoolean("enabled", false),
                section.getDouble("warning-distance", 0.0D),
                section.getString("message", "")
        );
    }

    private TownsConfig loadTowns(ConfigurationSection section, Logger logger) {
        if (section == null) {
            return new TownsConfig(TownsConfig.Provider.CONFIG, List.of());
        }
        TownsConfig.Provider provider;
        try {
            provider = TownsConfig.Provider.valueOf(
                    section.getString("provider", "CONFIG").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            logger.warning("HexPvpSmp: unknown towns.provider, defaulting to CONFIG");
            provider = TownsConfig.Provider.CONFIG;
        }

        ConfigurationSection regions = section.getConfigurationSection("regions");
        if (regions == null) {
            return new TownsConfig(provider, List.of());
        }
        List<ProtectedRegion> output = new ArrayList<>();
        for (String key : regions.getKeys(false)) {
            ConfigurationSection r = regions.getConfigurationSection(key);
            if (r == null) {
                continue;
            }
            try {
                String world = r.getString("world");
                if (world == null || world.isBlank()) {
                    logger.warning("HexPvpSmp: town '" + key + "' missing world");
                    continue;
                }
                Cuboid c = readCuboid(r, logger, "towns.regions." + key);
                if (c == null) {
                    continue;
                }
                output.add(new ProtectedRegion(new RegionId(key), world, RegionType.TOWN, c));
            } catch (Exception ex) {
                logger.warning("HexPvpSmp: skipping town '" + key + "': " + ex.getMessage());
            }
        }
        return new TownsConfig(provider, output);
    }

    private Cuboid readCuboid(ConfigurationSection section, Logger logger, String path) {
        if (section == null) {
            return null;
        }
        try {
            double minX = section.getDouble("min-x");
            double maxX = section.getDouble("max-x");
            double minY = section.getDouble("min-y");
            double maxY = section.getDouble("max-y");
            double minZ = section.getDouble("min-z");
            double maxZ = section.getDouble("max-z");
            return new Cuboid(
                    Math.min(minX, maxX), Math.min(minY, maxY), Math.min(minZ, maxZ),
                    Math.max(minX, maxX), Math.max(minY, maxY), Math.max(minZ, maxZ)
            );
        } catch (Exception ex) {
            logger.warning("HexPvpSmp: invalid cuboid at " + path + ": " + ex.getMessage());
            return null;
        }
    }
}
