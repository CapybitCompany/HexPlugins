package hex.auctionbazaar.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Spójna normalizacja i walidacja kwot pieniężnych (skala 2, HALF_UP - skala Economy)
 * oraz ochrona przed przepełnieniem kolumn {@code DECIMAL(19,2)} w bazie.
 *
 * Wszystkie ceny/sumy przed GUI, Economy, zapisem do DB, audytem i zwrotem powinny być
 * znormalizowane tym samym sposobem, aby te same wartości były używane wszędzie.
 */
public final class Money {

    public static final int SCALE = 2;

    /**
     * Maksimum dla {@code DECIMAL(19,2)}: 19 cyfr znaczących, 2 po przecinku -> 17 cyfr całości.
     * Wartość ponad tę granicę nie zmieści się w kolumnie i destabilizowałaby DB/GUI.
     */
    public static final BigDecimal MAX = new BigDecimal("99999999999999999.99");

    private Money() {
    }

    /** Normalizacja do skali 2, HALF_UP. {@code null} pozostaje {@code null}. */
    public static BigDecimal normalize(BigDecimal v) {
        return v == null ? null : v.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** True, gdy znormalizowana wartość jest nieujemna i mieści się w {@code DECIMAL(19,2)}. */
    public static boolean fits(BigDecimal v) {
        if (v == null) {
            return false;
        }
        BigDecimal n = normalize(v);
        return n.signum() >= 0 && n.compareTo(MAX) <= 0;
    }

    /**
     * Znormalizowany iloczyn {@code price * amount}. Zwraca {@code null}, gdy dane są nieprawidłowe
     * albo wynik nie mieści się w {@code DECIMAL(19,2)} - wołający MUSI wtedy odrzucić operację
     * (np. INVALID_PRICE) PRZED jakąkolwiek abbuchung/insertem.
     */
    public static BigDecimal totalOrNull(BigDecimal price, long amount) {
        if (price == null || amount < 0 || price.signum() < 0) {
            return null;
        }
        BigDecimal total = normalize(price).multiply(BigDecimal.valueOf(amount));
        BigDecimal n = normalize(total);
        return fits(n) ? n : null;
    }
}
