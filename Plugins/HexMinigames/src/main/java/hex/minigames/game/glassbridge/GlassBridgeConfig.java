package hex.minigames.game.glassbridge;

import hex.minigames.game.MinigameDefinition;
import hex.minigames.game.common.BossBarSettings;
import hex.minigames.game.common.CommonGameConfig;
import hex.minigames.game.common.GameSettings;
import hex.minigames.game.common.TutorialSettings;
import hex.minigames.model.BlockPosition;
import hex.minigames.model.CuboidRegion;
import hex.minigames.model.LocationSpec;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

public record GlassBridgeConfig(
        CuboidRegion region,
        LocationSpec respawnSpawn,
        CuboidRegion finishRegion,
        int roundDurationSeconds,
        TutorialSettings tutorial,
        BossBarSettings bossBar,
        Material glassMaterial,
        int respawnDelayTicks,
        int fallY,
        CuboidRegion startBoundary,
        int finishBonus,
        int progressOriginZ,
        String actionbar,
        int actionbarUpdateTicks,
        List<Pair> pairs
) {
    public static final String ID = "glass_bridge";

    public static GlassBridgeConfig fromDefinition(MinigameDefinition definition, List<String> errors) {
        List<String> targetErrors = errors == null ? new ArrayList<>() : errors;
        String source = definition.sourcePath().isBlank() ? "games/glass_bridge.yml" : definition.sourcePath();
        Object settings = definition.settings();
        CuboidRegion region = definition.region().orElse(null);
        if (region == null) targetErrors.add(source + ": region must be configured");
        String world = region == null ? "Hex_Minigames" : region.worldName();
        CuboidRegion finish = GameSettings.region(GameSettings.child(settings, "finish-region"), world);
        if (finish == null) finish = new CuboidRegion(world, new BlockPosition(211, 26, -67), new BlockPosition(214, 29, -67));
        CuboidRegion startBoundary = GameSettings.region(GameSettings.child(settings, "start-boundary"), world);
        if (startBoundary == null) startBoundary = new CuboidRegion(world, new BlockPosition(210, 28, 3), new BlockPosition(215, 28, 3));

        return new GlassBridgeConfig(
                region,
                !definition.participantSpawns().isEmpty() ? definition.participantSpawns().get(0)
                        : GameSettings.location(GameSettings.child(settings, "respawn-spawn")).configured()
                        ? GameSettings.location(GameSettings.child(settings, "respawn-spawn"))
                        : GameSettings.location(GameSettings.child(settings, "ghost-respawn-spawn")).configured()
                        ? GameSettings.location(GameSettings.child(settings, "ghost-respawn-spawn"))
                        : new LocationSpec(212.0, 27.0, 8.0, 180.0f, 0.0f, true),
                finish,
                definition.roundTimeSeconds(),
                CommonGameConfig.tutorial(settings, source, defaultTutorialLines(), targetErrors),
                CommonGameConfig.bossBar(settings, source, "&d&lSZKLANY MOST", targetErrors),
                GameSettings.material(settings, "materials.glass", Material.GLASS, source, targetErrors),
                respawnDelayTicks(settings),
                GameSettings.integer(settings, "fall-y", -26),
                startBoundary,
                Math.max(0, GameSettings.integer(settings, "scoring.finish-bonus", 1)),
                GameSettings.integer(settings, "progress-origin-z", 0),
                actionbar(settings),
                Math.max(1, GameSettings.integer(settings, "actionbar-update-ticks", 2)),
                defaultPairs(world)
        );
    }

    private static int respawnDelayTicks(Object settings) {
        Object rawTicks = GameSettings.child(settings, "respawn-delay-ticks");
        if (rawTicks != null) return Math.max(0, GameSettings.intValue(rawTicks, 200));
        Object rawSeconds = GameSettings.child(settings, "respawn-delay-seconds");
        if (rawSeconds != null) return Math.max(0, (int) Math.round(GameSettings.doubleValue(rawSeconds, 10.0) * 20.0));
        return Math.max(0, GameSettings.integer(settings, "ghost.respawn-delay-ticks", 200));
    }

    private static String actionbar(Object settings) {
        Object nested = GameSettings.child(settings, "actionbar.text");
        if (nested != null) return String.valueOf(nested);
        return GameSettings.string(settings, "actionbar", "&fCzas: &e{remaining} &8| &fPostep: &e{meters}m");
    }

    private static List<Pair> defaultPairs(String world) {
        List<Pair> out = new ArrayList<>();
        int index = 1;
        for (int z = 0; z >= -60; z -= 6) {
            CuboidRegion left = new CuboidRegion(world, new BlockPosition(209, 26, z), new BlockPosition(211, 26, z - 3));
            CuboidRegion right = new CuboidRegion(world, new BlockPosition(214, 26, z - 3), new BlockPosition(216, 26, z));
            out.add(new Pair(index++, new Platform(left), new Platform(right)));
        }
        return out;
    }

    private static List<String> defaultTutorialLines() {
        return List.of(
                "&d&lSZKLANY MOST",
                "&fW kazdej parze tylko jedna platforma jest bezpieczna.",
                "&fZla szyba peka po wskoczeniu na nia.",
                "&fIm dalej dotrzesz, tym lepszy bedzie Twoj wynik.",
                "&fDotarcie do mety daje dodatkowy &a+1 punkt&f."
        );
    }

    public record Pair(int index, Platform left, Platform right) {
    }

    public record Platform(CuboidRegion region) {
        public List<BlockPosition> blocks() {
            List<BlockPosition> out = new ArrayList<>();
            for (int x = region.minX(); x <= region.maxX(); x++) {
                for (int y = region.minY(); y <= region.maxY(); y++) {
                    for (int z = region.minZ(); z <= region.maxZ(); z++) out.add(new BlockPosition(x, y, z));
                }
            }
            return out;
        }
    }
}
