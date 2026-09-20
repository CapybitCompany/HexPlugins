package hex.minigames.game.discofloor;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class DiscoFloorRuntimeTest {
    @Test void scoresAllThresholdsAndEliminationFreezesProgress() {
        int[] points = {0, 0, 1, 2, 2, 3, 3, 4};
        for (int rounds = 0; rounds <= 7; rounds++) {
            var runtime = new DiscoFloorRuntime(9, new Random(1));
            var id = UUID.randomUUID();
            runtime.survive(id, rounds);
            assertEquals(points[rounds], runtime.points(id));
            assertTrue(runtime.eliminate(id));
            runtime.survive(id, 7);
            assertEquals(points[rounds], runtime.points(id));
            assertFalse(runtime.eliminate(id));
        }
    }
    @Test void choosesOnlyPresentColorsAndDoesNotRepeatAdjacentColors() {
        for (int colors = 1; colors <= 9; colors++) {
            var runtime = new DiscoFloorRuntime(colors, new Random(1));
            for (int i = 0; i < 7; i++) {
                assertTrue(runtime.color(i) >= 0 && runtime.color(i) < colors);
                if (i > 0 && colors > 1) assertNotEquals(runtime.color(i - 1), runtime.color(i));
            }
        }
    }
}
