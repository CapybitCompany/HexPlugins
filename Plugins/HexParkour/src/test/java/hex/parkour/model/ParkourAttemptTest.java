package hex.parkour.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParkourAttemptTest {
    @Test
    void resetDoesNotRestartTimerAndMoneyPointIsClaimedOnce() {
        ParkourAttempt attempt = new ParkourAttempt("wild_west");

        attempt.start(1_000L);
        assertTrue(attempt.timerStarted());
        assertEquals(4_000L, attempt.elapsedNanos(5_000L));
        assertTrue(attempt.claimMoneyPoint("money_1"));
        assertFalse(attempt.claimMoneyPoint("money_1"));
        assertEquals(9_000L, attempt.elapsedNanos(10_000L));
    }
}
