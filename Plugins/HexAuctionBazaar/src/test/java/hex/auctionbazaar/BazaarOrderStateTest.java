package hex.auctionbazaar;

import hex.auctionbazaar.bazaar.model.OrderState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BazaarOrderStateTest {

    @Test
    void allStatesExist() {
        assertNotNull(OrderState.valueOf("ACTIVE"));
        assertNotNull(OrderState.valueOf("PARTIALLY_FILLED"));
        assertNotNull(OrderState.valueOf("FILLED"));
        assertNotNull(OrderState.valueOf("CANCELLED"));
        assertNotNull(OrderState.valueOf("EXPIRED"));
        assertEquals(5, OrderState.values().length);
    }

    @Test
    void openStatesAreActiveAndPartial() {
        assertTrue(OrderState.ACTIVE.isOpen());
        assertTrue(OrderState.PARTIALLY_FILLED.isOpen());
        assertFalse(OrderState.FILLED.isOpen());
        assertFalse(OrderState.CANCELLED.isOpen());
        assertFalse(OrderState.EXPIRED.isOpen());
    }

    @Test
    void finalStatesAreFilledCancelledExpired() {
        assertTrue(OrderState.FILLED.isFinal());
        assertTrue(OrderState.CANCELLED.isFinal());
        assertTrue(OrderState.EXPIRED.isFinal());
        assertFalse(OrderState.ACTIVE.isFinal());
        assertFalse(OrderState.PARTIALLY_FILLED.isFinal());
    }
}
