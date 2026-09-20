package hex.minigames.config;

import hex.minigames.game.MinigameDefinition;
import hex.minigames.game.supermemory.SuperMemoryConfig;
import hex.minigames.model.BlockPosition;
import hex.minigames.model.CuboidRegion;
import hex.minigames.model.LocationSpec;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class MinigamesConfigLoader {
    private static final List<String> GAME_FILES = List.of(
            "red_light_green_light.yml",
            "glass_bridge.yml",
            "tag.yml",
            "super_memory.yml",
            "jump_rope.yml",
            "elytra.yml",
            "breeze_tower.yml",
            "hot_head.yml",
            "monkey_run.yml",
            "popcorn.yml",
            "disco_floor.yml",
            "dalgona.yml",
            "mingle.yml"
    );

    private final Plugin plugin;

    public MinigamesConfigLoader(Plugin plugin) {
        this.plugin = plugin;
    }

    public void saveDefaults() {
        plugin.saveDefaultConfig();
        saveResource("messages.yml");
        migrateSeriesDefaults();
        for (String gameFile : GAME_FILES) {
            saveResource("games/" + gameFile);
            BalanceConfigMigration.apply(plugin, gameFile);
        }
    }

    /** Add new message keys and migrate the previous waiting-room threshold once. */
    private void migrateSeriesDefaults() {
        File main = new File(plugin.getDataFolder(), "config.yml");
        File messages = new File(plugin.getDataFolder(), "messages.yml");
        var yaml = YamlConfiguration.loadConfiguration(main);
        if (yaml.getInt("series-ui-revision", 0) >= 1) return;
        try (var input = plugin.getResource("messages.yml")) {
            if (input == null) throw new java.io.IOException("Missing messages.yml resource");
            var defaults = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
            var existing = YamlConfiguration.loadConfiguration(messages);
            for (String key : defaults.getKeys(true)) if (!existing.contains(key)) existing.set(key, defaults.get(key));
            for (File file : List.of(main, messages)) {
                var backup = file.toPath().resolveSibling(file.getName() + ".before-series-ui-1.bak");
                if (file.isFile() && !java.nio.file.Files.exists(backup)) java.nio.file.Files.copy(file.toPath(), backup);
            }
            if (!yaml.contains("pregame.minimum-players") || yaml.getInt("pregame.minimum-players") == 5)
                yaml.set("pregame.minimum-players", 4);
            yaml.set("series-ui-revision", 1);
            existing.save(messages);
            yaml.save(main);
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot migrate series settings", error); }
    }

    public LoadedMinigamesConfig load() {
        File dataFolder = plugin.getDataFolder();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(dataFolder, "config.yml"));
        YamlConfiguration messagesYaml = YamlConfiguration.loadConfiguration(new File(dataFolder, "messages.yml"));

        List<String> errors = new ArrayList<>();
        GlobalConfig global = loadGlobal(config, errors);
        Messages messages = loadMessages(messagesYaml);
        Map<String, MinigameDefinition> games = new LinkedHashMap<>();

        File gamesFolder = new File(dataFolder, "games");
        for (String fileName : GAME_FILES) {
            File file = new File(gamesFolder, fileName);
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            MinigameDefinition definition = loadGame(fileName, yaml, global, errors);
            if ("super_memory".equalsIgnoreCase(definition.id())) {
                List<String> gameErrors = new ArrayList<>();
                SuperMemoryConfig.fromDefinition(definition, gameErrors);
                for (String error : gameErrors) plugin.getLogger().warning(error);
            }
            games.put(definition.id(), definition);
        }

        return new LoadedMinigamesConfig(global, messages, games, errors);
    }

    private GlobalConfig loadGlobal(YamlConfiguration yaml, List<String> errors) {
        String world = yaml.getString("world", "Hex_Minigames");
        int maxPlayers = yaml.getInt("global.max-players", 14);
        int developmentMinimumPlayers = yaml.getInt("development.minimum-players", 2);
        int gamesPerSeries = yaml.getInt("games-per-series", 5);
        int roundCountdown = yaml.getInt("countdowns.round", 5);
        int defaultRound = yaml.getInt("durations.default-round-seconds", 180);
        int roundResults = yaml.getInt("durations.round-results-seconds", 8);
        int intermission = yaml.getInt("durations.intermission-seconds", 8);
        int seriesResults = yaml.getInt("durations.series-results-seconds", 12);
        String storageType = yaml.getString("storage.type", "auto");
        String sqliteFile = yaml.getString("storage.sqlite-file", "minigames.db");
        boolean restoreOnJoin = yaml.getBoolean("inventory.restore-on-join", true);
        boolean ghostAllowFlight = yaml.getBoolean("ghost.allow-flight", true);
        PregameConfig pregame = loadPregame(yaml, world, errors);
        SeriesConfig series = loadSeries(yaml, errors);
        boolean debugEnabled = yaml.getBoolean("debug.enabled", true);
        boolean debugLog = yaml.getBoolean("debug.log-state-transitions", true);
        int debugDuration = yaml.getInt("debug.debug-game-duration-seconds", 10);
        LocationSpec debugSpawn = location(yaml.getConfigurationSection("debug.spawn"));
        ConfiguredSound roundEndSound = sound(
                yaml.getConfigurationSection("global.round-end-sound"),
                true,
                "ITEM_GOAT_HORN_SOUND_0",
                1.0f,
                1.0f
        );

        if (world == null || world.isBlank()) errors.add("config.yml: world must not be empty");
        if (maxPlayers <= 0) errors.add("config.yml: global.max-players must be > 0");
        if (developmentMinimumPlayers <= 0) errors.add("config.yml: development.minimum-players must be > 0");
        if (maxPlayers > 0 && developmentMinimumPlayers > maxPlayers) errors.add("config.yml: development.minimum-players must be <= global.max-players");
        if (gamesPerSeries <= 0) errors.add("config.yml: games-per-series must be > 0");
        if (roundCountdown < 0) errors.add("config.yml: countdowns.round must be >= 0");
        if (defaultRound <= 0) errors.add("config.yml: durations.default-round-seconds must be > 0");
        if (roundResults < 0) errors.add("config.yml: durations.round-results-seconds must be >= 0");
        if (intermission < 0) errors.add("config.yml: durations.intermission-seconds must be >= 0");
        if (seriesResults < 0) errors.add("config.yml: durations.series-results-seconds must be >= 0");
        if (debugDuration <= 0) errors.add("config.yml: debug.debug-game-duration-seconds must be > 0");
        if (!debugSpawn.configured()) errors.add("config.yml: debug.spawn must be configured");
        if (!roundEndSound.validSound()) errors.add("config.yml: global.round-end-sound.sound is not a valid Bukkit Sound: " + roundEndSound.sound());

        return new GlobalConfig(
                world,
                maxPlayers,
                developmentMinimumPlayers,
                gamesPerSeries,
                roundCountdown,
                defaultRound,
                roundResults,
                intermission,
                seriesResults,
                storageType,
                sqliteFile,
                restoreOnJoin,
                ghostAllowFlight,
                pregame,
                series,
                debugEnabled,
                debugLog,
                debugDuration,
                debugSpawn,
                roundEndSound,
                errors
        );
    }

    private PregameConfig loadPregame(YamlConfiguration yaml, String worldName, List<String> errors) {
        boolean enabled = yaml.getBoolean("pregame.enabled", true);
        Optional<CuboidRegion> region = region(worldName, yaml.getConfigurationSection("pregame.region"), "config.yml: pregame.region", enabled, errors);
        LocationSpec spawn = location(yaml.getConfigurationSection("pregame.spawn"));
        int minimumPlayers = yaml.getInt("pregame.minimum-players", yaml.getInt("pregame.min-players", 4));
        int drawDuration = yaml.getInt("pregame.draw-duration-seconds", 10);
        int countdown = yaml.getInt("pregame.countdown-seconds", 10);
        String bossBarTitle = yaml.getString("pregame.bossbar.title", "&d&lPOCZEKALNIA");
        BarColor bossBarColor = barColor(yaml.getString("pregame.bossbar.color", "WHITE"), BarColor.WHITE, "config.yml: pregame.bossbar.color", errors);
        BarStyle bossBarStyle = barStyle(yaml.getString("pregame.bossbar.style", "SOLID"), BarStyle.SOLID, "config.yml: pregame.bossbar.style", errors);
        ConfiguredSound countdownSound = sound(
                yaml.getConfigurationSection("pregame.countdown-sound"),
                true,
                "UI_BUTTON_CLICK",
                0.8f,
                1.4f
        );

        if (enabled && region.isEmpty()) errors.add("config.yml: pregame.region.pos1 and pos2 must contain x/y/z");
        if (enabled && !spawn.configured()) errors.add("config.yml: pregame.spawn must contain x/y/z");
        if (minimumPlayers <= 0) errors.add("config.yml: pregame.minimum-players must be > 0");
        if (drawDuration < 0) errors.add("config.yml: pregame.draw-duration-seconds must be >= 0");
        if (countdown < 0) errors.add("config.yml: pregame.countdown-seconds must be >= 0");

        if (!countdownSound.validSound()) errors.add("config.yml: pregame.countdown-sound.sound is not a valid Bukkit Sound: " + countdownSound.sound());

        return new PregameConfig(enabled, region.orElse(null), spawn, minimumPlayers, drawDuration, countdown, bossBarTitle, bossBarColor, bossBarStyle, countdownSound);
    }

    private SeriesConfig loadSeries(YamlConfiguration yaml, List<String> errors) {
        int minimumContinuation = yaml.getInt("series.minimum-continuation-players", 2);
        if (minimumContinuation <= 0) errors.add("config.yml: series.minimum-continuation-players must be > 0");
        return new SeriesConfig(minimumContinuation);
    }

    private Messages loadMessages(YamlConfiguration yaml) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : yaml.getKeys(true)) {
            if (yaml.isConfigurationSection(key)) continue;
            values.put(key, yaml.getString(key, ""));
        }
        return new Messages(values);
    }

    private MinigameDefinition loadGame(String fileName, YamlConfiguration yaml, GlobalConfig global, List<String> errors) {
        String path = "games/" + fileName;
        String id = yaml.getString("id", fileName.replace(".yml", ""));
        String displayName = yaml.getString("display-name", id);
        boolean enabled = yaml.getBoolean("enabled", false);
        int minPlayers = yaml.getInt("min-players", 1);
        int maxPlayers = yaml.getInt("max-players", 0);
        int weight = yaml.getInt("weight", 1);
        int roundTime = yaml.getInt("round-time-seconds", global.defaultRoundSeconds());
        if ("hot_head".equals(id) && roundTime == 120) roundTime = 60;
        if ("popcorn".equals(id) && (roundTime == 90 || roundTime == 60)) roundTime = 45;
        if ("dalgona".equals(id) && roundTime == 60) roundTime = 80;
        if ("glass_bridge".equals(id) && roundTime == 150) roundTime = 130;
        Optional<CuboidRegion> region = region(global.worldName(), yaml.getConfigurationSection("region"), path, enabled, errors);
        List<LocationSpec> spawns = locationList(yaml.getMapList("participant-spawns"));
        Optional<LocationSpec> spectator = optionalLocation(yaml.getConfigurationSection("spectator-spawn"));
        Map<String, Object> settings = sectionMap(yaml.getConfigurationSection("settings"));

        if (id == null || id.isBlank()) errors.add(path + ": id must not be empty");
        if (minPlayers < 0) errors.add(path + ": min-players must be >= 0");
        if (maxPlayers < 0) errors.add(path + ": max-players must be >= 0");
        if (weight <= 0) errors.add(path + ": weight must be > 0");
        if (roundTime <= 0) errors.add(path + ": round-time-seconds must be > 0");
        return new MinigameDefinition(
                id,
                displayName,
                enabled,
                false,
                false,
                minPlayers,
                maxPlayers,
                weight,
                region,
                spawns,
                spectator,
                roundTime,
                settings,
                path
        );
    }

    private Optional<CuboidRegion> region(String worldName, ConfigurationSection section, String path, boolean enabled, List<String> errors) {
        if (section == null || section.getKeys(false).isEmpty()) return Optional.empty();
        BlockPosition pos1 = block(section.getConfigurationSection("pos1"));
        BlockPosition pos2 = block(section.getConfigurationSection("pos2"));
        if (pos1 == null || pos2 == null) {
            if (enabled) errors.add(path + ": pos1 and pos2 must contain x/y/z");
            return Optional.empty();
        }
        return Optional.of(new CuboidRegion(worldName, pos1, pos2));
    }

    private BlockPosition block(ConfigurationSection section) {
        if (section == null) return null;
        if (!section.contains("x") || !section.contains("y") || !section.contains("z")) return null;
        return new BlockPosition(section.getInt("x"), section.getInt("y"), section.getInt("z"));
    }

    private List<LocationSpec> locationList(List<Map<?, ?>> rawList) {
        List<LocationSpec> out = new ArrayList<>();
        for (Map<?, ?> raw : rawList) {
            LocationSpec spec = location(raw);
            if (spec.configured()) out.add(spec);
        }
        return out;
    }

    private Optional<LocationSpec> optionalLocation(ConfigurationSection section) {
        LocationSpec spec = location(section);
        return spec.configured() ? Optional.of(spec) : Optional.empty();
    }

    private LocationSpec location(ConfigurationSection section) {
        if (section == null || section.getKeys(false).isEmpty()) return LocationSpec.missing();
        return new LocationSpec(
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                (float) section.getDouble("yaw", 0.0),
                (float) section.getDouble("pitch", 0.0),
                section.contains("x") && section.contains("y") && section.contains("z")
        );
    }

    private LocationSpec location(Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) return LocationSpec.missing();
        if (!raw.containsKey("x") || !raw.containsKey("y") || !raw.containsKey("z")) return LocationSpec.missing();
        return new LocationSpec(
                number(raw.get("x"), 0.0),
                number(raw.get("y"), 0.0),
                number(raw.get("z"), 0.0),
                (float) number(raw.get("yaw"), 0.0),
                (float) number(raw.get("pitch"), 0.0),
                true
        );
    }

    private BarColor barColor(String value, BarColor fallback, String path, List<String> errors) {
        try {
            return BarColor.valueOf(value == null ? fallback.name() : value);
        } catch (IllegalArgumentException error) {
            errors.add(path + " is not a valid BossBar color: " + value);
            return fallback;
        }
    }

    private BarStyle barStyle(String value, BarStyle fallback, String path, List<String> errors) {
        try {
            return BarStyle.valueOf(value == null ? fallback.name() : value);
        } catch (IllegalArgumentException error) {
            errors.add(path + " is not a valid BossBar style: " + value);
            return fallback;
        }
    }

    private ConfiguredSound sound(ConfigurationSection section, boolean defaultEnabled, String defaultSound, float defaultVolume, float defaultPitch) {
        if (section == null || section.getKeys(false).isEmpty()) {
            return new ConfiguredSound(defaultEnabled, defaultSound, defaultVolume, defaultPitch);
        }
        return new ConfiguredSound(
                section.getBoolean("enabled", defaultEnabled),
                section.getString("sound", defaultSound),
                (float) section.getDouble("volume", defaultVolume),
                (float) section.getDouble("pitch", defaultPitch)
        );
    }

    private Map<String, Object> sectionMap(ConfigurationSection section) {
        if (section == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            out.put(key, section.get(key));
        }
        return out;
    }

    private double number(Object raw, double fallback) {
        if (raw instanceof Number number) return number.doubleValue();
        if (raw == null) return fallback;
        try {
            return Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void saveResource(String path) {
        File file = new File(plugin.getDataFolder(), path);
        if (!file.isFile()) plugin.saveResource(path, false);
    }
}
