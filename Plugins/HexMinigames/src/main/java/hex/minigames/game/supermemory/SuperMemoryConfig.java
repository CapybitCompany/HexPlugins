package hex.minigames.game.supermemory;

import hex.minigames.config.ConfiguredSound;
import hex.minigames.game.MinigameDefinition;
import hex.minigames.model.BlockPosition;
import hex.minigames.model.CuboidRegion;
import hex.minigames.model.LocationSpec;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record SuperMemoryConfig(
        String displayName,
        CuboidRegion gameRegion,
        int roundDurationSeconds,
        int tutorialDurationSeconds,
        String tutorialTitle,
        String tutorialSubtitle,
        int tutorialTitleStayTicks,
        ConfiguredSound tutorialTickSound,
        List<String> tutorialChatLines,
        BossBarSettings bossBar,
        String actionbarActive,
        String actionbarFinished,
        String completionTitle,
        String completionSubtitle,
        int completionTitleStayTicks,
        Material idleMaterial,
        Material correctMaterial,
        Material wrongMaterial,
        int wrongResetDelayTicks,
        ConfiguredSound correctSound,
        ConfiguredSound wrongSound,
        ConfiguredSound completionLevelUpSound,
        ConfiguredSound completionChimeSound,
        ConfiguredSound completionCelebrateSound,
        List<Integer> placementPoints,
        ResultsFormat results,
        List<StationConfig> stations
) {
    public static final String ID = "super_memory";
    public static final int REQUIRED_STATIONS = 14;
    public static final int BLOCKS_PER_STATION = 8;

    public SuperMemoryConfig {
        tutorialChatLines = tutorialChatLines == null ? List.of() : List.copyOf(tutorialChatLines);
        placementPoints = placementPoints == null ? List.of() : List.copyOf(placementPoints);
        stations = stations == null ? List.of() : List.copyOf(stations);
    }

    public static SuperMemoryConfig fromDefinition(MinigameDefinition definition, List<String> errors) {
        List<String> targetErrors = errors == null ? new ArrayList<>() : errors;
        String source = definition.sourcePath().isBlank() ? "games/super_memory.yml" : definition.sourcePath();
        Object settings = definition.settings();
        CuboidRegion gameRegion = definition.region().orElse(null);
        if (gameRegion == null) {
            targetErrors.add(source + ": region must be configured for super_memory");
        }

        int roundDuration = definition.roundTimeSeconds();
        if (roundDuration <= 0) {
            targetErrors.add(source + ": round-time-seconds must be > 0");
            roundDuration = 90;
        }

        Material idle = material(settings, "materials.idle", Material.WHITE_CONCRETE, source, targetErrors);
        Material correct = material(settings, "materials.correct", Material.LIME_CONCRETE, source, targetErrors);
        Material wrong = material(settings, "materials.wrong", Material.RED_CONCRETE, source, targetErrors);
        int wrongResetDelay = positiveInt(settings, "wrong-reset-delay-ticks", 12, source, targetErrors);

        int tutorialDuration = positiveInt(settings, "tutorial.duration-seconds", 15, source, targetErrors);
        String tutorialTitle = string(settings, "tutorial.title", "&6&lZASADY NA CZACIE");
        String tutorialSubtitle = string(settings, "tutorial.subtitle", "&e{seconds}");
        int tutorialStay = intValue(child(settings, "tutorial.title-stay-ticks"), 25);
        if (tutorialStay <= 20) targetErrors.add(source + ": settings.tutorial.title-stay-ticks must be > 20");
        ConfiguredSound tutorialTickSound = sound(settings, "tutorial.tick-sound", true, "UI_BUTTON_CLICK", 0.8f, 1.4f, source, targetErrors);
        List<String> chatLines = stringList(child(settings, "tutorial.chat-lines"), defaultTutorialChatLines());

        BossBarSettings bossBar = new BossBarSettings(
                string(settings, "bossbar.title", "&6SUPER-PAMIEC"),
                barColor(settings, "bossbar.color", BarColor.YELLOW, source, targetErrors),
                barStyle(settings, "bossbar.style", BarStyle.SOLID, source, targetErrors)
        );

        String actionbarActive = string(settings, "actionbar.active", "&fPostep: &e{progress}/8 &8| &fCzas: &e{remaining}");
        String actionbarFinished = string(settings, "actionbar.finished", "&aUkonczono! &8| &fTwoj czas: &e{completion_time}");
        String completionTitle = string(settings, "completion.title", "&a&lUKONCZONO!");
        String completionSubtitle = string(settings, "completion.subtitle", "&fTwoj czas: &e{time}");
        int completionStay = intValue(child(settings, "completion.stay-ticks"), 50);
        if (completionStay <= 0) targetErrors.add(source + ": settings.completion.stay-ticks must be > 0");

        ConfiguredSound correctSound = sound(settings, "sounds.correct", true, "UI_BUTTON_CLICK", 0.8f, 1.5f, source, targetErrors);
        ConfiguredSound wrongSound = sound(settings, "sounds.wrong", true, "ENTITY_VILLAGER_NO", 1.0f, 1.0f, source, targetErrors);
        ConfiguredSound completionLevelUp = sound(settings, "sounds.completion.level-up", true, "ENTITY_PLAYER_LEVELUP", 0.9f, 1.0f, source, targetErrors);
        ConfiguredSound completionChime = sound(settings, "sounds.completion.chime", true, "BLOCK_AMETHYST_BLOCK_CHIME", 0.9f, 1.2f, source, targetErrors);
        ConfiguredSound completionCelebrate = sound(settings, "sounds.completion.celebrate", true, "ENTITY_VILLAGER_CELEBRATE", 0.9f, 1.2f, source, targetErrors);

        List<Integer> placementPoints = intList(child(settings, "scoring.placements"), List.of(4, 3, 2, 1, 1, 1, 1, 0));
        if (placementPoints.size() < 8) {
            targetErrors.add(source + ": settings.scoring.placements must contain at least 8 entries");
        }
        for (int i = 0; i < placementPoints.size(); i++) {
            if (placementPoints.get(i) < 0) {
                targetErrors.add(source + ": settings.scoring.placements[" + i + "] must be >= 0");
            }
        }

        ResultsFormat results = new ResultsFormat(
                stringList(child(settings, "results.header-lines"), List.of(
                        "&8&m----------------------------------------",
                        "&6&lSUPER-PAMIEC &8» &fWyniki",
                        "&7"
                )),
                string(settings, "results.placement-line", "&6{place}. &f{player} &8- &e{time} &8(&a+{points} pkt&8)"),
                string(settings, "results.dnf-line", "&8DNF: &7{players}"),
                string(settings, "results.footer-line", "&8&m----------------------------------------")
        );

        List<StationConfig> stations = stations(child(settings, "stations"), gameRegion, source, targetErrors);

        return new SuperMemoryConfig(
                definition.displayName(),
                gameRegion,
                roundDuration,
                tutorialDuration,
                tutorialTitle,
                tutorialSubtitle,
                tutorialStay,
                tutorialTickSound,
                chatLines,
                bossBar,
                actionbarActive,
                actionbarFinished,
                completionTitle,
                completionSubtitle,
                completionStay,
                idle,
                correct,
                wrong,
                wrongResetDelay,
                correctSound,
                wrongSound,
                completionLevelUp,
                completionChime,
                completionCelebrate,
                placementPoints,
                results,
                stations
        );
    }

    public int pointsForPlacement(int placement) {
        if (placement <= 0 || placement > placementPoints.size()) return 0;
        return placementPoints.get(placement - 1);
    }

    private static List<StationConfig> stations(Object raw, CuboidRegion gameRegion, String source, List<String> errors) {
        List<?> rawStations = raw instanceof List<?> list ? list : List.of();
        if (rawStations.size() != REQUIRED_STATIONS) {
            errors.add(source + ": settings.stations must contain exactly " + REQUIRED_STATIONS + " stations");
        }

        List<StationConfig> out = new ArrayList<>();
        Set<BlockPosition> globalBlocks = new HashSet<>();
        for (int i = 0; i < rawStations.size(); i++) {
            String path = "settings.stations[" + i + "]";
            Object station = rawStations.get(i);
            int index = intValue(child(station, "id"), i + 1);
            CuboidRegion stationRegion = region(child(station, "region"), gameRegion == null ? "" : gameRegion.worldName(), source + ": " + path + ".region", errors);
            LocationSpec spawn = location(child(station, "spawn"));
            List<BlockPosition> clickBlocks = blocks(child(station, "click-blocks"));

            if (stationRegion == null) {
                errors.add(source + ": " + path + ".region.pos1 and pos2 must contain x/y/z");
            } else if (gameRegion != null && !gameRegion.contains(stationRegion)) {
                errors.add(source + ": " + path + ".region must be inside region");
            }

            if (!spawn.configured()) {
                errors.add(source + ": " + path + ".spawn must contain x/y/z");
            } else if (stationRegion != null && !stationRegion.contains(block(spawn))) {
                errors.add(source + ": " + path + ".spawn must be inside station region");
            }

            Set<BlockPosition> unique = new LinkedHashSet<>(clickBlocks);
            if (clickBlocks.size() != BLOCKS_PER_STATION) {
                errors.add(source + ": " + path + ".click-blocks must contain exactly " + BLOCKS_PER_STATION + " blocks");
            }
            if (unique.size() != clickBlocks.size()) {
                errors.add(source + ": " + path + ".click-blocks must be unique");
            }
            for (int blockIndex = 0; blockIndex < clickBlocks.size(); blockIndex++) {
                BlockPosition clickBlock = clickBlocks.get(blockIndex);
                String blockPath = source + ": " + path + ".click-blocks[" + blockIndex + "]";
                if (stationRegion != null && !stationRegion.contains(clickBlock)) {
                    errors.add(blockPath + " must be inside station region");
                }
                if (gameRegion != null && !gameRegion.contains(clickBlock)) {
                    errors.add(blockPath + " must be inside game region");
                }
                if (!globalBlocks.add(clickBlock)) {
                    errors.add(blockPath + " duplicates a click-block used by another station");
                }
            }

            out.add(new StationConfig(index, stationRegion, spawn, clickBlocks));
        }
        return out;
    }

    private static CuboidRegion region(Object raw, String worldName, String path, List<String> errors) {
        BlockPosition pos1 = block(child(raw, "pos1"));
        BlockPosition pos2 = block(child(raw, "pos2"));
        if (pos1 == null || pos2 == null) return null;
        return new CuboidRegion(worldName, pos1, pos2);
    }

    private static List<BlockPosition> blocks(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<BlockPosition> out = new ArrayList<>();
        for (Object entry : list) {
            BlockPosition block = block(entry);
            if (block != null) out.add(block);
        }
        return out;
    }

    private static BlockPosition block(Object raw) {
        if (raw == null) return null;
        Object x = child(raw, "x");
        Object y = child(raw, "y");
        Object z = child(raw, "z");
        if (x == null || y == null || z == null) return null;
        return new BlockPosition(intValue(x, 0), intValue(y, 0), intValue(z, 0));
    }

    private static BlockPosition block(LocationSpec location) {
        return new BlockPosition((int) Math.floor(location.x()), (int) Math.floor(location.y()), (int) Math.floor(location.z()));
    }

    private static LocationSpec location(Object raw) {
        if (raw == null) return LocationSpec.missing();
        Object x = child(raw, "x");
        Object y = child(raw, "y");
        Object z = child(raw, "z");
        if (x == null || y == null || z == null) return LocationSpec.missing();
        return new LocationSpec(
                doubleValue(x, 0.0),
                doubleValue(y, 0.0),
                doubleValue(z, 0.0),
                (float) doubleValue(child(raw, "yaw"), 0.0),
                (float) doubleValue(child(raw, "pitch"), 0.0),
                true
        );
    }

    private static Material material(Object settings, String path, Material fallback, String source, List<String> errors) {
        String value = string(settings, path, fallback.name());
        Material material = Material.matchMaterial(value);
        if (material == null) {
            errors.add(source + ": settings." + path + " is not a valid Bukkit Material: " + value);
            return fallback;
        }
        return material;
    }

    private static BarColor barColor(Object settings, String path, BarColor fallback, String source, List<String> errors) {
        String value = string(settings, path, fallback.name());
        try {
            return BarColor.valueOf(value);
        } catch (IllegalArgumentException error) {
            errors.add(source + ": settings." + path + " is not a valid BossBar color: " + value);
            return fallback;
        }
    }

    private static BarStyle barStyle(Object settings, String path, BarStyle fallback, String source, List<String> errors) {
        String value = string(settings, path, fallback.name());
        try {
            return BarStyle.valueOf(value);
        } catch (IllegalArgumentException error) {
            errors.add(source + ": settings." + path + " is not a valid BossBar style: " + value);
            return fallback;
        }
    }

    private static ConfiguredSound sound(Object settings, String path, boolean defaultEnabled, String defaultSound, float defaultVolume, float defaultPitch, String source, List<String> errors) {
        Object raw = child(settings, path);
        boolean enabled = raw == null ? defaultEnabled : booleanValue(child(raw, "enabled"), defaultEnabled);
        String sound = raw == null ? defaultSound : string(raw, "sound", defaultSound);
        float volume = (float) (raw == null ? defaultVolume : doubleValue(child(raw, "volume"), defaultVolume));
        float pitch = (float) (raw == null ? defaultPitch : doubleValue(child(raw, "pitch"), defaultPitch));
        ConfiguredSound configured = new ConfiguredSound(enabled, sound, volume, pitch);
        if (!configured.validSound()) {
            errors.add(source + ": settings." + path + ".sound is not a valid Bukkit Sound: " + configured.sound());
        }
        return configured;
    }

    private static int positiveInt(Object settings, String path, int fallback, String source, List<String> errors) {
        int value = intValue(child(settings, path), fallback);
        if (value <= 0) {
            errors.add(source + ": settings." + path + " must be > 0");
            return fallback;
        }
        return value;
    }

    private static Object child(Object raw, String path) {
        if (raw == null || path == null || path.isBlank()) return raw;
        Object current = raw;
        for (String part : path.split("\\.")) {
            if (current == null) return null;
            current = directChild(current, part);
        }
        return current;
    }

    private static Object directChild(Object raw, String key) {
        if (raw instanceof ConfigurationSection section) return section.get(key);
        if (raw instanceof Map<?, ?> map) return map.get(key);
        return null;
    }

    private static String string(Object settings, String path, String fallback) {
        Object value = child(settings, path);
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean booleanValue(Object raw, boolean fallback) {
        if (raw instanceof Boolean value) return value;
        if (raw == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    private static int intValue(Object raw, int fallback) {
        if (raw instanceof Number number) return number.intValue();
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double doubleValue(Object raw, double fallback) {
        if (raw instanceof Number number) return number.doubleValue();
        if (raw == null) return fallback;
        try {
            return Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static List<String> stringList(Object raw, List<String> fallback) {
        if (!(raw instanceof List<?> list)) return List.copyOf(fallback);
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            out.add(String.valueOf(item));
        }
        return out;
    }

    private static List<Integer> intList(Object raw, List<Integer> fallback) {
        if (!(raw instanceof List<?> list)) return List.copyOf(fallback);
        List<Integer> out = new ArrayList<>();
        for (Object item : list) {
            out.add(intValue(item, 0));
        }
        return out;
    }

    private static List<String> defaultTutorialChatLines() {
        return List.of(
                "&8&m----------------------------------------",
                "&6&lSUPER-PAMIEC",
                "&7Odgadnij poprawna kolejnosc &f8 blokow&7.",
                "&7Poprawny wybor zmieni blok na &aZIELONY&7.",
                "&7Bledny wybor zmieni blok na &cCZERWONY",
                "&7i wyzeruje caly Twoj aktualny postep.",
                "&7",
                "&7Sekwencja po bledzie &fnie zmienia sie&7.",
                "&7Masz &e90 sekund&7.",
                "&7Liczy sie czas ukonczenia.",
                "&8&m----------------------------------------"
        );
    }

    public record StationConfig(int id, CuboidRegion region, LocationSpec spawn, List<BlockPosition> clickBlocks) {
        public StationConfig {
            clickBlocks = clickBlocks == null ? List.of() : List.copyOf(clickBlocks);
        }
    }

    public record BossBarSettings(String title, BarColor color, BarStyle style) {
    }

    public record ResultsFormat(List<String> headerLines, String placementLine, String dnfLine, String footerLine) {
        public ResultsFormat {
            headerLines = headerLines == null ? List.of() : List.copyOf(headerLines);
        }
    }
}
