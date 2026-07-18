package hexpvpsmp.config;

import hexpvpsmp.region.Cuboid;
import hexpvpsmp.region.ProtectedRegion;
import hexpvpsmp.region.PublicChest;
import hexpvpsmp.region.RegionId;
import hexpvpsmp.region.RegionType;
import org.bukkit.Material;
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
        SafezoneConfig safezones = loadSafezones(config, logger);
        ProtectionConfig protection = loadProtection(config);
        MessagesConfig messages = loadMessages(config.getConfigurationSection("messages"));
        Map<String, WorldConfig> worlds = loadWorlds(config.getConfigurationSection("worlds"), logger);

        return new HexPvpConfig(enabled, debug, combat, safezones, protection, messages, worlds);
    }

    private ProtectionConfig loadProtection(FileConfiguration config) {
        return new ProtectionConfig(
                config.getBoolean("protection.bypass.build", true),
                config.getBoolean("protection.bypass.interact", true),
                config.getBoolean("protection.bypass.items", true),
                config.getBoolean("protection.interactions.block-buttons", false),
                config.getBoolean("protection.items.block-pvp-in-no-build", false)
        );
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

    private SafezoneConfig loadSafezones(FileConfiguration config, Logger logger) {
        BarrierConfig barrier = loadBarrier(config.getConfigurationSection("safezones.entry-barrier"), logger);
        return new SafezoneConfig(
                config.getBoolean("safezones.block-entry-while-combat", true),
                config.getInt("safezones.warning-cooldown-ticks", 20),
                config.getInt("safezones.info-cooldown-ticks", 40),
                barrier
        );
    }

    private BarrierConfig loadBarrier(ConfigurationSection section, Logger logger) {
        if (section == null) {
            return BarrierConfig.defaults();
        }
        Material material = Material.RED_STAINED_GLASS;
        String raw = section.getString("material", "RED_STAINED_GLASS");
        if (raw != null) {
            Material parsed = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
            if (parsed == null || !parsed.isBlock()) {
                logger.warning("HexPvpSmp: invalid safezones.entry-barrier.material '" + raw
                        + "' — falling back to RED_STAINED_GLASS.");
            } else {
                material = parsed;
            }
        }
        return new BarrierConfig(
                section.getBoolean("enabled", true),
                material,
                section.getInt("duration-ticks", 40),
                section.getInt("radius", 4),
                section.getInt("height", 3)
        );
    }

    private MessagesConfig loadMessages(ConfigurationSection section) {
        if (section == null) {
            return MessagesConfig.defaults();
        }
        return new MessagesConfig(
                section.getString("no-permission"),
                section.getString("pvp-denied"),
                section.getString("safezone-entry-denied"),
                section.getString("command-blocked"),
                section.getString("combat-actionbar"),
                section.getString("leaving-spawn"),
                section.getString("build-denied"),
                section.getString("interact-denied"),
                section.getString("item-denied"),
                section.getString("reload-success"),
                section.getString("reload-failed"),
                section.getString("safezone-enter-title"),
                section.getString("safezone-enter-subtitle"),
                section.getString("safezone-exit-title"),
                section.getString("safezone-exit-subtitle")
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
            List<ProtectedRegion> noBuild = loadNoBuildZones(
                    w.getConfigurationSection("no-build-zones"), logger, normalizedWorld, world);
            List<PublicChest> chests = loadPublicChests(
                    w.getConfigurationSection("public-chests"), logger, normalizedWorld, world);
            output.put(normalizedWorld,
                    new WorldConfig(normalizedWorld, enabled, spawn, noBuild, chests));
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
        boolean blockMobSpawns = section.getBoolean("block-mob-spawns", true);
        boolean disableHungerLoss = section.getBoolean("disable-hunger-loss", true);
        return new SpawnConfig(enabled, cuboid, redLine, blockMobSpawns, disableHungerLoss);
    }

    private RedLineConfig loadRedLine(ConfigurationSection section) {
        if (section == null) {
            return RedLineConfig.disabled();
        }
        return new RedLineConfig(
                section.getBoolean("enabled", false),
                section.getDouble("warning-distance", 0.0D)
        );
    }

    private List<ProtectedRegion> loadNoBuildZones(ConfigurationSection section, Logger logger,
                                                   String worldKey, String worldRaw) {
        if (section == null) {
            return List.of();
        }
        List<ProtectedRegion> output = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection r = section.getConfigurationSection(key);
            if (r == null) {
                continue;
            }
            if (!r.getBoolean("enabled", true)) {
                continue;
            }
            Cuboid c = readCuboid(r.getConfigurationSection("region"), logger,
                    "worlds." + worldRaw + ".no-build-zones." + key);
            if (c == null) {
                continue;
            }
            try {
                output.add(new ProtectedRegion(new RegionId(key), worldKey, RegionType.NO_BUILD, c));
            } catch (Exception ex) {
                logger.warning("HexPvpSmp: skipping no-build zone '" + key + "': " + ex.getMessage());
            }
        }
        return output;
    }

    private List<PublicChest> loadPublicChests(ConfigurationSection section, Logger logger,
                                               String worldKey, String worldRaw) {
        if (section == null) {
            return List.of();
        }
        List<PublicChest> output = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection c = section.getConfigurationSection(key);
            if (c == null) {
                continue;
            }
            try {
                // 'world' is optional; defaults to the enclosing world section.
                String world = c.getString("world", worldKey);
                int x = c.getInt("x");
                int y = c.getInt("y");
                int z = c.getInt("z");
                output.add(new PublicChest(world, x, y, z));
            } catch (Exception ex) {
                logger.warning("HexPvpSmp: skipping public-chest '" + key
                        + "' in '" + worldRaw + "': " + ex.getMessage());
            }
        }
        return output;
    }

    private Cuboid readCuboid(ConfigurationSection section, Logger logger, String path) {
        if (section == null) {
            return null;
        }
        try {
            // Regions are vertically unbounded X/Z columns. Legacy configs may
            // still carry min-y/max-y — tolerate them, but ignore + warn once.
            if (section.isSet("min-y") || section.isSet("max-y")) {
                logger.warning("HexPvpSmp: '" + path + "' has min-y/max-y — these are "
                        + "ignored; regions now protect all heights (X/Z only).");
            }
            double minX = section.getDouble("min-x");
            double maxX = section.getDouble("max-x");
            double minZ = section.getDouble("min-z");
            double maxZ = section.getDouble("max-z");
            return new Cuboid(
                    Math.min(minX, maxX), Math.min(minZ, maxZ),
                    Math.max(minX, maxX), Math.max(minZ, maxZ)
            );
        } catch (Exception ex) {
            logger.warning("HexPvpSmp: invalid cuboid at " + path + ": " + ex.getMessage());
            return null;
        }
    }
}
