package hex.auctionbazaar;

import hex.auctionbazaar.audit.model.AuditAction;
import hex.auctionbazaar.auction.service.AuctionService;
import hex.auctionbazaar.auction.service.AuctionService.ItemRefundStatus;
import hex.auctionbazaar.util.RefundCompensation.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #5: reguły łączenia statusów kompensacji (item + geld). Pełny wynik
 * terminalny bazuje na tych czystych funkcjach - każdy błąd = FAILED, dowolne
 * "pending" = REFUND_PENDING, komplet dostarczony/zwrócony = ROLLBACK.
 */
class CompensationDecisionTest {

    @Test
    void combineClaimResultsRules() {
        assertEquals(ItemRefundStatus.DELIVERED, AuctionService.combineClaimResults(List.of()));
        assertEquals(ItemRefundStatus.CLAIMED, AuctionService.combineClaimResults(List.of(true)));
        assertEquals(ItemRefundStatus.CLAIMED, AuctionService.combineClaimResults(List.of(true, true)));
        assertEquals(ItemRefundStatus.FAILED, AuctionService.combineClaimResults(List.of(true, false)));
        assertEquals(ItemRefundStatus.FAILED, AuctionService.combineClaimResults(List.of(false)));
    }

    @Test
    void auditResultFailedWhenAnyFailed() {
        assertEquals(AuditAction.RESULT_FAILED,
                AuctionService.compensationAuditResult(ItemRefundStatus.FAILED, Status.REFUNDED));
        assertEquals(AuditAction.RESULT_FAILED,
                AuctionService.compensationAuditResult(ItemRefundStatus.DELIVERED, Status.FAILED));
        assertEquals(AuditAction.RESULT_FAILED,
                AuctionService.compensationAuditResult(ItemRefundStatus.FAILED, Status.FAILED));
    }

    @Test
    void auditResultPendingWhenAnyClaimedOrPending() {
        assertEquals(AuditAction.RESULT_REFUND_PENDING,
                AuctionService.compensationAuditResult(ItemRefundStatus.CLAIMED, Status.REFUNDED));
        assertEquals(AuditAction.RESULT_REFUND_PENDING,
                AuctionService.compensationAuditResult(ItemRefundStatus.DELIVERED, Status.PENDING_CLAIM));
    }

    @Test
    void auditResultRollbackWhenFullyResolved() {
        assertEquals(AuditAction.RESULT_ROLLBACK,
                AuctionService.compensationAuditResult(ItemRefundStatus.DELIVERED, Status.REFUNDED));
    }

    @Test
    void itemRefundStatusValuesStable() {
        assertEquals(3, ItemRefundStatus.values().length);
    }

    // ---- trackItemReturn (śledzenie rekompensaty przedmiotu) ----

    @Test
    void fullyDeliveredCompletesImmediatelyDelivered() {
        var f = AuctionService.trackItemReturn(true, List.of("x"), it -> okFuture());
        assertTrue(f.isDone());
        assertEquals(ItemRefundStatus.DELIVERED, f.join());
    }

    @Test
    void offlineOrLeftoverClaimSuccessMeansClaimed() {
        // Offline gracz -> jeden claim; sukces -> CLAIMED.
        assertEquals(ItemRefundStatus.CLAIMED,
                AuctionService.trackItemReturn(false, List.of("item"), it -> okFuture(true)).join());
        // Reszta z ekwipunku (kilka stacków) -> wszystkie OK -> CLAIMED.
        assertEquals(ItemRefundStatus.CLAIMED,
                AuctionService.trackItemReturn(false, List.of("a", "b"), it -> okFuture(true)).join());
    }

    @Test
    void claimInsertFailureMeansTerminalFailed() {
        assertEquals(ItemRefundStatus.FAILED,
                AuctionService.trackItemReturn(false, List.of("a", "b"),
                        it -> okFuture("b".equals(it) ? false : true)).join());
    }

    @Test
    void claimInsertThrowingMeansFailed() {
        assertEquals(ItemRefundStatus.FAILED,
                AuctionService.trackItemReturn(false, List.of("x"), it -> {
                    throw new RuntimeException("db down");
                }).join());
    }

    @Test
    void resultWaitsForClaimInsertToComplete() {
        java.util.concurrent.CompletableFuture<Boolean> pending = new java.util.concurrent.CompletableFuture<>();
        var f = AuctionService.trackItemReturn(false, List.of("x"), it -> pending);
        assertFalse(f.isDone(), "wynik czeka na potwierdzenie claim-insertu");
        pending.complete(true);
        assertTrue(f.isDone());
        assertEquals(ItemRefundStatus.CLAIMED, f.join());
    }

    private static java.util.concurrent.CompletableFuture<Boolean> okFuture() {
        return okFuture(true);
    }

    private static java.util.concurrent.CompletableFuture<Boolean> okFuture(boolean v) {
        return java.util.concurrent.CompletableFuture.completedFuture(v);
    }
}
