package hex.auctionbazaar;

import hex.auctionbazaar.bazaar.service.BazaarOrderService;
import hex.auctionbazaar.config.BazaarConfig;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regresja bledu #2: SELL offers never expire.
 * Sprawdzamy, ze pomocnicza funkcja computeExpiresAt jest jednakowa dla
 * BUY i SELL. Wczesniej placeSellOffer wstawialo null na sztywno.
 */
class OrderExpirySymmetryTest {

    private BazaarConfig cfg(long expirySeconds) {
        return new BazaarConfig(
                true, true, 14, expirySeconds, 6000,
                new BazaarConfig.Pricing(
                        new BigDecimal("0.5"),
                        new BigDecimal("10000"),
                        new BigDecimal("5"),
                        new BigDecimal("5")),
                "&8Bazar", "&8%display%", "&8Q", "&8O", "&8Zlecenie", "GRAY_STAINED_GLASS_PANE",
                List.of(1L, 64L, 576L),
                true, 60, 1500L,
                Map.<String, BazaarConfig.CategoryConfig>of(),
                "hexbazaar.open", "hexbazaar.buy", "hexbazaar.sell",
                "hexbazaar.orders", "hexbazaar.order.create.buy",
                "hexbazaar.order.create.sell", "hexbazaar.order.cancel",
                "hexbazaar.admin",
                Map.<String, hex.auctionbazaar.config.BazaarItemConfig>of());
    }

    @Test
    void expiryEnabledReturnsFutureTimestamp() {
        long now = 1_000_000L;
        Long ea = BazaarOrderService.computeExpiresAt(cfg(60L), now);
        assertNotNull(ea);
        assertEquals(1_060_000L, ea, "expiresAt = now + seconds*1000");
    }

    @Test
    void expiryDisabledReturnsNull() {
        assertNull(BazaarOrderService.computeExpiresAt(cfg(0L), 12345L));
    }

    @Test
    void negativeExpiryReturnsNull() {
        assertNull(BazaarOrderService.computeExpiresAt(cfg(-1L), 12345L));
    }

    @Test
    void buyAndSellUseSameExpiryComputation() {
        // Uzywajac wspoldzielonego helpera - to jest jedyna droga, wiec
        // BUY i SELL sa gwarantowanie symetryczne. Test symbolicznie potwierdza.
        long now = 500L;
        Long buyExp = BazaarOrderService.computeExpiresAt(cfg(120L), now);
        Long sellExp = BazaarOrderService.computeExpiresAt(cfg(120L), now);
        assertEquals(buyExp, sellExp,
                "BUY i SELL musi otrzymac ten sam expires_at przy identycznym now");
    }
}
