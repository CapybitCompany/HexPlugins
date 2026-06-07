package hex.rankexpiry.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankExpiryTest {
    @Test
    void daysRemainingReturnsZeroForExpiredRank() {
        assertEquals(0L, RankExpiry.daysRemaining(1_000L, 999L));
        assertEquals(0L, RankExpiry.daysRemaining(1_000L, 1_000L));
    }

    @Test
    void daysRemainingRoundsUpPartialDay() {
        assertEquals(1L, RankExpiry.daysRemaining(1_000L, 1_001L));
        assertEquals(1L, RankExpiry.daysRemaining(1_000L, 87_400L));
        assertEquals(2L, RankExpiry.daysRemaining(1_000L, 87_401L));
    }

    @Test
    void rankExpiryReportsActiveStateAndRemainingSeconds() {
        RankExpiry rank = new RankExpiry("nte.vip", "VIP", 1_500L);

        assertTrue(rank.activeAt(1_499L));
        assertFalse(rank.activeAt(1_500L));
        assertEquals(500L, rank.secondsRemaining(1_000L));
        assertEquals(0L, rank.secondsRemaining(1_600L));
    }
}
