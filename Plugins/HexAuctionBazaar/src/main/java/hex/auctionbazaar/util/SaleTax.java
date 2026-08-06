package hex.auctionbazaar.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Obliczenia podatku od sprzedaży/wystawienia aukcji. Wyłącznie {@link BigDecimal}
 * ze spójną skalą i zaokrągleniem (HALF_UP, 2 miejsca - skala Economy).
 *
 * Semantyka (punkt #9 + uwaga o podatku):
 *  - brutto  = cena wystawienia (kupujący płaci pełną kwotę),
 *  - podatek = round(brutto * procent / 100),
 *  - netto   = brutto - podatek (realny zysk sprzedawcy).
 *
 * Podatek jest pobierany z góry przy wystawieniu aukcji, więc kupujący płaci
 * pełne brutto, a sprzedawca po opłaceniu podatku realizuje netto.
 */
public final class SaleTax {

    /** Skala pieniężna zgodna z kolumnami DECIMAL(19,2) i formatem Economy. */
    public static final int MONEY_SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private SaleTax() {
    }

    public record Breakdown(BigDecimal gross, BigDecimal tax, BigDecimal net, BigDecimal percent) {
    }

    /**
     * Waliduje procent podatku do zakresu 0..100. Wartości null/ujemne -> 0,
     * powyżej 100 -> 100.
     */
    public static BigDecimal clampPercent(BigDecimal percent) {
        if (percent == null || percent.signum() < 0) {
            return BigDecimal.ZERO;
        }
        if (percent.compareTo(HUNDRED) > 0) {
            return HUNDRED;
        }
        return percent;
    }

    public static Breakdown compute(BigDecimal gross, BigDecimal percent) {
        BigDecimal safeGross = gross == null ? BigDecimal.ZERO : gross;
        BigDecimal pct = clampPercent(percent);
        BigDecimal grossScaled = safeGross.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal tax = grossScaled
                .multiply(pct)
                .divide(HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal net = grossScaled.subtract(tax);
        if (net.signum() < 0) {
            net = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return new Breakdown(grossScaled, tax, net, pct);
    }
}
