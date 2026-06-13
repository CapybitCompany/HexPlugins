package hex.auctionbazaar;

import hex.auctionbazaar.bazaar.model.BazaarPrice;
import hex.auctionbazaar.bazaar.service.BazaarPricer;
import hex.auctionbazaar.config.BazaarConfig;
import hex.auctionbazaar.config.BazaarItemConfig;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BazaarPricerTest {

    private BazaarItemConfig item() {
        return new BazaarItemConfig(
                "diamond",
                Material.DIAMOND,
                "Diamond",
                "minerals",
                new BigDecimal("500"),
                new BigDecimal("50"),
                new BigDecimal("5000"),
                1000L,
                true,
                true
        );
    }

    private BazaarConfig.Pricing pricing(String spread, String maxStep) {
        return new BazaarConfig.Pricing(
                new BigDecimal("0.5"),
                new BigDecimal("1000"),
                new BigDecimal(spread),
                new BigDecimal(maxStep)
        );
    }

    @Test
    void midAtReferenceStockEqualsBasePrice() {
        BazaarPrice p = BazaarPricer.compute(item(), pricing("0", "0"), 1000L, null, null);
        // ratio = 1, pow(1, 0.5) = 1 -> mid = 500
        assertEquals(new BigDecimal("500.00"), p.mid());
    }

    @Test
    void midAtLowStockClampsToMax() {
        // stock=1 -> ratio=1000, sqrt=~31.62 -> mid=15810 -> clamp to maxPrice=5000
        BazaarPrice p = BazaarPricer.compute(item(), pricing("0", "0"), 1L, null, null);
        assertEquals(new BigDecimal("5000.00"), p.mid());
    }

    @Test
    void midAtHighStockClampsToMin() {
        // stock=10_000_000 -> ratio=0.0001, sqrt=0.01 -> mid=5 -> clamp to minPrice=50
        BazaarPrice p = BazaarPricer.compute(item(), pricing("0", "0"), 10_000_000L, null, null);
        assertEquals(new BigDecimal("50.00"), p.mid());
    }

    @Test
    void spreadProducesBuyAboveSell() {
        BazaarPrice p = BazaarPricer.compute(item(), pricing("5", "0"), 1000L, null, null);
        assertTrue(p.buyPrice().compareTo(p.sellPrice()) > 0,
                "buyPrice must exceed sellPrice when spread > 0");
        // mid = 500, spread = 5% (2.5% each side) -> buy = 512.50, sell = 487.50
        assertEquals(new BigDecimal("512.50"), p.buyPrice());
        assertEquals(new BigDecimal("487.50"), p.sellPrice());
    }

    @Test
    void smoothingCapsBuyPriceJump() {
        // Last buy was 100, max step 5% -> new buy is capped at 105.
        BazaarItemConfig customItem = new BazaarItemConfig(
                "x", Material.STONE, "x", "x",
                new BigDecimal("1000"),   // base
                new BigDecimal("1"),      // min
                new BigDecimal("100000"), // max
                100L, true, true);
        BazaarConfig.Pricing pr = new BazaarConfig.Pricing(
                new BigDecimal("0.5"),    // elasticity
                new BigDecimal("100"),    // reference -> ratio=1 -> mid=1000
                new BigDecimal("0"),      // no spread
                new BigDecimal("5"));     // 5% step
        BazaarPrice p = BazaarPricer.compute(customItem, pr, 100L,
                new BigDecimal("100"), new BigDecimal("100"));
        assertEquals(new BigDecimal("105.00"), p.buyPrice());
        assertEquals(new BigDecimal("105.00"), p.sellPrice());
    }

    @Test
    void sellNeverExceedsBuy() {
        // Pathological case: sell smoothing could push sell above buy.
        BazaarItemConfig customItem = new BazaarItemConfig(
                "x", Material.STONE, "x", "x",
                new BigDecimal("100"),
                new BigDecimal("1"),
                new BigDecimal("10000"),
                100L, true, true);
        BazaarConfig.Pricing pr = new BazaarConfig.Pricing(
                new BigDecimal("0.5"),
                new BigDecimal("100"),
                new BigDecimal("20"),
                new BigDecimal("100"));
        BazaarPrice p = BazaarPricer.compute(customItem, pr, 100L,
                new BigDecimal("50"), new BigDecimal("200"));
        assertTrue(p.sellPrice().compareTo(p.buyPrice()) <= 0,
                "sellPrice must not exceed buyPrice");
    }
}
