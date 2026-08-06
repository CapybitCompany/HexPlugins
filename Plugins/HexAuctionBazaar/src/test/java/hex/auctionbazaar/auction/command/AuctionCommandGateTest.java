package hex.auctionbazaar.auction.command;

import org.junit.jupiter.api.Test;

import static hex.auctionbazaar.auction.command.AuctionCommand.CommandGate;
import static hex.auctionbazaar.auction.command.AuctionCommand.commandGate;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Punkt #4: jednolita bramka „enabled" Domu Aukcyjnego. Globalny tryb konserwacji ORAZ
 * feature-specific disable (auction.enabled:false) muszą BLOKOWAĆ nową komercję, ale PRZEPUSZCZAĆ akcje
 * odzysku/diagnostyki (reload, admin, cancel, claims, mylistings) - dalej chronione permisją i bramką DB.
 */
class AuctionCommandGateTest {

    @Test
    void recoveryAllowedUnderGlobalMaintenance() {
        for (String sub : new String[]{"reload", "admin", "cancel", "claims", "mylistings", "mine"}) {
            assertEquals(CommandGate.ALLOW, commandGate(sub, false, true), sub + " (global disable)");
        }
    }

    @Test
    void recoveryAllowedUnderFeatureSpecificDisable() {
        // KLUCZOWA naprawa: przy auction.enabled:false akcje odzysku NADAL przechodzą bramkę.
        for (String sub : new String[]{"reload", "admin", "cancel", "claims", "mylistings", "mine"}) {
            assertEquals(CommandGate.ALLOW, commandGate(sub, true, false), sub + " (feature disable)");
        }
    }

    @Test
    void recoveryAllowedWhenBothDisabled() {
        assertEquals(CommandGate.ALLOW, commandGate("cancel", false, false));
        assertEquals(CommandGate.ALLOW, commandGate("claims", false, false));
    }

    @Test
    void commerceBlockedByFeatureDisable() {
        assertEquals(CommandGate.FEATURE_DISABLED, commandGate("sell", true, false));
        // Puste (otwarcie GUI przeglądania prowadzącego do kupna) to komercja.
        assertEquals(CommandGate.FEATURE_DISABLED, commandGate("", true, false));
    }

    @Test
    void commerceBlockedByGlobalMaintenance() {
        assertEquals(CommandGate.MAINTENANCE, commandGate("sell", false, true));
        assertEquals(CommandGate.MAINTENANCE, commandGate("", false, true));
    }

    @Test
    void globalMaintenanceTakesPrecedenceOverFeatureDisable() {
        assertEquals(CommandGate.MAINTENANCE, commandGate("sell", false, false),
                "gdy oba wyłączone, komunikat konserwacji ma pierwszeństwo");
    }

    @Test
    void everythingAllowedWhenBothEnabled() {
        assertEquals(CommandGate.ALLOW, commandGate("sell", true, true));
        assertEquals(CommandGate.ALLOW, commandGate("", true, true));
        assertEquals(CommandGate.ALLOW, commandGate("cancel", true, true));
    }
}
