package hex.minigames.game.popcorn;

import hex.minigames.game.MinigameDefinition;
import hex.minigames.game.common.BossBarSettings;
import hex.minigames.game.common.CommonGameConfig;
import hex.minigames.game.common.GameSettings;
import hex.minigames.game.common.TutorialSettings;
import hex.minigames.model.BlockPosition;
import hex.minigames.model.CuboidRegion;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

public record PopcornConfig(
        CuboidRegion region,
        CuboidRegion platform,
        int roundDurationSeconds,
        TutorialSettings tutorial,
        BossBarSettings bossBar,
        Material whiteMaterial,
        Material yellowMaterial,
        Material orangeMaterial,
        Material redMaterial,
        int stageDurationTicks,
        double activationRateStart,
        double activationRateEnd,
        double accelerationCurve,
        int targetRemainingBlocks,
        String eliminationMessage,
        int survivorPoints
) {
    public static final String ID = "popcorn";

    public static PopcornConfig fromDefinition(MinigameDefinition definition, List<String> errors) {
        List<String> targetErrors = errors == null ? new ArrayList<>() : errors;
        String source = definition.sourcePath().isBlank() ? "games/popcorn.yml" : definition.sourcePath();
        Object settings = definition.settings();
        CuboidRegion region = definition.region().orElse(null);
        if (region == null) targetErrors.add(source + ": region must be configured");
        String world = region == null ? "Hex_Minigames" : region.worldName();
        CuboidRegion platform = GameSettings.region(GameSettings.child(settings, "platform"), world);
        if (platform == null) platform = new CuboidRegion(world, new BlockPosition(608, -26, -110), new BlockPosition(580, -26, -153));

        return new PopcornConfig(
                region,
                platform,
                definition.roundTimeSeconds(),
                CommonGameConfig.tutorial(settings, source, defaultTutorialLines(), targetErrors),
                CommonGameConfig.bossBar(settings, source, "&d&lPOPCORN", targetErrors),
                GameSettings.material(settings, "materials.white", Material.WHITE_CONCRETE, source, targetErrors),
                GameSettings.material(settings, "materials.yellow", Material.YELLOW_CONCRETE, source, targetErrors),
                GameSettings.material(settings, "materials.orange", Material.ORANGE_CONCRETE, source, targetErrors),
                GameSettings.material(settings, "materials.red", Material.RED_CONCRETE, source, targetErrors),
                Math.max(1, (int) hazardTiming(settings, "hazard.stage-duration-ticks", 60, 4, 8)),
                Math.max(0.0, hazardTiming(settings, "hazard.activation-rate-start", 1.0, 18.0, 48.0)),
                Math.max(0.0, hazardTiming(settings, "hazard.activation-rate-end", 32.0, 48.0, 80.0)),
                Math.max(0.1, GameSettings.decimal(settings, "hazard.acceleration-curve", 1.8)),
                Math.max(0, GameSettings.integer(settings, "hazard.target-remaining-blocks", 12)),
                GameSettings.string(settings, "messages.eliminated", "&c{player} został wyeliminowany!"),
                Math.max(0, GameSettings.integer(settings, "scoring.survivor-points", 2))
        );
    }

    public List<BlockPosition> platformBlocks() {
        List<BlockPosition> out = new ArrayList<>();
        for (int x = platform.minX(); x <= platform.maxX(); x++) {
            for (int z = platform.minZ(); z <= platform.maxZ(); z++) out.add(new BlockPosition(x, platform.minY(), z));
        }
        return out;
    }

    /** Applies the faster timings to existing installations using the former defaults. */
    private static double hazardTiming(Object settings, String path, double originalDefault, double previousDefault, double fallback) {
        double value = GameSettings.decimal(settings, path, fallback);
        return value == originalDefault || value == previousDefault ? fallback : value;
    }

    private static List<String> defaultTutorialLines() {
        return List.of(
                "&d&lPOPCORN",
                "&fObserwuj kolory blokow pod nogami.",
                "&fBialy -> zolty -> pomaranczowy -> czerwony -> znika.",
                "&fNie spadnij z platformy.",
                "&fPrzetrwaj &e{duration} sekund &f= &a+2 punkty&f."
        );
    }
}
