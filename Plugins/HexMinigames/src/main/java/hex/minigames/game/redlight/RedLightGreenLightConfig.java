package hex.minigames.game.redlight;

import hex.minigames.config.ConfiguredSound;
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

public record RedLightGreenLightConfig(
        CuboidRegion region,
        LocationSpec resetSpawn,
        CuboidRegion startLine,
        CuboidRegion finishRegion,
        int roundDurationSeconds,
        TutorialSettings tutorial,
        BossBarSettings bossBar,
        int greenMinTicks,
        int greenMaxTicks,
        int redMinTicks,
        int redMaxTicks,
        int redGraceTicks,
        double movementEpsilon,
        double rotationEpsilon,
        Material greenInventoryMaterial,
        Material redInventoryMaterial,
        ConfiguredSound greenSound,
        ConfiguredSound redSound,
        String actionbarActive,
        String actionbarFinished,
        String violationMessage,
        int firstPlacePoints,
        int secondToFourthPoints,
        int fifthToSeventhPoints
) {
    public static final String ID = "red_light_green_light";

    public static RedLightGreenLightConfig fromDefinition(MinigameDefinition definition, List<String> errors) {
        List<String> targetErrors = errors == null ? new ArrayList<>() : errors;
        String source = definition.sourcePath().isBlank() ? "games/red_light_green_light.yml" : definition.sourcePath();
        Object settings = definition.settings();
        String world = definition.region().map(CuboidRegion::worldName).orElse("Hex_Minigames");
        CuboidRegion region = definition.region().orElse(null);
        if (region == null) targetErrors.add(source + ": region must be configured");

        CuboidRegion startLine = GameSettings.region(GameSettings.child(settings, "start-line"), world);
        if (startLine == null) {
            startLine = new CuboidRegion(world, new BlockPosition(199, -31, -271), new BlockPosition(149, -12, -271));
        }
        CuboidRegion finish = GameSettings.region(GameSettings.child(settings, "finish-region"), world);
        if (finish == null) {
            finish = new CuboidRegion(world, new BlockPosition(149, -31, -189), new BlockPosition(199, -20, -189));
        }

        int lightMin = Math.max(1, GameSettings.integer(settings, "lights.light-duration-min-ticks", 30));
        int lightMax = Math.max(1, GameSettings.integer(settings, "lights.light-duration-max-ticks", 70));
        if (lightMax < lightMin) targetErrors.add(source + ": settings.lights.light-duration-max-ticks must be >= light-duration-min-ticks");

        return new RedLightGreenLightConfig(
                region,
                definition.participantSpawns().isEmpty()
                        ? new LocationSpec(173.0, -30.0, -274.0, 0.0f, 0.0f, true)
                        : definition.participantSpawns().get(0),
                startLine,
                finish,
                definition.roundTimeSeconds(),
                CommonGameConfig.tutorial(settings, source, defaultTutorialLines(), targetErrors),
                CommonGameConfig.bossBar(settings, source, "&d&lCZERWONE-ZIELONE", targetErrors),
                lightMin,
                Math.max(lightMin, lightMax),
                lightMin,
                Math.max(lightMin, lightMax),
                Math.max(0, GameSettings.integer(settings, "lights.red-grace-ticks", 15)),
                Math.max(0.015, GameSettings.decimal(settings, "movement.position-epsilon", 0.015)),
                Math.max(0.5, GameSettings.decimal(settings, "movement.rotation-epsilon", 0.5)),
                GameSettings.material(settings, "inventory.green-material", Material.LIME_CONCRETE, source, targetErrors),
                GameSettings.material(settings, "inventory.red-material", Material.RED_CONCRETE, source, targetErrors),
                GameSettings.sound(settings, "sounds.green", true, "BLOCK_NOTE_BLOCK_PLING", 1.0f, 1.5f, source, targetErrors),
                GameSettings.sound(settings, "sounds.red", true, "BLOCK_NOTE_BLOCK_BASS", 1.0f, 0.6f, source, targetErrors),
                GameSettings.string(settings, "actionbar.active", "&fCzas: &e{remaining}"),
                GameSettings.string(settings, "actionbar.finished", "&fCzas: &e{remaining} &8| &aTwój czas: &f{time}"),
                GameSettings.string(settings, "messages.violation", ""),
                Math.max(0, GameSettings.integer(settings, "scoring.first", 3)),
                Math.max(0, GameSettings.integer(settings, "scoring.second-to-fourth", 2)),
                Math.max(0, GameSettings.integer(settings, "scoring.fifth-to-seventh", 1))
        );
    }

    public int pointsForPlacement(int placement) {
        if (placement == 1) return firstPlacePoints;
        if (placement >= 2 && placement <= 4) return secondToFourthPoints;
        if (placement >= 5 && placement <= 7) return fifthToSeventhPoints;
        return 0;
    }

    private static List<String> defaultTutorialLines() {
        return List.of(
                "&d&lCZERWONE-ZIELONE",
                "&aZIELONE &f- biegnij w strone mety.",
                "&cCZERWONE &f- zatrzymaj sie calkowicie.",
                "&fNa czerwonym nie ruszaj sie ani nie obracaj kamera.",
                "&fDotrzyj do mety przed koncem czasu."
        );
    }
}
