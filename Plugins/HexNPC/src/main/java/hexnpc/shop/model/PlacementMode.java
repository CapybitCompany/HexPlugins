package hexnpc.shop.model;

import java.util.Locale;

/**
 * Strategia rozmieszczania itemów w głównym widoku sklepu.
 *
 * <ul>
 *   <li><b>AUTO</b> — itemy są rozkładane po kolei (kolejność z shops.yml)
 *       na skonfigurowane {@code item-slots} i automatycznie dzielone na
 *       strony. Wartość {@code slot} pojedynczego itemu jest ignorowana.</li>
 *   <li><b>MANUAL</b> — itemy trafiają dokładnie na swoje {@code slot}
 *       (i opcjonalną {@code page}). Pełna kontrola administratora, tak jak
 *       w starszych wersjach pluginu.</li>
 * </ul>
 *
 * Uwaga o kompatybilności: gdy sklep nie deklaruje {@code placement}, a jego
 * itemy mają jawne {@code slot}, loader zakłada {@link #MANUAL}, żeby istniejące
 * pliki nie straciły znaczenia slotów. Sklepy bez slotów dostają {@link #AUTO}.
 */
public enum PlacementMode {
    AUTO,
    MANUAL;

    public static PlacementMode parse(String raw, PlacementMode fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return PlacementMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
