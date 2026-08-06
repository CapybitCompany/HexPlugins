package hex.auctionbazaar;

import hex.auctionbazaar.util.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Punkt #6: jednolita skala (2, HALF_UP) i ochrona przed przepełnieniem DECIMAL(19,2).
 */
class MoneyTest {

    @Test
    void normalizeToScaleTwoHalfUp() {
        assertEquals(new BigDecimal("1.24"), Money.normalize(new BigDecimal("1.235")));
        assertEquals(new BigDecimal("1.23"), Money.normalize(new BigDecimal("1.234")));
        assertEquals(new BigDecimal("5.00"), Money.normalize(new BigDecimal("5")));
        assertNull(Money.normalize(null));
    }

    @Test
    void fitsRespectsDecimal19_2Bounds() {
        assertTrue(Money.fits(new BigDecimal("0")));
        assertTrue(Money.fits(new BigDecimal("99999999999999999.99")), "dokładnie MAX");
        assertFalse(Money.fits(new BigDecimal("100000000000000000.00")), "ponad MAX");
        assertFalse(Money.fits(new BigDecimal("-1")), "ujemne nie mieści się");
        assertFalse(Money.fits(null));
    }

    @Test
    void totalOrNullRejectsOverflow() {
        // 10^15 sztuk * 1000 = 10^18 -> ponad DECIMAL(19,2) (max ~10^17).
        assertNull(Money.totalOrNull(new BigDecimal("1000"), 1_000_000_000_000_000L),
                "iloczyn ponad DECIMAL(19,2) musi dać null (odrzucenie PRZED abbuchung)");
    }

    @Test
    void totalOrNullComputesNormalizedProduct() {
        BigDecimal t = Money.totalOrNull(new BigDecimal("2.505"), 4);
        assertNotNull(t);
        // 2.505 -> 2.51 (HALF_UP) * 4 = 10.04
        assertEquals(new BigDecimal("10.04"), t);
    }

    @Test
    void totalOrNullRejectsNegativeAmountOrPrice() {
        assertNull(Money.totalOrNull(new BigDecimal("5"), -1));
        assertNull(Money.totalOrNull(new BigDecimal("-5"), 1));
        assertNull(Money.totalOrNull(null, 1));
    }
}
