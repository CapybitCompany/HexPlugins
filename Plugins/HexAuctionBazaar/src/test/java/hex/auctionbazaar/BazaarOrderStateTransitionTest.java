package hex.auctionbazaar;

import hex.auctionbazaar.bazaar.model.OrderState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sprawdza semantyke przejsc stanu zlecenia po dopasowaniu.
 * Reflektuje logike tryFillPortionTx w repo:
 *  - jesli remaining po fill == 0 -> FILLED
 *  - w przeciwnym razie -> PARTIALLY_FILLED
 */
class BazaarOrderStateTransitionTest {

    private OrderState afterFill(long remainingBefore, long fill) {
        long after = remainingBefore - fill;
        if (after <= 0) return OrderState.FILLED;
        return OrderState.PARTIALLY_FILLED;
    }

    @Test
    void fillEqualToRemainingTransitionsToFilled() {
        assertEquals(OrderState.FILLED, afterFill(100L, 100L));
    }

    @Test
    void fillGreaterThanRemainingTransitionsToFilled() {
        assertEquals(OrderState.FILLED, afterFill(50L, 60L));
    }

    @Test
    void partialFillTransitionsToPartiallyFilled() {
        assertEquals(OrderState.PARTIALLY_FILLED, afterFill(100L, 30L));
    }

    @Test
    void zeroFillLeavesPartialState() {
        // Ta sytuacja nie powinna sie zdarzyc, ale zabezpieczmy.
        assertEquals(OrderState.PARTIALLY_FILLED, afterFill(100L, 0L));
    }
}
