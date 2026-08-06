package hex.auctionbazaar;

import hex.auctionbazaar.auction.service.AuctionService;
import hex.auctionbazaar.auction.service.AuctionService.BuyOutcome;
import hex.auctionbazaar.bazaar.service.BazaarOrderService;
import hex.auctionbazaar.bazaar.service.BazaarOrderService.PlaceResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Punkt #6: (a) klasyfikacja nieudanego withdraw przy zleceniu BUY - brak środków vs błąd techniczny;
 * (b) mapowanie wyniku zwolnienia rezerwacji przy kupnie aukcji - tylko potwierdzone true daje pierwotny
 * wynik ekonomii, a false/null/wyjątek to krytyczny COMPENSATION_FAILED (rezerwacja mogła utknąć).
 */
class EconomyErrorDecisionTest {

    // ---- klasyfikacja withdraw (BUY-order) ----

    @Test
    void withdrawExceptionIsEconomyError() {
        assertEquals(PlaceResult.ECONOMY_ERROR,
                BazaarOrderService.classifyWithdrawFailure(true, false, null));
    }

    @Test
    void withdrawNullResultIsEconomyError() {
        assertEquals(PlaceResult.ECONOMY_ERROR,
                BazaarOrderService.classifyWithdrawFailure(false, true, null));
    }

    @Test
    void withdrawNotEnoughFundsIsNotEnoughMoney() {
        assertEquals(PlaceResult.NOT_ENOUGH_MONEY,
                BazaarOrderService.classifyWithdrawFailure(false, false, "NOT_ENOUGH_FUNDS"));
    }

    @Test
    void withdrawOtherTechnicalReasonIsEconomyError() {
        assertEquals(PlaceResult.ECONOMY_ERROR,
                BazaarOrderService.classifyWithdrawFailure(false, false, "SOME_TECH_ERROR"));
        assertEquals(PlaceResult.ECONOMY_ERROR,
                BazaarOrderService.classifyWithdrawFailure(false, false, null));
    }

    // ---- zwolnienie rezerwacji (Auction buy) ----

    @Test
    void reservationReleasedTrueYieldsOriginalEconomyOutcome() {
        assertEquals(BuyOutcome.NOT_ENOUGH_MONEY,
                AuctionService.reservationReleaseBuyOutcome(Boolean.TRUE, null, BuyOutcome.NOT_ENOUGH_MONEY));
        assertEquals(BuyOutcome.ECONOMY_ERROR,
                AuctionService.reservationReleaseBuyOutcome(Boolean.TRUE, null, BuyOutcome.ECONOMY_ERROR));
    }

    @Test
    void reservationReleaseFalseNullOrExceptionIsCompensationFailed() {
        assertEquals(BuyOutcome.COMPENSATION_FAILED,
                AuctionService.reservationReleaseBuyOutcome(Boolean.FALSE, null, BuyOutcome.NOT_ENOUGH_MONEY));
        assertEquals(BuyOutcome.COMPENSATION_FAILED,
                AuctionService.reservationReleaseBuyOutcome(null, null, BuyOutcome.NOT_ENOUGH_MONEY));
        assertEquals(BuyOutcome.COMPENSATION_FAILED,
                AuctionService.reservationReleaseBuyOutcome(Boolean.TRUE,
                        new RuntimeException("db down"), BuyOutcome.ECONOMY_ERROR));
    }
}
