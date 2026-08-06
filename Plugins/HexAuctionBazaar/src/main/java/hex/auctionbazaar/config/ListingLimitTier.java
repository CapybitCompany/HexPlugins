package hex.auctionbazaar.config;

/**
 * Pojedynczy próg limitu aukcji oparty o permisję (LuckPerms rozdaje takie
 * permisje przez grupy i dziedziczenie, więc nie zależymy od nazwy primary
 * group). Gracz spełniający kilka progów dostaje najwyższy limit.
 *
 * @param name              nazwa progu z config.yml (tylko do logów/diagnozy)
 * @param permission        permisja, którą musi mieć gracz
 * @param maxActiveListings maksymalna liczba aktywnych/zarezerwowanych aukcji
 */
public record ListingLimitTier(String name, String permission, int maxActiveListings) {
}
