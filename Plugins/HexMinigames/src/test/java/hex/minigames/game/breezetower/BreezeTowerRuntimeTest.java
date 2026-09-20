package hex.minigames.game.breezetower;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class BreezeTowerRuntimeTest {
    @Test
    void scoreUsesExactSurvivalThresholdsAndFreezesAtElimination() {
        for (long ticks : new long[]{0, 399, 400, 799, 800, 1199, 1200}) {
            var runtime = new BreezeTowerRuntime(30, 40, 4, new Random(1));
            runtime.start(0);
            UUID player = UUID.randomUUID();
            assertTrue(runtime.eliminate(player, ticks));
            assertFalse(runtime.eliminate(player, 1200));
            assertEquals((int) (ticks / 400), runtime.points(player, 2000));
            assertEquals(ticks, runtime.survivedTicks(player, 2000));
        }
    }

    @Test
    void survivorsGetThreePointsNotSixAndWarmupDoesNotCount() {
        var runtime = new BreezeTowerRuntime(30, 40, 4, new Random(1));
        UUID player = UUID.randomUUID();
        runtime.start(100);
        assertEquals(0, runtime.points(player, 499));
        assertEquals(1, runtime.points(player, 500));
        assertEquals(2, runtime.points(player, 900));
        assertEquals(3, runtime.points(player, 1300));
        assertEquals(3, runtime.points(player, 5000));
    }

    @Test
    void shootersAlternateWithoutSimultaneousOrCatchupVolleys() {
        var runtime = new BreezeTowerRuntime(30, 40, 4, new Random(1));
        var players = List.of(UUID.randomUUID(), UUID.randomUUID());
        runtime.start(0);
        UUID previous = null;
        for (int tick = 0, count = 0; tick < 1200; tick++) {
            var shot = runtime.shot(tick, players);
            if (tick >= 40 && (tick - 40) % 30 == 0) {
                assertTrue(shot.isPresent());
                assertEquals(count++ % 4, shot.get().shooterIndex());
                assertNotEquals(previous, shot.get().target());
                previous = shot.get().target();
            } else assertTrue(shot.isEmpty());
            assertTrue(runtime.shot(tick, players).isEmpty());
        }
        runtime.start(0);
        assertTrue(runtime.shot(500, players).isPresent());
        assertTrue(runtime.shot(500, players).isEmpty());
        assertTrue(runtime.shot(529, players).isEmpty());
        assertTrue(runtime.shot(530, players).isPresent());
    }

    @Test
    void eliminatedOrAbsentPlayersAreNeverTargetedAndRestartClearsRoundState() {
        var runtime = new BreezeTowerRuntime(30, 40, 4, new Random(1));
        UUID player = UUID.randomUUID();
        runtime.start(0);
        assertNull(runtime.shot(40, List.of()).orElseThrow().target());
        runtime.eliminate(player, 40);
        assertNull(runtime.shot(70, List.of(player)).orElseThrow().target());
        runtime.start(0);
        assertTrue(runtime.shot(40, List.of(player)).isPresent());
        assertTrue(runtime.shot(1200, List.of(player)).isEmpty());
    }

    @Test
    void frequentVolleysSometimesUseTwoDifferentNpcsWithoutCatchupBursts() {
        var runtime = new BreezeTowerRuntime(12, 40, 4, new Random(1));
        runtime.start(0);
        int singles = 0, doubles = 0;
        for (long tick = 40; tick < 1200; tick += 12) {
            var volley = runtime.shots(tick, List.of());
            if (volley.size() == 1) singles++;
            if (volley.size() == 2) {
                doubles++;
                assertNotEquals(volley.get(0).shooterIndex(), volley.get(1).shooterIndex());
            }
            assertTrue(runtime.shots(tick, List.of()).isEmpty());
            assertTrue(volley.stream().allMatch(shot -> shot.target() == null));
        }
        assertTrue(singles > doubles && doubles > 0);
    }
}
