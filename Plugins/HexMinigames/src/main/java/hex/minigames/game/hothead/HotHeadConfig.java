package hex.minigames.game.hothead;

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

public record HotHeadConfig(
        CuboidRegion region,
        LocationSpec gameplaySpawn,
        int roundDurationSeconds,
        TutorialSettings tutorial,
        BossBarSettings bossBar,
        AxisConfig axis1,
        AxisConfig axis2,
        int warningTicks,
        int waveStepDelayTicks,
        int segmentHoldTicks,
        int cycleGapTicks,
        boolean allowFireDamage,
        Material segmentMaterial,
        String eliminationMessage,
        int survivorPoints
) {
    public static final String ID = "hot_head";

    public static HotHeadConfig fromDefinition(MinigameDefinition definition, List<String> errors) {
        List<String> targetErrors = errors == null ? new ArrayList<>() : errors;
        String source = definition.sourcePath().isBlank() ? "games/hot_head.yml" : definition.sourcePath();
        Object settings = definition.settings();
        CuboidRegion region = definition.region().orElse(null);
        if (region == null) targetErrors.add(source + ": region must be configured");
        String world = region == null ? "Hex_Minigames" : region.worldName();
        LocationSpec gameplaySpawn = GameSettings.location(GameSettings.child(settings, "gameplay-spawn"));
        if (!gameplaySpawn.configured()) gameplaySpawn = new LocationSpec(-18.0, -31.0, 39.0, 0.0f, 0.0f, true);

        return new HotHeadConfig(
                region,
                gameplaySpawn,
                definition.roundTimeSeconds(),
                CommonGameConfig.tutorial(settings, source, defaultTutorialLines(), targetErrors),
                CommonGameConfig.bossBar(settings, source, "&d&lHOT HEAD", targetErrors),
                axisConfig(settings, "axis1", true, defaultAxis1(), source, targetErrors),
                axisConfig(settings, "axis2", true, defaultAxis2(), source, targetErrors),
                waveTiming(settings, "wave.warning-ticks", 40, 10),
                waveTiming(settings, "wave.step-delay-ticks", 8, 1, 2),
                waveTiming(settings, "wave.segment-hold-ticks", 10, 3, 20),
                waveTiming(settings, "wave.cycle-gap-ticks", 40, 6, 20),
                GameSettings.bool(settings, "fire-damage-exception", true),
                GameSettings.material(settings, "wave.segment-material", Material.LIGHT_GRAY_CONCRETE, source, targetErrors),
                GameSettings.string(settings, "messages.eliminated", "&c{player} został wyeliminowany!"),
                Math.max(0, GameSettings.integer(settings, "scoring.survivor-points", 2))
        );
    }

    private static AxisConfig axisConfig(Object settings, String path, boolean defaultEnabled, List<Lane> lanes, String source, List<String> errors) {
        Object raw = GameSettings.child(settings, path);
        return new AxisConfig(
                GameSettings.bool(raw, "enabled", defaultEnabled),
                lanes
        );
    }

    /** Upgrades old bundled timings without overwriting custom arena settings. */
    private static int waveTiming(Object settings, String path, int previousDefault, int fallback) {
        int value = GameSettings.integer(settings, path, fallback);
        return Math.max(1, value == previousDefault ? fallback : value);
    }

    private static int waveTiming(Object settings, String path, int originalDefault, int previousDefault, int fallback) {
        int value = GameSettings.integer(settings, path, fallback);
        return Math.max(1, value == originalDefault || value == previousDefault ? fallback : value);
    }

    private static List<Lane> defaultAxis1() {
        List<Lane> lanes = new ArrayList<>();
        int index = 1;
        for (int z = 37; z <= 53; z += 2) {
            List<BlockPosition> lamps = List.of(new BlockPosition(-9, -31, z), new BlockPosition(-9, -31, z + 1));
            List<List<BlockPosition>> segments = new ArrayList<>();
            for (int x = -10; x >= -27; x -= 2) {
                segments.add(rect(x, -32, z, x - 1, -32, z + 1));
            }
            lanes.add(new Lane(index++, lamps, segments));
        }
        return lanes;
    }

    private static List<Lane> defaultAxis2() {
        List<Lane> lanes = new ArrayList<>();
        int index = 1;
        for (int x = -10; x >= -26; x -= 2) {
            List<BlockPosition> lamps = List.of(new BlockPosition(x, -31, 55), new BlockPosition(x - 1, -31, 55));
            List<List<BlockPosition>> segments = new ArrayList<>();
            for (int z = 54; z >= 37; z -= 2) {
                segments.add(rect(x, -32, z, x - 1, -32, z - 1));
            }
            lanes.add(new Lane(index++, lamps, segments));
        }
        return lanes;
    }

    private static List<BlockPosition> rect(int x1, int y1, int z1, int x2, int y2, int z2) {
        List<BlockPosition> out = new ArrayList<>();
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2);
        int maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) out.add(new BlockPosition(x, y, z));
            }
        }
        return out;
    }

    private static List<String> defaultTutorialLines() {
        return List.of(
                "&d&lHOT HEAD",
                "&fLampy ostrzegaja, ktory pas za chwile sie podniesie.",
                "&fUnikaj fal podnoszacej sie podlogi i ognia.",
                "&fPrzetrwaj &e{duration} sekund&f, aby zdobyc punkty."
        );
    }

    public record AxisConfig(boolean enabled, List<Lane> lanes) {
        public AxisConfig {
            lanes = lanes == null ? List.of() : List.copyOf(lanes);
        }
    }

    public record Lane(int id, List<BlockPosition> lamps, List<List<BlockPosition>> segments) {
        public Lane {
            lamps = lamps == null ? List.of() : List.copyOf(lamps);
            segments = segments == null ? List.of() : List.copyOf(segments);
        }
    }
}
