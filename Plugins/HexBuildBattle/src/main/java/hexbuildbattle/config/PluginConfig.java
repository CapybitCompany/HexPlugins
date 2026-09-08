package hexbuildbattle.config;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public record PluginConfig(
        PlayerLimits players,
        Timings timings,
        MapSettings map,
        ResetSettings reset,
        Network network,
        Database database,
        Safety safety,
        MaterialSettings materials,
        PlaceholderSettings placeholders,
        TaskSettings tasks,
        SoundSettings sounds,
        SnowEffectSettings snowEffect,
        Map<Integer, Integer> rankingPointRewards
) {

    public static PluginConfig load(FileConfiguration config, Path dataFolder, Logger logger) {
        int maxPlayers = boundedInt(config, logger, "players.max", 30, 1, 30);
        int minPlayers = boundedInt(config, logger, "players.min", 4, 1, maxPlayers);

        PlayerLimits players = new PlayerLimits(minPlayers, maxPlayers);
        Timings timings = new Timings(
                positiveInt(config, logger, "timings.countdown-seconds", 30),
                positiveInt(config, logger, "timings.theme-voting-seconds", 10),
                positiveInt(config, logger, "timings.selected-theme-title-seconds", 3),
                positiveInt(config, logger, "timings.building-seconds", 300),
                positiveInt(config, logger, "timings.pre-judging-seconds", 2),
                positiveInt(config, logger, "timings.judging-seconds-per-arena", 10),
                positiveInt(config, logger, "timings.results-seconds", 10),
                positiveIntegerSet(config, logger, "timings.building-reminder-seconds", Set.of(120, 60, 30))
        );
        MapSettings map = new MapSettings(
                stringValue(config, "map.world", "world"),
                new Point(
                        config.getDouble("map.lobby-spawn.x", 777.5D),
                        config.getDouble("map.lobby-spawn.y", -55.0D),
                        config.getDouble("map.lobby-spawn.z", 979.5D),
                        (float) config.getDouble("map.lobby-spawn.yaw", 0.48D),
                        (float) config.getDouble("map.lobby-spawn.pitch", 10.35D)
                ),
                loadArenaGeometry(config, logger)
        );
        ResetSettings reset = new ResetSettings(
                config.getBoolean("reset.prefer-fawe", true),
                boundedInt(config, logger, "reset.max-blocks-per-main-thread-tick", 1500, 1, 20000)
        );
        Network network = new Network(stringValue(config, "network.lobby-server", "lobby"));
        Database database = new Database(
                stringValue(config, "database.type", "sqlite").toLowerCase(Locale.ROOT),
                dataFolder.resolve(stringValue(config, "database.file", "statistics.db"))
        );
        Safety safety = new Safety(
                config.getBoolean("safety.judge-disconnected-builds", false),
                config.getBoolean("safety.fire-spread", false),
                config.getBoolean("safety.pvp", false),
                config.getBoolean("safety.damage", false),
                config.getBoolean("safety.hunger", false),
                config.getBoolean("safety.item-drop", false),
                config.getBoolean("safety.item-pickup", false),
                config.getBoolean("safety.portal-usage", false),
                config.getBoolean("safety.explosions", false)
        );
        Material defaultFloor = Material.BLACK_WOOL;
        MaterialSettings materials = new MaterialSettings(
                ConfigParsers.materialSet(config.getStringList("floor-blacklist"), logger, "floor-blacklist"),
                ConfigParsers.materialSet(config.getStringList("blocked-materials"), logger, "blocked-materials"),
                ConfigParsers.materialSet(config.getStringList("blocked-interactions"), logger, "blocked-interactions"),
                defaultFloor
        );
        PlaceholderSettings placeholders = new PlaceholderSettings(
                stringValue(config, "placeholders.no-theme", "&7Brak"),
                stringValue(config, "placeholders.voting-theme", "&eGlosowanie..."),
                stringValue(config, "placeholders.top-empty-name", "-"),
                stringValue(config, "placeholders.top-empty-points", "0")
        );
        TaskSettings tasks = new TaskSettings(
                boundedInt(config, logger, "tasks.lobby-actionbar-interval-ticks", 20, 1, 20 * 60),
                boundedInt(config, logger, "tasks.player-maintenance-interval-ticks", 20, 1, 20 * 60)
        );
        SoundSettings sounds = new SoundSettings(
                loadCountdownSounds(config, logger, timings.countdownSeconds()),
                loadBuildingFinalCountdownSounds(config, logger),
                soundSetting(config, logger, "sounds.countdown.start", "ENTITY_PLAYER_LEVELUP", 1.0D, 1.0D),
                soundSetting(config, logger, "sounds.floor.changed", "ENTITY_EXPERIENCE_ORB_PICKUP", 0.8D, 1.15D),
                soundSetting(config, logger, "sounds.floor.invalid", "ENTITY_VILLAGER_NO", 0.7D, 0.8D)
        );
        SnowEffectSettings snowEffect = new SnowEffectSettings(
                config.getBoolean("visual-effects.snow.enabled", true),
                boundedInt(config, logger, "visual-effects.snow.particle-count", 18, 1, 80),
                Math.max(0.5D, config.getDouble("visual-effects.snow.radius", 7.0D)),
                Math.max(0.5D, config.getDouble("visual-effects.snow.height", 5.0D)),
                Math.max(0.0D, config.getDouble("visual-effects.snow.y-offset", 2.5D)),
                Math.max(0.0D, config.getDouble("visual-effects.snow.speed", 0.01D)),
                ConfigParsers.particle(
                        config.getString("visual-effects.snow.particle"),
                        Particle.SNOWFLAKE,
                        logger,
                        "visual-effects.snow.particle"
                )
        );
        return new PluginConfig(
                players,
                timings,
                map,
                reset,
                network,
                database,
                safety,
                materials,
                placeholders,
                tasks,
                sounds,
                snowEffect,
                loadRankingRewards(config, logger)
        );
    }

    private static ArenaGeometry loadArenaGeometry(FileConfiguration config, Logger logger) {
        return new ArenaGeometry(
                positiveInt(config, logger, "map.arena-geometry.count", 30),
                positiveInt(config, logger, "map.arena-geometry.columns", 6),
                positiveInt(config, logger, "map.arena-geometry.rows", 5),
                config.getInt("map.arena-geometry.column-offset-x", 117),
                config.getInt("map.arena-geometry.row-offset-z", 117),
                stringValue(config, "map.arena-geometry.template-schematic", "bb_arena_template_good"),
                cuboid(config, "map.arena-geometry.module", new CuboidSpec(501, -34, 595, 616, 56, 710)),
                cuboid(config, "map.arena-geometry.build-region", new CuboidSpec(543, -23, 638, 573, 16, 668)),
                new FloorSpec(
                        config.getInt("map.arena-geometry.floor.min-x", 543),
                        config.getInt("map.arena-geometry.floor.y", -24),
                        config.getInt("map.arena-geometry.floor.min-z", 638),
                        config.getInt("map.arena-geometry.floor.max-x", 573),
                        config.getInt("map.arena-geometry.floor.max-z", 668)
                ),
                point(config, "map.arena-geometry.owner-spawn", new Point(558.5D, -23.0D, 653.5D, 90.0F, 0.0F)),
                point(config, "map.arena-geometry.judging-center", new Point(558.5D, -8.0D, 653.5D, 90.0F, 0.0F))
        );
    }

    private static CuboidSpec cuboid(FileConfiguration config, String path, CuboidSpec fallback) {
        return new CuboidSpec(
                config.getInt(path + ".min-x", fallback.minX()),
                config.getInt(path + ".min-y", fallback.minY()),
                config.getInt(path + ".min-z", fallback.minZ()),
                config.getInt(path + ".max-x", fallback.maxX()),
                config.getInt(path + ".max-y", fallback.maxY()),
                config.getInt(path + ".max-z", fallback.maxZ())
        );
    }

    private static Point point(FileConfiguration config, String path, Point fallback) {
        return new Point(
                config.getDouble(path + ".x", fallback.x()),
                config.getDouble(path + ".y", fallback.y()),
                config.getDouble(path + ".z", fallback.z()),
                (float) config.getDouble(path + ".yaw", fallback.yaw()),
                (float) config.getDouble(path + ".pitch", fallback.pitch())
        );
    }

    private static Map<Integer, Integer> loadRankingRewards(FileConfiguration config, Logger logger) {
        Map<Integer, Integer> rewards = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection("ranking-point-rewards");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    int place = Integer.parseInt(key);
                    rewards.put(place, Math.max(0, section.getInt(key)));
                } catch (NumberFormatException ex) {
                    logger.warning("Invalid ranking reward place '" + key + "' in config.yml.");
                }
            }
        }
        if (rewards.isEmpty()) {
            for (int place = 1; place <= 30; place++) {
                rewards.put(place, Math.max(1, 101 - place * 5));
            }
        }
        return Map.copyOf(rewards);
    }

    private static Map<Integer, SoundSetting> loadCountdownSounds(
            FileConfiguration config,
            Logger logger,
            int countdownSeconds
    ) {
        return loadSecondSounds(config, logger, "sounds.countdown", Math.max(30, countdownSeconds));
    }

    private static Map<Integer, SoundSetting> loadBuildingFinalCountdownSounds(FileConfiguration config, Logger logger) {
        return loadSecondSounds(config, logger, "sounds.building-final-countdown", 10);
    }

    private static Map<Integer, SoundSetting> loadSecondSounds(
            FileConfiguration config,
            Logger logger,
            String rootPath,
            int maxSecond
    ) {
        Map<Integer, SoundSetting> sounds = new HashMap<>();
        for (int second = maxSecond; second >= 1; second--) {
            String defaultSound = "BLOCK_NOTE_BLOCK_HAT";
            double defaultVolume = second > 10 ? 0.55D : 0.85D;
            double defaultPitch = switch (second) {
                case 3 -> 1.35D;
                case 2 -> 1.55D;
                case 1 -> 1.8D;
                default -> second > 10 ? 1.0D : 1.1D;
            };
            sounds.put(second, soundSetting(
                    config,
                    logger,
                    rootPath + "." + second,
                    defaultSound,
                    defaultVolume,
                    defaultPitch
            ));
        }
        return Map.copyOf(sounds);
    }

    private static SoundSetting soundSetting(
            FileConfiguration config,
            Logger logger,
            String path,
            String fallbackSound,
            double fallbackVolume,
            double fallbackPitch
    ) {
        return ConfigParsers.soundSetting(
                config.getConfigurationSection(path),
                fallbackSound,
                fallbackVolume,
                fallbackPitch,
                logger,
                "config.yml:" + path
        );
    }

    private static int positiveInt(FileConfiguration config, Logger logger, String path, int fallback) {
        return boundedInt(config, logger, path, fallback, 1, Integer.MAX_VALUE);
    }

    private static Set<Integer> positiveIntegerSet(
            FileConfiguration config,
            Logger logger,
            String path,
            Set<Integer> fallback
    ) {
        List<Integer> configured = config.getIntegerList(path);
        if (configured.isEmpty()) {
            return fallback;
        }

        Set<Integer> values = new HashSet<>();
        for (int value : configured) {
            if (value <= 0) {
                logger.warning("Invalid config value " + path + " contains " + value + ". Entry ignored.");
                continue;
            }
            values.add(value);
        }
        return values.isEmpty() ? fallback : Set.copyOf(values);
    }

    private static int boundedInt(
            FileConfiguration config,
            Logger logger,
            String path,
            int fallback,
            int min,
            int max
    ) {
        int value = config.getInt(path, fallback);
        if (value < min || value > max) {
            int safeFallback = Math.max(min, Math.min(max, fallback));
            logger.warning(String.format(Locale.ROOT,
                    "Invalid config value %s=%d, expected %d..%d. Using %d.",
                    path, value, min, max, safeFallback));
            return safeFallback;
        }
        return value;
    }

    private static String stringValue(FileConfiguration config, String path, String fallback) {
        String value = config.getString(path, fallback);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    public int minPlayers() {
        return players.min();
    }

    public int maxPlayers() {
        return players.max();
    }

    public int rankingReward(int place) {
        return rankingPointRewards.getOrDefault(place, 0);
    }

    public record PlayerLimits(int min, int max) {
    }

    public record Timings(
            int countdownSeconds,
            int themeVotingSeconds,
            int selectedThemeTitleSeconds,
            int buildingSeconds,
            int preJudgingSeconds,
            int judgingSecondsPerArena,
            int resultsSeconds,
            Set<Integer> buildingReminderSeconds
    ) {
    }

    public record MapSettings(String worldName, Point lobbySpawn, ArenaGeometry arenaGeometry) {
    }

    public record ArenaGeometry(
            int count,
            int columns,
            int rows,
            int columnOffsetX,
            int rowOffsetZ,
            String templateSchematic,
            CuboidSpec module,
            CuboidSpec buildRegion,
            FloorSpec floor,
            Point ownerSpawn,
            Point judgingCenter
    ) {
    }

    public record CuboidSpec(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    public record FloorSpec(int minX, int y, int minZ, int maxX, int maxZ) {
    }

    public record Point(double x, double y, double z, float yaw, float pitch) {
    }

    public record ResetSettings(boolean preferFawe, int maxBlocksPerMainThreadTick) {
    }

    public record Network(String lobbyServer) {
    }

    public record Database(String type, Path file) {
    }

    public record Safety(
            boolean judgeDisconnectedBuilds,
            boolean fireSpread,
            boolean pvp,
            boolean damage,
            boolean hunger,
            boolean itemDrop,
            boolean itemPickup,
            boolean portalUsage,
            boolean explosions
    ) {
    }

    public record MaterialSettings(
            Set<Material> floorBlacklist,
            Set<Material> blockedMaterials,
            Set<Material> blockedInteractions,
            Material defaultFloor
    ) {
        public MaterialSettings {
            floorBlacklist = Set.copyOf(new HashSet<>(floorBlacklist));
            blockedMaterials = Set.copyOf(new HashSet<>(blockedMaterials));
            blockedInteractions = Set.copyOf(new HashSet<>(blockedInteractions));
        }
    }

    public record PlaceholderSettings(
            String noTheme,
            String votingTheme,
            String topEmptyName,
            String topEmptyPoints
    ) {
    }

    public record TaskSettings(int lobbyActionbarIntervalTicks, int playerMaintenanceIntervalTicks) {
    }

    public record SoundSettings(
            Map<Integer, SoundSetting> countdown,
            Map<Integer, SoundSetting> buildingFinalCountdown,
            SoundSetting countdownStart,
            SoundSetting floorChanged,
            SoundSetting floorInvalid
    ) {
    }

    public record SnowEffectSettings(
            boolean enabled,
            int particleCount,
            double radius,
            double height,
            double yOffset,
            double speed,
            Particle particle
    ) {
    }
}
