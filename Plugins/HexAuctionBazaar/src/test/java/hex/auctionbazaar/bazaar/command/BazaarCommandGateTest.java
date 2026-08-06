package hex.auctionbazaar.bazaar.command;

import org.junit.jupiter.api.Test;

import static hex.auctionbazaar.bazaar.command.BazaarCommand.CommandGate;
import static hex.auctionbazaar.bazaar.command.BazaarCommand.commandGate;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Punkt #4: jednolita bramka „enabled" Rynku. Globalny tryb konserwacji ORAZ feature-specific disable
 * (bazaar.enabled:false) muszą BLOKOWAĆ nową komercję (buy/sell/buyorder/selloffer, otwarcie GUI Rynku),
 * ale PRZEPUSZCZAĆ akcje odzysku (reload, orders, order) - dalej chronione permisją i bramką DB.
 */
class BazaarCommandGateTest {

    @Test
    void recoveryAllowedUnderGlobalMaintenance() {
        for (String sub : new String[]{"reload", "orders", "order"}) {
            assertEquals(CommandGate.ALLOW, commandGate(sub, false, true), sub + " (global disable)");
        }
    }

    @Test
    void recoveryAllowedUnderFeatureSpecificDisable() {
        // KLUCZOWA naprawa: przy bazaar.enabled:false podgląd/anulowanie własnych zleceń NADAL przechodzi.
        for (String sub : new String[]{"reload", "orders", "order"}) {
            assertEquals(CommandGate.ALLOW, commandGate(sub, true, false), sub + " (feature disable)");
        }
    }

    @Test
    void commerceBlockedByFeatureDisable() {
        for (String sub : new String[]{"buy", "sell", "buyorder", "selloffer", ""}) {
            assertEquals(CommandGate.FEATURE_DISABLED, commandGate(sub, true, false), sub + " (feature disable)");
        }
    }

    @Test
    void commerceBlockedByGlobalMaintenance() {
        for (String sub : new String[]{"buy", "sell", "buyorder", "selloffer", ""}) {
            assertEquals(CommandGate.MAINTENANCE, commandGate(sub, false, true), sub + " (global disable)");
        }
    }

    @Test
    void globalMaintenanceTakesPrecedenceOverFeatureDisable() {
        assertEquals(CommandGate.MAINTENANCE, commandGate("buy", false, false));
    }

    @Test
    void everythingAllowedWhenBothEnabled() {
        assertEquals(CommandGate.ALLOW, commandGate("buy", true, true));
        assertEquals(CommandGate.ALLOW, commandGate("order", true, true));
        assertEquals(CommandGate.ALLOW, commandGate("", true, true));
    }
}
