package hex.minigames.game.glassbridge;

import hex.minigames.game.MinigameDefinition;
import hex.minigames.model.BlockPosition;
import hex.minigames.model.CuboidRegion;
import hex.minigames.model.LocationSpec;
import hex.minigames.runtime.RoundPlayerState;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GlassBridgeRuntimeTest {
    @Test
    void bridgeHasExactlyElevenPairsAndOneSafeSideEach() {
        GlassBridgeRuntime runtime = runtime(UUID.randomUUID());

        assertEquals(11, runtime.pairCount());
        for (int i = 1; i <= 11; i++) assertNotNull(runtime.safeSide(i));
    }

    @Test
    void sideHitDoesNotBreakPlatformWhenNotOnTopSurfaceY() {
        UUID player = UUID.randomUUID();
        GlassBridgeRuntime runtime = runtime(player);

        GlassBridgeRuntime.LandingResult result = runtime.land(player, new BlockPosition(209, 25, 0));

        assertEquals(GlassBridgeRuntime.LandingOutcome.NONE, result.outcome());
        assertEquals(0, runtime.maxProgressMeters(player));
    }

    @Test
    void landingOnBadPlatformBreaksAndFreezesProgress() {
        UUID player = UUID.randomUUID();
        GlassBridgeRuntime runtime = runtime(player);
        GlassBridgeRuntime.Side badSide = runtime.safeSide(1) == GlassBridgeRuntime.Side.LEFT ? GlassBridgeRuntime.Side.RIGHT : GlassBridgeRuntime.Side.LEFT;
        BlockPosition bad = badSide == GlassBridgeRuntime.Side.LEFT ? new BlockPosition(209, 26, 0) : new BlockPosition(214, 26, 0);

        GlassBridgeRuntime.LandingResult result = runtime.land(player, bad);

        assertEquals(GlassBridgeRuntime.LandingOutcome.BAD_BROKEN, result.outcome());
        assertFalse(runtime.eliminated(player));
    }

    @Test
    void fallTriggerFreezesProgressAndPreventsFinish() {
        UUID player = UUID.randomUUID();
        GlassBridgeRuntime runtime = runtime(player);

        runtime.updateProgress(player, -20.0);
        assertTrue(runtime.triggerFall(player, -26.0));

        assertTrue(runtime.eliminated(player));
        assertTrue(runtime.fallTriggered(player));
        assertEquals(20, runtime.maxProgressMeters(player));
        runtime.updateProgress(player, -67.0);
        assertEquals(20, runtime.maxProgressMeters(player));
        assertFalse(runtime.finish(player));
    }

    @Test
    void pairEightRightCoordinatesAreCorrect() {
        GlassBridgeConfig.Pair pair8 = config().pairs().get(7);

        assertEquals(214, pair8.right().region().minX());
        assertEquals(216, pair8.right().region().maxX());
        assertEquals(-45, pair8.right().region().minZ());
        assertEquals(-42, pair8.right().region().maxZ());
    }

    @Test
    void actionbarAndProgressOriginDefaultsMatchRuntimeContract() {
        GlassBridgeConfig config = config();

        assertEquals(0, config.progressOriginZ());
        assertEquals(-26, config.fallY());
        assertEquals(200, config.respawnDelayTicks());
        assertEquals(2, config.actionbarUpdateTicks());
        assertEquals(210, config.startBoundary().minX());
        assertEquals(215, config.startBoundary().maxX());
        assertEquals(3, config.startBoundary().maxZ());
        assertTrue(config.actionbar().contains("{remaining}"));
        assertTrue(config.actionbar().contains("{meters}"));
    }

    @Test
    void progressUsesNegativeZForwardMetersAndNeverDecreases() {
        UUID player = UUID.randomUUID();
        GlassBridgeRuntime runtime = runtime(player);

        assertEquals(0, runtime.updateProgress(player, 0.0));
        assertEquals(1, runtime.updateProgress(player, -1.0));
        assertEquals(20, runtime.updateProgress(player, -20.0));
        assertEquals(20, runtime.updateProgress(player, -10.0));
    }

    @Test
    void progressMetersMatchRequestedOriginExamples() {
        GlassBridgeRuntime runtime = runtime(UUID.randomUUID());

        assertEquals(0, runtime.metersFromZ(2.0));
        assertEquals(0, runtime.metersFromZ(0.0));
        assertEquals(1, runtime.metersFromZ(-1.0));
        assertEquals(10, runtime.metersFromZ(-10.0));
        assertEquals(40, runtime.metersFromZ(-40.0));
        assertEquals(67, runtime.metersFromZ(-67.0));
    }

    @Test
    void eliminatedGhostProgressIsFrozen() {
        UUID player = UUID.randomUUID();
        GlassBridgeRuntime runtime = runtime(player);

        runtime.updateProgress(player, -24.0);
        assertTrue(runtime.eliminate(player));
        assertFalse(runtime.eliminate(player));
        runtime.updateProgress(player, -67.0);

        assertEquals(24, runtime.maxProgressMeters(player));
    }

    @Test
    void finishAddsOneBonusPointOnce() {
        UUID player = UUID.randomUUID();
        GlassBridgeRuntime runtime = runtime(player);

        runtime.updateProgress(player, -67.0);
        assertTrue(runtime.finish(player));
        assertFalse(runtime.finish(player));

        assertEquals(67, runtime.maxProgressMeters(player));
        assertEquals(4, runtime.score(player));
    }

    @Test
    void glassBridgeDoesNotEndWhenAllActivePlayersResolve() {
        assertFalse(new GlassBridgeMinigame(null).finishWhenAllActiveResolved());
    }

    @Test
    void respawnDelayIsSeparateFromGhostState() {
        assertEquals(RoundPlayerState.RESPAWN_DELAY, RoundPlayerState.valueOf("RESPAWN_DELAY"));
        assertFalse(RoundPlayerState.RESPAWN_DELAY == RoundPlayerState.GHOST);
    }

    @Test
    void tutorialAndRespawnDelayCannotCrossStartLineTowardBridge() {
        GlassBridgeConfig config = config();

        assertTrue(GlassBridgeMinigame.crossesStartBoundary(config, loc(212, 27, 3.2), loc(212, 27, 2.9)));
        assertTrue(GlassBridgeMinigame.pastStartBoundary(config.startBoundary(), loc(212, 27, 2.0)));
        assertTrue(GlassBridgeMinigame.pastStartBoundary(config.startBoundary(), loc(212, 27, -10.0)));
        assertTrue(GlassBridgeMinigame.pastStartBoundary(config.startBoundary(), loc(209, 27, -10.0)));
        assertTrue(GlassBridgeMinigame.pastStartBoundary(config.startBoundary(), loc(216, 27, -10.0)));
        assertFalse(GlassBridgeMinigame.crossesStartBoundary(config, loc(212, 27, 3.2), loc(212, 27, 4.0)));
        assertFalse(GlassBridgeMinigame.crossesStartBoundary(config, loc(208, 27, 3.2), loc(208, 27, 2.9)));
    }

    @Test
    void activePlayersCanCrossStartLineAfterTutorial() {
        assertFalse(GlassBridgeMinigame.crossesStartBoundary(config(), loc(212, 27, 2.9), loc(212, 27, 0.0)));
    }

    private GlassBridgeRuntime runtime(UUID player) {
        return new GlassBridgeRuntime(config(), List.of(player), new Random(4L));
    }

    @Test
    void fractionalDistanceStopsBelowGlassOrOutsideCorridor() {
        UUID player = UUID.randomUUID();
        var runtime = runtime(player);
        assertEquals(1.25, runtime.updateProgress(player, 210, 27, -1.25), 0.00001);
        assertEquals(1.75, runtime.updateProgress(player, 210, 28, -1.75), 0.00001);
        assertEquals(1.75, runtime.updateProgress(player, 210, 26.9, -3.5), 0.00001);
        assertEquals(1.75, runtime.updateProgress(player, 208, 27, -5.5), 0.00001);
        assertEquals(1.75, runtime.updateProgress(player, 217, 27, -5.5), 0.00001);
        assertEquals(1.75, runtime.updateProgress(player, 210, 27, -0.5), 0.00001);
        assertEquals(5.5, runtime.updateProgress(player, 210, 28, -5.5), 0.00001);
    }

    @Test
    void delayAllowsAnotherAttemptAndAnotherFallKeepingBestDistance() {
        UUID player = UUID.randomUUID();
        var runtime = runtime(player);
        runtime.updateProgress(player, 210, 27, -20.25);
        assertTrue(runtime.markFall(player));
        runtime.updateProgress(player, 210, 27, -30.0);
        assertEquals(20.25, runtime.preciseProgressMeters(player));
        runtime.respawn(player);
        assertFalse(runtime.eliminated(player));
        assertEquals(20.25, runtime.updateProgress(player, 210, 27, -10.0));
        assertEquals(30.5, runtime.updateProgress(player, 210, 27, -30.5));
        assertTrue(runtime.markFall(player));
        runtime.respawn(player);
        assertTrue(runtime.finish(player));
    }

    private GlassBridgeConfig config() {
        return GlassBridgeConfig.fromDefinition(new MinigameDefinition(
                GlassBridgeConfig.ID,
                "Glass",
                true,
                true,
                false,
                1,
                14,
                1,
                Optional.of(new CuboidRegion("world", new BlockPosition(156, -33, -96), new BlockPosition(263, 66, 18))),
                List.of(new LocationSpec(212, 27, 8, 180, 0, true)),
                Optional.of(new LocationSpec(212, 30, 8, 180, 0, true)),
                150,
                Map.of(),
                "test"
        ), new java.util.ArrayList<>());
    }

    private Location loc(double x, double y, double z) {
        return new Location(null, x, y, z);
    }
}
