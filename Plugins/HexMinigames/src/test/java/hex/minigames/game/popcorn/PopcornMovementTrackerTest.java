package hex.minigames.game.popcorn;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PopcornMovementTrackerTest {
    @Test
    void twoSecondsThenThreeSecondsGraceAndMovementResetsTheCountdown() {
        var tracker = new PopcornMovementTracker();
        UUID id = UUID.randomUUID();
        assertEquals(0, tracker.idleTicks(id, 10, 10, 0));
        assertEquals(39, tracker.idleTicks(id, 10, 10, 39));
        assertEquals(40, tracker.idleTicks(id, 10, 10, 40));
        assertEquals(99, tracker.idleTicks(id, 10.01, 10, 99));
        assertEquals(100, tracker.idleTicks(id, 10, 10, 100));
        assertEquals(0, tracker.idleTicks(id, 10.3, 10, 101));
        assertEquals(40, tracker.idleTicks(id, 10.3, 10, 141));
        tracker.clear();
        assertEquals(0, tracker.idleTicks(id, 10.3, 10, 500));
    }

    @Test
    void slowWalkingAccumulatesAndPlayersHaveIndependentTimers() {
        var tracker = new PopcornMovementTracker();
        UUID one = UUID.randomUUID(), two = UUID.randomUUID();
        tracker.idleTicks(one, 0, 0, 0);
        tracker.idleTicks(two, 0, 0, 0);
        assertEquals(10, tracker.idleTicks(one, 0.1, 0, 10));
        assertEquals(0, tracker.idleTicks(one, 0.21, 0, 20));
        assertEquals(100, tracker.idleTicks(two, 0, 0, 100));
    }
}
