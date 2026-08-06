package hex.auctionbazaar.audit.service;

import hex.auctionbazaar.audit.model.AuditAction;
import hex.auctionbazaar.audit.repository.AuditLogRepository;
import hex.auctionbazaar.bridge.HexCoreBridge;
import hex.auctionbazaar.config.MessagesConfig;
import hex.auctionbazaar.testutil.InMemoryDb;
import hex.auctionbazaar.util.MessageFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #8: audyt lokalizuje techniczne tokeny (akcja/rynek) na polskie etykiety; nieznany token ->
 * bezpieczny polski opis (nigdy surowy enum bez kontekstu). Plus: ograniczone zamknięcie audytu.
 */
class AuditLocalizationTest {

    private static AuditService service() {
        Logger log = Logger.getAnonymousLogger();
        MessagesConfig cfg = new MessagesConfig(Map.of(
                "auction.admin-audit-action.bazaar_instant_buy", "Kupno w Rynku",
                "auction.admin-audit-market.auction", "Dom Aukcyjny",
                "auction.admin-audit-unknown", "(nieznane)",
                "auction.admin-audit-unknown-token", "nieznane (<token>)"));
        MessageFactory messages = new MessageFactory(() -> cfg, () -> "");
        return new AuditService(log, new HexCoreBridge(log),
                new AuditLogRepository(new InMemoryDb()), messages);
    }

    @Test
    void knownActionAndMarketTokensAreLocalized() {
        AuditService s = service();
        assertEquals("Kupno w Rynku",
                s.localizeToken("auction.admin-audit-action.", AuditAction.BAZAAR_INSTANT_BUY));
        assertEquals("Dom Aukcyjny",
                s.localizeToken("auction.admin-audit-market.", AuditAction.MARKET_AUCTION));
    }

    @Test
    void unknownTokenGetsSafePolishFallbackNotRawEnumAlone() {
        AuditService s = service();
        String out = s.localizeToken("auction.admin-audit-action.", "SOME_NEW_TOKEN");
        assertTrue(out.contains("nieznane"), "polski opis: " + out);
        assertTrue(out.contains("SOME_NEW_TOKEN"), "token do diagnostyki zachowany: " + out);
    }

    @Test
    void nullTokenGivesUnknownLabel() {
        assertEquals("(nieznane)", service().localizeToken("auction.admin-audit-action.", null));
    }

    @Test
    void shutdownStopsNewInsertsAndCompletesTerminally() {
        AuditService s = service();
        s.awaitPending(0L, true);   // DISABLE: bounded wait + stop accepting new
        assertTrue(s.isShutdown());
        Long id = s.log(s.builder().action(AuditAction.ADMIN_ACTION).market(AuditAction.MARKET_ADMIN)
                .result(AuditAction.RESULT_OK)).join();
        assertEquals(-1L, id, "po zamknięciu nie wstawiamy - wynik terminalny -1");
        assertEquals(0, s.pendingCount(), "brak zawieszonych futures");
        assertFalse(id == null);
    }
}
