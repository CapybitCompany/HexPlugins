package hexnpc.shop;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pokrywa semantykę cen: proporcjonalność, zaokrąglenie oraz zgodność
 * wstecz (ilość == amount daje dokładnie skonfigurowaną cenę).
 */
class PriceCalculatorTest {

    @Test
    void proportionalBuyPrices() {
        BigDecimal price = new BigDecimal("100.00");
        // amount = 64, cena za 64 = 100.
        assertEquals(new BigDecimal("100.00"), PriceCalculator.total(price, 64, 64, 2));
        assertEquals(new BigDecimal("200.00"), PriceCalculator.total(price, 64, 128, 2));
        // 100/64 = 1.5625 -> 1.56 (HALF_UP).
        assertEquals(new BigDecimal("1.56"), PriceCalculator.total(price, 64, 1, 2));
    }

    @Test
    void proportionalSellPrices() {
        BigDecimal price = new BigDecimal("25.00");
        assertEquals(new BigDecimal("25.00"), PriceCalculator.total(price, 64, 64, 2));
        assertEquals(new BigDecimal("12.50"), PriceCalculator.total(price, 64, 32, 2));
        assertEquals(new BigDecimal("50.00"), PriceCalculator.total(price, 64, 128, 2));
    }

    @Test
    void roundingHalfUp() {
        // 1 / 8 = 0.125 -> 0.13 (HALF_UP zaokrągla w górę na połówce).
        assertEquals(new BigDecimal("0.13"), PriceCalculator.total(new BigDecimal("1.00"), 8, 1, 2));
        // 10 / 3 = 3.3333... -> 3.33.
        assertEquals(new BigDecimal("3.33"), PriceCalculator.total(new BigDecimal("10"), 3, 1, 2));
        // 20 / 3 = 6.6666... -> 6.67.
        assertEquals(new BigDecimal("6.67"), PriceCalculator.total(new BigDecimal("10"), 3, 2, 2));
    }

    @Test
    void backwardCompatibleWhenQuantityEqualsAmount() {
        // Kluczowa gwarancja zgodności: ilość == amount => cena z konfiguracji.
        BigDecimal[] prices = {new BigDecimal("500.00"), new BigDecimal("100.00"), new BigDecimal("25.00")};
        int[] amounts = {1, 64, 16};
        for (BigDecimal p : prices) {
            for (int a : amounts) {
                BigDecimal total = PriceCalculator.total(p, a, a, 2);
                assertEquals(0, total.compareTo(p),
                        "przy ilości == amount cena musi być identyczna (" + p + " / " + a + ")");
            }
        }
    }

    @Test
    void zeroOrNegativeQuantityIsZero() {
        assertEquals(0, PriceCalculator.total(new BigDecimal("100"), 64, 0, 2).signum());
        assertEquals(0, PriceCalculator.total(new BigDecimal("100"), 64, -5, 2).signum());
    }

    @Test
    void usesOnlyBigDecimalScale() {
        BigDecimal total = PriceCalculator.total(new BigDecimal("100.00"), 64, 1, 2);
        assertEquals(2, total.scale(), "wynik musi mieć skonfigurowaną skalę");
        assertTrue(total.signum() > 0);
    }
}
