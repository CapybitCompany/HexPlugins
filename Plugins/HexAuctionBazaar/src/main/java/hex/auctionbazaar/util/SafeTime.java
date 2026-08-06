package hex.auctionbazaar.util;

/**
 * Bezpieczne wyliczanie terminów (deadline) w milisekundach z ochroną przed przepełnieniem (punkt #8).
 *
 * <p>Terminy typu {@code now + seconds*1000} przy bardzo dużych wartościach mogłyby przepełnić
 * {@code long} i dać UJEMNY {@code expiresAt}/{@code reservedUntil} - co natychmiast „wygasiłoby"
 * świeżo utworzony wpis. Używamy {@link Math#multiplyExact}/{@link Math#addExact} i przy przepełnieniu
 * zwracamy {@link Long#MAX_VALUE} (termin praktycznie „nigdy"), NIGDY wartości ujemnej.</p>
 */
public final class SafeTime {

    private SafeTime() {
    }

    /** {@code now + seconds*1000} z ochroną przed przepełnieniem. Przepełnienie -> {@link Long#MAX_VALUE}. */
    public static long deadlineMillis(long now, long seconds) {
        if (seconds <= 0) {
            return now;
        }
        try {
            return Math.addExact(now, Math.multiplyExact(seconds, 1000L));
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;   // nigdy ujemny termin z powodu przepełnienia
        }
    }
}
