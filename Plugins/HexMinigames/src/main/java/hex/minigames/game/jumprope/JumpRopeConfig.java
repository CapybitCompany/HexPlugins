package hex.minigames.game.jumprope;

import hex.minigames.game.MinigameDefinition;
import hex.minigames.game.common.*;
import hex.minigames.model.*;
import java.util.*;

/** Arena, rotating U-shaped rope and respawn settings. */
public record JumpRopeConfig(CuboidRegion region, LocationSpec spawn, CuboidRegion startBoundary,
        CuboidRegion finishRegion, int durationSeconds, TutorialSettings tutorial, BossBarSettings bossBar,
        double fallY, int respawnDelayTicks, int finishPoints, int ropeX, int pivotY, int bottomY,
        int nearZ, int farZ, int rotationTicks, double knockback, int hitCooldownTicks) {
    public static final String ID = "jump_rope";

    public static JumpRopeConfig fromDefinition(MinigameDefinition definition, List<String> errors) {
        var settings = definition.settings();
        var region = definition.region().orElse(null);
        if (region == null) errors.add("jump_rope: region must be configured");
        String world = region == null ? "Hex_Minigames" : region.worldName();
        var start = GameSettings.region(GameSettings.child(settings, "start-boundary"), world);
        if (start == null) start = new CuboidRegion(world, new BlockPosition(448, 13, -56), new BlockPosition(452, 13, -56));
        var finish = GameSettings.region(GameSettings.child(settings, "finish-region"), world);
        if (finish == null) finish = new CuboidRegion(world, new BlockPosition(454, 14, -83), new BlockPosition(446, 12, -83));
        int pivot = GameSettings.integer(settings, "rope.pivot-y", 17);
        int bottom = GameSettings.integer(settings, "rope.bottom-y", 12);
        int near = GameSettings.integer(settings, "rope.near-z", -57);
        int far = GameSettings.integer(settings, "rope.far-z", -82);
        int x = GameSettings.integer(settings, "rope.x", 450);
        if (pivot <= bottom || pivot - bottom > 20 || near <= far || near - far > 100) errors.add("jump_rope: invalid rope geometry");
        if (region != null && (!region.contains(start) || !region.contains(finish)
                || !region.contains(new BlockPosition(x - (pivot - bottom), bottom, far))
                || !region.contains(new BlockPosition(x + (pivot - bottom), pivot + (pivot - bottom), near)))) {
            errors.add("jump_rope: rope and boundaries must stay inside region");
        }
        return new JumpRopeConfig(region,
                definition.participantSpawns().isEmpty() ? new LocationSpec(450, 12, -52, 180, 0, true) : definition.participantSpawns().get(0),
                start, finish, definition.roundTimeSeconds(),
                CommonGameConfig.tutorial(settings, definition.sourcePath(), List.of("&d&lSKAKANKA",
                        "&fPrzeskakuj nad obracającą się skakanką i dotrzyj do mety.",
                        "&fUpadek oznacza powrót na start i chwilę opóźnienia.",
                        "&fPrzekroczenie mety daje &a+2 punkty&f."), errors),
                CommonGameConfig.bossBar(settings, definition.sourcePath(), "&d&lSKAKANKA", errors),
                GameSettings.decimal(settings, "fall-y", -23),
                Math.max(0, GameSettings.integer(settings, "respawn-delay-seconds", 10)) * 20,
                Math.max(0, GameSettings.integer(settings, "scoring.finish-points", 2)),
                x, pivot, bottom, near, far,
                Math.max(40, GameSettings.integer(settings, "rope.rotation-ticks", 60)),
                Math.max(0.1, Math.min(3, GameSettings.decimal(settings, "rope.knockback", 0.9))),
                Math.max(1, GameSettings.integer(settings, "rope.hit-cooldown-ticks", 10)));
    }
}
