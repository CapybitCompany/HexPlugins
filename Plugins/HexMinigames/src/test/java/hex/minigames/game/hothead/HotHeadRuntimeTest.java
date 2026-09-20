package hex.minigames.game.hothead;

import hex.minigames.game.MinigameDefinition;
import hex.minigames.model.BlockPosition;
import hex.minigames.model.CuboidRegion;
import hex.minigames.model.LocationSpec;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HotHeadRuntimeTest {
    @Test
    void entireMechanismRunsTwiceAsFastAfterFortySeconds() {
        assertEquals(62, cycleLength(0));
        assertEquals(33, cycleLength(840));
    }

    private long cycleLength(long firstTick) {
        var runtime = new HotHeadRuntime(defaultConfig(), new Random(2));
        runtime.start(0);
        if (firstTick >= 840) runtime.tick(800, 1200);
        assertEquals(firstTick >= 840 ? 2 : 3, runtime.tick(firstTick, 1200).stream().filter(c -> c.type() == HotHeadRuntime.CommandType.LAMP_ON).count());
        long firstSegment = -1;
        long lastSegment = -1;
        long firstLowering = -1;
        for (long tick = firstTick + 1; tick < firstTick + 100; tick++) {
            var commands = runtime.tick(tick, 1200);
            if (commands.stream().anyMatch(c -> c.type() == HotHeadRuntime.CommandType.SEGMENT_ON)) {
                if (firstSegment < 0) firstSegment = tick;
                lastSegment = tick;
            }
            if (commands.stream().anyMatch(c -> c.type() == HotHeadRuntime.CommandType.SEGMENT_OFF) && firstLowering < 0) firstLowering = tick;
            if (commands.stream().anyMatch(c -> c.type() == HotHeadRuntime.CommandType.LAMP_ON)) {
                assertEquals(firstTick >= 800 ? 5 : 10, firstSegment - firstTick);
                assertEquals(firstTick >= 800 ? 8 : 16, lastSegment - firstSegment);
                assertEquals(firstTick >= 800 ? 10 : 20, firstLowering - lastSegment);
                assertEquals(firstTick >= 800 ? 10 : 16, tick - firstLowering);
                return tick - firstTick;
            }
        }
        throw new AssertionError("No next wave");
    }

    @Test
    void accelerationClearsWaveAndPausesLampsForExactlyFortyTicks() {
        var runtime = new HotHeadRuntime(defaultConfig(), new Random(2));
        runtime.start(0);
        runtime.tick(780, 1200);
        runtime.tick(790, 1200);
        var transition = runtime.tick(800, 1200);
        assertEquals(3, transition.stream().filter(c -> c.type() == HotHeadRuntime.CommandType.LAMP_OFF).count());
        assertEquals(3, transition.stream().filter(c -> c.type() == HotHeadRuntime.CommandType.SEGMENT_OFF).count());
        assertEquals(1, transition.stream().filter(c -> c.type() == HotHeadRuntime.CommandType.ACCELERATION_NOTICE).count());
        for (long tick = 801; tick < 840; tick++) assertTrue(runtime.tick(tick, 1200).isEmpty());
        assertEquals(2, runtime.tick(840, 1200).stream().filter(c -> c.type() == HotHeadRuntime.CommandType.LAMP_ON).count());
    }

    @Test
    void bothAxesHaveNineLanes() {
        HotHeadRuntime runtime = runtime();

        assertEquals(9, runtime.axis1LaneCount());
        assertEquals(9, runtime.axis2LaneCount());
    }

    @Test
    void firstPhaseHasThreeWavesAcrossBothAxes() {
        HotHeadRuntime runtime = runtime();
        runtime.start(0L);

        runtime.tick(0L, 2400L);

        assertEquals(3, runtime.activeWaveCount(1) + runtime.activeWaveCount(2));
        assertTrue(runtime.axisActive(HotHeadRuntime.Axis.AXIS_X));
        assertTrue(runtime.axisActive(HotHeadRuntime.Axis.AXIS_Z));

        runtime.tick(1L, 2400L);

        assertEquals(3, runtime.activeWaveCount(1) + runtime.activeWaveCount(2));
    }

    @Test
    void shortWarningThenSegmentsAdvanceEveryTwoTicksBeforePreviousSegmentLowers() {
        HotHeadRuntime runtime = new HotHeadRuntime(defaultConfig(), new Random(2L));
        runtime.start(0L);

        List<HotHeadRuntime.Command> start = runtime.tick(0L, 2400L);

        assertEquals(3, start.stream().filter(command -> command.type() == HotHeadRuntime.CommandType.LAMP_ON).count());
        assertTrue(runtime.tick(9L, 2400L).isEmpty());
        assertEquals(3, runtime.tick(10L, 2400L).stream().filter(command -> command.type() == HotHeadRuntime.CommandType.SEGMENT_ON).count());
        assertTrue(runtime.tick(11L, 2400L).isEmpty());
        var next = runtime.tick(12L, 2400L);
        assertEquals(3, next.stream().filter(command -> command.type() == HotHeadRuntime.CommandType.SEGMENT_ON).count());
        assertFalse(next.stream().anyMatch(command -> command.type() == HotHeadRuntime.CommandType.SEGMENT_OFF));
    }

    @Test
    void waveProducesLampAndSegmentCleanupCommands() {
        HotHeadRuntime runtime = runtime();
        runtime.start(0L);
        runtime.tick(0L, 2400L);

        java.util.ArrayList<HotHeadRuntime.Command> commands = new java.util.ArrayList<>();
        for (long tick = 1L; tick < 30L; tick++) {
            commands.addAll(runtime.tick(tick, 2400L));
        }

        assertTrue(commands.stream().anyMatch(command -> command.type() == HotHeadRuntime.CommandType.LAMP_OFF));
        assertTrue(commands.stream().anyMatch(command -> command.type() == HotHeadRuntime.CommandType.SEGMENT_OFF));
    }

    @Test
    void firstSegmentStaysRaisedUntilTheEntireWaveHasArrived() {
        HotHeadRuntime runtime = runtime();
        runtime.start(0L);
        runtime.tick(0L, 2400L);

        List<HotHeadRuntime.Command> commands = new java.util.ArrayList<>();
        for (long tick = 1L; tick <= 17L; tick++) {
            commands.addAll(runtime.tick(tick, 2400L));
        }

        List<HotHeadRuntime.CommandType> segmentCommands = commands.stream()
                .filter(command -> command.axis() == HotHeadRuntime.Axis.AXIS_X)
                .map(HotHeadRuntime.Command::type)
                .filter(type -> type == HotHeadRuntime.CommandType.SEGMENT_ON || type == HotHeadRuntime.CommandType.SEGMENT_OFF)
                .toList();
        assertEquals(9 * runtime.activeWaveCount(1), segmentCommands.size());
        assertTrue(segmentCommands.stream().allMatch(type -> type == HotHeadRuntime.CommandType.SEGMENT_ON));
        assertEquals(27, runtime.tick(18, 2400).stream().filter(c -> c.type() == HotHeadRuntime.CommandType.SEGMENT_OFF).count());
    }

    @Test
    void waveVisitsEverySegmentOfBothSelectedLanesAndDoesNotRaiseWholeLaneAtOnce() {
        HotHeadRuntime runtime = runtime();
        runtime.start(0L);
        runtime.tick(0L, 2400L);

        List<HotHeadRuntime.Command> commands = new java.util.ArrayList<>();
        for (long tick = 1L; tick <= 18L; tick++) {
            List<HotHeadRuntime.Command> tickCommands = runtime.tick(tick, 2400L);
            assertTrue(tickCommands.stream()
                    .filter(command -> command.type() == HotHeadRuntime.CommandType.SEGMENT_ON)
                    .allMatch(command -> command.positions().size() == 4));
            assertTrue(tickCommands.stream()
                    .filter(command -> command.type() == HotHeadRuntime.CommandType.SEGMENT_ON && command.axis() == HotHeadRuntime.Axis.AXIS_X)
                    .count() <= 2);
            assertTrue(tickCommands.stream()
                    .filter(command -> command.type() == HotHeadRuntime.CommandType.SEGMENT_ON && command.axis() == HotHeadRuntime.Axis.AXIS_Z)
                    .count() <= 2);
            commands.addAll(tickCommands);
        }

        assertEquals(27, commands.stream().filter(command -> command.type() == HotHeadRuntime.CommandType.SEGMENT_ON).count());
        assertEquals(27, commands.stream().filter(command -> command.type() == HotHeadRuntime.CommandType.SEGMENT_OFF).count());
        assertEquals(3, commands.stream().filter(command -> command.type() == HotHeadRuntime.CommandType.LAMP_OFF).count());
    }

    @Test
    void cycleGapSeparatesNextLaneSelection() {
        HotHeadRuntime runtime = runtime();
        runtime.start(0L);
        runtime.tick(0L, 2400L);
        for (long tick = 1L; tick <= 18L; tick++) runtime.tick(tick, 2400L);

        assertTrue(runtime.tick(19L, 2400L).isEmpty());
        assertEquals(3, runtime.tick(20L, 2400L).stream().filter(command -> command.type() == HotHeadRuntime.CommandType.LAMP_ON).count());
    }

    @Test
    void fireAndFireTickAreRecognizedAsDamageExceptionCauses() {
        assertTrue(HotHeadMinigame.isFireDamage(EntityDamageEvent.DamageCause.FIRE));
        assertTrue(HotHeadMinigame.isFireDamage(EntityDamageEvent.DamageCause.FIRE_TICK));
        assertFalse(HotHeadMinigame.isFireDamage(EntityDamageEvent.DamageCause.FALL));
        assertTrue(config().allowFireDamage());
    }

    @Test
    void raisedFloorPositionIsOneBlockAboveOriginalSegment() {
        assertEquals(new BlockPosition(-10, -31, 37), HotHeadMinigame.raisedPosition(new BlockPosition(-10, -32, 37)));
    }

    @Test
    void gameplaySpawnDefaultsToMinusThirtyOneY() {
        assertEquals(-31.0, config().gameplaySpawn().y());
    }

    @Test
    void hotHeadDoesNotEndWhenAllActivePlayersResolve() {
        assertEquals(false, new HotHeadMinigame(null).finishWhenAllActiveResolved());
    }

    private HotHeadRuntime runtime() {
        return new HotHeadRuntime(config(), new Random(2L));
    }

    private HotHeadConfig config() {
        return HotHeadConfig.fromDefinition(new MinigameDefinition(
                HotHeadConfig.ID,
                "Hot Head",
                true,
                true,
                false,
                1,
                14,
                1,
                Optional.of(new CuboidRegion("world", new BlockPosition(-31, -33, 17), new BlockPosition(-6, -19, 58))),
                List.of(new LocationSpec(-18, -32, 26, 0, 0, true)),
                Optional.of(new LocationSpec(-18, -32, 39, 0, 0, true)),
                120,
                Map.of("wave", Map.of("warning-ticks", 1, "step-delay-ticks", 1, "segment-hold-ticks", 1, "cycle-gap-ticks", 2)),
                "test"
        ), new java.util.ArrayList<>());
    }

    private HotHeadConfig defaultConfig() {
        return HotHeadConfig.fromDefinition(new MinigameDefinition(
                HotHeadConfig.ID,
                "Hot Head",
                true,
                true,
                false,
                1,
                14,
                1,
                Optional.of(new CuboidRegion("world", new BlockPosition(-31, -33, 17), new BlockPosition(-6, -19, 58))),
                List.of(new LocationSpec(-18, -32, 26, 0, 0, true)),
                Optional.of(new LocationSpec(-18, -32, 39, 0, 0, true)),
                120,
                Map.of(),
                "test"
        ), new java.util.ArrayList<>());
    }
}
