package hex.auctionbazaar;

import hex.auctionbazaar.audit.model.AuditAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sanity-check dla stalych result-string uzywanych w AuditService.
 * Aktualne wartosci sa spodziewane przez formatter w messages.yml.
 */
class AuditResultValuesTest {

    @Test
    void resultValuesAreStable() {
        assertEquals("OK", AuditAction.RESULT_OK);
        assertEquals("FAILED", AuditAction.RESULT_FAILED);
        assertEquals("ROLLBACK", AuditAction.RESULT_ROLLBACK);
        assertEquals("REFUND_PENDING", AuditAction.RESULT_REFUND_PENDING);
    }

    @Test
    void marketConstantsAreStable() {
        assertEquals("AUCTION", AuditAction.MARKET_AUCTION);
        assertEquals("BAZAAR", AuditAction.MARKET_BAZAAR);
        assertEquals("ADMIN", AuditAction.MARKET_ADMIN);
    }
}
