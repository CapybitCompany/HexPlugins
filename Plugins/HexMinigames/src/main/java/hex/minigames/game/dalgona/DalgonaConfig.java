package hex.minigames.game.dalgona;

import hex.minigames.config.ConfiguredSound;
import hex.minigames.game.MinigameDefinition;
import hex.minigames.game.common.BossBarSettings;
import hex.minigames.game.common.CommonGameConfig;
import hex.minigames.game.common.GameSettings;
import hex.minigames.game.common.TutorialSettings;
import hex.minigames.model.BlockPosition;
import hex.minigames.model.CuboidRegion;
import hex.minigames.model.LocationSpec;
import org.bukkit.GameMode;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

public record DalgonaConfig(
        CuboidRegion region,
        int roundDurationSeconds,
        TutorialSettings tutorial,
        BossBarSettings bossBar,
        Material arenaMaterial,
        Material boardMaterial,
        ConfiguredSound completeSound,
        String completeTitle,
        String completeSubtitle,
        String eliminationMessage,
        int completePoints,
        double wrongBlockUpwardVelocity,
        GameMode gameplayGameMode,
        List<Station> stations,
        List<Pattern> patterns
) {
    public static final String ID = "dalgona";
    public static final int PATTERN_WIDTH = 11;
    public static final int PATTERN_HEIGHT = 10;

    public static DalgonaConfig fromDefinition(MinigameDefinition definition, List<String> errors) {
        List<String> targetErrors = errors == null ? new ArrayList<>() : errors;
        String source = definition.sourcePath().isBlank() ? "games/dalgona.yml" : definition.sourcePath();
        Object settings = definition.settings();
        CuboidRegion region = definition.region().orElse(null);
        if (region == null) targetErrors.add(source + ": region must be configured");

        List<Pattern> patterns = patterns(GameSettings.child(settings, "patterns"));
        if (patterns.isEmpty()) patterns = defaultPatterns();

        return new DalgonaConfig(
                region,
                definition.roundTimeSeconds(),
                CommonGameConfig.tutorial(settings, source, defaultTutorialLines(), targetErrors),
                CommonGameConfig.bossBar(settings, source, "&d&lDALGONA", targetErrors),
                GameSettings.material(settings, "materials.arena", Material.SAND, source, targetErrors),
                GameSettings.material(settings, "materials.board", Material.RED_CONCRETE, source, targetErrors),
                GameSettings.sound(settings, "sounds.complete", true, "ENTITY_PLAYER_LEVELUP", 0.9f, 1.0f, source, targetErrors),
                GameSettings.string(settings, "completion.title", ""),
                GameSettings.string(settings, "completion.subtitle", "&aZaliczono &f- &a+1 punkt"),
                GameSettings.string(settings, "messages.eliminated", "&c{player} został wyeliminowany!"),
                Math.max(0, GameSettings.integer(settings, "scoring.complete-points", 1)),
                Math.max(0.0, GameSettings.decimal(settings, "wrong-block.upward-velocity", 1.1)),
                gameMode(settings, "gameplay.game-mode", GameMode.SURVIVAL, source, targetErrors),
                stations(GameSettings.child(settings, "stations"), region == null ? "Hex_Minigames" : region.worldName()),
                patterns
        );
    }

    private static GameMode gameMode(Object settings, String path, GameMode fallback, String source, List<String> errors) {
        String raw = GameSettings.string(settings, path, fallback.name());
        try {
            return GameMode.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            if (errors != null) errors.add(source + ": settings." + path + " is not a valid Bukkit GameMode: " + raw);
            return fallback;
        }
    }

    private static List<Station> stations(Object raw, String world) {
        List<Station> out = new ArrayList<>();
        for (Object entry : GameSettings.list(raw)) {
            CuboidRegion arena = GameSettings.region(GameSettings.child(entry, "arena"), world);
            CuboidRegion board = GameSettings.region(GameSettings.child(entry, "board"), world);
            LocationSpec spawn = GameSettings.location(GameSettings.child(entry, "spawn"));
            if (arena != null && board != null && spawn.configured()) {
                out.add(new Station(GameSettings.integer(entry, "id", out.size() + 1), arena, board, spawn));
            }
        }
        return out.isEmpty() ? defaultStations(world) : out;
    }

    private static List<Pattern> patterns(Object raw) {
        List<Pattern> out = new ArrayList<>();
        for (Object entry : GameSettings.list(raw)) {
            String id = GameSettings.string(entry, "id", "");
            List<String> grid = GameSettings.stringList(GameSettings.child(entry, "grid"), List.of());
            if (!id.isBlank() && grid.size() == PATTERN_HEIGHT) {
                Pattern pattern = new Pattern(id, grid);
                if (DalgonaRuntime.logicalPixels(pattern).size() == PATTERN_WIDTH * PATTERN_HEIGHT) {
                    pattern = new Pattern(id, smallSquare());
                }
                out.add(pattern);
            }
        }
        return out;
    }

    private static List<Station> defaultStations(String world) {
        return List.of(
                station(world, 1, 430, -32, -224, 440, -32, -234, 430, -31, -236, 440, -22, -236, 435, -31, -229),
                station(world, 2, 446, -32, -234, 456, -32, -224, 446, -31, -236, 456, -22, -236, 451, -31, -229),
                station(world, 3, 461, -32, -224, 471, -32, -234, 461, -31, -236, 471, -22, -236, 466, -31, -229),
                station(world, 4, 477, -32, -224, 487, -32, -234, 477, -31, -236, 487, -22, -236, 482, -31, -229),
                station(world, 5, 430, -32, -240, 440, -32, -250, 430, -31, -252, 440, -22, -252, 435, -31, -245),
                station(world, 6, 446, -32, -240, 456, -32, -250, 446, -31, -252, 456, -22, -252, 451, -31, -245),
                station(world, 7, 461, -32, -240, 471, -32, -250, 461, -31, -252, 471, -22, -252, 466, -31, -245),
                station(world, 8, 477, -32, -240, 487, -32, -250, 477, -31, -252, 487, -22, -252, 482, -31, -245),
                station(world, 9, 430, -32, -256, 440, -32, -266, 430, -31, -268, 440, -22, -268, 435, -31, -261),
                station(world, 10, 446, -32, -256, 456, -32, -266, 446, -31, -268, 456, -22, -268, 451, -31, -261),
                station(world, 11, 461, -32, -256, 471, -32, -266, 461, -31, -268, 471, -22, -268, 466, -31, -261),
                station(world, 12, 477, -32, -256, 487, -32, -266, 477, -31, -268, 487, -22, -268, 482, -31, -261),
                station(world, 13, 446, -32, -272, 456, -32, -282, 446, -31, -284, 456, -22, -284, 451, -31, -277),
                station(world, 14, 461, -32, -272, 471, -32, -282, 461, -31, -284, 471, -22, -284, 466, -31, -277)
        );
    }

    private static Station station(String world, int id, int ax1, int ay1, int az1, int ax2, int ay2, int az2,
                                   int bx1, int by1, int bz1, int bx2, int by2, int bz2,
                                   double sx, double sy, double sz) {
        return new Station(
                id,
                new CuboidRegion(world, new BlockPosition(ax1, ay1, az1), new BlockPosition(ax2, ay2, az2)),
                new CuboidRegion(world, new BlockPosition(bx1, by1, bz1), new BlockPosition(bx2, by2, bz2)),
                new LocationSpec(sx, sy, sz, 180.0f, 0.0f, true)
        );
    }

    private static List<Pattern> defaultPatterns() {
        return List.of(
                new Pattern("triangle", List.of(
                        "...........",
                        "...........",
                        ".....#.....",
                        "....###....",
                        "...#####...",
                        "...........",
                        "...........",
                        "...........",
                        "...........",
                        "..........."
                )),
                new Pattern("square", List.of(
                        "...........",
                        "...........",
                        "...#####...",
                        "...#####...",
                        "...#####...",
                        "...#####...",
                        "...#####...",
                        "...........",
                        "...........",
                        "..........."
                )),
                new Pattern("diamond", List.of(
                        "...........",
                        "...........",
                        ".....#.....",
                        "....###....",
                        "...#####...",
                        "....###....",
                        ".....#.....",
                        "...........",
                        "...........",
                        "..........."
                )),
                new Pattern("circle", List.of(
                        "...........",
                        "...........",
                        "....###....",
                        "...#####...",
                        "...#####...",
                        "...#####...",
                        "....###....",
                        "...........",
                        "...........",
                        "..........."
                )),
                new Pattern("heart", List.of(
                        "...........",
                        "...........",
                        "....#.#....",
                        "...#####...",
                        "...#####...",
                        "....###....",
                        ".....#.....",
                        "...........",
                        "...........",
                        "..........."
                )),
                new Pattern("star", List.of(
                        "...........",
                        "...........",
                        ".....#.....",
                        ".....#.....",
                        "...#####...",
                        "....###....",
                        "....#.#....",
                        "...........",
                        "...........",
                        "..........."
                ))
        );
    }

    private static List<String> defaultTutorialLines() {
        return List.of(
                "&d&lDALGONA",
                "&fNa tablicy zobaczysz czerwony wzor.",
                "&fUsun dokladnie odpowiadajace mu bloki piasku.",
                "&cZly blok = eliminacja.",
                "&fUkonczenie wzoru daje &a+1 punkt&f."
        );
    }

    private static List<String> smallSquare() {
        return List.of("...........", "..#######..", "..#######..", "..#######..", "..#######..",
                "..#######..", "..#######..", "..#######..", "...........", "...........");
    }

    public record Station(int id, CuboidRegion arena, CuboidRegion board, LocationSpec spawn) {
    }

    public record Pattern(String id, List<String> grid) {
        public Pattern {
            grid = grid == null ? List.of() : List.copyOf(grid);
        }
    }
}
