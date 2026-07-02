package hex.auctionbazaar.util;

import hex.auctionbazaar.config.MessagesConfig;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Tlumaczy techniczne kody powodu claim-u (np. "bazaar-buy-overflow-diamond",
 * "auction-sold-42") na przyjazny polski tekst z messages.yml (sekcja
 * "claim-reasons").
 *
 * Algorytm:
 *  1. Probujemy calego rawReason jako klucz.
 *  2. Jesli nie ma trafienia - obcinamy jeden segment z konca (po '-')
 *     i probujemy ponownie. Powtarzamy az znajdziemy klucz lub segmentow zabraknie.
 *  3. Fallback: klucz "default", potem sam raw reason.
 * Baza pozostaje z surowym powodem (do audytu); tlumaczymy tylko widoczny tekst.
 */
public final class ClaimReasonTranslator {

    private final Supplier<MessagesConfig> messages;

    public ClaimReasonTranslator(Supplier<MessagesConfig> messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    public String friendly(String rawReason) {
        if (rawReason == null || rawReason.isBlank()) {
            return defaultFriendly();
        }
        String candidate = rawReason;
        while (!candidate.isEmpty()) {
            String value = messages.get().get("claim-reasons." + candidate);
            if (value != null && !value.isEmpty()) return value;
            int lastDash = candidate.lastIndexOf('-');
            if (lastDash <= 0) break;
            candidate = candidate.substring(0, lastDash);
        }
        return defaultFriendly();
    }

    private String defaultFriendly() {
        String def = messages.get().get("claim-reasons.default");
        return def == null ? "Odbiór z rynku" : def;
    }

    /**
     * Publiczna wersja lookup-u dla testow: przekazujemy funkcje "resolve"
     * ktora oddaje mapping klucz-messages albo null gdy brak.
     */
    public static String friendlyFor(String rawReason, java.util.function.Function<String, String> lookup,
                                      String fallback) {
        if (rawReason == null || rawReason.isBlank()) return fallback;
        String candidate = rawReason;
        while (!candidate.isEmpty()) {
            String value = lookup.apply("claim-reasons." + candidate);
            if (value != null && !value.isEmpty()) return value;
            int lastDash = candidate.lastIndexOf('-');
            if (lastDash <= 0) break;
            candidate = candidate.substring(0, lastDash);
        }
        return fallback;
    }
}
