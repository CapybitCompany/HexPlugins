package hex.auctionbazaar;

import hex.auctionbazaar.bazaar.model.BazaarOrder;
import hex.auctionbazaar.bazaar.model.OrderSide;
import hex.auctionbazaar.bazaar.model.OrderState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BazaarOrderModelTest {

    @Test
    void constructorPreservesFields() {
        UUID uuid = UUID.randomUUID();
        BazaarOrder o = new BazaarOrder(
                42L, uuid, "Test",
                "diamond", OrderSide.BUY,
                100L, 40L,
                new BigDecimal("500.00"),
                new BigDecimal("20000.00"),
                OrderState.PARTIALLY_FILLED,
                1000L, 2000L, null);
        assertEquals(42L, o.id());
        assertEquals(uuid, o.ownerUuid());
        assertEquals(OrderSide.BUY, o.side());
        assertEquals(OrderState.PARTIALLY_FILLED, o.state());
        assertEquals(100L, o.amountTotal());
        assertEquals(40L, o.amountRemaining());
        assertEquals(new BigDecimal("500.00"), o.pricePerUnit());
        assertEquals(new BigDecimal("20000.00"), o.reservedMoney());
    }

    @Test
    void filledOrderHasZeroRemaining() {
        BazaarOrder o = new BazaarOrder(
                1L, UUID.randomUUID(), null,
                "iron_ingot", OrderSide.SELL,
                64L, 0L,
                new BigDecimal("10.00"),
                null,
                OrderState.FILLED,
                0L, 1L, null);
        assertEquals(0L, o.amountRemaining());
        assertEquals(OrderState.FILLED, o.state());
    }
}
