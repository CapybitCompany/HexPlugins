package hexnpc.shop;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Wylicza cenę całkowitą dla wybranej ilości przedmiotów, zachowując
 * semantykę cen z istniejących plików shops.yml.
 *
 * <p>Historycznie {@code buy-price}/{@code sell-price} to cena za
 * skonfigurowaną ilość {@code amount}. Stąd:
 *
 * <pre>
 *   unitPrice  = configuredPrice / configuredAmount
 *   totalPrice = unitPrice * selectedQuantity
 * </pre>
 *
 * <p>Implementacja celowo mnoży <b>przed</b> dzieleniem
 * ({@code configuredPrice * quantity / configuredAmount}) i zaokrągla dopiero
 * końcowy wynik. Dzięki temu:
 * <ul>
 *   <li>nie kumuluje się błąd zaokrąglenia ceny jednostkowej,</li>
 *   <li>gdy {@code quantity == configuredAmount}, wynik jest dokładnie równy
 *       {@code configuredPrice} (dla typowych cen walutowych) — czyli ceny
 *       istniejących sklepów się nie zmieniają.</li>
 * </ul>
 *
 * <p>Skala i tryb zaokrąglenia są dobrane pod HexEconomy, które normalizuje
 * salda przez {@code setScale(decimals, HALF_UP)} (domyślnie 2 miejsca).
 * Wszystkie obliczenia używają wyłącznie {@link BigDecimal} — nigdy double.
 */
public final class PriceCalculator {

    /** Domyślny tryb zaokrąglenia — spójny z normalizacją sald HexEconomy. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private PriceCalculator() {
    }

    /**
     * @param configuredPrice   cena skonfigurowana za {@code configuredAmount} sztuk
     * @param configuredAmount  bazowa ilość, do której odnosi się cena (>=1)
     * @param quantity          faktycznie wybrana ilość (>=0)
     * @param scale             liczba miejsc po przecinku wyniku (>=0)
     * @return cena całkowita, nigdy null; ZERO dla ceny/ilości zerowej
     */
    public static BigDecimal total(BigDecimal configuredPrice, int configuredAmount,
                                   long quantity, int scale) {
        if (configuredPrice == null || configuredPrice.signum() <= 0 || quantity <= 0) {
            return BigDecimal.ZERO.setScale(Math.max(0, scale), ROUNDING);
        }
        int base = Math.max(1, configuredAmount);
        int safeScale = Math.max(0, scale);
        BigDecimal gross = configuredPrice.multiply(BigDecimal.valueOf(quantity));
        return gross.divide(BigDecimal.valueOf(base), safeScale, ROUNDING);
    }
}
