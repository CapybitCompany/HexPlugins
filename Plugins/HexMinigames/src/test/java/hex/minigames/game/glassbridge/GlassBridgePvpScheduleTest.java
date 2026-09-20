package hex.minigames.game.glassbridge;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

final class GlassBridgePvpScheduleTest {
    @Test
    void unlimitedRandomWindowsLastExactlySixtyTicksAndHavePeacefulGaps() {
        for (int seed = 0; seed < 100; seed++) {
            var schedule = new GlassBridgePvpSchedule(2600, new Random(seed));
            assertTrue(schedule.starts().size() > 3);
            long previousEnd = 0;
            for (long start : schedule.starts()) {
                assertTrue(start - previousEnd >= 300 && start - previousEnd <= 600);
                assertFalse(schedule.active(start - 1));
                for (long tick = start; tick < start + 60; tick++) assertTrue(schedule.active(tick));
                assertFalse(schedule.active(start + 60));
                previousEnd = start + 60;
            }
            assertTrue(previousEnd < 2600);
        }
        assertNotEquals(new GlassBridgePvpSchedule(2600, new Random(1)).starts(),
                new GlassBridgePvpSchedule(2600, new Random(2)).starts());
    }

    @Test
    void shortRoundsDoNotScheduleWindowsBeyondTheirDeadline() {
        for (int duration = 1; duration < 1000; duration++) {
            var schedule = new GlassBridgePvpSchedule(duration, new Random(1));
            for (long start : schedule.starts()) assertTrue(start + 60 < duration);
        }
    }
}
