package hex.auctionbazaar;

import hex.auctionbazaar.audit.model.AuditAction;
import hex.auctionbazaar.bazaar.service.BazaarOrderService;
import hex.auctionbazaar.bazaar.service.BazaarOrderService.BuyRefundOutcome;
import hex.auctionbazaar.bazaar.service.BazaarOrderService.PlaceResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #3: kompensacja po nieudanym zapisie zlecenia BUY (środki JUŻ pobrane). Klasyfikacja jest czysta,
 * testowalna i użyta PRODUKCYJNIE przez {@code compensateBuyPlacementFailure}:
 *  - deposit OK -> DB_FAILED (audyt ROLLBACK);
 *  - deposit nieudany, money-claim OK -> DB_FAILED (audyt REFUND_PENDING);
 *  - deposit ORAZ money-claim nieudane -> COMPENSATION_FAILED (audyt FAILED).
 * „Nieudany" deposit obejmuje success=false, null i wyjątek; „nieudany" claim - ujemne id, null i wyjątek.
 */
class BazaarBuyRefundTest {

    // ------------------------------------------------------------- deposit: success / false / null / exception

    @Test
    void depositRefundOkOnlyForSuccess() {
        assertTrue(BazaarOrderService.depositRefundOk(false, false, true), "sukces");
        assertFalse(BazaarOrderService.depositRefundOk(false, false, false), "success=false");
        assertFalse(BazaarOrderService.depositRefundOk(false, true, false), "null wynik");
        assertFalse(BazaarOrderService.depositRefundOk(true, false, false), "wyjątek");
    }

    // ------------------------------------------------------------- claim: success / false / null / exception

    @Test
    void claimRefundOkOnlyForConfirmedNonNegativeId() {
        assertTrue(BazaarOrderService.claimRefundOk(false, false, 1L), "sukces (id>=0)");
        assertFalse(BazaarOrderService.claimRefundOk(false, false, -1L), "ujemne id (false)");
        assertFalse(BazaarOrderService.claimRefundOk(false, true, -1L), "null id");
        assertFalse(BazaarOrderService.claimRefundOk(true, false, -1L), "wyjątek");
    }

    // ------------------------------------------------------------- terminalna mapa (deposit x claim)

    @Test
    void depositOkGivesDbFailedRollback() {
        assertEquals(new BuyRefundOutcome(PlaceResult.DB_FAILED, AuditAction.RESULT_ROLLBACK),
                BazaarOrderService.classifyBuyRefund(true, false));
        assertEquals(new BuyRefundOutcome(PlaceResult.DB_FAILED, AuditAction.RESULT_ROLLBACK),
                BazaarOrderService.classifyBuyRefund(true, true), "deposit ok wygrywa niezależnie od claim");
    }

    @Test
    void depositFailClaimOkGivesDbFailedRefundPending() {
        assertEquals(new BuyRefundOutcome(PlaceResult.DB_FAILED, AuditAction.RESULT_REFUND_PENDING),
                BazaarOrderService.classifyBuyRefund(false, true));
    }

    @Test
    void depositFailClaimFailGivesCompensationFailed() {
        // KLUCZOWA naprawa (punkt #3): oba nieudane -> COMPENSATION_FAILED (nie zwykły DB_FAILED).
        assertEquals(new BuyRefundOutcome(PlaceResult.COMPENSATION_FAILED, AuditAction.RESULT_FAILED),
                BazaarOrderService.classifyBuyRefund(false, false));
    }

    // ------------------------------------------------------------- pełna matryca „success/false/null/exception"

    @Test
    void fullMatrixDepositTimesClaim() {
        // deposit args: (hadError, nullResult, success); claim args: (hadError, nullId, id).
        boolean[][] depositNotOk = {{false, false, false}, {false, true, false}, {true, false, false}};
        Object[][] claimNotOk = {{false, false, -1L}, {false, true, -1L}, {true, false, -1L}};

        // Deposit sukces -> zawsze DB_FAILED/ROLLBACK, niezależnie od claim.
        boolean depOk = BazaarOrderService.depositRefundOk(false, false, true);
        assertEquals(new BuyRefundOutcome(PlaceResult.DB_FAILED, AuditAction.RESULT_ROLLBACK),
                BazaarOrderService.classifyBuyRefund(depOk, false));

        // Deposit nieudany (false/null/exception) x claim sukces -> DB_FAILED/REFUND_PENDING.
        boolean claimOk = BazaarOrderService.claimRefundOk(false, false, 5L);
        assertTrue(claimOk);
        for (boolean[] dep : depositNotOk) {
            boolean d = BazaarOrderService.depositRefundOk(dep[0], dep[1], dep[2]);
            assertFalse(d, "deposit nieudany");
            assertEquals(new BuyRefundOutcome(PlaceResult.DB_FAILED, AuditAction.RESULT_REFUND_PENDING),
                    BazaarOrderService.classifyBuyRefund(d, claimOk));
        }

        // Deposit nieudany x claim nieudany (false/null/exception) -> COMPENSATION_FAILED/FAILED.
        boolean depFail = BazaarOrderService.depositRefundOk(false, false, false);
        for (Object[] cl : claimNotOk) {
            boolean c = BazaarOrderService.claimRefundOk((boolean) cl[0], (boolean) cl[1], (long) cl[2]);
            assertFalse(c, "claim nieudany");
            assertEquals(new BuyRefundOutcome(PlaceResult.COMPENSATION_FAILED, AuditAction.RESULT_FAILED),
                    BazaarOrderService.classifyBuyRefund(depFail, c));
        }
    }
}
