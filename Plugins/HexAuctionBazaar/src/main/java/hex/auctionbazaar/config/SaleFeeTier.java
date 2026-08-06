package hex.auctionbazaar.config;

import java.math.BigDecimal;

/**
 * Pojedynczy próg podatku od wystawienia oparty o permisję. Podatek jest
 * pobierany przy wystawianiu aukcji. Gracz spełniający kilka progów płaci
 * NAJNIŻSZY pasujący podatek (najkorzystniejszy). Brak dopasowania -> wartość
 * domyślna {@code auction.sale-fee-percent}.
 *
 * @param name       nazwa progu z config.yml (tylko do logów/diagnozy)
 * @param permission permisja, którą musi mieć gracz
 * @param percent    procent podatku (0..100)
 */
public record SaleFeeTier(String name, String permission, BigDecimal percent) {
}
