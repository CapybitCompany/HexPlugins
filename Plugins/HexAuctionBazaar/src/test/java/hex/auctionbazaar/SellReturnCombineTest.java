package hex.auctionbazaar;

import hex.auctionbazaar.bazaar.service.BazaarService;
import hex.auctionbazaar.bazaar.service.BazaarService.ItemDelivery;
import hex.auctionbazaar.bazaar.service.BazaarService.SellResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Punkt #2: łączenie wyniku wypłaty ze statusem zwrotu niesprzedanej reszty. Nieudany/niepewny
 * zwrot ma pierwszeństwo (RETURN_FAILED) - nigdy fałszywy „Sprzedano/OK".
 */
class SellReturnCombineTest {

    @Test
    void unsafeRestReturnAlwaysOverridesToReturnFailed() {
        for (SellResult payout : new SellResult[]{
                SellResult.OK, SellResult.OK_PENDING_CLAIM, SellResult.NOTHING_SOLD}) {
            assertEquals(SellResult.RETURN_FAILED,
                    BazaarService.combineSellResult(payout, ItemDelivery.UNCERTAIN),
                    "UNCERTAIN reszta -> krytyczne, wypłata=" + payout);
            assertEquals(SellResult.RETURN_FAILED,
                    BazaarService.combineSellResult(payout, ItemDelivery.CLAIM_FAILED),
                    "CLAIM_FAILED reszta -> krytyczne, wypłata=" + payout);
        }
    }

    @Test
    void safeRestInInventoryKeepsPayoutResult() {
        assertEquals(SellResult.OK,
                BazaarService.combineSellResult(SellResult.OK, ItemDelivery.IN_INVENTORY));
        assertEquals(SellResult.OK,
                BazaarService.combineSellResult(SellResult.OK, ItemDelivery.NONE));
        assertEquals(SellResult.OK_PENDING_CLAIM,
                BazaarService.combineSellResult(SellResult.OK_PENDING_CLAIM, ItemDelivery.NONE));
    }

    @Test
    void okWithRestAsClaimBecomesRestClaimed() {
        assertEquals(SellResult.OK_REST_CLAIMED,
                BazaarService.combineSellResult(SellResult.OK, ItemDelivery.AS_CLAIM));
    }

    @Test
    void payoutFailedStaysPayoutFailedWhenRestSafe() {
        assertEquals(SellResult.PAYOUT_FAILED,
                BazaarService.combineSellResult(SellResult.PAYOUT_FAILED, ItemDelivery.IN_INVENTORY));
    }
}
