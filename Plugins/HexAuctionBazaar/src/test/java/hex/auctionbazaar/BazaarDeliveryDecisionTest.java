package hex.auctionbazaar;

import hex.auctionbazaar.audit.model.AuditAction;
import hex.auctionbazaar.bazaar.service.BazaarService;
import hex.auctionbazaar.bazaar.service.BazaarService.BuyFinal;
import hex.auctionbazaar.bazaar.service.BazaarService.BuyResult;
import hex.auctionbazaar.bazaar.service.BazaarService.ItemDelivery;
import hex.auctionbazaar.bazaar.model.BazaarPrice;
import hex.auctionbazaar.util.InventoryFit;
import hex.auctionbazaar.util.RefundCompensation;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt przeglądu (Rynek): {@link BazaarService#resolveItemDelivery} (tri-state -> sposób dostawy)
 * oraz {@link BazaarService#combineBuy} (dostawa + status zwrotu -> terminalny wynik + audyt).
 * Weryfikuje brak duplikacji (STATE_UNCERTAIN nie tworzy claim-a) i rozłączność zwrotów:
 * REFUNDED / REFUND_PENDING / OVERPAY_REFUND_PENDING / COMPENSATION_FAILED.
 */
class BazaarDeliveryDecisionTest {

    private static Supplier<CompletableFuture<Boolean>> claim(boolean ok, AtomicInteger calls) {
        return () -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(ok);
        };
    }

    // ---------------------------------------------------------------- resolveItemDelivery

    @Test
    void addedFullyIsInInventoryNoClaim() {
        AtomicInteger c = new AtomicInteger();
        ItemDelivery d = BazaarService.resolveItemDelivery(
                InventoryFit.Result.ADDED_FULLY, claim(true, c)).join();
        assertEquals(ItemDelivery.IN_INVENTORY, d);
        assertEquals(0, c.get(), "dostawa do ekwipunku nie tworzy claim-a");
    }

    @Test
    void notFitRevertedInsertOkIsAsClaimExactlyOnce() {
        AtomicInteger c = new AtomicInteger();
        ItemDelivery d = BazaarService.resolveItemDelivery(
                InventoryFit.Result.NOT_FIT_REVERTED, claim(true, c)).join();
        assertEquals(ItemDelivery.AS_CLAIM, d);
        assertEquals(1, c.get(), "dokładnie jeden item-claim");
    }

    @Test
    void notFitRevertedInsertFailIsClaimFailed() {
        AtomicInteger c = new AtomicInteger();
        ItemDelivery d = BazaarService.resolveItemDelivery(
                InventoryFit.Result.NOT_FIT_REVERTED, claim(false, c)).join();
        assertEquals(ItemDelivery.CLAIM_FAILED, d);
        assertEquals(1, c.get());
    }

    @Test
    void stateUncertainIsUncertainNoClaim() {
        AtomicInteger c = new AtomicInteger();
        ItemDelivery d = BazaarService.resolveItemDelivery(
                InventoryFit.Result.STATE_UNCERTAIN, claim(true, c)).join();
        assertEquals(ItemDelivery.UNCERTAIN, d);
        assertEquals(0, c.get(), "STATE_UNCERTAIN nigdy nie tworzy claim-a");
    }

    @Test
    void resolveItemDeliveryFutureTerminalEvenIfClaimThrows() {
        Supplier<CompletableFuture<Boolean>> boom = () -> {
            throw new RuntimeException("insert boom");
        };
        CompletableFuture<ItemDelivery> f = BazaarService.resolveItemDelivery(
                InventoryFit.Result.NOT_FIT_REVERTED, boom);
        assertTrue(f.isDone());
        assertEquals(ItemDelivery.CLAIM_FAILED, f.join(), "wyjątek insertu -> CLAIM_FAILED, nie zawiśnięcie");
    }

    // ---------------------------------------------------------------- combineBuy

    private static void assertFinal(BuyResult expectedResult, String expectedAudit,
                                    ItemDelivery delivery, RefundCompensation.Status refund) {
        BuyFinal f = BazaarService.combineBuy(delivery, refund);
        assertEquals(expectedResult, f.buyResult(), "buyResult dla " + delivery + "/" + refund);
        assertEquals(expectedAudit, f.auditResult(), "audyt dla " + delivery + "/" + refund);
    }

    @Test
    void deliveredNoRefundIsOk() {
        assertFinal(BuyResult.OK, AuditAction.RESULT_OK, ItemDelivery.IN_INVENTORY, null);
        assertFinal(BuyResult.OK, AuditAction.RESULT_OK, ItemDelivery.AS_CLAIM, null);
    }

    @Test
    void deliveredWithDirectOverpayRefundIsOk() {
        assertFinal(BuyResult.OK, AuditAction.RESULT_OK,
                ItemDelivery.IN_INVENTORY, RefundCompensation.Status.REFUNDED);
    }

    @Test
    void deliveredWithPendingOverpayIsOverpayRefundPending() {
        assertFinal(BuyResult.OVERPAY_REFUND_PENDING, AuditAction.RESULT_REFUND_PENDING,
                ItemDelivery.AS_CLAIM, RefundCompensation.Status.PENDING_CLAIM);
    }

    @Test
    void deliveredButOverpayRefundFailedIsCompensationFailed() {
        assertFinal(BuyResult.COMPENSATION_FAILED, AuditAction.RESULT_FAILED,
                ItemDelivery.IN_INVENTORY, RefundCompensation.Status.FAILED);
    }

    @Test
    void nothingDeliveredDirectRefundIsRollback() {
        assertFinal(BuyResult.REFUNDED, AuditAction.RESULT_ROLLBACK,
                ItemDelivery.NONE, RefundCompensation.Status.REFUNDED);
        assertFinal(BuyResult.REFUNDED, AuditAction.RESULT_ROLLBACK,
                ItemDelivery.CLAIM_FAILED, RefundCompensation.Status.REFUNDED);
    }

    @Test
    void nothingDeliveredRefundClaimIsRefundPending() {
        assertFinal(BuyResult.REFUND_PENDING, AuditAction.RESULT_REFUND_PENDING,
                ItemDelivery.NONE, RefundCompensation.Status.PENDING_CLAIM);
        assertFinal(BuyResult.REFUND_PENDING, AuditAction.RESULT_REFUND_PENDING,
                ItemDelivery.CLAIM_FAILED, RefundCompensation.Status.PENDING_CLAIM);
    }

    @Test
    void nothingDeliveredRefundFailedIsCompensationFailed() {
        assertFinal(BuyResult.COMPENSATION_FAILED, AuditAction.RESULT_FAILED,
                ItemDelivery.NONE, RefundCompensation.Status.FAILED);
        assertFinal(BuyResult.COMPENSATION_FAILED, AuditAction.RESULT_FAILED,
                ItemDelivery.CLAIM_FAILED, RefundCompensation.Status.FAILED);
    }

    @Test
    void uncertainAlwaysFailsWithoutRefund() {
        // STATE_UNCERTAIN dominuje - zawsze krytyczny błąd, niezależnie od (nieistniejącego) zwrotu.
        assertFinal(BuyResult.COMPENSATION_FAILED, AuditAction.RESULT_FAILED, ItemDelivery.UNCERTAIN, null);
        assertFinal(BuyResult.COMPENSATION_FAILED, AuditAction.RESULT_FAILED,
                ItemDelivery.UNCERTAIN, RefundCompensation.Status.REFUNDED);
    }

    @Test
    void claimFailedWithoutRefundIsCompensationFailed() {
        // Darmowy przedmiot, którego nie dało się dostarczyć, a nie ma czego zwracać -> nadal FAILED.
        assertFinal(BuyResult.COMPENSATION_FAILED, AuditAction.RESULT_FAILED, ItemDelivery.CLAIM_FAILED, null);
    }

    @Test
    void nothingDeliveredWithoutRefundIsNeverSuccess() {
        assertFinal(BuyResult.DB_FAILED, AuditAction.RESULT_FAILED, ItemDelivery.NONE, null);
    }

    @Test
    void outcomeCarriesActuallyDeliveredAmount() {
        BazaarPrice price = new BazaarPrice(new BigDecimal("5"), new BigDecimal("6"),
                new BigDecimal("4"));
        var outcome = BazaarService.toOutcome(BuyResult.OK, price, new BigDecimal("18"),
                3, ItemDelivery.IN_INVENTORY);
        assertEquals(BuyResult.OK, outcome.result());
        assertEquals(3, outcome.deliveredAmount());
        assertEquals(new BigDecimal("18"), outcome.total());
    }

    @Test
    void auditReasonContainsCompleteFinancialReconciliation() {
        String reason = BazaarService.buildBuyAuditReason("hybryda", 64, 12,
                new BigDecimal("640"), new BigDecimal("120"), new BigDecimal("520"),
                ItemDelivery.IN_INVENTORY, RefundCompensation.Status.PENDING_CLAIM);
        assertTrue(reason.contains("zamówiono=64"));
        assertTrue(reason.contains("dostarczono=12"));
        assertTrue(reason.contains("pobrano=640"));
        assertTrue(reason.contains("rozliczono=120"));
        assertTrue(reason.contains("zwrot_należny=520"));
        assertTrue(reason.contains("zwrot=czeka do odbioru"));
    }
}
