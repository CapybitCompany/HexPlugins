package hex.minigames.game.breezetower;

import hex.minigames.game.MinigameDefinition;
import hex.minigames.game.common.*;
import hex.minigames.model.*;
import java.util.ArrayList;
import java.util.List;

/** Arena positions and pacing for the Breeze survival round. */
public record BreezeTowerConfig(CuboidRegion region, int roundDurationSeconds,
        TutorialSettings tutorial, BossBarSettings bossBar, LocationSpec gameplaySpawn,
        LocationSpec spectatorSpawn, List<LocationSpec> breezes, double eliminationY,
        int shotIntervalTicks, int firstShotDelayTicks, double projectileSpeed, int projectileLifetimeTicks) {
    public static final String ID = "breeze_tower";

    public BreezeTowerConfig {
        breezes = List.copyOf(breezes);
    }

    public static BreezeTowerConfig fromDefinition(MinigameDefinition definition, List<String> errors) {
        Object settings = definition.settings();
        String source = definition.sourcePath();
        CuboidRegion region = definition.region().orElse(null);
        if (region == null) errors.add(source + ": region must be configured");
        LocationSpec spawn = location(settings, "gameplay-spawn", new LocationSpec(39, -2, -77, 135, 0, true));
        LocationSpec spectator = definition.spectatorSpawn().orElse(new LocationSpec(38, 8, -76, 0, 30, true));
        List<LocationSpec> breezes = new ArrayList<>();
        Object raw = GameSettings.child(settings, "breezes");
        if (raw == null) {
            breezes.addAll(List.of(new LocationSpec(29, 2, -59, 0, 0, true),
                    new LocationSpec(29, 1, -94, 0, 0, true), new LocationSpec(51, 2, -94, 0, 0, true),
                    new LocationSpec(51, 3, -60, 0, 0, true)));
        } else {
            for (Object entry : GameSettings.list(raw)) breezes.add(GameSettings.location(entry));
        }
        if (breezes.size() != 4) errors.add(source + ": settings.breezes must contain four positions");
        List<LocationSpec> positions = new ArrayList<>(breezes);
        positions.add(spawn);
        positions.add(spectator);
        for (LocationSpec position : positions) {
            if (!position.configured() || region != null && !region.contains(new BlockPosition(
                    (int) Math.floor(position.x()), (int) Math.floor(position.y()), (int) Math.floor(position.z())))) {
                errors.add(source + ": spawn positions must be configured inside the arena");
            }
        }
        double speed = GameSettings.decimal(settings, "shots.speed", 1.35);
        if (!Double.isFinite(speed) || speed <= 0) {
            errors.add(source + ": shots.speed must be positive");
            speed = 1.35;
        }
        return new BreezeTowerConfig(region, definition.roundTimeSeconds(),
                CommonGameConfig.tutorial(settings, source, tutorialLines(), errors),
                CommonGameConfig.bossBar(settings, source, "&d&lWIEŻA BREEZE'A", errors), spawn, spectator, breezes,
                GameSettings.decimal(settings, "elimination-y", -13),
                Math.max(1, GameSettings.integer(settings, "shots.interval-ticks", 6)),
                Math.max(1, GameSettings.integer(settings, "shots.first-delay-ticks", 20)), speed,
                Math.max(1, GameSettings.integer(settings, "shots.lifetime-ticks", 100)));
    }

    private static LocationSpec location(Object settings, String path, LocationSpec fallback) {
        return GameSettings.child(settings, path) == null ? fallback : GameSettings.location(GameSettings.child(settings, path));
    }

    private static List<String> tutorialLines() {
        return List.of("&d&lWIEŻA BREEZE'A", "&fUnikaj podmuchów wystrzeliwanych przez Breezy!",
                "&fNie spadnij z wieży. PvP jest wyłączone.",
                "&fPrzetrwaj &e20 / 40 / 60 sekund &f= &a1 / 2 / 3 punkty&f.");
    }
}
