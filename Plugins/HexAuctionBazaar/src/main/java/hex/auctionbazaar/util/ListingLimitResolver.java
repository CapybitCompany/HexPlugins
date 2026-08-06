package hex.auctionbazaar.util;

import hex.auctionbazaar.config.ListingLimitTier;

import java.util.List;
import java.util.function.Predicate;

/**
 * Rozwiązywanie limitu aktywnych aukcji gracza na podstawie progów permisji.
 *
 * Reguły (patrz punkt #8 zadania):
 *  - gracz otrzymuje NAJWYŻSZY limit spośród wszystkich pasujących progów,
 *  - kolejność progów w YAML nie wpływa na wynik,
 *  - brak dopasowania -> {@code defaultLimit},
 *  - progi z nieprawidłowymi wartościami powinny zostać odfiltrowane wcześniej
 *    (w ConfigLoader), tutaj zakładamy że lista jest już zwalidowana.
 *
 * Funkcja jest czysta (pure) - przyjmuje predykat uprawnień zamiast Playera,
 * dzięki czemu jest łatwo testowalna bez serwera.
 */
public final class ListingLimitResolver {

    private ListingLimitResolver() {
    }

    public static int resolve(Predicate<String> hasPermission,
                              int defaultLimit,
                              List<ListingLimitTier> tiers) {
        int best = defaultLimit;
        if (tiers != null && hasPermission != null) {
            for (ListingLimitTier tier : tiers) {
                if (tier == null || tier.permission() == null || tier.permission().isBlank()) {
                    continue;
                }
                if (hasPermission.test(tier.permission()) && tier.maxActiveListings() > best) {
                    best = tier.maxActiveListings();
                }
            }
        }
        return best;
    }
}
