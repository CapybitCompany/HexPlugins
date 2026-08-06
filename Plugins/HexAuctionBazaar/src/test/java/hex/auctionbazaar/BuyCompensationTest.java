package hex.auctionbazaar;

import hex.auctionbazaar.audit.model.AuditAction;
import hex.auctionbazaar.auction.service.AuctionService;
import hex.auctionbazaar.auction.service.AuctionService.BuyOutcome;
import hex.auctionbazaar.auction.service.AuctionService.ItemRefundStatus;
import hex.auctionbazaar.auction.service.AuctionService.SellResult;
import hex.auctionbazaar.util.RefundCompensation.Status;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkty #1/#2/#3: rozłączne wyniki kupna i mapy decyzyjne kompensacji.
 * Sukces WYŁĄCZNIE dla dostarczenia/claim; ścieżki zwrotu nigdy nie dają OK.
 */
class BuyCompensationTest {

    @Test
    void refundOutcomeNeverReportsSuccess() {
        assertEquals(BuyOutcome.REFUNDED, AuctionService.refundOutcome(Status.REFUNDED));
        assertEquals(BuyOutcome.REFUND_PENDING, AuctionService.refundOutcome(Status.PENDING_CLAIM));
        assertEquals(BuyOutcome.COMPENSATION_FAILED, AuctionService.refundOutcome(Status.FAILED));
        Set<BuyOutcome> refundOutcomes = EnumSet.of(
                AuctionService.refundOutcome(Status.REFUNDED),
                AuctionService.refundOutcome(Status.PENDING_CLAIM),
                AuctionService.refundOutcome(Status.FAILED));
        assertFalse(refundOutcomes.contains(BuyOutcome.OK_DELIVERED), "zwrot nie może dawać OK");
        assertFalse(refundOutcomes.contains(BuyOutcome.OK_ITEM_CLAIMED));
    }

    @Test
    void refundAuditResultMapping() {
        assertEquals(AuditAction.RESULT_ROLLBACK, AuctionService.refundAuditResult(Status.REFUNDED));
        assertEquals(AuditAction.RESULT_REFUND_PENDING, AuctionService.refundAuditResult(Status.PENDING_CLAIM));
        assertEquals(AuditAction.RESULT_FAILED, AuctionService.refundAuditResult(Status.FAILED));
    }

    @Test
    void deliveryOutcomeMapping() {
        assertEquals(BuyOutcome.OK_DELIVERED, AuctionService.deliveryOutcome(ItemRefundStatus.DELIVERED));
        assertEquals(BuyOutcome.OK_ITEM_CLAIMED, AuctionService.deliveryOutcome(ItemRefundStatus.CLAIMED));
        // Nieudany claim -> NIE OK, lecz krytyczny błąd kompensacji.
        assertEquals(BuyOutcome.COMPENSATION_FAILED, AuctionService.deliveryOutcome(ItemRefundStatus.FAILED));
    }

    @Test
    void distinctBuyOutcomesDoNotCollapse() {
        // Wszystkie stany są rozłączne; nie ma jednego generycznego "OK".
        assertEquals(13, BuyOutcome.values().length);
        for (BuyOutcome o : BuyOutcome.values()) {
            assertFalse(o.name().equals("OK"), "nie ma zbiorczego OK: " + o);
        }
    }

    @Test
    void buyResultFactories() {
        var l = listing(new java.math.BigDecimal("100"));
        assertEquals(BuyOutcome.OK_DELIVERED, AuctionService.BuyResult.delivered(l).outcome());
        assertEquals(BuyOutcome.OK_ITEM_CLAIMED, AuctionService.BuyResult.itemClaimed(l).outcome());
        assertEquals(BuyOutcome.REFUNDED,
                AuctionService.BuyResult.compensated(BuyOutcome.REFUNDED, l).outcome());
        assertEquals(new java.math.BigDecimal("100"),
                AuctionService.BuyResult.compensated(BuyOutcome.REFUNDED, l).pricePaid());
    }

    private static hex.auctionbazaar.auction.model.AuctionListing listing(java.math.BigDecimal price) {
        return new hex.auctionbazaar.auction.model.AuctionListing(1L, java.util.UUID.randomUUID(), "S",
                new byte[]{1}, "DIAMOND", 1, price,
                hex.auctionbazaar.auction.model.ListingState.ACTIVE, 0L, 1L, null, null, null, null,
                null, null, null, null);
    }

    // ---- withdraw-fail priorytet (sprzedaż) ----

    @Test
    void listingWithdrawFailItemFailureHasPriority() {
        // Niepowodzenie zwrotu przedmiotu przebija NOT_ENOUGH_MONEY / ECONOMY_ERROR.
        assertEquals(SellResult.COMPENSATION_FAILED,
                AuctionService.listingWithdrawFailResult(true, false, true));
        assertEquals(SellResult.COMPENSATION_FAILED,
                AuctionService.listingWithdrawFailResult(true, true, false));
    }

    @Test
    void listingWithdrawFailNormalResults() {
        assertEquals(SellResult.NOT_ENOUGH_MONEY,
                AuctionService.listingWithdrawFailResult(false, false, true));
        assertEquals(SellResult.ECONOMY_ERROR,
                AuctionService.listingWithdrawFailResult(false, true, false));
        assertEquals(SellResult.ECONOMY_ERROR,
                AuctionService.listingWithdrawFailResult(false, false, false));
    }
}
