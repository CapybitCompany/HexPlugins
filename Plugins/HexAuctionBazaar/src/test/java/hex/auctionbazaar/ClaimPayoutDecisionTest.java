package hex.auctionbazaar;

import hex.auctionbazaar.auction.service.AuctionService;
import hex.auctionbazaar.auction.service.AuctionService.ClaimOutcome;
import hex.auctionbazaar.util.InventoryFit;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt przeglądu (odbiór claim-a): {@link AuctionService#resolveItemPayout} - mapowanie tri-state
 * ekwipunku na wynik wypłaty BEZ duplikacji. ADDED_FULLY -> delete (nie rollback); NOT_FIT_REVERTED ->
 * rollback -> INVENTORY_FULL albo COMPENSATION_FAILED; STATE_UNCERTAIN -> ani delete, ani rollback
 * (claim zostaje w CLAIMING, brak ponownej wypłaty).
 */
class ClaimPayoutDecisionTest {

    private static Supplier<CompletableFuture<Boolean>> supplier(boolean ok, AtomicInteger calls) {
        return () -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(ok);
        };
    }

    @Test
    void addedFullyDeletesClaimNotRollback() {
        AtomicInteger del = new AtomicInteger(), rb = new AtomicInteger(), delFail = new AtomicInteger();
        ClaimOutcome o = AuctionService.resolveItemPayout(InventoryFit.Result.ADDED_FULLY,
                supplier(true, del), supplier(true, rb),
                delFail::incrementAndGet, () -> { }, () -> { }).join();
        assertEquals(ClaimOutcome.OK, o);
        assertEquals(1, del.get(), "usuwamy claim po pełnym wydaniu");
        assertEquals(0, rb.get(), "NIE robimy rollbacku po pełnym wydaniu");
        assertEquals(0, delFail.get());
    }

    @Test
    void addedFullyDeleteFailStaysOkLogsAndNeverRollsBack() {
        AtomicInteger del = new AtomicInteger(), rb = new AtomicInteger(), delFail = new AtomicInteger();
        ClaimOutcome o = AuctionService.resolveItemPayout(InventoryFit.Result.ADDED_FULLY,
                supplier(false, del), supplier(true, rb),
                delFail::incrementAndGet, () -> { }, () -> { }).join();
        assertEquals(ClaimOutcome.OK, o, "przedmiot wydany -> OK mimo nieudanego delete");
        assertEquals(1, delFail.get(), "log do ręcznej korekty (claim zostaje CLAIMING)");
        assertEquals(0, rb.get(), "NIGDY z powrotem na PENDING po wydaniu przedmiotu");
    }

    @Test
    void notFitRevertedRollbackOkGivesInventoryFull() {
        AtomicInteger del = new AtomicInteger(), rb = new AtomicInteger(), rbFail = new AtomicInteger();
        ClaimOutcome o = AuctionService.resolveItemPayout(InventoryFit.Result.NOT_FIT_REVERTED,
                supplier(true, del), supplier(true, rb),
                () -> { }, rbFail::incrementAndGet, () -> { }).join();
        assertEquals(ClaimOutcome.INVENTORY_FULL, o, "dopiero po UDANYM rollbacku -> INVENTORY_FULL");
        assertEquals(1, rb.get(), "rollback CLAIMING->PENDING wykonany");
        assertEquals(0, del.get(), "nie usuwamy claim-a przy pełnym ekwipunku");
        assertEquals(0, rbFail.get());
    }

    @Test
    void notFitRevertedRollbackFailIsCompensationFailedNotInventoryFull() {
        AtomicInteger rb = new AtomicInteger(), rbFail = new AtomicInteger(), del = new AtomicInteger();
        ClaimOutcome o = AuctionService.resolveItemPayout(InventoryFit.Result.NOT_FIT_REVERTED,
                supplier(true, del), supplier(false, rb),
                () -> { }, rbFail::incrementAndGet, () -> { }).join();
        assertEquals(ClaimOutcome.COMPENSATION_FAILED, o, "nieudany rollback NIE jest 'ekwipunek pełny'");
        assertEquals(1, rbFail.get(), "log techniczny admina");
        assertEquals(0, del.get());
    }

    @Test
    void stateUncertainNeitherDeletesNorRollsBackStaysClaiming() {
        AtomicInteger del = new AtomicInteger(), rb = new AtomicInteger(), unc = new AtomicInteger();
        ClaimOutcome o = AuctionService.resolveItemPayout(InventoryFit.Result.STATE_UNCERTAIN,
                supplier(true, del), supplier(true, rb),
                () -> { }, () -> { }, unc::incrementAndGet).join();
        assertEquals(ClaimOutcome.COMPENSATION_FAILED, o);
        assertEquals(0, del.get(), "STATE_UNCERTAIN nie usuwa claim-a");
        assertEquals(0, rb.get(), "STATE_UNCERTAIN nie cofa na PENDING (brak ponownej wypłaty -> brak duplikacji)");
        assertEquals(1, unc.get(), "rate-limitowany log wykonany raz");
    }

    @Test
    void futureAlwaysTerminalEvenIfDeleteSupplierThrows() {
        Supplier<CompletableFuture<Boolean>> boom = () -> {
            throw new RuntimeException("db boom");
        };
        CompletableFuture<ClaimOutcome> f = AuctionService.resolveItemPayout(
                InventoryFit.Result.ADDED_FULLY, boom, () -> CompletableFuture.completedFuture(true),
                () -> { }, () -> { }, () -> { });
        assertTrue(f.isDone(), "future domknięty mimo wyjątku delete");
        assertEquals(ClaimOutcome.OK, f.join());
    }
}
