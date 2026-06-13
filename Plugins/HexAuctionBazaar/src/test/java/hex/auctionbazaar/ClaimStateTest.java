package hex.auctionbazaar;

import hex.auctionbazaar.auction.model.AuctionClaim;
import hex.auctionbazaar.auction.model.ClaimState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ClaimStateTest {

    @Test
    void enumIsExactlyPendingAndClaiming() {
        assertEquals(2, ClaimState.values().length);
        assertNotNull(ClaimState.valueOf("PENDING"));
        assertNotNull(ClaimState.valueOf("CLAIMING"));
    }

    @Test
    void moneyClaimReportsMoneyAndNotItem() {
        AuctionClaim claim = new AuctionClaim(1L, UUID.randomUUID(), null,
                new BigDecimal("12.50"), "test", null, 0L, ClaimState.PENDING);
        assertTrue(claim.isMoney());
        assertFalse(claim.isItem());
    }

    @Test
    void itemClaimReportsItemAndNotMoney() {
        AuctionClaim claim = new AuctionClaim(2L, UUID.randomUUID(), new byte[]{1, 2, 3},
                null, "test", 99L, 0L, ClaimState.PENDING);
        assertFalse(claim.isMoney());
        assertTrue(claim.isItem());
    }

    @Test
    void emptyBlobIsNotConsideredAnItem() {
        AuctionClaim claim = new AuctionClaim(3L, UUID.randomUUID(), new byte[0],
                null, "test", null, 0L, ClaimState.PENDING);
        assertFalse(claim.isItem());
    }
}
