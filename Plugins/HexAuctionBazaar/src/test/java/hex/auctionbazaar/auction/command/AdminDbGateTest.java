package hex.auctionbazaar.auction.command;

import org.junit.jupiter.api.Test;

import static hex.auctionbazaar.auction.command.AuctionCommand.AdminDbGate;
import static hex.auctionbazaar.auction.command.AuctionCommand.adminDbGate;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Punkt #10: precyzyjna bramka DB dla /hexauction admin. Tylko dbstatus (i reload, obsłużony osobno)
 * omijają bramkę; cleanup oraz audit wymagają zdrowej bazy i gotowego schematu - inaczej odpowiedni
 * komunikat i BRAK zapytania. Sprawdzamy wszystkie podkomendy przy DB down / schema not ready.
 */
class AdminDbGateTest {

    @Test
    void dbstatusAlwaysBypassesGate() {
        assertEquals(AdminDbGate.ALLOW, adminDbGate("dbstatus", true, true));
        assertEquals(AdminDbGate.ALLOW, adminDbGate("dbstatus", false, false));
        assertEquals(AdminDbGate.ALLOW, adminDbGate("dbstatus", false, true));
        assertEquals(AdminDbGate.ALLOW, adminDbGate("dbstatus", true, false));
    }

    @Test
    void cleanupRequiresHealthyDbAndReadySchema() {
        assertEquals(AdminDbGate.ALLOW, adminDbGate("cleanup", true, true));
        assertEquals(AdminDbGate.DB_DOWN, adminDbGate("cleanup", false, true));
        assertEquals(AdminDbGate.SCHEMA_NOT_READY, adminDbGate("cleanup", true, false));
        // DB down ma pierwszeństwo nad schema-not-ready.
        assertEquals(AdminDbGate.DB_DOWN, adminDbGate("cleanup", false, false));
    }

    @Test
    void auditRequiresHealthyDbAndReadySchema() {
        assertEquals(AdminDbGate.ALLOW, adminDbGate("audit", true, true));
        assertEquals(AdminDbGate.DB_DOWN, adminDbGate("audit", false, true));
        assertEquals(AdminDbGate.SCHEMA_NOT_READY, adminDbGate("audit", true, false));
        assertEquals(AdminDbGate.DB_DOWN, adminDbGate("audit", false, false));
    }

    @Test
    void unknownMutatingSubIsGatedTooNeverSilentZeroResults() {
        // Nieznana/mutująca podkomenda również nie przechodzi przy niezdrowej bazie.
        assertEquals(AdminDbGate.DB_DOWN, adminDbGate("whatever", false, true));
        assertEquals(AdminDbGate.SCHEMA_NOT_READY, adminDbGate("whatever", true, false));
    }
}
