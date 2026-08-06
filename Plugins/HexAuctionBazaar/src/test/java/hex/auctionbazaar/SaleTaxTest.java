package hex.auctionbazaar;

import hex.auctionbazaar.util.SaleTax;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Punkt #9 / uwaga o podatku: podatek liczony wyłącznie BigDecimalem, skala 2,
 * HALF_UP. Testy dla 0%, 10%, zaokrągleń i nieprawidłowych wartości.
 */
class SaleTaxTest {

    @Test
    void zeroPercentMeansNoTax() {
        SaleTax.Breakdown b = SaleTax.compute(new BigDecimal("100"), BigDecimal.ZERO);
        assertEquals(new BigDecimal("100.00"), b.gross());
        assertEquals(new BigDecimal("0.00"), b.tax());
        assertEquals(new BigDecimal("100.00"), b.net());
    }

    @Test
    void tenPercentOfHundred() {
        SaleTax.Breakdown b = SaleTax.compute(new BigDecimal("100"), new BigDecimal("10"));
        assertEquals(new BigDecimal("100.00"), b.gross());
        assertEquals(new BigDecimal("10.00"), b.tax());
        assertEquals(new BigDecimal("90.00"), b.net());
    }

    @Test
    void roundingHalfUp() {
        // 10% z 33.33 = 3.333 -> 3.33 ; netto 30.00
        SaleTax.Breakdown b = SaleTax.compute(new BigDecimal("33.33"), new BigDecimal("10"));
        assertEquals(new BigDecimal("3.33"), b.tax());
        assertEquals(new BigDecimal("30.00"), b.net());
    }

    @Test
    void roundingRoundsUpAtHalf() {
        // 10% z 0.05 = 0.005 -> HALF_UP -> 0.01 ; netto 0.04
        SaleTax.Breakdown b = SaleTax.compute(new BigDecimal("0.05"), new BigDecimal("10"));
        assertEquals(new BigDecimal("0.01"), b.tax());
        assertEquals(new BigDecimal("0.04"), b.net());
    }

    @Test
    void negativePercentClampedToZero() {
        SaleTax.Breakdown b = SaleTax.compute(new BigDecimal("100"), new BigDecimal("-5"));
        assertEquals(new BigDecimal("0.00"), b.tax());
        assertEquals(new BigDecimal("100.00"), b.net());
        assertEquals(BigDecimal.ZERO, b.percent());
    }

    @Test
    void percentAboveHundredClampedToHundred() {
        SaleTax.Breakdown b = SaleTax.compute(new BigDecimal("100"), new BigDecimal("150"));
        assertEquals(new BigDecimal("100.00"), b.tax());
        assertEquals(new BigDecimal("0.00"), b.net());
        assertEquals(new BigDecimal("100"), b.percent());
    }

    @Test
    void nullPercentTreatedAsZero() {
        SaleTax.Breakdown b = SaleTax.compute(new BigDecimal("50"), null);
        assertEquals(new BigDecimal("0.00"), b.tax());
        assertEquals(new BigDecimal("50.00"), b.net());
    }

    @Test
    void clampPercentBounds() {
        assertEquals(BigDecimal.ZERO, SaleTax.clampPercent(new BigDecimal("-1")));
        assertEquals(BigDecimal.ZERO, SaleTax.clampPercent(null));
        assertEquals(new BigDecimal("100"), SaleTax.clampPercent(new BigDecimal("101")));
        assertEquals(new BigDecimal("8"), SaleTax.clampPercent(new BigDecimal("8")));
    }
}
