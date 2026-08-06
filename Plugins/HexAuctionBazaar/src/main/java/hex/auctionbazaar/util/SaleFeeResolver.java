package hex.auctionbazaar.util;

import hex.auctionbazaar.config.SaleFeeTier;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Predicate;

/**
 * Rozwiązywanie procentu podatku od wystawienia aukcji na podstawie progów
 * permisji (LuckPerms rank).
 *
 * Reguły (patrz punkt #9 + uwaga o podatku):
 *  - gracz otrzymuje NAJNIŻSZY (najkorzystniejszy) podatek spośród wszystkich
 *    pasujących progów,
 *  - kolejność progów w YAML nie wpływa na wynik,
 *  - brak dopasowania -> {@code defaultPercent},
 *  - progi z nieprawidłowymi wartościami powinny zostać odfiltrowane wcześniej
 *    (w ConfigLoader).
 *
 * Funkcja jest czysta - przyjmuje predykat uprawnień zamiast Playera.
 */
public final class SaleFeeResolver {

    private SaleFeeResolver() {
    }

    public static BigDecimal resolve(Predicate<String> hasPermission,
                                     BigDecimal defaultPercent,
                                     List<SaleFeeTier> tiers) {
        BigDecimal best = defaultPercent == null ? BigDecimal.ZERO : defaultPercent;
        if (tiers != null && hasPermission != null) {
            for (SaleFeeTier tier : tiers) {
                if (tier == null || tier.permission() == null || tier.permission().isBlank()
                        || tier.percent() == null) {
                    continue;
                }
                if (hasPermission.test(tier.permission()) && tier.percent().compareTo(best) < 0) {
                    best = tier.percent();
                }
            }
        }
        return best;
    }
}
