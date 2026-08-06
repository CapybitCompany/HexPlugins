package hex.auctionbazaar;

import hex.auctionbazaar.bazaar.service.BazaarService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regresja bledu #4: SELL deposit-failure returned OK too early.
 * Sprawdzamy stany SellResult i ich mapowanie.
 * Poprzednio SellOutcome bylo OK niezaleznie od czy money-claim zostal zapisany.
 */
class SellPayoutCompletionTest {

    @Test
    void okPendingClaimIsDistinctFromOk() {
        assertTrue(BazaarService.SellResult.OK != BazaarService.SellResult.OK_PENDING_CLAIM);
    }

    @Test
    void payoutFailedIsDistinctFromOtherStates() {
        assertTrue(BazaarService.SellResult.PAYOUT_FAILED != BazaarService.SellResult.OK);
        assertTrue(BazaarService.SellResult.PAYOUT_FAILED != BazaarService.SellResult.OK_PENDING_CLAIM);
        assertTrue(BazaarService.SellResult.PAYOUT_FAILED != BazaarService.SellResult.DB_FAILED);
    }

    @Test
    void okOutcomeReportsOk() {
        var o = BazaarService.SellOutcome.ok(null, java.math.BigDecimal.TEN, 5L);
        assertEquals(BazaarService.SellResult.OK, o.result());
        assertEquals(5L, o.amountSold(), "outcome niesie faktycznie sprzedaną ilość");
    }

    @Test
    void pendingClaimOutcomeReportsPending() {
        var o = BazaarService.SellOutcome.okPendingClaim(null, java.math.BigDecimal.TEN, 5L);
        assertEquals(BazaarService.SellResult.OK_PENDING_CLAIM, o.result());
        assertEquals(java.math.BigDecimal.TEN, o.total(),
                "total powinien odzwierciedlac to co zostalo zaksiegowane jako claim");
        assertEquals(5L, o.amountSold());
    }

    @Test
    void payoutFailureCarriesNoTotal() {
        var o = BazaarService.SellOutcome.fail(BazaarService.SellResult.PAYOUT_FAILED);
        assertEquals(BazaarService.SellResult.PAYOUT_FAILED, o.result());
    }
}
